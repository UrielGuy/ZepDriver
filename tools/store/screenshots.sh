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

# PROBE="<layers>;<layers>;...": the "navigate" scene once per set of map
# layers left out, reporting whether the app crashed. Takes no screenshots.
if [ -n "${PROBE:-}" ]; then
  IFS=';' read -ra SETS <<< "$PROBE"
  for hide in "${SETS[@]}"; do
    adb logcat -b crash -c || true
    start_scene navigate "$hide"
    sleep 150
    # Not pidof: the sticky driving service restarts the process after a crash.
    if adb logcat -b crash -d | grep -q "Fatal signal"; then verdict=CRASHED; else verdict=survived; fi
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
    # The route list says "Preparing routes" until the router's grid is built,
    # which is slow on an emulator.
    if [ "$scene" = route ]; then sleep 150; else sleep 40; fi
    adb exec-out screencap -p > "$OUT/$(printf '%02d' $n)-$scene.png"
    echo "captured $scene"
  fi
  n=$((n + 1))
done

# The foreground service videos for Play's declaration, one per task.
ready() { adb logcat -d -s ZepScenes:I | grep -q zep-scene-ready; }
crashed() { adb logcat -b crash -d | grep -q "Fatal signal"; }
fresh_logs() { adb logcat -c; adb logcat -b crash -c || true; }
# "x y" at the middle of the first thing on screen whose text is exactly $1.
find_text() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || return 0
  adb exec-out cat /sdcard/ui.xml | tr '>' '\n' | grep -F "text=\"$1\"" \
    | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | head -1 | tr -c '0-9\n' ' ' \
    | awk '{ print int(($1 + $3) / 2), int(($2 + $4) / 2) }' || true
}
tap() { if [ -n "$1" ]; then adb shell input tap $1; else echo "nothing to tap"; fi; }
from_background() {
  adb shell input keyevent KEYCODE_HOME; sleep 6                 # app in the background
  adb shell cmd statusbar expand-notifications; sleep 12         # the service carries on
  adb shell cmd statusbar collapse; sleep 12
  adb shell cmd statusbar expand-notifications; sleep 10         # progress since
  adb shell cmd statusbar collapse; sleep 2
  adb shell am start -n "$PKG/com.zepdriver.ui.MainActivity" >/dev/null   # back in the app
}

# Navigation: following a planned route, in the app and from the background.
# Takes [map layers to leave out] in case the emulator's GL crashes on some.
record_navigation() {
  fresh_logs
  start_scene navigation "$1"
  local t=0
  until ready; do
    sleep 5; t=$((t + 5))
    if crashed || [ $t -ge 360 ]; then return 1; fi
  done
  adb shell screenrecord --time-limit 80 --bit-rate 6000000 /sdcard/nav.mp4 &
  local rec=$!
  sleep 20    # following the route
  from_background
  wait $rec || true
  sleep 3
  ! crashed
}

# Location sharing: the driver connects the fleet radio (a pretend one in the
# simulator), the other cars show up, and sharing carries on in the background.
record_sharing() {
  fresh_logs
  adb shell settings put system show_touches 1
  start_scene sharing
  local t=0
  until ready || [ $t -ge 120 ]; do sleep 5; t=$((t + 5)); done
  sleep 3
  local connect fleet drive
  connect=$(find_text "Connect over Bluetooth"); fleet=$(find_text "Fleet"); drive=$(find_text "Drive")
  echo "taps: connect=[$connect] fleet=[$fleet] drive=[$drive]"
  adb shell screenrecord --time-limit 80 --bit-rate 6000000 /sdcard/share.mp4 &
  local rec=$!
  sleep 4; tap "$connect"; sleep 9    # looking for the radio, then connected
  tap "$fleet"; sleep 10              # the other cars
  tap "$drive"; sleep 10              # on the map
  from_background
  wait $rec || true
  sleep 3
  adb shell settings put system show_touches 0
  ! crashed
}

if [ -n "${VIDEO:-}" ]; then
  mkdir -p "$VIDEO"
  for hide in "" "blocks,landmarks,art" "street-labels,home-label,favorite-labels,camp-labels,poi-labels,toilet-labels,art-labels,fleet-labels,stop-numbers"; do
    if record_navigation "$hide"; then
      adb pull /sdcard/nav.mp4 "$VIDEO/foreground-service-navigation.mp4"
      echo "recorded the navigation video (map layers left out: [$hide])"
      break
    fi
    echo "navigation video failed (map layers left out: [$hide])"
    adb logcat -b crash -d | grep -m3 -E "Fatal signal|#00 pc|#01 pc" || true
  done
  if record_sharing; then
    adb pull /sdcard/share.mp4 "$VIDEO/foreground-service-location-sharing.mp4"
    echo "recorded the location sharing video"
  else
    echo "location sharing video failed"
    adb logcat -b crash -d | grep -m3 -E "Fatal signal|#00 pc|#01 pc" || true
  fi
fi
adb shell am broadcast -a com.android.systemui.demo -e command exit >/dev/null

# What happened to the app (crashes, memory kills, map errors), for the CI log.
echo "--- crashes ---"
adb logcat -b crash -d | tail -n 80 || true
echo "--- app and memory ---"
adb logcat -d | grep -E "zepdriver|ZepDriver|AndroidRuntime|lowmemorykiller|lmkd|MapLibre|Mbgl|Router" | tail -n 150 || true
