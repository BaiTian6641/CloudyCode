package dev.gbcode.app.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.gbcode.app.GbCodeApp
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Launch flow (the product path):
 *
 * 1. The user opens the app.
 * 2. If the guest backend is not running, the app executes a command in the
 *    Linux environment through the Terminal app's documented command intent
 *    (`android-terminal://start?cmd=…&background=true`). The Terminal app
 *    installs/initializes the VM when needed and asks the user to allow the
 *    command; the VM runs it in the background.
 * 3. The app waits for the backend port (the Terminal app forwards guest
 *    ports to Android loopback), then opens the workbench connected to it.
 * 4. The user works normally.
 *
 * The app-owned workbench remains available offline if no Linux environment
 * is present; onboarding (manual daemon pairing) stays as the advanced path.
 */
class StartupActivity : Activity() {
    private lateinit var statusText: TextView
    private lateinit var detail: TextView
    private lateinit var retry: Button
    private lateinit var openTerminal: Button
    private lateinit var offline: Button
    private lateinit var advanced: Button

    private val handler = Handler(Looper.getMainLooper())
    private var probeMode = false
    private var targetPort = BACKEND_PORT
    private var deadlineAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        probeMode = intent.getBooleanExtra("probe", false)
        if (probeMode) targetPort = PROBE_PORT

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
            setOnClickListener { begin() }
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
        root.addView(openTerminal)
        root.addView(offline)
        root.addView(advanced)
        setContentView(scroll)
        setButtonsVisible(false)
        begin()
    }

    private fun begin() {
        setButtonsVisible(false)
        val app = application as GbCodeApp
        val token = app.connectionToken()
        setStatus("Checking the Linux environment…")

        if (probeMode) {
            if (portOpen(targetPort)) {
                setStatus("Probe server already running on port $targetPort")
                setDetail("Terminal intent + port forwarding verified (probe mode).")
                return
            }
            if (!hasManageTerminal()) {
                setStatus("Probe requires the preinstalled Terminal permission.")
                setDetail("MANAGE_TERMINAL is signature|preinstalled; side loaded apps are denied.")
                setButtonsVisible(true)
                return
            }
            launchTerminalCommand("python3 -m http.server $PROBE_PORT --bind 0.0.0.0")
            awaitPort()
            return
        }

        if (portOpen(targetPort)) {
            setStatus("Backend already running; connecting…")
            connect(app, token)
            return
        }
        // The Terminal command endpoint is protected by the platform
        // signature|preinstalled permission MANAGE_TERMINAL: a sideloaded APK
        // gets "Permission Denial" (verified on the real device). Only a
        // preinstalled/partner build can start the VM command itself.
        if (hasManageTerminal()) {
            launchTerminalCommand(
                "\$HOME/.local/bin/gbcode-start-backend --token $token --port $BACKEND_PORT")
            awaitPort()
            return
        }
        setStatus("The Linux backend is not running.")
        setDetail(
            "Android allows only the privileged Terminal API to start commands in the VM. " +
                "Run GBCode's backend once in the Terminal (one-time setup); after that it " +
                "starts with the VM and this screen connects automatically on every launch.")
        setButtonsVisible(true)
    }

    private fun launchTerminalCommand(command: String) {
        val uri = Uri.parse(
            "android-terminal://start?cmd=" + Uri.encode(command) + "&background=true")
        val intent = Intent(Intent.ACTION_VIEW, uri)
            .putExtra("com.android.virtualization.terminal.EXTRA_GO_BACKGROUND", true)
        // resolveActivity() is gated by package visibility; launching an
        // implicit intent is not. Attempt it and treat a missing handler as
        // "no Linux environment".
        try {
            // The Terminal app requires an activity started for result
            // (it reads getCallingPackage()/getLaunchedFromUid()).
            startActivityForResult(intent, REQ_TERMINAL)
            setStatus("Starting the Linux environment…")
            setDetail("Approve the command in the Terminal dialog to let GBCode start its backend.")
        } catch (e: android.content.ActivityNotFoundException) {
            setStatus("No Linux environment available on this device.")
            setDetail("The Terminal app was not found; install the Googlebook Linux Terminal.")
            setButtonsVisible(true)
        }
    }

    private fun awaitPort() {
        deadlineAt = System.currentTimeMillis() + WAIT_MS
        handler.postDelayed(pollRunnable, 700)
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            val up = portOpen(targetPort)
            if (up) {
                onPortUp()
                return
            }
            if (System.currentTimeMillis() >= deadlineAt) {
                setStatus("The backend did not start in time.")
                setDetail(
                    if (probeMode) "Probe failed: no listener on port $targetPort."
                    else "The Terminal app may still be installing the VM, or the start " +
                        "command is not provisioned in this VM. Retry, continue offline, " +
                        "or use the advanced setup.")
                setButtonsVisible(true)
                return
            }
            handler.postDelayed(this, 500)
        }
    }

    private fun onPortUp() {
        handler.removeCallbacks(pollRunnable)
        if (probeMode) {
            setStatus("Probe server is reachable on 127.0.0.1:$PROBE_PORT")
            setDetail("Terminal command intent executed in the VM and the guest port is " +
                "forwarded to the app. The full flow is viable.")
            setButtonsVisible(false)
            return
        }
        val app = application as GbCodeApp
        setStatus("Backend is up; connecting…")
        connect(app, app.connectionToken())
    }

    private fun connect(app: GbCodeApp, token: String) {
        app.backendBaseUrl = "http://127.0.0.1:$BACKEND_PORT"
        app.backendKind = "reh-web"
        app.backendPassword = token
        app.setState(dev.gbcode.app.ConnectionState.CONNECTED)
        startActivity(Intent(this, WorkbenchActivity::class.java))
        finish()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_TERMINAL) {
            Log.i("GBCodeStartup", "terminal intent result: $resultCode")
            setDetail("Terminal accepted the command (result $resultCode); waiting for the backend…")
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(pollRunnable)
        super.onDestroy()
    }

    private fun setStatus(text: String) {
        runOnUiThread { statusText.text = text }
    }

    private fun setDetail(text: String) {
        runOnUiThread { detail.text = text }
    }

    /** Off-main-thread TCP probe of a loopback port. */
    private fun portOpen(port: Int): Boolean {
        return try {
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 300) }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun hasManageTerminal(): Boolean =
        checkSelfPermission("com.android.virtualization.terminal.permission.MANAGE_TERMINAL") ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

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

    private fun setButtonsVisible(visible: Boolean) {
        runOnUiThread {
            retry.visibility = if (visible) LinearLayout.VISIBLE else LinearLayout.GONE
            openTerminal.visibility = if (visible) LinearLayout.VISIBLE else LinearLayout.GONE
            offline.visibility = if (visible) LinearLayout.VISIBLE else LinearLayout.GONE
            advanced.visibility = if (visible) LinearLayout.VISIBLE else LinearLayout.GONE
        }
    }

    companion object {
        private const val REQ_TERMINAL = 41
        private const val BACKEND_PORT = 8085
        private const val PROBE_PORT = 18080
        private const val WAIT_MS = 180_000L
    }
}
