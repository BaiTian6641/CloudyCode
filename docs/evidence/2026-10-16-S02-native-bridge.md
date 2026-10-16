# S02.04 constrained native bridge — emulator lane (2026-10-16)

**Fixture:** `NativeBridge.java` in `android/probes/workbench-slice`, using AndroidX WebKit `WebViewCompat.addWebMessageListener` (androidx.webkit:webkit 1.14.0) with allowed origin `http://127.0.0.1:8083` only.

## Results

| Check | Result |
|---|---|
| Feature support on emulator WebView | `isSupported()=true` |
| `ping` roundtrip | `{"status":"ok","data":{"pong":7}}` |
| `getDeviceInfo` | manufacturer/model/sdk/abi JSON, correct origin logged (`http://127.0.0.1:8083`) |
| `requestDeviceSelection` (hardware path) | typed error `unsupported_in_slice` — no fake grant, hardware authority stays native |
| Malformed message | typed error `bad_schema`; oversized capped at 16 KiB |
| **Wrong origin adversarial** (`http://10.0.2.2:8083`, same server) | bridge object **not injected at all** (`bridge-absent`) — origin scoping is platform-enforced, not app-enforced |
| Sub-frame delivery | rejected by design (`isMainFrame` check + platform main-frame-only delivery) |

## Findings for production (S02.04 contract)

1. `WebViewCompat.addWebMessageListener` enforces the allowed-origin set at injection time: non-matching origins never see the JS object. This satisfies the plan's "exact allowed origin and main-frame check" requirement natively — no custom origin filter needed, but keep the explicit `isMainFrame` guard for defense-in-depth.
2. `WebView.evaluateJavascript` does **not** await Promises (a returned Promise serializes as `{}`); async JS results need an accumulator read back by a second call (or a bridge reply, which IS async-safe).
3. The bridge surface that fits the plan: `ping`, `getDeviceInfo`, `openExternal` (https-only), plus typed `unsupported_*` for not-yet-built authority paths. No generic exec/file/fetch/USB primitives — hardware selection/grants remain native-UI flows, not JS-callable methods.
4. Extension webviews run on `vscode-resource`/other origins and therefore cannot reach this bridge — matching the plan's requirement that untrusted extension frames get no native integration.

## Not established here

App-local gateway auth binding (bridge ↔ session pairing), per-window session scoping, hardware grant UI (S05), and physical-device WebView provider differences (bridge behavior on `com.google.android.webview` 149 on quartz — physical lane).
