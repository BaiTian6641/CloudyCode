# Cloudy-X11 third-party components

This file is a working inventory, not the final release notice/SBOM.

| Component | Version/source | License | Use |
|---|---|---|---|
| `magicdesk-x11` | commit `65c3ced09a26d0df15085c72010af242cd660a94`, `upstream/engine.lock.json` | GPL-3.0 | Embedded X server/renderer (`libLorieNative.a`, `cloudy_x11_jni.so`) |
| X.Org server and bundled libraries | pinned magicdesk submodules/build recipes | X.Org/MIT-style; see engine `LICENSE` and submodule notices | X protocol, XKB, pixman, fonts, etc. |
| xkeyboard-config XKB data | host `/usr/share/X11/xkb` tree packaged as `assets/xkb.zip` on 2026-10-18 | MIT-style; verify the exact upstream version and notices before release | `XKB_CONFIG_ROOT` for the engine |
| Android NDK runtime | NDK `27.3.13750724`, API 34 | NDK license; static libc++ linked into the JNI `.so` | arm64 native build |

The engine checkout is git-ignored; the pin and build recipe are tracked. GPLv3
corresponding source includes the pinned engine, submodules/dependencies, the
Cloudy-X11 host adapter (`app/src/main/cpp`), the JNI/build script and the exact
XKB archive recipe. Do not publish a binary without the source offer and notices.
