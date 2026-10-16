# S01 — Real-workbench vertical slice

[Plan index](README.md) · [Architecture specification](../Googlebook_CodeOSS_APK_Engineering_Plan_v2.md)

**Status:** planned; no implementation or device acceptance is claimed. **Default profile:** `wsl_usbip_desktop`. **Packages:** 6 scheduled, 0 excluded alternatives.

**Purpose:** Compare the two upstream server candidates using identical fixtures, then choose one.

**Exit gate:** APK edits a shared or explicitly selected guest file, runs a guest terminal, language server and extension webview, with authenticated access.

## Entry and execution contract

Use the proven route/WebView capability record and per-candidate immutable source pins. U03.01 must establish matched upstream controls before the APK integration is approved.

The workbench assets and server form a matched bundle. Compare each candidate to its own upstream revision using the same functional fixture; compare security freshness separately when versions differ. Product commit is not necessarily embedded upstream commit.

## End-to-end smoke proof

Launch each authenticated candidate in the target guest and load it in the probe APK. Edit/save an actual guest file, stage a Git change, run a task and terminal, trigger language completion, use a real debug adapter, open an extension webview and execute a notebook cell. Restart the backend and reconnect without disabling trust checks.

**Required evidence:** Source/asset hashes and licenses; desktop/web controls per candidate; conformance output and guest process locations; patch/security freshness comparison; backend selection decision.

**Failure containment:** A candidate may be rejected with evidence; both candidate evaluations must finish before selecting one. No maintained candidate meeting a mandatory capability means a documented blocker or explicitly justified upstream work, not a Monaco replacement.

## Work packages

Dependencies below are effective for the stated profile, not stage-number order. Every implementation checkbox is initially open. Deliverable paths name outputs to create during implementation; they are not claims that those files exist. A package is complete only with its acceptance result, evidence and independent review.

## S01.01

**Select candidate versions and distribution constraints**

**Status:** Planned; not started.

**Dependencies (default):** [S00.01](S00-platform-feasibility.md#s0001), [S00.02](S00-platform-feasibility.md#s0002)

**Owner:** LEAD/RELEASE.

**Deliverable:** `versions.lock.json and licensing ADR`.

- [ ] Select exact Code - OSS, code-server and OpenVSCode candidate revisions plus compatible toolchains; verify release provenance and architectures.
- [ ] Audit initial registry, branding and extension licenses; exclude proprietary server repackaging and unauthorized Marketplace access.
- [ ] Define a common small project and conformance extension so both candidates receive the same tests.

- [ ] Resolve each candidate to its embedded upstream Code - OSS commit independently of the server product commit. Compare against a clean control at that candidate's exact upstream revision; if revisions differ, record the feature/security delta instead of forcing both to an obsolete common version.
- [ ] Acquire upstream code into ignored upstream/checkouts/ or an external working copy, not into the root of this integration repository. Track immutable source pins, build inputs and a small ordered patch series; verify the selected guest architecture before packaging.

**Accept:** Version locks contain immutable identifiers/checksums and a permitted distribution strategy, not latest placeholders.

**Rollback/containment:** Restore the prior lockfile and do not install unverified bundles.

**Reopen before coding:** [R10] [R11] [R12] [R13] [R14] [R15] [R78].

## S01.02

**Run code-server baseline in Debian**

**Status:** Planned; not started. Physical-device evidence required.

**Dependencies (default):** [S00.03](S00-platform-feasibility.md#s0003), [S01.01](S01-workbench-vertical-slice.md#s0101)

**Owner:** GUEST.

**Deliverable:** `candidate A package and test report`.

- [ ] Install the pinned candidate in user-owned guest storage, enable authentication and bind only to the verified route.
- [ ] Exercise terminal, Git, file save, language-server process and self-contained extension webview using the shared test fixture.
- [ ] Record backend asset/network dependencies, startup behavior and ARM64/x86_64 package availability separately.

**Accept:** Candidate report includes real workbench functionality and authenticated negative tests; browser-only demo is insufficient.

**Rollback/containment:** Stop the candidate and remove only its versioned install directory.

**Reopen before coding:** [R14] [R16] [R19].

## S01.03

**Run OpenVSCode Server baseline in Debian**

**Status:** Planned; not started. Physical-device evidence required.

**Dependencies (default):** [S00.03](S00-platform-feasibility.md#s0003), [S01.01](S01-workbench-vertical-slice.md#s0101)

**Owner:** GUEST.

**Deliverable:** `candidate B package and test report`.

- [ ] Install the pinned candidate with explicit connection-token protection; do not use an unauthenticated sample launch configuration.
- [ ] Run exactly the same project, extension, terminal and reconnect tests as candidate A.
- [ ] Measure additional proxy/origin/branding patches required and document any failure rather than hiding it with relaxed browser settings.

**Accept:** Comparable evidence supports or rejects this candidate; default-no-auth exposure is absent.

**Rollback/containment:** Stop/remove only the candidate installation and revoke its temporary token.

**Reopen before coding:** [R15] [R16] [R19].

## S01.04

**Build the workbench conformance extension**

**Status:** Planned; not started.

**Dependencies (default):** [S01.01](S01-workbench-vertical-slice.md#s0101)

**Owner:** WEB.

**Deliverable:** `extensions/conformance and automated fixture`.

- [ ] Create minimal commands that prove workspace-host placement, launch a harmless guest subprocess and report architecture.
- [ ] Include a webview using constrained CSP/local resources, a small tree view, a task and a debug fixture reference.
- [ ] Assert file paths and process locations from test output; label browser-worker functionality separately.

- [ ] Include a permitted notebook provider with a guest kernel: execute cells, display a renderer output, interrupt/restart, save and reopen. A static notebook view is not sufficient for the full IDE contract.

**Accept:** The fixture detects wrong-host execution and broken webviews without using undocumented extension APIs.

**Rollback/containment:** Uninstall the test extension; no project data or global settings are modified.

**Reopen before coding:** [R16] [R18] [R19] [R21].

## S01.05

**Build the minimal real-workbench APK**

**Status:** Planned; not started. Physical-device evidence required.

**Dependencies (default):** [S00.04](S00-platform-feasibility.md#s0004), [S01.02](S01-workbench-vertical-slice.md#s0102), [S01.03](S01-workbench-vertical-slice.md#s0103), [S01.04](S01-workbench-vertical-slice.md#s0104), [U03.01](U03-workbench-fidelity.md#u0301)

**Owner:** ANDROID/WEB.

**Deliverable:** `android vertical-slice APK`.

- [ ] Host the candidate workbench in a resizable Activity with explicit connection/error states and minimal WebView settings.
- [ ] Use the verified authenticated route; add only the native integration needed for the vertical slice.
- [ ] Edit a file, run a guest terminal command, open the conformance webview and reconnect after a controlled backend restart.

**Accept:** All four interactions pass in the APK on real hardware; screenshots alone do not pass.

**Rollback/containment:** Disable the experimental APK connection and preserve backend/project files.

**Reopen before coding:** [R41] [R42] [R53] [R72].

## S01.06

**Choose the backend and approve the vertical slice**

**Status:** Planned; not started. Physical-device evidence required.

**Dependencies (default):** [S01.02](S01-workbench-vertical-slice.md#s0102), [S01.03](S01-workbench-vertical-slice.md#s0103), [S01.04](S01-workbench-vertical-slice.md#s0104), [S01.05](S01-workbench-vertical-slice.md#s0105)

**Owner:** LEAD/QA.

**Deliverable:** `ADR-backend-selection and gate report`.

- [ ] Compare function, patch burden, origin behavior, licenses and recovery; prefer demonstrated maintainability over unmeasured claims.
- [ ] Choose one default backend and record the alternative as a fallback with explicit switching criteria.
- [ ] Demonstrate editing, terminal, language server and extension webview to an independent reviewer and freeze the integration contract.
- [ ] Apply the original-workbench fidelity contract: prioritize a verified small patch set, not a preselected hosting brand; record all UI differences.

- [ ] Include release/security freshness and maintenance cadence in the decision. A smaller patch set on an obsolete runtime does not win automatically. Record a supported upgrade route and the work required for any rejected mandatory feature.

**Accept:** Level A is measured and the selected backend is pinned; blockers remain visible instead of being waived.

**Rollback/containment:** Keep the last verified candidate and revert only unapproved integration patches.

**Reopen before coding:** [R14] [R15] [R16] [R19].

## Source refresh

Start with the [dated upstream review](../research/2026-10-16-upstream-review.md), then reopen the task-specific references before implementation and on relevant version changes. Inherited links are research starting points, not a claim that all 98 sources were revalidated in this review. Mutable documentation is not a dependency lock.

[R10]: https://github.com/microsoft/vscode
[R11]: https://code.visualstudio.com/docs/supporting/faq
[R12]: https://code.visualstudio.com/docs/remote/vscode-server
[R13]: https://code.visualstudio.com/brand
[R14]: https://coder.com/docs/code-server/FAQ
[R15]: https://github.com/gitpod-io/openvscode-server
[R16]: https://code.visualstudio.com/api/advanced-topics/extension-host
[R18]: https://code.visualstudio.com/api/extension-guides/web-extensions
[R19]: https://code.visualstudio.com/api/extension-guides/webview
[R21]: https://code.visualstudio.com/api/references/vscode-api
[R41]: https://developer.android.com/develop/ui/views/layout/webapps/webview
[R42]: https://developer.android.com/develop/ui/views/layout/webapps/managing-webview
[R53]: https://developer.android.com/develop/ui/views/layout/support-multi-window-mode
[R72]: https://developer.android.com/reference/android/webkit/WebView
[R78]: https://github.com/microsoft/vscode/blob/main/LICENSE.txt
