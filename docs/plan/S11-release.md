# S11 — Distribution and release handoff

[Plan index](README.md) · [Architecture specification](../Googlebook_CodeOSS_APK_Engineering_Plan_v2.md)

**Status:** planned; no implementation or device acceptance is claimed. **Default profile:** `wsl_usbip_desktop`. **Packages:** 6 scheduled, 0 excluded alternatives.

**Purpose:** Produce auditable packages and an honest capability-scoped release.

**Exit gate:** Signed APK and guest bundle, source/SBOM, fresh-device reproduction, support matrix and known limitations are approved.

## Entry and execution contract

Build candidates after reviewed provenance, updater, security and protocol work. Final release waits for all mandatory gates and a fresh-device/VM install, not merely a signed APK.

This is an independently branded Code - OSS distribution. Preserve upstream notices and dependency/source obligations; no implicit Microsoft Marketplace, Settings Sync, remote-extension or Google endorsement rights. Keep signing keys outside the repository and coding-agent scope.

## End-to-end smoke proof

Install the signed candidate on a fresh certified device/VM profile from published instructions. Provision, pair, edit/build/debug, install a lawful user extension, run the mandatory hardware workflows offline, update/roll back and uninstall while preserving source. Verify manifests/hashes/signatures and recreate the build from pinned inputs.

**Required evidence:** SBOM/notices/source availability; signed architecture artifacts and hashes; reproducibility variance report; sideload/store policy decisions; fresh-install replay; user/support docs; signed S11.06 gate report.

**Failure containment:** A passing installer or store review cannot waive any default requirement. Hold release on missing hardware/evidence/license/identity policy, unsupported native ABI/page sizes or broken rollback. Publish only the exact reviewed capability matrix.

## Work packages

Dependencies below are effective for the stated profile, not stage-number order. Every implementation checkbox is initially open. Deliverable paths name outputs to create during implementation; they are not claims that those files exist. A package is complete only with its acceptance result, evidence and independent review.

## S11.01

**Complete license, provenance and supply-chain review**

**Status:** Planned; not started.

**Dependencies (default):** [S08.01](S08-extension-ecosystem.md#s0801), [S09.03](S09-lifecycle-and-recovery.md#s0903)

**Owner:** RELEASE/SECURITY.

**Deliverable:** `SBOM, notices and source/patch manifest`.

- [ ] Inventory backend, source patches, native libraries, bundled extensions, tools, icons and fonts.
- [ ] Check redistribution permissions, own-brand presentation and permitted registry access against current primary sources.
- [ ] Generate SBOM/checksums and ensure no signing or provider credentials are present in artifacts.

**Accept:** The exact shipping contents have traceable provenance and reviewed distribution rights.

**Rollback/containment:** Remove any component with unresolved rights and regenerate the bundle.

**Reopen before coding:** [R11] [R13] [R70] [R78] [R22].

## S11.02

**Build and verify signed architecture-specific packages**

**Status:** Planned; not started.

**Dependencies (default):** [S11.01](S11-release.md#s1101), [S10.01](S10-security-and-performance.md#s1001), [S10.02](S10-security-and-performance.md#s1002)

**Owner:** RELEASE.

**Deliverable:** `signed APKs and guest bundles`.

- [ ] Build the declared Android ABIs and corresponding guest packages on supported build hosts.
- [ ] Check native-library/page-size compatibility, signatures, reproducible manifest data and version constraints.
- [ ] Keep signing operations in a restricted release workflow separate from general coding agents.
- [ ] Reproduce from pinned inputs, enumerate build variance rather than promise bit-identical signing, verify native 16 KB page-size compatibility where required and retain hashes, notices, symbols and rollback artifacts.

**Accept:** Each artifact is installable on its declared target and matches signed hashes/source provenance.

**Rollback/containment:** Retain the prior signed release; never replace an artifact in place with a different untracked build.

**Reopen before coding:** [R61] [R62] [R65].

## S11.03

**Review sideload and store distribution requirements**

**Status:** Planned; not started. Physical-device evidence required.

**Dependencies (default):** [S11.02](S11-release.md#s1102)

**Owner:** RELEASE/RESEARCH.

**Deliverable:** `distribution checklist and tested install flow`.

- [ ] Recheck current target-SDK, foreground-service, permission, remotely loaded code and update policies for the chosen channel.
- [ ] Test user-driven APK installation/update/uninstall and permission explanations without assuming store approval. Review Play interpreted-code/native-bridge policies, scoped storage, app-update rules and current developer-verification requirements for the chosen distribution channel.
- [ ] Keep store-specific requirements separate from the core sideloadable APK; remove unnecessary permissions.

- [ ] Keep sideload and store approvals distinct; recheck applicable developer-verification and target-SDK rules at release time. Do not treat sideloading as exemption from identity requirements or assume guest downloads automatically violate Play policy.

**Accept:** The chosen distribution route has a current policy review and a tested user flow; Play acceptance is not claimed in advance.

**Rollback/containment:** Use the approved sideload/internal-test route while unresolved store issues are corrected.

**Reopen before coding:** [R64] [R58] [R50] [R79] [R80] [R81] [R82].

## S11.04

**Reproduce installation on a fresh device/VM profile**

**Status:** Planned; not started. Physical-device evidence required.

**Dependencies (default):** [S11.02](S11-release.md#s1102), [S09.04](S09-lifecycle-and-recovery.md#s0904), [S10.06](S10-security-and-performance.md#s1006)

**Owner:** QA.

**Deliverable:** `fresh-install acceptance record`.

- [ ] Follow only the documented instructions with no developer's hidden environment state.
- [ ] Provision, pair, share a folder, install profiles and complete an approved edit/build/serial workflow.
- [ ] Repeat on each claimed platform profile or clearly narrow the release matrix.

**Accept:** An independent tester can reproduce the advertised workflow without undocumented commands or privileges.

**Rollback/containment:** Reset only the approved disposable environment and preserve user backups.

**Reopen before coding:** [R02] [R03] [R63].

## S11.05

**Publish user, contributor and troubleshooting documentation**

**Status:** Planned; not started.

**Dependencies (default):** [S09.06](S09-lifecycle-and-recovery.md#s0906), [S11.03](S11-release.md#s1103), [S11.04](S11-release.md#s1104)

**Owner:** LEAD/DOCS.

**Deliverable:** `release documentation set`.

- [ ] Publish supported devices/extensions, separate storage modes, Terminal dependency and known USB limitations.
- [ ] Include safe recovery, update/rollback, diagnostic export and contribution/research rules.
- [ ] Link every platform claim to current primary sources and include issue templates requiring version/evidence data.

**Accept:** Documentation describes the shipping artifact rather than aspirational future capabilities.

**Rollback/containment:** Withdraw inaccurate claims and link the last verified instructions.

**Reopen before coding:** [R01] [R02] [R03] [R11].

## S11.06

**Approve a capability-scoped release**

**Status:** Planned; not started.

**Dependencies (default):** [S11.05](S11-release.md#s1105), [S10.03](S10-security-and-performance.md#s1003), [S10.04](S10-security-and-performance.md#s1004), [S10.05](S10-security-and-performance.md#s1005), [U03.08](U03-workbench-fidelity.md#u0308), [U02.08](U02-hardware-certification.md#u0208), [S03.05](S03-dedicated-workspace.md#s0305), [S08.05](S08-extension-ecosystem.md#s0805)

**Owner:** LEAD/QA.

**Deliverable:** `signed release gate report`.

- [ ] Review all mandatory gates, test evidence, unresolved defects and conditional/deferred tasks.
- [ ] Approve only the explicitly selected profile and its required gates: wsl_usbip_desktop requires actual USB/IP, S3 serial/JTAG/concurrency, an external adapter and original-workbench parity; narrowed modes remain labeled.
- [ ] Archive versions, ADRs, source ledger and handoff artifacts; define OS/WebView/backend change revalidation triggers.

**Accept:** Only the explicitly selected profile is released; its mandatory UI, extension, hardware, security and physical-evidence gates pass. Serial fallback is not completion of wsl_usbip_desktop.

**Rollback/containment:** Do not release; preserve a candidate build and explicit blocker list.

**Reopen before coding:** [R01] [R14] [R15] [R23].

## Source refresh

Start with the [dated platform review](../research/2026-10-16-platform-review.md), then reopen the task-specific references before implementation and on relevant version changes. Inherited links are research starting points, not a claim that all 98 sources were revalidated in this review. Mutable documentation is not a dependency lock.

[R01]: https://developer.android.com/blog/posts/googlebook-linux-terminal-preview-vm
[R02]: https://support.google.com/googlebook/answer/18119222?hl=en
[R03]: https://support.google.com/googlebook/answer/18668704?hl=en
[R11]: https://code.visualstudio.com/docs/supporting/faq
[R13]: https://code.visualstudio.com/brand
[R14]: https://coder.com/docs/code-server/FAQ
[R15]: https://github.com/gitpod-io/openvscode-server
[R22]: https://github.com/eclipse-openvsx/openvsx
[R23]: https://developer.android.com/develop/connectivity/usb/host
[R50]: https://developer.android.com/reference/android/Manifest.permission
[R58]: https://developer.android.com/develop/background-work/services/fgs/service-types
[R61]: https://developer.android.com/ndk/guides/abis
[R62]: https://developer.android.com/guide/practices/page-sizes
[R63]: https://developer.android.com/tools/adb
[R64]: https://developer.android.com/google/play/requirements/target-sdk
[R65]: https://developer.android.com/studio/install
[R70]: https://code.visualstudio.com/license
[R78]: https://github.com/microsoft/vscode/blob/main/LICENSE.txt
[R79]: https://support.google.com/googleplay/android-developer/answer/16559646?hl=en
[R80]: https://support.google.com/googleplay/android-developer/answer/10768383?hl=en
[R81]: https://support.google.com/googleplay/android-developer/answer/10467955?hl=en-GB
[R82]: https://developer.android.com/developer-verification
