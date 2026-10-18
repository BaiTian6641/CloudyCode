#!/usr/bin/env python3
"""Cloudy X11 proxy (guest side).

Sits in front of xpra's HTML5 endpoint and requires the app's per-install
token:

  - GET /__cloudy/ping?nonce=N  -> HMAC-SHA256(token, N) (the app verifies it)
  - every other request/WebSocket must carry  Cookie: cloudy_x11=<token>
  - xpra itself binds 127.0.0.1:14501 only; this proxy is the only listener
    on the forwarded port.

This prevents another same-device app from impersonating the X11 server and
keeps the app-side load fail-closed. Standard library only.
"""
import argparse
import hashlib
import hmac
import http.client
import json
import os
import re
import secrets
import select
import socket
import sys
import threading
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

COOKIE = "cloudy_x11"


def _readline(sock, limit):
    data = b""
    while len(data) < limit:
        chunk = sock.recv(1)
        if not chunk:
            break
        data += chunk
        if chunk == b"\n":
            break
    return data


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "CloudyX11Proxy/1.0"

    def log_message(self, fmt, *args):
        print("[cloudy-x11-proxy] " + fmt % args, flush=True)

    def _token(self):
        return self.server.token

    def _cookie_ok(self):
        raw = self.headers.get("Cookie", "")
        for part in raw.split(";"):
            k, _, v = part.strip().partition("=")
            if k == COOKIE and hmac.compare_digest(v, self._token()):
                return True
        return False

    def _deny(self):
        body = b"forbidden\n"
        self.send_response(403)
        self.send_header("Content-Type", "text/plain")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(body)
        self.close_connection = True

    def do_GET(self):
        self._handle()

    def do_POST(self):
        self._handle()

    def do_PUT(self):
        self._handle()

    def do_DELETE(self):
        self._handle()

    def do_HEAD(self):
        self._handle()

    def do_OPTIONS(self):
        self._handle()

    def _handle(self):
        parsed = urllib.parse.urlparse(self.path)
        if parsed.path == "/__cloudy/ping":
            # No cookie: the challenge must never carry the secret it proves.
            nonce = urllib.parse.parse_qs(parsed.query).get("nonce", [""])[0]
            sig = hmac.new(self._token().encode(), nonce.encode(), hashlib.sha256).hexdigest()
            body = sig.encode()
            self.send_response(200)
            self.send_header("Content-Type", "text/plain")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Connection", "close")
            self.end_headers()
            self.wfile.write(body)
            self.close_connection = True
            return
        if not self._cookie_ok():
            return self._deny()
        if parsed.path == "/x11-tunnel":
            return self._x11_tunnel()
        if (self.headers.get("Upgrade", "").lower() == "websocket"):
            return self._tunnel()
        return self._proxy_http()

    def _simple_error(self, code, text):
        body = (text + "\n").encode()
        self.send_response(code)
        self.send_header("Content-Type", "text/plain")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(body)
        self.close_connection = True

    def _x11_tunnel(self):
        """Raw HTTP Upgrade -> private VM Unix tunnel.

        The bridge authenticates the app, performs the private VM-side
        handshake, verifies it before 101, then emits exactly one versioned
        configuration line before becoming a byte pump. It never parses X11
        frames itself and never exposes the Unix socket on TCP.
        """
        if self.headers.get("Upgrade", "").lower() != "cloudy-x11":
            return self._simple_error(400, "Upgrade: cloudy-x11 required")
        upstream = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        upstream.settimeout(3)
        try:
            upstream.connect(self.server.x11_tunnel_socket)
            upstream.sendall(("CLOUDY-X11 %s\n" % self._token()).encode())
            line = _readline(upstream, 512)
        except OSError as e:
            upstream.close()
            self.log_message("x11 tunnel upstream failed: %s", e)
            return self._simple_error(502, "display tunnel unavailable")
        # A bare legacy OK is rejected: the bridge needs a display cookie.
        if not line.startswith(b"OK ") or len(line) > 130:
            upstream.close()
            return self._simple_error(502, "display tunnel handshake failed")
        cookie = line[3:].strip().decode("ascii", "replace")
        if not re.fullmatch(r"[0-9a-fA-F]{32,128}", cookie):
            upstream.close()
            return self._simple_error(502, "display credential malformed")

        config = {
            "v": 1,
            "display": self.server.display,
            "auth": "MIT-MAGIC-COOKIE-1",
            "cookie": cookie,
            "generation": secrets.token_hex(8),
            "max_frame": 1048576,
            "max_streams": 64,
            "max_queued_bytes": 16777216,
        }
        line = ("CLOUDY-X11-CONFIG 1 " +
                json.dumps(config, separators=(",", ":")) + "\n").encode()
        if len(line) > 4096:
            upstream.close()
            return self._simple_error(500, "configuration too large")

        self.send_response(101, "Switching Protocols")
        self.send_header("Upgrade", "cloudy-x11")
        self.send_header("Connection", "Upgrade")
        self.end_headers()
        self.close_connection = True
        try:
            self.wfile.write(line)
            self.wfile.flush()
        except OSError:
            upstream.close()
            return

        self.connection.settimeout(600)
        upstream.settimeout(600)
        try:
            while True:
                ready, _, _ = select.select([self.connection, upstream], [], [], 600)
                if not ready:
                    break
                for source in ready:
                    data = source.recv(65536)
                    if not data:
                        return
                    destination = upstream if source is self.connection else self.connection
                    destination.sendall(data)
        except OSError:
            pass
        finally:
            upstream.close()

    def _proxy_http(self):
        length = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(length) if length else None
        headers = {}
        for k, v in self.headers.items():
            lk = k.lower()
            if lk in ("host", "connection", "cookie", "content-length",
                      "accept-encoding"):
                continue
            headers[k] = v
        if body is not None:
            headers["Content-Length"] = str(len(body))
        try:
            conn = http.client.HTTPConnection(self.server.upstream_host,
                                              self.server.upstream_port, timeout=30)
            conn.request(self.command, self.path, body=body, headers=headers)
            resp = conn.getresponse()
            data = resp.read()
            # The HTML5 client uses a CSS viewport (device-width) which is
            # smaller than the WebView's physical pixels on high-DPI screens:
            # xpra then shrinks the X screen to that CSS size and the browser
            # letterboxes it (black margins, tiny text). Pin the client
            # viewport to the physical width supplied by the app.
            try:
                q = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
                w = int(q.get("cloudy_w", ["0"])[0])
                ctype = (resp.getheader("Content-Type") or "").lower()
                if 320 <= w <= 8192 and "text/html" in ctype:
                    html = data.decode("utf-8", "replace")
                    meta = f'<meta name="viewport" content="width={w}, initial-scale=1" />'
                    html, n = re.subn(
                        r'<meta\s+name="viewport"[^>]*>', meta, html,
                        count=1, flags=re.I)
                    if n == 0:
                        html = html.replace("<head>", "<head>" + meta, 1)
                    data = html.encode("utf-8")
            except Exception as e:
                self.log_message("viewport rewrite skipped: %s", e)
        except Exception as e:
            self.log_message("upstream error: %s", e)
            body = b"xpra upstream unavailable\n"
            self.send_response(502)
            self.send_header("Content-Type", "text/plain")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Connection", "close")
            self.end_headers()
            self.wfile.write(body)
            self.close_connection = True
            return
        self.send_response(resp.status)
        for k, v in resp.getheaders():
            if k.lower() in ("connection", "transfer-encoding", "content-length"):
                continue
            self.send_header(k, v)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Connection", "close")
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(data)
        self.close_connection = True

    def _tunnel(self):
        try:
            upstream = socket.create_connection(
                (self.server.upstream_host, self.server.upstream_port), timeout=10)
        except Exception as e:
            self.log_message("ws upstream error: %s", e)
            return self._deny()
        lines = [f"{self.command} {self.path} HTTP/1.1"]
        for k, v in self.headers.items():
            lk = k.lower()
            if lk in ("host", "cookie"):
                continue
            lines.append(f"{k}: {v}")
        lines.append(f"Host: {self.server.upstream_host}:{self.server.upstream_port}")
        upstream.sendall(("\r\n".join(lines) + "\r\n\r\n").encode())

        client = self.connection
        client.setblocking(False)
        upstream.setblocking(False)
        try:
            while True:
                readable, _, _ = select.select([client, upstream], [], [], 300)
                if not readable:
                    break
                for src in readable:
                    data = src.recv(65536)
                    if not data:
                        return
                    dst = upstream if src is client else client
                    dst.sendall(data)
        finally:
            try:
                upstream.close()
            except OSError:
                pass
            self.close_connection = True


class Server(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, listen, upstream, token, x11_tunnel_socket, display):
        host, port = listen
        self.upstream_host, self.upstream_port = upstream
        self.token = token
        self.x11_tunnel_socket = x11_tunnel_socket
        self.display = display
        super().__init__((host, port), Handler)


def main():
    ap = argparse.ArgumentParser(description="Cloudy X11 token proxy")
    ap.add_argument("--token-file", required=True)
    ap.add_argument("--listen", default="127.0.0.1:14500")
    ap.add_argument("--upstream", default="127.0.0.1:14501")
    ap.add_argument("--x11-tunnel-socket",
                    help="VM Unix tunnel (default: beside the token file)")
    ap.add_argument("--display", default=":99")
    args = ap.parse_args()
    with open(args.token_file) as f:
        token = f.read().strip()
    if not token:
        print("empty token file", file=sys.stderr)
        sys.exit(1)
    host, port = args.listen.rsplit(":", 1)
    uhost, uport = args.upstream.rsplit(":", 1)
    tunnel_socket = args.x11_tunnel_socket or os.path.join(
        os.path.dirname(os.path.abspath(args.token_file)), "x11-tunnel.sock")
    srv = Server((host, int(port)), (uhost, int(uport)), token,
                 tunnel_socket, args.display)
    print(f"[cloudy-x11-proxy] listening {args.listen} -> {args.upstream}", flush=True)
    srv.serve_forever()


if __name__ == "__main__":
    main()
