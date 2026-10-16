// GBCode S01.04 conformance extension (plain CommonJS, no build step).
// Proves: workspace extension-host execution, child_process in the remote host,
// webview with strict CSP + local resource, tree view, output channel, and
// machine-readable report for the selected backend.
"use strict";

const vscode = require("vscode");
const cp = require("child_process");
const os = require("os");
const fs = require("fs");
const path = require("path");
const crypto = require("crypto");

function gatherReport(context) {
  const exec = (cmd) => {
    try {
      return cp.execSync(cmd, { encoding: "utf8", timeout: 5000 }).trim();
    } catch (e) {
      return "ERROR: " + e.message;
    }
  };
  return {
    marker: "GBCODE-CONFORMANCE-0.1",
    ts: new Date().toISOString(),
    extensionHost: {
      extensionKind: context.extensionKind === vscode.ExtensionKind.Workspace ? "workspace" : "ui",
      remoteName: vscode.env.remoteName || null,
      appName: vscode.env.appName,
      appHost: vscode.env.appHost,
      uiKind: vscode.env.uiKind === vscode.UIKind.Desktop ? "desktop" : "web",
      node: process.version,
      arch: process.arch,
      platform: process.platform,
      pid: process.pid,
    },
    host: {
      uname: exec("uname -a"),
      osRelease: exec("head -1 /etc/os-release 2>/dev/null"),
      shell: process.env.SHELL || null,
      home: os.homedir(),
    },
    workspace: {
      folders: (vscode.workspace.workspaceFolders || []).map((f) => f.uri.toString()),
      isTrusted: vscode.workspace.isTrusted,
    },
    product: (() => {
      try {
        const p = JSON.parse(fs.readFileSync(path.join(vscode.env.appRoot, "product.json"), "utf8"));
        return { nameShort: p.nameShort, version: p.version, commit: p.commit };
      } catch (e) {
        return { error: e.message, appRoot: vscode.env.appRoot };
      }
    })(),
  };
}

function showReportWebview(context, report, nonce) {
  const panel = vscode.window.createWebviewPanel(
    "gbcodeConformance",
    "GBCode Conformance",
    vscode.ViewColumn.Beside,
    { enableScripts: true, localResourceRoots: [vscode.Uri.joinPath(context.extensionUri, "media")] }
  );
  const codiconUri = panel.webview.asWebviewUri(
    vscode.Uri.joinPath(context.extensionUri, "media", "marker.css")
  );
  panel.webview.html = `<!doctype html>
<html><head>
<meta http-equiv="Content-Security-Policy"
      content="default-src 'none'; style-src ${panel.webview.cspSource} 'nonce-${nonce}'; script-src 'nonce-${nonce}';">
<style nonce="${nonce}">body{font-family:monospace;padding:1em} .ok{color:green}</style>
<link rel="stylesheet" href="${codiconUri}">
</head><body>
<h1 class="gbcode-marker">GBCode conformance webview</h1>
<p class="ok">CSP nonce + local resource loaded; message roundtrip below.</p>
<pre id="report">${JSON.stringify(report, null, 2).replace(/</g, "&lt;")}</pre>
<script nonce="${nonce}">
  const vscode = acquireVsCodeApi();
  window.addEventListener('message', (e) => {
    document.getElementById('report').textContent += '\\nwebview-echo: ' + e.data;
  });
  vscode.postMessage('webview-ready');
</script>
</body></html>`;
  panel.webview.onDidReceiveMessage((msg) => {
    if (msg === "webview-ready") {
      panel.webview.postMessage("host-ack");
    }
  });
  return panel;
}

/** @param {vscode.ExtensionContext} context */
function activate(context) {
  const output = vscode.window.createOutputChannel("GBCode Conformance");
  output.appendLine("gbcode-conformance activated");

  const treeProvider = new (class {
    getTreeItem() {
      const item = new vscode.TreeItem("Run conformance report");
      item.command = { command: "gbcodeConformance.report", title: "Run" };
      return item;
    }
    getChildren() {
      return [undefined];
    }
  })();
  vscode.window.registerTreeDataProvider("gbcodeConformance.status", treeProvider);

  context.subscriptions.push(
    vscode.commands.registerCommand("gbcodeConformance.report", async () => {
      const report = gatherReport(context);
      const nonce = crypto.randomBytes(16).toString("base64");
      output.appendLine(JSON.stringify(report));
      output.show(true);
      showReportWebview(context, report, nonce);
      const outPath = path.join(os.tmpdir(), "gbcode-conformance-report.json");
      fs.writeFileSync(outPath, JSON.stringify(report, null, 2));
      vscode.window.showInformationMessage("GBCode conformance report written to " + outPath);
    })
  );
}

function deactivate() {}

module.exports = { activate, deactivate };
