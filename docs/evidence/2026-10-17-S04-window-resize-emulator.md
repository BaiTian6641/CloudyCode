# S04 emulator lane — resize, display density and rotation (2026-10-17)

Host-emulator verification for S04.01's window/inset requirements. Physical-device windowing/multi-instance remains required (S04.01); this lane covers layout relayout, configuration-change handling and origin stability.

## Environment

`emulator-5554` (Android 36, WebView `com.google.android.webview` 143.0.7499.35), `dev.gbcode.app` debug APK, gateway → `adb reverse` → code-server 4.141.0.

## Before the fix — density change recreated the activity and churned the origin

| Step | Observed |
|---|---|
| `wm size 1600x1000` + `wm density 160` | WorkbenchActivity **recreated**; gateway port changed `41679 → 37745`; workbench reloaded at the new origin |
| Result | Desktop-width layout correct (menubar, activity bar, 300 px Explorer, terminal panel, Chat), but the open notebook tab and the browser-stored theme were **lost** (new origin = new browser storage) |
| `wm size reset` + `wm density reset` | Recreated again (`37745 → 35907`) |

Cause: `WorkbenchActivity` declared `configChanges="orientation|screenSize|screenLayout|smallestScreenSize|keyboardHidden"` without `density`; a display-size/density change therefore destroys and recreates the activity, and each recreation binds a fresh ephemeral gateway port.

## Fix applied

`AndroidManifest.xml`: added `density` to `WorkbenchActivity`'s `configChanges`. Rebuilt and reinstalled.

| Step | Observed after fix |
|---|---|
| `wm size 1600x1000` + `wm density 160` | Origin unchanged (`http://127.0.0.1:41635`) — no recreation; layout relaid out at desktop width (1000 CSS px) |
| Notebook opened, then `wm size reset` + `wm density reset` | Origin still `41635`; tabs **retained** (`Welcome`, `GBCode Conformance`, `test.ipynb`); notebook editor intact |

## Rotation

`user_rotation 1` (landscape) with default size/density: origin unchanged (`35907`) before the fix too — orientation/screenSize were already handled. Viewport `914×411` CSS, upstream workbench relayout (title bar, Explorer, editor groups, Chat); codicon font loads after the first frames (an early screenshot without icons was a font-load timing artifact, not a rendering defect).

## Findings

1. **Fixed:** density/display-size changes no longer recreate the activity or churn the origin.
2. **Fixed:** the gateway now binds a stable loopback port (`127.0.0.1:41873`, ADR-0005) with an ephemeral fallback only on conflict. Verified: cold start → `41873`; notebook opened, `am force-stop` + relaunch → **same origin, tabs `test.ipynb` and `GBCode Conformance` restored, notebook editor reopened**. This replaces the earlier "new port on every restart → lost editor state" behavior.
3. Multi-window (S04.01) must choose one shared gateway (preferred) or define per-window origins; a second activity cannot also bind 41873 (fallback would create a new state bucket).
4. Desktop-width relayout is correct at 1600×1000 (menubar + three panes + terminal panel); landscape phone width collapses the menubar as upstream does.
5. The Restricted Mode trust dialog appears on terminal creation inside the APK (workspace untrusted), matching the trust correction.

## Not covered here

True multi-window/split-screen, external display, caption insets and IME — physical/implementation work in S04.01/S04.02.
