# U03 — Original workbench and user extension freedom

[Plan index](README.md) · [Architecture specification](../Googlebook_CodeOSS_APK_Engineering_Plan_v2.md)

**Status:** planned; no implementation or device acceptance is claimed. **Default profile:** `wsl_usbip_desktop`. **Packages:** 8 scheduled, 0 excluded alternatives.

**Purpose:** Preserve upstream main UI and implement WSL-style placement, extension management, profiles and desktop interactions.

**Exit gate:** Original-workbench functional/visual tests and independently installed user extensions pass with a published delta inventory.

## Entry and execution contract

U03.01 starts early before APK customization; remaining parity packages join desktop, extension and lifecycle results. U03.06 explicitly waits for the real serial/debug extension evidence.

Preserve stock Explorer/Search/SCM/Run-Debug/Extensions/editor groups/palette/Problems/Output/terminal/settings/keybindings/themes/profiles, plus functional notebooks. Only reviewed OS/branding/platform differences are allowed. No custom dashboard or curated-only extension store.

## End-to-end smoke proof

In a clean profile use stock commands to open a shared project, navigate symbols, stage/revert a Git change, run a task, debug, execute a notebook and manage a user-chosen extension. Launch a spaced/Unicode path with gbcode, restore two windows and settings, then compare real input and layout to the matched upstream controls.

**Required evidence:** Per-candidate source provenance and screenshots; command/process assertions; independent extension workflows; standard remote/workspace interactions; delta inventory; U03.08 independent contract review.

**Failure containment:** Fail fidelity on substituted UI, missing core workflow, hidden Electron/Marketplace limitation or unverified process placement. Visual masks cannot hide structural gaps. UI success does not waive hardware success.

## Work packages

Dependencies below are effective for the stated profile, not stage-number order. Every implementation checkbox is initially open. Deliverable paths name outputs to create during implementation; they are not claims that those files exist. A package is complete only with its acceptance result, evidence and independent review.

## U03.01

**Establish pinned upstream desktop and web UI baselines**

**Status:** Planned; not started.

**Dependencies (default):** [S01.01](S01-workbench-vertical-slice.md#s0101), [S00.04](S00-platform-feasibility.md#s0004)

**Owner:** WEB/QA.

**Deliverable:** `tests/workbench/baselines/ + provenance manifest`.

- [ ] Build/select matching upstream desktop and web Code - OSS controls at a recorded source revision.
- [ ] Use fixed theme, fonts, display scale, viewport, workspace and selected extensions; record allowed OS/branding differences.
- [ ] Capture stock core panels and commands with source provenance and functional fixtures, not a manually reproduced editor.
- [ ] Define visual/semantic comparison methods before Android-specific patches accumulate.

- [ ] Build a matched desktop/web control pair for each candidate upstream commit. Cross-version comparisons may assess capability and freshness, but must not be presented as same-revision visual parity.

**Accept:** Reference sources and comparison conditions are reproducible; no broad difference mask hides redesigned UI.

**Rollback/containment:** Recreate baselines from the pinned source and keep prior evidence for version comparisons.

**Reopen before coding:** [R10] [R11] [R15] [R16].

## U03.02

**Preserve stock workbench layout and command surfaces**

**Status:** Planned; not started. Physical-device evidence required.

**Dependencies (default):** [S01.06](S01-workbench-vertical-slice.md#s0106), [U03.01](U03-workbench-fidelity.md#u0301), [S04.01](S04-desktop-experience.md#s0401)

**Owner:** WEB/ANDROID.

**Deliverable:** `workbench patch inventory + stock-surface tests`.

- [ ] Reuse actual upstream main surfaces without replacement dashboard, simplified mobile UI or forced hardware sidebar.
- [ ] Implement only needed Android shell/branding/platform hooks and record each frontend patch with rationale.
- [ ] Verify Explorer, Search, SCM, Run/Debug, Extensions, terminals, settings, profiles and split editors against the control.
- [ ] Place connection/device actions in standard commands/status/Quick Pick and native permission dialogs.

**Accept:** The main application is the upstream workbench; approved platform differences are explicit and no required surface is missing.

**Rollback/containment:** Revert UI substitutions or excess patches rather than accept a lookalike as completion.

**Reopen before coding:** [R10] [R14] [R15] [R21].

## U03.03

**Validate extension-host placement without blanket overrides**

**Status:** Planned; not started. Physical-device evidence required.

**Dependencies (default):** [S08.01](S08-extension-ecosystem.md#s0801), [S08.03](S08-extension-ecosystem.md#s0803)

**Owner:** EXTENSIONS/GUEST.

**Deliverable:** `extensions/host-matrix.json + placement tests`.

- [ ] Classify main/browser entry points, extensionKind, native executable architecture, licensing and UI assumptions.
- [ ] Verify guest Node processes and frontend workers on actual selected extensions; distinguish absent local Electron/Node host.
- [ ] Reject global workspace placement overrides and preserve normal extension settings/activation behavior.
- [ ] Report incompatible vendor/native/UI dependencies through normal workbench errors and documented matrix.

**Accept:** Each tested extension runs in a valid host; unsupported cases are explicit and no blanket compatibility promise is made.

**Rollback/containment:** Remove unsafe host overrides and restore supported versions/profiles.

**Reopen before coding:** [R11] [R16] [R17] [R18].

## U03.04

**Implement unrestricted compatible-extension installation UX**

**Status:** Planned; not started. Physical-device evidence required.

**Dependencies (default):** [U03.03](U03-workbench-fidelity.md#u0303), [S04.04](S04-desktop-experience.md#s0404)

**Owner:** EXTENSIONS/WEB.

**Deliverable:** `tests/extensions/user-installation/`.

- [ ] Use the original Extensions view for permitted registry search/install and legally obtained VSIX import.
- [ ] Test user choice beyond curated bundles, disable/enable/uninstall, version upgrade/rollback and profile persistence.
- [ ] Handle native architecture/dependency download errors without silently replacing the selected extension.
- [ ] Verify offline reopening and explain proprietary registry/service limitations without spoofing product identity.

**Accept:** Users can manage compatible extensions of their choice in stock UI; certification fixtures are not a hidden allowlist.

**Rollback/containment:** Revert broken registry/config changes and retain users existing profile/extension data.

**Reopen before coding:** [R11] [R14] [R16] [R22].

## U03.05

**Implement WSL-style workspace, terminal and launcher behavior**

**Status:** Planned; not started. Physical-device evidence required.

**Dependencies (default):** [U03.02](U03-workbench-fidelity.md#u0302), [S09.02](S09-lifecycle-and-recovery.md#s0902)

**Owner:** WEB/GUEST/ANDROID.

**Deliverable:** `workbench integration commands + gbcode CLI`.

- [ ] Implement remote target status, connect/reopen/open-shared-folder commands and guest-default terminals using standard workbench APIs.
- [ ] Resolve guest path requests securely through paired sessions; optional code alias requires consent and no clobbering.
- [ ] Keep native UI limited to enrollment/OS permission/recovery and use supported notification/deep-link behavior for foregrounding.
- [ ] Test multiwindow, missing VM, saved/unsaved state and separation of guest settings from frontend credentials.

- [ ] Prove settings/profile persistence and explicit recovery of dirty buffers across two windows; do not promise guest persistence for state that the selected backend still keeps only in browser storage.

**Accept:** The application behaves like a coherent desktop remote-Linux workbench without pretending it implements Microsoft WSL or a private VM API.

**Rollback/containment:** Keep manual supported open/reconnect actions when automatic activation is unavailable; no silent local project copy.

**Reopen before coding:** [R01] [R02] [R21] [R59] [R77] [R84].

## U03.06

**Certify user-selected extension workflows beyond starter packs**

**Status:** Planned; not started. Physical-device evidence required.

**Dependencies (default):** [U03.04](U03-workbench-fidelity.md#u0304), [S08.06](S08-extension-ecosystem.md#s0806), [U02.02](U02-hardware-certification.md#u0202), [U02.05](U02-hardware-certification.md#u0205)

**Owner:** EXTENSIONS/QA.

**Deliverable:** `tests/evidence/user-extension-choice/`.

- [ ] Use clean profiles and install representative user-chosen theme, language, serial and debug extensions from lawful sources.
- [ ] Check primary workflows, update/uninstall and project/profile reuse rather than only successful download.
- [ ] Run separate guest architecture cases for native tools and record unsupported extensions with exact reasons.
- [ ] For the default hardware profile, correlate serial/debug extensions with U02 evidence; narrower profiles keep their limitations.

- [ ] Use U02.02 and U02.05 evidence for the installed serial/debug extensions; do not mark this package complete with stubs while the real USB/IP path is blocked.

**Accept:** User-controlled installation and ordinary workflows pass outside the curated preset; no test implies every Marketplace extension works.

**Rollback/containment:** Pin last working tested versions or remove only the selected extension on user request.

**Reopen before coding:** [R11] [R16] [R17] [R18] [R22].

## U03.07

**Run visual, command and desktop-input parity regressions**

**Status:** Planned; not started. Physical-device evidence required.

**Dependencies (default):** [U03.02](U03-workbench-fidelity.md#u0302), [U03.05](U03-workbench-fidelity.md#u0305), [S10.05](S10-security-and-performance.md#s1005)

**Owner:** WEB/QA.

**Deliverable:** `tests/evidence/workbench-parity/`.

- [ ] Compare stock surfaces to the pinned reference with reviewed OS/branding/rasterization differences only.
- [ ] Run functional commands against actual guest files and processes, including standard extension webviews and debug controls.
- [ ] Exercise physical shortcuts, IME, pointer, clipboard, menus, scaling, multiple windows and accessibility.
- [ ] Reject fake screenshot-only passes and protect baselines against masking regressions with expanded ignores.

- [ ] Include notebook execution, tasks/problem matchers, a breakpoint/step session and settings/profile round-trip in the command suite, not only Explorer and terminal screenshots.

**Accept:** Main UI structure/commands and actual desktop input pass with a published minimal delta inventory.

**Rollback/containment:** Revert violating patches or mark the release blocked; do not rename a simplified UI exact VS Code.

**Reopen before coding:** [R10] [R16] [R21] [R41] [R53] [R55].

## U03.08

**Independently approve workbench fidelity and plugin freedom**

**Status:** Planned; not started. Physical-device evidence required.

**Dependencies (default):** [U03.03](U03-workbench-fidelity.md#u0303), [U03.04](U03-workbench-fidelity.md#u0304), [U03.05](U03-workbench-fidelity.md#u0305), [U03.06](U03-workbench-fidelity.md#u0306), [U03.07](U03-workbench-fidelity.md#u0307)

**Owner:** LEAD/QA.

**Deliverable:** `docs/release/workbench-contract-review.md`.

- [ ] Review upstream provenance, patch size, screenshots, actual command results, host placement and user-selected installation evidence.
- [ ] Confirm the release has no forced dashboard, curated-only plugin policy, undisclosed native-host limitation or brand/Marketplace misrepresentation.
- [ ] Publish exact supported source/version, allowed platform differences and unresolved limitations.
- [ ] Require this gate alongside the selected hardware profile; UI fidelity cannot substitute for USB success.

**Accept:** The requested original-workbench UX and extension choice are independently approved with real target-device evidence.

**Rollback/containment:** Hold release until unsupported substitutions or missing evidence are resolved.

**Reopen before coding:** [R10] [R11] [R16] [R21] [R84].

## Source refresh

Start with the [dated upstream review](../research/2026-10-16-upstream-review.md), then reopen the task-specific references before implementation and on relevant version changes. Inherited links are research starting points, not a claim that all 98 sources were revalidated in this review. Mutable documentation is not a dependency lock.

[R01]: https://developer.android.com/blog/posts/googlebook-linux-terminal-preview-vm
[R02]: https://support.google.com/googlebook/answer/18119222?hl=en
[R10]: https://github.com/microsoft/vscode
[R11]: https://code.visualstudio.com/docs/supporting/faq
[R14]: https://coder.com/docs/code-server/FAQ
[R15]: https://github.com/gitpod-io/openvscode-server
[R16]: https://code.visualstudio.com/api/advanced-topics/extension-host
[R17]: https://code.visualstudio.com/api/advanced-topics/remote-extensions
[R18]: https://code.visualstudio.com/api/extension-guides/web-extensions
[R21]: https://code.visualstudio.com/api/references/vscode-api
[R22]: https://github.com/eclipse-openvsx/openvsx
[R41]: https://developer.android.com/develop/ui/views/layout/webapps/webview
[R53]: https://developer.android.com/develop/ui/views/layout/support-multi-window-mode
[R55]: https://developer.android.com/develop/ui/views/touch-and-input/keyboard-input/commands
[R59]: https://developer.android.com/privacy-and-security/keystore
[R77]: https://developer.android.com/training/app-links
[R84]: https://code.visualstudio.com/docs/remote/wsl
