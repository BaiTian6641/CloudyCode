#!/usr/bin/env bash
# One-time Cloudy Code backend build for the Googlebook Debian VM (arm64).
# Builds the pinned Code - OSS reh-web server natively in the VM, installs it
# under ~/.local/share/cloudy, and creates a start script + systemd user unit.
#
# Run inside the Terminal app (Debian VM). Needs network and sudo for build
# tools. Expect ~15-30 minutes and ~2-3 GB of disk.
set -euo pipefail

COMMIT="2a59476c9bfcb90b3ddc372c36762471b7dfad1c"
NODE_VERSION="24.18.0"
BASE="$HOME/.local/share/cloudy"
SRC="$BASE/src"
REPO="$SRC/vscode-$COMMIT"

echo "== Cloudy Code backend build =="
echo "base: $BASE"
sudo -v
sudo apt-get update
sudo apt-get install -y --no-install-recommends \
  build-essential python3 pkg-config libkrb5-dev libsecret-1-dev \
  ca-certificates curl xz-utils git

mkdir -p "$BASE/data" "$BASE/extensions" "$SRC"

if [ ! -x "$BASE/node/bin/node" ]; then
  echo "== installing Node $NODE_VERSION (arm64) =="
  curl -fL "https://nodejs.org/dist/v$NODE_VERSION/node-v$NODE_VERSION-linux-arm64.tar.xz" \
    -o /tmp/cloudy-node.tar.xz
  mkdir -p "$BASE/node"
  tar -xJf /tmp/cloudy-node.tar.xz -C "$BASE/node" --strip-components=1
fi
export PATH="$BASE/node/bin:$PATH"
node --version
npm --version

if [ ! -d "$REPO" ]; then
  echo "== fetching Code - OSS $COMMIT =="
  curl -fL "https://github.com/microsoft/vscode/archive/$COMMIT.tar.gz" -o /tmp/cloudy-vscode.tar.gz
  tar -xzf /tmp/cloudy-vscode.tar.gz -C "$SRC"
fi

cd "$REPO"
export NODE_OPTIONS="--max-old-space-size=4096"
echo "== npm ci =="
npm ci
echo "== building vscode-reh-web-linux-arm64 =="
npx gulp vscode-reh-web-linux-arm64

ARTIFACT="$SRC/vscode-reh-web-linux-arm64"
[ -x "$ARTIFACT/node" ] || { echo "build artifact missing: $ARTIFACT"; exit 1; }

rm -rf "$BASE/reh-web"
mv "$ARTIFACT" "$BASE/reh-web"

cat > "$BASE/start-backend.sh" <<'SH'
#!/usr/bin/env bash
# Starts the Cloudy Code backend. The token file is written by the app's
# "Copy Cloudy backend start command" action before this script runs.
set -euo pipefail
BASE="$HOME/.local/share/cloudy"
TOKEN_FILE="$BASE/token"
mkdir -p "$BASE/data" "$BASE/extensions"
if [ ! -s "$TOKEN_FILE" ]; then
  echo "missing $TOKEN_FILE - copy the start command from the Cloudy Code app first"
  exit 1
fi
cd "$BASE/reh-web"
exec ./node out/server-main.js \
  --host 127.0.0.1 --port 8085 \
  --connection-token-file "$TOKEN_FILE" \
  --telemetry-level off \
  --server-data-dir "$BASE/data" \
  --extensions-dir "$BASE/extensions"
SH
chmod +x "$BASE/start-backend.sh"

mkdir -p "$HOME/.config/systemd/user"
cat > "$HOME/.config/systemd/user/cloudy-code.service" <<UNIT
[Unit]
Description=Cloudy Code Linux backend (Code - OSS reh-web)
After=network.target

[Service]
ExecStart=$BASE/start-backend.sh
Restart=on-failure
RestartSec=2

[Install]
WantedBy=default.target
UNIT
systemctl --user daemon-reload 2>/dev/null || true
# Enable at login/boot. The unit stays down until the app seeds the token
# (one Copy start command action); afterwards every VM start brings the
# backend up. Linger is needed for user services to start without a login.
systemctl --user enable cloudy-code.service 2>/dev/null || true
loginctl enable-linger "$USER" 2>/dev/null || sudo loginctl enable-linger "$USER" 2>/dev/null || true

echo
echo "== done =="
echo "auto-start: systemd user unit enabled (needs one token seed from the app)"
echo "backend: $BASE/reh-web"
echo "start:   $BASE/start-backend.sh"
echo "Now copy the start command from the Cloudy Code setup screen and run it,"
echo "or: echo '<token>' > $BASE/token && $BASE/start-backend.sh"
