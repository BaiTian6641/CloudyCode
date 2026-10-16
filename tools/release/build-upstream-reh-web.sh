#!/usr/bin/env bash
# U03.01: build pristine Code - OSS 1.141.0 reh-web server control.
# Everything lives in ~/.cache/gbcode/upstream — never in the project repo.
set -euo pipefail

CACHE="$HOME/.cache/gbcode/upstream"
NODE_VER="24.18.0"
NODE_DIR="$HOME/.cache/gbcode/toolchains/node-v$NODE_VER-linux-x64"
SRC="$CACHE/vscode-1.141.0"
COMMIT="2a59476c9bfcb90b3ddc372c36762471b7dfad1c"
LOG="$CACHE/u0301-build.log"

mkdir -p "$HOME/.cache/gbcode/toolchains"
exec > >(tee -a "$LOG") 2>&1
echo "=== U03.01 build start $(date -Is) ==="

if [ ! -x "$NODE_DIR/bin/node" ]; then
  echo "--- fetching node $NODE_VER"
  curl -fsSL "https://nodejs.org/dist/v$NODE_VER/node-v$NODE_VER-linux-x64.tar.xz" \
    | tar -xJ -C "$HOME/.cache/gbcode/toolchains/"
fi
export PATH="$NODE_DIR/bin:$PATH"
node --version
npm --version

if [ ! -d "$SRC/.git" ]; then
  echo "--- cloning microsoft/vscode tag 1.141.0 (shallow)"
  git clone --depth 1 --branch 1.141.0 \
    https://github.com/microsoft/vscode.git "$SRC"
fi
cd "$SRC"
ACTUAL=$(git rev-parse HEAD)
echo "HEAD=$ACTUAL"
[ "$ACTUAL" = "$COMMIT" ] || { echo "COMMIT MISMATCH"; exit 1; }

echo "--- npm install (this is the long step)"
CI=true npm install --no-audit --no-fund

echo "--- gulp vscode-reh-web-linux-x64"
npm run gulp -- vscode-reh-web-linux-x64

echo "--- artifact"
ls -la "$SRC/../vscode-reh-web-linux-x64" | head -20
echo "=== U03.01 build done $(date -Is) ==="
