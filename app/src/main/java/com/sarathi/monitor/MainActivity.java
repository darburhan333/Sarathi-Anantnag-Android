package com.sarathi.monitor;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private FrameLayout container;
    private TextView statusView;
    private TextView logView;
    private Button desktopBtn;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            updateStatus();
            ui.postDelayed(this, 1000L);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        buildUi();
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        Core.attach(this, container);
        ui.post(refresh);
    }

    @Override
    protected void onStop() {
        ui.removeCallbacks(refresh);
        Core.detach(this);
        super.onStop();
    }

    @Override
    public void onBackPressed() {
        if (Core.web != null && Core.web.canGoBack()) {
            Core.web.goBack();
        } else {
            moveTaskToBack(true);
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private Button button(String label, View.OnClickListener l) {
        Button btn = new Button(this);
        btn.setText(label);
        btn.setAllCaps(false);
        btn.setTextSize(13);
        btn.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        btn.setLayoutParams(lp);
        return btn;
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(6), dp(6), dp(6), dp(6));

        statusView = new TextView(this);
        statusView.setTextSize(14);
        root.addView(statusView);

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.addView(button("START", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startMonitor();
            }
        }));
        row1.addView(button("STOP", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startService(new Intent(MainActivity.this, MonitorService.class).setAction(MonitorService.ACTION_STOP));
            }
        }));
        row1.addView(button("ALARM OFF", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Alerts.stopAlarm(MainActivity.this);
            }
        }));
        row1.addView(button("TEST", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Alerts.createChannels(MainActivity.this);
                Alerts.fire(MainActivity.this, "Test alert",
                        "Sarathi monitor test alert. If you hear the alarm and get this on Telegram, alerts work.", true);
            }
        }));
        root.addView(row1);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.addView(button("Settings", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSettings();
            }
        }));
        row2.addView(button("Battery", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openBatterySettings();
            }
        }));
        desktopBtn = button(Core.desktopMode(this) ? "Desktop: on" : "Desktop: off", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean now = !Core.desktopMode(MainActivity.this);
                Core.prefs(MainActivity.this).edit().putBoolean("desktop", now).apply();
                Core.applyUserAgent(MainActivity.this);
                desktopBtn.setText(now ? "Desktop: on" : "Desktop: off");
                if (Core.web != null) Core.web.reload();
            }
        });
        row2.addView(desktopBtn);
        row2.addView(button("Home", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (Core.web != null) Core.web.loadUrl(Core.HOME);
            }
        }));
        root.addView(row2);

        logView = new TextView(this);
        logView.setTextSize(11);
        logView.setMaxLines(5);
        root.addView(logView);

        container = new FrameLayout(this);
        root.addView(container, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    private void startMonitor() {
        SharedPreferences p = Core.prefs(this);
        if (p.getString("token", "").trim().isEmpty() || p.getString("chat", "").trim().isEmpty()) {
            Toast.makeText(this, "Telegram not set — you will only get phone alerts", Toast.LENGTH_LONG).show();
        }
        Intent i = new Intent(this, MonitorService.class).setAction(MonitorService.ACTION_START);
        startForegroundService(i);
    }

    private boolean ignoringBattery() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    private void openBatterySettings() {
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception e2) {
                Toast.makeText(this, "Open Settings > Apps > Sarathi Monitor > Battery > Unrestricted", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void updateStatus() {
        String battery = ignoringBattery() ? "battery: unrestricted" : "battery: RESTRICTED (tap Battery)";
        String u = Core.web == null ? null : Core.web.getUrl();
        String page = "-";
        if (u != null) {
            String path = Uri.parse(u).getPath();
            page = path == null || path.isEmpty() ? "/" : path;
        }
        statusView.setText((Core.running ? "● RUNNING  " : "○ stopped  ") + Core.status
                + "\nattempts " + Core.attempts + "  |  " + battery + "\npage: " + page);
        logView.setText(Core.logText(5));
    }

    private void showSettings() {
        final SharedPreferences p = Core.prefs(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(8), dp(16), dp(8));

        final EditText label = new EditText(this);
        label.setHint("Label shown in alerts (e.g. Phone 1)");
        label.setText(p.getString("label", ""));
        final EditText token = new EditText(this);
        token.setHint("Telegram bot token");
        token.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        token.setText(p.getString("token", ""));
        final EditText chat = new EditText(this);
        chat.setHint("Telegram chat ID (comma-separate several)");
        chat.setText(p.getString("chat", ""));
        box.addView(label);
        box.addView(token);
        box.addView(chat);

        new AlertDialog.Builder(this)
                .setTitle("Alert settings")
                .setView(box)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        p.edit()
                                .putString("label", label.getText().toString().trim())
                                .putString("token", token.getText().toString().trim())
                                .putString("chat", chat.getText().toString().trim())
                                .apply();
                        Toast.makeText(MainActivity.this, "Saved", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}
