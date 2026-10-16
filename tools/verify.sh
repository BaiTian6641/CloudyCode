#!/usr/bin/env bash
# GBCode verify: run every currently-green suite. Exit non-zero on any failure.
#
# Lanes:
#   guest tests   — sessiond (protocol/authz/registry/vectors/fuzz), gateway
#   APK builds    — probes + production app compile
#   on-device     — gateway instrumentation on the emulator (ANDROID_SERIAL-scoped)
#
# Physical-device lanes are NOT included (device hands-off until released);
# see docs/plan and docs/evidence for the physical matrix.
set -euo pipefail
cd "$(dirname "$0")/.."

GRADLE="$HOME/.gradle/wrapper/dists/gradle-9.8.1-bin/a2l6ltp3k1vth6lwipzm5p2jp/gradle-9.8.1/bin/gradle"
[ -x "$GRADLE" ] || GRADLE="$(command -v gradle || true)"
[ -n "$GRADLE" ] || { echo "gradle not found"; exit 1; }

step() { printf '\n=== %s ===\n' "$*"; }
fail=0

step "sessiond: protocol + authz + registry + vectors + fuzz"
(cd guest/sessiond && timeout 120 node --test) || fail=1

step "gateway: proxy/origin/WS/502"
(cd guest/gateway && timeout 60 node --test) || fail=1

step "probe APK builds"
"$GRADLE" -p android/probes/network-probe :app:assembleDebug --console=plain -q || fail=1
"$GRADLE" -p android/probes/workbench-slice :app:assembleDebug --console=plain -q || fail=1
"$GRADLE" -p android/probes/storage-probe :app:assembleDebug --console=plain -q || fail=1

step "production app build"
"$GRADLE" -p android/app :app:assembleDebug --console=plain -q || fail=1

step "gateway instrumentation (emulator only)"
if adb -s emulator-5554 get-state >/dev/null 2>&1; then
  ANDROID_SERIAL=emulator-5554 "$GRADLE" -p android/app :app:connectedDebugAndroidTest --console=plain -q || fail=1
else
  echo "emulator-5554 not running; skipping on-device tests"
fi

step "result"
if [ "$fail" = 0 ]; then
  echo "ALL GREEN"
else
  echo "FAILURES PRESENT"
  exit 1
fi
