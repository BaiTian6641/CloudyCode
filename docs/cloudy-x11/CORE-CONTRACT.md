# Cloudy-X11 core and remote-console contract

Status: core transport/engine/AIDL extracted to the Android library module
`android/cloudy-x11/core`; app depends on it. The native `libcloudy_x11_native.so`
and XKB asset remain packaged by the app. VM scripts/setup and the remote-console
contract are tracked. Cloudy Code pairing was verified via the backend
environment (C2.4).

## Modules

- `cloudy-x11-core` (Android library): authenticated 14500 tunnel client,
  O/D/C stream protocol, engine JNI wrapper, output-to-XID mapping, and the
  per-window host contract.
- `cloudy-x11-app`: the standalone `dev.cloudy.x11` UI, setup, overlay/task
  policy and Android lifecycle.
- `cloudy-x11-vm`: VM display proxy, Unix tunnel socket, 14500 bridge and
  environment installer.

The reusable core must not import `dev.gbcode.app`, GBCode Workbench, or
Cloudy Code classes.

## Transport contract

- App connects to the existing 14500 token proxy; no new VM TCP bind.
- `/x11-tunnel` Upgrade carries one authenticated tunnel; config contains
  display, X cookie, max frame/streams.
- O/D/C frames: `O sid`, `D sid length payload`, `C sid`.
- VM proxy serializes frame writes, replaces a stale app tunnel, and closes
  only streams owned by the replaced connection.
- The app engine closes all host tasks on tunnel loss; a new engine may
  reconnect and rehost X clients.

## Window contract

- One mapped parentless application/unclassified X window -> one free-form
  Android task; role-3 dialogs are admitted separately.
- Host owns a Surface; engine binds one nonzero output per XID.
- Unmap -> host finishes and clears the XID/output map; manual host close
  clears the map.
- Pointer/hardware key/text forwarding through the core AIDL/JNI path.
- Soft IME and per-XID OS caption are known platform blockers; see TODO.

## Remote-console reuse

A future remote-console app consumes the same core API: authenticate to 14500,
open the X11 tunnel, start a per-window host, and map each XID to one task. It
must not depend on Cloudy-X11's UI or setup Activity.

## Packaging

- Publish the core as an Android library with a versioned transport ABI and
  token/identity ownership separate from the app.
- Ship the VM scripts with the GPLv3 corresponding source and setup checksum.
- Keep the 16 KB ELF/APK gate and no-xpra/no-weston default path.
