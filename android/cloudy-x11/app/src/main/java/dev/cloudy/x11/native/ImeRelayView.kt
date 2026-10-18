package dev.cloudy.x11.native

import android.content.Context
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.text.InputType

/** Invisible editor used only to receive soft-keyboard commits for one X host. */
class ImeRelayView(context: Context) : View(context) {
    var onCommit: ((String) -> Unit)? = null
    var onKey: ((Int, Boolean) -> Unit)? = null
    var keyFor: ((Int) -> Int)? = null

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI
        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                text?.toString()?.takeIf { it.isNotEmpty() }?.let { onCommit?.invoke(it) }
                return true
            }

            override fun setComposingText(
                text: CharSequence?,
                newCursorPosition: Int,
            ): Boolean = true

            override fun deleteSurroundingText(
                beforeLength: Int,
                afterLength: Int,
            ): Boolean {
                repeat(beforeLength.coerceAtMost(64)) {
                    onKey?.invoke(22, true)
                    onKey?.invoke(22, false)
                }
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action != KeyEvent.ACTION_DOWN &&
                    event.action != KeyEvent.ACTION_UP) return true
                val xKey = keyFor?.invoke(event.keyCode) ?: 0
                if (xKey > 0) {
                    onKey?.invoke(xKey, event.action == KeyEvent.ACTION_DOWN)
                    return true
                }
                return super.sendKeyEvent(event)
            }
        }
    }
}
