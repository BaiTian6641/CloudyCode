# S06 — Optional serial compatibility fallback

[Plan index](README.md) · [Architecture specification](../Googlebook_CodeOSS_APK_Engineering_Plan_v2.md)

**Status:** planned; no implementation or device acceptance is claimed. **Default profile:** `wsl_usbip_desktop`. **Packages:** 0 scheduled, 6 excluded alternatives.

**Purpose:** Support a clearly narrowed RFC2217/serial product or diagnostic route; not the default USB/IP JTAG fulfillment.

**Exit gate:** Every shipped fallback workflow has explicit limitations and physical evidence.

## Entry and execution contract

Excluded from wsl_usbip_desktop. Execute only after an explicit diagnostic/reduced-profile decision and a selected, certified Android serial backend; activation requires review of all optional dependencies.

RFC2217 transports serial semantics, not USB identity or JTAG. Current esptool documentation excludes native USB Serial/JTAG and USB-OTG from RFC2217/socket URLs. PTY/CUSE and per-tool patches are separately bounded alternatives.

## End-to-end smoke proof

For an explicitly supported USB-UART fixture, run guest pySerial configuration/round-trip, a permitted monitor and an approved UART-backed flashing workflow. Change DTR/RTS, test negotiation errors, stall/revoke/detach and verify no unsafe transfer replay. Do not use this report as native S3 certification.

**Required evidence:** Approved reduced-profile scope; exact adapter/backend/tool versions; applicable SER tests and stream/control limits; measured reset/flash outcome; explicit missing JTAG/native-USB capabilities.

**Failure containment:** Unavailable modem controls or discovery features remain unsupported. Fallback success never opens the default hardware release gate; no silent switch from USB/IP, no fake device metadata and no root Android driver.

## Work packages

Dependencies below are effective for the stated profile, not stage-number order. Every implementation checkbox is initially open. Deliverable paths name outputs to create during implementation; they are not claims that those files exist. A package is complete only with its acceptance result, evidence and independent review.

## S06.01

**Implement and certify the guest RFC2217 service**

**Status:** Excluded alternative; not scheduled for the default release.

**Dependencies (optional-profile base):** [S05.04](S05-hardware-authority.md#s0504), [S05.05](S05-hardware-authority.md#s0505)

**Owner:** GUEST/PROTOCOL.

**Deliverable:** `guest/rfc2217 and golden vectors`.

- [ ] Implement or integrate a license-compatible RFC2217 state machine with Telnet escaping and serial control negotiation.
- [ ] Bind only to guest loopback, tie each endpoint to a lease and route operations through the authenticated broker channel.
- [ ] Test pySerial clients, control acknowledgments, malformed negotiations, timeout and concurrent-client rejection.

**Accept:** A compatible client configures and exchanges binary data correctly; raw TCP behavior is not mislabeled RFC2217.

**Rollback/containment:** Close endpoints and release leases; preserve the first-party broker interface.

**Reopen before coding:** [R32] [R74].

## S06.02

**Build the first-party serial monitor extension**

**Status:** Excluded alternative; not scheduled for the default release. Physical-device evidence required.

**Dependencies (optional-profile base):** [S06.01](S06-optional-serial-fallback.md#s0601), [S05.06](S05-hardware-authority.md#s0506), [S04.02](S04-desktop-experience.md#s0402)

**Owner:** WEB/GUEST.

**Deliverable:** `extensions/gbcode-serial-monitor`.

- [ ] Implement device chooser, permission state, configurable terminal/panel, line endings, text/hex views and bounded history.
- [ ] Keep processing/logging in the guest and hardware I/O in Android; batch display updates.
- [ ] Test disconnect, pause-display behavior, export, large input and monitor-to-flasher lease handoff.

**Accept:** A user can monitor a certified device without data-path ambiguity or unbounded UI memory.

**Rollback/containment:** Disable the extension and release its lease without affecting unrelated workbench features.

**Reopen before coding:** [R19] [R21] [R32].

## S06.03

**Validate esptool identification and flashing from Debian**

**Status:** Excluded alternative; not scheduled for the default release. Physical-device evidence required.

**Dependencies (optional-profile base):** [S06.01](S06-optional-serial-fallback.md#s0601), [S05.04](S05-hardware-authority.md#s0504)

**Owner:** HARDWARE/QA.

**Deliverable:** `certified serial-board workflow`.

- [ ] Pin esptool and verify its current CLI; begin with chip/flash identification on a user-selected board.
- [ ] Validate reset/control timing, then flash a known-good user-approved image and verify outcome using supported tool behavior.
- [ ] Repeat across safe baud settings and test bootloader re-enumeration, timeout and monitor ownership switching.

- [ ] For RFC2217/socket transport, use a separately certified USB-UART adapter profile. Current esptool documentation excludes USB-OTG and native USB Serial/JTAG for those URLs; do not count this fallback as native ESP32-S3 flashing or spoof its USB metadata.

**Accept:** Real VM-side identification, write and verification pass on the certified board; every destructive step has explicit approval.

**Rollback/containment:** Use the board-specific recovery procedure; never automatically replay an uncertain write.

**Reopen before coding:** [R33] [R74].

## S06.04

**Adapt selected serial extensions and PlatformIO paths**

**Status:** Excluded alternative; not scheduled for the default release. Physical-device evidence required.

**Dependencies (optional-profile base):** [S06.02](S06-optional-serial-fallback.md#s0602), [S06.03](S06-optional-serial-fallback.md#s0603)

**Owner:** WEB/GUEST.

**Deliverable:** `serial extension compatibility entries`.

- [ ] Inspect actual port discovery and configuration in the chosen extensions; prefer explicit RFC2217/endpoint settings.
- [ ] Test PlatformIO monitor and platform-specific upload separately; use controlled custom bindings only where necessary.
- [ ] Record extension/version/board combinations and unsupported discovery behavior.

- [ ] Keep monitor success separate from upload success and native-device discovery. A successful UART-backed RFC2217 session cannot satisfy the native S3 or zero-per-extension-patching gates.

**Accept:** Each claimed extension workflow has a repeatable physical test, not merely a matching settings field.

**Rollback/containment:** Remove the version-specific adapter and retain the tested CLI/first-party workflow.

**Reopen before coding:** [R39] [R40] [R32].

## S06.05

**Implement limited PTY and evaluate CUSE conditionally**

**Status:** Excluded alternative; not scheduled for the default release.

**Dependencies (optional-profile base):** [S06.01](S06-optional-serial-fallback.md#s0601), [S00.06](S00-platform-feasibility.md#s0006)

**Owner:** GUEST.

**Deliverable:** `PTY adapter and CUSE decision report`.

- [ ] Provide a private explicit PTY path for byte-only clients and document fixed/control limitations.
- [ ] Test representative termios/ioctl clients and return honest incompatibility instead of faking USB device identity.
- [ ] Only when prerequisites pass, build a small CUSE proof for required controls; stop if stock guest support is absent.

**Accept:** PTY capability labeling is accurate and CUSE receives an evidence-based proceed/defer decision.

**Rollback/containment:** Remove private PTY links and stop the optional CUSE instance without modifying global device discovery.

**Reopen before coding:** [R36] [R37] [R73].

## S06.06

**Stress serial operations and failure recovery**

**Status:** Excluded alternative; not scheduled for the default release. Physical-device evidence required.

**Dependencies (optional-profile base):** [S06.02](S06-optional-serial-fallback.md#s0602), [S06.03](S06-optional-serial-fallback.md#s0603), [S06.04](S06-optional-serial-fallback.md#s0604)

**Owner:** QA/HARDWARE.

**Deliverable:** `serial certification report`.

- [ ] Test cable removal, hub use, identical devices, permission revocation, app/VM death and sustained input.
- [ ] Check bounded memory, byte integrity, reset controls and no replay after uncertain completion.
- [ ] Repeat the approved flashing cycle enough times to expose intermittent failures and publish the test count/results.

**Accept:** No silent corruption or unauthorized target switching occurs in the agreed matrix; failures remain reproducible.

**Rollback/containment:** Withdraw the affected device profile and preserve a safe monitor-only mode where valid.

**Reopen before coding:** [R23] [R25] [R33].

## Source refresh

Start with the [dated hardware review](../research/2026-10-16-hardware-review.md), then reopen the task-specific references before implementation and on relevant version changes. Inherited links are research starting points, not a claim that all 98 sources were revalidated in this review. Mutable documentation is not a dependency lock.

[R19]: https://code.visualstudio.com/api/extension-guides/webview
[R21]: https://code.visualstudio.com/api/references/vscode-api
[R23]: https://developer.android.com/develop/connectivity/usb/host
[R25]: https://developer.android.com/reference/android/hardware/usb/UsbDeviceConnection
[R32]: https://pyserial.readthedocs.io/en/latest/url_handlers.html
[R33]: https://docs.espressif.com/projects/esptool/en/latest/esp32/remote-serial-ports.html
[R36]: https://man7.org/linux/man-pages/man7/pty.7.html
[R37]: https://github.com/libfuse/libfuse/blob/master/example/cuse.c
[R39]: https://docs.platformio.org/en/latest/core/userguide/device/cmd_monitor.html
[R40]: https://serialport.io/docs/api-bindings-interface/
[R73]: https://man7.org/linux/man-pages/man4/tty_ioctl.4.html
[R74]: https://www.rfc-editor.org/rfc/rfc2217.html
