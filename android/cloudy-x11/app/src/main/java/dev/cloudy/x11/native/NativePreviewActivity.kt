package dev.cloudy.x11.native

import android.app.Activity
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.GestureDetector
import android.view.SurfaceHolder
import android.view.ViewGroup
import android.view.SurfaceView
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.TextView
import dev.cloudy.x11.engine.ICloudyX11Engine

/**
 * First Android Surface proof for the embedded engine (C3.2). The Activity
 * only owns presentation: the :x11 service keeps the engine, tunnel and
 * renderer lifetime. One Surface generation is offered at a time; an offer is
 * retried while the engine is still booting or has not discovered a window.
 */
class NativePreviewActivity : Activity(), SurfaceHolder.Callback {
    private lateinit var surfaceView: SurfaceView
    private val main = Handler(Looper.getMainLooper())
    private var engine: ICloudyX11Engine? = null
    private var serviceBound = false
    private var surfaceGeneration = 0
    private var surfaceAlive = false
    private var acceptedGeneration = -1
    private var bindWindowStarted = 0L
    private var retry: Runnable? = null
    private lateinit var imeRelay: ImeRelayView
    private val gestureDetector by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onLongPress(e: MotionEvent) {
                showSoftKeyboard()
            }
        })
    }
    private val windowId by lazy { intent.getIntExtra("window_id", 0) }
    private val outputId by lazy { intent.getIntExtra("output_id", 0) }
    private val windowTitle by lazy {
        intent.getStringExtra("window_title") ?: TITLE
    }
    private val windowClosed = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) {
            if (intent.getIntExtra(
                    NativeEngineService.EXTRA_WINDOW_ID, 0) == windowId) {
                log("window closed by engine xid=0x${Integer.toHexString(windowId)}")
                finishAndRemoveTask()
            }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            engine = ICloudyX11Engine.Stub.asInterface(service)
            log("serviceConnected binder=${service != null} gen=$surfaceGeneration")
            if (surfaceAlive && acceptedGeneration != surfaceGeneration) {
                scheduleBind(0)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            engine = null
            acceptedGeneration = -1
            log("serviceDisconnected gen=$surfaceGeneration")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (windowId <= 0 || outputId <= 0) {
            // A restored legacy/diagnostic task has no X window binding. Never
            // present an unresizable black canvas for it.
            Log.i(TAG, "invalid host extras window=$windowId output=$outputId; finish")
            finishAndRemoveTask()
            return
        }
        setTitle(windowTitle)
        setTaskDescription(ActivityManager.TaskDescription(windowTitle))
        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        window.decorView.setBackgroundColor(Color.BLACK)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        // Keep the Surface inside the content area; the system caption and bars
        // must not overlap engine pixels.
        window.setDecorFitsSystemWindows(true)
        window.insetsController?.setSystemBarsAppearance(
            0,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
        )

        surfaceView = SurfaceView(this).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            setBackgroundColor(Color.BLACK)
            // Engine pixels must sit above the opaque window. Soft IME remains
            // hardware-key/text forwarding; the system IME cannot overlay this
            // on-top surface reliably.
            setZOrderOnTop(true)
            holder.addCallback(this@NativePreviewActivity)
            holder.setFormat(PixelFormat.RGBA_8888)
        }
        surfaceView.setOnTouchListener { _, event ->
            handlePointer(event)
            gestureDetector.onTouchEvent(event)
            true
        }
        imeRelay = ImeRelayView(this).apply {
            onCommit = { text -> sendSoftText(text) }
            onKey = { xKey, down -> sendSoftKey(xKey, down) }
            keyFor = { androidKey -> specialKeys[androidKey] ?: 0 }
        }
        val root = FrameLayout(this).apply {
            addView(surfaceView, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT))
            addView(imeRelay, FrameLayout.LayoutParams(1, 1))
        }
        setContentView(root)
        log("create task=$taskId ${bounds()}")
        window.decorView.post { log("laidOut task=$taskId ${bounds()}") }
        if (windowId > 0) {
            registerReceiver(
                windowClosed,
                IntentFilter(NativeEngineService.ACTION_WINDOW_CLOSED),
                android.content.Context.RECEIVER_NOT_EXPORTED,
            )
        }

        val start = Intent(this, NativeEngineService::class.java).putExtra("action", "start")
        try {
            startForegroundService(start)
            log("startForegroundService requested")
        } catch (e: Throwable) {
            log("startForegroundService failed ${e.javaClass.simpleName}: ${e.message}")
        }
        try {
            serviceBound = bindService(
                Intent(this, NativeEngineService::class.java),
                connection,
                Context.BIND_AUTO_CREATE,
            )
            log("bindService requested=$serviceBound")
        } catch (e: Throwable) {
            log("bindService failed ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceGeneration++
        surfaceAlive = true
        acceptedGeneration = -1
        bindWindowStarted = SystemClock.uptimeMillis()
        log("surfaceCreated gen=$surfaceGeneration valid=${holder.surface.isValid} ${bounds()}")
        surfaceView.requestFocus()
        scheduleBind(0)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun showSoftKeyboard() {
        imeRelay.requestFocus()
        val imm = getSystemService(InputMethodManager::class.java)
        imeRelay.post {
            imm.showSoftInput(imeRelay, InputMethodManager.SHOW_IMPLICIT)
            log("soft keyboard requested focused=${imeRelay.isFocused}")
        }
    }

    private fun sendSoftText(text: String) {
        val iface = engine ?: return
        var i = 0
        while (i < text.length) {
            val codePoint = text.codePointAt(i)
            iface.sendText(outputId, windowId, codePoint)
            i += Character.charCount(codePoint)
        }
    }

    private fun sendSoftKey(xKey: Int, down: Boolean) {
        engine?.sendKey(outputId, windowId, xKey, down)
    }

    override fun onResume() {
        super.onResume()
        applyTaskTitle()
        surfaceView.requestFocus()
    }

    private fun applyTaskTitle() {
        setTitle(windowTitle)
        setTaskDescription(ActivityManager.TaskDescription(windowTitle))
        window.decorView.post {
            setTitle(windowTitle)
            setTaskDescription(ActivityManager.TaskDescription(windowTitle))
        }
    }

    private fun handlePointer(event: MotionEvent): Boolean {
        val iface = engine ?: return false
        if (windowId <= 0 || outputId <= 0) return false
        val width = surfaceView.width.toFloat()
        val height = surfaceView.height.toFloat()
        if (width <= 0f || height <= 0f) return false
        val x = (event.x / width).coerceIn(0f, 1f)
        val y = (event.y / height).coerceIn(0f, 1f)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                iface.sendPointer(outputId, windowId, x, y, 1, true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                iface.sendPointer(outputId, windowId, x, y, 0, false)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP,
            MotionEvent.ACTION_CANCEL -> {
                iface.sendPointer(outputId, windowId, x, y, 1, false)
                return true
            }
        }
        return false
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val iface = engine
        if (iface != null && windowId > 0 && outputId > 0) {
            val xKey = specialKeys[event.keyCode]
            if (xKey != null) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    iface.sendKey(outputId, windowId, xKey, true)
                } else if (event.action == KeyEvent.ACTION_UP) {
                    iface.sendKey(outputId, windowId, xKey, false)
                }
                return true
            }
            if (event.action == KeyEvent.ACTION_DOWN &&
                !event.isCtrlPressed && !event.isAltPressed && !event.isMetaPressed) {
                val codePoint = event.unicodeChar
                if (codePoint != 0) {
                    iface.sendText(outputId, windowId, codePoint)
                    return true
                }
            } else if (event.action == KeyEvent.ACTION_UP && event.unicodeChar != 0) {
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        surfaceAlive = true
        acceptedGeneration = -1
        bindWindowStarted = SystemClock.uptimeMillis()
        log("surfaceChanged gen=$surfaceGeneration format=$format " +
            "frame=${holder.surfaceFrame.width()}x${holder.surfaceFrame.height()} " +
            "view=${width}x$height ${bounds()}")
        scheduleBind(0)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceAlive = false
        stopRetries()
        log("surfaceDestroyed gen=$surfaceGeneration ${bounds()}")
        unbindFromEngine()
    }

    private fun scheduleBind(delayMs: Long) {
        if (!surfaceAlive) return
        retry?.let { main.removeCallbacks(it) }
        val attempt = Runnable { attemptBind() }
        retry = attempt
        main.postDelayed(attempt, delayMs)
    }

    private fun attemptBind() {
        retry = null
        if (!surfaceAlive) return
        val generation = surfaceGeneration
        val iface = engine
        val surface = surfaceView.holder.surface
        val width = surfaceView.width
        val height = surfaceView.height
        if (iface == null) {
            log("bind gen=$generation skipped: service not connected")
            retryLater(generation)
            return
        }
        if (!surface.isValid || width <= 0 || height <= 0) {
            log("bind gen=$generation skipped: valid=${surface.isValid} ${width}x$height")
            retryLater(generation)
            return
        }
        val accepted = try {
            if (windowId > 0 && outputId > 0) {
                iface.bindWindowSurface(surface, width, height, outputId, windowId)
            } else {
                iface.bindSurface(surface, width, height)
            }
        } catch (e: Throwable) {
            log("bind gen=$generation threw ${e.javaClass.simpleName}: ${e.message}")
            false
        }
        log("bind gen=$generation ${width}x$height accepted=$accepted")
        if (accepted) {
            acceptedGeneration = generation
        } else {
            retryLater(generation)
        }
    }

    private fun retryLater(generation: Int) {
        if (!surfaceAlive || generation != surfaceGeneration) return
        val elapsed = SystemClock.uptimeMillis() - bindWindowStarted
        if (elapsed >= BIND_WINDOW_MS) {
            log("bind gen=$generation window exhausted after ${elapsed}ms")
            return
        }
        scheduleBind(BIND_RETRY_MS)
    }

    private fun stopRetries() {
        retry?.let { main.removeCallbacks(it) }
        retry = null
    }

    private fun unbindFromEngine() {
        val iface = engine ?: return
        try {
            if (windowId > 0 && outputId > 0) {
                iface.unbindWindowSurface(outputId)
            } else {
                iface.unbindSurface()
            }
            log("unbind gen=$surfaceGeneration window=$windowId")
        } catch (e: Throwable) {
            log("unbind gen=$surfaceGeneration threw ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        log("config task=$taskId orientation=${newConfig.orientation} " +
            "density=${newConfig.densityDpi} ${bounds()}")
    }

    override fun onDestroy() {
        log("destroy task=$taskId finishing=$isFinishing " +
            "configChange=$isChangingConfigurations gen=$surfaceGeneration")
        try {
            unregisterReceiver(windowClosed)
        } catch (_: Exception) {
        }
        if (windowId > 0 && !isChangingConfigurations) {
            sendBroadcast(
                Intent(NativeEngineService.ACTION_HOST_CLOSED)
                    .setPackage(packageName)
                    .putExtra(NativeEngineService.EXTRA_WINDOW_ID, windowId),
            )
        }
        stopRetries()
        main.removeCallbacksAndMessages(null)
        if (surfaceAlive) unbindFromEngine()
        surfaceAlive = false
        if (serviceBound) {
            try {
                unbindService(connection)
                log("unbindService requested")
            } catch (e: Throwable) {
                log("unbindService failed ${e.javaClass.simpleName}: ${e.message}")
            }
            serviceBound = false
        }
        engine = null
        super.onDestroy()
    }

    private fun bounds(): String {
        val window = getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
        val location = IntArray(2)
        surfaceView.getLocationOnScreen(location)
        return "task=$taskId window=${window.left},${window.top},${window.right},${window.bottom} " +
            "${window.width()}x${window.height()} " +
            "content=${location[0]},${location[1]}+${surfaceView.width}x${surfaceView.height}"
    }

    private fun log(line: String) {
        Log.i(TAG, line)
    }

    companion object {
        private val specialKeys = mapOf(
            KeyEvent.KEYCODE_ENTER to 36,
            KeyEvent.KEYCODE_DEL to 22,
            KeyEvent.KEYCODE_TAB to 23,
            KeyEvent.KEYCODE_ESCAPE to 9,
            KeyEvent.KEYCODE_DPAD_LEFT to 113,
            KeyEvent.KEYCODE_DPAD_RIGHT to 114,
            KeyEvent.KEYCODE_DPAD_UP to 111,
            KeyEvent.KEYCODE_DPAD_DOWN to 116,
            KeyEvent.KEYCODE_MOVE_HOME to 110,
            KeyEvent.KEYCODE_MOVE_END to 115,
            KeyEvent.KEYCODE_PAGE_UP to 112,
            KeyEvent.KEYCODE_PAGE_DOWN to 117,
            KeyEvent.KEYCODE_FORWARD_DEL to 119,
            KeyEvent.KEYCODE_SPACE to 65,
            KeyEvent.KEYCODE_SHIFT_LEFT to 50,
            KeyEvent.KEYCODE_SHIFT_RIGHT to 62,
            KeyEvent.KEYCODE_CTRL_LEFT to 37,
            KeyEvent.KEYCODE_CTRL_RIGHT to 105,
            KeyEvent.KEYCODE_ALT_LEFT to 64,
            KeyEvent.KEYCODE_ALT_RIGHT to 108,
        )
        private const val TAG = "CloudyX11Preview"
        private const val TITLE = "Cloudy X11 native preview"
        private const val BIND_RETRY_MS = 750L
        private const val BIND_WINDOW_MS = 45_000L
    }
}
