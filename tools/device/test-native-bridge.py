#!/usr/bin/env python3
"""Host test for the native 14500 bridge (no xpra, no VM, no X server)."""
import hashlib
import hmac
import json
import os
import socket
import struct
import subprocess
import tempfile
import threading
import time
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
TOKEN = '0123456789abcdef0123456789abcdef'
COOKIE = 'aabbccddeeff00112233445566778899'
tmp = tempfile.mkdtemp(prefix='cloudy-bridge-')
token_file = os.path.join(tmp, 'token')
tunnel = os.path.join(tmp, 'x11-tunnel.sock')
Path(token_file).write_text(TOKEN)
ready = threading.Event()
received = []


def recv_exact(sock, n):
    out = b''
    while len(out) < n:
        part = sock.recv(n - len(out))
        if not part:
            raise EOFError
        out += part
    return out


def fake_vm():
    srv = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    srv.bind(tunnel)
    srv.listen(1)
    ready.set()
    conn, _ = srv.accept()
    line = b''
    while not line.endswith(b'\n'):
        line += conn.recv(1)
    assert line == f'CLOUDY-X11 {TOKEN}\n'.encode(), line
    conn.sendall(f'OK {COOKIE}\n'.encode())
    while True:
        data = conn.recv(4096)
        if not data:
            break
        received.append(data)
        conn.sendall(data)
    conn.close()
    srv.close()


threading.Thread(target=fake_vm, daemon=True).start()
ready.wait(3)
proc = subprocess.Popen([
    'python3', str(ROOT / 'tools/device/cloudy-x11-proxy.py'),
    '--token-file', token_file,
    '--listen', '127.0.0.1:18090',
    '--x11-tunnel-socket', tunnel,
    '--display', ':99',
], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
try:
    for _ in range(50):
        try:
            socket.create_connection(('127.0.0.1', 18090), 0.1).close()
            break
        except OSError:
            time.sleep(0.1)
    else:
        raise SystemExit('bridge did not listen')

    nonce = 'abc123'
    with urllib.request.urlopen(
            f'http://127.0.0.1:18090/__cloudy/ping?nonce={nonce}') as r:
        body = r.read().decode()
    assert body == hmac.new(TOKEN.encode(), nonce.encode(),
                            hashlib.sha256).hexdigest()

    s = socket.create_connection(('127.0.0.1', 18090), 3)
    s.sendall(('GET /x11-tunnel HTTP/1.1\r\nHost: 127.0.0.1\r\n'
               'Cookie: cloudy_x11=wrong\r\nUpgrade: cloudy-x11\r\n'
               'Connection: Upgrade\r\n\r\n').encode())
    assert b' 403 ' in s.recv(200)
    s.close()

    s = socket.create_connection(('127.0.0.1', 18090), 3)
    s.sendall((f'GET /x11-tunnel HTTP/1.1\r\nHost: 127.0.0.1\r\n'
               f'Cookie: cloudy_x11={TOKEN}\r\nUpgrade: cloudy-x11\r\n'
               'Connection: Upgrade\r\n\r\n').encode())
    head = b''
    while b'\r\n\r\n' not in head:
        head += s.recv(1)
    assert head.startswith(b'HTTP/1.1 101'), head
    line = b''
    while not line.endswith(b'\n'):
        line += s.recv(1)
    assert line.startswith(b'CLOUDY-X11-CONFIG 1 '), line
    cfg = json.loads(line.split(b' ', 2)[2])
    assert cfg['display'] == ':99' and cfg['auth'] == 'MIT-MAGIC-COOKIE-1'
    assert cfg['cookie'] == COOKIE and cfg['max_frame'] == 1048576
    payload = b'ping-through-native-bridge'
    s.sendall(payload)
    assert recv_exact(s, len(payload)) == payload
    s.close()
    print('PASS ping HMAC, 403 wrong cookie, 101/config, binary pump')
finally:
    proc.terminate()
    proc.wait(5)
