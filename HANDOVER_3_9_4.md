# MPARIVAHAN J&K 3.9.4-midnight-teal
Base: tested Run #241 runtime baf857983c20836a6749d6045b1c9dc37c9552a4, documentation head b034c21501d07032f61692199cfbcd54e307d958.

## Thirteen approved changes
1. Ignore unused website-session loading callbacks while monitoring owns another current session. Native PAGE_QUERY replies additionally prove the current GeckoSession and query token before changing readiness/protection/RTO.
2. Unreadable encrypted applicant records expose an explicit reset for that applicant only, followed by the editor. No plaintext fallback.
3. Details dialogs use route generations and disable conflicting edit actions while saving. Dismissed routes cannot reopen from an old save/delete callback.
4. Critical history is append-only in private critical-history.log, written serially off the UI thread. Existing criticalEvents migrate before removal. UI/Copy Logs show a bounded recent critical tail; Logs > Export critical history saves the full archive through the Android file picker. Ordinary diagnostics remain bounded; archive events are never intentionally trimmed.
5. KeepAlive reuses its existing overlay. Queued show/hide work checks the current requested state, and main-thread stop removes synchronously, preventing stale removal after restart. Android owns its displaying-over-other-apps notice: this release cannot promise to hide it or display it only once. Overlay/silent audio/locks remain in place while required.
6. Replace fixed Anantnag with per-applicant RTO detected from explicitly named fields, selected RTO option text, or RTO/authority labels on a natively verified COV page. Missing/ambiguous data displays RTO not detected. No RTO value inferred from applicant number or personal details. Clear on Home, expiry and website loss/unload; retain during the same booking flow.
7. Device alert options use readable light text on teal selected/dropdown surfaces, matching native buttons and one overall scrolling dialog.
8. Ongoing foreground notification shows Monitoring: A1 · A4… using armed applicants only, without attempt counts or alert-mode labels. Critical alarm notifications and delivery behavior remain separate and unchanged.
9. Main actions are 3 × 2: Stop All / Start All / Home; Monitor-Website / Silence All / Call. Fill Details sits at the left of the applicant status bar below these rows.
10. Long-press Fill Details fills only the selected applicant's saved application number/DOB on the exact eligible stopped foreground form. Tap still opens the details panel. Native session token, no conflicting overwrite, manual CAPTCHA and Submit preserved.
11. Long-press Stop All stops only the selected applicant; ordinary tap stops all.
12. Long-press Start All prepares/starts only the selected applicant through the existing COV readiness and booking-lock gates; ordinary tap prepares all eligible applicants. A newer batch cancels the older pending batch.
13. Developer credit opens https://www.facebook.com/share/14u4TJG1Mn1/ via Android ACTION_VIEW. App logo remains branding; settings remain on hamburger.

## Identity and validation
Package, release certificate, nine context/extension IDs and GeckoView version unchanged. VersionCode 394, versionName 3.9.4-midnight-teal; production extension 3.4.17/debug 3.4.18. Homepage remains Sarathi stateSelection.do; COV target and minimum click guard remain seven seconds. Manual CAPTCHA/OTP and booking safeguards preserved.
Local suites: JS syntax, existing booking/adapter/isolation/fill/301-cycle fixtures; new RTO helper selected-option/label/ambiguity tests and live monitor RTO rendering. Android integration adds unused-session ownership, selected-only Start initialization, RTO/notification isolation, corrupt-details deletion and append-only archive retention. Existing seven CI gates/timeouts retained, focused suite expanded; signed minified release smoke checks exact 3 × 2 geometry plus status-bar Details position. Emulator/release results pending until the build completes. Physical Infinix Android 16 and real Sarathi RTO/field acceptance remain device verification, not claimed passed.
