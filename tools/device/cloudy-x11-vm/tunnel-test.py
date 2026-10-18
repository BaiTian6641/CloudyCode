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
