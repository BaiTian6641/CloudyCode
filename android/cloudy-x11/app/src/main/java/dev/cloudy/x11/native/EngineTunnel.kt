package dev.cloudy.x11.native

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import org.json.JSONObject
import java.io.BufferedInputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Minimal native client for the C2.5 14500 raw bridge. It reads exactly one
 * bridge config, then adapts O/D/C frames to socketpairs consumed by
 * lorieServerAcceptClient. This is C3.1 transport/engine plumbing; no renderer,
 * Android Surface or per-window host is present yet.
 */
class EngineTunnel private constructor(
    private val socket: Socket,
    private val input: DataInputStream,
    private val output: DataOutputStream,
    val display: String,
    val cookie: String,
    private val maxFrame: Int,
    private val maxStreams: Int,
) {
    private val writeLock = Any()
    private data class Stream(
        val fd: ParcelFileDescriptor,
        val out: FileOutputStream,
    )

    private val streams = ConcurrentHashMap<Int, Stream>()
    private val active = AtomicBoolean(true)

    private fun sendData(sid: Int, payload: ByteArray, offset: Int, length: Int) {
        synchronized(writeLock) {
            output.writeByte('D'.code)
            output.writeInt(sid)
            output.writeInt(length)
            output.write(payload, offset, length)
            output.flush()
        }
    }

    private fun sendClose(sid: Int) {
        try {
            synchronized(writeLock) {
                if (!active.get()) return
                output.writeByte('C'.code)
                output.writeInt(sid)
                output.flush()
            }
        } catch (_: Exception) {
            // Tunnel already gone during normal teardown; never let a daemon
            // stream reader's uncaught exception kill the :x11 process.
        }
    }

    private fun safeClose(sid: Int) {
        val stream = streams.remove(sid) ?: return
        try {
            stream.out.close()
        } catch (_: Exception) {
        }
        try {
            stream.fd.close()
        } catch (_: Exception) {
        }
    }

    private fun streamReader(sid: Int, stream: Stream) {
        val buffer = ByteArray(65536)
        try {
            val input = FileInputStream(stream.fd.fileDescriptor)
            try {
                while (active.get()) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (n > 0) sendData(sid, buffer, 0, n)
                }
            } finally {
                try {
                    input.close()
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        } finally {
            if (streams.remove(sid) != null) {
                try {
                    stream.out.close()
                } catch (_: Exception) {
                }
                try {
                    stream.fd.close()
                } catch (_: Exception) {
                }
            }
            sendClose(sid)
        }
    }

    fun startReader(onEnd: () -> Unit) {
        Thread({
            try {
                while (active.get()) {
                    val type = input.readUnsignedByte().toChar()
                    val sid = input.readInt()
                    when (type) {
                        'O' -> {
                            if (streams.size >= maxStreams) {
                                // Over the negotiated stream limit: leave the
                                // stream closed; the VM side may retry.
                                sendClose(sid)
                                continue
                            }
                            val rawA = FileDescriptor()
                            val rawB = FileDescriptor()
                            Os.socketpair(OsConstants.AF_UNIX,
                                OsConstants.SOCK_STREAM, 0, rawA, rawB)
                            val pfdA = ParcelFileDescriptor.dup(rawA)
                            val pfdB = ParcelFileDescriptor.dup(rawB)
                            Os.close(rawA)
                            Os.close(rawB)
                            val stream = Stream(pfdA,
                                FileOutputStream(pfdA.fileDescriptor))
                            val engineFd = pfdB.detachFd()
                            streams[sid] = stream
                            X11Native.acceptFd(engineFd)
                            Thread({ streamReader(sid, stream) },
                                "cloudy-x11-stream-$sid").apply {
                                isDaemon = true
                                start()
                            }
                        }
                        'D' -> {
                            val length = input.readInt()
                            if (length < 0 || length > maxFrame) {
                                throw IllegalStateException("frame $length exceeds limit")
                            }
                            val payload = ByteArray(length)
                            input.readFully(payload)
                            val stream = streams[sid]
                            if (stream != null) {
                                try {
                                    stream.out.write(payload)
                                    stream.out.flush()
                                } catch (_: Exception) {
                                    safeClose(sid)
                                }
                            }
                        }
                        'C' -> safeClose(sid)
                        else -> throw IllegalStateException("bad frame type")
                    }
                }
            } catch (_: Exception) {
            } finally {
                active.set(false)
                streams.keys.toList().forEach { safeClose(it) }
                try {
                    socket.close()
                } catch (_: Exception) {
                }
                onEnd()
            }
        }, "cloudy-x11-tunnel").apply {
            isDaemon = true
            start()
        }
    }

    fun close() {
        active.set(false)
        try {
            socket.close()
        } catch (_: Exception) {
        }
    }

    companion object {
        fun connect(token: String, port: Int = 14500): EngineTunnel {
            verifyProxy(token, port)
            val socket = Socket()
            try {
            socket.connect(InetSocketAddress("127.0.0.1", port), 3000)
            socket.soTimeout = 5000
            socket.tcpNoDelay = true
            val request = "GET /x11-tunnel HTTP/1.1\r\n" +
                "Host: 127.0.0.1:$port\r\n" +
                "Cookie: cloudy_x11=$token\r\n" +
                "Upgrade: cloudy-x11\r\n" +
                "Connection: Upgrade\r\n\r\n"
            socket.getOutputStream().write(request.toByteArray(Charsets.ISO_8859_1))
            socket.getOutputStream().flush()
            val rawInput = BufferedInputStream(socket.getInputStream())
            val headers = readHeaders(rawInput)
            if (!headers.startsWith("HTTP/1.1 101")) {
                throw IllegalStateException("tunnel upgrade failed: ${headers.lineSequence().first()}")
            }
            val configLine = readLine(rawInput)
            if (!configLine.startsWith("CLOUDY-X11-CONFIG 1 ")) {
                throw IllegalStateException("missing tunnel configuration")
            }
            val json = try {
                JSONObject(configLine.substring("CLOUDY-X11-CONFIG 1 ".length))
            } catch (e: org.json.JSONException) {
                // JSONTokener includes the raw line in its message, which may
                // contain the display cookie. Never propagate/log that text.
                throw IllegalStateException("invalid tunnel configuration")
            }
            val display = json.optString("display", ":99")
            val cookie = json.optString("cookie", "")
            if (cookie.length !in 32..128) {
                throw IllegalStateException("invalid display credential")
            }
            val maxFrame = json.optInt("max_frame", 1048576)
                .coerceIn(1024, 4 * 1024 * 1024)
            val maxStreams = json.optInt("max_streams", 64).coerceIn(1, 256)
            socket.soTimeout = 0
            val input = DataInputStream(rawInput)
            val output = DataOutputStream(socket.getOutputStream())
            return EngineTunnel(socket, input, output, display, cookie,
                maxFrame, maxStreams)
            } catch (e: Throwable) {
                try {
                    socket.close()
                } catch (_: Exception) {
                }
                throw e
            }
        }

        /**
         * The tunnel request carries the per-install token. Prove the listener
         * knows that token via the cookie-free HMAC challenge before sending
         * it, so a squatter on a temporarily unforwarded port cannot capture
         * the token or forge a display credential.
         */
        private fun verifyProxy(token: String, port: Int) {
            val nonce = ByteArray(16).also { SecureRandom().nextBytes(it) }
                .joinToString("") { "%02x".format(it) }
            val socket = Socket()
            socket.connect(InetSocketAddress("127.0.0.1", port), 3000)
            socket.soTimeout = 3000
            try {
                val request = "GET /__cloudy/ping?nonce=$nonce HTTP/1.1\r\n" +
                    "Host: 127.0.0.1:$port\r\n" +
                    "Connection: close\r\n\r\n"
                socket.getOutputStream().write(request.toByteArray(Charsets.ISO_8859_1))
                socket.getOutputStream().flush()
                val response = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(1024)
                while (response.size() < 8192) {
                    val n = socket.getInputStream().read(buffer)
                    if (n < 0) break
                    response.write(buffer, 0, n)
                }
                val text = response.toString("ISO-8859-1")
                if (!text.startsWith("HTTP/1.1 200")) {
                    throw IllegalStateException("proxy challenge failed")
                }
                val body = text.substringAfter("\r\n\r\n", "").trim()
                val mac = Mac.getInstance("HmacSHA256")
                mac.init(SecretKeySpec(token.toByteArray(Charsets.UTF_8), "HmacSHA256"))
                val expected = mac.doFinal(nonce.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }
                if (!MessageDigest.isEqual(
                        body.toByteArray(Charsets.UTF_8),
                        expected.toByteArray(Charsets.UTF_8))) {
                    throw IllegalStateException("proxy authentication mismatch")
                }
            } finally {
                socket.close()
            }
        }

        private fun readHeaders(input: InputStream): String {
            val bytes = java.io.ByteArrayOutputStream()
            var match = 0
            while (bytes.size() < 8192) {
                val b = input.read()
                if (b < 0) throw IllegalStateException("EOF in tunnel headers")
                bytes.write(b)
                match = when {
                    match == 3 && b == 10 -> 4
                    match == 2 && b == 13 -> 3
                    match == 1 && b == 10 -> 2
                    b == 13 -> 1
                    else -> 0
                }
                if (match == 4) break
            }
            if (match != 4) throw IllegalStateException("unterminated tunnel headers")
            return bytes.toString("ISO-8859-1")
        }

        private fun readLine(input: InputStream): String {
            val bytes = java.io.ByteArrayOutputStream()
            while (bytes.size() < 4096) {
                val b = input.read()
                if (b < 0) break
                if (b == 10) break
                bytes.write(b)
            }
            return bytes.toString("UTF-8").trimEnd('\r')
        }
    }
}
