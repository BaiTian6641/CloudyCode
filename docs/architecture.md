# GBCode architecture overview (current implementation state)

**Deep spec:** [architecture specification v2.1](Googlebook_CodeOSS_APK_Engineering_Plan_v2.md). This page is the map of what exists and how the pieces connect today.

## Topology

```
Googlebook Android host
  android/app (production APK)
    OnboardingActivity ── pair (ed25519, one-time secret) ──┐
    GbCodeApp (state, Keystore-persisted identity)           │  paired session
    connection/Gateway.kt  (in-app loopback proxy)           │  (sessiond)
    bridge/NativeBridge    (origin-scoped, main-frame only)  ▼
    WorkbenchActivity ── WebView ── http://127.0.0.1:<gw> ──► backend (Code - OSS server)
                                                              ▲
Googlebook built-in Debian Terminal VM ───────────────────────┘
  guest/bootstrap (install) → guest/sessiond (pairing/sessions/leases)
                              → backend bundle (reh-web / code-server)
                              → guest/doctor (capability record)
```

## Components and status

| Component | Path | State |
|---|---|---|
| Production APK shell | `android/app/` | v0.4 verified on emulator (pairing, persistence, gateway, native login, workbench) |
| Session daemon | `guest/sessiond/` | 22 tests green (protocol, authz, registry, vectors, fuzz) |
| Origin gateway (reference) | `guest/gateway/` | 5 tests green; Kotlin port in the APK |
| Guest bootstrap | `guest/bootstrap/` | verified install/uninstall/checksum on workstation |
| Capability doctor | `guest/doctor/` | emits §13.2 JSON; workstation-verified |
| Conformance extension | `extensions/conformance/` | runs on code-server, OpenVSCode, pristine control |
| Backend candidates | `versions.lock.json` | code-server 4.141.0 (selected, provisional ADR-0004), OpenVSCode 1.109.5 (fallback) |
| Upstream control | `tools/release/` | pristine Code - OSS 1.141.0 built from source, x64 + arm64 |

## Trust boundaries (enforced today)

- Pairing: one-time secret, single-use, 10-minute window; sessions are HMAC tokens; the daemon fingerprint is displayed for verification.
- The APK's native bridge is injected only for the exact backend origin, main frame only; wrong origin → not present.
- The backend password stays in app-side code; only the session cookie enters the WebView.
- Device authority is a whole-device lease with fresh incarnation per attachment (registry in sessiond); no per-extension fake identity.

## Lanes and evidence labels

- `emulator` / `workstation` lanes: everything in `docs/evidence/` so far.
- `physical` lane: pending the Googlebook's release (see README Blockers). Emulator results never become platform claims (AGENTS.md).

## Verification

`tools/verify.sh` — sessiond + gateway + all APK builds + gateway instrumentation on the emulator, one command, exit non-zero on failure.
