package dev.gbcode.app.connection

import dev.gbcode.app.web.WebAssets
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * App-local loopback origin gateway (production form of guest/gateway):
 * the WebView talks to 127.0.0.1:<gatewayPort>; the gateway serves the
 * app-owned core workbench assets (ADR-0006 class A) and forwards the scoped
 * IDE channels to the single approved backend upstream. This keeps the backend
 * credential out of page JavaScript and stabilizes the browser origin.
 *
 * Scope: HTTP (streamed, no buffering) and WebSocket upgrade piping.
 * Never a general proxy: exactly one fixed upstream per instance.
 */
class Gateway @JvmOverloads constructor(
    private val upstreamHost: String?,
    private val upstreamPort: Int,
    private val assets: WebAssets? = null,
    private val indexRenderer: ((nonce: String) -> String)? = null,
) : AutoCloseable {

    private val executor = Executors.newCachedThreadPool { r ->
        Thread(r, "gbcode-gateway").apply { isDaemon = true }
    }
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private var server: ServerSocket? = null
    @Volatile var port: Int = -1
        private set

    /** Requests denied because they targeted guest-served core workbench assets (ADR-0006). */
    @Volatile var denyCoreCount: Int = 0
        private set

    fun start(preferredPort: Int = 0): Int {
        require(upstreamHost == null || isLoopbackHost(upstreamHost)) {
            "APK gateway forwards to loopback backends only (got $upstreamHost)"
        }
        val srv = ServerSocket()
        var bound = false
        if (preferredPort in 1..65535) {
            try {
                srv.bind(InetSocketAddress("127.0.0.1", preferredPort))
                bound = true
            } catch (e: IOException) {
                // Another process owns the preferred port. Never route credentials
                // through it; fall back to a fresh verified origin and say so.
                android.util.Log.w(TAG, "preferred port $preferredPort unavailable (${e.message}); using ephemeral port")
            }
        }
        if (!bound) srv.bind(InetSocketAddress("127.0.0.1", 0))
        server = srv
        port = srv.localPort
        android.util.Log.i(TAG, "listening on 127.0.0.1:${srv.localPort} -> $upstreamHost:$upstreamPort")
        executor.execute {
            while (!srv.isClosed) {
                try {
                    val client = srv.accept()
                    sockets.add(client)
                    executor.execute { handle(client) }
                } catch (e: IOException) {
                    if (!srv.isClosed) android.util.Log.w(TAG, "accept failed", e)
                }
            }
        }
        return port
    }

    private fun handle(client: Socket) {
        try {
            client.tcpNoDelay = true
            client.soTimeout = 120_000
            // HTTP/1.1 keep-alive: sequential requests per connection until the
            // client closes, an upgrade takes over, or the idle timeout fires.
            while (!client.isClosed) {
                val request = try {
                    readHttpHead(client.getInputStream())
                } catch (e: java.net.SocketTimeoutException) {
                    return
                } ?: return
                if (!handleOne(client, request)) return
            }
        } catch (e: IOException) {
            android.util.Log.w(TAG, "request failed", e)
        } finally {
            closeQuietly(client)
            sockets.remove(client)
        }
    }

    /** Serves one request; returns false when the client connection must close. */
    private fun handleOne(client: Socket, request: ByteArray): Boolean {
        var upstream: Socket? = null
        try {
            val headText = String(request, Charsets.ISO_8859_1)
            val headEnd = headText.indexOf("\r\n\r\n")
            if (headEnd < 0) return false
            val head = headText.substring(0, headEnd + 4)
            val requestLine = head.substringBefore("\r\n")
            val headers = parseHeaders(head)

            // Gateway authorization (S02.05): this proxy exists only for our own
            // WebView origin. A loopback port is not authentication — validate
            // method, target, Host and Origin before any upstream contact, and
            // never forge an origin for a request that did not present ours.
            val parts = requestLine.split(" ")
            val method = parts.getOrNull(0) ?: ""
            val target = parts.getOrNull(1) ?: ""
            if (parts.size < 3 || parts[2] !in HTTP_VERSIONS ||
                method !in ALLOWED_METHODS || !target.startsWith("/")) {
                return reject(client, 400, "Bad Request")
            }
            val host = headers["host"]?.lowercase()
                ?: return reject(client, 400, "Bad Request")
            if (host != "127.0.0.1:$port" && host != "localhost:$port") {
                return reject(client, 421, "Misdirected Request")
            }
            val gatewayOrigin = "http://127.0.0.1:$port"
            val gatewayOriginAlt = "http://localhost:$port"
            val origin = headers["origin"]
            if (origin != null && origin != gatewayOrigin && origin != gatewayOriginAlt) {
                return reject(client, 403, "Forbidden")
            }

            // Log method and path only: a query may carry tokens (contract §5
            // forbids credentials in logs).
            val path = target.substringBefore('?')
            android.util.Log.i(TAG, "request: $method $path")

            val isUpgrade = headers["connection"]?.contains("upgrade", true) == true &&
                    headers.containsKey("sec-websocket-key")

            // ADR-0006: class A core workbench assets are app-owned. Serve them
            // locally (never falling back to the guest) and deny the guest's core
            // asset routes with a trace, so the main workbench cannot silently
            // fetch core HTML/JS/CSS/workers from the VM.
            if (!isUpgrade && assets != null) {
                if (path == "/" || path == "/index.html") {
                    return serveIndex(client, method)
                }
                if (isClassAAssetPath(path)) {
                    return serveAsset(client, method, path)
                }
                if (isGuestCoreAssetPath(path)) {
                    denyCoreCount++
                    android.util.Log.w(TAG, "deny-core: $method $path")
                    return reject(client, 403, "Forbidden")
                }
                // Packaged dynamic resources (e.g. built-in web extensions)
                // are served locally first; anything else may proxy below.
                if (assets.open(path) != null) {
                    return serveAsset(client, method, path)
                }
            }
            val upstreamHost = this.upstreamHost
                ?: return reject(client, 404, "Not Found")

            upstream = Socket()
            upstream.tcpNoDelay = true
            try {
                upstream.connect(InetSocketAddress(upstreamHost, upstreamPort), 10_000)
            } catch (e: IOException) {
                // Answer instead of closing: an empty response makes the
                // WebView show its own error page instead of the app's
                // reconnect surface.
                closeQuietly(upstream)
                return reject(client, 502, "Backend unavailable")
            }
            val out = upstream.getOutputStream()

            val clientWantsClose = headers["connection"]?.contains("close", true) == true
            val upstreamOrigin = "http://$upstreamHost:$upstreamPort"
            val rewritten = StringBuilder()
            rewritten.append(requestLine).append("\r\n")
            for ((key, value) in headers) {
                val lk = key.lowercase()
                val v = when (lk) {
                    "host" -> "$upstreamHost:$upstreamPort"
                    // Same-origin only (enforced above): map our origin to the
                    // upstream's. Absent origins stay absent.
                    "origin" -> upstreamOrigin
                    "referer" -> when {
                        value.startsWith(gatewayOrigin) -> upstreamOrigin + value.substring(gatewayOrigin.length)
                        value.startsWith(gatewayOriginAlt) -> upstreamOrigin + value.substring(gatewayOriginAlt.length)
                        else -> value
                    }
                    else -> value
                }
                rewritten.append(key).append(": ").append(v).append("\r\n")
            }
            rewritten.append("\r\n")
            out.write(rewritten.toString().toByteArray(Charsets.ISO_8859_1))
            val body = request.copyOfRange(headEnd + 4, request.size)
            if (body.isNotEmpty()) out.write(body)
            // Forward the request body (POST/PUT): readHttpHead stops at the
            // header boundary, so remaining body bytes are still in the stream.
            val reqContentLength = headers.entries
                .firstOrNull { it.key.equals("content-length", true) }
                ?.value?.trim()?.toLongOrNull() ?: 0L
            var bodyRemaining = reqContentLength - body.size
            val clientIn = client.getInputStream()
            val bodyBuf = ByteArray(64 * 1024)
            while (bodyRemaining > 0) {
                val n = clientIn.read(bodyBuf, 0, minOf(bodyBuf.size.toLong(), bodyRemaining).toInt())
                if (n < 0) break
                out.write(bodyBuf, 0, n)
                bodyRemaining -= n
            }
            out.flush()
            if (isUpgrade) {
                // WebSocket: pipe both ways until either side closes; this
                // connection is consumed and never reused for HTTP.
                val t = Thread {
                    try {
                        pump(upstream.getInputStream(), client.getOutputStream())
                    } catch (_: IOException) {
                    } finally {
                        closeQuietly(client)
                        closeQuietly(upstream)
                    }
                }
                t.isDaemon = true
                t.start()
                try {
                    pump(client.getInputStream(), upstream.getOutputStream())
                } catch (_: IOException) {
                } finally {
                    closeQuietly(client)
                    closeQuietly(upstream)
                }
                return false
            }

            // Plain HTTP: stream exactly content-length or until close.
            val complete = pumpResponse(upstream.getInputStream(), client.getOutputStream())
            closeQuietly(upstream)
            return complete && !clientWantsClose
        } catch (e: IOException) {
            android.util.Log.w(TAG, "request failed", e)
            closeQuietly(client)
            closeQuietly(upstream)
            return false
        }
    }

    private fun pump(input: InputStream, output: OutputStream) {
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            output.write(buf, 0, n)
            output.flush()
        }
    }

    /**
     * Streams one upstream response to the client with correct framing.
     * Returns true only when the response completed cleanly AND the upstream
     * kept its side open (safe to read another request from the client).
     */
    private fun pumpResponse(upstreamIn: InputStream, clientOut: OutputStream): Boolean {
        val head = readHttpHead(upstreamIn) ?: return false
        clientOut.write(head)
        val headText = String(head, Charsets.ISO_8859_1)
        val statusLine = headText.substringBefore("\r\n")
        val parts = statusLine.split(" ")
        val code = parts.getOrNull(1)?.toIntOrNull() ?: return false
        val headers = parseHeaders(headText)

        if (code in 100..199 || code == 204 || code == 304) return true
        val contentLength = headers.entries.firstOrNull { it.key.equals("content-length", true) }
            ?.value?.trim()?.toLongOrNull()
        if (contentLength != null) {
            var remaining = contentLength
            val buf = ByteArray(64 * 1024)
            while (remaining > 0) {
                val n = upstreamIn.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                if (n < 0) return false
                clientOut.write(buf, 0, n)
                remaining -= n
            }
            clientOut.flush()
            // Upstream keep-alive survives; the connection is reusable.
            return !headers.entries.any { it.key.equals("connection", true) && it.value.contains("close", true) }
        }
        val chunked = headers.entries.any {
            it.key.equals("transfer-encoding", true) && it.value.contains("chunked", true)
        }
        if (chunked) {
            pump(upstreamIn, clientOut) // read to close; do not reuse
            return false
        }
        // Close-delimited body.
        pump(upstreamIn, clientOut)
        return false
    }

    private fun readHttpHead(input: InputStream): ByteArray? {
        val buf = java.io.ByteArrayOutputStream()
        val window = IntArray(4) { -1 }
        var b: Int
        while (input.read().also { b = it } != -1) {
            buf.write(b)
            window[0] = window[1]; window[1] = window[2]; window[2] = window[3]; window[3] = b
            if (window.contentEquals(intArrayOf('\r'.code, '\n'.code, '\r'.code, '\n'.code))) break
            if (buf.size() > 64 * 1024) return null
        }
        if (buf.size() == 0) return null
        return buf.toByteArray()
    }

    private fun parseHeaders(head: String): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        val lines = head.split("\r\n")
        for (i in 1 until lines.size) {
            val idx = lines[i].indexOf(':')
            if (idx > 0) out[lines[i].substring(0, idx).lowercase()] = lines[i].substring(idx + 1).trim()
        }
        return out
    }

    private fun closeQuietly(socket: Socket?) {
        try {
            socket?.close()
        } catch (_: IOException) {
        }
    }

    /** Reject an unauthorized or malformed request before any upstream contact. */
    private fun reject(client: Socket, code: Int, reason: String): Boolean {
        try {
            val body = reason.toByteArray(Charsets.UTF_8)
            val head = "HTTP/1.1 $code $reason\r\nConnection: close\r\n" +
                    "Content-Type: text/plain\r\nContent-Length: ${body.size}\r\n\r\n"
            val out = client.getOutputStream()
            out.write(head.toByteArray(Charsets.ISO_8859_1))
            out.write(body)
            out.flush()
        } catch (_: IOException) {
        }
        closeQuietly(client)
        return false
    }

    /** Class A (app-owned core) request paths: local-only, never proxied. */
    private fun isClassAAssetPath(path: String): Boolean {
        return path == "/favicon.ico" || path == "/manifest.json" || path == "/package.json" ||
                path == "/code-192.png" || path == "/code-512.png" ||
                path == "/callback" || path == "/callback.html" ||
                path.startsWith("/out/") || path.startsWith("/node_modules/") ||
                path.startsWith("/resources/server/")
    }

    /**
     * Guest-side core workbench asset shapes: denied whenever core assets are
     * app-owned, so the main workbench cannot load its core from the VM.
     */
    private fun isGuestCoreAssetPath(path: String): Boolean {
        return path.contains("/static/out/") || path.contains("/static/node_modules/") ||
                path.contains("/static/resources/") || path.endsWith("/workbench.html") ||
                path.endsWith("/workbench.js") || path.endsWith("/workbench.css")
    }

    /** Serve the rendered workbench entry page (upstream template). */
    private fun serveIndex(client: Socket, method: String): Boolean {
        val nonce = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(ByteArray(16).also { java.security.SecureRandom().nextBytes(it) })
        val html = indexRenderer?.invoke(nonce) ?: return reject(client, 404, "Not Found")
        return writeLocal(client, method, "text/html; charset=utf-8", indexCsp(nonce)) { out ->
            out.write(html.toByteArray(Charsets.UTF_8))
        }
    }

    /** CSP for the app-owned workbench page (upstream-shaped, loopback-scoped). */
    private fun indexCsp(nonce: String): String {
        val ws = "ws://127.0.0.1:$port ws://localhost:$port"
        return "default-src 'none'; " +
                "script-src 'self' 'unsafe-eval' blob: 'nonce-$nonce'; " +
                "style-src 'self' 'unsafe-inline'; " +
                "img-src 'self' data: blob:; " +
                "font-src 'self' data:; " +
                "media-src 'self' data: blob:; " +
                "connect-src 'self' $ws blob: data:; " +
                "worker-src 'self' blob:; " +
                "frame-src 'self' blob: data:; " +
                "child-src 'self' blob:; " +
                "manifest-src 'self'; " +
                "base-uri 'none'; form-action 'none'"
    }

    /** Serve a packaged core asset from the APK; missing assets are 404, never proxied. */
    private fun serveAsset(client: Socket, method: String, path: String): Boolean {
        val asset = assets?.open(path) ?: return reject(client, 404, "Not Found")
        return writeLocal(client, method, asset.contentType, null) { out ->
            asset.open().use { it.copyTo(out) }
        }
    }

    /**
     * Write a local response. Bodies are streamed and the connection closes:
     * packaged assets can be tens of megabytes and are never buffered whole.
     */
    private fun writeLocal(
        client: Socket,
        method: String,
        contentType: String,
        csp: String?,
        writeBody: (java.io.OutputStream) -> Unit,
    ): Boolean {
        try {
            val out = client.getOutputStream()
            val head = StringBuilder("HTTP/1.1 200 OK\r\nContent-Type: $contentType\r\n")
            if (csp != null) head.append("Content-Security-Policy: ").append(csp).append("\r\n")
            head.append("Cache-Control: no-cache\r\nConnection: close\r\n\r\n")
            out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
            if (method != "HEAD") writeBody(out)
            out.flush()
        } catch (_: IOException) {
        }
        closeQuietly(client)
        return false
    }

    override fun close() {
        try {
            server?.close()
        } catch (_: IOException) {
        }
        for (socket in sockets) closeQuietly(socket)
        executor.shutdownNow()
    }

    companion object {
        private const val TAG = "GBCodeGateway"
        private val ALLOWED_METHODS = setOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
        private val HTTP_VERSIONS = setOf("HTTP/1.0", "HTTP/1.1")

        /**
         * The APK gateway forwards to loopback backends only: a remote backend
         * would send credentials over plaintext sockets (S02.03). Remote/TLS
         * backends need their own reviewed design, not a silent downgrade.
         */
        @JvmStatic
        fun isLoopbackHost(host: String): Boolean {
            val h = host.lowercase().removePrefix("[").removeSuffix("]")
            return h == "localhost" || h == "::1" || h == "0:0:0:0:0:0:0:1" ||
                    Regex("""^127\.\d{1,3}\.\d{1,3}\.\d{1,3}$""").matches(h)
        }
    }
}
