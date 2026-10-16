# SBOM / license inventory — current artifact set (2026-10-16)

**Scope:** what GBCode currently ships or runs. Generated for S11.01; regenerated at each release. This is an inventory, not legal advice; each entry's license text lives with its artifact.

## APK (`android/app`)

| Component | Version | License | Source |
|---|---|---|---|
| AndroidX WebKit | 1.14.0 | Apache-2.0 | maven.google.com |
| AndroidX DocumentFile | 1.1.0 | Apache-2.0 | maven.google.com |
| AndroidX test runner / junit (test-only) | 1.6.2 / 1.2.1 | Apache-2.0 | maven.google.com |
| GBCode app code (Kotlin/Java, incl. Ed25519, WsClient, SessionClient, Gateway, NativeBridge) | 0.1.0 | project license (this repo) | this repo |

## Backend bundles (guest)

| Component | Version | License | Source |
|---|---|---|---|
| code-server (candidate) | 4.141.0 | MIT | github.com/coder/code-server release artifacts (digest-pinned in versions.lock.json) |
| OpenVSCode Server (fallback candidate) | 1.109.5 | MIT | github.com/gitpod-io/openvscode-server release artifacts (digest-pinned) |
| Pristine Code - OSS reh-web control | 1.141.0 @ 2a59476c | MIT | github.com/microsoft/vscode, built from source (overlay 0001: no Copilot) |
| Bundled Node (inside each backend) | 24.21.0 / 22.21.1 | MIT | bundled by the respective backend |

## Guest tooling

| Component | Version | License | Source |
|---|---|---|---|
| gbcode-sessiond | 0.1.0 | project license | this repo (zero npm deps) |
| gbcode-gateway (reference) | 0.1.0 | project license | this repo (zero deps) |

## Extensions (installed during evaluation)

| Extension | Version | License | Source |
|---|---|---|---|
| gbcode-conformance (fixture) | 0.3.0 | project license | this repo |
| Catppuccin for VSCode | 3.19.0 | MIT | Open VSX |
| Jupyter | 2025.9.1 | MIT | Open VSX |
| Jupyter Notebook Renderers | 1.3.0 | MIT | Open VSX |
| Pyrefly | 1.4.0 | MIT | Open VSX |

## Explicitly excluded

- **Microsoft Visual Studio Marketplace** and any extension artifact obtained from it (ADR-0002).
- **Microsoft's Copilot extension and SDK** (proprietary; excluded from the control build by overlay 0001; not shipped).
- Any `product.json`-embedded Microsoft service entitlements (Settings Sync, account-based features) — not present in the OSS source and not added.

## Obligations tracking

- MIT/Apache notices: preserved in each backend's ThirdPartyNotices / LICENSE inside the bundle; the GBCode release must carry them through.
- `versions.lock.json` pins every backend artifact digest; extension artifacts record their SHA-256 in `docs/evidence/` entries.
- Rebuild rule: any version change re-runs this inventory and the conformance/parity suites.
