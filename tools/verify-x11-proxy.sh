#!/usr/bin/env bash
# X11 proxy regression guard.
#
# The setup embeds the proxy as a heredoc; the tested standalone copy is
# tools/device/cloudy-x11-proxy.py. They drifted once: the embedded ping
# required the cookie, so the app's deliberately cookie-free HMAC challenge got
# 403 and the window never loaded. Assert byte-identical copies and a live
# cookie-free challenge.
set -euo pipefail
cd "$(dirname "$0")/.."

tmp=$(mktemp -d)
pid=""
spid=""
trap 'kill "${pid:-}" "${spid:-}" 2>/dev/null || true; rm -rf "$tmp"' EXIT

awk 'index($0,"cat > \"$BASE/cloudy-x11-proxy.py\" <<"){f=1;next} f&&$0=="PY"{exit} f' \
  tools/device/cloudy-x11-setup.sh > "$tmp/embedded.py"

if ! diff -u tools/device/cloudy-x11-proxy.py "$tmp/embedded.py"; then
  echo "x11 proxy: embedded setup copy drifted from the standalone" >&2
  exit 1
fi

awk 'index($0,"cat > \"$BASE/cloudy-x11-sessiond.py\" <<"){f=1;next} f&&$0=="SESSIOND"{exit} f' \
  tools/device/cloudy-x11-setup.sh > "$tmp/sessiond.py"

if ! diff -u tools/device/cloudy-x11-sessiond.py "$tmp/sessiond.py"; then
  echo "x11 sessiond: embedded setup copy drifted from the standalone" >&2
  exit 1
fi

printf 'testtoken' > "$tmp/token"
python3 "$tmp/embedded.py" --token-file "$tmp/token" \
  --listen 127.0.0.1:14601 --upstream 127.0.0.1:1 >"$tmp/log" 2>&1 &
pid=$!

for _ in $(seq 1 30); do
  curl -sf -o /dev/null "http://127.0.0.1:14601/__cloudy/ping?nonce=probe" && break
  sleep 0.1
done

code=$(curl -s -o "$tmp/body" -w '%{http_code}' \
  "http://127.0.0.1:14601/__cloudy/ping?nonce=probe")
if [ "$code" != 200 ]; then
  echo "x11 proxy: cookie-free ping returned HTTP $code (expected 200)" >&2
  cat "$tmp/log" >&2
  exit 1
fi

expected=$(python3 -c \
  'import hashlib,hmac; print(hmac.new(b"testtoken", b"probe", hashlib.sha256).hexdigest())')
if [ "$(cat "$tmp/body")" != "$expected" ]; then
  echo "x11 proxy: ping HMAC mismatch" >&2
  exit 1
fi

echo "x11 proxy: embedded == standalone; cookie-free ping 200 + HMAC"

python3 "$tmp/sessiond.py" --base-dir "$tmp" --token-file "$tmp/token" \
  --listen 127.0.0.1:14602 >"$tmp/sessiond.log" 2>&1 &
spid=$!

for _ in $(seq 1 30); do
  curl -sf -o /dev/null "http://127.0.0.1:14602/__cloudy/ping?nonce=probe" && break
  sleep 0.1
done

code=$(curl -s -o "$tmp/body" -w '%{http_code}' \
  "http://127.0.0.1:14602/__cloudy/ping?nonce=probe")
if [ "$code" != 200 ] || [ "$(cat "$tmp/body")" != "$expected" ]; then
  echo "x11 sessiond: cookie-free ping failed (HTTP $code)" >&2
  cat "$tmp/sessiond.log" >&2
  exit 1
fi

code=$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:14602/list")
if [ "$code" != 403 ]; then
  echo "x11 sessiond: unauthenticated /list returned HTTP $code" >&2
  exit 1
fi

echo "x11 sessiond: embedded == standalone; ping 200 + HMAC; /list 403 without cookie"
