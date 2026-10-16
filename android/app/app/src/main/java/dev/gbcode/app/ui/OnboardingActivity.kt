package dev.gbcode.app.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.gbcode.app.ConnectionState
import dev.gbcode.app.GbCodeApp
import dev.gbcode.app.connection.SessionClient
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * First-run pairing: user supplies the daemon address and the one-time
 * pairing secret printed by gbcode-sessiond --enroll, plus the backend
 * workbench URL (the Code - OSS server). The fingerprint returned by pairing
 * is shown for out-of-band verification before the workbench opens.
 */
class OnboardingActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var logView: TextView
    private lateinit var daemonField: EditText
    private lateinit var workbenchField: EditText
    private lateinit var secretField: EditText
    private lateinit var passwordField: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as GbCodeApp

        val title = TextView(this)
        title.text = "GBCode — connect to the Googlebook Debian environment"
        title.textSize = 18f
        title.setPadding(24, 24, 24, 8)

        val instructions = TextView(this)
        instructions.text = "In the Terminal app, run the GBCode bootstrap, then start the daemon " +
                "with --enroll. Paste the printed daemon address and one-time pairing secret, and " +
                "the workbench backend URL. Compare the server fingerprint before continuing."
        instructions.setPadding(24, 0, 24, 16)

        daemonField = EditText(this)
        daemonField.setSingleLine(true)
        daemonField.hint = "daemon address (http://127.0.0.1:9123)"
        workbenchField = EditText(this)
        workbenchField.setSingleLine(true)
        workbenchField.hint = "workbench URL (http://127.0.0.1:8080)"
        secretField = EditText(this)
        secretField.setSingleLine(true)
        secretField.hint = "one-time pairing secret"
        passwordField = EditText(this)
        passwordField.setSingleLine(true)
        passwordField.hint = "backend password (optional; stored locally, never in page JS)"


        val pairButton = Button(this)
        pairButton.text = "Pair and connect"
        pairButton.setOnClickListener { startPairing(app) }

        logView = TextView(this)
        logView.setPadding(24, 16, 24, 16)
        val scroll = ScrollView(this)
        scroll.addView(logView)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.addView(title)
        root.addView(instructions)
        root.addView(daemonField)
        root.addView(workbenchField)
        root.addView(secretField)
        root.addView(passwordField)
        root.addView(pairButton)
        root.addView(scroll, LinearLayout.LayoutParams(

            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        // Debug-lane deep-fill: pairing parameters via intent extras (debug
        // builds only). The release UX is explicit user entry; a checked QR
        // import is S02.01 production work.
        if (dev.gbcode.app.BuildConfig.DEBUG) {
            intent.getStringExtra("daemon")?.let { daemonField.setText(it) }
            intent.getStringExtra("workbench")?.let { workbenchField.setText(it) }
            intent.getStringExtra("secret")?.let { secretField.setText(it) }
            intent.getStringExtra("password")?.let { passwordField.setText(it) }
        }

        app.addStateListener { state -> log("state: $state") }
        if (app.connectionState == ConnectionState.CONNECTED || app.restoreFromPersisted()) {
            app.setState(ConnectionState.CONNECTED)
            log("restored pairing (fingerprint ${app.serverFingerprint})")
            openWorkbench()
            return
        }
    }

    private fun startPairing(app: GbCodeApp) {
        val daemonBase = daemonField.text.toString().trim().ifEmpty { "http://127.0.0.1:9123" }
        val workbenchBase = workbenchField.text.toString().trim().ifEmpty { "http://127.0.0.1:8080" }
        val secret = secretField.text.toString().trim()
        if (secret.isEmpty()) {
            log("enter the one-time pairing secret from the daemon")
            return
        }
        app.setState(ConnectionState.PAIRING)
        log("pairing with $daemonBase …")
        executor.execute {
            try {
                val client = SessionClient(daemonBase)
                val result: JSONObject = client.fullFlow(secret,
                    "nonce-onboard-" + System.currentTimeMillis())
                val fingerprint = result.getJSONObject("hello")
                    .getJSONObject("payload").getString("fingerprint")
                runOnUiThread {
                    app.setState(ConnectionState.PAIRED, fingerprint)
                    app.backendBaseUrl = workbenchBase
                    val seedHex = client.keyPair.privateKey.joinToString("") { "%02x".format(it) }
                    app.savePairedState(GbCodeApp.PairedState(
                        seedHex = seedHex,
                        appHash = result.getString("enrolledApp"),
                        daemonBase = daemonBase,
                        workbenchBase = workbenchBase,
                        fingerprint = fingerprint,
                        backendPassword = passwordField.text.toString().ifEmpty { null },
                    ))
                    app.setState(ConnectionState.CONNECTED)
                    log("paired; server fingerprint:\n$fingerprint")
                    openWorkbench()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    app.setState(ConnectionState.ERROR)
                    log("pairing failed: ${e.message}")
                }
            }
        }
    }

    private fun openWorkbench() {
        startActivity(Intent(this, WorkbenchActivity::class.java))
    }

    private fun log(message: String) {
        android.util.Log.i("GBCodeOnboarding", message)
        runOnUiThread { logView.append(message + "\n") }
    }
}
