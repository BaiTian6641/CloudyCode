# Editor save-cycle proof — control lane (2026-10-16)

The last unproven core IDE interaction. On the pristine control (Chromium):

1. Quick Open `gbcode-probe.txt` → opens in the Monaco editor (existing content visible).
2. Ctrl+End, typed `\nEDITED_BY_GBCODE_PROBE_1792153`, Ctrl+S.
3. Host file `/tmp/gbcode-ws/gbcode-probe.txt` afterward contains the marker — the backend persisted the edit.

This closes the loop with S03.03 (workspace.fs authority): both the API path and the real editor→save path persist to the guest filesystem. `docs/evidence/` has the trigger/host evidence for the API path; this file records the editor path.
