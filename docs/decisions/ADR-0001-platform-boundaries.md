# ADR-0001: Platform boundaries for GBCode on Googlebook

- **Status:** Accepted
- **Date:** 2026-10-16
- **Owner:** RESEARCH/LEAD (per [S00.01](../plan/S00-platform-feasibility.md))
- **Scope:** Platform-integration boundaries for the GBCode APK (`wsl_usbip_desktop` default profile) on Googlebook's Android host and built-in Debian Terminal VM.
- **Supersedes:** None. **Superseded by:** None.
- **Companion evidence:** [claim ledger](../research/claim-ledger.jsonl) (GB-PLAT/GB-NET/GB-WEB/GB-FS/GB-SER/GB-USB/GB-EXT/GB-BACK/GB-DEV entries).

## Context

Three dated primary-source reviews, all accessed 2026-10-16, establish the current platform picture:

- [Platform review](../research/2026-10-16-platform-review.md) — Terminal preview (published 2026-10-12) and support pages document a built-in Debian VM, automatic localhost port forwarding to the host browser, user-managed shared folders, and an alternative graphics screen. USB passthrough, integrated Linux GUI/launcher, GPU/NPU, dynamic scaling and window-independent VM lifetime are **roadmap**. No documented API lets an ordinary third-party APK control the Terminal VM, execute guest commands, acquire its lifetime reference, or grant folders (bounded negative search finding, GB-PLAT-004). AOSP source shows an exported Terminal launch activity but a non-exported VM launcher service (SOURCE, not a shipping contract).
- [Upstream review](../research/2026-10-16-upstream-review.md) — Code - OSS is MIT source; Microsoft's product distribution, branding and Visual Studio Marketplace access are separate rights. The web + remote extension topology has a browser worker host and a remote Node host with no local Node host. Backend candidates have recoverable exact upstream controls (code-server v4.141.0 → Code 1.141.0; OpenVSCode Server v1.109.5 → upstream 1.109.5).
- [Hardware review](../research/2026-10-16-hardware-review.md) — USB/IP requires a real importing VHCI stack inside the actual guest (UNKNOWN for Google's VM). Android's public USB host APIs exist, but device exposure is untested. esptool excludes native USB-OTG/USB-Serial-JTAG from RFC2217/socket remote ports. Inspected exporter candidates have concrete source-level production gaps.

None of these reviews performed a physical test; they explicitly contain **no DEVICE-PASS results** and instruct that such entries never be populated from them.

The first physical observations were recorded separately by ADB on **2026-10-16** against an **HP Googlebook 14 (physical device)** and are entered in the ledger as GB-DEV-001..007 with status DEVICE-PASS: Android 17 (SDK 37), build `HP/quartz/quartz:17/CL3B.260622.276.R2/16560795:user/release-keys`, security patch 2026-09-01, ABI arm64-v8a, PAGE_SIZE 4096; WebView provider `com.google.android.webview` 149.0.7827.5 (targetSdk 36); Terminal `com.android.virtualization.terminal` v17 (targetSdk 37, minSdk 37) as uid u10_a204 with a `crosvm_ToscanaMicrodroid` VM process and `virtualizationservice` running; Antigravity package `com.google.android.apps.antigravity` installed; developer settings and ADB enabled. ADB is a development probe, never a production runtime dependency.

This ADR records the boundaries that follow before any product-scale implementation begins (S00 exit gate).

## Decision

**A1 — Public SDK only.** GBCode integrates exclusively through public, documented SDK surfaces. Hidden APIs, non-SDK interfaces (R08), undocumented privileges, and undeclared use of private services are banned. Absence from the public SDK is treated as unavailability for design purposes (GB-PLAT-009).

**A2 — No reverse engineering of undocumented internals.** Reading published AOSP source and upstream repositories is permitted research and produces SOURCE evidence only (e.g. GB-PLAT-003); shipping behavior must not depend on undeployed internals, on manifest details of a mutable branch, on decompilation of Google's shipped packages, on unexported services, or on ADB at runtime. No hidden API, no undocumented privilege, and no reliance on anything Google has not published as a supported interface.

**A3 — Built-in Terminal VM only.** Execution runs in Google's built-in Debian Terminal VM. No Termux, no cloud/remote IDE backend as a dependency, no second or alternative VM (QEMU-style), and no assumption of guest-root kernel replacement. Prior art from other environments (Haven's proot shims and alternate QEMU VM, WSL, Termux, VirtualHere) is evidence only, never imported as product design.

**A4 — User-driven Terminal bootstrap baseline.** Until a documented public API for VM control appears, the supported integration is: the user opens Terminal, the user approves a transparent bootstrap (no `curl | sudo sh` default), and the APK documents the Terminal dependency with honest VM-unavailable states. A silent architecture change to Termux, cloud or a replacement VM is banned. The negative API finding (GB-PLAT-004) is re-searched at the start of Stage 0 and before each integration release, and on the S00.01 reopen triggers [R01][R02][R03][R04][R05][R07][R08].

**A5 — Android-rendered original workbench; no guest GUI dependence.** The product renders the original upstream Code-OSS workbench in Android (WebView), preserving upstream panels, commands and keybinding services. No dependence on Linux GUI support: Terminal's alternative graphics screen is a user-facing display, not an integration surface, and the integrated Linux GUI/app-launcher experience is roadmap (GB-PLAT-007/008). No Electron port, no Compose/Monaco recreation, no desktop streaming as the main UI.

**A6 — Evidence categories and their limits.** Every capability claim uses the plan §2.1 vocabulary — DOC, SOURCE, DEVICE-PASS/DEVICE-FAIL, DESIGN, UNKNOWN, ROADMAP — and is recorded in the claim ledger with source IDs, scope and planned probe. Binding rules:

- A **ROADMAP** item is never marked available and never becomes a dependency (GB-PLAT-006/008, GB-USB-002).
- A **DOC** entry never becomes device support without a DEVICE result on the identified device.
- A **SOURCE** entry is not a shipping guarantee.
- An **UNKNOWN** stays unknown until a probe passes or fails; absence of evidence is recorded, not papered over.
- **DEVICE-PASS** comes only from a named physical-device observation with recorded model, build, SDK and relevant app/provider versions. The doc reviews never populate it. The 2026-10-16 ADB session (GB-DEV-001..007) is the first such entry.
- A negative search finding (no documented API) is bounded: it forbids reliance, not belief in impossibility, and it expires on any new publication.

**A7 — Emulator-versus-physical labeling.** Every observation carries an environment label: `physical:<model>/<build>` or `emulator:<image>/<version>`. Emulator results — including Google's desktop emulator (R69/P17) — are controls only; they never satisfy a physical-device gate (S00.02–S00.06, U-series) and never upgrade a ledger claim to DEVICE-PASS. A physical result is never inferred from an emulator pass, and an emulator failure never condemns the physical platform. Physical labels require the identity inventory of GB-DEV-001; a missing label invalidates the evidence.

## Consequences

- The first prototype must be a network probe APK (NET-01), not an editor shell: only browser forwarding is documented; APK-native and WebView reachability are UNKNOWN (GB-NET-001) and Chrome-only success blocks the architecture.
- Workspace onboarding requires two independent grants and a proven same-directory mapping (FS-01); no hard-coded guest path and no assumed POSIX semantics (GB-FS-001..004).
- The hardware profile owns Android-side USB authority and an in-guest VHCI importer (USB-01/USB-03); native passthrough stays roadmap (GB-USB-002/003). RFC2217 is not a fallback for the ESP32-S3 native controller (GB-SER-003).
- Distribution uses an independent brand, permitted registry and authorized VSIX; no Marketplace access or Microsoft identity (GB-EXT-001).
- Backend selection compares candidates against their own exact upstream controls (GB-BACK-001); no winner is declared without Android measurements.
- Anything not covered above stays UNKNOWN in the ledger and blocks the dependent release gate until probed — it is never downgraded to a silent assumption.

## Reopen triggers

Reopen this ADR when any of these change: Googlebook/Terminal version or manifest (Terminal observed at v17, GB-DEV-005), WebView provider/version (observed 149.0.7827.5, GB-DEV-004), guest kernel or VM service topology, target-SDK or local-network policy (R49), publication of any third-party VM/folder/USB/Antigravity integration API (flips GB-PLAT-004), USB passthrough or GUI roadmap items shipping (flips GB-USB-002/GB-PLAT-008), backend candidate or pinned upstream revision, libusb/exporter or ESP-IDF/OpenOCD toolchain, or the physical device's OTA (re-record GB-DEV-001..007).

## References

- [S00 — Platform truth and feasibility](../plan/S00-platform-feasibility.md) (S00.01 acceptance: every foundational claim sourced and statused; no roadmap item marked available)
- [Architecture specification v2.1](../Googlebook_CodeOSS_APK_Engineering_Plan_v2.md) (§2.1 evidence vocabulary, §15.2 probe matrix, §19 reference registry R01–R98)
- [Platform review 2026-10-16](../research/2026-10-16-platform-review.md) · [Upstream review 2026-10-16](../research/2026-10-16-upstream-review.md) · [Hardware review 2026-10-16](../research/2026-10-16-hardware-review.md)
- [Claim ledger](../research/claim-ledger.jsonl) — GB-PLAT-001..010, GB-NET-001..002, GB-WEB-001..002, GB-FS-001..004, GB-SER-001..003, GB-USB-001..007, GB-EXT-001..002, GB-BACK-001, GB-DEV-001..007
