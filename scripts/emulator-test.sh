#!/usr/bin/env bash
set +e
gradle -PtestAbi=x86_64 connectedDebugAndroidTest --stacktrace
result=$?
adb pull /data/local/tmp/monitor-preview.png monitor-preview.png
adb shell dumpsys activity services com.sarathi.anantnag.gecko.v33 > service-state.txt
adb logcat -d > emulator-logcat.txt
if [ "$result" -eq 0 ]; then
  # The emulator job intentionally has no production signing secrets. Create a disposable
  # CI-only key so the minified release APK can be installed and exercised by the smoke test.
  SMOKE_KEYSTORE="${RUNNER_TEMP:-${TMPDIR:-/tmp}}/release-smoke.jks"
  SMOKE_STORE_PASSWORD='smoke-store-pass'
  SMOKE_KEY_PASSWORD='smoke-key-pass'
  SMOKE_KEY_ALIAS='release-smoke'
  keytool -genkeypair -noprompt \
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
  adb uninstall com.sarathi.anantnag.gecko.v33 || exit 1
  adb install "$APK_PATH" || exit 1
  adb shell pm clear com.sarathi.anantnag.gecko.v33 || exit 1
  python3 scripts/release-smoke.py || exit 1
fi
exit "$result"
