# S01.02 workstation control lane — code-server 4.141.0

**Label:** `workstation` development control (Linux x86_64 host), NOT the Googlebook Debian guest. The guest/APK lane is blocked by the device hands-off constraint.

- Artifact: `code-server-4.141.0-linux-amd64.tar.gz`, SHA-256 `65bcd20b…b97a29` (GitHub API digest = local rehash, recorded in `versions.lock.json`).
- `code-server --version`: `4.141.0 8b41915d25547e2edd553bab03ea4cbd621f98c5 with Code 1.141.0`.
- Served `127.0.0.1:8080` with password auth (`--disable-telemetry`), driven by headless Chromium via the browser tool.
- Login flow: password form → workbench loaded. Page state: `isSecureContext=true`, `serviceWorker` API present, `.monaco-workbench` present, title `Welcome - code-server`.
- Integrated terminal: opened via Ctrl+\`; typed `echo GBCODE_MARK_$(uname -m) > /tmp/gbcode-mark.txt`; file contained `GBCODE_MARK_x86_64` → commands execute in the server host environment. (Terminal renders via canvas: no `.xterm-rows` DOM text — DOM-text scraping is not a viable terminal assertion; use command-side effects.)
- Screenshot: `docs/evidence/S01-code-server-workbench-welcome.webp`.

## Fidelity notes for U03 (observed deviations from stock upstream)

- Welcome tab shows a Coder promo banner ("Deploy code-server for your team / Templates").
- A "Build with Agent" chat sidebar is visible by default.
- Product name/branding is code-server's; workbench otherwise shows stock panels (Explorer/Search/SCM/Debug/Extensions icons, Problems/Output/Debug Console/Terminal/Ports panel tabs).
- These need `--disable-getting-started-override` / app-name / patch-inventory review in U03.01–U03.02.

## Not established here

Extension install from registry, language server, debugger, notebook, reconnect, ARM64 guest, WebView/APK embedding. Conformance extension lane: S01.04.
