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
        host, _, port = value[5:].rpartition(":")
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
        app.sendall(f"CLOUDY-X11 {self.token}\n".encode())
        line = b""
        while not line.endswith(b"\n"):
            part = app.recv(1)
            if not part:
                raise SystemExit("tunnel closed during handshake")
            line += part
        if line.strip() != b"OK":
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
    Bridge(ap.parse_args()).run()


if __name__ == "__main__":
    main()
