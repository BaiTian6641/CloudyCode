# S03 — Dedicated development space

[Plan index](README.md) · [Architecture specification](../Googlebook_CodeOSS_APK_Engineering_Plan_v2.md)

**Status:** planned; no implementation or device acceptance is claimed. **Default profile:** `wsl_usbip_desktop`. **Packages:** 6 scheduled, 0 excluded alternatives.

**Purpose:** Implement deliberate dual permissions, verified paths and explicit storage modes.

**Exit gate:** Source operations, watcher behavior, cache placement and recovery work without silent duplication or overwrites.

## Entry and execution contract

Use S00 storage semantics and the authenticated native integration bridge. The selected folder needs two independent grants; no guessed mount or conversion from content URI to Linux path.

Guest file service owns normal editing. Persist a space ID and privately verified guest mapping; keep toolchains, native binaries and caches on guest-native storage as needed. Managed-native mode is conditional, never an invisible second authoritative tree.

## End-to-end smoke proof

Create a disposable Git project via the approved space, edit from both sides, rename with Unicode/case edges, run an out-of-tree build and observe watcher behavior. Revoke the grant, remove the mount and exhaust a test volume. Restore from backup and verify file hashes and conflicts rather than accepting a success notification.

**Required evidence:** Bidirectional nonce proof; FS-01 through FS-08 results scoped to enabled modes; authoritative-tree decision; backup contents and restore verification.

**Failure containment:** Stop writes on missing/mismatched mounts or integrity failure. Preserve recovery copies and both conflict versions. A managed-native alternative requires explicit user-visible import/export semantics and retains the dedicated shared exchange space.

## Work packages

Dependencies below are effective for the stated profile, not stage-number order. Every implementation checkbox is initially open. Deliverable paths name outputs to create during implementation; they are not claims that those files exist. A package is complete only with its acceptance result, evidence and independent review.

## S03.01

**Build dual-permission workspace onboarding**

**Status:** Planned; not started.

**Dependencies (default):** [S00.05](S00-platform-feasibility.md#s0005), [S02.04](S02-connection-and-trust.md#s0204)

**Owner:** ANDROID/STORAGE.

**Deliverable:** `workspace setup UI`.

- [ ] Implement SAF tree selection and persisted grants with a suggested, not hardcoded, dedicated directory.
- [ ] Explain the separate Terminal folder grant and provide a visible checklist without attempting private permission delegation.
- [ ] Handle cancellation, revoked access, read-only providers and unsupported roots.
- [ ] Establish the workbench Workspace Trust decision explicitly during onboarding (never disable trust globally). Terminals, tasks and debug remain blocked in Restricted Mode until the user trusts the folder — surface that instead of hanging; evidence: `docs/evidence/2026-10-17-U03-terminal-trust-correction.md`.

**Accept:** A user can complete or cancel setup without granting all-files access or losing existing files.

**Rollback/containment:** Release newly added app grants on explicit removal; never remove Terminal mounts silently.

**Reopen before coding:** [R02] [R03] [R51].

## S03.02

**Implement verified host/guest path mapping**

**Status:** Planned; not started.

**Dependencies (default):** [S03.01](S03-dedicated-workspace.md#s0301), [S02.02](S02-connection-and-trust.md#s0202)

**Owner:** STORAGE/GUEST.

**Deliverable:** `space mapping service`.

- [ ] Implement bidirectional nonce verification with a space ID and bounded candidate discovery from actual mount information.
- [ ] Store mappings privately, detect remount/move/revocation and refuse an empty replacement path.
- [ ] Validate traversal, symlink escapes and malformed project metadata before opening a workspace.

**Accept:** The opened guest root corresponds to the selected host folder; stale mappings produce actionable errors.

**Rollback/containment:** Remove only project-created mapping metadata and keep source untouched.

**Reopen before coding:** [R02] [R51].

## S03.03

**Integrate shared-source file operations and watchers**

**Status:** Planned; not started. Physical-device evidence required.

**Dependencies (default):** [S03.02](S03-dedicated-workspace.md#s0302), [S01.06](S01-workbench-vertical-slice.md#s0106)

**Owner:** STORAGE/WEB.

**Deliverable:** `shared-source mode and watcher tests`.

- [ ] Use the existing backend file service as editor authority; avoid a second Android write path for normal edits.
- [ ] Run Git/save/rename/Unicode/case and cross-side watcher tests, including large generated-directory exclusions.
- [ ] Add bounded source-only polling where justified and expose remaining semantic limitations.

**Accept:** No missed edit, duplicate tree or silent save occurs in the certified shared-source test set.

**Rollback/containment:** Switch affected workspace to explicit read-only/managed mode rather than changing source silently.

**Reopen before coding:** [R21] [R51].

## S03.04

**Place tools, caches and build outputs appropriately**

**Status:** Planned; not started.

**Dependencies (default):** [S03.03](S03-dedicated-workspace.md#s0303)

**Owner:** GUEST/STORAGE.

**Deliverable:** `workspace layout policies and sample profiles`.

- [ ] Keep backend, native extensions, toolchains and selected caches on guest-native storage.
- [ ] Configure out-of-tree builds and language environments; test noexec and symlink limitations of the shared mount.
- [ ] Ensure cleanup removes generated artifacts only and does not delete user source or Git metadata.

**Accept:** Representative profiles build/run without relying on unproven shared-filesystem execution semantics.

**Rollback/containment:** Restore profile configuration; preserve source and user-managed tool installations.

**Reopen before coding:** [R16] [R17] [R51].

## S03.05

**Implement managed-native fallback only when needed**

**Status:** Planned conditional scope decision; implementation only when justified.

**Dependencies (default):** [S03.03](S03-dedicated-workspace.md#s0303), [S03.04](S03-dedicated-workspace.md#s0304)

**Owner:** STORAGE.

**Deliverable:** reviewed managed-native scope decision; if enabled, `managed-native mode and conflict journal`.

- [ ] Activate only for workflows whose required semantics fail in shared-source mode; show which tree is authoritative.
- [ ] Implement explicit import/export or journalled synchronization with hashes, rename/deletion handling and conflict preservation.
- [ ] Test simultaneous edits and interrupted copies without overwrite or automatic conflict guessing.

- [ ] Record a reviewed no-implementation decision if shared-source passes the required semantics; if managed-native is enabled, make export/import explicit and conflict-preserving. This conditional decision cannot waive the dedicated shared-space requirement.

**Accept:** Either shared-source evidence supports an independently reviewed no-implementation decision, or the enabled managed-native mode preserves conflicts and never labels an exported snapshot as a transparent live mount. Record which branch was accepted; neither branch waives the shared-space requirement.

**Rollback/containment:** Pause synchronization and preserve both source variants for manual recovery.

**Reopen before coding:** [R51] [R52].

## S03.06

**Build workspace backup and restore**

**Status:** Planned; not started.

**Dependencies (default):** [S03.04](S03-dedicated-workspace.md#s0304)

**Owner:** STORAGE/QA.

**Deliverable:** `backup inventory and restore test`.

- [ ] Inventory host-shared source, guest-native worktrees, configuration, extensions and unsaved-state policy separately.
- [ ] Export a versioned manifest and user-selected backup without secrets by default.
- [ ] Restore to a clean guest fixture and verify hashes, Git state and profile recreation; test managed mode when enabled.

**Accept:** A documented restore succeeds; a VM-reset claim is limited to categories actually backed up.

**Rollback/containment:** Leave originals intact and quarantine incomplete restored trees.

**Reopen before coding:** [R01] [R02] [R51] [R59].

## Source refresh

Start with the [dated platform review](../research/2026-10-16-platform-review.md), then reopen the task-specific references before implementation and on relevant version changes. Inherited links are research starting points, not a claim that all 98 sources were revalidated in this review. Mutable documentation is not a dependency lock.

[R01]: https://developer.android.com/blog/posts/googlebook-linux-terminal-preview-vm
[R02]: https://support.google.com/googlebook/answer/18119222?hl=en
[R03]: https://support.google.com/googlebook/answer/18668704?hl=en
[R16]: https://code.visualstudio.com/api/advanced-topics/extension-host
[R17]: https://code.visualstudio.com/api/advanced-topics/remote-extensions
[R21]: https://code.visualstudio.com/api/references/vscode-api
[R51]: https://developer.android.com/training/data-storage/shared/documents-files
[R52]: https://developer.android.com/guide/topics/providers/create-document-provider
[R59]: https://developer.android.com/privacy-and-security/keystore
