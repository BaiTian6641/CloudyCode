# Remote session presentation and local controls

**Authority:** user direction, 2026-10-16. **Status:** shared product UX contract; no implementation or qualification claim. **Scope:** Cloudy Desktop's Googlebook RDP, VNC and integrated streaming session surfaces, covering P1-S2, P3-S7/S8 and P5-S2. Windows/Linux/macOS host-side software remains a separate delivery track. This does not create native client ports, a Parsec/TeamViewer protocol dependency, or an embedding/reuse entitlement.

Read [desktop workflows](desktop-workflows.md), [interaction](interaction.md), [screen specifications S07](screen-specs.md#s07--embedded-desktop-session), [RDP interaction](desktop-rdp-interaction-contract.md), [backend boundaries](../02-architecture/remote-desktop-backends.md) and the [Googlebook desktop contract](../03-platform/googlebook/desktop-application-contract.md). Their protocol, trust, capture and lifecycle requirements continue to apply. This document specifies the shared presentation decision and its acceptance cases.

**Explicit scope clarification from the user:** Parsec/TeamViewer inform the interaction logic **only after a remote connection becomes ready**. Discovery, device lists, connection profiles, pre-connection navigation and authentication keep Cloudy's own information architecture and design language. The connection flow may disclose the selected fullscreen preference, but it does not adopt a competitor's device browser or dashboard. Connected controls also retain Cloudy's tokens and components; the reference is workflow logic, not a replacement visual theme.

## Connected means a usable session surface

A deliberate Connect action defaults to **enter fullscreen when that connection becomes ready**, with a compact local session overlay. The connecting UI makes this presentation intent visible before activation. Ready requires the current engine attempt's authenticated/session-established state, an attached rendering surface and its first valid presented remote desktop frame; a socket, authentication callback, decoder counter or old retained frame alone is insufficient. View-only sessions can be ready while input is unavailable; label that distinction. Actual visible playback and input remain independently qualified.

Bind the pending fullscreen intent to that explicit attempt and foreground window. Cancel it on cancellation, disconnect, window loss of foreground ownership or a newer attempt. A late callback cannot fullscreen another session. Background connection completion, automatic reconnect, process recreation and returning from another task never seize focus or initiate a new fullscreen request. Show a local Enter fullscreen action when appropriate. A session already fullscreen can retain that presentation during reconnect while showing truthful reconnect/input-disabled state, without reclaiming focus or capture.

Offer an explicit profile preference to open windowed; its initial value is fullscreen. Changing presentation during a session takes effect immediately, and remembering a different future default requires an explicit preference action. Exiting fullscreen must not cause another automatic entry later in the same attempt. Fullscreen, maximized, windowed and input-captured are separate observed states.

## Platform request and safe window fallback

Use the public [Activity fullscreen API](https://developer.android.com/reference/android/app/Activity#requestFullscreenMode(int,%20android.os.OutcomeReceiver%3Cjava.lang.Void,%20java.lang.Throwable%3E)), API34, with `FULLSCREEN_MODE_REQUEST_ENTER` / `EXIT` and an `OutcomeReceiver`. Official guidance requires a user-input response and a pinned or topmost activity on the focused display; `onTopResumedActivityChanged` helps determine eligibility. Approval/rejection precedes configuration change. Callback dispatch is undefined on API36.1 and below; marshal UI updates safely. Exit restores prior status only after entry through this API. Checked 2026-10-16; existing source/API mapping is G24/API01.

Cloudy's asynchronous Connect-to-ready intent is an original UX choice and needs device qualification against those rules. If deferred entry cannot satisfy platform eligibility, remain usable windowed and offer an immediate user-activated Enter fullscreen action. Handle denial, unavailable API and lifecycle changes without retry loops, alternate force-fullscreen mechanisms or false success indicators. Reconcile callback and actual window/layout changes before displaying completion. Preserve normal resizable desktop behavior and explain an unsuccessful request locally without blocking the connection.

Keep system caption controls, window/task switching, native insets and recovery gestures operable. Do not draw fake OS caption buttons or cover system interaction regions. Fullscreen does not imply permission to suppress all system navigation. On exit, restore the platform-managed prior window where supported, with a usable viewport even if the former display/layout no longer exists.

## Compact overlay and remote viewport

The remote image is the primary work surface. Avoid a permanent app sidebar/header inside a connected session and do not tint, blur or recolor remote pixels to match Cloudy. Use [canonical tokens](../../shared/design-system/tokens.json), current approved design feedback, existing density/type roles and native controls for local chrome.

Initially show a compact readable overlay identifying the destination and escape routes, then collapse after idle. Keep a small labeled or accessibly named local affordance reachable at all times; reveal through pointer activation, keyboard focus and accessibility navigation, with touch activation as an optional fallback. A hidden edge gesture or hover-only hotspot cannot be the only route. The collapsed control occupies only its actual hit area: no invisible full-width strip may swallow remote clicks. Position it clear of system captions and remote work hotspots; allow a bounded reposition/pin preference. Do not hide controls while focus, hover, a menu/dialog, accessibility exploration or an actionable failure requires them. Reduced motion and user text scaling remain effective.

The expanded overlay exposes the following shared anatomy; it can use a compact menu/overflow in narrow desktop windows while keeping release and exit reachable:

| Item | Required behavior |
| --- | --- |
| Context | Sanitized host/profile label, explicit RDP/VNC/stream lane, current attempt/session state and input ownership. Long labels remain readable in details; no credential appears. |
| Release input | Always local, releases capture and accepted held input, reveals/focuses local controls. Available independently of backend health. |
| Enter/Exit fullscreen | Reflects actual presentation and pending/denied result; changes local window presentation only. |
| Session settings | Shows input mode, scale/resolution, monitor, clipboard/audio and backend-specific options only with truthful capability states. Unsupported controls explain the unavailable boundary. |
| Diagnostics | Optional measured values with units, source, age and scope. Missing data says unavailable/unknown; no zero placeholder, invented latency score or live-looking stale value. |
| Disconnect | Stops this client connection through the real engine retirement path; preserves exact target and distinguishes stopping, retired and cleanup-uncertain states. |

Opening settings must not silently reconnect, downgrade security, change protocol, alter remote monitor topology or enable clipboard redirection. Commands perform their real operation or explicitly state unavailable; cosmetic switches and log-only handlers are not implementation. Product settings and deep diagnostics stay out of the remote image/data path; frames remain native.

## Local focus and remote input ownership

Fullscreen entry does **not** force pointer capture, relative mode or remote shortcut routing. Use the existing explicit capture policy and negotiated engine capabilities. Show capture/input owner and the release chord before capture. Reserve **Ctrl+Alt+Shift+R** locally under the existing qualified/remappable policy; consume the recognized activation locally and do not deliver its triggering key to the remote session. If preceding modifier downs were already accepted remotely before chord recognition, send their balancing releases when possible and clear local held state; do not claim those earlier events can be recalled. If the OS intercepts that chord, an independently reachable local Release input control and focus-loss escape remain available. Ordinary Escape remains usable by remote editors unless a focused local overlay/menu handles it.

Overlay activation first transfers ownership to local UI and releases remote held keys/buttons that the session accepted. No click, scroll, shortcut or IME text used to operate local controls may also reach the host, including the opening click and the closing dismissal event. Hovering alone does not transfer keyboard focus. Use one owner for the complete pointer/key sequence; an up event after local ownership change cannot synthesize a new remote down. Native local fields retain editing and composition; remote text commits follow exactly one qualified delivery path.

On dialog/menu, capture/focus loss, task switch, display change that invalidates coordinates, disconnect or ownership change, release accepted held input when transport permits and clear local held state. If releases cannot reach the host, report the interruption rather than claiming remote cleanup. Re-entry requires deliberate focus/input acquisition; never replay held modifiers, clicks, drags, composed text or queued input. The first acquisition action is consumed locally rather than clicking through to a remote application. Late callbacks/input from a retired surface or earlier attempt have no authority.

If capture prevents a pointer from reaching the overlay, the local release chord reveals it and releases capture. If keyboard escape is unavailable, focus/task switching and accessible local controls must still recover. A capture mode with no qualified safe exit is unavailable for that device/window combination. Input release must not depend on media decoding, network responsiveness or a successful protocol disconnect.

## Geometry, displays and retirement

Fit the full remote framebuffer into the available content rectangle without distortion by default; use letterboxing while window, DPI, inset or negotiated remote resize changes are in flight. Map pointer coordinates using the actual displayed frame/content transform. Letterbox, overlay and outside-content clicks do not clamp to a remote edge. Recompute mapping atomically with the matching visible geometry; suppress unsafe pointer delivery while no valid transform exists. Keep remote pixel dimensions, local scaling and optional zoom distinguishable. A resize preference does not prove server acceptance or create a new remote session silently.

Local fullscreen affects the current Googlebook window/display. Remote monitor selection, multiple windows and protocol multi-monitor negotiation are separate backend capabilities, with explicit server/build support. Do not promise spanning displays or change a host display arrangement merely because fullscreen succeeds. External display moves/unplug, fractional scaling and topology changes preserve escape controls and invalidate stale mappings; unsupported options remain honest.

Disconnect releases local capture/input promptly, cancels pending fullscreen intents and awaits known engine/surface retirement. Do not label stopped until owned resources are retired. If cleanup is uncertain, show that state and block conflicting reuse according to the existing lifecycle contract. Leaving fullscreen or closing an overlay does not disconnect; disconnecting is not a claim to log out or terminate the remote user's desktop. A separate remote logout/termination command requires its own supported operation and consequence flow. External streaming launch remains a labeled external milestone; Cloudy cannot promise its own overlay controls inside another app.

## Acceptance and evidence

Documentation alone does not require fresh screenshots or device reruns. Implemented surfaces must satisfy both rendered design review and real interaction/lifecycle checks; synthetic visual fixtures establish appearance only. Bind results to app/build, engine/server, device/OS, attempt and operation. A missing Windows/macOS host does not block Linux work or certify another host row.

| Case | Required acceptance |
| --- | --- |
| Ready/default | Foreground explicit Connect enters fullscreen only after current first valid presented frame and eligibility; windowed preference, slow auth/decode, stale ready callback, cancel and view-only status remain truthful. |
| Platform/lifecycle | Denial/API absence remains usable windowed; callback threading is safe; no surprise entry on background completion, reconnect, task return or recreation; enter/exit/restore and system caption/task controls work. |
| Visual contract | Light/dark, all three existing density modes, 100–200% text scale, narrow/short tiled window and fullscreen; long labels, expanded/collapsed/pinned controls, denied/error/reconnect/cleanup-uncertain states. Remote pixels remain unobscured except the small active local control region. |
| Input isolation | Physical keyboard, mouse and trackpad separately; overlay open/close/scroll/dialog/IME never send phantom remote input; held key/button release, dedicated chord, OS interception, unavailable capture and keyboard-only/accessibility/touch recovery. |
| Geometry | Resize, DPI/fractional scale, monitor selection where negotiated, external-display move/unplug and letterbox edges preserve correct coordinates and native recovery; no protocol capability inferred from local fullscreen. |
| Retirement | Disconnect during connect/frame wait/capture/reconnect; stale callbacks rejected, known versus uncertain cleanup visible, no automatic reacquire or input replay and no stranded local capture. |

These cases supplement S07, Q-P02 and the existing per-lane P0-S4/P3/P5 acceptance cases; they do not replace RDP relative-input/3D, security, performance or sustained-operation gates.

## Experience references and evidence boundary

Checked 2026-10-16. Parsec's official [overlay guide](https://support.parsec.app/hc/en-us/articles/32381603663636-Stream-Overlay-Stats-and-Logging) describes a connected-session control button, settings, diagnostics and disconnect, with hardware/software-dependent availability (page updated 2025-03-17). Its [Windows app guide](https://support.parsec.app/hc/en-us/articles/32381199341716-Parsec-App-for-Windows) documents connection window-mode preference and separately configured overlay, hotkeys and immersive input. These observations support the compact-control reference; they do not establish Googlebook behavior or a Parsec reuse API.

TeamViewer's official [remote session toolbar guide](https://www.teamviewer.com/apac/global/support/knowledge-base/teamviewer-remote/remote-control/remote-session-toolbar/?language-switched=true) documents fullscreen enter/exit and toolbar collapse/expand. The official search-extracted page content was inspected; the canonical English direct URL returned a fetch error in this review, so a full page-body review is not claimed. Its product/version/platform-specific controls are experience references, not Cloudy backend capabilities.

Cloudy's default ready-time fullscreen intent, ownership rules, recoverable collapse behavior, no-replay policy and acceptance matrix above are original project requirements from the user direction and existing contracts. No competitor's automatic focus/capture, shortcut choice, remote control permission or metrics semantics are inherited.
