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

## Files

- `U03-notebook-cdn-error.webp` — the CDN fetch failure in the notebook cell area.
- `U03-notebook-opened.webp`, `U03-notebook-cells.webp` — extension active, notebook editor open, kernel picker present.
