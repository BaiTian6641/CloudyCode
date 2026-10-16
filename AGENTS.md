# AGENTS.md — GBCode agent operating rules

Read [README.md](README.md), the [stage-plan index](docs/plan/README.md), and the [architecture specification](docs/Googlebook_CodeOSS_APK_Engineering_Plan_v2.md) before any work package. This file holds standing operating rules; it does not replace the stage plans' acceptance criteria.

## 1. Emulator first for testing

- Run generic tests on Google's Android emulator/AVD first: unit tests, Android instrumentation, WebView UI behavior, input/IME fixtures, and anything that does not depend on Googlebook-specific platform features.
- The available local AVD (`Medium_Phone_API_36.1`, SDK at `~/Android/Sdk`) is a phone image. It is valid for generic Android behavior only.
- The emulator **cannot** prove Googlebook Terminal VM, localhost forwarding, shared-folder mounts, USB permission behavior, guest VHCI, or lid/keyboard/trackpad specifics. Those require the ADB-attached physical Googlebook (currently: HP Googlebook 14, Android 17/SDK 37, arm64-v8a).
- Label every result `emulator` or `physical` with image/build identity. Never promote an emulator pass to a platform capability claim.
- Do not add emulator-only workarounds to release code paths; probe fixtures belong under `android/probes/` or `tests/`.

## 2. Review subagent results

- The lead (or a separate reviewer) must verify every subagent deliverable before it is used downstream: read the actual files, run its claimed checks where cheap, and confirm no claim exceeds its evidence.
- Check subagent outputs for: fabricated commands/tests/results, evidence-category inflation (DOC or SOURCE presented as DEVICE-PASS), edits outside the assigned file scope, placeholder/stub content, and unresolved conflicts with the architecture boundaries (public APIs only, built-in Terminal VM, no reverse engineering of undocumented internals).
- Reject and re-dispatch on violations; record the reason. A subagent's own "completed" status is not acceptance.
- Keep implementer and reviewer separate for security, native lifetime, guest privilege, and physical-device evidence.

## 3. Context conveyance and compression

- Subagents start with a blank context: supply the full slice requirements — goal, file targets, constraints, evidence boundaries, exact device facts, and what NOT to do. Never assume conversation history carries over.
- Point subagents at source documents by path (e.g., `docs/plan/S00-platform-feasibility.md`, `docs/research/2026-10-16-platform-review.md`) instead of pasting long excerpts; use `local://` artifacts for large payloads.
- When compressing or summarizing findings, preserve verbatim: package IDs, dependency lists, acceptance criteria, error messages, version/commit strings, and exact ADB/command output. Never paraphrase numbers, paths, identifiers, or quotes.
- Record device facts as structured evidence (JSON/JSONL), not prose memory, so later agents and later sessions can re-read ground truth without re-probing.
- After any session resume or context compaction, re-read the current task's stage file and the latest evidence files before acting; do not rely on remembered state.

## Standing constraints (from the goal and architecture)

- Official Google/Android/AOSP documentation and public SDK APIs only. No reverse engineering of undocumented internals, hidden APIs, or private services.
- Built-in Googlebook Terminal VM is the only Linux backend. No Termux, app-owned VM, cloud execution, Monaco clone, streamed desktop, or Electron port.
- Independent branding; no Microsoft Marketplace/brand/proprietary-service claims. Permitted registries and user-authorized VSIX only.
- Worker model allocation: `zai/glm-5.3-flash` for routine subagent work, `kimi-code/k3:max` for hard tasks. Verify requested selectors via `omp models find` before spawning.
- No destructive hardware operations (firmware erase/write, VM reset, eFuse changes), production signing, or publishing without explicit human approval.
- Commit each completed milestone with a meaningful message; keep `git status --short` clean between milestones.
