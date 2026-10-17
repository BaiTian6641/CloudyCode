# Desktop workflow design refinement

**Authority:** [Rule 0](../00-project/platform-rule-zero.md) and Revision 01. **State:** design specification for P1/P2/P3, not implemented UI. Keep the original Carbon × M3 synthesis: Carbon supplies productive alignment and information hierarchy; M3 supplies coherent tonal roles and native component semantics. Canonical values remain in [tokens.json](../../shared/design-system/tokens.json).

## Arrangement and visual hierarchy

Console has one primary work surface and one optional session file pane. The terminal keeps the largest uninterrupted region. The file pane can replace navigation before it forces a cramped four-column shell. Use existing supporting-pane widths, dividers, density controls and type scale; window constraints can collapse it into a labeled dock/drawer. Keep the destination host/path and pin/follow state visible at every width. These are desktop tiling arrangements, not mobile navigation variants.

Desktop's protocol selector sits with connection context, not as a saturated collection of unrelated cards. RDP, VNC and stream profiles share navigation and status anatomy while exposing their own settings. A native remote surface gets maximum area and a reachable input-release route. USB setup and routine auto-attach use the existing ownership table and a focused enrollment panel rather than a permanent onboarding overlay.

X11 top-level apps are separate OS-managed windows. Cloudy adds only the host/context and necessary connection controls around their native surface. Preserve system caption geometry and remote app content. Owned dialogs, menus and popups retain their hierarchy; an inspector must not intercept a remote modal's intended focus.

## Role mapping

| Element | Canonical roles | Intended reading |
| --- | --- | --- |
| Work/file surfaces | `surface`, `surface-muted`, `divider` | Calm flat regions with aligned productive data |
| Active host/file selection | `primary-container`, `on-primary-container` | Current context, distinct from completion |
| Drag destination | `primary-container`, `outline`, explicit host/path label | Where the copy will go; acceptance not yet completion |
| Keyboard focus | `focus` above hover/selection | Exact recipient of keyboard input |
| Routine ready/committed state | `success`, `success-container`, text/icon | Observed completed fact, not auto-attach preference |
| Awaiting permission/reconciliation | `warning`, `warning-container`, explanatory text | Action/state needing attention |
| Failed trust/write/attach | `danger`, `danger-container`, exact reason | Failed consequential operation, limited to affected row/pane |
| Unknown/stale observation | `text-secondary`, neutral surface, age | Missing evidence without a false healthy/error claim |
| Terminal | Fixed ANSI and terminal roles | Preserve application color semantics regardless of app accent |

Use paired foreground/background roles and existing contrast checks. Do not tint the remote application/desktop pixels, recolor terminal ANSI success/error, or let per-host accent colors replace trust/ownership text. Keep one dominant action per focused task and avoid using saturated green/teal for every connected item.

## Text, menus and actions

Retain the body/type defaults and user scaling. Monospace is for paths, fingerprints, identifiers and measurements; ordinary labels and explanatory text use the UI font. Preserve exact copyable path values independently of shortened display labels. Long names can wrap in details; a tooltip supplements a label rather than carrying the sole destination identity.

Context menus and task toolbars expose Upload, Download, Copy path, Rename, Open in editor, New X11 session, Focus window, Release capture, Enroll device and Pause auto-attach in their relevant contexts. Show shortcuts where qualified, keep right-click behavior predictable and provide keyboard access. Copy/paste within a terminal remains different from ordinary text fields; Ctrl+C does not become an app-wide clipboard override.

## Integration without ambiguity

An integrated file pane follows an explicitly displayed session binding. Its jobs retain source/destination after a tab switch. A remote edit shows staged/modified/uploading/committed/conflict separately. The drop overlay names copy versus path insertion and never implies a binary paste into the terminal. Outgoing exports show preparation and keep local staging access bounded.

On ordinary non-conditional servers, an overwrite decision appears for every remote save and names the exact staged revision. Preserve pending local work on cancel and show save-copy as an alternative; do not introduce a remembered overwrite permission. The dialog explains the remaining concurrent-writer risk without calling a prior check race-free.

An auto-attach preference describes intent. Ready describes a fresh confirmed consumer and usable device. Show both when needed: `Auto-attach enabled · Busy on another host`. Routine successful hotplug uses a quiet row/badge, while permission or protected ownership uses persistent inline recovery. Avoid repeated modals for previously enrolled idle-device rules.

Performance controls distinguish configured values from observed results. Use readable units and measurement sources; no decorative green latency score. Put detailed queue/codec/route statistics in a diagnostic panel so the default workspace remains focused on work. State uncertainty and comparison conditions in exported evidence.

## Review gates

Review S13–S17 with two hosts, two Console windows, separate X11 apps/dialogs, a native RDP/VNC session and an enrolled probe. Test keyboard/trackpad/mouse, native IME, narrow/short tiled windows, external-display scaling, light/dark, user text scaling and reduced motion. All operations have keyboard alternatives. Verify host/path/consumer identity remains legible through errors and context changes. Q-P03/Q-A01 and the linked feature cases are required; a browser specimen alone cannot pass them.

Use the [native workspace review procedure](native-workspace-review.md) to separate shell/state presentation from engine-backed evidence. Record unresolved feature components against their implementation stages; a synthetic device row cannot qualify USB permission or hotplug.
