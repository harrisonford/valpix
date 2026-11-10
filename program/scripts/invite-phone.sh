#!/usr/bin/env bash
# Brings a connected iPhone's valpix wallet in as a member, as the admin.
# The app must have been opened once on the phone (debug build), so that it
# has written its address where this script can read it.
#
#   ./scripts/invite-phone.sh <device id from `xcrun devicectl list devices`> [rpc url]
set -euo pipefail
cd "$(dirname "$0")"

DEVICE="${1:?device id}"
URL="${2:-https://api.devnet.solana.com}"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

xcrun devicectl device copy from --device "$DEVICE" \
  --domain-type appDataContainer --domain-identifier com.franky.valpix \
  --source Documents/debug-pubkey.txt --destination "$TMP/pubkey.txt" >/dev/null
WALLET="$(cat "$TMP/pubkey.txt")"
echo "phone wallet: $WALLET"
pnpm -s admin member "$WALLET" --url "$URL"
