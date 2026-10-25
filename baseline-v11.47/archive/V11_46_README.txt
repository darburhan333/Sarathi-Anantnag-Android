MPARIVAHAN ANANTNAG V11.46 - STANDALONE NAVIGATION ERROR ALERT FIX
Built directly from V11.44.0 BACKGROUND MONITOR FIX. 24 October 2026.
This is a normal desktop Chrome extension. NO Python, EXE, helper or shared dashboard.

INSTALL / UPDATE
1. STOP V11.44 and keep its ZIP as a backup. Review any pending booking before updating.
2. Extract the ZIP completely. manifest.json is at its root.
3. To keep the existing unpacked extension identity/settings, back up its folder, then
   replace its extension files IN THE SAME FOLDER with this extracted version.
4. Open chrome://extensions and Reload that extension. Approve the added webNavigation
   permission if Chrome requests it. If using a new folder instead, use Load unpacked;
   disable the old copy and check settings because a new extension ID may be assigned.
5. Refresh Sarathi to remove the previous page script. Reopen the popup/monitor.
6. Verify V11.46, check Telegram recipients/token and sound settings, and use TEST ALERTS.
7. Start on the correct Sarathi COV page. Each Chrome profile still monitors one tab.

WHAT CHANGES WHEN THE ERROR IN YOUR SCREENSHOT APPEARS
Chrome main-page navigation errors such as ERR_HTTP2_PROTOCOL_ERROR are now handled
by the background worker, independently of the unavailable Sarathi page script.
A qualifying monitored-tab error requests desktop/Telegram alerts and the repeating
alarm according to your settings. It stops monitoring and assisted booking, clears
the retry countdown, and shows the error in Latest activity and History.
Existing BOOK SLOT reservation/dispatch locks are NOT reset by this failure handler.
Check the website manually. No automatic reload, POST replay or BOOK SLOT retry occurs.
After recovery, restart manually; inspect any prior booking result before resetting a lock.

OTHER FIXES
- Closed/missing monitored tabs alert instead of silently changing monitor status.
- Three failed health checks now stop and alert, including the sound alarm.
- Health checks cover calendar/manual-OTP receipt-watching states without restarting
  booking automation there. Responsive OTP pages are not considered stalled.
- A delayed health check is cancelled when running/calendar/booking-lock state changes.
- Manual booking-review and login/session warnings also request the enabled alarm.
- Failed START clears the leftover receipt watcher and retry countdown.
- Standby now shows error-alert delivery failures for desktop/Telegram.
- STOP ALARM text no longer incorrectly claims stopped monitoring is continuing.
- New navigation records are bound to the current session. Only browser-event timestamps
  are compared with each other (Chrome documents clock skew versus extension Date.now()).

PRESERVED
8-second wait; LMV/MCWG choice; displayed-month green Wednesday/Thursday scanning;
positive integer quota >=1; date required and final recheck; one-time BOOK SLOT lock;
manual OTP/final confirmation; monitor window, seven-stage tracker, sound/Telegram.
Primary Telegram chat ID 7930718570 and optional second recipient are unchanged.
No multi-profile coordinator or nativeMessaging permission is included.

ALERT CONDITIONS / LIMITATIONS
- Desktop, sound and Telegram toggles still apply. Telegram needs a valid token and internet.
  A disconnected computer cannot deliver a Telegram message at that time; local alerts can
  still be requested. Failed Telegram delivery is shown; this release does not queue retries.
- ERR_ABORTED, subframe/resource errors, unrelated tabs/sites and stopped sessions are ignored.
- A navigation must be associated with the active monitoring session. If the extension missed
  the navigation start, the existing periodic health check provides the fallback.
- HTTP 500/503 pages that load normally and failed AJAX requests do not necessarily produce
  a main-frame navigation error. This release does not claim universal server-error detection.
- Generic page unresponsiveness still requires three one-minute health checks; browser scheduling
  may delay them. Explicit navigation failure events do not wait for those checks.
- Alerts are not guaranteed if Chrome exits, the computer sleeps or Windows blocks notifications/audio.
- Closing the monitored Sarathi tab while active intentionally triggers a warning. STOP first
  if you want to close it without a monitoring-failure alert.
- Exhausted calendar scans retain the previous manual-review behaviour; no new month/day fallback.

TESTING
All 14 offline Node suites and JavaScript syntax checks passed. Included tests use mocked
Chrome APIs/DOM/network. They do NOT establish live Chrome109, Windows7, Sarathi, Telegram
or speaker compatibility. See TEST_RESULTS_V11_46.txt and RELEASE_V11_46.txt.

ROLLBACK
STOP, restore the backed-up V11.44 files in the same extension folder, Reload in Chrome,
and refresh Sarathi before restarting. Do not run two copies against the same applicant.
