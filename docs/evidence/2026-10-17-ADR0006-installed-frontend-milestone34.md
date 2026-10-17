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

## Platform constraint: Terminal command endpoint requires a privileged app (verified on HP Googlebook 14, Android 17 / build CL3B)

The Googlebook Terminal app (`com.android.virtualization.terminal`, APK `VmTerminalAppGoogle`) exposes a command endpoint: `android.intent.action.VIEW` with `android-terminal://start?cmd=<command>&background=true` (`EXTRA_GO_BACKGROUND` also accepted), handled by `TerminalIntentActivity`. It installs/initializes the VM when needed, validates the command (ASCII only; blocks `<<`, forkbombs, base64-piped shells, function definitions, `&`/`bg`), and shows a user dialog to allow the calling app to run it. The caller must be an **activity started for result** (`getCallingPackage()`).

**But the component is protected:** `com.android.virtualization.terminal.permission.MANAGE_TERMINAL`, protection level `signature|preinstalled`.

- A sideloaded APK is denied at start: `Permission Denial: starting Intent { … cmp=com.android.virtualization.terminal/.new2.ui.TerminalIntentActivity … } from ProcessRecord{… dev.gbcode.app} … requires com.android.virtualization.terminal.permission.MANAGE_TERMINAL`.
- `pm grant dev.gbcode.app …MANAGE_TERMINAL` fails: "not a changeable permission type".
- `adb shell am start` (shell UID) is denied too.

Consequences for the launch flow:

| Path | Status |
|---|---|
| App executes the backend command in the VM (desired step 2) | **Requires a preinstalled/partner build** holding `MANAGE_TERMINAL`. Code path implemented (`StartupActivity`), cannot work sideloaded. |
| Auto-connect when the backend is already running | **Works today**: startup probes `127.0.0.1:8085` and opens the workbench connected (the Terminal app forwards guest ports to Android loopback). |
| One-time setup without the permission | The user starts the backend once in the Terminal; the VM can keep it running (systemd user service), so every later GBCode launch connects automatically. |

Also noted: `resolveActivity()` for the scheme is masked by Android 11 package visibility even with a `<queries>` entry; implicit launch must be attempted and `ActivityNotFoundException` handled. The app now declares the permission (harmless on sideload, effective if preinstalled) and gates the intent on `checkSelfPermission`.

## Sideloaded launch flow (implemented and verified on the HP Googlebook 14)

User-approved design (2026-10-17): on first setup the user runs a command in the Terminal to install **code-server**; when the server is available the app continues startup by itself.

`StartupActivity` (launcher) does:

1. Probe the forwarded ports off the main thread (main-thread probes throw `NetworkOnMainThreadException`, which silently reads as "closed" — found and fixed): pinned reh-web `8085`, then code-server `8080`.
2. If either is up: open the workbench connected (reh-web = installed frontend + remote agent; code-server = proxy-mode workbench, guest page through the app-origin gateway).
3. If neither is up: one-time setup page with **Copy setup command**, **Open Terminal**, Retry, offline workbench and advanced pairing. The page keeps polling and continues automatically when the server appears.
4. The privileged Terminal command intent (`android-terminal://start?cmd=…&background=true`) is attempted only if the build holds `MANAGE_TERMINAL` (preinstalled); a sideloaded build gets `SecurityException`/Permission Denial by design.

Setup command offered by the page (user runs once in the Terminal):

```
curl -fsSL https://code-server.dev/install.sh | sh -s -- --method=standalone --prefix=$HOME/.local   && nohup $HOME/.local/bin/code-server --bind-addr 0.0.0.0:8080 --auth none >$HOME/code-server.log 2>&1 &
```

Signing experiment (per user request): the app was signed with a new 4096-bit release key and reinstalled. The denial persists — the Terminal endpoint requires `MANAGE_TERMINAL`, and the Terminal app is signed by **Google** (cert SHA-256 `21049340…`), while our release cert is `fa670962…`; `pm grant` is refused ("not a changeable permission type"). Only a preinstalled/partner build can use the command intent.

Verified with a real code-server 4.141.0 on the workstation, exposed into the device loopback with `adb reverse tcp:8080 tcp:8080` (stands in for the VM-forwarded port):

| Step | Result |
|---|---|
| Launch with backend down | setup page shown (Copy/Open Terminal/offline/advanced) |
| Server appears while the page is open | app transitions to WorkbenchActivity within ~6 s and loads the guest page (gateway request trace) |
| Launch with server already up | connects immediately; code-server Welcome renders (activity bar, welcome, status bar) through the app-origin gateway |
| Sideloaded privileged intent | denied (SecurityException/Permission Denial) — expected |

Security note: the interim code-server lane uses `--auth none` on a forwarded loopback port (dev lane). Production keeps the authenticated reh-web/pairing path.

## Cloudy Code shell redesign and first real-VM success (2026-10-17)

Product name: **Cloudy Code** (display name, window caption, in-app titles). The application id remains `dev.gbcode.app`; repository/plan documents still use the internal GBCode name.

Shell redesign per `docs/04-design` (Carbon structure + M3 tonal clarity):

| Item | Change |
|---|---|
| Title bar | Removed the app's own title bar (`Theme.Cloudy`, `NoActionBar`). Only the Android system caption remains — the design forbids recreating system window controls. |
| Tokens | Light/dark `cw_*` colors from `tokens.css` (canvas `#F4F7F7`, teal primary `#006A6A`, terminal `#101C22`, etc.), text styles 24/20/14/12 sp with `sans-serif-medium`/`monospace`, radii: buttons 8, cards/code 12, fields 6, spacing 4-unit rhythm. |
| Startup | Calm status column with product title, metadata, one status line, thin indeterminate progress, one filled teal action ("Copy setup command"), outline ("Open Terminal"), text actions ("Retry", offline, advanced). Code block in terminal surface. |
| Advanced setup | Same system: labels above bordered fields, filled primary pair action, outline reconnect, text link for offline, activity log block. |
| Icon | Adaptive icon: teal cloud + `</>` on the primary-container background, with a monochrome layer; legacy edit icon removed. |
| Width | Content measure capped at 560 dp on wide desktop windows. |

**First real-VM end-to-end success**: on the HP Googlebook 14 the Terminal app forwards the Linux VM's port; with the user's code-server 4.141.0 running in the VM (installed by the setup command), `127.0.0.1:8080` appeared on Android and the app connected automatically with no further steps. The workbench rendered the real VM code-server (Explorer, Welcome, status bar); the window caption shows "Cloudy Code" and the new icon. Evidence: `cloudy-startup.png`, `cloudy-advanced-setup.png`, `cloudy-workbench-real-vm.png`.

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
