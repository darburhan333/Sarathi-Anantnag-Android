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

## Run #242 evidence and additional lifecycle fix
Run #242 runtime 7a8332ff4173dd31b77e5752dc50abeb9db999bb passed compile, signed ARM64/lint, notification Stop, 38/38 focused lifecycle tests and signed/minified release UI (all nine applicants, exact 3×2 controls, status-bar Details and landscape). UI attempt1 failed only in an emulator SDK ZIP download; unchanged retry passed.
The full job then passed its nine-applicant background/Doze case and long realExtensionLoop, but froze on subsequent Activity teardown: main-thread dump shows ViewRootImpl clearing virtual accessibility focus through SessionAccessibility.NativeProvider.getNodeClassName. The job hit the unchanged 55-minute deadline with subsequent Activity launch failures; this candidate is NOT a passed release.
Fix: visible/owned/live-session accessibility provider guard in ApplicantGeckoView; clear Android virtual focus before releasing a session; detach browser views in finish() before Android's teardown traversal; prevent finishing render callbacks from reattaching them. Pause hides the accessibility provider while retaining the monitoring session and its activity policy. Foreground accessibility remains available. Two native lifecycle regressions added; all prior assertions, timed background cases, seven jobs and timeouts retained. Final validation pending on the follow-up commit.

## Run 245 native crash investigation
Release UI failed while querying Alerts accessibility. Exact x86_64 libxul build ID 29c6410cc13b15b2e8ab2040d523549d58a35d2c was symbolicated using Mozilla symbols: GetNodeInfo -> PopulateNodeInfo -> JNI PopulateNodeInfo callback. It is not the BrowserParent teardown signature.
The next change invalidates cached accessibility providers on ownership/visibility changes and guards every provider operation, not just provider lookup. It removes the explicit host ACTION_CLEAR_ACCESSIBILITY_FOCUS and clears host focus only after session detachment. Visible current pages retain normal accessibility. Hidden session retention and booking rules are unchanged. Existing lifecycle test now exercises cached provider calls after hide and re-show. All original release UI assertions and gates remain required. Validation pending.

Follow-up before validation: Native PopulateNodeInfo invokes root View metadata on the Gecko thread. The earlier provider lookup called GeckoSession.getAccessibility()/SessionAccessibility.getView(), which assert UI-thread ownership. Provider lookup now returns only its prebuilt volatile guarded provider on non-UI threads, never calls Gecko UiThread APIs and never blocks waiting for UI. Provider operations still require the UI thread and current epoch/ownership. Existing hidden-surface regression additionally requests metadata from a background thread with a bounded three-second Future, asserting a visible provider is returned without a thread violation.

## Verified release: run 247
- Runtime commit: 93683354e6ea9407e01d1d99fc8f78c3ef11b403.
- CI: https://github.com/darburhan333/Sarathi-Anantnag-Android/actions/runs/42259453154
- All seven jobs passed: compile, APK/lint/signing, release UI, lifecycle preflight, notification Stop, main integration, verified release.
- Lifecycle preflight: 40/40; notification Stop: 1/1; full integration: 70/70 (0 skipped, 0 failed), 40m42s. Main integration then repeated the minified release UI checks successfully.
- Release UI verified Alerts for all nine applicants, Logs isolation, 3x2 controls, status-bar Fill Details, and landscape space.
- Immutable TESTS-PASSED artifact: 13527279526. APK bytes: 91758277.
- APK SHA-256: 55b232b7c79c7b150b4f550eb2da7a1a6183ba141151df815dc2d565b7713479.
- Certificate SHA-256: c7404cb6be4325298d64a82d00f27ffa4fefaf182b0a569b4ed2d91fc072b145 (existing release identity).
- Verified APK equals the earlier validated signed candidate byte for byte. ARM64, package com.sarathi.anantnag.gecko.v33, versionCode394/name3.9.4-midnight-teal, source marker9368335; all nine production extension IDs/version3.4.17/scripts verified.
- Physical Infinix X6870 / Android16 background behavior still needs real-device validation; emulator passes are not a physical-phone pass.
- Android owns the displaying-over-other-apps system notice; the app cannot guarantee that it appears only once. Overlay lifecycle races were fixed without removing keep-alive protection.
