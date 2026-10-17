# Workspace home design revision — 16 October 2026

**Authority:** user direction, 2026-10-16. **State:** approved design direction for the next native milestones; no implementation, device result or capability promotion is claimed by this document. It refines [screen specifications](screen-specs.md) S01/S06/S15 and amends the [desktop terminal interaction contract](desktop-terminal-interaction-contract.md) and [interaction rules](interaction.md).

## Decisions

| Decision | Resolution |
| --- | --- |
| Product home | Termius-style host-centric home is the default window content in **both** apps from one shared Compose shell; Termius remains a UX reference, not an implementation or embedding API. |
| Scope | Console home covers SSH hosts with saved passwords/SSH keys via this app's vault; Desktop home covers RDP/VNC/Stream protocol profiles. Apps, stores and credentials stay independent per Rule 0 and ADR 0001. |
| Terminal input defaults | MobaXterm pointer model: copy-on-select, right-click paste, Shift+Insert paste, Ctrl+wheel zoom. Right-click has a persisted per-app toggle back to the context menu. Ctrl+Shift+C/V, Ctrl+C terminal semantics, IME composition, remote-mouse routing (Shift+drag local override) and one-recipient focus ownership are unchanged. |
| Development surfaces | Existing diagnostic/debug tools remain intact and reachable under the home's Advanced section; they are replaced only by a later explicit approval. |
| Design vehicle | Native-first: the home is built and reviewed in the real Compose modules with rendered evidence per the [worker visual acceptance contract](worker-visual-acceptance.md). The HTML specimen stays a historical study. |
| X11 | The bounded P0-S7 broker experiment proceeds per the [experiment packet](../03-platform/googlebook/p0-s7-x11-broker-experiment.md); S14's separate-window design is unchanged and checked against its outcome. |

## What changes in the specifications

- **S01/S06** become the real default window content: Favorites/Recents/All hosts navigation, tag-derived groups, host rows with protocol, host:port, favorite marker and actual last-used time, and a detail pane with endpoint, fingerprint state and this app's credential-reference state.
- **Connect** routes only to qualified operations. Before their native stages land, SSH terminal, RDP and Stream show an honest not-integrated state beside the affected host; VNC keeps its existing qualified native entry. No fabricated session, metric or transcript.
- **Credential references** are resolved against the owning app's vault (P1-S3 remains in progress); an unqualified vault operation surfaces a typed unavailable state, never a fake list or invented save.
- **Home data** requires the profile metadata extension (tags, favorite, last-used) with a schema-v2 migration of the existing per-app profile store; existing rows preserve identity and revision semantics.

## Boundaries

- Canonical tokens, density tiers, typography and the Carbon × M3 synthesis are unchanged; the home uses existing roles and components per [foundations](foundations.md) and [components](components.md).
- Parsec/TeamViewer-style references apply only to connected sessions; pre-connection navigation is Cloudy's own design.
- This revision does not qualify vault save/use, terminal rendering, RDP/streaming, X11 or USB behavior; each keeps its own stage gate and device evidence requirements.
- The user paused the full P0–P6 goal on 2026-10-16 and later resumed this UX lane explicitly; progress is reported as verified gates, never calendar estimates.

## 2026-10-17 — Console desktop visual refinement

The user reaffirmed that Cloudy Console should look and feel similar to **Termius**, adapted to
a **desktop experience and the Cloudy Design Language**. This refines visual hierarchy, not the
platform, dependency, authentication or release contracts.

The parent inspected the [current official overview](https://termius.com/) and its
[desktop workspace image](https://framerusercontent.com/images/5adSItOENNTFRDEwDBUq6GIETU.jpg?width=3081&height=1827):
stable side navigation, compact top actions, organized host groups and clear primary/secondary
host information. Adopt those organizational patterns with Cloudy's semantic colors, typography,
density, native captions and keyboard/pointer/IME behavior. Do not copy branding/assets, invent
OS identities or add nonfunctional session tabs, team avatars or controls.

The bounded home refinement keeps real Favorites/Recents/All/tag navigation, current host/trust
and credential-reference state, prominent Connect, real secondary actions and Advanced tools.
Narrow tiled windows and large text reflow rather than shrinking into a phone or clipping actions.
The connected terminal/files/X11 journeys retain their own implementation and acceptance gates.
Source changes must pass actual native wide/narrow, long/empty/error and interaction review before
visual acceptance; an attractive source proposal is not a rendered or completed application.

