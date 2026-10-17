# ADR-0006 milestones 3–4 — installed workbench ↔ guest IDE services, real workflow (2026-10-17)

The installed (APK-owned) Code-OSS workbench now connects to the **guest remote agent** over the app gateway, and a real workflow runs end to end: guest folder, edit/save, guest terminal, guest language server, extension webview. Emulator lane; the "guest" is a pristine `vscode-reh-web` build of the same pinned commit on this workstation (the Googlebook Debian VM is not released).

## Compatible set (verified in this run)

| Layer | Value |
|---|---|
| Workbench assets (APK) | Code-OSS `2a59476c9bfcb90b3ddc372c36762471b7dfad1c` / 1.141.0; 2428 files, 178 326 031 bytes; manifest sha256 `b5bd462d459b4d56af3495ecc7e2fb30b5a616d41911b7e6f7fcac9e80d3a643` |
| Guest remote agent | `vscode-reh-web-linux-x64`, same commit; `out/server-main.js` sha256 `f9b032b062be28dedb73736c7742f86bf804cb6dde156033ace77a2ba09332c4` |
| Transport | app gateway `127.0.0.1:41873` (loopback, Host/Origin/method checks) → guest `127.0.0.1:8085`; remote-agent WebSocket at `/<quality>-<commit>` (`/oss-2a59476c…`); `connectionToken` held natively, injected through the app-owned page configuration |
| Pairing | sessiond receipt-verified pairing; authenticated reconnect verified (S02 lane) |

## Defect found and fixed during connection

1. **Malformed WebSocket URL from `serverBasePath`.** The config used `serverBasePath: ""`; the client computes `posix.join(serverBasePath, '<quality>-<commit>')`, producing `oss-…` without a leading slash → `new WebSocket('ws://127.0.0.1:41873oss-…')` threw *SyntaxError: invalid URL* (proved by wrapping `window.WebSocket`: 10 constructor failures; perf marks `willCreateSocket/Management` → `didCreateSocketError/Management`). Fixed to `"/"` (what `webClientServer` passes). The WS then connected.
2. **Webview host page came from the OSS CDN template.** The stock OSS `product.json` sets `webviewContentExternalBaseUrlTemplate` to `https://{{uuid}}.vscode-cdn.net/insider/<foreign commit>/…`; webview iframes loaded that foreign build over the Internet (observed `0nac6…vscode-cdn.net/insider/ef65ac…`), violating the app-owned-core requirement and breaking offline webviews. Fixed with the stock hook `options.webviewEndpoint` → `http://127.0.0.1:<gateway>/out/vs/workbench/contrib/webview/browser/pre/` (packaged pre-page). After the fix, webview iframes are `127.0.0.1:41873/.../pre/index.html` and the panel renders.
3. **Guest packaging gap (recorded, not hidden):** our reh-web package was built without the `@vscode/vsce-sign` runtime dependency, so extension installs failed with "Signature verification was not executed" before any user decision. Copied `@vscode/vsce-sign` + `@vscode/vsce-sign-linux-x64` from the build tree into the guest package. The install then followed the **stock** path: signature verification ran, the extension (unsigned, Open VSX) triggered the stock dialog *"Cannot install … because Code - OSS cannot verify the extension signature"* → "Install Anyway (Don't Verify Signature)" (user-approved, upstream UI). The release pipeline must include this dependency.

## Workflow evidence (emulator, guest service on workstation)

| Step | Result | Evidence |
|---|---|---|
| Installed workbench connects to the guest | PASS — WS established; guest log: `ManagementConnection`, `ExtensionHostConnection`, `Launched Extension Host Process` (PID recorded) | guest `remoteagent.log`; gateway trace |
| Guest folder opened | PASS — Explorer lists guest `/tmp/gbcode-m4-ws` (`hello.py`, `README.md`, `.vscode`); title `hello.py - gbcode-m4-ws` | screenshot `ADR0006-m34-guest-workspace.png` |
| Workspace Trust preserved | PASS — folder opened in Restricted Mode; trust granted explicitly through the stock Trust UI for the disposable fixture | `ADR0006-m34-trust.png` |
| Edit + save to guest | PASS — appended `# m4 installed-frontend edit`; saved via Ctrl+S; guest file content updated, sha256 `9ed6038c…` → `ab2df27c…` | shell read-back |
| Integrated guest terminal | PASS — typed `echo M4_TERM_OK > /tmp/gbcode-m4-term.txt`; file on guest = `M4_TERM_OK`; shell processes `/usr/bin/bash --init-file <guest>/shellIntegration-bash.sh` under the guest pty host (started on demand) | shell read-back + `ps` |
| Real language completion | PASS — `Pyrefly - Python Language Tooling` installed **through the Extensions view from Open VSX** into the guest (`/tmp/gbcode-guest-m3/extensions/meta.pyrefly-1.4.0-linux-x64`); status bar `Pyrefly (Basic)`; hover `(function) greet: def greet(name: str) -> str`; LSP process `/tmp/gbcode-guest-m3/extensions/.../pyrefly lsp` (PID 1501610) with parent = guest extension host (PID 1498796) | `ADR0006-m34-pyrefly-hover.png`, process tree |
| Extension webview | PASS — conformance webview panel renders the report (node 24.04.1, `/bin/bash`, workspace `vscode-remote://…/tmp/gbcode-m4-ws`, `isTrusted:true`, product `Code - OSS` 1.141.0, commit `2a59476c9b…`); webview host iframe served from the app package | `ADR0006-m34-webview.png` |
| Debug session (Python) | PASS — installed `ms-python.debugpy` 2026.6.0 **and its required `ms-python.python` 2026.4.0** into the guest (debugpy refuses to activate without it); guest `.vscode/launch.json` (`type: debugpy`, `stopOnEntry`); F5 from the installed workbench: debug toolbar + first-line stop; adapter `…/ms-python.debugpy-2026.6.0/bundled/libs/debugpy/adapter` (PID 1520061) and launcher (1520112) parented by the **guest extension host** (1519366), debuggee `/bin/python3.14` (1520117) under the launcher; continue → session ends and every debugpy process exits | `ADR0006-m34-debug-stopOnEntry.png`, `ADR0006-m34-debug-complete.png`, `ps` tree |
| Guest core asset routes denied | PASS — `GET /oss-…/static/out/vs/loader.js` → 403 + `deny-core` log; `/static/node_modules/...` → 403 + `deny-core`; dynamic `/static/extensions/...` proxied (class C); workbench traffic proxied only WS + `vscode-remote-resource` | gateway trace |
| No external core fetches | PASS for workbench/core; the webview CDN default was found and eliminated (defect 2) | iframe URL trace |

## Request trace (categories)

- **App-owned (local, never proxied):** `/out/**`, `/extensions/**` (packaged built-ins), `/resources/server/**`, `/`.
- **Guest-allowed:** `/<quality>-<commit>` (WS upgrade), `/<quality>-<commit>/vscode-remote-resource` (dynamic extension/webview resources).
- **Guest-denied:** `/<quality>-<commit>/static/out/**`, `/static/node_modules/**`, `/static/resources/**`, `*/workbench.{html,js,css}` → 403 + `deny-core` trace.

## Startup orchestration: app launch starts the Linux backend

Reference check (Antigravity): Google Antigravity's Googlebook surface is an Android app (`com.google.android.apps.antigravity`, pre-installed via Google Play) whose agent/backend executes **inside the Debian environment** (its IDE/CLI builds target Googlebook's glibc). Its docs expose no third-party "run a command in the VM" API. GBCode therefore implements the same placement with its own in-guest daemon: the app asks the daemon to start the backend over the authenticated channel.

Implementation: `backend.ensure` op (contracts/session-v1.md §4.1) + `--backend-config` launcher. The daemon spawns the backend detached (log `<state-dir>/backend.log`), polls its port, and reports `running|started|starting|failed|not_configured`. The app calls it after pairing and on every authenticated reconnect **before** opening the workbench; failures are non-fatal.

| Scenario | Result |
|---|---|
| Cold start with backend stopped (port 8085 closed, no server process) → user pairs | PASS — app log `backend: started`; daemon spawned the server (PID 1531176, 13:31:46); `ss` shows LISTEN 127.0.0.1:8085; guest log shows ManagementConnection + ExtensionHostConnection and the extension host process; workbench opens on `gbcode-m4-ws` |
| Relaunch (force-stop → open) | PASS — `backend: running`; same PID 1531176; no duplicate process; workbench reconnects |
| Launcher cannot spawn | PASS (test) — `failed` with the spawn error instead of hanging |

Evidence: `ADR0006-startup-backend-started.png`; app log lines; `ss -ltnp`; guest `backend.log`. Tests: 3 new sessiond tests (not_configured, `started`→`running`, fail-fast ENOENT); `tools/verify.sh` ALL GREEN.

Limitation: starting the **VM itself** (and the daemon inside it) is the Googlebook-platform lane — no public third-party API was found. This orchestration assumes the daemon already runs in the Linux environment (real Terminal VM, or the workstation lane here).

## Tests

`tools/verify.sh` → ALL GREEN. sessiond 27/27; gateway 5/5; instrumented 47/47 (emulator only), including `WebAssetGatewayTest` with the renderer's new parameters.

## Remaining limitations

1. Physical Googlebook lanes remain blocked (device owned by another project): real Terminal VM connectivity, mount semantics, input, lifecycle.
2. `@vscode/vsce-sign` packaging gap must be fixed in the release build (S11); the workstation lane worked around it by copying the module.
3. Terminal xterm rows are not DOM-readable in this WebView (canvas rendering), so the terminal proof is the side effect on the guest filesystem plus the guest pty host process tree.
4. Repeated reloads leave reconnection-grace extension hosts on the guest (expected upstream behavior; bounded by the grace timeout).
5. Notebook/debug/profile recovery under the installed frontend (milestone 5): **debug now verified** (rows above); notebooks still need the Jupyter extension + kernel under the installed origin (code-server lane remains the notebook baseline); user settings/UI state in installed-frontend mode are stored in the WebView's profile, not the guest user-data-dir — define the profile semantics in S02.19/S09.
6. The shared emulator dropped the app process and its data mid-run; re-pairing exercised the onboarding flow again (fresh daemon + `kind=reh-web` + token). The debugpy dependency on `ms-python.python` is upstream behavior; the guest needs both.
7. Webview sandbox = upstream policy; class-C resources are still served by the guest (allowed) and must never replace the app document (deny rules).

## Rollback

Revert this increment's commits; the gateway with `assets = null` and no remote authority keeps the guest-hosted baseline. The guest service and its state live in `/tmp/gbcode-guest-m3` (disposable); the app's pairing state is replaced on the next pairing.
