# MPARIVAHAN J&K 3.9.2-midnight-teal
Base: verified 3.9.1 source d9d5ae28f1ab82061b26426cb5c45a1a375715a7, canonical run #234; documentation head 27f0ef55c017b16f0de92144539b907bcbb98529.

## Requested changes
- Upper row: Stop All / Start All / Home.
- Second row: Monitor (toggles to Website on the dashboard) / Silence All / selected-applicant Call.
- Opening the app or selecting/reopening an applicant displays that applicant's existing Sarathi website session. It does not navigate, reload, start monitoring or replace the session. Fresh/closed usable sessions retain the Sarathi state-selection homepage.
- Opening Alerts still shows the isolated credential/settings page; Back retains the existing main-monitor behavior.
- COV retry target changed from 10 seconds to 7 seconds. Both native-extension background PROCEED authorization gates now enforce a 7-second minimum, matching the existing content-side minimum. Countdown threshold is aligned. Server response/navigation time can lengthen a cycle.
- This explicit user-requested 7-second interval supersedes the old 8-second minimum for COV PROCEED only; booking safety and manual SMS/OTP/CAPTCHA/final confirmation are unchanged.
- Package, release certificate, nine applicant identities and KeepAlive remain unchanged.
- versionCode 392, versionName 3.9.2-midnight-teal; extension production 3.4.13/debug 3.4.14.

## Validation
- Local scripts/verify.py passed: adapter gates, credential isolation, Telegram, booking protection, Stop/restart, COV selection, 301-cycle fixtures, quota and UI route suites.
- Added clock-boundary checks against the real background action handler: both PROCEED and PROCEED_CONFIRM reject 6999ms, authorize 7000ms and reject immediate duplicates.
- Native controls regression updated for the exact requested row order and selected website/session retention.
- Signed release smoke checks now assert website-by-default on fresh launch and all nine applicant selections, plus the two-row order.
- Canonical signed APK build, lint, required notification Stop, lifecycle preflight, minified UI smoke and full integration must pass before release. Pending at this commit.
- Physical Infinix X6870 Android 16 minimized/locked testing remains unverified.

## Release UI routing correction
Run #236 failed its real Alerts assertion for Applicant 4: the native Alerts header was visible but the embedded dashboard remained on LIVE MONITOR, so Chat ID 2 was absent. Compile, focused lifecycle and required notification Stop passed.
- Removed extension navigation from MainActivity.render().
- BrowserEngine now owns the requested panel and loads a newly created extension session directly at #alerts-settings or #monitor once. Requested panels survive delayed extension initialization.
- Explicit Monitor toggles restore #monitor while applicant selection still defaults to the retained website.
- Local contract harness executed the actual extracted native routing methods with reentrant rendering and verified fresh Alerts, rapid changes, session retention, both toggle directions and delayed extension readiness.
- The signed release smoke retains all nine-applicant Alerts field assertions, route isolation and exact control-order checks. No waits, assertions or release gates were relaxed.
- Canonical rerun pending at this commit. The saved-details clipboard remains a concept preview; it is not implemented in this build.

## Notification Stop fixture server correction
- Run #237 passed compile, production APK, release UI preflight and lifecycle preflight. Notification Stop failed before STOP dispatch because its real local SMS page did not become protected.
- Artifact 13485743222 logs show a socket read timeout followed immediately by a broken pipe for GET /slots/dlsltprev.do; no SMS response was successfully served.
- The sequential server could let an idle speculative connection block the actual HTTP request. Fixture clients now use a bounded four-worker daemon pool and a 5-second per-client read timeout. Only the intended SMS path serves the SMS document; other paths return 404.
- Added a successful HTTP-response assertion before the existing protected-document and Stop assertions. The 30-second protected-document deadline and all STOP/foreground/KeepAlive/audio/session/priority assertions remain unchanged.
- Local Java harness executed the actual server helper, held an idle connection open, verified the SMS request completed within a 2-second socket bound, and verified unrelated paths returned 404.
- This change affects Android instrumentation only; production monitoring, routing and booking behavior are unchanged. Canonical rerun required before release.
