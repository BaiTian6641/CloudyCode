#!/usr/bin/env bash
# Fetch the exact magicdesk-x11 pin and build the LorieNative static libraries.
# CI and local clean builds use the same helper; no developer checkout/prebuilt
# engine artifacts are required.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
LOCK="${ENGINE_LOCK:-$ROOT/upstream/engine.lock.json}"
ENGINE="${ENGINE_CHECKOUT:-$ROOT/upstream/checkouts/magicdesk-x11}"
BUILD="${ENGINE_BUILD:-$ENGINE/build-ci}"
NDK="${ANDROID_NDK_HOME:?ANDROID_NDK_HOME must select the pinned NDK}"
CMAKE="${CMAKE_BIN:-cmake}"
JOBS="${JOBS:-4}"

[[ -f "$LOCK" ]] || { echo "missing engine lock: $LOCK" >&2; exit 1; }
[[ -d "$NDK" ]] || { echo "missing NDK: $NDK" >&2; exit 1; }
command -v "$CMAKE" >/dev/null || { echo "missing cmake: $CMAKE" >&2; exit 1; }
command -v ninja >/dev/null || { echo "missing ninja" >&2; exit 1; }
command -v bison >/dev/null || { echo "missing bison" >&2; exit 1; }

read -r REPO PIN < <(python3 - "$LOCK" <<'PY'
import json, sys
with open(sys.argv[1], encoding="utf-8") as f:
    lock = json.load(f)
repo = lock["repository"]
pin = lock["commit"]
if len(pin) != 40 or any(c not in "0123456789abcdef" for c in pin.lower()):
    raise SystemExit(f"invalid engine commit: {pin!r}")
print(repo, pin)
PY
)
[[ -n "$REPO" && -n "$PIN" ]] || { echo "could not parse engine lock" >&2; exit 1; }

if [[ ! -d "$ENGINE/.git" ]]; then
    mkdir -p "$(dirname "$ENGINE")"
    git clone --no-checkout "$REPO" "$ENGINE"
fi
git -C "$ENGINE" fetch --tags --force origin
git -C "$ENGINE" checkout --detach "$PIN"
git -C "$ENGINE" submodule sync --recursive
git -C "$ENGINE" submodule update --init --recursive
ACTUAL="$(git -C "$ENGINE" rev-parse HEAD)"
[[ "$ACTUAL" == "$PIN" ]] || {
    echo "engine checkout mismatch: expected $PIN, got $ACTUAL" >&2
    exit 1
}

rm -rf "$BUILD"
"$CMAKE" -S "$ENGINE/lorie/src/main/cpp" -B "$BUILD" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a \
    -DANDROID_PLATFORM=android-34 \
    -DCMAKE_BUILD_TYPE=Release \
    -DLORIE_BUILD_SMOKE=OFF
"$CMAKE" --build "$BUILD" --target LorieNative --parallel "$JOBS"

LIBS=(
  libLorieNative.a libpixman.a libXfont2.a libfontenc.a libxshmfence.a
  libxshmfence_pthread.a libxshmfence_futex.a libxkbcomp.a libxserver_glx.a
  libxserver_glxvnd.a libxserver_fb.a libxserver_mi.a libxserver_dix.a
  libxserver_composite.a libxserver_damageext.a libxserver_dbe.a
  libxserver_randr.a libxserver_miext_damage.a libxserver_render.a
  libxserver_present.a libxserver_xext.a libxserver_dri3.a
  libxserver_miext_sync.a libxserver_xfixes.a libxserver_xi.a
  libxserver_xkb.a libxserver_record.a libxserver_xi_stubs.a
  libxserver_xkb_stubs.a libxserver_os.a libtirpc.a libXdmcp.a libXau.a
  libmd.a libxserver_exa.a
)
for lib in "${LIBS[@]}"; do
    [[ -s "$BUILD/$lib" ]] || {
        echo "engine build is missing $BUILD/$lib" >&2
        exit 1
    }
done

if [[ -n "${GITHUB_ENV:-}" ]]; then
    {
        echo "ENGINE_CHECKOUT=$ENGINE"
        echo "ENGINE_BUILD=$BUILD"
        echo "ENGINE_LOCK_COMMIT=$PIN"
    } >> "$GITHUB_ENV"
fi
echo "magicdesk-x11 $PIN built: $BUILD"
