# Cloudy Code APK CI

Cloudy Code (`dev.gbcode.app`, project `android/app`) has its own workflow:
`.github/workflows/cloudy-code-apk.yml`. It calls the shared
`.github/workflows/_cloudy-apk-build.yml` implementation.

## Behavior

- `pull_request`, push to `main`, and manual dispatch.
- Manual input `mode`: `debug` (default) or `release`.
- PR/push/manual-debug builds use only the runner debug certificate and need no
  release credentials.
- Release mode is accepted only from `refs/heads/main` and only when all
  `CLOUDY_CODE_*` signing secrets are present.
- Concurrency cancels obsolete debug/PR runs; release runs are preserved.
- No path filters: the Gradle project, workflow and shared helper changes form
  one build boundary.

## Build facts

| Item | Value |
|---|---|
| Module/project | `android/app`, `:app` |
| Package | `dev.gbcode.app` |
| Variant | debug / release |
| Gradle | checked-in wrapper 9.8.1 |
| JDK | Temurin 21 |
| AGP | 9.4.1 |
| compile/target SDK | 36 |
| min SDK | 33 |
| Build Tools | 36.0.0 |
| Native engine | not packaged in this APK |
| Native ABI | none (bytecode-only APK; artifact label `universal`) |
| Version | `0.1.0` / `versionCode` 1 |

Cloudy Code is independent of `android/cloudy-x11`; it does not build
`magicdesk-x11` and does not consume the X11 core library. Keep the application
IDs and signing identities separate.

## Local commands

```sh
cd android/app
./gradlew --no-daemon :app:lintDebug :app:testDebugUnitTest
./gradlew --no-daemon :app:assembleDebug
```

Unsigned release output for the workflow:

```
android/app/app/build/outputs/apk/release/app-release-unsigned.apk
```

Inspect it with:

```sh
BT="$ANDROID_SDK_ROOT/build-tools/36.0.0"
"$BT/aapt2" dump badging android/app/app/build/outputs/apk/debug/app-debug.apk
"$BT/apksigner" verify --verbose --print-certs android/app/app/build/outputs/apk/debug/app-debug.apk
python3 tools/ci/verify-apk.py \
  --apk android/app/app/build/outputs/apk/debug/app-debug.apk \
  --package dev.gbcode.app --app-name "Cloudy Code" \
  --variant debug --mode debug \
  --aapt2 "$BT/aapt2" --apksigner "$BT/apksigner" \
  --metadata-out /tmp/cloudy-code-metadata.json
```

## Artifacts and signing

Artifact naming:

```
cloudy-code-<debug|release>-universal-<short-sha>-run<run-id>-attempt<attempt>
```

The artifact contains the APK, `SHA256SUMS`, and `build-metadata.json`; retention
is 14 days. Failure reports are a separate 7-day diagnostics artifact.

Cloudy Code release secrets are deliberately separate from Cloudy-X11 and
GBCode:

- `CLOUDY_CODE_KEYSTORE_BASE64`
- `CLOUDY_CODE_KEYSTORE_PASSWORD`
- `CLOUDY_CODE_KEY_ALIAS`
- `CLOUDY_CODE_KEY_PASSWORD`

Optional expected identity variable: `CLOUDY_CODE_CERT_SHA256`.

No Cloudy Code release identity is established in the repository. Release mode
fails clearly until these are configured; do not substitute the GBCode key or
the runner debug key. Debug APK signing identities are runner-generated and not
guaranteed stable across CI runs or update-compatible with installed builds.

See `docs/ci/cloudy-x11-apk.md` for shared workflow, artifact verification,
cache/toolchain and manual-dispatch details.
