# U03 parity gap closure — breadcrumbs, keyboard shortcuts editor, user profiles (2026-10-17)

An independent parity audit (desktop VS Code (WSL-backed) vs GBCode) found three mandatory workbench surfaces with **no evidence**: breadcrumbs, the keyboard shortcuts editor, and user profiles (`docs/Googlebook_CodeOSS_APK_Engineering_Plan_v2.md` §6.7 mandatory surfaces). All three are now verified on the selected backend (code-server 4.141.0, workstation lane; they are stock workbench surfaces, so the pristine control shares them).

| Surface | Verification | Evidence |
|---|---|---|
| Breadcrumbs | Editor shows the stock breadcrumb bar (`probe.py › greet`) above the editor group | `U03-parity-multi-surface.png` |
| Keyboard shortcuts editor | `Ctrl+K Ctrl+S` opens the stock editor: "Type to search in keybindings" search box, Command / Keybinding / When / Source columns, keybinding rows, record-key and clear-filter controls | `U03-parity-keyboard-shortcuts.png` |
| User profiles | `Profiles: New Profile…` opens the stock Profiles editor: profile list (Default ✓ Active), New Profile menu, Name / Icon / Copy from, and per-source Contents toggles for Settings, Keyboard Shortcuts, Tasks, MCP Servers, Snippets, Extensions; Cancel / Preview / Create | `U03-parity-profiles-editor.png` (created nothing — cancelled) |

The profile command surface is complete and indexable in the keybindings editor (`Profiles: New Profile…`, `Profiles: New Window with Temporary Profile`, `Profiles: Export Profile…`, `Profiles: Save Current Profile As…`, `Create New Terminal (With Profile)`): `U03-parity-profile-commands.png`.

## Re-confirmed in the same session (`U03-parity-multi-surface.png`)

- Notebooks: rendered markdown H1, code cell, `[1]` execution output, `Python 3 (GBCode)` kernel.
- Run and Debug: session paused at `debugger;`, debug toolbar active.
- Language tooling: pyrefly semantic hover (`def greet(name: str) -> str`).
- Terminals: five terminal tabs with a live guest prompt.
- Panels: Problems (2) / Output / Debug Console / Terminal / Ports (1).
- Explorer with SCM status badges; status bar (master*, 0/0 △ 14, Ln/Col, Spaces, UTF-8, Python, Pyrefly).

## What this does not close

The audit's remaining gaps stand and are tracked in the stage plans: rows proven only on the control or by screenshots (weak), the state-model divergence (user settings/profiles persist browser-locally without remote storage — `workbench-delta-inventory.md` rows 10–11, `docs/audit/E13-secret-storage.md`), and the WSL-analogy caveat that **no workload has yet run inside the actual Googlebook Debian Terminal VM** (device released? no — `docs/evidence/device-profile-quartz.json` keeps those fields `unknown`).
