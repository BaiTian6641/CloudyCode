// S02.01: enrollment receipt semantics for gbcode-sessiond.
// The receipt binds "enroll" || appHash || nonce under the server identity key
// (contracts/session-v1.md §2); the Android client verifies it before trusting
// any enrollment field. These tests pin the server side of that contract.
"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const crypto = require("crypto");
const fs = require("fs");
const os = require("os");
const path = require("path");

const { SessionDaemon } = require("../src/server");
const { b64u, sha256Hex, publicKeyFromRaw } = require("../src/keys");

function tmpDir() {
  return fs.mkdtempSync(path.join(os.tmpdir(), "gbcode-receipt-"));
}

async function setup() {
  const dir = tmpDir();
  const daemon = new SessionDaemon({ stateDir: dir });
  const server = daemon.createServer();
  await new Promise((r) => server.listen(0, "127.0.0.1", r));
  return { daemon, server, port: server.address().port };
}

async function pair(port, secret, nonce) {
  const app = crypto.generateKeyPairSync("ed25519");
  const appRaw = app.publicKey.export({ format: "der", type: "spki" }).subarray(-32);
  const resp = await fetch(`http://127.0.0.1:${port}/pair`, {
    method: "POST",
    headers: { "content-type": "application/json", "connection": "close" },
    body: JSON.stringify({
      v: 1,
      pairingSecret: secret,
      appPublicKey: b64u(appRaw),
      appLabel: "receipt-test",
      nonce,
    }),
  });
  return { status: resp.status, body: await resp.json(), appRaw, app };
}

test("receipt signature verifies over enroll|appHash|nonce", async () => {
  const { daemon, server, port } = await setup();
  const nonce = "receipt-nonce-1";
  const secret = daemon.openEnrollment();
  const { status, body, appRaw } = await pair(port, secret, nonce);
  assert.equal(status, 200);

  // Fields bind the enrollment to our app key and this server identity.
  assert.equal(body.enrolledApp, sha256Hex(appRaw));
  const serverRaw = Buffer.from(body.serverPublicKey, "base64url");
  assert.equal(body.serverFingerprint, sha256Hex(serverRaw));

  const message = Buffer.concat([
    Buffer.from("enroll", "utf8"),
    Buffer.from(body.enrolledApp, "hex"),
    Buffer.from(nonce, "utf8"),
  ]);
  const signature = Buffer.from(body.receiptSig, "base64url");
  assert.ok(crypto.verify(null, message, publicKeyFromRaw(body.serverPublicKey), signature),
    "receipt signature must verify with the server public key");

  await server.closeAllConnections?.();
  await new Promise((r) => server.close(r));
});

test("receipt does not verify under a different nonce or app hash", async () => {
  const { daemon, server, port } = await setup();
  const nonce = "receipt-nonce-2";
  const secret = daemon.openEnrollment();
  const { status, body } = await pair(port, secret, nonce);
  assert.equal(status, 200);
  const sig = Buffer.from(body.receiptSig, "base64url");
  const serverKey = publicKeyFromRaw(body.serverPublicKey);

  for (const message of [
    Buffer.concat([Buffer.from("enroll"), Buffer.from(body.enrolledApp, "hex"), Buffer.from("other-nonce")]),
    Buffer.concat([Buffer.from("enroll"), Buffer.alloc(32), Buffer.from(nonce)]),
    Buffer.concat([Buffer.from("enroll"), Buffer.from(body.enrolledApp, "hex")]),
  ]) {
    assert.equal(crypto.verify(null, message, serverKey, sig), false, message.toString("hex"));
  }

  await server.closeAllConnections?.();
  await new Promise((r) => server.close(r));
});

test("full hash is required for challenge; truncated identity is unknown_app", async () => {
  const { daemon, server, port } = await setup();
  const secret = daemon.openEnrollment();
  const { status, body, appRaw } = await pair(port, secret, "receipt-nonce-3");
  assert.equal(status, 200);

  const ok = await fetch(`http://127.0.0.1:${port}/session/challenge?app=${body.enrolledApp}`);
  assert.equal(ok.status, 200);

  // The 16-char display truncation (and a display ellipsis) is not an identity.
  for (const bad of [body.enrolledApp.substring(0, 16) + "…", body.enrolledApp.substring(0, 16)]) {
    const resp = await fetch(`http://127.0.0.1:${port}/session/challenge?app=${encodeURIComponent(bad)}`);
    assert.equal(resp.status, 403, bad);
    assert.equal((await resp.json()).error.code, "unknown_app");
  }
  assert.ok(appRaw.length === 32);

  await server.closeAllConnections?.();
  await new Promise((r) => server.close(r));
});
