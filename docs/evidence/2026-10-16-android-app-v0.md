# android/app v0 — production shell emulator lane (2026-10-16)

**Module:** `android/app` (AGP 9.4.1, Kotlin UI + promoted Java fixtures). `dev.gbcode.app`.

## Proven flow (emulator, adb reverse → workstation sessiond + code-server)

| Step | Result |
|---|---|
| Onboarding: daemon + workbench URL + one-time secret | PASS — state PAIRING→PAIRED→CONNECTED, fingerprint `3ccad41b…` matches daemon |
| Pairing security | Cleartext loopback gated by scoped network-security config (debug lane); TLS/pinned identity is S02 production work |
| In-app gateway (`connection/Gateway.kt`) | PASS after fix — login page + CSS/assets render through `127.0.0.1:<port>` → code-server; WebSocket piping present |
| Workbench page load in WebView | PASS — `page finished: http://127.0.0.1:43177/login`, code-server login rendered (`docs/evidence/S04-app-workbench-login.png`) |
| Origin-scoped native bridge | PASS (same class as slice, origin = gateway origin) |

## Bug found and fixed

The Kotlin gateway initially kept client connections open after each response (keep-alive semantics claimed, not implemented) → WebView stalled after the first document. Fixed with one-request-per-connection + `Connection: close` rewrite toward the upstream (correct, if not optimal; keep-alive pipelining is S04 performance work).

## Known v0 gaps (recorded, not hidden)

1. **No persistence:** app identity/session live in process memory; process death → re-pair required (S02.01 production step: Ed25519 seed in Android Keystore, paired-state restore).
2. **Login is via the code-server password form in the WebView** (works; production S02.03 moves the credential into the gateway so page JS never sees it).
3. **Emulator focus interference:** a stray system Theme-settings app on the test AVD repeatedly stole foreground during automation; app behavior itself is unaffected. Desktop windowing/multi-window behavior is the physical lane.
4. No guest-side backend launch yet: v0 connects to a pre-running backend; S09 bootstrap + Terminal-driven start is the physical flow.
