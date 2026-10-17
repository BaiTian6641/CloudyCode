# Cloudy Code — Code - OSS for Googlebook

An independently branded desktop IDE for Google's Android-based Googlebook: the original Code - OSS workbench in an Android APK, with terminals, Git, language servers, extensions, compilers and debuggers running in Google's built-in Debian Terminal VM.

**Status: working prototype, verified on an HP Googlebook 14 (Android 17) and the emulator; not yet a certified product.** The APK boots the app-owned Code - OSS workbench, auto-detects and attaches to a backend, reconnects with Cloudy overlays, and ships its own Open VSX extension gallery. The device lane currently uses code-server; the pinned Code - OSS arm64 backend build is pending. USB forwarding is deferred (see Blockers).

Cloudy Code is the product name; repository and historical plan documents keep the internal GBCode identifier. Not a Microsoft or Google product or an approved trademark.

## What exists and is proven

- **`android/app/`** (Cloudy Code) — production shell: onboarding → pairing (ed25519, one-time secret, **verified enrollment receipt**) → Keystore-persisted identity → **authenticated reconnect (per-session daemon proof)** → in-app origin gateway (loopback-only upstream, Host/Origin authorization) → native backend login (full cookie attributes) → **the real Code - OSS workbench, packaged in the APK and served from app-owned assets** (ADR-0006; boots offline with no guest and no network — palette, Settings, untitled editor verified), **with its own Open VSX extension gallery (the packaged OSS product has none), backend auto-detection/attach and reconnect overlays, and connected to the guest remote agent over the gateway** (workspace Node extension host, guest file service, integrated terminals, language servers, extension webviews — verified on the emulator against a pristine `reh-web` guest of the same commit); on launch it probes the forwarded backend ports and connects automatically, and when none is running it shows the one-time setup page (install code-server in the Terminal) and continues by itself once the server appears. The preinstalled/privileged path can start the VM command directly (`MANAGE_TERMINAL`); sideloaded builds cannot — verified on a Googlebook 14. Credentials are Keystore-encrypted at rest and excluded from backup.
- **`guest/sessiond/`** — guest session daemon: pairing with signed enrollment receipts, sessions with per-session server proofs, framed WebSocket channel, device registry with exclusive leases. 27 tests (protocol, authz, receipt/proof, channel auth, vectors, fuzz) green.
- **`guest/gateway/`** — Node reference gateway (HTTP/WS proxy, origin rewrite). 5 tests green.
- **`guest/bootstrap/`** — transparent, checksum-verified guest installer with user-service/foreground paths and clean uninstall.
- **`guest/doctor/`** — read-only capability-record emitter (kernel, VHCI, CDC, systemd-user) for the VM.
- **`extensions/conformance/`** — workbench conformance fixture (webview CSP, tree, output, terminal, workspace.fs authority, trigger bridge).
- **Backends pinned and built:** code-server 4.141.0 and OpenVSCode 1.109.5 verified digests (`versions.lock.json`); pristine Code - OSS 1.141.0 `reh-web` built from source for x64. The cached arm64 tree still has x64 native addons, so the in-VM arm64 build remains a task.
- **Shared folders:** Googlebook Terminal → Settings → Shared folders mounts Android folders into the VM. Open the mount in Cloudy Code with File → Open Folder (full IDE on Googlebook storage). The app cannot add shares itself — the Terminal app owns that API.
- **Evidence:** `docs/evidence/` — network/WebView/storage probes, parity, notebook (incl. the CDN-renderer finding), debugging, language-server-in-backend, performance, adversarial review.

`tools/verify.sh` runs every green suite in one command.

## Plan and decisions

- [Detailed implementation stages](docs/plan/README.md) — 16 stages, 104 work-package IDs, explicit dependencies, acceptance gates.
- [Architecture specification](docs/Googlebook_CodeOSS_APK_Engineering_Plan_v2.md) — v2.1.
- [Agent operating rules](AGENTS.md) — emulator-first, subagent review, device ownership constraint, no sed for code edits.
- ADRs in `docs/decisions/` (platform boundaries, registry/licensing, exporter candidates, backend selection).
- Research in `docs/research/` (platform, upstream, hardware — all dated 2026-10-16).

## Blockers

The physical Googlebook (HP Googlebook 14, "quartz", Android 17) is available and was exercised: install/boot, app-owned workbench, code-server lane, combined setup, extension catalog, reconnect/auto-attach, and the shared-folder flow. Remaining gates:

- **USB forwarding — deferred.** The shipped Terminal APK's USB passthrough is stubbed (empty `attachAllUsbDevices`, hotplug receiver never registered) and the VM kernel lacks `vhci_hcd`; USB/IP and serial bridges stay behind the experimental `--with-usb` flag.
- **Pinned Code - OSS arm64 backend:** build in an arm64 host/VM (cached arm64 native addons are x64).
- **Physical Terminal-VM certification:** the VM route was exercised with a workstation guest and the Terminal app's port forwarding; formal device certification and the hardware matrix remain.
- **Desktop input/windowing:** observed on the device, not certified.
- **Release gates:** signing/update channel and the dependent S01.06/S10.06/S11 gates.

## Not the product (boundaries)

No Termux, no app-owned VM, no cloud execution, no Monaco clone, no streamed desktop, no Electron port, no Microsoft Marketplace/brand/proprietary services. Extension sources are Open VSX + user-authorized VSIX. USB forwarding is currently deferred (platform passthrough stubbed; no VHCI in the VM kernel); the serial/USB-IP lanes are opt-in experiments.
