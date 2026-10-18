#!/usr/bin/env bash
# Cloudy-X11 native VM setup v4 (2026-10-18).
# One-time install: persistent :99 display proxy -> Unix tunnel -> 14500 bridge.
# No xpra, no sessiond, no per-shell wrappers. X11 clients run normally in the VM.
set -euo pipefail

TOKEN=""
while [ $# -gt 0 ]; do
  case "$1" in
    --token) TOKEN="${2:-}"; shift 2;;
    *) echo "unknown argument: $1" >&2; exit 2;;
  esac
done
[ -n "$TOKEN" ] || { echo "usage: cloudy-x11-setup.sh --token <token>" >&2; exit 2; }

echo "== Cloudy-X11 native VM setup v3 =="
BASE="$HOME/.local/share/cloudy"
mkdir -p "$BASE" "$HOME/.config/systemd/user"
umask 077
printf '%s' "$TOKEN" > "$BASE/x11-token"
chmod 600 "$BASE/x11-token"

sudo -v
sudo apt-get update
sudo apt-get install -y --no-install-recommends xauth x11-utils xterm x11-apps || {
  echo "required X11 utilities could not be installed" >&2
  exit 1
}

# Retire the prototype services; the native path does not use xpra/sessiond.
systemctl --user stop cloudy-x11.service cloudy-x11-sessiond.service 2>/dev/null || true
systemctl --user disable cloudy-x11.service cloudy-x11-sessiond.service 2>/dev/null || true
pkill -f 'cloudy-x11-sessiond.py' 2>/dev/null || true

cat > "$BASE/cloudy-x11-proxy.py" <<'PY'
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

PY
chmod +x "$BASE/cloudy-x11-proxy.py"

cat > "$BASE/cloudy-x11-vm.py" <<'CLOUDY_X11_VM_PY'
#!/usr/bin/env python3
"""Cloudy-X11 VM display proxy (display :99).

Owns /tmp/.X11-unix/X<display> and forwards each X11 client connection either to
a local upstream X server (test mode) or to the Cloudy-X11 app over one
authenticated framed connection (production mode). The proxy is transparent to
the X11 protocol; X11 authentication remains between the client and the app's X
server.

Tunnel protocol (the app side initiates the connection to --tunnel-listen):
  app -> proxy: b"CLOUDY-X11 " + token + b"\n"
  proxy -> app: b"OK " + display-cookie + b"\n" (or b"ERR\n")
The display cookie comes from --cookie-file; without it the proxy sends bare
OK, which the production 14500 bridge rejects. X11 auth itself remains between
the VM client and the app's X server.
  frame: type(1) + stream_id(!I) [+ length(!I) + payload for DATA]
    O open, D data, C close.  The proxy sends O/D/C; the app sends D/C.
"""
import argparse
import hmac
import os
import socket
import struct
import sys
import threading
import time

MAX_FRAME = 1 << 20
CHUNK = 65536


def log(msg):
    print(f"[cloudy-x11-vm] {msg}", flush=True)


def parse_upstream(value):
    if value.startswith("unix:"):
        return ("unix", value[5:])
    if value.startswith("tcp:"):
        host, _, port = value[5:].rpartition(":")
        return ("tcp", (host, int(port)))
    if ":" in value:
        host, _, port = value.rpartition(":")
        return ("tcp", (host, int(port)))
    raise ValueError("address must be unix:/path, tcp:host:port or host:port")


def connect_addr(spec, timeout=5):
    kind, addr = spec
    s = socket.socket(socket.AF_UNIX if kind == "unix" else socket.AF_INET,
                      socket.SOCK_STREAM)
    s.settimeout(timeout)
    s.connect(addr)
    s.settimeout(None)
    return s


def recv_exact(sock, n):
    buf = bytearray()
    while len(buf) < n:
        part = sock.recv(n - len(buf))
        if not part:
            raise EOFError
        buf += part
    return bytes(buf)


def pump(src, dst, on_eof):
    try:
        while True:
            data = src.recv(CHUNK)
            if not data:
                break
            dst.sendall(data)
    except OSError:
        pass
    finally:
        on_eof()


class Proxy:
    def __init__(self, args):
        self.display = args.display.lstrip(":") if args.display.startswith(":") else args.display
        self.display_path = f"/tmp/.X11-unix/X{self.display}"
        self.upstream = parse_upstream(args.upstream) if args.upstream else None
        self.tunnel_listen = parse_upstream(args.tunnel_listen) if args.tunnel_listen else None
        if bool(self.upstream) == bool(self.tunnel_listen):
            raise SystemExit("exactly one of --upstream or --tunnel-listen is required")
        self.token = open(args.token_file).read().strip() if args.token_file else ""
        self.cookie = ""
        if getattr(args, "cookie_file", None):
            try:
                self.cookie = open(args.cookie_file).read().strip()
            except OSError as e:
                log(f"cookie file unreadable: {e}")
        self.client_wait = args.client_wait
        self.app = None
        self.app_ready = threading.Event()
        self.app_lock = threading.Lock()
        self.streams = {}
        self.stream_lock = threading.Lock()
        self.next_sid = 1
        self.stop = threading.Event()

    def start(self):
        if self.tunnel_listen:
            threading.Thread(target=self.tunnel_server, daemon=True).start()
        self.display_server()

    def display_server(self):
        os.makedirs("/tmp/.X11-unix", exist_ok=True)
        try:
            os.unlink(self.display_path)
        except FileNotFoundError:
            pass
        srv = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        srv.bind(self.display_path)
        os.chmod(self.display_path, 0o777)
        srv.listen(64)
        log(f"display :{self.display} on {self.display_path}")
        while not self.stop.is_set():
            conn, _ = srv.accept()
            threading.Thread(target=self.client, args=(conn,), daemon=True).start()

    # ---- local upstream (transport test) ----
    def client(self, conn):
        if self.upstream:
            try:
                up = connect_addr(self.upstream)
            except OSError as e:
                log(f"upstream connect failed: {e}")
                conn.close()
                return
            threading.Thread(target=pump, args=(up, conn, up.close), daemon=True).start()
            pump(conn, up, lambda: (conn.close(), up.close()))
            return

        # ---- app tunnel ----
        if not self.app_ready.wait(timeout=self.client_wait):
            log("client rejected: no app tunnel")
            conn.close()
            return
        with self.stream_lock:
            sid = self.next_sid
            self.next_sid += 1
            self.streams[sid] = conn
        self.send_frame(b"O" + struct.pack("!I", sid))
        while True:
            try:
                data = conn.recv(CHUNK)
            except OSError:
                data = b""
            if not data:
                break
            self.send_frame(b"D" + struct.pack("!I", sid) + struct.pack("!I", len(data)) + data)
        with self.stream_lock:
            self.streams.pop(sid, None)
        self.send_frame(b"C" + struct.pack("!I", sid))
        conn.close()

    def send_frame(self, frame):
        with self.app_lock:
            app = self.app
        if app is None:
            return
        try:
            app.sendall(frame)
        except OSError:
            pass

    def tunnel_server(self):
        kind, addr = self.tunnel_listen
        if kind == "unix":
            try:
                os.unlink(addr)
            except FileNotFoundError:
                pass
            srv = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
            srv.bind(addr)
            os.chmod(addr, 0o600)
            log(f"tunnel on unix:{addr}")
        else:
            srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            srv.bind(addr)
            log(f"tunnel on tcp:{addr[0]}:{addr[1]}")
        srv.listen(1)
        while not self.stop.is_set():
            conn, peer = srv.accept()
            if self.app_ready.is_set():
                log(f"rejecting second app from {peer}")
                conn.close()
                continue
            try:
                line = b""
                conn.settimeout(5)
                while not line.endswith(b"\n") and len(line) < 256:
                    line += conn.recv(1)
                conn.settimeout(None)
                expected = f"CLOUDY-X11 {self.token}\n".encode()
                if not hmac.compare_digest(line, expected):
                    conn.sendall(b"ERR\n")
                    conn.close()
                    log(f"app auth failed from {peer}")
                    continue
                conn.sendall(("OK %s\n" % self.cookie).encode() if self.cookie
                             else b"OK\n")
            except OSError as e:
                log(f"app handshake failed: {e}")
                conn.close()
                continue
            with self.app_lock:
                self.app = conn
            self.app_ready.set()
            log(f"app connected from {peer}")
            threading.Thread(target=self.app_reader, args=(conn,), daemon=True).start()

    def app_reader(self, app):
        try:
            while True:
                kind = recv_exact(app, 1)
                sid = struct.unpack("!I", recv_exact(app, 4))[0]
                if kind == b"D":
                    size = struct.unpack("!I", recv_exact(app, 4))[0]
                    if size > MAX_FRAME:
                        break
                    data = recv_exact(app, size)
                    with self.stream_lock:
                        client = self.streams.get(sid)
                    if client is not None:
                        try:
                            client.sendall(data)
                        except OSError:
                            pass
                elif kind == b"C":
                    with self.stream_lock:
                        client = self.streams.pop(sid, None)
                    if client is not None:
                        client.close()
                else:
                    log(f"bad frame type {kind!r}")
                    break
        except (EOFError, OSError):
            pass
        finally:
            with self.app_lock:
                self.app = None
            self.app_ready.clear()
            with self.stream_lock:
                clients = list(self.streams.values())
                self.streams.clear()
            for client in clients:
                client.close()
            app.close()
            log("app disconnected")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--display", default=":99")
    ap.add_argument("--upstream", help="unix:/path or tcp:host:port (test mode)")
    ap.add_argument("--tunnel-listen", help="tcp:127.0.0.1:14511 (app mode)")
    ap.add_argument("--token-file")
    ap.add_argument("--cookie-file", help="Xauthority MIT-MAGIC-COOKIE-1 hex")
    ap.add_argument("--client-wait", type=float, default=30.0)
    args = ap.parse_args()
    if args.tunnel_listen and not args.token_file:
        ap.error("--token-file is required with --tunnel-listen")
    Proxy(args).start()


if __name__ == "__main__":
    main()

CLOUDY_X11_VM_PY
chmod +x "$BASE/cloudy-x11-vm.py"

cat > "$BASE/cloudy-x11-install-env.sh" <<'CLOUDY_X11_ENV_SH'
#!/bin/sh
# Install the VM-wide Cloudy-X11 display environment and user service.
# Usage: install-env.sh [--base DIR] [--display :99] [--system]
set -eu
BASE="${HOME}/.local/share/cloudy-x11-vm"
DISPLAY=":99"
SYSTEM=0
while [ $# -gt 0 ]; do
  case "$1" in
    --base) BASE="$2"; shift 2 ;;
    --display) DISPLAY="$2"; shift 2 ;;
    --system) SYSTEM=1; shift ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done
mkdir -p "$BASE" "$HOME/.config/environment.d" "$HOME/.config/systemd/user"
XAUTH="$BASE/Xauthority"
umask 077
COOKIE=$(xauth -f "$XAUTH" list "$DISPLAY" 2>/dev/null | awk 'NR==1{print $3}') || true
if [ -z "${COOKIE:-}" ]; then
  COOKIE=$(python3 - <<'PY'
import secrets
print(secrets.token_hex(16))
PY
)
  xauth -f "$XAUTH" add "$DISPLAY" . "$COOKIE"
  xauth -f "$XAUTH" add ":98" . "$COOKIE"
  echo "created $XAUTH (display $DISPLAY)"
fi
printf '%s' "$COOKIE" > "$BASE/x11-cookie"
chmod 600 "$BASE/x11-cookie"
cat > "$HOME/.config/environment.d/90-cloudy-x11.conf" <<EOF
DISPLAY=$DISPLAY
XAUTHORITY=$XAUTH
EOF
# Replace the prototype's fixed :100 export with the VM-wide Cloudy display.
for rc in "$HOME/.bashrc" "$HOME/.profile"; do
  [ -f "$rc" ] || continue
  grep -v 'export DISPLAY=:100' "$rc" > "$rc.cloudy.tmp" || true
  if ! grep -q 'CLOUDY_X11_DISPLAY' "$rc.cloudy.tmp"; then
    cat >> "$rc.cloudy.tmp" <<EOF
# CLOUDY_X11_DISPLAY
export DISPLAY=$DISPLAY
export XAUTHORITY=$XAUTH
EOF
  fi
  if ! grep -q 'CLOUDY_X11_NATIVE_LAUNCHERS' "$rc.cloudy.tmp"; then
    cat >> "$rc.cloudy.tmp" <<'EOF'
# CLOUDY_X11_NATIVE_LAUNCHERS
if [ -n "$BASH_VERSION" ]; then
  unalias firefox cloudy-firefox cloudy-run 2>/dev/null || true
  unset -f firefox cloudy-firefox cloudy-run 2>/dev/null || true
  alias firefox=/usr/bin/firefox 2>/dev/null || true
fi
EOF
  fi
  mv "$rc.cloudy.tmp" "$rc"
done
if [ "$SYSTEM" = 1 ]; then
  printf '# CLOUDY_X11_DISPLAY\nexport DISPLAY=%s\nexport XAUTHORITY=%s\n' "$DISPLAY" "$XAUTH" \
    | sudo tee /etc/profile.d/cloudy-x11.sh >/dev/null
  echo "installed /etc/profile.d/cloudy-x11.sh"
fi
cat > "$HOME/.config/systemd/user/cloudy-x11-vm.service" <<EOF
[Unit]
Description=Cloudy-X11 VM display proxy (:99)
After=default.target

[Service]
ExecStart=/usr/bin/python3 $BASE/cloudy-x11-vm.py --display $DISPLAY --tunnel-listen unix:$BASE/x11-tunnel.sock --token-file $BASE/x11-token --cookie-file $BASE/x11-cookie
Restart=on-failure
RestartSec=2

[Install]
WantedBy=default.target
EOF
if [ ! -s "$BASE/x11-token" ]; then
  echo "missing $BASE/x11-token; the app setup must write the per-install token" >&2
fi
systemctl --user enable cloudy-x11-vm.service 2>/dev/null || true
echo "Installed environment and service. Restart the Cloudy Code backend and open a new shell."
echo "Test (VM): $BASE/cloudy-x11-vm.py + tunnel-test.py with a local Xvfb; see README."

CLOUDY_X11_ENV_SH
chmod +x "$BASE/cloudy-x11-install-env.sh"

"$BASE/cloudy-x11-install-env.sh" --base "$BASE" --display :99 --system \
  || echo "environment service write reported an error" >&2
systemctl --user daemon-reload 2>/dev/null || true
systemctl --user enable cloudy-x11-vm.service 2>/dev/null || true
systemctl --user restart cloudy-x11-vm.service 2>/dev/null || {
  pkill -f 'cloudy-x11-vm.py' 2>/dev/null || true
  nohup python3 "$BASE/cloudy-x11-vm.py" --display :99 \
    --tunnel-listen "unix:$BASE/x11-tunnel.sock" \
    --token-file "$BASE/x11-token" --cookie-file "$BASE/x11-cookie" \
    > "$BASE/x11-vm.log" 2>&1 &
}

cat > "$HOME/.config/systemd/user/cloudy-x11-proxy.service" <<UNIT
[Unit]
Description=Cloudy X11 14500 bridge
After=network.target

[Service]
ExecStart=/usr/bin/python3 $BASE/cloudy-x11-proxy.py --token-file $BASE/x11-token --listen 127.0.0.1:14500 --upstream 127.0.0.1:14501 --x11-tunnel-socket $BASE/x11-tunnel.sock --display :99
Restart=on-failure
RestartSec=2

[Install]
WantedBy=default.target
UNIT
systemctl --user daemon-reload
systemctl --user enable cloudy-x11-proxy.service 2>/dev/null || true
systemctl --user restart cloudy-x11-proxy.service 2>/dev/null || {
  pkill -f 'cloudy-x11-proxy.py' 2>/dev/null || true
  nohup python3 "$BASE/cloudy-x11-proxy.py" --token-file "$BASE/x11-token" \
    --listen 127.0.0.1:14500 --upstream 127.0.0.1:14501 \
    --x11-tunnel-socket "$BASE/x11-tunnel.sock" --display :99 \
    > "$BASE/x11-proxy.log" 2>&1 &
}

loginctl enable-linger "$USER" 2>/dev/null || sudo loginctl enable-linger "$USER" 2>/dev/null || true
sleep 2
ss -ltn 2>/dev/null | grep -E ':14500' || true
ss -xl 2>/dev/null | grep x11-tunnel || true
echo "Cloudy-X11 native setup v4 complete."
echo "Open Cloudy X11, then run an X11 app in a new Terminal tab."
echo "No cloudy-x11-vm.py command or DISPLAY prefix is needed."
