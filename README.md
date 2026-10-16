# GBCode — Code - OSS for Googlebook

An independently branded desktop IDE for Google's Android-based Googlebook: the original Code - OSS workbench in an Android APK, with terminals, Git, language servers, extensions, compilers and debuggers running in Google's built-in Debian Terminal VM.

**Status: working prototype with verified evidence, not yet a device-certified product.** The production APK shell runs on the emulator and pairs with a guest daemon, drives the real workbench through an in-app gateway, and persists pairing across process death. Physical Googlebook certification is pending device availability (see Blockers).

GBCode is a working name, not a Microsoft or Google product or an approved trademark.

## What exists and is proven

- **`android/app/`** — production shell: onboarding → pairing (ed25519, one-time secret) → Keystore-persisted identity → in-app origin gateway → native backend login → the real Code - OSS workbench in a WebView. Verified on the emulator against code-server and the pristine upstream 1.141.0 control.
- **`guest/sessiond/`** — guest session daemon: pairing, sessions, framed WebSocket channel, device registry with exclusive leases. 22 tests (protocol, authz, vectors, fuzz) green.
- **`guest/gateway/`** — Node reference gateway (HTTP/WS proxy, origin rewrite). 5 tests green.
- **`guest/bootstrap/`** — transparent, checksum-verified guest installer with user-service/foreground paths and clean uninstall.
- **`guest/doctor/`** — read-only capability-record emitter (kernel, VHCI, CDC, systemd-user) for the VM.
- **`extensions/conformance/`** — workbench conformance fixture (webview CSP, tree, output, terminal, workspace.fs authority, trigger bridge).
- **Backends pinned and built:** code-server 4.141.0 and OpenVSCode 1.109.5 verified digests (`versions.lock.json`); pristine Code - OSS 1.141.0 `reh-web` built from source for x64 and arm64 (`tools/release/`).
- **Evidence:** `docs/evidence/` — network/WebView/storage probes, parity, notebook (incl. the CDN-renderer finding), debugging, language-server-in-backend, performance, adversarial review.

`tools/verify.sh` runs every green suite in one command.

## Plan and decisions

- [Detailed implementation stages](docs/plan/README.md) — 16 stages, 104 work-package IDs, explicit dependencies, acceptance gates.
- [Architecture specification](docs/Googlebook_CodeOSS_APK_Engineering_Plan_v2.md) — v2.1.
- [Agent operating rules](AGENTS.md) — emulator-first, subagent review, device ownership constraint, no sed for code edits.
- ADRs in `docs/decisions/` (platform boundaries, registry/licensing, exporter candidates, backend selection).
- Research in `docs/research/` (platform, upstream, hardware — all dated 2026-10-16).

## Blockers

The physical Googlebook is currently assigned to another project (see AGENTS.md). The following are blocked until it's released: Terminal-VM network route (S00.03–.06 physical), guest VHCI/kernel inventory (U00.01), USB authority and USB/IP import (U00.03–.05), ESP32-S3 serial/JTAG and external-adapter certification (U02), desktop windowing/input on real hardware (S04), and the release gates that depend on them (S01.06 final, S10.06, S11).

## Not the product (boundaries)

No Termux, no app-owned VM, no cloud execution, no Monaco clone, no streamed desktop, no Electron port, no Microsoft Marketplace/brand/proprietary services. Extension sources are Open VSX + user-authorized VSIX. USB/IP hardware path is default but gated on actual guest VHCI support.
