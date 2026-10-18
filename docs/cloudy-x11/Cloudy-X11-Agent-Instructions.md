# Cloudy-X11 implementation and continuation prompt

Use this document as the instruction for the agent working in the existing project repository. It is written to support continued implementation, progress recovery, and verifiable decisions. The starting snapshot is the supplied `GOAL.md`, `TODO.md`, and `NEXT-STEPS.md`, dated 2026-10-17. Their build and device results are reported project evidence; inspect the actual repository and retained evidence before relying on them. Public references were checked on 2026-10-18 UTC.

## 1 Continue the existing Cloudy-X11 project

Continue implementing **Cloudy-X11 as a standalone Googlebook OS application that supplies an X11 display service and native application windows for the built-in Debian Linux VM**.

The intended experience is simple: after the user starts the VM and pairs/enables Cloudy-X11, running an ordinary X11 application from a supported VM launch path makes its application windows appear as resizable Googlebook desktop windows. Terminal and Cloudy Code are ways to launch Linux processes. Cloudy-X11 owns their X11 presentation.

Use MobaXterm's automatic display forwarding and multiwindow X server behavior as the reference. Implement the behavior using reviewed open-source components. Cloudy-X11 does not need MobaXterm's terminal emulator, SSH session manager, file browser, or Windows implementation. [S1]

### Product boundaries

- Keep the standalone APK/package `dev.cloudy.x11` and the separate `android/cloudy-x11` project unless actual repository state establishes a documented successor.
- Cloudy Code/GBCode and its Code-OSS workbench are consumers of the VM display configuration. Cloudy-X11 must build, install, and run without them.
- Cloudy Code's workbench remains its existing Android frontend with a Linux backend. Launching an X11 program from its terminal or extension host must not replace the workbench with a Linux GUI.
- Target Terminal shells, scripts, Cloudy Code integrated terminals, tasks, and extension-host children. Include configured systemd user services in the launch-environment contract.
- Keep the existing `DISPLAY=:99` VM endpoint as the starting design. The authenticated app-initiated tunnel carries X11 connections to the Android X server.
- Present independent application windows as Android tasks. Menus, child windows, and dialogs need the role policy in section 7.
- Keep the embedded native engine as the target. The xpra/HTML5 prototype is useful historical evidence and a temporary diagnostic carrier; it is not completion of the native path.
- The later remote-console app is outside this implementation scope. Produce a reusable engine/transport/window-host boundary and an integration example for that later project.

The target includes X11-capable applications. Wayland-only applications, a complete Linux desktop, universal guest GPU acceleration, USB forwarding, and a new remote-console product are separate capabilities.

### Meaning of anywhere in the VM

For this release, provide the default X11 environment for the **paired VM user and the supported launch paths**. Newly launched children inherit it. A running process's environment cannot be repaired by editing a profile file; stale parent processes need a targeted restart or an explicit supported update. Root, other users, containers, `env -i`, and launchers that deliberately replace their environment require separate integration. Do not silently distribute one user's cookie to these contexts. [S13]

Applications should launch with their normal commands. Per-application aliases, `cloudy-run` wrappers, and selecting an application from Cloudy-X11 are not the default forwarding mechanism. A single integration point in the Cloudy Code backend launcher is appropriate because it initializes all of that backend's children.

## 2 Recover progress before changing code

Read all applicable repository instructions, including ancestor `AGENTS.md` files. Then locate and read:

1. `GOAL.md`, `TODO.md`, and `NEXT-STEPS.md`.
2. `android/cloudy-x11/AGENTS.md`.
3. `docs/plan/x11-forwarding-evaluation.md`.
4. `docs/decisions/ADR-001-embedded-x11.md`.
5. `docs/plan/native-window-surface.md` and relevant platform, lifecycle, security, and release documents referenced by these files.
6. The actual engine lock file, pinned headers, build configuration, VM setup payload, and existing tunnel tests.

Search with `rg` or `rg --files` if paths have moved. Record missing documents; do not invent their contents. Preserve repository requirements and the user's current product goal when reconciling stale plans.

Inspect the actual checkout before selecting work:

```sh
git status --short --branch
git log --oneline -15
git diff --stat
git diff --cached --stat
git submodule status --recursive
```

Inspect relevant diffs after this overview. The recorded head `8a13464` is historical context, not a requested checkout target. Keep `df86bbe` in history and correct forward. Preserve other agents' and the user's changes.

Create or update a concise progress table with the columns: requirement, current implementation, evidence, verification environment, remaining work. Classify each claim as documented platform behavior, reported historical evidence, code/build verified, physical-device verified, or unresolved. A green checkbox requires evidence for the particular behavior it claims.

### Reconcile the supplied snapshot

| Item in the supplied documents | Required interpretation or correction |
|---|---|
| Standalone APK builds and xpra X Eyes renders | Reported evidence for extraction, pairing, and the prototype path. It does not prove the embedded engine or native per-X-window hosting. |
| `xdpyinfo` reaches Xvfb through the VM proxy and harness | Evidence for that VM/harness route. Record the real upstream server in every test. |
| `lorie-smoke` starts under the app UID | Evidence of native bootstrap in that environment. Rendering, accepted-fd authentication, and window management remain separate gates. |
| GOAL still describes GBCode X11 activities or an old token | Compare with the completed extraction/pairing records and actual code; move obsolete descriptions into history. |
| C2.5 assumes native access to forwarded 14500 | TODO still leaves native socket/WebView reachability unproved. Run G1 below. |
| `EADDRNOTAVAIL` described as a prohibition on 145xx ports | Retain the exact observed failure. Check the requested address, family, interface, namespace, and errno before stating an OS policy. Reusing 14500 remains the starting implementation decision. [S14] |
| One client, one X top-level, and one window family used interchangeably | Apply the window-role contract below. Stream IDs, processes, and windows are different identities. |
| Cookie rotation said to invalidate existing sessions | Define and implement whether old connections are explicitly disconnected. File replacement alone is insufficient. |
| xpra proxy scheduled for deletion | Preserve or extract the new 14500 native bridge before removing its old carrier. |

## 3 Prove the platform assumptions early

Add G1–G3 to the existing tracker as prerequisites. Read-only audits and independent probes may proceed in parallel; coordinate access to the same physical device and services.

### G1 Native APK to VM transport

Google documents the Debian VM, port controls, and automatic localhost forwarding to the host browser. That documentation does not independently establish the proposed native APK/raw-upgrade route. [S2, S3]

Run a small probe **from code in the installed Cloudy-X11 APK under its real UID and target SDK**. Use the existing forwarded control endpoint, currently expected to be port 14500. Record the actual address and address family. Verify:

1. Native connection and authenticated request.
2. HTTP/1.1 upgrade, if that transport remains the candidate.
3. Simultaneous binary traffic in both directions, including zero bytes, short reads, coalesced messages, and messages spanning several writes.
4. Rejection of wrong credentials and malformed upgrade requests.
5. Defined behavior on startup delay, peer loss, and reconnect.

G1 may implement a minimal diagnostic upgrade/binary-echo handler on the existing 14500 bridge. Use short-lived probe credentials, no real display cookie, and no X server. This is the prerequisite experiment; production C2.5 evolves or replaces it after route feasibility is established. Completing production pairing/encryption is not required to run this narrowly scoped probe.

Record OS/build, Terminal version, APK build/UID, endpoint, permissions, and sanitized results. Browser success, WebView success, an adb-shell connection, and a VM-local harness each establish different facts; label them separately.

Use this probe to decide the transport. If native binary WebSocket transport works while a custom raw HTTP Upgrade does not, it is a valid candidate: it can carry X11 bytes into the native engine without an HTML renderer. Update both endpoints and the protocol decision together. If no supported native app-to-VM route works, record the exact blocker and continue independent work. Do not quietly substitute a viewer or undocumented VM-control interface.

### G2 Android task and window policy

Use a minimal native host with synthetic window events to test platform behavior before completing the renderer.

Pure Android task/lifecycle probes can run independently. Once G1 passes, Terminal and Cloudy Code send synthetic window-event messages through that probe transport. They do not need to launch real X clients at this stage. Repeat with real X window events during native end-to-end acceptance.

Verify:

- Create two separate, resizable tasks with distinct identities. Verify dynamic title/icon behavior in captions, taskbar, Recents, and Alt+Tab separately.
- Trigger a new task while Cloudy-X11 is visible, obscured, minimized, and has no visible task but its session service is running. Wait beyond recent-interaction allowances. Trigger from both Terminal and Cloudy Code.
- Record behavior after process death and explicit force-stop as separate states. Do not promise automatic cold activation without a supported activation route.
- Observe system caption Close, Back, task removal, minimize, Activity recreation, Surface replacement, and display changes. Determine which notifications are actually available; do not infer a cancellable close hook from `onDestroy()`.
- Distinguish user-driven host resize from X-client-requested programmatic host resize. Test initial bounds and later geometry independently.

Android restricts background activity launches. A foreground service does not by itself supply unrestricted launch authority. Check documented exceptions on the actual build. A documented user-grantable permission may be evaluated with an explicit setup explanation and the platform permission flow; never assume it is already granted. Do not use accessibility abuse, hidden APIs, transparent keepalive activities, or fabricated roles to bypass the policy. [S4]

A notification action that opens a pending X window can be a clearly labeled fallback. It does not pass the fully automatic goal. Record any required permission, user interaction, or foreground condition in acceptance results.

Android supports multiple tasks and initial launch bounds. Its API 37 `REPOSITION_SELF_WINDOWS` permission for programmatic task movement/resizing is restricted to the default browser and OEM signature apps. An ordinary APK must not assume that permission. Report actual host bounds back to X clients when requested geometry cannot be granted. [S5, S6]

Repeat Close/Save/Cancel with a real X application during C3.3; the synthetic probe establishes host lifecycle behavior only.

### G3 Audit the exact pinned engine

Keep the accepted `magicdesk-x11` pin as the baseline; the supplied documents name `65c3ced0`. Resolve the full commit and recursive dependency pins locally. Inspect the header, implementation, tests, and actual target configuration.

The current public upstream header documents process-exiting server shutdown, descriptor consumption, and callback/thread ownership. Public `main` is a research reference, not proof of the pinned ABI. If the pin has that shutdown behavior, host the X server in a dedicated **app-UID process**, separated from the launcher and Activity hosts. A private process name does not imply `isolatedProcess=true`. [S7, S8]

Write a compact API audit: symbol, pinned declaration, required caller thread, descriptor/Surface ownership, error behavior, and test. Resolve every JNI name against source before implementing it. Verify the engine's window-management bridge and host graphics contract. Building `LorieNative` does not supply the missing Android adapter or presenter. [S7, S9, S10]

Change engines only for a concrete, recorded incompatibility and an updated architectural decision. Keep source and distribution obligations visible in that decision.

## 4 Keep the transport and component responsibilities explicit

The starting design has four responsibilities:

| Component | Responsibility |
|---|---|
| `cloudy-x11-vm` | Per-user display socket/auth setup, launch-environment integration, X-client stream relay, and VM-side connection state. |
| Forwarded control bridge | Authenticated access through the tested host-to-VM route; bridge to the private VM Unix tunnel. |
| `cloudy-x11-core` | Session supervision, transport adapter, native engine integration, authoritative window registry, and host contracts. Keep product setup UI outside it. |
| `cloudy-x11-app` | Standalone setup/status UI, Android services/processes, Activity/Surface hosts, and platform permission interactions. |

Keep engine integration usable without Cloudy Code classes or Workbench code. Start with narrow internal interfaces; split Gradle modules when the working boundary is clear. A large module rewrite is not a prerequisite for the first visible application.

The app initiates the control connection. X11 traffic then travels in both directions over it. The VM-local display socket and private Unix tunnel are different endpoints; the Android app cannot open the VM's Unix pathname directly.

## 5 Complete launch-environment integration

Resume C2.4 without treating it as a prerequisite for C2.5. They are independent until end-to-end acceptance.

Create one authoritative display configuration for the paired VM user. Integrate it into the actual Terminal shell startup path, the Cloudy Code backend launcher, and supported systemd user units. A systemd environment file affects services started through that manager; arbitrary launchers obtain their environment elsewhere. [S13]

Required behavior:

- Future supported launches resolve `DISPLAY=:99` and the intended absolute Xauthority path.
- The backend, extension host, tasks, and terminals receive the environment before they launch children. A successful integrated-terminal `.bashrc` check cannot stand in for the extension-host check.
- Existing stale parents are diagnosed. Restart only the necessary processes with attention to running jobs; do not restart every VM service indiscriminately.
- Setup is idempotent. Use managed configuration blocks/drop-ins and preserve unrelated user customizations.
- Detect display/socket collisions. Do not unlink an active foreign X socket or take over another user's display.
- Define an opt-out and preserve explicit alternate display selections, including future SSH-forwarded displays. Scope default replacement to the intended local VM launch paths.
- Do not alter Google's Terminal application or capture its graphics screen. Selecting Cloudy-X11's display endpoint is sufficient to bypass that screen for these clients.

Check each source separately:

1. A fresh Terminal shell and a child script.
2. The Cloudy Code backend's relevant environment keys.
3. A fresh integrated terminal.
4. A task child.
5. A harmless GUI child launched by an extension-host test fixture.
6. A configured systemd user service.
7. A deliberately stale parent created before setup, followed by the documented repair.

Inspect only `DISPLAY`, `XAUTHORITY`, and other directly relevant keys; do not dump complete environments into evidence. Run `xdpyinfo` against the configured endpoint. Before the native path exists, explicitly identify a harness/Xvfb upstream; repeat with the real Android engine before claiming end-to-end success.

## 6 Specify and implement the tunnel contract

Resume C2.5 after G1 establishes a supported route. Retain the existing 14500 control bridge and private Unix tunnel as the starting topology. Do not create a raw X11 TCP listener. The authenticated control connection may use TCP; the prohibition concerns exposing the X server's protocol endpoint directly.

### Handshake ownership and readiness

The supplied plan describes conflicting owners for `CLOUDY-X11 <token>`. Resolve this in a single versioned protocol document before changing both implementations.

For the planned raw-upgrade design, use this ownership unless actual code requires a documented migration:

1. The native app sends one defined authentication mechanism and an HTTP/1.1 upgrade request to the control bridge.
2. The bridge authenticates that request, connects to the private VM Unix tunnel, and performs the Unix-side handshake. The app does not duplicate that private handshake.
3. The bridge verifies upstream success before sending HTTP `101`, with the correct `Connection` and `Upgrade` fields. Upstream failure must still yield a meaningful HTTP error.
4. After the header terminator, the bridge sends exactly one bounded, versioned session-configuration message. It identifies the display, auth method, session generation, and negotiated limits. Define how the display credential is delivered through the verified protected channel or an explicit secure provisioning mechanism, including its encoding and bounds. Do not accidentally retain a second legacy `OK <cookie>` message.
5. The app installs the same display credential used by VM clients, starts the engine, and waits for its real readiness callback. Missing, malformed, or inconsistent authorization prevents readiness.
6. The app sends the defined READY message. Only then does the VM admit/forward client streams.

Specify what happens to X connections arriving before readiness: prompt failure or a bounded wait with timeout. Never buffer unlimited data while the renderer or engine starts.

Preserve bytes already read beyond the HTTP header terminator. Have one read owner and one serialized frame writer after the upgrade. Bound headers/control lines and validate the exact protocol version. HTTP Upgrade handling must follow the tested connection semantics. [S15]

### Framing and resource ownership

The existing O/D/C framing is a starting point, not a complete transport contract. Preserve compatible fields where useful and revise both ends and the harness atomically when the version changes.

Define and test:

- The VM allocates a stream ID for each accepted X11 connection; the ID is not an XID, PID, or Android task ID.
- ID uniqueness, reuse/wrap rules, and a new session generation after disconnect/reset.
- Open admission or rejection and data ordering in both directions.
- Maximum frame payload, active streams, queued bytes per stream, and total queued bytes. Retain the existing 1 MiB frame ceiling unless evidence justifies a change; choose explicit numeric queue/connection limits in the implementation contract.
- Fair scheduling and backpressure. A stalled client must not cause unlimited buffering or permanently block all other windows. Add per-stream credits or a documented bounded slow-stream failure policy if the existing framing cannot express backpressure adequately.
- Incremental parsing across arbitrary read boundaries. A short read is normal; truncated EOF, exceeded limits, or invalid state are errors.
- Partial writes, simultaneous close, data after close, duplicate opens, unknown IDs/types, cancellation, and timeout behavior.
- Whether half-close is supported; implement that decision consistently.
- Which side owns each socketpair descriptor before and after JNI admission, and how every failure path releases resources.

Use one frame writer so concurrent stream pumps cannot interleave frame bytes. Never forward guest descriptor numbers as if they were Android file descriptors.

### Authentication and pairing

Keep tunnel pairing credentials separate from X11 display cookies. Use private state directories, restrictive file permissions, atomic writes, and sanitized diagnostics. Select the correct `MIT-MAGIC-COOKIE-1` record for the VM client's connection family/address/display. Write a valid binary Xauthority file, not a file containing only a hexadecimal string. Verify the engine's actual `-auth` loading and accepted-fd path. [S16, S17]

Prove that missing and wrong X11 authorization fail through the native admitted socket, even though the engine sees a local socketpair. Do not use `-ac`, permissive `xhost`, or accidental local-user exemptions to make the test pass.

An HTTP bearer cookie is not encryption, mutual authentication, or replay resistance. Document the local channel's actual trust boundary and bridge identity checks. Verified platform protections may establish the required peer identity and confidentiality; do not infer these from a localhost address. Where the tested route lacks them, use a maintained authenticated secure channel before shipping reusable credentials or the display cookie. Do not invent a cryptographic protocol. G1 uses only short-lived diagnostic credentials and does not depend on this production gate. If only that diagnostic path is implemented, mark the production security gate incomplete.

Define pairing rotation, display-cookie replacement, and revocation separately. Updating authorization files does not automatically close already authenticated X clients. If the product deliberately revokes current sessions, implement explicit teardown, communicate that it closes active GUI connections, and test it. [S17]

### The VM boundary changes extension compatibility

The VM client connects to a local Unix socket, but the X server is across a kernel boundary. MIT-SHM relies on shared memory; DRI3 includes descriptor transport. An O/D/C byte relay cannot carry those resources transparently. [S11, S12]

For the initial software-rendered path, disable or withhold incompatible local-only capabilities in the engine configuration/build, or use a proven per-client restriction that covers this admission path. Do not advertise unsupported capabilities and depend on individual applications discovering failures later. Check SHM variants, DRI3, and any FD-based rendering path.

Record `xdpyinfo` extension output from the actual server and exercise representative toolkit clients. Test that unsupported requests fail cleanly without hanging or desynchronizing the connection. Shared buffers between two Android processes are a separate, potentially valid renderer mechanism; do not confuse them with VM-to-Android memory sharing. Host GPU presentation also does not imply guest GPU acceleration.

## 7 Implement the engine and native window hosts

### Engine ownership

Resume C3.1 using the audited pinned API. A session supervisor owns the tunnel and engine lifecycle. Activity hosts own presentation bindings, not the X server's lifetime.

Where the audited engine requires it, use a dedicated private app-UID server process and transfer the renderer connection through supported Android IPC. Keep engine-control and renderer calls on their required threads. Copy borrowed metadata before handing it to another thread. Honor accepted-fd consumption on failure and Surface-release acknowledgments. Build engine and renderer from compatible pinned sources. [S8, S9]

Verify start, readiness, one authorized X connection, rejected authorization, shutdown, and restart. A server crash must be reported by the supervisor without being mistaken for an intentional window close.

### First working display

Resume C3.2 with the simplest supported presenter. Implement the actual graphics-host contract, including buffer format/stride, synchronization, composition, and Surface lifetime. A software Android presenter is a suitable initial target; optimize after measuring the working path. [S10]

First prove known pixels in a real Surface. Then show `xeyes` from a fresh Terminal shell through the entire native path. Add `xterm` to verify input and observable client layout changes. Record engine window events, task identities, Surface content size, and a device screenshot or short recording.

The acceptance path is VM X client → `:99` relay → private Unix tunnel/control bridge → native app transport → embedded X server → window host → Android Surface. The harness and xpra viewer must not be the active display server or renderer for this result.

### Window roles

Use engine window discovery and ICCCM/EWMH semantics, not the command name or tunnel stream count, to create hosts. [S18, S19]

| X object | Presentation policy |
|---|---|
| Independently managed normal top-level | One independent Android task/window. |
| Second normal window from the same application or client | Another independent task; do not merge by PID, class, or connection. |
| Transient dialog or modal window | Associate with its owner. Choose a supported owned host or family composition policy; preserve modality, stacking, input, and close behavior. |
| Managed utility, toolbar, torn-off menu, or standalone dialog | Provide a usable owned host when an owner resolves, or an independent host under the documented role policy. Do not discard a window because it lacks a parent. |
| Short-lived dropdown/popup menus, tooltips, combo popups, drag icons, override-redirect surfaces | Dependent presentation without an ordinary taskbar/Alt+Tab entry. Preserve positioning and input grabs; distinguish these from managed torn-off menus. |
| Splash window | Temporary startup presentation with a defined handoff to the main window. |
| Root, desktop, dock, input-only window, internal child | No ordinary application task. |

Track server/session lifetime, XID, and window incarnation so a reused XID or late callback cannot target a new window accidentally. Keep output IDs, tasks, display IDs, and Surface generations distinct. Restoration must rebind a live window and suppress duplicate tasks.

Set explicit bounds for window count, task-creation rate, dimensions, and metadata. Handle exceeded limits without exhausting Android task or memory resources.

Explicitly identify the component that owns X11 window management. Composite discovery alone does not implement focus and configuration policy. Audit the pinned engine's application WM bridge and its selection ownership; current upstream documents a host-WM mode. Test supported EWMH state, focus, transients, and a conflicting WM. Do not introduce a complete guest desktop to conceal missing host behavior. [S9, S19]

### Resize and display geometry

The required path is: Android content-size change → corresponding output/window geometry → X client notification and relayout → new content presented at the actual host size.

Measure content bounds excluding system caption/insets. Preserve a consistent coordinate transform for pointer input, popups, and damage. Handle resize increments, aspect constraints, fixed-size clients, maximize/restore, and density changes. A fixed-size client may need padding or aspect-preserving fitting within its independent host. Do not silently clip controls or popup content; cropping requires a usable pan/scroll policy. Scaling an entire fixed desktop is not the target. [S18]

Keep shared X root geometry separate from each application's geometry. Resizing one task must not resize unrelated clients. Use supported initial placement; accept, clamp, or reject later X geometry requests according to actual OS authority, and report the resulting geometry. Never treat `setLaunchBounds()` as a universal live-resize API. [S5, S6]

Audit DPI scope at the pin. The current public API exposes screen DPI, so independently mutable toolkit DPI for each task on one shared X screen must not be assumed. Define one initial screen-DPI policy and measure mixed-display behavior. [S8]

### Close and lifecycle semantics

Graceful application close targets the selected managed window through `WM_DELETE_WINDOW` and waits for the resulting X state. Test an editor with unsaved data and a Save/Discard/Cancel dialog. Do not destroy the presentation solely because the close request was sent. [S18]

Use G2 results to implement system caption Close honestly. If the system removes a task before the application can cancel, preserve the client and provide a recoverable presentation path using supported APIs; record the gap in native close equivalence. `onDestroy()` must not unconditionally terminate the X client: it also occurs for lifecycle changes. [S20]

Force close is an explicit operation. Disconnecting an owning X client can remove its other windows too; protect unrelated clients. Track this scope in the UI and tests. [S9]

Audit the pinned engine's fallback when `WM_DELETE_WINDOW` is unsupported. Current upstream describes a client-disconnection fallback even with `force=false`. Detect or otherwise account for that case, define its effect on other windows on the connection, and do not label every non-force API call a cancellable graceful close. [S9]

## 8 Complete interaction and recovery

Resume C3.3 in small, testable changes:

- Pointer motion, hover, buttons, wheel/trackpad scrolling, cursor shape, and accurate coordinates.
- Keyboard layouts, modifiers, shortcuts, dead keys, and focus transfer. Respect OS-reserved shortcuts.
- Android IME composition, commit/cancel, CJK text, and Unicode input. A text-injection function alone does not prove full IME support; verify preedit ownership and exactly-once committed input.
- Modal focus, nested dialogs, popup placement near every edge, and input grabs.
- Bounded UTF-8 clipboard transfer, selection ownership changes, focus/permission constraints, loop suppression, and an opt-out.

Treat rich clipboard content and file drag-and-drop as their own milestone. Guest paths and Android content URIs require a real transfer/permission bridge. A `file://` string from the VM is not automatically an Android-readable file. Do not claim file integration from successful plain-text copying.

### Separate failure cases

| Event | Required behavior |
|---|---|
| Activity/Surface recreation | Rebind the existing engine window when its session remains alive. |
| Renderer connection replacement | Rebuild outputs only to the extent supported by the audited engine; do not automatically restart VM clients. |
| Raw X-stream tunnel loss or X server exit | Close/reset affected streams and stale bindings, create a new session generation, and restore availability for new clients. Do not claim restoration of old X connection state. |
| Last Cloudy-X11 host closes | Apply the explicit session/service policy; do not confuse this with VM shutdown. |
| VM stops or resets | Show a precise unavailable/reset state and guide re-pairing as needed. Preserve unrelated projects/settings. |
| App update, force-stop, suspend, or system process death | Report which layer survives and which must be restarted; verify each separately. |

A raw reliable byte-stream contract does not provide general X application session resumption after the stream is destroyed. [S16]

Choose a legitimate foreground/background service strategy for the target SDK and declare its actual purpose and permissions. A foreground service does not control Terminal's VM lifetime. Google's current preview places VM persistence independent of the Terminal window on its roadmap; keep the actual Terminal/VM prerequisites visible until the tested platform supports more. [S2, S21]

## 9 Finish the native path and retain a reusable core

### C3.4 End-to-end proof

Remove the harness from the active path and verify all supported launch sources against the real APK and engine. Run concurrent applications and the lifecycle cases below. A launcher-side app picker is not evidence for ordinary Terminal/Cloudy Code launches.

Use toolkit programs in X11 mode. Backend-selection switches may be documented test fixtures when a program has both X11 and Wayland modes; do not disguise server compatibility defects with per-app production wrappers.

### C3.5 Retire the prototype carefully

Before deleting xpra services, extract or retain the authenticated native 14500 bridge, its service ownership, credential migration, and required dependencies. Then remove Cloudy-X11's obsolete xpra dependency, HTML5 assets, managed session-launch machinery, and managed per-app aliases from the default setup when the native acceptance gate passes. Uninstall xpra packages only if they were installed solely for this prototype and are not required by another user workflow.

Verify a clean target-only install and an upgrade from the current prototype. Preserve user data and unrelated shell configuration. The clean install must reach the embedded X server without xpra or the harness.

### C4 Reuse contract for the later console

Publish a short integration contract and a small second host or adapter fixture. Cover session start/stop/state, accepted X streams, window events, Surface bind/unbind, resize/focus/input/text, clipboard, graceful/forced close, cancellation, and error reporting. Define ownership and thread rules.

Make the transport injectable. The current adapter uses the local VM bridge; a later console may supply SSH X11 channels with its own authentication. Do not implement remote-console UI or couple the core to Cloudy Code now.

Reserve session and trust-domain identity. A shared `:99` is one traditional X11 trust domain: separate Android tasks do not isolate its clients from one another. A later remote connection must not automatically receive the local VM's cookie or join that display. Separate sessions/engine processes may be needed for different remote trust domains. [S16]

### C5 Release

Complete required security, compatibility, performance, and licensing gates. Preserve the accepted GPLv3 engine decision, dependency notices, SBOM, recursively pinned source, host-adapter source, and build recipes required by the chosen distribution. Check the actual license files and release documents. [S7]

Run the existing relevant native sanitizer/fuzz targets for client admission, malformed inputs, and lifecycle handling. Record results and missing coverage. Add focused coverage for concrete gaps; keep this bounded and separate from the first visible-window milestone.

Measure launch-to-visible time, interactive response, resize behavior, CPU use, memory per active window, and idle cost on the actual Googlebook. Set explicit project budgets from measurements and the intended hardware; do not invent results or claim guest GPU support from the Android renderer.

## 10 Acceptance matrix

Record build identifiers, exact commands/actions, expected/observed results, and durable sanitized evidence. The rows below are coverage requirements; implement the smallest meaningful tests for them. Do not endlessly repeat tests that already settle an unchanged contract.

| Area | Required proof |
|---|---|
| Standalone boundary | APK builds/installs/runs without GBCode/Cloudy Code; no workbench dependencies in the X11 path. |
| Native route | Real APK UID completes authenticated bidirectional transport through the tested VM forward. |
| Automatic launch | New windows appear from Terminal and Cloudy Code in the required visibility states, with any permission/interaction condition recorded. |
| VM environment | Fresh shell, script, backend, integrated terminal, task, extension-host child, and supported user service use the intended display; stale-parent recovery works. |
| Native display | `xeyes`, `xterm`, and representative toolkit/browser windows render through the actual engine, without xpra/Xvfb/harness in the active path. |
| Window roles | Two normal windows from one client produce two hosts; menus/tooltips do not create taskbar entries; dialogs and splash transitions behave correctly. |
| Resize | Two windows resize independently with real X geometry changes; fixed-size and increment-constrained clients have defined behavior. |
| Desktop metadata | Caption, taskbar, Recents, and Alt+Tab tested separately; title/icon updates recorded. |
| Close | Normal close, unsaved Save/Discard/Cancel, dialog close, force-close scope, task removal, and Activity recreation distinguished. |
| Input | Pointer, scrolling, physical keyboard, modifiers, focus, dead keys, and IME composition/commit/cancel verified. |
| Clipboard | Bounded text, selection changes, loop prevention, and platform access behavior; rich/file transfer separately labeled. |
| Extensions | Actual advertised SHM/DRI3/local-only behavior matches the transport and fails unsupported paths cleanly. |
| Authentication | Wrong tunnel token and missing/wrong X cookie fail on the real path; no permissive bypass; rotation/revocation policy demonstrated. |
| Multiplexing | Fragmentation, coalescing, malformed/oversize frames, duplicate/unknown IDs, slow peers, fairness, queue limits, and cleanup tested. |
| Lifetimes | Surface replacement, engine crash, transport loss, VM stop/reset, app update/force-stop, suspend, and display changes have recorded outcomes. |
| Fresh setup and upgrade | Idempotent target-only setup; authenticated bridge retained; prototype retirement preserves relevant user state. |
| Reuse and release | Second host/adapter fixture uses the core contract; performance evidence and source/license requirements complete. |

For a device-required row, host tests or emulator results may support development but cannot mark that row physically verified. When hardware is unavailable, complete useful source/build/protocol work and mark the remaining device gate explicitly.

## 11 Git and evidence discipline

Work in small commits that each implement one bounded behavior or resolve one contract. Use the repository's branch/worktree conventions and preserve existing work. Stage explicit paths, inspect the staged diff, and exclude credentials, signing keys, cookies, unrelated edits, and unnecessary build output.

Update `TODO.md` and `NEXT-STEPS.md` when a task or verified result changes. Update `GOAL.md` when product decisions or the current state change. Keep related implementation and tracker changes together. Correct obsolete text instead of appending mutually contradictory status paragraphs.

Keep `df86bbe` in history. Do not reset to historical heads, rewrite shared history, delete uncommitted work, or force-push to make the tracker appear consistent. Use normal forward commits. Remote push, publication, signing distribution, and destructive deployment must follow the actual repository and user authorization.

Keep durable, sanitized evidence in the repository's designated evidence location, such as `docs/evidence/`, following its size/privacy rules. `/tmp` screenshots alone are not durable acceptance records. For each evidence item record source commit or exact working-tree identity, device/build, test command/action, outcome, and the narrow claim established. Avoid circular self-hashes; the containing commit and recorded tested source revision together identify the result.

After compaction or handoff, re-read the goal, tracker, next steps, applicable instructions, current git state, and unresolved gate. Resume from verified progress. Do not restart the project or repeat a broad redesign without new evidence.

## 12 Immediate execution order and session report

Start with recovery and state reconciliation. Then prioritize G1 native reachability, G2 task policy, and G3 pinned-engine audit. Run C2.4 environment work independently where possible. Complete the production C2.5 bridge and authorization/readiness contract, C3.1 engine integration, and C3.2 first real window. Expand into C3.3 interaction and C3.4 launch-source/lifecycle coverage. Retire xpra only after the native path passes, then finish reuse and release work.

Continue implementing while the next step is authorized and unblocked. A failed platform gate blocks the claims and dependent work that rely on it; it does not require abandoning independent useful tasks. Report the concrete failing assumption and supported alternatives. Preserve the original automatic native-window goal when reporting a degraded mode.

At the end of each work session, report:

1. Actual branch/head and scoped commits created.
2. Concrete behavior implemented and what the user can now do.
3. Validation performed, separated into code/build, harness, emulator, and physical device.
4. Remaining failures or unverified platform conditions.
5. Updated evidence/document paths and the next executable task.

Begin now by reading the repository, auditing actual progress, and executing the earliest unverified prerequisite. Produce implementation and evidence; do not stop after rewriting another plan.

## Primary references

These sources support the platform and protocol corrections above. For engine APIs, inspect the repository's exact pin before using current-main examples. Required internal ADRs and referenced project documents must be read in the implementation repository; their contents were not included with the three-file snapshot.

- **S1** [MobaXterm documentation](https://mobaxterm.mobatek.net/documentation.html) — embedded X server, multiwindow behavior, DISPLAY setup, and SSH X11 forwarding.
- **S2** [Googlebook Linux Terminal Preview](https://developer.android.com/blog/posts/googlebook-linux-terminal-preview-vm) — October 12, 2026 platform capabilities and roadmap, including browser forwarding and VM persistence.
- **S3** [Use the Terminal app on Googlebook](https://support.google.com/googlebook/answer/18119222?hl=en) — Debian VM, port controls, and Terminal graphics screen.
- **S4** [Android activity security](https://developer.android.com/guide/components/activities/secure-bal) — background activity launch restrictions, exceptions, and PendingIntent privilege requirements.
- **S5** [Android multi-window support](https://developer.android.com/develop/ui/views/layout/support-multi-window-mode) — tasks, multi-instance behavior, initial bounds, and resize verification.
- **S6** [REPOSITION_SELF_WINDOWS](https://developer.android.com/reference/android/Manifest.permission#REPOSITION_SELF_WINDOWS) — API 37 privilege restriction for programmatic task movement/resizing.
- **S7** [magicdesk-x11 repository](https://github.com/mekhontsev/magicdesk-x11) — native-engine scope, host responsibility, build inputs, and GPLv3 source requirements.
- **S8** [magicdesk-x11 current public header](https://github.com/mekhontsev/magicdesk-x11/blob/main/lorie/src/main/cpp/lorie/embedded.h) — process, descriptor, callback, output, and DPI contracts. Current main was reviewed; pin `65c3ced0` was not independently retrieved for this review.
- **S9** [magicdesk-x11 embedding documentation](https://github.com/mekhontsev/magicdesk-x11/blob/main/docs/embedding.md) — renderer ownership, host-WM bridge, window families, and close behavior.
- **S10** [MagicDesk graphics host documentation](https://github.com/mekhontsev/magicdesk/blob/main/docs/graphics.md) — graphics interface and software/Android presentation reference.
- **S11** [MIT-SHM specification](https://xorg.freedesktop.org/archive/current/doc/xextproto/shm.html) — shared-memory requirements.
- **S12** [DRI3 protocol source](https://sources.debian.org/src/xorgproto/2018.4-4/dri3proto.txt/) — upstream protocol text distributed by Debian, including descriptor-based resources.
- **S13** [systemd environment.d](https://www.freedesktop.org/software/systemd/man/latest/environment.d.html) — scope of environment propagation through the user service manager.
- **S14** [Linux bind manual](https://man7.org/linux/man-pages/man2/bind.2.html) — meaning of `EADDRNOTAVAIL` and related bind failures.
- **S15** [RFC 9110 section 7.8](https://www.rfc-editor.org/rfc/rfc9110.html#section-7.8) — HTTP Upgrade semantics.
- **S16** [X.Org client/server communication and security](https://www.x.org/guide/communication/) — reliable ordered streams, connection authentication, and traditional shared-display trust.
- **S17** [xauth manual](https://manpages.debian.org/bookworm/xauth/xauth.1.en.html) — authorization records and the distinction between file editing and server communication.
- **S18** [ICCCM](https://xorg.freedesktop.org/archive/current/doc/xorg-docs/icccm/icccm.html) — window groups, transients, size hints, and graceful close.
- **S19** [EWMH application window properties](https://specifications.freedesktop.org/wm/latest/ar01s05.html) — normal windows, dialogs, popups, modality, and state hints.
- **S20** [Android desktop windowing](https://developer.android.com/develop/adaptive-apps/guides/support-desktop-windowing) and [Activity API](https://developer.android.com/reference/android/app/Activity) — system decoration, host integration, and lifecycle callbacks.
- **S21** [Foreground service launch](https://developer.android.com/develop/background-work/services/fgs/launch) and [service types](https://developer.android.com/develop/background-work/services/fgs/service-types) — supported service strategy and declarations.
