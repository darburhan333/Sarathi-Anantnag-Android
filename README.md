# Sarathi Anantnag Android 2.2 — V11.47 port

Android app based on the GeckoView access test confirmed working on the user's Infinix phone. Uses GeckoView 158.0.20261021001945. User-started foreground service supports minimized and screen-off monitoring. Battery optimization exemption is required for reliable Doze operation; manufacturer restrictions still require device testing.

## Use
1. Install the arm64 APK. Allow notifications. The diagnostic Gecko Test can stay installed separately.
2. Navigate from Parivahan to Sarathi and log in manually. Open the vehicle-class (COV) page.
3. Tap **Background / battery settings** and allow battery optimization exemption. On Infinix also allow background activity in app battery settings. Tap **Monitor**, configure Telegram and test sound/notifications, then START.
4. Check the ongoing **Sarathi monitoring active** notification. Monitoring can continue after Home/minimize or screen lock. Return to the same browser session by opening the notification.
5. STOP (in the app or notification) cancels monitoring and releases the service wake lock. Silence stops the alarm. A network change, page/engine failure, missing heartbeat or progress stall stops monitoring with a notification; return and START explicitly. Force-stop, reboot and process death do not restart automation.

The imported baseline uses 8-second COV waits, LMV/MCWG or whichever exists, no-slots popup Back, calendar detection, displayed-month green Wednesday/Thursday selection, and integer quota >=1. Assisted booking is OFF by default; enabled assistance retains the original one-click BOOK SLOT protection and stops at manual SMS/OTP. CAPTCHA and final confirmation remain manual. If a submission's result is uncertain, its lock remains across app restart.

Telegram supports a configurable primary recipient and optional second recipient, with the token entered locally. /status, /today and /help are read-only. Enable the receiver on only ONE device per bot; turn the PC receiver off before enabling this one. Replies require the service/browser process and network to remain available. No immediate delivery is guaranteed during network loss. Existing desktop settings do not transfer automatically. History exports through Android's share sheet.

## Port details
- Original ZIP hashes and all 15 original regression suites verified locally. The public project contains Android source and focused port tests; archived desktop files are not included. Telegram recipients have no personal preset.
- Production content retains the original algorithm. Adds a per-document nonce because Firefox may omit Chrome's documentId.
- MV2 persistent bundled Firefox extension, promise API adapter, sequential tabs.executeScript injection.
- Native Android alarm/vibration replaces Chrome offscreen audio; Android notifications replace desktop notifications.
- Mobile monitor retains settings, counters, history, seven stages and green developer credit.
- Bound-and-started foreground service owns Gecko runtime sessions, extension message channel and alerts. Activity destruction only detaches the views.
- `specialUse` subtype documents continuous user-started browser-session monitoring; service starts from a visible screen only.
- Partial CPU wake lock is bounded and renewed while the native watchdog runs. No Wi-Fi lock, exact-alarm workaround, browser spoofing, or direct Sarathi HTTP automation.
- Existing COV waits use native Handler replies with elapsed-realtime interruption checks. The original page action/booking gates remain; native service authorization is additionally required.
- Native page pings track heartbeat separately from real loop progress; 60-second stalls stop and notify. Current status and longest scheduler gap appear in the header/log.
- No silent automation resume after process loss. Prior process exit reasons are logged where available.
- Telegram remains in the persistent bundled extension; a native offline queue and encrypted-token migration are not included in this version.
- One arm64 release APK, compressed native libraries. The Mozilla engine still contributes most of the size.
- Release currently uses the build's debug signing certificate for personal testing. Updates from a different build key may require reinstall; this is not a Play Store release.

## Validation
`python3 scripts/verify.py`: 8 suites against ported code, adapter tests, production permissions and syntax checks. All 15 original desktop suites also passed locally before and after porting. GitHub Actions builds/lints release and runs a separate x86_64 Android emulator integration test using a loopback fixture. The background fixture checks actual COV actions and loopback HTTP requests while minimized, locked, forced into Doze with exemption, and after Activity destruction/re-attachment. These short emulator checks are not a 30-minute/2-hour real-device soak test. The fixture is debug-only; the release has no localhost host permission, cleartext traffic or smoke script.

Offline/emulator tests do not prove live Sarathi behavior, phone alarm audibility, Telegram delivery, or booking success. Final phone validation remains necessary.
