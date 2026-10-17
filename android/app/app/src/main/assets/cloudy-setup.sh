#!/usr/bin/env bash
# Cloudy Code guest setup (one-time): serial bridge, USB/IP relay, and the
# backend, installed together as systemd user services with auto-start.
#
#   bash cloudy-setup.sh --token <app token> [--backend auto|reh-web|code-server|none]
#                        [--with-usb]  (deferred USB forwarding experiment)
#                        [--dry-run]
#
# The token is the app's connection token (copied by "Copy setup command").
# Run it inside the Terminal app; afterwards every VM start brings the
# services up. The Terminal app must stay open for the port forwards.
set -euo pipefail

TOKEN=""; BACKEND="auto"; DRY=0; WITH_USB=0
while [ $# -gt 0 ]; do
  case "$1" in
    --token) TOKEN="${2:-}"; shift 2;;
    --backend) BACKEND="${2:-}"; shift 2;;
    --dry-run) DRY=1; shift;;
    --with-usb) WITH_USB=1; shift;;
    *) echo "unknown argument: $1" >&2; exit 2;;
  esac
done
[ -n "$TOKEN" ] || { echo "usage: cloudy-setup.sh --token <token>" >&2; exit 2; }

BASE="${HOME}/.local/share/cloudy"
UNITS="${HOME}/.config/systemd/user"
mkdir -p "$BASE/data" "$BASE/extensions" "$UNITS"
(umask 077; printf '%s' "$TOKEN" > "$BASE/token")

if [ "$DRY" = 0 ]; then
  sudo -v
  sudo apt-get update
  sudo apt-get install -y --no-install-recommends python3 ca-certificates curl kmod usbutils
fi

cat > "$BASE/cloudy-serial-bridge.py" <<'PY'
#!/usr/bin/env python3
"""Cloudy serial bridge (guest side).

The APK owns the USB device (ESP32-S3 CDC). This bridge listens for the app on
the Terminal-forwarded port, authenticates it with the app's one-time token,
and exposes the stream locally as an RFC 2217 (telnet serial) server so
esptool / PlatformIO / pyserial can use:

    rfc2217://127.0.0.1:7002

Wire protocol app<->bridge: [type:1][length:2 big-endian][payload]
  0x01 data app -> guest      0x02 data guest -> app
  0x03 control bitmap (bit0 DTR, bit1 RTS)
  0x04 keepalive (ignored)

Only Python's standard library is used.
"""
import argparse
import selectors
import socket
import sys

IAC = 255
DONT = 254
DO = 253
WONT = 252
WILL = 251
SB = 250
SE = 240
OPT_BINARY = 0
OPT_COM_PORT = 44
SET_BAUDRATE = 1
SET_CONTROL = 5

F_APP_DATA = 0x01
F_GUEST_DATA = 0x02
F_CONTROL = 0x03
F_PING = 0x04


class Framed:
    """Parse [type][len][payload] from a bytearray buffer."""

    def __init__(self):
        self.buf = bytearray()

    def feed(self, data):
        self.buf.extend(data)
        out = []
        while len(self.buf) >= 3:
            typ = self.buf[0]
            n = (self.buf[1] << 8) | self.buf[2]
            if len(self.buf) < 3 + n:
                break
            payload = bytes(self.buf[3:3 + n])
            del self.buf[:3 + n]
            out.append((typ, payload))
        return out


def frame(typ, payload=b""):
    return bytes([typ]) + len(payload).to_bytes(2, "big") + payload


class Telnet:
    """Incremental telnet IAC parser for the RFC2217 client direction."""

    def __init__(self):
        self.state = 0  # 0 data, 1 IAC, 2 option, 3 SB cmd, 4 SB data
        self.cmd = None
        self.sb_cmd = None
        self.sb = bytearray()

    def feed(self, data):
        """Return (serial_bytes, control_bitmap_or_None, negotiation_replies)."""
        out = bytearray()
        control = None
        replies = bytearray()
        for b in data:
            if self.state == 0:
                if b == IAC:
                    self.state = 1
                else:
                    out.append(b)
            elif self.state == 1:
                if b == IAC:
                    out.append(IAC)
                    self.state = 0
                elif b in (DO, DONT, WILL, WONT):
                    self.cmd = b
                    self.state = 2
                elif b == SB:
                    self.state = 3
                    self.sb = bytearray()
                else:
                    self.state = 0
            elif self.state == 2:
                # Telnet negotiation: accept BINARY and COM-PORT in both
                # directions; pyserial waits for the server's DO/WILL replies.
                opt = b
                if self.cmd == WILL and opt in (OPT_BINARY, OPT_COM_PORT):
                    replies += bytes([IAC, DO, opt])
                elif self.cmd == DO and opt in (OPT_BINARY, OPT_COM_PORT):
                    replies += bytes([IAC, WILL, opt])
                self.state = 0
            elif self.state == 3:
                # option byte after IAC SB (COM-PORT = 44)
                self.state = 4
            elif self.state == 4:
                if b == IAC:
                    self.state = 5  # expect SE (empty subnegotiation)
                else:
                    self.sb_cmd = b
                    self.state = 6
            elif self.state == 6:
                if b == IAC:
                    self.state = 5
                else:
                    self.sb.append(b)
            elif self.state == 5:
                if b == SE:
                    cmd = self.sb_cmd
                    if cmd == SET_CONTROL and self.sb:
                        control = self.sb[0]
                        # Acknowledge the control change (SERVER_SET_CONTROL).
                        replies += (bytes([IAC, SB, OPT_COM_PORT, SET_CONTROL + 100])
                                    + bytes(self.sb) + bytes([IAC, SE]))
                    elif cmd in (1, 2, 3, 4, 12):
                        # RFC 2217: acknowledge settings with the server
                        # response code (client command + 100).
                        replies += (bytes([IAC, SB, OPT_COM_PORT, cmd + 100])
                                    + bytes(self.sb) + bytes([IAC, SE]))
                    self.sb_cmd = None
                    self.sb = bytearray()
                self.state = 0
        return bytes(out), control, bytes(replies)


def escape_iac(data):
    return data.replace(b"\xff", b"\xff\xff")


class Bridge:
    def __init__(self, token, back_port, local_port):
        self.token = token
        self.back_port = back_port
        self.local_port = local_port
        self.sel = selectors.DefaultSelector()
        self.app = None
        self.app_frames = Framed()
        self.app_handshook = False
        self.app_buf = bytearray()
        self.client = None
        self.telnet = Telnet()
        self.back_listener = self._listen("0.0.0.0", back_port)
        self.client_listener = self._listen("127.0.0.1", local_port)
        self.sel.register(self.back_listener, selectors.EVENT_READ, "back")
        self.sel.register(self.client_listener, selectors.EVENT_READ, "client_listen")

    @staticmethod
    def _listen(host, port):
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        s.bind((host, port))
        s.listen(1)
        s.setblocking(False)
        return s

    def log(self, msg):
        print(f"[cloudy-serial] {msg}", flush=True)

    def run(self):
        self.log(f"waiting for the app on 0.0.0.0:{self.back_port}; "
                 f"local RFC2217 on 127.0.0.1:{self.local_port}")
        while True:
            for key, _ in self.sel.select():
                tag = key.data
                if tag == "back":
                    self._accept_app()
                elif tag == "client_listen":
                    self._accept_client()
                elif tag == "app":
                    self._read_app()
                elif tag == "client":
                    self._read_client()

    def _accept_app(self):
        try:
            sock, addr = self.back_listener.accept()
        except OSError:
            return
        if self.app is not None:
            try:
                self.app.close()
            except OSError:
                pass
            self.sel.unregister(self.app)
        sock.setblocking(False)
        self.app = sock
        self.app_handshook = False
        self.app_buf = bytearray()
        self.sel.register(sock, selectors.EVENT_READ, "app")
        self.log(f"app connected from {addr[0]}:{addr[1]}; waiting for handshake")

    def _accept_client(self):
        try:
            sock, addr = self.client_listener.accept()
        except OSError:
            return
        if self.client is not None:
            try:
                self.client.close()
            except OSError:
                pass
            self.sel.unregister(self.client)
        sock.setblocking(False)
        self.client = sock
        self.telnet = Telnet()
        self.sel.register(sock, selectors.EVENT_READ, "client")
        # Offer RFC 2217 and BINARY (pyserial requires both).
        sock.sendall(bytes([IAC, WILL, OPT_BINARY, IAC, WILL, OPT_COM_PORT]))
        self.log(f"serial client connected from {addr[0]}:{addr[1]}")

    def _app_send(self, typ, payload=b""):
        if self.app is None:
            return
        try:
            self.app.sendall(frame(typ, payload))
        except OSError:
            self._drop_app()

    def _drop_app(self):
        if self.app is not None:
            try:
                self.sel.unregister(self.app)
            except Exception:
                pass
            try:
                self.app.close()
            except OSError:
                pass
        self.app = None
        self.app_handshook = False
        self.log("app disconnected; waiting again")

    def _read_app(self):
        try:
            data = self.app.recv(65536)
        except OSError:
            data = b""
        if not data:
            self._drop_app()
            return
        if not self.app_handshook:
            self.app_buf.extend(data)
            if b"\n" not in self.app_buf:
                return
            line, _, rest = bytes(self.app_buf).partition(b"\n")
            parts = line.decode("utf-8", "replace").strip().split(" ", 2)
            if len(parts) == 3 and parts[0] == "CLOUDY-SERIAL" and parts[2] == self.token:
                self.app_handshook = True
                self.app.sendall(b"OK\n")
                self.log("app authenticated")
                data = rest
            else:
                try:
                    self.app.sendall(b"ERR\n")
                except OSError:
                    pass
                self.log("app rejected: bad token")
                self._drop_app()
                return
        for typ, payload in self.app_frames.feed(data):
            if typ == F_APP_DATA and self.client is not None:
                try:
                    self.client.sendall(escape_iac(payload))
                except OSError:
                    pass
            # F_PING and unknown frames are ignored.

    def _read_client(self):
        try:
            data = self.client.recv(65536)
        except OSError:
            data = b""
        if not data:
            try:
                self.sel.unregister(self.client)
            except Exception:
                pass
            try:
                self.client.close()
            except OSError:
                pass
            self.client = None
            self.log("serial client disconnected")
            return
        serial_bytes, control, replies = self.telnet.feed(data)
        if replies:
            try:
                self.client.sendall(replies)
            except OSError:
                pass
        if serial_bytes:
            self._app_send(F_GUEST_DATA, serial_bytes)
        if control is not None:
            self._app_send(F_CONTROL, bytes([control]))
            self.log(f"control bitmap 0x{control:02x}")


def main():
    ap = argparse.ArgumentParser(description="Cloudy serial bridge (guest side)")
    ap.add_argument("--token", required=True, help="the app's connection token")
    ap.add_argument("--back-port", type=int, default=7001,
                    help="port the app connects to (forwarded by the Terminal app)")
    ap.add_argument("--local-port", type=int, default=7002,
                    help="local RFC2217 port for esptool/PlatformIO/pyserial")
    args = ap.parse_args()
    try:
        Bridge(args.token, args.back_port, args.local_port).run()
    except KeyboardInterrupt:
        sys.exit(0)


if __name__ == "__main__":
    main()

PY

cat > "$BASE/cloudy-usbip-relay.py" <<'PY'
#!/usr/bin/env python3
"""Cloudy USB/IP relay (guest side).

The APK cannot be reached inbound from the VM, so the app connects out to this
relay through the Terminal app's port forward (guest 0.0.0.0:3241 -> Android
127.0.0.1:3241), authenticates with the app token, and multiplexes USB/IP
streams over that connection. Local `usbip` clients connect to
127.0.0.1:3240; each client gets its own stream to the app's USB/IP exporter.

Mux frame: [stream:u32 BE][type:u8][len:u16 BE][payload]
  type 1 = OPEN (relay -> app), 2 = DATA (both), 3 = CLOSE (both)

The app speaks the USB/IP protocol (DEVLIST/IMPORT/URB submit) on each stream.
Requires Python 3 standard library only.
"""
import argparse
import selectors
import socket
import struct
import sys

T_OPEN, T_DATA, T_CLOSE = 1, 2, 3


def frame(stream, typ, payload=b""):
    return struct.pack(">IBH", stream, typ, len(payload)) + payload


class Relay:
    def __init__(self, token, app_port=3241, client_port=3240):
        self.token = token
        self.sel = selectors.DefaultSelector()
        self.app = None
        self.app_buf = bytearray()
        self.app_ok = False
        self.next_stream = 1
        self.clients = {}      # stream id -> client socket
        self.app_listener = self._listen("0.0.0.0", app_port)
        self.client_listener = self._listen("127.0.0.1", client_port)
        self.sel.register(self.app_listener, selectors.EVENT_READ, "app_listen")
        self.sel.register(self.client_listener, selectors.EVENT_READ, "client_listen")

    @staticmethod
    def _listen(host, port):
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        s.bind((host, port))
        s.listen(8)
        s.setblocking(False)
        return s

    def log(self, msg):
        print(f"[cloudy-usbip] {msg}", flush=True)

    def run(self):
        self.log(f"waiting for the app on 0.0.0.0:{self.app_listener.getsockname()[1]}; "
                 f"usbip clients on 127.0.0.1:{self.client_listener.getsockname()[1]}")
        while True:
            for key, _ in self.sel.select():
                tag = key.data
                if tag == "app_listen":
                    self._accept_app()
                elif tag == "client_listen":
                    self._accept_client()
                elif tag == "app":
                    self._read_app()
                elif isinstance(tag, tuple) and tag[0] == "client":
                    self._read_client(tag[1])

    def _accept_app(self):
        try:
            sock, addr = self.app_listener.accept()
        except OSError:
            return
        if self.app is not None:
            try:
                self.app.close()
            except OSError:
                pass
            self.sel.unregister(self.app)
        for s in list(self.clients.values()):
            try:
                s.close()
            except OSError:
                pass
        self.clients.clear()
        sock.setblocking(False)
        self.app = sock
        self.app_buf = bytearray()
        self.app_ok = False
        self.sel.register(sock, selectors.EVENT_READ, "app")
        self.log(f"app connected from {addr[0]}:{addr[1]}")

    def _accept_client(self):
        try:
            sock, _ = self.client_listener.accept()
        except OSError:
            return
        if self.app is None or not self.app_ok:
            self.log("client rejected: app not connected/authenticated")
            sock.close()
            return
        sock.setblocking(False)
        sid = self.next_stream
        self.next_stream += 1
        self.clients[sid] = sock
        self.sel.register(sock, selectors.EVENT_READ, ("client", sid))
        self._send_app(frame(sid, T_OPEN))
        self.log(f"usbip client stream {sid} opened")

    def _send_app(self, data):
        if self.app is None:
            return
        try:
            self.app.sendall(data)
        except OSError:
            self._drop_app()

    def _drop_app(self):
        if self.app is not None:
            try:
                self.sel.unregister(self.app)
            except Exception:
                pass
            try:
                self.app.close()
            except OSError:
                pass
        self.app = None
        self.app_ok = False
        for sid, s in list(self.clients.items()):
            try:
                s.close()
            except OSError:
                pass
            try:
                self.sel.unregister(s)
            except Exception:
                pass
        self.clients.clear()
        self.log("app disconnected; waiting for reconnect")

    def _read_app(self):
        try:
            data = self.app.recv(65536)
        except OSError:
            data = b""
        if not data:
            self._drop_app()
            return
        self.log(f"app raw {len(data)}B")
        if not self.app_ok:
            self.app_buf.extend(data)
            if b"\n" not in self.app_buf:
                return
            line, _, rest = bytes(self.app_buf).partition(b"\n")
            parts = line.decode("utf-8", "replace").strip().split(" ", 2)
            if len(parts) == 3 and parts[0] == "CLOUDY-USBIP" and parts[2] == self.token:
                self.app_ok = True
                self.app.sendall(b"OK\n")
                self.log("app authenticated")
                # Drop the handshake bytes; only post-handshake data is mux.
                self.app_buf = bytearray()
                data = rest
            else:
                try:
                    self.app.sendall(b"ERR\n")
                except OSError:
                    pass
                self.log("app rejected: bad token")
                self._drop_app()
                return
        self.app_buf.extend(data)
        while len(self.app_buf) >= 7:
            sid, typ, n = struct.unpack(">IBH", self.app_buf[:7])
            if len(self.app_buf) < 7 + n:
                break
            payload = bytes(self.app_buf[7:7 + n])
            del self.app_buf[:7 + n]
            client = self.clients.get(sid)
            if typ == T_DATA and client is not None:
                self.log(f"stream {sid} app->client {len(payload)}B")
                try:
                    client.sendall(payload)
                except OSError:
                    pass
            elif typ == T_CLOSE and client is not None:
                try:
                    self.sel.unregister(client)
                except Exception:
                    pass
                try:
                    client.close()
                except OSError:
                    pass
                self.clients.pop(sid, None)
                self.log(f"stream {sid} closed")

    def _read_client(self, sid):
        client = self.clients.get(sid)
        if client is None:
            return
        try:
            data = client.recv(65536)
        except OSError:
            data = b""
        if not data:
            try:
                self.sel.unregister(client)
            except Exception:
                pass
            try:
                client.close()
            except OSError:
                pass
            self.clients.pop(sid, None)
            self._send_app(frame(sid, T_CLOSE))
            self.log(f"stream {sid} closed by client")
            return
        self.log(f"stream {sid} client->app {len(data)}B")
        self._send_app(frame(sid, T_DATA, data))


def main():
    ap = argparse.ArgumentParser(description="Cloudy USB/IP relay (guest side)")
    ap.add_argument("--token", required=True, help="the app's connection token")
    ap.add_argument("--app-port", type=int, default=3241,
                    help="port the app connects to (forwarded by the Terminal app)")
    ap.add_argument("--client-port", type=int, default=3240,
                    help="local port for usbip clients")
    args = ap.parse_args()
    try:
        Relay(args.token, args.app_port, args.client_port).run()
    except KeyboardInterrupt:
        sys.exit(0)


if __name__ == "__main__":
    main()

PY

cat > "$BASE/run-serial-bridge.sh" <<'SH'
#!/usr/bin/env bash
exec python3 "$HOME/.local/share/cloudy/cloudy-serial-bridge.py" \
  --token "$(cat "$HOME/.local/share/cloudy/token")"
SH
cat > "$BASE/run-usbip-relay.sh" <<'SH'
#!/usr/bin/env bash
exec python3 "$HOME/.local/share/cloudy/cloudy-usbip-relay.py" \
  --token "$(cat "$HOME/.local/share/cloudy/token")"
SH
chmod +x "$BASE/run-serial-bridge.sh" "$BASE/run-usbip-relay.sh"

write_unit() {
  local name="$1" desc="$2" exec="$3"
  cat > "$UNITS/$name.service" <<UNIT
[Unit]
Description=$desc
After=network.target

[Service]
ExecStart=$exec
Restart=on-failure
RestartSec=2

[Install]
WantedBy=default.target
UNIT
}

write_unit cloudy-serial-bridge "Cloudy serial bridge (USB CDC -> RFC2217)" "$BASE/run-serial-bridge.sh"
write_unit cloudy-usbip-relay "Cloudy USB/IP relay" "$BASE/run-usbip-relay.sh"

[ "$BACKEND" = auto ] && { [ -x "$BASE/reh-web/node" ] && BACKEND=reh-web || BACKEND=code-server; }
BACKEND_STARTED=no
case "$BACKEND" in
  reh-web)
    if [ -x "$BASE/start-backend.sh" ]; then
      write_unit cloudy-backend "Cloudy backend (Code - OSS reh-web)" "$BASE/start-backend.sh"
      BACKEND_STARTED=yes
    else
      echo "reh-web backend not built yet; run the backend build script first" >&2
    fi
    ;;
  code-server)
    CS="$(command -v code-server || true)"
    [ -n "$CS" ] || CS="$HOME/.local/bin/code-server"
    if [ -x "$CS" ]; then
      write_unit cloudy-backend "Cloudy backend (code-server)" \
        "$CS --bind-addr 0.0.0.0:8080 --auth none"
      BACKEND_STARTED=yes
    else
      echo "code-server not found; install code-server or build the Cloudy backend" >&2
    fi
    ;;
  none) ;;
  *) echo "unknown backend: $BACKEND" >&2; exit 2;;
esac

if [ "$WITH_USB" = 1 ]; then
# Root helper: import the USB/IP device and attach it through VHCI. No usbip
# CLI is required (the kernel sysfs interface is used directly).
cat > "$BASE/cloudy-vhci-attach.py" <<'PY'
#!/usr/bin/env python3
"""Cloudy VHCI attach (no usbip CLI required).

Imports the USB/IP device from the local relay (127.0.0.1:3240), then hands
the connected socket to the kernel through the vhci sysfs interface:

    echo "<port> <sockfd> <devid> <speed>" > .../vhci_hcd.0/attach

Run as root (system unit). Requires the vhci_hcd module. Keeps the socket
open for the kernel; when the device detaches (app/relay gone), it re-imports.

Standard library only.
"""
import argparse
import glob
import os
import platform
import shutil
import socket
import struct
import subprocess
import sys
import time

VERSION = 0x0111
OP_REQ_IMPORT = 0x8003
VDEV_ST_NULL = 4
VDEV_ST_NOTASSIGNED = 5
VDEV_ST_USED = 6
BUSID_DEFAULT = "cloudy-303a-1001"


def recvn(sock, n):
    buf = b""
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise EOFError("connection closed")
        buf += chunk
    return buf


def vhci_dir():
    candidates = sorted(glob.glob("/sys/devices/platform/vhci_hcd*"))
    for path in candidates:
        if os.path.exists(os.path.join(path, "attach")):
            return path
    return None


def module_dir():
    release = platform.release()
    return f"/lib/modules/{release}/kernel/drivers/usb/usbip"


def diagnose():
    release = platform.release()
    print(f"kernel: {release}", flush=True)
    print(f"modprobe: {shutil.which('modprobe') or 'MISSING'}", flush=True)
    print(f"insmod: {shutil.which('insmod') or 'MISSING'}", flush=True)
    moddir = module_dir()
    print(f"usbip module dir: {moddir} exists={os.path.isdir(moddir)}", flush=True)
    if os.path.isdir(moddir):
        print("  modules: " + ", ".join(sorted(os.listdir(moddir))), flush=True)
    vdir = vhci_dir()
    print(f"vhci sysfs: {vdir or 'MISSING'}", flush=True)
    if vdir:
        try:
            with open(os.path.join(vdir, "status")) as f:
                print(f.read(), flush=True)
        except OSError as e:
            print(f"status read failed: {e}", flush=True)


def ensure_vhci():
    """Return the vhci sysfs dir, attempting to load the module."""
    vdir = vhci_dir()
    if vdir:
        return vdir
    # 1) modprobe (needs kmod)
    if shutil.which("modprobe"):
        try:
            subprocess.run(["modprobe", "vhci-hcd"], check=False,
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        except Exception:
            pass
        vdir = vhci_dir()
        if vdir:
            return vdir
    # 2) insmod against the distro module file (no kmod needed)
    moddir = module_dir()
    for name in ("vhci-hcd.ko", "vhci_hcd.ko", "vhci-hcd.ko.zst", "vhci_hcd.ko.zst"):
        path = os.path.join(moddir, name)
        if os.path.exists(path) and shutil.which("insmod") and not name.endswith(".zst"):
            try:
                subprocess.run(["insmod", path], check=False,
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            except Exception:
                pass
            vdir = vhci_dir()
            if vdir:
                return vdir
    return vhci_dir()


def free_port(vdir):
    status = os.path.join(vdir, "status")
    try:
        with open(status) as f:
            for line in f:
                parts = line.split()
                if len(parts) >= 6 and parts[0] in ("hs", "ss"):
                    port = int(parts[1])
                    st = int(parts[2])
                    if st == VDEV_ST_NULL:
                        return port
    except OSError:
        return None
    return None


def port_status(vdir, port):
    try:
        with open(os.path.join(vdir, "status")) as f:
            for line in f:
                parts = line.split()
                if len(parts) >= 6 and parts[0] in ("hs", "ss") and int(parts[1]) == port:
                    return int(parts[2])
    except OSError:
        pass
    return None


def import_device(host, tcp_port, busid):
    sock = socket.create_connection((host, tcp_port), timeout=4)
    sock.sendall(struct.pack(">HHI", VERSION, OP_REQ_IMPORT, 0)
                 + busid.encode().ljust(32, b"\0"))
    ver, code, status = struct.unpack(">HHI", recvn(sock, 8))
    if status != 0:
        sock.close()
        raise OSError(f"import rejected (status {status})")
    dev = recvn(sock, 312)
    busnum, devnum, speed = struct.unpack(">III", dev[288:300])
    vid, pid = struct.unpack(">HH", dev[300:304])
    devid = (busnum << 16) | devnum
    return sock, devid, speed, vid, pid


def detach(vdir, port):
    try:
        with open(os.path.join(vdir, "detach"), "w") as f:
            f.write(str(port))
    except OSError:
        pass


def main():
    ap = argparse.ArgumentParser(description="Cloudy VHCI attach")
    ap.add_argument("--server", default="127.0.0.1")
    ap.add_argument("--tcp-port", type=int, default=3240)
    ap.add_argument("--busid", default=BUSID_DEFAULT)
    ap.add_argument("--check", action="store_true",
                    help="import once, print the device and exit (no sysfs)")
    ap.add_argument("--diagnose", action="store_true",
                    help="print kernel/module/vhci diagnostics and exit")
    args = ap.parse_args()

    if args.check:
        sock, devid, speed, vid, pid = import_device(args.server, args.tcp_port, args.busid)
        print(f"imported busid={args.busid} devid=0x{devid:08x} speed={speed} "
              f"vid={vid:04x} pid={pid:04x}")
        sock.close()
        return

    if args.diagnose:
        diagnose()
        return

    vdir = ensure_vhci()
    if not vdir:
        print("vhci_hcd is not available in this kernel; diagnostics:", flush=True)
        diagnose()
        print("retrying every 10s", flush=True)
        while True:
            time.sleep(10)
            vdir = ensure_vhci()
            if vdir:
                break
    print(f"vhci: {vdir}", flush=True)
    while True:
        port = free_port(vdir)
        if port is None:
            time.sleep(3)
            continue
        try:
            sock, devid, speed, vid, pid = import_device(args.server, args.tcp_port, args.busid)
        except Exception as e:
            print(f"waiting for the relay: {e}", flush=True)
            time.sleep(3)
            continue
        if speed == 0:
            speed = 2  # full speed fallback
        # The kernel takes over this socket; it must be blocking.
        sock.settimeout(None)
        attach = os.path.join(vdir, "attach")
        try:
            with open(attach, "w") as f:
                f.write(f"{port} {sock.fileno()} {devid} {speed}")
        except OSError as e:
            print(f"attach write failed: {e}", file=sys.stderr, flush=True)
            sock.close()
            time.sleep(3)
            continue
        print(f"attached port={port} devid=0x{devid:08x} speed={speed} "
              f"vid={vid:04x} pid={pid:04x}", flush=True)
        # Keep the socket open for the kernel. NOTASSIGNED is normal until the
        # device is probed; only NULL means the kernel detached the port.
        while True:
            time.sleep(3)
            st = port_status(vdir, port)
            if st == VDEV_ST_NULL or st is None:
                print(f"port {port} detached (status {st})", flush=True)
                break
        detach(vdir, port)
        sock.close()
        time.sleep(1)


if __name__ == "__main__":
    main()
PY
chmod +x "$BASE/cloudy-vhci-attach.py"
SYSDIR="/etc/systemd/system"
if [ "$DRY" = 1 ]; then SYSDIR="$BASE/system"; fi
mkdir -p "$SYSDIR" 2>/dev/null || true
if [ "$DRY" = 1 ]; then
  cat > "$SYSDIR/cloudy-usbip-attach.service" <<UNIT
[Unit]
Description=Cloudy USB/IP auto-attach (VHCI)
After=network.target

[Service]
ExecStart=$BASE/cloudy-vhci-attach.py
Restart=always
RestartSec=3

[Install]
WantedBy=multi-user.target
UNIT
else
  sudo install -m 755 "$BASE/cloudy-vhci-attach.py" /usr/local/bin/cloudy-vhci-attach
  sudo modprobe vhci-hcd 2>/dev/null || echo "warning: vhci-hcd unavailable in this kernel" >&2
  sudo tee "$SYSDIR/cloudy-usbip-attach.service" >/dev/null <<UNIT
[Unit]
Description=Cloudy USB/IP auto-attach (VHCI)
After=network.target

[Service]
ExecStart=/usr/local/bin/cloudy-vhci-attach
Restart=always
RestartSec=3

[Install]
WantedBy=multi-user.target
UNIT
  sudo systemctl daemon-reload
  sudo systemctl enable --now cloudy-usbip-attach.service 2>/dev/null || true
fi

fi

# Default: USB forwarding is deferred. Remove/disable anything left from
# earlier experimental setups so the VM carries only the backend.
if [ "$WITH_USB" = 0 ]; then
  systemctl --user disable --now cloudy-serial-bridge cloudy-usbip-relay 2>/dev/null || true
  rm -f "$BASE/cloudy-serial-bridge.py" "$BASE/cloudy-usbip-relay.py" \
        "$BASE/run-serial-bridge.sh" "$BASE/run-usbip-relay.sh" \
        "$BASE/cloudy-vhci-attach.py" \
        "$UNITS/cloudy-serial-bridge.service" "$UNITS/cloudy-usbip-relay.service"
  if [ "$DRY" = 0 ]; then
    sudo systemctl disable --now cloudy-usbip-attach 2>/dev/null || true
    sudo rm -f /etc/systemd/system/cloudy-usbip-attach.service /usr/local/bin/cloudy-vhci-attach 2>/dev/null || true
    sudo systemctl daemon-reload 2>/dev/null || true
  fi
fi

echo "== installed =="
echo "token:        $BASE/token (mode 600)"
if [ "$WITH_USB" = 1 ]; then
  echo "serial:       $BASE/cloudy-serial-bridge.py (guest 7001 -> RFC2217 7002)"
  echo "usb/ip:       $BASE/cloudy-usbip-relay.py (guest 3241 <- app; 127.0.0.1:3240 for usbip)"
else
  echo "usb:          forwarding deferred (not installed; use --with-usb to experiment)"
fi
echo "backend:      $BACKEND"
if [ "$DRY" = 1 ]; then
  echo "dry run: services written to $UNITS, not started"
  exit 0
fi

systemctl --user daemon-reload
SERVICES="cloudy-backend"
[ "$WITH_USB" = 1 ] && SERVICES="cloudy-serial-bridge cloudy-usbip-relay cloudy-backend"
for u in $SERVICES; do
  if [ -f "$UNITS/$u.service" ]; then
    systemctl --user enable "$u.service" 2>/dev/null || true
    # Start now unless the service's port is already served by a manual process.
    case "$u" in
      cloudy-serial-bridge) systemctl --user restart "$u.service" 2>/dev/null || true;;
      cloudy-usbip-relay) systemctl --user restart "$u.service" 2>/dev/null || true;;
      cloudy-backend)
        systemctl --user enable "$u.service" 2>/dev/null || true
        if ! (echo > /dev/tcp/127.0.0.1/8080) 2>/dev/null && ! (echo > /dev/tcp/127.0.0.1/8085) 2>/dev/null; then
          systemctl --user restart "$u.service" 2>/dev/null || true
        fi
        ;;
    esac
  fi
done
loginctl enable-linger "$USER" 2>/dev/null || sudo loginctl enable-linger "$USER" 2>/dev/null || true

echo
if [ "$WITH_USB" = 1 ]; then
  echo "usb/ip attach: cloudy-usbip-attach.service (system) enabled"
fi
echo "services enabled; they start with the VM."
echo "In the Terminal app keep this window open for the port forwards."
echo "Next: open Cloudy Code (it connects on 8085 or 8080)."
