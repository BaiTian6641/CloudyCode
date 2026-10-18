# Cloudy-X11 release checklist

Status: draft; C5 acceptance is not complete.

## Functional acceptance

- [x] Live VM display proxy (14500 Unix/cookie tunnel) reaches the embedded engine; target-only setup.
- [x] xeyes and Firefox open free-form tasks; three simultaneous xeyes hosts verified.
  - [ ] xterm/representative toolkit and Cloudy Code launched GUI app.
- [x] Close/reopen, resize, pointer and hardware text verified.
  - [ ] True transient dialog and soft IME.
- [ ] 14500 tunnel survives Terminal window visibility changes or reconnects
      without a black host.
- [x] Cloudy Code backend environment inherits `DISPLAY=:99`; `xdpyinfo` reaches it.

## Security/reliability

- [x] Token not logged in tested paths; HMAC ping/cookie path host test passes.
- [x] No raw X11 TCP listener in the active path; Unix tunnel + token proxy.
- [x] Stale app tunnel replacement implemented and exercised by reconnects.
- [ ] Frame/stream limits and teardown fuzzing (pending).
- [ ] Engine/service crash recovery and host task cleanup.

## Performance

- [ ] Map-to-window latency and frame/present rate recorded.
- [ ] Multi-window memory/CPU bounded.
- [ ] Resize and large-window behavior measured.

## Licensing

- [x] magicdesk-x11 pin/source metadata recorded; release source archive pending.
- [ ] NOTICE/SBOM and app distribution obligations reviewed.
- [x] No proprietary engine blob; 16 KB ELF/APK verification passes.
