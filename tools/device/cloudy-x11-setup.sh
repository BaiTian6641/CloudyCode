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
sudo apt-get install -y --no-install-recommends xpra xpra-x11 xpra-html5 xvfb x11-utils xterm x11-apps || {
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

cat > "$BASE/cloudy-x11-sessiond.py" <<'SESSIOND'
#!/usr/bin/env python3
"""Cloudy X11 per-application session manager (guest side).

Each request starts a dedicated xpra seamless session (its own display and TCP
port) plus a token proxy on a forwarded loopback port. The app opens one
Android window per session, so each X application gets its own Googlebook OS
window instead of sharing one desktop.

The proxy script and token file live in --base-dir. All endpoints except the
cookie-free HMAC challenge require the per-install token cookie.
"""
import argparse
import hashlib
import hmac
import json
import os
import socket
import subprocess
import sys
import threading
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

COOKIE = "cloudy_x11"
PRESETS = {
    "firefox": ("Firefox", "firefox"),
    "xterm": ("Terminal", "xterm"),
    "xcalc": ("Calculator", "xcalc"),
    "xeyes": ("X Eyes", "xeyes"),
}
DISPLAY_START = 110
DISPLAY_END = 199
XPRA_PORT_START = 14610
PROXY_PORT_START = 14510
PORT_RANGE = 80


def port_free(port):
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    try:
        s.bind(("127.0.0.1", port))
        return True
    except OSError:
        return False
    finally:
        s.close()


def port_open(port, timeout=0.2):
    try:
        with socket.create_connection(("127.0.0.1", port), timeout=timeout):
            return True
    except OSError:
        return False


def xpra_displays():
    try:
        out = subprocess.run(["xpra", "list"], capture_output=True, text=True,
                             timeout=5).stdout
    except (OSError, subprocess.SubprocessError):
        return set()
    found = set()
    for token in out.split():
        if token.startswith(":") and token[1:].isdigit():
            found.add(int(token[1:]))
    return found


class Sessions:
    def __init__(self, base, token_file, width, height, dpi):
        self.base = base
        self.token_file = token_file
        self.width = width
        self.height = height
        self.dpi = dpi
        self.lock = threading.Lock()
        self.sessions = {}
        self.counter = 0
        self.token = open(token_file).read().strip()
        if not self.token:
            raise SystemExit("empty token file")

    def _display(self):
        used = xpra_displays()
        for n in range(DISPLAY_START, DISPLAY_END + 1):
            if n not in used and not os.path.exists(f"/tmp/.X11-unix/X{n}"):
                return n
        raise RuntimeError("no free display")

    def _port(self, start):
        for p in range(start, start + PORT_RANGE):
            if port_free(p):
                return p
        raise RuntimeError("no free port")

    def start(self, name, command):
        with self.lock:
            display = self._display()
            xpra_port = self._port(XPRA_PORT_START)
            proxy_port = self._port(PROXY_PORT_START)
            xvfb = (f"/usr/bin/Xvfb -screen 0 {self.width}x{self.height}x24")
            cmd = [
                "xpra", "start", f":{display}", "--daemon=yes",
                f"--bind-tcp=127.0.0.1:{xpra_port}", "--html=on",
                "--tcp-auth=none", "--mdns=no", "--notifications=no",
                f"--xvfb={xvfb}", f"--dpi={self.dpi}",
                "--encoding=png", "--video-encoders=none",
                "--quality=100", "--min-quality=100",
                f"--start={command}",
            ]
            try:
                subprocess.run(cmd, check=True, capture_output=True, text=True,
                               timeout=25)
            except (OSError, subprocess.SubprocessError) as e:
                subprocess.run(["xpra", "stop", f":{display}"],
                               capture_output=True, timeout=15)
                raise RuntimeError(f"xpra start failed: {e}")
            deadline = time.time() + 12
            while time.time() < deadline and not port_open(xpra_port):
                time.sleep(0.2)
            if not port_open(xpra_port):
                subprocess.run(["xpra", "stop", f":{display}"],
                               capture_output=True, timeout=15)
                raise RuntimeError("xpra did not listen")
            log_path = os.path.join(self.base, f"x11-session-{display}.log")
            log = open(log_path, "ab")
            proxy = subprocess.Popen(
                [sys.executable, os.path.join(self.base, "cloudy-x11-proxy.py"),
                 "--token-file", self.token_file,
                 "--listen", f"127.0.0.1:{proxy_port}",
                 "--upstream", f"127.0.0.1:{xpra_port}"],
                stdout=log, stderr=log, start_new_session=True)
            deadline = time.time() + 5
            while time.time() < deadline and not port_open(proxy_port):
                time.sleep(0.1)
            if not port_open(proxy_port):
                proxy.terminate()
                subprocess.run(["xpra", "stop", f":{display}"],
                               capture_output=True, timeout=15)
                raise RuntimeError("proxy did not listen")
            self.counter += 1
            sid = f"s{self.counter}"
            info = {"id": sid, "name": name, "display": f":{display}",
                    "port": proxy_port, "xpra_port": xpra_port, "command": command}
            self.sessions[sid] = info | {"proxy_pid": proxy.pid}
            return {k: info[k] for k in ("id", "name", "display", "port")}

    def stop(self, sid):
        with self.lock:
            info = self.sessions.pop(sid, None)
            if info is None:
                return False
            pid = info["proxy_pid"]
            try:
                os.kill(pid, 15)
            except OSError:
                pass
            try:
                subprocess.run(["xpra", "stop", info["display"]],
                               capture_output=True, timeout=15)
            except (OSError, subprocess.SubprocessError):
                pass
            return True

    def list(self):
        with self.lock:
            return [{k: v for k, v in s.items() if k != "proxy_pid"}
                    for s in self.sessions.values()]


class Handler(BaseHTTPRequestHandler):
    server_version = "cloudy-x11-sessiond"

    def log_message(self, fmt, *args):
        sys.stdout.write("[cloudy-x11-sessiond] " + fmt % args + "\n")
        sys.stdout.flush()

    def _json(self, code, obj):
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(body)
        self.close_connection = True

    def _auth(self):
        raw = self.headers.get("Cookie", "")
        for part in raw.split(";"):
            k, _, v = part.strip().partition("=")
            if k == COOKIE:
                return hmac.compare_digest(v, self.server.sessions.token)
        return False

    def _body(self):
        n = int(self.headers.get("Content-Length") or 0)
        return json.loads(self.rfile.read(n) or b"{}")

    def do_GET(self):
        parsed = urllib.parse.urlparse(self.path)
        if parsed.path == "/__cloudy/ping":
            nonce = urllib.parse.parse_qs(parsed.query).get("nonce", [""])[0]
            sig = hmac.new(self.server.sessions.token.encode(), nonce.encode(),
                           hashlib.sha256).hexdigest()
            body = sig.encode()
            self.send_response(200)
            self.send_header("Content-Type", "text/plain")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Connection", "close")
            self.end_headers()
            self.wfile.write(body)
            self.close_connection = True
            return
        if not self._auth():
            return self._json(403, {"error": "forbidden"})
        if parsed.path == "/list":
            return self._json(200, {"sessions": self.server.sessions.list()})
        return self._json(404, {"error": "not found"})

    def do_POST(self):
        if not self._auth():
            return self._json(403, {"error": "forbidden"})
        if self.path != "/start" and self.path != "/stop":
            return self._json(404, {"error": "not found"})
        try:
            req = self._body()
        except (ValueError, json.JSONDecodeError):
            return self._json(400, {"error": "bad json"})
        try:
            if self.path == "/stop":
                ok = self.server.sessions.stop(str(req.get("id", "")))
                return self._json(200 if ok else 404,
                                  {"stopped": ok})
            app = str(req.get("app", ""))
            if app in PRESETS:
                name, command = PRESETS[app]
            elif app == "custom":
                command = str(req.get("command", "")).strip()
                if not command or len(command) > 512:
                    return self._json(400, {"error": "bad command"})
                name = command[:48]
            else:
                return self._json(400, {"error": "unknown app"})
            return self._json(200, self.server.sessions.start(name, command))
        except RuntimeError as e:
            return self._json(500, {"error": str(e)})


class Server(ThreadingHTTPServer):
    daemon_threads = True


def main():
    ap = argparse.ArgumentParser(description="Cloudy X11 session manager")
    ap.add_argument("--base-dir", required=True)
    ap.add_argument("--token-file", required=True)
    ap.add_argument("--listen", default="127.0.0.1:14509")
    ap.add_argument("--width", type=int, default=2880)
    ap.add_argument("--height", type=int, default=1800)
    ap.add_argument("--dpi", type=int, default=144)
    args = ap.parse_args()
    host, port = args.listen.rsplit(":", 1)
    sessions = Sessions(args.base_dir, args.token_file, args.width,
                        args.height, args.dpi)
    srv = Server((host, int(port)), Handler)
    srv.sessions = sessions
    print(f"[cloudy-x11-sessiond] listening {args.listen}", flush=True)
    srv.serve_forever()


if __name__ == "__main__":
    main()
SESSIOND
chmod +x "$BASE/cloudy-x11-sessiond.py"

cat > "$HOME/.config/systemd/user/cloudy-x11.service" <<'UNIT'
[Unit]
Description=Cloudy X11 seamless server (xpra, loopback)
After=network.target

[Service]
ExecStart=/usr/bin/xpra start :100 --daemon=no --bind-tcp=127.0.0.1:14501 --html=on --tcp-auth=none --mdns=no --notifications=no --xvfb="/usr/bin/Xvfb -screen 0 2880x1800x24" --dpi=144 --encoding=png --video-encoders=none --quality=100 --min-quality=100
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

cat > "$HOME/.config/systemd/user/cloudy-x11-sessiond.service" <<UNIT
[Unit]
Description=Cloudy X11 per-app session manager
After=cloudy-x11.service

[Service]
ExecStart=/usr/bin/python3 $BASE/cloudy-x11-sessiond.py --base-dir $BASE --token-file $BASE/x11-token --listen 127.0.0.1:14509
Restart=on-failure
RestartSec=2

[Install]
WantedBy=default.target
UNIT

pkill -f 'cloudy-x11-proxy.py' 2>/dev/null || true
systemctl --user daemon-reload
systemctl --user enable cloudy-x11.service cloudy-x11-proxy.service cloudy-x11-sessiond.service 2>/dev/null || true
systemctl --user restart cloudy-x11.service cloudy-x11-proxy.service cloudy-x11-sessiond.service \
  || echo "systemd user start failed (will fall back to direct start)"
loginctl enable-linger "$USER" 2>/dev/null || sudo loginctl enable-linger "$USER" 2>/dev/null || true
sleep 2

# Inline fallback: no helper scripts. systemd user units can be unavailable in
# some terminal sessions, so start whichever piece is missing directly.
if ! systemctl --user is-active cloudy-x11.service >/dev/null 2>&1; then
  echo "xpra service inactive; starting it directly"
  xpra stop :100 >/dev/null 2>&1 || true
  xpra start :100 --daemon=yes --bind-tcp=127.0.0.1:14501 --html=on \
    --tcp-auth=none --mdns=no --notifications=no \
    "--xvfb=/usr/bin/Xvfb -screen 0 2880x1800x24" --dpi=144 \
    --encoding=png --video-encoders=none --quality=100 --min-quality=100 || true
  sleep 2
fi
if ! ss -ltn 2>/dev/null | grep -q ':14509'; then
  echo "session manager not listening; starting it directly"
  nohup python3 "$BASE/cloudy-x11-sessiond.py" --base-dir "$BASE" \
    --token-file "$BASE/x11-token" --listen 127.0.0.1:14509 \
    >"$BASE/x11-sessiond.log" 2>&1 &
  sleep 1
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
systemctl --user --no-pager status cloudy-x11-sessiond.service 2>/dev/null | head -6 || true
echo "xpra sessions:"; xpra list 2>/dev/null || true
echo "listeners:"; ss -ltn 2>/dev/null | grep -E ':14500|:14501|:14509' || true
if ! ss -ltn 2>/dev/null | grep -q ':14500'; then
  echo "== proxy did not start: diagnostics =="
  systemctl --user --no-pager status cloudy-x11-proxy.service 2>/dev/null | head -12 || true
  journalctl --user -u cloudy-x11-proxy -n 20 --no-pager 2>/dev/null || true
  tail -20 "$BASE/x11-proxy.log" 2>/dev/null || true
fi
echo
echo "Open the Cloudy X11 launcher and pick an application; each opens in its"
echo "own Googlebook window. The full desktop session is still DISPLAY=:100."
echo "If nothing appears: journalctl --user -u cloudy-x11 -u cloudy-x11-proxy -u cloudy-x11-sessiond -n 40 --no-pager"
