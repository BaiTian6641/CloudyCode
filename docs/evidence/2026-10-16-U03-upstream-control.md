# U03.01 pristine upstream control — Code - OSS 1.141.0 (2026-10-16)

**Build:** `tools/release/build-upstream-reh-web.sh` → `vscode-reh-web-linux-x64` from microsoft/vscode `2a59476c9bfcb90b3ddc372c36762471b7dfad1c` (tag 1.141.0, shallow clone at that commit), Node 24.18.0 toolchain, `GBCODE_NO_COPILOT=1` with [overlay 0001](../../upstream/patches/0001-oss-control-no-copilot.md). Build-host deps resolved without root: local libxkbfile sysroot (`~/.cache/gbcode/sysroot`); Copilot SDK packaging step skipped by overlay (public source cannot reproduce the private VSIX path).

**Run:** `./node out/server-main.js --host 127.0.0.1 --port 8082 --connection-token-file …` — Extension host agent + Web UI confirmed. Product readback: `Code - OSS 1.141.0`, commit `2a59476c…`.

## Control comparison results

| Check | Pristine control 1.141.0 | code-server 4.141.0 | OpenVSCode 1.109.5 |
|---|---|---|---|
| Workbench in Chromium (token auth) | PASS | PASS (password) | PASS (token) |
| Conformance extension: command, child_process, webview CSP, tree, output | PASS | PASS | PASS |
| Reported `extensionKind` for Node host | "ui" | "ui" | "ui" |
| **Extension-created terminal spawn** (`createTerminal`/`terminal.new`, processId, sendText execution) | **PASS** — pid resolves, `GBCODE_CTRL_OK` written, transcript captured | **STALL** — pid never resolves, ptyhost log empty | **STALL** — same |
| Workspace Trust on `/tmp/gbcode-ws` | Restricted (`isTrusted:false`) | Restricted | Auto-trusted |
| Bundled Node | v24.21.0 | v24.21.0 | v22.21.1 |

## Findings

1. **Extension-host terminal stall is a candidate regression, not upstream.** Decisive via the control: pristine 1.141.0 spawns correctly. Recorded in ADR-0004 as a required code-server patch/investigation item and upgrade re-test. GBCode companion-extension design must account for it (browser-path creation, daemon-managed terminals, or patching code-server).
2. `extensionKind: "ui"` for server-side Node execution is upstream behavior in the server-distro topology (present on all three); U03.03 must measure actual process placement, not the label.
3. Restricted-Mode behavior of the control matches code-server; OpenVSCode's auto-trust is the outlier to document in U03.
4. Control build excludes Copilot by overlay; all OSS surfaces present (built-in extensions list includes ipynb, git, debug, Emmet, etc.).

## Not established here

Desktop Electron control (needs display/xvfb — deferred), ARM64 control build, WebView/APK lane against the control (next: S02.03 gateway + slice APK rerun), performance baselines.
