package dev.gbcode.app.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import dev.gbcode.app.GbCodeApp
import dev.gbcode.app.R
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * Launch screen (Cloudy design): calm status first, one filled teal action.
 *
 * 1. Probe the forwarded backend ports off the main thread.
 * 2. If a backend is up: open the workbench connected.
 * 3. Otherwise: one-time terminal setup card (copy the command, open the
 *    Terminal) while checking continues; the screen advances by itself as
 *    soon as the server appears.
 * 4. The privileged Terminal command intent is used only when the build holds
 *    MANAGE_TERMINAL (preinstalled/partner build).
 */
class StartupActivity : Activity() {
    private lateinit var statusText: TextView
    private lateinit var detail: TextView
    private lateinit var progress: View
    private lateinit var setupCard: View
    private lateinit var offline: Button
    private lateinit var advanced: Button

    private val handler = Handler(Looper.getMainLooper())
    private val probe = Executors.newSingleThreadExecutor { r ->
        Thread(r, "cloudy-startup-probe").apply { isDaemon = true }
    }
    private val pollBoth = AtomicBoolean(false)
    private var probeMode = false
    private var forceIntent = false
    private var targetPort = BACKEND_PORT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        probeMode = intent.getBooleanExtra("probe", false)
        forceIntent = intent.getBooleanExtra("force_intent", false)
        if (probeMode) targetPort = PROBE_PORT
        setContentView(R.layout.activity_startup)

        statusText = findViewById(R.id.statusText)
        detail = findViewById(R.id.detail)
        progress = findViewById(R.id.progress)
        setupCard = findViewById(R.id.setupCard)
        offline = findViewById(R.id.offline)
        advanced = findViewById(R.id.advanced)

        findViewById<TextView>(R.id.commandBlock).text = GuestSetup.COMMAND
        findViewById<Button>(R.id.copySetup).setOnClickListener {
            setClipboard("Cloudy Code setup", GuestSetup.COMMAND)
            detail.text = "Command copied. Paste it in the Terminal; this screen continues " +
                "by itself when the server is available."
        }
        findViewById<Button>(R.id.openTerminal).setOnClickListener { openTerminalApp() }
        findViewById<Button>(R.id.copyBuildScript).setOnClickListener {
            val script = assets.open("cloudy-build-backend.sh").bufferedReader().use { it.readText() }
            setClipboard("Cloudy backend build", script)
            detail.text = "Build script copied. Paste it in the Terminal once (about 15-30 " +
                "minutes). When it finishes, copy the Cloudy backend start command and run it."
        }
        findViewById<Button>(R.id.copyRehWebStart).setOnClickListener {
            val token = (application as GbCodeApp).connectionToken()
            setClipboard("Cloudy backend start", GuestSetup.rehWebStartCommand(token))
            detail.text = "Start command copied. Run it in the Terminal; this screen connects " +
                "on port 8085 when the Cloudy backend is up."
        }
        findViewById<Button>(R.id.retry).setOnClickListener {
            pollBoth.set(false)
            handler.removeCallbacks(setupPoll)
            begin()
        }
        offline.setOnClickListener {
            val app = application as GbCodeApp
            app.backendBaseUrl = null
            app.backendKind = "reh-web"
            app.guestFolder = null
            startActivity(Intent(this, WorkbenchActivity::class.java))
            finish()
        }
        advanced.setOnClickListener {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
        }

        // Productive column: prose stays within a readable measure on wide
        // Googlebook windows instead of stretching edge to edge.
        val content = findViewById<LinearLayout>(R.id.content)
        content.post {
            val maxContent = dp(560)
            val pad = max(dp(48), (content.width - maxContent) / 2)
            content.setPadding(pad, dp(40), pad, dp(40))
        }
        showSetup(false)
        begin()
    }

    private fun begin() {
        showSetup(false)
        progress.visibility = View.VISIBLE
        statusText.setText(R.string.startup_checking)
        val app = application as GbCodeApp
        val token = app.connectionToken()
        if (probeMode) {
            probe.execute {
                val up = portOpen(targetPort)
                runOnUiThread {
                    if (up) {
                        progress.visibility = View.GONE
                        statusText.text = "Probe server is reachable on 127.0.0.1:$PROBE_PORT"
                        detail.text = "Terminal command intent executed in the VM and the " +
                            "guest port is forwarded to the app."
                    } else if (hasManageTerminal() || forceIntent) {
                        launchTerminalCommand("python3 -m http.server $PROBE_PORT --bind 0.0.0.0")
                        awaitPort()
                    } else {
                        showSetup(true)
                        statusText.text = "Probe requires the preinstalled Terminal permission."
                        detail.text = "MANAGE_TERMINAL is signature|preinstalled; sideloaded " +
                            "apps are denied (verified on this device)."
                    }
                }
            }
            return
        }
        probe.execute {
            val rehUp = portOpen(BACKEND_PORT)
            val csUp = !rehUp && portOpen(CODE_SERVER_PORT)
            runOnUiThread {
                when {
                    rehUp -> {
                        statusText.text = "Backend is running; connecting…"
                        connect(app, token)
                    }
                    csUp -> {
                        statusText.text = "code-server is running; connecting…"
                        connectCodeServer(app)
                    }
                    else -> setupState()
                }
            }
        }
    }

    private fun setupState() {
        if (hasManageTerminal() || forceIntent) {
            launchTerminalCommand(
                GuestSetup.rehWebStartCommand((application as GbCodeApp).connectionToken()))
            awaitPort()
            return
        }
        showSetup(true)
        statusText.text = "One-time setup: start the Linux backend"
        detail.text = "Android lets only the preinstalled Terminal API run commands in the VM " +
            "(MANAGE_TERMINAL is signature|preinstalled). Copy the setup command and run it " +
            "once in the Terminal, then keep the Terminal app running: it provides the VM " +
            "port bridge this window needs. This screen keeps checking and continues by " +
            "itself as soon as the server is available."
        pollBoth.set(true)
        handler.postDelayed(setupPoll, 1500)
    }

    private val setupPoll: Runnable = object : Runnable {
        override fun run() {
            if (!pollBoth.get()) return
            probe.execute {
                val rehUp = portOpen(BACKEND_PORT)
                val csUp = !rehUp && portOpen(CODE_SERVER_PORT)
                runOnUiThread {
                    val app = application as GbCodeApp
                    when {
                        rehUp -> connect(app, app.connectionToken())
                        csUp -> connectCodeServer(app)
                        pollBoth.get() -> handler.postDelayed(setupPoll, 1500)
                    }
                }
            }
        }
    }

    private fun launchTerminalCommand(command: String) {
        val uri = Uri.parse(
            "android-terminal://start?cmd=" + Uri.encode(command) + "&background=true")
        val intent = Intent(Intent.ACTION_VIEW, uri)
            .putExtra("com.android.virtualization.terminal.EXTRA_GO_BACKGROUND", true)
        try {
            startActivityForResult(intent, REQ_TERMINAL)
            statusText.setText(R.string.startup_starting_vm)
            detail.text = "Approve the command in the Terminal dialog."
        } catch (e: SecurityException) {
            showSetup(true)
            statusText.text = "The Terminal command endpoint is privileged."
            detail.text = "A sideloaded app cannot use it; copy the setup command and run it " +
                "once in the Terminal. This screen continues when the server is up."
            pollBoth.set(true)
            handler.postDelayed(setupPoll, 1500)
        } catch (e: android.content.ActivityNotFoundException) {
            showSetup(true)
            statusText.text = "No Linux environment available on this device."
            detail.text = "The Terminal app was not found."
        }
    }

    private fun awaitPort() {
        progress.visibility = View.VISIBLE
        pollBoth.set(true)
        handler.postDelayed(setupPoll, 700)
    }

    private fun connect(app: GbCodeApp, token: String) {
        pollBoth.set(false)
        app.backendBaseUrl = "http://127.0.0.1:$BACKEND_PORT"
        app.backendKind = "reh-web"
        app.backendPassword = token
        app.setState(dev.gbcode.app.ConnectionState.CONNECTED)
        startActivity(Intent(this, WorkbenchActivity::class.java))
        finish()
    }

    private fun connectCodeServer(app: GbCodeApp) {
        pollBoth.set(false)
        app.backendBaseUrl = "http://127.0.0.1:$CODE_SERVER_PORT"
        app.backendKind = "code-server"
        app.backendPassword = null
        app.guestFolder = null
        app.setState(dev.gbcode.app.ConnectionState.CONNECTED)
        startActivity(Intent(this, WorkbenchActivity::class.java))
        finish()
    }

    private fun openTerminalApp() {
        val launch = packageManager.getLaunchIntentForPackage("com.android.virtualization.terminal")
        if (launch == null) {
            detail.text = "The Googlebook Terminal app is not installed."
            return
        }
        try {
            startActivity(launch)
        } catch (e: Exception) {
            detail.text = "Could not open the Terminal app: ${e.message}"
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_TERMINAL) {
            Log.i("CloudyStartup", "terminal intent result: $resultCode")
            detail.text = "Terminal accepted the command (result $resultCode); waiting for the backend…"
        }
    }

    override fun onDestroy() {
        pollBoth.set(false)
        handler.removeCallbacks(setupPoll)
        probe.shutdownNow()
        super.onDestroy()
    }

    private fun showSetup(visible: Boolean) {
        val v = if (visible) View.VISIBLE else View.GONE
        setupCard.visibility = v
        offline.visibility = v
        advanced.visibility = v
        if (visible) progress.visibility = View.VISIBLE
    }

    private fun hasManageTerminal(): Boolean =
        checkSelfPermission("com.android.virtualization.terminal.permission.MANAGE_TERMINAL") ==
            PackageManager.PERMISSION_GRANTED

    /** Off-main-thread TCP probe of a loopback port. */
    private fun portOpen(port: Int): Boolean {
        return try {
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 400) }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun setClipboard(label: String, value: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQ_TERMINAL = 41
        private const val BACKEND_PORT = 8085
        private const val CODE_SERVER_PORT = 8080
        private const val PROBE_PORT = 18080

    }
}
