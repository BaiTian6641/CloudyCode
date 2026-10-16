# S02.03 origin gateway — emulator/workstation lane (2026-10-16)

**Prototype:** `guest/gateway` (Node, zero deps): loopback, fixed-upstream HTTP+WS reverse proxy with origin/referer rewrite. Tests: 5/5 (`node --test` in `guest/gateway`) — GET with host rewrite, streaming (unbuffered), WS binary echo end-to-end, upstream-down 502, origin rewrite.

## End-to-end results through the gateway (WebView → adb reverse → gateway :8083 → code-server :8080)

| Flow | Result |
|---|---|
| Workbench page + assets through proxy | PASS (session cookie, favicon, static bundles) |
| Management WebSocket stability | PASS — two concurrent WS (management + extension-host channel) lived 283s/314s, closed only by client reload |
| code-server Origin check | FOUND+FIXED — code-server 403s WS upgrades whose `Origin` ≠ its host; gateway rewrites `Origin`/`Referer` to upstream (test 5) |
| Conformance extension webview through proxy | PASS (`webviews:2`, title "GBCode Conformance") |
| **Terminal creation + command through gateway** | **OPEN ISSUE** — management WS fine but no new pty appears in `ptyhost.log`; same flow direct to :8080 works (mark `GBCODE_SLICE_WEBVIEW_OK` written 04:47) |

## Open issue: terminal path through gateway

- Symptom: menuterm "New Terminal" click succeeds in the browser, but no persistent-process entry appears in `ptyhost.log` (direct connection creates one immediately; the direct path then executes the fed command).
- Ruled out: gateway WS stability (connections live minutes), code-server state (direct works at the same time), the feed mechanism (works direct and works on pristine control).
- Next hypotheses: (1) permessage-deflate negotiation subtlety on the management channel — VS Code's protocol may tolerate direct compressed frames differently through a pipe; (2) the pty-lifecycle roundtrip needs the extension-host channel, whose reconnect/exit timing through the gateway differs (extension host process exited code 0 shortly after a proxied reconnect); (3) pty socket path resolution via `remoteAuthority` differing when proxied (logs show `remoteAuthority=127.0.0.1:8083` — correct for the gateway origin).
- **Impact:** none on the production design yet — S02.03's production gateway is an APK-owned loopback component; this prototype is Node-side proof. The issue must be resolved (or the terminal path re-verified) before S09.02 production cutover, and re-verified on the physical lane.

## Production implications validated here

- App-local gateway on loopback is a viable secure-context topology (matches emulator secure-context evidence: `127.0.0.1` origins get `isSecureContext=true`).
- Upstream credential isolation is feasible (gateway terminates client TLS/HTTP and holds upstream auth — not yet implemented beyond password-cookie passthrough).
- Origin rewriting must be scoped to the pre-approved upstream only (implemented via `rewriteOrigin` option, default on for the fixed upstream).
