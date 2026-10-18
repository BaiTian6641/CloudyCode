# C3.1 evidence — VM X client through the APK into the embedded engine

**Date:** 2026-10-18. **Branch:** `main`. **Source tested:** working tree on top
of `a0c5af8`, committed with the C3.1 implementation. **Device:** Googlebook
`C1L63708PZ`, build
`HP/quartz/quartz:17/CL3B.260622.276.R2/16560795:user/release-keys`, SDK 37,
app UID `1010410`. **APK:** debug `dev.cloudy.x11`,
sha256 `28c6826875b50f7a9b7ed2044c6ef512653707904479c8704735e694c4b87537`
(this includes the 16 KB JNI rebuild). No token/cookie is recorded.

## Claim

An X11 client running in the Debian VM connected to `DISPLAY=:99`, traversed the
VM display proxy and the private Unix tunnel, the existing 14500 bridge, the
installed APK's dedicated `:x11` foreground service, and the pinned embedded X
server. `xdpyinfo` returned the **embedded engine** identity, not Xvfb:

```
name of display:    :99
vendor release number:    12101099
X.Org version: 21.1.99
maximum request size:  16777212 bytes
```

The app log recorded the accepted fd:

```
CloudyX11Native: tunnel connected display=:99 cookie_len=32
CloudyX11Native: auth written bytes=46
CloudyX11Native: native X11 ready display=99
CloudyX11Native: accepting connected X11 fd=122
```

The VM proxy log recorded only the app connection and its private tunnel; no TCP
X11 listener was added. The tunnel config supplied the display cookie; the app
wrote a `FamilyWild` MIT-MAGIC-COOKIE-1 Xauthority record and started with
`-auth`, never `-ac`.

## Environment and path

```
VM xdpyinfo :99
  -> /tmp/.X11-unix/X99 (cloudy-x11-vm)
  -> unix:$HOME/.local/share/cloudy/x11-tunnel.sock
  -> 14500 token proxy /x11-tunnel (raw Upgrade + versioned config)
  -> dev.cloudy.x11:x11 NativeEngineService (foreground, app UID)
  -> EngineTunnel O/D/C -> socketpair -> lorieServerAcceptClient
  -> LorieNative X server :99 (21.1.99)
```

The app service also passed a synthetic socketpair admission test; that only
proves the JNI admission call, not X11 auth. The `xdpyinfo` result proves the
tunneled, authenticated client path. No renderer, `LorieConnection`, Android
Surface or per-window task exists in this evidence.

## Lifecycle

- `NativeEngineService` is a foreground service in process `:x11`; the engine
  process exits on normal stop. Explicit stop logged
  `X11Native.stop requested` / `LorieNative: Server stopped (0)` and removed the
  `:x11` process.
- A later start reconnected the tunnel and reached ready again.
- `adb shell am force-stop dev.cloudy.x11` removed both the UI process and the
  `:x11` process (`am_kill ... dev.cloudy.x11:x11`), while the VM 14500 proxy
  stayed healthy. Cold activation is not automatic; the UI/service must be
  started by a supported route.

## 16 KB page-size compatibility

The user reported a 16 KB warning and Googlebook Android 17. The earlier JNI
build had 4 KB LOAD alignment and triggered `PageSizeMismatchDialog`. The build
now links with:

```
-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384
```

Verification of the packaged APK:

```
LOAD ... Align 0x4000   (all four LOAD segments)
zipalign -c -P 16 -v 4 app-debug.apk:
  lib/arm64-v8a/libcloudy_x11_native.so (OK)
  Verification successful
```

No `PageSizeMismatchDialog` appeared after installing/starting the rebuilt app.
The APK stores the native library uncompressed; `app/src/main/jniLibs` is
generated (ignored) and Gradle `preBuild` runs `tools/device/build-x11-jni.sh`.
NDK remains `27.3.13750724`, API 34, arm64-v8a.

## Honest limits

- No Android Surface, pixels, window callbacks or per-X-window task in this
  evidence. C3.2 remains open.
- The `xdpyinfo` client ran from a VM harness shell; Terminal/Cloudy Code
  interactive paths are separate acceptance rows.
- The VM was restarted once during the session, which cleared the prior
  forwarded listeners; the Terminal VM was relaunched and the services
  recovered. This is a real VM-lifecycle dependency, not an app-only state.
- The engine's fatal-error logger at this pin can corrupt the option text in
  logcat; the fatal argument error that blocked the first attempt was the
  unsupported `-screen` option. The smoke-proven form is `:99 -noreset
  -nolisten tcp -auth <file>`.
