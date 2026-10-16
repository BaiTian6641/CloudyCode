// gbcode-sessiond server (S02.02): pairing, sessions, framed channel.
// Implements contracts/session-v1.md draft. Node built-ins only.
"use strict";

const crypto = require("crypto");
const fs = require("fs");
const http = require("http");
const path = require("path");

const { loadServerIdentity, fingerprint, sign, verify, publicKeyFromRaw, constantTimeEqual, b64u, sha256Hex } = require("./keys");
const { TokenIssuer } = require("./tokens");
const { acceptKey, encodeFrame, FrameParser, OPCODES } = require("./ws");

const MAX_BODY = 64 * 1024;
const PAIR_ATTEMPT_WINDOW_MS = 60_000;
const PAIR_ATTEMPT_MAX = 5;
const SESSION_TTL_MS = 12 * 3600_000;

class SessionDaemon {
  constructor(options) {
    this.stateDir = options.stateDir;
    this.identity = loadServerIdentity(this.stateDir);
    this.tokens = new TokenIssuer();
    this.enrollment = null; // { secret, expiresAt }
    this.apps = new Map();  // appHash -> { publicKeyRaw, enrolledAt }
    this.usedNonces = new Set();
    this.pairAttempts = new Map(); // ip -> [timestamps]
    this.challenges = new Map();   // appHash -> { nonce, expiresAt }
    this.channelSockets = new Set();
    this.ops = {
      "session.hello": (payload) => ({
        agent: "gbcode-sessiond/0.1.0",
        fingerprint: this.fingerprintHex,
        protocol: 1,
        echo: payload && payload.echo,
      }),
      "session.capabilities": () => ({
        protocol: 1,
        ops: Object.keys(this.ops),
        limits: { controlFrame: 65536, binaryFrame: 1048576, inFlight: 64 },
      }),
      "session.ping": (payload) => ({ pong: payload && payload.n !== undefined ? payload.n : null }),
      "device.list": () => ({ devices: [], note: "hardware providers load in S05/U01" }),
      "session.close": () => ({ closing: true }),
    };

    const appsPath = path.join(this.stateDir, "apps.json");
    if (fs.existsSync(appsPath)) {
      for (const [hash, rec] of Object.entries(JSON.parse(fs.readFileSync(appsPath, "utf8")))) {
        this.apps.set(hash, rec);
      }
    }
    this.appsPath = appsPath;
    this.fingerprintHex = fingerprint(this.identity.publicRaw);
  }

  saveApps() {
    fs.writeFileSync(this.appsPath,
      JSON.stringify(Object.fromEntries(this.apps)), { mode: 0o600 });
  }

  /** Open a 10-minute enrollment window; returns the one-time secret. */
  openEnrollment() {
    const secret = b64u(crypto.randomBytes(32));
    this.enrollment = { secret, expiresAt: Date.now() + 10 * 60_000 };
    return secret;
  }

  pairRateOk(ip) {
    const now = Date.now();
    const list = (this.pairAttempts.get(ip) || []).filter((t) => now - t < PAIR_ATTEMPT_WINDOW_MS);
    this.pairAttempts.set(ip, list);
    if (list.length >= PAIR_ATTEMPT_MAX) return false;
    list.push(now);
    return true;
  }

  json(res, code, obj) {
    const body = Buffer.from(JSON.stringify(obj));
    res.writeHead(code, { "content-type": "application/json", "content-length": body.length });
    res.end(body);
  }

  error(res, code, errorCode, retriable) {
    this.json(res, code, { v: 1, error: { code: errorCode, retriable: !!retriable } });
  }

  async readBody(req) {
    const chunks = [];
    let total = 0;
    for await (const chunk of req) {
      total += chunk.length;
      if (total > MAX_BODY) throw new Error("body too large");
      chunks.push(chunk);
    }
    return Buffer.concat(chunks);
  }

  handlePair(req, res, ip) {
    if (!this.pairRateOk(ip)) return this.error(res, 429, "rate_limited", true);
    return this.readBody(req).then((raw) => {
      let body;
      try { body = JSON.parse(raw.toString("utf8")); } catch { return this.error(res, 400, "bad_request"); }
      const enr = this.enrollment;
      if (!enr || Date.now() > enr.expiresAt) return this.error(res, 403, "pairing_closed");
      if (typeof body.pairingSecret !== "string" || !constantTimeEqual(body.pairingSecret, enr.secret)) {
        return this.error(res, 403, "secret_invalid");
      }
      if (typeof body.appPublicKey !== "string") return this.error(res, 400, "bad_request");
      let appRaw;
      try { appRaw = Buffer.from(body.appPublicKey, "base64url"); } catch { return this.error(res, 400, "bad_request"); }
      if (appRaw.length !== 32) return this.error(res, 400, "bad_request");
      if (typeof body.nonce !== "string" || body.nonce.length < 8 || this.usedNonces.has(body.nonce)) {
        return this.error(res, 403, "nonce_replayed");
      }
      this.usedNonces.add(body.nonce);
      if (this.usedNonces.size > 4096) this.usedNonces.clear();

      const appHash = sha256Hex(appRaw);
      this.apps.set(appHash, { publicKeyRaw: body.appPublicKey, enrolledAt: new Date().toISOString(), label: String(body.appLabel || "").slice(0, 64) });
      this.saveApps();
      this.enrollment = null; // single use

      const receiptSig = sign(this.identity.privateKey,
        Buffer.from("enroll"), Buffer.from(appHash, "hex"), Buffer.from(body.nonce, "utf8"));
      this.json(res, 200, {
        v: 1,
        serverPublicKey: b64u(this.identity.publicRaw),
        serverFingerprint: this.fingerprintHex,
        enrolledApp: appHash,
        receiptSig: b64u(receiptSig),
      });
    }).catch(() => this.error(res, 400, "bad_request"));
  }

  handleChallenge(req, res) {
    const url = new URL(req.url, "http://localhost");
    const appHash = url.searchParams.get("app");
    if (!appHash || !this.apps.has(appHash)) return this.error(res, 403, "unknown_app");
    const nonce = b64u(crypto.randomBytes(32));
    this.challenges.set(appHash, { nonce, expiresAt: Date.now() + 60_000 });
    this.json(res, 200, { v: 1, challenge: nonce, expiresInMs: 60_000 });
  }

  handleSession(req, res) {
    return this.readBody(req).then((raw) => {
      let body;
      try { body = JSON.parse(raw.toString("utf8")); } catch { return this.error(res, 400, "bad_request"); }
      const appHash = req.headers["x-gbcode-app"];
      const app = appHash && this.apps.get(appHash);
      if (!app) return this.error(res, 403, "unknown_app");
      const ch = this.challenges.get(appHash);
      if (!ch || Date.now() > ch.expiresAt || body.challenge !== ch.nonce) {
        return this.error(res, 403, "challenge_invalid");
      }
      this.challenges.delete(appHash);
      let ok = false;
      try {
        ok = verify(publicKeyFromRaw(app.publicKeyRaw), Buffer.from(body.appSig || "", "base64url"),
          Buffer.from("session"), Buffer.from(ch.nonce, "utf8"));
      } catch { ok = false; }
      if (!ok) return this.error(res, 403, "signature_invalid");
      const issued = this.tokens.issue(appHash, SESSION_TTL_MS);
      this.json(res, 200, { v: 1, session: issued.token, expiresAt: issued.expiresAt });
    }).catch(() => this.error(res, 400, "bad_request"));
  }

  handleChannel(req, socket, session) {
    const key = req.headers["sec-websocket-key"];
    socket.write("HTTP/1.1 101 Switching Protocols\r\n"
      + "Upgrade: websocket\r\nConnection: Upgrade\r\n"
      + `Sec-WebSocket-Accept: ${acceptKey(key)}\r\n\r\n`);
    socket.setNoDelay(true);
    this.channelSockets.add(socket);
    const drop = () => this.channelSockets.delete(socket);
    socket.on("close", drop);
    socket.on("error", drop);
    const streams = new Map(); // streamId -> expected seq
    const parser = new FrameParser((opcode, payload) => {
      if (opcode === OPCODES.PING) { socket.write(encodeFrame(OPCODES.PONG, payload)); return; }
      if (opcode === OPCODES.CLOSE) { socket.end(encodeFrame(OPCODES.CLOSE, Buffer.alloc(0))); return; }
      if (opcode === OPCODES.BINARY) {
        if (payload.length < 8) return;
        const streamId = payload.readUInt32BE(0);
        const seq = payload.readUInt32BE(4);
        const expected = streams.get(streamId) || 0;
        if (seq !== expected) {
          this.sendControl(socket, { v: 1, id: 0, ok: false, error: { code: "seq_gap", message: `stream ${streamId} expected ${expected} got ${seq}`, retriable: true } });
          return;
        }
        streams.set(streamId, seq + 1);
        return; // binary echo intentionally not implemented; real handlers in S05/U01
      }
      if (opcode !== OPCODES.TEXT) return;
      if (payload.length > 65536) {
        this.sendControl(socket, { v: 1, id: 0, ok: false, error: { code: "frame_too_large", message: "control frame exceeds 64 KiB", retriable: false } });
        return;
      }
      let msg;
      try { msg = JSON.parse(payload.toString("utf8")); } catch {
        this.sendControl(socket, { v: 1, id: 0, ok: false, error: { code: "bad_frame", message: "invalid JSON", retriable: false } });
        return;
      }
      if (msg.v !== 1 || typeof msg.id !== "number" || typeof msg.op !== "string") {
        this.sendControl(socket, { v: 1, id: msg && typeof msg.id === "number" ? msg.id : 0, ok: false, error: { code: "bad_frame", message: "need {v:1,id,op}", retriable: false } });
        return;
      }
      const handler = this.ops[msg.op];
      if (!handler) {
        this.sendControl(socket, { v: 1, id: msg.id, ok: false, error: { code: "unsupported_op", message: msg.op, retriable: false } });
        return;
      }
      try {
        const out = handler(msg.payload, session);
        this.sendControl(socket, { v: 1, id: msg.id, ok: true, payload: out });
      } catch (e) {
        this.sendControl(socket, { v: 1, id: msg.id, ok: false, error: { code: "op_failed", message: e.message, retriable: false } });
      }
    });
    socket.on("data", (chunk) => {
      try { parser.push(chunk); } catch (e) { socket.destroy(); }
    });
  }

  sendControl(socket, obj) {
    socket.write(encodeFrame(OPCODES.TEXT, Buffer.from(JSON.stringify(obj))));
  }

  createServer() {
    const server = http.createServer((req, res) => {
      const ip = req.socket.remoteAddress || "unknown";
      const url = new URL(req.url, "http://localhost");
      if (url.pathname === "/healthz" && req.method === "GET") {
        return this.json(res, 200, { v: 1, status: "ok", fingerprint: this.fingerprintHex });
      }
      if (url.pathname === "/pair" && req.method === "POST") return this.handlePair(req, res, ip);
      if (url.pathname === "/session/challenge" && req.method === "GET") return this.handleChallenge(req, res);
      if (url.pathname === "/session" && req.method === "POST") return this.handleSession(req, res);
      return this.error(res, 404, "not_found");
    });

    server.on("upgrade", (req, socket) => {
      const url = new URL(req.url, "http://localhost");
      if (url.pathname !== "/channel") { socket.destroy(); return; }
      const session = this.tokens.verify(url.searchParams.get("session"));
      if (!session) { socket.end("HTTP/1.1 403 Forbidden\r\n\r\n"); return; }
      this.handleChannel(req, socket, session);
    });
    return server;
  }

  /** Destroy channel sockets and close the HTTP server. */
  async shutdown(server) {
    for (const socket of this.channelSockets) {
      socket.destroy();
    }
    this.channelSockets.clear();
    server.closeAllConnections?.();
    await new Promise((resolve) => server.close(resolve));
  }
}

module.exports = { SessionDaemon };
