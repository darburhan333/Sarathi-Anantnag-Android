package com.sarathi.anantnag;

import android.accessibilityservice.AccessibilityService;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.core.app.NotificationCompat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class SarathiAccessibilityService extends AccessibilityService {
    private static final String CHROME = "com.android.chrome";
    private static final String CHANNEL_ID = "sarathi_slots";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private long lastAlert;
    private String lastSignature = "";

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            checkChrome();
            handler.postDelayed(this, 2500L);
        }
    };

    @Override public void onServiceConnected() {
        super.onServiceConnected();
        handler.removeCallbacks(poll);
        handler.post(poll);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event != null && CHROME.contentEquals(event.getPackageName())) checkChrome();
    }

    @Override public void onInterrupt() {}

    @Override public void onDestroy() {
        handler.removeCallbacks(poll);
        super.onDestroy();
    }

    private void checkChrome() {
        SharedPreferences p = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        if (!p.getBoolean(MainActivity.RUNNING, false)) return;

        AccessibilityNodeInfo root = null;
        try {
            AccessibilityNodeInfo active = getRootInActiveWindow();
            if (active != null && CHROME.contentEquals(active.getPackageName())) root = active;

            if (root == null && Build.VERSION.SDK_INT >= 21) {
                for (android.view.accessibility.AccessibilityWindowInfo w : getWindows()) {
                    AccessibilityNodeInfo r = w.getRoot();
                    if (r != null && CHROME.contentEquals(r.getPackageName())) {
                        root = r;
                        break;
                    }
                }
            }
        } catch (Exception ignored) {}

        if (root == null) return;

        String text = collect(root, new StringBuilder()).toString();
        String n = normalize(text);
        if (n.length() < 20 || !(n.contains("sarathi") || n.contains("parivahan"))) return;

        p.edit().putString("last_seen",
            new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date())).apply();

        boolean ssl = n.contains("ssl1001") ||
            (n.contains("slot booking is allowed") && n.contains("sarathi portal"));
        if (ssl) {
            lastSignature = "SSL1001";
            return;
        }

        boolean lmv = n.contains("light motor vehicle") || n.contains(" lmv ");
        boolean positive = n.contains("slot available") ||
            n.contains("available slots") ||
            n.contains("available quota") ||
            n.contains("quota available") ||
            n.contains("slots available");
        boolean negative = n.contains("no slot") ||
            n.contains("slot not available") ||
            n.contains("no slots available") ||
            n.contains("quota is not defined");

        if (lmv && positive && !negative) {
            String signature = "LMV|" + n;
            if (!signature.equals(lastSignature)) {
                lastSignature = signature;
                alert(compact(text));
            }
        }
    }

    private StringBuilder collect(AccessibilityNodeInfo node, StringBuilder out) {
        if (node == null) return out;
        CharSequence t = node.getText();
        CharSequence d = node.getContentDescription();
        if (t != null) out.append(t).append(' ');
        if (d != null) out.append(d).append(' ');
        for (int i = 0; i < node.getChildCount(); i++) collect(node.getChild(i), out);
        return out;
    }

    private String normalize(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private String compact(String s) {
        return normalize(s);
    }

    private void alert(String detail) {
        long now = System.currentTimeMillis();
        if (now - lastAlert < 120000L) return;
        lastAlert = now;

        Intent i = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
            this, 1, i,
            PendingIntent.FLAG_UPDATE_CURRENT |
            (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("🚨 Sarathi LMV slot detected")
            .setContentText("LMV availability detected on the Sarathi page.")
            .setStyle(new NotificationCompat.BigTextStyle().bigText(detail))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setAutoCancel(true)
            .setContentIntent(pi);

        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(1001, b.build());
    }
}