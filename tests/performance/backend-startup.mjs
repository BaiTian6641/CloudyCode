// S10.03: backend cold-start and workbench-ready timing on this workstation.
// Usage: node tests/performance/backend-startup.mjs <code-server|control> <runs>
// Labels: workstation lane. Numbers are host baselines, not device results.
import { spawn } from "node:child_process";
import { setTimeout as sleep } from "node:timers/promises";

import { readFileSync } from "node:fs";

const which = process.argv[2] || "code-server";
const runs = Number(process.argv[3] || "3");

const configs = {
  "code-server": {
    cwd: `${process.env.HOME}/.cache/gbcode/upstream/code-server-4.141.0-linux-amd64`,
    cmd: ["./bin/code-server", "--bind-addr", "127.0.0.1:8090", "--auth", "password", "--disable-telemetry"],
    env: { ...process.env, PASSWORD: readFileSync(`${process.env.HOME}/.cache/gbcode/run/probe-password`, "utf8").trim() },
    port: 8090,
  },
  control: {
    cwd: `${process.env.HOME}/.cache/gbcode/upstream/vscode-reh-web-linux-x64`,
    cmd: ["./node", "out/server-main.js", "--host", "127.0.0.1", "--port", "8091", "--connection-token-file", `${process.env.HOME}/.cache/gbcode/run/probe-password`, "--telemetry-level", "off"],
    port: 8091,
  },
};

const cfg = configs[which];
if (!cfg) { console.error("unknown backend"); process.exit(1); }

const timings = [];
for (let i = 0; i < runs; i++) {
  const t0 = Date.now();
  const proc = spawn(cfg.cmd[0], cfg.cmd.slice(1), { cwd: cfg.cwd, env: cfg.env, stdio: "ignore" });
  let ready = -1;
  const deadline = t0 + 60_000;
  while (Date.now() < deadline) {
    try {
      const resp = await fetch(`http://127.0.0.1:${cfg.port}/healthz`, { signal: AbortSignal.timeout(2000) }).catch(() => null);
      // code-server has no /healthz; accept any HTTP response as "up"
      if (resp || true) {}
      if (resp) { ready = Date.now() - t0; break; }
      // try the root page
      const root = await fetch(`http://127.0.0.1:${cfg.port}/`, { signal: AbortSignal.timeout(2000) }).catch(() => null);
      if (root) { ready = Date.now() - t0; break; }
    } catch { /* retry */ }
    await sleep(250);
  }
  if (ready === -1) {
    const root = await fetch(`http://127.0.0.1:${cfg.port}/`, { signal: AbortSignal.timeout(3000) }).catch(() => null);
    if (root) ready = Date.now() - t0;
  }
  timings.push(ready);
  proc.kill("SIGTERM");
  await sleep(1500);
}

const sorted = [...timings].sort((a, b) => a - b);
const median = sorted[Math.floor(sorted.length / 2)];
const p95 = sorted[Math.min(sorted.length - 1, Math.floor(sorted.length * 0.95))];
console.log(JSON.stringify({
  backend: which, runs, timingsMs: timings, medianMs: median, p95Ms: p95,
  host: "workstation", recordedAt: new Date().toISOString(),
}, null, 1));
