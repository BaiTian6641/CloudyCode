# ADR-0002: Extension registry and licensing strategy for the GBCode workbench

**Status:** Accepted
**Date:** 2026-10-16
**Supersedes:** none
**Related:** [ADR-0001-platform-boundaries](ADR-0001-platform-boundaries.md) · `../../versions.lock.json` · [Upstream review 2026-10-16](../research/2026-10-16-upstream-review.md) · [S01.01](../plan/S01-workbench-vertical-slice.md#s0101)

## Context

GBCode embeds an independently branded workbench built from Code - OSS source, served inside the Googlebook Debian Terminal VM by one of two MIT-licensed backend candidates locked in `versions.lock.json`:

- **code-server v4.141.0** (Coder, MIT), repo commit `8b41915d25547e2edd553bab03ea4cbd621f98c5`, embedding Code - OSS `2a59476c9bfcb90b3ddc372c36762471b7dfad1c` (1.141.0, Node 24.18.0 per `.nvmrc`).
- **OpenVSCode Server v1.109.5** (Gitpod, MIT), repo commit `4ffe2270acdf711bbefecc3e8c79f4b3631640e5`, tracking upstream `1.109.5` = `072586267e68ece9a47aa43f8c108e0dcbf44622`.

Both server projects are MIT-licensed and build from MIT-licensed Code - OSS source. The Microsoft **product** binaries (desktop and the Visual Studio Code remote server) are distributed under a proprietary license that restricts use to the Visual Studio product family and **prohibits combining the software with applications for others to use**; the Visual Studio Marketplace Terms likewise forbid alternative/fork products from accessing the Marketplace. The APK is distributed by us, not Microsoft, so neither entitlement applies to GBCode.

## Decision

### D1 — Permitted extension registry: Open VSX; self-hosted Open VSX allowed

The only configured extension registry for GBCode is **Open VSX** (`open-vsx.org`) — vendor-neutral, operated under EPL-2.0 registry tooling that may also be **self-hosted** for offline/controlled deployments. The gallery endpoint in both candidates' product configuration MUST point at Open VSX (code-server already defaults to it) and never at `marketplace.visualstudio.com`.

### D2 — User-supplied VSIX is a first-class path

Users MAY install extensions from local `.vsix` files through the standard *Extensions: Install from VSIX* command. This is a normal distribution channel, not a workaround for registry restrictions. Each VSIX remains subject to its own license (D4).

### D3 — No Microsoft Marketplace access, ever

GBCode MUST NOT access, proxy, scrape, or bundle the Visual Studio Marketplace, and MUST NOT ship Microsoft/affiliate extensions acquired from it. Microsoft/affiliate proprietary extensions (Remote-WSL/SSH/Containers, Live Share, Pylance, Copilot backends, Microsoft Settings Sync, proprietary debuggers/AI services) are not licensed for use outside the Visual Studio product family. Absence of such extensions must have a clear normal failure path in the UI; no silent substitution of similarly named extensions. Product branding, name, and icon MUST be original; Microsoft brand guidelines forbid using the VS Code identity for other products (upstream review E21–E23).

### D4 — Extension licenses remain per-artifact

Open VSX hosting or MIT workbench source does NOT relicense any extension. Availability, publisher identity, version, target architecture, `engines.vscode` compatibility, and the artifact's own license MUST be checked per extension artifact before it becomes part of any starter profile or documented supported workflow. Where an independently open-licensed extension source exists, building it ourselves with permitted service access is a separate, artifact-specific decision — neither a blanket ban nor a blanket approval.

### D5 — Independent branding and attribution

- Original product name and icon; no Visual Studio Code marks, icons, or names implying endorsement.
- Preserve MIT copyright and permission notices from Code - OSS, code-server/OpenVSCode Server, and all bundled third-party components in an about/notices surface; retain notices and an SBOM for each release bundle.

### D6 — Server licensing: MIT servers only; Microsoft proprietary server excluded

Only the MIT-licensed servers (code-server, OpenVSCode Server, or a directly built Code - OSS server) are eligible backends. The Microsoft Visual Studio Code Server (including anything downloaded via the upstream CLI's `serve_web` machinery) MUST NOT be repackaged or shipped in the APK baseline: its license permits use only "with Microsoft Visual Studio Code" and prohibits combining it with applications for others to use, regardless of the MIT licensing of the CLI source that can download it (upstream review E23, E24).

## Candidate patch/license audit obligations

Both candidates are MIT, but MIT covers their source, not automatically the behavior of their patches. Per-candidate obligations carried into S01 (evidence from the upstream review):

**code-server v4.141.0** (ordered patch series at tag: marketplace, proposed-API, webview, proxy, storage, clipboard, telemetry, signature-verification, Copilot changes — E14):

- **Webview patch (E12):** serves webviews from the same origin and adds matching-`hostname` shortcuts around parent-origin validation. Before production: audit sandbox/CSP, service-worker scope, cookie scope, and ensure the APK grants extension frames no access to the main-frame native bridge.
- **Remote secret-storage patch (E13):** permits persisted storage **without encryption** when the encryption service is unavailable (paired with `--enable-remote-storage`). The exact deployed SecretStorage protection, key storage, and recovery on the guest are UNKNOWN; do not assume Android Keystore or a guest keyring. An explicit storage/security design is required before enabling remote storage.
- Product-identity/build overrides exist outside the patch series (`ci/build/build-vscode.sh` sets its own commit as `BUILD_SOURCEVERSION`, trusted auth access, TypeScript plugin placement — E11); they must be inventoried with the patches.
- The resolved embedded upstream commit is the `lib/vscode` gitlink, never the About-dialog commit (E09).

**OpenVSCode Server v1.109.5:**

- Verify its minimal-patch claim against the actual diff to upstream `07258626…` (merge base confirmed; ancestry ≠ complete audit — E10): inventory any product/build overrides needed for proxy, origin, branding, and authentication.
- Authentication via `--connection-token`/`--connection-token-file` is required in every launch configuration; the unauthenticated Docker default is prohibited.
- Release freshness (2026-02-20, embedding 1.109.5) is a recorded maintenance concern, not an automatic rejection; recheck supported Node/dependency baseline before adoption.

**Both:** every unavoidable project patch records owner, upstream issue/PR, exact base, rationale, touched behavior, acceptance evidence, and removal condition. Extension-host placement regressions are realistic (code-server 4.141.0 fixed one itself — E08); the shared conformance extension (S01.04) detects wrong-host execution.

## Consequences

- Both candidates remain evaluable under the same licensing strategy; backend choice (S01.06) is on functional/maintenance/security grounds, not licensing differences.
- Offline operation requires the self-hosted Open VSX option or pre-provisioned VSIX; core workflows must not depend on registry reachability.
- Any future desire for Microsoft-proprietary extensions is a documented blocker with an explicit license-rights resolution — never a Marketplace integration.

## Verification performed (2026-10-16)

All four release artifacts listed in `versions.lock.json` were downloaded and their SHA-256 digests recomputed locally, matching the digests published via the GitHub releases API (`api.github.com/repos/{coder/code-server,vscode-server tags}` asset `digest` fields). code-server v4.141.0 publishes **no separate `.sha256` sidecar assets** (asset listing verified), so the GitHub API digest plus local re-hash are the two checks recorded there. See `versions.lock.json` → `digestMethod` and the appendix below for exact commands/URLs.

## Appendix A — Digest provenance

| Artifact | Digest source | Command (run 2026-10-16) |
|---|---|---|
| code-server-4.141.0-linux-amd64.tar.gz | GitHub API `GET https://api.github.com/repos/coder/code-server/releases/tags/v4.141.0` → assets[].digest `sha256:65bcd20b…`; confirmed by `curl -sL -o … <asset-url> && sha256sum` | same URL as lock file `candidates.code-server.artifacts.linux-amd64.url` |
| code-server-4.141.0-linux-arm64.tar.gz | same API call → `sha256:e5cc78da…`; confirmed by local `sha256sum` | lock file `.linux-arm64.url` |
| openvscode-server-v1.109.5-linux-x64.tar.gz | GitHub API `GET https://api.github.com/repos/gitpod-io/openvscode-server/releases/tags/openvscode-server-v1.109.5` → `sha256:b433bf4f…`; confirmed by local `sha256sum` | lock file `candidates.openvscode-server.artifacts.linux-amd64.url` |
| openvscode-server-v1.109.5-linux-arm64.tar.gz | same API call → `sha256:36d9c140…`; confirmed by local `sha256sum` | lock file `.linux-arm64.url` |

Source-commit identities were taken from the upstream review (E06–E10): code-server `.gitmodules`/`lib` gitlink at tag, upstream `1.109.5` tag ref, and the fork `compare` merge-base result. No hash in this ADR or the lock file was invented; none is null.
