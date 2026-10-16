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
        var upstream: Socket? = null
        try {
            client.tcpNoDelay = true
            val request = readHttpHead(client.getInputStream()) ?: return
            val headText = String(request, Charsets.ISO_8859_1)
            val headEnd = headText.indexOf("\r\n\r\n")
            if (headEnd < 0) return
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
            val rewritten = StringBuilder()
            rewritten.append(requestLine).append("\r\n")
            for ((key, value) in headers) {
                val lk = key.lowercase()
                val v = when (lk) {
                    "host" -> "$upstreamHost:$upstreamPort"
                    "origin" -> "http://$upstreamHost:$upstreamPort"
                    "referer" -> "http://$upstreamHost:$upstreamPort/"
                    // One request per connection: the browser learns from the
                    // rewritten Connection header and opens a fresh connection
                    // per resource. (Keep-alive pipelining is S04 work.)
                    "connection" -> if (isUpgrade) value else "close"
                    else -> value
                }
                rewritten.append(key).append(": ").append(v).append("\r\n")
            }
            rewritten.append("\r\n")
            out.write(rewritten.toString().toByteArray(Charsets.ISO_8859_1))
            val body = request.copyOfRange(headEnd + 4, request.size)
            if (body.isNotEmpty()) out.write(body)
            out.flush()

            if (isUpgrade) {
                // WebSocket: pipe both ways until either side closes.
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
            } else {
                // Plain HTTP: stream the response, then close (Connection: close
                // above makes the upstream close after the response body).
                pump(upstream.getInputStream(), client.getOutputStream())
                closeQuietly(upstream)
                closeQuietly(client)
            }
        } catch (e: IOException) {
            android.util.Log.w(TAG, "request failed", e)
            closeQuietly(client)
            closeQuietly(upstream)
        } finally {
            sockets.remove(client)
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
            if (idx > 0) out[lines[i].substring(0, idx)] = lines[i].substring(idx + 1).trim()
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
