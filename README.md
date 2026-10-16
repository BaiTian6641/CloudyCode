# GBCode — Code - OSS for Googlebook

An independently branded desktop IDE for Google's Android-based Googlebook: the original Code - OSS workbench in an Android APK, with terminals, Git, language servers, extensions, compilers and debuggers running in Google's built-in Debian Terminal VM.

**Status: reviewed engineering plan, not an implemented IDE.** No APK/backend or Googlebook hardware validation is included yet. GBCode is a working name, not a Microsoft or Google product or an approved trademark.

## Start here

- [Detailed implementation stages](docs/plan/README.md) — canonical execution plan: 16 stages, 104 retained work-package IDs, explicit dependencies, checklists, deliverables, smoke scenarios and release gates.
- [Architecture specification](docs/Googlebook_CodeOSS_APK_Engineering_Plan_v2.md) — reviewed revision 2.1; original workbench, Android/VM boundaries, storage, security, USB/IP and product acceptance.
- [Googlebook and Android research](docs/research/2026-10-16-platform-review.md) — Terminal, networking, desktop behavior, storage, public APIs and release-policy evidence.
- [Upstream and extension research](docs/research/2026-10-16-upstream-review.md) — source identities, backend comparison, licenses, extension hosts, webview isolation and persistence.
- [USB/IP and hardware research](docs/research/2026-10-16-hardware-review.md) — actual kernel prerequisites, Android authorized-FD integration, exporter source gaps and ESP32-S3/external-adapter certification.

## Product boundary

Preserve the real upstream workbench and normal user-selected extension workflow. Deliver a laptop-grade edit/build/debug experience, including Git, tasks, terminals, notebooks, webviews, profiles, keyboard/IME, multiple windows, recovery and provisioned offline use. Use permitted extension registries and authorized VSIX artifacts; Code - OSS's MIT source license does not grant Microsoft branding, Marketplace access or proprietary-service rights.

The default `wsl_usbip_desktop` profile also requires native ESP32-S3 serial/flashing/JTAG with simultaneous serial/JTAG, plus an independently certified external programmer/debug adapter. USB/IP depends on the **actual built-in guest's VHCI support** and an authorized Android exporter. Serial-only bridges, a different VM, Termux or a cloud backend are not equivalent completion.

## Repository use

This is the APK/integration repository, not a vendored copy of [microsoft/vscode](https://github.com/microsoft/vscode). Backend selection and immutable source/artifact locks belong to the feasibility stages. Use an external source cache or ignored `upstream/checkouts/` when implementation starts; do not clone upstream over this repository.

Git is initialized locally on `main`. No remote or initial commit was created by the planning setup. `.gitignore` excludes build/dependency output, local signing/credential files and private device evidence; lockfiles, source patches and reviewed documentation remain trackable. `.gitattributes` supplies portable text handling. Ignore rules are a safety aid, not a substitute for reviewing secrets before committing.

**First implementation work:** S00 platform probes and U00 USB/IP feasibility, with upstream control preparation alongside them. Do not mark a device capability supported from documentation, an emulator, another VM or a successful Git setup.
