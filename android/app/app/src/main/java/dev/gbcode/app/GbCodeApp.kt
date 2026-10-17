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
        val serverPublicKey: String,
        val backendPassword: String? = null,
    )

    private fun prefs() = getSharedPreferences("gbcode", Context.MODE_PRIVATE)

    private val passwordAlias = "gbcode-backend-password"

    private fun hexOf(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    private fun bytesOf(hex: String) = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    fun savePairedState(state: PairedState) {
        // Secrets (pairing seed, backend password) are Keystore-encrypted;
        // plaintext never touches persistent storage.
        val seedEncHex = hexOf(dev.gbcode.app.connection.CredentialStore.encrypt(bytesOf(state.seedHex)))
        val passwordEncHex = state.backendPassword?.let {
            hexOf(dev.gbcode.app.connection.CredentialStore.encrypt(
                passwordAlias, it.toByteArray(Charsets.UTF_8)))
        }
        prefs().edit()
            .putString("seedEnc", seedEncHex)
            .putString("appHash", state.appHash)
            .putString("daemon", state.daemonBase)
            .putString("workbench", state.workbenchBase)
            .putString("fingerprint", state.fingerprint)
            .putString("serverPublicKey", state.serverPublicKey)
            .putString("passwordEnc", passwordEncHex)
            .remove("backendPassword") // legacy plaintext key must never survive
            .apply()
        backendPassword = state.backendPassword
    }

    fun loadPairedState(): PairedState? {
        val p = prefs()
        // Migrate/scrub legacy plaintext first: pre-M1 installs have a
        // plaintext password and may lack newer fields (serverPublicKey), so
        // the scrub must not depend on any later early return.
        val password = loadPassword(p)
        val seedEnc = p.getString("seedEnc", null) ?: return null
        val appHash = p.getString("appHash", null) ?: return null
        val daemon = p.getString("daemon", null) ?: return null
        val workbench = p.getString("workbench", null) ?: return null
        val fp = p.getString("fingerprint", "") ?: ""
        val serverPublicKey = p.getString("serverPublicKey", null) ?: return null
        val seedBytes = dev.gbcode.app.connection.CredentialStore.decrypt(bytesOf(seedEnc))
        val seedHex = hexOf(seedBytes)
        return PairedState(seedHex, appHash, daemon, workbench, fp,
            serverPublicKey, password)
    }

    /**
     * Decrypt the stored backend password; a legacy plaintext value (written
     * before S02.03) is migrated once and removed from disk.
     */
    private fun loadPassword(p: android.content.SharedPreferences): String? {
        val enc = p.getString("passwordEnc", null)
        if (enc != null) {
            return String(
                dev.gbcode.app.connection.CredentialStore.decrypt(passwordAlias, bytesOf(enc)),
                Charsets.UTF_8)
        }
        val legacy = p.getString("backendPassword", null) ?: return null
        prefs().edit()
            .putString("passwordEnc", hexOf(dev.gbcode.app.connection.CredentialStore.encrypt(
                passwordAlias, legacy.toByteArray(Charsets.UTF_8))))
            .remove("backendPassword")
            .apply()
        return legacy
    }

    fun clearPairedState() {
        prefs().edit().clear().apply()
        backendPassword = null
        // The backend session cookie is a credential too: it must not outlive
        // the pairing state (S02.03).
        val manager = android.webkit.CookieManager.getInstance()
        manager.removeAllCookies(null)
        manager.flush()
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
