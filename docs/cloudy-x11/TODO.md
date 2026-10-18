# Cloudy-X11 — execution tracker

**Last updated:** 2026-10-17. Update this file in the same commit as each stage.
The product goal and invariants are in `GOAL.md`. Read
`android/cloudy-x11/AGENTS.md` before changing the standalone app.

**Current phase:** T1 — standalone app extraction/build (T0 in progress).

## T0 — Tracking and product boundary

- [x] Review `df86bbe`; keep it in history, correct forward (no undo).
- [x] Record the standalone-app boundary, VM-wide display, Terminal bypass,
  Cloudy Code pairing and reusable core in the evaluation/ADR/goal.
- [x] Create persistent `GOAL.md`, this TODO and the module `AGENTS.md`.
- [x] Commit the tracking documents (`a025eb9`).
- [x] Create `android/cloudy-x11` as a separate Gradle application project
  (`dev.cloudy.x11`) with its own token store.
- [x] Move the X11 prototype activities/resources/icon/VM setup asset into it.
- [x] Remove X11 activities, launcher entry, token method and assets from the
  GBCode app; build both.

**Evidence:** standalone debug APK
`android/cloudy-x11/app/build/outputs/apk/debug/app-debug.apk` (3.5 MB);
GBCode debug APK (59 MB) builds with no X11 references; standalone installed as
`dev.cloudy.x11` and launched in a free-form task (`/tmp/cloudy-x11-standalone.png`).

## T1 — Standalone app functional (prototype carrier)

- [x] APK builds with the existing xpra/HTML5 prototype and launches on the
  Googlebook; it is independent of GBCode code/packages.
- [ ] App token/setup card installs the VM prototype; the launcher probe passes
  after re-running the setup with the standalone app's token. **Pending:** the
  VM currently holds the previous GBCode token; the standalone launcher reports
  "Session service not reachable" and offers X11 setup/repair, as expected.
- [x] No `dev.gbcode.app` imports, `GbCodeApp` token, Workbench or Code-OSS
  dependency in the standalone source.
- [x] Launcher/diagnostics/status UI; the prototype sessions still use the
  existing setup path.
- [x] Commit with build/launch evidence.

**Evidence:** standalone `assembleDebug` status 0 (3.5 MB); GBCode
`assembleDebug` status 0 (59 MB, no X11 refs); install/launch `dev.cloudy.x11`
in free-form task; screenshot `/tmp/cloudy-x11-standalone.png`; launcher logs
`session watch failed: HTTP 403` because the VM token is still the old one;
engine smoke X1a in `8666b25`.

## T2 — VM-wide forwarding (next)

- [ ] `cloudy-x11-vm`: display proxy `:99`, Xauthority, client accept, stream
  multiplexing over the app-initiated channel; no X11 TCP.
- [ ] Install VM-wide env: `/etc/profile.d`, user profile, environment.d/systemd
  user, Cloudy Code backend launch.
- [ ] Prove the app→VM route for native socket and WebView separately (S00.03).
- [ ] `xeyes` from a fresh Terminal shell and from Cloudy Code's integrated
  terminal → Cloudy-X11 tasks, not the Terminal graphics screen.
- [ ] Verify stale backend/old `DISPLAY` detection and setup reruns.
- [ ] Commit with device evidence.

## T3 — Engine host and per-window tasks

- [ ] Integrate `LorieNative` in `cloudy-x11-core`; start the engine in the
  standalone service (app UID), consume forwarded client fds with
  `lorieServerAcceptClient`.
- [ ] Renderer connection, `LorieGraphics` host (software first), Surface
  lifecycle.
- [ ] One free-form Googlebook task per Composite window family; caption/title/
  icon; taskbar/Alt+Tab/multi-instance.
- [ ] Two-way resize (`windowSize`, `lorieOutputResize`), DPI, min/aspect rules.
- [ ] Input/IME/cursor/scroll/drag-drop; dialogs/transients; close/force-close.
- [ ] Commit with emulator/device evidence.

## T4 — Product integration and reuse

- [ ] Pairing/setup/lifecycle UX: VM required/unavailable, stop/reset, app
  update, service/notification policy.
- [ ] Remove xpra display path and per-app wrappers from the default build.
- [ ] Package `cloudy-x11-core` for the later remote-console app; document the
  stable contract and a second test host.
- [ ] GBCode/Cloudy Code remain consumers only; no code dependency.
- [ ] Commit.

## T5 — Acceptance, security, release

- [ ] Googlebook acceptance matrix from `GOAL.md`; Terminal + Cloudy Code.
- [ ] Performance/latency/memory; external display/scale changes.
- [ ] Adversarial transport/X11 tests; native fuzzing/sanitizers; no TCP.
- [ ] GPLv3 notices/SBOM/corresponding source; signed APK/reproducible inputs.
- [ ] Commit and release evidence.

## Evidence log

| Date | Commit | What |
|---|---|---|
| 2026-10-17 | `8666b25` | Engine `lorie-smoke` starts on Googlebook under app UID. |
| 2026-10-17 | `29b836b`/`6a8ef6e` | Forward corrections: standalone app, VM-wide display, stage order. |

## Context recovery

If the session was compacted: read `GOAL.md` → this file →
`android/cloudy-x11/AGENTS.md`; run `git log --oneline -10` and `git status`.
Do not undo `df86bbe`. Do not add X11 back into GBCode. The prototype is not the
target; T2/T3 replace it. Update this file before yielding.
