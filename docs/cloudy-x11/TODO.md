# Cloudy-X11 — execution tracker

**Last updated:** 2026-10-17. Update this file in the same commit as each stage.
The product goal and invariants are in `GOAL.md`; the detailed execution plan is
[`NEXT-STEPS.md`](NEXT-STEPS.md). Read `android/cloudy-x11/AGENTS.md` before
changing the standalone app.

**Current phase:** C3.2 per-X-window Android hosts. First VM `xeyes` pixels were
rendered through the embedded engine into a real Android Surface; the diagnostic
preview is not yet one task per X top-level. G1, C2.4, C2.5, G3 and C3.1 are
complete; G2 is partially verified (visible task identity/bounds/title;
background launch unproven). VM persistence/port-forward availability while
Terminal is hidden is an observed external blocker.

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
- [x] App token/setup card installs the VM prototype; the launcher probe passes
  after re-running the setup with the standalone app's token. The standalone
  token was written to the VM `x11-token`; the sessiond/proxy/X11 services
  restarted; `/list` returns 200; the launcher reads "Ready".`
- [x] No `dev.gbcode.app` imports, `GbCodeApp` token, Workbench or Code-OSS
  dependency in the standalone source.
- [x] Launcher/diagnostics/status UI; the prototype sessions still use the
  existing setup path.
- [x] Commit with build/launch evidence (`5fe15a9`).
- [x] Install both packages: `dev.cloudy.x11` standalone and the release-signed
  GBCode update with its X11 entry removed (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`
  with the debug key confirmed the original release-key requirement).

**Evidence:** standalone `assembleDebug` status 0 (3.5 MB); GBCode
`assembleDebug` status 0 (59 MB, no X11 refs); install/launch `dev.cloudy.x11`
in free-form task; screenshot `/tmp/cloudy-x11-standalone.png`; VM token updated
to the standalone token; sessiond `/list` 200; X Eyes session `14510` in the
standalone app (`/tmp/cloudy-x11-xeyes2.png`); GBCode release-signed update
installed with no X11 launcher; engine smoke X1a in `8666b25`.

## T2 — VM-wide forwarding (in progress)

- [x] `cloudy-x11-vm`: display proxy `:99`, token-authenticated app tunnel,
  frame multiplexing, local-upstream test mode; no X11 TCP listener.
- [x] App-side tunnel protocol harness (`tunnel-test.py`); transport test passed
  with a fake upstream (`hello-through-tunnel`).
- [x] VM-wide environment installer: Xauthority, profile/`.bashrc`,
  environment.d/systemd, `/etc/profile.d`, proxy service, Cloudy Code restart note.
- [x] Fresh-VM setup integrated in the standalone app's setup card (VM proxy,
  env installer, tunnel harness, prototype service); the setup ran in the VM.
- [x] VM X11 display test: Xvfb + tunnel harness + `DISPLAY=:99 xdpyinfo`
  passed; a new shell shows `DISPLAY=:99`/the Cloudy Xauthority and reaches the
  display. TCP 145xx binds fail in this VM; the proxy tunnel is a Unix socket,
  bridged through the existing 14500 proxy for the app.
- [x] **C2.4** Cloudy Code backend/extension-host display environment: systemd
  drop-in sets `DISPLAY=:99`/`XAUTHORITY`; backend restart verified its env.
  UI-driven integrated-terminal and extension-host GUI child remain follow-ups.
- [x] **C2.5** 14500 `/x11-tunnel` raw Upgrade bridge implemented; harness-only
  path reached the display; wrong cookie/upgrade and X11 auth fail closed; no
  TCP X11 listener. Xvfb upstream is a harness, not the native engine.
- [ ] App-facing tunnel/engine integration belongs to C3.1/C3.4.
- [x] **G1** Native APK-UID authenticated binary probe passed 25 checks against
  the existing 14509 control endpoint; not an X11/native-window proof.
- [ ] **G2** Synthetic Android task/lifecycle probe: visible two-task identity,
  bounds and dynamic title verified. Background/obscured creation, permission,
  Terminal/Cloudy Code triggers, icon/taskbar/Recents/Alt+Tab, close and Surface
  recreation remain unproven (`docs/evidence/2026-10-18-G2-task-probe.md`).
- [x] **G3** Pinned engine API/process/renderer audit complete
  (`docs/cloudy-x11/ENGINE-AUDIT.md`); no JNI/renderer code yet.
- [ ] Verify stale backend/old `DISPLAY` detection and setup reruns.
- [ ] Commit with device evidence.

## T3 — Engine host and per-window tasks

Detailed plan: [`NEXT-STEPS.md`](NEXT-STEPS.md) §C3.

- [x] **C3.1** Native tunnel service/JNI bootstrap: dedicated `:x11` foreground
  service, 14500 tunnel, `FamilyWild` Xauthority, `LorieNative :99`; VM
  `xdpyinfo` returned X.Org 21.1.99 through `lorieServerAcceptClient`; explicit
  stop/restart and force-stop teardown verified. Renderer/Surface is C3.2.
- [x] **C3.2a** CPU `LorieGraphics` staging presenter + Surface `/x11` Binder,
  first known pixels, and a VM `xeyes` client rendered in a real Surface
  (`present lock=0/post=0`; evidence screenshot). Diagnostic single-XID preview,
  not per-window.
- [ ] **C3.2b** Per-X-window host: native window callbacks + distinct
  output/Surface + one task per mapped parentless application are implemented
  and build-verified. Device test blocked: the VM/forwarder disappeared (502)
  when Terminal was covered; task launch/G2 background restrictions untested.
  XID+incarnation registry, role policy, window removal/close and two-window
  proof remain. Live VM unit was stale (`127.0.0.1:14511`, no cookie); fresh
  setup writes Unix+cookie, but the live unit needs repair. Terminal input focus
  prevented the final xeyes/xterm launch. See C3.2b evidence.
- [ ] **C3.3** Real resize/input/IME/close, dialogs/multi-window, Surface
  replacement/recreation, colored/alpha pixel fixture.
- [x] **16 KB gate** every native artifact: ELF LOAD `0x4000`, APK
  `zipalign -c -P 16` (`tools/device/verify-16kb.sh`). Verified for the C3.2
  JNI/APK; Gradle `preBuild` regenerates the JNI.
- [x] Review hardening: HMAC pre-auth before sending the tunnel token, engine
  service/preview no longer exported, JSON config redaction, stream-close catch,
  tunnel-loss service/engine teardown, bundled XKB asset extraction. Device
  rerun of these final edits is pending because the VM stopped.
- [ ] **C3.2** Renderer/`LorieGraphics` (software first) + first free-form
  Googlebook task/Surface; `xeyes` visible.
- [ ] **C3.3** Resize/input/IME/close/title/icon, multi-window/dialogs.
- [ ] **C3.4** End-to-end VM X client → 14500 → Unix tunnel → app engine → task;
  Terminal + Cloudy Code.
- [ ] **C3.5** Retire xpra from the default path; target-only fresh setup.
- [ ] Commit with device evidence for each.

## T4 — Product integration and reuse

Detailed plan: [`NEXT-STEPS.md`](NEXT-STEPS.md) §C4.

- [ ] Package `cloudy-x11-core`/`cloudy-x11-app`/`cloudy-x11-vm` boundaries;
  Cloudy Code pairing.
- [ ] Pairing/setup/lifecycle UX: VM required/unavailable, stop/reset, app
  update, service/notification policy.
- [ ] Remote-console core contract + second test host.
- [ ] GBCode/Cloudy Code remain consumers only; no code dependency.
- [ ] Commit.

## T5 — Acceptance, security, release

Detailed plan: [`NEXT-STEPS.md`](NEXT-STEPS.md) §C5.

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
| 2026-10-17 | `8a13464` | VM `:99` display verified; Unix tunnel; TCP 145xx unavailable; fresh setup updated. |
| 2026-10-18 | `bf75469` | 14500 X11 bridge + display cookie; Cloudy Code backend display env; setup sync. |
| 2026-10-18 | `b363fdd` | G1 native APK-UID binary transport probe passed 25 checks. |
| 2026-10-18 | pending | C3.1 VM `xdpyinfo` through APK into embedded X.Org 21.1.99; 16 KB JNI/APK verified. |
| 2026-10-18 | pending | C3.2 first VM xeyes pixels in native Surface; review hardening/XKB; per-window host next. |
| 2026-10-18 | pending | C3.2b per-window host code/build; clean XKB extraction; HMAC bridge reached 502 because VM/forwarder stopped. |
| 2026-10-18 | pending | G2 visible tasks/title/bounds verified; background launch remains unproven. |

## Context recovery

If the session was compacted: read `GOAL.md` → this file → `NEXT-STEPS.md` →
`android/cloudy-x11/AGENTS.md`; run `git log --oneline -10` and `git status`.
Do not undo `df86bbe`. Do not add X11 back into GBCode. The prototype is not the
target; T2/T3 replace it. Update this file before yielding.
