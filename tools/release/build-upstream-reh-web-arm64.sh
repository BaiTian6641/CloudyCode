#!/usr/bin/env bash
# Cross-build the ARM64 reh-web control (the guest-side backend bundle for the
# Googlebook's arm64 Debian VM). Same base, overlay and verification as the
# x64 control; reuses the prepared source tree in the GBCode cache.
set -euo pipefail
cd "$HOME/.cache/gbcode/upstream/vscode-1.141.0"
export PATH="$HOME/.cache/gbcode/toolchains/node-v24.18.0-linux-x64/bin:$PATH"
export PKG_CONFIG_PATH="$HOME/.cache/gbcode/sysroot/lib/pkgconfig"
export GBCODE_NO_COPILOT=1
echo "=== reh-web linux-arm64 build start $(date -Is) ==="
npm run gulp -- vscode-reh-web-linux-arm64
echo "=== artifact ==="
ls -la "$HOME/.cache/gbcode/upstream/vscode-reh-web-linux-arm64" | head -10
echo "=== done $(date -Is) ==="
