SARATHI MONITOR — ANDROID, ALERT-ONLY (v1.0.0)
Port of the monitoring half of MPARIVAHAN ANANTNAG V11.46. UNTESTED ON A PHONE.

WHAT IT DOES
- Opens Sarathi in a WebView. YOU log in, solve the CAPTCHA and reach the class-of-vehicle page.
- After START it presses Proceed about every 8 seconds (LMV + MCWG ticked), and presses the
  no-slots popup's Back button, like the Chrome extension.
- It ALERTS you (loud alarm + notification + Telegram) when it sees: the booking calendar
  (with green dates listed), the Available Quota page, or Sarathi's "session flow interrupted
  or expired" error. It warns without alarm if the page is not recognised for 2 minutes.
- A foreground service, wake lock and Wi-Fi lock keep it running with the screen locked.
  The one-second ticks are driven by native code, not browser timers.

WHAT IT NEVER DOES
- Click a date, select a quota radio, click BOOK SLOT, read an OTP, or solve a CAPTCHA.
  When the alarm rings you book by hand.

BUILD THE APK WITHOUT ANDROID STUDIO (GitHub builds it for you)
1. Create a PRIVATE repository on github.com.
2. Upload ALL files of this folder to it, keeping the folders. Make sure
   .github/workflows/build.yml is there. If your upload skipped it (hidden folder), choose
   Add file > Create new file, name it  .github/workflows/build.yml  and paste its contents.
3. Open the Actions tab. The "Build APK" workflow runs on every upload (or press Run workflow).
4. When it is green, open the run and download the artifact "SarathiMonitor-debug-apk".
   Unzip it and install app-debug.apk on the phone (allow "Install unknown apps").
5. If the build fails, open the failed step, copy the red error lines and send them to Claude.
   This code was written without being compiled against the real Android SDK, so a few
   rounds of fixes are possible.
Never put your Telegram token or chat ID in these files. Enter them in the app.

FIRST RUN CHECKLIST
1. Allow notifications when asked.
2. Settings: a label (e.g. Phone 1), Telegram bot token, chat ID(s). Press TEST: you should hear
   the alarm, see the notification and receive the Telegram message. ALARM OFF stops it.
3. Press Battery and choose Unrestricted / Don't optimise. Without this Android may freeze the
   app when the screen is off. On Xiaomi/Redmi/Realme/Oppo/Vivo also enable Autostart, set
   battery to "No restrictions" and lock the app in the recent-apps screen.
4. Log in to Sarathi in the app and go to the class-of-vehicle page. Press START.
5. Lock the phone. Keep it on the charger and on one network for the first tests.
6. First tests: watch it for 10 minutes unlocked, then 10 minutes locked, before trusting it.

KNOWN LIMITS
- Android can still stop the app on some phones; then the monitor silently stops. Check it.
- Sessions can expire; the alert tells you. You must log in again by hand.
- Desktop-site mode is used so the page matches the Chrome extension's selectors.
- Only one Sarathi session per app (one cookie jar). Use a different browser/cloned app for more.
- Nothing here was tested against the live Sarathi site or on a real device.

TESTS (PC): node tests/step_test.js   (mock-DOM test of the page script)
