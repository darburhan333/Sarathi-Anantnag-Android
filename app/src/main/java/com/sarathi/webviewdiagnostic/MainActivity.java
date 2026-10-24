package com.sarathi.webviewdiagnostic;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.pm.PackageInfo;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;

/** Isolated baseline: no injected scripts, request interception, UA override or cookie changes. */
public class MainActivity extends Activity {
    private static final String HOME = "https://parivahan.gov.in/";
    private final ArrayList<String> logs = new ArrayList<>();
    private WebView web;
    private TextView status;
    private String engine;

    private String safeLocation(String url) {
        if (url == null) return "";
        Uri u = Uri.parse(url);
        // Exclude queries and fragments so applicant details and tokens are not copied.
        return (u.getHost() == null ? "" : u.getHost()) + (u.getPath() == null ? "" : u.getPath());
    }
    private void log(String text) {
        logs.add(text);
        if (logs.size() > 40) logs.remove(0);
        status.setText(text);
    }
    private void addButton(LinearLayout row, String label, Runnable action) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(v -> action.run());
        row.addView(b, new LinearLayout.LayoutParams(0, -2, 1));
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        TextView heading = new TextView(this);
        heading.setText("Sarathi WebView Test — diagnostic only\nJavaScript + DOM storage enabled; other WebSettings unchanged.");
        root.addView(heading);
        status = new TextView(this);
        status.setTextSize(12);
        status.setMaxLines(3);
        root.addView(status);
        LinearLayout buttons = new LinearLayout(this);
        addButton(buttons, "Home", () -> web.loadUrl(HOME));
        addButton(buttons, "Reload", () -> web.reload());
        addButton(buttons, "Copy log", () -> {
            String data = engine + "\n" + String.join("\n", logs);
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("WebView diagnostic", data));
            Toast.makeText(this, "Diagnostic log copied", Toast.LENGTH_SHORT).show();
        });
        root.addView(buttons);
        web = new WebView(this);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageStarted(WebView view, String url, Bitmap icon) {
                log("Loading " + safeLocation(url));
            }
            @Override public void onPageFinished(WebView view, String url) {
                log("Finished " + safeLocation(url));
            }
            @Override public void onReceivedHttpError(WebView view, WebResourceRequest req, WebResourceResponse res) {
                if (req.isForMainFrame()) log("HTTP " + res.getStatusCode() + " " + safeLocation(req.getUrl().toString()));
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
                if (req.isForMainFrame()) log("Network error " + err.getErrorCode());
            }
        });
        root.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        PackageInfo info = WebView.getCurrentWebViewPackage();
        engine = "App: diagnostic 1.0\nAndroid API: " + android.os.Build.VERSION.SDK_INT
                + "\nWebView: " + (info == null ? "unknown" : info.packageName + " " + info.versionName)
                + "\nUser agent: " + web.getSettings().getUserAgentString();
        web.loadUrl(HOME);
    }
    @Override public void onBackPressed() {
        if (web.canGoBack()) web.goBack(); else super.onBackPressed();
    }
    @Override protected void onPause() { web.onPause(); super.onPause(); }
    @Override protected void onResume() { super.onResume(); if (web != null) web.onResume(); }
    @Override protected void onDestroy() { web.destroy(); super.onDestroy(); }
}
