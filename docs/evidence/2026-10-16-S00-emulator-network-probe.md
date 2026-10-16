# S00 emulator-lane network/WebView probe — 2026-10-16

**Environment:** emulator `Medium_Phone_API_36.1`, sdk_gphone64_x86_64, Android 16 (SDK 36), build BE4B.251210.005, WebView from Play image (see UA in results). **Label:** `emulator` — generic WebView behavior only; not Googlebook Terminal evidence.

**Fixture:** `android/probes/network-probe` (AGP 9.4.1, no third-party deps) + `tools/echo-server/server.py` on host port 8766. Raw records: `docs/evidence/NET-EMU-results-2026-10-16.jsonl`.

## Results

| Route | Test | Result |
|---|---|---|
| Native HTTP → `http://10.0.2.2:8766/nonce` | NET-EMU-HTTP | PASS (200, nonce body) |
| Native WebSocket → `ws://10.0.2.2:8766/ws` | NET-EMU-WS-BINARY | PASS (1024 B binary echo, SHA-256 match) |
| Native HTTP/WS → `http://127.0.0.1:8766` via `adb reverse` | same tests | PASS |
| WebView → `http://10.0.2.2:8766/` | secureContext / serviceWorker / crypto.subtle | **FAIL** — insecure origin (`isSecureContext=false`; no `serviceWorker` API; `crypto.subtle` undefined) |
| WebView → `http://10.0.2.2:8766/` | Worker, IndexedDB, localStorage | PASS |
| WebView → `http://127.0.0.1:8766/` via `adb reverse` | secureContext, serviceWorker registration, binary WS echo (512 B), Worker, IndexedDB, localStorage | **ALL PASS** |

## Findings

1. WebView treats `127.0.0.1` as a potentially trustworthy origin and enables secure-context features; a cleartext non-localhost address (`10.0.2.2`) does not qualify. **The production workbench origin must be loopback** (direct verified loopback or the app-local gateway), matching the plan §5.2 production candidate.
2. Native sockets and WebView both work over `adb reverse` loopback; source-separated reporting (`native` vs `webview`) worked end-to-end.
3. UI coordinate taps did not drive the probe app reliably; intent extras (`--es test …`, `--es host …`) are the deterministic automation path and are implemented in the probe.

## Not established here

- Googlebook Terminal forwarding behavior (physical, blocked by device hands-off): NET-01 rows for Chrome vs native vs WebView against the real VM.
- Target-SDK-37 `ACCESS_LOCAL_NETWORK` behavior (probe targets 36; emulator is API 36).
- WebView provider on target (emulator Play image ≠ `com.google.android.webview` 149.0.7827.5 on quartz).
