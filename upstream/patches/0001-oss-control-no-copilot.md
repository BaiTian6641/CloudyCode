# Overlay 0001: OSS control build without proprietary Copilot

- **Status:** applied to the cached upstream tree (`~/.cache/gbcode/upstream/vscode-1.141.0`), 2026-10-16. **Not vendored into this repository**; the upstream checkout is disposable cache per ADR-0002/upstream review §3.2.
- **Upstream base:** microsoft/vscode `2a59476c9bfcb90b3ddc372c36762471b7dfad1c` (1.141.0).
- **Owner:** RELEASE. **Removal condition:** upstream provides a supported way to build `vscode-reh-web-*` from public source without the Copilot SDK packaging step, or GBCode ships a Copilot-compatible bundle (no current plan).

## Problem

`gulp vscode-reh-web-linux-x64` fails at `prepareCopilotRipgrepShim`: the task chain expects the Copilot SDK inside the output's `extensions/copilot/node_modules`, but from public source the SDK files are not materialized there (CI builds obtain Copilot as a prebuilt VSIX from Microsoft's private marketplace; public `npm install` of `extensions/copilot` is not sufficient). Code - OSS's public build therefore cannot complete this target unmodified. Copilot is a proprietary service extension and is **not** part of GBCode (ADR-0002: permitted registries only; no proprietary-service bundling).

## Patch (in cached tree)

1. `build/gulpfile.reh.ts` — extension filter gains:
   `.filter(name => process.env.GBCODE_NO_COPILOT !== '1' || name !== 'copilot')`
2. `build/gulpfile.reh.ts` — `prepareCopilotRipgrepShimTaskREH` returns early (with a log line) when `extensions/copilot` is absent from the output dir.

Build with `GBCODE_NO_COPILOT=1`. Behavior without the env var is upstream-default.

## Consequences

- The control server contains no Copilot extension and no Copilot ripgrep/tgrep shims. All OSS surfaces (workbench, terminal, debugging, notebooks, extensions host) are unaffected.
- code-server 4.141.0 already ships its own product configuration; this overlay affects only the pristine control build used for comparison (U03.01).
- Reapply after any upstream re-clone (documented in `tools/release/build-upstream-reh-web.sh`).
