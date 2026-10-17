package dev.gbcode.app.ui

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
import dev.gbcode.app.GbCodeApp
import dev.gbcode.app.R
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
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
    private val token: String get() = (application as GbCodeApp).x11Token()

    private lateinit var status: TextView
    private lateinit var custom: EditText
    private var presetButtons: List<Button> = emptyList()
    private var probing = false

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
    }

    override fun onResume() {
        super.onResume()
        probe()
    }

    override fun onDestroy() {
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
            val ok = verifyChallenge()
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
