#!/bin/sh
set -eu
umask 077
base="${HOME:?}/.local/share/eta-tailscale"
mkdir -p "$base"
exec "$base/bin/tailscaled" \
  --tun=userspace-networking \
  --state="$base/tailscaled.state" \
  --socket="$base/tailscaled.sock" \
  --statedir="$base/state"
