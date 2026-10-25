MPARIVAHAN ANANTNAG V11.44.0
Chrome desktop extension — upgrade from V11.43.
Read RELEASE_V11_44.txt for changes and test limitations.

UPGRADE WHILE RETAINING SETTINGS
1. Stop monitoring. Keep a backup of your current extension folder and ZIP.
2. Close the extension popup and separate monitor window.
3. Extract this ZIP and copy its contents into the SAME existing unpacked
   extension folder, replacing the old files. manifest.json must be at its root.
4. Open chrome://extensions and click Reload on that extension.
5. Confirm version 11.44.0. Do not run two versions simultaneously.
6. Refresh the Sarathi tab before restarting; this replaces old page scripts.
   Complete any required login/CAPTCHA manually.
7. Open the extension and use TEST ALERTS; confirm phone and desktop delivery.
8. For automation up to SMS, enable assisted calendar booking, then START.
   OTP and final confirmation remain manual.

NEW INSTALL
Extract ZIP; chrome://extensions -> Developer mode -> Load unpacked -> choose
folder containing manifest.json. Grant site access for sarathi.parivahan.gov.in.
A different folder/extension identity may not retain old Telegram settings.

BEHAVIOUR
Eight-second minimum wait before Proceed; site response time adds to total.
Only displayed-month green Wednesdays/Thursdays are scanned. Existing quota
rules require verified positive availability (>=1). Stops on ambiguous data.
Attempts count 9 AM–9 PM computer local time; monitoring itself is not restricted
by that counting window. Keep Chrome and Sarathi open; PC sleep stops activity.

TESTING
Eleven offline Node test suites passed. Live booking and live alerts were not tested
here. Included historic release files describe previous builds, not new proof.

V11.44 requires a verified quota-page date before automatic booking.
The configured primary Telegram default is intentionally preserved.

BACKGROUND MONITORING CHECK
After upgrading, leave Sarathi open and switch to another tab for at least
10 minutes. Check that attempts and last-check time continue to advance.
The monitor window may be closed. Keep Chrome and the computer awake.
Report the latest activity and attempt count if the loop stalls again.
This release uses background-worker waits; live Chrome 109 verification
is still required. An exact 8-second cadence is not guaranteed under load.
