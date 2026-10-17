# Workbench delta inventory — code-server 4.141.0 vs pristine Code - OSS 1.141.0

Published minimal delta list for U03.02 (fidelity contract). Scope: workbench-visible differences measured on this workstation, plus product fields read from the deployed `lib/vscode/product.json` of the pinned code-server build (`8b41915d…`). Physical-lane confirmation is still required; rows marked SOURCE are read from product/patch metadata, not yet exercised.

Classifications: **B** branding/identity, **C** cosmetic, **F** functional addition, **S** security-relevant, **+** beneficial (fixes an upstream limitation).

| # | Surface | Pristine 1.141.0 | code-server 4.141.0 | Class | Disposition for GBCode | Evidence |
|---|---|---|---|---|---|---|
| 1 | Product identity | `Code - OSS` | `code-server`, `dataFolderName .code-server`, `urlProtocol code-oss` | B | Replace via product overlay + build config (`GBCode`); do not patch behavior | product.json (SOURCE) |
| 2 | Welcome promo | none | "Deploy code-server for your team" Coder banner | C | Remove in product build | S01 screenshots, U03 parity |
| 3 | Agent/Chat sidebar | upstream Chat UI present; Copilot-dependent | agent sidebar default; `defaultChatAgent` = `GitHub.copilot`, `builtInExtensionsEnabledWithAutoUpdates: GitHub.copilot-chat` | C/S | Hide by default; Copilot is a proprietary service and stays unsupported (ADR-0002) | product.json + observed UI |
| 4 | Ports panel | absent | extra view (server port forwarding) | F | Keep as a documented addition, or hide behind product config if review requires zero additions | U03 parity |
| 5 | Extension gallery | none configured | Open VSX by default (`linkProtectionTrustedDomains` includes `open-vsx.org`) | + | Keep — matches ADR-0002 | S08, registry lane |
| 6 | Gallery wording | — | view says "Search Extensions in Marketplace" while backend is Open VSX | C | Relabel to the actual registry (product string patch) | registry lane |
| 7 | Publisher trust | signature verification upstream | modal "publisher is not verified — Trust Publisher & Install" | S | Keep; document as an expected first-install step | registry lane |
| 8 | Webview resource origin | resources fetched from `*.vscode-cdn.net` (fails offline; renderer error observed) | local service worker serves them; notebook renders with the CDN hostname unresolvable | + | Keep — required for offline/self-contained operation | notebook lane, E12 |
| 9 | `webviewContentExternalBaseUrlTemplate` | points at `https://{{uuid}}.vscode-cdn.net/insider/…` | same field remains; code-server patch sets the runtime endpoint locally | S | Audit at each upgrade (E12); add egress deny for `*.vscode-cdn.net` | product.json (SOURCE), E12 |
| 10 | Remote storage / secrets | in-memory secrets (web) | `--enable-remote-storage` persists extension secrets **plaintext** | S | Flag stays OFF by default until a secret-storage design exists | E13 |
| 11 | User settings storage | browser profile | same by default; guest `User/settings.json` does not drive the client | S/F | Define persistence in S09 (remote storage with E13 caveat, or explicit export) | registry lane |
| 12 | Telemetry | `enableTelemetry: true` | same; we run `--disable-telemetry` | S | Build default OFF; document `--disable-telemetry` in packaging | product.json (SOURCE) |
| 13 | Update check | upstream defaults | code-server update-check + store-socket patches | C/F | Disable update endpoints in product; updates are GBCode-signed (S09.03) | patch series (SOURCE) |
| 14 | Trusted extension auth access | upstream list | `github.copilot*`, `vscode.git`, `vscode.github`, PR extension | S | Prune to the extensions GBCode supports; never grant Copilot implicitly | product.json (SOURCE) |
| 15 | Proposed-API allowance | requires `--enable-proposed-api` per extension | code-server's proposed-api patch permits extension proposals | S | Keep the restriction where possible; record as an audit item (S02.03) | E12/E14 |
| 16 | Insecure-origin notification | upstream | disabled/patch-controlled (`insecure-notification.diff`) | S | Keep the warning enabled for non-loopback origins | patch series (SOURCE) |
| 17 | Terminal creation | Workspace Trust gates it | identical (trust prompt, pending `processId`) | — | No delta; onboarding must establish trust (S03.01) | trust correction |
| 18 | Notebook renderer | fails offline from CDN | works offline (row 8) | + | No action beyond row 8 | notebook lane |
| 19 | Editing/terminal/tasks/debug/LSP/SCM | — | identical stock surfaces, verified on both | — | No delta | S01, U03 lanes |
| 20 | Extension-host placement | `extensionKind: "ui"` reported for server-side Node execution in the web topology | identical | — | Measure actual placement, not the label (U03.03) | U03 control lane |

## Minimal required changes for the default product

1. **Branding/identity** (rows 1, 2, 6): product overlay + localized strings; remove Coder promo; label the gallery correctly.
2. **Agent/Chat surface** (row 3): hide by default; Copilot stays unsupported.
3. **Telemetry/update/auth lists** (rows 12–14): build defaults off, own update channel, pruned auth access.
4. **Keep the beneficial deltas** (rows 5, 8, 18) and the security prompts (7, 16); audit 9/15 per upgrade.
5. **Document, don't silently change**: Ports panel (4), storage model (10, 11), trust gating (17).

Nothing in this list requires modifying upstream workbench behavior beyond the branding/product fields and the two string/visibility decisions; the functional deltas that remain (Ports panel, local webview origin, publisher trust, proposed-API allowance) are tracked with owners in U03.02/S02.03.
