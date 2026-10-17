# ADR-0006: Installed-frontend delivery — APK-owned Code-OSS workbench assets

- **Status:** Accepted. Supersedes the **frontend-delivery** requirement only (guest-hosted workbench website in the WebView); all unrelated requirements in ADR-0001…0005 remain in force.
- **Date:** 2026-10-17
- **Depends on:** ADR-0001 (platform boundaries), ADR-0002 (registry/licensing), ADR-0004 (backend selection, unchanged for guest services), ADR-0005 (origin topology), S02 lane (pairing/session/gateway).

## Context

The production goal is an Android desktop application containing the **original Code-OSS workbench**, with the guest Debian VM running the remote agent, workspace Node extension host, terminals, Git, language servers, compilers and debug adapters (VS Code + WSL as the workflow reference; Antigravity as the onboarding reference).

The working baseline loads the backend's **guest-hosted workbench website** through the app-local gateway into a WebView. That exercises the real workbench but fails the revised production requirement: core workbench assets and bootstrap must belong to the installed client, and the main workbench must not silently fetch missing core HTML/JS/CSS/workers from the VM or the Internet. Guest-hosted loading remains a useful baseline and a rollback mode.

Reconciliation of current claims: `docs/compatibility/installed-frontend-matrix.md`.

## Source-level integration points (microsoft/vscode `2a59476c`, 1.141.0)

These are the actual interfaces at the pinned commit — integration points, not a stable Android SDK:

| Interface | Location | Role |
|---|---|---|
| `IWorkbenchConstructionOptions` | `src/vs/workbench/browser/web.api.ts` | Embedder contract: `remoteAuthority`, `serverBasePath`, `connectionToken` (or `vscode-tkn` cookie), `webSocketFactory`, `resourceUriProvider`/`remoteResourceProvider`, `workspaceProvider`, `secretStorageProvider`, `additionalBuiltinExtensions` |
| workbench bootstrap | `src/vs/code/browser/workbench/workbench.ts` → `create(container, options)` (`workbench.web.main.internal`) | App page constructs the workbench from packaged assets |
| remote service wiring | `src/vs/workbench/browser/web.main.ts:324,359-362` | `connectionToken` resolution; `RemoteSocketFactoryService` registered with `BrowserSocketFactory(webSocketFactory)`; `RemoteAgentService` |
| socket factory | `src/vs/platform/remote/browser/browserSocketFactory.ts` | `IWebSocketFactory.create(url, debugLabel)`; default opens `ws(s)://host:port<path>?<query>&skipWebSocketFrames=false` |
| connection handshake | `src/vs/platform/remote/common/remoteAgentConnection.ts:185-260` | `remoteSocketFactoryService.connect(connectTo, RemoteAuthorities.getServerRootPath(), "reconnectionToken=…&reconnection=…")`, then protocol hello with `auth: connectionToken` |
| resource root | `src/vs/base/common/network.ts:210-218` | `serverRootPath = posix.join(serverBasePath ?? '/', <quality>-<commit>)` |
| server HTTP/WS routes | `src/vs/server/node/remoteExtensionHostAgentServer.ts:115-236` | `/version`, `/delay-shutdown`, `/vscode-remote-resource`, web client server for the rest; WS upgrade at the server root path (`skipWebSocketFrames`, `reconnectionToken`) → management/extension-host channel connections |

## Decision

### 1. Asset classes

| Class | Owner | Source | Allowed origin |
|---|---|---|---|
| **A. Core workbench + bootstrap** (HTML/JS/CSS/workers/media/nls/fonts, incl. lazy-loaded core deps) | **APK** | `vscode-web` dist built from pinned `2a59476c`; asset manifest generated from the actual build | app gateway origin only; guest core routes **denied** and traced |
| B. Guest IDE services (remote agent channels: files, terminal, extension host, tasks, debug) | Guest Debian VM | `vscode-reh-web-linux-{x64,arm64}` (same commit) — code-server 4.141.0 stays the fallback/service baseline | gateway WebSocket only |
| C. Workspace/browser extensions + their webview/notebook resources | Guest/registry (dynamic) | Open VSX / VSIX (ADR-0002) | extension resource routes; **may not** replace the privileged app document or inherit Android authority |
| D. Forwarded development apps | Guest ports | per S02.05 | separate; never the workbench origin |

### 2. Origin, transport, session binding

- The app-local gateway origin (`http://127.0.0.1:41873`, ADR-0005) serves class A from APK assets and proxies class B over the authenticated WebSocket (`X-GBCode-Session` per `contracts/session-v1.md` for sessiond; VS Code `connectionToken` held native and injected via `IWorkbenchConstructionOptions.connectionToken`).
- The workbench is constructed with `remoteAuthority` resolving to the gateway; `webSocketFactory` (or the default factory against the gateway host/port) carries the remote-agent socket. No TLS bypass: loopback only (S02.04 bound).
- Native bridge authority stays main-frame + exact-origin scoped (S02.04). Class C content never receives it.

### 3. State owners

- UI settings, profiles and unsaved buffers are **user data**, not disposable WebView cache (ADR-0005, E13): persist them (working-copy backup service or a narrow adapter), never enable plaintext remote secret persistence.
- Guest filesystem is the file authority; SAF is grant/import/export only (S03).
- Pairing/identity/credentials stay native (S02 lane, verified).

### 4. Compatibility set

Client bundle, embedded Code-OSS revision (`2a59476c` / 1.141.0), guest `reh-web` build of the same commit, session protocol v1, gateway/bridge versions form one tested set, recorded in `versions.lock.json`. Mismatch between the APK asset manifest and the guest revision must **fail closed** (refuse or warn explicitly), never silently fall back to guest-served core assets. No Microsoft VS Code Server, no product/Marketplace spoofing (ADR-0002).

### 5. Rollback limits

- Guest-hosted workbench loading (the previous baseline) remains available as an explicit rollback mode behind a switch; it is not the production default.
- Reverting to baseline mode keeps pairing/gateway/bridge code intact (S02 contracts unchanged by this ADR).
- An APK downgrade with a newer asset manifest + guest revision may leave state that the older client cannot read; document per release (S09/S11).

## Consequences

- Asset pipeline: build `vscode-web` (min) from the pinned source, generate a manifest (path + hash) from the actual build, package into APK assets, serve via the gateway; deny + trace guest core-asset routes.
- Offline proof required before claiming the milestone: fresh profile, backend stopped, network off → stock workbench UI, Command Palette, Settings and an untitled editor start from packaged assets.
- Notebooks/extension webviews (class C) must be re-verified under the new origin (the E12 service-worker findings were measured against code-server's webview patch).
- Workspace Trust behavior is preserved as upstream; pairing does not imply repository trust.

## Reopen triggers

A Google platform constraint on asset size/WebView storage; an upstream interface change at a revision bump; evidence that class C requires class A files from the guest; a decision to switch the guest service host from code-server to pristine `reh-web` for all lanes.
