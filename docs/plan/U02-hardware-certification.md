# U02 — ESP32-S3 and external programmer certification

[Plan index](README.md) · [Architecture specification](../Googlebook_CodeOSS_APK_Engineering_Plan_v2.md)

**Status:** planned; no implementation or device acceptance is claimed. **Default profile:** `wsl_usbip_desktop`. **Packages:** 8 scheduled, 0 excluded alternatives.

**Purpose:** Prove native serial, flashing, JTAG, concurrent interface use and one separate external adapter through normal guest tools.

**Exit gate:** No hardware success is inferred from enumeration alone; real debug/program workflows and failure recovery pass.

## Entry and execution contract

Use production U01.10, exact hardware profiles and approved safe firmware. Native ESP32-S3 CDC/JTAG and a separate external adapter/target are all mandatory; identify connector and electrical limits before tests.

An authorized operator names the board, operation and artifact hash before programming/reset. Normal guest esptool, Espressif OpenOCD/GDB and independently installed extensions access real imported Linux devices; no per-extension transport patch earns default acceptance.

## End-to-end smoke proof

On the native S3 controller: discover CDC, exchange integrity-checked serial data, program and verify safe firmware, halt at a source breakpoint, step/read state, and retain serial output during JTAG. Repeat an actual program/debug operation with a named external adapter/target, then exercise revoke, unplug, sleep and re-enumeration without replay.

**Required evidence:** Exact connectors/pinouts/voltages/tools/firmware; readback or equivalent programmed-result proof; debugger trace; concurrent serial integrity; independent adapter workflow; physical resilience matrix and approval.

**Failure containment:** Any missing serial/flashing/JTAG/concurrency/external-adapter capability blocks the default. Do not accept an external UART connector, TinyUSB profile, lsusb output, synthetic fixture or alternate VM as the native-controller result.

## Work packages

Dependencies below are effective for the stated profile, not stage-number order. Every implementation checkbox is initially open. Deliverable paths name outputs to create during implementation; they are not claims that those files exist. A package is complete only with its acceptance result, evidence and independent review.

## U02.01

**Create exact board, connector and adapter profiles**

**Status:** Planned; not started. Physical-device evidence required.

**Dependencies (default):** [U00.02](U00-usbip-feasibility.md#u0002), [U00.03](U00-usbip-feasibility.md#u0003)

**Owner:** HARDWARE/RESEARCH.

**Deliverable:** `hardware/profiles/ + documented test fixtures`.

- [ ] Identify actual ESP32-S3 board/controller connector, firmware mode, cable/hub and independent external JTAG adapter/target.
- [ ] Record descriptors, interface/backend selection, required guest drivers, architecture and tool licensing.
- [ ] Obtain target pinout/voltage from primary board/adapter documentation and approve safe test programs; avoid guessed wiring.
- [ ] Keep external UART, built-in Serial/JTAG and firmware USB-OTG modes as different profiles.

**Accept:** The hardware inventory is exact enough to reproduce tests and does not assume all similarly named adapters behave alike.

**Rollback/containment:** Remove uncertain profiles and stop hardware writes until identity/wiring is established.

**Reopen before coding:** [R38] [R91] [R92] [R94] [R95].

## U02.02

**Certify native CDC serial and real guest discovery**

**Status:** Planned; not started. Physical-device evidence required.

**Dependencies (default):** [U01.10](U01-production-usbip.md#u0110), [U02.01](U02-hardware-certification.md#u0201)

**Owner:** HARDWARE/GUEST.

**Deliverable:** `tests/evidence/esp32s3-cdc/`.

- [ ] Confirm actual imported descriptors, CDC driver, sysfs identity and serial node as the normal development user.
- [ ] Run bidirectional monitor traffic with a normal guest client and user-installed compatible extension.
- [ ] Exercise open/close, disconnect, stale node resolution and another same-model board without fake discovery metadata.
- [ ] Verify no RFC2217/custom transport patch is required for this certified default path.

**Accept:** The normal Linux serial workflow works through USB/IP with real device discovery and documented permissions.

**Rollback/containment:** Close serial clients, detach only the selected device and preserve exact discovery failures.

**Reopen before coding:** [R16] [R34] [R83] [R91].

## U02.03

**Certify VM-native ESP32-S3 flashing**

**Status:** Planned; not started. Physical-device evidence required.

**Dependencies (default):** [U02.02](U02-hardware-certification.md#u0202)

**Owner:** HARDWARE/GUEST.

**Deliverable:** `tests/evidence/esp32s3-flash/`.

- [ ] Pin compatible ESP-IDF/esptool and guest toolchain versions; identify the selected physical native serial device.
- [ ] With explicit authorization, build and flash a disposable test project through the real imported port using normal guest tools.
- [ ] Verify firmware behavior and target identity, not just process exit status; log transfer timing without secret payloads.
- [ ] Test controlled interruption/recovery on expendable hardware and forbid automatic destructive retry.

**Accept:** An actual VM-built firmware is programmed and verified through the USB/IP native serial path, with safe failure handling.

**Rollback/containment:** Stop uncertain operations and use only documented, user-approved recovery; never modify security fuses.

**Reopen before coding:** [R33] [R91] [R92].

## U02.04

**Certify Espressif OpenOCD native JTAG**

**Status:** Planned; not started. Physical-device evidence required.

**Dependencies (default):** [U01.10](U01-production-usbip.md#u0110), [U02.01](U02-hardware-certification.md#u0201)

**Owner:** HARDWARE/DEBUG.

**Deliverable:** `tests/evidence/esp32s3-openocd/`.

- [ ] Use Espressif-supported OpenOCD and board/adapter config matched to the pinned ESP-IDF release, not an assumed generic package.
- [ ] Run an explicitly authorized target attach through normal guest libusb access and verify actual device/interface selection.
- [ ] Test identification, halt/resume, known breakpoint and clean detach while retaining logs and device state.
- [ ] Record which USB control/reset behaviors are required and whether the exporter supports them.

**Accept:** Real built-in ESP32-S3 JTAG operations pass using guest OpenOCD without an Android debug-server substitute.

**Rollback/containment:** Detach carefully, preserve uncertain target state and require documented recovery on failure.

**Reopen before coding:** [R38] [R92] [R93] [R94] [R95].

## U02.05

**Run a normal installed extension through GDB and OpenOCD**

**Status:** Planned; not started. Physical-device evidence required.

**Dependencies (default):** [U02.04](U02-hardware-certification.md#u0204), [S08.02](S08-extension-ecosystem.md#s0802)

**Owner:** EXTENSIONS/DEBUG.

**Deliverable:** `tests/evidence/esp32s3-workbench-debug/`.

- [ ] Install a lawful compatible debug extension through the ordinary Extensions view rather than preloading a bespoke debugger UI.
- [ ] Verify guest debug-adapter, GDB, OpenOCD and target architecture/version alignment and actual process placement.
- [ ] Set a source breakpoint, launch/attach, step, inspect authorized state and detach through stock Run and Debug controls.
- [ ] Preserve launch configuration and profile across reconnect without saving credentials in the shared source tree.

**Accept:** The original workbench performs a real source-debug session using Linux backend tools and the imported USB device.

**Rollback/containment:** Stop session and restore prior extension/config version without auto-resetting the target.

**Reopen before coding:** [R16] [R17] [R21] [R93] [R95].

## U02.06

**Certify simultaneous serial console and JTAG**

**Status:** Planned; not started. Physical-device evidence required.

**Dependencies (default):** [U02.03](U02-hardware-certification.md#u0203), [U02.04](U02-hardware-certification.md#u0204), [U02.05](U02-hardware-certification.md#u0205)

**Owner:** HARDWARE/QA.

**Deliverable:** `tests/evidence/esp32s3-concurrent/`.

- [ ] Run known log-producing firmware with an ordinary serial client and a simultaneous JTAG debug session.
- [ ] Observe serial before/during/after halt/continue, accounting for expected CPU halt rather than blaming transport for absent firmware-generated logs.
- [ ] Exercise independent interface open/close and confirm no exporter whole-device mutex or queue starvation breaks the other interface.
- [ ] Test one interface failure and documented device-global reset behavior with explicit ownership rules.

- [ ] Keep both serial and JTAG active on the native Serial/JTAG controller; record sleep, reset, pin/PHY reconfiguration and firmware modes that remove the device. Whole-device reset affects both interfaces and requires coordinated recovery.

**Accept:** Physical evidence shows real concurrent CDC/JTAG operation; two sequential demonstrations do not pass.

**Rollback/containment:** Stop both clients and revoke import when global state is uncertain; do not replay pending writes.

**Reopen before coding:** [R34] [R91] [R93] [R94].

## U02.07

**Certify one independent external JTAG or programmer**

**Status:** Planned; not started. Physical-device evidence required.

**Dependencies (default):** [U02.01](U02-hardware-certification.md#u0201), [U01.10](U01-production-usbip.md#u0110)

**Owner:** HARDWARE/GUEST.

**Deliverable:** `tests/evidence/external-programmer/`.

- [ ] Choose the actual available external probe and target, distinguishing HID/bulk CMSIS-DAP, FTDI MPSSE, ST-Link or a vendor cable.
- [ ] Verify guest driver/libusb/HID access, tool ABI and license, required reset/config semantics and correct target wiring.
- [ ] Perform a meaningful approved target program/debug operation through the normal Linux tool and USB/IP path.
- [ ] Document unsupported families and test detach/reconnect; built-in ESP32-S3 JTAG alone does not satisfy this task.
- [ ] Certify this profile independently of S3-specific JTAG success. A SWD adapter needs its own supported target; ESP32-S3 does not support SWD. Record voltage/pinout evidence and never alter security/debug eFuses to force a transport test.

**Accept:** One named external adapter/firmware/target/tool combination is physically certified independently of the S3 built-in controller.

**Rollback/containment:** Stop unsafe hardware operations and remove unsupported profile claims; no arbitrary pinout or root workaround.

**Reopen before coding:** [R38] [R34] [R83].

## U02.08

**Approve the hardware workflow and resilience matrix**

**Status:** Planned; not started. Physical-device evidence required.

**Dependencies (default):** [U02.06](U02-hardware-certification.md#u0206), [U02.07](U02-hardware-certification.md#u0207), [S09.05](S09-lifecycle-and-recovery.md#s0905)

**Owner:** HARDWARE/QA.

**Deliverable:** `hardware/compatibility.json + reviewed release evidence`.

- [ ] Aggregate serial, flash, JTAG, simultaneous-interface and external-adapter evidence using exact OS/app/kernel/tool versions.
- [ ] Exercise suspend, Terminal closure, service death, cable removal, permission revocation and two-device behavior with honest not-run outcomes.
- [ ] Compare performance with a direct Linux control where available and label all proposed thresholds versus measurements.
- [ ] Block full-profile release on missing mandatory hardware, security or recovery evidence; no serial-only substitution.

**Accept:** All mandatory hardware workflows and failure policies have independent physical evidence and a bounded compatibility matrix.

**Rollback/containment:** Hold release and publish exact blocked capabilities; preserve known-working versions for development.

**Reopen before coding:** [R01] [R34] [R35] [R38] [R91] [R93].

## Source refresh

Start with the [dated hardware review](../research/2026-10-16-hardware-review.md), then reopen the task-specific references before implementation and on relevant version changes. Inherited links are research starting points, not a claim that all 98 sources were revalidated in this review. Mutable documentation is not a dependency lock.

[R01]: https://developer.android.com/blog/posts/googlebook-linux-terminal-preview-vm
[R16]: https://code.visualstudio.com/api/advanced-topics/extension-host
[R17]: https://code.visualstudio.com/api/advanced-topics/remote-extensions
[R21]: https://code.visualstudio.com/api/references/vscode-api
[R33]: https://docs.espressif.com/projects/esptool/en/latest/esp32/remote-serial-ports.html
[R34]: https://docs.kernel.org/usb/usbip_protocol.html
[R35]: https://github.com/torvalds/linux/blob/master/drivers/usb/usbip/Kconfig
[R38]: https://openocd.org/doc/html/Debug-Adapter-Configuration.html
[R83]: https://learn.microsoft.com/en-us/windows/wsl/connect-usb
[R91]: https://docs.espressif.com/projects/esp-idf/en/stable/esp32s3/api-guides/usb-serial-jtag-console.html
[R92]: https://docs.espressif.com/projects/esp-idf/en/stable/esp32s3/api-guides/jtag-debugging/configure-builtin-jtag.html
[R93]: https://docs.espressif.com/projects/esp-idf/en/stable/esp32s3/api-guides/jtag-debugging/index.html
[R94]: https://raw.githubusercontent.com/espressif/openocd-esp32/master/tcl/interface/esp_usb_jtag.cfg
[R95]: https://raw.githubusercontent.com/espressif/openocd-esp32/master/tcl/board/esp32s3-builtin.cfg
