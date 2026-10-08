# Sarathi Anantnag LMV Slot Monitor — background version

This version has no popup, no desktop notifications, and no notification permission.

It runs as a Manifest V3 background service worker plus a content script on Sarathi pages. You can leave the Sarathi appointment tab open and switch to other Chrome tabs.

Target flow:
Jammu & Kashmir → Appointments → Anantnag → Anantnag → LMV

The extension only reads text already rendered by the official Sarathi page. It does not call protected slot endpoints, bypass SSL1001, spoof headers, click controls, submit forms, book appointments, or use AccessibilityService.

Important: Chrome may suspend/throttle inactive tabs, and Sarathi may return SSL1001/503 when a protected endpoint is accessed outside the normal portal flow. This extension cannot bypass that server-side protection.

Install:
Chrome → Extensions → Manage Extensions → Developer mode → Load unpacked → select this folder.

After installation, open the official Sarathi site in one tab, complete the normal appointment flow to the Anantnag/LMV page, then switch to another tab.