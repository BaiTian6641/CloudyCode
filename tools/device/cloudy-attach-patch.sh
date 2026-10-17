#!/usr/bin/env bash
# Cloudy USB/IP attach patch (short, idempotent): installs the usbip tool,
# loads vhci_hcd, and installs a root unit that attaches cloudy-303a-1001
# whenever the app's relay is serving it. Safe to re-run.
set -euo pipefail

echo "== Cloudy USB/IP attach setup =="
sudo -v
sudo apt-get update
sudo apt-get install -y usbip || true
if ! command -v usbip >/dev/null 2>&1; then
  sudo apt-get install -y "linux-tools-$(uname -r)" || true
fi
if ! command -v usbip >/dev/null 2>&1; then
  sudo apt-get install -y linux-tools-common || true
fi
if ! command -v usbip >/dev/null 2>&1; then
  sudo apt-get install -y linux-tools-generic || true
fi
if ! command -v usbip >/dev/null 2>&1; then
  echo "usbip is still missing. Try one of:" >&2
  echo "  sudo apt-get install usbip" >&2
  echo "  sudo apt-get install linux-tools-$(uname -r)" >&2
  exit 1
fi

if ! sudo modprobe vhci-hcd; then
  echo "warning: vhci-hcd is not available in this kernel; transparent USB cannot attach" >&2
fi

sudo tee /usr/local/bin/cloudy-usbip-attach >/dev/null <<'SH'
#!/usr/bin/env bash
set -u
modprobe vhci-hcd 2>/dev/null || true
BUSID="cloudy-303a-1001"
while true; do
  if usbip port 2>/dev/null | grep -q "$BUSID"; then sleep 5; continue; fi
  if usbip list -r 127.0.0.1 2>/dev/null | grep -q "$BUSID"; then
    usbip attach -r 127.0.0.1 -b "$BUSID" 2>/dev/null || true
  fi
  sleep 3
done
SH
sudo chmod +x /usr/local/bin/cloudy-usbip-attach
sudo tee /etc/systemd/system/cloudy-usbip-attach.service >/dev/null <<'UNIT'
[Unit]
Description=Cloudy USB/IP auto-attach (VHCI)
After=network.target

[Service]
ExecStart=/usr/local/bin/cloudy-usbip-attach
Restart=always
RestartSec=3

[Install]
WantedBy=multi-user.target
UNIT
sudo systemctl daemon-reload
sudo systemctl enable --now cloudy-usbip-attach.service

echo
echo "== status =="
usbip port || true
ls -l /dev/ttyACM* 2>/dev/null || echo "(no /dev/ttyACM* yet; keep the app open with USB/IP forwarding active)"
