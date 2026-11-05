#!/usr/bin/env bash
set +e
result=0
if [ "${1:-all}" != "release-only" ]; then
  read -r -a runner_args <<< "${SARATHI_TEST_RUNNER_ARGS:-}"
  gradle -PtestAbi=x86_64 "${runner_args[@]}" connectedDebugAndroidTest --stacktrace
  result=$?
fi
if [ "${1:-all}" != "release-only" ]; then
mkdir -p ci-evidence
: > emulator-diagnostic-capture-status.txt
adb pull /data/local/tmp/monitor-preview.png monitor-preview.png >> emulator-diagnostic-capture-status.txt 2>&1 || echo "monitor-preview.png unavailable (non-fatal; test may have failed before capture)" >> emulator-diagnostic-capture-status.txt
adb shell dumpsys activity services com.sarathi.anantnag.gecko.v33 > service-state.txt 2>&1 || echo "dumpsys activity services failed" >> emulator-diagnostic-capture-status.txt
adb shell dumpsys power > emulator-power.txt 2>&1 || echo "dumpsys power failed" >> emulator-diagnostic-capture-status.txt
adb shell dumpsys deviceidle > emulator-deviceidle.txt 2>&1 || echo "dumpsys deviceidle failed" >> emulator-diagnostic-capture-status.txt
adb shell dumpsys battery > ci-evidence/emulator-battery.txt 2>&1 || echo "dumpsys battery failed" >> emulator-diagnostic-capture-status.txt
adb shell dumpsys notification --noredact > ci-evidence/emulator-notifications.txt 2>&1 || echo "dumpsys notification failed" >> emulator-diagnostic-capture-status.txt
adb logcat -d > emulator-logcat.txt 2>&1 || echo "logcat capture failed" >> emulator-diagnostic-capture-status.txt
python3 scripts/capture-diagnostics.py || result=1
fi
if [ "$result" -eq 0 ]; then
  # The emulator job intentionally has no production signing secrets. Create a disposable
  # CI-only key so the minified release APK can be installed and exercised by the smoke test.
  if [ "${SARATHI_PREBUILT_SMOKE_APK:-0}" != "1" ]; then
    bash scripts/build-smoke-release.sh || exit 1
  fi

  # Prefer the signed release output. Never silently fall back to an unsigned APK because
  # Android correctly rejects unsigned APKs with INSTALL_PARSE_FAILED_NO_CERTIFICATES.
  APK_PATH="$(find app/build/outputs/apk -type f -name '*.apk' -path '*/release/*' -print -quit)"
  if [ -z "$APK_PATH" ]; then
    echo "No release APK found under app/build/outputs/apk after assembleRelease."
    find app/build/outputs -maxdepth 6 -type f -name '*.apk' -print || true
    exit 1
  fi
  APKSIGNER="$(find "$ANDROID_HOME/build-tools" -name apksigner -type f | sort -V | tail -1)"
  if [ -z "$APKSIGNER" ]; then echo 'Android apksigner was not found.'; exit 1; fi
  "$APKSIGNER" verify --verbose "$APK_PATH" || exit 1
  echo "Installing signed release smoke APK: $APK_PATH"
  # Clear only the disposable CI emulator so debug extensions cannot survive into release smoke.
  if adb shell pm path com.sarathi.anantnag.gecko.v33 | grep -q '^package:'; then
    adb uninstall com.sarathi.anantnag.gecko.v33 || exit 1
  fi
  adb install "$APK_PATH" || exit 1
  adb shell pm clear com.sarathi.anantnag.gecko.v33 || exit 1
  python3 -u scripts/release-smoke.py || exit 1
fi
exit "$result"

