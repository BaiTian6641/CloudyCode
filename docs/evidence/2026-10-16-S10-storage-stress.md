# S10.04 storage stress — workspace.fs write path (2026-10-16)

**Fixture:** 40 triggers, each writing a distinct ~1KB file into the workspace via `vscode.workspace.fs.writeFile` on the pristine control (server-side authority). Host verification of every file.

## Result

| Metric | Value |
|---|---|
| Trigger results OK | 40/40 (`ok: true`, `readBackMatches: true`) |
| Host files present with exact content | 40/40 |
| Failures | 0 |

Timing note: this fixture measures the write path's integrity, not throughput latency — the trigger scanner polls at 2s and both backends shared the trigger dir during the run (dual execution, identical content; timing stamps collide at ms precision and are not reported as performance data). Latency benchmarks belong to the physical lane with a single backend and a dedicated results channel.

## Notes for production

- The write path is correct under burst load on this host.
- The dual-execution collision during this run (both backends watching one trigger dir) is itself evidence for the S03.03 finding: production trigger/result channels must be scoped per backend instance.
- Larger trees (FS-03/FS-05 Git/watcher semantics) and fault injection (disk full, mount revocation) are the physical lane.
