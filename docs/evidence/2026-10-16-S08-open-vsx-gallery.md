# S08.01 Open VSX gallery — findings (2026-10-16, budget-limited lane)

**Decisive finding:** pristine Code - OSS ships `extensionGallery: null` — no extension registry works out of the box. GBCode must configure the gallery explicitly (ADR-0002 already mandates Open VSX/VSIX). Applying the Open VSX config to the control's `product.json` (cached tree, disposable):

```json
"extensionGallery": {
  "serviceUrl": "https://open-vsx.org/vscode/gallery",
  "itemUrl": "https://open-vsx.org/vscode/item",
  "resourceUrlTemplate": "https://open-vsx.org/vscode/unpkg/{publisher}/{name}/{version}/{path}",
  "controlUrl": ""
}
```

Open VSX API verified reachable (search + download URLs return real artifacts, e.g. Catppuccin VSIX). The gallery config must ship in GBCode's own product configuration (not a runtime patch).

**Fixture note (unresolved, non-blocking):** driving the Extensions search box via synthetic typing drops the first keystroke into the widget's focus accelerator (`catppuccin` → `atppuccin`). Leading junk-then-backspace did not fully recover in this lane; production tests should type through the palette/`workbench.extensions.action.installExtension` command or use the registry API directly for headless verification. The Extensions view itself opens correctly (palette path proven); search UX on real input hardware is a physical-lane check.

**code-server note:** code-server configures Open VSX by default (FAQ E05), so candidate packaging handles this; the finding applies to the pristine control and to GBCode's own product config (S11).


**VSIX lane PASS (2026-10-16, control restart):** downloaded `Catppuccin.catppuccin-vsc-3.19.0.vsix` from Open VSX (SHA-256 `ebf34766…29780`), installed via `code-server-oss --install-extension`, and after restart the theme picker lists all four variants; applying "Catppuccin Mocha" changed the workbench background `rgb(250,250,253)` → `rgb(17,17,27)` (`docs/evidence/S08-catppuccin-mocha-applied.webp`). User-selected extension install → activate → apply works on the pristine control.