# S05.01 device registry and exclusive leases — design lane (2026-10-16)

**Implementation:** `guest/sessiond/src/devices.js` (DeviceRegistry) wired into daemon ops `device.list`, `device.requestLease`, `device.releaseLease` (session-bound, typed wire errors). Tests: `test/devices.test.js` (7 cases) — 19/19 total suite green.

## Semantics proven by tests

| Property | Result |
|---|---|
| Opaque device IDs + fresh incarnation per physical attachment | PASS — same attachment event returns same record; re-attach gets new id+incarnation |
| Two same-model devices distinguishable | PASS |
| Exclusive lease per device; second session rejected `device_busy` (retriable) | PASS |
| Owner renew/release; foreign release rejected `not_lease_owner` | PASS |
| Stale incarnation rejected after re-attach (`stale_incarnation`) | PASS — an old attachment view cannot authorize a new physical device |
| Detach kills the lease with the attachment | PASS |
| Expired lease frees the device; lease IDs unique | PASS |

## Contract notes for U01

- Lease ops carry `(deviceId, incarnation, sessionSid)`; the wire error for contention is `device_busy` with `retriable: true`, matching the architecture's explicit-conflict requirement.
- `device.list` currently returns the registry only (empty until a hardware provider registers attachments — USB providers in U00/U01 are the only sources; nothing is fabricated).
- Lease TTL default 60s, capped at 300s per request; expiry is lazy (checked on contention) — production adds the sweep timer in S05.06 lifecycle work.
- The registry is intentionally transport-agnostic: USB/IP whole-device leases and (optional) serial-port leases share the same exclusivity/incarnation model.

## Not established here

Real device enumeration/grants (U00.x, blocked physical), composite-interface concurrency policy beyond the whole-device lease (U01.08 contract), persistent registry across daemon restarts (by design: fresh attachment events invalidate everything — correct for VM resets).
