// GBCode S01.04 conformance extension (plain CommonJS, no build step).
// Proves: workspace extension-host execution, child_process in the remote host,
// webview with strict CSP + local resource, tree view, output channel, and
// Version 0.2.0 adds a server-side trigger bridge: JSON files dropped into
// /tmp/gbcode-slice-triggers/ are executed as workbench actions (terminal
// creation, marker commands, reports). This mirrors the production companion
// extension pattern: guest-side logic drives real workbench APIs while the
// APK renders the result. Trigger dir is host-local, not a network surface.
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

  // --- Server-side trigger bridge (vertical-slice automation) ---
  const triggerDir = path.join(os.tmpdir(), "gbcode-slice-triggers");
  const resultDir = path.join(os.tmpdir(), "gbcode-slice-results");
  fs.mkdirSync(triggerDir, { recursive: true });
  fs.mkdirSync(resultDir, { recursive: true });
  output.appendLine("watching triggers in " + triggerDir);

  async function handleTrigger(file) {
    let trigger;
    try {
      trigger = JSON.parse(fs.readFileSync(path.join(triggerDir, file), "utf8"));
    } catch (e) {
      output.appendLine("bad trigger " + file + ": " + e.message);
      return;
    }
    const result = { trigger, ts: new Date().toISOString(), ok: false, detail: null };
    try {
      if (trigger.action === "terminal-cmd") {
        // Use the workbench command (same path as user palette) instead of the
        // raw createTerminal API, whose lazy pty spawn stalls in web servers.
        const target = trigger.target || path.join(os.tmpdir(), "gbcode-cmd.txt");
        const marker = trigger.marker || "GBCODE_CMD";
        await vscode.commands.executeCommand("workbench.action.terminal.new");
        const term = vscode.window.activeTerminal;
        if (!term) throw new Error("no activeTerminal after terminal.new");
        let transcript = "";
        const dataSub = vscode.window.onDidWriteTerminalData
            ? vscode.window.onDidWriteTerminalData((e) => {
                if (e.terminal === term) transcript += e.data;
              })
            : { dispose() {} };
        let pid = "pending";
        term.processId.then((p) => { pid = String(p); }, () => { pid = "reject"; });
        setTimeout(() => term.sendText(`echo ${marker} > ${target}`, true), 4000);
        const deadline = Date.now() + 15000;
        let written = null;
        while (Date.now() < deadline) {
          await new Promise((r) => setTimeout(r, 500));
          if (fs.existsSync(target)) {
            written = fs.readFileSync(target, "utf8").trim();
            if (written === marker) break;
          }
        }
        dataSub.dispose();
        result.detail = {
          pid,
          written,
          transcript: transcript.replace(/\x1b\[[0-9;?]*[a-zA-Z]/g, "").slice(-400),
        };
        result.ok = written === marker;
      } else if (trigger.action === "terminal-debug") {
        const term = vscode.window.createTerminal({ name: "GBCode Debug" });
        term.show(false);
        let pidResolved = "pending";
        term.processId.then(
          (p) => { pidResolved = String(p); },
          (e) => { pidResolved = "reject:" + e.message; }
        );
        let transcript = "";
        const dataSub = vscode.window.onDidWriteTerminalData
            ? vscode.window.onDidWriteTerminalData((e) => {
                if (e.terminal === term) transcript += e.data;
              })
            : { dispose() {} };
        const target = trigger.target || path.join(os.tmpdir(), "gbcode-dbg.txt");
        const marker = trigger.marker || "GBCODE_DBG";
        setTimeout(() => term.sendText(`echo ${marker} > ${target}`, true), 3000);
        const deadline = Date.now() + 12000;
        let written = null;
        while (Date.now() < deadline) {
          await new Promise((r) => setTimeout(r, 500));
          if (fs.existsSync(target)) {
            written = fs.readFileSync(target, "utf8").trim();
            if (written === marker) break;
          }
        }
        dataSub.dispose();
        result.detail = {
          pidResolved,
          written,
          transcript: transcript.replace(/\x1b\[[0-9;?]*[a-zA-Z]/g, "").slice(-500),
        };
        result.ok = written === marker;
      } else if (trigger.action === "terminal-mark") {
        let transcript = "";
        const dataSub = vscode.window.onDidWriteTerminalData
            ? vscode.window.onDidWriteTerminalData((e) => {
                if (e.terminal === term) transcript += e.data;
              })
            : { dispose() {} };
        // Shell startup lags; sendText queues input, so poll for the side effect.
        setTimeout(() => term.sendText(`echo ${marker} > ${target}`, true), 2500);
        const deadline = Date.now() + 15000;
        let written = null;
        while (Date.now() < deadline) {
          await new Promise((r) => setTimeout(r, 500));
          if (fs.existsSync(target)) {
            written = fs.readFileSync(target, "utf8").trim();
            if (written === marker) break;
          }
        }
        dataSub.dispose();
        result.detail = {
          terminals: vscode.window.terminals.length,
          termPid,
          written,
          transcript: transcript.replace(/\x1b\[[0-9;?]*[a-zA-Z]/g, "").slice(-600),
        };
        result.ok = written === marker;
      } else if (trigger.action === "report") {
        await vscode.commands.executeCommand("gbcodeConformance.report");
        result.ok = true;
        result.detail = "report command executed";
      } else if (trigger.action === "state") {
        result.ok = true;
        result.detail = {
          terminals: vscode.window.terminals.map((t) => t.name),
          remoteName: vscode.env.remoteName || null,
          appHost: vscode.env.appHost,
        };
      } else if (trigger.action === "workspace-write") {
        const folder = (vscode.workspace.workspaceFolders || [])[0];
        if (!folder) throw new Error("no workspace folder open");
        const rel = trigger.path || "gbcode-probe.txt";
        const content = trigger.content || "gbcode-write-" + Date.now();
        const uri = vscode.Uri.joinPath(folder.uri, rel);
        await vscode.workspace.fs.writeFile(uri, Buffer.from(content, "utf8"));
        const readBack = Buffer.from(await vscode.workspace.fs.readFile(uri)).toString("utf8");
        result.ok = readBack === content;
        result.detail = { uri: uri.toString(), bytes: content.length, readBackMatches: result.ok };
      } else if (trigger.action === "workspace-read") {
        const folder = (vscode.workspace.workspaceFolders || [])[0];
        if (!folder) throw new Error("no workspace folder open");
        const uri = vscode.Uri.joinPath(folder.uri, trigger.path || "gbcode-probe.txt");
        const data = Buffer.from(await vscode.workspace.fs.readFile(uri)).toString("utf8");
        result.ok = true;
        result.detail = { uri: uri.toString(), bytes: data.length, content: data.slice(0, 100) };
      } else {
        result.detail = "unknown action";
      }
    } catch (e) {
      result.detail = "ERROR: " + e.message;
    }
    fs.writeFileSync(
      path.join(resultDir, file.replace(/\.json$/, "") + "-result.json"),
      JSON.stringify(result, null, 2)
    );
    output.appendLine("trigger " + file + " ok=" + result.ok);
  }

  // Poll instead of fs.watch: directory watchers are unreliable across
  // delete/recreate cycles and some filesystems. 2s scan, processed-set guard.
  const processed = new Set();
  setInterval(() => {
    let files;
    try {
      files = fs.readdirSync(triggerDir);
    } catch {
      return;
    }
    for (const file of files) {
      if (file.endsWith(".json") && !file.endsWith("-result.json") && !processed.has(file)) {
        processed.add(file);
        handleTrigger(file);
      }
    }
  }, 2000).unref();

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
