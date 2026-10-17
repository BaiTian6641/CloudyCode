# S01.05 vertical-slice APK — emulator lane (2026-10-16)

**Label:** `emulator` (Medium_Phone_API_36.1, SDK 36) + `workstation` backend (code-server 4.141.0 on loopback via `adb reverse tcp:8080`). Physical Googlebook lane blocked by device hands-off.

**APK:** `android/probes/workbench-slice` (AGP 9.4.1, Java, zero third-party deps). Backend: code-server with password auth; extension: gbcode-conformance 0.2.0.

## Acceptance interactions — all PASS (code-server lane)

| Interaction | Result | Evidence |
|---|---|---|
| Authenticated workbench in Android WebView | PASS — password autofill → `/?folder=/tmp/gbcode-ws`, `.monaco-workbench` present | logcat `GBCodeSlice`, state evals |
| Secure context / service workers | PASS — `isSecureContext=true`, `serviceWorker` API present on `127.0.0.1` origin | state eval JSON |
| Open terminal in workbench | PASS — Application Menu → Terminal → New Terminal (drill-in on action-item clicks); real shell prompt visible | screencap `slice-term.png` |
| Run guest terminal command | PASS — command fed through xterm helper textarea (value + `InputEvent` + Enter) executed on server: `/tmp/gbcode-slice-user.txt` = `GBCODE_SLICE_WEBVIEW_OK` | mark file, logcat `fed+enter` |
| Extension webview in WebView | PASS — server-side trigger ran `GBCode: Run Conformance Report`; WebView shows "GBCode Conformance" tab, `webviews:2` | `S01-slice-webview-conformance.png`, state eval, `S01-slice-trigger-report.json` |
| Server-side automation bridge | PASS — trigger files in `/tmp/gbcode-slice-triggers/` execute workbench actions via polling scanner (fs.watch proved unreliable) | t6/t12 results |
| Reconnect after backend restart | PASS (implicit) — workbench reloaded and functional across repeated code-server restarts this session | session logs |

## Findings that shape production (S02/S04)

1. ~~Extension-host terminal spawn stalls in web servers.~~ **Retracted 2026-10-17:** `createTerminal`/`terminal.new` from the extension host is gated by Workspace Trust — the client holds the terminal behind the trust prompt, so `processId` stays pending and the pty host is never asked. With the folder trusted, the same paths spawn correctly on code-server and OpenVSCode (`docs/evidence/2026-10-17-U03-terminal-trust-correction.md`). No backend patch is required; automation must not spawn terminals while the folder is untrusted, and first-run product flows must surface/handle trust explicitly.
2. **Trusted-keyboard driving is unreliable for workbench automation** (F1 consumed by IME/system; Gboard fragments terminal input). Working automation paths: DOM action-item drill-in clicks, quick-input typing via trusted `input text` only when a real input is focused, xterm textarea value+`InputEvent` feed.
3. **Synthetic `ClipboardEvent` paste is ignored by xterm**; synthetic F1/Enter `KeyboardEvent` does not trigger monaco keybindings; `mouseenter` must be dispatched on the `.action-item`, non-bubbling.
4. **Phone-width layout differs from desktop** (compact Application Menu, no menubar). Googlebook-class width needs re-verification of layout-sensitive flows; emulator AVD is a behavior fixture, not a layout fixture.
5. **code-server restores terminals across browser reloads** (xterm count grows across reloads); session state persists in user-data dir — relevant for S09 lifecycle.

## Not established here

Physical Terminal-VM route, guest placement of the backend, keyboard/IME desktop behavior, file editing save-cycle inside WebView (S03.03 covers the authority path; the editor UX path is the physical lane).


## OpenVSCode APK lane (follow-up, same fixture)

The slice APK loaded OpenVSCode Server 1.109.5 in the WebView over `adb reverse` with the token-authenticated URL: `isSecureContext=true`, service worker present, workbench present, restored terminal and the conformance extension active (title "GBCode Conformance - gbcode-ws - OpenVSCode Server"). Both backend candidates have APK-lane evidence; code-server's lane additionally includes terminal command execution and the gateway path.