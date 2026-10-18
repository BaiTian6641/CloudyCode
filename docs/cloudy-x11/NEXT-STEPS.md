# Cloudy-X11 — detailed next-step plan

**Status:** active execution plan. **Last updated:** 2026-10-17.
**Read order after context compression:** [`GOAL.md`](GOAL.md) → [`TODO.md`](TODO.md)
→ this file → [`ENGINE-AUDIT.md`](ENGINE-AUDIT.md) →
[`android/cloudy-x11/AGENTS.md`](../../android/cloudy-x11/AGENTS.md)
→ [`../plan/x11-forwarding-evaluation.md`](../plan/x11-forwarding-evaluation.md)
→ [`../decisions/ADR-001-embedded-x11.md`](../decisions/ADR-001-embedded-x11.md).

**Do not undo `df86bbe` or rewrite history.** Corrections are forward. The current
tracked head is `8a13464`; check `git log --oneline -10` and `git status` before
starting. Update `TODO.md`, this file and `GOAL.md` in the same commit as each
stage.

## 0. Current verified state

| Area | State |
|---|---|
| Standalone app | `android/cloudy-x11`, package `dev.cloudy.x11`; builds, installs, launches in a free-form Googlebook task; independent of GBCode/Cloudy Code. |
| Fresh install | Setup card embeds the 49 KB payload: apt/xauth, VM proxy, environment installer, systemd unit, tunnel harness, prototype service. |
| VM display | `DISPLAY=:99`/`XAUTHORITY` installed; a new Terminal shell exports them; `xdpyinfo` reaches `:99` through the VM proxy + tunnel harness + Xvfb (`X.Org 21.1.16`). |
| VM tunnel | Unix socket `$BASE/x11-tunnel.sock`; frame protocol O/D/C verified with a fake upstream; **new TCP binds in the 145xx range fail with `EADDRNOTAVAIL`**. |
| App-facing path | 14500 `/x11-tunnel` bridge implemented and harness-verified; G1 native APK transport probe passed. Native engine/renderer integration not implemented. |
| Cloudy Code | Backend systemd drop-in sets `DISPLAY=:99`/`XAUTHORITY`; live backend env verified. UI integrated-terminal/extension-host child follow-up. |
| Engine | `magicdesk-x11` pinned `65c3ced0`; `LorieNative`/`lorie-smoke` build; smoke starts under app UID. API/process audit complete (`ENGINE-AUDIT.md`); not integrated. |
| Current app transport | Still the xpra/HTML5 prototype viewer; no per-X-window host. G1 probe is diagnostic, not the viewer replacement. |
| Native engine path | Dedicated `:x11` FGS owns tunnel/engine; VM `xdpyinfo :99` reached embedded X.Org 21.1.99. CPU presenter + Surface now render VM `xeyes`; single-XID diagnostic preview, per-window host pending. |
| 16 KB | JNI/APK 16 KB aligned and zipaligned; `tools/device/verify-16kb.sh` passes. NDK 27.3 explicit linker flags. |
| G2 | Visible two tasks, bounds/title updates verified; HOME+16 s launch calls did not create activities; denial mechanism not captured. |
| Evidence | `8666b25` engine smoke; `3bb9d8e` T1 pairing + X Eyes; `5cc88f3` proxy/tunnel/setup; `8a13464` Unix display verification. |

**MVP definition:** one X11 client launched from the VM becomes one resizable,
free-form Googlebook task with its own caption/title/icon, rendered by the
embedded X server (no WebView, no Terminal graphics screen). Then input/IME,
multi-window/dialogs, Cloudy Code, and cleanup.

## 1. Guardrails

- Standalone Cloudy-X11 app/core; GBCode and Cloudy Code are VM consumers, not
  dependencies.
- The VM's default display is Cloudy-X11's; the Terminal's built-in X11 graphics
  screen is bypassed, never extended or captured.
- Terminal shells **and** Cloudy Code's integrated terminals use the same
  VM-wide `DISPLAY=:99`/`XAUTHORITY`.
- One X top-level = one Googlebook task; system caption/taskbar/multi-instance;
  native resize; no whole-desktop canvas.
- No X11 TCP listener and no new TCP tunnel port in the VM. The app-facing bridge
  goes through the existing Terminal-forwarded 14500 token proxy.
- X11 authentication stays between the VM client and the app's embedded X server.
  Never disable auth (`-ac`) in production.
- GPLv3 engine obligations are release requirements (notices, SBOM,
  corresponding source). Do not vendor silently.
- Commit small, scoped changes; never claim a gate without device evidence.

## 2. Target at a glance

```
Terminal shell / Cloudy Code terminal / script
  DISPLAY=:99, XAUTHORITY=~/.local/share/cloudy/Xauthority
        │
        ▼
VM proxy :99 (Unix socket) ── frame O/D/C ── Unix tunnel socket
        ▲                                      │
        │                        14500 token proxy /x11-tunnel
        │                         (raw HTTP Upgrade bridge)
        │                                      │
        └──────── Android app ◄────────────────┘
                  native tunnel service
                        │ socketpair per stream
                        ▼
                  LorieNative X server
                   ├─ renderer / LorieGraphics
                   └─ one free-form Activity/task per X window family
```

The VM proxy is transparent to the X11 protocol. The Android app authenticates
the tunnel, accepts each X11 client stream as a file descriptor, and runs the
embedded X server. The per-window host supplies the Surface, input and geometry.

## 3. Execution plan

### C2.4 — Verify Cloudy Code's integrated terminal

**Owner:** APP/VM. **Priority:** P0. **Depends on:** the VM setup (done).

**Steps**
1. Restart the Cloudy Code backend/terminal after the setup so it inherits
   `/etc/profile.d` and `environment.d` (`DISPLAY=:99`, `XAUTHORITY`).
2. In Cloudy Code's integrated terminal:
   ```sh
   echo $DISPLAY
   echo $XAUTHORITY
   xdpyinfo | head -4
   ```
3. Record the backend process environment if the terminal shows the old value
   (`DISPLAY=:100` or empty); restart the Code-OSS server, not only the terminal.
4. Repeat from a **fresh** terminal tab; stale terminals keep their env.

**Files:** none expected; backend launch/profile if a gap is found.

**Accept:** both `echo $DISPLAY` and `xdpyinfo` use `:99`; the display is reached
through the VM proxy; no Terminal graphics screen.

**Evidence:** terminal transcript/screenshot; backend restart note.

### C2.5 — 14500 `/x11-tunnel` raw upgrade bridge

**Owner:** VM/APP. **Priority:** P0. **Depends on:** C2.4 (routing), VM Unix tunnel.

**Why:** the Android app can reach the Terminal-forwarded 14500 port but not the
VM Unix socket. A raw HTTP Upgrade keeps the existing auth/forwarding and avoids
the unavailable 145xx TCP binds.

**Protocol**
```http
GET /x11-tunnel HTTP/1.1
Host: 127.0.0.1:14500
Cookie: cloudy_x11=<per-install token>
Upgrade: cloudy-x11
Connection: Upgrade
```
- 403 without a valid cookie; 101 on success; then the raw O/D/C frame stream.
- On 101 the VM-side proxy connects to `$BASE/x11-tunnel.sock`, sends
  `CLOUDY-X11 <token>\n`, reads `OK <cookie>\n` (Xauthority cookie; see contract
  4.3), forwards the OK line to the app, then pipes frames in both directions.
- No WebSocket framing is required; the app's native socket can use the raw
  upgrade. Do not expose a new TCP port.

**Implementation**
- `tools/device/cloudy-x11-proxy.py`: add `do_GET` handling for `/x11-tunnel`
  before the xpra proxy path; validate the cookie; connect the Unix socket;
  perform the VM proxy handshake; write 101; bidirectional raw pump (selectors or
  two threads); close on either side.
- `tools/device/cloudy-x11-vm/cloudy-x11-vm.py`: handshake returns
  `OK <cookie>` (read the Xauthority cookie from `--xauth`/`$BASE/Xauthority`);
  the app's engine can write its `-auth` file.
- `tools/device/cloudy-x11-vm/tunnel-test.py`: add `--via-proxy`/HTTP upgrade so
  the harness can authenticate to 14500 and verify the bridge end-to-end.
- Update the setup asset and standalone app asset; rebuild/install.

**Accept:** the harness connects only to 14500, upgrades, and `xdpyinfo :99`
works through the Unix tunnel; 403 without the cookie; no TCP X11 listener and no
new VM port.

**Evidence:** proxy log, harness log, `xdpyinfo` output.

### C3.1 — Android native tunnel + engine bootstrap

**Owner:** APP/NATIVE. **Priority:** P0. **Depends on:** C2.5; engine pin.

**Steps**
1. Create the reusable `cloudy-x11-core` service (or the first internal service
   in the app; split the modules when the boundary stabilizes). It:
   - opens the 14500 socket to the Terminal forward;
   - sends the raw upgrade request with the per-install token;
   - reads `101` and the `OK <cookie>` line;
   - writes an Xauthority/`-auth` file for the embedded X server;
   - starts `lorieServerStart` with `-noreset -nolisten tcp -auth <file>` and a
     renderer connection;
   - creates a `socketpair` per O frame, passes one fd to
     `lorieServerAcceptClient`, and pumps D/C frames to/from the other fd.
2. Add the JNI bridge/CMake for `LorieNative`; link the pinned engine; keep the
   service in the app UID (XKB staged; SELinux is enforced).
3. Log engine readiness, accepted stream count and disconnects. No UI change yet.

**Files:** `android/cloudy-x11/app/src/main/cpp` (JNI/CMake), the service,
`Ambient`/manifest, `cloudy-x11-core` later; `upstream/engine.lock.json`.

**Accept:** a test stream from the tunnel reaches `lorieServerAcceptClient`; the
engine stays alive; app restart tears down cleanly; no `-ac`.

**Evidence:** logcat/JNI logs; engine and tunnel process state.

### C3.2 — First per-X-window free-form host

**Owner:** APP/NATIVE. **Priority:** P0. **Depends on:** C3.1.

**Status:** first Surface/CPU presenter and VM `xeyes` pixels are verified in a
single diagnostic preview (`docs/evidence/2026-10-18-C32-first-surface.md`).
Per-window hosting is the remaining part.

**Steps**
1. Wire `window`/`windowsCommitted` events to a XID+incarnation registry; create
   one free-form Activity/task per independent normal top-level; bind a distinct
   nonzero output/Surface per window. No PID/stream identity.
2. Apply the role policy: dialogs/owned hosts, popups without task entries,
   splash handoff, no root/dock/input-only tasks; title/icon metadata.
3. Honor content insets and window constraints; resize the engine output; test
   two concurrent windows and a dialog.
4. Start with `xeyes`: one task, caption title, content only; no letterboxing.

**Accept:** `DISPLAY=:99 xeyes` (and a second normal window) from the Terminal
appears as independent free-form Googlebook tasks rendered by the engine, not
the xpra viewer or Terminal screen.

**Evidence:** device screenshot; window callback/task metadata logs; 16 KB gate.

### C3.3 — Resize, input, IME, close, multi-window

**Owner:** APP. **Priority:** P0/P1. **Depends on:** C3.2.

- Android content size → `lorieOutputResize`; client configure events; min/aspect
  constraints; density per window; no Activity recreation on resize.
- Pointer/scroll/touch/key/text; IME commit/cancel; focus/modal dialogs;
  contextual cursor; trackpad vs touchscreen.
- Close = `WM_DELETE_WINDOW`; force close = owning client only; caption/task
  removal reconciliation; other windows survive.
- Dialogs/transients via `lorieOutputBindDependents` or a separate host per the
  engine's role/family snapshot.
- Multi-instance: stable XID↔task map; no duplicate hosts; taskbar/Alt+Tab.

**Accept:** Firefox + xterm + dialog; resize each from Googlebook; one caption
close leaves the others; input/IME works; taskbar/metadata correct.

### C3.4 — End-to-end app path

**Owner:** APP/VM. **Priority:** P0. **Depends on:** C2.5, C3.1–C3.3.

- Remove the tunnel harness from the path; the app's service is the only app
  side.
- VM client → `:99` → Unix tunnel → 14500 raw bridge → app tunnel → engine → task.
- Test `xeyes`, `xterm`, Firefox, a dialog, two concurrent apps.
- Repeat from Cloudy Code's integrated terminal and a script started before the
  backend; verify the stale-env diagnosis.
- Verify auth: wrong token 403; wrong Xauthority rejected; no `-ac`; no TCP X11.

**Accept:** the MVP definition; both Terminal and Cloudy Code sources; no xpra.

### C3.5 — Retire the xpra prototype

**Owner:** APP/VM. **Depends on:** C3.4.

- Remove the xpra sessiond/proxy/HTML5 viewer and per-app aliases from the
  default app/setup; keep an explicit debug fallback only if useful.
- Setup becomes target-only: VM proxy/env/Xauthority/systemd, app install/pair.
- Update the launcher/setup card; remove the old `cloudy-firefox`/`cloudy-run`
  wrappers from the default; update docs.

**Accept:** fresh install has no xpra dependency for the default path; one X11
task from a fresh shell with no aliases.

### C4 — Standalone packaging, Cloudy Code and remote-console core

- `cloudy-x11-core` library + `cloudy-x11-app` APK + `cloudy-x11-vm` package with
  stable boundaries; no GBCode imports.
- Cloudy Code pairing: backend env, integrated terminal, restart guidance.
- Core API for the later remote console: session start/stop, window
  create/remove, Surface binding, resize, input/IME, clipboard/DnD, close,
  connection state; the same VM forwarder contract. A second test host consumes
  the API.
- Lifecycle: foreground service/notification, VM unavailable/reconnect/reset,
  app update, external display.

### C5 — Acceptance, security, release

- Full Googlebook matrix: Terminal + Cloudy Code, per-window/caption/taskbar/
  multi-instance, resize/DPI, input/IME/cursors, dialogs, clipboard/DnD,
  external display.
- Adversarial: tunnel auth/replay, malformed frames/streams, X11 auth, no TCP,
  native sanitizers/fuzz; performance/latency/memory budgets.
- GPLv3 notices/SBOM/corresponding source, signed APK/reproducible inputs.

## 4. Contracts

### 4.1 VM display

- Display `:99`, socket `/tmp/.X11-unix/X99`; `XAUTHORITY=$BASE/Xauthority`.
- VM-wide env: profile, `~/.bashrc`/`.profile`, `environment.d`, `/etc/profile.d`;
  Cloudy Code backend launch/restart.
- The Terminal's built-in graphics screen is not the endpoint.

### 4.2 Tunnel framing

- App → VM proxy handshake (inside the 14500 raw upgrade): `CLOUDY-X11 <token>\n`.
- VM proxy → app: `OK <xauthority-cookie>\n` or `ERR\n`.
- Frames (big-endian):
  - `O` + `uint32 sid` — open an X11 stream.
  - `D` + `uint32 sid` + `uint32 len` + payload — data (max 1 MiB).
  - `C` + `uint32 sid` — close.
- One app tunnel; disconnect closes all streams; no partial frame acceptance.

### 4.3 Xauthority

- The VM Xauthority cookie is created by the setup; the VM proxy returns it only
  over the token-authenticated tunnel; the app writes its engine `-auth` file.
- The VM clients use `XAUTHORITY`; the app X server validates the X11 auth; never
  `-ac` in production.
- Rotating the cookie invalidates existing sessions by design.

### 4.4 Engine JNI

- `lorieServerStart(argc, argv, callbacks, context)`: `ready`, `windowSize`.
- `lorieServerConnect`: renderer descriptor/connection.
- `lorieServerAcceptClient(fd)`: consumes a connected X11 stream; app owns the
  socketpair pump.
- `LorieConnection`: `lorieConnectionCreate/Connect`, `lorieConnectionSurface`,
  `lorieOutputBind`, `lorieOutputBindDependents`, `lorieOutputResize`,
  `lorieOutputPointer/Scroll/Key/Text/Focus`, `lorieCloseWindow`,
  `lorieSetScreenDpi`.
- Window metadata callbacks supply title/icon/family/role/constraints.

### 4.5 Window host

- One root Activity/task per application window family; resizable; no orientation
  lock; `setLaunchBounds`; content bounds excluding caption/insets.
- System caption is authoritative; `setTaskDescription` title/icon; no duplicate
  controls; task removal sends WM_DELETE; other windows/services survive.
- A singleton service owns the X server/tunnel; Activities are hosts only.

### 4.6 Remote-console core API

- `startSession(config)` / `stopSession()` / connection state.
- Window events: created/updated/removed with title/icon/constraints/family.
- Host commands: bind Surface, resize, focus/blur, pointer/key/text, close,
  clipboard/DnD.
- Transport injectable (14500 bridge now; other console transports later).
- No UI, GBCode, Workbench or Code-OSS dependency.

## 5. Test matrix

| Layer | Test |
|---|---|
| VM | `echo $DISPLAY` from a fresh shell and Cloudy Code terminal; `xdpyinfo`; wrong token 403; stale backend detection. |
| Tunnel | 14500 raw upgrade; 403/101; `OK <cookie>`; O/D/C ordering; malformed/oversize/partial frames; disconnect. |
| Engine | start/stop in app UID; XKB; auth; one/multiple streams; no TCP; no `-ac`. |
| Window | xeyes/xterm/Firefox; caption/title/icon; taskbar/Alt+Tab; one task per X top-level. |
| Resize | drag width/height; min/aspect; reflow; DPI; external display; no Activity recreate. |
| Input | pointer/hover/right-click/wheel; keyboard/IME/dead keys; focus/modal; cursor. |
| Lifecycle | close/force-close; other windows survive; task removal; service/tunnel restart; VM reset. |
| Cloudy Code | integrated terminal env; GUI app → task; backend restart. |
| Security/perf | token replay, X11 auth failure, fuzz/sanitizers, latency/memory; GPLv3 release. |

## 6. Risks and open decisions

- **Engine host complexity:** renderer/`LorieGraphics`, Surface lifecycle and
  window callbacks are the main native work; software presenter first.
- **Xauthority handshake:** the app must get the cookie only over the
  authenticated tunnel; verify the engine's `-auth` path and rotation.
- **VM network policy:** TCP 145xx binds fail; keep the Unix tunnel +
  14500 bridge. Do not reintroduce a new TCP port.
- **SELinux/app UID:** the engine starts under the app UID; adb-shell cannot bind
  the X socket. The service must own the engine.
- **Googlebook window semantics:** caption insets, task removal without a close
  callback, multi-instance grouping; measure on the physical device.
- **Cloudy Code stale env:** the backend can predate the setup; restart and
  verify; the integrated terminal's `.bashrc` is not enough for extension-host
  processes.
- **GPLv3:** notices/SBOM/corresponding source; decide distribution before
  release.
- **Open:** software vs AHB/Vulkan presenter for the first slice; engine service
  in the app process vs a dedicated process; window host Activity vs embedded
  Surface; exact cookie handshake format.

## 7. Tracking and commit protocol

- Update `TODO.md` (checkboxes + evidence) and this file in the same commit as
  the code. Update `GOAL.md` when the verified state changes.
- Commit small: one task or one contract per commit; include the task ID and
  evidence path in the message.
- Never undo `df86bbe`; never claim device acceptance from emulator/host tests.
- Evidence paths: screenshots/logs under `/tmp` are transient; copy durable
  evidence into `docs/evidence/` before claiming a gate.
- After context compression, read the four docs in the order at the top and run
  `git log --oneline -10` / `git status`.

## 8. First-session checklist

1. [ ] Read `GOAL.md`, `TODO.md`, this file and `AGENTS.md`; check git head/status.
2. [ ] Run the VM `:99` display test; record the result in TODO.
3. [ ] C2.4 Cloudy Code integrated-terminal env; restart the backend if stale.
4. [ ] C2.5 add `/x11-tunnel` to the 14500 proxy; test with `tunnel-test.py
   --via-proxy`; no new TCP port.
5. [ ] C3.1 native tunnel + engine bootstrap; log an accepted stream.
6. [ ] C3.2 first free-form task for `xeyes`; capture evidence.
7. [ ] Commit each completed task with its evidence; update TODO/GOAL.
8. [ ] C3.3/C3.4 only after the first task is visible and resizable.
