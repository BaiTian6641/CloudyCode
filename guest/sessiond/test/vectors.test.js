// S02.06: run contracts/test-vectors/session-v1.json against a live sessiond channel.
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

const VECTORS = JSON.parse(fs.readFileSync(
  path.join(__dirname, "../../../contracts/test-vectors/session-v1.json"), "utf8"));

function tmpDir() {
  return fs.mkdtempSync(path.join(os.tmpdir(), "gbcode-vectors-"));
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
      if (!head.includes("101")) return reject(new Error("handshake failed"));
      resolve({ socket, leftover: handshake.subarray(end + 4) });
    };
    socket.on("data", onData);
    socket.on("error", reject);
  });
}

function sendControl(socket, obj) {
  const payload = Buffer.from(JSON.stringify(obj));
  const mask = crypto.randomBytes(4);
  const masked = Buffer.from(payload);
  for (let i = 0; i < masked.length; i++) masked[i] ^= mask[i % 4];
  const header = [0x81];
  if (payload.length < 126) {
    header.push(0x80 | payload.length);
  } else {
    header.push(0x80 | 126, (payload.length >> 8) & 0xff, payload.length & 0xff);
  }
  socket.write(Buffer.concat([Buffer.from(header), mask, masked]));
}

function readFrame(socket, buffered) {
  const state = buffered || { buf: Buffer.alloc(0) };
  return new Promise((resolve, reject) => {
    const onData = (chunk) => {
      state.buf = Buffer.concat([state.buf, chunk]);
      if (state.buf.length < 2) return;
      let len = state.buf[1] & 0x7f;
      let idx = 2;
      if (len === 126) {
        if (state.buf.length < 4) return;
        len = state.buf.readUInt16BE(2);
        idx = 4;
      }
      if (state.buf.length < idx + len) return;
      socket.off("data", onData);
      const payload = state.buf.subarray(idx, idx + len);
      state.buf = state.buf.subarray(idx + len);
      resolve({ payload, state });
    };
    socket.on("data", onData);
    socket.on("error", reject);
    if (state.buf.length >= 2) onData(Buffer.alloc(0));
  });
}

function checkExpect(vector, response) {
  const exp = vector.expect;
  assert.equal(response.id, exp.id, `${vector.id}: id`);
  assert.equal(response.ok, exp.ok, `${vector.id}: ok`);
  if (exp.error) {
    assert.equal(response.error.code, exp.error.code, `${vector.id}: error.code`);
    assert.equal(response.error.retriable, exp.error.retriable, `${vector.id}: error.retriable`);
  }
  if (exp.payload) {
    for (const [key, value] of Object.entries(exp.payload)) {
      assert.deepEqual(response.payload[key], value, `${vector.id}: payload.${key}`);
    }
  }
  if (exp.payloadContains) {
    for (const [key, marker] of Object.entries(exp.payloadContains)) {
      const actual = response.payload[key];
      if (marker === "hex64") {
        assert.match(actual, /^[0-9a-f]{64}$/, `${vector.id}: payload.${key} hex64`);
      } else if (marker === "arrayEmpty") {
        assert.deepEqual(actual, [], `${vector.id}: payload.${key} empty array`);
      } else {
        assert.equal(actual, marker, `${vector.id}: payload.${key}`);
      }
    }
  }
  if (exp.payloadHas) {
    for (const key of exp.payloadHas) {
      assert.ok(key in response.payload, `${vector.id}: payload has ${key}`);
    }
  }
}

test("golden vectors pass against live sessiond", async () => {
  const dir = tmpDir();
  const daemon = new SessionDaemon({ stateDir: dir });
  const server = daemon.createServer();
  await new Promise((r) => server.listen(0, "127.0.0.1", r));
  const port = server.address().port;

  const secret = daemon.openEnrollment();
  const appKeys = crypto.generateKeyPairSync("ed25519");
  const appRaw = appKeys.publicKey.export({ format: "der", type: "spki" }).subarray(-32);
  const pair = await request(port, "POST", "/pair", {
    pairingSecret: secret, appPublicKey: b64u(appRaw), nonce: "nonce-vectors-1",
  });
  assert.equal(pair.status, 200);
  const appHash = pair.body.enrolledApp;
  const ch = await request(port, "GET", `/session/challenge?app=${appHash}`);
  const sig = crypto.sign(null, Buffer.concat([Buffer.from("session"), Buffer.from(ch.body.challenge, "utf8")]), appKeys.privateKey);
  const sess = await request(port, "POST", "/session",
    { challenge: ch.body.challenge, appSig: b64u(sig) }, { "x-gbcode-app": appHash });
  assert.equal(sess.status, 200);

  const { socket, leftover } = await connectWs(port, sess.body.session);
  let state = { buf: leftover };

  for (const vector of VECTORS.vectors) {
    sendControl(socket, vector.send);
    const frame = await readFrame(socket, state);
    state = frame.state;
    const response = JSON.parse(frame.payload.toString("utf8"));
    checkExpect(vector, response);
  }

  socket.destroy();
  await daemon.shutdown(server);
});
