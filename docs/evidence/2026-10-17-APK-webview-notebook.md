# APK WebView lane — notebook renderer, service worker and conformance webview (2026-10-17)

Emulator lane for the Android WebView renderer. Establishes what the APK's WebView can do for the workbench before the physical lane (WebView 149 on the device vs 143 here); physical re-verification remains required by S00/S04.

## Environment

| Item | Value |
|---|---|
| Device | `emulator-5554` (AVD, Android 36, x86_64/arm64-v8a) |
| WebView provider | `com.google.android.webview` **143.0.7499.35** (physical Googlebook is 149.0.7827.5) |
| APK | `dev.gbcode.app` debug build from `android/app` (installed fresh) |
| Backend | code-server 4.141.0 on the workstation, reached as `127.0.0.1:8080` via `adb reverse` |
| Pairing | `guest/sessiond` with an open enrollment; Ed25519 pairing succeeded, native login `HTTP 302` → session cookie installed from app code |

## Results

| Check | Result |
|---|---|
| Pairing + workbench in WebView | PASS — `WorkbenchActivity` resumed, workbench rendered (fresh profile → Restricted Mode) |
| Service worker in WebView | PASS — CDP target list shows `Service Worker http://127.0.0.1:41679/_static/out/browser/serviceWorker.js`, and the notebook webview frame reports that SW as `navigator.serviceWorker.controller` |
| Notebook opens (Explorer) | PASS — notebook editor (narrow-width mobile layout), markdown cell + code cell |
| Markdown renderer | PASS — H1 "GBCode notebook probe" rendered by `vscode.markdown-it-renderer`; no "Error loading renderer" banner |
| Renderer resources via virtual origin | PASS — every request to `…vscode-resource.vscode-cdn.net/…` (markdown renderer, jupyter-renderers, preload, katex, mermaid) returned **200** |
| Code cell output | PASS — stored output `gbcode notebook cell` rendered in the cell webview (`[1]`, kernel label `Python`) |
| **Offline reload** | **PASS** — wifi + data disabled (`ping 8.8.8.8` → Network unreachable, only `lo`), workbench reloaded over `adb reverse`, notebook renderer still produced the `markup` cell output → resources were served locally by the service worker, not the internet |
| Conformance webview in WebView | PASS — report webview renders with report data (`appName: code-server`, `remoteName: 127.0.0.1:41679`, Node v24.21.0, pid, `/bin/bash`), proving CSP/nonce, local resources and extension→webview messaging inside WebView |

Screenshots: `2026-10-17-APK-webview-notebook.png`, `2026-10-17-APK-webview-conformance.png`.

## Notes

- The fresh profile is **untrusted** (Restricted Mode). Viewing/rendering works; terminal/task/debug remain gated until the user trusts the folder — matches `2026-10-17-U03-terminal-trust-correction.md`. The APK onboarding should surface this explicitly (S03.01).
- Debug builds now call `WebView.setWebContentsDebuggingEnabled(true)` under `BuildConfig.DEBUG` for CDP-driven diagnostics; release builds are unaffected.
- Kernel selection/execution inside the WebView was not driven in this lane (the notebook rendered its stored output); the execution loop was proven in Chromium on the same backend (`2026-10-16-U03-notebook-lane.md`). Physical lane should run a fresh execution.
