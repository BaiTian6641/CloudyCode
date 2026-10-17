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
