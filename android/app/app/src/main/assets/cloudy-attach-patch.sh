#!/usr/bin/env bash
# Cloudy USB/IP attach (short, idempotent): no usbip CLI required.
# Installs a Python VHCI importer + a root unit that attaches the exported
# ESP32-S3 as soon as the app's relay serves it. Run with sudo.
set -euo pipefail

echo "== Cloudy USB/IP attach (no usbip package) =="
sudo -v

sudo tee /usr/local/bin/cloudy-vhci-attach >/dev/null <<'PY'
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
import socket
import struct
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
    args = ap.parse_args()

    if args.check:
        sock, devid, speed, vid, pid = import_device(args.server, args.tcp_port, args.busid)
        print(f"imported busid={args.busid} devid=0x{devid:08x} speed={speed} "
              f"vid={vid:04x} pid={pid:04x}")
        sock.close()
        return

    vdir = vhci_dir()
    if not vdir:
        print("vhci_hcd is not present (/sys/devices/platform/vhci_hcd*)", file=sys.stderr)
        print("run: sudo modprobe vhci-hcd", file=sys.stderr)
        sys.exit(1)

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
sudo chmod +x /usr/local/bin/cloudy-vhci-attach

if ! sudo modprobe vhci-hcd; then
  echo "warning: vhci-hcd is not available in this kernel; transparent USB cannot attach" >&2
fi

sudo tee /etc/systemd/system/cloudy-usbip-attach.service >/dev/null <<'UNIT'
[Unit]
Description=Cloudy USB/IP auto-attach (VHCI, no usbip CLI)
After=network.target

[Service]
ExecStart=/usr/local/bin/cloudy-vhci-attach
Restart=always
RestartSec=3

[Install]
WantedBy=multi-user.target
UNIT
sudo systemctl daemon-reload
sudo systemctl enable --now cloudy-usbip-attach.service

echo
echo "== status =="
sleep 2
sudo systemctl status --no-pager cloudy-usbip-attach.service | head -12 || true
sudo cat /sys/devices/platform/vhci_hcd*/status 2>/dev/null | head -6 || true
ls -l /dev/ttyACM* 2>/dev/null || echo "(no /dev/ttyACM* yet; keep the app open with USB/IP forwarding active)"
