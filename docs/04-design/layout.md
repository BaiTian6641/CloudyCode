# Layout, density, and adaptive behavior

Adapt to the **current window's usable content area** after system insets, not physical panel resolution. Google's [adaptive window guidance](https://developer.android.com/develop/ui/compose/layouts/adaptive/use-window-size-classes) provides the platform route; the thresholds below are Cloudy's initial content constraints and will be tuned in P1.

These are Googlebook desktop window rules under [Rule 0](../00-project/platform-rule-zero.md), including narrow tiling. They do not create phone/tablet layout targets. Keep desktop commands, focus and host context even when supporting panes collapse.

## Shell geometry

| Region | Proposed baseline | Behavior |
| --- | --- | --- |
| System caption | Measured native inset/control bounds | Never recreate or cover system controls |
| App toolbar | 56 units | App identity, workspace context, principal task action |
| Navigation pane | 224 default, 192–280 adjustable | Collapse to labeled access in narrow windows |
| Work surface | Flexible, minimum useful terminal/file width | Gets remaining space; preserve task content first |
| Inspector | 288 default, 240–360 adjustable | Optional; becomes drawer when insufficient width |
| Status strip | 28 minimum; wraps to content if needed | Connection, persistence, counters; critical state is not hidden |
| Gutter/divider | 1-unit visual divider, discoverable drag handle | Keyboard resize command and reset action |

The specimen uses an app toolbar in a browser page; it does not simulate or implement Android's system caption APIs. The native implementation must use real insets and occupied control rectangles.

## Width behavior

| Usable width | Initial arrangement | Priority |
| --- | --- | --- |
| Under 600 | One work pane, modal navigation/inspector, compact task toolbar | Preserve focused task, names and critical status |
| 600–959 | Work pane plus optional navigation; inspector overlays on request | No squeezed three-pane layout |
| 960–1279 | Navigation + main; inspector replaces navigation or opens as drawer | Keep main task usable |
| 1280 and above | Navigation + main + optional inspector | Split terminal only if each leaf meets minimum size |

These are not renamed Android window-size-class constants. Native code maps available constraints and user settings to these arrangements. At large font scales or short window heights, reflow earlier. Hide secondary labels only when an equivalent accessible name and discovery path remain.

## Density

| Mode | Table/list row | Control visual height | Intended use |
| --- | --- | --- | --- |
| Comfortable | 48 nominal minimum | 48 nominal minimum | Default when input capability is unknown or touch is primary |
| Standard pointer | 40 nominal minimum | 40 nominal minimum | Qualified precise pointer workflow |
| Compact pointer | 32 nominal minimum | 32 nominal minimum | Explicit user preference for high-density work |

Keep body text at 14 by default in every density. Compact mode changes padding and row height; it is not a global zoom-down. Avoid overlapping invisible hit areas. Native controls may keep platform minimum touch targets; custom compact controls require a documented precise-input mode and accessibility review. The current HTML specimen offers all three choices: Comfortable, Standard pointer and Compact pointer. Its controls and rows use nominal48/40/32 minimums with content growth, rather than fixed heights or text shrinking; wrapped labels and scaled content may increase rendered height. This is browser presentation, not native hit-target or device qualification.

The implemented native presentation preference is app-owned and observed by its open windows; independent window-profile layout persistence remains planned. Density preference is explicit and sticky per owning app. Do not rearrange the UI on every mouse/touch event. Provide an obvious comfortable-mode switch and keep a safe default when capabilities are ambiguous.

## Console arrangement

The terminal gets the largest continuous area. Tabs sit above it; host/environment/workspace context stays in a compact header. An adjacent SFTP pane uses the supporting-pane width constraints, with visible host/path and pin/follow state. At moderate width it can replace navigation; at narrow width it docks as a reachable drawer or independent file window. Avoid narrowing the terminal, navigation, files and inspector all at once. Split dividers preserve leaf constraints and resize remote PTYs after a short debounce. Search/forwards/transfers use a supporting drawer; opening one does not steal terminal input until the user enters its control.

Persist split proportions, active tab/pane, font scale, drawer choice, and remote session IDs by window profile. When a saved layout no longer fits, keep all sessions reachable in tabs; do not terminate hidden sessions to satisfy geometry.

## Desktop arrangement

Workspace/host navigation on the left, native RDP/VNC/stream surface or explicit external-stream launch state in the center, optional files/device inspector on the right. The full Files destination uses a table and details pane. Devices uses a stable ownership table with enrollment/auto-attach settings and a persistent protected-operation region. Activity is chronological, filterable by host/project, and preserves provenance.

When the streaming app is external, display a launch/return state rather than a fabricated live thumbnail. Embedded media later uses the center surface and native input capture; app overlays must not obstruct the release affordance or misdirect input.

Separate X11 applications are native desktop windows outside the main Console pane. Give each a compact host/app context and native caption, keeping menus/dialogs associated with their owner. Do not redraw an entire remote desktop as the substitute for per-app windows. See [desktop workflow design](desktop-workflows.md).

## Alignment and overflow

Align labels/values on common starts, file sizes and numeric metrics at the end, and row actions consistently. Truncate long host names/paths at the least useful segment while exposing the full value on focus/hover and in details. The main shell must not horizontally scroll. A terminal, data table, or technical path may have an intentional local scroll region.

On RTL locales, mirror navigation and ordinary layout appropriately, while preserving LTR treatment for commands, identifiers, IP addresses, and terminal content. Do not reverse stream input coordinates merely because app chrome is mirrored.

## Current specimen role follow-through — 2026-10-16

Reviewed HTML/CSS source now binds section/dialog headings to the existing20/28 role, explanatory body text to14/20, and supporting metadata to12/16. Neutral workspace monograms use surface-muted with an outline border and visible identity text, so row hover does not rely on an indistinguishable fill alone. This preserves existing canonical roles; it adds no palette, halo or EDA micro-density tier. The [final specimen review](../06-validation/2026-10-16-specimen-density.md) accepts sixteen layout and ten interaction-state captures in both appearances; the corrected run04 keeps pane geometry stable and waits for announcements to expire. Browser geometry and native component acceptance remain separate.
