package com.sarathi.geckotest;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.service.notification.StatusBarNotification;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.GrantPermissionRule;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class PhoneAlertsTest {
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
        StatusBarNotification delivered = find(manager, 1102);
        assertNotNull("Enabled applicant should deliver its notification", delivered);
        Notification n = delivered.getNotification();
        assertEquals("Applicant 2 · Monitoring needs attention", n.extras.getString(Notification.EXTRA_TITLE));
        assertEquals("Applicant 2 warning", n.extras.getString(Notification.EXTRA_TEXT));

        first.setEnabled(true);
        first.notify("Slot detected", "LMV quota 3 detected");
        delivered = find(manager, 1101);
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

    private static StatusBarNotification find(NotificationManager manager, int id) {
        for (StatusBarNotification item : manager.getActiveNotifications())
            if (item.getId() == id) return item;
        return null;
    }
}
