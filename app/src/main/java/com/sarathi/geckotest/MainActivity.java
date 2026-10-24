package com.sarathi.geckotest;

import android.app.Activity;
import android.app.AlertDialog;
import org.mozilla.geckoview.GeckoSession.PromptDelegate;
import org.mozilla.geckoview.GeckoSession.PromptDelegate.ChoicePrompt;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoView;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.WebRequestError;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final String HOME = "https://parivahan.gov.in/";
    private static GeckoRuntime runtime;
    private GeckoView view;
    private GeckoSession current;
    private TextView status;
    private final ArrayList<GeckoSession> sessions = new ArrayList<>();
    private final ArrayList<String> logs = new ArrayList<>();
    private boolean canGoBack;

    private String safeLocation(String url) {
        if (url == null) return "";
        Uri u = Uri.parse(url);
        return (u.getHost() == null ? "" : u.getHost()) + (u.getPath() == null ? "" : u.getPath());
    }
    private void log(String message) {
        String time = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        logs.add(time + " " + message);
        if (logs.size() > 60) logs.remove(0);
        status.setText(message);
    }
    private void button(LinearLayout row, String label, Runnable action) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(12);
        b.setOnClickListener(v -> action.run());
        row.addView(b, new LinearLayout.LayoutParams(0, -2, 1));
    }
    private void showSession(GeckoSession s) {
        if (current == s) return;
        if (current != null) { current.setActive(false); view.releaseSession(); }
        current = s;
        canGoBack = false;
        view.setSession(s);
        s.setActive(true);
    }
    private void flattenChoices(ChoicePrompt.Choice[] input, ArrayList<ChoicePrompt.Choice> output) {
        for (ChoicePrompt.Choice c : input) {
            if (c.disabled || c.separator) continue;
            if (c.items != null) flattenChoices(c.items, output); else output.add(c);
        }
    }
    private GeckoSession newSession() {
        GeckoSession s = new GeckoSession();
        sessions.add(s);
        s.setPromptDelegate(new PromptDelegate() {
            @Override public GeckoResult<PromptResponse> onChoicePrompt(GeckoSession session, ChoicePrompt prompt) {
                GeckoResult<PromptResponse> result = new GeckoResult<>();
                ArrayList<ChoicePrompt.Choice> options = new ArrayList<>();
                flattenChoices(prompt.choices, options);
                String[] labels = new String[options.size()];
                boolean[] selected = new boolean[options.size()];
                for (int i = 0; i < options.size(); i++) {
                    labels[i] = options.get(i).label;
                    selected[i] = options.get(i).selected;
                }
                AlertDialog.Builder dialog = new AlertDialog.Builder(MainActivity.this)
                    .setTitle(prompt.title == null ? "Select option" : prompt.title)
                    .setNegativeButton("Cancel", (d, w) -> { if (!prompt.isComplete()) result.complete(prompt.dismiss()); })
                    .setOnCancelListener(d -> { if (!prompt.isComplete()) result.complete(prompt.dismiss()); });
                if (prompt.type == ChoicePrompt.Type.MULTIPLE) {
                    dialog.setMultiChoiceItems(labels, selected, (d, i, checked) -> selected[i] = checked);
                    dialog.setPositiveButton("OK", (d, w) -> {
                        ArrayList<String> ids = new ArrayList<>();
                        for (int i = 0; i < options.size(); i++) if (selected[i]) ids.add(options.get(i).id);
                        if (!prompt.isComplete()) result.complete(prompt.confirm(ids.toArray(new String[0])));
                    });
                } else {
                    dialog.setItems(labels, (d, i) -> { if (!prompt.isComplete()) result.complete(prompt.confirm(options.get(i))); });
                }
                dialog.show();
                return result;
            }
        });
        s.setProgressDelegate(new GeckoSession.ProgressDelegate() {
            @Override public void onPageStart(GeckoSession session, String url) {
                showSession(session);
                log("Loading " + safeLocation(url));
            }
            @Override public void onPageStop(GeckoSession session, boolean success) {
                if (session == current) log("Load completed: " + success + " (check the displayed page)");
            }
        });
        s.setContentDelegate(new GeckoSession.ContentDelegate() {
            @Override public void onTitleChange(GeckoSession session, String title) {
                if (session == current) log("Title: " + (title == null ? "" : title.substring(0, Math.min(title.length(), 150))));
            }
        });
        s.setNavigationDelegate(new GeckoSession.NavigationDelegate() {
            @Override public void onCanGoBack(GeckoSession session, boolean value) {
                if (session == current) canGoBack = value;
            }
            @Override public GeckoResult<GeckoSession> onNewSession(GeckoSession session, String uri) {
                log("New page " + safeLocation(uri));
                // Gecko opens this session; attach it when its page begins loading.
                return GeckoResult.fromValue(newSession());
            }
            @Override public GeckoResult<String> onLoadError(GeckoSession session, String uri, WebRequestError error) {
                log("Navigation error at " + safeLocation(uri));
                return null;
            }
        });
        return s;
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        TextView heading = new TextView(this);
        heading.setText("Sarathi Gecko Test — access test only\nMozilla engine; navigate manually to slot booking.");
        heading.setPadding(12, 8, 12, 8);
        root.addView(heading);
        status = new TextView(this);
        status.setTextSize(12);
        status.setMaxLines(3);
        root.addView(status);
        LinearLayout row = new LinearLayout(this);
        button(row, "Back", () -> { if (current != null && canGoBack) current.goBack(); });
        button(row, "Home", () -> current.loadUri(HOME));
        button(row, "Reload", () -> current.reload());
        button(row, "Copy log", () -> {
            String report = "App: Gecko access test 1.0\nEngine: GeckoView 158.0.20261021001945\nAndroid API: "
                    + android.os.Build.VERSION.SDK_INT + "\n" + String.join("\n", logs);
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("Gecko access test", report));
            Toast.makeText(this, "Log copied", Toast.LENGTH_SHORT).show();
        });
        root.addView(row);
        view = new GeckoView(this);
        root.addView(view, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        if (runtime == null) runtime = GeckoRuntime.create(getApplicationContext());
        GeckoSession first = newSession();
        first.open(runtime);
        showSession(first);
        first.loadUri(HOME);
    }
    @Override public void onBackPressed() {
        if (current != null && canGoBack) current.goBack(); else super.onBackPressed();
    }
    @Override protected void onPause() {
        if (current != null) current.setActive(false);
        super.onPause();
    }
    @Override protected void onResume() {
        super.onResume();
        if (current != null) current.setActive(true);
    }
    @Override protected void onDestroy() {
        view.releaseSession();
        for (GeckoSession s : sessions) if (s.isOpen()) s.close();
        super.onDestroy();
    }
}
