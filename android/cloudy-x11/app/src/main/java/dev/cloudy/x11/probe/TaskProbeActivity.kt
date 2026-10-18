package dev.cloudy.x11.probe

import android.app.Activity
import android.app.ActivityManager
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import dev.cloudy.x11.R
import java.io.File

/**
 * Diagnostic host for G2. It is not an X11 window and does not own a server.
 * Each instance is launched with NEW_DOCUMENT|MULTIPLE_TASK so the probe can
 * measure task identity, initial bounds, title/icon updates and lifecycle.
 */
class TaskProbeActivity : Activity() {
    private val id: String by lazy { intent.getStringExtra("probe_id") ?: "unknown" }
    private val title: String by lazy {
        intent.getStringExtra("probe_title") ?: "Cloudy probe $id"
    }
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTitle(title)
        applyTaskDescription(title)
        val text = TextView(this).apply {
            textSize = 20f
            gravity = Gravity.CENTER
            text = "$title\nid=$id\ntask=${taskId}\n\nSynthetic task host"
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
            addView(text, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setContentView(root)
        log("create id=$id task=${taskId} bounds=${bounds()}")
        window.decorView.post { log("laid-out id=$id task=${taskId} bounds=${bounds()}") }
        handler.postDelayed({
            val updated = "$title *"
            setTitle(updated)
            applyTaskDescription(updated)
            log("title-update id=$id task=${taskId} title='$updated'")
        }, 3000)
    }

    private fun applyTaskDescription(label: String) {
        val icon = BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher_x11)
        setTaskDescription(ActivityManager.TaskDescription(label, icon))
    }

    private fun bounds(): String {
        val b = getSystemService(android.view.WindowManager::class.java).currentWindowMetrics.bounds
        return "${b.left},${b.top},${b.right},${b.bottom} ${b.width()}x${b.height()}"
    }

    override fun onResume() {
        super.onResume()
        log("resume id=$id task=${taskId} bounds=${bounds()}")
    }

    override fun onPause() {
        log("pause id=$id task=${taskId} bounds=${bounds()}")
        super.onPause()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        log("config id=$id task=${taskId} bounds=${bounds()} " +
            "orientation=${newConfig.orientation} density=${newConfig.densityDpi}")
    }

    override fun onSaveInstanceState(outState: Bundle) {
        log("save-state id=$id task=${taskId}")
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        log("new-intent id=$id task=${taskId} action=${intent.action}")
    }

    @Deprecated("Probe measures the legacy Back path explicitly")
    override fun onBackPressed() {
        log("back id=$id task=${taskId}")
        finishAndRemoveTask()
    }

    override fun onDestroy() {
        log("destroy id=$id task=${taskId} finishing=$isFinishing changing=${isChangingConfigurations}")
        handler.removeCallbacksAndMessages(null)
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
    }
}
