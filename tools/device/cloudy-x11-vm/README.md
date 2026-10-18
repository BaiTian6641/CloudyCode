# Cloudy-X11 VM forwarder

- `cloudy-x11-vm.py` owns `:99` (`/tmp/.X11-unix/X99`). In production it
  accepts one app-authenticated tunnel on `127.0.0.1:14511` and frames each X11
  client stream to the Android app. `--upstream` is the local transport test
  mode.
- `tunnel-test.py` is the app-side protocol harness. It authenticates, accepts
  opened streams, and forwards them to a local X server such as Xvfb. The
  Android embedded X server will implement the same frames.
- `install-env.sh` writes the VM-wide `DISPLAY=:99`/`XAUTHORITY`, the systemd
  user service and the Cloudy Code terminal environment.

## VM transport test (no Android yet)

```sh
BASE=~/cloudy-x11-test
mkdir -p $BASE
python3 - <<'PY'
import secrets
print(secrets.token_hex(24))
PY
# write the printed token to $BASE/x11-token
Xvfb :98 -screen 0 1280x800x24 -nolisten tcp -auth $BASE/Xauthority &
xauth -f $BASE/Xauthority add :99 . "$(python3 -c 'import secrets;print(secrets.token_hex(16))')"
# Use the same cookie for :98 and :99; simplest is the install-env.sh xauth.
python3 cloudy-x11-vm.py --display :99 --tunnel-listen tcp:127.0.0.1:14511 --token-file $BASE/x11-token &
python3 tunnel-test.py --connect tcp:127.0.0.1:14511 --token-file $BASE/x11-token --upstream unix:/tmp/.X11-unix/X98 &
DISPLAY=:99 XAUTHORITY=$BASE/Xauthority xdpyinfo | head
```

The final Android app connects the tunnel and supplies `lorieServerAcceptClient`
to the embedded X server; no X11 TCP listener is exposed.
