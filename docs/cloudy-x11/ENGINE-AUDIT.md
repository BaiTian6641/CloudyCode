# Engine API audit — pinned magicdesk-x11

**Scope:** static audit of the pinned engine before choosing Android hosting,
JNI ownership, renderer integration, or per-window policy. **Pin:**
`65c3ced09a26d0df15085c72010af242cd660a94`, NDK `27.3.13750724`, arm64-v8a,
platform android-34, GPLv3. The checkout is at
`upstream/checkouts/magicdesk-x11` (ignored); `upstream/engine.lock.json` is the
tracked pin. This audit does not claim a rendered frame; `lorie-smoke` only
proves bootstrap.

## Process and lifetime

| Fact | Pinned source |
|---|---|
| One X server per process; `started` is a process-global guard. | `cmdentrypoint.cpp`, `lorieServerStart` |
| `lorieServerStart` validates `TMPDIR` and `XKB_CONFIG_ROOT` (absolute, accessible), copies argv/callbacks, and returns `false` on invalid input. | same |
| It starts a detached pthread that calls `exit(dix_main(...))`. **Server completion exits the process.** | same |
| `ready` runs on the X server thread at ddxReady, after sockets/input exist. Only then may the host connect the renderer or stop. | `embedded.h`; `lorieEmbeddedServerReady` |
| `lorieServerStop` enqueues `GiveUp(0)` on the X thread; it is not an in-process restart. | `cmdentrypoint.cpp` |
| `lorieServerAcceptClient(fd)` consumes an already connected X11 stream, closes it if enqueue fails, and calls `AddAuthenticatedClientOnOpenFD`; normal X11 authorization remains. | `cmdentrypoint.cpp` |
| The host owns listener policy and fd delivery; the engine has no Binder, Java or platform identity. | `docs/embedding.md` |

**Decision:** host the server in a dedicated app-UID process (for example a
`:x11` process), supervised by a session service. Do not host it in the
launcher/Activity process. A private process name is not
`isolatedProcess=true`; run as the app UID. Activities own presentation and
send commands through the service; they never own the X server lifetime.

**Start preconditions:** stage XKB in app-private storage; set `TMPDIR` and
`XKB_CONFIG_ROOT`; start with `-noreset -nolisten tcp -auth <file>` (no `-ac`).
`lorie-smoke` evidence used this model under the app UID; adb-shell UID was
blocked by SELinux socket creation.

## Renderer and threads

| Symbol | Pinned contract |
|---|---|
| `lorieServerConnect()` | Returns a new renderer socket; caller owns it; replaces the previous renderer connection. Call only after `ready`. |
| `lorieConnectionCreate(callbacks, context, graphics)` | Creates one opaque connection on a prepared Android Looper. |
| `lorieConnectionConnect(connection, ownedDescriptor)` | Connects the renderer; the connection owns the descriptor. |
| `lorieConnectionSurface(connection, output, ANativeWindow*, release)` | Borrows the Surface; the renderer retains its reference and acknowledges replacement. |
| Commands | `lorieOutputBind`, `lorieOutputBindDependents`, `lorieOutputResize`, pointer/scroll/touch/tablet/key/text/focus/blur/release, `lorieObserveWindows`, `lorieCloseWindow`, `lorieSetScreenDpi`. |
| Threading | Create/connect/command/surface/destroy calls are serialized on the creating Looper. Native callbacks run on that Looper. The renderer worker calls `LorieGraphics`; it never attaches to a JVM. |
| Memory | Window snapshot strings/icons are borrowed only during `window`; a `data` callback owns its fd; Surface/renderer resources are retained until acknowledged release. |
| `presented` | Runs on the renderer thread after Android presentation, not on the connection Looper. Marshal it to the control thread and reject stale serials. |
| `lorieConnectionDestroy` | Does not stop Xorg; destroy is serialized with other connection calls. |

`LorieGraphics` is a host-owned immutable function table:
`create/destroy`, `image/releaseImage`, `surface/releaseSurface`,
`acquire`, `begin/draw/submit/cancel`, `present`. `submit` waits for pixel-access
completion; `present` may wait for Android and must run outside the shared X
pixel mutex. **The pin contains no Android host graphics implementation and no
JNI adapter.** The host must supply both; a software presenter is the first
supported target. Build server and renderer from the same revision.

**JNI ownership:** keep a thin JNI control/command adapter in the dedicated
service. Attach the JVM only on callback threads that need to notify Kotlin
(e.g. `ready`, `window`, `windowsCommitted`, `presented`); do not attach the
renderer worker. Copy borrowed metadata into Kotlin-owned strings/bitmaps before
return. Keep one ordered command queue and one frame/session writer. Do not
call engine control functions from the Activity main thread unless the audited
Looper requirement is satisfied.

## Window discovery and policy

`lorieObserveWindows` plus `window`/`windowsCommitted` publish bounded snapshots:
XID, title, `WM_CLASS` instance/class, 64x64 ARGB icon, mapped, role, parent,
current width/height, `WM_NORMAL_HINTS` constraints, management request/actual
state and interaction. A null info removes the XID. XIDs are not process IDs or
tunnel stream IDs; track XID **and** window incarnation/session generation.

`LorieWindowRole` is `APPLICATION`, `SPLASH`, `UNCLASSIFIED`, `DIALOG`. Apply the
host policy from the execution guide:

| Object | Host |
|---|---|
| Independent normal top-level | One independent free-form Android task. |
| Second normal from the same client | Another independent task; do not merge by PID/class/stream. |
| Transient dialog/modal | Owned host/family; preserve owner, modality, stacking and close. |
| Managed utility/toolbar/torn-off menu | Owned host if owner resolves; otherwise documented independent host. |
| Dropdown/tooltip/combo/popup/override-redirect | Dependent presentation without taskbar/Recents entry; preserve position and grabs. |
| Splash | Temporary startup host with defined handoff. |
| Root/desktop/dock/input-only/internal child | No ordinary application task. |

`lorieOutputBindDependents` borrows a mapped transient family without its main
layer; the parent remains the client-size owner and cannot be recursively
borrowed. `lorieOutputBindShell` is for shell surfaces and is not an application
task substitute. `MAGICDESK_X11_HOST_WM=1` enables the dedicated-application
EWMH bridge; it owns `WM_S0` and only supported state hints, and yields to an
existing WM. Test a conflicting WM and do not assume it supplies Android task
policy. Do not introduce a guest desktop to hide host window-management gaps.

## Resize, DPI, close

- `windowSize` is a synchronous host callback on the X server thread. It
  receives validated per-output `LorieWindowConstraints` (min/max, base,
  increment, aspect) and returns positive pixel limits. It must not block or
  re-enter the server. It is not called for root/borrowed outputs.
- `lorieOutputResize(connection, output, window, w, h)` is the host→X resize
  command. The Android host computes content bounds excluding system caption and
  insets; apply real client geometry, not a scaled whole desktop.
- `lorieSetScreenDpi` is screen-wide at this pin. Do not assume independently
  mutable per-window client DPI on one shared screen.
- `lorieCloseWindow(..., force=false)` sends `WM_DELETE_WINDOW`, **falling back to
  client termination if the client lacks the protocol**. `force=true` bypasses
  the protocol and disconnects the owning client, including its other windows,
  never the whole server. Window-destruction callbacks establish actual removal.
  A normal request is not a guaranteed cancellable close. Protect unrelated
  clients and distinguish caption/Back/task removal/Activity destruction.

## Authentication, capabilities and the VM boundary

- Accept path preserves X11 auth; use a valid binary `XAUTHORITY` with the
  correct `MIT-MAGIC-COOKIE-1` display record. Never `-ac` in the product.
- The VM transport is a byte relay (O/D/C). MIT-SHM and DRI3 descriptor-backed
  resources cannot cross it. Withhold/disable incompatible local-only
  capabilities in the initial software path and test real toolkit clients;
  do not advertise them and wait for failures.
- The 14500 bridge returns the display cookie after authenticating the app
  token and verifying the VM Unix handshake. The bridge config is not a
  production confidentiality mechanism by itself; record the trust boundary.
- Rotating the cookie or tunnel token must define whether existing authenticated
  clients are explicitly torn down. File replacement alone does not close them.

## Open gaps before C3.1/C3.2

1. Build `LorieNative` as a shared JNI-loadable library from the pin, with
   matching renderer sources and staged XKB.
2. Implement the dedicated service, start/ready/stop supervision, accepted-fd
   socketpair pump, and error teardown.
3. Implement the software `LorieGraphics` host and an `ANativeWindow` Surface
   lifecycle; prove known pixels before `xeyes`.
4. Implement XID+incarnation window registry and the role policy; test two
   normal windows, dialogs/popups, title/icon, resize and close.
5. Verify real SHM/DRI3 behavior and extension rejection with the actual engine.
6. Run G2 task/lifecycle probes before claiming automatic native-window
   behavior.

**Status:** static audit complete (G3). No JNI or renderer code exists yet;
`lorie-smoke` does not prove rendering, accepted-fd authentication, or window
hosting.


## Verified integration (C3.1)

- Pinned server starts with exactly `:99 -noreset -nolisten tcp -auth <file>`
  plus `-legacy-drawing -disable-gpu-present -disable-dri3`; `-screen` is not a
  supported generic option at this pin. The smoke-proven form does not need
  `-displayfd`; the ready callback reports the display.
- `MAGICDESK_X11_HOST_WM=1` is set before startup for the per-application EWMH
  bridge. No conflicting WM is present in the current test.
- A dedicated `:x11` foreground service owns the tunnel and engine. The app
  writes a valid binary Xauthority with a `FamilyWild` MIT-MAGIC-COOKIE-1 record
  from the bridge config; `-ac` is never used.
- A VM `xdpyinfo :99` traversed VM proxy → Unix tunnel → 14500 bridge → APK
  tunnel → `lorieServerAcceptClient` and returned the embedded `X.Org 21.1.99`.
  The app logs confirm accepted fd admission. Renderer, `LorieConnection`,
  Surface and window callbacks remain C3.2.
- JNI receiver declarations are instance methods (`jobject` second argument),
  and the JNI strings are released after the synchronous `strdup`/`setenv`
  copies. Earlier corrupted option text was the pin's fatal-logger `va_list`
  misuse plus the unsupported `-screen`, not an argv lifetime bug.
- Native artifacts link with 16 KB page flags and pass ELF/APK verification.
