#!/usr/bin/env bash
# Runs inside the emulator job: instrumented tests, then a monkey stress run.
set -x
PKG=com.rcj811.podscompanion
adb logcat -c
gradle connectedDebugAndroidTest --stacktrace
TEST_RC=$?

adb install -r app/build/outputs/apk/debug/app-debug.apk
for p in BLUETOOTH_SCAN BLUETOOTH_CONNECT ACCESS_FINE_LOCATION POST_NOTIFICATIONS; do
  adb shell pm grant $PKG android.permission.$p || true
done
adb shell appops set $PKG SYSTEM_ALERT_WINDOW allow
adb shell am start -n $PKG/.ui.MainActivity
sleep 3
adb shell monkey -p $PKG --pct-syskeys 0 --pct-appswitch 5 --throttle 50 -s 42 -v 3000 > monkey.txt 2>&1
MONKEY_RC=$?
tail -5 monkey.txt
sleep 2
adb logcat -d > logcat.txt
if grep -E "FATAL EXCEPTION|ANR in $PKG|CRASH: $PKG" -A 30 logcat.txt monkey.txt; then
  echo "::error::App crashed or froze"
  exit 1
fi
echo "monkey rc=$MONKEY_RC test rc=$TEST_RC"
[ $TEST_RC -eq 0 ] && [ $MONKEY_RC -eq 0 ]
