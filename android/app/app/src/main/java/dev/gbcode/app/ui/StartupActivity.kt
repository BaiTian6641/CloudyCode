package dev.gbcode.app.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.gbcode.app.GbCodeApp
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Launch flow (the product path):
 *
 * 1. The user opens the app.
 * 2. If the guest backend is not running, the app shows the one-time setup:
 *    the Terminal command endpoint is protected by the platform
 *    signature|preinstalled permission MANAGE_TERMINAL, so a sideloaded APK
 *    cannot run the command itself. The page offers the exact setup command
 *    and opens the Terminal; it keeps checking the forwarded ports.
 * 3. As soon as the backend (code-server on 8080, or the pinned reh-web on
 *    8085) is reachable, the workbench opens connected.
 * 4. The user works normally. A preinstalled/partner build holds the
 *    permission and can run the Terminal command intent itself.
 */
class StartupActivity : Activity() {
    private lateinit var statusText: TextView
    private lateinit var detail: TextView
    private lateinit var retry: Button
    private lateinit var copySetup: Button
    private lateinit var openTerminal: Button
    private lateinit var offline: Button
    private lateinit var advanced: Button

    private val handler = Handler(Looper.getMainLooper())
    private val probe = Executors.newSingleThreadExecutor { r ->
        Thread(r, "gbcode-startup-probe").apply { isDaemon = true }
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
        buildUi()
        begin()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 48)
        }
        val title = TextView(this).apply {
            text = "GBCode"
            textSize = 26f
            setTextColor(Color.parseColor("#e0e0e0"))
        }
        statusText = TextView(this).apply {
            text = "Preparing the Linux environment…"
            textSize = 17f
            setTextColor(Color.parseColor("#c0c0c0"))
            setPadding(0, 24, 0, 8)
        }
        detail = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#909090"))
        }
        retry = Button(this).apply {
            text = "Retry"
            setOnClickListener {
                pollBoth.set(false)
                handler.removeCallbacks(setupPoll)
                begin()
            }
        }
        copySetup = Button(this).apply {
            text = "Copy setup command"
            setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("GBCode setup", SETUP_COMMAND))
                setDetail("Command copied. Paste it in the Terminal; this screen continues " +
                    "by itself when the server is available.")
            }
        }
        openTerminal = Button(this).apply {
            text = "Open Terminal (one-time backend setup)"
            setOnClickListener { openTerminalApp() }
        }
        offline = Button(this).apply {
            text = "Continue without Linux (offline workbench)"
            setOnClickListener {
                startActivity(Intent(this@StartupActivity, WorkbenchActivity::class.java))
                finish()
            }
        }
        advanced = Button(this).apply {
            text = "Advanced setup (pair with an existing daemon)"
            setOnClickListener {
                startActivity(Intent(this@StartupActivity, OnboardingActivity::class.java))
                finish()
            }
        }
        root.addView(title)
        root.addView(statusText)
        root.addView(detail)
        val scroll = ScrollView(this).apply { addView(root) }
        root.addView(retry)
        root.addView(copySetup)
        root.addView(openTerminal)
        root.addView(offline)
        root.addView(advanced)
        setContentView(scroll)
        setButtonsVisible(false)
    }

    private fun begin() {
        setButtonsVisible(false)
        val app = application as GbCodeApp
        val token = app.connectionToken()
        setStatus("Checking the Linux environment…")
        if (probeMode) {
            probe.execute {
                val up = portOpen(targetPort)
                runOnUiThread {
                    if (up) {
                        setStatus("Probe server is reachable on 127.0.0.1:$PROBE_PORT")
                        setDetail("Terminal command intent executed in the VM and the guest " +
                            "port is forwarded to the app.")
                    } else if (hasManageTerminal() || forceIntent) {
                        launchTerminalCommand(
                            "python3 -m http.server $PROBE_PORT --bind 0.0.0.0")
                        awaitPort()
                    } else {
                        setStatus("Probe requires the preinstalled Terminal permission.")
                        setDetail("MANAGE_TERMINAL is signature|preinstalled; sideloaded " +
                            "apps are denied (verified on this device).")
                        setButtonsVisible(true)
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
                        setStatus("Backend is running; connecting…")
                        connect(app, token)
                    }
                    csUp -> {
                        setStatus("code-server is running; connecting…")
                        connectCodeServer(app)
                    }
                    else -> setupState()
                }
            }
        }
    }

    private fun setupState() {
        // The privileged command path is only usable by a preinstalled build.
        if (hasManageTerminal() || forceIntent) {
            launchTerminalCommand(
                "\$HOME/.local/bin/gbcode-start-backend --token " +
                    "${(application as GbCodeApp).connectionToken()} --port $BACKEND_PORT")
            awaitPort()
            return
        }
        setStatus("One-time setup: start the Linux backend")
        setDetail(
            "Android only lets the preinstalled Terminal API run commands in the VM " +
                "MANAGE_TERMINAL is signature|preinstalled). Use 'Copy setup command' and " +
                "run it once in the Terminal. This screen keeps checking and continues " +
                "by itself as soon as the server is available.")
        setButtonsVisible(true)
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
            // The Terminal app requires an activity started for result (it
            // reads getCallingPackage()/getLaunchedFromUid()).
            startActivityForResult(intent, REQ_TERMINAL)
            setStatus("Starting the Linux environment…")
            setDetail("Approve the command in the Terminal dialog.")
        } catch (e: SecurityException) {
            setStatus("The Terminal command endpoint is privileged (MANAGE_TERMINAL).")
            setDetail("A sideloaded APK cannot use it; use 'Copy setup command' and run " +
                "it once in the Terminal. This screen continues when the server is up.")
            setButtonsVisible(true)
            pollBoth.set(true)
            handler.postDelayed(setupPoll, 1500)
        } catch (e: android.content.ActivityNotFoundException) {
            setStatus("No Linux environment available on this device.")
            setDetail("The Terminal app was not found.")
            setButtonsVisible(true)
        }
    }

    private fun awaitPort() {
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
            setDetail("The Googlebook Terminal app is not installed.")
            return
        }
        try {
            startActivity(launch)
        } catch (e: Exception) {
            setDetail("Could not open the Terminal app: ${e.message}")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_TERMINAL) {
            Log.i("GBCodeStartup", "terminal intent result: $resultCode")
            setDetail("Terminal accepted the command (result $resultCode); waiting for the backend…")
        }
    }

    override fun onDestroy() {
        pollBoth.set(false)
        handler.removeCallbacks(setupPoll)
        probe.shutdownNow()
        super.onDestroy()
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

    private fun setStatus(text: String) {
        runOnUiThread { statusText.text = text }
    }

    private fun setDetail(text: String) {
        runOnUiThread { detail.text = text }
    }

    private fun setButtonsVisible(visible: Boolean) {
        val v = if (visible) LinearLayout.VISIBLE else LinearLayout.GONE
        runOnUiThread {
            retry.visibility = v
            copySetup.visibility = v
            openTerminal.visibility = v
            offline.visibility = v
            advanced.visibility = v
        }
    }

    companion object {
        private const val REQ_TERMINAL = 41
        private const val BACKEND_PORT = 8085
        private const val CODE_SERVER_PORT = 8080
        private const val PROBE_PORT = 18080
        private const val SETUP_COMMAND =
            "curl -fsSL https://code-server.dev/install.sh | sh -s -- --method=standalone " +
                "--prefix=\$HOME/.local && nohup \$HOME/.local/bin/code-server " +
                "--bind-addr 0.0.0.0:8080 --auth none >\$HOME/code-server.log 2>&1 &"
    }
}
