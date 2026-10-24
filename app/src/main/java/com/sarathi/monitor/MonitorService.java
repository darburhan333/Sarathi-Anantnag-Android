package com.sarathi.monitor;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.webkit.ValueCallback;
import android.webkit.WebView;

import org.json.JSONObject;

/**
 * Foreground service that keeps the CPU awake and drives the page script once per second from
 * native code, so it does not depend on browser timers that Android throttles when the screen is off.
 * ALERT ONLY: it never clicks a date, a quota radio or BOOK SLOT.
 */
public class MonitorService extends Service {
    static final String ACTION_START = "com.sarathi.monitor.START";
    static final String ACTION_STOP = "com.sarathi.monitor.STOP";
    static final String ACTION_STOP_ALARM = "com.sarathi.monitor.STOP_ALARM";

    private static final long PROCEED_EVERY_MS = 8000L;
    private static final long UNRECOGNISED_WARN_MS = 120000L;
    private static final long REPEAT_ALERT_MS = 30 * 60 * 1000L;
    private static final long REPEAT_WARNING_MS = 10 * 60 * 1000L;

    private Handler handler;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private boolean ticking = false;
    private int tickCount = 0;

    private String lastAlertKey = "";
    private long lastAlertAt = 0;
    private long lastExpiryAlertAt = 0;
    private long lastUnrecognisedAlertAt = 0;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!Core.running) return;
            try {
                doTick();
            } catch (Exception e) {
                Core.log("Tick error: " + e.getClass().getSimpleName());
            }
            handler.postDelayed(this, 1000L);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        Alerts.createChannels(this);
        Core.ensureWebView(getApplicationContext());
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopMonitoring();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_STOP_ALARM.equals(action)) {
            Alerts.stopAlarm(this);
            return START_STICKY;
        }
        // A null intent means Android restarted the service after killing the app.
        if (action == null && !Core.prefs(this).getBoolean("running", false)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startMonitoring();
        return START_STICKY;
    }

    private void startMonitoring() {
        try {
            Notification n = Alerts.foreground(this, "Starting…");
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(Alerts.ID_FOREGROUND, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(Alerts.ID_FOREGROUND, n);
            }
        } catch (Exception e) {
            Core.log("Could not start foreground service: " + e.getClass().getSimpleName());
            Core.status = "Could not start — open the app and press START";
            Core.running = false;
            Core.prefs(this).edit().putBoolean("running", false).apply();
            stopSelf();
            return;
        }
        acquireLocks();
        if (!Core.running) {
            Core.running = true;
            Core.lastProceedAt = 0;
            Core.lastRecognizedAt = System.currentTimeMillis();
            Core.status = "Monitoring started";
            Core.log("Monitoring started");
        }
        Core.prefs(this).edit().putBoolean("running", true).apply();
        WebView w = Core.web;
        if (w != null && w.getUrl() == null) w.loadUrl(Core.HOME);
        if (!ticking) {
            ticking = true;
            handler.post(tick);
        }
    }

    private void stopMonitoring() {
        ticking = false;
        handler.removeCallbacksAndMessages(null);
        Core.running = false;
        Core.status = "Stopped";
        Core.prefs(this).edit().putBoolean("running", false).apply();
        Alerts.stopAlarm(this);
        releaseLocks();
        stopForeground(true);
        Core.log("Monitoring stopped");
    }

    private void acquireLocks() {
        try {
            if (wakeLock == null) {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SarathiMonitor:monitor");
                wakeLock.setReferenceCounted(false);
            }
            if (!wakeLock.isHeld()) wakeLock.acquire();
            if (wifiLock == null) {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (wm != null) {
                    wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "SarathiMonitor:wifi");
                    wifiLock.setReferenceCounted(false);
                }
            }
            if (wifiLock != null && !wifiLock.isHeld()) wifiLock.acquire();
        } catch (Exception e) {
            Core.log("Wake/Wi-Fi lock failed: " + e.getClass().getSimpleName());
        }
    }

    private void releaseLocks() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
            if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        } catch (Exception ignored) {
            // Already released.
        }
    }

    @Override
    public void onDestroy() {
        ticking = false;
        if (handler != null) handler.removeCallbacksAndMessages(null);
        releaseLocks();
        super.onDestroy();
    }

    private void doTick() {
        final WebView w = Core.web;
        if (w == null) return;
        long now = System.currentTimeMillis();
        boolean due = now - Core.lastProceedAt >= PROCEED_EVERY_MS;
        String js = "(" + Core.loadScript(this) + ")({allowProceed:" + due + "})";
        w.resumeTimers();
        w.evaluateJavascript(js, new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String value) {
                handleResult(value);
            }
        });
        if (++tickCount % 5 == 0) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            nm.notify(Alerts.ID_FOREGROUND, Alerts.foreground(this, Core.status));
        }
    }

    private void handleResult(String value) {
        if (!Core.running) return;
        if (value == null || value.equals("null")) {
            Core.status = "Page not ready";
            return;
        }
        JSONObject o;
        try {
            o = new JSONObject(value);
        } catch (Exception e) {
            Core.status = "Unreadable page state";
            return;
        }
        String state = o.optString("state", "UNKNOWN");
        String acted = o.optString("acted", "");
        String greens = o.optString("greens", "");
        long now = System.currentTimeMillis();

        if (state.equals("COV")) {
            Core.lastRecognizedAt = now;
            if (acted.equals("proceed")) {
                Core.lastProceedAt = now;
                Core.attempts++;
                Core.status = "Proceed clicked (attempt " + Core.attempts + ")";
            } else if (acted.equals("select")) {
                Core.status = "Selecting vehicle classes";
            } else {
                long wait = Math.max(0, (PROCEED_EVERY_MS - (now - Core.lastProceedAt)) / 1000);
                Core.status = "Class page — next Proceed in " + wait + "s";
            }
        } else if (state.equals("NOSLOTS")) {
            Core.lastRecognizedAt = now;
            Core.status = acted.equals("back") ? "No slots — popup Back clicked" : "No-slots popup showing";
        } else if (state.equals("CALENDAR")) {
            Core.lastRecognizedAt = now;
            boolean green = !greens.isEmpty();
            Core.status = green ? "GREEN DATE visible: " + greens : "Calendar visible (no green date)";
            String key = "CAL:" + greens;
            if (shouldAlert(key, now)) {
                Alerts.fire(this,
                        green ? "Green date detected" : "Booking calendar detected",
                        green ? "Sarathi Anantnag: green date(s) " + greens + " visible. Open the app and book now."
                                : "Sarathi Anantnag: booking calendar is showing. Open the app and check.",
                        true);
            }
        } else if (state.equals("QUOTA")) {
            Core.lastRecognizedAt = now;
            Core.status = "Quota page visible";
            if (shouldAlert("QUOTA", now)) {
                Alerts.fire(this, "Quota page detected",
                        "Sarathi Anantnag: the Available Quota page is showing. Open the app and check.", true);
            }
        } else if (state.equals("EXPIRED")) {
            Core.status = "SESSION EXPIRED — return to the slot booking home page";
            if (now - lastExpiryAlertAt > REPEAT_WARNING_MS) {
                lastExpiryAlertAt = now;
                Alerts.fire(this, "Session expired",
                        "Sarathi session expired. Return to the slot booking home page and log in again.", true);
            }
        } else {
            Core.status = state.equals("LOGIN") ? "Login / CAPTCHA page" : "Page not recognised";
            if (now - Core.lastRecognizedAt > UNRECOGNISED_WARN_MS
                    && now - lastUnrecognisedAlertAt > REPEAT_WARNING_MS) {
                lastUnrecognisedAlertAt = now;
                Alerts.fire(this, "Monitor needs attention",
                        "Sarathi page not recognised for 2 minutes (login, CAPTCHA, network or page change?). Open the app.",
                        false);
            }
        }
    }

    private boolean shouldAlert(String key, long now) {
        if (key.equals(lastAlertKey) && now - lastAlertAt < REPEAT_ALERT_MS) return false;
        lastAlertKey = key;
        lastAlertAt = now;
        return true;
    }
}
