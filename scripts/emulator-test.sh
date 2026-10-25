#!/usr/bin/env bash
set +e
gradle -PtestAbi=x86_64 connectedDebugAndroidTest --stacktrace
result=$?
adb pull /data/local/tmp/monitor-preview.png monitor-preview.png
adb shell dumpsys activity services com.sarathi.anantnag.gecko > service-state.txt
adb logcat -d > emulator-logcat.txt
exit "$result"
