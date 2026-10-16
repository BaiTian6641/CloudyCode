# S10 — Adversarial testing and performance

[Plan index](README.md) · [Architecture specification](../Googlebook_CodeOSS_APK_Engineering_Plan_v2.md)

**Status:** planned; no implementation or device acceptance is claimed. **Default profile:** `wsl_usbip_desktop`. **Packages:** 6 scheduled, 0 excluded alternatives.

**Purpose:** Exercise failures and quantify the experience before release claims.

**Exit gate:** Security, protocol, storage, input and hardware release suites have reviewable evidence; no unresolved release-blocking defects.

## Entry and execution contract

This is final cross-cutting certification, not the first time security or performance is tested. Use shipping providers only; excluded RFC2217/raw-shim code is not a release dependency.

Bind results to exact device, OS/WebView/kernel/ABI/source and workload. Keep physical, synthetic and emulated evidence separate. Initial performance budgets are targets, not results; preserve medians, p95 and worst cases.

## End-to-end smoke proof

Exercise hostile local app/webview/preview requests, replay, malformed USB/IP/tunnel frames and cancellation races. On the real target run large Git trees and out-of-tree builds under disk-full/mount-loss faults, keyboard-only and screen-reader workflows, warm/cold reconnect and full U02 hardware certification. Observe errors, retained data and resource ceilings.

**Required evidence:** Independent security review; minimized fuzz regressions/sanitizer results; bounded-allocation/queue measurements; FS/desktop stress results; performance workload baselines; hardware release pack.

**Failure containment:** Any unresolved serious authorization, memory-safety, data-loss or mandatory hardware/fidelity defect blocks release. Optimization cannot trade correctness for benchmarks; do not re-label a missing mandatory row as not applicable.

## Work packages

Dependencies below are effective for the stated profile, not stage-number order. Every implementation checkbox is initially open. Deliverable paths name outputs to create during implementation; they are not claims that those files exist. A package is complete only with its acceptance result, evidence and independent review.

## S10.01

**Perform adversarial trust-boundary testing**

**Status:** Planned; not started.

**Dependencies (default):** [S02.05](S02-connection-and-trust.md#s0205), [S02.04](S02-connection-and-trust.md#s0204), [S05.01](S05-hardware-authority.md#s0501), [S09.03](S09-lifecycle-and-recovery.md#s0903)

**Owner:** SECURITY/QA.

**Deliverable:** `security review report`.

- [ ] Attempt localhost CSRF, unrelated-app access, hostile iframe/native messages, path traversal and stolen/stale token replay.
- [ ] Test malicious guest hardware requests, wrong-device selection and untrusted-workspace command activation.
- [ ] Review unsafe native code, exported Android components and update-signature trust anchors.

**Accept:** No unresolved high-severity boundary violation remains; mitigations are independently reproduced.

**Rollback/containment:** Disable the vulnerable interface/profile and revoke affected credentials.

**Reopen before coding:** [R44] [R48] [R59] [R20].

## S10.02

**Fuzz protocol, USB and RFC2217 state machines**

**Status:** Planned; not started.

**Dependencies (default):** [S02.06](S02-connection-and-trust.md#s0206), [U01.10](U01-production-usbip.md#u0110)

**Owner:** PROTOCOL/QA.

**Default-profile scope:** Fuzz every shipping USB/IP, tunnel and hardware-state parser; RFC2217 only if actually shipped.

**Deliverable:** `fuzz corpus and sanitizer report`.

- [ ] Fuzz parsers with truncation, invalid lengths, unknown operations, cancellation races and disconnects.
- [ ] Run memory/undefined-behavior sanitizers on native components and bounded-allocation tests on all runtimes.
- [ ] Preserve minimized regression inputs and verify error behavior is deterministic and non-destructive.

**Accept:** Agreed fuzz/sanitizer runs have no unresolved crash, leak or unbounded allocation; corpus is reproducible.

**Rollback/containment:** Disable the affected operation family and retain the last safe parser version.

**Reopen before coding:** [R25] [R71] [R74].

## S10.03

**Measure editor and transport performance**

**Status:** Planned; not started. Physical-device evidence required.

**Dependencies (default):** [S04.03](S04-desktop-experience.md#s0403), [U01.10](U01-production-usbip.md#u0110), [S08.02](S08-extension-ecosystem.md#s0802)

**Owner:** QA/PERFORMANCE.

**Default-profile scope:** Measure original workbench and USB/IP transport/workflows, not unshipped fallback bridges.

**Deliverable:** `performance baseline and regression thresholds`.

- [ ] Measure input/render latency, resize behavior, startup/reconnect, CPU/memory and serial/control latency on reference hardware.
- [ ] Compare APK against the same backend in desktop Chrome as a diagnostic baseline, not as identical platforms.
- [ ] Profile before optimizing and tune queue/batching defaults without trading away correctness.

**Accept:** Published numbers include device/build/workload and distinguish proposed targets from measured results.

**Rollback/containment:** Revert an optimization that harms correctness or declared latency/memory budgets.

**Reopen before coding:** [R04] [R42] [R61].

## S10.04

**Stress repositories and shared storage**

**Status:** Planned; not started. Physical-device evidence required.

**Dependencies (default):** [S03.03](S03-dedicated-workspace.md#s0303), [S03.06](S03-dedicated-workspace.md#s0306), [S08.02](S08-extension-ecosystem.md#s0802)

**Owner:** STORAGE/QA.

**Deliverable:** `large-tree and fault-injection suite`.

- [ ] Run small/medium/large fixtures with many files, Unicode/case edges, search, branch switches and out-of-tree builds.
- [ ] Inject disk full, mount revocation, interrupted rename and watcher loss; verify no silent save success.
- [ ] Compare shared and managed-native modes where enabled and define suitability guidance.

**Accept:** No silent data loss or ambiguous authoritative tree remains in certified modes.

**Rollback/containment:** Make the workspace read-only and preserve recovery copies on any integrity failure.

**Reopen before coding:** [R21] [R51].

## S10.05

**Certify accessibility and desktop input matrix**

**Status:** Planned; not started. Physical-device evidence required.

**Dependencies (default):** [S04.02](S04-desktop-experience.md#s0402), [S04.03](S04-desktop-experience.md#s0403), [S04.04](S04-desktop-experience.md#s0404), [S04.06](S04-desktop-experience.md#s0406)

**Owner:** ANDROID/QA.

**Deliverable:** `desktop acceptance report`.

- [ ] Test screen-reader paths, keyboard-only navigation, focus, high contrast and native permission dialogs.
- [ ] Repeat IME/shortcut checks across certified layouts, display scales and multiple windows.
- [ ] Test large clipboard/drop payloads and renderer recovery while accessibility features are enabled.

**Accept:** Core edit/build/device-selection workflows are usable without mouse-only or inaccessible native steps.

**Rollback/containment:** Disable the regressed adaptation and document the remaining limitation before release.

**Reopen before coding:** [R53] [R54] [R55] [R56] [R57].

## S10.06

**Run end-to-end hardware release certification**

**Status:** Planned; not started. Physical-device evidence required.

**Dependencies (default):** [U02.08](U02-hardware-certification.md#u0208), [S07.06](S07-hardware-matrix-and-alternatives.md#s0706), [S09.05](S09-lifecycle-and-recovery.md#s0905)

**Owner:** HARDWARE/QA.

**Default-profile scope:** Require all U02 hardware gates; no silent serial-only release.

**Deliverable:** `device release evidence pack`.

- [ ] Repeat certified serial and raw-USB workflows on all claimed adapter/board/guest-architecture combinations.
- [ ] Include cold attach, regrant, hub, contention, reset, unplug and recovery tests with approved firmware.
- [ ] Freeze the supported matrix and withdraw any entry lacking repeatable evidence.

**Accept:** Claims for the selected release profile match physical hardware evidence exactly; the default requires all U02 gates and unknown/failed combinations are never promoted.

**Rollback/containment:** Ship only the previously certified subset and disable unstable profiles.

**Reopen before coding:** [R23] [R33] [R38].

## Source refresh

Start with the [dated platform review](../research/2026-10-16-platform-review.md), then reopen the task-specific references before implementation and on relevant version changes. Inherited links are research starting points, not a claim that all 98 sources were revalidated in this review. Mutable documentation is not a dependency lock.

[R04]: https://developer.android.com/develop/adaptive-apps/guides/googlebook/overview
[R20]: https://code.visualstudio.com/api/extension-guides/workspace-trust
[R21]: https://code.visualstudio.com/api/references/vscode-api
[R23]: https://developer.android.com/develop/connectivity/usb/host
[R25]: https://developer.android.com/reference/android/hardware/usb/UsbDeviceConnection
[R33]: https://docs.espressif.com/projects/esptool/en/latest/esp32/remote-serial-ports.html
[R38]: https://openocd.org/doc/html/Debug-Adapter-Configuration.html
[R42]: https://developer.android.com/develop/ui/views/layout/webapps/managing-webview
[R44]: https://developer.android.com/privacy-and-security/risks/insecure-webview-native-bridges
[R48]: https://developer.android.com/privacy-and-security/security-config
[R51]: https://developer.android.com/training/data-storage/shared/documents-files
[R53]: https://developer.android.com/develop/ui/views/layout/support-multi-window-mode
[R54]: https://developer.android.com/develop/ui/compose/layouts/adaptive/support-desktop-windowing
[R55]: https://developer.android.com/develop/ui/views/touch-and-input/keyboard-input/commands
[R56]: https://developer.android.com/develop/ui/views/touch-and-input/drag-drop
[R57]: https://developer.android.com/develop/ui/views/touch-and-input/copy-paste
[R59]: https://developer.android.com/privacy-and-security/keystore
[R61]: https://developer.android.com/ndk/guides/abis
[R71]: https://developer.android.com/reference/android/hardware/usb/UsbRequest
[R74]: https://www.rfc-editor.org/rfc/rfc2217.html
