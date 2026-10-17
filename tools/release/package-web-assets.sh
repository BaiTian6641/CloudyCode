#!/usr/bin/env bash
# Package the pinned Code-OSS web workbench into the APK as app-owned core
# assets (ADR-0006). The asset manifest is generated from the actual build:
# every shipped file with its SHA-256, plus the upstream revision it came from.
#
# Usage: tools/release/package-web-assets.sh [--with-builtin-extensions]
#   --with-builtin-extensions  also package the dist extensions/ tree (class C
#                              built-in web extensions; ~105 MB extra)
#
# Input : 1. the `vscode-web` dist built from the pinned source (target `web`:
#            workbench library, workers, resources, node_modules)
#         2. the page shell built from the same source (target `server-web`):
#            workbench.js + workbench.css, the upstream bootstrap the
#            workbench.html entry loads (upstream's browser shell)
#         Defaults: ~/.cache/gbcode/upstream/vscode-web and
#         ~/.cache/gbcode/upstream/vscode-1.141.0/out-vscode-shell-min
# Output: android/app/app/src/main/assets/web/            (gitignored, generated)
#         android/app/app/src/main/assets/web/asset-manifest.json
#         android/app/app/src/main/assets/web/dist-info.json
#         android/app/asset-manifest/vscode-web-manifest.json  (committed copy)
set -euo pipefail
cd "$(dirname "$0")/../.."

DIST="${GBCode_WEB_DIST:-$HOME/.cache/gbcode/upstream/vscode-web}"
SHELL_DIST="${GBCode_WEB_SHELL:-$HOME/.cache/gbcode/upstream/vscode-1.141.0/out-vscode-shell-min}"
DEST="android/app/app/src/main/assets/web"
MANIFEST_COMMITTED="android/app/asset-manifest/vscode-web-manifest.json"
INCLUDE_EXTENSIONS=0
for arg in "$@"; do
  case "$arg" in
    --with-builtin-extensions) INCLUDE_EXTENSIONS=1 ;;
    *) echo "unknown argument: $arg" >&2; exit 2 ;;
  esac
done

[ -d "$DIST/out" ] || { echo "dist not found (run the web build first): $DIST" >&2; exit 1; }
[ -f "$DIST/out/vs/code/browser/workbench/workbench.html" ] || {
  echo "dist is not a workbench dist: $DIST" >&2; exit 1; }
SHELL_OK=1
for f in workbench.js workbench.css; do
  [ -f "$SHELL_DIST/vs/code/browser/workbench/$f" ] || SHELL_OK=0
done
[ "$SHELL_OK" = "1" ] || {
  echo "page shell missing in $SHELL_DIST (run build-upstream-web.sh first)" >&2; exit 1; }

echo "=== packaging web assets from shell $SHELL_DIST (+ deps from $DIST)"
rm -rf "$DEST"
mkdir -p "$DEST/out"

# Class A (app-owned core): the upstream page shell build is the packaging base
# — its nls table matches workbench.js. Mixing the web-library build's table in
# scrambles localized strings (verified: raw {0} templates in palette/settings).
# Server-side entries are the guest's (class B) and never ship in the APK.
cp -a "$SHELL_DIST/." "$DEST/out/"
rm -f "$DEST/out/bootstrap-fork.js" "$DEST/out/server-cli.js" "$DEST/out/server-main.js"
rm -rf "$DEST/out/vs/platform/agentHost" "$DEST/out/vs/platform/files" \
       "$DEST/out/vs/platform/terminal" "$DEST/out/vs/workbench/api/node"
find "$DEST" -name '*.map' -delete
# Lazy-loaded core dependencies (xterm, katex, tree-sitter wasm, oniguruma,
# textmate) come from the web library package.
cp -a "$DIST/node_modules" "$DEST/node_modules"
for f in favicon.ico manifest.json package.json code-192.png code-512.png; do
  [ -f "$DIST/$f" ] && cp -a "$DIST/$f" "$DEST/$f"
done
# workbench.html references {{BASE}}/resources/server/*; the dist keeps these
# at its root. Provide the expected layout as well.
mkdir -p "$DEST/resources/server"
for f in favicon.ico manifest.json code-192.png code-512.png; do
  [ -f "$DEST/$f" ] && cp -a "$DEST/$f" "$DEST/resources/server/$f"
done
if [ "$INCLUDE_EXTENSIONS" = "1" ] && [ -d "$DIST/extensions" ]; then
  # Built-in web extensions (themes, icons, language basics): packaged so the
  # stock workbench is complete offline. Extension code is still class C —
  # user-installed extensions come from the registry/guest at runtime.
  cp -a "$DIST/extensions" "$DEST/extensions"
fi

echo "=== generating asset manifest"
python3 - "$DEST" "$DIST" "$SHELL_DIST" "$INCLUDE_EXTENSIONS" <<'PY'
import hashlib, json, os, sys, datetime, pathlib
dest, dist, shell, with_ext = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4] == "1"
entries = []
total = 0
for root, _dirs, files in os.walk(dest):
    for name in sorted(files):
        if name in ("asset-manifest.json", "dist-info.json"):
            continue
        p = pathlib.Path(root) / name
        rel = p.relative_to(dest).as_posix()
        data = p.read_bytes()
        entries.append({"path": rel, "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()})
        total += len(data)
entries.sort(key=lambda e: e["path"])
manifest = {
    "schemaVersion": 1,
    "assetClass": "A-core-workbench",
    "generatedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds"),
    "sourceDist": os.path.abspath(dist),
    "sourceShellDist": os.path.abspath(shell),
    "includesBuiltinExtensions": with_ext,
    "fileCount": len(entries),
    "totalBytes": total,
    "files": entries,
}
(pathlib.Path(dest) / "asset-manifest.json").write_text(json.dumps(manifest, indent=1))
info = {
    "upstreamProject": "microsoft/vscode",
    "upstreamVersion": "1.141.0",
    "upstreamCommit": "2a59476c9bfcb90b3ddc372c36762471b7dfad1c",
    "dist": "vscode-web-min",
    "shell": "out-vscode-shell-min (server-web target: workbench.js/workbench.css)",
    "includesBuiltinExtensions": with_ext,
    "fileCount": len(entries),
    "totalBytes": total,
}
(pathlib.Path(dest) / "dist-info.json").write_text(json.dumps(info, indent=1))
print(f"files={len(entries)} totalBytes={total}")
PY

mkdir -p "$(dirname "$MANIFEST_COMMITTED")"
cp "$DEST/asset-manifest.json" "$MANIFEST_COMMITTED"
echo "=== done: $DEST ($(du -sh "$DEST" | cut -f1))"
echo "    committed manifest: $MANIFEST_COMMITTED"
