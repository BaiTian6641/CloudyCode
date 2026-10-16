#!/usr/bin/env python3
"""GBCode S00 probe echo server (stdlib only).

Endpoints:
  GET  /        capability fixture page (WebView lane)
  GET  /sw.js   minimal service worker
  GET  /nonce   JSON nonce (HTTP reachability check)
  POST /report  append one JSON line to results.jsonl (204)
  WS   /ws      binary/text echo (RFC 6455)

Binds 0.0.0.0:8765 so the Android emulator can reach it via 10.0.2.2.
Loopback-only test fixture; no auth, not for production use.
"""

import base64
import hashlib
import json
import socket
import struct
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parent
RESULTS = ROOT / "results.jsonl"
WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"


def ws_accept(key: str) -> str:
    digest = hashlib.sha1((key + WS_GUID).encode("ascii")).digest()
    return base64.b64encode(digest).decode("ascii")


def ws_echo(conn: socket.socket) -> None:
    """Echo frames until close/error. Text and binary echoed verbatim."""
    conn.settimeout(60)
    buffer = b""
    while True:
        try:
            chunk = conn.recv(65536)
        except (TimeoutError, ConnectionError):
            return
        if not chunk:
            return
        buffer += chunk
        while True:
            if len(buffer) < 2:
                break
            b0, b1 = buffer[0], buffer[1]
            opcode = b0 & 0x0F
            masked = bool(b1 & 0x80)
            length = b1 & 0x7F
            idx = 2
            if length == 126:
                if len(buffer) < 4:
                    break
                length = struct.unpack(">H", buffer[2:4])[0]
                idx = 4
            elif length == 127:
                if len(buffer) < 10:
                    break
                length = struct.unpack(">Q", buffer[2:10])[0]
                idx = 10
            if length > 1 << 20:
                return
            mask = b""
            if masked:
                if len(buffer) < idx + 4:
                    break
                mask = buffer[idx:idx + 4]
                idx += 4
            if len(buffer) < idx + length:
                break
            payload = bytearray(buffer[idx:idx + length])
            buffer = buffer[idx + length:]
            if masked:
                for i in range(len(payload)):
                    payload[i] ^= mask[i % 4]
            if opcode == 0x8:  # close
                conn.sendall(b"\x88\x00")
                return
            if opcode == 0x9:  # ping -> pong
                header = b"\x8a" + (bytes([len(payload)]) if len(payload) < 126 else b"")
                conn.sendall(header + bytes(payload))
                continue
            if opcode not in (0x1, 0x2, 0xA):
                continue
            n = len(payload)
            if n < 126:
                header = bytes([0x80 | opcode, n])
            elif n <= 0xFFFF:
                header = bytes([0x80 | opcode, 126]) + struct.pack(">H", n)
            else:
                header = bytes([0x80 | opcode, 127]) + struct.pack(">Q", n)
            conn.sendall(header + bytes(payload))


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        print(f"[{time.strftime('%H:%M:%S')}] {self.address_string()} {fmt % args}", flush=True)

    def _send(self, code, body=b"", content_type="text/plain; charset=utf-8", extra=None):
        self.send_response(code)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.end_headers()
        if body:
            self.wfile.write(body)

    def do_GET(self):
        if self.path == "/ws" and self.headers.get("Upgrade", "").lower() == "websocket":
            key = self.headers.get("Sec-WebSocket-Key", "")
            self.send_response(101, "Switching Protocols")
            self.send_header("Upgrade", "websocket")
            self.send_header("Connection", "Upgrade")
            self.send_header("Sec-WebSocket-Accept", ws_accept(key))
            self.end_headers()
            self.close_connection = True
            ws_echo(self.connection)
            return
        if self.path == "/nonce":
            body = json.dumps({
                "nonce": hashlib.sha256(str(time.time_ns()).encode()).hexdigest()[:32],
                "server": "gbcode-echo",
                "ts": time.time(),
            }).encode()
            self._send(200, body, "application/json")
            return
        if self.path in ("/", "/capability.html"):
            self._send(200, (ROOT / "capability.html").read_bytes(),
                       "text/html; charset=utf-8")
            return
        if self.path == "/sw.js":
            self._send(200, (ROOT / "sw.js").read_bytes(), "text/javascript")
            return
        self._send(404, b"not found\n")

    def do_POST(self):
        if self.path != "/report":
            self._send(404, b"not found\n")
            return
        length = int(self.headers.get("Content-Length", "0"))
        raw = self.rfile.read(min(length, 1 << 20))
        record = {
            "receivedAt": time.strftime("%Y-%m-%dT%H:%M:%S.000Z", time.gmtime()),
            "client": self.client_address[0],
        }
        try:
            record.update(json.loads(raw.decode("utf-8")))
        except Exception:
            record["parseError"] = True
            record["raw"] = raw.decode("utf-8", "replace")[:2000]
        with RESULTS.open("a", encoding="utf-8") as fh:
            fh.write(json.dumps(record) + "\n")
        self._send(204)


def main():
    import sys
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8765
    server = ThreadingHTTPServer(("0.0.0.0", port), Handler)
    print(f"gbcode echo server on 0.0.0.0:{port} (emulator uses 10.0.2.2:{port})", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
