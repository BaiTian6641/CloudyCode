# S02 pairing client — APK ↔ sessiond end-to-end (2026-10-16)

**Flow:** slice APK (`SessionClient` + pure-Java `Ed25519` + `WsClient`) → `adb reverse tcp:9123` → `gbcode-sessiond` on the workstation. Protocol: `contracts/session-v1.md`.

**Result: PASS** — one intent (`pairtest`) executed:

```
enrolledApp:    93576ebfaef56cb3…   (POST /pair, one-time secret, single use)
sessionPrefix:  v1.eyJhcHAiO…       (POST /session, ed25519 challenge signature verified)
hello:          {"v":1,"id":1,"ok":true,"payload":{"agent":"gbcode-sessiond/0.1.0",
                 "fingerprint":"3ccad41b739608a0…","protocol":1,"echo":"from-apk"}}
```

## Verified properties

1. **Pure-Java Ed25519 produces RFC 8032-valid signatures** — the daemon's Node `crypto.verify` accepted the challenge signature. This also removes the provider lottery: on this emulator, `KeyPairGenerator("Ed25519")` routes to AndroidKeyStore and throws `IllegalStateException: Not initialized`; provider `"Conscrypt"` does not exist. The bundled implementation (BigInteger math, auditable ~200 lines) is deterministic across devices including the Googlebook.
2. Enrollment is single-use and time-windowed (the first attempt after window expiry returned `pairing_closed`, correctly).
3. Channel authentication: WS upgrade only with a valid HMAC session token; hello roundtrip carries the server fingerprint for display/pinning.
4. Intent-driven native actions must not require a loaded page — `maybeDispatchAction` now exempts native-only actions; `onNewIntent` calls `setIntent` so extras are never stale (both were real bugs found by this fixture).

## Not established here

Persistent app-key storage in Android Keystore (production S02.01 requirement; this fixture generates per-run keys), fingerprint display UX, session renewal/revocation flows, physical-lane route to the VM-resident daemon.
