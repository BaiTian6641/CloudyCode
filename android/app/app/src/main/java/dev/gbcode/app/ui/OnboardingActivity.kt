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

        setContentView(dev.gbcode.app.R.layout.activity_onboarding)

        daemonField = findViewById(dev.gbcode.app.R.id.daemonField)
        workbenchField = findViewById(dev.gbcode.app.R.id.workbenchField)
        secretField = findViewById(dev.gbcode.app.R.id.secretField)
        passwordField = findViewById(dev.gbcode.app.R.id.passwordField)
        logView = findViewById(dev.gbcode.app.R.id.logView)

        findViewById<Button>(dev.gbcode.app.R.id.pairButton)
            .setOnClickListener { startPairing(app) }
        val reconnectButton = findViewById<Button>(dev.gbcode.app.R.id.reconnectButton)
        reconnectButton.isEnabled = app.loadPairedState() != null
        reconnectButton.setOnClickListener { authenticateRestored(app) }
        findViewById<Button>(dev.gbcode.app.R.id.offlineButton).setOnClickListener {
            startActivity(Intent(this, WorkbenchActivity::class.java))
        }

        // Productive column: cap the text measure on wide windows.
        val content = findViewById<LinearLayout>(dev.gbcode.app.R.id.content)
        content.post {
            val maxContent = (560 * resources.displayMetrics.density).toInt()
            val pad = maxOf((48 * resources.displayMetrics.density).toInt(),
                (content.width - maxContent) / 2)
            content.setPadding(pad, content.paddingTop, pad, content.paddingBottom)
        }

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
                // Startup orchestration: ask the daemon (inside the Linux
                // environment) to start the IDE backend before the workbench
                // opens. Best-effort: the workbench is app-owned, so a failure
                // only means no guest services on this launch.
                val ensureState = try {
                    client.backendEnsure(session).optString("state", "unknown")
                } catch (e: Exception) {
                    log("backend ensure failed: ${e.message}")
                    "failed"
                }
                runOnUiThread {
                    app.setState(ConnectionState.CONNECTED, state.fingerprint)
                    log("reconnected; daemon proof verified (fingerprint ${state.fingerprint}); backend: $ensureState")
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
                // Startup orchestration (same as reconnect): start the backend
                // in the Linux environment through the freshly authenticated
                // channel before the workbench opens.
                val ensureState = try {
                    client.backendEnsure(result.getString("session")).optString("state", "unknown")
                } catch (e: Exception) {
                    log("backend ensure failed: ${e.message}")
                    "failed"
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
                    log("paired; enrollment receipt verified; backend: $ensureState; server fingerprint:\n$fingerprint")
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
