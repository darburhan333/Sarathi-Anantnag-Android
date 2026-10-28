#!/usr/bin/env bash
set +e
gradle -PtestAbi=x86_64 connectedDebugAndroidTest --stacktrace
result=$?
adb pull /data/local/tmp/monitor-preview.png monitor-preview.png
adb shell dumpsys activity services com.sarathi.anantnag.gecko.v33 > service-state.txt
adb logcat -d > emulator-logcat.txt
if [ "$result" -eq 0 ]; then
  gradle -PtestAbi=x86_64 -PsmokeRelease assembleRelease --stacktrace || exit 1
  # Resolve the release APK by actual output location/name; Gradle may use a variant-specific filename.
  APK_PATH="$(find app/build/outputs/apk -type f -name '*.apk' -path '*/release/*' -print -quit)"
  if [ -z "$APK_PATH" ]; then
    echo "No release APK found under app/build/outputs/apk after assembleRelease."
    find app/build/outputs -maxdepth 6 -type f -name '*.apk' -print || true
    exit 1
  fi
  echo "Installing release smoke APK: $APK_PATH"
  # Clear only the disposable CI emulator so same-version debug extensions cannot survive into release smoke.
  adb install -r "$APK_PATH" || exit 1
  adb shell pm clear com.sarathi.anantnag.gecko.v33 || exit 1
  python3 scripts/release-smoke.py || exit 1
fi
exit "$result"
