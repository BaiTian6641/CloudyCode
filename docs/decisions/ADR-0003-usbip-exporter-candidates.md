# ADR-0003: USB/IP exporter candidates for the authorized Android route

- **Status:** Accepted-experimental (2026-10-16). No candidate in this ADR is production-approved.
- **Context task:** U00.02 ("Audit reusable userspace exporters and licenses") in [U00 — USB/IP feasibility](../plan/U00-usbip-feasibility.md).
- **Evidence base:** the completed, source-only [hardware review of 2026-10-16](../research/2026-10-16-hardware-review.md) (hereafter "the review"). Every factual claim below cites the review section and/or a pinned upstream URL. No hardware, build, or test was run to produce this ADR. All external links were accessed 2026-10-16.

## Decision summary

1. **usbipdcpp** (`yunsmall/usbipdcpp`, pinned commit `fcb76a45e41148f4482c9230de3a14f6718fc8cb`, LGPL-3.0, C++23) is selected as the **first experimental candidate** for the ordinary-APK authorized-FD experiment — explicitly **not production-approved** (§4.1, §4.2 of the review; [pinned revision](https://github.com/yunsmall/usbipdcpp/commit/fcb76a45e41148f4482c9230de3a14f6718fc8cb)).
2. **jiegec/usbip** (pinned commit `61938f8c48ed01c508faaacfa7cb3fc3810b5ae0`, MIT, Rust edition 2024) is selected **only** as the independent synthetic CDC conformance fixture, with a confined listener; it is not a production physical-exporter candidate (§4.1, §4.3 of the review; [pinned source](https://github.com/jiegec/usbip)).
3. A defined set of **source corrections must land before U01.05 acceptance**; neither candidate passes unchanged.
4. **No candidate is production-approved.** Production selection is deferred to U00.06 and gated on U00.03–U00.05 physical evidence plus the listed corrections (review §7, P0/U00.02–U00.06 rows).

## 1. Candidate matrix

All commits, licenses, activity dates and quotes below were verified during the source audit (review §4.1, §4.2, §4.3; source register H12–H21).

| Candidate | Pin / license / language | Role in this decision | Blocking findings |
|---|---|---|---|
| `yunsmall/usbipdcpp` | `fcb76a45e41148f4482c9230de3a14f6718fc8cb`; LGPL v3 license file (README additionally requests attribution); C++23 / GCC 13, Termux clang/libc++ noted in README; latest-commit metadata 2026-09-24 | **First experimental APK candidate** (not production-approved) | Reset no-op returning success; EPIPE error merge; configuration/alternate-setting fall-through; unbounded shutdown; unsecured example listener — see §2 |
| `jiegec/usbip` | `61938f8c48ed01c508faaacfa7cb3fc3810b5ae0`; MIT; Rust edition 2024, Tokio, `rusb` 0.9.3, `nusb` 0.2.1, version 0.9.0; latest-commit metadata 2026-09-03 | **Independent synthetic CDC oracle only**; physical path rejected for production use | Blocking shared-mutex handlers; discarded transfer errors; no real in-flight cancellation on unlink; EP0 packet-size hardcode; alternate settings ignored — see §2 |
| Haven (GlassHaven) | Maintainer-report documentation (issue #287; `docs/features/usb.md`) | **Prior art only** — no code reuse, no design import | proot shims, second QEMU VM, broad sudo recipe, VID/PID auto-reselection; CDC/FIDO verified, JTAG/concurrency untested; later provisioning failure reported (review §4.1; H18, H19) |
| VirtualHere | Vendor documentation; commercial license | **Diagnostic comparator only** (optional, user-authorized, matched client/server) | Linux client "uses the built-in Linux usbip client driver" — it still requires guest VHCI; not a workaround for missing VHCI; redistribution/interoperability rights not established (review §4.1; H20) |
| usbipd-win | GPL-3.0-only C# Windows exporter | Rejected — architectural precedent only | Windows drivers/kernel assumptions do not transfer to an Android APK (review §4.1; H21) |

## 2. Source-audit findings carried into acceptance

### 2.1 usbipdcpp production blockers (review §4.2, source register H13–H15)

Pinned `src/LibusbHandler/LibusbDeviceHandler.cpp` at `fcb76a45…`:

- **Reset no-op returning success:** `tweak_reset_device_cmd` contains a commented-out `libusb_reset_device(native_handle)` and returns `0`; the caller emits a successful `RET_SUBMIT` without performing a physical reset. U01.05 must reject or honestly implement the actual operation; a fixture that tolerates this no-op cannot certify production correctness.
- **EPIPE error merge:** `trxstat2error` maps `ERROR`, `STALL`, `TIMED_OUT` and `OVERFLOW` all to `EPIPE`, although libusb distinguishes them. Faithful per-outcome mapping and tests are required; upstream Linux semantics, not another exporter's simplification, define the contract ([R34]).
- **Configuration fall-through:** `tweak_set_configuration_cmd` returns `-1`, causing ordinary raw control submission rather than libusb's configuration API; failed clear-halt/alternate-setting tweaks likewise fall through into raw control submission. Host-kernel state, active descriptors, claims and pending transfers must be synchronized; a raw successful control response is not a coherent backend configuration.
- **Unbounded shutdown:** disconnect cancels outstanding transfers, waits ten seconds, then waits **without a deadline** for pending completions. Shutdown must retain event servicing and define safe escalation; a timeout must never be "fixed" by freeing buffers or destroying owners while callbacks may still execute ([R31]).
- Additional audit items: wrapped-FD import claims every active interface, detaches drivers, resets alternate settings to zero, and the alternate-setting initialization does not check its return value before updating bookkeeping (review §4.2). Positive but limited evidence: asynchronous libusb control/bulk/interrupt paths, external-FD ownership preserved by wrapped-FD handling, explicit libusb↔Linux speed-enum conversion, endpoint metadata built from the active descriptor (review §4.2, H14, H15).
- Security/INTegration note: the pinned Termux example calls `libusb_set_option(nullptr, LIBUSB_OPTION_WEAK_AUTHORITY)` and listens on all IPv4 interfaces (default port 3240). It is an authorized-FD example, **not** a secure in-process APK integration; the example listener must be replaced by the product's protected route (review §4.2, H13).

### 2.2 jiegec/usbip physical-path blockers (review §4.3, source register H16)

Pinned `src/host.rs` and `src/lib.rs` at `61938f8c…`:

- **Blocking shared-mutex handlers:** synchronous transfers with one-second timeouts inside rusb interface handlers sharing a `Mutex<DeviceHandle<GlobalContext>>` — unrelated endpoints are serialized; a long CDC IN can starve JTAG traffic.
- **Discarded errors:** several failed OUT calls are `.ok()`-discarded and return `Ok(vec![])`; failed IN paths can also reach an empty successful response. Error and actual-length information is lost.
- **No real in-flight cancellation:** `src/lib.rs` awaits a submitted operation inside the connection command loop; the unlink branch logs `unlink_seqnum` and responds without canceling a stored in-flight native operation. Per the Linux USB/IP specification, a successfully unlinked submission has **no** corresponding `RET_SUBMIT`, and unlink after completion yields `RET_UNLINK(0)` — not a "nonzero means failure" rule ([R34], review §5.2).
- **EP0 packet-size hardcode:** nusb construction hardcodes EP0 packet size 16.
- **Alternate settings ignored:** both construction paths explicitly ignore alternate settings (`// ignore alternate se…` in pinned source).
- Additional: OUT `actual_length` is returned from the requested `data.len()` rather than backend actual length (review §4.3, §5.2).

**Consequence:** the physical path is not certified unchanged; the project's CDC fixture use is confined to the synthetic example (`examples/cdc_acm_serial.rs`), which currently only emits a character periodically and listens on all IPv4 interfaces — a reviewed bidirectional workload and a **confined listener** are required before U00.04 use (review §4.3, §7 U00.04 row). Independent implementation is not an infallible oracle.

## 3. Decision and required corrections before U01.05 acceptance

**Decision:** adopt usbipdcpp `fcb76a45…` as the first **experimental** candidate for the U00.03/U00.05 ordinary-APK authorized-FD experiment, and adopt jiegec/usbip `61938f8c…` as the independent synthetic conformance fixture for U00.04, run with a confined listener. This mirrors the review's P0/U00.02 gate: "choose C++ only as experimental candidate and reviewed independent CDC fixture."

**Required corrections before U01.05 acceptance** (all are review findings; U01.05 explicitly requires truthful reset/error/EP0 behavior):

1. **Truthful reset:** the reset no-op must be implemented honestly or reported as unsupported with a defined error/teardown behavior; success without the physical operation is prohibited (review §4.2; U00.05/U01.05 acceptance).
2. **Faithful error mapping:** distinct libusb outcomes (`ERROR`, `STALL`, `TIMED_OUT`, `OVERFLOW`, partial/zero-length completions) must map to distinct, tested wire errors; OUT completion length must come from backend `actual_length`, not requested length (review §4.2, §5.2).
3. **EP0/configuration semantics:** set-configuration, clear-halt and alternate-setting requests must be handled with coherent backend state (claims, descriptors, pending transfers), synchronized with upstream `stub_rx.c` special-control handling — never silently re-addressing Android's already-enumerated device (review §4.2, §5.1; [R96]).
4. **Cancellation correctness:** unlink must implement cancel-before-completion (`RET_UNLINK(-ECONNRESET)`) and cancel-after-completion (`RET_UNLINK(0)`) semantics with real in-flight cancellation and no fabricated `RET_SUBMIT` after a successful unlink (review §5.2, [R34]).
5. **Lifetime/drain:** bounded shutdown that keeps servicing completion events until buffers can be safely reclaimed; no freeing transfers whose callbacks may still run (review §4.2; libusb contract, [R31]).
6. **NDK build + 16 KB page-size check:** README C++23/GCC 13 and Termux clang/libc++ claims are not NDK evidence; a pinned-NDK build with Android ABI and 16 KB page-size compatibility ([R61], [R62]) must pass before production reliance (review §4.1 "unproved areas").
7. **LGPL packaging review:** combined-work obligations include notices, license copies, suitable relinking/shared-library provision and installation information where applicable; a shared-library build flag is not proof a signed APK satisfies them. The actual APK, dependencies, modified source, relink route and notices must be reviewed before selection. The Rust fixture's MIT license does not erase dependency licenses. No legal compliance conclusion is asserted here (review §4.1).

## 4. Rejected alternatives

- **jiegec/usbip as production physical exporter** — rejected: blocking handlers, discarded errors, absent cancellation, EP0/alternate-setting defects (§2.2). Retained only as the synthetic oracle.
- **Haven as an implementation source or design template** — rejected: different architecture (proot shims, alternate QEMU VM, broad sudo recipe, VID/PID auto-reselection); its documented CDC/FIDO verification does not cover S3 JTAG or concurrency, and a later provisioning failure is reported (review §4.1, H18, H19). Prior art only.
- **VirtualHere as the runtime transport or a VHCI substitute** — rejected: its Linux client still depends on built-in kernel USB/IP support, so it does not remove the guest-VHCI prerequisite; commercial-license and redistribution/interoperability terms are not established for undisclosed runtime dependency (review §4.1, H20). Permitted only as an optional user-authorized diagnostic comparator.
- **usbipd-win as a reuse candidate** — rejected: Windows-specific exporter/drivers; do not copy Windows kernel/update/firewall assumptions into the APK (review §4.1, H21).
- **Implementing a replacement USB/IP stack from scratch** — rejected for now: the review's source evidence justifies reviewed adaptations of the pinned candidates, not a new stack (review §4.3).
- **Native passthrough without USB/IP** (serial shims, RFC2217 fallbacks, alternate VMs) — outside this ADR's scope and already excluded by the platform-boundaries decision (ADR-0001; review §1). This ADR introduces **no roadmap capability and no native-passthrough reliance**: all physical capabilities remain UNKNOWN until U00.03–U00.05 evidence exists.

## 5. Conformance fixture plan (feeds U01.01)

The independent fixture and the corpus built on it must cover the acceptance cases the review enumerates (§5.2), mapped to U01.01's deliverable `contracts/usbip-profile.md + tests/usbip/corpus/`:

| Corpus case (review §5.2) | Fixture obligation |
|---|---|
| Fragmented/coalesced stream; truncated/oversized headers and payloads; version/opcode errors | TCP read/WebSocket message is not a USB/IP message boundary (review §2.2); parser must resynchronize or fail with defined teardown, never desync |
| Submit failure; illegal endpoint/direction; setup/data-length mismatch; duplicate in-flight sequence numbers; unknown unlink target | Defined error responses; no fabricated completions |
| Unlink before completion / after completion | `RET_UNLINK(-ECONNRESET)` vs `RET_UNLINK(0)`; successfully unlinked submit has no `RET_SUBMIT` ([R34], review §5.2) |
| Short IN; partial OUT; zero-length transfer and zero-packet semantics | Completion validated from backend `actual_length`, not requested length (review §5.2) |
| Stall / clear-halt | Distinct error mapping (not merged to `EPIPE`); clear-halt synchronized with pending transfers (review §4.2, §5.2) |
| EP0 setup byte order | USB setup fields are little-endian while USB/IP envelope fields are big-endian; EP0 buffers include an eight-byte setup prefix and completion `actual_length` refers to data, not setup bytes ([R31], review §5.2) |
| Timeout / disconnect; teardown with active callbacks; bounded queue/payload memory | Bounded drain and cancellation; buffers never freed before completion callbacks run ([R31]) |
| Import exclusivity; two identical devices | Registry-level duplicate-import rejection (U01.03 scope, informed by this corpus) |
| CDC bidirectional loopback | The jiegec emit-only example needs a reviewed bidirectional workload before it counts as a test (review §7, U00.04 row) |

## 6. Status, consequences, rollback

**Status:** Accepted-experimental. The selection authorizes the bounded U00 experiments only. It does **not** approve either implementation for production, and it does not claim any Googlebook, NDK-build, performance or security result.

**Consequences:**

- Production selection is deferred to U00.06 and requires U00.03–U00.05 physical evidence (ordinary-APK authorized-FD access, synthetic-CDC guest import, real S3 import with truthful EP0/reset behavior and short CDC/JTAG overlap) **plus** the §3 corrections (review §7).
- The identified reset/error/blocking gaps are carried into acceptance criteria of U00.05 and U01.05; a passing fixture cannot launder a no-op reset into production correctness.
- U01.01 conformance-contract work may proceed immediately from this audited source base; physical production integration remains gated on U00.06 (review §7, dependency refinements).

**Rollback:** both source pins (`fcb76a45…`, `61938f8c…`) are retained as the audit baseline. Switching either candidate requires a new ADR; silently substituting another revision, fork or stack is prohibited (U00.02 rollback/containment).

## References

- [Hardware review 2026-10-16](../research/2026-10-16-hardware-review.md) — evidence base; sections cited inline.
- [U00 — USB/IP feasibility](../plan/U00-usbip-feasibility.md#u0002) — U00.02 acceptance and rollback.
- [U01 — Production USB/IP](../plan/U01-production-usbip.md) — U01.01/U01.05 downstream requirements.
- usbipdcpp: [repo](https://github.com/yunsmall/usbipdcpp), [pinned commit](https://github.com/yunsmall/usbipdcpp/commit/fcb76a45e41148f4482c9230de3a14f6718fc8cb), [LICENSE (LGPL v3)](https://raw.githubusercontent.com/yunsmall/usbipdcpp/fcb76a45e41148f4482c9230de3a14f6718fc8cb/LICENSE), [Termux example](https://raw.githubusercontent.com/yunsmall/usbipdcpp/fcb76a45e41148f4482c9230de3a14f6718fc8cb/examples/termux_libusb_server/termux_libusb_server.cpp), [LibusbDeviceHandler.cpp](https://raw.githubusercontent.com/yunsmall/usbipdcpp/fcb76a45e41148f4482c9230de3a14f6718fc8cb/src/LibusbHandler/LibusbDeviceHandler.cpp).
- jiegec/usbip: [repo](https://github.com/jiegec/usbip), [pinned Cargo.toml](https://raw.githubusercontent.com/jiegec/usbip/61938f8c48ed01c508faaacfa7cb3fc3810b5ae0/Cargo.toml), [MIT LICENSE](https://raw.githubusercontent.com/jiegec/usbip/61938f8c48ed01c508faaacfa7cb3fc3810b5ae0/LICENSE), [host.rs](https://raw.githubusercontent.com/jiegec/usbip/61938f8c48ed01c508faaacfa7cb3fc3810b5ae0/src/host.rs), [lib.rs](https://raw.githubusercontent.com/jiegec/usbip/61938f8c48ed01c508faaacfa7cb3fc3810b5ae0/src/lib.rs), [CDC example](https://raw.githubusercontent.com/jiegec/usbip/61938f8c48ed01c508faaacfa7cb3fc3810b5ae0/examples/cdc_acm_serial.rs).
- Prior ADR: [ADR-0001](ADR-0001-platform-boundaries.md) (platform boundaries; native-passthrough exclusion), [ADR-0002](ADR-0002-extension-registry-and-licensing.md) (license-audit style precedent).
