#!/usr/bin/env bash
set +e
result=0
if [ "${1:-all}" != "release-only" ]; then
  read -r -a runner_args <<< "${SARATHI_TEST_RUNNER_ARGS:-}"
  gradle -PtestAbi=x86_64 "${runner_args[@]}" connectedDebugAndroidTest --stacktrace
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
valid_diagnostic_file() {
  local file="$1"
  [ -s "$file" ] &&
    ! grep -q '^run-as: unknown package:' "$file" &&
    grep -q '^DIAG-START$' "$file" &&
    grep -q '^DIAG-END$' "$file" &&
    grep -q '=== CRITICAL EVENTS (never trimmed) ===' "$file" &&
    grep -q '=== CURRENT DIAGNOSTICS ===' "$file"
}
try_diagnostic_route() {
  local route="$1"
  if valid_diagnostic_file monitor-copy-log.txt.tmp; then
    mv monitor-copy-log.txt.tmp monitor-copy-log.txt
    CAPTURED=true
    echo "monitor Copy-log captured and validated from $route" >> emulator-diagnostic-capture-status.txt
    return 0
  fi
  echo "monitor Copy-log route rejected: $route (missing/invalid markers or command error)" >> emulator-diagnostic-capture-status.txt
  rm -f monitor-copy-log.txt.tmp
  return 1
}
# Try all supported sources, validating each before accepting it.
adb exec-out run-as "$PACKAGE" cat files/monitor-copy-log.txt > monitor-copy-log.txt.tmp 2>> emulator-diagnostic-capture-status.txt
if [ -s monitor-copy-log.txt.tmp ]; then try_diagnostic_route "app-private files/" || true; fi
if [ "$CAPTURED" != true ]; then
  adb pull "/sdcard/Android/data/$PACKAGE/files/monitor-copy-log.txt" monitor-copy-log.txt.tmp >> emulator-diagnostic-capture-status.txt 2>&1
  if [ -s monitor-copy-log.txt.tmp ]; then try_diagnostic_route "external app files/" || true; else rm -f monitor-copy-log.txt.tmp; fi
fi
if [ "$CAPTURED" != true ]; then
  adb pull /data/local/tmp/monitor-copy-log.txt monitor-copy-log.txt.tmp >> emulator-diagnostic-capture-status.txt 2>&1
  if [ -s monitor-copy-log.txt.tmp ]; then try_diagnostic_route "instrumentation shell handoff" || true; else rm -f monitor-copy-log.txt.tmp; fi
fi
# Android's instrumentation runner uninstalls the target and test APKs when it
# exits. The test teardown therefore also mirrors the exact report to DIAG logcat.
# Recover the last complete report from that durable log before declaring failure.
if [ "$CAPTURED" != true ]; then
  awk '
    / I DIAG    : DIAG-START$/ { collecting=1; report="DIAG-START\n"; next }
    collecting {
      line=$0
      sub(/^.* I DIAG    : /, "", line)
      if (line ~ /DIAG-END$/) {
        sub(/DIAG-END$/, "", line)
        if (line != "") report=report line "\n"
        last=report "DIAG-END\n"
        collecting=0
      } else {
        report=report line "\n"
      }
    }
    END { if (last != "") printf "%s", last }
  ' emulator-logcat.txt > monitor-copy-log.txt.tmp
  if [ -s monitor-copy-log.txt.tmp ]; then try_diagnostic_route "instrumentation DIAG logcat" || true; else rm -f monitor-copy-log.txt.tmp; fi
fi
if [ "$CAPTURED" != true ]; then
  {
    echo "Package visibility diagnostic:"
    adb shell pm list packages | grep -F "$PACKAGE" || echo "Target package not present in package list: $PACKAGE"
    echo "Android users:"
    adb shell pm list users
  } >> emulator-diagnostic-capture-status.txt 2>&1
  echo "::error::monitor-copy-log.txt could not be captured with all required diagnostic markers"
  echo "monitor Copy-log capture FAILED or INVALID" >> emulator-diagnostic-capture-status.txt
  result=1
else
  echo "monitor-copy-log.txt captured with required diagnostic markers: $(wc -c < monitor-copy-log.txt) bytes"
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

