#!/usr/bin/env bash
set +e
result=0
if [ "${1:-all}" != "release-only" ]; then
  gradle -PtestAbi=x86_64 connectedDebugAndroidTest --stacktrace
  result=$?
fi
mkdir -p ci-evidence
: > emulator-diagnostic-capture-status.txt
adb pull /data/local/tmp/monitor-preview.png monitor-preview.png >> emulator-diagnostic-capture-status.txt 2>&1 || echo "monitor-preview.png unavailable (non-fatal; test may have failed before capture)" >> emulator-diagnostic-capture-status.txt
adb shell dumpsys activity services com.sarathi.anantnag.gecko.v33 > service-state.txt 2>&1 || echo "dumpsys activity services failed" >> emulator-diagnostic-capture-status.txt
adb shell dumpsys power > emulator-power.txt 2>&1 || echo "dumpsys power failed" >> emulator-diagnostic-capture-status.txt
adb shell dumpsys deviceidle > emulator-deviceidle.txt 2>&1 || echo "dumpsys deviceidle failed" >> emulator-diagnostic-capture-status.txt
adb shell dumpsys battery > ci-evidence/emulator-battery.txt 2>&1 || echo "dumpsys battery failed" >> emulator-diagnostic-capture-status.txt
adb shell dumpsys notification --noredact > ci-evidence/emulator-notifications.txt 2>&1 || echo "dumpsys notification failed" >> emulator-diagnostic-capture-status.txt
adb logcat -d > emulator-logcat.txt 2>&1 || echo "logcat capture failed" >> emulator-diagnostic-capture-status.txt
rm -f monitor-copy-log.txt monitor-copy-log.txt.tmp
PACKAGE="com.sarathi.anantnag.gecko.v33"
CAPTURED=false
# Prefer the app-private diagnostics file. run-as reads the exact file written
# by the instrumentation target, avoiding stale or mismatched external paths.
if adb exec-out run-as "$PACKAGE" cat files/monitor-copy-log.txt > monitor-copy-log.txt.tmp 2>> emulator-diagnostic-capture-status.txt && [ -s monitor-copy-log.txt.tmp ]; then
  mv monitor-copy-log.txt.tmp monitor-copy-log.txt
  CAPTURED=true
  echo "monitor Copy-log captured from app-private files/" >> emulator-diagnostic-capture-status.txt
fi
# Fall back to the app external-files directory, then the instrumentation shell handoff.
if [ "$CAPTURED" != true ] && adb pull "/sdcard/Android/data/$PACKAGE/files/monitor-copy-log.txt" monitor-copy-log.txt.tmp >> emulator-diagnostic-capture-status.txt 2>&1 && [ -s monitor-copy-log.txt.tmp ]; then
  mv monitor-copy-log.txt.tmp monitor-copy-log.txt
  CAPTURED=true
  echo "monitor Copy-log captured from external app files/" >> emulator-diagnostic-capture-status.txt
fi
if [ "$CAPTURED" != true ] && adb pull /data/local/tmp/monitor-copy-log.txt monitor-copy-log.txt.tmp >> emulator-diagnostic-capture-status.txt 2>&1 && [ -s monitor-copy-log.txt.tmp ]; then
  mv monitor-copy-log.txt.tmp monitor-copy-log.txt
  CAPTURED=true
  echo "monitor Copy-log captured from instrumentation shell handoff" >> emulator-diagnostic-capture-status.txt
fi
rm -f monitor-copy-log.txt.tmp
if [ "$CAPTURED" != true ] || [ ! -s monitor-copy-log.txt ]; then
  echo "::error::monitor-copy-log.txt could not be captured or was empty; instrumentation diagnostics capture is mandatory"
  echo "monitor Copy-log capture FAILED or EMPTY" >> emulator-diagnostic-capture-status.txt
  result=1
else
  echo "monitor-copy-log.txt captured: $(wc -c < monitor-copy-log.txt) bytes"
fi
if [ "$result" -eq 0 ]; then
  # The emulator job intentionally has no production signing secrets. Create a disposable
  # CI-only key so the minified release APK can be installed and exercised by the smoke test.
  SMOKE_KEYSTORE="${RUNNER_TEMP:-${TMPDIR:-/tmp}}/release-smoke.jks"
  SMOKE_STORE_PASSWORD='smoke-store-pass'
  SMOKE_KEY_PASSWORD='smoke-key-pass'
  SMOKE_KEY_ALIAS='release-smoke'
  keytool -genkeypair -noprompt -storetype JKS \
    -keystore "$SMOKE_KEYSTORE" \
    -storepass "$SMOKE_STORE_PASSWORD" \
    -keypass "$SMOKE_KEY_PASSWORD" \
    -alias "$SMOKE_KEY_ALIAS" \
    -keyalg RSA -keysize 2048 -validity 2 \
    -dname 'CN=CI Release Smoke Test, OU=Testing, O=Local CI, L=NA, ST=NA, C=US' || exit 1

  RELEASE_KEYSTORE_PATH="$SMOKE_KEYSTORE" \
  ANDROID_KEYSTORE_PASSWORD="$SMOKE_STORE_PASSWORD" \
  ANDROID_KEY_ALIAS="$SMOKE_KEY_ALIAS" \
  ANDROID_KEY_PASSWORD="$SMOKE_KEY_PASSWORD" \
    gradle -PtestAbi=x86_64 -PsmokeRelease assembleRelease --stacktrace || exit 1

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
  python3 scripts/release-smoke.py || exit 1
fi
exit "$result"

