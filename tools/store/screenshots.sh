#!/usr/bin/env bash
# Takes the Play Store phone screenshots on a running emulator (see
# .github/workflows/store-screenshots.yml). Each scene is set up by the debug
# build's ScreenshotScenes (simulated car, debug controls hidden).
#
#   [VIDEO=<dir>] [SCENES="route ..."] tools/store/screenshots.sh <app-debug.apk> <out-dir>
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

start_scene() {
  adb shell am force-stop "$PKG"
  if [ -n "${2:-}" ]; then
    adb shell am start -n "$PKG/com.zepdriver.ui.MainActivity" --es zep.screenshot "$1" --es zep.hide "$2" >/dev/null
  else
    adb shell am start -n "$PKG/com.zepdriver.ui.MainActivity" --es zep.screenshot "$1" >/dev/null
  fi
}

# PROBE="<layers>;<layers>;...": the route scene once per set of map layers
# left out, reporting whether the app survived. Takes no screenshots.
if [ -n "${PROBE:-}" ]; then
  IFS=';' read -ra SETS <<< "$PROBE"
  for hide in "${SETS[@]}"; do
    adb logcat -b crash -c || true
    start_scene route "$hide"
    sleep 150
    if adb shell pidof "$PKG" >/dev/null; then verdict=alive; else verdict=CRASHED; fi
    echo "PROBE hide=[$hide]: $verdict"
    adb logcat -b crash -d | grep -m3 -E "Fatal signal|#00 pc|#01 pc" || true
  done
  exit 0
fi

# SCENES (e.g. "route") retakes some of them; the file numbers stay the same.
ALL="map warning route search fleet night"
SCENES="${SCENES:-$ALL}"
if [ -n "${VIDEO_ONLY:-}" ]; then SCENES=""; fi
n=1
for scene in $ALL; do
  if [[ " $SCENES " == *" $scene "* ]]; then
    start_scene "$scene"
    # Routes wait for the router to build its grid, which is slow on an emulator.
    if [ "$scene" = route ]; then sleep 150; else sleep 40; fi
    adb exec-out screencap -p > "$OUT/$(printf '%02d' $n)-$scene.png"
    echo "captured $scene"
  fi
  n=$((n + 1))
done

# The foreground service video for Play's declaration: navigating, then the
# app goes to the background and keeps working (status in the notification),
# then back to the app.
if [ -n "${VIDEO:-}" ]; then
  start_scene video
  sleep 40    # the car driving
  adb shell screenrecord --time-limit 75 --bit-rate 6000000 /sdcard/fgs.mp4 &
  REC=$!
  sleep 15                                              # navigating in the app
  adb shell input keyevent KEYCODE_HOME; sleep 8        # app in the background
  adb shell cmd statusbar expand-notifications; sleep 12  # the service keeps navigating
  adb shell cmd statusbar collapse; sleep 12
  adb shell cmd statusbar expand-notifications; sleep 10  # progress since
  adb shell cmd statusbar collapse; sleep 2
  adb shell am start -n "$PKG/com.zepdriver.ui.MainActivity" >/dev/null   # back in the app
  wait $REC || true
  sleep 3
  mkdir -p "$VIDEO"
  adb pull /sdcard/fgs.mp4 "$VIDEO/foreground-service-location.mp4"
  echo "recorded the foreground service video"
fi
adb shell am broadcast -a com.android.systemui.demo -e command exit >/dev/null

# What happened to the app (crashes, memory kills, map errors), for the CI log.
echo "--- crashes ---"
adb logcat -b crash -d | tail -n 80 || true
echo "--- app and memory ---"
adb logcat -d | grep -E "zepdriver|ZepDriver|AndroidRuntime|lowmemorykiller|lmkd|MapLibre|Mbgl|Router" | tail -n 150 || true
