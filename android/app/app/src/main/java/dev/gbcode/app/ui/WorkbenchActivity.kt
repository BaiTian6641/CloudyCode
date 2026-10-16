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

        webView!!.loadUrl("$origin/")
    }

    override fun onDestroy() {
        webView?.destroy()
        gateway?.close()
        super.onDestroy()
    }
}
