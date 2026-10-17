# Design principles and Carbon × Material 3 synthesis

**Product constraint:** [Rule 0](../00-project/platform-rule-zero.md). Cloudy designs Android-packaged desktop/laptop tools for Googlebook. Physical keyboard, trackpad/mouse, native windows and desktop information density are primary. Phone/tablet optimization is outside scope; narrow tiled windows and Googlebook accessibility remain required.

## 1. Make work legible before making chrome expressive

The terminal, file list, desktop, and device state are the working content. Navigation provides stable alignment and hierarchy. Avoid turning every host, row, or diagnostic into a raised card. Use whitespace and section labels to group related data; use a container when the group is itself selected, actionable, or independently manipulated.

Carbon's [productive typography](https://www.carbondesignsystem.com/building-blocks/foundations/typography/overview), [grid](https://www.carbondesignsystem.com/building-blocks/foundations/2x-grid/overview), and [table patterns](https://www.carbondesignsystem.com/building-blocks/core/components/data-table/guidelines) inform this structure. Cloudy adapts them into its own sizes and components.

## 2. Use tonal hierarchy to make density comfortable

Material's [color-role model](https://m3.material.io/styles/color/the-color-system) informs paired foreground/background roles and quiet surface separation. Cloudy uses a cool mineral canvas, mostly neutral work surfaces, a lightly tinted navigation selection, and saturated color for the current action/focus. Dark appearance uses deliberate surface steps rather than black borders around every pane.

Keep text contrast strong. A subdued color can communicate lower priority only while remaining readable. Selection, hover, focus, disabled, pending, and error are distinct states. Do not reuse “green” for both the active workspace and successful completion without labels.

## 3. Keep actions close to their consequences

Host actions appear beside the selected host. File actions appear above the selected file list. USB assignment states both provider and consumer. Terminal session actions distinguish view closure, network disconnect, detach, terminate, and stop job. Use an inspector to reveal detail without displacing the primary work area.

## 4. Preserve context across the two products

Console and Desktop share host/environment/project vocabulary, color roles, action hierarchy, and focus behavior. Each still has a task-specific arrangement. Use a labeled cross-app action with the destination and project; do not hide the process/window transition behind a fake in-app tab.

## 5. Design for precise input and real touch access

Menus, pane dividers, tab controls, range selection, and shortcuts support keyboard/pointer work. A comfortable mode preserves touch targets; compact mode is explicit and optimized for precise input. Discover shortcuts in menus and the palette. Provide keyboard alternatives to dragging and never depend on hover for essential actions.

## 6. Give uncertainty a truthful visual state

“Unknown” is an observation state, not an error color by default. Distinguish it from unavailable, disconnected, permission-required, reconnecting, and failed. Stale information shows its age. An expired event history shows a gap. Last-confirmed USB ownership persists with an unknown badge until reconciliation.

## Contribution matrix

| Design concern | Carbon reference contribution | M3 reference contribution | Cloudy resolution |
| --- | --- | --- | --- |
| Structure | Productive grids, aligned data and toolbars | Adaptive panes/navigation | Stable sidebar, work surface, optional inspector |
| Color | Semantic roles and layered neutrals | Tonal containers and on-color pairs | Original mineral/teal/indigo schemes |
| Type | Productive Plex hierarchy | Purpose-based type roles | Small explicit scale; mono only where it aids parsing |
| Components | Tables, inline actions, compact feedback | Native semantic controls, shape and state treatment | Unified wrappers with selected radii and density |
| Focus | Precise visible focus | Platform focus/semantics | Shared focus role plus native accessibility semantics |
| Expression | Restrained product chrome | Softer bounded surfaces | Gentle corners on controls/menus, flat working panes |

Do not mix untouched Carbon React controls and untouched M3 controls side by side. The design is unified by Cloudy tokens, anatomy, and interaction contracts. Framework choice follows platform behavior and maintainability, not the visual reference name.
