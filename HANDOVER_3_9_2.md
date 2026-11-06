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
