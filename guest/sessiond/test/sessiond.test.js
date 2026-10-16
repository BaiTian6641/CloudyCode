// S02.01/S02.02 exercised tests: pairing, sessions, channel framing.
"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const crypto = require("crypto");
const fs = require("fs");
const net = require("net");
const os = require("os");
const path = require("path");

const { SessionDaemon } = require("../src/server");
const { encodeFrame, OPCODES } = require("../src/ws");
const { b64u } = require("../src/keys");

function tmpDir() {
  return fs.mkdtempSync(path.join(os.tmpdir(), "gbcode-sessiond-test-"));
}

async function request(port, method, urlPath, body, headers = {}) {
  const resp = await fetch(`http://127.0.0.1:${port}${urlPath}`, {
    method,
    headers: { "content-type": "application/json", "connection": "close", ...headers },
    body: body ? JSON.stringify(body) : undefined,
  });
  return { status: resp.status, body: await resp.json().catch(() => null) };
}

function connectWs(port, session) {
  return new Promise((resolve, reject) => {
    const key = crypto.randomBytes(16).toString("base64");
    const socket = net.connect(port, "127.0.0.1", () => {
      socket.write(`GET /channel?session=${encodeURIComponent(session)} HTTP/1.1\r\n`
        + `Host: 127.0.0.1:${port}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n`
        + `Sec-WebSocket-Key: ${key}\r\nSec-WebSocket-Version: 13\r\n\r\n`);
    });
    let handshake = Buffer.alloc(0);
    const onData = (chunk) => {
      handshake = Buffer.concat([handshake, chunk]);
      const end = handshake.indexOf("\r\n\r\n");
      if (end === -1) return;
      socket.off("data", onData);
      const head = handshake.subarray(0, end).toString("latin1");
      if (!head.includes("101")) return reject(new Error("handshake failed: " + head.split("\r\n")[0]));
      resolve({ socket, leftover: handshake.subarray(end + 4) });
    };
    socket.on("data", onData);
    socket.on("error", reject);
  });
}

/** Read one server frame (server frames are unmasked). */
function readFrame(socket, leftover) {
  return new Promise((resolve, reject) => {
    let buf = leftover || Buffer.alloc(0);
    const onData = (chunk) => {
      buf = Buffer.concat([buf, chunk]);
      if (buf.length < 2) return;
      let len = buf[1] & 0x7f;
      let idx = 2;
      if (len === 126) {
        if (buf.length < 4) return;
        len = buf.readUInt16BE(2);
        idx = 4;
      }
      if (buf.length < idx + len) return;
      socket.off("data", onData);
      resolve({ opcode: buf[0] & 0x0f, payload: buf.subarray(idx, idx + len), rest: buf.subarray(idx + len) });
    };
    socket.on("data", onData);
    socket.on("error", reject);
    // process already-buffered bytes
    if (buf.length >= 2) onData(Buffer.alloc(0));
  });
}

function sendControl(socket, obj) {
  // client frames must be masked
  const payload = Buffer.from(JSON.stringify(obj));
  const mask = crypto.randomBytes(4);
  const masked = Buffer.from(payload);
  for (let i = 0; i < masked.length; i++) masked[i] ^= mask[i % 4];
  const header = [];
  header.push(0x80 | OPCODES.TEXT);
  if (payload.length < 126) {
    header.push(0x80 | payload.length);
  } else {
    header.push(0x80 | 126, (payload.length >> 8) & 0xff, payload.length & 0xff);
  }
  socket.write(Buffer.concat([Buffer.from(header), mask, masked]));
}

async function setup() {
  const dir = tmpDir();
  const daemon = new SessionDaemon({ stateDir: dir });
  const server = daemon.createServer();
  await new Promise((r) => server.listen(0, "127.0.0.1", r));
  const port = server.address().port;
  return { daemon, server, port, dir };
}

test("healthz is public and minimal", async () => {
  const { server, port } = await setup();
  const { status, body } = await request(port, "GET", "/healthz");
  assert.equal(status, 200);
  assert.equal(body.status, "ok");
  assert.equal(typeof body.fingerprint, "string");
  await new Promise((r) => server.close(r));
});

test("pairing rejects wrong secret and consumes on success", async () => {
  const { daemon, server, port } = await setup();
  const secret = daemon.openEnrollment();

  const appKeys = crypto.generateKeyPairSync("ed25519");
  const appRaw = appKeys.publicKey.export({ format: "der", type: "spki" }).subarray(-32);

  const bad = await request(port, "POST", "/pair", {
    pairingSecret: "wrong", appPublicKey: b64u(appRaw), nonce: "nonce-abcdef-1",
  });
  assert.equal(bad.status, 403);
  assert.equal(bad.body.error.code, "secret_invalid");

  const good = await request(port, "POST", "/pair", {
    pairingSecret: secret, appPublicKey: b64u(appRaw), appLabel: "test", nonce: "nonce-abcdef-2",
  });
  assert.equal(good.status, 200);
  assert.equal(good.body.serverFingerprint, daemon.fingerprintHex);

  // single use: second attempt with same secret must fail
  const again = await request(port, "POST", "/pair", {
    pairingSecret: secret, appPublicKey: b64u(appRaw), nonce: "nonce-abcdef-3",
  });
  assert.equal(again.status, 403);
  assert.equal(again.body.error.code, "pairing_closed");

  await new Promise((r) => server.close(r));
});

test("session requires enrolled app and valid signature", async () => {
  const { daemon, server, port } = await setup();
  const secret = daemon.openEnrollment();
  const appKeys = crypto.generateKeyPairSync("ed25519");
  const appRaw = appKeys.publicKey.export({ format: "der", type: "spki" }).subarray(-32);
  const pair = await request(port, "POST", "/pair", {
    pairingSecret: secret, appPublicKey: b64u(appRaw), nonce: "nonce-abcdef-a",
  });
  assert.equal(pair.status, 200);
  const appHash = pair.body.enrolledApp;

  const ch = await request(port, "GET", `/session/challenge?app=${appHash}`);
  assert.equal(ch.status, 200);

  // bad signature
  const badSig = await request(port, "POST", "/session",
    { challenge: ch.body.challenge, appSig: b64u(crypto.randomBytes(64)) },
    { "x-gbcode-app": appHash });
  assert.equal(badSig.status, 403);

  // good signature needs a fresh challenge (used one is consumed)
  const ch2 = await request(port, "GET", `/session/challenge?app=${appHash}`);
  const sig = crypto.sign(null, Buffer.concat([Buffer.from("session"), Buffer.from(ch2.body.challenge, "utf8")]), appKeys.privateKey);
  const sess = await request(port, "POST", "/session",
    { challenge: ch2.body.challenge, appSig: b64u(sig) },
    { "x-gbcode-app": appHash });
  assert.equal(sess.status, 200);
  assert.ok(sess.body.session.startsWith("v1."));
  assert.ok(sess.body.expiresAt > Date.now());

  await new Promise((r) => server.close(r));
});

test("channel: hello, capabilities, unsupported op, bad frame", async () => {
  const { daemon, server, port } = await setup();
  const secret = daemon.openEnrollment();
  const appKeys = crypto.generateKeyPairSync("ed25519");
  const appRaw = appKeys.publicKey.export({ format: "der", type: "spki" }).subarray(-32);
  const pair = await request(port, "POST", "/pair", {
    pairingSecret: secret, appPublicKey: b64u(appRaw), nonce: "nonce-abcdef-c",
  });
  const appHash = pair.body.enrolledApp;
  const ch = await request(port, "GET", `/session/challenge?app=${appHash}`);
  const sig = crypto.sign(null, Buffer.concat([Buffer.from("session"), Buffer.from(ch.body.challenge, "utf8")]), appKeys.privateKey);
  const sess = await request(port, "POST", "/session",
    { challenge: ch.body.challenge, appSig: b64u(sig) }, { "x-gbcode-app": appHash });

  // unauthenticated channel attempt must fail
  await assert.rejects(connectWs(port, "bogus-token"));

  const { socket, leftover } = await connectWs(port, sess.body.session);
  let rest = leftover;

  sendControl(socket, { v: 1, id: 1, op: "session.hello", payload: { echo: "hi" } });
  let frame = await readFrame(socket, rest);
  rest = frame.rest;
  const hello = JSON.parse(frame.payload.toString("utf8"));
  assert.equal(hello.id, 1);
  assert.equal(hello.ok, true);
  assert.equal(hello.payload.echo, "hi");

  sendControl(socket, { v: 1, id: 2, op: "session.capabilities" });
  frame = await readFrame(socket, rest);
  rest = frame.rest;
  const caps = JSON.parse(frame.payload.toString("utf8"));
  assert.equal(caps.ok, true);
  assert.ok(caps.payload.ops.includes("device.list"));

  sendControl(socket, { v: 1, id: 3, op: "no.such.op" });
  frame = await readFrame(socket, rest);
  rest = frame.rest;
  const un = JSON.parse(frame.payload.toString("utf8"));
  assert.equal(un.ok, false);
  assert.equal(un.error.code, "unsupported_op");

  sendControl(socket, "not-json");
  // sending a string (invalid JSON frame content) — build raw masked frame
  const rawPayload = Buffer.from("not-json");
  const mask = crypto.randomBytes(4);
  const masked = Buffer.from(rawPayload);
  for (let i = 0; i < masked.length; i++) masked[i] ^= mask[i % 4];
  socket.write(Buffer.concat([Buffer.from([0x80 | OPCODES.TEXT, 0x80 | rawPayload.length]), mask, masked]));
  frame = await readFrame(socket, rest);
  rest = frame.rest;
  const bad = JSON.parse(frame.payload.toString("utf8"));
  assert.equal(bad.ok, false);
  assert.equal(bad.error.code, "bad_frame");

  socket.destroy();
  await daemon.shutdown(server);
});

test("identity persists across restarts", async () => {
  const dir = tmpDir();
  const d1 = new SessionDaemon({ stateDir: dir });
  const d2 = new SessionDaemon({ stateDir: dir });
  assert.equal(d1.fingerprintHex, d2.fingerprintHex);
});
