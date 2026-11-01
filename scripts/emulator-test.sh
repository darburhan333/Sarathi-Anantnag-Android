#!/usr/bin/env bash
set +e
result=0
mkdir -p ci-evidence
: > emulator-diagnostic-capture-status.txt
adb logcat -c >> emulator-diagnostic-capture-status.txt 2>&1 || echo "logcat clear failed (non-fatal)" >> emulator-diagnostic-capture-status.txt
if [ "${1:-all}" != "release-only" ]; then
  gradle -PtestAbi=x86_64 connectedDebugAndroidTest --stacktrace
  result=$?
fi
adb pull /data/local/tmp/monitor-preview.png monitor-preview.png >> emulator-diagnostic-capture-status.txt 2>&1 || echo "monitor-preview.png unavailable (non-fatal; test may have failed before capture)" >> emulator-diagnostic-capture-status.txt
INSTALLED_SARATHI_PACKAGES="$(adb shell pm list packages | tr -d '\r' | grep -i sarathi || true)"
printf 'Installed Sarathi packages (adb shell pm list packages | grep -i sarathi):\n%s\n' "$INSTALLED_SARATHI_PACKAGES" >> emulator-diagnostic-capture-status.txt
PACKAGE_LINE="$(printf '%s\n' "$INSTALLED_SARATHI_PACKAGES" | grep -iv '\.test
adb shell dumpsys power > emulator-power.txt 2>&1 || echo "dumpsys power failed" >> emulator-diagnostic-capture-status.txt
adb shell dumpsys deviceidle > emulator-deviceidle.txt 2>&1 || echo "dumpsys deviceidle failed" >> emulator-diagnostic-capture-status.txt
adb shell dumpsys battery > ci-evidence/emulator-battery.txt 2>&1 || echo "dumpsys battery failed" >> emulator-diagnostic-capture-status.txt
adb shell dumpsys notification --noredact > ci-evidence/emulator-notifications.txt 2>&1 || echo "dumpsys notification failed" >> emulator-diagnostic-capture-status.txt
adb logcat -d > emulator-logcat.txt 2>&1 || echo "logcat capture failed" >> emulator-diagnostic-capture-status.txt
rm -f monitor-copy-log.txt monitor-copy-log.txt.tmp
PACKAGE="$PACKAGE"
CAPTURED=false
CAPTURE_TMP="monitor-copy-log.txt.tmp"
: > "$CAPTURE_TMP"
validate_diagnostic_candidate() {
  local source="$1"
  if [ ! -s "$CAPTURE_TMP" ]; then
    echo "SOURCE $source FAILED: file missing or empty" >> emulator-diagnostic-capture-status.txt
    return 1
  fi
  if grep -Eiq 'run-as:|unknown package' "$CAPTURE_TMP"; then
    echo "SOURCE $source FAILED: file contains run-as/unknown-package error text" >> emulator-diagnostic-capture-status.txt
    return 1
  fi
  if ! grep -Fxq 'DIAG-START' "$CAPTURE_TMP"; then
    echo "SOURCE $source FAILED: missing exact DIAG-START marker line" >> emulator-diagnostic-capture-status.txt
    return 1
  fi
  if ! grep -Fxq 'DIAG-END' "$CAPTURE_TMP"; then
    echo "SOURCE $source FAILED: missing exact DIAG-END marker line" >> emulator-diagnostic-capture-status.txt
    return 1
  fi
  mv "$CAPTURE_TMP" monitor-copy-log.txt
  echo "SOURCE $source SUCCEEDED: validated DIAG-START and DIAG-END; $(wc -c < monitor-copy-log.txt) bytes" >> emulator-diagnostic-capture-status.txt
  CAPTURED=true
  return 0
}
# Source 1: app-private file via run-as. Its failure is recorded, not mistaken for file content.
if [ -n "$PACKAGE" ] && adb exec-out run-as "$PACKAGE" cat files/monitor-copy-log.txt > "$CAPTURE_TMP" 2>> emulator-diagnostic-capture-status.txt; then
  validate_diagnostic_candidate "app-private-run-as" || rm -f "$CAPTURE_TMP"
else
  rc=$?
  echo "SOURCE app-private-run-as FAILED: run-as command exit=$rc" >> emulator-diagnostic-capture-status.txt
  rm -f "$CAPTURE_TMP"
fi
# Source 2: target app external-files directory.
if [ "$CAPTURED" != true ]; then
  rm -f "$CAPTURE_TMP"
  if [ -n "$PACKAGE" ] && adb pull "/sdcard/Android/data/$PACKAGE/files/monitor-copy-log.txt" "$CAPTURE_TMP" >> emulator-diagnostic-capture-status.txt 2>&1; then
    validate_diagnostic_candidate "target-app-external-files" || rm -f "$CAPTURE_TMP"
  else
    rc=$?
    echo "SOURCE target-app-external-files FAILED: adb pull exit=$rc" >> emulator-diagnostic-capture-status.txt
    rm -f "$CAPTURE_TMP"
  fi
fi
# Source 3: instrumentation shell handoff written directly by the test.
if [ "$CAPTURED" != true ]; then
  rm -f "$CAPTURE_TMP"
  if adb pull /data/local/tmp/monitor-copy-log.txt "$CAPTURE_TMP" >> emulator-diagnostic-capture-status.txt 2>&1; then
    validate_diagnostic_candidate "instrumentation-shell-handoff" || rm -f "$CAPTURE_TMP"
  else
    rc=$?
    echo "SOURCE instrumentation-shell-handoff FAILED: adb pull exit=$rc" >> emulator-diagnostic-capture-status.txt
    rm -f "$CAPTURE_TMP"
  fi
fi
rm -f "$CAPTURE_TMP"
if [ "$CAPTURED" != true ] || [ ! -s monitor-copy-log.txt ]; then
  echo "::error::No valid monitor-copy-log.txt source; required DIAG-START/DIAG-END markers were not captured"
  echo "FINAL CAPTURE RESULT: FAILED" >> emulator-diagnostic-capture-status.txt
  result=1
else
  echo "FINAL CAPTURE RESULT: SUCCEEDED from validated source" >> emulator-diagnostic-capture-status.txt
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
  if adb shell pm path $PACKAGE | grep -q '^package:'; then
    adb uninstall $PACKAGE || exit 1
  fi
  adb install "$APK_PATH" || exit 1
  adb shell pm clear $PACKAGE || exit 1
  python3 scripts/release-smoke.py || exit 1
fi
exit "$result"

 | head -n1)"
PACKAGE="${PACKAGE_LINE#package:}"
if [ -n "$PACKAGE" ]; then
  echo "Selected installed target package: $PACKAGE" >> emulator-diagnostic-capture-status.txt
  adb shell dumpsys activity services "$PACKAGE" > service-state.txt 2>&1 || echo "dumpsys activity services failed" >> emulator-diagnostic-capture-status.txt
else
  echo "No installed Sarathi target package found; package-dependent captures skipped" >> emulator-diagnostic-capture-status.txt
  : > service-state.txt
fi
adb shell dumpsys power > emulator-power.txt 2>&1 || echo "dumpsys power failed" >> emulator-diagnostic-capture-status.txt
adb shell dumpsys deviceidle > emulator-deviceidle.txt 2>&1 || echo "dumpsys deviceidle failed" >> emulator-diagnostic-capture-status.txt
adb shell dumpsys battery > ci-evidence/emulator-battery.txt 2>&1 || echo "dumpsys battery failed" >> emulator-diagnostic-capture-status.txt
adb shell dumpsys notification --noredact > ci-evidence/emulator-notifications.txt 2>&1 || echo "dumpsys notification failed" >> emulator-diagnostic-capture-status.txt
adb logcat -d > emulator-logcat.txt 2>&1 || echo "logcat capture failed" >> emulator-diagnostic-capture-status.txt
rm -f monitor-copy-log.txt monitor-copy-log.txt.tmp
PACKAGE="$PACKAGE"
CAPTURED=false
CAPTURE_TMP="monitor-copy-log.txt.tmp"
: > "$CAPTURE_TMP"
validate_diagnostic_candidate() {
  local source="$1"
  if [ ! -s "$CAPTURE_TMP" ]; then
    echo "SOURCE $source FAILED: file missing or empty" >> emulator-diagnostic-capture-status.txt
    return 1
  fi
  if grep -Eiq 'run-as:|unknown package' "$CAPTURE_TMP"; then
    echo "SOURCE $source FAILED: file contains run-as/unknown-package error text" >> emulator-diagnostic-capture-status.txt
    return 1
  fi
  if ! grep -Fxq 'DIAG-START' "$CAPTURE_TMP"; then
    echo "SOURCE $source FAILED: missing exact DIAG-START marker line" >> emulator-diagnostic-capture-status.txt
    return 1
  fi
  if ! grep -Fxq 'DIAG-END' "$CAPTURE_TMP"; then
    echo "SOURCE $source FAILED: missing exact DIAG-END marker line" >> emulator-diagnostic-capture-status.txt
    return 1
  fi
  mv "$CAPTURE_TMP" monitor-copy-log.txt
  echo "SOURCE $source SUCCEEDED: validated DIAG-START and DIAG-END; $(wc -c < monitor-copy-log.txt) bytes" >> emulator-diagnostic-capture-status.txt
  CAPTURED=true
  return 0
}
# Source 1: app-private file via run-as. Its failure is recorded, not mistaken for file content.
if [ -n "$PACKAGE" ] && adb exec-out run-as "$PACKAGE" cat files/monitor-copy-log.txt > "$CAPTURE_TMP" 2>> emulator-diagnostic-capture-status.txt; then
  validate_diagnostic_candidate "app-private-run-as" || rm -f "$CAPTURE_TMP"
else
  rc=$?
  echo "SOURCE app-private-run-as FAILED: run-as command exit=$rc" >> emulator-diagnostic-capture-status.txt
  rm -f "$CAPTURE_TMP"
fi
# Source 2: target app external-files directory.
if [ "$CAPTURED" != true ]; then
  rm -f "$CAPTURE_TMP"
  if [ -n "$PACKAGE" ] && adb pull "/sdcard/Android/data/$PACKAGE/files/monitor-copy-log.txt" "$CAPTURE_TMP" >> emulator-diagnostic-capture-status.txt 2>&1; then
    validate_diagnostic_candidate "target-app-external-files" || rm -f "$CAPTURE_TMP"
  else
    rc=$?
    echo "SOURCE target-app-external-files FAILED: adb pull exit=$rc" >> emulator-diagnostic-capture-status.txt
    rm -f "$CAPTURE_TMP"
  fi
fi
# Source 3: instrumentation shell handoff written directly by the test.
if [ "$CAPTURED" != true ]; then
  rm -f "$CAPTURE_TMP"
  if adb pull /data/local/tmp/monitor-copy-log.txt "$CAPTURE_TMP" >> emulator-diagnostic-capture-status.txt 2>&1; then
    validate_diagnostic_candidate "instrumentation-shell-handoff" || rm -f "$CAPTURE_TMP"
  else
    rc=$?
    echo "SOURCE instrumentation-shell-handoff FAILED: adb pull exit=$rc" >> emulator-diagnostic-capture-status.txt
    rm -f "$CAPTURE_TMP"
  fi
fi
# Source 4: recover the complete DIAG-tagged report from logcat.
if [ "$CAPTURED" != true ]; then
  rm -f "$CAPTURE_TMP"
  if adb logcat -d -v raw -s DIAG:I > "$CAPTURE_TMP" 2>> emulator-diagnostic-capture-status.txt; then
    validate_diagnostic_candidate "diag-tagged-logcat" || rm -f "$CAPTURE_TMP"
  else
    rc=$?
    echo "SOURCE diag-tagged-logcat FAILED: adb logcat exit=$rc" >> emulator-diagnostic-capture-status.txt
    rm -f "$CAPTURE_TMP"
  fi
fi
rm -f "$CAPTURE_TMP"
if [ "$CAPTURED" != true ] || [ ! -s monitor-copy-log.txt ]; then
  echo "::error::No valid monitor-copy-log.txt source; required DIAG-START/DIAG-END markers were not captured"
  echo "FINAL CAPTURE RESULT: FAILED" >> emulator-diagnostic-capture-status.txt
  result=1
else
  echo "FINAL CAPTURE RESULT: SUCCEEDED from validated source" >> emulator-diagnostic-capture-status.txt
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
  if adb shell pm path $PACKAGE | grep -q '^package:'; then
    adb uninstall $PACKAGE || exit 1
  fi
  adb install "$APK_PATH" || exit 1
  if [ -z "$PACKAGE" ]; then
    INSTALLED_AFTER_BUILD="$(adb shell pm list packages | tr -d '\r' | grep -i sarathi || true)"
    PACKAGE_LINE="$(printf '%s\n' "$INSTALLED_AFTER_BUILD" | grep -iv '\.test$' | head -n1)"
    PACKAGE="${PACKAGE_LINE#package:}"
  fi
  [ -n "$PACKAGE" ] || { echo "Unable to resolve installed Sarathi package after release install."; exit 1; }
  adb shell pm clear $PACKAGE || exit 1
  python3 scripts/release-smoke.py || exit 1
fi
exit "$result"

