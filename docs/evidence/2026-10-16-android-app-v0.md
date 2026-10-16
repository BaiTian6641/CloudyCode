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

1. ~~No persistence~~ **fixed in v0.2:** enrolled identity persists app-private and survives force-stop/relaunch; Keystore hardening is the remaining S02.01 production step.
2. **Login is via the code-server password form in the WebView** (works; production S02.03 moves the credential into the gateway so page JS never sees it).
3. **Emulator focus interference:** a stray system Theme-settings app on the test AVD repeatedly stole foreground during automation; app behavior itself is unaffected. Desktop windowing/multi-window behavior is the physical lane.
4. No guest-side backend launch yet: v0 connects to a pre-running backend; S09 bootstrap + Terminal-driven start is the physical flow.

## v0.2 hardening (same session, follow-up commit)

| Fix | Evidence |
|---|---|
| Gateway keep-alive: sequential requests per connection, response framing by content-length/chunked/close, idle timeout | login + CSS + assets load via reused connections; `page finished` after all resources |
| Pairing persistence: Ed25519 seed + appHash + bases + fingerprint in app-private SharedPreferences | force-stop → relaunch restores CONNECTED state and opens the workbench with no re-pairing; daemon still recognizes the enrolled app |
| S02.01 production hardening remains: seed moves to Android Keystore; daemon-side session revocation list | recorded as production requirement, not done here |

## v0.3 — gateway-held backend credential (native login)

Full flow PASS on emulator: onboarding (debug prefilled fields) → pair → **native POST /login through the gateway** (password never enters page JS; session cookie installed into CookieManager for the gateway origin) → workbench loads directly to `/?folder=/tmp/gbcode-ws`, trusted via the workbench dialog, conformance extension active. Final screenshot: `S04-app-workbench-final.png`.

Two real gateway bugs found and fixed by this run: header keys must be lowercased at parse time (upgrade/body-length detection silently missed `Connection: Upgrade` and `Content-Length`), and request bodies must be forwarded past the header boundary. Both are now covered behaviorally by the app's own login flow; unit coverage for the gateway belongs to the S02.06 contract tests.
