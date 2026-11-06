# MPARIVAHAN J&K 3.9.3-midnight-teal
Base: Run #238, commit 904658fc990bab945f095f3d555ea4bbb0941833; all seven release jobs passed.

## Ten approved improvements
1. Settings > Applicant Details: per-applicant application number and DOB, edit/delete, encrypted native Android Keystore AES-GCM storage. No credentials or applicant values in diagnostic logs or extension storage.
2. Selected applicant Fill Details panel: individual sensitive clipboard Copy actions and explicit one-tap field entry. Requires selected, visible, stopped website; a short-lived native token is bound to that GeckoSession and consumed once. Only the exact HTTPS Sarathi /slots/dlslotbook.do form posting to /slots/dldetsubmit.do is eligible. Captured field IDs applno/dob/dlslotipform_subtype1. Does not overwrite conflicting values, change disabled/read-only fields, navigate, solve CAPTCHA, enter OTP or submit. Website acceptance still requires a user device check.
3. Two rows: Stop All / Start All / Home / Fill Details; Monitor / Silence All / Call. Buttons increased from 42dp to 48dp baseline, font 12 to 12.5sp; large-font growth preserved.
4. Start All prepares unopened dashboard controls temporarily without changing the applicant or visible website. Existing ready-only COV and booking safety checks preserved; pending setup is cancelled on Stop.
5. Home recreating an unavailable page loads the homepage once rather than twice. Protected-page confirmation retained.
6. Service startup opens the persisted selected applicant instead of always opening Applicant 1 first. Shared GeckoRuntime retained; no preload of all nine websites.
7. Repeated background-setup system checks cached for five seconds and invalidated on resume; memory-pressure query skipped when no unloadable applicant exists. Monitoring clock cadence and priority policy retained, except temporary hidden dashboard initialization for explicit Start All.
8. Website loading progress, actual first Sarathi document timing separate from initial browser blank document, and measured retained/new applicant view attachment timing. No claimed server/network or physical-phone speed guarantee.
9. Selector uses distinct mint/teal selected row, medium font and check icon. Other applicant rows remain distinct.
10. Consistent native vector icons before all seven action labels.

## Preserved behavior and identity
Package com.sarathi.anantnag.gecko.v33, production signing certificate c7404cb6be4325298d64a82d00f27ffa4fefaf182b0a569b4ed2d91fc072b145, extension IDs and nine isolated contexts unchanged. KeepAlive overlay/silent audio, foreground service and locks retained. State-selection homepage and 7-second COV target/guards unchanged. Manual CAPTCHA, OTP and final confirmation preserved. Applicant details require explicit user fill; no automatic login or form replay.
VersionCode 393; versionName 3.9.3-midnight-teal; production extension 3.4.15/debug 3.4.16.

## Validation
Local verification suites include a new real-helper VM test for fill ownership, strict URL/form, leap/date validation, read-only/disabled/conflicting fields, no CAPTCHA or submit, and one-use tokens. Native emulator tests cover actual Keystore isolation/deletion and cold dashboard Start All without view switching; exact layout, leading icons and selected-row highlight assertions updated. Signed minified release smoke checks all nine applicant bridges/Alerts, seven-button row order and native details screens. All existing release gates and timeouts retained. Canonical build and emulator results pending at this commit. Physical Infinix X6870 Android 16 and actual Sarathi form acceptance are not claimed as tested.
