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
