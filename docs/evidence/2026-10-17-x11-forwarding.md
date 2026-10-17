# Built-in X11 forwarding, first iteration (2026-10-17)

## Goal

Launching an X11 application in the Linux VM (e.g. `firefox`) should surface its windows, dialogs and input in the app itself — a MobaXterm-like built-in X11 path — instead of requiring separate third-party software.

## Engine decision

A true native per-window X server on Android requires an Xorg DDX/GLES engine (Termux:X11 / Xorg) bundled into the app — a large native project with GPLv3 implications. For the first built-in iteration the app hosts an **xpra seamless X11 server's HTML5 client**:

```
X11 app in VM (DISPLAY=:100)
   ↕ xpra (Xvfb + seamless windows, dialogs, input events)
   ↕ HTTP/WebSocket on guest 14500 (HTML5 client)
Terminal app port forwarding → Android 127.0.0.1:14500
   ↕
Cloudy X11 window (origin-locked WebView; WebSocket)
```

xpra handles all X11 windows/dialogs/events, clipboard and keyboard/mouse input; the WebView is only the renderer/host. This is the same conceptual model as MobaXterm's embedded X server (X windows inside its own host window), not separate Android OS windows. A native Xorg engine remains future work if per-OS-window behavior is required.

## Implementation

| Piece | Detail |
|---|---|
| `tools/device/cloudy-x11-setup.sh` | Adds the official `xpra.org` Debian repository (the stock VM apt has no xpra candidate; key installed under `/usr/share/keyrings/xpra.asc`), installs `xpra` + **`xpra-x11`** (seamless X11 support) + **`xpra-html5`** (the served client) + `xvfb`, enables a systemd user unit (`cloudy-x11.service`) running `xpra start :100 --daemon=no --bind-tcp=0.0.0.0:14500 --html=on --tcp-auth=none`, linger enabled. Documents `DISPLAY=:100 firefox &`. |
| `X11Activity` | Cloudy X11 window. Polls `127.0.0.1:14500` off the main thread; when up, loads the HTML5 client. When down, shows a Cloudy setup card (Copy setup command / Open Terminal / Retry) and explains the flow. Restores the setup card after three failed probes. |
| WebView hardening | Only `http://127.0.0.1:14500` may load: navigation override + subresource interception return an empty response for any other origin; file/content access off; no JavaScript/native bridge; network loads enabled only while the host is up. |
| Entry points | Launcher alias **Cloudy X11** (separate launcher icon) and a startup-screen text action. Activity label `Cloudy X11`. |

## Verification

- App builds; the `Cloudy X11` launcher alias is registered in the package manager.
- Host window verified on the HP Googlebook 14 with a stub HTTP server on port 14500 (via adb reverse): the WebView loaded only the local origin, accepted keyboard focus and rendered the page in the Cloudy window (screenshot `x11-host-stub.png`).
- Guest script passes `bash -n`; xpra's single TCP endpoint serving the HTML5 client and WebSocket upgrades is per upstream docs ("A TCP endpoint can also serve the HTML5 client and automatically recognize WebSocket connections").
- Real xpra/Firefox run requires the one-time VM setup; to test: run the copied command, install Firefox, `DISPLAY=:100 firefox &`, open Cloudy X11.

Screenshot: `docs/evidence/x11-host-stub.png`.

## Security review (GLM-5.3, opencode-go/glm-5.3)

Initial review: **HIGH** risk. Findings and fixes:

| # | Severity | Finding | Fix |
|---|---|---|---|
| 1 | High | xpra endpoint trusted on a bare TCP connect; any same-device app could squat 127.0.0.1:14500 | Per-install X11 token; VM-side token proxy (`cloudy-x11-proxy.py`) gates every request/WebSocket on `Cookie: cloudy_x11=<token>`; readiness is an HMAC-SHA256 challenge (`/__cloudy/ping?nonce=`) verified by the app. The WebView never loads on a bare port check. |
| 2 | High | Workbench session cookie shared across app WebViews (cookies ignore ports) | The X11 WebView runs in a dedicated WebView **Profile** (`ProfileStore`/`WebViewCompat.setProfile`), with its own cookie jar and DOM storage; token cookie set only there. If profiles are unavailable, X11 stays disabled (fail closed). |
| 3 | Medium | xpra bound `0.0.0.0` with no auth inside the VM | xpra now binds **127.0.0.1:14501** only; the token proxy is the sole listener on the forwarded port. |
| 4 | Medium | Exported X11 activity, default launch mode | `X11Activity` is non-exported with `singleTask`; only the launcher alias is exported; distinct X11 icon. |
| 5 | Low | Unrestricted WebSocket egress while the page is loaded | Root cause (untrusted page) removed by #1; the page only exists behind the authenticated proxy. |
| 6 | Low | Polling while paused; hidden page kept alive | Polling stops in `onPause`, resumes in `onResume`; loss of the server blanks the WebView (`about:blank`) and shows the setup surface. |
| 7 | Low | Unpinned xpra package (supply chain) | Recorded; pin the tested version in the release build. The setup script otherwise uses quoted heredocs, no secrets, no interpolation. |

Verification after the fixes (HP Googlebook 14, adb reverse standing in for the Terminal port forward):

- Token proxy unit test: no cookie and wrong cookie → **403**; correct cookie → 200; HMAC ping matches the expected HMAC-SHA256.
- Authenticated load: the X11 window loaded the stub page (`X11 client loaded: http://127.0.0.1:14500/`) in an isolated profile; screenshot `x11-authenticated.png`.
- **Fail-closed against a squatter**: with a plain HTTP server (phishing page) bound to 14500 instead of the proxy, the app showed the Cloudy setup surface and never loaded the page; screenshot `x11-fail-closed.png`.

Residual accepted tradeoffs: the VM user and same-user processes can still reach xpra's loopback port or `DISPLAY=:100` directly (inherent to X11); the stream is plaintext on-device loopback/VM bridge (it may carry keystrokes, including passwords); the WebView profile API requires a modern WebView (otherwise the feature is disabled).

### Re-review (GLM-5.3, final)

The first hardening round was re-reviewed and two **Critical** bypasses were found and fixed:

1. **Challenge leaked the secret.** The ping carried the token cookie to the very party it was authenticating; a protocol-aware squatter could read the token, compute the HMAC and pass. Fixed: `/__cloudy/ping` is answered **without any cookie** (HMAC of the unguessable token over the nonce only; no oracle), and the app no longer sends the cookie on the ping (`X11Activity.authenticate`). The HMAC is now genuine proof-of-possession with no secret disclosure.
2. **Attacker-controlled token via the exported alias.** The debug `token` intent extra overrode the real token and was reachable through the exported launcher alias. Fixed: the override is **deleted**; the activity always uses `GbCodeApp.x11Token()`.

Additional hardening: HTTP 401/403 and 5xx responses blank the WebView immediately (session takeover); the setup command clipboard entry is marked sensitive; polling pauses/resumes with the lifecycle; server loss blanks the page.

Final verdict from the reviewer on must-fix items: cookie isolation (#2) and exported/launchMode (#4) **resolved**; VM bind/auth (#3) **resolved in substance** (xpra loopback-only; token-gated proxy the sole listener; the proxy binds `127.0.0.1:14500` because the Terminal forwarder exposes loopback listeners only — verified on-device); challenge/auth (#1) resolved after the two Critical fixes.

Verification after the final fixes:

- Proxy unit: `/` without/wrong cookie → 403; with cookie → upstream; `/__cloudy/ping?nonce=N` answers the correct HMAC **without a cookie**; wrong-token HMAC differs.
- App fail-closed with a **protocol-aware squatter** (returns a forged HMAC and a phishing page): the window shows the Cloudy setup card and never loads the page (`x11-fail-closed-protocol-aware.png`).
- Authenticated WebView load was verified with a test token before the debug override was removed (same HMAC/load path); the release path now uses the real per-install token and the same proxy challenge. The proxy/HMAC unit tests and the protocol-aware squatter test cover the post-fix behavior; a live xpra/Firefox run remains the one-time VM setup test.

Residuals (accepted/documented): the proxy binds loopback only (required for forwarding); one-time setup token appears in the pasted command (clipboard marked sensitive; bash history note); a squatting server that seizes the port mid-session is caught by the ≤2 s re-auth/403 handling with a small race window; same-user VM processes can read the token or use `DISPLAY=:100` (inherent X11).

## Live end-to-end verification (2026-10-17)

The live setup initially failed in three distinct ways, all fixed:

1. **Terminal forwarder exposes loopback listeners only.** xpra on `127.0.0.1:14501` appeared on Android; the proxy on `0.0.0.0:14500` did not. The proxy now binds `127.0.0.1:14500`.
2. **xpra's package-owned system socket/service also bind 14500.** `xpra-server.socket` (`[::]:14500`, systemd/PID 1) caused `EADDRINUSE` for the proxy and answered the app's challenge with 403. The setup now stops/disables `xpra-server.socket` and `xpra-server.service`; our per-user xpra remains on 14501.
3. **The setup-embedded proxy drifted from the tested standalone.** Its `/__cloudy/ping` required the token cookie, but the app's challenge is deliberately cookie-free, so every probe returned 403 and the window stayed on "Looking…". The embedded copy is now byte-identical to `tools/device/cloudy-x11-proxy.py`; `tools/verify-x11-proxy.sh` asserts equality and a live cookie-free 200 + HMAC.

Verified state:

- Android listeners: `127.0.0.1:14500` (proxy) and `127.0.0.1:14501` (xpra); guest owners: `python3` proxy and `xpra` PID 524.
- Host→device ping through the forwarder: HTTP 200 with the expected 64-hex HMAC.
- App log: `CloudyX11: X11 client loaded: http://127.0.0.1:14500/`.
- xpra session LIVE at `:100`; `pgrep -a firefox` → `978 firefox-esr`; the Cloudy X11 window rendered the Firefox window and a second X client window.
- Rebuilt/installed app (`./tools/verify-x11-proxy.sh` green, `assembleDebug` green) and re-verified the load.


## Per-application windows (2026-10-17)

Each application now gets its own xpra session and its own Googlebook OS
window, instead of one desktop window hosting every X client.

Architecture:

- `cloudy-x11-sessiond.py` listens on `127.0.0.1:14509` (token-authenticated,
  cookie-free HMAC ping). The app's launcher asks it to start a preset or a
  custom command.
- Per session the daemon allocates a display (`:110`+), an xpra HTML5 TCP port
  (`14610`+) and a token proxy port (`14510`+), then starts xpra with
  `--start=<app>` and the proxy in front of it.
- The launcher starts `X11Activity` with `NEW_DOCUMENT|MULTIPLE_TASK` and
  document launch mode, so every session is a separate freeform task/window
  with the same launch bounds (72% x 78% of the display).
- Closing the window stops its xpra session and proxy (verified: ports and
  `xpra list` entry disappear).

Containment/sharpness:

- Viewer URL: `?floating_menu=no&autohide=yes&border=no&video=no&encoding=png`.
- Injected CSS removes xpra's wallpaper (`background-image:none`, black) and
  hides the toolbar and the in-page `.simple-keyboard`. Hardware-key input
  still works (typed `example.com` and loaded it with no on-screen keyboard).
- Server: Xvfb `2880x1800x24`, DPI 144, `--encoding=png`,
  `--video-encoders=none`, quality 100 (lossless; no video/JPEG blur).
- WebView: no zoom, hardware layer, zoom controls disabled.

Verified on the Googlebook: Firefox on proxy `14510` and xterm on `14511`
running concurrently in two tasks; both windows contain only the app on black;
no soft keyboard; typing works; per-window close cleans up the session.


### Physical viewport and window fitting (2026-10-17 revision)

The HTML5 client was laying out at the WebView's **CSS** width (about 800 px)
while the freeform window was ~1400 physical px. xpra consequently shrank the
X screen to the CSS size and the browser letterboxed it: black margins and
tiny text.

Fixes now in place:

- The viewer adds `cloudy_w=<physical width>` (plus `override_width`); the token
  proxy rewrites the xpra HTML5 client's viewport meta to that width, so the
  X screen is the physical window size, not the CSS size.
- The WebView reloads when its layout width changes (verified: `1398` -> `1900`
  after `am task resize`), so the X screen follows the Android window.
- A daemon-side fit thread (`xdotool`, top-level largest visible window)
  sizes the app window to the virtual screen; per-app sessions run **seamless**
  (no desktop WM frame) and the xterm preset uses a 13pt Xft font.
- Verified: a fresh terminal session fills its Cloudy X11 window with readable
  text (no wallpaper, keyboard, or desktop margins).

Remaining limit: after resizing the Android window, the X screen reloads and
resizes, but the app's own top-level window still does not reflow to the new
screen size, so black margins can remain. The xpra HTML5 canvas plus a virtual
X screen does not expose native X11 window-configure semantics. True
OS-window/app-window size coupling (the MobaXterm model) requires a native
X11 server/client that creates one Android window per X top-level and forwards
resize events both ways; the WebView/xpra model can only approximate it.
Workaround: reopen the session after a resize (or add a "Fit" action) so the
app starts at the new screen size.

## Limits

- One Android window hosting all X windows (xpra seamless UI), not one OS window per X top-level.
- Requires xpra in the VM and the Terminal app's port forward; the Terminal app must stay running.
- GPU/GL acceleration depends on xpra's rendering; software paths for Firefox may be slow.
- Not certified for arbitrary X11 clients/extensions; Firefox is the reference target.
