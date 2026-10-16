package dev.gbcode.app

import android.app.Application
import android.content.Context
import java.util.concurrent.CopyOnWriteArrayList

/** Connection state machine for the backend session. */
enum class ConnectionState {
    NOT_CONFIGURED, PAIRING, PAIRED, CONNECTING, CONNECTED, UNAVAILABLE, ERROR
}

class GbCodeApp : Application() {
    @Volatile
    var connectionState: ConnectionState = ConnectionState.NOT_CONFIGURED
        private set

    @Volatile
    var serverFingerprint: String? = null
        private set

    @Volatile
    var backendBaseUrl: String? = null

    /** Backend credential held natively; never exposed to page JavaScript. */
    @Volatile
    var backendPassword: String? = null

    private val listeners = CopyOnWriteArrayList<(ConnectionState) -> Unit>()

    fun addStateListener(listener: (ConnectionState) -> Unit) {
        listeners.add(listener)
    }

    fun setState(next: ConnectionState, fingerprint: String? = null) {
        connectionState = next
        if (fingerprint != null) serverFingerprint = fingerprint
        for (listener in listeners) listener(next)
    }

    /** Persisted pairing identity; the seed is Keystore-encrypted at rest (S02.01). */
    data class PairedState(
        val seedHex: String,
        val appHash: String,
        val daemonBase: String,
        val workbenchBase: String,
        val fingerprint: String,
        val backendPassword: String? = null,
    )

    private fun prefs() = getSharedPreferences("gbcode", Context.MODE_PRIVATE)

    fun savePairedState(state: PairedState) {
        // Encrypt the Ed25519 seed with the Android Keystore AES-GCM key;
        // plaintext never touches persistent storage.
        val seedBytes = state.seedHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val seedEncHex = dev.gbcode.app.connection.CredentialStore.encrypt(seedBytes)
            .joinToString("") { "%02x".format(it) }
        prefs().edit()
            .putString("seedEnc", seedEncHex)
            .putString("appHash", state.appHash)
            .putString("daemon", state.daemonBase)
            .putString("workbench", state.workbenchBase)
            .putString("fingerprint", state.fingerprint)
            .putString("backendPassword", state.backendPassword)
            .apply()
        backendPassword = state.backendPassword
    }

    fun loadPairedState(): PairedState? {
        val p = prefs()
        val seedEnc = p.getString("seedEnc", null) ?: return null
        val appHash = p.getString("appHash", null) ?: return null
        val daemon = p.getString("daemon", null) ?: return null
        val workbench = p.getString("workbench", null) ?: return null
        val fp = p.getString("fingerprint", "") ?: ""
        val seedBytes = dev.gbcode.app.connection.CredentialStore.decrypt(
            seedEnc.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
        val seedHex = seedBytes.joinToString("") { "%02x".format(it) }
        return PairedState(seedHex, appHash, daemon, workbench, fp,
            p.getString("backendPassword", null))
    }

    fun clearPairedState() {
        prefs().edit().clear().apply()
        backendPassword = null
    }

    fun restoreFromPersisted(): Boolean {
        val saved = loadPairedState() ?: return false
        serverFingerprint = saved.fingerprint
        backendBaseUrl = saved.workbenchBase
        backendPassword = saved.backendPassword
        connectionState = ConnectionState.PAIRED
        return true
    }
}
