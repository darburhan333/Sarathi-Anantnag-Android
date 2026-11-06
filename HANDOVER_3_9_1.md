# MPARIVAHAN 3.9.1 Midnight Teal
Development branch: build/mparivahan-v3.6-complete. Package, applicant context IDs, extension IDs, release certificate and KeepAlive are unchanged.

## Included changes
- Midnight Teal native controls and embedded monitor/Alerts palette. Six top controls remain Website/Monitor, Start All, Silence All / Stop All, Home, Call. View toggle never starts a run; logo remains branding only.
- Startup, fresh website sessions and Home open https://sarathi.parivahan.gov.in/sarathiservice/stateSelection.do. Protected-page navigation still requires confirmation.
- Session-expiry native request and port routes now use the same terminal handler and redacted evidence. Evidence includes service instance, Activity visibility, age since pause, pending page load, most recent load outcome, network transport/validation and native heartbeat/progress timing. No query strings, cookies or credentials are added. This improves diagnosis; it does not claim to prevent server/session expiry.
- An idle-page closure leaves a persistent applicant-specific notice, retained across reopening/process restart until explicitly dismissed. Wording requests reopening and checking login; it does not assume every unload loses login.
- Optional device-local Telegram edit password: set from Applicant Alerts. PBKDF2-HMAC-SHA256, 310000 iterations, fresh 16-byte salt, constant-time comparison; no plaintext password or exported verifier. Wrong-attempt delays persist across lock recreation. Unlock lasts two minutes for one applicant and is revoked on pause, applicant switch, leaving Alerts, or manual Lock. Native guards protect credentials and Telegram switches. Phone numbers, routine alert modes and monitoring/alert delivery stay usable.
- Existing encrypted settings transfer requires Telegram authorization when password protection is configured; the export passphrase remains separate. The device edit password is not imported/exported.
- Password has no recovery bypass; choose and retain it carefully. App updates preserve the local verifier.

## Validation
Release workflow retains all existing regression/browser/emulator assertions and timeouts. New tests cover password hashing, failed-attempt persistence, per-applicant expiring authorization, guarded adapter mutations, persistent idle notices and added redacted expiry evidence.
The notification STOP job is now required by verified-release, rather than advisory.
Do not label an APK TESTS-PASSED until compile, regression/browser, signed release/lint, release UI, Gecko integration and notification STOP all succeed.
Physical Infinix Android 16 locked/minimized monitoring is a separate real-device check. Emulator results do not establish physical-device reliability.

## Preserved safety
Manual SMS/OTP/CAPTCHA and final confirmation. No automatic login, navigation recovery or booking replay. Existing applicant ownership, protected-page retention and PROCEED cadence remain in place.

## Lifecycle and release validation fixes
- Release a GeckoView's accessibility owner and cached virtual focus before native session close. Route all page closure and Activity view releases through the same cleanup path.
- Bind only the displayed website or monitor to an Android view; keep both Gecko sessions and existing background/protected-page activity policy. The hidden surface has no stale Android accessibility owner. The new regression checks both directions, session identity, node-provider availability and that view switching does not start monitoring.
- Avoid unchanged native text/tint/window updates and unchanged embedded idle dashboard paints.
- Foreground-service idle-stop grace has its own guarded 30-second task instead of relying only on the monitoring clock.
- Instrumentation prepares POST_NOTIFICATIONS before app launch, connects accessibility explicitly and keeps accessibility enabled. A controlled comparison passed all 29 original lifecycle cases on GeckoView 158 in both arms; a separate SMS reproducer passed 12 cycles each on 158 and 159. These are focused results, not a full release pass. Production GeckoView stays 158.0.20261021001945.
- Full release verification remains required; physical Infinix testing remains pending.

- Preserve a latched protected page when a queued STOPPED snapshot arrives before native Stop begins its retention timer. The focused SMS test now explicitly covers this ordering. Notification Stop uses an actual offline SMS document so a still-loading real homepage cannot overwrite a synthetic fixture; all original safety assertions remain. Notification test reports/logcat are always uploaded, including failures.
