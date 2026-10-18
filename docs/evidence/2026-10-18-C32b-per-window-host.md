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


## Target-only setup v3

The setup asset was replaced with a 27 KB native-only installer. It:
- writes `~/.local/share/cloudy/cloudy-x11-setup.sh` and removes the old
  `~/cloudy-x11-setup.sh`;
- installs no xpra/sessiond and stops/disables the old prototype units;
- installs the VM `:99` proxy as a persistent user service with
  `unix:$BASE/x11-tunnel.sock` and `--cookie-file`;
- installs the 14500 bridge service without an xpra dependency;
- writes global `DISPLAY=:99`/`XAUTHORITY` and enables linger.

The APK asset and `tools/device/cloudy-x11-setup.sh` are identical. The native
unit is independent of the old xpra HTML5 viewer.


## Native-only v4 / weston clarification

The 14500 bridge source now contains only the cookie-free HMAC ping and the
authenticated `/x11-tunnel` Upgrade + raw pump. The xpra HTTP/HTML5 proxy,
renderer viewport rewrite, and upstream proxy code are gone. Setup v4 is 22 KB,
installs no xpra/sessiond/weston, stops/disables the old xpra and sessiond units,
and writes the same Unix/cookie VM service and 14500 bridge.

`weston.service` is a Google Terminal/Wayland desktop service, not part of
Cloudy-X11. Its `failed to create compositor backend` error can occur
independently; Cloudy-X11 X11 clients use `DISPLAY=:99` and the app's embedded X
server, not weston. The setup does not install, start, or depend on weston.


## Native setup screen

The setup button now opens `SetupActivity` (no WebView, no xpra, no app picker).
It copies the v4 command to the versioned path, opens Terminal, and is the only
setup surface. The old `X11Activity` manifest entry was removed; the xpra viewer
is not reachable from the app.


## Live diagnosis: no auto window after v4 setup (fixed)

- VM v4 was active: guest journal showed `[cloudy-x11-vm] app connected` and
  `[cloudy-x11-bridge] "GET /x11-tunnel HTTP/1.1" 101`.
- Engine reached `native ready display=:99`, mapped xeyes `0x20000a`, and the
  renderer received frames. The missing piece was Java: `NativeEngineService`
  did not override `X11Api.Callback.onNativeWindow`, so no host task was
  created; the launcher also never got a ready state after a task restore.
- Manual `NativePreviewActivity` run proved Surface binding (`bind surface
  output=1 window=0x20000a`) and visible xeyes pixels.
- Fix: each mapped top-level application/unclassified window allocates a
  distinct output and opens one free-form `NativePreviewActivity` task; output
  1 stays reserved for diagnostics. `onStartCommand(start)` re-broadcasts ready
  when the engine is already running.
- The Terminal's Weston Compositor top-level is excluded by title; it is the
  bypassed graphics screen, not an application.
- Live result: xeyes map 22:37:52.743 -> `launchWindowHost output=2
  xid=0x20000a title=xeyes`, host 455x357, Surface bound, `present copied=1`
  with nonblack eyes. No Weston host task. Launcher status:
  "VM display connected. X11 windows open automatically."
- Evidence: `/tmp/cloudy-x11-ctrlj.png`,
  `/tmp/cloudy-x11-ready-status.png`.
- Remaining cosmetic/behavior work for C3.3: the free-form caption still shows
  the app label rather than the X window title, and input/resize/close/dialogs
  are not yet wired.


## Window close/reopen and Firefox wrapper

- `onNativeWindow(mapped=false)` now removes the XID/output mapping and
  broadcasts `WINDOW_CLOSED`; the host Activity finishes and removes its task.
  A host closed by the user broadcasts `HOST_CLOSED`, which also clears the map.
  This fixes the black stale window and lets a reused XID be hosted again.
- Firefox's curl to `127.0.0.1:14509` is the retired prototype GUI wrapper, not
  `/usr/bin/firefox` (`firefox_head` showed the normal firefox-esr wrapper).
  Setup now appends a shell cleanup that removes old `firefox`, `cloudy-firefox`
  and `cloudy-run` aliases/functions and aliases firefox to `/usr/bin/firefox`.
  Immediate workaround: run `/usr/bin/firefox` in Terminal (DISPLAY=:99).


## Multi-stream Firefox and tunnel recovery

- Firefox opened a native host (`Mozilla Firefox`, output 2, 812x357) but the
  first long run ended with `CloudyX11Tunnel: bad frame type`. The VM proxy's
  O/D/C writer serialized only the app lookup, not `sendall`; Firefox's
  multiple X streams interleaved frame bytes. Added a dedicated `send_lock`.
- The VM proxy also rejected a new app tunnel while the Terminal-forwarded
  bridge was still half-open, producing HTTP 502. It now replaces a stale app
  tunnel and clears only streams owned by that app (identity-checked reader).
- The app closes all host tasks on tunnel loss and on engine-start failure;
  `EngineTunnel` logs the reader exception. Live sequence: first drop closed
  the stale host, the service reconnected, and the Firefox host reappeared and
  remained stable.
- Firefox must be launched as `/usr/bin/firefox` until the updated setup's
  shell cleanup removes the old 14509 wrapper. The VM display path itself is
  healthy (`DISPLAY=:99`, xdpyinfo, 14500).


## Free-form resize and host input

- Screenshot diagnosis found the resize complaint was a restored legacy
  `NativePreviewActivity` with no `window_id`/`output_id`: a black canvas that
  no resize could affect. The Activity now finishes immediately when either
  extra is missing; per-window hosts always carry both.
- Valid host resize verified with task 1000061: 455x357 -> 1000x800. Logs:
  `config ... 1000x800`, `surfaceChanged gen=1 frame=1000x735`,
  `bindWindowSurface output=2 xid=0x20000a returned=true`, frames 1002x737.
  The fixed-size xeyes content scales/crops; Firefox is the meaningful resize
  test.
- Pointer forwarding added through AIDL -> service handler -> JNI
  `lorieOutputPointer`; tapping inside the enlarged host moved the xeyes pupils.
  A tap on the window caption opened Android's App info menu, confirming that
  tap was chrome, not X input.
- Hardware key/text forwarding added via `lorieOutputKey`/`lorieOutputText`;
  IME soft-keyboard and title/icon refresh remain.
- Evidence: `/tmp/cloudy-x11-resize-before.png`,
  `/tmp/cloudy-x11-after-resize.png`, `/tmp/cloudy-x11-pointer-before.png`,
  `/tmp/cloudy-x11-pointer-after.png`.
