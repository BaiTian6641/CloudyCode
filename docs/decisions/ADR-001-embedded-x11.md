# ADR-001: Embedded X11 server for freeform per-window forwarding

- **Status:** Accepted (2026-10-17)
- **Supersedes:** the assumption that the xpra/HTML5 viewer path can provide
  MobaXterm-style per-window forwarding.
- **Related:** [X11 forwarding evaluation](../plan/x11-forwarding-evaluation.md),
  [native window surface goal](../plan/native-window-surface.md).

## Context

The product requirement is MobaXterm-style X11 forwarding: run an X11 program in
the Debian VM Terminal and get its window as a native, freely resizable Googlebook
OS window. The current stack streams an X display through xpra into a WebView:

- one OS window per **app session**, not per X top-level;
- HTML5 keeps remote windows inside the browser page;
- xpra exposes `window_move_resize` only as a server→client event, so the client
  cannot request a per-window resize; the shipped per-app seamless route fits
  the app to the Xvfb screen with xdotool, and a planned openbox WM route was
  not implemented;
- the Linux Terminal app's built-in X11 desktop is the Terminal app's own
  compositor, not a system X server available to other apps.

MobaXterm embeds a rootless Xorg server on the host and forwards X11; its default
"multiwindow" mode maps every X top-level to a native host window with native
resize. The Android equivalent requires an X server in the app plus an X11
forwarding proxy in the VM.

Googlebook-specific facts constrain the design:

- Googlebook runs Android with ChromeOS desktop foundations. Desktop windowing
  gives free-form, resizable windows, a fixed taskbar and a system caption bar;
  multi-instance is a documented first-class workflow (Alt+Tab, taskbar, header
  insets). Each X window can therefore be one Googlebook task.
- The Linux Terminal support page documents GUI programs launching in an
  **alternative graphics screen inside the Terminal app** (computer icon). The
  preview blog lists launcher-integrated Linux GUI as roadmap. That graphics
  screen is Terminal-owned and cannot be attached to other apps.
- The preview documents automatic localhost port forwarding to the **host
  browser**; a guest→host listener is not documented. The app→VM direction and
  the VM-side proxy must carry the X11 streams (S00.03 is the hard route gate).
- Window-independent VM persistence and launcher-integrated GUI are roadmap; the
  companion must recover when the Terminal/VM stops.
- The existing Code - OSS plan keeps the workbench Android-rendered and does not
  use a Linux GUI as the frontend. This decision adds an optional companion
  surface, not a new workbench frontend or a streamed Linux desktop.

## Decision

Adopt the embeddable `magicdesk-x11` engine (a Termux:X11 fork, GPLv3) as the
X11 engine, with:

1. A VM-side X11 forwarding proxy (display `:99`, Xauthority, client streams
   over the existing app-initiated control channel).
2. An Android native host around `embedded.h`: one freeform Activity/task per X
   window family, host-provided Surface and `LorieGraphics`, input/IME,
   `lorieOutputResize` for two-way resize, titles/icons, clipboard and
   `lorieCloseWindow`.
3. No X11 TCP listener; no xpra display in the default path.

The app and the combined distribution become GPLv3. Upstream Code - OSS/MIT
sources remain compatible; notices and corresponding source are required at
release.

## Consequences

- **Positive:** true per-window native windows and resize, direct X11 semantics,
  reuse of a mature X11 engine (window families, auth, input, icons, clipboard),
  and a transport that fits the existing app→VM direction.
- **Negative:** a substantial native/Android host implementation; `LorieGraphics`
  host backend; GPLv3 obligations; new build toolchain (NDK 27.3.13750724);
  the shipped per-app seamless/fit route remains only an interim demo.
- **Neutral:** the Terminal app's built-in desktop remains Google's own UI; the
  shell's `DISPLAY` must point at the forwarding proxy.

## Follow-ups

- X1: build `LorieNative`/`lorie-smoke` and pin the engine commit; record the
  license notice and NDK version. **Done:** engine `65c3ced0`, NDK
  `27.3.13750724`, `lorie-smoke` linked (20 MB); on-device smoke pending.
- X2: VM forwarding proxy + `lorieServerAcceptClient`; two independent windows.
- X3: Android host (Surface, input, resize, close, dialogs).
- X4: make the forwarding display the shell default; retire the xpra display
  path; keep the old route behind a debug switch until acceptance.
- S11: GPLv3 distribution/provenance review and corresponding-source offer.
