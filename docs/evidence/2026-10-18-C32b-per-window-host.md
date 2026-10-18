# C3.2b per-window host — implementation/build evidence, device test blocked

**Date:** 2026-10-18. **Source:** working tree after `587ca18`, committed with
this note. **No token/cookie recorded.**

## Implemented

- Native `window` callbacks now notify Kotlin for every mapped/unmapped XID
  (title, role, parent, X/Y size); the callback runs on the connection Looper.
- `bindWindowSurface(Surface, w, h, output, window)` / `unbindWindowSurface`
  give each independent top-level a distinct nonzero output and Surface; the
  engine's existing `lorieConnectionSurface`/`lorieOutputBind`/
  `lorieOutputResize` path is used.
- `NativeEngineService` keeps an XID→output map and launches one
  `NativePreviewActivity` task per mapped, parentless application/unclassified
  top-level, with initial free-form bounds and title. Dialogs/popups/splash are
  not launched as ordinary tasks yet. Window removal only drops the map entry;
  close/task reconciliation is C3.3.
- The app-internal `NativePreviewActivity` can bind either the first-window
  preview or a specific XID/output. The service/preview remain non-exported;
  fresh XKB is extracted from `assets/xkb.zip`.

## Validation performed

| Check | Result |
|---|---|
| `assembleDebug` | succeeds |
| JNI build | succeeds; four LOAD segments align `0x4000` |
| APK 16 KB | `tools/device/verify-16kb.sh` passes |
| Clean XKB extraction | verified in log: `XKB staged at .../files/xkb` |
| HMAC pre-auth | reached the 14500 bridge and got an upstream `502`; no token/auth mismatch was reported |
| Per-window task creation | **not verified**; the VM/14500 private tunnel was down (502) before any X client connected |
| Two X11 tasks | **not verified** |

## Blocker observed

After launching the Cloudy-X11 launcher over the Terminal VM, the VM's forwarded
control ports disappeared and the bridge returned `502 Bad Gateway`. The
Terminal app/crosvm may remain present, but the Terminal-owned forwarding/VM
session is not usable while Terminal is not the foreground/visible task. Device
unlock/reboot/charging issues were also reported by the user. This is the
documented VM-persistence prerequisite, not an X11 engine crash. No app-side
code can synthesize the VM's X clients or guarantee its port forward.

The per-window Activity launch also depends on Android background-activity
policy. G2 showed service `startActivity` calls after HOME+16 s produced no
activity creation; the current service launch path is untested with the VM
available. Do not claim automatic background window creation.

## Next executable step

When the VM/forwarder is stable and a visible session host exists:
1. Launch `X11AppsActivity` in a free-form/side-by-side window (do not cover
   Terminal completely) or restore VM persistence.
2. Launch `xeyes` and `xterm`; verify the service creates two distinct tasks,
   each with its own output/Surface and XID title; capture window callbacks,
   task IDs and pixels.
3. Then implement XID incarnation/reuse, window close/task removal, role policy,
   and G2 background-launch fallback/permission behavior.

## Live-VM diagnosis (2026-10-18)

The app service successfully connected to the 14500 bridge after the VM private
proxy was started manually:

```
CloudyX11Native: tunnel connected display=:99 cookie_len=32
CloudyX11Native: native ready display=:99
CloudyX11Renderer: renderer connected
CloudyX11Renderer: windows committed
```

A raw `/x11-tunnel` request returned `HTTP/1.1 101` + `CLOUDY-X11-CONFIG 1`
after starting:

```
python3 ~/.local/share/cloudy/cloudy-x11-vm.py --display :99 \
  --tunnel-listen unix:/home/droid/.local/share/cloudy/x11-tunnel.sock \
  --token-file .../x11-token --cookie-file .../x11-cookie
```

The installed `cloudy-x11-vm.service` was **stale**: its ExecStart still used
`--tunnel-listen 127.0.0.1:14511` and omitted `--cookie-file`. That unit returns
`Active (running)` yet cannot serve `/x11-tunnel`, so the base proxy returned 502.
The current setup/install-env source writes the Unix+cookie form; a fresh setup
repairs it. The live VM was not cleanly rewired because the host file-transfer
path and Terminal focus were unavailable. This is a setup/repair defect, not the
X11 engine.

`xeyes`/`xterm` were not observed by the engine in the last run; Terminal input
focus was taken by the session host. No per-window task or window callback was
recorded. The session host itself is now a real, app-internal Activity with
side-by-side launch bounds; it is not a placeholder, but the final two-window
proof remains blocked by the VM unit/focus environment.

## Exact repair for the next run

From a Terminal shell in the VM (or by re-running the app's X11 setup/repair):

```sh
systemctl --user cat cloudy-x11-vm.service   # must show unix:$BASE/x11-tunnel.sock and --cookie-file
systemctl --user restart cloudy-x11-vm.service
```

Then, while Terminal remains visible and the Cloudy-X11 session host is visible:

```sh
DISPLAY=:99 XAUTHORITY=$HOME/.local/share/cloudy/Xauthority xeyes &
DISPLAY=:99 XAUTHORITY=$HOME/.local/share/cloudy/Xauthority xterm &
```

Expected: two native window tasks, each with a distinct output/Surface; verify
`window xid=...` callback pairs and task IDs.


## Why the user's xeyes produced no window (2026-10-18)

Post-run inspection found the main app process alive but **no
`dev.cloudy.x11:x11` process**. The VM tunnel and base proxy were healthy
(`/x11-tunnel` 101 previously), but the engine service had stopped after a
tunnel-loss/failed start. xeyes connected to the VM display proxy, but no app
engine was present to receive the O frame, so no window callback and no task
were produced. The service must be restarted and ready **before** the client.

Fix committed: `PerWindowSessionActivity` now requests the engine foreground
service from `onCreate`, `onResume`, and `onNewIntent`, so reopening/bringing the
session host restarts a dead engine. This is the path for the next check.


## User-visible control (2026-10-18)

The app now exposes **Native X11 session (per-window)** in the Cloudy-X11
launcher. It starts the small session host with free-form bounds and closes the
launcher, so the user does not need an adb-only intent. The session host starts
or restarts the engine on create/resume/new-intent. This is the supported route
for the next xeyes/xterm check.


## Live engine state after single-flight fix

`NativeEngineService` now serializes startup (`starting`) so the session host's
create+resume requests cannot race two tunnel handshakes. The latest run shows:

```text
CloudyX11Session: engine start requested
CloudyX11Native: start ignored: started=false starting=true
CloudyX11Native: tunnel connected display=:99
CloudyX11Native: onNativeWindow callback registered
CloudyX11Native: native ready display=:99
CloudyX11Renderer: renderer connected / windows committed
```

The engine and session host are alive. No X client was observed in the final
run because ADB text input landed in the session-host window, not the Terminal
shell. The user must tap the Terminal prompt and run xeyes; this is an input
focus issue, not an engine/task stub.


## Overlay permission and live readiness

The session host now exposes the documented **Display over other apps** setup
(`SYSTEM_ALERT_WINDOW`), which is Android's user-grantable background-activity
launch exemption. On-device:

```text
CloudyX11Session: overlayPermission=true
CloudyX11Native: tunnel connected display=:99
CloudyX11Native: onNativeWindow callback registered
CloudyX11Native: native ready display=:99
CloudyX11Renderer: renderer connected
CloudyX11Renderer: windows committed
```

The VM base bridge returned `101` and the engine is alive. The final run still
did not receive an xeyes client because injected text landed in the session-host
window rather than the VM Terminal. Tap the Terminal prompt and run the command
below; this is the sole remaining trigger.


## Simplified wait-for-window UI

The user-facing launcher is reduced to a status line, **Open Terminal**, and a
one-time **Set up X11** action. The preset launcher, custom-command box, full
desktop and native-session controls are removed from the default screen. The
engine starts automatically on resume and remains waiting; the native engine
logs show `native ready :99`, `renderer connected`, `windows committed`. The VM
X11 window is expected to arrive from an ordinary Terminal launch; the app does
not require an in-app app picker.


## Detailed live check (2026-10-18)

- Installed Terminal package resolves to
  `com.android.virtualization.terminal/.new2.ui.MainActivity`; the previously
  installed APK lacked `<queries>`, so `getLaunchIntentForPackage` could return
  null. The current manifest has the package query and an explicit MainActivity
  fallback.
- The installed setup asset is v2 and writes to
  `~/.local/share/cloudy/cloudy-x11-setup.sh`, removing the old
  `~/cloudy-x11-setup.sh`. The embedded VM unit is the Unix-socket/cookie form.
- A raw `/x11-tunnel` probe on 14500 timed out. That means the current live
  14500 bridge is still the old/legacy path and/or the VM private :99 proxy is
  not running. The app engine therefore cannot get a config; `xeyes` correctly
  reports `Can't open display :99` because no process owns the VM X99 socket.
- Opening the Cloudy-X11 app only starts the Android engine. It cannot start the
  VM-side proxy; that is the one-time VM setup's job.
- The app now receives engine state broadcasts: `ready` or
  `VM display not ready. Run Set up X11 once.` instead of silently pretending.
