#!/usr/bin/env bash
# Build the Cloudy-X11 JNI bridge into the pinned LorieNative static library.
# Development build: uses the local ignored engine checkout and its configured
# Ninja build. The resulting .so is packaged from app/src/main/jniLibs.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
ENGINE="${ENGINE_CHECKOUT:-$ROOT/upstream/checkouts/magicdesk-x11}"
BUILD="${ENGINE_BUILD:-$ENGINE/build}"
NDK="${ANDROID_NDK_HOME:-$HOME/Android/Sdk/ndk/27.3.13750724}"
OUT="$ROOT/android/cloudy-x11/app/src/main/jniLibs/arm64-v8a"
CXX="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/clang++"
[ -x "$CXX" ] || { echo "missing NDK clang++: $CXX" >&2; exit 1; }
[ -f "$BUILD/libLorieNative.a" ] || { echo "missing $BUILD/libLorieNative.a" >&2; exit 1; }
mkdir -p "$OUT"
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
DEPS=()
for lib in "${LIBS[@]:1}"; do DEPS+=("$BUILD/$lib"); done
"$CXX" --target=aarch64-none-linux-android34 \
  --sysroot="$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot" \
  -DANDROID -fPIC -O2 -g -fdata-sections -ffunction-sections \
  -fstack-protector-strong -std=c++17 -shared \
  -I"$ENGINE/lorie/src/main/cpp/lorie" -I"$ENGINE/lorie/src/main/cpp" \
  "$ROOT/android/cloudy-x11/app/src/main/cpp/cloudy_x11_jni.cpp" \
  "$ROOT/android/cloudy-x11/app/src/main/cpp/cloudy_x11_renderer.cpp" \
  "$BUILD/libLorieNative.a" \
  -Wl,--whole-archive "${DEPS[@]}" -Wl,--no-whole-archive \
  -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384 \
  -Wl,--build-id=sha1 -Wl,--gc-sections -Wl,--no-undefined \
  -static-libstdc++ -landroid -llog -lm -lz -latomic \
  -o "$OUT/libcloudy_x11_native.so"
echo "built $OUT/libcloudy_x11_native.so"
