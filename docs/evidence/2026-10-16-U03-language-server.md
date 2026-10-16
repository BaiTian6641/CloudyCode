# U03.06-class lane — language-server extension in the backend (2026-10-16)

**Extension:** `meta.pyrefly` 1.4.0 (`pyrefly-1.4.0-linux-x64.vsix`, SHA-256 `97dad84a…9275`) from Open VSX — a user-selected extension beyond the curated set, with a native binary.

## Evidence

| Check | Result |
|---|---|
| VSIX installs into the pristine control | PASS |
| Extension activates on a Python file | PASS — status bar shows "Pyrefly (Basic)" |
| Language server runs in the backend | PASS — `/home/polar/.vscode-server-oss/extensions/meta.pyrefly-1.4.0/bin/pyrefly lsp` (PID 3949926), a real process in the server environment |
| Semantic response (hover) | PASS — hovering `greet` returns "greet (function)" from the LSP |

This is the WSL-style execution claim proven for real tooling: a user-chosen extension's native language server executes server-side and serves the browser workbench. `docs/evidence/U03-pyrefly-hover.webp`.

## Architecture notes for GBCode

- The pyrefly VSIX is **target-platform specific** (`@linux-x64` here). On the ARM64 Googlebook guest, GBCode must select the `linux-arm64` artifact — the extension classification record (plan §9.1) must capture target platforms per extension, and install must resolve the guest's actual architecture. This is exactly the kind of per-architecture correctness the plan's S08.03 audit covers.
- Pyrefly is a single self-contained Rust binary — ideal for the guest. Extensions that download binaries at activation (platform-specific) need the guest architecture honored at download time.
