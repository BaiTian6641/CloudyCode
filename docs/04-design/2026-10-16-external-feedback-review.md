# External design feedback review

Reviewed 2026-10-16 against source commit `7ce21a43401c64ea123c433d66c041b166aa2f76`. The user supplied `cloudy_design_language_optimization.docx`, attributed to Gemini 4 Argon, for review. Its SHA256 is `d9915ed8bb0c86d9ae6be8e83e501c20fa8d7f3e9e5e8d90ca8fce7469a17d03` (51,461 bytes). The local original was read without modification; its 14 rendered pages were inspected. It contains no embedded images, tracked edits or comments. The original and private rendering intermediates are not published in this repository.

The feedback offers useful P1-S2 candidates. It is external review material, **not an approved specification or instructions to workers**. In particular, its EDA/IDE product, micro-density tier, toolkit theme injection and embedded-window assumptions do not amend [Rule 0](../00-project/platform-rule-zero.md), the Console/Desktop split or separate [X11 windows](../02-architecture/x11-windows.md). The full implementation goal continues; this review does not complete a stage or qualify a component.

## Current disposition at 520a0b9

The leader and a direct Codex GPT6.1-Sol coworker refreshed this review against source `520a0b9fa64de65fafd1c7b4163d1acced2422f0` and the unchanged Word original. **Keep the current visual direction and apply the feedback selectively.** The dated discrepancy table below describes the original source, not a fresh list of unfixed defects. The later follow-through sections bind the accepted changes and their evidence.

| Recommendation | Current disposition |
| --- | --- |
| Canonical typography, three density choices and distinct neutral workspace identity on hover | Implemented in the specimen, with recorded layout/state review. Native appearance/density choices also exist; their evidence is separately scoped. Do not repeat those fixes. |
| Deliberate dialog roles and readable scaled controls | Vault dialog roles are explicitly bound; profile editor/dialog geometry has bounded actual-device repairs. Trust/vault geometry, other components and broader accessibility still need their own evidence. |
| Complete native role/state consistency | Continue component-by-component review of actual Material consumers, including focus, selection, disabled and warning states. The theme's partial role mapping remains documented; a new palette is not implied. |
| Additional prose/info roles, surface colors or focus halo | Candidates only when an actual component demonstrates the need. The existing focus/accent-container ratios do not establish a visibility failure. |
| Fixed compact heights, a fourth micro tier, EDA/IDE product and remote toolkit theme injection | Not adopted. Keep content growth, user font scaling, existing product scope and independent remote content/window ownership. |

Remaining native priorities are scaled Dark, physical keyboard/pointer/IME, screen-reader behavior, hit-target overlap and complete confirmation visibility. Preserve the [native review's exact boundaries](native-workspace-review.md); browser captures and framework-injected events are not substitutes for those observations. No unrecorded major discrepancy was found in this bounded refresh. Official D01/D10–D12 guidance was rechecked; no token, product source, API availability or capability decision changes follow.

## Six discrepancy claims

| Feedback topic | Finding from current source | Recommended disposition |
| --- | --- | --- |
| Typography bindings | [Specimen CSS](preview.css) hardcodes body 14px/1.5, terminal 14px/1.85 and inspector text line-height 1.8. These diverge from canonical role values. Native typography already uses generated values in scalable sp. | Reconcile specimen roles during P1-S2. The terminal change from 25.9px to 21px implies about 23.3% more rows per fixed height before rounding/padding; it is not a measured native capacity or readability gain. |
| Shell geometry | Toolbar64 differs from baseline56; navigation212 and inspector264 differ from defaults224/288 but remain within their adjustable ranges. Status minimum34 satisfies the documented minimum28. | Centralize accepted defaults while retaining resize, content wrapping and font scaling. Do not classify every difference as a contract violation. |
| Standard pointer | The specimen offers only Comfortable/Compact, explicitly documented in [layout](layout.md). Its table heights differ from nominal tiers. | Adding Standard pointer is useful existing-scope work. Default selection needs qualified input policy; exact fixed heights and a fourth 26dp EDA tier are new proposals. |
| Status foreground roles | Canonical success/warning/danger foregrounds already pair with their containers and pass declared contrast checks. Separate on-container names are absent. | Consider explicit aliases for clearer component semantics. Material `ColorScheme` has error roles, not built-in success/warning roles; this is not a missing one-to-one API mapping. |
| Hover fill | Unselected navigation hover and neutral monogram both use surface-raised. The badge foreground remains visible. | Resolve the fill collision through a demonstrated state treatment; the entire badge does not disappear. |
| Focus/accent | Light focus and accent share a color, but accent-container is a different color. Existing CSS uses a 2px ring with 3px offset. | Inspect actual adjacent states before choosing a halo. Color equality alone does not prove failed focus visibility. |

## Numerical and standards corrections

Independent sRGB calculation reproduces 27 of the document's 28 light/dark ratios in section 3 to two decimals. Dark terminal text `#DCE9ED` against `#0C1519` is **14.88:1**, not 15.22:1; it still passes the stated text threshold. The current canonical 25 declared pairs per theme and 16 ANSI colors per theme pass their configured checks: 82 checks total. This covers declared solid-color combinations only.

Focus against accent-container is **5.14:1 light / 6.06:1 dark**. This supports further state inspection rather than the asserted failure. [W3C non-text contrast guidance](https://www.w3.org/WAI/WCAG22/Understanding/non-text-contrast.html) evaluates meaningful indicators against adjacent colors; it does not require all temporal hover/rest states to contrast 3:1 with one another. Actual overlays, clipping and focus placement still matter (D11).

The proposed EDA table also contains arithmetic discrepancies: `#8DB4FF` on `#101C22` is 8.34:1, not 8.44:1; `#EBC577` is 10.56:1, not 10.22:1. For the active-PC row, the displayed `#7DD5C7` bar against `#164C43` is 5.68:1; its quoted 7.74:1 belongs to the different canonical on-primary-container foreground. A row listing wire, bus and cell colors cannot assign one contrast ratio to all three. These are proposed data colors, not accepted Cloudy tokens.

The claim that staying below 16:1 avoids halation is unsupported by the supplied evidence. Contrast ratios do not establish long-session visual comfort or overall WCAG conformance. [W3C contrast guidance](https://www.w3.org/WAI/WCAG22/Understanding/contrast-minimum.html) supplies minimum criteria; display conditions, individual preference, typography and rendered interaction require separate review (D10). Keep eye-comfort language as design intent.

## Native work the feedback does not cover

The [Android generator](../../tools/generate_android_tokens.py) already emits canonical colors, numeric typography, spacing, radii, density and motion. [CloudyTheme](../../shared/design-system/android/src/main/java/io/github/baitian6641/cloudy/design/CloudyTheme.kt) maps a subset into Material roles. Its secondary and secondaryContainer both currently use AccentContainer; other surface/inverse/tertiary and typography roles retain Material defaults. This is a partial local-window implementation, not the completed catalog. Review roles actually used by each new component, including selection/disabled/focus, before expanding coverage. Emitted constants alone do not implement density or shell geometry.

[Carbon's guidance](https://www.carbondesignsystem.com/building-blocks/foundations/color/overview) alternates light-theme layers and progressively lightens dark-theme layers, with separate interaction tokens (D01). The proposed monotonic surface mapping is an original Cloudy candidate, not a literal Carbon-to-Material equivalence. Keep static container roles separate from transient hover/press roles. The inspected [upstream ColorScheme source](https://android.googlesource.com/platform/frameworks/support/+/eec456ba3412a9a3b355a127c065fbab72ad7392/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/ColorScheme.kt) documents surface and foreground roles (D13); this source snapshot is explanatory, not a dependency repin or device result.

## Useful candidates and protected boundaries

Flat working panes, restrained control rounding, quiet secondary diagnostics, readable prose and labeled workspace identity fit the existing design direction. A prose role and explicit informational colors can be evaluated when an actual component needs them. Identity colors supplement host/account/trust text. Critical permission, ownership, errors and capture-release controls remain visible.

The document's proposed `on-success-container`, `on-warning-container` and `on-danger-container` values change foreground colors; they are not aliases for the existing status foregrounds. Adding clearer semantic names while retaining current values is a separate option. Treat the supplied replacement values as palette candidates, requiring review on the actual components and both appearances before adoption. Likewise, the claimed reduction in fatigue is a design hypothesis, not a result established by the palette calculations.

Retain the three current density tiers as nominal baselines with content-driven growth. Do not freeze rows at a height that clips user-scaled text or creates overlapping targets. [Compose accessibility guidance](https://developer.android.com/develop/ui/compose/accessibility/api-defaults) explains minimum touch-target expansion and overlap risks (D12). Desktop precision modes require their own review; they do not justify a global font reduction or a phone/tablet workstream.

Cloudy owns its chrome and terminal defaults. It must preserve terminal ANSI/truecolor and remote RDP/VNC/engineering content. Separate X11 application windows and modal ownership remain required; a unified embedded canvas is not a substitute. General Wayland embedding, removing remote menus, automatic GTK/Qt/Xresources configuration, local EDA editors/synthesis tools and shared HIL authority are outside the approved scope of this feedback review. Future integrations need explicit scope and real backend contracts. Bare Escape and canvas mnemonics follow existing [input ownership](interaction.md), including OS/IME/modal precedence and a distinct release route.

## Follow-through

P1-S2 owner: design/Android worker, accepted by the integration leader. First reconcile current native roles and clear specimen typography differences; then demonstrate necessary surface/state, geometry and three-density behavior. Proposed colors, halo and prose values remain candidates until component review. Check both themes, hover/pressed/selected/focused states, narrow/short desktop windows and user font scaling through the existing [native review](native-workspace-review.md). Preserve native and specimen evidence separately.

No token, CSS, APK or device behavior changes in this review. No new stage, dependency, EDA product, automatic host mutation or capability promotion is authorized. P1-S3 [protected identity work](../03-platform/googlebook/product-vault-entry.md) remains independently ready. Two direct Codex coworkers supplied read-only audits; the leader checked their source findings, corrected one arithmetic interpretation and independently recomputed contrast. The supplied document was not sent to OMP.

## Follow-up — 2026-10-16 typography alignment

The [bounded specimen typography record](../06-validation/2026-10-16-specimen-typography.md) now binds the CSS change and four final light/dark renders at1440px and560px. Body14/20, page24/32 and terminal14/21 use existing canonical roles, including responsive overrides. The parent visually reviewed the final captures after correcting theme/transition capture issues. This is Comfortable Console synthetic HTML evidence only; native font scaling, accessibility, other app/density and full P1-S2 acceptance remain open. The original dated source findings above are preserved.

## Current-source reconciliation at 64b8437

The follow-up review checked the same unchanged Word original against source commit `64b8437f6fa121e653f4b492dd15dedbc394f00e`. A direct Codex GPT6.1-Sol coworker audited the current implementation; the integration leader inspected its findings against the source. Existing D10–D12 guidance was rechecked and the terminal/focus ratios above were independently reproduced. This records remaining work after the accepted typography increment, so subsequent workers do not repeat the completed fix or infer that every text role is finished.

| Area | Current state | Next bounded review |
| --- | --- | --- |
| Body, page and terminal | Canonical CSS bindings and responsive overrides are present. | Retain the completed specimen evidence; native scaling remains a separate gate. |
| Section and dialog headings | Inspector, Desktop summary, file-list and command-palette headings still use ad hoc 18px sizes in [preview.css](preview.css), rather than the documented section20/28 role. | Bind the existing section role, or record a component-specific exception after visual review. |
| Explanatory text and metadata | Inspector paragraphs remain12px/21.6px; several other descriptions use12px. The previous increment explicitly left these unchanged. | Classify body explanation separately from supporting metadata, then bind the appropriate existing role. Avoid blanket font replacement or adopting a new prose role without a component need. |
| Native component coverage | The local window screen (`WindowWorkspaceScreen.kt`, reviewed at that commit; superseded the same day by the platform `WorkspaceHomeScreen` under the [workspace home revision](2026-10-16-workspace-home-revision.md)) uses mapped typography and explicitly sized main window actions. The [vault dialog](../../shared/android/platform/src/main/kotlin/io/github/baitian6641/cloudy/platform/CloudyVaultActivity.kt) inherits a page-style title and unmapped surface/shape defaults, as traced below. | Bind its accepted dialog roles deliberately, then review the rendered component. Include the Saved credentials entry action in shared-control density/shape review. |
| Density and interaction states | The specimen still offers only Comfortable/Compact; canonical Standard pointer exists as data. Neutral workspace monograms still share their hover-row fill. | Demonstrate all three density behaviors and distinguish hover, focus and selection in both appearances. Preserve scaled content growth and visible identity text. |

The vault deletion dialog supplies no explicit title style, container color or shape. Inspection of the selected Material3 Android1.4.0 artifact confirms `DialogTokens.HeadlineFont = HeadlineSmall`, `ContainerColor = SurfaceContainerHigh` and `ContainerShape = CornerExtraLarge`; the dialog consumes those defaults. Cloudy maps `headlineSmall` to page24/32, but documents section20/28 for dialog headings. It maps `surfaceContainer`, leaving `surfaceContainerHigh` at the library default, and assigns its20dp dialog radius to `large`, while this component consumes `extraLarge`. These are specific implementation gaps; they do not justify replacing the complete palette or remapping the page heading globally.

The coworker and leader independently inspected the pinned bytecode. The inspected `classes.jar` matches the bytes inside the locked1.4.0 AAR; SHA256 `5293787ad0dcb97d245586942135fd3e3aaa6f0faadef5fd1a1c981413e3242c`. No cached upstream source JAR was available, so this is an actual selected-artifact finding, not a claim about current upstream source text or a native rendered result. Keep the deletion warning, confirmation, cancellation and authentication semantics intact when addressing the presentation.

P1-S2 remains the owner of these items. Use the [native workspace review](native-workspace-review.md) for narrow/short windows, font scaling, keyboard focus and pointer behavior; keep HTML and native results separately labeled. This source reconciliation makes no component pass claim and changes no palette, density values, product scope, phase dependencies or release gates.

## Native profile component follow-through

The [0.1.4 profile editor](../../qualification/results/P1-S3/2026-10-16-profile-editor.md) now consumes canonical geometry through a public `CloudyControlMetrics` facade. Its confirmation dialogs explicitly select section typography, the accepted raised-container color and20dp dialog radius; working fields and buttons retain their existing canonical roles. This applies the source-level lesson above without changing the palette or globally remapping page typography. Actual CRUD/state tests pass; both-theme rendering, scaled/narrow geometry, physical focus/input and accessibility remain unqualified for this component. The original vault dialog finding and remaining specimen density/state work are still open.


## Native SSH trust component follow-through

The [0.1.5 SSH trust manager](../../qualification/results/P1-S3/2026-10-16-ssh-trust-manager.md) applies the same explicit canonical section typography, raised dialog container,20dp dialog radius and control metrics. Exact peer/key/state details remain selectable, with an explicit fingerprint-only copy action. Inspection/revocation and controlled recreation pass actual Activity tests; both-theme/scaled/narrow rendering, physical focus/selection and clipboard execution still need their own evidence. This adds another source-level application of the feedback without promoting the remaining vault dialog, specimen density/state or full P1-S2 gates.

## Native appearance, density and vault-dialog follow-through

The [0.1.6 native display increment](../../qualification/results/P1-S2/2026-10-16-native-display-settings.md) implements per-app System/Light/Dark and the existing three density tiers. It also corrects the vault deletion dialog's explicit section typography, raised container and20dp shape, preserving its security/lifecycle behavior. The vault change is source/compile reviewed; protected-dialog rendering and authentication remain unqualified.

Twelve actual Android cases pass. Six leader-inspected Console viewport captures cover Light/Dark × Comfortable/Standard pointer/Compact pointer at font scale1.0, with preserved native draft/selection and window identity. Sampled control semantics heights are48/40/36.31dp;32dp remains a nominal Compact minimum, not an exact rendered size or hit-target measurement. No token/palette replacement or scope expansion follows from this result.

This supersedes only the native missing-choice/nominal-density and vault-source findings above. The HTML specimen still needs its section/metadata, Standard pointer and hover-state work. Native narrow/short/scaled geometry, physical focus/input, accessibility and full component review remain open. The original document remains unchanged review material, and proposed palette/halo/EDA integration ideas retain their earlier disposition.

## Latest source follow-through — 2026-10-16 density and roles

The current [HTML](preview.html) and [CSS](preview.css) now expose Comfortable, Standard pointer and Compact pointer with nominal48/40/32 control/list minimums and content growth. Reviewed bindings use the existing section20/28, body14/20 and metadata12/16 roles; labels may wrap and controls may grow rather than forcing scaled text into a fixed height. Neutral workspace monograms now combine surface-muted, an outline border and visible text, preserving identity through neutral-row hover without a new semantic palette. This supersedes the unresolved current-source items at64b8437 above while preserving those dated findings as history.

The [final specimen review](../06-validation/2026-10-16-specimen-density.md) now accepts sixteen layout and ten interaction-state captures in corrected run04. The earlier incomplete comparisons and capture failures remain preserved separately. No browser result certifies native font scaling, hit-target overlap, physical input, focus or full P1-S2 acceptance. The original Word document remains external feedback rather than project authority. Palette replacement, status-role expansion, halo effects, a fourth EDA density tier, toolkit injection and EDA/IDE product scope remain deferred proposals; no new canonical token scope is approved by this follow-through.

## Native geometry follow-through

The [eight actual local-workspace cases](../../qualification/results/P1-S2/2026-10-16-native-window-geometry.md) now establish sampled narrow/short reachability at font scales1.3/2.0 on unchanged0.1.6 products. Sixteen Light viewports were inspected, with retained draft/window identity and restored app/system settings. The selected Compose semantics discrepancy and corrected line-geometry assertions are explicitly recorded with both failed attempts. This narrows the remaining geometry work; it does not establish scaledDark, physical input, accessibility or other components. No palette replacement, fixed compact height or global font reduction was needed.


## Profile-manager follow-through

The [profile geometry review and repair](../../qualification/results/P1-S2/2026-10-16-profile-geometry.md) now turns two source risks into observed failures and verified fixes. Console/Desktop0.1.7 reveal a selected lower-list editor and let the complete frozen confirmation text scroll while actions remain visible. Six actual geometry cases and sixteen profile/lifecycle regressions pass; both products preserve data and system settings. This addresses readable desktop interaction at existing text roles and densities. The feedback's deferred palette, halo, toolkit-injection and EDA product proposals retain their earlier disposition; full P1-S2 remains open.


## Native credential-entry follow-through

The [0.1.9 SSH entry](../../qualification/results/P1-S3/2026-10-16-ssh-authentication-ui.md) reuses existing Cloudy typography and control metrics with native username/password editing. Credential retirement also disposes field undo state; sampled framework Ctrl+Z/Ctrl+Shift+Z cannot restore cleared fields after success/cancellation. This applies desktop editing together with credential lifetime requirements, without disabling native selection/composition while typing. These are actual framework input/lifecycle cases, not physical keyboard, IME, visual/accessibility or full P1-S2 acceptance. No palette, density or scope decision changes.
