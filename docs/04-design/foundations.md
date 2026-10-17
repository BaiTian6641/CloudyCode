# Visual foundations

All values below are **Cloudy proposals**. They are not copied vendor palettes or a claim that Material's dynamic-color algorithm produced these values. The source of truth is [tokens.json](../../shared/design-system/tokens.json); native code and web specimen consume or generate from that file.

## Color roles

| Role | Light | Dark | Use |
| --- | --- | --- | --- |
| Canvas | `#F4F7F7` | `#101A1D` | App background behind functional surfaces |
| Surface | `#FFFFFF` | `#17262B` | Main work region, menus, dialogs |
| Surface muted | `#EDF2F3` | `#1C3036` | Navigation, low-emphasis supporting region |
| Surface raised | `#E3EAEC` | `#263E45` | Hover/secondary bounded surface; not every row |
| Text primary | `#182427` | `#E1EBED` | Essential labels, values, body |
| Text secondary | `#4C6066` | `#AEC3C9` | Metadata with adequate contrast |
| Primary | `#006A6A` | `#7DD5C7` | Principal action, selected indicator |
| On primary | `#FFFFFF` | `#003A34` | Content on filled primary |
| Primary container | `#D2F1EA` | `#164C43` | Selected/tinted region |
| On primary container | `#005047` | `#B1F2DE` | Content in selection container |
| Accent / link | `#3559C7` | `#B6C5FF` | Links and secondary navigational emphasis |
| Accent container | `#E4EAFF` | `#2E4076` | Occasional non-status annotation |
| Control outline | `#667A80` | `#79959E` | Visible form/control boundaries |
| Subtle divider | `#D3DFE2` | `#35515A` | Decorative separation, never sole target boundary |
| Focus | `#3559C7` | `#BAC8FF` | Visible focus ring with contrasting offset |

Use success, warning, and danger token pairs for operational meaning. Light foregrounds: success `#216C46`, warning `#865000`, danger `#B3261E`; dark foregrounds: `#93D7AE`, `#F5C36B`, `#FFB4AC`. Pair them with their dedicated containers from the token file. A warning banner needs an action or persistent condition, not merely a yellow icon.

**Pairing rules:** text-primary/secondary may sit on tested neutral surfaces. On-primary belongs only on primary, and on-primary-container only on its container. Decorative dividers need not reach control-boundary contrast. Outlines and focus must meet the tested adjacent-surface requirement. Do not lower an entire control's opacity to create disabled styling; use explicit disabled tokens and keep explanatory content readable.

**Dynamic color:** opt-in research only after the fixed schemes pass. Remap non-critical accent roles, regenerate tested pairs, and preserve status colors, host identity labels, and ANSI colors. Wallpaper changes must not change terminal interpretation.

## Typography

Preferred packaged candidates: IBM Plex Sans for UI and IBM Plex Mono for code/terminal; choose appropriate Noto/system CJK fallbacks. Pin font versions/licenses/subsets in P1. The specimen uses system fallbacks so it works offline without downloading fonts.

| Token | Proposed size / line height | Weight | Use |
| --- | --- | --- | --- |
| Page title | 24 / 32 | 500 | Top-level page title, sparingly |
| Section title | 20 / 28 | 500 | Workspace title, dialog heading |
| Body | 14 / 20 | 400 | Tables, content, explanatory UI |
| Label | 14 / 20 | 500 | Buttons, fields, navigation |
| Metadata | 12 / 16 | 400 | Timestamps, units, supporting captions |
| Terminal | 14 / 21 | 400 | Independent adjustable mono grid |

Sizes are CSS px in the specimen, sp for native text at baseline; layout sizes map to dp in native implementation. Their visual equivalence must be checked at real display density and user font scale. Use tabular numerals for changing metrics/aligned file sizes. Use sentence case. Avoid full-uppercase navigation and all-mono UI. Keep prose line lengths roughly 55–85 characters when space permits; allow technical paths to use horizontal scrolling or a full-path inspector.

## Spacing and geometry

Spacing scale: `2, 4, 8, 12, 16, 24, 32, 48`. The 4-unit rhythm is a project rule informed by systematic grid design, not a claim of exact Carbon token equivalence. Use 8 inside compact control groups, 12–16 between related controls, 24 between distinct tasks, and 32 for major section separation.

Corner roles: working panes/dividers `0`; fields `6`; buttons `8`; bounded previews/menus `12`; dialogs `20`; short status chips `full`. Do not round the entire terminal grid, every table row, or every sidebar item into independent pills. Selection may use an 8-radius inset surface with a persistent start marker.

Use flat surfaces for structure. Elevate menus, palette, and dialogs only when they overlap content. Light shadows are optional supplemental depth; dark borders/surface steps remain the primary separation. Never rely on shadow alone to define an interactive target.

## Motion

Original timing proposals: immediate feedback `100 ms`, small state transition `160 ms`, pane/dialog transition `220 ms`. Keep terminal input and media frames outside app-chrome animation. Use one easing curve for entering and one for leaving only when the distinction clarifies motion. Do not loop decorative status animation. Indeterminate progress needs a clear accessible status and respects reduced-motion preferences.

Reduced motion removes pane translation and nonessential interpolation; content/state changes remain immediate. Theme changes may update without animation. Reconnect does not make the whole workspace pulse.

## Terminal palette

Default terminal background/text are separately tokenized and remain dark in both shell appearances. A light terminal is an explicit future option after ANSI contrast testing. ANSI normal/bright colors are fixed terminal semantics, not the shell's success/warning/link roles. Ensure each normal foreground is readable on the default terminal background. Test inverse video, selection, cursor, dim, bold, and remote application truecolor; no global guarantee is possible for arbitrary remote-specified color combinations.
