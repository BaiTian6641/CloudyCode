# S02 — Connection, protocol and native trust

[Plan index](README.md) · [Architecture specification](../Googlebook_CodeOSS_APK_Engineering_Plan_v2.md)

**Status:** planned; no implementation or device acceptance is claimed. **Default profile:** `wsl_usbip_desktop`. **Packages:** 6 scheduled, 0 excluded alternatives.

**Purpose:** Replace experiment-only plumbing with reviewed pairing, bounded protocols and secure origin handling.

**Exit gate:** Unpaired peers and untrusted webviews cannot reach privileged interfaces; protocol conformance and reconnect tests pass.

## Entry and execution contract

The vertical slice supplies a real workbench, not a production trust boundary. Approve draft identity, framing and origin contracts before peers implement them; freeze exercised compatibility in S02.06.

Native-to-guest identity, UI session authentication and USB authority are separate. The native bridge is exact-origin/main-frame scoped; workbench, extension frames and forwarded apps retain distinct privileges. A local port is not authentication.

## End-to-end smoke proof

Pair once, reconnect, revoke and re-pair. Replay the enrollment secret, present a different VM identity, squat the loopback port, load a malicious iframe and try a forwarded app request. Real workbench workers/webviews must continue working while unauthorized requests are rejected without exposing credentials.

**Required evidence:** Threat model and pairing/origin decisions; protocol schemas and vectors; NET-05/06/07/08; WEB-02/03; permission/LAN exposure report.

**Failure containment:** Fail closed on identity/origin/version conflicts. Restore the last secure topology; never use trust-all TLS, a global JavaScript interface, arbitrary proxy targets or cloud/VPN routing to hide a platform blocker.

## Work packages

Dependencies below are effective for the stated profile, not stage-number order. Every implementation checkbox is initially open. Deliverable paths name outputs to create during implementation; they are not claims that those files exist. A package is complete only with its acceptance result, evidence and independent review.

## S02.01

**Implement pairing and identity lifecycle**

**Status:** Planned; not started.

**Dependencies (default):** [S00.03](S00-platform-feasibility.md#s0003), [S01.06](S01-workbench-vertical-slice.md#s0106)

**Owner:** SECURITY/GUEST.

**Deliverable:** `pairing implementation and threat tests`.

- [ ] Define enrollment, high-entropy one-time secret handling, server identity verification, app identity and revocation.
- [ ] Use reviewed cryptographic libraries and Android key storage; keep credentials out of URLs, workspaces and normal logs.
- [ ] Test wrong identity, expired/reused secret, concurrent pairing, reset and explicit re-pairing.

- [ ] Approve a draft of enrollment, authorization, revocation and protocol-version contracts before coding the peer implementations; S02.06 freezes the exercised contract rather than inventing it after parallel implementations diverge.

**Accept:** A stale or impersonated guest cannot silently inherit trust; enrollment secrets cannot be reused.

**Rollback/containment:** Revoke test identities and return to unpaired state without deleting projects.

**Reopen before coding:** [R48] [R59].

## S02.02

**Implement the guest session daemon**

**Status:** Planned; not started.

**Dependencies (default):** [S01.06](S01-workbench-vertical-slice.md#s0106), [S02.01](S02-connection-and-trust.md#s0201)

**Owner:** GUEST.

**Deliverable:** `guest/sessiond`.

- [ ] Implement status, authenticated session establishment, backend supervision hooks and capability negotiation without replacing Code - OSS protocols.
- [ ] Create private sockets/configuration with bounded logs and typed errors; run as a non-root guest user.
- [ ] Test process death, missing backend, malformed request and interrupted startup using fake hardware.

**Accept:** Daemon tests pass and no unauthenticated command/USB endpoint exists.

**Rollback/containment:** Stop the daemon and restore its previous version/configuration snapshot.

**Reopen before coding:** [R14] [R15] [R06].

## S02.03

**Choose and implement the production origin topology**

**Status:** Planned; not started. Physical-device evidence required.

**Dependencies (default):** [S00.04](S00-platform-feasibility.md#s0004), [S02.01](S02-connection-and-trust.md#s0201), [S02.02](S02-connection-and-trust.md#s0202)

**Owner:** ANDROID/WEB.

**Deliverable:** `ADR-origin-topology and gateway module`.

- [ ] Choose direct verified origin or app-local gateway based on the measured WebView/TLS constraints.
- [ ] For a gateway, implement bounded HTTP/WS proxying, authentication, streaming and origin-preserving routes; never arbitrary target proxying.
- [ ] Test service workers, extension webviews, port collisions, cookie host scope and offline resources under the chosen topology.

- [ ] Audit the pinned backend's webview-origin patches, service-worker behavior and parent validation. Prove that an untrusted extension frame and a forwarded development app cannot inherit the native bridge even when resource hosts coincide.

**Accept:** No SSL bypass or origin flattening is required; extension webviews and authenticated reconnect remain functional.

**Rollback/containment:** Fall back to the last secure tested topology; do not relax validation to retain UI appearance.

**Reopen before coding:** [R14] [R19] [R47] [R48] [R72] [R75].

## S02.04

**Implement the constrained native integration bridge**

**Status:** Planned; not started.

**Dependencies (default):** [S02.03](S02-connection-and-trust.md#s0203)

**Owner:** ANDROID/SECURITY.

**Deliverable:** `android native bridge and adversarial fixture`.

- [ ] Expose only allowlisted integration messages using supported origin-aware WebKit APIs and main-frame checks.
- [ ] Validate payloads and session IDs; remove bridge access after navigation; keep hardware approvals native.
- [ ] Attempt calls from malicious iframes, external pages, stale windows and oversized messages.

**Accept:** Untrusted content cannot invoke native integration, and no generic exec/file-fetch/USB primitive is exposed.

**Rollback/containment:** Disable the bridge while retaining basic workbench browsing.

**Reopen before coding:** [R43] [R44] [R45].

## S02.05

**Implement endpoint and port authorization**

**Status:** Planned; not started. Physical-device evidence required.

**Dependencies (default):** [S02.02](S02-connection-and-trust.md#s0202), [S02.03](S02-connection-and-trust.md#s0203)

**Owner:** SECURITY/GUEST.
**Deliverable:** `authorization tests and exposure report`.

- [ ] Enforce origin/host and session checks on workbench-facing services; restrict forwarded development ports separately.
- [ ] Test unrelated Android clients, cross-origin web requests, LAN reachability and accidental wildcard binding.
- [ ] Validate local-network permission denial/regrant and cleartext policy against the selected SDK.

**Accept:** Only approved clients/routes work; a localhost assumption cannot bypass authentication.

**Rollback/containment:** Close all unexpected listeners and retain the last secure configuration.

**Reopen before coding:** [R02] [R48] [R49] [R50].

## S02.06

**Freeze protocol schemas and compatibility tests**

**Status:** Planned; not started.

**Dependencies (default):** [S02.01](S02-connection-and-trust.md#s0201), [S02.02](S02-connection-and-trust.md#s0202), [S02.04](S02-connection-and-trust.md#s0204)

**Owner:** PROTOCOL/LEAD.

**Deliverable:** `contracts and golden test vectors`.

- [ ] Define per-operation schemas, binary framing, limits, version negotiation, leases, generations and cancellation semantics.
- [ ] Generate or hand-maintain compatible Kotlin/Rust/TypeScript adapters from one authoritative contract with round-trip tests.
- [ ] Reject unknown major versions and invalid frames; distinguish queue acceptance, completion and uncertain outcomes.

**Accept:** Independent implementations pass shared vectors and fail malformed cases deterministically.

**Rollback/containment:** Version the protocol change and preserve the previous compatible implementation.

**Reopen before coding:** [R25] [R71] [R74].

## Source refresh

Start with the [dated platform review](../research/2026-10-16-platform-review.md), then reopen the task-specific references before implementation and on relevant version changes. Inherited links are research starting points, not a claim that all 98 sources were revalidated in this review. Mutable documentation is not a dependency lock.

[R02]: https://support.google.com/googlebook/answer/18119222?hl=en
[R06]: https://source.android.com/docs/core/virtualization/virtualization-service
[R14]: https://coder.com/docs/code-server/FAQ
[R15]: https://github.com/gitpod-io/openvscode-server
[R19]: https://code.visualstudio.com/api/extension-guides/webview
[R25]: https://developer.android.com/reference/android/hardware/usb/UsbDeviceConnection
[R43]: https://developer.android.com/develop/ui/views/layout/webapps/native-api-access-jsbridge
[R44]: https://developer.android.com/privacy-and-security/risks/insecure-webview-native-bridges
[R45]: https://developer.android.com/reference/androidx/webkit/WebViewCompat
[R47]: https://developer.android.com/develop/ui/views/layout/webapps/load-local-content
[R48]: https://developer.android.com/privacy-and-security/security-config
[R49]: https://developer.android.com/privacy-and-security/local-network-permission
[R50]: https://developer.android.com/reference/android/Manifest.permission
[R59]: https://developer.android.com/privacy-and-security/keystore
[R71]: https://developer.android.com/reference/android/hardware/usb/UsbRequest
[R72]: https://developer.android.com/reference/android/webkit/WebView
[R74]: https://www.rfc-editor.org/rfc/rfc2217.html
[R75]: https://www.w3.org/TR/secure-contexts/
