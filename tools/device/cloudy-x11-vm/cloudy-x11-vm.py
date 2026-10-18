#!/usr/bin/env python3
"""Cloudy-X11 VM display proxy (display :99).

Owns /tmp/.X11-unix/X<display> and forwards each X11 client connection either to
a local upstream X server (test mode) or to the Cloudy-X11 app over one
authenticated framed connection (production mode). The proxy is transparent to
the X11 protocol; X11 authentication remains between the client and the app's X
server.

Tunnel protocol (the app initiates the TCP connection to --tunnel-listen):
  app -> proxy: b"CLOUDY-X11 " + token + b"\n"
  proxy -> app: b"OK\n" (or b"ERR\n")
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
                conn.sendall(b"OK\n")
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
    ap.add_argument("--client-wait", type=float, default=30.0)
    args = ap.parse_args()
    if args.tunnel_listen and not args.token_file:
        ap.error("--token-file is required with --tunnel-listen")
    Proxy(args).start()


if __name__ == "__main__":
    main()
