package dev.gbcode.app.connection

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * On-device tests for the production Gateway: HTTP proxying, host/origin
 * rewrite, WebSocket upgrade piping. Run on the emulator via
 * connectedDebugAndroidTest; results are device evidence (emulator lane).
 */
@RunWith(AndroidJUnit4::class)
class GatewayTest {

    private var upstream: MiniUpstream? = null
    private var gateway: Gateway? = null

    /** Tiny upstream: /json returns a JSON body with the Host header; /ws echoes WS frames. */
    class MiniUpstream : AutoCloseable {
        private val server = ServerSocket()
        private val sockets = java.util.concurrent.ConcurrentHashMap.newKeySet<Socket>()
        @Volatile var lastHost: String? = null
        @Volatile var lastOrigin: String? = null
        val port: Int get() = server.localPort

        fun start(): Int {
            server.bind(InetSocketAddress("127.0.0.1", 0))
            Thread {
                while (!server.isClosed) {
                    try {
                        val c = server.accept()
                        sockets.add(c)
                        Thread { serve(c) }.apply { isDaemon = true }.start()
                    } catch (_: Exception) { break }
                }
            }.apply { isDaemon = true }.start()
            return server.localPort
        }

        private fun serve(client: Socket) {
            try {
                val input = client.getInputStream()
                val head = readHead(input)
                val headText = String(head, Charsets.ISO_8859_1)
                for (line in headText.split("\r\n").drop(1)) {
                    val i = line.indexOf(':')
                    if (i <= 0) continue
                    val k = line.substring(0, i).lowercase().trim()
                    val v = line.substring(i + 1).trim()
                    if (k == "host") lastHost = v
                    if (k == "origin") lastOrigin = v
                }
                val path = headText.substringBefore("\r\n").split(" ")[1]
                if (path == "/ws" && headText.lowercase().contains("upgrade: websocket")) {
                    val key = headText.split("\r\n").first { it.startsWith("Sec-WebSocket-Key:", true) }
                        .substringAfter(':').trim()
                    val accept = Base64.getEncoder().encodeToString(
                        MessageDigest.getInstance("SHA-1")
                            .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()))
                    val out = client.getOutputStream()
                    out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n" +
                            "Connection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n")
                        .toByteArray(Charsets.ISO_8859_1))
                    out.flush()
                    // echo one binary frame then close
                    val frame = readWsFrame(input) ?: return
                    out.write(frame) // server frames unmasked: re-wrap the client payload
                    out.flush()
                    return
                }
                val body = ("{\"ok\":true,\"host\":\"" + (lastHost ?: "") + "\"}").toByteArray()
                val out = client.getOutputStream()
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                        "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n")
                    .toByteArray(Charsets.ISO_8859_1))
                out.write(body)
                out.flush()
                client.close()
            } catch (_: Exception) {
                try { client.close() } catch (_: Exception) {}
            }
        }

        private fun readHead(input: InputStream): ByteArray {
            val buf = ByteArrayOutputStream()
            var prev3 = -1; var prev2 = -1; var prev1 = -1
            var b: Int
            while (input.read().also { b = it } != -1) {
                buf.write(b)
                if (prev3 == 13 && prev2 == 10 && prev1 == 13 && b == 10) break
                prev3 = prev2; prev2 = prev1; prev1 = b
            }
            return buf.toByteArray()
        }

        private fun readWsFrame(input: InputStream): ByteArray? {
            // minimal: read one masked client binary/text frame, return an
            // unmasked server frame with the same payload (echo).
            val b0 = input.read(); if (b0 < 0) return null
            val b1 = input.read(); if (b1 < 0) return null
            val opcode = b0 and 0x0f
            val masked = (b1 and 0x80) != 0
            var len = b1 and 0x7f
            if (len == 126) { len = (input.read() shl 8) or input.read() }
            val mask = if (masked) ByteArray(4).also { input.read(it) } else null
            val payload = ByteArray(len)
            var off = 0
            while (off < len) {
                val n = input.read(payload, off, len - off)
                if (n < 0) return null
                off += n
            }
            if (mask != null) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
            // echo unmasked
            val header = if (len < 126) byteArrayOf((0x80 or opcode).toByte(), len.toByte())
            else byteArrayOf((0x80 or opcode).toByte(), 126, (len shr 8).toByte(), (len and 0xff).toByte())
            return header + payload
        }

        override fun close() {
            for (s in sockets) try { s.close() } catch (_: Exception) {}
            try { server.close() } catch (_: Exception) {}
        }
    }

    @Before
    fun setUp() {
        upstream = MiniUpstream()
        val upPort = upstream!!.start()
        gateway = Gateway("127.0.0.1", upPort)
        gateway!!.start()
    }

    @After
    fun tearDown() {
        gateway?.close()
        upstream?.close()
    }

    @Test
    fun httpGetProxiedWithHostRewrite() {
        val url = URL("http://127.0.0.1:${gateway!!.port}/json")
        val body = url.readText()
        assertTrue(body.contains("\"ok\":true"))
        assertEquals("127.0.0.1:${upstream!!.port}", upstream!!.lastHost)
    }

    @Test
    fun originRewrittenToUpstream() {
        val conn = URL("http://127.0.0.1:${gateway!!.port}/json").openConnection() as java.net.HttpURLConnection
        conn.setRequestProperty("Origin", "http://127.0.0.1:${gateway!!.port}")
        conn.inputStream.readBytes()
        conn.disconnect()
        assertEquals("http://127.0.0.1:${upstream!!.port}", upstream!!.lastOrigin)
    }

    @Test
    fun webSocketEchoThroughGateway() {
        val key = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val keyB64 = Base64.getEncoder().encodeToString(key)
        val socket = Socket()
        socket.connect(InetSocketAddress("127.0.0.1", gateway!!.port), 5000)
        socket.soTimeout = 8000
        val out = socket.getOutputStream()
        out.write(("GET /ws HTTP/1.1\r\nHost: 127.0.0.1:${gateway!!.port}\r\nUpgrade: websocket\r\n" +
                "Connection: Upgrade\r\nSec-WebSocket-Key: $keyB64\r\nSec-WebSocket-Version: 13\r\n\r\n")
            .toByteArray(Charsets.ISO_8859_1))
        out.flush()

        val input = socket.getInputStream()
        val head = ByteArrayOutputStream()
        var prev3 = -1; var prev2 = -1; var prev1 = -1
        var b: Int
        while (input.read().also { b = it } != -1) {
            head.write(b)
            if (prev3 == 13 && prev2 == 10 && prev1 == 13 && b == 10) break
            prev3 = prev2; prev2 = prev1; prev1 = b
        }
        assertTrue(String(head.toByteArray()).contains("101"))

        // send a masked binary frame, expect the same payload echoed back
        val payload = ByteArray(128).also { SecureRandom().nextBytes(it) }
        val mask = ByteArray(4).also { SecureRandom().nextBytes(it) }
        val masked = payload.copyOf()
        for (i in masked.indices) masked[i] = (masked[i].toInt() xor mask[i % 4].toInt()).toByte()
        val header = byteArrayOf(0x82.toByte(), (0x80 or 126).toByte(), 0, 128.toByte())
        out.write(header + mask + masked)
        out.flush()

        // read echoed unmasked frame: 4-byte extended header + 128 payload
        val echo = ByteArray(132)
        var off = 0
        while (off < echo.size) {
            val n = input.read(echo, off, echo.size - off)
            if (n < 0) break
            off += n
        }
        assertEquals(0x82.toByte(), echo[0])
        assertEquals(126.toByte(), echo[1])
        val echoedPayload = echo.copyOfRange(4, 4 + 128)
        assertTrue(MessageDigest.isEqual(payload, echoedPayload))
        socket.close()
    }

    @Test
    fun upstreamDownReturns502OrClientError() {
        upstream?.close()
        val latch = CountDownLatch(1)
        var outcome: Int = -1
        Thread {
            try {
                val conn = URL("http://127.0.0.1:${gateway!!.port}/json").openConnection() as java.net.HttpURLConnection
                outcome = conn.responseCode
                conn.disconnect()
            } catch (e: Exception) {
                outcome = -2 // connection-level failure is also acceptable evidence
            }
            latch.countDown()
        }.start()
        assertTrue("request must terminate", latch.await(12, TimeUnit.SECONDS))
        assertTrue("expected 502 or connection failure, got $outcome", outcome == 502 || outcome == -2)
    }

    @Test
    fun keepAliveReusesConnection() {
        // Upstream keeps connections alive and counts responses.
        val responses = java.util.concurrent.atomic.AtomicInteger(0)
        val server = ServerSocket()
        server.bind(InetSocketAddress("127.0.0.1", 0))
        val serverThread = Thread {
            while (!server.isClosed) {
                try {
                    val c = server.accept()
                    Thread {
                        try {
                            val input = c.getInputStream()
                            val out = c.getOutputStream()
                            while (true) {
                                val head = ByteArrayOutputStream()
                                var p3 = -1; var p2 = -1; var p1 = -1; var b: Int
                                while (input.read().also { b = it } != -1) {
                                    head.write(b)
                                    if (p3 == 13 && p2 == 10 && p1 == 13 && b == 10) break
                                    p3 = p2; p2 = p1; p1 = b
                                }
                                if (head.size() == 0) break
                                val body = "{\"n\":${responses.incrementAndGet()}}".toByteArray()
                                out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                                        "Content-Length: ${body.size}\r\nConnection: keep-alive\r\n\r\n")
                                    .toByteArray(Charsets.ISO_8859_1))
                                out.write(body)
                                out.flush()
                            }
                        } catch (_: Exception) {
                        }
                    }.apply { isDaemon = true }.start()
                } catch (_: Exception) { break }
            }
        }
        serverThread.isDaemon = true
        serverThread.start()

        val gw = Gateway("127.0.0.1", server.localPort)
        gw.start()
        try {
            // Two sequential requests on ONE raw client socket: proves the
            // gateway's keep-alive loop answers both on the same connection.
            val client = Socket()
            client.connect(InetSocketAddress("127.0.0.1", gw.port), 5000)
            client.soTimeout = 8000
            val out = client.getOutputStream()
            val input = client.getInputStream()
            fun exchange(): String {
                out.write(("GET /json HTTP/1.1\r\nHost: 127.0.0.1:${gw.port}\r\n\r\n")
                    .toByteArray(Charsets.ISO_8859_1))
                out.flush()
                val head = ByteArrayOutputStream()
                var p3 = -1; var p2 = -1; var p1 = -1; var b: Int
                while (input.read().also { b = it } != -1) {
                    head.write(b)
                    if (p3 == 13 && p2 == 10 && p1 == 13 && b == 10) break
                    p3 = p2; p2 = p1; p1 = b
                }
                val headText = String(head.toByteArray(), Charsets.ISO_8859_1)
                val len = headText.split("\r\n")
                    .firstOrNull { it.startsWith("Content-Length:", true) }
                    ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
                val body = ByteArray(len)
                var off = 0
                while (off < len) {
                    val r = input.read(body, off, len - off)
                    if (r < 0) break
                    off += r
                }
                return String(body)
            }
            val first = exchange()
            val second = exchange()
            assertTrue("first: $first", first.contains("\"n\":1"))
            assertTrue("second: $second", second.contains("\"n\":2"))
            client.close()
        } finally {
            gw.close()
            try { server.close() } catch (_: Exception) {}
        }
    }
}
