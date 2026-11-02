package com.sarathi.geckotest;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.ActivityTestRule;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class MonitorDiagnosticsTest {
    @Rule public ActivityTestRule<MainActivity> activity = new ActivityTestRule<>(MainActivity.class);

    @Test public void diagnosticsExposeIndependentClockAndApplicantHealth() throws Exception {
        MainActivity main = activity.getActivity();
        long deadline = System.currentTimeMillis() + 30000;
        while (main.serviceForTest() == null && System.currentTimeMillis() < deadline)
            Thread.sleep(200);
        MonitorService service = main.serviceForTest();
        assertNotNull("Monitoring service should bind", service);

        String report = service.diagnosticReport();
        assertTrue(report, report.contains("KeepAlive:"));
        assertTrue(report, report.contains("clock gap max="));
        assertTrue(report, report.contains("main age="));
        assertTrue(report, report.contains("Applicant 1"));
        assertTrue(report, report.contains("longest tick gap"));
        assertTrue(report, report.contains("screen-off max gap"));
    }

    @Test public void foregroundNotificationChangesAreThrottledAndUnchangedTextIsNeverReposted() {
        assertFalse(MonitorService.shouldUpdateForegroundNotificationForTest("same", "same", 50000, 40000));
        assertFalse(MonitorService.shouldUpdateForegroundNotificationForTest("old", "new", 49000, 40000));
        assertTrue(MonitorService.shouldUpdateForegroundNotificationForTest("old", "new", 50000, 40000));
        assertTrue(MonitorService.shouldUpdateForegroundNotificationForTest("", "first", 1, 0));
    }

    @Test public void criticalEventBufferIsSeparateNeverTrimmedAndShownFirst() throws Exception {
        MainActivity main = activity.getActivity();
        long deadline = System.currentTimeMillis() + 30000;
        while (main.serviceForTest() == null && System.currentTimeMillis() < deadline) Thread.sleep(200);
        MonitorService service = main.serviceForTest();
        assertNotNull(service);
        String oldest = "SESSION critical-buffer-oldest-marker";
        service.record(oldest);
        service.record("DISARM critical-buffer-disarm-marker");
        service.record("STALL critical-buffer-stall-marker");
        service.record("ALERT critical-buffer-alert-marker");
        service.record("Applicant 4 page unloaded, login will be needed");
        for (int i = 0; i < 2200; i++) service.record("routine-retention-" + i);
        String critical = service.getSharedPreferences("diagnostics", 0).getString("criticalEvents", "");
        assertTrue("Old critical SESSION event must survive routine log trimming", critical.contains(oldest));
        assertTrue(critical.contains("DISARM critical-buffer-disarm-marker"));
        assertTrue(critical.contains("STALL critical-buffer-stall-marker"));
        assertTrue(critical.contains("ALERT critical-buffer-alert-marker"));
        assertTrue(critical.contains("Applicant 4 page unloaded, login will be needed"));
        String report = service.diagnosticReport();
        assertTrue("Critical events must appear before ordinary diagnostics", report.indexOf("CRITICAL EVENTS") < report.indexOf("KeepAlive:"));
        assertTrue(report.contains(oldest));
    }

    @Test public void repeatedScreenOffAndOnEventsAreCollapsed() throws Exception {
        MainActivity main = activity.getActivity();
        long deadline = System.currentTimeMillis() + 30000;
        while (main.serviceForTest() == null && System.currentTimeMillis() < deadline) Thread.sleep(200);
        MonitorService service = main.serviceForTest();
        assertNotNull(service);
        android.content.SharedPreferences prefs = service.getSharedPreferences("diagnostics", 0);
        String original = prefs.getString("events", "");
        try {
            prefs.edit().putString("events", "").commit();
            service.record("Screen OFF at elapsed=111");
            service.record("Screen OFF at elapsed=222");
            service.record("Screen ON at elapsed=333");
            service.record("Screen ON at elapsed=444");
            String events = prefs.getString("events", "");
            assertTrue("First minimize transition must be retained", events.contains("Screen OFF at elapsed=111"));
            assertFalse("Repeated minimize transition must be collapsed", events.contains("Screen OFF at elapsed=222"));
            assertTrue("First resume transition must be retained", events.contains("Screen ON at elapsed=333"));
            assertFalse("Repeated resume transition must be collapsed", events.contains("Screen ON at elapsed=444"));
        } finally { prefs.edit().putString("events", original).commit(); }
    }

    @Test public void audioModeNamesIdentifyCallAndRingtoneModes() throws Exception {
        assertEquals("IN_CALL", MonitorService.audioModeNameForTest(android.media.AudioManager.MODE_IN_CALL));
        assertEquals("RINGTONE", MonitorService.audioModeNameForTest(android.media.AudioManager.MODE_RINGTONE));
        MainActivity main = activity.getActivity();
        long deadline = System.currentTimeMillis() + 30000;
        while (main.serviceForTest() == null && System.currentTimeMillis() < deadline) Thread.sleep(200);
        MonitorService service = main.serviceForTest();
        assertNotNull(service);
        service.recordAudioModeForTest(android.media.AudioManager.MODE_NORMAL);
        service.recordAudioModeForTest(android.media.AudioManager.MODE_IN_CALL);
        service.recordAudioModeForTest(android.media.AudioManager.MODE_RINGTONE);
        String events = service.getSharedPreferences("diagnostics", 0).getString("events", "");
        assertTrue(events, events.contains(" -> IN_CALL"));
        assertTrue(events, events.contains("IN_CALL -> RINGTONE"));
    }

    private static int occurrences(String value, String token) {
        int count = 0, from = 0;
        while ((from = value.indexOf(token, from)) >= 0) { count++; from += token.length(); }
        return count;
    }

    @Test public void diagnosticTrimmingKeepsWholeLinesAndNewestCompleteEvents() {
        String trimmed = MonitorService.trimDiagnosticHistory("first-line\\nsecond-line\\nthird-line\\n", 23);
        assertEquals("second-line\\nthird-line\\n", trimmed);
        assertFalse("Trimmed history must not begin with a partial line", trimmed.startsWith("rst-line"));
        assertTrue("Newest complete line must remain", trimmed.endsWith("third-line\\n"));
    }

    @Test public void repeatedActivityTransitionsAndHistoricalExitLinesAreCollapsed() throws Exception {
        MainActivity main = activity.getActivity();
        long deadline = System.currentTimeMillis() + 30000;
        while (main.serviceForTest() == null && System.currentTimeMillis() < deadline) Thread.sleep(200);
        MonitorService service = main.serviceForTest();
        assertNotNull(service);
        android.content.SharedPreferences prefs = service.getSharedPreferences("diagnostics", 0);
        String original = prefs.getString("events", "");
        try {
            prefs.edit().putString("events", "").commit();
            service.record("Activity paused; first");
            service.record("Activity paused; duplicate");
            service.record("Activity resumed; first");
            service.record("Activity resumed; duplicate");
            service.record("historicalExit reason=1 first");
            service.record("historicalExit reason=2 duplicate");
            String events = prefs.getString("events", "");
            assertTrue(events.contains("Activity paused; first"));
            assertFalse(events.contains("Activity paused; duplicate"));
            assertTrue(events.contains("Activity resumed; first"));
            assertFalse(events.contains("Activity resumed; duplicate"));
            assertTrue(events.contains("historicalExit reason=1 first"));
            assertFalse(events.contains("historicalExit reason=2 duplicate"));
        } finally { prefs.edit().putString("events", original).commit(); }
    }

    @Test public void disarmDiagnosticsUseTerminalReasonNotStalePageReason() throws Exception {
        MainActivity main = activity.getActivity();
        long deadline = System.currentTimeMillis() + 30000;
        while (main.serviceForTest() == null && System.currentTimeMillis() < deadline) Thread.sleep(200);
        MonitorService service = main.serviceForTest();
        assertNotNull(service);
        BrowserEngine engine = service.profile(9);
        engine.lastPageReason = "COV selection ready";
        java.lang.reflect.Field reason = MonitorRun.class.getDeclaredField("lastReason");
        reason.setAccessible(true);
        reason.set(engine.run, "Session expired");
        assertTrue("DISARM diagnostics must report the terminal reason",
            engine.run.diagnostics().contains("reason=Session expired"));
    }

    @Test public void diagnosticEventHistoryRemainsBounded() throws Exception {
        MainActivity main = activity.getActivity();
        long deadline = System.currentTimeMillis() + 30000;
        while (main.serviceForTest() == null && System.currentTimeMillis() < deadline)
            Thread.sleep(200);
        MonitorService service = main.serviceForTest();
        assertNotNull(service);

        String payload = "diagnostic-retention-check-0123456789";
        for (int i = 0; i < 2200; i++) service.record(payload + "-" + i);
        String events = service.getSharedPreferences("diagnostics", 0).getString("events", "");
        assertTrue("Diagnostic history must be capped at 64 KiB", events.length() <= 65536);
        assertTrue("Newest diagnostics should be retained", events.contains(payload + "-2199"));
    }
}
