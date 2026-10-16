# U00 — USB/IP feasibility before scale

[Plan index](README.md) · [Architecture specification](../Googlebook_CodeOSS_APK_Engineering_Plan_v2.md)

**Status:** planned; no implementation or device acceptance is claimed. **Default profile:** `wsl_usbip_desktop`. **Packages:** 6 scheduled, 0 excluded alternatives.

**Purpose:** Establish actual guest VHCI, ordinary APK physical authority and a real ESP32-S3 import before committing to production hardware code.

**Exit gate:** Independent decision states pass/fail/unknown for each prerequisite on the actual Googlebook; missing mandatory support blocks the default profile.

## Entry and execution contract

Start kernel inventory beside S00 networking/storage, not after desktop polish. A synthetic importer fixture and Android authorized-FD probe are separate experiments; U00.05 joins them on the real device.

Guest sudo is not a kernel installation API. Verify live VHCI, matching modules, CDC/raw-USB access, authorization and a viable paired route. Candidate source behavior and license must be reviewed before invoking it.

## End-to-end smoke proof

First import a synthetic CDC fixture into the actual built-in guest and round-trip data. Then export the real native ESP32-S3 from the ordinary APK, observe guest CDC and raw JTAG interfaces, exchange serial data and perform an operator-approved OpenOCD connection. Run a short overlapping CDC/JTAG exchange to expose serialized endpoints before production investment. Detach/revoke and observe owned-port cleanup.

**Required evidence:** Live kernel inventory; pinned exporter source/license audit; synthetic-vs-physical reports; native FD lifetime trace; serial and JTAG results; independent USB/IP go/no-go decision.

**Failure containment:** Missing/unknown importer, prohibited required Android operation, or failed native S3 prerequisites block the default release. Preserve a precise kernel/API report for Google; do not swap in another VM, fabricate reset success or reclassify a UART bridge as JTAG.

## Work packages

Dependencies below are effective for the stated profile, not stage-number order. Every implementation checkbox is initially open. Deliverable paths name outputs to create during implementation; they are not claims that those files exist. A package is complete only with its acceptance result, evidence and independent review.

## U00.01

**Inventory the running guest USB/IP and class-driver prerequisites**

**Status:** Planned; not started. Physical-device evidence required.

**Dependencies (default):** [S00.02](S00-platform-feasibility.md#s0002)

**Owner:** GUEST/QA.

**Deliverable:** `docs/evidence/guest-usb-kernel.json + redacted read-only probe output`.

- [ ] Read the actual guest kernel release, matching configuration/module availability, live VHCI sysfs and required CDC/HID/USB-UART facilities without loading modules.
- [ ] Distinguish built-in, module-present, loadable-not-tested, missing and unknown; a missing modinfo result is not proof a built-in feature is absent.
- [ ] Verify whether Google documents an authorized module or booted-kernel update path; do not assume apt-installed kernels boot in this VM.
- [ ] Prepare a specific supported-importer question for Google when evidence is incomplete.

**Accept:** Actual kernel evidence is recorded with unknowns intact; no synthetic/other-VM result is counted as the target kernel.

**Rollback/containment:** No kernel mutation; retain the report and block functional import until prerequisites are resolved.

**Reopen before coding:** [R01] [R02] [R35] [R83].

## U00.02

**Audit reusable userspace exporters and licenses**

**Status:** Planned; not started.

**Dependencies (default):** [S00.01](S00-platform-feasibility.md#s0001)

**Owner:** PROTOCOL/RESEARCH.

**Deliverable:** `docs/decisions/ADR-usbip-exporter-candidates.md`.

- [ ] Reopen usbipdcpp, jiegec/usbip, libusb Android guidance and primary implementation reports; pin the source revisions investigated.
- [ ] Compare authorized-FD access, NDK/compiler requirements, DEVLIST/import, composite descriptors, cancellation, resets, error fidelity and test fixtures.
- [ ] Review actual licenses, linking obligations and packaging; do not assume a Termux example mandates Termux runtime or proves Googlebook compatibility.
- [ ] Select a short experiment candidate and an independent synthetic/conformance fixture; document gaps before coding.

- [ ] Resolve the reset-success no-op and error-mapping findings in the dated hardware review against the chosen immutable exporter revision. Neither a permissive README nor an authorized-FD example approves that implementation for production.

**Accept:** Two implementation paths are assessed and a justified experimental candidate is selected without unverified support or redistribution claims.

**Rollback/containment:** Retain source audit; switch candidate only through a documented ADR.

**Reopen before coding:** [R30] [R31] [R34] [R85] [R86] [R87] [R88].

## U00.03

**Prove Android authorized native USB access**

**Status:** Planned; not started. Physical-device evidence required.

**Dependencies (default):** [S00.06](S00-platform-feasibility.md#s0006)

**Owner:** ANDROID/HARDWARE.

**Deliverable:** `android/probes/native-usb + physical permission/descriptor report`.

- [ ] Build a minimal ordinary-permission APK using UsbManager and a kept-alive UsbDeviceConnection; select a user-authorized development device.
- [ ] Wrap its FD with the pinned libusb interface where applicable; verify ownership and cancellation without global root USB enumeration.
- [ ] Record full required descriptors, actual interfaces, speed reporting and safe control/bulk/interrupt operations; mark unsupported resets/isochronous behavior.
- [ ] Test permission denial, detach and repeated open/close; no automatic device reset or programming.

**Accept:** Actual target APK can access the required development-device interfaces with correct handle lifetime and no privileged host assumption.

**Rollback/containment:** Close native/Java handles, revoke the test share and stop when required operations are denied.

**Reopen before coding:** [R23] [R24] [R25] [R30] [R31] [R71].

## U00.04

**Functionally test VHCI using a synthetic CDC device**

**Status:** Planned; not started. Physical-device evidence required.

**Dependencies (default):** [U00.01](U00-usbip-feasibility.md#u0001), [U00.02](U00-usbip-feasibility.md#u0002)

**Owner:** GUEST/PROTOCOL.

**Deliverable:** `tests/evidence/synthetic-vhci-import/`.

- [ ] Obtain authorization before loading an available matching module or installing probe tooling; verify the booted kernel again.
- [ ] Use a reviewed synthetic CDC fixture on a confined route and import it into the actual Googlebook guest; do not inject keyboard/storage fixtures.
- [ ] Verify enumeration, guest driver binding, real serial node, read/write loopback, detach and port cleanup.
- [ ] Preserve exact attach errors and tag evidence as synthetic device on physical guest, not real peripheral support.

**Accept:** A functional importer result is recorded; loaded-module or lsusb-only evidence cannot pass the full test.

**Rollback/containment:** Detach only the test import and stop its fixture; leave unrelated ports/devices intact.

**Reopen before coding:** [R34] [R35] [R83] [R85] [R86].

## U00.05

**Import the real ESP32-S3 through the probe APK**

**Status:** Planned; not started. Physical-device evidence required.

**Dependencies (default):** [U00.03](U00-usbip-feasibility.md#u0003), [U00.04](U00-usbip-feasibility.md#u0004), [S00.03](S00-platform-feasibility.md#s0003)

**Owner:** HARDWARE/GUEST.

**Deliverable:** `tests/evidence/esp32s3-usbip-spike/`.

- [ ] Identify the actual native USB Serial/JTAG connector and firmware mode; record descriptors and distinguish any external UART bridge.
- [ ] Export the whole approved composite device over the proven APK/guest route and confirm guest descriptors and CDC/raw-USB access.
- [ ] Run an authorized serial exchange and a separately approved Espressif OpenOCD connection using matching tools; record incomplete operations honestly.
- [ ] Test detach and grant revocation; label any experiment-only transport security as not release-ready.

- [ ] Test the actual native USB Serial/JTAG connector and the active controller mode; the USB-OTG/TinyUSB profile and an external UART connector cannot substitute. Record permission and safe-operation evidence separately from synthetic importer results.
- [ ] Run a short simultaneous serial/JTAG exchange and capture required EP0/reset operations. Do not accept a reset no-op as success or require application-generated logs to continue while the target CPU is halted; full stress and source-debug certification remain in U02.

**Accept:** The real import has physical serial, JTAG and short-concurrency feasibility results with truthful control-operation behavior; enumeration or two sequential endpoint demonstrations are insufficient for a default go decision.

**Rollback/containment:** Revoke share, detach owned VHCI port and stop without retrying resets or firmware writes.

**Reopen before coding:** [R25] [R30] [R34] [R91] [R92] [R94] [R95].

## U00.06

**Approve or block the USB/IP architecture gate**

**Status:** Planned; not started. Physical-device evidence required.

**Dependencies (default):** [U00.01](U00-usbip-feasibility.md#u0001), [U00.02](U00-usbip-feasibility.md#u0002), [U00.03](U00-usbip-feasibility.md#u0003), [U00.04](U00-usbip-feasibility.md#u0004), [U00.05](U00-usbip-feasibility.md#u0005)

**Owner:** LEAD/QA.

**Deliverable:** `docs/decisions/ADR-usbip-go-no-go.md`.

- [ ] Independently review kernel, APK authority, route, descriptors, serial, JTAG and short-concurrency evidence; enumerate missing tests and external-adapter requirements.
- [ ] Choose the production exporter and required profile, or mark the default release blocked with a precise platform prerequisite.
- [ ] Describe RFC2217/tool-specific alternatives only as reduced-scope modes; do not silently switch to another VM or serial-only completion.

**Accept:** A defensible decision exists; a default go requires real prerequisite passes, while fail/unknown leaves downstream hardware acceptance blocked.

**Rollback/containment:** Suspend production hardware rollout; independent UI/storage work may continue without claiming hardware success.

**Reopen before coding:** [R01] [R34] [R35] [R83] [R91].

## Source refresh

Start with the [dated hardware review](../research/2026-10-16-hardware-review.md), then reopen the task-specific references before implementation and on relevant version changes. Inherited links are research starting points, not a claim that all 98 sources were revalidated in this review. Mutable documentation is not a dependency lock.

[R01]: https://developer.android.com/blog/posts/googlebook-linux-terminal-preview-vm
[R02]: https://support.google.com/googlebook/answer/18119222?hl=en
[R23]: https://developer.android.com/develop/connectivity/usb/host
[R24]: https://developer.android.com/reference/android/hardware/usb/UsbManager
[R25]: https://developer.android.com/reference/android/hardware/usb/UsbDeviceConnection
[R30]: https://raw.githubusercontent.com/libusb/libusb/master/android/README
[R31]: https://libusb.sourceforge.io/api-1.0/group__libusb__dev.html
[R34]: https://docs.kernel.org/usb/usbip_protocol.html
[R35]: https://github.com/torvalds/linux/blob/master/drivers/usb/usbip/Kconfig
[R71]: https://developer.android.com/reference/android/hardware/usb/UsbRequest
[R83]: https://learn.microsoft.com/en-us/windows/wsl/connect-usb
[R85]: https://github.com/yunsmall/usbipdcpp
[R86]: https://github.com/jiegec/usbip
[R87]: https://github.com/GlassHaven/Haven/issues/287
[R88]: https://github.com/GlassHaven/Haven/blob/main/docs/features/usb.md
[R91]: https://docs.espressif.com/projects/esp-idf/en/stable/esp32s3/api-guides/usb-serial-jtag-console.html
[R92]: https://docs.espressif.com/projects/esp-idf/en/stable/esp32s3/api-guides/jtag-debugging/configure-builtin-jtag.html
[R94]: https://raw.githubusercontent.com/espressif/openocd-esp32/master/tcl/interface/esp_usb_jtag.cfg
[R95]: https://raw.githubusercontent.com/espressif/openocd-esp32/master/tcl/board/esp32s3-builtin.cfg
