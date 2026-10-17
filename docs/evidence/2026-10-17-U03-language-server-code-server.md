# U03 language-server lane on the selected backend — code-server (2026-10-17)

Prior LSP evidence (pyrefly) was recorded on the pristine control only. ADR-0004/S01.06 needs it on the **selected** backend, so this lane re-ran it on code-server 4.141.0.

## Setup

- code-server 4.141.0 (`8b41915d…`), default user data dir, authenticated, workspace `/tmp/gbcode-ws` (trusted).
- `meta.pyrefly` 1.4.0 (`pyrefly-1.4.0-linux-x64.vsix` from Open VSX) installed via the code-server CLI.
- Fixture `/tmp/gbcode-ws/probe.py`:
  ```python
  def greet(name: str) -> str:
      return f"hello {name}"


  result: int = greet("gbcode")
  ```

## Results

| Check | Result |
|---|---|
| Extension activates on a Python file | PASS — status bar shows `Pyrefly (Basic)` |
| Server-side process | PASS — `~/.local/share/code-server/extensions/meta.pyrefly-1.4.0/bin/pyrefly lsp` (PID 1112759), a guest/server process, not inferred from UI |
| Semantic hover at the call site (line 5, col 15, `Ctrl+K Ctrl+I`) | PASS — `(function) greet: def greet(name: str) -> str: ...` with "Go to str" link |

Screenshot: `U03-pyrefly-hover-code-server.png`.

## Consequence

Success criterion 4's "working baseline on the selected backend with real … language-server evidence" now has all four measured surfaces on code-server: editing/save, terminal, webview + conformance extension, notebook (renderer + kernel execution), and language server. ADR-0004's evidence table records the row; physical-lane re-verification (guest ARM64 pyrefly artifact) stays open under S08.03/U03.

## Note

The extension must resolve the guest architecture: the VSIX used here is `linux-x64`; the Googlebook guest is `arm64`, so install must select the `linux-arm64` artifact (already recorded in `2026-10-16-U03-language-server.md`).
