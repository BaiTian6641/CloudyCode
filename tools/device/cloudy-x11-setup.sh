#!/usr/bin/env bash
# Cloudy X11 forwarding: xpra on guest loopback plus a token-authenticated
# proxy on port 14500. The app sets the token cookie and verifies an HMAC
# before loading anything. X clients use DISPLAY=:100.
set -euo pipefail

TOKEN=""
while [ $# -gt 0 ]; do
  case "$1" in
    --token) TOKEN="${2:-}"; shift 2;;
    *) echo "unknown argument: $1" >&2; exit 2;;
  esac
done
[ -n "$TOKEN" ] || { echo "usage: cloudy-x11-setup.sh --token <token>" >&2; exit 2; }

echo "== Cloudy X11 setup (xpra + token proxy) =="
BASE="$HOME/.local/share/cloudy"
mkdir -p "$BASE" "$HOME/.config/systemd/user"
(umask 077; printf '%s' "$TOKEN" > "$BASE/x11-token")

sudo -v
# xpra is not in the stock VM apt sources: add the official xpra.org repo.
. /etc/os-release
CODENAME="${VERSION_CODENAME:-bookworm}"
sudo install -d -m 0755 /usr/share/keyrings
curl -fsSL https://xpra.org/xpra.asc | sudo tee /usr/share/keyrings/xpra.asc >/dev/null
echo "deb [signed-by=/usr/share/keyrings/xpra.asc] https://xpra.org/ $CODENAME main" \
  | sudo tee /etc/apt/sources.list.d/xpra.list >/dev/null
sudo apt-get update
sudo apt-get install -y --no-install-recommends xpra xpra-x11 xpra-html5 xvfb x11-utils || {
  echo "xpra still unavailable; check the xpra.org repo for $CODENAME:" >&2
  apt-cache policy xpra >&2
  exit 1
}

# The xpra package also ships a system xpra-server.socket/service that binds
# 14500, which is our token proxy's forwarded port. They are unrelated to the
# per-user seamless session on 14501; stop and disable them.
sudo systemctl stop xpra-server.socket xpra-server.service 2>/dev/null || true
sudo systemctl disable xpra-server.socket xpra-server.service 2>/dev/null || true

cat > "$BASE/cloudy-x11-proxy.py" <<'PY'
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
import os
import select
import socket
import sys
import threading
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

COOKIE = "cloudy_x11"


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
            if not self._cookie_ok():
                return self._deny()
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
        if (self.headers.get("Upgrade", "").lower() == "websocket"):
            return self._tunnel()
        return self._proxy_http()

    def _proxy_http(self):
        length = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(length) if length else None
        headers = {}
        for k, v in self.headers.items():
            lk = k.lower()
            if lk in ("host", "connection", "cookie", "content-length"):
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
            if lk == "upgrade" or lk.startswith("sec-websocket"):
                lines.append(f"{k}: {v}")
            else:
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

    def __init__(self, listen, upstream, token):
        host, port = listen
        self.upstream_host, self.upstream_port = upstream
        self.token = token
        super().__init__((host, port), Handler)


def main():
    ap = argparse.ArgumentParser(description="Cloudy X11 token proxy")
    ap.add_argument("--token-file", required=True)
    ap.add_argument("--listen", default="127.0.0.1:14500")
    ap.add_argument("--upstream", default="127.0.0.1:14501")
    args = ap.parse_args()
    with open(args.token_file) as f:
        token = f.read().strip()
    if not token:
        print("empty token file", file=sys.stderr)
        sys.exit(1)
    host, port = args.listen.rsplit(":", 1)
    uhost, uport = args.upstream.rsplit(":", 1)
    srv = Server((host, int(port)), (uhost, int(uport)), token)
    print(f"[cloudy-x11-proxy] listening {args.listen} -> {args.upstream}", flush=True)
    srv.serve_forever()


if __name__ == "__main__":
    main()

PY
chmod +x "$BASE/cloudy-x11-proxy.py"

cat > "$HOME/.config/systemd/user/cloudy-x11.service" <<'UNIT'
[Unit]
Description=Cloudy X11 seamless server (xpra, loopback)
After=network.target

[Service]
ExecStart=/usr/bin/xpra start :100 --daemon=no --bind-tcp=127.0.0.1:14501   --html=on --tcp-auth=none --mdns=no --notifications=no
Restart=on-failure
RestartSec=2

[Install]
WantedBy=default.target
UNIT

cat > "$HOME/.config/systemd/user/cloudy-x11-proxy.service" <<UNIT
[Unit]
Description=Cloudy X11 token proxy
After=cloudy-x11.service

[Service]
ExecStart=/usr/bin/python3 $BASE/cloudy-x11-proxy.py --token-file $BASE/x11-token --listen 127.0.0.1:14500 --upstream 127.0.0.1:14501
Restart=on-failure
RestartSec=2

[Install]
WantedBy=default.target
UNIT

pkill -f 'cloudy-x11-proxy.py' 2>/dev/null || true
systemctl --user daemon-reload
systemctl --user enable --now cloudy-x11.service cloudy-x11-proxy.service \
  || echo "systemd user start failed (will fall back to direct start)"
loginctl enable-linger "$USER" 2>/dev/null || sudo loginctl enable-linger "$USER" 2>/dev/null || true
sleep 2

# Inline fallback: no helper scripts. systemd user units can be unavailable in
# some terminal sessions, so start whichever piece is missing directly.
if ! systemctl --user is-active cloudy-x11.service >/dev/null 2>&1; then
  echo "xpra service inactive; starting it directly"
  xpra stop :100 >/dev/null 2>&1 || true
  xpra start :100 --daemon=yes --bind-tcp=127.0.0.1:14501 --html=on \
    --tcp-auth=none --mdns=no --notifications=no || true
  sleep 2
fi
if ! ss -ltn 2>/dev/null | grep -q ':14500'; then
  echo "proxy not listening; starting it directly"
  nohup python3 "$BASE/cloudy-x11-proxy.py" --token-file "$BASE/x11-token" \
    --listen 127.0.0.1:14500 --upstream 127.0.0.1:14501 >"$BASE/x11-proxy.log" 2>&1 &
  sleep 1
fi

echo "== status =="
systemctl --user --no-pager status cloudy-x11.service 2>/dev/null | head -6 || true
systemctl --user --no-pager status cloudy-x11-proxy.service 2>/dev/null | head -6 || true
echo "xpra sessions:"; xpra list 2>/dev/null || true
echo "listeners:"; ss -ltn 2>/dev/null | grep -E ':14500|:14501' || true
if ! ss -ltn 2>/dev/null | grep -q ':14500'; then
  echo "== proxy did not start: diagnostics =="
  systemctl --user --no-pager status cloudy-x11-proxy.service 2>/dev/null | head -12 || true
  journalctl --user -u cloudy-x11-proxy -n 20 --no-pager 2>/dev/null || true
  tail -20 "$BASE/x11-proxy.log" 2>/dev/null || true
fi
echo
echo "Open GUI apps in the VM with:"
echo "  DISPLAY=:100 firefox &"
echo "Then open the 'Cloudy X11' window in the app."
echo "If nothing appears: journalctl --user -u cloudy-x11 -u cloudy-x11-proxy -n 40 --no-pager"
