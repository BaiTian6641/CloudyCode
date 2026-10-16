// S02.03 gateway tests: HTTP proxying, WS upgrade piping, header rules, 502 path.
"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const http = require("http");
const net = require("net");
const crypto = require("crypto");

const { createGateway } = require("../src/gateway");
const { acceptKey, encodeFrame, FrameParser, OPCODES } = require("../../sessiond/src/ws");

function makeUpstream() {
  const server = http.createServer((req, res) => {
    if (req.url === "/json") {
      const body = Buffer.from(JSON.stringify({ ok: true, path: req.url, host: req.headers.host }));
      res.writeHead(200, { "content-type": "application/json", "content-length": body.length });
      res.end(body);
      return;
    }
    if (req.url === "/stream") {
      res.writeHead(200, { "content-type": "application/octet-stream" });
      let i = 0;
      const timer = setInterval(() => {
        res.write(Buffer.from(`chunk-${i++};`));
        if (i >= 5) { clearInterval(timer); res.end(); }
      }, 10);
      return;
    }
    res.writeHead(404); res.end();
  });
  // WS echo endpoint
  const upgradeSockets = new Set();
  server.on("upgrade", (req, socket) => {
    upgradeSockets.add(socket);
    socket.on("close", () => upgradeSockets.delete(socket));
    if (req.url !== "/ws") { socket.destroy(); return; }
    socket.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
      + `Sec-WebSocket-Accept: ${acceptKey(req.headers["sec-websocket-key"])}\r\n\r\n`);
    const parser = new FrameParser((opcode, payload) => {
      if (opcode === OPCODES.TEXT || opcode === OPCODES.BINARY) {
        socket.write(encodeFrame(opcode, payload));
      }
    });
    socket.on("data", (c) => parser.push(c));
  });
  server.destroyUpgradeSockets = () => { for (const s of upgradeSockets) s.destroy(); };
  return new Promise((r) => server.listen(0, "127.0.0.1", () => r(server)));
}
test("HTTP GET is proxied with host rewrite and body intact", async () => {
  const upstream = await makeUpstream();
  const up = upstream.address().port;
  const gw = await createGateway({ upstream: `http://127.0.0.1:${up}` });

  const resp = await fetch(`http://127.0.0.1:${gw.port}/json`);
  assert.equal(resp.status, 200);
  const body = await resp.json();
  assert.equal(body.ok, true);
  assert.equal(body.host, `127.0.0.1:${up}`);

  gw.server.closeAllConnections?.();
  gw.server.close();
  upstream.closeAllConnections?.();
  upstream.close();
});

test("streaming response is not buffered", async () => {
  const upstream = await makeUpstream();
  const gw = await createGateway({ upstream: `http://127.0.0.1:${upstream.address().port}` });

  const resp = await fetch(`http://127.0.0.1:${gw.port}/stream`);
  const reader = resp.body.getReader();
  const t0 = Date.now();
  let first = null;
  let total = 0;
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    if (first === null) first = Date.now() - t0;
    total += value.length;
  }
  assert.ok(total > 20);
  assert.ok(first < 500, `first chunk took ${first}ms (buffered?)`);

  gw.server.closeAllConnections?.();
  gw.server.close();
  upstream.closeAllConnections?.();
  upstream.close();
});

test("WebSocket upgrade pipes binary echo end to end", async () => {
  const upstream = await makeUpstream();
  const gw = await createGateway({ upstream: `http://127.0.0.1:${upstream.address().port}` });

  const key = crypto.randomBytes(16).toString("base64");
  const socket = net.connect(gw.port, "127.0.0.1", () => {
    socket.write(`GET /ws HTTP/1.1\r\nHost: 127.0.0.1:${gw.port}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: ${key}\r\nSec-WebSocket-Version: 13\r\n\r\n`);
  });

  let handshake = Buffer.alloc(0);
  const frames = [];
  await new Promise((resolve, reject) => {
    const parser = new FrameParser((opcode, payload) => frames.push({ opcode, payload }));
    const onData = (chunk) => {
      if (handshake !== null) {
        handshake = Buffer.concat([handshake, chunk]);
        const end = handshake.indexOf("\r\n\r\n");
        if (end === -1) return;
        const rest = handshake.subarray(end + 4);
        handshake = null;
        if (rest.length) parser.push(rest);
        // send masked binary frame
        const payload = crypto.randomBytes(512);
        const mask = crypto.randomBytes(4);
        const masked = Buffer.from(payload);
        for (let i = 0; i < masked.length; i++) masked[i] ^= mask[i % 4];
        socket.write(Buffer.concat([Buffer.from([0x82, 0x80 | 126, 0x02, 0x00]), mask, masked]));
        frames.expected = payload;
        setTimeout(() => {
          assert.equal(frames.length, 1);
          assert.equal(frames[0].opcode, OPCODES.BINARY);
          assert.deepEqual(frames[0].payload, frames.expected);
          resolve();
        }, 200);
        return;
      }
      parser.push(chunk);
    };
    socket.on("data", onData);
    socket.on("error", reject);
  });

  socket.destroy();
  upstream.destroyUpgradeSockets();
  gw.server.closeAllConnections?.();
  gw.server.close();
  upstream.closeAllConnections?.();
  upstream.close();
});

test("upstream down yields 502 json", async () => {
  const gw = await createGateway({ upstream: "http://127.0.0.1:1" });
  const resp = await fetch(`http://127.0.0.1:${gw.port}/json`);
  assert.equal(resp.status, 502);
  const body = await resp.json();
  assert.equal(body.error.code, "upstream_unavailable");
  gw.server.close();
});

test("origin/referer are rewritten to the upstream origin when enabled", async () => {
  const upstream = http.createServer((req, res) => {
    if (req.headers.origin && req.headers.origin !== `http://127.0.0.1:${upstream.address().port}`) {
      res.writeHead(403); res.end("bad origin"); return;
    }
    const body = Buffer.from(JSON.stringify({ origin: req.headers.origin || null }));
    res.writeHead(200, { "content-type": "application/json", "content-length": body.length });
    res.end(body);
  });
  await new Promise((r) => upstream.listen(0, "127.0.0.1", r));
  const gw = await createGateway({ upstream: `http://127.0.0.1:${upstream.address().port}` });

  const resp = await fetch(`http://127.0.0.1:${gw.port}/json`, { headers: { origin: `http://127.0.0.1:${gw.port}` } });
  assert.equal(resp.status, 200);
  const body = await resp.json();
  assert.equal(body.origin, `http://127.0.0.1:${upstream.address().port}`);

  gw.server.closeAllConnections?.();
  gw.server.close();
  upstream.closeAllConnections?.();
  upstream.close();
});
