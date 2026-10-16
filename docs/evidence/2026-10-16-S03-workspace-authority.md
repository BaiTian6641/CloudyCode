# S03.03 guest file-authority proof — workstation/control lane (2026-10-16)

**Path under test:** conformance extension 0.3.0 (server-side Node extension host) → `vscode.workspace.fs.writeFile` → workspace folder → host filesystem. This is the plan §4.5 model: the guest file service is the single authority for project file operations; the APK does not mirror keystrokes.

## Result: PASS

| Step | Evidence |
|---|---|
| Trigger `workspace-write` `{path: gbcode-probe.txt, content: GBCODE_WORKSPACE_AUTHORITY_OK_1792152}` | `w3-result.json`: `ok: true`, `uri: file:///tmp/gbcode-ws/gbcode-probe.txt`, `bytes: 37`, `readBackMatches: true` |
| Host-side file content | `/tmp/gbcode-ws/gbcode-probe.txt` contains exactly `GBCODE_WORKSPACE_AUTHORITY_OK_1792152` |
| Read-back through the same API | `workspace-read`/`writeFile` roundtrip consistent |

## Process findings (recorded for production)

1. **Trigger directories must be scoped per backend instance.** Two servers sharing `/tmp/gbcode-slice-triggers` answered each other's triggers; the control's result was overwritten by code-server's stale extension. Production: per-backend trigger/result paths (or the session channel, once S02 hardware ops land) — never a shared global tmp dir.
2. **Extension host reconnection grace can serve stale code.** After `--install-extension` of 0.3.0 + server restart, the reconnected client briefly ran the old extension host; a full page reload loaded the new code. Fixture rule: verify the installed version before attributing results.
3. Workspace Trust interacts: the write ran with the folder untrusted-by-default earlier in the session; the conformance fixture declares `untrustedWorkspaces.supported` (recorded in S01.04 evidence). Production file operations through the guest authority do not depend on trust state, but editor-triggered tasks/debug do.

## Not established here

Editor-buffer → save UX path (typing into the editor DOM and Ctrl+S), watcher latency for external changes (FS-05), the shared-mount guest path (Terminal VM mount, physical lane), large-file/binary performance.
