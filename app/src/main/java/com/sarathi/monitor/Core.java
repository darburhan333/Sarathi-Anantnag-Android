package com.sarathi.monitor;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.MutableContextWrapper;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedList;
import java.util.Locale;

/** Process-wide state. The WebView lives here so it survives the Activity being closed. */
final class Core {
    static final String HOME = "https://sarathi.parivahan.gov.in/";
    static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    static WebView web;
    static MutableContextWrapper wrapper;
    static String stepScript = "";

    static volatile boolean running = false;
    static volatile String status = "Stopped";
    static volatile int attempts = 0;
    static volatile long lastProceedAt = 0;
    static volatile long lastRecognizedAt = 0;

    private static final LinkedList<String> LOGS = new LinkedList<String>();

    private Core() {}

    static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("sarathi", Context.MODE_PRIVATE);
    }

    static synchronized void log(String line) {
        String t = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        LOGS.addLast(t + "  " + line);
        while (LOGS.size() > 40) LOGS.removeFirst();
    }

    static synchronized String logText(int max) {
        StringBuilder sb = new StringBuilder();
        int skip = Math.max(0, LOGS.size() - max);
        int i = 0;
        for (String s : LOGS) {
            if (i++ < skip) continue;
            sb.append(s).append('\n');
        }
        return sb.toString();
    }

    static String loadScript(Context c) {
        if (!stepScript.isEmpty()) return stepScript;
        try {
            InputStream in = c.getAssets().open("step.js");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            stepScript = out.toString("UTF-8");
        } catch (IOException e) {
            log("Could not read step.js");
        }
        return stepScript;
    }

    @SuppressLint("SetJavaScriptEnabled")
    static synchronized void ensureWebView(Context appContext) {
        if (web != null) return;
        wrapper = new MutableContextWrapper(appContext);
        web = new WebView(wrapper);
        loadScript(appContext);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setUserAgentString(UA);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                String host = u.getHost();
                boolean ok = "https".equals(u.getScheme()) && host != null
                        && (host.equals("parivahan.gov.in") || host.endsWith(".parivahan.gov.in"));
                if (!ok) log("Blocked navigation to " + host);
                return !ok;
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (r.isForMainFrame()) log("Page error " + e.getErrorCode());
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                CookieManager.getInstance().flush();
            }
        });

        // Page dialogs would need an Activity window. Never confirm anything on the user's behalf.
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onJsAlert(WebView v, String url, String message, JsResult result) {
                log("Page alert: " + (message == null ? "" : message.replaceAll("\\s+", " ")));
                result.confirm();
                return true;
            }

            @Override
            public boolean onJsConfirm(WebView v, String url, String message, JsResult result) {
                log("Page confirm dialog cancelled");
                result.cancel();
                return true;
            }

            @Override
            public boolean onJsPrompt(WebView v, String url, String message, String d, JsPromptResult result) {
                result.cancel();
                return true;
            }
        });

        giveOffscreenSize();
    }

    private static void giveOffscreenSize() {
        int w = Resources.getSystem().getDisplayMetrics().widthPixels;
        int h = Resources.getSystem().getDisplayMetrics().heightPixels;
        web.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        web.layout(0, 0, w, h);
    }

    static void attach(android.app.Activity a, FrameLayout container) {
        ensureWebView(a.getApplicationContext());
        wrapper.setBaseContext(a);
        ViewGroup p = (ViewGroup) web.getParent();
        if (p != null) p.removeView(web);
        container.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        web.onResume();
        if (web.getUrl() == null) web.loadUrl(HOME);
    }

    /** Keeps the page alive off screen. The WebView is deliberately never paused. */
    static void detach(android.app.Activity a) {
        if (web == null) return;
        ViewGroup p = (ViewGroup) web.getParent();
        if (p != null) p.removeView(web);
        wrapper.setBaseContext(a.getApplicationContext());
        giveOffscreenSize();
    }
}
