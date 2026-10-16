// S02.05: endpoint and port authorization checks for gbcode-sessiond.
"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const crypto = require("crypto");
const fs = require("fs");
const net = require("net");
const os = require("os");
const path = require("path");

const { SessionDaemon } = require("../src/server");
const { b64u } = require("../src/keys");

function tmpDir() {
  return fs.mkdtempSync(path.join(os.tmpdir(), "gbcode-authz-"));
}

async function setup() {
  const dir = tmpDir();
  const daemon = new SessionDaemon({ stateDir: dir });
  const server = daemon.createServer();
  await new Promise((r) => server.listen(0, "127.0.0.1", r));
  return { daemon, server, port: server.address().port, dir };
}

async function request(port, method, urlPath, body, headers = {}) {
  const resp = await fetch(`http://127.0.0.1:${port}${urlPath}`, {
    method,
    headers: { "content-type": "application/json", "connection": "close", ...headers },
    body: body ? JSON.stringify(body) : undefined,
  });
  return { status: resp.status, body: await resp.json().catch(() => null) };
}

test("unknown paths return 404 without leaking anything", async () => {
  const { server, port } = await setup();
  for (const p of ["/", "/admin", "/pair/extra", "/session", "/debug/vars"]) {
    const { status, body } = await request(port, "GET", p);
    assert.equal(status, 404, p);
    assert.equal(body.error.code, "not_found", p);
  }
  await server.closeAllConnections?.();
  await new Promise((r) => server.close(r));
});

test("pairing is closed without an enrollment window", async () => {
  const { server, port } = await setup();
  const { status, body } = await request(port, "POST", "/pair", {
    pairingSecret: "x", appPublicKey: b64u(crypto.randomBytes(32)), nonce: "nonce-authz-1",
  });
  assert.equal(status, 403);
  assert.equal(body.error.code, "pairing_closed");
  await server.closeAllConnections?.();
  await new Promise((r) => server.close(r));
});

test("pairing rate limit trips after 5 attempts per minute", async () => {
  const { daemon, server, port } = await setup();
  daemon.openEnrollment();
  let last = null;
  for (let i = 0; i < 6; i++) {
    last = await request(port, "POST", "/pair", {
      pairingSecret: "definitely-wrong", appPublicKey: b64u(crypto.randomBytes(32)),
      nonce: "nonce-rate-" + i,
    });
  }
  assert.equal(last.status, 429);
  assert.equal(last.body.error.code, "rate_limited");
  assert.equal(last.body.error.retriable, true);
  await server.closeAllConnections?.();
  await new Promise((r) => server.close(r));
});

test("session endpoints reject unknown apps", async () => {
  const { server, port } = await setup();
  const ch = await request(port, "GET", "/session/challenge?app=deadbeef");
  assert.equal(ch.status, 403);
  assert.equal(ch.body.error.code, "unknown_app");
  const sess = await request(port, "POST", "/session",
    { challenge: "x", appSig: "y" }, { "x-gbcode-app": "deadbeef" });
  assert.equal(sess.status, 403);
  await server.closeAllConnections?.();
  await new Promise((r) => server.close(r));
});

test("loopback bind is not reachable via non-loopback local addresses", async () => {
  const { daemon, server, port } = await setup();
  // find a non-loopback IPv4 of this host
  const ifaces = os.networkInterfaces();
  const lan = Object.values(ifaces).flat()
    .find((i) => i && i.family === "IPv4" && !i.internal);
  if (!lan) {
    // single-homed CI: only verify loopback works and document the skip
    const { status } = await request(port, "GET", "/healthz");
    assert.equal(status, 200);
    await new Promise((r) => server.close(r));
    return;
  }
  await assert.rejects(
    fetch(`http://${lan.address}:${port}/healthz`, { signal: AbortSignal.timeout(3000) }),
    /fetch failed|ECONNREFUSED|TimeoutError/,
    "listener must not be reachable from non-loopback address " + lan.address);
  const { status } = await request(port, "GET", "/healthz");
  assert.equal(status, 200);
  await new Promise((r) => server.close(r));
});

test("channel rejects unauthenticated upgrade", async () => {
  const { daemon, server, port } = await setup();
  await new Promise((resolve, reject) => {
    const socket = net.connect(port, "127.0.0.1", () => {
      socket.write("GET /channel?session=bogus HTTP/1.1\r\nHost: x\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: eA==\r\nSec-WebSocket-Version: 13\r\n\r\n");
    });
    let buf = Buffer.alloc(0);
    socket.on("data", (c) => {
      buf = Buffer.concat([buf, c]);
      const i = buf.indexOf("\r\n\r\n");
      if (i === -1) return;
      const head = buf.subarray(0, i).toString("latin1");
      assert.ok(head.includes("403"), head.split("\r\n")[0]);
      socket.destroy();
      resolve();
    });
    socket.on("error", reject);
  });
  await daemon.shutdown(server);
});
