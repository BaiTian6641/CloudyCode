# S03 storage semantics probe — emulator lane (2026-10-16)

**Fixture:** `android/probes/storage-probe` (SAF `ACTION_OPEN_DOCUMENT_TREE` + persisted grant + automated semantics suite). Environment: emulator (API 36), ExternalStorage documents provider, `content://com.android.externalstorage.documents/tree/primary%3ADocuments`.

## Suite results — all PASS

| Test | Result | Note |
|---|---|---|
| Tree grant + persistable permission | PASS | `takePersistableUriPermission` held across force-stop + restart (suite reran on persisted grant without picker) |
| mkdir in tree | PASS | `gbcode-probe-<ts>` |
| create/write/read | PASS | byte-exact roundtrip |
| `.gbcode-space-id` sentinel | PASS | random 16-byte space ID write/read — the architecture's workspace-identity pattern works over SAF |
| Unicode rename | PASS | `héllo Wörld 世界.txt` with content intact |
| Case siblings (`Case.txt`/`case.txt`) | PASS | both created, distinct contents preserved (this provider is case-sensitive) |
| Nested directory | PASS with semantic note | `createDirectory("nested/deep")` produced a directory literally named **`nested_deep`** — SAF document names do not treat `/` as a separator |
| delete / recursive cleanup | PASS | test tree removed |

## Findings for S03 design

1. **SAF document names are not paths**: separator characters are sanitized (`nested/deep` → `nested_deep`). GBCode's SAF-side code must never construct paths by joining document names with `/`; use child-document traversal (DocumentsContract) and keep the Linux-side path mapping purely guest-side (S03.02).
2. The dual-grant model works as designed: SAF grant persists across process death (matches plan §7.1); the Terminal-side grant remains the physical-lane counterpart (blocked).
3. Case-sensitivity is provider-dependent; the FS-02 matrix must record per-provider behavior rather than assuming POSIX semantics (this provider: case-sensitive; Googlebook Terminal mount: physical lane).
4. Sentinel + nonce proof can run entirely over SAF: production onboarding should do APK write → guest read → guest write → APK read; the APK half is proven here.

## Not established here

Terminal VM mount semantics (physical lane, blocked), watcher/inotify behavior over the mount, executable bits/symlinks (SAF does not model them; guest-side test required), large-tree performance, Googlebook DocumentsUI differences from AVD picker.
