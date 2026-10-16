# S05 — Hardware authority, lifecycle and optional serial providers

[Plan index](README.md) · [Architecture specification](../Googlebook_CodeOSS_APK_Engineering_Plan_v2.md)

**Status:** planned; no implementation or device acceptance is claimed. **Default profile:** `wsl_usbip_desktop`. **Packages:** 2 scheduled, 4 excluded alternatives.

**Purpose:** Implement shared ownership/lifecycle and retain serial-specific providers only in the release profiles that need them.

**Exit gate:** The selected provider has real permission, bounded operations, correct ownership and tested lifecycle.

## Entry and execution contract

S05.01 is common authority work. The default executes S05.01 and S05.06 only; S05.02–S05.05 are excluded serial-fallback packages. S05.06 depends on the USB/IP owner, not a high-level serial backend.

Use an application-scoped broker service and fresh attachment generations. Share a selected whole device with the paired guest trust domain; do not pretend arbitrary extensions have kernel-level isolation or their own trustworthy USB identity.

## End-to-end smoke proof

Choose one of two identical boards from native UI, approve sharing, open two IDE windows, and attempt conflicting use. Revoke from outside the workbench, detach/replug and stop the foreground service. Verify the old lease cannot authorize a new attachment and permitted serial/JTAG interfaces can coexist.

**Required evidence:** Lease state transitions and negative authorization; service-type/permission declaration review; two-board/two-window results; app/VM/service interruption evidence.

**Failure containment:** Native revoke remains available even when the renderer is malicious or dead. On unsupported foreground lifecycle, stop safely with an actionable result rather than holding stale authority or claiming the VM is kept alive.

## Work packages

Dependencies below are effective for the stated profile, not stage-number order. Every implementation checkbox is initially open. Deliverable paths name outputs to create during implementation; they are not claims that those files exist. A package is complete only with its acceptance result, evidence and independent review.

## S05.01

**Implement device registry, generations and exclusive leases**

**Status:** Planned; not started.

**Dependencies (default):** [S00.06](S00-platform-feasibility.md#s0006), [S02.06](S02-connection-and-trust.md#s0206)

**Owner:** HARDWARE/PROTOCOL.

**Deliverable:** `hardware lease manager`.

- [ ] Create opaque device IDs and fresh attachment generations with optional privacy-safe metadata.
- [ ] Implement native approval, lease expiry, allowed operation classes and monitor/flasher exclusivity.
- [ ] Test identical devices, composite interfaces, stale leases and claimed extension identities.
- [ ] Model one native whole-device lease to the guest and separate guest interface use; permit certified simultaneous CDC and JTAG interfaces without claiming per-extension isolation.

**Accept:** An old attachment or unapproved guest cannot reuse a device lease.

**Rollback/containment:** Revoke all leases and close owned handles safely.

**Reopen before coding:** [R23] [R24] [R25].

## S05.02

**Implement the API-37 serial backend**

**Status:** Excluded alternative; not scheduled for the default release. Physical-device evidence required.

**Dependencies (optional-profile base):** [S05.01](S05-hardware-authority.md#s0501)

**Owner:** ANDROID/HARDWARE.

**Deliverable:** `android/serial-api37`.

- [ ] Compile against the verified SDK and isolate API-37 calls behind runtime checks.
- [ ] Enumerate/listen/open with the public serial classes and manage ParcelFileDescriptor lifetime.
- [ ] Implement validated native serial configuration and record host-driver and foreground-service limitations.

**Accept:** On supported hardware, permission denial, open, configured I/O and detach work; unavailable paths are cleanly disabled.

**Rollback/containment:** Close descriptors/listeners and select the tested USB-driver backend when available.

**Reopen before coding:** [R26] [R27] [R28] [R58] [R73].

## S05.03

**Implement the USB user-space serial backend**

**Status:** Excluded alternative; not scheduled for the default release. Physical-device evidence required.

**Dependencies (optional-profile base):** [S05.01](S05-hardware-authority.md#s0501)

**Owner:** ANDROID/HARDWARE.

**Deliverable:** `android/usb-serial`.

- [ ] Pin a supported driver library and implement permission, device selection and interface-specific opening.
- [ ] Expose driver capabilities and safe controls; do not seize unrelated devices or claim every VID/PID is supported.
- [ ] Test at least one real adapter family, detach/regrant and conflicts with another host application.

**Accept:** A certified adapter passes configured reads/writes and clean ownership release.

**Rollback/containment:** Close driver/USB connections and revoke the active lease.

**Reopen before coding:** [R23] [R24] [R25] [R29].

## S05.04

**Unify serial configuration, signals and errors**

**Status:** Excluded alternative; not scheduled for the default release. Physical-device evidence required. Optional serial profile also requires at least one of [S05.02](S05-hardware-authority.md#s0502) or [S05.03](S05-hardware-authority.md#s0503).

**Dependencies (optional-profile base):** [S05.01](S05-hardware-authority.md#s0501), [S02.06](S02-connection-and-trust.md#s0206)

**Owner:** HARDWARE/PROTOCOL.

**Deliverable:** `common serial transport contract`.

- [ ] Define exact baud/parity/stop/control/flow/break/flush/drain semantics and per-backend capabilities.
- [ ] Implement host-side bounded reset sequences and error mapping; return unsupported rather than false success.
- [ ] Run the same control tests on every enabled backend, including S05.02 when supported.

**Accept:** Clients can distinguish unsupported controls, permission errors, detach and uncertain completion.

**Rollback/containment:** Disable unsupported controls/profile entries without changing validated byte-stream behavior.

**Reopen before coding:** [R27] [R29] [R73] [R74].

## S05.05

**Implement bounded binary streaming and control priority**

**Status:** Excluded alternative; not scheduled for the default release.

**Dependencies (optional-profile base):** [S05.04](S05-hardware-authority.md#s0504)

**Owner:** PROTOCOL/GUEST.

**Deliverable:** `hardware channel streaming implementation`.

- [ ] Implement binary chunks, sequence numbers, credits and per-stream/application queue budgets.
- [ ] Prioritize cancel/revoke/control traffic and separate display-history loss from device-transport correctness.
- [ ] Stress with synthetic streams before hardware; inject slow consumer, stall and disconnect.

**Accept:** Memory remains bounded and no state-changing write is silently dropped or replayed.

**Rollback/containment:** Stop the affected stream and return an explicit error; retain diagnostic counters.

**Reopen before coding:** [R71] [R74].

## S05.06

**Integrate provider-neutral hardware foreground lifecycle**

**Status:** Planned; not started. Physical-device evidence required. If a separate serial profile is approved, replace these default dependencies with S05.05 and at least one of S05.02/S05.03; do not mix providers on one attachment.

**Dependencies (default):** [S05.01](S05-hardware-authority.md#s0501), [U01.02](U01-production-usbip.md#u0102)

**Owner:** ANDROID/QA.

**Default-profile scope:** Apply lifecycle to the USB/IP provider; no requirement to implement optional Android high-level serial backends.

**Deliverable:** `broker service lifecycle module`.

- [ ] Create the eligible native connected-device lifecycle for whichever providers the selected release actually ships; distinguish renderer death from broker death.
- [ ] Cancel outstanding operations and reconcile leases on detach, app/service death, VM loss, grant revocation and foreground/background transitions.
- [ ] For USB/IP, follow U01 generation/import semantics; for a fallback serial provider, retain control-line and stream behavior. Do not mix providers on the same attachment.

- [ ] Verify base and connectedDevice foreground-service permissions, a legitimate qualifying USB authorization, user-visible start/notification/stop, and background-start refusal. Do not add unrelated permissions to make eligibility checks pass or assume SerialPort authorization is equivalent to UsbManager permission.

**Accept:** The selected provider survives permitted lifecycle transitions or fails explicitly without stale handles, unsafe replay or false VM-persistence promises.

**Rollback/containment:** Stop service, cancel transfers and release leases; do not leave orphan listeners.

**Reopen before coding:** [R23] [R25] [R58] [R76] [R34].

## Source refresh

Start with the [dated hardware review](../research/2026-10-16-hardware-review.md), then reopen the task-specific references before implementation and on relevant version changes. Inherited links are research starting points, not a claim that all 98 sources were revalidated in this review. Mutable documentation is not a dependency lock.

[R23]: https://developer.android.com/develop/connectivity/usb/host
[R24]: https://developer.android.com/reference/android/hardware/usb/UsbManager
[R25]: https://developer.android.com/reference/android/hardware/usb/UsbDeviceConnection
[R26]: https://developer.android.com/reference/android/hardware/serial/SerialManager
[R27]: https://developer.android.com/reference/android/hardware/serial/SerialPort
[R28]: https://developer.android.com/reference/android/hardware/serial/SerialPortResponse
[R29]: https://github.com/mik3y/usb-serial-for-android
[R34]: https://docs.kernel.org/usb/usbip_protocol.html
[R58]: https://developer.android.com/develop/background-work/services/fgs/service-types
[R71]: https://developer.android.com/reference/android/hardware/usb/UsbRequest
[R73]: https://man7.org/linux/man-pages/man4/tty_ioctl.4.html
[R74]: https://www.rfc-editor.org/rfc/rfc2217.html
[R76]: https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
