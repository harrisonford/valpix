#!/usr/bin/env bash
# Brings a connected Android phone's valpix wallet in as a member, as the admin.
# The debug build must have been opened once on the phone, so that it has
# written its address where this script can read it.
#
#   ./scripts/invite-android.sh [rpc url] [adb serial]
set -euo pipefail
cd "$(dirname "$0")"

URL="${1:-https://api.devnet.solana.com}"
ADB=(adb)
if [ -n "${2:-}" ]; then ADB=(adb -s "$2"); fi

WALLET="$("${ADB[@]}" shell run-as com.franky.valpix cat files/debug-pubkey.txt | tr -d '\r\n')"
echo "phone wallet: $WALLET"
pnpm -s admin member "$WALLET" --url "$URL"
