package com.sarathi.lmvmonitor;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONTokener;

public class MainActivity extends AppCompatActivity {
    private static final String HOME = "https://sarathi.parivahan.gov.in/";
    private static final String CHANNEL = "sarathi_lmv_slots";
    private static final int NOTIFY_PERMISSION = 10;
    private static final long CHECK_MS = 5000L;
    private static final long REFRESH_MS = 60000L;

    private WebView web;
    private TextView status;
    private TextView checked;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean monitoring = true;
    private boolean lastAvailable = false;
    private boolean pageLoaded = false;

    private final Runnable checker = new Runnable() {
        @Override public void run() {
            if (monitoring && pageLoaded) checkPage();
            handler.postDelayed(this, CHECK_MS);
        }
    };

    private final Runnable refresher = new Runnable() {
        @Override public void run() {
            if (monitoring && pageLoaded) web.reload();
            handler.postDelayed(this, REFRESH_MS);
        }
    };

    @Override protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);

        status = findViewById(R.id.status);
        checked = findViewById(R.id.checked);
        web = findViewById(R.id.web);

        createChannel();
        requestNotifications();
        configureWebView();

        Button home = findViewById(R.id.home);
        Button back = findViewById(R.id.back);
        Button monitor = findViewById(R.id.monitor);

        home.setOnClickListener(v -> web.loadUrl(HOME));
        back.setOnClickListener(v -> { if (web.canGoBack()) web.goBack(); });
        monitor.setOnClickListener(v -> {
            monitoring = !monitoring;
            monitor.setText(monitoring ? "PAUSE" : "MONITOR");
            setStatus(monitoring ? "Monitoring is ON." : "Monitoring is paused.");
            if (monitoring) checkPage();
        });

        handler.post(checker);
        handler.post(refresher);
    }

    private void configureWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setSupportMultipleWindows(false);
        CookieManager.getInstance().setAcceptCookie(true);

        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageStarted(WebView view, String url, Bitmap favicon) {
                pageLoaded = false;
                setStatus("Loading Sarathi…");
            }

            @Override public void onPageFinished(WebView view, String url) {
                pageLoaded = true;
                setStatus("Loaded. Complete the normal Sarathi flow to Anantnag → LMV.");
                checkPage();
            }
        });

        web.loadUrl(HOME);
    }

    private void checkPage() {
        String js =
            "(function(){" +
            "var t=((document.body&&document.body.innerText)||'').replace(/\\s+/g,' ').trim();" +
            "var l=t.toLowerCase();" +
            "var sar=/sarathi|parivahan/i.test(l);" +
            "var lmv=/\\bLMV\\b/i.test(t)||/light motor vehicle/i.test(t);" +
            "var blocked=/ssl1001|slot booking is allowed from sarathi portal only/i.test(t);" +
            "var none=/no slots? available|no appointment(?:s)? available/i.test(t);" +
            "var pos=/slots?\\s+(?:is|are)?\\s*(?:available|open)|appointments?\\s+(?:is|are)?\\s*(?:available|open)|available\\s+slots?|book(?:ing)?\\s+(?:is\\s+)?available|select\\s+slot|choose\\s+slot/i.test(t);" +
            "var url=location.href;" +
            "return [sar,lmv,blocked,none,pos,url].join('|');" +
            "})()";

        web.evaluateJavascript(js, value -> {
            if (value == null) return;
            try {
                Object parsed = new JSONTokener(value).nextValue();
                if (!(parsed instanceof String)) return;
                String[] p = ((String) parsed).split("\\|", -1);
                if (p.length < 6) return;
                handleResult(
                    "true".equals(p[0]),
                    "true".equals(p[1]),
                    "true".equals(p[2]),
                    "true".equals(p[3]),
                    "true".equals(p[4])
                );
            } catch (Exception ignored) {
                setStatus("Page loaded, but its text could not be read yet. Retrying…");
            }
        });
    }

    private void handleResult(boolean sarathi, boolean lmv, boolean blocked,
                              boolean none, boolean positive) {
        boolean available = sarathi && lmv && positive && !blocked && !none;

        if (blocked) {
            setStatus("Sarathi portal-flow page detected. Waiting for the normal appointment page.");
        } else if (available) {
            setStatus("SLOT AVAILABLE — verify and book it now.");
            if (!lastAvailable) notifySlot();
        } else if (lmv) {
            setStatus("LMV appointment page found. No available slot detected.");
        } else if (sarathi) {
            setStatus("Sarathi is open. Continue to the Anantnag LMV appointment/date page.");
        } else {
            setStatus("Waiting for the official Sarathi page…");
        }

        lastAvailable = available;
        checked.setText("Last checked: " +
            android.text.format.DateFormat.format("dd MMM yyyy, hh:mm:ss a", System.currentTimeMillis()));
    }

    private void setStatus(String value) {
        status.setText(value);
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL, "Sarathi LMV slot alerts", NotificationManager.IMPORTANCE_HIGH);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private void requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFY_PERMISSION);
        }
    }

    private void notifySlot() {
        NotificationCompat.Builder n = new NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Sarathi LMV slot available")
            .setContentText("An LMV appointment appears available. Open the app and verify it.")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setAutoCancel(true);
        getSystemService(NotificationManager.class).notify(1001, n.build());
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (web != null) web.destroy();
        super.onDestroy();
    }
}