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

## Protocol spike result (2026-10-17)

Checked the xpra source and the HTML5 client:

- `window_move_resize` exists only as a **server→client** event
  (`xpra/client/base/record.py`, HTML5 `Client.js` handler). There is **no
  client→server resize packet**; the server ignores one if sent.
- Client-side window drags are expressed as pointer events; the server's
  window manager performs the resize.
- Therefore two viable routes exist:
  1. **WM route (implemented now):** run an external EWMH window manager
     (openbox) in a per-app `start-desktop` session, maximize the app to the
     X screen, make the X screen equal the Android window's physical size, and
     reload/resize on OS-window resize. The app fills its window and follows
     screen resizes.
  2. **Native client route (long-term):** a native Android X11 surface — either
     a native xpra protocol client (one Activity per X window, pointer/keyboard
     packets, server WM via pointer drags) or a native X server. This is the
     only way to guarantee app-window identity for arbitrary dialogs and
     clients that ignore the WM.

## Architecture (WM route)

```
Android freeform Task (one per app)
  X11Activity / WebView
    token proxy 127.0.0.1:<port>
      xpra start-desktop :N (resize-display=yes)
        Xvfb screen = Android window physical size
        openbox (no decorations) maximises the app
        app (firefox-esr / xterm / custom)
```

- The session daemon allocates a display, xpra port, proxy port and starts
  openbox before the app.
- The WebView sends `cloudy_w=<physical width>`; the proxy rewrites the HTML5
  client viewport to that width, so the X screen is physical, not CSS-sized.
- On Android layout resize, the viewer reloads with the new width; xpra resizes
  the X screen; the daemon re-maximises the app; openbox applies the new screen
  size.
- The daemon fit thread uses `xdotool windowstate --add
  MAXIMIZED_VERT/MAXIMIZED_HORZ` on the top-level window and re-applies it after
  screen changes.
- The xterm preset uses a 13pt Xft font for readable physical-pixel text.

## Milestones

1. **Goal + protocol spike** — done (this document).
2. **WM session** — per-app `start-desktop` + openbox + decorations off;
   app maximised to the screen; initial OS window is exactly the app.
3. **Coupled resize** — OS window resize → viewer reload with new physical
   width → X screen resize → openbox/daemon re-maximise → app reflows.
4. **Per-window dialogs/input** — per-window rendering for dialogs (filtered
   clients or the native client), pointer/keyboard routing, focus.
5. **Native surface (optional replacement)** — native xpra client or X server
   if the WM route cannot cover arbitrary clients; removes the WebView/canvas
   dependency and gives exact app-window identity.

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

- openbox may not re-maximise on every screen change; the daemon re-applies
  maximisation and logs the result.
- The xpra HTML5 client's CSS/DPR interaction is the source of the former
  letterboxing; the viewport rewrite + physical width are load-bearing.
- Multiple clients/windows per session require sharing and per-window filtering;
  dialogs inside the app window are the interim behavior.
- A native client is a substantial protocol/render/input implementation; it is
  staged after the WM route acceptance.
