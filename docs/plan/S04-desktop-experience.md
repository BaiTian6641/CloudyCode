# S04 — Desktop workbench experience

[Plan index](README.md) · [Architecture specification](../Googlebook_CodeOSS_APK_Engineering_Plan_v2.md)

**Status:** planned; no implementation or device acceptance is claimed. **Default profile:** `wsl_usbip_desktop`. **Packages:** 6 scheduled, 0 excluded alternatives.

**Purpose:** Make the actual workbench usable as a laptop application rather than a mobile browser wrapper.

**Exit gate:** Keyboard/IME, resizing, pointer, clipboard, multiwindow and recovery tests pass on the reference hardware.

## Entry and execution contract

Start with the actual upstream workbench and approved bridge; keep Kotlin/native UI limited to OS integration, enrollment and recovery.

The upstream keybinding service receives ordinary input. Native interception is a small allowlist; OS-reserved combinations get documented alternatives. Per-window workspace identity is separate from the shared hardware service.

## End-to-end smoke proof

Using physical keyboard/trackpad and an IME, edit and debug while resizing, switching between two workspaces and moving between display scales. Exercise Ctrl-C in terminal, composition, AltGr/dead keys, menus, clipboard and a granted file drop. Kill only the renderer, reopen, and identify exactly which saved/dirty state survives.

**Required evidence:** Window/input matrix; WEB-04 through WEB-08; renderer-recovery report; external-URI/authentication traces with secrets removed; reviewed platform differences.

**Failure containment:** No synthetic JavaScript typing, mobile replacement UI, unsafe OAuth callback or promise that unload prevents Android process death. Preserve recoverable buffers and state unsupported shortcuts/unsent-edit risks explicitly.

## Work packages

Dependencies below are effective for the stated profile, not stage-number order. Every implementation checkbox is initially open. Deliverable paths name outputs to create during implementation; they are not claims that those files exist. A package is complete only with its acceptance result, evidence and independent review.

## S04.01

**Implement desktop windows and workspace sessions**

**Status:** Planned; not started. Physical-device evidence required.

**Dependencies (default):** [S01.06](S01-workbench-vertical-slice.md#s0106), [S02.04](S02-connection-and-trust.md#s0204)

**Owner:** ANDROID.

**Deliverable:** `window/session manager`.

- [ ] Handle resize/insets/caption chrome and persist lightweight window/workspace identity.
- [ ] Create multiple workspace activities using supported APIs with one shared broker owner.
- [ ] Test resize, external display, window switching and closing one window while another remains active.
- [ ] Keep the stock workbench as the entire main application surface; confine native setup to necessary platform interactions and no permanent replacement dashboard.

- [ ] Verify Googlebook adaptive-app manifest/window requirements against the selected SDK, including resizability, caption insets, multiple instances, external displays and process recreation; do not hardcode phone orientation or screen size.

**Accept:** No duplicate USB ownership or lost workspace identity occurs across windows.

**Rollback/containment:** Disable multiwindow creation while retaining the single-window baseline.

**Reopen before coding:** [R04] [R53] [R54].

## S04.02

**Validate keyboard routing and IME composition**

**Status:** Planned; not started. Physical-device evidence required.

**Dependencies (default):** [S04.01](S04-desktop-experience.md#s0401)

**Owner:** ANDROID/WEB.

**Deliverable:** `keyboard matrix and input regression suite`.

- [ ] Pass ordinary input to the workbench; define a minimal native-command allowlist with focus-aware routing.
- [ ] Test Ctrl/Alt/Meta, AltGr, dead keys, editor/terminal differences and Chinese/Japanese composition.
- [ ] Document OS-reserved shortcuts and configurable alternatives rather than adding privileged key interception.

- [ ] Cover the official desktop quality cases for keyboard navigation, input combinations, triple-click and IME commit/cancel. Validate final document/terminal bytes rather than interpreting every IME operation as hardware key events.

**Accept:** No dropped/duplicated text or broken composition in the supported matrix; terminal Ctrl-C remains correct.

**Rollback/containment:** Revert the specific native shortcut interception that causes regression.

**Reopen before coding:** [R55] [R72].

## S04.03

**Complete pointer, clipboard and scaling behavior**

**Status:** Planned; not started. Physical-device evidence required.

**Dependencies (default):** [S04.01](S04-desktop-experience.md#s0401)

**Owner:** ANDROID/WEB.

**Deliverable:** `desktop interaction suite`.

- [ ] Test right-click, hover, selection, splitter drag, wheel/trackpad gestures and multiple display scales.
- [ ] Integrate user-triggered clipboard actions with size limits and sensitive-data redaction.
- [ ] Fix focus restoration after native dialogs without forcing editor focus over terminals or accessibility users.

**Accept:** The common desktop interaction checklist passes without duplicate events or clipboard leakage.

**Rollback/containment:** Disable only the faulty native adaptation and retain browser defaults.

**Reopen before coding:** [R54] [R56] [R57].

## S04.04

**Implement file import/export and external drop handling**

**Status:** Planned; not started.

**Dependencies (default):** [S03.02](S03-dedicated-workspace.md#s0302), [S04.01](S04-desktop-experience.md#s0401)

**Owner:** ANDROID/STORAGE.

**Deliverable:** `file integration module`.

- [ ] Handle document selection and external drag URIs with actual grants and an explicit copy/open policy.
- [ ] Validate file sizes, filenames, archive paths and target workspace containment.
- [ ] Test provider revocation, duplicate names, partial imports and multiple dropped files.

**Accept:** No URI is mistaken for a guest path and no existing file is overwritten without policy/confirmation.

**Rollback/containment:** Remove only incomplete import temporaries and release temporary URI permissions.

**Reopen before coding:** [R51] [R56].

## S04.05

**Implement external URLs and authenticated return flows**

**Status:** Planned; not started.

**Dependencies (default):** [S02.04](S02-connection-and-trust.md#s0204), [S04.01](S04-desktop-experience.md#s0401)

**Owner:** WEB/ANDROID.

**Deliverable:** `external navigation and OAuth adapters`.

- [ ] Use system browser/Custom Tabs for external pages and provider-approved authentication.
- [ ] Implement state/PKCE and verified return routing where applicable; audit extension external-URI hooks.
- [ ] Test hostile redirects, callback replay, custom-scheme interception and cancellation.

**Accept:** Authentication returns to the intended session without exposing hardware bridge origin or secrets.

**Rollback/containment:** Revoke test credentials and disable the affected provider integration.

**Reopen before coding:** [R17] [R21] [R60] [R77].

## S04.06

**Recover renderer loss and unsaved workbench state**

**Status:** Planned; not started. Physical-device evidence required.

**Dependencies (default):** [S04.01](S04-desktop-experience.md#s0401), [S03.03](S03-dedicated-workspace.md#s0303)

**Owner:** ANDROID/WEB.

**Deliverable:** `renderer and dirty-buffer recovery tests`.

- [ ] Handle renderer death by recreating the WebView and restoring a verified workspace session.
- [ ] Measure upstream dirty-buffer persistence under renderer/app/VM loss and add a narrow journal only for demonstrated gaps.
- [ ] Test reopen, stale session, unavailable backend and close-with-unsaved-edits without relying on unload prompts.

**Accept:** Recovery behavior is measured for every loss type; saved/unsaved status never misrepresents persistence.

**Rollback/containment:** Disable a faulty recovery patch and preserve backups for explicit user recovery.

**Reopen before coding:** [R42] [R72].

## Source refresh

Start with the [dated platform review](../research/2026-10-16-platform-review.md), then reopen the task-specific references before implementation and on relevant version changes. Inherited links are research starting points, not a claim that all 98 sources were revalidated in this review. Mutable documentation is not a dependency lock.

[R04]: https://developer.android.com/develop/adaptive-apps/guides/googlebook/overview
[R17]: https://code.visualstudio.com/api/advanced-topics/remote-extensions
[R21]: https://code.visualstudio.com/api/references/vscode-api
[R42]: https://developer.android.com/develop/ui/views/layout/webapps/managing-webview
[R51]: https://developer.android.com/training/data-storage/shared/documents-files
[R53]: https://developer.android.com/develop/ui/views/layout/support-multi-window-mode
[R54]: https://developer.android.com/develop/ui/compose/layouts/adaptive/support-desktop-windowing
[R55]: https://developer.android.com/develop/ui/views/touch-and-input/keyboard-input/commands
[R56]: https://developer.android.com/develop/ui/views/touch-and-input/drag-drop
[R57]: https://developer.android.com/develop/ui/views/touch-and-input/copy-paste
[R60]: https://developer.chrome.com/docs/android/custom-tabs
[R72]: https://developer.android.com/reference/android/webkit/WebView
[R77]: https://developer.android.com/training/app-links
