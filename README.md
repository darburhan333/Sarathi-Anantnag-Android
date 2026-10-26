# MPARIVAHAN SLOT BOOKING -J&K 3.0

Android app based on the GeckoView access test confirmed working on the user's Infinix phone. Uses GeckoView 158.0.20261021001945. User-started foreground service supports minimized and screen-off monitoring. Battery optimization exemption is required for reliable Doze operation; manufacturer restrictions still require device testing.

## Three applicants
Use Applicant 1, 2 and 3 to log into different applicants. Each slot has a persistent Gecko `contextId` and its own bundled extension identity, so website cookies/storage and monitor state/booking locks are separate. Only one page is visible at a time. One native foreground service owns all active runs. Stop this stops the selected applicant; Stop all and the notification action stop every applicant. Switching profiles does not stop active runs.

Profile-bound native content delegates authorize only read-only ownership queries. A monitor can select only a page verified to belong to its applicant. New windows inherit the opener's applicant. Never infer ownership from the currently visible tab. The engine binary is shared; three applicants increase memory/network work, not three copies of the engine in the APK.

## Use
1. Install the arm64 APK. Allow notifications. The diagnostic Gecko Test can stay installed separately.
2. Navigate from Parivahan to Sarathi and log in manually. Open the vehicle-class (COV) page.
3. Tap **Battery settings** and allow battery optimization exemption. On Infinix also allow background activity in app battery settings. Tap **Monitor**, configure Telegram and test sound/notifications, then START.
4. Check the ongoing **MPARIVAHAN SLOT BOOKING -J&K** notification. Monitoring can continue after Home/minimize or screen lock. Return to the same browser session by opening the notification.
5. Stop this cancels only the selected applicant. Stop all cancels all three. A bounded 30-second grace period lets pending alerts finish; an active repeating alarm keeps the service alive until silenced. The wake lock is released when no runs, alarm or delivery grace remain. Silence stops the alarm. A network change, page/engine failure, missing heartbeat or progress stall stops monitoring with a notification; return and START explicitly. Force-stop, reboot and process death do not restart automation.

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
- UI updates are coalesced, critical Stop state is immediate, hidden dashboard painting pauses, and notification counters update at most every ten seconds. Wake-lock renewal occurs every five minutes rather than every tick. Native page pings track heartbeat separately from real loop progress; 60-second stalls stop and notify. Current status and longest scheduler gap appear in the header/log.
- No silent automation resume after process loss. Prior process exit reasons are logged where available.
- Telegram delivery remains in the persistent bundled extensions. Credentials are shared across applicants and encrypted at rest using an Android Keystore AES-GCM key; legacy plaintext credentials migrate on access. Applicant labels identify alerts. Configure the single status-command receiver under Applicant 1. A durable offline delivery queue is not included.
- One arm64 release APK, R8 code/resource shrinking and compressed native libraries. The Mozilla engine still contributes most of the size.
- CI produces an unsigned arm64 release. The delivered APK is signed separately with a fixed private release key, which is never committed. The old debug-signed app may need one uninstall before installing this release; save settings/history first. Future releases must reuse the private key backup. This is not a Play Store release.

## Build
Run `gradle assembleRelease lintRelease`. `preBuild` generates Applicant 2/3 extension packages from the single maintained source. `-PtestAbi=x86_64` selects the emulator ABI. `-PsmokeRelease` signs a minified release with the CI debug certificate solely for emulator installation; do not distribute that test build.

## Validation
`python3 scripts/verify.py`: 8 suites against ported code, adapter tests, production permissions and syntax checks. All 15 original desktop suites also passed locally before and after porting. GitHub Actions builds/lints release and runs a separate x86_64 Android emulator integration test using a loopback fixture. The background fixture checks actual COV actions and loopback HTTP requests while minimized, locked, forced into Doze with exemption, and after Activity destruction/re-attachment. The three-applicant fixture also checks cookie, localStorage, IndexedDB and extension-state isolation, cross-profile message rejection, concurrent screen-off HTTP, independent Stop and Stop all. A separate smoke check installs the minified release and opens all three applicant bridges. These short emulator checks are not a 30-minute/2-hour real-device soak test. The fixture is debug-only; the release has no localhost host permission, cleartext traffic or smoke script.

Offline/emulator tests do not prove live Sarathi behavior, phone alarm audibility, Telegram delivery, or booking success. Final phone validation remains necessary.

