# S08 — Extension ecosystem and engineering profiles

[Plan index](README.md) · [Architecture specification](../Googlebook_CodeOSS_APK_Engineering_Plan_v2.md)

**Status:** planned; no implementation or device acceptance is claimed. **Default profile:** `wsl_usbip_desktop`. **Packages:** 6 scheduled, 0 excluded alternatives.

**Purpose:** Define useful supported workflows and the extension compatibility envelope.

**Exit gate:** Chosen language/debug profiles and extension versions pass on their declared guest architectures, including offline reopening.

## Entry and execution contract

Configure the permitted registry after candidate licensing and backend selection. Starter profiles are reproducible examples, not a list of extensions the user is allowed to install.

Record publisher/ID/version/license/artifact/host/ABI and downloaded dependencies. Browser workers are not Electron-local Node hosts; workspace extension computation and build/debug tools execute in Debian. Secrets and ordinary UI state need separate persistence decisions.

## End-to-end smoke proof

From a clean profile install a lawful theme, language extension, debug adapter and notebook provider through stock UI/VSIX. Run C/C++, Python, TypeScript and notebook workloads with completion, tasks, Git and debugging; open a guest web preview on a separate origin. Disable/uninstall/update and repeat provisioned workflows without internet.

**Required evidence:** Registry/license decision; per-architecture extension/tool matrix; real host-placement/process records; offline/update reports; profile/settings/secret persistence decision.

**Failure containment:** Report legal, architecture, Electron-local and entitlement incompatibilities precisely. Do not force all extensions to workspace, spoof Marketplace identity, auto-replace the user selection or require an AI subscription for local development.

## Work packages

Dependencies below are effective for the stated profile, not stage-number order. Every implementation checkbox is initially open. Deliverable paths name outputs to create during implementation; they are not claims that those files exist. A package is complete only with its acceptance result, evidence and independent review.

## S08.01

**Configure a permitted extension registry and VSIX flow**

**Status:** Planned; not started.

**Dependencies (default):** [S01.06](S01-workbench-vertical-slice.md#s0106), [S01.01](S01-workbench-vertical-slice.md#s0101)

**Owner:** WEB/RELEASE.

**Deliverable:** `registry configuration and extension manifest`.

- [ ] Use Open VSX or another permitted curated source, with source/license checks for every bundled extension.
- [ ] Implement local VSIX installation with version/hash metadata and clear trust warnings.
- [ ] Test install/update/remove without adding unauthorized Marketplace endpoints.
- [ ] Keep installation in the standard Extensions view; supported registry and lawful VSIX choices are user-controlled, not confined to a curated allowlist.

**Accept:** All default extensions have provenance and license records; registry configuration survives restart.

**Rollback/containment:** Revert registry configuration and uninstall only project-bundled test extensions.

**Reopen before coding:** [R11] [R14] [R22] [R78].

## S08.02

**Build baseline C/C++, Python and TypeScript profiles**

**Status:** Planned; not started. Physical-device evidence required.

**Dependencies (default):** [S03.04](S03-dedicated-workspace.md#s0304), [S01.06](S01-workbench-vertical-slice.md#s0106), [S08.01](S08-extension-ecosystem.md#s0801)

**Owner:** GUEST/WEB.

**Deliverable:** `sample projects and profile manifests`.

- [ ] Pin representative guest toolchains/extensions and define deterministic build/test commands.
- [ ] Prove language services, formatting, terminal execution and one real debug workflow per claimed profile.
- [ ] Keep profile configuration architecture-aware and generated outputs out of unsuitable shared storage.

- [ ] Require one permitted notebook/kernel workflow alongside C/C++, Python and TypeScript. Keep downloaded tools and native modules guest-architecture specific; identify any profile whose extension or debugger is legally unavailable.

**Accept:** Sample projects pass from the APK with actual guest process evidence and no hidden workstation dependency.

**Rollback/containment:** Remove only profile-managed packages/configuration after user review.

**Reopen before coding:** [R16] [R17] [R21].

## S08.03

**Audit native modules and extension-host placement**

**Status:** Planned; not started.

**Dependencies (default):** [S08.01](S08-extension-ecosystem.md#s0801), [S08.02](S08-extension-ecosystem.md#s0802)

**Owner:** GUEST/QA.

**Deliverable:** `extension architecture compatibility report`.

- [ ] Inspect entry points, extensionKind, native node modules and downloaded helper executables.
- [ ] Test available ARM64 and x86_64 guest builds independently; classify UI/Electron-only and proprietary dependencies.
- [ ] Add per-extension fixes only with a maintained patch and regression test; avoid global host-placement overrides.
- [ ] Distinguish guest Node, browser worker and unavailable Electron-local Node hosts; do not globally override extensionKind or promise vendor entitlements.

**Accept:** Every supported extension version has a declared runtime/architecture and measured outcome.

**Rollback/containment:** Disable incompatible versions and restore the last certified extension lock.

**Reopen before coding:** [R16] [R17] [R18] [R61].

## S08.04

**Integrate development previews and external URI handling**

**Status:** Planned; not started. Physical-device evidence required.

**Dependencies (default):** [S02.05](S02-connection-and-trust.md#s0205), [S04.05](S04-desktop-experience.md#s0405), [S08.02](S08-extension-ecosystem.md#s0802)

**Owner:** WEB/GUEST.

**Deliverable:** `preview and URI integration tests`.

- [ ] Reuse backend port/external-URI support for guest web applications and extension callbacks.
- [ ] Isolate untrusted project web content from the privileged workbench/native origin.
- [ ] Test relative URLs, websockets, multiple preview ports, denied exposure and external-browser opening.

**Accept:** Previews work without broad port exposure or inherited native permissions.

**Rollback/containment:** Close preview routes and return to explicit external-browser access.

**Reopen before coding:** [R14] [R17] [R19] [R21].

## S08.05

**Define optional agent/AI extension profiles**

**Status:** Planned conditional scope decision; implementation only when justified.

**Dependencies (default):** [S08.03](S08-extension-ecosystem.md#s0803), [S04.05](S04-desktop-experience.md#s0405)

**Owner:** LEAD/WEB.

**Deliverable:** reviewed AI-profile scope decision; if enabled, `optional AI integration profile`.

- [ ] Validate the actual chosen providers/model IDs and extensions against their current official documentation.
- [ ] Keep API credentials private and configure explicit permissions for terminal, file and hardware actions.
- [ ] Test search-tool availability and evidence logging separately from model text generation; do not assume models browse unaided.

- [ ] A reviewed decision not to ship an AI profile completes the scope assessment, not an AI capability. No hosted model, provider-specific worker allocation or paid subscription is a prerequisite for the offline IDE.

**Accept:** Either an independently reviewed decision excludes a bundled AI profile while preserving user extension choice, or the enabled profile passes its authorized-provider/secret/hardware boundary checks. Core local IDE operation must remain independent of hosted models and subscriptions.

**Rollback/containment:** Revoke provider credentials and disable the optional profile without affecting the IDE.

**Reopen before coding:** [R66] [R67] [R68] [R20].

## S08.06

**Certify offline and extension-update behavior**

**Status:** Planned; not started. Physical-device evidence required.

**Dependencies (default):** [S08.03](S08-extension-ecosystem.md#s0803), [S08.04](S08-extension-ecosystem.md#s0804)

**Owner:** QA/WEB.

**Deliverable:** `offline and update compatibility suite`.

- [ ] Disconnect external networking after provisioning and open/edit/build the sample projects.
- [ ] Detect remote asset dependencies and classify features genuinely requiring a registry/provider connection.
- [ ] Test extension update rollback and workbench/backend compatibility checks.

- [ ] Verify settings, keybindings, profiles, extension state and secrets separately after reconnect, APK update, renderer death and browser-origin change. If choosing code-server remote storage, review encryption/fallback semantics rather than assume disk persistence implies encrypted secrets.

**Accept:** Core local workflows remain usable offline; network-required optional features fail clearly.

**Rollback/containment:** Restore the extension lock and cached self-contained assets.

**Reopen before coding:** [R14] [R18] [R22].

## Source refresh

Start with the [dated upstream review](../research/2026-10-16-upstream-review.md), then reopen the task-specific references before implementation and on relevant version changes. Inherited links are research starting points, not a claim that all 98 sources were revalidated in this review. Mutable documentation is not a dependency lock.

[R11]: https://code.visualstudio.com/docs/supporting/faq
[R14]: https://coder.com/docs/code-server/FAQ
[R16]: https://code.visualstudio.com/api/advanced-topics/extension-host
[R17]: https://code.visualstudio.com/api/advanced-topics/remote-extensions
[R18]: https://code.visualstudio.com/api/extension-guides/web-extensions
[R19]: https://code.visualstudio.com/api/extension-guides/webview
[R20]: https://code.visualstudio.com/api/extension-guides/workspace-trust
[R21]: https://code.visualstudio.com/api/references/vscode-api
[R22]: https://github.com/eclipse-openvsx/openvsx
[R61]: https://developer.android.com/ndk/guides/abis
[R66]: https://docs.z.ai/guides/vlm/glm-5.3-flash
[R67]: https://platform.kimi.ai/docs/overview
[R68]: https://api-docs.deepseek.com/
[R78]: https://github.com/microsoft/vscode/blob/main/LICENSE.txt
