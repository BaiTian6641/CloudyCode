package dev.cloudy.x11.ui

import android.app.Activity
import android.app.ActivityManager
import android.content.res.Configuration
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.util.Log
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import dev.cloudy.x11.CloudyX11App
import dev.cloudy.x11.R
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Built-in X11 forwarding window.
 *
 * The VM runs xpra (seamless X11) loopback-only plus `cloudy-x11-proxy.py`,
 * which listens on the forwarded port 14500 behind a per-install token:
 *   - the app sets `cloudy_x11=<token>` in a dedicated WebView profile;
 *   - readiness is an HMAC challenge (`/__cloudy/ping`), not a bare TCP
 *     connect, so a squatter on 14500 cannot impersonate the server;
 *   - the WebView only loads the local origin after the challenge passes,
 *     runs in an isolated cookie/DOM-storage profile (no workbench session
 *     cookie), has no JS bridge/file access and drops foreign navigations.
 */
class X11Activity : Activity() {
    private lateinit var setupView: View
    private lateinit var webView: WebView
    private lateinit var status: TextView
    private lateinit var detail: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val probe = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "cloudy-x11-probe").apply { isDaemon = true }
    }
    private var polling = false
    private var loaded = false
    private var downCount = 0
    private var profileReady = false

    private val port: Int by lazy { intent.getIntExtra("port", DEFAULT_PORT) }
    private val sessionId: String? by lazy { intent.getStringExtra("sid") }
    private val sessionName: String? by lazy { intent.getStringExtra("name") }
    private val windowTitle: String? by lazy { intent.getStringExtra("name") }
    private val windowId: Int? by lazy {
        if (intent.hasExtra("winId")) intent.getIntExtra("winId", 0) else null
    }
    private val forceSetup: Boolean by lazy { intent.getBooleanExtra("setup", false) }
    private val origin: String by lazy { "http://$HOST:$port" }
    private var lastWidth = 0
    private fun scheduleReload() {
        if (!loaded || sessionId == null) return
        handler.removeCallbacks(reloadRunnable)
        handler.postDelayed(reloadRunnable, 500)
    }

    private val reloadRunnable = Runnable {
        if (!loaded || sessionId == null) return@Runnable
        val w = if (webView.width > 0) webView.width
            else getSystemService(WindowManager::class.java).currentWindowMetrics.bounds.width()
        if (w != lastWidth) {
            windowId?.let { id ->
                val hh = webView.height.coerceAtLeast(240)
                Thread {
                    try {
                        control("/resize",
                            "{\"id\":$id,\"w\":$w,\"h\":$hh}")
                    } catch (e: Exception) {
                        Log.w(TAG, "window resize failed: ${e.message}")
                    }
                }.apply { isDaemon = true }.start()
            }
            loaded = false
            webView.stopLoading()
            webView.settings.blockNetworkLoads = true
            handler.removeCallbacks(probeRunnable)
            handler.post(probeRunnable)
        }
    }

    private val token: String
        get() = (application as CloudyX11App).x11Token()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val title = windowTitle ?: sessionName ?: "Cloudy X11"
        setTitle(title)
        setTaskDescription(ActivityManager.TaskDescription(title))
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        buildUi()
        startPolling()
    }

    private fun buildUi() {
        val root = android.widget.FrameLayout(this).apply {
            setBackgroundColor(getColor(R.color.cw_canvas))
        }
        setupView = buildSetup()
        webView = WebView(this)
        webView.addOnLayoutChangeListener { _, l, _, r, _, ol, _, or, _ ->
            if (loaded && r - l != or - ol && r - l > 0) scheduleReload()
        }
        profileReady = applyIsolatedProfile(webView)
        webView.apply {
            visibility = View.GONE
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.allowFileAccessFromFileURLs = false
            settings.allowUniversalAccessFromFileURLs = false
            settings.blockNetworkLoads = true
            settings.setSupportZoom(false)
            settings.builtInZoomControls = false
            settings.displayZoomControls = false
            settings.textZoom = 100
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val uri = request.url
                    val allowed = uri.scheme == "http" && uri.host == HOST && uri.port == port
                    if (!allowed) Log.w(TAG, "blocked navigation: $uri")
                    return !allowed
                }

                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest):
                        android.webkit.WebResourceResponse? {
                    val uri = request.url
                    return if (uri.scheme == "http" && uri.host == HOST && uri.port == port) null
                    else android.webkit.WebResourceResponse("text/plain", "utf-8",
                        java.io.ByteArrayInputStream(ByteArray(0)))
                }

                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest,
                                                 response: android.webkit.WebResourceResponse) {
                    if (response.statusCode == 403) {
                        // Auth failure (e.g. a squatter replaced the proxy): fail closed.
                        loseServer("The X11 session was rejected.")
                    } else if (request.isForMainFrame && response.statusCode >= 500) {
                        loseServer("The X11 server is unavailable (HTTP ${response.statusCode}).")
                    }
                }

                override fun onPageFinished(view: WebView, url: String) {
                    Log.i(TAG, "X11 client loaded: $url")
                    injectClientCss()
                    if (windowId != null) injectWindowFilter()
                    hideIme()
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest,
                                             error: android.webkit.WebResourceError) {
                    if (request.isForMainFrame) {
                        Log.w(TAG, "X11 load failed: ${error.description}")
                        loseServer("X11 client failed to load: ${error.description}")
                    }
                }
            }
        }
        root.addView(webView, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(setupView, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(root)
        if (dev.cloudy.x11.BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
    }

    private fun applyIsolatedProfile(view: WebView): Boolean = try {
        ProfileStore.getInstance().getOrCreateProfile(PROFILE)
        WebViewCompat.setProfile(view, PROFILE)
        val cookies = ProfileStore.getInstance().getProfile(PROFILE)?.cookieManager
        cookies?.setCookie(origin, "$COOKIE=${token}; Path=/; HttpOnly")
        cookies?.flush()
        true
    } catch (e: Throwable) {
        Log.w(TAG, "WebView profiles unavailable; X11 stays disabled: ${e.message}")
        false
    }

    private fun buildSetup(): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(40), dp(48), dp(40))
        }
        val title = TextView(this).apply {
            text = "X11 windows"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 24f)
            setTextColor(getColor(R.color.cw_text_primary))
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        }
        status = TextView(this).apply {
            text = "Looking for the X11 server…"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 20f)
            setTextColor(getColor(R.color.cw_text_primary))
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setPadding(0, dp(24), 0, dp(8))
        }
        detail = TextView(this).apply {
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(getColor(R.color.cw_text_secondary))
            setLineSpacing(dp(4).toFloat(), 1f)
        }
        val copy = Button(this).apply {
            text = "Copy X11 setup command"
            setBackgroundResource(R.drawable.bg_button_primary)
            setTextColor(getColor(R.color.cw_on_primary))
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            minHeight = dp(44)
            setOnClickListener { copySetupCommand() }
        }
        val terminal = Button(this).apply {
            text = "Open Terminal"
            setBackgroundResource(R.drawable.bg_button_secondary)
            setTextColor(getColor(R.color.cw_text_primary))
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            minHeight = dp(44)
            setOnClickListener { openTerminal() }
        }
        val retry = Button(this).apply {
            text = "Retry"
            setBackgroundColor(Color.TRANSPARENT)
            setTextColor(getColor(R.color.cw_accent))
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            setOnClickListener { showSetup("Looking for the X11 server…"); startPolling() }
        }
        column.addView(title)
        column.addView(status)
        column.addView(detail)
        val scroll = android.widget.ScrollView(this).apply { addView(column) }
        column.addView(copy)
        column.addView(terminal)
        column.addView(retry)
        column.post {
            val maxContent = dp(560)
            val pad = maxOf(dp(48), (column.width - maxContent) / 2)
            column.setPadding(pad, dp(40), pad, dp(40))
        }
        return scroll
    }

    private fun startPolling() {
        if (polling) return
        polling = true
        handler.post(probeRunnable)
    }

    private val probeRunnable: Runnable = object : Runnable {
        override fun run() {
            if (!polling) return
            probe.execute {
                val auth = if (profileReady) authenticate() else Auth.UNREACHABLE
                runOnUiThread {
                    if (!polling) return@runOnUiThread
                    if (auth == Auth.OK) {
                        downCount = 0
                        if (!loaded && forceSetup) {
                            showSetup("X11 setup")
                        } else if (!loaded) {
                            setupView.visibility = View.GONE
                            webView.visibility = View.VISIBLE
                            webView.settings.blockNetworkLoads = false
                            webView.requestFocus()
                            val bounds = getSystemService(WindowManager::class.java)
                                .currentWindowMetrics.bounds
                            val width = if (webView.width > 0) webView.width else bounds.width()
                            lastWidth = width
                            val override = when {
                                windowId != null ->
                                    "&override_width=$width&cloudy_w=$width&cloudy_win=${windowId}"
                                sessionId != null ->
                                    "&override_width=$width&cloudy_w=$width"
                                else -> ""
                            }
                            Log.d(TAG, "load port=$port width=$width")
                            webView.loadUrl("$origin/?floating_menu=no&autohide=yes" +
                                "&border=no&video=no&encoding=png${if (windowId != null) "&sharing=yes&steal=no&username=cloudy_$windowId" else ""}$override")
                            loaded = true
                        }
                    } else {
                        if (loaded) loseServer("The X11 endpoint could not be authenticated.")
                        else if (!profileReady) showSetup(
                            "This WebView build lacks isolated profiles, so the X11 window stays " +
                                "disabled for safety.")
                        else if (auth == Auth.MISMATCH) showSetup(
                            "The X11 endpoint is running but rejected this app's token. Re-run " +
                                "the setup from this window (Copy X11 setup command).")
                        else if (auth == Auth.BAD_RESPONSE) showSetup(
                            "The X11 endpoint answered but did not return the challenge. Another " +
                                "service may own port $port; re-run the setup from this window.")
                        else showSetup("Looking for the X11 server…")
                    }
                    handler.postDelayed(this, 2000)
                }
            }
        }
    }

    private enum class Auth { OK, UNREACHABLE, MISMATCH, BAD_RESPONSE }

    private fun authenticate(): Auth = try {
        val nonceBytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val nonce = nonceBytes.joinToString("") { "%02x".format(it) }
        val conn = URL("$origin/__cloudy/ping?nonce=$nonce").openConnection() as HttpURLConnection
        conn.connectTimeout = 1500
        conn.readTimeout = 1500
        if (conn.responseCode != 200) {
            Log.w(TAG, "auth port=$port HTTP ${conn.responseCode}")
            conn.disconnect()
            Auth.BAD_RESPONSE
        } else {
            val body = conn.inputStream.bufferedReader().use { it.readText() }.trim()
            conn.disconnect()
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(token.toByteArray(Charsets.UTF_8), "HmacSHA256"))
            val expected = mac.doFinal(nonce.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            val ok = MessageDigest.isEqual(
                body.toByteArray(Charsets.UTF_8), expected.toByteArray(Charsets.UTF_8))
            Log.d(TAG, "auth port=$port ok=$ok bodyLen=${body.length}")
            if (ok) Auth.OK else Auth.MISMATCH
        }
    } catch (e: java.net.SocketTimeoutException) {
        Auth.UNREACHABLE
    } catch (e: java.net.ConnectException) {
        Auth.UNREACHABLE
    } catch (e: Exception) {
        Log.w(TAG, "auth port=$port failed: ${e.javaClass.simpleName}: ${e.message}")
        Auth.UNREACHABLE
    }

    private fun loseServer(reason: String) {
        loaded = false
        webView.visibility = View.GONE
        webView.settings.blockNetworkLoads = true
        webView.loadUrl("about:blank")
        showSetup(reason)
    }

    private fun showSetup(text: String) {
        setupView.visibility = View.VISIBLE
        status.text = text
        detail.text = "Cloudy X11 needs a one-time setup in the Linux Terminal. " +
            "Tap Copy setup command, paste it into Terminal and run it. " +
            "After it finishes, return here and open any X11 app from Terminal."
    }

    private fun copySetupCommand() {
        val script = assets.open("cloudy-x11-setup.sh").bufferedReader().use { it.readText() }
        val dir = "~/.local/share/cloudy"
        val setupPath = "$dir/cloudy-x11-setup.sh"
        val command = "rm -f ~/cloudy-x11-setup.sh\n" +
            "mkdir -p $dir\n" +
            "cat > $setupPath <<'CLOUDY_X11_EOF'\n$script\nCLOUDY_X11_EOF\n" +
            "bash $setupPath --token $token"
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Cloudy X11 setup", command)
        clip.description.extras = android.os.PersistableBundle().apply {
            putBoolean("android.content.extra.IS_SENSITIVE", true)
        }
        clipboard.setPrimaryClip(clip)
        detail.text = "Setup v3 command copied. Paste it into Terminal and press Enter. " +
            "This one-time setup installs the persistent display service."
    }

    private fun openTerminal() {
        val pkg = "com.android.virtualization.terminal"
        val launch = packageManager.getLaunchIntentForPackage(pkg)
            ?: Intent().setComponent(android.content.ComponentName(
                pkg, "$pkg.new2.ui.MainActivity"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(launch)
        } catch (e: Exception) {
            detail.text = "Could not open Terminal. Launch it from the dock."
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onResume() {
        super.onResume()
        val title = windowTitle ?: sessionName
        title?.let {
            setTitle(it)
            setTaskDescription(ActivityManager.TaskDescription(it))
        }
        hideIme()
        startPolling()
    }

    override fun onPause() {
        polling = false
        handler.removeCallbacks(probeRunnable)
        super.onPause()
    }

    override fun onDestroy() {
        polling = false
        handler.removeCallbacks(probeRunnable)
        if (isFinishing) {
            windowId?.let { id ->
                Thread {
                    try { control("/close", "{\"id\":$id}") }
                    catch (e: Exception) { Log.w(TAG, "window close failed: ${e.message}") }
                }.apply { isDaemon = true }.start()
            } ?: sessionId?.let { sid ->
                Thread {
                    try { stopSession(sid) } catch (e: Exception) {
                        Log.w(TAG, "session stop failed: ${e.message}")
                    }
                }.apply { isDaemon = true }.start()
            }
        }
        probe.shutdownNow()
        webView.stopLoading()
        webView.destroy()
        super.onDestroy()
    }

    private fun injectClientCss() {
        val js = """
            (function(){
              if (document.getElementById('cloudy-css')) return;
              var s=document.createElement('style');
              s.id='cloudy-css';
              s.textContent=
                'html,body{background-image:none!important;background-color:#000!important}'+
                '#top_bar,.toolbar,#toolbar,.floating-menu,.floating_menu,.simple-keyboard{display:none!important}';
              document.documentElement.appendChild(s);
            })()
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun hideIme() {
        val imm = getSystemService(InputMethodManager::class.java)
        imm.hideSoftInputFromWindow(webView.windowToken, 0)
        window.decorView.postDelayed({
            getSystemService(InputMethodManager::class.java)
                .hideSoftInputFromWindow(webView.windowToken, 0)
        }, 500)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        scheduleReload()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideIme()
    }

    private fun injectWindowFilter() {
        val id = windowId ?: return
        val titleJson = JSONObject.quote(windowTitle ?: "")
        val js = """
            (function(){
              const WID = "$id";
              const TITLE = $titleJson;
              function fit(){
                try {
                  if (typeof client === 'undefined' || !client.id_to_window) return;
                  let target = client.id_to_window[WID];
                  if (!target && TITLE) {
                    for (const k in client.id_to_window) {
                      const w = client.id_to_window[k];
                      if (w && w.metadata && w.metadata.title === TITLE) { target = w; break; }
                    }
                  }
                  for (const k in client.id_to_window) {
                    const w = client.id_to_window[k];
                    if (w && w.div) w.div.style.display = (w === target) ? '' : 'none';
                  }
                  if (target && target.div) {
                    target.div.style.left = '0px';
                    target.div.style.top = '0px';
                  }
                  const scr = document.getElementById('screen');
                  if (scr) { scr.style.overflow = 'hidden'; scr.style.background = '#000'; }
                  document.body.style.background = '#000';
                } catch(e) {}
              }
              fit();
              setInterval(fit, 350);
            })()
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun control(path: String, body: String) {
        val conn = URL("http://$HOST:$CONTROL_PORT$path").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Cookie", "$COOKIE=$token")
        conn.connectTimeout = 1500
        conn.readTimeout = 3000
        conn.outputStream.use { it.write(body.toByteArray()) }
        conn.responseCode
        conn.disconnect()
    }

    private fun stopSession(sid: String) {
        val conn = URL("http://$HOST:$CONTROL_PORT/stop").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Cookie", "$COOKIE=$token")
        conn.connectTimeout = 1500
        conn.readTimeout = 3000
        conn.outputStream.use { it.write("{\"id\":\"$sid\"}".toByteArray()) }
        conn.responseCode
        conn.disconnect()
    }

    companion object {
        private const val TAG = "CloudyX11"
        private const val HOST = "127.0.0.1"
        private const val DEFAULT_PORT = 14500
        private const val CONTROL_PORT = 14509
        private const val COOKIE = "cloudy_x11"
        private const val PROFILE = "cloudy-x11"
    }
}
