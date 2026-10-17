# ADR-0004: Backend selection for the GBCode workbench (provisional, development)

- **Status:** Provisional — selected for development; final approval (S01.06 exit gate) requires the physical Googlebook lane after device release.
- **Date:** 2026-10-16
- **Depends on:** S01.01 (locks), S01.02/.03 (workstation lane), S01.04 (conformance), S01.05 (emulator vertical slice); U03.01 upstream control build in progress.

## Context

Two maintained candidates were pinned in `versions.lock.json` with verified digests:

| Candidate | Server release | Embedded Code - OSS | Freshness gap |
|---|---|---|---|
| code-server | 4.141.0 (2026-10-08, commit `8b41915d…`) | 1.141.0 (`2a59476c…`) | current |
| OpenVSCode Server | 1.109.5 (2026-02-20, commit `4ffe2270…`) | 1.109.5 (`07258626…`) | ~8 months older |

Workstation and emulator evidence (docs/evidence/2026-10-16-S01-*.md):

- Both: authenticated login, real workbench in browser and **in Android WebView** (code-server lane), integrated terminal executing commands, VSIX installation, conformance extension with webview/CSP/tree/output, Workspace Trust enforcement.
- Both candidates initially showed extension-created terminal stalls (`processId` never resolves; ptyhost log empty). **Corrected 2026-10-17:** a controlled A/B on one code-server instance shows the stall is Workspace Trust gating terminal creation in Restricted Mode — not a backend regression (`docs/evidence/2026-10-17-U03-terminal-trust-correction.md`). With the folder trusted, code-server, OpenVSCode and pristine all spawn extension terminals correctly.
- OpenVSCode differences: ~8-month-older runtime (1.109.5, Node 22), minimal-patch objective (smaller audit surface), auto-trusted a folder code-server restricted, Docker default is unauthenticated (we require explicit token), token honored via `--connection-token-file`.
- code-server differences: current upstream (1.141.0, Node 24), `--enable-remote-storage` option (audited, E13), self-hosted webviews (audited, E12), Coder promo banner + agent sidebar (removable), Restricted Mode honored.

## Evidence accumulated after the provisional selection (2026-10-16)

| Lane | code-server 4.141.0 | OpenVSCode 1.109.5 | Pristine control 1.141.0 |
|---|---|---|---|
| Authenticated workbench (Chromium) | PASS | PASS | PASS |
| Workbench in Android WebView (emulator) | PASS — full slice (login, terminal command, conformance webview) | PASS — workbench + extension active | (not run in APK; same upstream server) |
| Terminal execution (browser path) | PASS | PASS | PASS |
| Extension-host terminal spawn | PASS — pid resolves, `GBCODE_CTRL_OK` written, transcript captured | PASS — pid + marker after folder trust (untrusted blocks creation by design) | PASS — pid + marker (transcript unavailable: proposed API) |
| Workspace Trust | Restricted honored | auto-trusted folder | Restricted honored |
| Notebook renderer, offline (vscode-cdn.net unresolvable) | **PASS** — SW-served, markdown + code cell render | not tested | **FAIL** — renderer module fetch from CDN authority |
| Language server (pyrefly semantic hover) | **PASS** — `def greet(name: str) -> str` at the call site, server-side `pyrefly lsp` | not run | PASS (control lane) |
| Conformance extension (webview/CSP/tree/output/workspace.fs) | PASS | PASS | PASS |
| Cold start median (this host) | 264 ms | not re-measured | 261 ms |
| Structural parity vs control | Ports panel extra | older upstream baseline | reference |
| Guest ARM64 package | official artifact (verified digest) | official artifact (verified digest) | built from source (overlay) |

The provisional choice stands: current upstream, active maintenance, verified digests. No backend patch is required for extension terminals; trust gating must be handled by the product flow (user grants trust once; automation must not spawn terminals while untrusted). Re-test at each candidate upgrade.

## Decision

**Develop against code-server 4.141.0** (pinned commit/artifacts in `versions.lock.json`), with OpenVSCode Server retained as the documented fallback. Selection rationale:

1. Security freshness and maintenance cadence outweigh the smaller patch surface of a release 8 months behind upstream (upstream review §3.1 rule: smaller patch set never overrides missing fixes).
2. Workstation + emulator behavior is equal on the measured surfaces; workspace-trust gating of terminals and the extension-host placement label behave the same on all three, so neither differentiates the candidates.
3. code-server's additional patches are known and bounded (webview origin, secret storage, product overrides); they become explicit S02.03/U03 review items rather than unknowns.
4. Offline notebook viability is already solved on code-server: with `vscode-cdn.net` unresolvable on the host, notebook renderer resources were served by its webview service worker and the notebook rendered correctly, while the pristine control failed on the same fixture (`docs/evidence/2026-10-16-U03-notebook-lane.md`). A self-contained Googlebook needs exactly this; a stock build would require writing the same patch ourselves.

**Fallback trigger:** switch to OpenVSCode only via a new ADR if code-server shows a blocker it cannot resolve with a maintainable patch (e.g., webview origin model incompatible with the native-bridge isolation requirement, or unmaintainable upgrade cost measured in U03).

## Explicitly not decided here
- **Retracted:** the earlier "extension-host terminal stall regression" claim. Re-tested 2026-10-17 with an isolated A/B on one code-server instance: terminal creation is gated by Workspace Trust; after "Trust Folder & Continue" the same extension spawns a terminal (pid + sendText), and OpenVSCode passes on a trusted workspace too (`docs/evidence/2026-10-17-U03-terminal-trust-correction.md`). No backend patch is needed. Consequence: the product must handle first-run trust explicitly, and automation must not spawn terminals while the folder is untrusted.
- No production approval: the physical Terminal-VM lane (guest ARM64 package, forwarding behavior, Terminal lifecycle) is untested and remains a release gate (S01.06 exit, S09).
- No Marketplace/proprietary enablement: registry stays Open VSX/VSIX per ADR-0002.

## Consequences

- S02 origin/gateway work targets code-server 4.141.0 behavior first; patch inventory in `upstream/patches/` tracks any project-owned changes with removal conditions.
- Required audits before production: webview-origin isolation (E12 — mechanism audit in `docs/audit/E12-webview-resource-origin.md`; live allowlist probe and fail-closed network policy still open), remote-secret-storage plaintext fallback (E13 — empirically audited in `docs/audit/E13-secret-storage.md`; `--enable-remote-storage` stays OFF in the default profile until a secret-storage design exists), product.json/build overrides (E11), `--disable-getting-started-override` and branding deltas for U03.02.
- Upgrade/rollback: candidate updates only via `versions.lock.json` change + full conformance re-run + emulator slice re-run; the previous pin stays usable until the new one passes.

## Reopen triggers

Physical-lane results contradicting this choice; code-server maintenance/policy change; upstream 1.14x incompatibility discovered in U03.01 control comparison; a Google platform constraint that favors the alternate patch model.
