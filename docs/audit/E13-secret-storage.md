# E13 audit — extension secret storage on code-server 4.141.0 (2026-10-17)

Scope: code-server 4.141.0 (`8b41915d…`, embedded upstream 1.141.0), default and `--enable-remote-storage` configurations. Question: when remote storage is enabled, are `ExtensionContext.secrets` values persisted, and how?

## Method

Instrumented probe added to the conformance extension (`secret-probe` trigger, v0.3.1): `context.secrets.store(key, value)` → `context.secrets.get(key)` → compare, result written to `/tmp/gbcode-slice-results/`. Two isolated instances, each with its own `--user-data-dir`/`--extensions-dir`, loopback, `--auth none`, no TLS (test only):

| Instance | Port | Flag | Probe value |
|---|---|---|---|
| A | 8090 | `--enable-remote-storage` | `GBCODE_SECRET_MARKER_123`, key `gbcode.conformance.secret` |
| B | 8091 | (default) | `GBCODE_NO_PERSIST_789`, key `gbcode.conformance.secret.nostore2` |

## Results

| Observation | A (`--enable-remote-storage`) | B (default) |
|---|---|---|
| store→get round-trip | PASS | PASS (in-process) |
| Secret on disk | **YES** — `/tmp/gbcode-cs-secret/User/globalStorage/state.vscdb`, `ItemTable` row key `secret://{"extensionId":"gbcode.gbcode-conformance","key":"gbcode.conformance.secret"}`, value bytes = `GBCODE_SECRET_MARKER_123` | **NO** — no `secret://` row and no marker anywhere in the DB |
| DB file mode | `-rw-r--r--` (0644) | n/a |

So with remote storage on, extension secrets are **plaintext in the user data directory**; there is no encryption service in the web server, and the patch's documented fallback is exactly this path. With the flag off, secrets are memory-only and are lost on restart (they are not silently written somewhere else).

## Consequences and required decision

1. **Do not enable `--enable-remote-storage` in the default product profile without a secret-storage design.** The flag is a settings/persistence mechanism, not a secure keystore. If it is enabled for workbench state, extension secrets inherit plaintext-on-disk semantics.
2. If enabled, the guest deployment must at minimum: place the user data dir inside the workspace space with `0700` directories and `0600` files (measured default is 0644), document that DB backups/dumps contain secrets, and state that credential-bearing extensions must not be used in that profile.
3. APK-side secrets (pairing keys) stay in Android Keystore, as already implemented; they must never round-trip through this storage.
4. Unknown, not asserted: behavior when the flag is turned off after values were written (whether stale plaintext rows survive), and whether any code-server future release adds encryption. Re-test at each upgrade (ADR-0004 upgrade rule).

**Status:** E13 empirical audit complete for the pinned version; product decision (enable vs stay off) pending and recorded against S02.05 / S09 state-persistence work.
