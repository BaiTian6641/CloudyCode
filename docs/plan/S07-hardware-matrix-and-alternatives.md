# S07 — Fallback raw-USB adapters and hardware matrix

[Plan index](README.md) · [Architecture specification](../Googlebook_CodeOSS_APK_Engineering_Plan_v2.md)

**Status:** planned; no implementation or device acceptance is claimed. **Default profile:** `wsl_usbip_desktop`. **Packages:** 1 scheduled, 5 excluded alternatives.

**Purpose:** Maintain selected tool-specific alternatives and evaluate future native passthrough without displacing USB/IP.

**Exit gate:** The selected release publishes an accurate support matrix and no unverified universal claims.

## Entry and execution contract

Only S07.06 is scheduled for the default, after U02.08. S07.01–S07.05 are excluded adapter/native-passthrough alternatives, not prerequisites to USB/IP.

The published matrix names exact host/build/guest/backend/adapter/target/firmware combinations and operations. Future native passthrough stays roadmap/unknown until independently demonstrated with supported public interfaces.

## End-to-end smoke proof

Have a reviewer choose a matrix row and reproduce its build/program/debug sequence from recorded inputs. Verify that an untested family and wrong firmware/tool combination are not represented as certified; correlate default rows to the U02 evidence pack.

**Required evidence:** Versioned support/exclusion matrix; links to real workflow evidence; explicit transport and limitation per row; revalidation triggers for platform/provider changes.

**Failure containment:** Withdraw unsupported rows rather than broaden a family label. A selected raw-USB bridge is not transparent arbitrary-extension compatibility. Required native S3 and external-adapter workflows cannot be removed to ship the default.

## Work packages

Dependencies below are effective for the stated profile, not stage-number order. Every implementation checkbox is initially open. Deliverable paths name outputs to create during implementation; they are not claims that those files exist. A package is complete only with its acceptance result, evidence and independent review.

## S07.01

**Select a fallback raw-USB tool adapter only for a scoped need**

**Status:** Excluded alternative; not scheduled for the default release. Physical-device evidence required.

**Dependencies (optional-profile base):** [S00.06](S00-platform-feasibility.md#s0006), [S02.06](S02-connection-and-trust.md#s0206)

**Owner:** HARDWARE/RESEARCH.

**Deliverable:** `raw USB pilot ADR and descriptors`.

- [ ] Select a user-owned safe debug/programming adapter and research the exact OpenOCD/tool driver, interface and licensing.
- [ ] Record required control/bulk/interrupt operations, reset behavior, permissions and guest architecture support.
- [ ] Choose a narrow measurable goal and reject unsupported HID/isochronous/kernel assumptions from the first pilot.

**Accept:** Every required operation has a proposed public host path or an explicit blocker before transport coding begins.

**Rollback/containment:** Keep the pilot disabled and retain the serial release path.

**Reopen before coding:** [R25] [R31] [R38].

## S07.02

**Implement Android raw USB transport for the pilot**

**Status:** Excluded alternative; not scheduled for the default release. Physical-device evidence required.

**Dependencies (optional-profile base):** [S07.01](S07-hardware-matrix-and-alternatives.md#s0701), [S05.01](S05-hardware-authority.md#s0501)

**Owner:** ANDROID/HARDWARE.

**Deliverable:** `android/usb-native pilot module`.

- [ ] Open only user-approved interfaces and implement the bounded transfer set identified by the pilot.
- [ ] Use Java async requests or same-app native libusb with explicit descriptor ownership; do not transfer numeric handles to Debian.
- [ ] Test short transfer, stall, timeout, cancel/detach races and interface release with synthetic and physical cases.

**Accept:** Required pilot operations work without root, leaked handles or unsupported-operation false success.

**Rollback/containment:** Cancel transfers, close native handles and release interfaces in a defined order.

**Reopen before coding:** [R25] [R30] [R31] [R71].

## S07.03

**Implement the guest tool transport adapter**

**Status:** Excluded alternative; not scheduled for the default release.

**Dependencies (optional-profile base):** [S07.02](S07-hardware-matrix-and-alternatives.md#s0702)

**Owner:** GUEST/HARDWARE.

**Deliverable:** `guest/tool-adapters/pilot`.

- [ ] Use an upstream hook or a narrow version-pinned adapter for the selected tool; keep its execution in the VM.
- [ ] Implement enumeration, transfer results, cancellation and errors needed by that tool only, with ABI tests.
- [ ] Avoid global libusb replacement/LD_PRELOAD; document static/proprietary/HID exclusions.

**Accept:** The selected guest tool uses broker transport with traceable semantics and no Android-side tool migration.

**Rollback/containment:** Restore the original tool binary/configuration and disable the scoped adapter.

**Reopen before coding:** [R31] [R38].

## S07.04

**Certify a build, program and debug hardware workflow**

**Status:** Excluded alternative; not scheduled for the default release. Physical-device evidence required.

**Dependencies (optional-profile base):** [S07.03](S07-hardware-matrix-and-alternatives.md#s0703), [S08.02](S08-extension-ecosystem.md#s0802)

**Owner:** HARDWARE/QA.

**Deliverable:** `raw USB device certification`.

- [ ] Build a known sample in the VM, identify the target and perform user-approved programming.
- [ ] Validate halt, breakpoint, continue and state inspection through the normal workbench debug UI.
- [ ] Test detach and reconnect, adapter contention and clean end-of-session release.

**Accept:** The complete workflow passes on an identified board/adapter/tool version; partial enumeration is insufficient.

**Rollback/containment:** Follow the target recovery procedure and withdraw certification if state becomes uncertain.

**Reopen before coding:** [R38] [R21].

## S07.05

**Evaluate future documented Google native passthrough**

**Status:** Excluded alternative; not scheduled for the default release. Physical-device evidence required.

**Dependencies (optional-profile base):** [S00.01](S00-platform-feasibility.md#s0001), [S00.06](S00-platform-feasibility.md#s0006)

**Owner:** GUEST/HARDWARE.

**Deliverable:** `USBIP feasibility spike report`.

- [ ] Search current official Googlebook sources for a shipping native USB-passthrough contract and third-party permission/lifecycle details.
- [ ] If a supported provider exists, compare real guest discovery, ownership, reset and security behavior against the project provider; a roadmap alone is not availability.
- [ ] Record a bounded migration ADR or an evidence-backed not-applicable result. Do not duplicate or defer the mandatory U00 USB/IP gate under this legacy task.

**Accept:** A current source-backed provider decision distinguishes available native passthrough from roadmap work; no future API is invented.

**Rollback/containment:** Keep the current certified provider; remove experimental hooks without changing user data.

**Reopen before coding:** [R01] [R02] [R23] [R83].

## S07.06

**Publish the hardware support and exclusion matrix**

**Status:** Planned; not started. Physical-device evidence required.

**Dependencies (default):** [U02.08](U02-hardware-certification.md#u0208)

**Owner:** LEAD/HARDWARE.

**Default-profile scope:** Publish actual USB/IP import, CDC serial, S3 flashing/JTAG/concurrency and external-adapter evidence; label untested families.

**Deliverable:** `docs/compatibility/devices.json`.

- [ ] Classify serial, raw USB, HID and kernel-driver clients by actual tested transport and operation coverage.
- [ ] Include adapter/board/tool versions, architectures, grants, reset limitations and evidence links.
- [ ] Keep future Google passthrough separate and define the revalidation trigger when it ships.

**Accept:** No broad USB claim exceeds demonstrated coverage; users can identify a working route before installing a plugin.

**Rollback/containment:** Mark regressed entries unsupported and retain historical evidence.

**Reopen before coding:** [R01] [R23] [R34] [R38].

## Source refresh

Start with the [dated hardware review](../research/2026-10-16-hardware-review.md), then reopen the task-specific references before implementation and on relevant version changes. Inherited links are research starting points, not a claim that all 98 sources were revalidated in this review. Mutable documentation is not a dependency lock.

[R01]: https://developer.android.com/blog/posts/googlebook-linux-terminal-preview-vm
[R02]: https://support.google.com/googlebook/answer/18119222?hl=en
[R21]: https://code.visualstudio.com/api/references/vscode-api
[R23]: https://developer.android.com/develop/connectivity/usb/host
[R25]: https://developer.android.com/reference/android/hardware/usb/UsbDeviceConnection
[R30]: https://raw.githubusercontent.com/libusb/libusb/master/android/README
[R31]: https://libusb.sourceforge.io/api-1.0/group__libusb__dev.html
[R34]: https://docs.kernel.org/usb/usbip_protocol.html
[R38]: https://openocd.org/doc/html/Debug-Adapter-Configuration.html
[R71]: https://developer.android.com/reference/android/hardware/usb/UsbRequest
[R83]: https://learn.microsoft.com/en-us/windows/wsl/connect-usb
