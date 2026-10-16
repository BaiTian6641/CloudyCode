# S10.01 adversarial trust-boundary review — current evidence (2026-10-16)

**Scope:** consolidates the adversarial evidence produced so far; the physical-lane adversarial matrix (LAN probes, malicious guest content, device-target confusion on real USB) is pending the physical device.

## Evidence by boundary

| Boundary | Test artifact | Result |
|---|---|---|
| Unauthenticated session daemon access | `guest/sessiond/test/authz.test.js` | 404 hygiene, `pairing_closed`, `rate_limited` (429 at 5/min), `unknown_app`, unauthenticated WS upgrade 403, loopback-only bind unreachable from LAN address — all pass |
| Protocol parser abuse | `guest/sessiond/test/fuzz.test.js` | garbage/truncated/oversize/mutated frames: bounded, typed failures, no crash/hang; server responsive after channel garbage |
| Pairing secret single-use + replay | `test/sessiond.test.js` | secret_invalid on wrong secret; pairing_closed on reuse; challenge single-use; expired sessions rejected |
| Session token forgery | authz + sessiond tests | HMAC verify fails on tampered tokens (bad signature rejected) |
| Native bridge from wrong origin | `docs/evidence/2026-10-16-S02-native-bridge.md` | bridge object not injected at all for `http://10.0.2.2:8083` (same server, wrong origin); subframe delivery rejected; oversized/malformed messages typed-error |
| Hardware authority from JS | NativeBridge | `requestDeviceSelection` → typed `unsupported_in_slice`; no exec/file/fetch/USB primitives exist on the bridge |
| Device lease theft | `test/devices.test.js` | `device_busy` on second session, `not_lease_owner` on foreign release, `stale_incarnation` on re-attached device |
| Gateway | `GatewayTest` (instrumentation) + S02.03 evidence | origin/referer rewrite scoped to the single fixed upstream; upstream-down 502; no arbitrary-target proxying exists |

## Open adversarial work (physical lane)

- LAN-reachability scan of all gateway/IDE/bridge ports from a second device (NET-04/NET-05 on the real topology).
- Malicious repository content (tasks/launch configs) in Restricted Mode on the device.
- Cookie cross-port leakage check on the physical loopback topology (NET-07/08).
- Malformed USB/IP frames against the production exporter (U01.10 fuzz lane).

## Verdict

No unresolved high-severity issue in the current design lane. The trust boundaries hold in the tested layers; the physical matrix is the remaining evidence.
