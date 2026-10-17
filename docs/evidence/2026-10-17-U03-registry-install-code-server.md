# U03 registry installation on the selected backend — code-server (2026-10-17)

Prior extension evidence covered VSIX installation and gallery access on the pristine control (which needed explicit registry configuration). This lane proves the **registry path end-to-end on code-server 4.141.0**, which configures Open VSX by default.

## Sequence and results

| Step | Result |
|---|---|
| Open the Extensions view, search `catppuccin` | PASS — gallery results with download counts and per-row Install actions (Open VSX, configured by code-server) |
| Click Install on "Catppuccin for VSCode" | PASS — publisher-trust dialog: "Catppuccin is not verified… install anyway?" |
| Trust Publisher & Install | PASS — installed to `~/.local/share/code-server/extensions/catppuccin.catppuccin-vsc-3.19.0-universal` |
| Activation | PASS — remote exthost log: `$deltaExtensions: … Catppuccin.catppuccin-vsc …` then `ExtensionService#_doActivateExtension Catppuccin.catppuccin-vsc` |
| Theme registered and applied | PASS — picker lists all four variants; "Catppuccin Mocha" applied, workbench background `rgb(250,250,253)` → `rgb(17,17,27)` |

Screenshot: `U03-registry-install-code-server.png` (dark workbench; Installed list shows Catppuccin, GBCode Conformance, Jupyter stack, Pyrefly).

## Behavioral notes (product-relevant)

1. **Publisher trust prompt is a required step** on code-server (signature/verification patch). The product flow must expect one confirmation per publisher; it is not an error.
2. **The theme picker's live preview did not repaint** this client; the selection applied after a page reload. Cosmetic client quirk, not a data problem.
3. **Server-side `User/settings.json` does not drive the browser workbench's theme** — without `--enable-remote-storage`, user settings live in the browser profile, not the guest filesystem. This is the same storage model as E13 and matters for S09 persistence: settings/profiles survive only if the WebView profile survives, or if remote storage is enabled (with the E13 plaintext-secret caveat) or settings are exported explicitly. The APK must not treat its WebView data as disposable.

## Consequence

Success criterion 5's "extension installation (registry + VSIX)" now has both paths measured on the selected backend. U03.02 still owes the delta inventory for code-server's gallery wording ("Marketplace" label over an Open VSX backend) and S11 must pin the registry.
