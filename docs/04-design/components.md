# Component contracts

All components use the canonical Cloudy roles. A component's action, state model, focus behavior, and accessible name are part of its contract, not finishing work after visual implementation.

## Shared controls

| Component | Anatomy / use | States and behavior | Accessibility |
| --- | --- | --- | --- |
| Primary button | Filled primary, on-primary label, optional leading icon | One principal task action; hover/pressed/focus/loading/disabled distinct | Visible verb; loading announces status and prevents duplicate submission |
| Secondary button | Outline or tonal surface, readable label | Companion task; not equal saturated emphasis | Same target/focus contract as primary |
| Icon button | One recognized action, 16–20 icon in target | Persistent for essential action; tooltip on hover/focus | Explicit accessible name; no icon-only destructive ambiguity |
| Text field | Persistent label, input, helper, optional validation | Validation near field; never placeholder-only labeling | Error association, IME type, selection and paste |
| Select/menu | Current value, disclosure, anchored surface | Keyboard navigation, Escape, selected item | Semantic role, focus return, screen-reader label |
| Navigation row | Icon/label, optional count, selected marker | Hover differs from selected; counts describe meaningful scope | Selected state announced; full name discoverable |
| Tabs | Short context labels, state marker, optional close | Roving focus; close returns focus to neighbor; overflow menu | Tab/tabpanel relationship, close name includes tab name |
| Split divider | Thin separation + discoverable handle | Drag, keyboard resize, reset; preserve minimum panes | Named separator with orientation and value where supported |
| Badge | Short icon/shape + label on subtle semantic container | Status, not a button by default | Meaning remains in text; no color-only encoding |
| Tooltip | Supplemental short label/value | Hover and focus, dismissible; no unique required instructions | Associated description, no focus trap |

## Data and operational components

**Host row:** display name, environment/OS when relevant, last-known connection/capability state, and a small action set. Default selection opens details, not an immediate privileged operation. Separate connect from edit and forget. A stale host record includes age; unknown reachability does not become a red failure badge.

**File table:** name/type, location/root, modified/version, size, transfer/conflict marker, and contextual actions. Header sort direction is explicit. Selection checkboxes and batch actions do not replace single-file open. Range selection follows familiar keyboard/pointer conventions. Grid view is optional; tables remain the dense baseline. Carbon's [table guidance](https://www.carbondesignsystem.com/building-blocks/core/components/data-table/guidelines) is a reference, with Cloudy-specific density and semantics.

**Transfer row:** source → destination, state, bytes/total when known, rate/ETA only when meaningful, pause/cancel/retry as supported. `100% sent` may still be verifying or waiting for remote commit. Unknown total uses an indeterminate indicator, not a fake percentage. Failed rows retain the actual error and whether temporary data remains.

**USB ownership row:** device/product, approved identity/serial display, local provider, confirmed consumer, observation time, operation protection, and an explicit action. During uncertainty, show the last confirmed owner and unknown state together. “Release” is disabled with a reason or requires the documented protected-operation flow while programming; no misleading instantaneous state flip.

**Connection strip:** connection state, host/environment, persistence mode, path if observable, and small measured counters. Use neutral unknown and caution reconnecting. Avoid an all-green “healthy” strip when only SSH is connected and USB/capture are unknown. Keep action targets distinct from read-only measurements.

**Activity item:** host/project, source app/job, event type, occurrence time, received/stale indicator when relevant, body, and supported action. Do not display an action after its originating request expired. Group related updates by stable ID, never solely by equal text.

## Feedback and overlays

| Pattern | Use | Lifetime / action |
| --- | --- | --- |
| Inline validation | Invalid field or operation precondition | Persists until resolved; field-specific action |
| Inline task error | One pane/transfer/host action failed | Keep unaffected work available; show retry only if safe |
| Banner | Ongoing workspace condition, ownership uncertainty, lost permissions | Persists while condition matters; clear explanation and recovery |
| Snackbar | Non-critical completed local action | Short-lived; no unique information needed to recover |
| Dialog | Trust decision, destructive consequence, enrollment | Focus trapped only while modal; explicit action and cancel; return focus |
| Command palette | Find actions and registered contexts | Search, arrow navigation, Enter explicit action, Escape dismiss |
| Empty state | No hosts/sessions/files/activity | Explain scope and provide one relevant starting action |
| Skeleton | Short predictable content load | Preserve geometry; mark busy; avoid in terminal output |

## State visual precedence

Focus is always visible above selection/hover. A selected row may contain an error badge without turning the entire row red. Disabled controls retain a reason nearby or in accessible description. Pending operations have cancellation semantics; spinners never imply safe automatic retry. Destructive actions use danger emphasis at the final consequential control, not every navigation step leading to it.

## Content standards

Use concrete verbs: Connect, Open Console, Copy path, Release device, Review conflict. Error copy follows `operation + observed cause + available next action`. Example: “Upload paused: the source file changed. Select the new version to restart.” Prefer names/units over unexplained icons and abbreviations. Never hide a host path or device consumer identity behind a generic “Ready.”
