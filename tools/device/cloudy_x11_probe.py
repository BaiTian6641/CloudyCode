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
