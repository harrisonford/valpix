#!/usr/bin/env bash
# Starts the debug app on the phone connected over adb, with debug settings
# for this one run. Settings travel in a file inside the app's private
# storage, which only adb can write; the app reads it once and removes it.
#
#   scripts/launch.sh                                   # a plain launch
#   scripts/launch.sh VALPIX_SKIP_AUTH=1 VALPIX_OPEN=history
#   scripts/launch.sh VALPIX_SKIP_AUTH=1 VALPIX_AUTOCONFIRM=1 VALPIX_OPEN=charge VALPIX_CHARGE=100
#
# See android/README.md for what each setting does.
set -euo pipefail
APP=com.franky.valpix

adb shell am force-stop "$APP"
if [ "$#" -gt 0 ]; then
  printf '%s\n' "$@" | adb shell "run-as $APP sh -c 'cat > files/debug-launch'"
else
  adb shell "run-as $APP rm -f files/debug-launch"
fi
adb shell am start -n "$APP/.MainActivity" >/dev/null
