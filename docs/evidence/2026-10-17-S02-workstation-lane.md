# S02 workstation/emulator lane — pairing, authenticated reconnect, credential protection, gateway/bridge authorization (2026-10-17)

**Scope:** development/workstation/emulator lane for S02 (connection, protocol, native trust). No S02 acceptance is claimed from emulator evidence; physical-lane acceptance remains blocked on device release.

**Evidence identity:** emulator `emulator-5554` (AVD `Medium_Phone_API_36.1`, Android 36, x86_64/arm64-v8a, WebView `com.google.android.webview` 143.0.7499.35); host workstation Linux x64; backend code-server 4.141.0 (`8b41915d…`); `guest/sessiond` 0.1.0 (Node v22.22.1); APK `dev.gbcode.app` 0.1.0 debug build (Keystore-backed credentials). Physical unit `C1L63708PZ` (HP Googlebook 14) is owned by another project and was **not** touched.

## 1. Review findings verified against source

| # | Finding | Verified at | Status after this lane |
|---|---|---|---|
| 1 | `fullFlow` truncated the enrolled app hash before onboarding persisted it | `SessionClient.fullFlow` (`appHash.substring(0, 16) + "…"`), `OnboardingActivity` persisted it | **Fixed** — full hex hash persisted (`7030974`); regression tests `ReceiptVerificationTest.truncatedAppHashIsRejected`, `receipt.test.js` "full hash is required for challenge" |
| 2 | Restoring settings marked CONNECTED without authenticating to sessiond | `OnboardingActivity.onCreate` (`restoreFromPersisted()` → `CONNECTED`) | **Fixed** — `CONNECTED` only after `session()` + `serverProof` verification + channel hello (`2f3140e`) |
| 3 | Android client did not verify the signed enrollment receipt | `SessionClient.pair` checked only HTTP 200 | **Fixed** — `verifyReceipt` checks `enrolledApp = SHA-256(app key)`, `fingerprint = SHA-256(server key)`, and `ed25519(serverKey, "enroll"\|\|appHash\|\|nonce)` (`7030974`) |
| 4 | APK gateway used plain upstream sockets | `Gateway.handleOne` `Socket()` to user-supplied backend | **Fixed (bounded)** — `http` loopback upstreams only (`Gateway.isLoopbackHost`, `require` in `start`, UI-level URL check in `WorkbenchActivity`); remote/TLS backends rejected, not silently downgraded (`48584ba`) |
| 5 | Backend password stored directly in SharedPreferences | `GbCodeApp.savePairedState` `putString("backendPassword", …)` | **Fixed** — Keystore AES-GCM (`gbcode-backend-password` alias), one-time legacy migration, backup disabled (`allowBackup=false`, `data_extraction_rules.xml`) (`6d746f8`) |
| 6 | Native login stripped Set-Cookie attributes | `nativeLogin` `Set-Cookie.substringBefore(";")` | **Fixed** — all raw `Set-Cookie` values forwarded with attributes (`Cookies.setCookieHeaders`), names logged, values never (`6d746f8`) |
| 7 | Gateway Origin rewriting without incoming-origin authorization | `Gateway.handleOne` unconditional `origin -> http://upstream` | **Fixed (bounded)** — per-request authorization: method allowlist, origin-form target only, `Host` must be the gateway authority (421 otherwise, 400 if absent), `Origin` must be the gateway origin when present (403 otherwise); Origin/Referer rewritten only for same-origin requests (`48584ba`) |

## 2. Milestones and contract change

| Milestone | Commit | Content |
|---|---|---|
| M1 pairing | `7030974` | Signed enrollment receipt verification; full app hash persistence; golden vectors pinned from the Node signing path |
| M2 reconnect | `2f3140e` | **Contract change (additive, v1):** `/session` response gains `serverProof = ed25519(serverKey, "serverprove"\|\|appHash\|\|challenge)`; client verifies it before using the token; authenticated reconnect flow + "Reconnect saved identity" |
| M3 credentials | `6d746f8` | Password encrypted at rest (legacy plaintext migrated and deleted); backup/transfer excluded; full `Set-Cookie` forwarding |
| M4 gateway | `48584ba` | Host/Origin/method/target authorization before upstream contact; same-origin-only origin rewrite; loopback-only upstream; explicit bridge origin check |

Protocol compatibility: `serverProof` is an additive field — old v1 peers ignore unknown optional fields and remain compatible (`contracts/session-v1.md` §3/§6). `v` stays 1. Old clients remain *insecure* against a replaced daemon but do not break; the new client requires the field (fails closed if absent).

## 3. Commands run and test results

| Suite | Command | Result |
|---|---|---|
| sessiond | `cd guest/sessiond && node --test test/*.test.js` | **27/27 pass** (protocol, authz, receipt/proof incl. call-site codes `secret_consumed`/`unsupported_version`, channel auth header + deprecated query form, vectors, fuzz) |
| gateway (Node reference) | `cd guest/gateway && node --test test/*.test.js` | 5/5 pass |
| instrumented (emulator only) | `ANDROID_SERIAL=emulator-5554 adb -s emulator-5554 install -r …` then `adb -s emulator-5554 shell am instrument -w dev.gbcode.app.test/androidx.test.runner.AndroidJUnitRunner` | **41/41 pass**: `GatewayTest` 15, `ReceiptVerificationTest` 7, `ServerProofVerificationTest` 6, `SessionClientCallSiteTest` 6, `CredentialStorageTest` 5, `CookiesTest` 2 |
| builds | `gradle assembleDebug assembleDebugAndroidTest` (offline) | clean |
| full suite | `tools/verify.sh` | ALL GREEN |

`tools/verify.sh` was corrected to run on-device tests via `adb -s emulator-5554` + `am instrument` instead of gradle's `connectedDebugAndroidTest`, which walks every ddmlib-seen device (ANDROID_SERIAL is not a reliable filter) and could touch the hands-off physical unit.

### Emulator end-to-end (manual, `adb -s emulator-5554`)

| Scenario | Observed |
|---|---|
| Pair (`--enroll` secret via debug extras) | `paired; enrollment receipt verified; server fingerprint: 19bf87b3…`; persisted prefs show full 64-hex `appHash`, `serverPublicKey`, Keystore-encrypted `seedEnc`/`passwordEnc`, **no plaintext** `backendPassword` |
| Reconnect after `force-stop` | `reconnected; daemon proof verified (fingerprint 19bf87b3…)` → workbench at `http://127.0.0.1:41873` |
| Daemon down | `reconnect failed …`, state `UNAVAILABLE`, **workbench stays closed** (OnboardingActivity resumed) |
| Daemon back + "Reconnect saved identity" | `CONNECTED` → workbench |
| Cookie install | `native login HTTP 302, set-cookie=1, stored=1 names=[code-server-session]`; page loads authenticated (no login screen) |
| Gateway live probes (`nc` from emulator shell) | `Host: evil.example` → `421`; `Origin: http://evil.example` → `403`; `CONNECT` → `400`; legit GET → `302` (proxied) |
| Backup flags in built APK | `allowBackup=false`, `fullBackupContent=false`, `dataExtractionRules` set (`aapt2 dump xmltree`) |

## 4. Independent review

Implementer and reviewers were separate agents (AGENTS.md §2). Dispositions:

**Code review (S02CodeReview):** 3 findings — all fixed in this lane.

| Finding | Disposition |
|---|---|
| Channel-hello error replies could reach CONNECTED: `channelHello` never checked `ok`, and both call sites compared fingerprints only when present | **Fixed** — `channelHello` throws on `ok != true` or a missing fingerprint; `startPairing`/`authenticateRestored` now compare mandatorily |
| Legacy password migration unreachable for pre-M1 installs (`serverPublicKey` early return precedes `loadPassword`) | **Fixed** — migration/scrub runs before any early return; fixture test `legacyPlaintextScrubsEvenWithoutServerPublicKey` |
| Tests covered verification helpers but not their call sites | **Fixed** — `SessionClientCallSiteTest` (6 tests) drives `pair()`/`session()` against a stub daemon with valid, tampered and missing receipt/`serverProof` |

**Security review (S02SecurityReview, independent):** all seven lane fixes confirmed closed; the three signed transcripts ("enroll"/"serverprove"/"session") use non-colliding domain prefixes and fixed-width fields. Findings GB-S02-001…009:

| ID | Disposition |
|---|---|
| 001 Backend password POSTed to an unauthenticated loopback upstream (high) | **Open, design item** — remediation is daemon-mediated backend vouching/login (S02.05/S09); not implementable as a bounded fix here. Documented as the top residual |
| 002 Gateway admission is reachability-based (low, by-design) | **Documented** — Host/Origin checks target browser/DNS-rebinding threats; a per-install gateway token is future work (ADR-0005 already states "a loopback port is not authentication") |
| 003 Session token in the `/channel` URL query (low) | **Fixed** — token now rides the `X-GBCode-Session` upgrade header; the query form is accepted server-side only for pre-freeze clients and is deprecated (contract §4/§5 updated) |
| 004 Unpairing left WebView cookies (low) | **Fixed** — `clearPairedState` clears CookieManager |
| 005 Unmasked credential fields (low) | **Fixed** — password input type on the secret and password fields |
| 006 Gateway logged the full request line incl. query (low) | **Fixed** — logs method + path without the query string |
| 007 Debug intent-extra prefill (low, debug-only) | **Accepted for the debug lane** — release builds ignore the extras; visual prefill flagging noted as future |
| 008 Ed25519 verifier strictness (informational) | **Documented** — constraints recorded next to `verify()`; keys are pinned transitively in this lane; re-audit before reuse with untrusted keys |
| 009 Contract/implementation divergences (informational) | **Fixed** — `secret_consumed` on replay, `unsupported_version` for unknown major versions, `appLabel: gbcode-apk`, key-storage wording corrected in the contract |

## 5. Remaining limitations (not fixed here)

1. **Local non-browser clients (GB-S02-002):** the gateway binds loopback and validates browser-level Host/Origin, but a native app on the same device can forge those headers. A per-install gateway token/lease remains S02.05 work.
2. **Backend identity (GB-S02-001, top residual):** the password is POSTed to whatever process owns the backend port; daemon-mediated backend vouching or guest-side login is the designed remediation (S02.05/S09).
3. **Remote/TLS backends** are rejected rather than supported; a reviewed TLS design is future work.
4. **Revocation UI** and revoked-identity reconnect tests are not implemented (S09).
5. **Bridge adversarial fixture** (malicious iframe/oversized-message runtime tests) pending; current coverage is code-level allowlist + platform origin filter + explicit check.
6. **Debug intent-extra prefill (GB-S02-007)** remains debug-lane-only by design; visual flagging of pre-filled values is future work.
7. **Physical-lane acceptance** (pairing over the real Terminal route, LAN exposure, local-network permission matrix, VM-reset re-pairing) is blocked on device release.

(Previously listed residuals now closed: session token moved to the `X-GBCode-Session` header (GB-S02-003); unpair clears cookies (GB-S02-004); credential fields masked (GB-S02-005); request-line query stripped from logs (GB-S02-006); contract error codes/label/key-storage wording conformed (GB-S02-009).)

## 6. Rollback

- Each milestone is a separate commit and can be reverted independently: `7030974` (M1), `2f3140e` (M2), `6d746f8` (M3), `48584ba` (M4).
- **M3 migration is one-way:** reverting to pre-M3 code leaves `passwordEnc` unreadable and no `backendPassword`; the user re-enters the backend password (pairing identity is unaffected).
- **M2 contract change is additive:** reverting the client leaves the server's `serverProof` field harmless; reverting the server makes the new client fail closed (missing `serverProof`) — revert the client first or upgrade the daemon together.
- **M1/M4** are behavior-only fixes; reverting restores the truncation/authorization bugs and is not recommended.
- Data: `adb shell pm clear dev.gbcode.app` (or uninstall) removes pairing state and the Keystore-encrypted credentials together; `guest/sessiond` state lives in its `--state-dir` (`rm -rf` after stopping the daemon).
