# Correction — "extension-host terminal stall" was Workspace Trust (2026-10-17)

Earlier lanes recorded extension-created terminals as stalling on code-server 4.141.0 and OpenVSCode 1.109.5 (`processId` never resolves, ptyhost logs no `createProcess`) while pristine 1.141.0 worked — inferred as a candidate regression. This document replaces that inference with a controlled A/B.

## Method

Conformance extension `gbcode-conformance` 0.3.3 `terminal-debug` trigger (`vscode.window.createTerminal` → `processId` → `sendText` of a marker file), isolated per-instance trigger/result directories (`GBCODE_TRIGGER_DIR`/`GBCODE_RESULT_DIR`), fresh `--user-data-dir` where noted.

| # | Instance | Workspace trust | Result |
|---|---|---|---|
| 1 | code-server 4.141.0 (fresh data dir, `--log trace`) | untrusted — trust dialog "Creating a terminal process requires executing code" appeared | `pid: pending`, marker not written, `ptyhost.log` shows no `createProcess` (only `getDefaultSystemShell`, `setTerminalLayoutInfo`) |
| 2 | **same instance**, after clicking "Trust Folder & Continue" | trusted | **PASS** — pid `1104883`, marker written, transcript captured |
| 3 | OpenVSCode 1.109.5 (restarted, trusted `/tmp/gbcode-ws`) | trusted | **PASS** — pid `1108037`, marker written (transcript empty; see harness note) |
| 4 | pristine 1.141.0 (current state) | trusted | terminals present and working (previously recorded PASS) |

Same code-server binary, same extension, same workspace: only the trust decision changed between #1 and #2. That isolates the cause.

## Root cause

**Restricted Mode (Workspace Trust) gates terminal creation.** With an untrusted folder the client holds the request behind the trust prompt; the terminal object exists (so `processId` is pending and `sendText` queues) but no pty is ever requested, which is exactly the "stall, ptyhost log empty" signature. Granting trust releases it. This is core VS Code behavior, not a backend patch bug.

The earlier pristine `PASS` and code-server/OpenVSCode `STALL` rows were therefore comparing different trust states at trigger time, not different backend behavior. The pristine run's stored report also recorded `isTrusted:false`, but its working terminals and the confirmation above show the *terminal trigger* ran under trust (state changed between report time and trigger time in that long-lived instance).

**Harness note (OpenVSCode 1.109.5):** `onDidWriteTerminalData` is a proposed API there; an unguarded call threw and aborted the trigger before the spawn result could be recorded. 0.3.3 guards the subscription (try/catch, disposer fallback), so the terminal path is measured even where transcript capture is unavailable.

## Consequences

1. No code-server/OpenVSCode patch is required for terminal spawning. ADR-0004 and the U03/S01 evidence rows are corrected.
2. **First-run product behavior:** after install, `/workspace` (or the chosen folder) is untrusted, so terminal creation prompts. GBCode must decide trust explicitly — recommended: the user grants trust once per workspace (never disable `security.workspace.trust` globally); the bootstrap/companion flow must not attempt unattended terminal spawning before trust exists, and should surface the reason instead of hanging.
3. Automation (conformance, CI, physical lanes) must establish trust before exercising terminal paths, or it will reproduce the "stall" artifact.

## Files

- `U03-terminal-trust-prompt.png` — the trust prompt shown on terminal creation in Restricted Mode.
