# Design implementation and visual acceptance for workers

**User requirement, 2026-10-16:** smaller models must implement the established Cloudy language carefully and perform visual checks. Applies to Googlebook client UI and any future Windows/Linux/macOS host enrollment, status, settings or helper UI. A headless core command does not need an invented screen merely to produce a screenshot.

## The worker's design packet

Provide the exact screen/component/state, current source base, writable paths, [canonical tokens](../../shared/design-system/tokens.json), relevant [layout](layout.md)/[component](components.md)/[interaction](interaction.md) contracts, accepted [feedback disposition](2026-10-16-external-feedback-review.md#current-disposition-at-520a0b9), and a reviewed reference image when useful. Include actual supported actions, long/error/empty states, native window/input constraints and acceptance dimensions. Host UI is a small permission/status surface unless a reviewed task requires more; do not copy the full remote-workspace shell into a tray dialog.

Workers reuse existing components, semantic colors, typography, spacing, density and icon conventions. Do not invent palettes, gradients, decorative cards, navigation, a fourth micro-density tier, compressed fixed-height text or mobile interaction. Original Word feedback is review material, not authority to build an EDA/IDE or restyle remote applications. Preserve OS caption/menu conventions, IME, font scaling, terminal ANSI colors, meaningful host/protocol context and permission ownership.

For remote sessions read [fullscreen and overlay behavior](remote-session-presentation.md). Video stays on its native surface. Ordinary local controls stay readable above changing remote content without routing video through a UI bridge. A visual reference may use synthetic data; production actions must execute real operations or show an honest unavailable state.

## Visual evidence and acceptance

1. Compile the actual changed UI target before capturing it. Label an HTML specimen or static layout as such; it cannot stand in for a native product screenshot.
2. Record source/artifact hash, OS/runtime, screen/state, actual viewport/window bounds, appearance, density, scaling and screenshot hash. Use synthetic nonprivate accounts/projects for external model packets. Supply only approved images/reference excerpts, never unrelated screen contents or raw private transcripts.
3. Inspect the rendered image, not just a successful capture exit or screenshot filename. Check information hierarchy, alignment, spacing, text overflow, action visibility, selection/focus/disabled/error states, overlay contrast and remote working area. Describe observable pixels and location. Hidden content, performance and event behavior are unknown from a still image.
4. Exercise changed interactions: keyboard-only navigation, pointer/right-click, local-versus-remote shortcuts, focus return, native text/IME, scale/reflow and accessibility semantics. Physical capture/release, assistive speech and OS prompts need their own actual observations. An accessibility-tree pass is useful evidence, not a screen-reader speech pass.
5. Fix observed defects, re-render affected cases and have a reviewer other than the author inspect the final source/images. Parent accepts or records explicit remaining limits. Do not add a global redesign or re-run unrelated device cases to repair one component.

The minimum matrix is proportional to the surface: both appearances; supported density modes where applied; default and large text; normal and narrow/short windows; idle, focus/selected, busy, failure/permission and confirmation states affected by the change. Existing unchanged captures can be reused. A relevant obscured action, focus trap, unreadable state, ambiguous host, lost input-release path or silent no-op blocks acceptance.

## Model image routing

Exact OMP `kimi-code/k3` and `zai/glm-5.3-flash` catalogs advertise image input. Both have completed one finite single-image observation task through OMP's `@image` attachment mechanism with zero tools and actual matching routes. The parent inspected the source PNG and both final outputs. Kimi correctly identified the selected Console view; Flash missed that control and overclaimed footer clipping. The latter claim is rejected. See the [record](../06-validation/2026-10-16-parallel-host-workers.md).

Use both models for bounded screenshot observations with review. This is not tool-enabled autonomous browser/device qualification or a promise of perfect visual judgment. Text-only tasks cannot claim image inspection. If a route fails to receive an image, return that limitation; direct Codex GPT-6.1 Sol and parent image review remain available. Preserve actual provider/model, attachment hash/encoding, questions, final findings and accepted/rejected observations. Do not publish raw reasoning/event streams.
