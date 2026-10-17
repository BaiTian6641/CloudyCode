# S02.03 gateway port-conflict test (2026-10-17)

Validates ADR-0005's conflict rule on the emulator: preferred port taken → ephemeral fallback, never routing through the occupier.

## Method

1. Force-stopped `dev.gbcode.app`, freeing `127.0.0.1:41873`.
2. Occupied 41873 from the emulator shell: `nc -l -p 41873`. `/proc/net/tcp6` showed a **dual-stack IPv6 wildcard listener** (`:::A391`, state `0A`, uid 2000/shell) — so an IPv6 listener also conflicts with the IPv4 bind here.
3. Relaunched the app; read `logcat -s GBCodeGateway`.
4. Opened the workbench at the fallback origin via CDP; checked it renders.
5. Killed the holder (`pkill -f "nc -l -p 41873"`), force-stopped and relaunched the app; verified the stable origin and restored tabs.

## Results

| Step | Observed |
|---|---|
| App start with 41873 occupied | `W GBCodeGateway: preferred port 41873 unavailable (bind failed: EADDRINUSE (Address already in use)); using ephemeral port` → `listening on 127.0.0.1:45989` |
| Workbench at fallback origin | PASS — workbench + Explorer render at `http://127.0.0.1:45989`; state is fresh (no prior tabs; trust prompt again) |
| Credential handling | The gateway never connected to the occupier; bind failed before any request. The backend credential is only used against the app's own gateway upstream |
| Holder killed, app relaunched | Back on `http://127.0.0.1:41873`; tabs `test.ipynb`, `GBCode Conformance` restored (state bucket intact) |

## Automated coverage

| Suite | Result |
|---|---|
| `GatewayTest` (instrumented, `emulator-5554`) | **7/7 PASS** — includes new `preferredPortBindsWhenFree` (binds exactly the requested port) and `occupiedPreferredPortFallsBack` (falls back, still proxies the single upstream) |
| `guest/sessiond` (`node --test test/*.test.js`) | 22/22 PASS |
| `guest/gateway` (`node --test test/*.test.js`) | 5/5 PASS |

Both package `test` scripts were changed from `node --test test/` (treated as a module on Node 22) to the explicit glob `node --test test/*.test.js`.

## Notes

- Conflict can come from an **IPv6 wildcard** listener; the IPv4 bind fails with `EADDRINUSE` as expected. The fallback covers both families.
- A fallback origin is a fresh browser-storage bucket (settings, trust decision, open tabs start empty). ADR-0005 accepts this for the rare conflict case; diagnostics should surface that the preferred port was unavailable so the user can understand the reset.
- The test also re-confirmed that the stable origin preserves state across restarts once the preferred port is available.
