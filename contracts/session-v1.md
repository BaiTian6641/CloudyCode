# GBCode session contract v1 (draft for S02.01/S02.02 freeze)

**Status:** draft — to be frozen by S02.06 after the exercised compatibility tests. Implements architecture §5.5 (pairing) and §13.3 (framing). No Google/Microsoft API is implied.

## 1. Roles and identities

- **Guest**: `gbcode-sessiond` in the built-in Debian VM. Holds a persistent **server identity**: ed25519 keypair generated at first bootstrap, private key mode 0600 under `~/.config/gbcode/`, public key fingerprint = `SHA-256(raw public key)` shown to the user at bootstrap.
- **App**: the GBCode APK. Holds a per-install **app identity**: ed25519 keypair generated on first run, private key in Android Keystore where supported.
- **Session**: short-lived proof derived from an enrolled app identity. A session is not the pairing secret and not the device lease.

## 2. Pairing (one-time enrollment)

1. User starts Terminal and runs the inspected bootstrap (S09.01). The daemon prints: bind address, port, server fingerprint, and a **one-time pairing secret** (32 bytes, base64url, single use, 10-minute enrollment window).
2. The app connects over the measured route (S00.03) and calls `POST /pair`:
   ```json
   {
     "v": 1,
     "pairingSecret": "<base64url>",
     "appPublicKey": "<base64url raw ed25519>",
     "appLabel": "gbcode-apk",
     "nonce": "<16 random bytes base64url>"
   }
   ```
3. Daemon verifies: enrollment window open, secret matches (constant-time), nonce not seen. On success it stores the app key, **invalidates the secret**, and returns the signed enrollment receipt:
   ```json
   {
     "v": 1,
     "serverPublicKey": "<base64url>",
     "serverFingerprint": "<hex sha256>",
     "enrolledApp": "<hex sha256(appPublicKey)>",
     "receiptSig": "<base64url ed25519(serverKey, 'enroll'|appKeyHash|nonce)>"
   }
   ```
4. Failure responses: `pairing_closed`, `secret_invalid`, `secret_consumed`, `nonce_replayed`, `rate_limited` (max 5 attempts/minute/IP). Failures never reveal which field failed beyond the listed codes.

## 3. Sessions

`POST /session` with header `X-GBCode-App: <appKeyHash>` and body:
```json
{ "v": 1, "challenge": "<last server nonce from GET /session/challenge>", "appSig": "<base64url ed25519(appKey, 'session'|challenge)>" }
```
Returns `{ "v": 1, "session": "<token>", "expiresAt": <unix ms> }`. Token format: `v1.<payload64>.<sig64>` where payload = `{app, iat, exp, sid}` and sig = HMAC-SHA-256(sessionSecret, payload) with a daemon-rotated secret. Default TTL 12h; revocation list survives restart (S09).

## 4. Message channel (WebSocket)

`GET /channel?session=<token>` upgrades to RFC 6455. Frames:

- **Control** (JSON, opcode 1): `{v:1, id:<u32>, op:<string>, payload:<object>}`; reply `{v:1, id, ok:true, payload}` or `{v:1, id, ok:false, error:{code, message, retriable}}`.
- **Binary** (opcode 2): 8-byte header `{streamId:u32, seq:u32}` + payload; per-stream monotonic seq; receiver reports gaps.
- Limits: control frame ≤ 64 KiB, binary frame ≤ 1 MiB, 64 in-flight ops, unknown op → `unsupported_op`, unknown major version → `unsupported_version`.
- Cancellation: `{op:"cancel", id}` best-effort; completion states are `accepted`, `completed`, `failed`, `outcome_unknown`.

Initial ops (S02.02 scope only): `session.hello`, `session.capabilities`, `session.ping`, `session.close`, `device.list` (empty until S05), `error`. Hardware ops are defined by S05/U01 contracts, not here.

## 5. Security invariants

- No pairing endpoint outside an explicit enrollment window; no unauthenticated op except `/healthz`, `/pair`, `/session/challenge`, `/session`.
- Credentials never in URLs beyond the single session-token upgrade query (to be moved to a header if the WebView path permits; recorded risk), never in logs, never in the shared workspace.
- Server identity change (VM reset) requires re-pairing; unchanged port/folder names are not identity evidence.
- Constant-time secret comparison; rate limiting on pairing; per-session replay protection via challenge nonces.

## 6. Compatibility

Peers reject unknown major versions and unknown required fields; unknown optional fields are ignored and logged once. Schema changes bump `v` only for incompatible changes; additions are minor.
