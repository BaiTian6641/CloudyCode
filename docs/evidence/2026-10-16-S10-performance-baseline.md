# S10.03 performance baseline — workstation lane (2026-10-16)

**Label:** `workstation` host baselines (Linux x86_64, this development machine). Not device results; device numbers are the physical lane. Fixture: `tests/performance/backend-startup.mjs` + browser-tool timing.

## Cold start (process spawn → first HTTP response)

| Backend | runs | timings (ms) | median | p95 |
|---|---|---|---|---|
| code-server 4.141.0 | 3 | 301, 263, 264 | **264 ms** | 301 ms |
| pristine control 1.141.0 | 3 | 301, 261, 261 | **261 ms** | 301 ms |

Both backends are at the same order (~0.3s) on this host — no meaningful startup penalty in the packaged server relative to the pristine build.

## Workbench-ready (control, browser navigation → usable)

| Metric | runs (ms) | median |
|---|---|---|
| `.monaco-workbench` present | 1418, 1645, 1510 | **1510 ms** |
| interactive (palette usable) | 1475, 1707, 1549 | **1549 ms** |
| palette open latency | 4, 3, 7 | **4 ms** |

Against the plan §15.3 targets: cached-workbench-ready p95 ≤ 5s — host lane comfortably inside; warm reconnect p95 ≤ 3s — not directly measured here (session restore across reloads was exercised repeatedly in the S01/S02 lanes without failure).

## Not measured here

Editor input-to-visible latency (physical input stack), terminal echo latency under load, USB/serial transport timing, WebView rendering overhead vs Chromium (physical lane), memory/CPU under the Googlebook's VM limits, battery.
