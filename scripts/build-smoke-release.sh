#!/usr/bin/env bash
set -euo pipefail
# Build before emulator boot so R8 does not compete with the Android launcher.
SMOKE_KEYSTORE="${RUNNER_TEMP:-${TMPDIR:-/tmp}}/release-smoke.jks"
SMOKE_STORE_PASSWORD='smoke-store-pass'
SMOKE_KEY_PASSWORD='smoke-key-pass'
SMOKE_KEY_ALIAS='release-smoke'
if [ ! -f "$SMOKE_KEYSTORE" ]; then
keytool -genkeypair -noprompt -storetype JKS \
  -keystore "$SMOKE_KEYSTORE" \
  -storepass "$SMOKE_STORE_PASSWORD" \
  -keypass "$SMOKE_KEY_PASSWORD" \
  -alias "$SMOKE_KEY_ALIAS" \
  -keyalg RSA -keysize 2048 -validity 2 \
  -dname 'CN=CI Release Smoke Test, OU=Testing, O=Local CI, L=NA, ST=NA, C=US' || exit 1
fi

RELEASE_KEYSTORE_PATH="$SMOKE_KEYSTORE" \
ANDROID_KEYSTORE_PASSWORD="$SMOKE_STORE_PASSWORD" \
ANDROID_KEY_ALIAS="$SMOKE_KEY_ALIAS" \
ANDROID_KEY_PASSWORD="$SMOKE_KEY_PASSWORD" \
  gradle -PtestAbi=x86_64 -PsmokeRelease assembleRelease --stacktrace || exit 1


# Release R8/Gradle heap before starting the 4 GiB Android emulator.
gradle --stop
