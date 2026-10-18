#!/usr/bin/env bash
# Cloudy-X11 VM setup v2 (2026-10-18).
# Native target: persistent display proxy :99 -> Unix tunnel -> 14500 bridge.
# Install this once to ~/.local/share/cloudy/cloudy-x11-setup.sh. The legacy
# xpra HTML5 carrier remains installable here but is not the target path.
set -euo pipefail

TOKEN=""
while [ $# -gt 0 ]; do
  case "$1" in
    --token) TOKEN="${2:-}"; shift 2;;
    *) echo "unknown argument: $1" >&2; exit 2;;
  esac
done
[ -n "$TOKEN" ] || { echo "usage: cloudy-x11-setup.sh --token <token>" >&2; exit 2; }

echo "== Cloudy-X11 VM setup v2 (native :99 + tunnel) =="
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
sudo apt-get install -y --no-install-recommends xpra xpra-x11 xpra-html5 xvfb x11-utils xterm x11-apps xauth xdotool || {
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
PY
chmod +x "$BASE/cloudy-x11-proxy.py"

cat > "$BASE/cloudy_x11_probe.py" <<'CLOUDY_X11_PROBE_PY'
#!/usr/bin/env python3
"""Short-lived, authenticated binary-echo endpoint for gate G1.

This is deliberately *not* the X11 transport. It exercises the exact path a
native Android client uses to reach the VM through the Terminal-forwarded
control port: HTTP/1.1 Upgrade, a session cookie, a short-lived probe token, and
full-duplex binary framing. It has no X server and no display cookie.

Wire format after the 101 response:

    uint32 big-endian length, then payload bytes (0 .. 1 MiB)
    server echoes the same length+payload

An over-limit length is answered with 0xffffffff and the connection closes.
The probe token lives in ``x11-probe-token`` beside this module; absent or
wrong values fail closed. Remove that file and the diagnostic route can remain
dormant.
"""

import hmac
import os
import struct

MAX_PAYLOAD = 1024 * 1024
OVER_LIMIT = 0xFFFFFFFF
_TOKEN_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                           "x11-probe-token")


def _expected_token():
    try:
        with open(_TOKEN_FILE, "rb") as f:
            return f.read().strip()
    except OSError:
        return b""


def handle(handler):
    expected = _expected_token()
    supplied = handler.headers.get("X-Cloudy-Probe", "").encode("utf-8", "replace")
    if not expected or not hmac.compare_digest(expected, supplied):
        handler.send_error(403, "probe forbidden")
        return
    if handler.headers.get("Upgrade", "").lower() != "cloudy-probe":
        handler.send_error(400, "Upgrade: cloudy-probe required")
        return

    handler.send_response(101, "Switching Protocols")
    handler.send_header("Upgrade", "cloudy-probe")
    handler.send_header("Connection", "Upgrade")
    handler.end_headers()
    handler.close_connection = True
    try:
        handler.wfile.flush()
    except OSError:
        return

    try:
        while True:
            header = handler.rfile.read(4)
            if len(header) != 4:
                return
            length = struct.unpack("!I", header)[0]
            if length > MAX_PAYLOAD:
                handler.wfile.write(struct.pack("!I", OVER_LIMIT))
                handler.wfile.flush()
                return
            payload = handler.rfile.read(length)
            if len(payload) != length:
                return
            handler.wfile.write(struct.pack("!I", length) + payload)
            handler.wfile.flush()
    except (BrokenPipeError, ConnectionResetError, OSError):
        return
CLOUDY_X11_PROBE_PY
chmod +x "$BASE/cloudy_x11_probe.py"

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
import signal
import subprocess
import sys
import threading
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

COOKIE = "cloudy_x11"
PRESETS = {
    "firefox": ("Firefox", "/usr/bin/firefox-esr", "firefox|Navigator"),
    "xterm": ("Terminal", "/usr/bin/xterm -fa Monospace -fs 13", "XTerm|xterm"),
    "xcalc": ("Calculator", "/usr/bin/xcalc", "xcalc"),
    "xeyes": ("X Eyes", "/usr/bin/xeyes", "xeyes"),
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
    def __init__(self, base, token_file, width, height, dpi,
                 base_display=":100", base_port=14500):
        self.base = base
        self.token_file = token_file
        self.width = width
        self.height = height
        self.dpi = dpi
        self.base_display = base_display
        self.base_port = base_port
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

    def _xd(self, *args):
        env = os.environ.copy()
        env["DISPLAY"] = self.base_display
        return subprocess.run(["/usr/bin/xdotool", *args], env=env,
                              capture_output=True, text=True, timeout=5)

    def windows(self):
        """Top-level windows on the shared display (apps launched in the VM)."""
        try:
            found = self._xd("search", "--onlyvisible", "--maxdepth", "1",
                             "--name", ".*").stdout.split()
        except (OSError, subprocess.SubprocessError):
            return []
        result = []
        for wid in found:
            try:
                geo = self._xd("getwindowgeometry", "--shell", wid)
                vals = {}
                for line in geo.stdout.splitlines():
                    k, _, v = line.partition("=")
                    vals[k] = v
                x = int(vals.get("X", 0))
                y = int(vals.get("Y", 0))
                w = int(vals.get("WIDTH", 0))
                h = int(vals.get("HEIGHT", 0))
                if w < 2 or h < 2:
                    continue
                title = self._xd("getwindowname", wid).stdout.strip()
                cls = self._xd("getwindowclassname", wid).stdout.strip()
                if cls in ("xpra",):
                    continue
                result.append({"id": int(wid), "title": title, "class": cls,
                               "x": x, "y": y, "w": w, "h": h})
            except (ValueError, OSError, subprocess.SubprocessError):
                continue
        return result

    def close_window(self, wid):
        self._xd("windowclose", str(wid))

    def resize_window(self, wid, w, h):
        self._xd("windowsize", str(wid), str(w), str(h))
        self._xd("windowmove", str(wid), "0", "0")

    def start(self, name, command, cls=None, width=None, height=None):
        with self.lock:
            display = self._display()
            xpra_port = self._port(XPRA_PORT_START)
            proxy_port = self._port(PROXY_PORT_START)
            w = max(320, min(int(width or self.width), 8192))
            h = max(240, min(int(height or self.height), 8192))
            xvfb = (f"/usr/bin/Xvfb -screen 0 {w}x{h}x24")
            # Single-app seamless session: the Xvfb screen is the requested
            # Android window size. _fit_window moves/sizes the app's top-level
            # to the screen with xdotool; --resize-display makes the virtual
            # screen follow the HTML5 client's physical size. No window manager
            # is started.
            wrapper = os.path.join(self.base, f"x11-app-{display}.sh")
            with open(wrapper, "w") as f:
                f.write("#!/bin/sh\n")
                f.write(f"exec {command}\n")
            os.chmod(wrapper, 0o700)
            cmd = [
                "xpra", "start", f":{display}", "--daemon=yes",
                f"--bind-tcp=127.0.0.1:{xpra_port}", "--html=on",
                "--tcp-auth=none", "--mdns=no", "--notifications=no",
                f"--xvfb={xvfb}", f"--dpi={self.dpi}",
                "--encoding=png", "--video-encoders=none",
                "--quality=100", "--min-quality=100",
                "--resize-display=yes",
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
            app_env = os.environ.copy()
            app_env["DISPLAY"] = f":{display}"
            app_log_path = os.path.join(self.base, f"x11-app-{display}.log")
            app_log = open(app_log_path, "ab")
            app = subprocess.Popen(["/bin/sh", wrapper], env=app_env,
                                   stdout=app_log, stderr=app_log,
                                   start_new_session=True)
            fit_stop = threading.Event()
            threading.Thread(target=self._fit_window,
                             args=(display, cls, app.pid, fit_stop),
                             daemon=True).start()
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
                    "port": proxy_port, "xpra_port": xpra_port,
                    "width": w, "height": h, "command": command,
                    "wrapper": wrapper, "app_pid": app.pid,
                    "fit_stop": fit_stop}
            self.sessions[sid] = info | {"proxy_pid": proxy.pid}
            return {k: info[k] for k in ("id", "name", "display", "port")}

    def _fit_window(self, display, cls, pid, stop):
        """Keep the app's main window exactly on the virtual screen.

        The window is resized from the daemon (not a shell watcher) so the
        result is logged and survives shell quoting differences.
        """
        env = os.environ.copy()
        env["DISPLAY"] = f":{display}"
        log_path = os.path.join(self.base, f"x11-watch-{display}.log")
        log = open(log_path, "a", buffering=1)
        log.write(f"[fit] start display=:{display} cls={cls!r} pid={pid}\n")
        last = None
        while not stop.is_set():
            try:
                geom = subprocess.run(
                    ["/usr/bin/xdotool", "getdisplaygeometry"],
                    env=env, capture_output=True, text=True, timeout=3)
                if geom.returncode != 0:
                    raise RuntimeError(geom.stderr.strip() or "getdisplaygeometry")
                w, h = map(int, geom.stdout.split())
                cmd = ["/usr/bin/xdotool", "search", "--onlyvisible", "--maxdepth", "1"]
                if cls:
                    cmd += ["--class", cls]
                else:
                    cmd += ["--pid", str(pid)]
                found = subprocess.run(cmd, env=env, capture_output=True,
                                       text=True, timeout=3)
                best, best_area = "", 0
                for wid in found.stdout.split():
                    geo = subprocess.run(
                        ["/usr/bin/xdotool", "getwindowgeometry", "--shell", wid],
                        env=env, capture_output=True, text=True, timeout=3)
                    vals = {}
                    for line in geo.stdout.splitlines():
                        k, _, v = line.partition("=")
                        vals[k] = v
                    try:
                        area = int(vals.get("WIDTH", 0)) * int(vals.get("HEIGHT", 0))
                    except ValueError:
                        area = 0
                    if area > best_area:
                        best, best_area = wid, area
                if best:
                    subprocess.run(["/usr/bin/xdotool", "windowmove", best, "0", "0"],
                                   env=env, timeout=3)
                    subprocess.run(["/usr/bin/xdotool", "windowsize", best, str(w), str(h)],
                                   env=env, timeout=3)
                    subprocess.run(["/usr/bin/xdotool", "windowstate", "--add",
                                    "MAXIMIZED_VERT", "--add", "MAXIMIZED_HORZ", best],
                                   env=env, timeout=3)
                    if last != (w, h):
                        log.write(f"[fit] window {best} ({best_area}px) -> {w}x{h}\n")
                        last = (w, h)
            except Exception as e:
                log.write(f"[fit] error: {e}\n")
            stop.wait(0.5)

    def stop(self, sid):
        with self.lock:
            info = self.sessions.pop(sid, None)
            if info is None:
                return False
            info.get("fit_stop").set() if info.get("fit_stop") else None
            pid = info["proxy_pid"]
            try:
                os.kill(pid, 15)
            except OSError:
                pass
            try:
                os.killpg(os.getpgid(info["app_pid"]), signal.SIGTERM)
            except OSError:
                pass
            try:
                subprocess.run(["xpra", "stop", info["display"]],
                               capture_output=True, timeout=15)
            except (OSError, subprocess.SubprocessError):
                pass
            try:
                os.unlink(info.get("wrapper", ""))
            except OSError:
                pass
            return True

    def list(self):
        fields = ("id", "name", "display", "port", "xpra_port", "width", "height")
        with self.lock:
            return [{k: s[k] for k in fields if k in s}
                    for s in self.sessions.values()]


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
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
        if parsed.path == "/__cloudy/probe":
            # G1 diagnostic only: binary echo after this session token check
            # and the short-lived x11-probe-token check inside the module.
            import cloudy_x11_probe
            return cloudy_x11_probe.handle(self)
        if parsed.path == "/list":
            return self._json(200, {"sessions": self.server.sessions.list()})
        if parsed.path == "/windows":
            return self._json(200, {
                "windows": self.server.sessions.windows(),
                "proxy": self.server.sessions.base_port,
            })
        return self._json(404, {"error": "not found"})

    def do_POST(self):
        if not self._auth():
            return self._json(403, {"error": "forbidden"})
        if self.path not in ("/start", "/stop", "/close", "/resize"):
            return self._json(404, {"error": "not found"})
        try:
            req = self._body()
        except (ValueError, json.JSONDecodeError):
            return self._json(400, {"error": "bad json"})
        try:
            if self.path == "/close":
                self.server.sessions.close_window(int(req.get("id", 0)))
                return self._json(200, {"closed": True})
            if self.path == "/resize":
                self.server.sessions.resize_window(
                    int(req.get("id", 0)), int(req.get("w", 0)),
                    int(req.get("h", 0)))
                return self._json(200, {"resized": True})
            if self.path == "/stop":
                ok = self.server.sessions.stop(str(req.get("id", "")))
                return self._json(200 if ok else 404,
                                  {"stopped": ok})
            app = str(req.get("app", ""))
            if app in PRESETS:
                name, command, cls = PRESETS[app]
            elif app == "custom":
                command = str(req.get("command", "")).strip()
                if not command or len(command) > 512:
                    return self._json(400, {"error": "bad command"})
                name = command[:48]
                cls = None
            else:
                return self._json(400, {"error": "unknown app"})
            return self._json(200, self.server.sessions.start(
                name, command, cls, req.get("width"), req.get("height")))
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
    ap.add_argument("--base-display", default=":100")
    ap.add_argument("--base-port", type=int, default=14500)
    args = ap.parse_args()
    host, port = args.listen.rsplit(":", 1)
    sessions = Sessions(args.base_dir, args.token_file, args.width,
                        args.height, args.dpi, args.base_display, args.base_port)
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
ExecStart=/usr/bin/xpra start :100 --daemon=no --bind-tcp=127.0.0.1:14501 --html=on --sharing=yes --tcp-auth=none --mdns=no --notifications=no --xvfb="/usr/bin/Xvfb -screen 0 2880x1800x24" --dpi=144 --encoding=png --video-encoders=none --quality=100 --min-quality=100
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

# Apps launched from the Terminal connect to the shared Cloudy X display.
# Terminal wrappers: an app command starts a per-app Cloudy session; the app
# polls /list and opens the Googlebook OS window.
cat > "$BASE/cloudy-firefox" <<'SH'
#!/bin/sh
TOKEN=$(cat "$HOME/.local/share/cloudy/x11-token")
exec curl -fsS -H "Cookie: cloudy_x11=$TOKEN" -H "Content-Type: application/json" \
  -d '{"app":"firefox"}' http://127.0.0.1:14509/start >/dev/null
SH
chmod +x "$BASE/cloudy-firefox"
cat > "$BASE/cloudy-run" <<'SH'
#!/bin/sh
TOKEN=$(cat "$HOME/.local/share/cloudy/x11-token")
exec curl -fsS -H "Cookie: cloudy_x11=$TOKEN" -H "Content-Type: application/json" \
  -d "{\"app\":\"custom\",\"command\":\"$*\"}" http://127.0.0.1:14509/start >/dev/null
SH
chmod +x "$BASE/cloudy-run"
BASHRC="$HOME/.bashrc"
touch "$BASHRC"
grep -q 'export DISPLAY=:100' "$BASHRC" 2>/dev/null || echo 'export DISPLAY=:100' >> "$BASHRC"
sed -i '/alias firefox=/d' "$BASHRC" 2>/dev/null || true
echo "alias firefox='$BASE/cloudy-firefox'" >> "$BASHRC"
grep -q 'alias cloudy-run=' "$BASHRC" 2>/dev/null || echo "alias cloudy-run='$BASE/cloudy-run'" >> "$BASHRC"

# --- VM-wide X11 forwarding: display :99, VM proxy and app tunnel ---
# The app connects to 127.0.0.1:14511 through the Terminal's forwarded port.
# X11 authentication stays between the VM client and the app's embedded X
# server; the proxy forwards the protocol transparently.
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
cat > "$BASE/cloudy-x11-tunnel-test.py" <<'CLOUDY_X11_TUNNEL_TEST'
#!/usr/bin/env python3
"""App-side Cloudy-X11 tunnel test harness / protocol reference.

Connects to the VM proxy, authenticates with the per-install token, and forwards
every opened stream to a local upstream X server. In the product this role is
served by the embedded X server service; this harness proves the VM proxy and
the framing without Android.
"""
import argparse
import socket
import struct
import threading

CHUNK = 65536
MAX_FRAME = 1 << 20


def parse_addr(value):
    if value.startswith("unix:"):
        return ("unix", value[5:])
    if value.startswith("tcp:"):
        host, _, port = value[4:].rpartition(":")
        return ("tcp", (host, int(port)))
    raise ValueError(value)


def connect(spec, timeout=5):
    kind, addr = spec
    s = socket.socket(socket.AF_UNIX if kind == "unix" else socket.AF_INET,
                      socket.SOCK_STREAM)
    s.settimeout(timeout)
    s.connect(addr)
    s.settimeout(None)
    return s


def read_line(sock, limit=4096):
    data = b""
    while len(data) < limit:
        ch = sock.recv(1)
        if not ch:
            break
        data += ch
        if ch == b"\n":
            break
    return data


def read_headers(sock, limit=8192):
    data = b""
    while len(data) < limit and not data.endswith(b"\r\n\r\n"):
        ch = sock.recv(1)
        if not ch:
            raise EOFError("EOF in proxy headers")
        data += ch
    return data


def recv_exact(sock, n):
    buf = bytearray()
    while len(buf) < n:
        part = sock.recv(n - len(buf))
        if not part:
            raise EOFError
        buf += part
    return bytes(buf)


class Bridge:
    def __init__(self, args):
        self.connect_addr = parse_addr(args.connect)
        self.upstream = parse_addr(args.upstream)
        self.token = open(args.token_file).read().strip()
        self.via_proxy = args.via_proxy
        self.app = None
        self.lock = threading.Lock()
        self.streams = {}

    def send(self, frame):
        with self.lock:
            app = self.app
        if app:
            try:
                app.sendall(frame)
            except OSError:
                pass

    def upstream_reader(self, sid, upstream):
        try:
            while True:
                data = upstream.recv(CHUNK)
                if not data:
                    break
                self.send(b"D" + struct.pack("!I", sid) + struct.pack("!I", len(data)) + data)
        except OSError:
            pass
        finally:
            self.send(b"C" + struct.pack("!I", sid))
            try:
                upstream.close()
            except OSError:
                pass

    def run(self):
        app = connect(self.connect_addr)
        if self.via_proxy:
            host, port = self.connect_addr[1]
            request = (f"GET /x11-tunnel HTTP/1.1\r\n"
                       f"Host: {host}:{port}\r\n"
                       f"Cookie: cloudy_x11={self.token}\r\n"
                       "Upgrade: cloudy-x11\r\n"
                       "Connection: Upgrade\r\n\r\n")
            app.sendall(request.encode())
            headers = read_headers(app)
            first = headers.split(b"\r\n", 1)[0].decode("latin1", "replace")
            if not first.startswith("HTTP/1.1 101"):
                raise SystemExit(f"proxy upgrade failed: {first!r}")
            config = read_line(app).decode("utf-8", "replace").strip()
            if not config.startswith("CLOUDY-X11-CONFIG 1 "):
                raise SystemExit(f"missing bridge config: {config[:80]!r}")
            import json
            cfg = json.loads(config.split(" ", 2)[2])
            print("proxy connected display=%s generation=%s limits=%s/%s/%s"
                  % (cfg.get("display"), cfg.get("generation"),
                     cfg.get("max_frame"), cfg.get("max_streams"),
                     cfg.get("max_queued_bytes")), flush=True)
        else:
            app.sendall(f"CLOUDY-X11 {self.token}\n".encode())
            line = b""
            while not line.endswith(b"\n"):
                part = app.recv(1)
                if not part:
                    raise SystemExit("tunnel closed during handshake")
                line += part
            if not line.startswith(b"OK"):
                raise SystemExit(f"tunnel auth failed: {line!r}")
        self.app = app
        print("tunnel connected", flush=True)
        try:
            while True:
                kind = recv_exact(app, 1)
                sid = struct.unpack("!I", recv_exact(app, 4))[0]
                if kind == b"O":
                    up = connect(self.upstream)
                    self.streams[sid] = up
                    threading.Thread(target=self.upstream_reader, args=(sid, up), daemon=True).start()
                elif kind == b"D":
                    size = struct.unpack("!I", recv_exact(app, 4))[0]
                    if size > MAX_FRAME:
                        raise SystemExit("oversized frame")
                    data = recv_exact(app, size)
                    up = self.streams.get(sid)
                    if up:
                        up.sendall(data)
                elif kind == b"C":
                    up = self.streams.pop(sid, None)
                    if up:
                        up.close()
                else:
                    raise SystemExit(f"bad frame {kind!r}")
        except (EOFError, OSError):
            pass
        finally:
            for up in self.streams.values():
                try:
                    up.close()
                except OSError:
                    pass
            app.close()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--connect", required=True, help="tcp:127.0.0.1:14511")
    ap.add_argument("--token-file", required=True)
    ap.add_argument("--upstream", required=True, help="unix:/tmp/.X11-unix/X98")
    ap.add_argument("--via-proxy", action="store_true",
                    help="upgrade through the 14500 bridge instead of the Unix tunnel")
    Bridge(ap.parse_args()).run()


if __name__ == "__main__":
    main()
CLOUDY_X11_TUNNEL_TEST
chmod +x "$BASE/cloudy-x11-tunnel-test.py"

# VM-wide DISPLAY=:99/XAUTHORITY (profile, environment.d, /etc/profile.d) and
# the systemd user unit for the display proxy.
"$BASE/cloudy-x11-install-env.sh" --base "$BASE" --display :99 --system \
  || echo "VM-wide environment install reported an error" >&2

# The Cloudy Code backend is the launch point for its integrated terminals,
# tasks, extension hosts, and extension-host GUI children. A user-service
# drop-in makes the display environment apply before those children exist.
BACKEND_DROPIN="$HOME/.config/systemd/user/cloudy-backend.service.d"
mkdir -p "$BACKEND_DROPIN"
cat > "$BACKEND_DROPIN/90-cloudy-x11.conf" <<EOF
[Service]
Environment=DISPLAY=:99
Environment=XAUTHORITY=$BASE/Xauthority
EOF
systemctl --user daemon-reload 2>/dev/null || true
if systemctl --user is-active cloudy-backend.service >/dev/null 2>&1; then
  systemctl --user restart cloudy-backend.service 2>/dev/null \
    || echo "Cloudy Code backend restart failed; restart it manually" >&2
  echo "Cloudy Code backend restarted with DISPLAY=:99"
else
  echo "cloudy-backend.service is not active; it will inherit DISPLAY=:99 on start"
fi
systemctl --user daemon-reload 2>/dev/null || true
systemctl --user enable cloudy-x11-vm.service 2>/dev/null || true
systemctl --user restart cloudy-x11-vm.service 2>/dev/null || {
  pkill -f 'cloudy-x11-vm.py' 2>/dev/null || true
  nohup python3 "$BASE/cloudy-x11-vm.py" --display :99 --tunnel-listen unix:$BASE/x11-tunnel.sock \
    --token-file "$BASE/x11-token" --cookie-file "$BASE/x11-cookie" >"$BASE/x11-vm.log" 2>&1 &
}

echo "VM-wide X11 display: DISPLAY=:99 XAUTHORITY=$BASE/Xauthority"
echo "Cloudy-X11 VM tunnel: $BASE/x11-tunnel.sock (bridged via the 14500 proxy for the app)"
echo "Restart the Cloudy Code backend / open a new shell to inherit DISPLAY=:99."
