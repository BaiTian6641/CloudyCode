# U01 — Production USB/IP exporter and importer

[Plan index](README.md) · [Architecture specification](../Googlebook_CodeOSS_APK_Engineering_Plan_v2.md)

**Status:** planned; no implementation or device acceptance is claimed. **Default profile:** `wsl_usbip_desktop`. **Packages:** 10 scheduled, 0 excluded alternatives.

**Purpose:** Implement the authorized native exporter, exact USB/IP semantics, protected relay, narrow guest helper and composite lifecycle.

**Exit gate:** Physical imported device is usable through ordinary Linux interfaces with authenticated, bounded and independently reviewed transport.

## Entry and execution contract

Conformance design U01.01 may start from the source audit. Production exporter/tunnel work waits for U00.06; device ownership uses S05.01 and authenticated framing uses S02.06.

One native whole-device lease and incarnation owns the authorized USB connection; guest interface concurrency is separate. Standard USB/IP is carried unchanged inside bounded authenticated streams; userspace terminates encryption before a normal TCP stream reaches VHCI.

## End-to-end smoke proof

Through production authentication, attach the real composite device, run serial traffic while submitting JTAG transfers, induce short reads/stalls and cancel/unlink races, then revoke/unplug/kill the broker. Verify completion fidelity, bounded queues, no reuse-before-completion, no unauthorized listeners and cleanup of owned imports only.

**Required evidence:** Exporter/helper/tunnel review; descriptor and transfer conformance; sanitizer/race results; native lifetime traces; real imported device results; U01.10 independent approval.

**Failure containment:** Unavailable reset/configuration/transfer semantics are explicit errors and may block a certified device. Never return a fabricated successful completion, replay a possibly destructive transfer or keep an unsafe exporter merely because enumeration worked.

## Work packages

Dependencies below are effective for the stated profile, not stage-number order. Every implementation checkbox is initially open. Deliverable paths name outputs to create during implementation; they are not claims that those files exist. A package is complete only with its acceptance result, evidence and independent review.

## U01.01

**Freeze USB/IP conformance and transfer capability contracts**

**Status:** Planned; not started.

**Dependencies (default):** [U00.02](U00-usbip-feasibility.md#u0002)

**Owner:** PROTOCOL/SECURITY.

**Deliverable:** `contracts/usbip-profile.md + tests/usbip/corpus/`.

- [ ] Specify the supported transfer profile, version, request lifecycle, descriptor fidelity, resource bounds and typed unsupported-operation behavior.
- [ ] Build independent fragmented-stream, malformed-frame, short-packet, stall and cancel-race tests from authoritative protocol/source behavior.
- [ ] Document how outer tunnel framing is removed before stock USB/IP processing; no custom fields inside protocol packets.
- [ ] Agree transfer ownership and memory budgets with Android/guest teams before independent implementations diverge.

**Accept:** An independent corpus covers positive/negative protocol cases and cancellation rules; no fabricated successful completion paths.

**Rollback/containment:** Version and revert the contract with all consumers together.

**Reopen before coding:** [R34] [R96] [R85] [R86].

## U01.02

**Implement the authorized native exporter device owner**

**Status:** Planned; not started. Physical-device evidence required.

**Dependencies (default):** [U00.03](U00-usbip-feasibility.md#u0003), [S05.01](S05-hardware-authority.md#s0501), [U00.06](U00-usbip-feasibility.md#u0006)

**Owner:** ANDROID/NDK.

**Deliverable:** `android/usbip/device-owner + JNI lifetime tests`.

- [ ] Integrate the selected exporter/library through the APK native build with pinned libusb and Android ABI/page-size requirements.
- [ ] Implement grant-to-open-to-wrap lifetime, ownership of duplicated/borrowed FDs, interface claims and safe asynchronous close.
- [ ] Disable unauthorized global discovery; never run an exporter requiring host root or expose default library listeners to the LAN.
- [ ] Test native errors and callback cleanup under Android process/service transitions.

**Accept:** Only granted devices can be used; native/Java lifetime is race-safe and actual device operations pass.

**Rollback/containment:** Revoke exporter access and retain the previous provider; never leave an orphan handle or hidden listener.

**Reopen before coding:** [R23] [R25] [R30] [R31] [R61] [R62] [R85].

## U01.03

**Implement faithful discovery and exclusive imports**

**Status:** Planned; not started. Physical-device evidence required.

**Dependencies (default):** [U01.01](U01-production-usbip.md#u0101), [U01.02](U01-production-usbip.md#u0102)

**Owner:** PROTOCOL/ANDROID.

**Deliverable:** `android/usbip/registry + import conformance tests`.

- [ ] Expose DEVLIST/import only for approved device incarnations and validated sessions.
- [ ] Preserve complete composite descriptors and consistent configuration/interface/endpoint/speed metadata.
- [ ] Prevent duplicate imports and unauthorized devices; retain a mapping to real attachment identity rather than trusting VID/PID alone.
- [ ] Test malformed descriptors, missing strings, two devices of the same model and competing import requests.

**Accept:** The guest sees truthful descriptors and only one authorized outer import per physical attachment.

**Rollback/containment:** Revoke affected import and rebuild registry from current native attachments.

**Reopen before coding:** [R24] [R25] [R34] [R85].

## U01.04

**Implement asynchronous transfers and correct cancellation**

**Status:** Planned; not started. Physical-device evidence required.

**Dependencies (default):** [U01.01](U01-production-usbip.md#u0101), [U01.02](U01-production-usbip.md#u0102)

**Owner:** PROTOCOL/NDK.

**Deliverable:** `android/usbip/transfer-engine + race/fuzz tests`.

- [ ] Implement control/bulk/interrupt submission with bounded concurrent queues and no UI-thread blocking.
- [ ] Map actual completion lengths and supported backend errors; do not invent precise errno from an undifferentiated Java failure.
- [ ] Implement cancel-before/after completion, disconnect and shutdown according to upstream submit/unlink semantics.
- [ ] Verify serial reads cannot starve JTAG endpoints and buffers are never reused before completion.

**Accept:** Independent conformance, sanitizer and actual multi-endpoint tests pass; no byte drops or duplicate contradictory completions.

**Rollback/containment:** Disable the device profile on unsafe errors and cancel/drain handles before teardown.

**Reopen before coding:** [R25] [R31] [R34] [R71] [R96].

## U01.05

**Handle EP0, configurations and reset limitations honestly**

**Status:** Planned; not started. Physical-device evidence required.

**Dependencies (default):** [U01.03](U01-production-usbip.md#u0103), [U01.04](U01-production-usbip.md#u0104)

**Owner:** PROTOCOL/HARDWARE.

**Deliverable:** `android/usbip/enumeration-policy + control-request tests`.

- [ ] Audit standard request handling against the selected exporter and Linux stub source; virtual enumeration must not blindly re-address an already enumerated physical device.
- [ ] Synchronize configuration/alternate-setting changes, interface claims and clear-halt behavior with pending transfers.
- [ ] Document unavailable physical resets or unsupported transfer modes; refuse unsupported operations rather than return success.
- [ ] Test repeated enumeration and ordinary device reboot without assuming arbitrary reset API availability.

- [ ] Add a regression proving that an unavailable physical reset is reported unsupported rather than successful. Exercise configuration changes, clear-halt, speed/descriptor fidelity and distinct errors against the selected exporter, not only a synthetic echo fixture.

**Accept:** The certified device enumerates reliably and unsupported device-global operations fail explicitly with recoverable state.

**Rollback/containment:** Detach the import on irreconcilable configuration changes; require a fresh authorized attachment.

**Reopen before coding:** [R25] [R31] [R34] [R96].

## U01.06

**Build authenticated tunnel and guest TCP relay**

**Status:** Planned; not started.

**Dependencies (default):** [U01.01](U01-production-usbip.md#u0101), [S02.06](S02-connection-and-trust.md#s0206), [U00.06](U00-usbip-feasibility.md#u0006)

**Owner:** GUEST/PROTOCOL/SECURITY.

**Deliverable:** `guest/usbip-relay + authenticated transport tests`.

- [ ] Carry per-device USB/IP byte streams through the paired full-duplex route without relying on guest-to-host reverse reachability.
- [ ] Terminate TLS/WebSocket/multiplexing in userspace and supply a normal guest TCP stream to the importer; no TLS fd handed directly to VHCI.
- [ ] Protect local import access with a private route or a one-use authorized local connection and credential-checked control channel.
- [ ] Test unpaired clients, replay, port squatting, LAN reachability, truncation, queue pressure and session loss.

**Accept:** Only approved peer/lease streams reach the importer; no raw LAN service or loopback-only trust assumption remains.

**Rollback/containment:** Close relay channels and revoke imports; restore known-safe endpoint configuration.

**Reopen before coding:** [R01] [R34] [R48] [R49] [R59] [R96].

## U01.07

**Implement narrow guest attach helper and device permissions**

**Status:** Planned; not started. Physical-device evidence required.

**Dependencies (default):** [U00.01](U00-usbip-feasibility.md#u0001), [U01.06](U01-production-usbip.md#u0106)

**Owner:** GUEST/SECURITY.

**Deliverable:** `guest/usbip-helper + udev/permission policy + privilege tests`.

- [ ] Expose fixed inspect/attach/detach/status operations with credential checks and validated leases; reject arbitrary commands/sysfs paths.
- [ ] Use only matching live VHCI facilities and owned ports; tools/extension host remain non-root.
- [ ] Configure profile-scoped permissions for serial/raw USB/HID as actually needed; avoid world-writable wildcard device rules.
- [ ] Test denied callers, unrelated USB/IP ports, stale imports, port exhaustion and uninstall cleanup.

**Accept:** Minimal guest privilege suffices for real imports while unprivileged development tools access only approved profiles.

**Rollback/containment:** Detach owned imports, remove narrowly owned rules/helper files and preserve unrelated kernel/userspace state.

**Reopen before coding:** [R34] [R35] [R38] [R83].

## U01.08

**Implement composite interface ownership and concurrent use**

**Status:** Planned; not started. Physical-device evidence required.

**Dependencies (default):** [U01.05](U01-production-usbip.md#u0105), [U01.07](U01-production-usbip.md#u0107)

**Owner:** HARDWARE/PROTOCOL.

**Deliverable:** `contracts/composite-ownership.md + simultaneous interface tests`.

- [ ] Separate the native whole-device lease from guest interface ownership and normal driver claims.
- [ ] Permit ESP32-S3 CDC and JTAG interfaces concurrently while serial flashing/monitor conflicts use normal coordinated ownership.
- [ ] Coordinate device-global reset/configuration events across interfaces and avoid high-level Android serial ownership during raw export.
- [ ] Document guest-wide trust: raw requests do not identify a privileged originating extension or reliably classify destructive intent.

**Accept:** Two legitimate interfaces operate concurrently, conflicting ownership fails clearly, and security claims match actual authority.

**Rollback/containment:** Revoke the whole import on unresolved global state rather than silently corrupt another interface.

**Reopen before coding:** [R25] [R34] [R91] [R94].

## U01.09

**Implement import lifecycle and safe re-enumeration**

**Status:** Planned; not started. Physical-device evidence required.

**Dependencies (default):** [U01.04](U01-production-usbip.md#u0104), [U01.06](U01-production-usbip.md#u0106), [S05.01](S05-hardware-authority.md#s0501), [S05.06](S05-hardware-authority.md#s0506)

**Owner:** ANDROID/GUEST.

**Deliverable:** `guest/android import journal + lifecycle suite`.

- [ ] Track session identity, physical attachment incarnation, import id and owned VHCI port in a bounded journal.
- [ ] Reconcile app/VM/tunnel loss with actual live imports; remove only stale owned ports and invalidate old requests.
- [ ] Require fresh identity/authorization when ambiguous, and never replay uncertain programming/reset transfers.
- [ ] Test two windows, two boards, permission revocation and reconnection during another active device session.

**Accept:** Every failure produces correct revocation or explicit uncertain state; no duplicate/stale import survives recovery.

**Rollback/containment:** Detach affected owned ports, preserve evidence and require explicit user recovery.

**Reopen before coding:** [R23] [R34] [R58] [R76] [R91].

## U01.10

**Integrate and review the production USB/IP path**

**Status:** Planned; not started. Physical-device evidence required.

**Dependencies (default):** [U00.06](U00-usbip-feasibility.md#u0006), [U01.03](U01-production-usbip.md#u0103), [U01.04](U01-production-usbip.md#u0104), [U01.05](U01-production-usbip.md#u0105), [U01.06](U01-production-usbip.md#u0106), [U01.07](U01-production-usbip.md#u0107), [U01.08](U01-production-usbip.md#u0108), [U01.09](U01-production-usbip.md#u0109)

**Owner:** LEAD/QA.

**Deliverable:** `tests/evidence/usbip-production + reviewed provider ADR`.

- [ ] Integrate the independently reviewed exporter, relay, helper, lease and discovery contracts on the target Googlebook.
- [ ] Re-run normal Linux serial/raw-USB discovery and operations over production authentication, not only diagnostic listeners.
- [ ] Verify no native USB permissions are reachable from untrusted extension webviews and no process runs the editor as root.
- [ ] Record unresolved transfer modes and stop release on mandatory-profile failures.

**Accept:** The complete physical USB/IP route passes with production boundaries; required end-tool certification can proceed.
**Rollback/containment:** Disable the USB/IP provider cleanly and preserve the no-silent-fallback release block.

**Reopen before coding:** [R23] [R34] [R35] [R44] [R83] [R91].

## Source refresh

Start with the [dated hardware review](../research/2026-10-16-hardware-review.md), then reopen the task-specific references before implementation and on relevant version changes. Inherited links are research starting points, not a claim that all 98 sources were revalidated in this review. Mutable documentation is not a dependency lock.

[R01]: https://developer.android.com/blog/posts/googlebook-linux-terminal-preview-vm
[R23]: https://developer.android.com/develop/connectivity/usb/host
[R24]: https://developer.android.com/reference/android/hardware/usb/UsbManager
[R25]: https://developer.android.com/reference/android/hardware/usb/UsbDeviceConnection
[R30]: https://raw.githubusercontent.com/libusb/libusb/master/android/README
[R31]: https://libusb.sourceforge.io/api-1.0/group__libusb__dev.html
[R34]: https://docs.kernel.org/usb/usbip_protocol.html
[R35]: https://github.com/torvalds/linux/blob/master/drivers/usb/usbip/Kconfig
[R38]: https://openocd.org/doc/html/Debug-Adapter-Configuration.html
[R44]: https://developer.android.com/privacy-and-security/risks/insecure-webview-native-bridges
[R48]: https://developer.android.com/privacy-and-security/security-config
[R49]: https://developer.android.com/privacy-and-security/local-network-permission
[R58]: https://developer.android.com/develop/background-work/services/fgs/service-types
[R59]: https://developer.android.com/privacy-and-security/keystore
[R61]: https://developer.android.com/ndk/guides/abis
[R62]: https://developer.android.com/guide/practices/page-sizes
[R71]: https://developer.android.com/reference/android/hardware/usb/UsbRequest
[R76]: https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
[R83]: https://learn.microsoft.com/en-us/windows/wsl/connect-usb
[R85]: https://github.com/yunsmall/usbipdcpp
[R86]: https://github.com/jiegec/usbip
[R91]: https://docs.espressif.com/projects/esp-idf/en/stable/esp32s3/api-guides/usb-serial-jtag-console.html
[R94]: https://raw.githubusercontent.com/espressif/openocd-esp32/master/tcl/interface/esp_usb_jtag.cfg
[R96]: https://raw.githubusercontent.com/torvalds/linux/master/drivers/usb/usbip/stub_rx.c
