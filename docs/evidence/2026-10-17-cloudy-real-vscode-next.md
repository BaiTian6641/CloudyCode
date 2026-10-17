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

## Extension catalog reality (2026-10-17)

The user asked for "VS Code official plugins plus Open VSX". Facts checked against the registries:

- `ms-vscode.vscode-serial-monitor`, `ms-vscode.cpptools`, `ms-vscode.js-debug`, `ms-vscode-remote.remote-ssh` are **not** on Open VSX (HTTP 404).
- The Microsoft Marketplace service is licensed for Microsoft products; using it from a third-party client is not permitted, and the engineering plan explicitly forbids spoofing Marketplace access.
- Open VSX does host viable alternatives, e.g. `eclipse-cdt.serial-monitor` ("Web and Desktop Serial Monitor", 2.0.0) and the Jupyter/Table Visualizer entries already visible in our gallery.

Supported paths, in order:

1. **Open VSX** in the app-owned workbench (implemented, verified: Popular list + Install).
2. **VSIX sideload** for extensions the user has rights to (workbench "Install from VSIX…"; verified previously on the workstation lane).
3. **Bundled MIT extensions built from Microsoft's own repositories** where useful. `microsoft/vscode-serial-monitor` is MIT licensed, so a build/bundle step is legally possible; it still needs guest USB access to see a port (below). A self-hosted gallery aggregating Open VSX + redistributable VSIXs is a later option; the workbench supports one gallery service per client.

## Backend auto-start

The one-time build script now enables the **systemd user unit** (`cloudy-code.service`) and `loginctl enable-linger` for the VM user. The first app "Copy Cloudy backend start command" seeds the app's connection token and restarts the unit; afterwards the backend starts with the VM. Fallback when systemd is unavailable: the same command runs `start-backend.sh` in the foreground. The Terminal app is still required for the VM→Android port bridge.

## USB host spike on the real Googlebook (2026-10-17, ESP32-S3 attached)

Device: `303a:1001` "USB JTAG/serial debug unit", serial `24:EC:4A:07:FD:A8`, 3 interfaces:

| Interface | Class | Endpoints |
|---|---|---|
| 1.0 | `0x02` CDC communication | `0x82` interrupt IN |
| 1.1 | `0x0a` CDC data | `0x01` bulk OUT, `0x81` bulk IN |
| 1.2 | `0xff` vendor (JTAG) | `0x02` bulk OUT, `0x83` bulk IN |

`UsbInventoryActivity` (native host spike, exported for diagnostics) proves:

1. Enumeration of all host devices.
2. Android 17 requires the per-device USB permission even to read names/descriptors (`SecurityException` when absent; the scan now guards this instead of crashing).
3. `requestPermission` + user grant exposes the full descriptor tree.
4. `openDevice` + `claimInterface(CDC data, force)` succeeds; a 1 s bulk read on `0x81` returns 0 bytes (no firmware output yet) without error. Read-only: no writes, no DTR/RTS toggling (which could reset the target).

This is the Android half of the hardware broker (S07.02). Remaining gate: guest-side **VHCI/USB/IP import** (or the documented serial fallback if the VM kernel lacks `vhci_hcd`). PlatformIO in the VM cannot see the board until forwarding exists.

Guest probe command (read-only) to run once in the Terminal:

```
command -v usbip; ls /sys/module/vhci_hcd >/dev/null 2>&1 && echo VHCI_LOADED; ls /lib/modules/$(uname -r)/kernel/drivers/usb/usbip 2>/dev/null; uname -r
```

## Serial forwarding implemented end-to-end (2026-10-17)

Serial lane for ESP32-S3 embedded work (explicitly not USB/IP or JTAG):

| Piece | Role |
|---|---|
| `SerialBridgeService` (APK) | Foreground `connectedDevice` service; claims the CDC data interface; connects to the Terminal-forwarded guest port `127.0.0.1:7001`; authenticates with the app token (`CLOUDY-SERIAL 1 <token>`); relays framed data both ways and maps RFC2217 control bitmaps to CDC `SET_CONTROL_LINE_STATE` (DTR/RTS). |
| `tools/device/cloudy-serial-bridge.py` (guest) | Listens on `0.0.0.0:7001` for the app (forwarded by the Terminal app), authenticates the token, then serves **RFC 2217** on `127.0.0.1:7002` with BINARY/COM-PORT negotiation and setting acknowledgements. |
| Client tools | `esptool`/PlatformIO/pyserial use `rfc2217://127.0.0.1:7002` (upload and monitor). |

Wire protocol: `[type:1][len:2 BE][payload]`; 0x01 app→guest data, 0x02 guest→app data, 0x03 control bitmap (bit0 DTR, bit1 RTS), 0x04 keepalive.

Verified:

1. Local bridge unit test with pyserial: negotiation completes, app→guest and guest→app data, control notifications.
2. **Real board, full chain** (workstation bridge via `adb reverse` standing in for the VM port forward): app authenticated, pyserial opened `rfc2217://127.0.0.1:7002`, DTR/RTS toggles produced CDC control transfers in the app log (`dtr=1 rts=1`, `dtr=1 rts=0`, …), and a 2-byte write exercised the CDC OUT path. No data was read because the board emits nothing yet.
3. USB permission/claim/read on the device (previous section).

Usage in the VM (after copying "Copy guest bridge command" from the USB screen into the Terminal and tapping Start serial forwarding):

```
esptool --port rfc2217://127.0.0.1:7002 chip_id
# PlatformIO: upload_port = rfc2217://127.0.0.1:7002
#             monitor_port = rfc2217://127.0.0.1:7002
```

Limits: serial only (no JTAG or arbitrary USB devices); requires the Terminal app to stay open for the port forward; the app must hold the USB device (a second Android app cannot claim it concurrently). USB/IP + VHCI remains the transparent path for JTAG/PlatformIO debugging.

## Operating constraint

The Terminal app owns the VM port bridge; closing it removes the forwarded port (the app's reconnect surface handles this and offers Open Terminal). A VM-side systemd service keeps the *server* alive but not the bridge; a preinstalled build could start the VM command itself (`MANAGE_TERMINAL`).
