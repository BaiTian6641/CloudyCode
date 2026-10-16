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

    /** Persisted pairing identity (S02.01 production: seed moves to Keystore). */
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
        prefs().edit()
            .putString("seed", state.seedHex)
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
        val seed = p.getString("seed", null) ?: return null
        val appHash = p.getString("appHash", null) ?: return null
        val daemon = p.getString("daemon", null) ?: return null
        val workbench = p.getString("workbench", null) ?: return null
        val fp = p.getString("fingerprint", "") ?: ""
        return PairedState(seed, appHash, daemon, workbench, fp,
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
