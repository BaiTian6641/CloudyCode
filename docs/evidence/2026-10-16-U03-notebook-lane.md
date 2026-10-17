# U03 notebook lane — Jupyter on the pristine control (2026-10-16)

**Setup:** `ms-toolsai.jupyter` 2025.9.1 + `ms-toolsai.jupyter-renderers` 1.3.0 from Open VSX, installed into the pristine control (VSIX lane); workspace notebook `test.ipynb` (markdown + code cell).

## Results

| Check | Result |
|---|---|
| Jupyter extension installs from Open VSX and activates | PASS — walkthrough page and kernel picker render; extension contributions live |
| Notebook editor opens the .ipynb as a notebook (not raw JSON) | PASS — notebook editor + 2 cells + kernel selection present |
| **Markdown cell renderer** | **FAIL** — "Error loading renderer 'vscode.markdown-it-renderer': Failed to fetch dynamically imported module: https://vscode-resource…vscode-cdn.net/…/notebook-out/index.js" |
| Cell execution | not exercised (renderer failure blocks output verification; kernel execution lane next) |

## Production-blocking finding

**Notebook/webview resources resolve to `vscode-cdn.net` by default on pristine Code - OSS web.** On a self-contained Googlebook (and offline after provisioning) that CDN origin is unavailable or untrusted, so notebook renderers and some extension webview assets break. This is exactly the class of issue code-server's self-hosted-webview patch (upstream review E12) exists to solve — evidence FOR that patch direction (and for our gateway serving the `vscode-resource`/`webview` origins locally). S02.03/U03.02 must include: self-hosted webview resources, no CDN dependence for built-in renderers, and an offline notebook fixture.

## Code-server comparison (2026-10-17) — the failure is backend-specific

Same fixture (`test.ipynb`), same Jupyter 2025.9.1 + jupyter-renderers 1.3.0 VSIXs, installed into code-server 4.141.0 and opened in the workbench (`/tmp/gbcode-ws`).

| Check | Pristine 1.141.0 | code-server 4.141.0 |
|---|---|---|
| Markdown cell renders | **FAIL** (`Failed to fetch dynamically imported module: https://vscode-resource…vscode-cdn.net/…`) | **PASS** — H1 "GBCode notebook probe" + code cell rendered |
| `markdown-language-features/notebook-out/index.js` fetch | fails | **200 OK**, 16 ms |
| Renderer resource authority | `vscode-remote+…vscode-resource.vscode-cdn.net` (real fetch) | same authority, but served by code-server's service worker (`/_static/out/browser/serviceWorker.js` is the page's SW controller) |
| Real `vscode-cdn.net` reachability on this host | — | **DNS: no vscode-cdn.net** (`curl` → could not resolve) |

Because the CDN hostname does not resolve at all on this host, the successful 200s and the rendered notebook prove the resources were served locally by code-server's webview service worker — not fetched from the internet. This is the E12 self-hosted-webview behavior required for an offline/self-contained Googlebook, measured end-to-end.

**Consequence for ADR-0004:** this is a hard differentiator in code-server's favor (the provisional choice). The pristine control (and, by the same upstream webview path, a stock build) is not notebook-viable without its own resource-origin patch; code-server already has one. U03.02 must still audit the SW origin/scope (E12), but the offline notebook capability itself now has a passing lane.

## Cell execution (2026-10-17) — PASS

Kernel: `gbcode-python` kernelspec registered by `ipykernel` from a dedicated venv (`/tmp/gbcode-kernel-venv`), selected through the Jupyter kernel picker (interpreter discovered via `python.defaultInterpreterPath`; no Python extension required). `Execute Cell` on the code cell:

- Notebook UI: `[1]`, duration `0.0s`, kernel label `Python 3 (GBCode)`, output `gbcode notebook cell`.
- Saved file (`Ctrl+S`, read back): `execution_count: 1`, `outputs[0] = {output_type: stdout, text: ["gbcode notebook cell\n"]}`, `kernelspec.name = gbcode-python`.

So the full notebook lane — extension activation, renderer, kernel selection, real execution, output persistence — works on code-server with no internet access to the CDN origin.

## Files

- `U03-notebook-code-server-rendered.webp` — notebook rendered on code-server (H1 + code cell, no renderer error).
- `U03-notebook-code-server-executed.png` — cell executed on code-server: `[1]`, 0.0s, kernel `Python 3 (GBCode)`, stdout output.
- `U03-notebook-cdn-error.webp` — the CDN fetch failure in the notebook cell area.
- `U03-notebook-opened.webp`, `U03-notebook-cells.webp` — extension active, notebook editor open, kernel picker present.
