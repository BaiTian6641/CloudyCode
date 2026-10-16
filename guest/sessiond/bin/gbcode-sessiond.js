#!/usr/bin/env node
// gbcode-sessiond CLI: bootstrap identity, open enrollment, serve.
"use strict";

const path = require("path");
const os = require("os");
const { SessionDaemon } = require("../src/server");

const args = process.argv.slice(2);
function arg(name, fallback) {
  const i = args.indexOf("--" + name);
  return i >= 0 && args[i + 1] ? args[i + 1] : fallback;
}

const bind = arg("bind", "127.0.0.1");
const port = Number(arg("port", "9123"));
const stateDir = arg("state-dir", path.join(os.homedir(), ".config", "gbcode"));

const daemon = new SessionDaemon({ stateDir });
const server = daemon.createServer();
server.listen(port, bind, () => {
  console.log(`gbcode-sessiond 0.1.0`);
  console.log(`bind: http://${bind}:${port}`);
  console.log(`server fingerprint (verify in the app): ${daemon.fingerprintHex}`);
  if (args.includes("--enroll")) {
    const secret = daemon.openEnrollment();
    console.log(`enrollment open for 10 minutes; one-time pairing secret: ${secret}`);
  }
});
