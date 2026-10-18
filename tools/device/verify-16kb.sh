#!/usr/bin/env bash
# Verify Googlebook/Android 17 16 KB page compatibility for the packaged app.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
APK="${1:-$ROOT/android/cloudy-x11/app/build/outputs/apk/debug/app-debug.apk}"
SO="${2:-$ROOT/android/cloudy-x11/app/src/main/jniLibs/arm64-v8a/libcloudy_x11_native.so}"
NDK="${ANDROID_NDK_HOME:-$HOME/Android/Sdk/ndk/27.3.13750724}"
READELF="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
ZIPALIGN="$(ls -d "$HOME"/Android/Sdk/build-tools/* 2>/dev/null | sort -V | tail -1)/zipalign"
[ -f "$SO" ] || { echo "missing $SO; build the JNI first" >&2; exit 1; }
[ -f "$APK" ] || { echo "missing $APK; assemble first" >&2; exit 1; }
ALIGNMENTS="$("$READELF" -lW "$SO" | awk '/LOAD/{print $NF}' | sort -u)"
[ "$ALIGNMENTS" = "0x4000" ] || {
  echo "ELF LOAD alignments are '$ALIGNMENTS', expected 0x4000" >&2
  exit 1
}
echo "ELF LOAD segments: 0x4000"
"$ZIPALIGN" -c -P 16 -v 4 "$APK" | tail -2
echo "16 KB verification passed: $APK"
