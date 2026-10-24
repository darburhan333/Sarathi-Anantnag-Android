# Sarathi Gecko access test 1.0

Separate ARM64 Android 8+ test app, using Mozilla GeckoView stable 158.0.20261021001945.
Starts at https://parivahan.gov.in/. Navigate manually to Sarathi and slot booking.
No monitoring, auto-clicking, CAPTCHA handling, Telegram, session sharing, or user-agent override.
Copy log includes engine version, Android API, navigation paths (no query or fragment),
page title and load result. Review logs before sharing. Loading success alone does not establish
that the appointment form appeared: a website error page can load successfully.
Use a screenshot to confirm the final result. Website compatibility is not yet device-tested.
Installs separately as com.sarathi.geckotest. GitHub Actions creates a debug-signed APK.
