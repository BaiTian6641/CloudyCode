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
import dev.gbcode.app.connection.Ed25519
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
        secretField.inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        secretField.hint = "one-time pairing secret"
        passwordField = EditText(this)
        passwordField.setSingleLine(true)
        passwordField.inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        passwordField.hint = "backend password (optional; stored locally, never in page JS)"


        val pairButton = Button(this)
        pairButton.text = "Pair and connect"
        pairButton.setOnClickListener { startPairing(app) }

        val reconnectButton = Button(this)
        reconnectButton.text = "Reconnect saved identity"
        reconnectButton.isEnabled = app.loadPairedState() != null
        reconnectButton.setOnClickListener { authenticateRestored(app) }

        // ADR-0006: the workbench is app-owned and starts from packaged assets
        // with no guest and no network. Pairing only adds guest IDE services.
        val offlineButton = Button(this)
        offlineButton.text = "Open workbench (offline, no guest)"
        offlineButton.setOnClickListener {
            startActivity(Intent(this, WorkbenchActivity::class.java))
        }

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
        root.addView(reconnectButton)
        root.addView(offlineButton)
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
        if (app.connectionState == ConnectionState.CONNECTED) {
            openWorkbench()
            return
        }
        if (app.restoreFromPersisted()) {
            log("restored pairing (fingerprint ${app.serverFingerprint}); authenticating to the daemon …")
            authenticateRestored(app)
        }
    }

    /**
     * Authenticated reconnect (S02.01): the stored identity must obtain a
     * session whose serverProof verifies against the key pinned at enrollment,
     * and the channel hello must carry that same fingerprint. CONNECTED is set
     * only after the daemon proves its identity; failures stay on onboarding.
     */
    private fun authenticateRestored(app: GbCodeApp) {
        app.setState(ConnectionState.CONNECTING)
        executor.execute {
            try {
                val state = app.loadPairedState()
                    ?: throw IllegalStateException("no paired identity stored")
                val seed = state.seedHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                val client = SessionClient(state.daemonBase, Ed25519.fromSeed(seed))
                val session = client.session(state.appHash, state.serverPublicKey)
                val hello = client.channelHello(session)
                // channelHello only returns an ok:true hello that carries a
                // fingerprint; comparing is mandatory, not optional.
                val helloFingerprint = hello.getJSONObject("payload").getString("fingerprint")
                if (helloFingerprint != state.fingerprint) {
                    throw SecurityException("daemon identity changed since pairing")
                }
                runOnUiThread {
                    app.setState(ConnectionState.CONNECTED, state.fingerprint)
                    log("reconnected; daemon proof verified (fingerprint ${state.fingerprint})")
                    openWorkbench()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    app.setState(ConnectionState.UNAVAILABLE)
                    log("reconnect failed: ${e.message}")
                    log("stored identity not trusted; the workbench stays closed. Pair and connect again after checking the daemon (a VM reset requires re-pairing).")
                }
            }
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
                val nonceBytes = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
                val nonce = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(nonceBytes)
                val result: JSONObject = client.fullFlow(secret, nonce)
                // fullFlow verified the signed enrollment receipt; the channel
                // hello must agree with the signed fingerprint.
                val fingerprint = result.getString("serverFingerprint")
                // channelHello only returns an ok:true hello that carries a
                // fingerprint; comparing is mandatory, not optional.
                val helloFingerprint = result.getJSONObject("hello")
                    .getJSONObject("payload").getString("fingerprint")
                if (helloFingerprint != fingerprint) {
                    throw SecurityException("channel fingerprint differs from signed receipt")
                }
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
                        serverPublicKey = result.getString("serverPublicKey"),
                        backendPassword = passwordField.text.toString().ifEmpty { null },
                        guestFolder = if (dev.gbcode.app.BuildConfig.DEBUG) {
                            intent.getStringExtra("folder")
                        } else null,
                        backendKind = if (dev.gbcode.app.BuildConfig.DEBUG) {
                            intent.getStringExtra("kind") ?: "code-server"
                        } else "code-server",
                    ))
                    app.setState(ConnectionState.CONNECTED)
                    log("paired; enrollment receipt verified; server fingerprint:\n$fingerprint")
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
