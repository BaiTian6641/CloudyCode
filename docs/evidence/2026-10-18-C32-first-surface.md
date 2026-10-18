# C3.2 first-Surface evidence — VM xeyes pixels in a native Android Surface

**Date:** 2026-10-18. **Device:** Googlebook `C1L63708PZ`, user 10, SDK 37.
**Tested source:** C3.2 CPU-graphics worktree, based on `c2be608`; the final
security/XKB edits in the same series were built and 16 KB-verified but were not
re-run on the device before the VM stopped. **No token/cookie is recorded.**

## Claim

A VM X11 client (`xeyes`, launched in the Debian VM with `DISPLAY=:99`) produced
pixels through the complete native path and into a real Android Surface:

```
VM xeyes -> VM :99 proxy -> 14500 /x11-tunnel -> APK :x11 service
  -> EngineTunnel O/D/C -> lorieServerAcceptClient -> LorieNative X server
  -> LorieConnection + CPU LorieGraphics -> ANativeWindow lock/unlockAndPost
```

Observed engine/device evidence:

```
CloudyX11Renderer: connectRenderer connected firstWindow=0 known=false
CloudyX11Native: tunnel connected display=:99 cookie_len=32
CloudyX11Native: native ready display=:99
CloudyX11Renderer: first mapped window xid=0x20000a title=xeyes role=0 parent=0x0 150x100
CloudyX11Renderer: bind surface output=1 window=0x20000a size=2073x1231 bound=1
CloudyX11Renderer: frame output=1 window=0x20000a 2075x1233 available=1
CloudyX11Renderer: draw src=2075x1233 dst=0.7,0.0 2071.6x1231.0 ... blend=0 swap=1
CloudyX11Renderer: present lock=0 window=2073x1231 fmt=1 target=2073x1231
CloudyX11Renderer: present copied=1 post=0 nonBlack=1664/3618
```

The Android Surface was a `SurfaceView` in the diagnostic
`NativePreviewActivity`; a screenshot shows the xeyes pixels. A cropped copy is
[`2026-10-18-C32-xeyes-pixels.png`](2026-10-18-C32-xeyes-pixels.png). The black
window in the capture is the still-visible launcher task; the xeyes pixels are in
the native preview task behind it. This is evidence of pixels, not a clean
per-window product capture.

**Key Surface fixes found during the run:**
- Initial geometry overrides (`ANativeWindow_setBuffersGeometry(0,0,...)` or
  per-acquire) made `ANativeWindow_lock` return `-22`; removing them and using
  the SurfaceView's RGBA format/size made lock/post succeed.
- A normal `SurfaceView` is behind the Activity window; the opaque black window
  hid every frame. `setZOrderOnTop(true)` made the engine Surface visible.
- Binding XID zero before a real window, and double-binding the same Surface,
  caused black frames/unstable locks. The service now waits for the first mapped
  top-level, binds once, and the launcher finishes when it starts the preview.

## What is not proven

- **Not per-X-window hosting.** One diagnostic preview binds the first mapped
  XID. Multiple X11 top-levels, one Android task each, dialogs/popups, stable
  XID+incarnation mapping and task metadata are not implemented/verified.
- **No resize/input/IME/close.** Engine `lorieOutputResize` is called on bind;
  native two-way resize, pointer/keyboard/IME, WM_DELETE and caption/taskbar
  reconciliation remain C3.3.
- **Clean install XKB not device-tested.** `assets/xkb.zip` and extraction were
  added after this pixel run; the run used XKB staged in app-private files. The
  current build compiles and passes 16 KB verification, but a clean-install
  engine start was not re-run.
- **Current security build not device-rerun.** HMAC pre-auth, non-exported
  engine service/preview, JSON redaction, tunnel-loss teardown and XKB extraction
  are in the current code/build; the xeyes capture predates those final edits.
- **VM lifecycle.** The Terminal VM's forwarded ports disappeared after the VM's
  Terminal was backgrounded/preview covered it; the user also reported a device
  unlock/reboot/charging issue. The app cannot keep clients flowing without the
  VM and its port forward. This matches the documented platform prerequisite;
  window-independent VM persistence remains roadmap. A clean front-task capture
  was lost when the VM stopped.
- **Alpha/channel fidelity** is only demonstrated by black/white xeyes; a
  colored/premultiplied fixture is still required.
- The preview's AIDL Surface path is app-internal; no per-window Activity/task
  manager or background-launch policy is claimed.

## Next executable C3.2 work

1. Convert `NativePreviewActivity` into a per-XID window host: service exposes
   `window` events (XID, role, title, parent, constraints, session generation),
   creates one task/Activity per independent normal top-level, and binds each
   output to its own Surface (nonzero output ID).
2. Apply the role policy: dialogs/owned hosts, popups without task entries,
   splash handoff, no root/dock/input-only tasks; never use PID/stream ID as
   identity.
3. Prove two xeyes/xterm-style normal windows as two independent free-form
   tasks, then dialog/popup behavior.
4. Add real input/resize/IME/close in C3.3; keep the 16 KB gate and no `-ac`.
5. Re-run clean-install XKB extraction and the HMAC tunnel path on a stable VM;
   record Terminal/Cloudy Code launch sources separately.
