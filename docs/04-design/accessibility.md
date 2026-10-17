# Accessibility and visual verification

Target accessible desktop use in both apps and the web specimen. [WCAG 2.2](https://www.w3.org/TR/WCAG22/) supplies web criteria; Android semantics and Googlebook input tests also need native verification. This document defines goals and checks, not a conformance certification.

## Contrast and color

For ordinary text, target at least **4.5:1** against its actual surface. Large text and meaningful non-text UI have applicable **3:1** criteria. Use color with text/icon/shape for status, errors, selection, and focus. Low-contrast subtle dividers are decorative only; use the control-outline role where a boundary is needed to recognize an input.

The checker computes sRGB relative luminance and contrast for declared token pairs in both schemes. Pair definitions include primary/secondary text on supported neutral layers, filled primary labels, selection labels, semantic statuses, outlines/focus, and terminal normal ANSI colors. Passing this check does not certify arbitrary overlays, opacity effects, remote truecolor, screenshots, or future component states.

Inspect selected/hover/pressed states, menus over content, banners, disabled explanations, placeholders, and focus offsets in the real layout. Focus must remain visible when selected and when an adjacent surface changes. A 2-unit ring plus offset is the initial project treatment; verify its contrast and visibility at actual scale.

## Keyboard and assistive technology

All primary workflows must be possible without dragging or hover. Keep logical traversal order, named controls, selected/expanded/checked state, dialog labeling, and focus restoration. Do not announce every terminal byte as a live-region event. Provide renderer-supported accessible navigation/output and test the actual Android screen reader with real TUIs.

Preserve Ctrl+C interrupt and IME composition. Give users a way out of remote/terminal input capture. Native media surfaces expose surrounding controls and current context through semantics even though streamed host content itself is not made locally accessible by the video pixels.

## Target size and density

Comfortable mode favors 48-unit native touch targets. Precise-pointer modes use explicit visual targets and spacing; compact controls do not gain overlapping invisible hit regions. WCAG's minimum target-size criterion and exceptions are a separate web assessment, not a reason to ignore the platform's touch ergonomics. Provide keyboard alternatives for pane resizing and drag/drop.

## Text, language, and reflow

Test 100%, 125%, 150%, and 200% user text scaling where supported, plus browser zoom for the specimen. Chrome reflows; terminal text remains a meaningful two-dimensional grid with independent zoom/scroll. Essential host/device context and error actions must remain reachable. CJK/RTL/path/emoji tests include long identifiers, combining marks, and wide cells.

Do not encode a state solely by text weight, location, or color. Use full labels with readable units. Label synthetic and estimated values. Avoid time-limited dialogs for recoverable trust/permission decisions.

## Motion and sensory behavior

Honor reduced motion; remove nonessential translations, pulses, and animated theme transitions. Do not loop decorative animation or flash. Offer quiet notification defaults and per-channel controls; visual completion does not require sound. Avoid changes that unexpectedly steal focus when background events arrive.

## Acceptance matrix

| Check | Web specimen | Native app gate |
| --- | --- | --- |
| Token pair contrast | Automated checker | Same token values + rendered state review |
| Keyboard traversal/palette/dialog | Browser interaction review | Physical keyboard + system shortcut conflicts |
| Screen reader | Semantic structure inspection | TalkBack with terminal/stream controls |
| Scaling and overflow | Small/large widths, zoom | Googlebook dp/sp scaling + external display |
| Touch/trackpad/mouse | Browser visual approximation | Separate physical input runs |
| IME and CJK | Text samples only | Exact committed-byte fixtures + candidate placement |
| Reduced motion | CSS preference | Native accessibility setting |

Record evidence through [Q-A01](../06-validation/test-cases.md). Do not promote browser verification to native or hardware proof.
