package dev.gbcode.app.ui

import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import dev.gbcode.app.R

/** Non-blocking Cloudy banner offering the preferred backend after detection. */
class BackendBanner(activity: Activity, parent: FrameLayout) {
    private val view: View =
        LayoutInflater.from(activity).inflate(R.layout.view_backend_banner, parent, false)
    private val text: TextView = view.findViewById(R.id.bannerText)

    var onSwitch: () -> Unit = {}
    var onDismiss: () -> Unit = {}

    init {
        parent.addView(view)
        view.findViewById<Button>(R.id.bannerSwitch).setOnClickListener {
            hide()
            onSwitch()
        }
        view.findViewById<Button>(R.id.bannerDismiss).setOnClickListener {
            hide()
            onDismiss()
        }
    }

    val isVisible: Boolean get() = view.visibility == View.VISIBLE

    fun show(message: String) {
        text.text = message
        view.visibility = View.VISIBLE
    }

    fun hide() {
        view.visibility = View.GONE
    }
}
