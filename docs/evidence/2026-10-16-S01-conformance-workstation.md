# S01.04 conformance extension — workstation lane results (2026-10-16)

**Fixture:** `extensions/conformance` (plain CommonJS, no build step), packaged as VSIX 0.1.0 → 0.1.2. Installed via each server's CLI into isolated user-data dirs. Reports: `docs/evidence/S01-conformance-{code-server,openvscode}{,-workspace}.json`; screenshots: `S01-conformance-webview-code-server.webp`, `S01-{code-server,openvscode}-workbench-welcome.webp`.

**Label:** `workstation` control. Guest/APK lanes pending device release.

## Fixture capabilities proven

| Capability | code-server 4.141.0 | OpenVSCode 1.109.5 |
|---|---|---|
| VSIX install via CLI + activation | PASS | PASS |
| Command palette discovery | PASS (after trust capability added) | PASS |
| child_process (`uname -a`) from extension | PASS — server-side Node | PASS — server-side Node |
| Report file write to host fs | PASS | PASS |
| Webview: strict CSP nonce, local resource, message roundtrip | PASS (screenshot) | PASS |
| Tree view + output channel | PASS | PASS |
| Product identity readback | `code-server 1.141.0` commit `8b41915d…` | `OpenVSCode Server 1.109.5` commit `07258626…` |

## Findings

1. **Extension-host placement:** both servers run the fixture in a host reporting `extensionKind: "ui"` with working `child_process` — i.e., a server-side Node UI extension host (appHost `server-distro` / `openvscode-server`), not a browser worker. Declaring `"extensionKind": ["workspace"]` (v0.1.1) did not change the reported kind on either server. For GBCode this supports "extension code executes in the guest," but U03.03 must not assume the ui/workspace label distinguishes browser vs server execution on these backends; measure actual process location instead.
2. **Workspace Trust is enforced and blocks discovery:** with an untrusted folder, the fixture's command disappeared from the palette ("No matching commands"). Adding `capabilities.untrustedWorkspaces.supported` restored it. code-server reported `isTrusted:false` (Restricted Mode banner visible); OpenVSCode auto-trusted the same folder (`isTrusted:true`). Difference must be documented in U03.
3. **Terminal DOM scraping is not an assertion path** (canvas renderer); verify via command side effects.
4. **code-server product deltas observed:** Coder promo banner on Welcome tab; "Build with Agent" chat sidebar; code-server branding. OpenVSCode shows walkthrough page + same chat sidebar. Both need the U03.02 stock-surface treatment (`--disable-getting-started-override`, app name, patch inventory).
5. **Auth:** code-server password login; OpenVSCode `--connection-token-file` honored (UI token in startup log used to access). Neither left unauthenticated.

## Fixture gaps (next iterations)

- No `browser`-entry variant yet (web-worker lane untested).
- No debug adapter, task, or notebook controller yet (planned for U03/U03.01 fixtures).
- Report does not yet assert guest-vs-host (needs device lane).
