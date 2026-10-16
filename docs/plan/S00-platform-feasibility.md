# S00 — Platform truth and feasibility

[Plan index](README.md) · [Architecture specification](../Googlebook_CodeOSS_APK_Engineering_Plan_v2.md)

**Status:** planned; no implementation or device acceptance is claimed. **Default profile:** `wsl_usbip_desktop`. **Packages:** 6 scheduled, 0 excluded alternatives.

**Purpose:** Do not begin product-scale implementation before identifying the actual device, VM route, WebView and hardware capabilities.

**Exit gate:** A physical-device capability report distinguishes pass, fail and unknown; unsupported mandatory routes block downstream integration.

## Entry and execution contract

Start with the existing research and a named device/build-host owner. Source review and build-host preparation can proceed without hardware; target-device tasks cannot pass until that hardware is available.

Keep one redacted environment identity covering host build, target SDK, Terminal version/settings, WebView provider, host ABI, guest kernel/ABI, mounts and tool versions. Record Chrome, native sockets and WebView as independent clients.

## End-to-end smoke proof

On the identified Googlebook, install the ordinary probe APK, serve a bounded binary echo from the built-in VM, use both APK clients, and map a disposable shared folder by nonce. Deny/regrant access and close Terminal. Observe byte integrity, correct folder identity and an honest VM-unavailable result; a Chrome screenshot cannot substitute.

**Required evidence:** Environment inventory; NET-01/02/03/04 and FS-01 reports; WebView feature results; USB grant report; route and workspace-mode decisions.

**Failure containment:** No APK route blocks connected-IDE integration. Missing shared storage blocks workspace onboarding. Missing device access blocks only physical proof; continue source/build-host work without marking device capabilities supported.

## Work packages

Dependencies below are effective for the stated profile, not stage-number order. Every implementation checkbox is initially open. Deliverable paths name outputs to create during implementation; they are not claims that those files exist. A package is complete only with its acceptance result, evidence and independent review.

## S00.01

**Refresh Googlebook, Terminal and Antigravity evidence**

**Status:** Planned; not started.

**Dependencies (default):** None.

**Owner:** RESEARCH/LEAD.

**Deliverable:** `docs/research/platform-baseline.md`.

- [ ] Reopen the current Googlebook developer/support pages and search official sources for newly published third-party VM, folder, USB or Antigravity integration APIs.
- [ ] Record publication/update/access dates, exact supported scope, roadmap language and unresolved questions; distinguish generic AOSP from shipping Googlebook.
- [ ] Create initial ADRs banning hidden APIs, undocumented privileges and a silent change to Termux/cloud architecture.

- [ ] Use the three dated research reviews as a starting ledger, not as physical results. Recheck current source changes and create the decisions required by this package before marking it complete.

**Accept:** Every foundational platform claim has a source and status; no roadmap item is marked available.

**Rollback/containment:** Revert unsupported assumptions and leave related capabilities unknown.

**Reopen before coding:** [R01] [R02] [R03] [R04] [R05] [R07] [R08].

## S00.02

**Inventory physical host, Debian guest and build hosts**

**Status:** Planned; not started. Physical-device evidence required.

**Dependencies (default):** [S00.01](S00-platform-feasibility.md#s0001)

**Owner:** GUEST/QA.

**Deliverable:** `tools/doctor + redacted device profile`.

- [ ] Record physical model, Android build/SDK, WebView provider, app ABI, guest kernel, Debian architecture, memory/disk and current Terminal settings.
- [ ] Inspect service-manager support, actual mounts, kernel module availability and accessible USB/CUSE/VHCI facilities without changing the host.
- [ ] Validate the separate machine used for Android SDK/NDK builds; do not infer Linux binary support from the APK ABI.

- [ ] Identify an actually available Googlebook and owner-approved native ESP32-S3 board plus an external adapter/target. Record acquisition/access blockers separately from unsupported platform capabilities.

**Accept:** A reproducible profile identifies every tested environment; unsupported build-host combinations are explicit.

**Rollback/containment:** No persistent mutation is required; redact and delete diagnostic data on request.

**Reopen before coding:** [R02] [R61] [R62] [R63] [R65].

## S00.03

**Prove APK and WebView connectivity to the VM**

**Status:** Planned; not started. Physical-device evidence required.

**Dependencies (default):** [S00.02](S00-platform-feasibility.md#s0002)

**Owner:** ANDROID/GUEST.

**Deliverable:** `network probe APK and guest echo service`.

- [ ] Run an authenticated or unprivileged minimal test listener using Terminal-supported forwarding; compare Chrome, native APK HTTP and WebView HTTP.
- [ ] Test binary WebSocket traffic, idle reconnect, IPv4/IPv6, permission denied/granted states and only the required forwarded ports.
- [ ] Check accessibility from another LAN device and behavior after Terminal closes; write the route-selection ADR from measurements.

- [ ] Use the same isolated echo workload across Chrome, a native socket, and WebView; include service restart and a second LAN client. Capture source/destination bind addresses so localhost forwarding is not mistaken for a generic VM API.
- [ ] For applicable host builds test target-36 versus target-37 variants, denied/revoked local-network permission and exact loopback/private-address routes. The retrieved general all-networking-APIs rule does not itself certify WebView permission inheritance or classify the Terminal route.

**Accept:** At least one supported native APK-to-VM route passes bidirectional tests; Chrome-only success does not pass.

**Rollback/containment:** Remove test listeners/forwarding rules and stop downstream integration if no supported route exists.

**Reopen before coding:** [R01] [R02] [R48] [R49] [R50].

## S00.04

**Probe WebView features needed by Code - OSS**

**Status:** Planned; not started. Physical-device evidence required.

**Dependencies (default):** [S00.03](S00-platform-feasibility.md#s0003)

**Owner:** WEB/ANDROID.

**Deliverable:** `WebView capability fixture`.

- [ ] Measure secure context, workers, service workers, WebSocket, clipboard user gesture and extension-resource origin behavior on the actual renderer.
- [ ] Test basic editor IME and keyboard input without user-agent tricks or SSL bypasses; record provider/version.
- [ ] Compare the same fixture in desktop Chrome to isolate WebView-specific failures, while keeping the two results separate.

**Accept:** Required browser features either pass or have a tested origin/gateway remedy; failures are preserved as evidence.

**Rollback/containment:** Return to the minimal probe; do not replace real extension tests with a static editor screenshot.

**Reopen before coding:** [R14] [R19] [R41] [R45] [R75].

## S00.05

**Probe the dedicated shared-folder model**

**Status:** Planned; not started. Physical-device evidence required.

**Dependencies (default):** [S00.02](S00-platform-feasibility.md#s0002)

**Owner:** STORAGE/QA.

**Deliverable:** `storage probe report and test workspace`.

- [ ] Create a small user-approved folder and grant it separately to APK and Terminal; map it with bidirectional nonce files.
- [ ] Test rename, case, Unicode, executable bits, symlinks, watcher events and a small Git workflow without touching unrelated directories.
- [ ] Choose a provisional shared-source or managed-native mode and record which tests still require larger fixtures.

**Accept:** Both sides demonstrably access the same folder; no guessed guest path or false POSIX guarantee remains.

**Rollback/containment:** Remove only probe-created files; preserve the user directory and any unrelated data.

**Reopen before coding:** [R02] [R03] [R51] [R52].

## S00.06

**Probe ordinary-APK USB authority and identify kernel questions**

**Status:** Planned; not started. Physical-device evidence required.

**Dependencies (default):** [S00.02](S00-platform-feasibility.md#s0002)

**Owner:** HARDWARE/QA.

**Deliverable:** `hardware capability report`.

- [ ] Test UsbManager enumeration, user-granted device access and required interfaces using an ordinary APK on the actual Googlebook; record host restrictions.
- [ ] Separately inspect API-37 serial availability for optional fallback support; do not make it a prerequisite for a working raw-USB exporter.
- [ ] Record running-guest kernel/VHCI/class-driver unknowns for U00 rather than treating USB/IP as a late experiment. Preserve permission-denied results.

**Accept:** A physical report distinguishes Android raw USB, optional serial APIs and importer uncertainty; no unsupported API is assumed.

**Rollback/containment:** Close handles, unregister listeners and remove temporary permissions/settings where applicable.

**Reopen before coding:** [R23] [R24] [R25] [R26] [R30] [R35] [R71].

## Source refresh

Start with the [dated platform review](../research/2026-10-16-platform-review.md), then reopen the task-specific references before implementation and on relevant version changes. Inherited links are research starting points, not a claim that all 98 sources were revalidated in this review. Mutable documentation is not a dependency lock.

[R01]: https://developer.android.com/blog/posts/googlebook-linux-terminal-preview-vm
[R02]: https://support.google.com/googlebook/answer/18119222?hl=en
[R03]: https://support.google.com/googlebook/answer/18668704?hl=en
[R04]: https://developer.android.com/develop/adaptive-apps/guides/googlebook/overview
[R05]: https://source.android.com/docs/core/virtualization/usecases
[R07]: https://android.googlesource.com/platform/packages/modules/Virtualization/+/refs/heads/main/android/TerminalApp/AndroidManifest.xml
[R08]: https://developer.android.com/guide/app-compatibility/restrictions-non-sdk-interfaces
[R14]: https://coder.com/docs/code-server/FAQ
[R19]: https://code.visualstudio.com/api/extension-guides/webview
[R23]: https://developer.android.com/develop/connectivity/usb/host
[R24]: https://developer.android.com/reference/android/hardware/usb/UsbManager
[R25]: https://developer.android.com/reference/android/hardware/usb/UsbDeviceConnection
[R26]: https://developer.android.com/reference/android/hardware/serial/SerialManager
[R30]: https://raw.githubusercontent.com/libusb/libusb/master/android/README
[R35]: https://github.com/torvalds/linux/blob/master/drivers/usb/usbip/Kconfig
[R41]: https://developer.android.com/develop/ui/views/layout/webapps/webview
[R45]: https://developer.android.com/reference/androidx/webkit/WebViewCompat
[R48]: https://developer.android.com/privacy-and-security/security-config
[R49]: https://developer.android.com/privacy-and-security/local-network-permission
[R50]: https://developer.android.com/reference/android/Manifest.permission
[R51]: https://developer.android.com/training/data-storage/shared/documents-files
[R52]: https://developer.android.com/guide/topics/providers/create-document-provider
[R61]: https://developer.android.com/ndk/guides/abis
[R62]: https://developer.android.com/guide/practices/page-sizes
[R63]: https://developer.android.com/tools/adb
[R65]: https://developer.android.com/studio/install
[R71]: https://developer.android.com/reference/android/hardware/usb/UsbRequest
[R75]: https://www.w3.org/TR/secure-contexts/
