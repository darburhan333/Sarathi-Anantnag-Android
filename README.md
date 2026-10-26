# MPARIVAHAN SLOT BOOKING -J&K 3.2

Nine independent applicant containers, built from V3.1. The website engine and package identity are unchanged. Release versionCode is 320; use the existing private release certificate for in-place updates.

## Controls

Rows 1 and 2 contain applicants 1–4 and 5–8. Row 3 contains applicant 9, Background setup, Phone numbers and Copy log. Row 4 contains the bold Monitor/Website toggle, Stop, Silence and Home. The toggle changes colour with the displayed view. Status stays below the controls. Long-press Stop for a Stop all confirmation; the ongoing notification also has STOP ALL. Long-press an applicant to save or open its encrypted phone number in the dialer. No automatic calling.

## Monitoring and recovery

- One user-started specialUse foreground service owns all runs, independently of Activity visibility. START requires notifications, Doze exemption and Battery Saver off.
- An independent native HandlerThread schedules waits, renews the bounded CPU wake lock and posts coalesced UI-thread health checks. Gecko operations and ownership transitions remain on the main thread.
- A scheduling interruption pauses action authorization and requests a fresh owned-page health check. Only a still-running COV loop in the same applicant/session, with no calendar lock, expiry or uncertain booking, can release held waits. Every action retains the original document/session gate. Recovery never reloads or resubmits a form. Failed recovery stops once with an alert. Repeated recovery without actual progress is rejected.
- Killed/closed browser sessions are removed and replaced on return with a fresh Home page. Login/form recovery is manual. A dead idle dashboard or unused page does not stop another run.
- Home revokes native authorization first, then waits at most 1.2 seconds for STOP acknowledgement. It works even with a disconnected extension.
- Idle/duplicate browser callbacks do not alert. Old native ports cannot update state. Cold launch records interrupted previous runs in the dashboard without sounding a fresh stop alarm. No automation resumes after process death.
- Copy log includes Android PID separately from service instance ID, exit reason/detail, independent clock and main-thread progress, screen state, current foreground-service/wake state and per-applicant longest tick gaps.

## Performance and retained features

Website containers open lazily; dashboards open only when requested. The runtime/engine is shared, and only the current website for each armed applicant is kept active at high priority. Nine active applicants still require more RAM and network traffic than one.

The original 8-second COV wait, LMV/MCWG/HMV label selection, popup Back handling, displayed-month calendar rules, quota checks, one-click booking protections and manual OTP/CAPTCHA/final confirmation remain. HMV support concerns COV selection, not additional assisted quota rules. Assisted booking remains off by default. Telegram supports two chat IDs with one encrypted token; configure the status-command receiver only on Applicant 1 and only one device per bot. Network handoff/loss still requires inspection and manual restart. No direct Sarathi endpoint automation or browser identity spoofing is used.

The arm64 release uses code/resource shrinking and compressed native libraries. GeckoView contributes most of the APK size. Signing keys and Telegram credentials must never enter this repository.

## Build and validation

`python3 scripts/verify.py` runs adapter, lifecycle and baseline content regression tests. `gradle assembleRelease lintRelease` builds the arm64 APK. Profile preparation generates applicants 2–9 from one extension source. `-PtestAbi=x86_64` selects emulator architecture. `-PsmokeRelease` signs a minified build with a debug key strictly for emulator testing, not distribution.

CI runs real Gecko extension fixtures, nine-container cookie/localStorage/IndexedDB isolation, locked-screen and forced-Doze HTTP progress, independent Stop, Activity recreation, idle browser loss without alerts, disconnected Home, stale recovery rejection, wait cancellation and encrypted settings/phone storage. Release UI startup is exercised across all nine applicants.

These are emulator and offline-fixture tests. They do not prove uninterrupted monitoring under Infinix firmware, live Sarathi availability, Telegram delivery or booking success. Test minimized screen-on and locked-screen operation separately on the target phone, including 30-minute and two-hour runs, before relying on background monitoring. Force-stop/reboot always ends monitoring.
