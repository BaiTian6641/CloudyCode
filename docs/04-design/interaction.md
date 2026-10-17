# Interaction, input, and recovery

Desktop requirements are explicit in the [terminal interaction contract](desktop-terminal-interaction-contract.md). Reusing a mobile-oriented app component does not make long-press or on-screen key rows acceptable prerequisites. Preserve supported native editing and adapt the terminal boundary through documented APIs.

## Focus ownership

Each window has one focused surface: chrome, terminal pane, stream surface, drawer, or modal. Show focus visibly on that surface/control. Moving pointer hover does not move keyboard focus. Opening a dialog/palette places focus in its first relevant control; dismissing returns it to the exact initiating control or active pane when that control no longer exists.

Tab/Shift+Tab traverse chrome and supporting controls logically. Arrow keys navigate within menus, tabs, and lists. The terminal receives input while focused, including Tab, unless a deliberately chosen local chord applies. Provide a visible “focus toolbar” action and keyboard route out of terminal/stream capture. Do not trap users in an inaccessible canvas/WebView.

## Shortcut precedence

1. OS-reserved shortcuts remain with the OS.
2. A focused modal, IME composition, or menu handles its own valid keys.
3. Deliberately assigned Cloudy shortcuts handle local actions.
4. Remaining input goes to the focused terminal or remote desktop.

Never apply a global Cloudy shortcut to a partly composed IME sequence. Keep context-specific shortcuts visible in the relevant menu and searchable in the palette. Validate on actual Googlebook keyboard layouts; do not promise the app can intercept every system chord.

| Proposed chord | In terminal | In chrome / details |
| --- | --- | --- |
| Ctrl+C | Remote interrupt | Copy where ordinary selection semantics apply |
| Ctrl+Shift+C | Copy terminal selection | Copy supported selection |
| Ctrl+Shift+V | Explicit paste | Paste into active editable field |
| Ctrl+Shift+P | Command palette, if not OS-reserved | Command palette |
| Ctrl+W | Preserve terminal meaning | Close tab only in a documented chrome context |
| Ctrl+J | Preserve literal terminal input | Normal field/menu semantics |
| Escape | Remote input unless local overlay/capture escape policy applies | Close top local overlay |
| Alt+arrow / custom chord | User-configured pane navigation after conflict test | Component-specific navigation |

The release-capture shortcut is configurable and always displayed while capture is active. A local Escape policy must not silently consume every Escape intended for a remote editor. Test a dedicated chord and focus-loss release path before selecting its default.

## Pointer and selection

Use text, resize, default, and remote tool cursors appropriately. In the terminal's default local policy (MobaXterm model, adopted 2026-10-16), completing a pointer selection copies it immediately with visible feedback and right-click performs the single explicit paste. A per-app persisted setting restores right-click as the context-menu gesture, and the menu always keeps a keyboard/visible-button route. Shift+Insert and Ctrl+Shift+V remain explicit paste chords, Ctrl+Shift+C copies the selection, and Ctrl+wheel adjusts terminal font size within the readable type policy. An explicit remote-mouse mode assigns secondary clicks to the remote TUI and keeps a local-menu override. RDP/VNC and X11 surfaces normally send secondary clicks to the remote application; no event may also trigger a local action. Shift+drag provides local terminal selection when remote mouse reporting is active. Double/triple click selects word/line with documented word boundaries. Files support additive and range selection.

Pointer capture begins after a user action on a focused native stream view and releases on focus loss, disconnect, user release, or stream termination. Clear remote held modifiers/buttons when leaving capture. Physical trackpad and mouse are separate qualification inputs; a touchscreen trackpad simulation is not evidence for either.

## IME and multiline input

Keep preedit/candidate composition local and visually anchored. Commit the exact text once. Resizing or switching panes must not duplicate or lose a composition. The optional composer is a local buffer; its Send action explains whether it inserts only or inserts and explicitly submits. Attachments upload first, then insert a valid remote reference. Preserve large prompts as files when that is more reliable.

## State changes and network recovery

| Situation | Visible behavior | Prohibited surprise |
| --- | --- | --- |
| Connecting | Target host/environment, cancellable status | No ambiguous global spinner |
| Host key changed | Fingerprint comparison and deliberate resolution | No silent trust replacement |
| Network lost | Keep layout/output, mark transport disconnected, preserve unsent buffer | No replay after reconnect |
| Durable session reattached | Show actual attachment and restored context | No automatic last-command rerun |
| Host rebooted | New boot/session identity and ended/unknown process state | No claim prior process survived |
| Event gap | History gap marker and refreshed snapshot | No fabricated continuous history |
| USB ownership unknown | Last-confirmed owner + reconciliation action | No reassignment based on timer alone |
| File write conflict | Both versions and commit choices | No silent overwrite |

## Confirmation and reversibility

Ordinary navigation, viewing details, copying text, and opening drawers require no confirmation. Confirm when consequence is destructive or crosses trust/ownership boundaries: changed host key, terminate remote session, discard uncommitted file edits, protected device release, and broad permission enrollment. Explain the specific consequence with host/device/project context. Avoid generic “Are you sure?” copy.

Enrolled USB auto-attach is ordinary application of an explicit saved policy when permission and idle ownership are confirmed; do not add another Cloudy confirmation on every plug. Unknown/competing/protected state stops that automatic action with an inline recovery explanation. Backend auto-use toggles require state reconciliation, not blind retries.

File drops display the captured destination host/path and operation before commit according to the saved transfer policy. Tab switches cannot silently retarget jobs. A file drop onto a terminal inserts a reference only after upload and never submits it. A remote-file export supplies a staged readable URI, not a shell path. Unsupported remote GUI file-drop is labeled separately.

Remote overwrites on a backend without conditional replacement prompt on every save. Show the exact host/path and staged revision, with Overwrite this save, Save a copy and Cancel / keep pending. Do not offer a remember/always-overwrite option. Local autosaves stay pending instead of stacking dialogs; a changed staged revision or destination requires a refreshed decision. Explicit consent does not remove the possibility of an intervening remote edit; follow the [publication contract](../02-architecture/file-publication.md).

X11 windows and native RDP/VNC obey native task focus, IME and system shortcut precedence. Closing an X11 window requests that app's close protocol and may reveal its unsaved dialog. Leaving a native desktop surface releases held input; reconnect never replays clicks. Changing protocol/profile presents the new session context explicitly.

## Alerts and sound

Terminal bell is a configurable alert, not a trustworthy agent-status parser. Structured job events may show explicit waiting/completed states only when the adapter knows the current job/request ID. Prefer quiet badges and in-app activity; user-selectable notification channels handle urgent device failure and job completion. Never generate a burst of fresh notifications from expired events after reconnect.
