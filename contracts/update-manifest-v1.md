# S09.03 signed coordinated updates — manifest design (draft for freeze review)

**Status:** design draft; implementation and signing-key custody are S09.03/S11 work. Aligns with architecture §12.5.

## Compatibility set

GBCode ships four versioned components that must stay compatible:

| Component | Lives in | Updated by |
|---|---|---|
| APK (shell, gateway, bridge, session client) | Android | Play/sideload update |
| Backend bundle (code-server/pristine reh-web) | guest `~/.local/share/gbcode/releases/<id>/` | guest bootstrap updater |
| `gbcode-sessiond` | guest, inside the backend release | same |
| Companion extension (`gbcode-conformance` line) | guest extension dir | backend-managed install |

A release is a **manifest**: the four coordinates with hashes, plus protocol/schema versions and rollback data. APK ↔ backend protocol mismatches are rejected at connect time (S02 version negotiation).

## Manifest schema (version 1)

```json
{
  "manifestVersion": 1,
  "releaseId": "2026-10-16.1",
  "issuedAt": "2026-10-16T00:00:00Z",
  "channel": "dev",
  "components": {
    "apk":      { "version": "0.1.0", "minProtocol": 1, "sha256": "<hex>", "abis": ["arm64-v8a", "x86_64"] },
    "backend":  { "version": "4.141.0", "upstreamCommit": "2a59476c…", "sha256": "<hex>", "arches": ["linux-arm64", "linux-x64"] },
    "sessiond": { "version": "0.1.0", "sha256": "<hex>" },
    "extension":{ "id": "gbcode.gbcode-conformance", "version": "0.3.0", "sha256": "<hex>" }
  },
  "compatibility": {
    "minAppProtocol": 1, "maxAppProtocol": 1,
    "minDaemonProtocol": 1, "maxDaemonProtocol": 1
  },
  "rollbackTo": "2026-09-30.0",
  "signature": "<base64url ed25519 over canonical JSON of everything above>"
}
```

## Rules

1. **Atomicity:** the guest updater installs a new release into `releases/<id>`, verifies ALL component hashes, then flips `current`. The APK verifies the manifest signature and protocol compatibility before the guest flip is allowed to matter (app refuses to connect to an incompatible/incompatible-signature backend).
2. **Signature:** ed25519, offline release key; the public key ships in the APK. Key custody is human-held (stop condition 3) — signing is never automated from this repo.
3. **Rollback:** `rollbackTo` points to the previous signed manifest; downgrade restores that release directory and records irreversible migrations as such. A failed update leaves `current` untouched.
4. **Version negotiation:** `session.hello`/`session.capabilities` exchange protocol versions; mismatched major → clean, explained refusal (never silent degradation).
5. **Offline:** updates download only when online; a provisioned device runs indefinitely offline on the installed manifest.

## Out of scope for v1

Play in-app update API, delta patches, staged rollout percentages. Sideload-first.
