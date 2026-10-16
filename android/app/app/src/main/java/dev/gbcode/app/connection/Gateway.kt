package dev.gbcode.app.connection

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
 * the WebView talks to 127.0.0.1:<gatewayPort>; the gateway forwards to the
 * single approved backend upstream. This keeps the backend credential out of
 * page JavaScript and stabilizes the browser origin.
 *
 * Scope: HTTP (streamed, no buffering) and WebSocket upgrade piping.
 * Never a general proxy: exactly one fixed upstream per instance.
 */
class Gateway(private val upstreamHost: String, private val upstreamPort: Int) : AutoCloseable {

    private val executor = Executors.newCachedThreadPool { r ->
        Thread(r, "gbcode-gateway").apply { isDaemon = true }
    }
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private var server: ServerSocket? = null
    @Volatile var port: Int = -1
        private set

    fun start(): Int {
        val srv = ServerSocket()
        srv.bind(InetSocketAddress("127.0.0.1", 0))
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
            android.util.Log.i(TAG, "request: $requestLine")

            upstream = Socket()
            upstream.tcpNoDelay = true
            upstream.connect(InetSocketAddress(upstreamHost, upstreamPort), 10_000)
            val out = upstream.getOutputStream()

            val isUpgrade = headers["connection"]?.contains("upgrade", true) == true &&
                    headers.containsKey("sec-websocket-key")
            val clientWantsClose = headers["connection"]?.contains("close", true) == true
            val rewritten = StringBuilder()
            rewritten.append(requestLine).append("\r\n")
            for ((key, value) in headers) {
                val lk = key.lowercase()
                val v = when (lk) {
                    "host" -> "$upstreamHost:$upstreamPort"
                    "origin" -> "http://$upstreamHost:$upstreamPort"
                    "referer" -> "http://$upstreamHost:$upstreamPort/"
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
    }
}
