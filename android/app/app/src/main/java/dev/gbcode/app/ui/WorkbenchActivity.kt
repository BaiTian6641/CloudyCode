package dev.gbcode.app.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import dev.gbcode.app.GbCodeApp
import dev.gbcode.app.bridge.NativeBridge
import dev.gbcode.app.connection.Gateway

/**
 * The workbench host: starts the app-local gateway to the paired backend and
 * loads the original Code - OSS workbench in a WebView. Native integration is
 * limited to the origin-scoped bridge; the editor is upstream, not a clone.
 */
class WorkbenchActivity : Activity() {
    private var gateway: Gateway? = null
    private var webView: WebView? = null
    private var bridge: NativeBridge? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as GbCodeApp
        val backend = app.backendBaseUrl
        if (backend == null) {
            finish() // not paired; onboarding owns this state
            return
        }

        val upstream = java.net.URI(backend)
        val upstreamHost = upstream.host ?: "127.0.0.1"
        val upstreamPort = if (upstream.port > 0) upstream.port else 80

        gateway = Gateway(upstreamHost, upstreamPort).also { it.start() }
        val origin = "http://127.0.0.1:${gateway!!.port}"

        webView = WebView(this)
        val settings = webView!!.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true

        bridge = NativeBridge(origin) { method, payload, sourceOrigin ->
            android.util.Log.i("GBCodeBridge", "method=$method origin=$sourceOrigin payload=$payload")
        }
        bridge!!.attach(webView!!)

        webView!!.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                android.util.Log.i("GBCodeWorkbench", "page finished: $url")
            }
        }

        val root = FrameLayout(this)
        root.addView(webView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)

        // The backend credential stays native: we log in through the gateway
        // from app code and hand the WebView only the session cookie.
        val password = app.backendPassword
        if (password != null) {
            Thread {
                try {
                    val cookie = nativeLogin(origin, password)
                    if (cookie != null) {
                        val manager = android.webkit.CookieManager.getInstance()
                        manager.setCookie(origin, cookie)
                        manager.flush()
                        android.util.Log.i("GBCodeWorkbench", "backend session cookie installed natively")
                    }
                    runOnUiThread { webView!!.loadUrl("$origin/") }
                } catch (e: Exception) {
                    android.util.Log.w("GBCodeWorkbench", "native login failed; loading anyway", e)
                    runOnUiThread { webView!!.loadUrl("$origin/") }
                }
            }.start()
        } else {
            webView!!.loadUrl("$origin/")
        }
    }


    /**
     * POST the backend login form through the gateway and return the session
     * cookie header value, or null when the backend has no password auth.
     * code-server: POST /login with password field -> Set-Cookie session.
     */
    private fun nativeLogin(origin: String, password: String): String? {
        val body = "password=" + java.net.URLEncoder.encode(password, "UTF-8") + "&base="
        val conn = java.net.URL("$origin/login").openConnection() as java.net.HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.instanceFollowRedirects = false
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val cookie = conn.getHeaderField("Set-Cookie")?.substringBefore(";")
        conn.disconnect()
        android.util.Log.i("GBCodeWorkbench", "native login HTTP $code, cookie=${cookie != null}")
        return cookie
    }
    override fun onDestroy() {
        webView?.destroy()
        gateway?.close()
        super.onDestroy()
    }
}
