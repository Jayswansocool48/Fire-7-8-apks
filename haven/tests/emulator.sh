#!/usr/bin/env bash
# Screenshots Haven (TV) or Haven Remote (phone) on the emulator.
#   emulator.sh tv    <haven.apk>                     <WxH> <density>
#   emulator.sh phone <haven.apk> <havenremote.apk>   <WxH> <density>
# Writes PNGs, results.txt and crashes.txt to $OUT.
set -u
MODE=$1; shift
OUT=${OUT:-screens}
TV=io.github.jayswansocool48.haven
RM=io.github.jayswansocool48.havenremote
mkdir -p "$OUT"
adb shell settings put global hide_error_dialogs 1 2>/dev/null || true
adb logcat -c
calm() { adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS > /dev/null 2>&1 || true; }
shot() { sleep "${2:-2}"; calm; adb exec-out screencap -p > "$OUT/$1.png"; }
key() { for k in "$@"; do adb shell input keyevent "$k"; sleep 0.6; done; }
alive() { adb shell pidof "$2" > /dev/null && echo "$1: running" >> "$OUT/results.txt" || echo "$1: NOT RUNNING" >> "$OUT/results.txt"; }
# Taps the first on-screen element whose text contains $1.
tap_text() {
  adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1
  local b
  b=$(adb shell cat /sdcard/ui.xml | tr '>' '\n' | grep -F "text=\"" | grep -F "$1" | head -1 | grep -o 'bounds="[^"]*"' | grep -o '[0-9]\+' | tr '\n' ' ')
  if [ -z "$b" ]; then echo "tap: '$1' not found" >> "$OUT/results.txt"; return 1; fi
  set -- $b
  adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
}

if [ "$MODE" = tv ]; then
  APK=$1; SIZE=$2; DENSITY=$3
  adb shell wm size "$SIZE"; adb shell wm density "$DENSITY"
  adb install -r "$APK"
  adb shell am start -W -n $TV/.LauncherActivity > /dev/null
  shot 01-home 8; alive home $TV
  key DPAD_RIGHT; shot 02-focus-second 1
  key DPAD_DOWN; shot 03-all-apps 1
  key DPAD_DOWN DPAD_DOWN; shot 04-scrolled 1
  adb shell input keyevent --longpress DPAD_CENTER; shot 05-app-menu 2
  key BACK
  adb shell am start -W -n $TV/.LauncherActivity > /dev/null; sleep 2   # Home: back to the top
  key DPAD_UP DPAD_UP; shot 06-header-focus 1
  key DPAD_CENTER; shot 07-settings 2
  key DPAD_CENTER; shot 08-looks 2
  key DPAD_DOWN DPAD_DOWN DPAD_DOWN DPAD_DOWN; shot 09-looks-starry-preview 2
  key DPAD_CENTER; shot 10-starry-settings 2
  key BACK; shot 11-starry-home 2
  key DPAD_UP DPAD_UP DPAD_CENTER DPAD_CENTER DPAD_UP DPAD_UP DPAD_UP DPAD_UP DPAD_UP DPAD_CENTER; sleep 2
  key BACK; shot 12-back-to-lavender 2
  alive end $TV
else
  HAVEN=$1; APK=$2; SIZE=$3; DENSITY=$4
  adb shell wm size "$SIZE"; adb shell wm density "$DENSITY"
  adb install -r "$HAVEN"; adb install -r "$APK"
  # Haven on the same emulator plays the TV, so the remote has an app list to show.
  adb shell am start -W -n $TV/.LauncherActivity > /dev/null; sleep 4
  adb shell am start -W -n $RM/.MainActivity > /dev/null
  shot 01-setup-first-run 5; alive setup $RM
  tap_text "Find my TV"; shot 02-found 10
  adb shell input keyevent BACK; sleep 1
  adb shell am start -W -n $RM/.MainActivity > /dev/null; sleep 2
  tap_text "Remote"; shot 03-remote 3
  tap_text "Swipe"; shot 04-remote-swipe 2
  tap_text "Buttons"; sleep 1
  tap_text "⌨" || true; shot 05-keyboard 3
  adb shell input keyevent BACK; sleep 1
  tap_text "Apps"; shot 06-apps 8; alive apps $RM
  tap_text "Setup"; shot 07-setup 3
  adb shell input swipe 500 1800 500 400 400; shot 08-setup-scrolled 2
fi
adb logcat -d | grep -E "FATAL|AndroidRuntime" | head -40 > "$OUT/crashes.txt" || true
