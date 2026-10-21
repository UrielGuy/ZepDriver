#!/usr/bin/env bash
# Takes the Play Store phone screenshots on a running emulator (see
# .github/workflows/store-screenshots.yml). Each scene is set up by the debug
# build's ScreenshotScenes (simulated car, debug controls hidden).
#
#   tools/store/screenshots.sh <app-debug.apk> <out-dir>
set -euo pipefail
APK="$1"
OUT="$2"
PKG=com.zepdriver.debug
mkdir -p "$OUT"

adb wait-for-device
adb install -r "$APK"
for p in ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION POST_NOTIFICATIONS BLUETOOTH_CONNECT BLUETOOTH_SCAN; do
  adb shell pm grant "$PKG" "android.permission.$p" 2>/dev/null || true
done

# A clean status bar: full battery, full signal, 9:41.
adb shell settings put global sysui_demo_allowed 1
adb shell am broadcast -a com.android.systemui.demo -e command enter >/dev/null
adb shell am broadcast -a com.android.systemui.demo -e command clock -e hhmm 0941 >/dev/null
adb shell am broadcast -a com.android.systemui.demo -e command battery -e level 100 -e plugged false >/dev/null
adb shell am broadcast -a com.android.systemui.demo -e command network -e mobile show -e level 4 -e datatype none >/dev/null
adb shell am broadcast -a com.android.systemui.demo -e command notifications -e visible false >/dev/null

n=1
for scene in map warning route search fleet night; do
  adb shell am force-stop "$PKG"
  adb shell am start -n "$PKG/com.zepdriver.ui.MainActivity" --es zep.screenshot "$scene" >/dev/null
  sleep 35
  adb exec-out screencap -p > "$OUT/$(printf '%02d' $n)-$scene.png"
  echo "captured $scene"
  n=$((n + 1))
done
adb shell am broadcast -a com.android.systemui.demo -e command exit >/dev/null
