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
