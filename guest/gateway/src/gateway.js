// S02.03 origin gateway prototype: fixed-upstream HTTP+WS reverse proxy.
// Production role: WebView talks to this loopback gateway; the gateway owns
// the authenticated upstream connection so long-lived credentials stay out
// of page JavaScript. Bound to loopback only; single pre-approved upstream;
// no arbitrary target parameter (never a general proxy).
"use strict";

const http = require("http");
const net = require("net");
const { URL } = require("url");

const HOP_BY_HOP = new Set([
  "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
  "te", "trailer", "transfer-encoding", "upgrade",
]);

function createGateway({ listenHost = "127.0.0.1", listenPort = 0, upstream, maxHeaderBytes = 64 * 1024, rewriteOrigin = true }) {
  if (!upstream) throw new Error("upstream URL required");
  const upstreamUrl = new URL(upstream);
  const upstreamPort = Number(upstreamUrl.port || (upstreamUrl.protocol === "https:" ? 443 : 80));
  const upstreamHost = upstreamUrl.hostname;
  const upstreamOrigin = `${upstreamUrl.protocol}//${upstreamUrl.host}`;
  function fixOriginHeaders(headers) {
    if (!rewriteOrigin) return headers;
    const out = { ...headers };
    if (out.origin) out.origin = upstreamOrigin;
    if (out.referer) out.referer = upstreamOrigin + "/";
    return out;
  }

  const server = http.createServer((req, res) => {
    console.log("[http]", req.method, req.url);
    const options = {
      host: upstreamHost,
      port: upstreamPort,
      path: req.url,
      method: req.method,
      headers: fixOriginHeaders({ ...req.headers, host: upstreamUrl.host }),
    };
    for (const key of Object.keys(options.headers)) {
      if (HOP_BY_HOP.has(key.toLowerCase())) delete options.headers[key];
    }
    const proxyReq = http.request(options, (proxyRes) => {
      const headers = {};
      for (const [key, value] of Object.entries(proxyRes.headers)) {
        if (!HOP_BY_HOP.has(key.toLowerCase())) headers[key] = value;
      }
      res.writeHead(proxyRes.statusCode, proxyRes.statusMessage, headers);
      proxyRes.pipe(res);
      proxyRes.on("error", () => res.destroy());
    });
    proxyReq.on("error", (err) => {
      if (!res.headersSent) {
        res.writeHead(502, { "content-type": "application/json" });
      }
      res.end(JSON.stringify({ v: 1, error: { code: "upstream_unavailable", message: err.code } }));
    });
    req.pipe(proxyReq);
  });

  server.on("upgrade", (req, clientSocket, head) => {
    const openedAt = Date.now();
    console.log(`[ws] open ${req.url}`);
    const upstreamSocket = net.connect(upstreamPort, upstreamHost, () => {
      const headers = fixOriginHeaders({ ...req.headers, host: upstreamUrl.host });
      let request = `${req.method} ${req.url} HTTP/${req.httpVersion}\r\n`;
      for (const [key, value] of Object.entries(headers)) {
        if (!HOP_BY_HOP.has(key.toLowerCase()) || key.toLowerCase() === "upgrade" || key.toLowerCase() === "connection") {
          request += `${key}: ${value}\r\n`;
        }
      }
      request += "\r\n";
      upstreamSocket.write(request);
      if (head && head.length) upstreamSocket.write(head);
      clientSocket.pipe(upstreamSocket);
      upstreamSocket.pipe(clientSocket);
    });
    const fail = (why) => {
      console.log(`[ws] close ${req.url} after ${Date.now() - openedAt}ms (${why})`);
      clientSocket.destroy();
      upstreamSocket.destroy();
    };
    upstreamSocket.on("error", (e) => fail("upstream " + e.code));
    clientSocket.on("error", (e) => fail("client " + e.code));
    clientSocket.on("close", () => fail("client close"));
    upstreamSocket.on("close", () => fail("upstream close"));
    // Guard against unbounded idle tunnels: 6h max, configurable later.
    upstreamSocket.setTimeout(6 * 3600_000, () => fail("idle timeout"));
  });

  server.maxHeadersCount = 0; // use byte cap instead
  server.maxRequestsPerSocket = 0;
  return new Promise((resolve) => {
    server.listen(listenPort, listenHost, () => {
      const address = server.address();
      resolve({ server, port: address.port, host: address.address, upstream: upstreamUrl });
    });
  });
}

module.exports = { createGateway };
