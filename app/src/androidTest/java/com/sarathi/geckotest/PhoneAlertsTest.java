package com.sarathi.geckotest;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.service.notification.StatusBarNotification;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.ActivityTestRule;
import androidx.test.rule.GrantPermissionRule;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.json.JSONObject;
import java.lang.reflect.Method;
import org.junit.runner.RunWith;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class PhoneAlertsTest {
    @org.junit.Rule public ActivityTestRule<MainActivity> activity = new ActivityTestRule<>(MainActivity.class);
    @org.junit.Rule public GrantPermissionRule permission = GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS);
    @Test public void applicantAlertPreferencesAndDeliveryAreIndependent() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        PhoneAlerts first = new PhoneAlerts(context, 1);
        PhoneAlerts second = new PhoneAlerts(context, 2);
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        first.stop(); second.stop();
        first.clearNotification(); second.clearNotification();

        first.setEnabled(false);
        assertFalse(first.isEnabled());
        assertTrue("Disabling applicant 1 must not disable applicant 2", second.isEnabled());

        first.notify("Monitoring needs attention", "Applicant 1 warning");
        second.notify("Monitoring needs attention", "Applicant 2 warning");
        assertNull("Disabled applicant must not post a notification", find(manager, 1101));
        StatusBarNotification delivered = awaitNotification(manager, 1102);
        assertNotNull("Enabled applicant should deliver its notification", delivered);
        Notification n = delivered.getNotification();
        assertEquals("Applicant 2 · Monitoring needs attention", n.extras.getString(Notification.EXTRA_TITLE));
        assertEquals("Applicant 2 warning", n.extras.getString(Notification.EXTRA_TEXT));

        first.setEnabled(true);
        first.notify("Slot detected", "LMV quota 3 detected");
        delivered = awaitNotification(manager, 1101);
        assertNotNull(delivered);
        assertEquals("Applicant 1 · Slot detected", delivered.getNotification().extras.getString(Notification.EXTRA_TITLE));
        assertEquals("LMV quota 3 detected", delivered.getNotification().extras.getString(Notification.EXTRA_TEXT));

        first.clearNotification(); second.clearNotification();
        first.stop(); second.stop();
    }

    @Test public void audioModesRespectPerApplicantEnableAndStop() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        PhoneAlerts first = new PhoneAlerts(context, 3);
        PhoneAlerts second = new PhoneAlerts(context, 4);
        first.setEnabled(false);
        first.audio("START", true, 85);
        assertFalse("Disabled alert mode must not start repeating audio", first.isRepeating());
        second.audio("START", true, 85);
        assertTrue(second.isRepeating());
        second.audio("STOP", false, 85);
        assertFalse(second.isRepeating());
        first.setEnabled(true);
        first.audio("START", true, 85);
        assertTrue(first.isRepeating());
        first.audio("STOP", false, 85);
        assertFalse(first.isRepeating());
    }


    @Test public void allCriticalKindsOverrideRoutineOffAndSilenceStopsAlarm() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        PhoneAlerts alerts = new PhoneAlerts(context, 5);
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        String originalMode = alerts.mode();
        alerts.stop();
        alerts.clearNotification();
        alerts.setMode("ROUTINE OFF");
        try {
            alerts.notify("Routine", "This must remain muted");
            alerts.audio("START", true, 85);
            assertFalse("ROUTINE OFF must mute routine audio", alerts.isRepeating());
            assertNull("ROUTINE OFF must mute routine notifications", find(manager, 1105));

            String[] kinds = {"SMS_READY", "GREEN_DATE", "CALENDAR", "SESSION_EXPIRED", "MONITOR_FAILED", "PROTECTED_PAGE_LOST"};
            for (String kind : kinds) {
                alerts.stop();
                alerts.clearNotification();
                alerts.newRun();
                alerts.critical(kind, "Critical test: " + kind);
                assertTrue(kind + " must start a repeating critical alarm", alerts.isRepeating());
                StatusBarNotification delivered = awaitNotification(manager, 1105);
                assertNotNull(kind + " must post a notification", delivered);
                assertEquals("sarathi-critical-v36", delivered.getNotification().getChannelId());
                assertEquals("Applicant 5 · " + kind, delivered.getNotification().extras.getString(Notification.EXTRA_TITLE));
                assertEquals("Critical test: " + kind, delivered.getNotification().extras.getString(Notification.EXTRA_TEXT));

                // Mirrors the native Silence acknowledgement: stop audio and clear the alert.
                alerts.stop();
                alerts.clearNotification();
                assertFalse(kind + " must stop after Silence", alerts.isRepeating());
                assertNull(kind + " notification must clear after Silence", find(manager, 1105));
            }
        } finally {
            alerts.stop();
            alerts.clearNotification();
            alerts.setMode(originalMode);
        }
    }

    @Test public void extensionCriticalMessageReachesNativePhoneAlerts() throws Exception {
        MainActivity main = activity.getActivity();
        long deadline = System.currentTimeMillis() + 30000;
        while (main.serviceForTest() == null && System.currentTimeMillis() < deadline) Thread.sleep(200);
        MonitorService service = main.serviceForTest();
        assertNotNull("Monitoring service should bind", service);
        BrowserEngine engine = service.profile(1);
        PhoneAlerts alerts = engine.alerts;
        String originalMode = alerts.mode();
        alerts.stop();
        alerts.clearNotification();
        alerts.newRun();
        alerts.setMode("ROUTINE OFF");
        try {
            JSONObject message = new JSONObject();
            message.put("type", "CRITICAL");
            message.put("kind", "GREEN_DATE");
            message.put("message", "Extension critical route test");
            Method receive = BrowserEngine.class.getDeclaredMethod("receive", Object.class);
            receive.setAccessible(true);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                try { receive.invoke(engine, message); }
                catch (Exception e) { throw new RuntimeException(e); }
            });
            assertTrue("Extension CRITICAL must reach PhoneAlerts.critical()", alerts.isRepeating());
            StatusBarNotification delivered = awaitNotification(
                service.getSystemService(NotificationManager.class), 1101);
            assertNotNull("Extension CRITICAL must post the native notification", delivered);
            assertEquals("sarathi-critical-v36", delivered.getNotification().getChannelId());
            assertEquals("Applicant 1 · GREEN_DATE", delivered.getNotification().extras.getString(Notification.EXTRA_TITLE));
        } finally {
            alerts.stop();
            alerts.clearNotification();
            alerts.setMode(originalMode);
        }
    }

    private static StatusBarNotification awaitNotification(NotificationManager manager, int id) {
        long deadline = android.os.SystemClock.elapsedRealtime() + 3000;
        StatusBarNotification item;
        do {
            item = find(manager, id);
            if (item != null) return item;
            try { Thread.sleep(50); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for notification delivery", e);
            }
        } while (android.os.SystemClock.elapsedRealtime() < deadline);
        return find(manager, id);
    }

    private static StatusBarNotification find(NotificationManager manager, int id) {
        for (StatusBarNotification item : manager.getActiveNotifications())
            if (item.getId() == id) return item;
        return null;
    }
}
