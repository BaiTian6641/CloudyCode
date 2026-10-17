// S02 startup orchestration: backend.ensure starts the IDE backend inside the
// Linux environment and is idempotent (running -> no second spawn).
"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("fs");
const net = require("net");
const os = require("os");
const path = require("path");

const { SessionDaemon } = require("../src/server");

function tmpDir() {
  return fs.mkdtempSync(path.join(os.tmpdir(), "gbcode-sessiond-backend-"));
}

function freePort() {
  return new Promise((resolve) => {
    const s = net.createServer();
    s.listen(0, "127.0.0.1", () => {
      const { port } = s.address();
      s.close(() => resolve(port));
    });
  });
}

test("backend.ensure reports not_configured without a launcher", async () => {
  const daemon = new SessionDaemon({ stateDir: tmpDir() });
  assert.deepEqual(await daemon.ensureBackend(), { state: "not_configured" });
});

test("backend.ensure starts a backend once and then reports running", async () => {
  const port = await freePort();
  const stateDir = tmpDir();
  const script = `require("net").createServer(s=>{s.end("ok")}).listen(${port},"127.0.0.1")`;
  const daemon = new SessionDaemon({
    stateDir,
    backend: {
      cmd: [process.execPath, "-e", script],
      port,
      waitMs: 10_000,
    },
  });
  const first = await daemon.ensureBackend();
  assert.equal(first.state, "started");
  assert.equal(first.port, port);
  // The launcher's output goes to the state dir, not the daemon's stdio.
  assert.ok(fs.existsSync(path.join(stateDir, "backend.log")));
  const second = await daemon.ensureBackend();
  assert.equal(second.state, "running");
  // Cleanup: the detached child is tracked by the daemon.
  if (daemon.backendChild) daemon.backendChild.kill("SIGTERM");
});

test("backend.ensure fails fast when the launcher cannot be spawned", async () => {
  const port = await freePort();
  const daemon = new SessionDaemon({
    stateDir: tmpDir(),
    backend: { cmd: ["/nonexistent/gbcode-backend"], port, waitMs: 3_000 },
  });
  const out = await daemon.ensureBackend();
  assert.equal(out.state, "failed");
  assert.match(out.error, /ENOENT|spawn/);
});
