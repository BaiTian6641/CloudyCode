package dev.cloudy.x11.native

import android.app.Activity
import android.app.ActivityManager
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Small visible controller for a per-window session. It deliberately owns no
 * Surface and no X client; it starts the :x11 engine service and leaves the
 * Terminal VM visible beside the X11 window tasks.
 */
class PerWindowSessionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTitle("Cloudy X11 session")
        setTaskDescription(ActivityManager.TaskDescription("Cloudy X11 session"))
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(32, 32, 32, 32)
            setBackgroundColor(Color.BLACK)
            addView(TextView(this@PerWindowSessionActivity).apply {
                text = "Cloudy X11\nPer-window session active\n\nWindows appear as separate tasks."
                textSize = 16f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
            })
        }
        setContentView(root)
        startEngine()
    }

    override fun onResume() {
        super.onResume()
        startEngine()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        startEngine()
    }

    private fun startEngine() {
        try {
            startForegroundService(Intent(this, NativeEngineService::class.java)
                .putExtra("action", "start"))
            Log.i(TAG, "engine start requested")
        } catch (e: Exception) {
            Log.w(TAG, "engine start failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "CloudyX11Session"
    }
}
