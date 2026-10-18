#!/usr/bin/env python3
"""Cloudy-X11 native 14500 bridge.

Only two endpoints exist:

  GET /__cloudy/ping?nonce=N  -> HMAC-SHA256(token, N), cookie-free
  GET /x11-tunnel             -> authenticated HTTP Upgrade to the VM Unix
                                 display tunnel, then one versioned config line
                                 and a raw byte pump

There is no xpra, HTML5, upstream HTTP proxy, or session launcher here. The
legacy viewer is intentionally not part of the native path.
"""
import argparse
import hashlib
import hmac
import json
import os
import secrets
import select
import socket
import sys
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
    server_version = "CloudyX11Bridge/1.0"

    def log_message(self, fmt, *args):
        print("[cloudy-x11-bridge] " + fmt % args, flush=True)

    def _token(self):
        return self.server.token

    def _cookie_ok(self):
        raw = self.headers.get("Cookie", "")
        for part in raw.split(";"):
            k, _, v = part.strip().partition("=")
            if k == COOKIE and hmac.compare_digest(v, self._token()):
                return True
        return False

    def _simple(self, code, text):
        body = (text + "\n").encode()
        self.send_response(code)
        self.send_header("Content-Type", "text/plain")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(body)
        self.close_connection = True

    def do_GET(self):
        parsed = urllib.parse.urlparse(self.path)
        if parsed.path == "/__cloudy/ping":
            nonce = urllib.parse.parse_qs(parsed.query).get("nonce", [""])[0]
            body = hmac.new(self._token().encode(), nonce.encode(),
                            hashlib.sha256).hexdigest().encode()
            self.send_response(200)
            self.send_header("Content-Type", "text/plain")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Connection", "close")
            self.end_headers()
            self.wfile.write(body)
            self.close_connection = True
            return
        if not self._cookie_ok():
            return self._simple(403, "forbidden")
        if parsed.path == "/x11-tunnel":
            return self._x11_tunnel()
        return self._simple(404, "not found")

    def _x11_tunnel(self):
        if self.headers.get("Upgrade", "").lower() != "cloudy-x11":
            return self._simple(400, "Upgrade: cloudy-x11 required")
        upstream = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        upstream.settimeout(3)
        try:
            upstream.connect(self.server.x11_tunnel_socket)
            upstream.sendall(("CLOUDY-X11 %s\n" % self._token()).encode())
            line = _readline(upstream, 512)
        except OSError as e:
            upstream.close()
            self.log_message("x11 tunnel upstream failed: %s", e)
            return self._simple(502, "display tunnel unavailable")
        if not line.startswith(b"OK ") or len(line) > 130:
            upstream.close()
            return self._simple(502, "display tunnel handshake failed")
        cookie = line[3:].strip().decode("ascii", "replace")
        if not (32 <= len(cookie) <= 128) or any(
                c not in "0123456789abcdefABCDEF" for c in cookie):
            upstream.close()
            return self._simple(502, "display credential malformed")

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
            return self._simple(500, "configuration too large")

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
                ready, _, _ = select.select(
                    [self.connection, upstream], [], [], 600)
                if not ready:
                    break
                for source in ready:
                    data = source.recv(65536)
                    if not data:
                        return
                    destination = (upstream if source is self.connection
                                   else self.connection)
                    destination.sendall(data)
        except OSError:
            pass
        finally:
            upstream.close()


class Server(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, listen, token, x11_tunnel_socket, display):
        self.token = token
        self.x11_tunnel_socket = x11_tunnel_socket
        self.display = display
        super().__init__(listen, Handler)


def main():
    ap = argparse.ArgumentParser(description="Cloudy X11 native bridge")
    ap.add_argument("--token-file", required=True)
    ap.add_argument("--listen", default="127.0.0.1:14500")
    ap.add_argument("--x11-tunnel-socket",
                    help="VM Unix tunnel (default: beside token file)")
    ap.add_argument("--display", default=":99")
    # Accepted for compatibility with older service units; intentionally unused.
    ap.add_argument("--upstream", default=None)
    args = ap.parse_args()
    with open(args.token_file) as f:
        token = f.read().strip()
    if not token:
        print("empty token file", file=sys.stderr)
        sys.exit(1)
    host, port = args.listen.rsplit(":", 1)
    tunnel = args.x11_tunnel_socket or os.path.join(
        os.path.dirname(os.path.abspath(args.token_file)), "x11-tunnel.sock")
    srv = Server((host, int(port)), token, tunnel, args.display)
    print("[cloudy-x11-bridge] listening %s -> unix:%s" %
          (args.listen, tunnel), flush=True)
    srv.serve_forever()


if __name__ == "__main__":
    main()
