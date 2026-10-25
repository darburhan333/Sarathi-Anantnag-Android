MPARIVAHAN ANANTNAG V11.47 — TELEGRAM STATUS
Desktop Chrome 109+ / Windows. Based directly on V11.46.0. 24 October 2026.
No Python, EXE, server or new bot required.

UPDATE
1. STOP the old monitor and inspect any pending booking before updating.
2. Back up the existing extension folder. Extract this ZIP (manifest.json at root).
3. Replace files IN THE SAME existing unpacked-extension folder to preserve its ID/settings.
4. chrome://extensions > Reload. Disable any duplicate old extension copy.
5. Refresh the Sarathi page to remove the previous page script. Reopen the monitor.
6. Check V11.47, Telegram settings and TEST ALERTS; restart on the intended COV page.

ENABLE TELEGRAM STATUS
1. Open Telegram settings in the extension popup or monitor.
2. Keep the same saved bot token and primary recipient used for alerts.
3. Turn ON 'Enable Telegram /status replies on this PC'. The toggle saves immediately.
4. On your phone, open your existing bot's PRIVATE chat from the primary Telegram account.
5. Send /status. /today gives the same complete report; /help explains the commands.

IMPORTANT: Enable the receiver in ONE Chrome profile on ONE PC per bot. Other profiles
can still SEND alerts with that bot, but must leave the status receiver OFF. This release
does not aggregate multiple profiles. Do not run another getUpdates receiver or webhook
for the same bot. We do not delete existing webhooks or take over other bot integrations.
A 409 conflict is displayed in the extension's Telegram settings. Simultaneous receivers
may consume each other's commands even without a visible conflict.

WHAT THE REPLY MEANS
- RUNNING means a fresh content-script ping verified ownership, the COV page, an active
  loop and loop progress within 45 seconds. It is a point-in-time check, not a guarantee
  of the next submission or Sarathi server availability.
- Separate reports for STOPPED, ERROR/MANUAL REVIEW, CALENDAR/QUOTA PAUSED, MANUAL OTP,
  BOOK SLOT INITIATED, APPOINTMENT CONFIRMED and UNCONFIRMED/DELAYED activity.
- Daily attempts keep the existing 9 AM–9 PM PC-local-time counting rule.
- Session attempts count recorded Proceed clicks since START, including outside that window.
- Last recorded Proceed click is distinct from last status update. Report timestamps use IST.
- These are clicks recorded by the extension, not successful requests or successful bookings.
  Navigation/browser shutdown can interrupt recording; counters are not an audit of the server.
- Existing daily count is preserved. No lifetime total is invented or reconstructed. Session
  counter and last-attempt tracking start with V11.47; START creates a new session counter.
- No applicant information, OTP, URLs, cookies, token or raw page text is included.

TIMING AND LIMITATIONS
One 20-second long poll is started on a one-minute Chrome alarm, plus activation/startup.
Under normal scheduling expect a reply within roughly 60–80 seconds. Browser scheduling,
network problems and queued updates can delay it further. This is not instant messaging.
The receiver remains available when monitoring is STOPPED, provided its own toggle is ON.
It is independent of the automatic Telegram-alert toggle; turning alerts OFF does not turn
status commands OFF. Only the primary private account can request status; the optional
second recipient continues to receive alerts but cannot issue commands in this release.

PC must remain awake, online, with Chrome running. Windows screen lock is fine if the PC
stays awake; sleep/hibernate/shutdown or loss of internet prevents replies. A missing reply
is not proof monitoring stopped. There is no external always-online watchdog in this build.

Commands older than five minutes or predating activation are ignored. A burst of valid
commands in one poll receives one latest reply. The update cursor is saved before reply
sending to avoid duplicate replay after a restart; a failed/interrupted reply is not retried
for that command. Send /status again. Delivery errors appear in Telegram settings.
Read-only commands cannot START, STOP, reload, select dates, reset locks or book anything.

PRESERVED
All V11.46 HTTP2/navigation-error alerts, tab closure handling and health checks remain.
8-second COV wait; LMV/MCWG selection; displayed-month green Wednesday/Thursday dates;
positive integer quota >=1; required date checks and final quota recheck; one-click booking
lock; manual OTP/final confirmation; sound, Telegram, monitor window and seven-stage tracker.
Primary recipient 7930718570 unchanged. Audio and booking-rules files are byte-identical.

ADDITIONAL CORRECTION
The popup/monitor no longer labels a stopped COV loop simply STOPPED while its manual-OTP
receipt watcher remains active: it shows MANUAL OTP or WATCHING. This label does not assert
that the tab is responsive; /status performs the fresh check. Corrected obsolete primary-ID
help text. No unrelated booking algorithm changes were made.

TESTS
15 offline Node suites and six production JavaScript syntax checks passed. Includes actual
worker loading of the status module, mocked Telegram APIs, authorization, duplicate handling,
configuration-change races, state reports and a 301-cycle synthetic counter test. No live
Sarathi booking, Telegram delivery, Windows 7/Chrome109 installation or audio test was run.
Read TEST_RESULTS_V11_47.txt. Older release notes are kept under archive/.

ROLLBACK
STOP; turn OFF the status receiver; restore the backed-up V11.46 files in the same folder,
Reload and refresh Sarathi. Review any booking outcome before restarting.
