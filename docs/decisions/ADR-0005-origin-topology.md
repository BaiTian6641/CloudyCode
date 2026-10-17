# ADR-0005: Stable app-local loopback origin for the APK workbench

- **Status:** Accepted for the development APK; physical-device re-verification required (S02.03/S04.01 exit).
- **Date:** 2026-10-17
- **Depends on:** ADR-0001 (platform boundaries), ADR-0004 (backend selection), S02.03 (origin topology), S04.01 (windows/sessions).

## Context

The APK hosts the upstream workbench in a WebView and keeps the backend credential native: an app-local loopback gateway forwards to the paired backend, and the WebView only ever talks to `http://127.0.0.1:<gatewayPort>`.

Measured on the emulator (2026-10-17, `docs/evidence/2026-10-17-S04-window-resize-emulator.md`):

- The gateway bound an **ephemeral** port (`ServerSocket(0)`). Every activity recreation or process restart produced a new port and therefore a new browser origin (`41679 → 37745 → 35907`).
- Browser storage (workbench settings, theme, open editors, service-worker caches) is keyed by scheme+host+port. A new origin after a display-density change or process death **lost the open notebook tab and theme** (observed).
- Rotation and display-size changes are handled in-place after adding `density` to `configChanges`, but process death inherently rebinds.

## Decision

Bind the gateway to a **stable loopback port** (`127.0.0.1:41873`, `PREFERRED_GATEWAY_PORT`) so the WebView origin is identical across activity recreations and process restarts.

Conflict rule (S02.03): if the preferred port is already owned by another process, the bind fails; the gateway **falls back to an ephemeral port and logs the fallback**. It never connects to or routes credentials through the occupying process. The fallback produces a fresh verified origin (state for that origin starts empty) — acceptable for a rare conflict, and it must be surfaced in diagnostics. Verified on the emulator, including a conflict from a **dual-stack IPv6 wildcard** listener (`docs/evidence/2026-10-17-S02-gateway-port-conflict.md`).

## Evidence after the change

| Test | Result |
|---|---|
| Cold start after install | origin `http://127.0.0.1:41873` |
| Notebook opened, then `am force-stop` + relaunch | same origin; tabs `test.ipynb`, `GBCode Conformance` restored; notebook editor reopened |
| Display size/density change (`wm size 1600x1000`, density 160) and reset | same origin (`41635` in that build), no recreation after the `density` config change; tabs retained |
| Rotation | handled in place; origin unchanged |

## Consequences

- Settings/theme/editor state and service-worker caches now survive ordinary app restarts and display changes, without enabling code-server remote storage (which remains OFF per E13).
- Two simultaneous workbench activities cannot both own 41873; multi-window (S04.01) must either share one gateway (preferred: one origin, one broker) or define per-window origins plus an explicit state model. This ADR does not authorize a second origin per window.
- The port is not a security boundary: the gateway binds loopback only, authenticates to the upstream with the native-held credential, and the session cookie is scoped to this origin. Another local app can attempt to bind the port first (DoS); the fallback handles it without credential exposure.
- APK/WebView data for this origin is user data (workbench settings, profiles). It must not be treated as disposable cache (S09).
- Physical lane must verify: no other installed app/service owns 41873 on the Googlebook, or the fallback path is visible to the user/diagnostics.

## Reopen triggers

Port conflict observed on the target device; multi-window implementation; a requirement for per-window isolation; change of backend/gateway topology.
