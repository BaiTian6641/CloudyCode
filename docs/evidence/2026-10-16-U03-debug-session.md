# U03 debugging lane — real debug session on the pristine control (2026-10-16)

**Fixture:** `/tmp/gbcode-ws/probe.js` (compute loop with a `debugger;` statement) + `.vscode/launch.json` (Node launch, integrated terminal).

## Result: PASS (workstation lane)

- F5 launched the bundled `ms-vscode.js-debug` adapter: the terminal shows the real spawn `... /usr/bin/node --inspect-... ./probe.js` and **"Debugger attached."**
- Execution **paused at the `debugger;` statement** (line 5 highlighted, "compute (function)" scope tooltip, debug toolbar visible, status bar shows the active "Debug probe.js (gbcode-ws)" session).
- Continued through the loop iterations; the debug UI (toolbar, variables/call-stack surfaces, Debug Console tab) is the stock upstream one.
- Screenshot: `docs/evidence/U03-debug-session.webp`.

## Notes

- js-debug was bundled into the reh-web control build from the upstream source build's extension fetch (`ms-vscode.js-debug@1.140.0` pulled during the U03.01 build) — the debug stack is upstream, not a Marketplace dependency.
- Canvas-rendered terminal/debug-console text is not DOM-readable; for machine-verifiable output assertions, the physical lane should use a debug-console evaluation API or the debug adapter's output events.
- This is the path the ESP32-S3 OpenOCD/GDB debug configuration (U02.05) will reuse: workbench debug UI → server-side debug adapter → tool process.
