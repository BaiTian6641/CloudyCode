package dev.cloudy.x11.native

/**
 * Thin JNI contract for the dedicated X server process. The engine has no JVM;
 * every call is made from the service's prepared Looper thread. The callback
 * runs on the X server thread and is marshalled back with AttachCurrentThread.
 */
object X11Native {
    init {
        System.loadLibrary("cloudy_x11_native")
    }

    interface Callback {
        fun onNativeReady(display: String)
        fun onNativeWindow(
            id: Int,
            title: String?,
            role: Int,
            parent: Int,
            width: Int,
            height: Int,
            mapped: Boolean,
        ) {}
    }

    external fun start(
        tmpDir: String,
        xkbRoot: String,
        authFile: String,
        width: Int,
        height: Int,
        callback: Callback,
    ): Boolean

    external fun stop()

    external fun acceptFd(fd: Int)

    /** Test-only connected socketpair admission; returns the consumed fd number. */
    external fun acceptSelfTest(): Int

    /**
     * Create the sole renderer connection and start observing X windows.
     * Must be called on the prepared HandlerThread Looper after the server is
     * ready; all connection calls stay serialized on that thread.
     */
    external fun connectRenderer(): Boolean

    /**
     * Attach the presentation Surface to the first mapped top-level window,
     * falling back to the root window when none is known yet. The engine takes
     * its own native-window reference; [width] and [height] are content pixels.
     */
    external fun bindSurface(surface: android.view.Surface?, width: Int, height: Int): Boolean

    /** Detach presentation while retaining the X output and client. */
    external fun unbindSurface()

    /** Bind one X top-level to its own nonzero output/Surface. */
    external fun bindWindowSurface(
        surface: android.view.Surface?,
        width: Int,
        height: Int,
        output: Int,
        window: Int,
    ): Boolean

    /** Release one per-window output; does not close the X client. */
    external fun unbindWindowSurface(output: Int)

    /** Normalized pointer position in the bound output (0..1). */
    external fun sendPointer(
        output: Int, window: Int, x: Float, y: Float, button: Int, down: Boolean,
    )

    /** Send one Unicode code point to the focused X client. */
    external fun sendText(output: Int, window: Int, codePoint: Int)

    /** Send an X11 keycode press/release to the bound window. */
    external fun sendKey(output: Int, window: Int, xKeyCode: Int, down: Boolean)

    /** XID of the first mapped application top-level window, or zero. */
    external fun firstWindow(): Int

    /** Tear down the renderer connection; does not stop the X server. */
    external fun destroyRenderer()
}
