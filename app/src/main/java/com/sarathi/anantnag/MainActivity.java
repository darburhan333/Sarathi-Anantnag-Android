package com.sarathi.anantnag;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {
    private static final String SARATHI_URL = "https://sarathi.parivahan.gov.in/";
    private static final String CHANNEL_ID = "sarathi_slots";
    private static final int NOTIFICATION_REQUEST = 77;
    private static final long CHECK_INTERVAL_MS = 5000L;
    private static final long REFRESH_INTERVAL_MS = 60000L;

    private WebView webView;
    private TextView statusText;
    private TextView checkedText;
    private CheckBox monitorBox;
    private CheckBox refreshBox;
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean lastAvailable = false;
    private boolean pageReady = false;

    private final Runnable domCheck = new Runnable() {
        @Override public void run() {
            if (monitorBox != null && monitorBox.isChecked() && pageReady) {
                inspectRenderedPage();
            }
            handler.postDelayed(this, CHECK_INTERVAL_MS);
        }
    };

    private final Runnable autoRefresh = new Runnable() {
        @Override public void run() {
            if (refreshBox != null && refreshBox.isChecked() && monitorBox.isChecked()
                    && pageReady) {
                webView.reload();
            }
            handler.postDelayed(this, REFRESH_INTERVAL_MS);
        }
    };

    @Override protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.statusText);
        checkedText = findViewById(R.id.checkedText);
        monitorBox = findViewById(R.id.monitorBox);
        refreshBox = findViewById(R.id.refreshBox);
        webView = findViewById(R.id.webView);
        Button backButton = findViewById(R.id.backButton);
        Button homeButton = findViewById(R.id.homeButton);

        createNotificationChannel();
        requestNotificationPermission();
        setupWebView();

        monitorBox.setOnCheckedChangeListener((button, checked) -> {
            setStatus(checked ? "Monitoring the rendered Sarathi page…" : "Monitoring paused");
            if (checked) inspectRenderedPage();
        });

        refreshBox.setOnCheckedChangeListener((button, checked) ->
                setStatus(checked ? "Auto-refresh enabled (60 seconds)" : "Auto-refresh disabled"));

        backButton.setOnClickListener(v -> {
            if (webView.canGoBack()) webView.goBack();
        });
        homeButton.setOnClickListener(v -> webView.loadUrl(SARATHI_URL));

        handler.post(domCheck);
        handler.post(autoRefresh);
    }

    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);

        webView.setWebChromeClient(new WebChromeClient());
        webView.addJavascriptInterface(new PageBridge(), "SarathiMonitor");
        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                pageReady = true;
                setStatus("Page loaded. Navigate normally to Anantnag → LMV.");
                injectObserver();
                inspectRenderedPage();
            }
        });
        webView.loadUrl(SARATHI_URL);
    }

    private void injectObserver() {
        webView.evaluateJavascript(
            "(function(){if(window.__sarathiMonitorInstalled)return;window.__sarathiMonitorInstalled=true;" +
            "var f=function(){try{if(window.SarathiMonitor)window.SarathiMonitor.pageChanged();}catch(e){}};" +
            "new MutationObserver(function(){clearTimeout(window.__sm_t);window.__sm_t=setTimeout(f,400);})" +
            ".observe(document.documentElement,{subtree:true,childList:true,characterData:true});f();})();",
            null);
    }

    private void inspectRenderedPage() {
        String js =
            "(function(){var t=(document.body&&document.body.innerText||'').replace(/\\\\s+/g,' ').trim();" +
            "var l=t.toLowerCase();" +
            "var ssl=/ssl1001|slot booking is allowed from sarathi portal only/i.test(t);" +
            "var neg=/service unavailable|temporarily unavailable|no slots? available|no appointment(?:s)? available/i.test(t);" +
            "var lmv=/\\\\bLMV\\\\b/i.test(t)||/light motor vehicle/i.test(t);" +
            "var pos=/slot(?:s)?\\\\s+(?:is|are)?\\\\s*(?:available|open)|appointment(?:s)?\\\\s+(?:is|are)?\\\\s*(?:available|open)|available\\\\s+slot|book(?:ing)?\\\\s+(?:is\\\\s+)?available|select\\\\s+slot|choose\\\\s+slot/i.test(t);" +
            "var sar=/sarathi|parivahan/i.test(l);" +
            "return [sar,lmv,ssl,(!ssl&&!neg&&lmv&&pos)].join('|');})()";
        webView.evaluateJavascript(js, value -> {
            if (value == null) return;
            String result = value.replace("\"", "");
            handleResult(result);
        });
    }

    private void handleResult(String result) {
        String[] p = result.split("\\\\|", -1);
        if (p.length < 4) return;

        boolean sarathi = "true".equals(p[0]);
        boolean lmv = "true".equals(p[1]);
        boolean ssl = "true".equals(p[2]);
        boolean available = "true".equals(p[3]);

        if (ssl) {
            setStatus("Sarathi SSL1001 portal-flow page. No availability decision made.");
        } else if (available) {
            setStatus("⚠ LMV SLOT APPEARS AVAILABLE — verify it now.");
            if (!lastAvailable) notifySlot();
        } else if (lmv) {
            setStatus("LMV appointment page detected. No available slot found.");
        } else if (sarathi) {
            setStatus("Sarathi page detected. Continue to the Anantnag LMV appointment page.");
        } else {
            setStatus("Waiting for the official Sarathi appointment page…");
        }

        lastAvailable = available;
        checkedText.setText("Last checked: " + android.text.format.DateFormat.format(
                "dd MMM yyyy, hh:mm:ss a", System.currentTimeMillis()));
    }

    private void setStatus(String text) {
        if (statusText != null) statusText.setText(text);
    }

    private void notifySlot() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Sarathi LMV slot available")
                .setContentText("An LMV appointment appears available. Open the app and verify it.")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true);
        nm.notify(1001, b.build());
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Sarathi slot alerts",
                    NotificationManager.IMPORTANCE_HIGH);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                    this, new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    NOTIFICATION_REQUEST);
        }
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (webView != null) {
            webView.removeJavascriptInterface("SarathiMonitor");
            webView.destroy();
        }
        super.onDestroy();
    }

    private class PageBridge {
        @JavascriptInterface
        public void pageChanged() {
            runOnUiThread(() -> {
                if (monitorBox.isChecked()) inspectRenderedPage();
            });
        }
    }
}