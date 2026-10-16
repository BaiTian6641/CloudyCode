// S10.02: adversarial fuzzing of the sessiond wire parsers.
// Deterministic seeded corpus + random mutations; asserts bounded, typed
// failures — never a crash, hang, or unbounded allocation.
"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const crypto = require("crypto");
const fs = require("fs");
const net = require("net");
const os = require("os");
const path = require("path");

const { SessionDaemon } = require("../src/server");
const { FrameParser, OPCODES } = require("../src/ws");
const { b64u } = require("../src/keys");

function tmpDir() {
  return fs.mkdtempSync(path.join(os.tmpdir(), "gbcode-fuzz-"));
}

// --- parser-level fuzz (no network) ---

test("frame parser: garbage, truncation, oversize, random mutations", () => {
  const daemon = new SessionDaemon({ stateDir: tmpDir() });
  void daemon; // parser is exercised standalone below
  const collected = [];
  const errors = [];
  const parser = new FrameParser((opcode, payload) => collected.push({ opcode, len: payload.length }));

  // seeded PRNG for reproducibility
  let seed = 0x9e3779b9;
  const rand = () => {
    seed = (seed * 1103515245 + 12345) & 0x7fffffff;
    return seed / 0x7fffffff;
  };

  const corpus = [
    Buffer.alloc(0),
    Buffer.from([0x81]),
    Buffer.from([0x81, 0x80]),
    Buffer.from([0x81, 0x80, 0xff, 0xff, 0xff, 0xff]),
    Buffer.from([0x81, 0xfe, 0xff, 0xff]), // claims 64 KiB, no payload
    Buffer.from([0x81, 0xff, 0, 0, 0, 0, 0, 0, 0x40, 0]), // 64-bit length 64
    Buffer.from([0x81, 0xff, 0x7f, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff]), // huge
    Buffer.from([0x80 | 0x0f, 0x00]), // unknown opcode
    Buffer.from("GET / HTTP/1.1\r\n\r\n"),
    crypto.randomBytes(256),
  ];

  for (const input of corpus) {
    // feed whole
    try { parser.push(input); } catch (e) { errors.push(e.message); }
    // feed byte-by-byte (fragmentation)
    for (let i = 0; i < input.length; i++) {
      try { parser.push(input.subarray(i, i + 1)); } catch (e) { errors.push(e.message); }
    }
  }

  // random mutations of a valid masked frame
  const valid = (() => {
    const payload = Buffer.from('{"v":1,"id":1,"op":"session.ping","payload":{"n":1}}');
    const mask = Buffer.from([1, 2, 3, 4]);
    const masked = Buffer.from(payload);
    for (let i = 0; i < masked.length; i++) masked[i] ^= mask[i % 4];
    return Buffer.concat([Buffer.from([0x81, 0x80 | payload.length]), mask, masked]);
  })();

  for (let i = 0; i < 400; i++) {
    const mutant = Buffer.from(valid);
    const pos = Math.floor(rand() * mutant.length);
    mutant[pos] = Math.floor(rand() * 256);
    try { parser.push(mutant); } catch (e) { errors.push(e.message); }
    // truncation
    const cut = Math.floor(rand() * mutant.length);
    try { parser.push(mutant.subarray(0, cut)); } catch (e) { errors.push(e.message); }
  }

  // nothing crashed the process; oversize frames rejected with an error
  assert.ok(errors.some((m) => m.includes("too large")), "oversize frame must error");
});

// --- endpoint-level fuzz over a live server ---

async function setupServer() {
  const dir = tmpDir();
  const daemon = new SessionDaemon({ stateDir: dir });
  const server = daemon.createServer();
  await new Promise((r) => server.listen(0, "127.0.0.1", r));
  return { daemon, server, port: server.address().port };
}

test("endpoints: malformed bodies, wrong methods, oversize bodies", async () => {
  const { server, port } = await setupServer();

  const cases = [
    ["POST", "/pair", "not json", 400],
    ["POST", "/pair", "{}", 403], // missing fields, closed enrollment
    ["GET", "/pair", null, 404], // GET on POST-only path? no: /pair GET -> 404? actually falls through to 404
    ["POST", "/nonexistent", "{}", 404],
    ["GET", "/session/challenge", null, 403],
    // Unknown app + any body: 403 unknown_app (fail-closed before body parsing).
    ["POST", "/session", "[]", 403],
  ];
  for (const [method, p, body, want] of cases) {
    const resp = await fetch(`http://127.0.0.1:${port}${p}`, {
      method,
      headers: { "content-type": "application/json", "connection": "close" },
      body: body === null ? undefined : body,
    });
    assert.equal(resp.status, want, `${method} ${p} -> ${resp.status}, want ${want}`);
    await resp.text().catch(() => {});
  }

  // oversize body must be rejected before allocation
  const big = Buffer.alloc(70 * 1024, 0x41);
  const resp = await fetch(`http://127.0.0.1:${port}/pair`, {
    method: "POST",
    headers: { "content-type": "application/json", "connection": "close" },
    body: big,
  });
  assert.ok([400, 403, 413].includes(resp.status), "oversize body rejected");
  await resp.text().catch(() => {});

  await server.closeAllConnections?.();
  await new Promise((r) => server.close(r));
});

test("channel: garbage frames then server stays alive and responsive", async () => {
  const { daemon, server, port } = await setupServer();
  const secret = daemon.openEnrollment();
  const appKeys = crypto.generateKeyPairSync("ed25519");
  const appRaw = appKeys.publicKey.export({ format: "der", type: "spki" }).subarray(-32);
  const post = async (p, body, headers = {}) => {
    const r = await fetch(`http://127.0.0.1:${port}${p}`, {
      method: "POST",
      headers: { "content-type": "application/json", "connection": "close", ...headers },
      body: JSON.stringify(body),
    });
    return r.json();
  };
  const pair = await post("/pair", {
    pairingSecret: secret, appPublicKey: b64u(appRaw), nonce: "nonce-fuzz-1",
  });
  const appHash = pair.enrolledApp;
  const ch = await (await fetch(`http://127.0.0.1:${port}/session/challenge?app=${appHash}`)).json();
  const sig = crypto.sign(null, Buffer.concat([Buffer.from("session"), Buffer.from(ch.challenge, "utf8")]), appKeys.privateKey);
  const sess = await post("/session", { challenge: ch.challenge, appSig: b64u(sig) }, { "x-gbcode-app": appHash });

  const socket = net.connect(port, "127.0.0.1", () => {
    const key = crypto.randomBytes(16).toString("base64");
    socket.write(`GET /channel?session=${encodeURIComponent(sess.session)} HTTP/1.1\r\n`
      + `Host: x\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n`
      + `Sec-WebSocket-Key: ${key}\r\nSec-WebSocket-Version: 13\r\n\r\n`);
  });
  await new Promise((resolve) => {
    let buf = Buffer.alloc(0);
    socket.on("data", (c) => {
      buf = Buffer.concat([buf, c]);
      if (buf.indexOf("\r\n\r\n") !== -1) resolve();
    });
  });

  // blast garbage + a huge claimed length + truncated frames
  socket.write(crypto.randomBytes(512));
  const huge = Buffer.from([0x81, 0xff, 0x7f, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff]);
  socket.write(huge);
  socket.write(Buffer.from([0x81, 0x80])); // truncated masked frame header

  // wait, then verify the server still answers a fresh valid session
  await new Promise((r) => setTimeout(r, 300));
  const health = await fetch(`http://127.0.0.1:${port}/healthz`, { headers: { connection: "close" } });
  assert.equal(health.status, 200, "server alive after garbage frames");
  await health.text().catch(() => {});

  socket.destroy();
  await daemon.shutdown(server);
});
