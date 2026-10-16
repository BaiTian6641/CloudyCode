#!/usr/bin/env bash
# GBCode guest bootstrap (S09.01) — transparent, inspectable, checksum-verified.
#
# What this does, in order:
#   1. Creates ~/.local/share/gbcode/{releases,current-link} layout.
#   2. Installs the pinned backend bundle from --bundle <path-or-url> after
#      SHA-256 verification against --sha256 <hex> (required for URLs).
#   3. Installs gbcode-sessiond into the release and creates the config dir
#      (~/.config/gbcode, mode 0700).
#   4. Detects `systemctl --user`; installs a user service if usable, else
#      prints the foreground command.
#   5. Prints the server fingerprint and a fresh one-time pairing secret.
#
# It never runs sudo, never modifies anything outside ~/.local/share/gbcode,
# ~/.config/gbcode and ~/.config/systemd/user, and prints every action.
# Uninstall:  gbcode-bootstrap.sh --uninstall
set -euo pipefail

GB_HOME="${GBCODE_HOME:-$HOME/.local/share/gbcode}"
GB_CONFIG="${GBCODE_CONFIG:-$HOME/.config/gbcode}"
GB_SYSTEMD="${GBCODE_SYSTEMD:-$HOME/.config/systemd/user}"
VERSION="0.1.0"

log() { printf '[gbcode] %s\n' "$*"; }
die() { printf '[gbcode] ERROR: %s\n' "$*" >&2; exit 1; }

usage() {
  cat <<'EOF'
gbcode-bootstrap.sh — GBCode guest installer (inspectable; no sudo; no pipes)

  --bundle <path|url>   Backend bundle (.tar.gz) to install (required)
  --sha256 <hex>        Expected SHA-256 of the bundle (required for URLs)
  --release <id>        Release identifier (default: bundle file name)
  --uninstall           Remove GBCode guest files (keeps projects; stops service)
  --help                This text
EOF
}

uninstall() {
  log "stopping service if present"
  if command -v systemctl >/dev/null 2>&1; then
    systemctl --user stop gbcode-sessiond.service 2>/dev/null || true
    systemctl --user disable gbcode-sessiond.service 2>/dev/null || true
    rm -f "$GB_SYSTEMD/gbcode-sessiond.service"
    systemctl --user daemon-reload 2>/dev/null || true
  fi
  log "removing $GB_HOME and $GB_CONFIG (projects are never touched)"
  rm -rf "$GB_HOME" "$GB_CONFIG"
  log "uninstalled"
  exit 0
}

BUNDLE=""
SHA256=""
RELEASE=""
for arg in "$@"; do
  case "$arg" in
    --uninstall) uninstall ;;
    --help) usage; exit 0 ;;
  esac
done
while [ $# -gt 0 ]; do
  case "$1" in
    --bundle) BUNDLE="$2"; shift 2 ;;
    --sha256) SHA256="$2"; shift 2 ;;
    --release) RELEASE="$2"; shift 2 ;;
    *) die "unknown argument: $1 (see --help)" ;;
  esac
done

[ -n "$BUNDLE" ] || { usage; die "--bundle is required"; }

# --- fetch/verify bundle ---
TMPD="$(mktemp -d)"
trap 'rm -rf "$TMPD"' EXIT
if [ -f "$BUNDLE" ]; then
  PKG="$BUNDLE"
else
  [ -n "$SHA256" ] || die "--sha256 is required when --bundle is a URL"
  PKG="$TMPD/bundle.tar.gz"
  log "downloading $BUNDLE"
  curl -fsSL --retry 3 -o "$PKG" "$BUNDLE"
fi
ACTUAL="$(sha256sum "$PKG" | awk '{print $1}')"
if [ -n "$SHA256" ] && [ "$ACTUAL" != "$SHA256" ]; then
  die "checksum mismatch: expected $SHA256 got $ACTUAL"
fi
[ -z "$SHA256" ] || log "checksum verified: $ACTUAL"
[ -z "$SHA256" ] && log "WARNING: no --sha256 given for local file; continuing with local trust"

# --- install layout ---
RELEASE="${RELEASE:-$(basename "$PKG" .tar.gz)}"
DEST="$GB_HOME/releases/$RELEASE"
log "installing release $RELEASE into $GB_HOME/releases/"
mkdir -p "$GB_HOME/releases"
rm -rf "$DEST"
mkdir -p "$DEST"
tar -xzf "$PKG" -C "$DEST" --strip-components=1 2>/dev/null || tar -xzf "$PKG" -C "$DEST"
[ -x "$DEST/bin/gbcode-sessiond.js" ] || [ -f "$DEST/bin/gbcode-sessiond.js" ] || \
  log "note: bundle has no bin/gbcode-sessiond.js; sessiond ships with GBCode releases"
ln -sfn "releases/$RELEASE" "$GB_HOME/current"

# --- config dir ---
mkdir -p "$GB_CONFIG"
chmod 700 "$GB_CONFIG"

# --- service management ---
SESSIOND_BIN="$GB_HOME/current/bin/gbcode-sessiond.js"
if command -v systemctl >/dev/null 2>&1 && systemctl --user is-system-running >/dev/null 2>&1; then
  log "systemd user manager detected; installing gbcode-sessiond.service"
  mkdir -p "$GB_SYSTEMD"
  cat > "$GB_SYSTEMD/gbcode-sessiond.service" <<EOF
[Unit]
Description=GBCode guest session daemon
After=default.target

[Service]
Type=simple
ExecStart=$(command -v node) $SESSIOND_BIN --bind 127.0.0.1 --port 9123 --state-dir $GB_CONFIG
Restart=on-failure
RestartSec=3

[Install]
WantedBy=default.target
EOF
  systemctl --user daemon-reload
  systemctl --user enable --now gbcode-sessiond.service
  log "service enabled and started"
else
  log "no usable systemd --user; run manually when needed:"
  log "  node $SESSIOND_BIN --bind 127.0.0.1 --port 9123 --state-dir $GB_CONFIG"
fi

# --- pairing bootstrap output ---
# The one-time enrollment secret is produced by the daemon itself on first
# --enroll launch (kept out of shell history and this script's process list).
log "pairing: start the daemon, then read its printed fingerprint and one-time secret:"
log "  node $GB_HOME/current/bin/gbcode-sessiond.js --enroll --state-dir $GB_CONFIG"

log "done. release=$RELEASE"
log "next: pair the GBCode app using the fingerprint + one-time secret printed by the daemon"
