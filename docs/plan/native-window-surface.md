# Goal: native per-window X11 surface for Googlebook OS

## Objective

Every X top-level window becomes a native Googlebook OS window:

- the OS window's size equals the X window's size at all times;
- resizing the OS window resizes the X application window (two-way);
- only the application is shown (no desktop, wallpaper, toolbar or letterboxing);
- text renders at physical pixel density;
- closing the OS window closes/stops its session.

This is the MobaXterm-style model, moved from a single virtual desktop canvas to
one OS window per X window.

**Direction update (2026-10-17):** the shipped code is the **per-app seamless
xpra route** (`xpra start`, Xvfb screen, `_fit_window` via xdotool, HTML5
viewer); the openbox/`start-desktop` WM route below was a planned experiment and
was **not implemented**. Both are interim. A re-evaluation against MobaXterm
establishes that xpra's HTML5/native clients cannot provide true per-window
identity and native resize (no client→server resize packet), and that the
Terminal app's built-in X11 desktop is its own compositor. The target is an
embedded X server in a **standalone Cloudy-X11 app/core** plus a **VM-wide**
X11 forwarding display (`DISPLAY=:99`) used by both Terminal shells and Cloudy
Code's Linux VM backend, so the Terminal's built-in graphics screen is bypassed;
one Android host per X window family. GBCode/Cloudy Code are consumers, not
dependencies, and the later remote-console app reuses the forwarding core. See
[x11-forwarding-evaluation.md](x11-forwarding-evaluation.md). The embeddable
GPLv3 `magicdesk-x11` engine is the chosen candidate
([ADR-001](../decisions/ADR-001-embedded-x11.md)); do not invest further in the
HTML5 viewer path.

## Protocol spike result (2026-10-17)

Checked the xpra source and the HTML5 client:

- `window_move_resize` exists only as a **server→client** event
  (`xpra/client/base/record.py`, HTML5 `Client.js` handler). There is **no
  client→server resize packet**; the server ignores one if sent.
- Client-side window drags are expressed as pointer events; the server's
  window manager performs the resize.
- Therefore two viable routes exist:
  1. **WM route (planned; not implemented in the shipped code):** run an external EWMH window manager
     (openbox) in a per-app `start-desktop` session, maximize the app to the
     X screen, make the X screen equal the Android window's physical size, and
     reload/resize on OS-window resize. The app fills its window and follows
     screen resizes.
  2. **Native client route (long-term):** a native Android X11 surface — either
     a native xpra protocol client (one Activity per X window, pointer/keyboard
     packets, server WM via pointer drags) or a native X server. This is the
     only way to guarantee app-window identity for arbitrary dialogs and
     clients that ignore the WM.

**Shipped implementation (as of `df86bbe`):** the session daemon runs `xpra
start` (seamless) with an Xvfb screen sized to the requested window, launches
the app through a wrapper, and `_fit_window` moves/sizes the app's top-level to
the screen with xdotool (`windowmove`, `windowsize`, `windowstate MAXIMIZED_*`).
`--resize-display=yes` follows the HTML5 client's physical size. No openbox or
`start-desktop` is started; the openbox/matchbox packages are not used.

## Shipped architecture (per-app seamless + fit)

```
Android freeform Task (one per app session)
  X11Activity / WebView
    token proxy 127.0.0.1:<port>
      xpra start :N (seamless, --resize-display=yes)
        Xvfb screen = requested Android window size
        app (wrapper; no window manager)
        _fit_window (xdotool move/size/EWMH maximize state)
```

- The session daemon allocates the display, xpra port and proxy port, starts
  xpra, then launches the app through a wrapper. No `start-desktop` and no
  window manager are used.
- `--resize-display=yes` makes the virtual screen follow the HTML5 client's
  physical size. On Android layout resize, the viewer reloads, xpra resizes the
  X screen, and `_fit_window` moves/sizes the app's top-level again.
- `_fit_window` uses `xdotool search`, `windowmove`, `windowsize` and
  `windowstate --add MAXIMIZED_VERT/MAXIMIZED_HORZ`; it re-applies on screen
  changes and logs the result.
- The xterm preset uses a 13pt Xft font for readable physical-pixel text.

**Earlier WM-route design (not implemented):** an openbox/`start-desktop`
session with a WM-owned maximize was designed but is absent from the shipped
code. It is superseded by ADR-001; do not use it as a description of current
behaviour.


## Dynamic window discovery (implemented)

The desired UX — type an app in the VM Terminal and get a Googlebook OS window —
is implemented through per-application sessions (not the shared base display):

- The setup installs `cloudy-firefox` and `cloudy-run` in the VM and aliases
  `firefox` to `cloudy-firefox` in `~/.bashrc`.
- `cloudy-firefox` POSTs `{"app":"firefox"}` to the session daemon; the
  daemon starts a dedicated xpra seamless session + token proxy + app.
- The launcher polls `/list` every 2 s and opens one freeform viewer per new
  session (title/name from the session), which loads the per-session proxy.
- `cloudy-run <command>` starts a custom app the same way.
- Verified: launcher Firefox preset -> session started on 14510 -> viewer
  shows the Firefox window (screenshot). The Terminal alias invokes the same
  `/start` and the same `/list` watcher.

Remaining for the shared-display path (`DISPLAY=:100`, raw X apps): xpra
rejects a second HTML5 client with "session busy" even with `--sharing=yes`
and unique usernames; the per-window `/windows` viewer therefore stays disabled
until multi-client sharing is resolved (or a native multiplexing client exists).
The per-app wrapper path is the interim dynamic-window UX. It is per-shell and
per-app; it cannot capture Cloudy Code's integrated terminals or arbitrary VM
processes. The target is the VM-wide forwarding display described in the
evaluation, with the Terminal's built-in graphics screen bypassed.

Remaining polish: the per-app viewer's window-vs-app sizing (fit/black margin),
background window discovery when the launcher is not resumed (foreground
service), and per-window dialogs.

## Milestones

1. **Goal + protocol spike** — done (this document).
2. **WM session (planned; not implemented)** — per-app `start-desktop` +
   openbox + decorations off; app maximised to the screen; initial OS window is
   exactly the app.
3. **Coupled resize (implemented for the shipped seamless route; WM variant
   not)** — OS window resize → viewer reload with new physical width → xpra
   `--resize-display` X screen resize → `_fit_window` re-sizes the app; the
   planned openbox variant was not implemented.
4. **Per-window dialogs/input** — per-window rendering for dialogs (filtered
   clients or the native client), pointer/keyboard routing, focus.
5. **Embedded X server (target)** — replace the xpra/HTML5 surface with an
   embedded X server in the app and an X11 forwarding proxy in the VM; one
   Android host per X window family with native resize. The native xpra-client
   variant is rejected for the resize requirement (see the evaluation).

## Acceptance gates

- Launch Firefox and xterm: two separate OS windows; each contains only its app.
- `xdotool getdisplaygeometry` equals the WebView's physical content size
  (within the window frame) for both.
- Resize each OS window by 200–400 px: within 1–2 s the app reflows to the new
  content area; no black root margin remains.
- Text is readable at physical density (xterm 13pt; no CSS upscaling).
- Closing a window stops its session, proxy and app; the other window is
  unaffected.

## Risks

- The shipped `_fit_window` loop may miss the app window or fail to re-apply on
  screen changes; it logs and retries every 0.5 s. The planned openbox variant
  was not implemented.
- The xpra HTML5 client's CSS/DPR interaction is the source of the former
  letterboxing; the viewport rewrite + physical width are load-bearing.
- Multiple clients/windows per session require sharing and per-window filtering;
  dialogs inside the app window are the interim behavior.
- A native client or embedded X server is a substantial protocol/render/input
  implementation; it is the target per ADR-001, not staged after the
  unimplemented WM route.
