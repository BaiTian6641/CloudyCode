package dev.cloudy.x11.native

import android.app.Activity
import android.app.ActivityManager
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.widget.Button
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
        val status = TextView(this).apply {
            tag = "overlay-status"
            text = "Allow app windows to open automatically"
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 16)
        }
        val allow = Button(this).apply {
            text = "Allow new-window tasks"
            setOnClickListener {
                try {
                    startActivity(Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")))
                } catch (e: Exception) {
                    Log.w(TAG, "overlay settings failed: ${e.message}")
                }
            }
        }
        val root = LinearLayout(this).apply {
            tag = "session-root"
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(32, 32, 32, 32)
            setBackgroundColor(Color.BLACK)
            addView(TextView(this@PerWindowSessionActivity).apply {
                text = "Cloudy X11 is running.\nOpen an X11 app from Terminal."
                textSize = 16f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, 16)
            })
            addView(status)
            addView(allow)
        }
        setContentView(root)
        startEngine()
    }

    override fun onResume() {
        super.onResume()
        startEngine()
        val granted = Settings.canDrawOverlays(this)
        Log.i(TAG, "overlayPermission=$granted")
        window.decorView.findViewWithTag<TextView>("overlay-status")?.text =
            if (granted) "Ready to open app windows"
            else "Allow app windows to open automatically"
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
