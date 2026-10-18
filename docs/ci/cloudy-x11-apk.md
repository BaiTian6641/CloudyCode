# Cloudy-X11 and Cloudy Code APK CI

The repository builds both independent Android apps on GitHub Actions:

- Cloudy-X11: `dev.cloudy.x11` in `android/cloudy-x11`
- Cloudy Code: `dev.gbcode.app` in `android/app`

They remain independent Gradle projects. Cloudy-X11 uses the embedded
`magicdesk-x11` engine; Cloudy Code is the Android/WebView shell and does not
package the X11 native engine.

## Workflows and triggers

| Workflow | App | Triggers |
|---|---|---|
| `.github/workflows/cloudy-x11-apk.yml` | Cloudy-X11 | `pull_request`, `push` to `main`, `workflow_dispatch` |
| `.github/workflows/cloudy-code-apk.yml` | Cloudy Code | `pull_request`, `push` to `main`, `workflow_dispatch` |

Both call the shared implementation
`.github/workflows/_cloudy-apk-build.yml`. There are no path filters because the
shared Gradle, native lock, setup scripts, generated assets and workflow files
form one boundary.

Manual dispatch has one input:

- `mode`: `debug` (default) or `release`.

Pull requests and ordinary pushes always select `debug`. They never reference
release signing credentials. Debug jobs require only `contents: read`.

A signed release request is accepted only from `refs/heads/main`. It must be
started with `workflow_dispatch` and provide all four signing secrets; otherwise
the job fails before building. Missing/partial credentials never fall back to a
debug signature or an unsigned APK.

GitHub requires a workflow to exist on the default branch before
`workflow_dispatch` is available. Land the workflow on `main` first, then use a
PR/branch run for initial validation.

Concurrency cancels obsolete PR/debug runs on the same ref. Manual release runs
are not cancelled.

## Actual toolchain and native pins

| Item | Value |
|---|---|
| Gradle wrapper | 9.8.1, distribution SHA-256 `dce76f55f8e251a3a1f130eb120f30b3d271de2b76c9b0729d316b5a1b6dc01f` |
| JDK | Temurin 21 |
| AGP | 9.4.1 |
| compileSdk / targetSdk | 36 / 36 |
| minSdk | 33 |
| Build Tools | 36.0.0 |
| Android SDK packages | `platforms;android-36`, `build-tools;36.0.0`; native jobs add `ndk;27.3.13750724` and `cmake;3.22.1` |
| NDK | 27.3.13750724 |
| CMake | 3.22.1 |
| Engine | `magicdesk-x11` `65c3ced09a26d0df15085c72010af242cd660a94`, GPL-3.0, 16 submodules |
| Native ABI | `arm64-v8a` only |
| Cloudy-X11 version | `0.1.0` (`versionCode` 1) |
| Cloudy Code version | `0.1.0` (`versionCode` 1) |

The checked-in wrapper uses the official Gradle distribution checksum and
`gradle/actions/setup-gradle` wrapper validation. The setup-gradle action is the
single Gradle cache owner; `setup-java` caching is intentionally not enabled.

The engine stage is **embedded native integration**, not the historical
xpra/HTML5 prototype. `prepare-magicdesk-engine.sh` clones the exact locked
revision, updates its recursive submodules, builds the `LorieNative` static
libraries with NDK 27.3/API 34, checks every library required by the JNI link,
and exposes the build to Gradle. The app's `preBuild` invokes
`tools/device/build-x11-jni.sh`; the final APK must contain:

- `lib/arm64-v8a/libcloudy_x11_native.so`
- `assets/xkb.zip`
- `assets/cloudy-x11-setup.sh`

Cloudy Code does not build the engine and must not be described as containing
it. Its APK is bytecode-only/universal for the current sources.

## Local build and checks

### Cloudy-X11

```sh
export ANDROID_SDK_ROOT="$HOME/Android/Sdk"
export ANDROID_NDK_HOME="$ANDROID_SDK_ROOT/ndk/27.3.13750724"
export ENGINE_CHECKOUT="$PWD/upstream/checkouts/magicdesk-x11"
export ENGINE_BUILD="$ENGINE_CHECKOUT/build-ci"

bash tools/ci/prepare-magicdesk-engine.sh

cd android/cloudy-x11
./gradlew --no-daemon :core:assembleDebug :core:lintDebug \
  :app:lintDebug :app:testDebugUnitTest
./gradlew --no-daemon :app:assembleDebug
```

The debug APK is:

```
android/cloudy-x11/app/build/outputs/apk/debug/app-debug.apk
```

### Cloudy Code

```sh
cd android/app
./gradlew --no-daemon :app:lintDebug :app:testDebugUnitTest
./gradlew --no-daemon :app:assembleDebug
```

The debug APK is:

```
android/app/app/build/outputs/apk/debug/app-debug.apk
```

### Inspect an APK

```sh
BT="$ANDROID_SDK_ROOT/build-tools/36.0.0"
"$BT/aapt2" dump badging path/to/app.apk
"$BT/apksigner" verify --verbose --print-certs path/to/app.apk
python3 tools/ci/verify-apk.py \
  --apk path/to/app.apk --package dev.cloudy.x11 \
  --app-name Cloudy-X11 --variant debug --mode debug \
  --aapt2 "$BT/aapt2" --apksigner "$BT/apksigner" \
  --expected-abi arm64-v8a \
  --require-native-lib lib/arm64-v8a/libcloudy_x11_native.so \
  --require-asset assets/xkb.zip \
  --require-asset assets/cloudy-x11-setup.sh \
  --metadata-out /tmp/build-metadata.json
```

For Cloudy Code use `--package dev.gbcode.app` and omit the native/ABI/asset
requirements.

The existing 16 KB gate is:

```sh
bash tools/device/verify-16kb.sh \
  android/cloudy-x11/app/build/outputs/apk/debug/app-debug.apk \
  android/cloudy-x11/app/src/main/jniLibs/arm64-v8a/libcloudy_x11_native.so
```

## Artifacts

Each successful job uploads one artifact containing only:

- `<app>-<mode>-<abi>-<short-sha>-run<run-id>-attempt<attempt>.apk`
- `SHA256SUMS` (APK and `build-metadata.json`)
- `build-metadata.json`

Examples:

- `cloudy-x11-debug-arm64-v8a-abc12345-run123456789-attempt1`
- `cloudy-code-debug-universal-abc12345-run123456789-attempt1`

Download from the run's **Artifacts** section, then verify:

```sh
sha256sum -c SHA256SUMS
apksigner verify --verbose --print-certs <artifact>.apk
```

Artifact retention is 14 days. Failure diagnostics (lint/test reports and
Gradle output logs) are uploaded separately for 7 days and are not combined
with the deliverable artifact.

`build-metadata.json` records the checked-out full commit, triggering ref and
event, run ID/attempt, PR head SHA when present, app/package/variant/version/SDK,
actual packaged ABIs, implementation stage, engine pin, toolchain versions,
native packaging status, APK filename/hash and signer certificate SHA-256.

## Release signing

No Cloudy-X11 or Cloudy Code release identity is committed to this repository.
Do not use the GBCode release key or infer a key from a debug certificate.
Signed release mode is intentionally unconfigured until the following are set
in the repository/environment.

Repository secrets:

| Secret | Purpose |
|---|---|
| `CLOUDY_X11_KEYSTORE_BASE64` | Base64 of the existing Cloudy-X11 release keystore |
| `CLOUDY_X11_KEYSTORE_PASSWORD` | Keystore password |
| `CLOUDY_X11_KEY_ALIAS` | Existing key alias |
| `CLOUDY_X11_KEY_PASSWORD` | Key password |

Cloudy Code uses the same four names with a `CLOUDY_CODE_` prefix. Configure a
separate identity; never reuse the GBCode release key without explicit owner
approval.

Optional non-secret repository variables:

- `CLOUDY_X11_CERT_SHA256`
- `CLOUDY_CODE_CERT_SHA256`

When set, the final release APK’s signer SHA-256 must match. When unset, the
actual fingerprint is recorded in metadata but the intended identity cannot be
enforced automatically.

Release flow:

1. Build `assembleRelease` as an unsigned APK.
2. `zipalign -P 16 -f 4` the unsigned APK.
3. Sign with the provided keystore using `apksigner`, with passwords passed via
   environment variable names (`env:`), not command-line values.
4. Verify the final signed file and expected certificate.
5. Delete temporary keystore material and the temporary aligned file in all
   outcomes.

The release job fails clearly if any required secret is absent, or if the
request is made from anything other than `refs/heads/main`. It does not upload
an unsigned or debug-signed APK as a release. The current version policy is not
changed by CI; bump `versionCode`/`versionName` before publishing a new release.

## Debug signing and installation continuity

Debug APKs use the Android debug keystore generated by the runner. Fresh
GitHub-hosted runners can have different debug certificates. A CI debug APK is
not guaranteed to update an installed release-signed build, another runner’s
debug build, or a historical debug build. Do not change the application ID,
uninstall automatically, or label a signature mismatch as a build failure of
the pipeline. Uninstall/reinstall is an explicit device operator decision.

## Common failures

- **Engine clone/submodule failure:** network or a submodule URL; the pin and
  recursive update are mandatory. Never replace the pin with a branch tip.
- **CMake/NDK/SDK failure:** verify `cmake;3.22.1`, `ndk;27.3.13750724`,
  `platforms;android-36`, and `build-tools;36.0.0` installed by setup-android.
- **Missing native library:** the engine target or JNI link failed; inspect the
  prepare step and `build-x11-jni.sh`, then rerun.
- **Lint failure:** current lint is a gate; reports are in failure diagnostics.
- **APK missing:** check the variant path
  `app/build/outputs/apk/<debug|release>/`; release is
  `app-release-unsigned.apk` before signing.
- **Signature mismatch:** confirm the intended secret identity and expected
  certificate variable; a different key cannot update an existing install.
- **Artifact upload failure:** `if-no-files-found: error` means the APK was not
  staged; inspect the verification step instead of uploading a wildcard.

## Validation status

Local validation completed for both apps: Gradle wrappers, lint and unit-test
tasks, debug/release assembly, APK inspection, debug signature verification,
throwaway-key release signing, and 16 KB checks. GitHub Actions execution and
the real release key are not configured in this checkout.

This pipeline proves build/package/signature identity. X11 forwarding,
independent freeform windows, Cloudy Code runtime behavior and physical
Googlebook acceptance remain separate runtime gates.
