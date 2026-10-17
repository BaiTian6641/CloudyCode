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
