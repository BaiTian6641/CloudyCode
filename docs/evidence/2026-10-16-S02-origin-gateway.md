# S02.03 origin gateway — emulator/workstation lane (2026-10-16)

**Prototype:** `guest/gateway` (Node, zero deps): loopback, fixed-upstream HTTP+WS reverse proxy with origin/referer rewrite. Tests: 5/5 (`node --test` in `guest/gateway`) — GET with host rewrite, streaming (unbuffered), WS binary echo end-to-end, upstream-down 502, origin rewrite.

## End-to-end results through the gateway (WebView → adb reverse → gateway :8083 → code-server :8080)

| Flow | Result |
|---|---|
| Workbench page + assets through proxy | PASS (session cookie, favicon, static bundles) |
| Management WebSocket stability | PASS — two concurrent WS (management + extension-host channel) lived 283s/314s, closed only by client reload |
| code-server Origin check | FOUND+FIXED — code-server 403s WS upgrades whose `Origin` ≠ its host; gateway rewrites `Origin`/`Referer` to upstream (test 5) |
| Conformance extension webview through proxy | PASS (`webviews:2`, title "GBCode Conformance") |
| **Terminal creation + command through gateway** | **PASS after trust acceptance** — root cause was per-origin Workspace Trust (see below), not transport |

## Resolved: terminal path was per-origin Workspace Trust

- Investigation eliminated: gateway WS stability (multi-minute connections), code-server state (direct worked concurrently), ptyhost.log as a signal (empty even on direct success — not a reliable creation indicator), textarea targeting (visible/active selection verified).
- Decisive evidence: screencap showed the "Creating a terminal process requires executing code" trust dialog blocking the spawn. `xterm` count still incremented (UI tab is allowed in Restricted Mode), which had masked the spawn block.
- **Production implication (S02.03/S03.01/S09):** trust state is per-origin. The production gateway is a stable loopback origin, so trust is accepted once per workspace — but onboarding must surface the trust step (native or workbench dialog) before terminal/debug/task creation, and per-origin isolation means other origins never inherit the gateway's trust. The slice `trust` action (DOM click on "Trust Folder") proves the flow; the production UX will use the workbench's own trust UI.
- Also resolved alongside: the earlier "1–2ms upstream close" reconnect storm was code-server's Origin check (fixed by scoped origin rewrite), and dead restored terminals stealing the feed (fixed by visible-textarea targeting).
