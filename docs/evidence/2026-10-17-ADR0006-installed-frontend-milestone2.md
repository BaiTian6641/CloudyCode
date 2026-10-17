# ADR-0006 milestone 2 — locally packaged Code-OSS workbench: offline proof (2026-10-17)

The revised production requirement: core workbench assets and bootstrap belong to the installed Android client; the main workbench must not silently fetch missing core HTML/JS/CSS/workers from the VM or the Internet. This milestone packages the **actual upstream Code-OSS workbench** (pinned commit `2a59476c`, 1.141.0) into the APK and proves it starts with **no backend, no network, and a fresh app profile**.

## What was built

| Piece | Where | Notes |
|---|---|---|
| Web library dist build | `tools/release/build-upstream-web.sh` | `gulp vscode-web-min` (workbench library, workers, resources) with Node 24.18.0 toolchain |
| Page shell build | same script | `build/next/index.ts bundle --out out-vscode-shell-min --target server-web --minify --mangle-privates --nls` — upstream's own `workbench.js`/`workbench.css` bootstrap (`vs/code/browser/workbench/workbench.ts`) |
| Asset packaging + manifest | `tools/release/package-web-assets.sh` | shell build as base + `node_modules` lazy core deps + built-in web extensions; manifest generated from the actual build (path, bytes, sha256 per file) into `android/app/asset-manifest/vscode-web-manifest.json` and `assets/web/asset-manifest.json` |
| Gateway asset serving | `Gateway.kt` | class A served from APK (never proxied), guest core routes **denied + traced** (`deny-core:` log), packaged class C served local-first, upstream optional (local mode) |
| Template rendering | `WebAssets.kt` (`WorkbenchPage`) | client-side counterpart of the pinned commit's `webClientServer.renderWorkbench` (same `{{PLACEHOLDER}}` + HTML-attribute encoding); `enableWorkspaceTrust: true` preserved |
| Offline entry | `OnboardingActivity` "Open workbench (offline, no guest)" | installed workbench starts with no pairing |

## Two real defects found and fixed during integration

1. **Scrambled localized strings** (raw `{0}` templates in the palette; Settings descriptions mismatched their titles): the first packaging mixed the `web-min` build's nls table (24 934 messages) with the `server-web` shell's `workbench.js` (22 956 messages). Message indices are per-build, so the tables must come from the same bundle as the code that looks them up. Fixed by basing packaging on the shell build (its `nls.messages.js` travels with `workbench.js`). After the fix: palette rows are real command names with keybindings; Settings descriptions match.
2. **Missing page entry artifacts**: `vscode-web` (target `web`) intentionally ships only the workbench *library* (`workbench.web.main.internal`) — the upstream page shell (`workbench.js`/`workbench.css`, the files `workbench.html` loads) belongs to target `server-web`. Fixed by building the shell from the same pinned source (`build/next/index.ts bundle --target server-web`).

## The proof (emulator, AVD `Medium_Phone_API_36.1`, WebView 143)

Environment: `pm clear dev.gbcode.app` (fresh profile, no web cache), backend **stopped** (code-server killed; host ports 8080 down), emulator network **disabled** (`svc wifi disable; svc data disable` → `ping 8.8.8.8` → *Network is unreachable*).

| Required surface | Result | Evidence |
|---|---|---|
| Stock workbench UI boots | PASS — activity bar, Welcome page, status bar, codicons, default themes | `ADR0006-offline-palette.png` (Welcome behind palette) |
| Command Palette | PASS — `Accounts: Manage Accounts`, `Add Data Breakpoint at Address`, `Change Keyboard Layout`, `Chat: Open Chat` with real keybindings | `ADR0006-offline-palette.png` |
| Settings UI | PASS — "Commonly Used" with working controls (Font Size input, Format On Save checkbox, Auto Save dropdown); descriptions match titles | `ADR0006-offline-settings.png` |
| Untitled editor | PASS — `Untitled-3` created (Ctrl+Alt+N), `installed frontend edit` typed, dirty indicator on | `ADR0006-offline-untitled.png` |
| No guest-served core assets | PASS — gateway trace + CDP trace show only app-served class A; guest core routes are denied by policy (`deny-core`, 403) | request trace below; `WebAssetGatewayTest.guestCoreAssetRoutesAreDeniedWithTrace` |
| No Internet | PASS — CDP request trace: **21 requests, hosts `127.0.0.1:41873` only (+ blob/data), 0 failed** | request trace below |

### Request trace (CDP-recorded after reload)

- Core: `/out/vs/code/browser/workbench/workbench.js` 200, `workbench.css` 200, `/out/nls.messages.js` 200, `/out/media/codicon.ttf` 200.
- Built-in extensions (packaged, class C): `/extensions/theme-defaults/themes/2026-light.json` 200, `theme-seti/icons/vs-seti-icon-theme.json` 200, `light_modern.json` 200, `light_plus.json` 200.
- Failed requests: **0**. External hosts: **0**. Guest requests: **0** (backend is stopped).

### Platform delta found

`Ctrl+N` is captured by the browser/WebView (opens nothing in the page); the stock `File: New Untitled Text File` command works via the palette and its `Ctrl+Alt+N` keybinding. Recorded in the workbench delta inventory as a WebView input constraint (S04 input matrix must cover browser-reserved chords).

## Tests

`tools/verify.sh` → ALL GREEN. Instrumented (emulator only, `adb -s emulator-5554`): **47/47** including new `WebAssetGatewayTest` (6): template renders without placeholders, core assets served locally, missing core asset 404 **without upstream contact**, guest core routes denied with trace (3/3 + `denyCoreCount`), manifest matches shipped assets (2428 files, sha256 per file, pinned commit recorded), local mode works with no upstream.

## Versions / compatibility set

APK asset manifest ↔ Code-OSS `2a59476c` (1.141.0): the shell and library dists are built from the same checkout; `dist-info.json` records `upstreamCommit`/`upstreamVersion`. Guest `reh-web` packages for the same commit remain the service host candidate (milestone 3).

## Remaining limitations

1. Guest IDE services are **not yet connected** to the installed client (milestone 3: WS channel via the gateway, connectionToken, deny/trace of guest core routes on the live path).
2. `/callback` route serves the raw callback template (OAuth flows out of scope).
3. CSP is nonce+`'self'`-scoped (upstream-shaped); iterative hardening remains.
4. Built-in extensions ship from the APK (179 MB assets; ~40 MB APK). S11 may split/dedupe; user-installed extensions remain class C from registry/guest.
5. The dirty-buffer/user-data story (settings/profiles/buffers persistence) builds on ADR-0005/E13 and needs the S09 backup-vs-saved definition.
6. Physical Googlebook lanes remain blocked (device owned by another project).

## Rollback

- Revert the milestone commits; `Gateway(assets = null)` keeps the previous proxy-only baseline behavior exactly (guest-hosted workbench rollback mode).
- `tools/release/package-web-assets.sh` output is generated (`android/app/app/src/main/assets/web/` is gitignored); nothing user-owned is deleted by re-running it.
