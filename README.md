# MPARIVAHAN SLOT BOOKING -J&K 3.4

This update keeps the v3.3 package (`com.sarathi.anantnag.gecko.v33`) and repository release signing secrets. Version code: 340.

- Timing stalls pause native action permission and verify the same applicant/session. Four attempts use 5/15/30-second backoff; unsuccessful recovery alerts and stops only that applicant. Booking/calendar locks still require manual review. No missed requests are replayed.
- Start All uses the existing guarded start flow, skips unopened/active/unready applicants and preserves uncertain booking locks. Stop cancels pending batch starts. Results appear in Copy log.
- Page-ready messages require a response from the owned content script. Loading and slow/unverified states no longer assert that navigation completion means usable content.
- Nine profiles, independent encrypted Telegram settings, COV support, phone options, Silence All and Stop All are retained. Header height adapts to landscape.
- Release smoke tests use a disposable emulator signing key; distributed APKs use repository release secrets only.

A foreground service and wake lock cannot guarantee uninterrupted execution under all manufacturer power policies. Recovery handling is not proof of continuous background polling. Validate on the affected Infinix device using elapsed/uptime gaps and actual page progress. Do not uninstall v3.3 when installing a same-certificate update.

## Previous documentation

# MPARIVAHAN SLOT BOOKING -J&K 3.3

Nine independent applicant containers, built from the completed V3.2 baseline. The website engine and package identity are unchanged. Release versionCode is 330; use the existing private release certificate for in-place updates.

## Controls

Rows 1 and 2 contain applicants 1–4 and 5–8. Row 3 contains applicant 9, Background setup, Phone numbers and Copy log. Row 4 contains the bold Monitor/Website toggle, Stop, Silence and Home. The toggle changes colour with the displayed view. Status stays below the controls. Long-press Stop for a Stop all confirmation; the ongoing notification also has STOP ALL. Long-press an applicant to save or call its encrypted phone number. The coloured Phone numbers list returns after saving, removing or cancelling an edit. Direct calling is optional and OFF by default; enable it in Phone numbers and grant CALL_PHONE. Otherwise long-press opens the dialer. Android controls the call screen. Long-press Silence silences all current alarms without stopping monitoring or disabling future alerts.

## Monitoring and recovery

- One user-started specialUse foreground service owns all runs, independently of Activity visibility. START requires notifications, Doze exemption and Battery Saver off.
- An independent native HandlerThread schedules waits, renews the bounded CPU wake lock and posts coalesced UI-thread health checks. Gecko operations and ownership transitions remain on the main thread.
- A scheduling interruption pauses action authorization and requests a fresh owned-page health check. Only a still-running COV loop in the same applicant/session, with no calendar lock, expiry or uncertain booking, can release held waits. Every action retains the original document/session gate. Recovery never reloads or resubmits a form. Foreground recovery failure stops once with an alert. When minimized, a detected scheduling/heartbeat/progress stall instead revokes authorization for ALL active applicants, cancels pending native waits and sends a safe STOP to each extension. It does not close/reload pages, retry recovery or restart on return. Idle applicants do not generate failure alarms. Already-submitted website requests cannot be undone; Android and the website can still expire or kill sessions. Repeated recovery without actual progress is rejected.
- Killed/closed browser sessions are removed and replaced on return with a fresh Home page. Login/form recovery is manual. A dead idle dashboard or unused page does not stop another run.
- Home revokes native authorization first, then waits at most 1.2 seconds for STOP acknowledgement. It works even with a disconnected extension.
- Idle/duplicate browser callbacks do not alert. Old native ports cannot update state. Cold launch records interrupted previous runs in the dashboard without sounding a fresh stop alarm. No automation resumes after process death.
- Copy log includes Android PID separately from service instance ID, exit reason/detail, independent clock and main-thread progress, screen state, current foreground-service/wake state and per-applicant longest tick gaps.

## Performance and retained features

Website containers open lazily; dashboards open only when requested. The runtime/engine is shared, and only the current website for each armed applicant is kept active at high priority. Nine active applicants still require more RAM and network traffic than one.

The original 8-second COV wait, LMV/MCWG/MCWOG/HMV/TRANS checkbox-row selection, popup Back handling, displayed-month calendar rules, quota checks, one-click booking protections and manual OTP/CAPTCHA/final confirmation remain. MCWOG/HMV/TRANS support concerns COV selection, not additional assisted quota rules; unsupported quota choices require manual handling. Assisted booking remains off by default. Each applicant has its own encrypted Telegram token and two chat IDs. Credentials are never copied to other applicants by default. Previous shared settings require explicit import into each chosen applicant. Remove clears only that applicant. Status polling is available per applicant, with one receiver lease per bot within this process; use separate bots for independent receivers. Delivery queues are bounded to 30 jobs / 10 minutes, with explicit 429 retry per recipient and timestamps for delayed messages. Ambiguous network/timeouts are not replayed because delivery may already have occurred. Network handoff/loss still requires inspection and manual restart. No direct Sarathi endpoint automation or browser identity spoofing is used.

The arm64 release uses code/resource shrinking and compressed native libraries. GeckoView contributes most of the APK size. Signing keys and Telegram credentials must never enter this repository.

## Build and validation

`python3 scripts/verify.py` runs adapter, lifecycle and baseline content regression tests. `gradle assembleRelease lintRelease` builds the arm64 APK. Profile preparation generates applicants 2–9 from one extension source. `-PtestAbi=x86_64` selects emulator architecture. `-PsmokeRelease` signs a minified build with a debug key strictly for emulator testing, not distribution.

CI runs real Gecko extension fixtures, nine-container cookie/localStorage/IndexedDB isolation, locked-screen and forced-Doze HTTP progress, independent Stop, Activity recreation, idle browser loss without alerts, disconnected Home, stale recovery rejection, wait cancellation and encrypted settings/phone storage. Release UI startup is exercised across all nine applicants.

These are emulator and offline-fixture tests. They do not prove uninterrupted monitoring under Infinix firmware, live Sarathi availability, Telegram delivery or booking success. Test minimized screen-on and locked-screen operation separately on the target phone, including 30-minute and two-hour runs, before relying on background monitoring. Force-stop/reboot always ends monitoring.

## V3.3 validation status

Local JavaScript/adapter regression suites passed, including checkbox-row COV parsing, nine applicant credential isolation, per-recipient Telegram retry/deduplication and recovery gates. Android compilation, lint and runtime test results must be checked in the current Actions run before distribution. The fixture test now exercises ten minutes minimized with the screen on; another test injects a background stall and checks all runs/waits stop without closing the page. These tests do not establish success on the physical Infinix phone.

Signing is performed outside the source tree with the existing release identity; CI exports an unsigned APK. No private key, password, applicant details or real Telegram credentials belong in commits.
