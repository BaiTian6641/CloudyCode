#!/usr/bin/env bash
# ADR-0006: build the app-owned Code-OSS web workbench dist from the pinned
# source (same commit as the guest reh-web services). Everything lives in
# ~/.cache/gbcode/upstream — never in the project repo.
#
# Output: ~/.cache/gbcode/upstream/vscode-web  (the dist that
#         tools/release/package-web-assets.sh packages into the APK).
set -euo pipefail

CACHE="$HOME/.cache/gbcode/upstream"
NODE_VER="24.18.0"
NODE_DIR="$HOME/.cache/gbcode/toolchains/node-v$NODE_VER-linux-x64"
SRC="$CACHE/vscode-1.141.0"
COMMIT="2a59476c9bfcb90b3ddc372c36762471b7dfad1c"
LOG="$CACHE/adr0006-web-build.log"

mkdir -p "$HOME/.cache/gbcode/toolchains"
exec > >(tee -a "$LOG") 2>&1
echo "=== ADR-0006 web build start $(date -Is) ==="

if [ ! -x "$NODE_DIR/bin/node" ]; then
  echo "--- fetching node $NODE_VER"
  curl -fsSL "https://nodejs.org/dist/v$NODE_VER/node-v$NODE_VER-linux-x64.tar.xz" \
    | tar -xJ -C "$HOME/.cache/gbcode/toolchains/"
fi
export PATH="$NODE_DIR/bin:$PATH"
node --version

[ -d "$SRC/.git" ] || { echo "source checkout missing: $SRC (run build-upstream-reh-web.sh first)"; exit 1; }
actual="$(git -C "$SRC" rev-parse HEAD)"
[ "$actual" = "$COMMIT" ] || { echo "source commit mismatch: $actual != $COMMIT"; exit 1; }
[ -d "$SRC/node_modules" ] || { echo "node_modules missing in $SRC (run its npm install first)"; exit 1; }

echo "--- gulp vscode-web-min (workbench library + workers + resources)"
cd "$SRC"
npm run gulp -- vscode-web-min

echo "--- page shell bundle (server-web target: workbench.js + workbench.css)"
node --experimental-strip-types --max-old-space-size=8192 ./build/next/index.ts bundle \
  --out out-vscode-shell-min --target server-web --minify --mangle-privates --nls

echo "--- dist ready: $CACHE/vscode-web (library) + $SRC/out-vscode-shell-min (shell)"
echo "next: tools/release/package-web-assets.sh"
