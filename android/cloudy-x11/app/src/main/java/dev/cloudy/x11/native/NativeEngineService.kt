package dev.cloudy.x11.native

import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Surface
import dev.cloudy.x11.R
import dev.cloudy.x11.engine.ICloudyX11Engine
import java.io.BufferedInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.zip.ZipInputStream
import java.util.concurrent.TimeUnit

/**
 * Dedicated app-UID process for the embedded X server. The server exits on
 * normal shutdown; this service must be treated as one engine lifetime, not a
 * place to host Activities. The host process hands over Surface presentation
 * through [ICloudyX11Engine]; all renderer calls stay on the connection looper.
 */
class NativeEngineService : Service(), X11Native.Callback {
    private var thread: HandlerThread? = null
    @Volatile private var handler: Handler? = null
    @Volatile private var started = false
    private var tunnel: EngineTunnel? = null
    private var readyLatch = CountDownLatch(1)
    @Volatile private var rendererConnected = false
    @Volatile private var windowKnown = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowHosts = mutableMapOf<Int, Int>()
    private var nextOutput = 1

    /**
     * Surface IPC from the host process. Native calls are always posted to the
     * connection HandlerThread; bindSurface is accepted only after
     * connectRenderer succeeded, so the Activity's retry loop covers engine
     * startup instead of blocking a Binder thread. The result stays false
     * until a mapped top-level window is observed, so an early root bind is
     * replaced once the first client window is known.
     */
    private val engineBinder = object : ICloudyX11Engine.Stub() {
        override fun bindSurface(surface: Surface?, width: Int, height: Int): Boolean {
            if (surface == null || width <= 0 || height <= 0) {
                log("bindSurface rejected surface=${surface != null} ${width}x$height")
                return false
            }
            if (!rendererConnected) {
                log("bindSurface deferred: renderer not connected ${width}x$height")
                return false
            }
            val h = handler
            if (h == null) {
                log("bindSurface deferred: no connection looper")
                return false
            }
            val window = try {
                X11Native.firstWindow()
            } catch (e: Throwable) {
                log("firstWindow failed ${e.javaClass.simpleName}: ${e.message}")
                -1
            }
            if (window <= 0) {
                // Do not bind XID zero: the per-window output needs a real
                // top-level. Keep the Activity retry loop alive.
                log("bindSurface deferred: no mapped window yet")
                return false
            }
            windowKnown = true
            h.post {
                if (!rendererConnected) return@post
                val ok = try {
                    X11Native.bindSurface(surface, width, height)
                } catch (e: Throwable) {
                    log("bindSurface threw ${e.javaClass.simpleName}: ${e.message}")
                    false
                }
                log("bindSurface ${width}x$height window=$window " +
                    "known=${windowKnown} returned=$ok")
            }
            return true
        }

        override fun bindWindowSurface(
            surface: Surface?,
            width: Int,
            height: Int,
            output: Int,
            window: Int,
        ): Boolean {
            if (surface == null || width <= 0 || height <= 0 ||
                output <= 0 || window <= 0) return false
            if (!rendererConnected) {
                log("bindWindowSurface deferred: renderer not connected")
                return false
            }
            val h = handler ?: return false
            h.post {
                try {
                    val ok = X11Native.bindWindowSurface(
                        surface, width, height, output, window)
                    log("bindWindowSurface output=$output xid=0x" +
                        Integer.toHexString(window) + " returned=$ok")
                } catch (e: Throwable) {
                    log("bindWindowSurface threw ${e.javaClass.simpleName}: ${e.message}")
                }
            }
            return true
        }

        override fun unbindWindowSurface(output: Int) {
            handler?.post {
                try {
                    X11Native.unbindWindowSurface(output)
                } catch (e: Throwable) {
                    log("unbindWindowSurface threw ${e.javaClass.simpleName}: ${e.message}")
                }
            }
        }

        override fun unbindSurface() {
            val h = handler
            if (h == null || !started) {
                log("unbindSurface ignored handler=${h != null} started=$started")
                return
            }
            val done = CountDownLatch(1)
            val queued = h.post {
                try {
                    if (rendererConnected) {
                        try {
                            X11Native.unbindSurface()
                            log("unbindSurface native returned")
                        } catch (e: Throwable) {
                            log("unbindSurface threw ${e.javaClass.simpleName}: ${e.message}")
                        }
                    }
                } finally {
                    done.countDown()
                }
            }
            if (!queued) {
                log("unbindSurface not queued")
                return
            }
            // The Activity destroys its Surface after this returns, so wait for
            // the renderer to acknowledge detachment. Bounded to avoid an ANR.
            if (!done.await(2, TimeUnit.SECONDS)) {
                log("unbindSurface timed out waiting for connection looper")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        log("service create pid=${android.os.Process.myPid()} uid=${android.os.Process.myUid()}")
        createChannel()
        try {
            startForeground(NOTIFICATION_ID, notification())
            log("foreground notification active")
        } catch (e: Throwable) {
            log("startForeground failed ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun notification(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_cloudy_x11_fg)
            .setContentTitle("Cloudy X11 engine")
            .setContentText("Embedded X server process")
            .setOngoing(true)
            .build()

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL_ID, "Cloudy X11 engine", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        log("onStartCommand action=${intent?.getStringExtra("action") ?: "start"} startId=$startId")
        when (intent?.getStringExtra("action") ?: "start") {
            "start" -> startEngine()
            "accept" -> {
                if (started) {
                    handler?.post {
                        val fd = try {
                            X11Native.acceptSelfTest()
                        } catch (e: Exception) {
                            log("acceptSelfTest failed ${e.javaClass.simpleName}: ${e.message}")
                            -1
                        }
                        log("acceptSelfTest returned fd=$fd")
                    }
                } else {
                    log("accept requested before start")
                }
            }
            "stop" -> stopEngine()
            else -> log("unknown action")
        }
        return START_NOT_STICKY
    }

    private fun startEngine() {
        log("startEngine entered")
        if (started) {
            log("start ignored: already started")
            return
        }
        val xkb = File(filesDir, "xkb")
        try {
            ensureXkb(xkb)
        } catch (e: Throwable) {
            log("XKB staging failed ${e.javaClass.simpleName}: ${e.message}")
            stopSelf()
            return
        }
        readyLatch = CountDownLatch(1)
        val t = HandlerThread("cloudy-x11-native").also { it.start() }
        thread = t
        val h = Handler(t.looper)
        handler = h
        log("native thread started")
        h.post {
            try {
                val token = (application as dev.cloudy.x11.CloudyX11App).x11Token()
                val connected = EngineTunnel.connect(token)
                tunnel = connected
                log("tunnel connected display=${connected.display} " +
                    "cookie_len=${connected.cookie.length}")
                val auth = File(filesDir, "engine-xauth")
                writeXauthority(auth, connected.cookie, connected.display)
                log("auth written bytes=${auth.length()}")
                val ok = X11Native.start(filesDir.absolutePath, xkb.absolutePath,
                    auth.absolutePath, 1280, 800, this)
                log("X11Native.start returned $ok")
                if (!ok) {
                    connected.close()
                    tunnel = null
                    return@post
                }
                started = true
                if (readyLatch.await(8, TimeUnit.SECONDS)) {
                    connectRendererOnHandler()
                } else {
                    log("native ready timed out; renderer waits for ready callback")
                }
                connected.startReader {
                    log("tunnel reader ended")
                    handler?.post {
                        log("stopping engine after tunnel loss")
                        stopEngine()
                        stopSelf()
                    }
                }
            } catch (e: Throwable) {
                log("tunnel/start failed ${e.javaClass.simpleName}: ${e.message}")
                tunnel?.close()
                tunnel = null
                rendererConnected = false
                started = false
                handler?.post {
                    handler?.removeCallbacksAndMessages(null)
                    thread?.quitSafely()
                    handler = null
                    thread = null
                }
                stopSelf()
            }
        }
    }

    /** Runs only on the connection HandlerThread, after onNativeReady. */
    private fun connectRendererOnHandler() {
        if (rendererConnected || !started) return
        val ok = try {
            X11Native.connectRenderer()
        } catch (e: Throwable) {
            log("connectRenderer threw ${e.javaClass.simpleName}: ${e.message}")
            false
        }
        rendererConnected = ok
        if (ok) {
            val window = try {
                X11Native.firstWindow()
            } catch (e: Throwable) {
                log("firstWindow failed ${e.javaClass.simpleName}: ${e.message}")
                -1
            }
            windowKnown = window > 0
            log("connectRenderer connected firstWindow=$window known=$windowKnown")
        } else {
            log("connectRenderer failed")
        }
    }

    private fun stopEngine() {
        if (!started) {
            log("stop without a live engine; removing service")
            tunnel?.close()
            tunnel = null
            handler?.removeCallbacksAndMessages(null)
            thread?.quitSafely()
            handler = null
            thread = null
            stopSelf()
            return
        }
        started = false
        rendererConnected = false
        windowKnown = false
        tunnel?.close()
        tunnel = null
        try {
            X11Native.stop()
            log("X11Native.stop requested")
        } catch (e: Throwable) {
            log("stop threw ${e.javaClass.simpleName}: ${e.message}")
        }
        handler?.removeCallbacksAndMessages(null)
        handler = null
        thread?.quitSafely()
        thread = null
    }

    override fun onNativeReady(display: String) {
        log("native ready display=:$display")
        readyLatch.countDown()
        // Covers a ready signal that arrives after the startup wait expired.
        handler?.post { connectRendererOnHandler() }
    }

    override fun onBind(intent: Intent?): IBinder = engineBinder

    override fun onDestroy() {
        log("service destroy")
        rendererConnected = false
        windowKnown = false
        tunnel?.close()
        tunnel = null
        if (started) {
            try {
                X11Native.stop()
            } catch (_: Throwable) {
            }
        }
        handler?.removeCallbacksAndMessages(null)
        handler = null
        thread?.quitSafely()
        super.onDestroy()
    }

    /**
     * Bundle the pinned-engine XKB data so a clean install reaches ready.
     * The asset is a zipped xkeyboard-config tree; entries are confined to the
     * app-private destination and extraction is idempotent.
     */
    private fun ensureXkb(destination: File) {
        if (File(destination, "symbols").isDirectory) {
            log("XKB already staged at ${destination.absolutePath}")
            return
        }
        destination.deleteRecursively()
        destination.mkdirs()
        val root = destination.canonicalFile
        assets.open("xkb.zip").use { raw ->
            ZipInputStream(BufferedInputStream(raw)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val target = File(root, entry.name).canonicalFile
                    if (target != root &&
                        !target.path.startsWith(root.path + File.separator)) {
                        throw SecurityException("XKB zip path escape")
                    }
                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        target.outputStream().use { out -> zip.copyTo(out) }
                    }
                    zip.closeEntry()
                }
            }
        }
        if (!File(destination, "symbols").isDirectory) {
            throw IllegalStateException("XKB archive has no symbols directory")
        }
        log("XKB staged at ${destination.absolutePath}")
    }

    private fun writeXauthority(file: File, cookieHex: String, display: String) {
        val cookie = ByteArray(cookieHex.length / 2) { i ->
            cookieHex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        val number = display.removePrefix(":").toByteArray()
        DataOutputStream(file.outputStream()).use { out ->
            out.writeShort(65535)     // FamilyWild: local tunnel peer, any host
            out.writeShort(0)         // empty address
            out.writeShort(number.size)
            out.write(number)
            val name = "MIT-MAGIC-COOKIE-1".toByteArray()
            out.writeShort(name.size)
            out.write(name)
            out.writeShort(cookie.size)
            out.write(cookie)
        }
        file.setReadable(false, false)
        file.setReadable(true, true)
        file.setWritable(false, false)
        file.setWritable(true, true)
    }

    private fun log(line: String) {
        Log.i(TAG, line)
        try {
            File(filesDir, "native-engine.txt").appendText(line + "\n")
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val TAG = "CloudyX11Native"
        private const val CHANNEL_ID = "cloudy-x11-engine"
        private const val NOTIFICATION_ID = 42
    }
}
