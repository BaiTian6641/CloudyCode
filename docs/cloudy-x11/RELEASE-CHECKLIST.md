# Cloudy-X11 release checklist

Status: draft; C5 acceptance is not complete.

## Functional acceptance

- [ ] Fresh VM setup reaches the embedded engine without xpra/sessiond.
- [ ] `xeyes`, `xterm`, Firefox and a representative toolkit app open one
      free-form task each from Terminal and Cloudy Code.
- [ ] Close, reopen, resize, pointer, hardware key/text and dialog behavior.
- [ ] 14500 tunnel survives Terminal window visibility changes or reconnects
      without a black host.
- [ ] Cloudy Code integrated terminal inherits `DISPLAY=:99`.

## Security/reliability

- [ ] Token never logged; HMAC ping and cookie path verified.
- [ ] No raw X11 TCP listener; tunnel uses token + Unix socket.
- [ ] Stale app tunnel replacement closes only the old streams.
- [ ] Frame limits, stream limits and socket teardown fuzzed.
- [ ] Engine/service crash recovery and host task cleanup.

## Performance

- [ ] Map-to-window latency and frame/present rate recorded.
- [ ] Multi-window memory/CPU bounded.
- [ ] Resize and large-window behavior measured.

## Licensing

- [ ] GPLv3 magicdesk-x11 pin and corresponding source available.
- [ ] NOTICE/SBOM and app distribution obligations reviewed.
- [ ] No proprietary engine blob; 16 KB native/APK verification passes.
