# U03 debugging lane — real debug session on the pristine control (2026-10-16)

**Fixture:** `/tmp/gbcode-ws/probe.js` (compute loop with a `debugger;` statement) + `.vscode/launch.json` (Node launch, integrated terminal).

## Result: PASS (workstation lane)

- F5 launched the bundled `ms-vscode.js-debug` adapter: the terminal shows the real spawn `... /usr/bin/node --inspect-... ./probe.js` and **"Debugger attached."**
- Execution **paused at the `debugger;` statement** (line 5 highlighted, "compute (function)" scope tooltip, debug toolbar visible, status bar shows the active "Debug probe.js (gbcode-ws)" session).
- Continued through the loop iterations; the debug UI (toolbar, variables/call-stack surfaces, Debug Console tab) is the stock upstream one.
- Screenshot: `docs/evidence/U03-debug-session.webp`.

## Selected backend re-run — code-server 4.141.0 (2026-10-17)

Same fixture (`probe.js` + `.vscode/launch.json`), trusted workspace, launch configuration picked from Run and Debug.

| Check | Result |
|---|---|
| Launch configuration starts (`Debug probe.js`) | PASS — debug toolbar visible, status bar `Debug probe.js (gbcode-ws)` |
| Execution pauses at `debugger;` | PASS — line 5 highlighted yellow in the editor |
| Real adapter + process | PASS — terminal shows `/usr/bin/node …/ms-vscode.js-debug/src/bootloader.js --inspect… ./probe.js` and "Debugger attached."; server-side `ms-vscode.js-debug/src/watchdog.js` (PID 1115954) runs from code-server's bundled extension |
| Stock debug surfaces | PASS — toolbar, breakpoint gutter, Debug Console tab present |

Screenshot: `U03-debug-session-code-server.png`.

**Consequence:** the debugger lane now exists on the selected backend, not only the pristine control. The physical lane still must prove breakpoints/stepping with a guest debug adapter (and the ESP32-S3 OpenOCD/GDB flow in U02.05).

## Notes

- js-debug was bundled into the reh-web control build from the upstream source build's extension fetch (`ms-vscode.js-debug@1.140.0` pulled during the U03.01 build) — the debug stack is upstream, not a Marketplace dependency.
- Canvas-rendered terminal/debug-console text is not DOM-readable; for machine-verifiable output assertions, the physical lane should use a debug-console evaluation API or the debug adapter's output events.
- This is the path the ESP32-S3 OpenOCD/GDB debug configuration (U02.05) will reuse: workbench debug UI → server-side debug adapter → tool process.
