#!/bin/sh
# Install the VM-wide Cloudy-X11 display environment and user service.
# Usage: install-env.sh [--base DIR] [--display :99] [--system]
set -eu
BASE="${HOME}/.local/share/cloudy-x11-vm"
DISPLAY=":99"
SYSTEM=0
while [ $# -gt 0 ]; do
  case "$1" in
    --base) BASE="$2"; shift 2 ;;
    --display) DISPLAY="$2"; shift 2 ;;
    --system) SYSTEM=1; shift ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done
mkdir -p "$BASE" "$HOME/.config/environment.d" "$HOME/.config/systemd/user"
XAUTH="$BASE/Xauthority"
umask 077
COOKIE=$(xauth -f "$XAUTH" list "$DISPLAY" 2>/dev/null | awk 'NR==1{print $3}') || true
if [ -z "${COOKIE:-}" ]; then
  COOKIE=$(python3 - <<'PY'
import secrets
print(secrets.token_hex(16))
PY
)
  xauth -f "$XAUTH" add "$DISPLAY" . "$COOKIE"
  xauth -f "$XAUTH" add ":98" . "$COOKIE"
  echo "created $XAUTH (display $DISPLAY)"
fi
printf '%s' "$COOKIE" > "$BASE/x11-cookie"
chmod 600 "$BASE/x11-cookie"
cat > "$HOME/.config/environment.d/90-cloudy-x11.conf" <<EOF
DISPLAY=$DISPLAY
XAUTHORITY=$XAUTH
EOF
# Replace the prototype's fixed :100 export with the VM-wide Cloudy display.
for rc in "$HOME/.bashrc" "$HOME/.profile"; do
  [ -f "$rc" ] || continue
  grep -v 'export DISPLAY=:100' "$rc" > "$rc.cloudy.tmp" || true
  if ! grep -q 'CLOUDY_X11_DISPLAY' "$rc.cloudy.tmp"; then
    cat >> "$rc.cloudy.tmp" <<EOF
# CLOUDY_X11_DISPLAY
export DISPLAY=$DISPLAY
export XAUTHORITY=$XAUTH
EOF
  fi
  mv "$rc.cloudy.tmp" "$rc"
done
if [ "$SYSTEM" = 1 ]; then
  printf '# CLOUDY_X11_DISPLAY\nexport DISPLAY=%s\nexport XAUTHORITY=%s\n' "$DISPLAY" "$XAUTH" \
    | sudo tee /etc/profile.d/cloudy-x11.sh >/dev/null
  echo "installed /etc/profile.d/cloudy-x11.sh"
fi
cat > "$HOME/.config/systemd/user/cloudy-x11-vm.service" <<EOF
[Unit]
Description=Cloudy-X11 VM display proxy (:99)
After=default.target

[Service]
ExecStart=/usr/bin/python3 $BASE/cloudy-x11-vm.py --display $DISPLAY --tunnel-listen unix:$BASE/x11-tunnel.sock --token-file $BASE/x11-token --cookie-file $BASE/x11-cookie
Restart=on-failure
RestartSec=2

[Install]
WantedBy=default.target
EOF
if [ ! -s "$BASE/x11-token" ]; then
  echo "missing $BASE/x11-token; the app setup must write the per-install token" >&2
fi
systemctl --user enable cloudy-x11-vm.service 2>/dev/null || true
echo "Installed environment and service. Restart the Cloudy Code backend and open a new shell."
echo "Test (VM): $BASE/cloudy-x11-vm.py + tunnel-test.py with a local Xvfb; see README."
