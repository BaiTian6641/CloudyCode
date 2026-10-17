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
| sessiond | `cd guest/sessiond && node --test test/*.test.js` | **26/26 pass** (protocol, authz, receipt/proof, vectors, fuzz) |
| gateway (Node reference) | `cd guest/gateway && node --test test/*.test.js` | 5/5 pass |
| instrumented (emulator only) | `ANDROID_SERIAL=emulator-5554 adb -s emulator-5554 install -r …` then `adb -s emulator-5554 shell am instrument -w dev.gbcode.app.test/androidx.test.runner.AndroidJUnitRunner` | **34/34 pass**: `GatewayTest` 15 (proxy/WS/keepalive/ports + 8 authorization cases), `ReceiptVerificationTest` 7, `ServerProofVerificationTest` 6, `CredentialStorageTest` 4, `CookiesTest` 2 |
| builds | `gradle assembleDebug assembleDebugAndroidTest` (offline) | clean |

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

Security and code reviews were run by separate reviewers (see the S02 review addendum below/next commit); findings and dispositions are recorded there. Implementation and review are separate agents per AGENTS.md.

## 5. Remaining limitations (not fixed here)

1. **Local non-browser clients:** the gateway binds loopback and validates browser-level Host/Origin, but a native app on the same device can forge those headers. A per-install gateway token/lease remains S02.05 work.
2. **Session token in the WebSocket URL query** (`contracts/session-v1.md` §5 recorded risk) — still present.
3. **Remote/TLS backends** are rejected rather than supported; a reviewed TLS design is future work.
4. **Revocation UI** and revoked-identity reconnect tests are not implemented (S09).
5. **Bridge adversarial fixture** (malicious iframe/oversized-message runtime tests) pending; current coverage is code-level allowlist + platform origin filter + explicit check.
6. **Physical-lane acceptance** (pairing over the real Terminal route, LAN exposure, local-network permission matrix, VM-reset re-pairing) is blocked on device release.

## 6. Rollback

- Each milestone is a separate commit and can be reverted independently: `7030974` (M1), `2f3140e` (M2), `6d746f8` (M3), `48584ba` (M4).
- **M3 migration is one-way:** reverting to pre-M3 code leaves `passwordEnc` unreadable and no `backendPassword`; the user re-enters the backend password (pairing identity is unaffected).
- **M2 contract change is additive:** reverting the client leaves the server's `serverProof` field harmless; reverting the server makes the new client fail closed (missing `serverProof`) — revert the client first or upgrade the daemon together.
- **M1/M4** are behavior-only fixes; reverting restores the truncation/authorization bugs and is not recommended.
- Data: `adb shell pm clear dev.gbcode.app` (or uninstall) removes pairing state and the Keystore-encrypted credentials together; `guest/sessiond` state lives in its `--state-dir` (`rm -rf` after stopping the daemon).
