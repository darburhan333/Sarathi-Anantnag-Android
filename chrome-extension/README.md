# Sarathi Anantnag LMV Slot Monitor

Chrome Manifest V3 extension for passively monitoring the rendered official Sarathi Parivahan appointment page.

Target flow:
Jammu & Kashmir → Appointments → DL service → Anantnag → Anantnag → LMV

The extension observes visible page text after the user completes the normal Sarathi flow. It detects LMV plus positive availability wording and sends a Chrome notification.

It does NOT:
- call Sarathi slot endpoints directly
- bypass SSL1001 or portal restrictions
- spoof headers or browser identity
- click buttons
- submit forms
- book appointments
- use AccessibilityService
- control other applications

Important limitation: Chrome may throttle inactive tabs and Sarathi may change its page wording. This is a passive monitor, not a guaranteed real-time checker.

Install:
Chrome → Extensions → Manage Extensions → Developer mode → Load unpacked → select this chrome-extension folder.

Keep the Sarathi appointment tab open while monitoring.