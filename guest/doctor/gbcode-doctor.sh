#!/usr/bin/env bash
# GBCode guest doctor: emit the machine-readable capability record (§13.2).
# Read-only: gathers identity and capability state without changing anything.
# Usage: guest/doctor.sh [--pretty]
set -u

pretty=0
[ "${1:-}" = "--pretty" ] && pretty=1

kernel_release="$(uname -r 2>/dev/null || echo unknown)"
kernel_mach="$(uname -m 2>/dev/null || echo unknown)"
os_pretty="$(. /etc/os-release 2>/dev/null && echo "${PRETTY_NAME:-unknown}" || echo unknown)"

# VHCI / USB/IP inventory (read-only; never loads modules)
vhci="unknown"
if [ -e /sys/class/usbip ]; then
  vhci="present-sysfs"
elif grep -qsE 'CONFIG_USBIP_VHCI_HCD=[ym]' /proc/config.gz 2>/dev/null; then
  vhci="config-builtin-or-module"
elif [ -e "/lib/modules/${kernel_release}/drivers/usb/usbip/vhci-hcd.ko" ] \
  || [ -e "/lib/modules/${kernel_release}/drivers/usb/usbip/vhci-hcd.ko.xz" ]; then
  vhci="module-present"
elif command -v modinfo >/dev/null 2>&1 && modinfo -n vhci-hcd >/dev/null 2>&1; then
  vhci="module-present"
else
  vhci="absent-or-unknown"
fi

usbip_core="unknown"
if grep -qsE 'CONFIG_USBIP_CORE=[ym]' /proc/config.gz 2>/dev/null; then
  usbip_core="config-present"
elif [ -d "/lib/modules/${kernel_release}/drivers/usb/usbip" ]; then
  usbip_core="module-dir-present"
fi

# serial drivers (CDC ACM etc.)
cdc_acm="unknown"
if [ -e "/lib/modules/${kernel_release}/drivers/usb/class/cdc-acm.ko" ] \
  || [ -e "/lib/modules/${kernel_release}/drivers/usb/class/cdc-acm.ko.xz" ] \
  || { command -v modinfo >/dev/null 2>&1 && modinfo -n cdc-acm >/dev/null 2>&1; }; then
  cdc_acm="module-present"
fi

# systemd user
systemd_user="unavailable"
if command -v systemctl >/dev/null 2>&1 && systemctl --user is-system-running >/dev/null 2>&1; then
  systemd_user="available"
fi

# writable config dirs
cfg="${GBCODE_CONFIG:-$HOME/.config/gbcode}"
state="${GBCODE_HOME:-$HOME/.local/share/gbcode}"

json() {
  cat <<EOF
{
  "schemaVersion": 1,
  "tool": "gbcode-doctor/0.1.0",
  "collectedAt": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "host": {
    "kernelRelease": "$kernel_release",
    "machine": "$kernel_mach",
    "os": "$os_pretty"
  },
  "capabilities": {
    "guestVhci": {"state": "$vhci"},
    "usbipCore": {"state": "$usbip_core"},
    "cdcAcm": {"state": "$cdc_acm"},
    "systemdUser": {"state": "$systemd_user"}
  },
  "paths": {
    "config": "$cfg",
    "state": "$state"
  },
  "notes": "read-only inventory; a present module is not proof of loadable support"
}
EOF
}

if [ "$pretty" = 1 ]; then
  json | python3 -m json.tool 2>/dev/null || json
else
  json
fi
