# Cloudy Code: extension gallery fix; real Code-OSS backend and serial/USB path (2026-10-17)

## Fixed: the app-owned workbench had no extension gallery

The OSS `product.json` (pinned 1.141.0) has `extensionsGallery: null`. In the installed-frontend mode the workbench therefore showed an empty Extensions view, while the code-server proxy mode showed one (code-server injects its own product). The client now receives a supported `productConfiguration` through the workbench web configuration (`WebAssets.renderWorkbench`):

- branding: `nameShort`/`nameLong` = Cloudy Code, `applicationName` = cloudy-code
- Open VSX gallery: `serviceUrl`, `itemUrl`, `publisherUrl`, `resourceUrlTemplate`, `controlUrl`, `nlsBaseUrl`
- CSP (`Gateway.indexCsp`) allows `https://open-vsx.org`, `https://*.open-vsx.org` and `https://openvsx.eclipsecontent.org` for `connect-src`/`img-src`

Verified on the emulator, local app-owned workbench: Extensions view shows **Installed 0 / Popular** with real Open VSX entries (Salesforce, Table Visualizer, Jupyter Cell Tags, Jupyter Slideshow, Peripheral Viewer) and Install actions; zero CSP violations after the CDN allowance. Microsoft Marketplace is not used anywhere (not licensed for non-Microsoft products); Open VSX plus VSIX sideloading remains the supported path.

Note: in pure browser (no backend) mode only web extensions can activate. Node/desktop extensions install and run in the guest only with the remote backend — the next item.

## Real Code-OSS backend: the arm64 artifact is incomplete

`~/.cache/gbcode/upstream/vscode-reh-web-linux-arm64` exists with:

- arm64 `node` (Node 24.x), same commit `2a59476c…` (1.141.0)
- arm64 platform packages (`@github/copilot-sdk-linux-arm64`, `@microsoft/mxc-sdk/bin/arm64`, `@vscode/ripgrep-universal/bin/linux-arm64`)

but its **native addons are x86-64**: `node-pty`, `@vscode/spdlog`, `@parcel/watcher`, `@vscode/native-watchdog`, `@vscode/sqlite3`, `kerberos` (and `@vscode/deviceid` Windows-only). The artifact therefore cannot run in the arm64 Debian VM. Cross-compiling on this workstation is blocked: no `aarch64-linux-gnu` toolchain and no non-interactive sudo.

Path A (recommended): build inside the VM (arm64, native):
1. Node 24.18.0 (official arm64 tarball) + apt build deps (`build-essential`, `python3`, `pkg-config`, `libkrb5-dev`, `libsecret-1-dev` as required by the build).
2. `npm ci` in the pinned source; `npx gulp vscode-reh-web-linux-arm64`.
3. Package to `$HOME/.local/share/cloudy/reh-web`; start with `--host 127.0.0.1 --port 8085 --connection-token-file …`; optional systemd user unit so it starts with the VM.
4. The app already prefers 8085 (reh-web: installed frontend + remote agent + the gallery above) and falls back to 8080 (code-server) and local mode.

Path B: cross-build in CI with an aarch64 sysroot/toolchain. Path C (not adopted): Microsoft's proprietary server build.

## Serial / USB forwarding

Device audit (HP Googlebook 14, Android 17): USB host support exists (`/sys/bus/usb`), currently no external USB device (`host_connected=false`; only the FPMCU and camera on the bus). The Linux VM has no USB access today.

Plan default (already specified in the engineering plan): Android userspace **USB/IP exporter** in the APK (authorized `UsbManager` access, per-device permission, foreground service, lease model) + **VHCI import** inside the VM, so normal Linux drivers expose `/dev/ttyUSB*`/USB devices to unmodified extensions. The guest kernel's `vhci_hcd` availability on Google's booted VM is the early gate. Serial fallbacks (API-37 `SerialManager`, `usb-serial-for-android`, RFC2217) are explicitly not equivalent to transparent USB.

Next concrete steps:
1. Plug an ESP32-S3 (native USB) or a USB-serial adapter into the Googlebook and record VID/PID + Android permission behavior.
2. Probe the VM for `vhci_hcd`/`usbip`; if absent, fall back to the selected-adapter scope.
3. Implement the exporter spike (enumerate → claim → USB/IP over the authenticated local route) and one certified serial workflow in the VM.

## Operating constraint

The Terminal app owns the VM port bridge; closing it removes the forwarded port (the app's reconnect surface handles this and offers Open Terminal). A VM-side systemd service keeps the *server* alive but not the bridge; a preinstalled build could start the VM command itself (`MANAGE_TERMINAL`).
