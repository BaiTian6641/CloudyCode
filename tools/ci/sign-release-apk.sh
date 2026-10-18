#!/usr/bin/env bash
# Align an unsigned release APK, sign it with the supplied Cloudy identity and
# verify the final signed file. Password values are read from environment
# variables by the SDK tools; they are never printed.
set -euo pipefail

APK="${1:?usage: sign-release-apk.sh <unsigned.apk> <output.apk> <keystore> <alias> <ks-pass-env> <key-pass-env> [build-tools-dir]}"
OUT="${2:?}"
KEYSTORE="${3:?}"
ALIAS="${4:?}"
KS_PASS_ENV="${5:?}"
KEY_PASS_ENV="${6:?}"
BUILD_TOOLS="${7:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}/build-tools/36.0.0}"
ZIPALIGN="$BUILD_TOOLS/zipalign"
APKSIGNER="$BUILD_TOOLS/apksigner"

[[ -f "$APK" ]] || { echo "missing unsigned APK: $APK" >&2; exit 1; }
[[ -f "$KEYSTORE" ]] || { echo "missing keystore: $KEYSTORE" >&2; exit 1; }
[[ -n "${!KS_PASS_ENV:-}" ]] || { echo "missing environment variable $KS_PASS_ENV" >&2; exit 1; }
[[ -n "${!KEY_PASS_ENV:-}" ]] || { echo "missing environment variable $KEY_PASS_ENV" >&2; exit 1; }
[[ -x "$ZIPALIGN" ]] || { echo "missing zipalign: $ZIPALIGN" >&2; exit 1; }
[[ -x "$APKSIGNER" ]] || { echo "missing apksigner: $APKSIGNER" >&2; exit 1; }

OUT_DIR="$(dirname "$OUT")"
mkdir -p "$OUT_DIR"
ALIGNED="$(mktemp "$OUT_DIR/.aligned.XXXXXX.apk")"
trap 'rm -f "$ALIGNED"' EXIT

"$ZIPALIGN" -P 16 -f 4 "$APK" "$ALIGNED"
"$APKSIGNER" sign \
    --ks "$KEYSTORE" \
    --ks-key-alias "$ALIAS" \
    --ks-pass "env:$KS_PASS_ENV" \
    --key-pass "env:$KEY_PASS_ENV" \
    --v4-signing-enabled false \
    --out "$OUT" \
    "$ALIGNED"
"$APKSIGNER" verify --verbose --print-certs "$OUT"
echo "signed $OUT"
