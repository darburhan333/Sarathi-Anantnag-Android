#!/usr/bin/env bash
set +e
gradle -PtestAbi=x86_64 connectedDebugAndroidTest --stacktrace
result=$?
adb pull /sdcard/Android/data/com.sarathi.anantnag.gecko/files/monitor-preview.png monitor-preview.png
adb logcat -d > emulator-logcat.txt
exit "$result"
