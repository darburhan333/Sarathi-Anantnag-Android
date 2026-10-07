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
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private static final String SARATHI = "https://sarathi.parivahan.gov.in/sarathiservice/stateSelection.do";
    private static final String PREFS = "sarathi_prefs";
    private static final String CHANNEL_ID = "sarathi_slots";
    private static final long[] SERVER_RETRY_DELAYS_MS = {
        10000L, 20000L, 40000L, 60000L
    };

    private final Handler handler = new Handler();
    private final ExecutorService network = Executors.newSingleThreadExecutor();

    private WebView web;
    private TextView status, lastChecked, result, telegramState;
    private EditText interval;
    private boolean running = false;
    private long lastAlert = 0L;
    private int serverRetryAttempt = 0;
    private boolean serverRetryScheduled = false;
    private String lastSarathiPageUrl = SARATHI;
    private String lastPortalPageUrl = SARATHI;

    private final Runnable monitorLoop = new Runnable() {
        @Override public void run() {
            if (!running) return;
            runAutomationCycle();
            handler.postDelayed(this, clampInterval() * 1000L);
        }
    };

    @Override protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(0xFFF4F6F8);
        getWindow().getDecorView().setSystemUiVisibility(android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        setContentView(R.layout.activity_main);
        status = findViewById(R.id.status);
        lastChecked = findViewById(R.id.lastChecked);
        result = findViewById(R.id.result);
        telegramState = findViewById(R.id.telegramState);
        interval = findViewById(R.id.interval);
        Button start = findViewById(R.id.start);
        Button stop = findViewById(R.id.stop);
        Button settings = findViewById(R.id.settings);
        web = findViewById(R.id.web);

        createNotificationChannel();
        requestNotificationPermission();
        setupWebView();
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
        web.getSettings().setMediaPlaybackRequiresUserGesture(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        CookieManager.getInstance().flush();
        web.setWebChromeClient(new WebChromeClient());
        web.addJavascriptInterface(new AndroidBridge(), "SarathiAndroid");
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
            }

            @Override public void onReceivedHttpError(WebView view, WebResourceRequest request,
                                                       android.webkit.WebResourceResponse errorResponse) {
                super.onReceivedHttpError(view, request, errorResponse);
                if (errorResponse != null && errorResponse.getStatusCode() == 503) {
                    if (request.isForMainFrame() && request.getUrl() != null) {
                        String failedUrl = request.getUrl().toString();
                        lastSarathiPageUrl = failedUrl;
                        if (failedUrl.contains("/slots/")) {
                            handlePortalFlowRequired();
                        } else {
                            handleServerUnavailable();
                        }
                    } else {
                        handleServerUnavailable();
                    }
                }
            }

            @Override public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (url != null && url.startsWith("https://sarathi.parivahan.gov.in/")) {
                    lastSarathiPageUrl = url;
                    if (!url.contains("/slots/") && !url.contains("/cas/")) {
                        lastPortalPageUrl = url;
                    }
                }
                if (running) {
                    runAutomationCycle();
                }
            }
        });
        CookieManager.getInstance().setAcceptCookie(true);
    }

    private int clampInterval() {
        try {
            int v = Integer.parseInt(interval.getText().toString().trim());
            return Math.max(2, Math.min(60, v));
        } catch (Exception e) {
            return 5;
        }
    }

    private void startMonitoring() {
        if (running) return;
        running = true;
        status.setText("🟢 MONITORING");
        result.setText("Result: starting...");
        handler.removeCallbacks(monitorLoop);
        monitorLoop.run();
    }

    private void stopMonitoring() {
        running = false;
        handler.removeCallbacks(monitorLoop);
        handler.removeCallbacks(serverRetryRunnable);
        serverRetryScheduled = false;
        serverRetryAttempt = 0;
        status.setText("🔴 STOPPED");
        result.setText("Result: stopped");
    }

    private void runAutomationCycle() {
        lastChecked.setText("Last checked: " + new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date()));
        web.evaluateJavascript(AUTOMATION_JS, null);
    }

    private void handlePortalFlowRequired() {
        serverRetryScheduled = false;
        serverRetryAttempt = 0;
        status.setText("🟠 SARATHI PORTAL FLOW REQUIRED");
        result.setText("Result: Sarathi rejected the slot request. Returning through portal history — use the normal DL Slot Book / Proceed button again.");
        handler.postDelayed(() -> {
            if (!running) return;
            if (web.canGoBack()) {
                web.goBack();
            } else if (lastPortalPageUrl != null
                    && lastPortalPageUrl.startsWith("https://sarathi.parivahan.gov.in/")
                    && !lastPortalPageUrl.contains("/slots/")) {
                web.loadUrl(lastPortalPageUrl);
            }
        }, 500);
    }

    private void handleServerUnavailable() {
        if (!running || serverRetryScheduled) return;

        long delay = SERVER_RETRY_DELAYS_MS[
            Math.min(serverRetryAttempt, SERVER_RETRY_DELAYS_MS.length - 1)
        ];
        serverRetryAttempt = Math.min(serverRetryAttempt + 1, SERVER_RETRY_DELAYS_MS.length - 1);
        serverRetryScheduled = true;

        status.setText("🟡 SERVER UNAVAILABLE");
        result.setText("Result: Sarathi unavailable — retrying in " + (delay / 1000) + "s");

        handler.postDelayed(serverRetryRunnable, delay);
    }

    private final Runnable serverRetryRunnable = new Runnable() {
        @Override public void run() {
            serverRetryScheduled = false;
            if (!running) return;
            result.setText("Result: retrying current Sarathi page...");
            if (lastSarathiPageUrl != null && lastSarathiPageUrl.startsWith("https://sarathi.parivahan.gov.in/")) {
                web.loadUrl(lastSarathiPageUrl);
            } else {
                web.reload();
            }
        }
    };

    private void handleServerAvailable() {
        serverRetryAttempt = 0;
        serverRetryScheduled = false;
        if (running) {
            status.setText("🟢 MONITORING");
        }
    }

    private final String AUTOMATION_JS =
        "(function(){"
        + "function n(x){return String(x||'').toLowerCase().replace(/\\s+/g,' ').trim();}"
        + "function label(e){return n([e.innerText,e.textContent,e.value,e.getAttribute&&e.getAttribute('aria-label'),e.getAttribute&&e.getAttribute('title')].filter(Boolean).join(' '));}"
        + "function fire(e){try{e.scrollIntoView({block:'center'});e.click();return true}catch(x){}return false;}"
        + "function row(e){var r=e&&e.closest&&e.closest('tr');return n(r?r.innerText:(e&&e.parentElement?e.parentElement.innerText:''));}"
        + "function lmv(){var a=[].slice.call(document.querySelectorAll('input[type=checkbox]'));for(var i=0;i<a.length;i++){var t=row(a[i]);if(t.indexOf('light motor vehicle')>=0||/\\blmv\\b/.test(t))return a[i]}return null;}"
        + "function proceed(){var a=[].slice.call(document.querySelectorAll('button,input[type=button],input[type=submit],a'));for(var i=0;i<a.length;i++)if(/proceed\\s+to\\s+book/.test(label(a[i])))return a[i];return null;}"
        + "function green(e){var x=e;for(var i=0;i<3&&x;i++,x=x.parentElement){var s=getComputedStyle(x),c=s.backgroundColor+','+s.color;if(/green|available/.test(n(x.className)+' '+c))return true;}return false;}"
        + "function dates(){var a=[];var cells=[].slice.call(document.querySelectorAll('td'));for(var i=0;i<cells.length;i++){var t=n(cells[i].innerText||cells[i].textContent);if(/^\\d{1,2}$/.test(t)&&green(cells[i]))a.push(cells[i].querySelector('a,button,span')||cells[i]);}return a;}"
        + "function lmvOne(){var rs=[].slice.call(document.querySelectorAll('tr'));for(var i=0;i<rs.length;i++){var t=n(rs[i].innerText||rs[i].textContent);if(/\\blmv\\b/.test(t)&&/(^|\\s)1(\\s|$)/.test(t))return true;}return false;}"
        + "var txt=n(document.body&&document.body.innerText);"
        + "if((/\\b503\\b/.test(txt)&&/service unavailable/.test(txt))||/service unavailable/.test(txt)){window.SarathiAndroid&&window.SarathiAndroid.serverUnavailable();return;}"
        + "window.SarathiAndroid&&window.SarathiAndroid.serverAvailable();"
        + "if(txt.indexOf('select covs')>=0){var c=lmv();if(c&&!c.checked){fire(c);try{c.dispatchEvent(new Event('change',{bubbles:true}))}catch(x){}}return;}"
        + "if(txt.indexOf('calendar indicator')>=0&&txt.indexOf('available quota')>=0){var ds=dates();if(ds.length){var idx=window.__sarathiDateIndex||0;if(idx>=ds.length)idx=0;window.__sarathiDateIndex=idx+1;fire(ds[idx]);setTimeout(function(){if(lmvOne()){window.SarathiAndroid&&window.SarathiAndroid.slotFound('LMV quota is 1 on an available date.')}} ,1200);}}"
        + "})();";

    private void refreshTelegramState() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        boolean ok = !p.getString("token","").isEmpty() && !p.getString("chat","").isEmpty();
        telegramState.setText(ok ? "Telegram: connected" : "Telegram: not configured");
    }

    private void showTelegramDialog() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        final EditText token = new EditText(this);
        token.setHint("Telegram bot token");
        token.setText(p.getString("token",""));
        final EditText chat = new EditText(this);
        chat.setHint("Telegram chat ID");
        chat.setText(p.getString("chat",""));

        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int)(16 * getResources().getDisplayMetrics().density);
        box.setPadding(pad,pad,pad,pad);
        box.addView(token);
        box.addView(chat);

        new AlertDialog.Builder(this)
            .setTitle("Telegram settings")
            .setMessage("Create a bot with BotFather, send /start to it, then enter its token and your chat ID.")
            .setView(box)
            .setPositiveButton("SAVE", (d,w) -> {
                p.edit().putString("token",token.getText().toString().trim())
                     .putString("chat",chat.getText().toString().trim()).apply();
                refreshTelegramState();
            })
            .setNegativeButton("CANCEL", null).show();
    }

    public class AndroidBridge {
        @JavascriptInterface public void serverUnavailable() {
            runOnUiThread(() -> handleServerUnavailable());
        }

        @JavascriptInterface public void portalFlowRequired() {
            runOnUiThread(() -> handlePortalFlowRequired());
        }

        @JavascriptInterface public void serverAvailable() {
            runOnUiThread(() -> handleServerAvailable());
        }

        @JavascriptInterface public void slotFound(String detail) {
            runOnUiThread(() -> {
                result.setText("Result: 🚨 LMV SLOT FOUND");
                long now = System.currentTimeMillis();
                if (now - lastAlert < 120000L) return;
                lastAlert = now;
                notifyUser("🚨 Sarathi LMV Slot Found", detail);
                sendTelegram(detail);
            });
        }
    }

    private void notifyUser(String title, String body) {
        NotificationManager nm = (NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE);
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
        String token = p.getString("token","");
        String chat = p.getString("chat","");
        if(token.isEmpty() || chat.isEmpty()) return;

        network.execute(() -> {
            try {
                URL u = new URL("https://api.telegram.org/bot" + token + "/sendMessage");
                HttpURLConnection c = (HttpURLConnection)u.openConnection();
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type","application/json");
                String text = "🚨 Sarathi Anantnag — LMV Slot Found!\n\n"
                    + detail + "\nTarget: J&K → Anantnag → Anantnag\nPlease check the app now.";
                String json = "{\"chat_id\":\"" + esc(chat) + "\",\"text\":\"" + esc(text) + "\"}";
                try(OutputStream os=c.getOutputStream()){os.write(json.getBytes(StandardCharsets.UTF_8));}
                c.getResponseCode();
                c.disconnect();
            } catch(Exception ignored) {}
        });
    }

    private String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    private void createNotificationChannel() {
        if(Build.VERSION.SDK_INT>=26){
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID,"Sarathi slot alerts",NotificationManager.IMPORTANCE_HIGH);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }

    private void requestNotificationPermission() {
        if(Build.VERSION.SDK_INT>=33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            ActivityCompat.requestPermissions(this,new String[]{Manifest.permission.POST_NOTIFICATIONS},77);
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(monitorLoop);
        handler.removeCallbacks(serverRetryRunnable);
        network.shutdownNow();
        super.onDestroy();
    }
}
