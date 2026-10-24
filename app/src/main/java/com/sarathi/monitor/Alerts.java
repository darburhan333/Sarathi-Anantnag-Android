package com.sarathi.monitor;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Vibrator;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/** Notification, looping alarm sound and Telegram message. Nothing here touches the Sarathi page. */
final class Alerts {
    static final String CH_MONITOR = "monitor";
    static final String CH_ALERT = "alerts_v1";
    static final int ID_FOREGROUND = 1;
    static final int ID_ALERT = 2;
    private static final long ALARM_MAX_MS = 10 * 60 * 1000L;

    private static MediaPlayer player;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Runnable AUTO_STOP = new Runnable() {
        @Override
        public void run() {
            stopAlarm(null);
        }
    };

    private Alerts() {}

    static void createChannels(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel mon = new NotificationChannel(CH_MONITOR, "Monitoring status", NotificationManager.IMPORTANCE_LOW);
        mon.setShowBadge(false);
        nm.createNotificationChannel(mon);

        NotificationChannel al = new NotificationChannel(CH_ALERT, "Slot alerts", NotificationManager.IMPORTANCE_HIGH);
        Uri sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        if (sound == null) sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        al.setSound(sound, new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build());
        al.enableVibration(true);
        al.setVibrationPattern(new long[]{0, 600, 300, 600});
        al.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(al);
    }

    private static PendingIntent openApp(Context c) {
        Intent i = new Intent(c, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(c, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static PendingIntent serviceAction(Context c, String action, int code) {
        Intent i = new Intent(c, MonitorService.class).setAction(action);
        return PendingIntent.getService(c, code, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static Notification foreground(Context c, String text) {
        return new Notification.Builder(c, CH_MONITOR)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Sarathi monitor running")
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openApp(c))
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(c, android.R.drawable.ic_menu_close_clear_cancel),
                        "Stop", serviceAction(c, MonitorService.ACTION_STOP, 11)).build())
                .build();
    }

    /** title/message are shown in the notification; the message (with the label) is sent to Telegram. */
    static void fire(Context app, String title, String message, boolean alarm) {
        Context c = app.getApplicationContext();
        String label = Core.prefs(c).getString("label", "").trim();
        String full = (label.isEmpty() ? "" : "[" + label + "] ") + message;
        Core.log("ALERT: " + title);

        try {
            PendingIntent open = openApp(c);
            Notification.Builder b = new Notification.Builder(c, CH_ALERT)
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentTitle(label.isEmpty() ? title : label + " — " + title)
                    .setContentText(message)
                    .setStyle(new Notification.BigTextStyle().bigText(message))
                    .setCategory(Notification.CATEGORY_ALARM)
                    .setContentIntent(open)
                    .setFullScreenIntent(open, true)
                    .setAutoCancel(true);
            if (alarm) {
                b.addAction(new Notification.Action.Builder(
                        Icon.createWithResource(c, android.R.drawable.ic_lock_silent_mode),
                        "Stop alarm", serviceAction(c, MonitorService.ACTION_STOP_ALARM, 12)).build());
            }
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            nm.notify(ID_ALERT, b.build());
        } catch (Exception e) {
            Core.log("Notification failed: " + e.getClass().getSimpleName());
        }

        if (alarm) startAlarm(c);
        telegram(c, full);
    }

    static synchronized void startAlarm(Context c) {
        stopAlarm(null);
        try {
            AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0);
            Uri u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (u == null) u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            MediaPlayer mp = new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            mp.setDataSource(c, u);
            mp.setLooping(true);
            mp.setWakeMode(c, PowerManager.PARTIAL_WAKE_LOCK);
            mp.prepare();
            mp.start();
            player = mp;
        } catch (Exception e) {
            Core.log("Alarm sound failed: " + e.getClass().getSimpleName());
            player = null;
        }
        try {
            Vibrator v = (Vibrator) c.getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null) v.vibrate(new long[]{0, 700, 400}, 0);
        } catch (Exception ignored) {
            // Vibration is optional.
        }
        MAIN.removeCallbacks(AUTO_STOP);
        MAIN.postDelayed(AUTO_STOP, ALARM_MAX_MS);
    }

    static synchronized void stopAlarm(Context c) {
        MAIN.removeCallbacks(AUTO_STOP);
        if (player != null) {
            try {
                player.stop();
            } catch (Exception ignored) {
                // Already stopped.
            }
            player.release();
            player = null;
        }
        try {
            if (c != null) {
                Vibrator v = (Vibrator) c.getSystemService(Context.VIBRATOR_SERVICE);
                if (v != null) v.cancel();
            }
        } catch (Exception ignored) {
            // Vibration is optional.
        }
    }

    static void telegram(Context c, final String text) {
        final String token = Core.prefs(c).getString("token", "").trim();
        final String chats = Core.prefs(c).getString("chat", "").trim();
        if (token.isEmpty() || chats.isEmpty()) {
            Core.log("Telegram not configured (token/chat ID missing)");
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                int sent = 0;
                int failed = 0;
                for (String id : chats.split("[,\\s]+")) {
                    if (id.isEmpty()) continue;
                    HttpURLConnection con = null;
                    try {
                        URL url = new URL("https://api.telegram.org/bot" + token + "/sendMessage");
                        con = (HttpURLConnection) url.openConnection();
                        con.setConnectTimeout(10000);
                        con.setReadTimeout(10000);
                        con.setRequestMethod("POST");
                        con.setDoOutput(true);
                        con.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                        String body = "chat_id=" + URLEncoder.encode(id, "UTF-8")
                                + "&text=" + URLEncoder.encode(text, "UTF-8");
                        OutputStream os = con.getOutputStream();
                        os.write(body.getBytes("UTF-8"));
                        os.close();
                        if (con.getResponseCode() == 200) sent++;
                        else failed++;
                    } catch (Exception e) {
                        // Never log the exception message: it can contain the bot token URL.
                        failed++;
                    } finally {
                        if (con != null) con.disconnect();
                    }
                }
                Core.log("Telegram: " + sent + " sent, " + failed + " failed");
            }
        }).start();
    }
}
