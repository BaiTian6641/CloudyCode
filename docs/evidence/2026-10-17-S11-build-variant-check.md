# S11 pre-signing build verification (2026-10-17)

Checks the current APK variants before the S11.02 signing workflow. Development-only; no signing keys or production artifacts were produced.

## Builds

| Command | Result |
|---|---|
| `gradle assembleDebug` | BUILD SUCCESSFUL — `app-debug.apk` 3,903,846 bytes |
| `gradle assembleRelease` (online; the offline run failed only because `lint-gradle:32.4.1` was not cached) | BUILD SUCCESSFUL — `app-release-unsigned.apk` 3,204,615 bytes; lint vitals ran |

## Debug-only diagnostic isolation

`WorkbenchActivity` enables `WebView.setWebContentsDebuggingEnabled(true)` inside `if (BuildConfig.DEBUG)`.

| Artifact | `setWebContentsDebuggingEnabled` references across all `classes*.dex` |
|---|---|
| debug | **1** (`classes6.dex`) — hook compiled in, used for CDP diagnostics |
| release (unsigned) | **0** — the `BuildConfig.DEBUG` branch is constant-folded out of the release build |

So the release artifact contains no WebView-inspection hook, and the debug lane keeps the diagnostic path.

## Artifact notes

- The release APK is **unsigned**: no `signingConfig` is declared, by design. Production signing belongs to the restricted S11.02 workflow with human approval (goal boundary: production signing pauses for approval). Do not install an unsigned release as a product artifact.
- No `lib/` entries in the APK (dependencies are Java/Kotlin AndroidX), so the 16 KB native page-size check does not apply to this artifact; it applies to native components and guest bundles (U01/S11.02).
- Not covered here: ABI splits/universal packaging policy, reproducible-build variance, SBOM hashes for the APK contents (S11.01), install/replay on a fresh device (S11.04).
