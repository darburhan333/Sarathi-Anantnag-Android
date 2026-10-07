package com.sarathi.anantnag;

import android.Manifest;
import android.app.AlertDialog;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class MainActivity extends AppCompatActivity implements SlotMonitor.Listener {
    private static final String SARATHI = "https://sarathi.parivahan.gov.in/sarathiservice/stateSelection.do";
    private static final String PREFS = "sarathi_prefs";
    private static final String CHANNEL_ID = "sarathi_slots";

    private final Handler handler = new Handler();
    private WebView web;
    private SlotMonitor monitor;
    private TextView status, lastChecked, result, telegramState;
    private EditText interval;
    private boolean running;
    private long lastAlert;

    private final Runnable loop = new Runnable() {
        @Override public void run() {
            if (!running) return;
            if (isSlotPage(web.getUrl())) monitor.check();
            handler.postDelayed(this, intervalMs());
        }
    };

    @Override protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(0xFFF4F6F8);
        getWindow().getDecorView().setSystemUiVisibility(android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        setContentView(R.layout.activity_main);

        status = findViewById(R.id.status);
        lastChecked = findViewById(R.id.lastChecked);
        result = findViewById(R.id.result);
        telegramState = findViewById(R.id.telegramState);
        interval = findViewById(R.id.interval);
        web = findViewById(R.id.web);

        Button start = findViewById(R.id.start);
        Button stop = findViewById(R.id.stop);
        Button settings = findViewById(R.id.settings);

        createNotificationChannel();
        requestNotificationPermission();
        setupWebView();
        monitor = new SlotMonitor(web, this);
        refreshTelegramState();

        start.setOnClickListener(v -> startMonitoring());
        stop.setOnClickListener(v -> stopMonitoring());
        settings.setOnClickListener(v -> showTelegramDialog());

        web.loadUrl(SARATHI);
    }

    private void setupWebView() {
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setDatabaseEnabled(true);
        web.getSettings().setSupportZoom(false);
        web.getSettings().setBuiltInZoomControls(false);
        web.getSettings().setDisplayZoomControls(false);
        web.getSettings().setCacheMode(android.webkit.WebSettings.LOAD_DEFAULT);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);

        web.setWebChromeClient(new WebChromeClient());
        web.addJavascriptInterface(new AndroidBridge(), "SarathiAndroid");

        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
            }

            @Override public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (running && isSlotPage(url)) {
                    status.setText("🟢 SLOT PAGE MONITORING");
                    result.setText("Result: checking the current Sarathi slot page.");
                } else if (running) {
                    status.setText("🟡 PORTAL SAFE MODE");
                    result.setText("Result: browse Sarathi normally. Monitoring starts on the slot/calendar page.");
                }
            }

            @Override public void onReceivedHttpError(WebView view, WebResourceRequest request,
                                                       android.webkit.WebResourceResponse errorResponse) {
                super.onReceivedHttpError(view, request, errorResponse);
                if (errorResponse == null || errorResponse.getStatusCode() != 503 || !request.isForMainFrame()) return;

                String url = request.getUrl() == null ? "" : request.getUrl().toString();
                if (url.contains("/slots/")) {
                    status.setText("🟠 SARATHI PORTAL FLOW REQUIRED");
                    result.setText("Result: Sarathi returned SSL1001. Continue through the official portal; the app will not bypass that server check.");
                } else {
                    status.setText("🟡 SARATHI TEMPORARILY UNAVAILABLE");
                    result.setText("Result: the portal returned HTTP 503.");
                }
            }
        });
    }

    private boolean isSlotPage(String url) {
        return url != null
            && url.startsWith("https://sarathi.parivahan.gov.in/")
            && url.contains("/slots/")
            && !url.contains("loginPage.do");
    }

    private long intervalMs() {
        try {
            int seconds = Integer.parseInt(interval.getText().toString().trim());
            return Math.max(2, Math.min(60, seconds)) * 1000L;
        } catch (Exception e) {
            return 5000L;
        }
    }

    private void startMonitoring() {
        if (running) return;
        running = true;
        handler.removeCallbacks(loop);
        if (isSlotPage(web.getUrl())) {
            status.setText("🟢 SLOT PAGE MONITORING");
            result.setText("Result: monitoring started.");
        } else {
            status.setText("🟡 PORTAL SAFE MODE");
            result.setText("Result: browse Sarathi normally; monitoring begins when the slot/calendar page is open.");
        }
        loop.run();
    }

    private void stopMonitoring() {
        running = false;
        handler.removeCallbacks(loop);
        status.setText("🔴 STOPPED");
        result.setText("Result: monitoring stopped.");
    }

    @Override public void onCheck() {
        lastChecked.setText("Last checked: " + new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date()));
    }

    @Override public void onServerUnavailable() {
        status.setText("🟡 SERVER UNAVAILABLE");
        result.setText("Result: Sarathi returned HTTP 503.");
    }

    @Override public void onServerAvailable() {
        if (running && isSlotPage(web.getUrl())) status.setText("🟢 SLOT PAGE MONITORING");
    }

    @Override public void onSlotFound(String detail) {
        result.setText("Result: 🚨 LMV SLOT FOUND");
        long now = System.currentTimeMillis();
        if (now - lastAlert < 120000L) return;
        lastAlert = now;
        notifyUser("🚨 Sarathi LMV Slot Found", detail);
        sendTelegram(detail);
    }

    public final class AndroidBridge {
        @JavascriptInterface public void serverUnavailable() {
            runOnUiThread(() -> onServerUnavailable());
        }
        @JavascriptInterface public void serverAvailable() {
            runOnUiThread(() -> onServerAvailable());
        }
        @JavascriptInterface public void slotFound(String detail) {
            runOnUiThread(() -> onSlotFound(detail));
        }
    }

    private void refreshTelegramState() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        boolean ok = !p.getString("token", "").isEmpty() && !p.getString("chat", "").isEmpty();
        telegramState.setText(ok ? "Telegram: connected" : "Telegram: not configured");
    }

    private void showTelegramDialog() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        EditText token = new EditText(this);
        token.setHint("Telegram bot token");
        token.setText(p.getString("token", ""));
        EditText chat = new EditText(this);
        chat.setHint("Telegram chat ID");
        chat.setText(p.getString("chat", ""));

        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad, pad, pad);
        box.addView(token);
        box.addView(chat);

        new AlertDialog.Builder(this)
            .setTitle("Telegram settings")
            .setMessage("Enter your bot token and chat ID.")
            .setView(box)
            .setPositiveButton("SAVE", (d, w) -> {
                p.edit().putString("token", token.getText().toString().trim())
                    .putString("chat", chat.getText().toString().trim()).apply();
                refreshTelegramState();
            })
            .setNegativeButton("CANCEL", null)
            .show();
    }

    private void notifyUser(String title, String body) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true);
        nm.notify(1001, b.build());
    }

    private void sendTelegram(String detail) {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        String token = p.getString("token", "");
        String chat = p.getString("chat", "");
        if (token.isEmpty() || chat.isEmpty()) return;

        new Thread(() -> {
            try {
                URL u = new URL("https://api.telegram.org/bot" + token + "/sendMessage");
                HttpURLConnection c = (HttpURLConnection) u.openConnection();
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                String msg = "Sarathi Anantnag — LMV Slot Found!\\n\\n" + detail;
                String json = "{\"chat_id\":\"" + esc(chat) + "\",\"text\":\"" + esc(msg) + "\"}";
                try (OutputStream os = c.getOutputStream()) {
                    os.write(json.getBytes(StandardCharsets.UTF_8));
                }
                c.getResponseCode();
                c.disconnect();
            } catch (Exception ignored) {}
        }).start();
    }

    private String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "Sarathi slot alerts", NotificationManager.IMPORTANCE_HIGH);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.POST_NOTIFICATIONS}, 77);
        }
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(loop);
        if (web != null) web.destroy();
        super.onDestroy();
    }
}