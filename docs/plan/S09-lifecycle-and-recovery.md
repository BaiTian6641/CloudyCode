# S09 — Operational lifecycle and recoverability

[Plan index](README.md) · [Architecture specification](../Googlebook_CodeOSS_APK_Engineering_Plan_v2.md)

**Status:** planned; no implementation or device acceptance is claimed. **Default profile:** `wsl_usbip_desktop`. **Packages:** 6 scheduled, 0 excluded alternatives.

**Purpose:** Provide transparent installation, controlled services, upgrades and VM-reset recovery.

**Exit gate:** A user can provision, reconnect, update, roll back, diagnose and recover without undocumented host privileges.

## Entry and execution contract

Build on reviewed pairing, verified storage and lawful extension installation. Installation is user-driven in Terminal until a supported ordinary-APK integration API is proven.

APK, backend/workbench, daemon/protocol and companion extension form a compatibility set. Stage releases atomically; preserve state separately and identify irreversible migrations. Terminal-window independence remains unproven/roadmap, not a daemon feature.

## End-to-end smoke proof

Provision a clean guest with an inspected signed/pinned package, pair, build and save a project. Close/reopen Terminal, kill the renderer/app/VM separately, suspend/resume and interrupt an update. Roll back, restore a backup after an explicitly approved VM reset, and re-pair the changed identity; compare saved sources and configuration inventory.

**Required evidence:** Install/uninstall logs and provenance; service capability decision; update/rollback manifest; saved/unsent-state survival inventory; LIFE tests; redacted diagnostic export.

**Failure containment:** Never execute curl-to-sudo blindly or reset the user VM automatically. No replay of uncertain hardware writes and no claim that tmux/systemd prevents VM destruction. Uninstall preserves user work and unrelated packages/settings.

## Work packages

Dependencies below are effective for the stated profile, not stage-number order. Every implementation checkbox is initially open. Deliverable paths name outputs to create during implementation; they are not claims that those files exist. A package is complete only with its acceptance result, evidence and independent review.

## S09.01

**Implement transparent guest bootstrap and uninstall**

**Status:** Planned; not started.

**Dependencies (default):** [S02.02](S02-connection-and-trust.md#s0202), [S03.04](S03-dedicated-workspace.md#s0304), [S08.01](S08-extension-ecosystem.md#s0801)

**Owner:** GUEST/RELEASE.

**Deliverable:** `guest/bootstrap and packaging`.

- [ ] Detect guest OS/architecture/dependencies and verify pinned artifacts before installation.
- [ ] Install into versioned user-owned directories, enumerate any required sudo package operations and support idempotent reruns.
- [ ] Provide offline provisioning and uninstall that preserves projects, backups and unrelated tools.

**Accept:** A clean supported guest installs reproducibly without an unreviewed privileged pipe-to-shell command.

**Rollback/containment:** Uninstall only tracked artifacts and retain user data and previous release.

**Reopen before coding:** [R02] [R14] [R15] [R61].

## S09.02

**Integrate service startup and VM dependency UX**

**Status:** Planned; not started. Physical-device evidence required.

**Dependencies (default):** [S09.01](S09-lifecycle-and-recovery.md#s0901), [S04.06](S04-desktop-experience.md#s0406)

**Owner:** GUEST/ANDROID.

**Deliverable:** `startup supervisor and connection state UI`.

- [ ] Test user-service availability and implement a supported fallback launcher where absent.
- [ ] Expose actionable Terminal-required, stopped-backend and wrong-route states without private VM-control calls.
- [ ] Validate an optional launch-only intent separately; keep manual startup as supported fallback.

**Accept:** Startup instructions match the real guest and no service claims to keep a terminated VM alive.

**Rollback/containment:** Stop the launcher and return to manual guest startup.

**Reopen before coding:** [R02] [R03] [R06] [R07].

## S09.03

**Implement signed coordinated updates and rollback**

**Status:** Planned; not started.

**Dependencies (default):** [S09.01](S09-lifecycle-and-recovery.md#s0901), [S02.06](S02-connection-and-trust.md#s0206), [S08.06](S08-extension-ecosystem.md#s0806)

**Owner:** RELEASE/SECURITY.

**Deliverable:** `release manifest and update manager`.

- [ ] Verify artifacts and compatibility across APK/backend/workbench/protocol/extension revisions.
- [ ] Install guest releases side-by-side, activate atomically and classify state migrations as reversible or irreversible.
- [ ] Test interrupted download/install, invalid signatures, disk full and rollback after smoke-test failure.

**Accept:** Unverified or incompatible artifacts never replace the last working release; state rollback claims are accurate.

**Rollback/containment:** Reactivate the prior compatible release and preserve failed artifacts for diagnosis.
**Reopen before coding:** [R48] [R59] [R78].

## S09.04

**Validate VM reset and complete recovery**

**Status:** Planned; not started. Physical-device evidence required.

**Dependencies (default):** [S03.06](S03-dedicated-workspace.md#s0306), [S09.01](S09-lifecycle-and-recovery.md#s0901)

**Owner:** QA/STORAGE.

**Deliverable:** `reset/restore runbook and evidence`.

- [ ] Use a disposable or explicitly approved VM to exercise the actual reset workflow after taking backups.
- [ ] Repair pairing, remap shared folders and restore guest-native worktrees/profile configuration.
- [ ] Verify source hashes, Git state, extensions and secret-reenrollment behavior; identify unrecoverable unsaved-state cases.

**Accept:** Recovery works from the documented backup categories without implying host source is the whole VM.

**Rollback/containment:** Retain backups and stop before destructive reset when required evidence/approval is absent.

**Reopen before coding:** [R01] [R02] [R51] [R59].

## S09.05

**Test suspend, Terminal closure and long-session recovery**

**Status:** Planned; not started. Physical-device evidence required.

**Dependencies (default):** [S09.02](S09-lifecycle-and-recovery.md#s0902), [S05.06](S05-hardware-authority.md#s0506), [S04.06](S04-desktop-experience.md#s0406)

**Owner:** QA/ANDROID.

**Deliverable:** `lifecycle failure matrix`.

- [ ] Test minimize, window close, app kill, screen lock, lid close, Terminal close and VM restart as separate events.
- [ ] Check build/session survival only when the VM actually remains alive, and verify hardware no-replay behavior.
- [ ] Run scenarios during editing, build output and an approved hardware operation with safe recovery procedures.

**Accept:** Each interruption has measured behavior and a clear user message; no blanket persistence promise remains.

**Rollback/containment:** Terminate affected sessions safely and preserve recovery journals/logs.

**Reopen before coding:** [R01] [R02] [R42] [R58] [R76].

## S09.06

**Implement actionable diagnostics and support export**

**Status:** Planned; not started.

**Dependencies (default):** [S09.02](S09-lifecycle-and-recovery.md#s0902), [U01.06](U01-production-usbip.md#u0106)

**Owner:** GUEST/ANDROID.

**Default-profile scope:** Diagnose USB/IP identities, safe kernel state, relay and native exporter without payload/token export.

**Deliverable:** `gbcode doctor and redacted export`.

- [ ] Report actual versions, routes, permissions, shared mapping, device capabilities and queue/error counters.
- [ ] Provide a previewed redacted export without source, payloads, keys or personal device identifiers by default.
- [ ] Map frequent errors to precise remediation and label mock versus hardware evidence.

**Accept:** A support report distinguishes VM, network, origin, storage, extension and hardware failures without leaking secrets.

**Rollback/containment:** Disable diagnostics collection and delete only explicitly selected diagnostic exports.

**Reopen before coding:** [R02] [R42] [R49].

## Source refresh

Start with the [dated platform review](../research/2026-10-16-platform-review.md), then reopen the task-specific references before implementation and on relevant version changes. Inherited links are research starting points, not a claim that all 98 sources were revalidated in this review. Mutable documentation is not a dependency lock.

[R01]: https://developer.android.com/blog/posts/googlebook-linux-terminal-preview-vm
[R02]: https://support.google.com/googlebook/answer/18119222?hl=en
[R03]: https://support.google.com/googlebook/answer/18668704?hl=en
[R06]: https://source.android.com/docs/core/virtualization/virtualization-service
[R07]: https://android.googlesource.com/platform/packages/modules/Virtualization/+/refs/heads/main/android/TerminalApp/AndroidManifest.xml
[R14]: https://coder.com/docs/code-server/FAQ
[R15]: https://github.com/gitpod-io/openvscode-server
[R42]: https://developer.android.com/develop/ui/views/layout/webapps/managing-webview
[R48]: https://developer.android.com/privacy-and-security/security-config
[R49]: https://developer.android.com/privacy-and-security/local-network-permission
[R51]: https://developer.android.com/training/data-storage/shared/documents-files
[R58]: https://developer.android.com/develop/background-work/services/fgs/service-types
[R59]: https://developer.android.com/privacy-and-security/keystore
[R61]: https://developer.android.com/ndk/guides/abis
[R76]: https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
[R78]: https://github.com/microsoft/vscode/blob/main/LICENSE.txt
