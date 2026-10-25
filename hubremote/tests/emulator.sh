#!/usr/bin/env bash
# Drives Hub Remote on the emulator against a real Hub server (on the host, 10.0.2.2)
# and a fake Roku. Writes screenshots and results to $OUT.
#   emulator.sh <apk> <screen WxH> <density> <pad center X> <pad center Y>
set -u
APK=$1; SIZE=$2; DENSITY=$3; PX=$4; PY=$5
OUT=${OUT:-screens}
PKG=io.github.jayswansocool48.hubremote
mkdir -p "$OUT"
adb shell wm size "$SIZE"; adb shell wm density "$DENSITY"; adb logcat -c
adb install -r "$APK"
start() { adb shell am start -W -n $PKG/.MainActivity "$@" > /dev/null; }
shot() { sleep "${2:-4}"; adb exec-out screencap -p > "$OUT/$1.png"; }
alive() { adb shell pidof $PKG > /dev/null && echo "$1: running" >> "$OUT/results.txt" || echo "$1: NOT RUNNING" >> "$OUT/results.txt"; }

start; shot 00-connect 6; alive connect
start --es server 10.0.2.2 --es tab remote; shot 01-remote 6; alive remote

# swipe right on the pad, then tap it: the fake TV should get Right and Select
: > roku-keys.log
adb shell input swipe $((PX - 90)) "$PY" $((PX + 90)) "$PY" 250; sleep 2
adb shell input tap "$PX" "$PY"; sleep 2
shot 02-remote-after-swipe 1

for tab in channels browser send hub; do
  start --es tab $tab; shot "03-$tab" 7; alive "$tab"
done
# scroll the Hub settings page to see more
adb shell input swipe $((PX + 200)) 700 $((PX + 200)) 150 400; shot 04-hub-scrolled 2
adb shell input swipe $((PX + 200)) 700 $((PX + 200)) 150 400; shot 05-hub-scrolled-more 2

# open a channel from the Channels tab
start --es tab channels; sleep 5
adb logcat -d | grep -E "FATAL|AndroidRuntime" | head -40 > "$OUT/crashes.txt" || true
