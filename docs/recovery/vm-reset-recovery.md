# S09.04 VM-reset recovery procedure (design; physical verification pending)

**Scope:** what survives a Terminal VM reset, and how GBCode recovers. Based on plan §7.6 and the Terminal preview's recovery model (VM reset keeps host-shared files, destroys guest-private state).

## What survives / what is lost

| Data | Survives reset? | Recovery action |
|---|---|---|
| Source in the shared project folder | YES (host filesystem) | Reopen after re-pairing; verify space ID + nonce proof |
| Guest-installed extensions, user settings, keybindings | NO (guest filesystem) | Reinstall from the signed release manifest + re-run the user-managed extension install (or reapply an exported profile) |
| Guest toolchains, caches, venvs, native modules | NO | Rebuild from the pinned profile (S08.02) |
| Pairing identity (guest server key) | NO (guest state) | Re-pair: new enrollment, new fingerprint; the app must treat a changed fingerprint as a NEW server identity and prompt the user |
| App-side identity (Ed25519 seed, Keystore) | YES (app-private) | Reuse after re-pairing the new server identity |
| Backend session cookie | NO (guest restart) | Re-authenticate natively (stored backend password, or re-prompt) |
| Unsent editor buffers | NO if the backend died before save | Honest loss reporting; the workspace write path (S03.03) confirms persistence only after the backend confirms |

## Recovery flow (post-reset)

1. Detect: the sessiond is unreachable or reports a different server fingerprint → mark the connection `UNAVAILABLE` (never silently open an empty local project).
2. Re-provision: user runs the bootstrap in the fresh VM (installs the pinned release, starts the daemon with `--enroll`).
3. Re-pair: the app shows the new fingerprint for confirmation; the old enrollment is revoked (the daemon is a fresh identity).
4. Reopen: the shared project folder re-verified via the sentinel (`.gbcode-space-id` + nonce round trip) before any write.
5. Rebuild: extensions/toolchains from the signed manifest + user profile export.

## Invariants enforced

- A changed server fingerprint NEVER silently inherits trust (re-pair is explicit).
- The workspace is never writable until the sentinel proof passes (FS-01/FS-08).
- Guest-private state is never assumed durable across resets.

**Physical-lane verification (pending device release):** perform a real VM reset on a disposable VM, then execute this flow end-to-end and confirm the shared sources survive and the guest rebuild completes from the manifest.
