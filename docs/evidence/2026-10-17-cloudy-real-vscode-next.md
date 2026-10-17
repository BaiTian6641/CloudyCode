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

## Arbitrary USB: USB/IP exporter + relay implemented (2026-10-17)

Transparent lane for unmodified Linux drivers/tools (JTAG, esptool, OpenOCD, arbitrary USB):

| Piece | Role |
|---|---|
| `UsbIpService` (APK) | Connects out to the guest relay (Terminal-forwarded `127.0.0.1:3241`), token-authenticates (`CLOUDY-USBIP 1 <token>`), and serves USB/IP over multiplexed streams. Implements DEVLIST, IMPORT and URB submit for **control, bulk and interrupt** (no isochronous). Claims the device interfaces on IMPORT; releases on close. |
| `tools/device/cloudy-usbip-relay.py` (guest) | Token-authenticated mux relay: app on `0.0.0.0:3241`, local `usbip` clients on `127.0.0.1:3240`. Each client connection gets its own stream to the exporter. |
| USB screen | Start/Stop USB/IP forwarding + Copy relay command (script + app token). |

Protocol verified against the **real ESP32-S3** through the app (workstation relay via adb reverse):

- DEVLIST: `cloudy-303a-1001`, idVendor `0x303a`, idProduct `0x1001`, full speed, 3 interfaces.
- IMPORT: status 0, device struct correct.
- Control `GET_DESCRIPTOR(Device)`: 18 bytes, `12 01 00 02 ef 02 01 40 3a 30 01 10 …` (correct VID/PID).
- CDC bulk IN: status 0, actual 0 (no firmware output).

Guest attach (needs root + kernel support; the remaining gate):

```
python3 ~/cloudy-usbip-relay.py --token <app token>   # keep the Terminal open
# app: Start USB/IP forwarding
sudo modprobe vhci-hcd
sudo usbip attach -r 127.0.0.1 -b cloudy-303a-1001
```

Limits: only the Espressif device is exported for now; isochronous transfers unsupported; if the VM kernel lacks `vhci_hcd` or module loading is blocked, the transparent path is unavailable and the serial lane remains. Bulk OUT/control OUT and interrupt paths are implemented but not yet exercised against a class driver.

## Frontend auto-detection and attach (2026-10-17)

The workbench now watches for backends independently of the launch screen:

- Every 4 s (2 s while a surface is visible) it probes `8085` (pinned Cloudy) and `8080` (code-server) off the main thread.
- **Local/offline workbench** (`upstream == null`): when a backend appears, `ConnectionOverlay` shows **Linux workspace found → Attaching…** with a "Stay offline" cancel; after 1.8 s the app switches (`startActivity` with an `attaching` hint, so the Cloudy overlay covers the reload instead of a white flash). The new activity keeps the overlay until the first `onPageFinished`.
- **Attached to code-server** and `8085` appears: a non-blocking Cloudy **banner** offers "Cloudy Code backend available (recommended)" with Switch / Not now.
- **Attached but its backend is down** and the other backend answers: automatic switch with the same attaching surface.
- The startup screen already prefers `8085` then `8080` at launch.

Verified on the emulator: with the workbench in offline mode, starting a real code-server and exposing it (`adb reverse 8080`) made the app detect it and attach; the WebView became `Welcome - code-server` with assets proxied through the app gateway. (The shared emulator's other app stole focus during the later overlay/banner capture, so those two visuals are implemented but not screenshotted yet.)

Design: all surfaces reuse the Cloudy tokens/typography/radii from `ConnectionOverlay`; the banner uses the same card surface, primary "Switch" action and text "Not now".

## Guest auto-attach and app auto-forward (2026-10-17, real device)

The user's VM had the bridges running and forwarded (7001/7002, 3240/3241 visible on Android) but no serial port in the frontend: the guest had no VHCI attach, so no `/dev/ttyACM*` existed.

Added:

1. **`cloudy-setup.sh`** now installs `usbip` and a root system unit `cloudy-usbip-attach.service` running `/usr/local/bin/cloudy-usbip-attach`: `modprobe vhci-hcd`, then loops until `usbip list -r 127.0.0.1` finds `cloudy-303a-1001` (the relay serves the list only while the app's exporter is connected) and attaches it. On success `/dev/ttyACM*` exists and the serial monitor/PlatformIO can use it.
2. App: on workbench resume, if the ESP32-S3 is attached and permitted (and the serial lane is not in use), `UsbIpService` auto-starts, so the relay is served without opening the USB screen.
3. Entry point: the USB/IP foreground notification now opens the USB screen ("USB devices" action); that screen gained **Copy Linux setup command (services + auto-attach)** so the updated setup can be re-run even when the backend makes the startup screen unreachable.

Verified on the device: guest bridges forwarded; the app's exporter auto-started and has an established connection to the relay (127.0.0.1:3241). The remaining step is running the updated setup in the VM (installs the attach unit) — guest-side VHCI/kernel support is still the platform gate.

Backend state observed: only code-server on 8080 is listening; the pinned reh-web backend on 8085 is not running (build/service pending).

## Guest `usbip` missing: short attach patch and compressed updates (2026-10-17)

The VM reported `usbip: command not found`: the running setup predates the usbip install, and Debian packaging varies. Response:

1. `tools/device/cloudy-attach-patch.sh` — short, idempotent VM patch: tries `usbip`, `linux-tools-$(uname -r)`, `linux-tools-common`, `linux-tools-generic`; verifies the binary; `modprobe vhci-hcd` with a clear warning; installs `/usr/local/bin/cloudy-usbip-attach` + `cloudy-usbip-attach.service` (root) that waits for the relay to serve `cloudy-303a-1001` and attaches it; prints `usbip port` and `/dev/ttyACM*`.
2. The USB screen copies it ("Copy USB auto-attach patch (short)"), reachable from the USB/IP notification.
3. The full setup now also uses the same apt fallbacks/verification, and its copy is **gzip+base64 compressed**: the exact command is 8326 bytes instead of ~25 KB and was validated end-to-end (dry-run) from the generated string.
4. Update story: setup remains idempotent; the compressed one-liner is the current update path. A push-updater over the authenticated bridge (app -> guest) is the next step if copy-free updates are wanted.

## VHCI attach diagnostics (2026-10-17)

`cloudy-usbip-attach` runs but the VM reported `modprobe: not found`-style trouble. The helper now:

- installs `kmod`/`usbutils` from the patch (so `modprobe`/`lsusb` exist);
- has `--diagnose`: kernel release, modprobe/insmod presence, `/lib/modules/<release>/kernel/drivers/usb/usbip` contents, vhci sysfs path and status;
- tries `modprobe vhci-hcd`, then `insmod` on the distro module file; if still absent it logs the diagnostics and retries every 10 s instead of exiting;
- the patch prints the diagnostics after installation.

This distinguishes: missing `kmod` (fixable), missing module files, and a kernel built without `vhci_hcd` (transparent USB impossible; serial lane only).

## Decisive finding: the Terminal app provides native USB passthrough (2026-10-17)

The GMSC Terminal APK (`com.android.virtualization.terminal`, decompiled) contains a real USB passthrough path:

- `VmController.attachAllUsbDevices(vm)` runs when the VM starts;
- a hotplug receiver listens for `USB_DEVICE_ATTACHED`/`DETACHED`, checks a denylist (no allowlist files present on this device), requests the per-device USB permission, then `vm.attachUsbDevice(UsbDeviceConnection)`; logs under tag `VmTerminalApp` ("USB Device Attached", "Attaching USB device");
- no vhci/usbip involved: the VM receives the USB device directly.

Consequences:

1. **No `vhci_hcd` exists in this VM kernel** (`/sys/devices/platform/vhci_hcd*` missing; no kernel messages), so the USB/IP lane cannot work here regardless of tools.
2. Our app's USB/IP exporter (and serial bridge) **claim the device**, which blocks the Terminal app's attach — this is why the board never appeared in the VM while our forwarding was active.
3. The app no longer auto-starts the USB/IP exporter when the Googlebook Terminal app is installed; the native passthrough owns the device. The manual USB screen remains for other environments.
4. Remaining user-side step: replug the board (or restart the Terminal VM) with the app stopped so the Terminal app runs its attach flow, and accept its USB permission dialog. The device should then appear as `/dev/ttyACM*` (no usbip, no modprobe needed). The guest `cloudy-usbip-attach` unit is obsolete and can be disabled.

## Terminal USB passthrough is stubbed; serial lane generalized and verified (2026-10-17)

Definitive evidence from the shipped Terminal APK (build CL3B) and live logs:

- `VmController.attachAllUsbDevices(vm)` is an **empty method** in the APK.
- The hotplug receiver registration is present in decompiled code, but never logs "Dynamic USB hotplug broadcast receiver registered" and the receiver is absent from `dumpsys activity broadcasts`; the VM start log shows no USB activity. Replugging with the VM running produces a kernel USB attach event but no `VmTerminalApp` handling.
- Conclusion: this Terminal build **does not attach USB devices to the VM**. No `/dev/tty*` will appear from USB, independent of the app. The earlier plan's platform gate (guest USB support) is not met.

Pivot (working today): the serial lane now supports **any** CDC device, not just Espressif.

- `SerialDevicePicker`: Espressif with CDC → composite multi-UART device (≥4 interfaces) → any CDC data device; internal VIDs (hub/camera/FPMCU) excluded.
- With the SLogic UARTx4 (`359f:3101`, 4 CDC pairs) the USB screen shows the device, grants permission, and the serial bridge logs `bridge connected; CDC claimed` (first CDC data interface, endpoints 0x01/0x81). It authenticates to the guest relay at 7001 (ESTAB verified) and the guest bridge serves RFC 2217 on `127.0.0.1:7002`.
- esptool 5.1 in PlatformIO **supports RFC2217** (`serial.serial_for_url`, RFC2217-aware reset paths), so upload/flash and monitor work over the bridge; pyserial's monitor likewise.

Limits: only the first of the SLogic's four UART channels is forwarded; auto-reset depends on the SLogic mapping DTR/RTS to the target; a kernel TTY (for port pickers) requires platform USB passthrough, which this build does not provide.

## Decision: USB forwarding is deferred (2026-10-17)

After hands-on attempts with an ESP32-S3 and a SLogic UARTx4:

- the Terminal build does **not** pass USB devices into the VM (empty `attachAllUsbDevices`, hotplug receiver never registered);
- the VM kernel has **no `vhci_hcd`** (no sysfs/module), so USB/IP cannot attach;
- the serial bridge works mechanically but is a fallback with limited ergonomics (no kernel TTY for port pickers, first channel only).

Decision: **punt USB forwarding**. The product returns to the core: app-owned frontend, backend connection/reconnect, extension gallery, and the guest backend setup.

- The app no longer exposes USB entries (startup button removed, notification action removed, no auto-start/claim).
- `cloudy-setup.sh` defaults to **no USB services**; existing experimental units/files are disabled/removed on rerun. `--with-usb` re-enables the experiment.
- USB code remains in the tree (diagnostic `UsbInventoryActivity`, serial/USB-IP services) for later work if the platform enables passthrough or a vhci-capable kernel.

Revisit when Google ships USB passthrough in the Terminal VM or a vhci-capable guest kernel.

## Operating constraint

The Terminal app owns the VM port bridge; closing it removes the forwarded port (the app's reconnect surface handles this and offers Open Terminal). A VM-side systemd service keeps the *server* alive but not the bridge; a preinstalled build could start the VM command itself (`MANAGE_TERMINAL`).
