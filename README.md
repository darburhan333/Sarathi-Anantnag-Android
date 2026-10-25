# Sarathi Anantnag Android 2.0 — V11.47 port

Android app based on the GeckoView access test confirmed working on the user's Infinix phone. Uses GeckoView 158.0.20261021001945. Screen-on monitoring only.

## Use
1. Install the arm64 APK. Allow notifications. The diagnostic Gecko Test can stay installed separately.
2. Navigate from Parivahan to Sarathi and log in manually. Open the vehicle-class (COV) page.
3. Tap **Monitor / Website**, configure Telegram and test sound/notifications, then START.
4. Return to the website with the same button. The screen stays awake while monitoring.
5. STOP cancels monitoring; Silence stops the alarm. Locking or leaving the app stops automation. Return and START explicitly.

The imported baseline uses 8-second COV waits, LMV/MCWG or whichever exists, no-slots popup Back, calendar detection, displayed-month green Wednesday/Thursday selection, and integer quota >=1. Assisted booking is OFF by default; enabled assistance retains the original one-click BOOK SLOT protection and stops at manual SMS/OTP. CAPTCHA and final confirmation remain manual. If a submission's result is uncertain, its lock remains across app restart.

Telegram supports a configurable primary recipient and optional second recipient, with the token entered locally. /status, /today and /help are read-only. Enable the receiver on only ONE device per bot; turn the PC receiver off before enabling this one. Replies need the app awake and online; no background/locked-screen promise. Existing desktop settings do not transfer automatically. History exports through Android's share sheet.

## Port details
- Original ZIP hashes and all 15 original regression suites verified locally. The public project contains Android source and focused port tests; archived desktop files are not included. Telegram recipients have no personal preset.
- Production content retains the original algorithm. Adds a per-document nonce because Firefox may omit Chrome's documentId.
- MV2 persistent bundled Firefox extension, promise API adapter, sequential tabs.executeScript injection.
- Native Android alarm/vibration replaces Chrome offscreen audio; Android notifications replace desktop notifications.
- Mobile monitor retains settings, counters, history, seven stages and green developer credit.
- Foreground gate and lifecycle stop; no automatic start after restart.
- One arm64 release APK, compressed native libraries. The Mozilla engine still contributes most of the size.
- Release currently uses the build's debug signing certificate for personal testing. Updates from a different build key may require reinstall; this is not a Play Store release.

## Validation
`python3 scripts/verify.py`: 8 suites against ported code, adapter tests, production permissions and syntax checks. All 15 original desktop suites also passed locally before and after porting. GitHub Actions builds/lints release and runs a separate x86_64 Android emulator integration test using a loopback fixture. The fixture is debug-only; the release has no localhost host permission, cleartext traffic or smoke script.

Offline/emulator tests do not prove live Sarathi behavior, phone alarm audibility, Telegram delivery, or booking success. Final phone validation remains necessary.
