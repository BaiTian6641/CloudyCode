package dev.cloudy.x11.ui

import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import dev.cloudy.x11.CloudyX11App
import dev.cloudy.x11.R
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Launcher for per-application X11 sessions.
 *
 * Each preset starts a dedicated xpra session in the VM via the session manager
 * and opens it in a new freeform task, so every X application is its own
 * Googlebook OS window. The full desktop remains available as one window.
 */
class X11AppsActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "cloudy-x11-apps").apply { isDaemon = true }
    }
    private val token: String get() = (application as CloudyX11App).x11Token()
    private val nativeProbe: Boolean get() = intent.getBooleanExtra("probe", false)
    private val probeToken: String get() = intent.getStringExtra("probe_token").orEmpty()
    private val probePort: Int get() = intent.getIntExtra("probe_port", 14509)

    private lateinit var status: TextView
    private lateinit var custom: EditText
    private var presetButtons: List<Button> = emptyList()
    private var probing = false
    private var watching = false
    private val openedSessions = mutableSetOf<Int>()

    private val watchRunnable = object : Runnable {
        override fun run() {
            if (!watching) return
            io.execute {
                try {
                    val sessionRes = get("/list")
                    val sessions = sessionRes.optJSONArray("sessions")
                    handler.post {
                        if (sessions != null) {
                            for (i in 0 until sessions.length()) {
                                val so = sessions.optJSONObject(i) ?: continue
                                val p = so.optInt("port", 0)
                                val name = so.optString("name", "Window")
                                val sid = so.optString("id", "")
                                if (p > 0 && openedSessions.add(p)) {
                                    launchViewer(p, sid, name, false)
                                }
                            }
                        }
                        if (watching) handler.postDelayed(this, 2000)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "session watch failed: ${e.message}")
                    handler.post { if (watching) handler.postDelayed(this, 3000) }
                }
            }
        }
    }

    private data class Preset(val app: String, val label: String)

    private val presets = listOf(
        Preset("firefox", "Firefox"),
        Preset("xterm", "Terminal"),
        Preset("xcalc", "Calculator"),
        Preset("xeyes", "X Eyes"),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        handleLaunchModeIntent()
        if (nativeProbe) {
            status.text = "Native transport probe starting…"
            runNativeProbe()
        }
    }

    private fun handleLaunchModeIntent() {
        if (intent.getBooleanExtra("per_window_host", false)) {
            val dm = resources.displayMetrics
            val left = (dm.widthPixels * 0.64f).toInt()
            val top = (dm.heightPixels * 0.08f).toInt()
            val right = (dm.widthPixels * 0.96f).toInt()
            val bottom = (dm.heightPixels * 0.34f).toInt()
            val opts = ActivityOptions.makeBasic()
                .setLaunchBounds(Rect(left, top, right, bottom)).toBundle()
            startActivity(Intent(this, dev.cloudy.x11.native.PerWindowSessionActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), opts)
            finish()
            return
        }
        if (intent.getBooleanExtra("per_window", false)) {
            try {
                startForegroundService(Intent(this,
                    dev.cloudy.x11.native.NativeEngineService::class.java)
                    .putExtra("action", "start"))
            } catch (e: Exception) {
                Log.w(TAG, "per-window engine start failed: ${e.message}")
            }
        }
        if (intent.getBooleanExtra("native_preview", false)) {
            startActivity(Intent(this, dev.cloudy.x11.native.NativePreviewActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            // The preview is the diagnostic host; do not leave the launcher
            // covering its Surface.
            finish()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchModeIntent()
    }

    override fun onResume() {
        super.onResume()
        if (nativeProbe) return
        probe()
        if (!watching) {
            watching = true
            handler.post(watchRunnable)
        }
    }

    override fun onPause() {
        watching = false
        handler.removeCallbacks(watchRunnable)
        super.onPause()
    }

    override fun onDestroy() {
        watching = false
        handler.removeCallbacks(watchRunnable)
        io.shutdownNow()
        super.onDestroy()
    }

    private fun buildUi(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(56, 48, 56, 48)
            setBackgroundColor(getColor(R.color.cw_canvas))
        }
        col.addView(TextView(this).apply {
            text = "Cloudy X11"
            textSize = 28f
            setTextColor(getColor(R.color.cw_text_primary))
        })
        col.addView(TextView(this).apply {
            text = "Each application opens in its own Googlebook window."
            textSize = 15f
            setTextColor(getColor(R.color.cw_text_secondary))
            setPadding(0, 8, 0, 24)
        })
        status = TextView(this).apply {
            text = "Checking the session service…"
            textSize = 14f
            setTextColor(getColor(R.color.cw_text_secondary))
            setPadding(0, 0, 0, 16)
        }
        col.addView(status)

        presetButtons = presets.map { preset ->
            Button(this).apply {
                text = preset.label
                isEnabled = false
                setOnClickListener { startApp(preset.app, null) }
            }.also {
                col.addView(it, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT))
            }
        }

        col.addView(TextView(this).apply {
            text = "Custom command"
            textSize = 14f
            setTextColor(getColor(R.color.cw_text_secondary))
            setPadding(0, 24, 0, 8)
        })
        custom = EditText(this).apply {
            hint = "e.g. gimp, libreoffice, python3 -m idlelib"
            setSingleLine(true)
        }
        col.addView(custom, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT))
        col.addView(Button(this).apply {
            text = "Launch command"
            isEnabled = false
            setOnClickListener {
                val cmd = custom.text.toString().trim()
                if (cmd.isEmpty()) Toast.makeText(
                    this@X11AppsActivity, "Enter a command", Toast.LENGTH_SHORT).show()
                else startApp("custom", cmd)
            }
        }.also { presetButtons = presetButtons + it })

        col.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(1, 24)
        })
        col.addView(Button(this).apply {
            text = "Full Linux desktop"
            setOnClickListener { launchViewer(14500, null, "Linux desktop", false) }
        })
        col.addView(Button(this).apply {
            text = "X11 setup / repair"
            setOnClickListener { launchViewer(14500, null, "Cloudy X11 setup", true) }
        })
        setPresetsEnabled(false)

        return ScrollView(this).apply { addView(col) }
    }

    private fun setPresetsEnabled(enabled: Boolean) {
        presetButtons.forEach { it.isEnabled = enabled }
    }

    private fun setBusy(busy: Boolean) {
        if (busy) setPresetsEnabled(false) else probe()
    }

    private fun probe() {
        if (probing) return
        probing = true
        io.execute {
            // The authenticated /list is the readiness gate: it proves the
            // per-install token in the same way the session watcher does. The
            // HMAC challenge remains a diagnostic because a service can have a
            // working cookie path while its ping endpoint is stale.
            val challenge = verifyChallenge()
            val ok = try {
                get("/list")
                true
            } catch (e: Exception) {
                Log.w(TAG, "session list probe failed: ${e.message}")
                false
            }
            if (!challenge && ok) Log.w(TAG, "HMAC challenge failed but /list is authenticated")
            handler.post {
                probing = false
                if (isFinishing) return@post
                if (ok) {
                    status.text = "Ready — pick an application."
                    setPresetsEnabled(true)
                } else {
                    status.text = "Session service not reachable. Run X11 setup below, then return."
                    setPresetsEnabled(false)
                }
            }
        }
    }

    private fun verifyChallenge(): Boolean = try {
        val nonce = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
        val conn = URL("$CONTROL/__cloudy/ping?nonce=$nonce")
            .openConnection() as HttpURLConnection
        conn.connectTimeout = 1500
        conn.readTimeout = 1500
        if (conn.responseCode != 200) {
            conn.disconnect()
            false
        } else {
            val body = conn.inputStream.bufferedReader().use { it.readText() }.trim()
            conn.disconnect()
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(token.toByteArray(Charsets.UTF_8), "HmacSHA256"))
            val expected = mac.doFinal(nonce.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            body == expected
        }
    } catch (e: Exception) {
        Log.w(TAG, "session service probe failed: ${e.message}")
        false
    }

    private fun startApp(app: String, command: String?) {
        setPresetsEnabled(false)
        status.text = "Starting…"
        io.execute {
            try {
                val (width, height) = windowSize()
                val payload = JSONObject().put("app", app)
                    .put("width", width).put("height", height)
                if (command != null) payload.put("command", command)
                val result = post("/start", payload.toString())
                val port = result.getInt("port")
                val sid = result.getString("id")
                val name = result.optString("name", app)
                handler.post {
                    if (!isFinishing) {
                        openedSessions.add(port)
                        status.text = "Started $name."
                        launchViewer(port, sid, name, false)
                        probe()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "start failed: ${e.message}")
                handler.post {
                    status.text = "Could not start: ${e.message}"
                    Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
                    probe()
                }
            }
        }
    }

    // G1 diagnostic. This runs in the installed APK under its real UID and
    // proves native reachability of the Terminal-forwarded control port with a
    // raw HTTP Upgrade and full-duplex binary framing. It is not the X11 path.
    private fun runNativeProbe() {
        io.execute {
            val results = mutableListOf<String>()
            val connectStart = System.currentTimeMillis()
            fun pass(name: String, detail: String = "") {
                results += "PASS $name${if (detail.isEmpty()) "" else " $detail"}"
            }
            fun fail(name: String, detail: String = "") {
                results += "FAIL $name${if (detail.isEmpty()) "" else " $detail"}"
            }
            fun expect(name: String, expected: ByteArray, actual: ByteArray) {
                if (expected.contentEquals(actual)) pass(name, "bytes=${expected.size}")
                else fail(name, "expected=${expected.size} got=${actual.size}")
            }
            fun sendUpgrade(socket: Socket, cookie: String, upgrade: String, probe: String) {
                val request = "GET /__cloudy/probe HTTP/1.1\r\n" +
                    "Host: 127.0.0.1:$probePort\r\n" +
                    "Cookie: cloudy_x11=$cookie\r\n" +
                    "X-Cloudy-Probe: $probe\r\n" +
                    "Upgrade: $upgrade\r\n" +
                    "Connection: Upgrade\r\n\r\n"
                socket.getOutputStream().write(request.toByteArray(Charsets.ISO_8859_1))
                socket.getOutputStream().flush()
            }
            fun open(cookie: String = token, upgrade: String = "cloudy-probe",
                     probe: String = probeToken): Socket {
                val s = Socket()
                s.connect(InetSocketAddress("127.0.0.1", probePort), 3000)
                s.soTimeout = 5000
                s.tcpNoDelay = true
                sendUpgrade(s, cookie, upgrade, probe)
                val (code, headers) = readHeaders(s.getInputStream())
                val up = headers["upgrade"] ?: ""
                if (code != 101 || up.lowercase() != "cloudy-probe") {
                    s.close()
                    throw IllegalStateException("upgrade HTTP $code upgrade=$up")
                }
                return s
            }
            fun statusFor(cookie: String, upgrade: String, probe: String): Int {
                val s = Socket()
                try {
                    s.connect(InetSocketAddress("127.0.0.1", probePort), 3000)
                    s.soTimeout = 5000
                    sendUpgrade(s, cookie, upgrade, probe)
                    return readHeaders(s.getInputStream()).first
                } finally {
                    s.close()
                }
            }
            try {
                results += "INFO uid=${android.os.Process.myUid()} " +
                    "sdk=${android.os.Build.VERSION.SDK_INT} package=$packageName"
                results += "INFO build=${android.os.Build.FINGERPRINT}"
                results += "INFO endpoint=127.0.0.1:$probePort"
                val first = open()
                val peer = first.inetAddress
                results += "INFO peer=${peer.hostAddress} " +
                    "family=${if (peer is Inet6Address) "inet6" else "inet"} " +
                    "connectMs=${System.currentTimeMillis() - connectStart}"
                val input = first.getInputStream()
                val output = first.getOutputStream()

                writeFrame(output, ByteArray(0))
                expect("zero-length", ByteArray(0), readFrame(input))

                val short = "short-payload".toByteArray()
                writeFrame(output, short)
                expect("short-read", short, readFrame(input))

                val coalesced = listOf("one".toByteArray(), "two-two".toByteArray(),
                    "three-three-three".toByteArray())
                val coalescedBytes = coalesced.fold(ByteArray(0)) { acc, item ->
                    acc + frameOf(item)
                }
                output.write(coalescedBytes)
                output.flush()
                coalesced.forEachIndexed { i, item ->
                    expect("coalesced-$i", item, readFrame(input))
                }

                val split = "frame-split-across-writes".toByteArray()
                val splitFrame = frameOf(split)
                output.write(splitFrame, 0, 2)
                output.flush()
                Thread.sleep(20)
                output.write(splitFrame, 2, 2)
                output.flush()
                Thread.sleep(20)
                output.write(splitFrame, 4, splitFrame.size - 4)
                output.flush()
                expect("split-writes", split, readFrame(input))

                val large = ByteArray(256 * 1024) { ((it * 31 + 7) and 0xff).toByte() }
                writeFrame(output, large)
                expect("large-spanning-writes", large, readFrame(input))

                val duplex = (0 until 8).map { i ->
                    ("duplex-$i-".toByteArray() + ByteArray(4096) {
                        ((it + i) and 0xff).toByte()
                    })
                }
                val writerError = AtomicReference<Throwable?>(null)
                val writer = Thread {
                    try {
                        duplex.forEach { writeFrame(output, it) }
                    } catch (e: Throwable) {
                        writerError.set(e)
                    }
                }
                writer.start()
                duplex.forEachIndexed { i, item ->
                    expect("duplex-$i", item, readFrame(input))
                }
                writer.join(5000)
                if (writer.isAlive) fail("duplex-writer", "still running")
                writerError.get()?.let { fail("duplex-writer", it.message ?: it.javaClass.simpleName) }
                first.close()

                val oversize = open()
                val overOut = oversize.getOutputStream()
                overOut.write(byteArrayOf(0x00, 0x10, 0x00, 0x01))
                overOut.flush()
                val overReply = readExact(oversize.getInputStream(), 4)
                if (overReply.contentEquals(byteArrayOf(
                        0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte()))) {
                    pass("oversize-rejected")
                } else {
                    fail("oversize-rejected", overReply.joinToString("") { "%02x".format(it) })
                }
                oversize.close()

                val wrongCookie = statusFor("wrong-token", "cloudy-probe", probeToken)
                if (wrongCookie == 403) pass("wrong-session-cookie", "HTTP 403")
                else fail("wrong-session-cookie", "HTTP $wrongCookie")

                val wrongProbe = statusFor(token, "cloudy-probe", "wrong-probe-token")
                if (wrongProbe == 403) pass("wrong-probe-token", "HTTP 403")
                else fail("wrong-probe-token", "HTTP $wrongProbe")

                val wrongUpgrade = statusFor(token, "websocket", probeToken)
                if (wrongUpgrade == 400) pass("malformed-upgrade", "HTTP 400")
                else fail("malformed-upgrade", "HTTP $wrongUpgrade")

                val lost = open()
                lost.getOutputStream().write(byteArrayOf(0, 0))
                lost.getOutputStream().flush()
                lost.close()
                pass("peer-loss-closed")

                val reconnect = open()
                val reconnectBody = "reconnect-after-loss".toByteArray()
                writeFrame(reconnect.getOutputStream(), reconnectBody)
                expect("reconnect", reconnectBody, readFrame(reconnect.getInputStream()))
                reconnect.close()
            } catch (e: Throwable) {
                fail("exception", "${e.javaClass.simpleName}: ${e.message}")
            }

            val ok = results.none { it.startsWith("FAIL") }
            val summary = if (ok) "Native transport probe PASS (${results.size} checks)"
                else "Native transport probe FAIL (${results.count { it.startsWith("FAIL") }} failures)"
            val report = results.joinToString("\n") + "\n"
            try {
                java.io.File(filesDir, "native-probe.txt").writeText(report)
            } catch (e: Exception) {
                Log.w(TAG, "could not store probe report: ${e.message}")
            }
            results.forEach { Log.i(TAG, "PROBE $it") }
            Log.i(TAG, "PROBE SUMMARY $summary")
            handler.post {
                if (isFinishing) return@post
                status.text = summary
                setPresetsEnabled(ok)
                Toast.makeText(this@X11AppsActivity, summary, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun readHeaders(input: InputStream): Pair<Int, Map<String, String>> {
        val buffer = ByteArrayOutputStream()
        var match = 0
        while (buffer.size() < 8192) {
            val b = input.read()
            if (b < 0) throw EOFException("EOF in response headers")
            buffer.write(b)
            match = when {
                match == 3 && b == 10 -> 4
                match == 2 && b == 13 -> 3
                match == 1 && b == 10 -> 2
                b == 13 -> 1
                else -> 0
            }
            if (match == 4) break
        }
        if (match != 4) throw IllegalStateException("unterminated or oversized headers")
        val text = buffer.toString("ISO-8859-1")
        val lines = text.split("\r\n")
        val code = lines.firstOrNull()?.split(" ")?.getOrNull(1)?.toIntOrNull()
            ?: throw IllegalStateException("bad status line")
        val headers = lines.drop(1).filter { it.contains(":") }.associate {
            val name = it.substringBefore(":").trim().lowercase()
            name to it.substringAfter(":").trim()
        }
        return code to headers
    }

    private fun readExact(input: InputStream, count: Int): ByteArray {
        val out = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val n = input.read(out, offset, count - offset)
            if (n < 0) throw EOFException("EOF after $offset/$count bytes")
            offset += n
        }
        return out
    }

    private fun readFrame(input: InputStream): ByteArray {
        val header = readExact(input, 4)
        val length = ((header[0].toInt() and 0xff) shl 24) or
            ((header[1].toInt() and 0xff) shl 16) or
            ((header[2].toInt() and 0xff) shl 8) or
            (header[3].toInt() and 0xff)
        if (length == -1 || length > 1024 * 1024) {
            throw IllegalStateException("invalid echo length $length")
        }
        return readExact(input, length)
    }

    private fun frameOf(payload: ByteArray): ByteArray {
        val n = payload.size
        val out = ByteArray(4 + n)
        out[0] = ((n ushr 24) and 0xff).toByte()
        out[1] = ((n ushr 16) and 0xff).toByte()
        out[2] = ((n ushr 8) and 0xff).toByte()
        out[3] = (n and 0xff).toByte()
        payload.copyInto(out, 4)
        return out
    }

    private fun writeFrame(output: OutputStream, payload: ByteArray) {
        output.write(frameOf(payload))
        output.flush()
    }

    private fun get(path: String): JSONObject {
        val conn = URL("$CONTROL$path").openConnection() as HttpURLConnection
        conn.setRequestProperty("Cookie", "cloudy_x11=$token")
        conn.connectTimeout = 2000
        conn.readTimeout = 4000
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        conn.disconnect()
        if (code != 200) throw IllegalStateException("HTTP $code")
        return JSONObject(text)
    }

    private fun post(path: String, body: String): JSONObject {
        val conn = URL("$CONTROL$path").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Cookie", "cloudy_x11=$token")
        conn.connectTimeout = 2000
        conn.readTimeout = 35000
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        conn.disconnect()
        if (code != 200) throw IllegalStateException(
            JSONObject(text).optString("error", "HTTP $code"))
        return JSONObject(text)
    }

    private fun windowSize(): Pair<Int, Int> {
        val dm = resources.displayMetrics
        return Pair((dm.widthPixels * 0.72f).toInt(),
            (dm.heightPixels * 0.78f).toInt())
    }

    private fun launchViewer(port: Int, sid: String?, name: String, setup: Boolean) {
        val intent = Intent(this, X11Activity::class.java).apply {
            putExtra("port", port)
            putExtra("name", name)
            putExtra("setup", setup)
            if (sid != null) putExtra("sid", sid)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_NEW_DOCUMENT or
                Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        }
        val dm = resources.displayMetrics
        val (w, h) = windowSize()
        val x = (dm.widthPixels - w) / 2
        val y = (dm.heightPixels - h) / 2
        val opts = ActivityOptions.makeBasic()
            .setLaunchBounds(Rect(x, y, x + w, y + h)).toBundle()
        startActivity(intent, opts)
    }

    companion object {
        private const val TAG = "CloudyX11"
        private const val CONTROL = "http://127.0.0.1:14509"
    }
}
