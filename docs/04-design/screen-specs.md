# Screen-by-screen design specifications

Screen IDs are stable references for design review and implementation tickets. The [specimen](preview.html) illustrates the initial S02 and S06 subset plus selected states; it has not been expanded to implement the revised X11/SFTP/RDP/VNC/USB flows. S13–S17 and revisions below are specifications for native Googlebook desktop UI under [Rule 0](../00-project/platform-rule-zero.md), not completed mockups. The [2026-10-16 workspace home revision](2026-10-16-workspace-home-revision.md) refines S01, S06 and S15 into the native Termius-style host home and records the MobaXterm input-default amendment to the [terminal interaction contract](desktop-terminal-interaction-contract.md).

## S01 — Console hosts home and first connection

**Goal:** find/create the right environment and establish trust. This is the Console default window content (the Termius-style personal workspace home), replacing the development launcher; the diagnostic tools move under an Advanced section unchanged.

Navigation lists Favorites, Recents, All hosts, and groups derived from tags. The main table shows one row per host: label, protocol (SSH in Console), host:port, tags, favorite marker, and last-used time (`Never used` when no connect was recorded). Sorting follows the section: recents order by actual last-used, not by edit time. A compact detail pane for the selected host shows endpoint, protocol, key fingerprint state when enrolled, the matching credential reference from this app's vault (or `No saved credential`), and persistence choice. One primary action per selection: Connect. Add host is available with or without a selection.

Connect routes only to a qualified operation. Before the interactive terminal lands (P2-S2/P2-S3), SSH Connect shows the honest `Terminal is not integrated in this build` state beside the affected host and offers the real available follow-ups (manage trust, edit profile, browse files). No fabricated session, spinner or fake transcript is shown. First trust uses a focused dialog with host/address/port, algorithm/fingerprint, comparison guidance, and explicit save/cancel. A changed key displays old/new fingerprints and stops connection; do not bury it in a transient toast. Empty state provides Add host and optional non-secret SSH-config import. Import preview lists unsupported directives and never executes config commands.

The window header keeps the native window identity (name, New window, Close) and system caption controls. Advanced exposes the existing native tools: connection profiles, SSH files, files locations, host keys, authentication, saved credentials (vault keychain), and display settings. Rows remain operable at narrow tiled widths and 100–200% text scale; long host values wrap in details while Copy path/Copy host preserve exact values.

## S02 — Console active workspace

**Goal:** work in several terminal contexts with little chrome. Left navigation uses workspace/host names. Header shows project and host/environment. Tabs represent named contexts; a two-pane terminal layout includes active-pane border/focus treatment and subtle host/session labels. A supporting inspector lists session persistence, forwards, and attachments. Status strip carries connection/path/counter provenance.

Primary action is contextual: new session or open a known workspace, not a large app-wide “Run.” Terminal uses its own dark palette and mono grid. A copied selection has immediate feedback without changing terminal content. Search opens locally with result navigation and closes back to the same pane.

**States:** connecting; authenticating; connected; detached; reconnecting; remote session ended; output backpressure limit; missing credential. Each terminal pane can have a different transport state without an all-or-nothing workspace error.

## S03 — Local composer and attachment staging

**Goal:** prepare a prompt reliably. Composer opens beneath or beside the terminal without replacing it. Show text area, attachment queue, destination host/project, insert/send policy, and explicit action. Attachment rows show name, size, upload state, and final remote reference.

If upload fails, keep the local attachment and text buffer. If the selected terminal changes host, require an explicit destination update before sending. Binary content does not enter a shell paste. Large text can be uploaded as a file with a clear reference.

## S04 — Port-forward manager

Columns: label, local bind/port, remote destination, owner session, state, action. Loopback is the default. Changing bind scope is an advanced deliberate setting. Show collision/authentication/network errors on the affected row. Closing a tunnel never implicitly closes the shell; closing its owner explains the tunnel lifetime. Open preview launches the external browser.

## S05 — Console reconnect / session recovery

Keep prior output readable and the target visible. Show route/host/authentication progress with a cancel action. Mark a local buffer unsent. Once trust and identity are checked, attach to the recorded durable session. If missing, offer a new session as an explicit action; do not reconstruct it by replaying commands. A reboot creates a distinct explanation from ordinary network loss.

## S06 — Desktop hosts home and workspace overview

**Goal:** open the right visual context and inspect supporting resources. This is the Desktop default window content, sharing the Termius-style home anatomy with S01: Favorites/Recents/All hosts navigation, one row per host with protocol (RDP, VNC, or Stream), host:port, tags, favorite marker and last-used time, and a compact detail pane with endpoint, credential reference state from this app's vault, and protocol profile summary.

The primary action is Connect for the selected protocol. Connect routes only to the qualified operation: a VNC profile opens the native VNC session path; RDP and Stream profiles show their honest not-yet-integrated state until P3-S7/P5-S2 land — no fabricated session, metrics or thumbnails. In R2 the main region hosts the native RDP/VNC surface or an honest external high-performance-stream launch state, according to the selected protocol. The specimen uses a labeled illustrative desktop scene. Supporting panes show selected files and device ownership.

Distinguish installed backend, paired backend, available host session, and stream state. If the stream opens externally, label the destination and return affordance. Do not show live-looking metrics or thumbnails unless actually measured/current. The specimen's numbers are explicitly synthetic. The window header and Advanced tools match S01; USB enrollment (S16) and performance diagnostics (S17) remain their own focused surfaces, not permanent home content.

## S07 — Embedded desktop session

The [shared fullscreen and overlay contract](remote-session-presentation.md) makes this screen precise: foreground ready-time fullscreen by default, an explicit windowed preference, local recoverable controls, separate capture ownership and real retirement state.

**Goal:** use a native remote surface while retaining escape and context. The surface takes maximum area; a minimal overlay exposes input profile, monitor, stream settings, capture/release, and diagnostics. Controls auto-hide only when there is a reliable visible/reachable recovery mechanism. Critical protected USB state remains available without hijacking remote input.

States: initial decode, playing, permission-required, stream stalled, host locked, display unavailable, backend error. Display configured profile separately from observed frame/decode statistics. No general UI bridge handles video frames.

## S08 — Files and transfers

Root selector identifies Local Googlebook, host SFTP, Drive provider, or lab location. Breadcrumb/path is selectable/copyable. File table uses density preference, sortable columns, range selection, and local horizontal overflow where needed. Inspector shows full path, source version, cached/offline state, and permissions.

Transfer drawer groups active/queued/history. Status names separate sent, verifying, committed remotely, and synced by another provider. Conflict view displays both versions with modified/size/version evidence and explicit preserve/replace/save-copy choices. An unknown remote version disables a destructive resume/commit.

For ordinary backends without conditional replacement, every remote save presents an overwrite choice for that exact destination and staged revision. Cancel keeps local work; Save a copy uses a qualified no-replace operation. A previous confirmation does not approve another save, and completion-unknown cannot turn into an automatic retry. Display the concurrency limitation in the decision flow.

## S09 — Devices and protected operation

Table shows provider/device, current consumer, state, last confirmed time, and assign/release actions. Selecting a device exposes VID/PID/serial/approved reset identities and backend diagnostics. During programming, a persistent protected-operation region names the host/device and explains release consequences. On loss, show owner unknown/last confirmed host; recovery reconciles real backend state before reassignment.

## S10 — Activity and notification preferences

Chronological items identify host/project/source and occurrence time. Filters operate on known fields; stale/replayed history is visually different from newly actionable events. An action is enabled only when its source capability and request expiry allow it. Notification settings group job completion, connection, transfer, and device events; permission denial leaves in-app history usable.

## S11 — Settings and diagnostics

Groups: appearance/density/type, shortcuts/input, hosts/trust/credentials, connection/lifecycle, file cache, notification channels, backend versions, and diagnostic export. Keep secrets in native enrollment flows, with per-app ownership explicit. Diagnostics show versions, last error, capability snapshot, and counter scope. Export offers redacted content inspection.

## S12 — Command palette

Search registered actions/workspaces/hosts, displaying scope and destination. Results label actions as local navigation, connect, or consequential operation. Enter performs the selected ordinary action; consequential operations open their normal deliberate flow. Arrow keys navigate, Escape dismisses, focus returns correctly. The palette never turns arbitrary text into a shell command.

## Review checklist for every screen

Identify its primary task, host/environment context, focus entry/exit, empty/loading/partial/error states, narrow-window behavior, readable long labels, touch/keyboard path, and the exact operation authority. Link changes to a stage and an acceptance case. A visually polished happy path alone does not pass design review.

## S13 — Console SFTP beside the active session

Place the session-linked file pane beside the terminal with host/environment and breadcrumb always visible. Its header offers pin/follow-active, refresh, upload and destination actions. A dense table supports range/additive selection, rename, download, Copy path and staged edit through desktop menus. The transfer strip expands into the shared journal without hiding the active destination. Directory tracking shows explicit/manual, shell-integrated or unavailable state.

Incoming drag targets name `Upload to host:path`; outgoing remote files show Preparing download before a staged URI is available. Dropping on the terminal offers path/reference insertion after commit. Keyboard alternatives provide every operation. States include missing SFTP, permission denied, stale listing, queued/paused/verifying/committing, conflict and revoked local grant. **Stages/cases:** P2-S7, Q-C09/C10.

Remote edit state includes Pending remote save, Awaiting overwrite decision, Saved copy, Replaced and Completion unknown. The per-save overwrite prompt follows S08 and [the shared publication contract](../02-architecture/file-publication.md); local editor autosaves never become blanket consent.

## S14 — Separate X11 application and window list

The remote app occupies its own native window with system caption controls, sanitized application title and persistent host identity in Cloudy chrome. Console's graphical-window list groups by host/session and focuses existing windows. Owned dialogs stay with their parent; popups do not create standalone task entries. Resize and DPI updates preserve input coordinate mapping.

Connection loss keeps an honest ended/unknown GUI state and explains that tmux does not preserve X connections. Closing the app requests normal remote close; force close is separate. Restricted/trusted forwarding policy and prerequisites live in session settings with exact reasons when unavailable. **Stages/cases:** P0-S7/P2-S6, Q-C07/C08/Q-P02.

## S15 — Desktop protocol profiles and native session

Choose High-performance stream, RDP or VNC in a labeled protocol control; the same protocol identity is shown on the S06 home row, and switching protocol never masquerades as continuation of the old session. Each profile exposes relevant endpoint/trust/session/input/display settings and collapses unsupported options with an explanation. First trust/authentication uses native prompts. An existing compatible profile opens directly.

The native surface has a compact context/release toolbar, backend-specific resize/clipboard controls and expandable observed diagnostics. Distinguish external stream launch from an integrated RDP/VNC surface. Certificate/security mismatch, missing server, unsupported encoding and expired session appear as layer-specific errors. **Stages/cases:** P3-S7/S8/P5-S2, Q-D07/D08/Q-N02/N03.

## S16 — USB enrollment and routine hotplug

Enrollment identifies provider, exact device/serial or explicit port, approved host, driver/provider permission state and auto-attach choice. A successful setup produces a persistent concise rule such as `This probe → Lab workstation; attach when idle`. Subsequent recognized plugs show Detecting → Attaching → Ready without another Cloudy modal when grants and ownership permit.

Busy/unknown/permission-required uses the existing ownership row and a concrete recovery action; protected programming remains visible. Renaming a device does not change its identity. Unplug/replug and approved bootloader state retain the same logical device record with a new connection generation. **Stages/cases:** P3-S4/S6, Q-D04/D06.

## S17 — Performance profile and diagnostic evidence

Show configured resolution/fps/bitrate separately from actual decoder, negotiated transport/encoding, presented fps, queue/decode timing, RTT and route when observable. Missing metrics read Unknown with their source, never zero. Balanced/Sharp/Metered are editable starting profiles with readable text/CAD previews and no fabricated latency badge.

Advanced diagnostics can export redacted measurements with exact versions and boundaries. A benchmark comparison labels matched/unmatched conditions and proposed budget versus measured result. Surface stalled/recovering state without trapping input. **Stages/cases:** P0-S4/S6/P5-S2/S4, Q-N01/N02/N03.
