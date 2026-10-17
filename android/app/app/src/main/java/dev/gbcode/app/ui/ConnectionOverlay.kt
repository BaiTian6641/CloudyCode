package dev.gbcode.app.ui

import android.app.Activity
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import dev.gbcode.app.R

/**
 * Cloudy connection surface shown instead of the WebView's own error page.
 *
 * States:
 *  - reconnecting: quiet progress line, status text, optional "Show options".
 *  - unavailable: explanation plus explicit recovery actions (retry, open the
 *    Terminal that provides the VM port bridge, copy the setup command).
 *
 * Motion is a short fade/8dp rise on entry and nothing looping; the progress
 * line is the only indeterminate element and carries its own status text.
 */
class ConnectionOverlay(activity: Activity, parent: FrameLayout) {
    private val view: View =
        LayoutInflater.from(activity).inflate(R.layout.view_connection_state, parent, false)
    private val title: TextView = view.findViewById(R.id.overlayTitle)
    private val detail: TextView = view.findViewById(R.id.overlayDetail)
    private val progress: View = view.findViewById(R.id.overlayProgress)
    private val actions: View = view.findViewById(R.id.overlayActions)
    private val showOptions: Button = view.findViewById(R.id.showOptions)
    private val attachCancel: Button = view.findViewById(R.id.attachCancel)

    var onRetry: () -> Unit = {}
    var onOpenTerminal: () -> Unit = {}
    var onCopyCommand: () -> Unit = {}
    var onAdvanced: () -> Unit = {}
    var onOffline: () -> Unit = {}
    var onCancelAttach: () -> Unit = {}

    init {
        parent.addView(view, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        view.visibility = View.GONE
        view.findViewById<Button>(R.id.retryNow).setOnClickListener { onRetry() }
        view.findViewById<Button>(R.id.openTerminal).setOnClickListener { onOpenTerminal() }
        view.findViewById<Button>(R.id.copyCommand).setOnClickListener { onCopyCommand() }
        view.findViewById<Button>(R.id.advancedSetup).setOnClickListener { onAdvanced() }
        view.findViewById<Button>(R.id.offlineWorkbench).setOnClickListener { onOffline() }
        attachCancel.setOnClickListener { onCancelAttach() }
        showOptions.setOnClickListener {
            actions.visibility = View.VISIBLE
            showOptions.visibility = View.GONE
        }
        // Readable measure on wide desktop windows.
        val content = view.findViewById<View>(R.id.overlayContent)
        content.post {
            val maxContent = (520 * activity.resources.displayMetrics.density).toInt()
            val pad = maxOf((48 * activity.resources.displayMetrics.density).toInt(),
                (content.width - maxContent) / 2)
            content.setPadding(pad, content.paddingTop, pad, content.paddingBottom)
        }
    }

    val isVisible: Boolean get() = view.visibility == View.VISIBLE

    fun showSearching() {
        render(
            titleText = "Looking for the Linux workspace",
            detailText = "Waiting for the backend to appear. You can keep working offline; " +
                "the workspace attaches automatically when it is found.",
            unavailable = false,
        )
        attachCancel.visibility = View.GONE
    }

    fun showAttaching(kind: String) {
        render(
            titleText = "Linux workspace found",
            detailText = "Attaching to the $kind backend…",
            unavailable = false,
        )
        attachCancel.visibility = View.VISIBLE
    }

    fun showReconnecting(reason: String? = null) {
        render(
            titleText = "Reconnecting…",
            detailText = reason ?: "Waiting for the Linux workspace to answer. Retrying " +
                "automatically; your editor restores when the connection is back.",
            unavailable = false,
        )
    }

    fun showUnavailable(detailText: String) {
        render(
            titleText = "Linux workspace not detected",
            detailText = detailText,
            unavailable = true,
        )
    }

    fun setDetail(text: String) {
        detail.text = text
    }

    fun hide() {
        if (view.visibility != View.VISIBLE) return
        if (!animationsEnabled()) {
            view.visibility = View.GONE
            return
        }
        view.animate().alpha(0f).setDuration(140).withEndAction {
            view.visibility = View.GONE
            view.alpha = 1f
        }.start()
    }

    private fun render(titleText: String, detailText: String, unavailable: Boolean) {
        if (view.visibility != View.VISIBLE) {
            if (animationsEnabled()) {
                view.alpha = 0f
                view.translationY = 8f * view.resources.displayMetrics.density
                view.animate().alpha(1f).translationY(0f).setDuration(180)
                    .setInterpolator(DecelerateInterpolator()).start()
            } else {
                view.alpha = 1f
                view.translationY = 0f
            }
            view.visibility = View.VISIBLE
        }
        title.text = titleText
        detail.text = detailText
        progress.visibility = if (unavailable) View.GONE else View.VISIBLE
        actions.visibility = if (unavailable) View.VISIBLE else View.GONE
        showOptions.visibility = if (unavailable) View.GONE else View.VISIBLE
        attachCancel.visibility = View.GONE
    }

    private fun animationsEnabled(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.animation.ValueAnimator.areAnimatorsEnabled()
        } else true
}
