package dev.cloudy.x11.probe

import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.IBinder
import android.util.Log
import dev.cloudy.x11.R
import java.io.File

/**
 * Diagnostic foreground service for G2. It is deliberately separate from the
 * future X11 session supervisor. It starts synthetic free-form tasks so the
 * platform's background-launch, identity and lifecycle behavior can be
 * measured before the native window host is built. It has no X server, client
 * or display cookie.
 */
class TaskProbeService : Service() {
    override fun onCreate() {
        super.onCreate()
        createChannel()
        try {
            startForeground(NOTIFICATION_ID, notification())
        } catch (e: Exception) {
            log("startForeground failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.getStringExtra("action") ?: "noop"
        val trigger = intent?.getStringExtra("trigger") ?: "unknown"
        log("service command action=$action trigger=$trigger startId=$startId " +
            "sdk=${Build.VERSION.SDK_INT}")
        when (action) {
            "start" -> Unit
            "one" -> startProbe("one-$startId", "Cloudy probe one", trigger, 0.18f, 0.18f)
            "two" -> {
                startProbe("two-a-$startId", "Cloudy probe A", trigger, 0.18f, 0.16f)
                startProbe("two-b-$startId", "Cloudy probe B", trigger, 0.58f, 0.48f)
            }
            "stop" -> stopSelf()
            else -> log("unknown action=$action")
        }
        return START_STICKY
    }

    private fun startProbe(id: String, title: String, trigger: String,
                           xFraction: Float, yFraction: Float) {
        val bounds = getSystemService(android.view.WindowManager::class.java)
            .currentWindowMetrics.bounds
        val w = (bounds.width() * 0.42f).toInt().coerceAtLeast(320)
        val h = (bounds.height() * 0.42f).toInt().coerceAtLeast(240)
        val left = (bounds.width() * xFraction).toInt()
        val top = (bounds.height() * yFraction).toInt()
        val launchBounds = Rect(left, top,
            (left + w).coerceAtMost(bounds.width()),
            (top + h).coerceAtMost(bounds.height()))
        val intent = Intent(this, TaskProbeActivity::class.java).apply {
            putExtra("probe_id", id)
            putExtra("probe_title", title)
            putExtra("probe_trigger", trigger)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_NEW_DOCUMENT or
                Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        }
        val options = ActivityOptions.makeBasic().setLaunchBounds(launchBounds).toBundle()
        try {
            startActivity(intent, options)
            log("startActivity id=$id trigger=$trigger bounds=$launchBounds")
        } catch (e: Exception) {
            log("startActivity failed id=$id ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun notification(): Notification {
        val open = PendingIntent.getService(
            this, 1,
            Intent(this, TaskProbeService::class.java).putExtra("action", "start"),
            PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_cloudy_x11_fg)
            .setContentTitle("Cloudy X11 task probe")
            .setContentText("Synthetic free-form window host is active")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(
                CHANNEL_ID, "Cloudy X11 task probe", NotificationManager.IMPORTANCE_LOW))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        log("service destroy")
        super.onDestroy()
    }

    private fun log(line: String) {
        Log.i(TAG, line)
        try {
            File(filesDir, "task-probe.txt").appendText(line + "\n")
        } catch (e: Exception) {
            Log.w(TAG, "log write failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "CloudyX11TaskProbe"
        private const val CHANNEL_ID = "cloudy-x11-task-probe"
        private const val NOTIFICATION_ID = 41
    }
}
