package dev.cloudy.x11

import android.app.Application
import java.security.SecureRandom

/**
 * Standalone Cloudy-X11 application.
 *
 * The X11 token is a per-install secret shared with the VM setup command. It is
 * app-private; unlike the GBCode prototype it does not depend on the GBCode
 * connection credential store.
 */
class CloudyX11App : Application() {
    fun x11Token(): String {
        val prefs = getSharedPreferences("cloudy_x11", MODE_PRIVATE)
        prefs.getString("token", null)?.let { return it }
        val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
        val token = bytes.joinToString("") { "%02x".format(it) }
        prefs.edit().putString("token", token).apply()
        return token
    }
}
