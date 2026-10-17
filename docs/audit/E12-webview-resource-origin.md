# E12 audit — code-server self-hosted webview resources (2026-10-17)

Scope: code-server 4.141.0 (`8b41915d…`, embedded upstream 1.141.0) as deployed on this workstation. Question: are webview/notebook resources served locally and safely, and does the mechanism create a new file-read or network surface?

## What was measured

| Check | Result |
|---|---|
| `vscode-cdn.net` DNS/HTTPS reachability from the host | **unresolvable** (`getent hosts` none; `curl` could not resolve) |
| Notebook renderer resources (markdown-it, jupyter-renderers, katex, mermaid) | **HTTP 200**, notebook rendered, cell executed (`docs/evidence/2026-10-16-U03-notebook-lane.md`) |
| Renderer request authority | `vscode-remote+127-…vscode-resource.vscode-cdn.net` — a virtual hostname, not the network |
| Service-worker registrations on the page | (1) scope `http://127.0.0.1:8080/` → `/_static/out/browser/serviceWorker.js`; (2) scope `…/webview/browser/pre/` → upstream webview SW with `vscode-resource-base-authority`/`remoteAuthority` params |
| Root-scope SW body (`out/browser/serviceWorker.js`) | 402-byte **PWA stub**: install/activate handlers, empty `fetch` listener, no `respondWith` — it does not intercept |
| `Service-Worker-Allowed` header on the static route | `/` (set by `out/node/routes/index.js` for paths ending `/serviceWorker.js`) |
| Direct `fetch()`/`<img>` probe of `https://…vscode-cdn.net/<path>` from the outer webview frame | request failed for every path, including a legitimate extension resource — blocked by frame CSP/content nesting, so the probe cannot establish the allowlist either way |

## Mechanism (from deployed source)

1. **Interception** — the scoped webview service worker (`lib/vscode/out/vs/workbench/contrib/webview/browser/pre/service-worker.js`) handles `fetch` only for `https:` URLs whose hostname ends with `.` + `vscode-resource-base-authority` (`vscode-resource.vscode-cdn.net`), GET/HEAD only. Hostname encodes `scheme+authority` (`vscode-remote+127-0-0-1-8080`), path/query are forwarded.
2. **Resolution** — `processResourceRequest` posts `{channel:'load-resource', id, scheme, authority, path, query, ifNoneMatch, range}` to the **outer webview client** (the webview host page, running workbench code) and waits for the reply. The host page answers with the bytes/ETag; the SW builds the Response (with cache/range handling).
3. **Path authorization** — the SW forwards `path` **verbatim** and contains no allowlist. Validation is therefore in the webview host/workbench resource service, i.e. the same `asWebviewUri`/workspace-trust rules that apply to upstream webviews.
4. **Fallbacks** — if the client cannot be resolved, or the host replies non-OK, the SW executes `fetch(t.request)` — a **real network request to the virtual hostname**.
5. Root-scope SW: registrations for PWA installability only; `Service-Worker-Allowed: /` is served by `express.static` on `/_static` (code-server `out/node/routes/index.js`).

## Assessment

- **Offline capability:** proven. With the CDN hostname unresolvable, webview resources are still served — they are proxied through the session-authenticated local server, not fetched from the internet.
- **New file-read surface:** no direct one in the SW. It is a message-passing proxy; the trusted workbench host page performs the actual resource lookup under the normal webview resource rules. A compromised webview still only reaches what the workbench grants it, and remote extension code already has full guest-filesystem access by design.
- **Cookie/origin scope:** resources are requested from the virtual `https://*.vscode-cdn.net` origin; they are answered by the SW using the workbench client's already-authenticated connection. The SW does not attach credentials itself.
- **Residual risk 1 (fail-open network fallback):** on a host where `*.vscode-resource.vscode-cdn.net` resolves (wildcard DNS) or a proxy replies, a failed/unauthorized webview request falls through to the real CDN with the **local path in the URL**. Whether Microsoft wildcard-resolves that hostname is UNKNOWN; this is a metadata-leak path, not a data-loss path.
- **Residual risk 2 (unverified allowlist):** the CSP/nesting prevented an end-to-end unauthorized-path probe from the outer frame. Live validation of "webview asks for a path outside its allowed roots" remains to be demonstrated with an instrumented build or a probe inside the inner content frame.

## Required follow-ups (S02.03 / U03.02)

1. Deny outbound DNS/HTTPS to `*.vscode-cdn.net` and `vscode-cdn.net` in the guest network policy (defense in depth; also removes residual risk 1). Record in the S02.03 network policy.
2. Instrumented probe: from the renderer content frame, request (a) an extension-root asset, (b) a workspace file via `asWebviewUri`, (c) `/etc/passwd` and an unrelated home path; record status codes and confirm (c) is refused before the network fallback (or patch the SW to fail closed).
3. Keep the APK native bridge main-frame-only (already a design rule): no webview frame may reach it, regardless of this SW.

**Status:** source + behavior audit complete (this document); live allowlist probe and fail-closed patch decision remain open.
