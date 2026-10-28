#!/usr/bin/env bash
set +e
gradle -PtestAbi=x86_64 connectedDebugAndroidTest --stacktrace
result=$?
adb pull /data/local/tmp/monitor-preview.png monitor-preview.png
adb shell dumpsys activity services com.sarathi.anantnag.gecko.v33 > service-state.txt
adb logcat -d > emulator-logcat.txt
if [ "$result" -eq 0 ]; then
  gradle -PtestAbi=x86_64 -PsmokeRelease assembleRelease --stacktrace || exit 1
  # Clear only the disposable CI emulator so same-version debug extensions cannot survive into release smoke.
  adb install -r app/build/outputs/apk/release/app-release.apk || exit 1
  adb shell pm clear com.sarathi.anantnag.gecko.v33 || exit 1
  python3 scripts/release-smoke.py || exit 1
fi
exit "$result"


