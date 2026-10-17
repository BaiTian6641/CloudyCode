package dev.gbcode.app.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.content.ClipData
import android.content.Intent
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import dev.gbcode.app.BuildConfig
import dev.gbcode.app.GbCodeApp
import dev.gbcode.app.bridge.NativeBridge
import dev.gbcode.app.connection.Gateway
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors

/**
 * The workbench host: starts the app-local gateway to the paired backend and
 * loads the original Code - OSS workbench in a WebView. Native integration is
 * limited to the origin-scoped bridge; the editor is upstream, not a clone.
 */
class WorkbenchActivity : Activity() {
    private var gateway: Gateway? = null
    private var webView: WebView? = null
    private var bridge: NativeBridge? = null
    private var overlay: ConnectionOverlay? = null

    // Backend health: probe the loopback upstream directly (the gateway may
    // still answer from packaged assets in installed-frontend mode).
    private var probeHost: String? = null
    private var probePort = 0
    private val monitor = Handler(Looper.getMainLooper())
    private val probeExec = Executors.newSingleThreadExecutor { r ->
        Thread(r, "cloudy-backend-monitor").apply { isDaemon = true }
    }
    private var monitorRunning = false
    private var probeInFlight = false
    private var failures = 0
    private var downSince = 0L
    private var pageErrored = false
    private var origin: String? = null

    // Auto-detection: attach to a backend that appears while the workbench is
    // local, switch backends when the current one is down, and offer the
    // preferred backend non-blockingly while another one is healthy.
    private var mode = MODE_LOCAL
    private var discoveryRunning = false
    private var bannerShown = false
    private var stayOffline = false
    private var attachTarget: Int? = null
    private var banner: BackendBanner? = null
    private val discoveryExec = Executors.newSingleThreadExecutor { r ->
        Thread(r, "cloudy-backend-discovery").apply { isDaemon = true }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as GbCodeApp

        var upstreamHost: String? = null
        var upstreamPort = 0
        val backend = app.backendBaseUrl
        if (backend != null) {
            val upstream = java.net.URI(backend)
            val scheme = upstream.scheme?.lowercase()
            val host = upstream.host ?: "127.0.0.1"
            val port = when {
                upstream.port > 0 -> upstream.port
                scheme == "https" -> 443
                else -> 80
            }
            // Fail closed: only http loopback backends. A remote or https backend
            // would put the login credential on a plaintext path or silently
            // downgrade; that needs a reviewed design, not a guess (S02.03).
            if (scheme != "http" || !Gateway.isLoopbackHost(host)) {
                val msg = "backend URL rejected: only http loopback backends are supported (got $backend)"
                android.util.Log.e("GBCodeWorkbench", msg)
                val errorView = android.widget.TextView(this)
                errorView.text = "Cloudy Code: $msg"
                errorView.setPadding(48, 96, 48, 48)
                setContentView(errorView)
                return
            }
            upstreamHost = host
            upstreamPort = port
            probeHost = host
            probePort = port
        }

        // ADR-0006: only the installed-frontend + pristine reh-web guest sets a
        // remote authority (the app-owned page connects to the guest's remote
        // agent through the gateway). The code-server baseline keeps its
        // guest-hosted page semantics: the gateway proxies the guest page and
        // the native login hands the WebView only the session cookie.
        val installedFrontend = app.backendKind == "reh-web"
        val proxyMode = !installedFrontend && upstreamHost != null
        // App-owned assets are only needed for the installed frontend. In
        // code-server mode the guest serves the whole page through the gateway.
        val webAssets = if (proxyMode) null else dev.gbcode.app.web.WebAssets(assets)
        val template = webAssets?.readText("out/vs/code/browser/workbench/workbench.html")
        if (!proxyMode && template == null) {
            val msg = "packaged workbench assets missing (run tools/release/package-web-assets.sh)"
            android.util.Log.e("GBCodeWorkbench", msg)
            val errorView = android.widget.TextView(this)
            errorView.text = "Cloudy Code: $msg"
            errorView.setPadding(48, 96, 48, 48)
            setContentView(errorView)
            return
        }
        if (proxyMode) {
            gateway = Gateway(upstreamHost, upstreamPort, null)
                .also { it.start(PREFERRED_GATEWAY_PORT) }
        } else {
            val pageTemplate = template!!
            gateway = Gateway(upstreamHost, upstreamPort, webAssets) { nonce ->
                val authority = if (upstreamHost != null && installedFrontend) {
                    "127.0.0.1:${gateway?.port ?: PREFERRED_GATEWAY_PORT}"
                } else null
                renderWorkbench(
                    pageTemplate,
                authority,
                    if (authority != null) app.backendPassword else null,
                    if (authority != null) app.guestFolder else null,
                    "http://127.0.0.1:${gateway?.port ?: PREFERRED_GATEWAY_PORT}/out/vs/workbench/contrib/webview/browser/pre/",
                    nonce,
                )
            }.also { it.start(PREFERRED_GATEWAY_PORT) }
        }
        val origin = "http://127.0.0.1:${gateway!!.port}"
        this.origin = origin
        mode = when {
            upstreamHost == null -> MODE_LOCAL
            installedFrontend -> MODE_REH
            else -> MODE_CS
        }
        val attaching = intent.getBooleanExtra(EXTRA_ATTACHING, false)

        if (BuildConfig.DEBUG) {
            // Debug-only: allow CDP inspection of the workbench WebView for
            // diagnostics and conformance runs; never enabled in release.
            WebView.setWebContentsDebuggingEnabled(true)
        }

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
                if (attaching) overlay?.hide()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest,
                                         error: WebResourceError) {
                if (!request.isForMainFrame) return
                // Never show the engine's error page (it exposes an empty
                // response and breaks the shell). Blank it and hand over to
                // the Cloudy connection surface.
                pageErrored = true
                view.stopLoading()
                view.loadUrl("about:blank")
                onBackendLost(immediate = true)
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest,
                                             errorResponse: WebResourceResponse) {
                if (!request.isForMainFrame) return
                if (errorResponse.statusCode < 500) return
                pageErrored = true
                view.stopLoading()
                view.loadUrl("about:blank")
                onBackendLost(immediate = true)
            }
        }

        val root = FrameLayout(this)
        root.addView(webView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
        banner = BackendBanner(this, root).apply {
            onSwitch = { scheduleAttach(REH_WEB_PORT, "reh-web") }
            onDismiss = { }
        }
        overlay = ConnectionOverlay(this, root).apply {
            onRetry = { retryNow() }
            onCancelAttach = { cancelAttach() }
            onOpenTerminal = { openTerminalApp() }
            onCopyCommand = { copySetupCommand() }
            onAdvanced = { startActivity(Intent(this@WorkbenchActivity, OnboardingActivity::class.java)) }
            onOffline = { openOfflineWorkbench() }
        }

        if (attaching) overlay?.showAttaching("Linux workspace")

        // The backend credential stays native: we log in through the gateway
        // from app code and hand the WebView only the session cookie(s).
        val password = app.backendPassword
        if (password != null && upstreamHost != null && !installedFrontend) {
            Thread {
                try {
                    val installed = nativeLogin(origin, password)
                    if (installed == 0) {
                        android.util.Log.w("GBCodeWorkbench", "no backend cookie installed; the workbench may require re-login")
                    }
                } catch (e: Exception) {
                    android.util.Log.w("GBCodeWorkbench", "native login failed; loading anyway", e)
                }
                runOnUiThread { webView!!.loadUrl("$origin/") }
            }.start()
        } else {
            webView!!.loadUrl("$origin/")
        }
    }

    override fun onResume() {
        super.onResume()
        startMonitor()
        startDiscovery()
        maybeStartUsbForwarding()
    }

    override fun onPause() {
        monitorRunning = false
        discoveryRunning = false
        monitor.removeCallbacks(monitorRunnable)
        monitor.removeCallbacks(discoveryRunnable)
        monitor.removeCallbacks(attachRunnable)
        super.onPause()
    }

    private fun startMonitor() {
        if (probeHost == null || probePort <= 0) return
        if (monitorRunning) return
        monitorRunning = true
        monitor.postDelayed(monitorRunnable, 2500)
    }

    private val monitorRunnable: Runnable = object : Runnable {
        override fun run() {
            if (!monitorRunning) return
            val host = probeHost ?: return
            if (probeInFlight) {
                monitor.postDelayed(monitorRunnable, 800)
                return
            }
            probeInFlight = true
            probeExec.execute {
                val up = portOpen(host, probePort)
                runOnUiThread {
                    probeInFlight = false
                    if (!monitorRunning) return@runOnUiThread
                    if (up) onBackendUp() else onBackendDown()
                    monitor.postDelayed(monitorRunnable, if (overlay?.isVisible == true) 1500 else 3000)
                }
            }
        }
    }

    private fun onBackendDown() {
        failures++
        if (failures < 2) return
        if (downSince == 0L) downSince = System.currentTimeMillis()
        val elapsed = System.currentTimeMillis() - downSince
        if (elapsed >= UNAVAILABLE_AFTER_MS) {
            overlay?.showUnavailable(UNAVAILABLE_DETAIL)
        } else {
            overlay?.showReconnecting()
        }
    }

    private fun onBackendUp() {
        failures = 0
        downSince = 0L
        val o = overlay ?: return
        if (!o.isVisible) return
        o.hide()
        if (pageErrored) {
            pageErrored = false
            origin?.let { webView?.loadUrl("$it/") }
        }
    }

    /** Main-frame load failed (or the upstream vanished): show the surface now. */
    private fun onBackendLost(immediate: Boolean) {
        failures = maxOf(failures, if (immediate) 2 else 1)
        if (downSince == 0L) downSince = System.currentTimeMillis()
        overlay?.showReconnecting("The Linux workspace stopped answering. Retrying " +
            "automatically.")
        monitor.removeCallbacks(monitorRunnable)
        if (monitorRunning) monitor.postDelayed(monitorRunnable, 400)
    }

    private fun retryNow() {
        failures = 0
        downSince = System.currentTimeMillis()
        overlay?.showReconnecting("Retrying the connection…")
        monitor.removeCallbacks(monitorRunnable)
        if (monitorRunning) monitor.post(monitorRunnable)
    }

    private fun openTerminalApp() {
        val launch = packageManager.getLaunchIntentForPackage("com.android.virtualization.terminal")
        if (launch == null) {
            overlay?.setDetail("The Googlebook Terminal app is not installed.")
            return
        }
        overlay?.setDetail(
            "Keep the Terminal app running: it provides the VM port bridge this window " +
                "uses. The workspace reconnects automatically.")
        try {
            startActivity(launch)
        } catch (e: Exception) {
            overlay?.setDetail("Could not open the Terminal app: ${e.message}")
        }
    }

    private fun copySetupCommand() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Cloudy Code setup", GuestSetup.COMMAND))
        overlay?.setDetail("Command copied. Run it in the Terminal if the server stopped.")
    }

    private fun openOfflineWorkbench() {
        val app = application as GbCodeApp
        app.backendBaseUrl = null
        app.backendKind = "reh-web"
        app.guestFolder = null
        recreate()
    }

    private fun portOpen(host: String, port: Int): Boolean {
        return try {
            Socket().use { it.connect(InetSocketAddress(host, port), 800) }
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Keep the USB/IP exporter running while the device is present. */
    private fun maybeStartUsbForwarding() {
        try {
            if (dev.gbcode.app.SerialBridgeService.state == "connected") return
            val usb = getSystemService(Context.USB_SERVICE) as android.hardware.usb.UsbManager
            val device = usb.deviceList.values.firstOrNull { it.vendorId == 0x303A } ?: return
            if (!usb.hasPermission(device)) return
            val app = application as GbCodeApp
            startForegroundService(Intent(this, dev.gbcode.app.UsbIpService::class.java)
                .setAction(dev.gbcode.app.UsbIpService.ACTION_START)
                .putExtra(dev.gbcode.app.UsbIpService.EXTRA_TOKEN, app.connectionToken()))
        } catch (e: Exception) {
            android.util.Log.w("CloudyUsbIp", "auto-start failed: ${e.message}")
        }
    }

    private fun startDiscovery() {
        if (discoveryRunning) return
        discoveryRunning = true
        monitor.postDelayed(discoveryRunnable, 1500)
    }

    private val discoveryRunnable: Runnable = object : Runnable {
        override fun run() {
            if (!discoveryRunning) return
            discoveryExec.execute {
                val rehUp = portOpen("127.0.0.1", REH_WEB_PORT)
                val csUp = portOpen("127.0.0.1", CODE_SERVER_PORT)
                runOnUiThread {
                    if (!discoveryRunning) return@runOnUiThread
                    if (attachTarget == null) {
                        when {
                            mode == MODE_LOCAL && !stayOffline && rehUp ->
                                scheduleAttach(REH_WEB_PORT, "reh-web")
                            mode == MODE_LOCAL && !stayOffline && csUp ->
                                scheduleAttach(CODE_SERVER_PORT, "code-server")
                            mode == MODE_CS && rehUp && !bannerShown -> {
                                bannerShown = true
                                banner?.show("Cloudy Code backend available (recommended)")
                            }
                            mode == MODE_REH && !rehUp && csUp ->
                                scheduleAttach(CODE_SERVER_PORT, "code-server")
                            mode == MODE_CS && !csUp && rehUp ->
                                scheduleAttach(REH_WEB_PORT, "reh-web")
                        }
                    }
                    monitor.postDelayed(this, if (overlay?.isVisible == true) 2000 else 4000)
                }
            }
        }
    }

    private fun scheduleAttach(port: Int, kind: String) {
        attachTarget = port
        overlay?.showAttaching(if (kind == "reh-web") "Cloudy Code" else "code-server")
        monitor.postDelayed(attachRunnable, 1800)
    }

    private val attachRunnable = Runnable {
        val port = attachTarget ?: return@Runnable
        attachTo(port, if (port == REH_WEB_PORT) "reh-web" else "code-server")
    }

    private fun attachTo(port: Int, kind: String) {
        val app = application as GbCodeApp
        app.backendBaseUrl = "http://127.0.0.1:$port"
        app.backendKind = kind
        app.backendPassword = if (kind == "reh-web") app.connectionToken() else null
        app.guestFolder = null
        app.setState(dev.gbcode.app.ConnectionState.CONNECTED)
        startActivity(Intent(this, WorkbenchActivity::class.java).putExtra(EXTRA_ATTACHING, true))
        finish()
    }

    private fun cancelAttach() {
        attachTarget = null
        stayOffline = true
        monitor.removeCallbacks(attachRunnable)
        overlay?.hide()
    }

    /**
     * POST the backend login form through the gateway and install the returned
     * session cookie(s) with their attributes intact. Returns how many cookie
     * names were found stored afterward. code-server: POST /login with password
     * field -> Set-Cookie session. Cookie values are credentials and are never
     * logged.
     */
    private fun nativeLogin(origin: String, password: String): Int {
        val body = "password=" + java.net.URLEncoder.encode(password, "UTF-8") + "&base="
        val conn = java.net.URL("$origin/login").openConnection() as java.net.HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.instanceFollowRedirects = false
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val cookies = dev.gbcode.app.connection.Cookies.setCookieHeaders(conn)
        conn.disconnect()

        val manager = android.webkit.CookieManager.getInstance()
        // CookieManager's callback overload needs a Looper; install on the UI
        // thread and wait so the cookie is in place before the page loads.
        val latch = java.util.concurrent.CountDownLatch(1)
        runOnUiThread {
            for (raw in cookies) manager.setCookie(origin, raw)
            manager.flush()
            latch.countDown()
        }
        latch.await(3, java.util.concurrent.TimeUnit.SECONDS)
        val stored = manager.getCookie(origin) ?: ""
        val names = cookies.map { it.substringBefore("=").trim() }
        val installed = names.count { stored.contains("$it=") }
        android.util.Log.i("GBCodeWorkbench", "native login HTTP $code, set-cookie=${cookies.size}, stored=$installed names=$names")
        return installed
    }
    /**
     * Render the upstream workbench template with the app configuration
     * (`WorkbenchPage.renderWorkbench`, the client-side counterpart of the
     * pinned commit's webClientServer.renderWorkbench).
     */
    private fun renderWorkbench(template: String, remoteAuthority: String?,
                                connectionToken: String?, folderPath: String?,
                                webviewEndpoint: String?, nonce: String): String {
        return dev.gbcode.app.web.WorkbenchPage.renderWorkbench(
            template, remoteAuthority, connectionToken, folderPath, webviewEndpoint, nonce)
    }

    override fun onDestroy() {
        monitorRunning = false
        discoveryRunning = false
        monitor.removeCallbacks(monitorRunnable)
        monitor.removeCallbacks(discoveryRunnable)
        monitor.removeCallbacks(attachRunnable)
        discoveryExec.shutdownNow()
        probeExec.shutdownNow()
        webView?.destroy()
        gateway?.close()
        super.onDestroy()
    }

    private companion object {
        // Browser storage (settings, editor state, service worker caches) is keyed
        // by scheme+host+port. A stable loopback port keeps that origin across
        // process restarts; Gateway falls back to an ephemeral port (and logs it)
        // if another process already owns this one — it never routes through it.
        const val PREFERRED_GATEWAY_PORT = 41873
        const val REH_WEB_PORT = 8085
        const val CODE_SERVER_PORT = 8080
        const val MODE_LOCAL = 0
        const val MODE_REH = 1
        const val MODE_CS = 2
        const val EXTRA_ATTACHING = "attaching"

        /** Sustained outage before recovery actions replace the progress state. */
        const val UNAVAILABLE_AFTER_MS = 15_000L
        const val UNAVAILABLE_DETAIL =
            "The Linux workspace is not answering. The Terminal app provides the VM port " +
                "bridge: keep it running (or reopen it). If the VM restarted, start the " +
                "server again with the setup command, then retry."
    }
}
