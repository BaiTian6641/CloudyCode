# Cloudy-X11 standalone app — agent notes

Read `../../docs/cloudy-x11/GOAL.md`, `../../docs/cloudy-x11/TODO.md` and
`../../docs/cloudy-x11/NEXT-STEPS.md` before changing this project. Re-read them
after context compaction or session recovery. The long-form design is
`../../docs/plan/x11-forwarding-evaluation.md`; the engine decision is
`../../docs/decisions/ADR-001-embedded-x11.md`.

## Boundaries

- This is a **separate application** (package `dev.cloudy.x11`), not part of
  GBCode/Cloudy Code. Do not import `dev.gbcode.app` classes, its token, its
  Workbench or Code-OSS code.
- The prototype currently carried here uses xpra/HTML5 **only as a temporary
  carrier** while the VM-wide forwarder and embedded X server are built. New
  features go to the target path, not the xpra viewer.
- The VM-wide display must be `:99` (Xauthority, profile/environment.d/Cloudy
  Code backend). The Terminal app's built-in X11/graphics screen is bypassed, not
  extended.
- One X11 top-level must become one free-form Googlebook task; no whole-screen
  canvas in the target.
- Do not add X11 code back to the GBCode app.
- Do not undo `df86bbe`; history is kept and corrections are forward.
- No raw X11 TCP listener; no new TCP tunnel port. The VM tunnel is the Unix
  socket `$BASE/x11-tunnel.sock`; the app-facing path must be the existing 14500
  token proxy with the raw `/x11-tunnel` Upgrade (C2.5). The VM rejects new
  145xx TCP binds.
- Do not disable X11 auth (`-ac`) in production; the app engine gets the
  Xauthority cookie only over the authenticated tunnel.
- Current phase: C2.4 (Cloudy Code terminal env) / C2.5 (14500 bridge), then
  C3.1 engine bootstrap, C3.2 first free-form task. See NEXT-STEPS.

## Build

```sh
cd android/cloudy-x11
~/.gradle/wrapper/dists/gradle-9.8.1-bin/a2l6ltp3k1vth6lwipzm5p2jp/gradle-9.8.1/bin/gradle --offline -q assembleDebug
# APK: android/cloudy-x11/app/build/outputs/apk/debug/app-debug.apk
```

`local.properties` contains `sdk.dir=/home/polar/Android/Sdk` and is git-ignored.
The GBCode app is separate: `android/app/app`.

## VM asset

`app/src/main/assets/cloudy-x11-setup.sh` is a copy of
`tools/device/cloudy-x11-setup.sh` (the VM installs the prototype from the app's
setup card, with the standalone app's token). Keep them in sync until the T2/T3
forwarder replaces this asset; do not edit only one copy.

## Tracking

- Update `docs/cloudy-x11/TODO.md` in the same commit as each completed task.
- Commit small, scoped changes. Record build/launch evidence in the TODO.
- If a decision changes the product boundary or transport, update `GOAL.md`, the
  evaluation and an ADR before coding.

## Device

The Googlebook is serial `C1L63708PZ`; the package is `dev.cloudy.x11` once the
standalone project is built. The prototype VM setup requires the Terminal app
and the user-approved setup command; the VM is not started by an undocumented
API. The engine smoke evidence is in `upstream/engine.lock.json` and the ADR.
