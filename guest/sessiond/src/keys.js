// Identity and key handling for gbcode-sessiond.
// Server identity: persistent ed25519 keypair, private key mode 0600.
"use strict";

const crypto = require("crypto");
const fs = require("fs");
const path = require("path");

const b64u = (buf) => Buffer.from(buf).toString("base64url");
const sha256Hex = (buf) => crypto.createHash("sha256").update(buf).digest("hex");

/** Load or create the persistent server identity in stateDir. */
function loadServerIdentity(stateDir) {
  fs.mkdirSync(stateDir, { recursive: true, mode: 0o700 });
  const privPath = path.join(stateDir, "server-ed25519.json");
  if (fs.existsSync(privPath)) {
    const stored = JSON.parse(fs.readFileSync(privPath, "utf8"));
    const publicKey = crypto.createPublicKey({ key: stored.publicJwk, format: "jwk" });
    const privateKey = crypto.createPrivateKey({ key: stored.privateJwk, format: "jwk" });
    return { publicKey, privateKey, publicRaw: Buffer.from(stored.publicJwk.x, "base64url") };
  }
  const { publicKey, privateKey } = crypto.generateKeyPairSync("ed25519");
  const publicJwk = publicKey.export({ format: "jwk" });
  const privateJwk = privateKey.export({ format: "jwk" });
  fs.writeFileSync(privPath, JSON.stringify({ publicJwk, privateJwk }), { mode: 0o600 });
  const publicRaw = publicKey.export({ format: "der", type: "spki" }).subarray(-32);
  return { publicKey, privateKey, publicRaw };
}

function fingerprint(publicRaw) {
  return sha256Hex(publicRaw);
}

function sign(privateKey, ...parts) {
  return crypto.sign(null, Buffer.concat(parts.map((p) => Buffer.from(p))), privateKey);
}

function verify(publicKey, signature, ...parts) {
  return crypto.verify(null, Buffer.concat(parts.map((p) => Buffer.from(p))), publicKey, signature);
}

function publicKeyFromRaw(rawB64u) {
  // Wrap raw 32-byte ed25519 key into a minimal SPKI DER.
  const prefix = Buffer.from("302a300506032b6570032100", "hex");
  return crypto.createPublicKey({ key: Buffer.concat([prefix, Buffer.from(rawB64u, "base64url")]), format: "der", type: "spki" });
}

function constantTimeEqual(a, b) {
  const ba = Buffer.from(String(a));
  const bb = Buffer.from(String(b));
  if (ba.length !== bb.length) return false;
  return crypto.timingSafeEqual(ba, bb);
}

module.exports = { loadServerIdentity, fingerprint, sign, verify, publicKeyFromRaw, constantTimeEqual, b64u, sha256Hex };
