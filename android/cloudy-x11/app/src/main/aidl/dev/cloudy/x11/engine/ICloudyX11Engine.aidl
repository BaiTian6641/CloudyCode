package dev.cloudy.x11.engine;

import android.view.Surface;

/**
 * Surface handoff to the dedicated :x11 engine process. Calls are marshalled
 * onto the service's connection HandlerThread; bindSurface returns true only
 * when the renderer connection exists and the bind was queued.
 *
 * The package is "engine" because "native" is a Java keyword and cannot be a
 * segment of an AIDL-generated Java package.
 */
interface ICloudyX11Engine {
    boolean bindSurface(in Surface surface, int width, int height);
    void unbindSurface();
}
