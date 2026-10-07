package com.sarathi.anantnag;

import android.Manifest;
import android.app.AlertDialog;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {
    public static final String PREFS = "sarathi_prefs";
    public static final String RUNNING = "running";
    private static final String CHANNEL_ID = "sarathi_slots";

    private TextView serviceState, monitorState, lastSeen;

    @Override protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(0xFFF4F6F8);
        getWindow().getDecorView().setSystemUiVisibility(
            android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        setContentView(R.layout.activity_main);

        serviceState = findViewById(R.id.serviceState);
        monitorState = findViewById(R.id.monitorState);
        lastSeen = findViewById(R.id.lastSeen);

        createNotificationChannel();
        requestNotificationPermission();

        findViewById(R.id.enable).setOnClickListener(v ->
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        findViewById(R.id.openChrome).setOnClickListener(v -> openSarathi());
        findViewById(R.id.start).setOnClickListener(v -> {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(RUNNING, true).apply();
            refresh();
        });
        findViewById(R.id.stop).setOnClickListener(v -> {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(RUNNING, false).apply();
            refresh();
        });
        findViewById(R.id.telegram).setOnClickListener(v -> showTelegramDialog());
    }

    @Override protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        boolean enabled = isAccessibilityEnabled();
        boolean running = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(RUNNING, false);

        serviceState.setText(enabled
            ? "🟢 Chrome monitor permission: ENABLED"
            : "🔴 Chrome monitor permission: NOT ENABLED");
        monitorState.setText(running ? "🟢 Monitoring is ON" : "⚪ Monitoring is OFF");
        lastSeen.setText("Last Sarathi page seen: " +
            getSharedPreferences(PREFS, MODE_PRIVATE).getString("last_seen", "--"));
    }

    private boolean isAccessibilityEnabled() {
        String enabled = Settings.Secure.getString(
            getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return false;

        String mine = new ComponentName(
            this, SarathiAccessibilityService.class).flattenToString();

        for (String item : enabled.split(":")) {
            if (mine.equalsIgnoreCase(item)) return true;
        }
        return false;
    }

    private void openSarathi() {
        startActivity(new Intent(Intent.ACTION_VIEW,
            Uri.parse("https://sarathi.parivahan.gov.in/")));
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
                this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, 77);
        }
    }

    private void showTelegramDialog() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);

        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int)(16 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad, pad, pad);

        android.widget.EditText token = new android.widget.EditText(this);
        token.setHint("Telegram bot token");
        token.setText(p.getString("token", ""));

        android.widget.EditText chat = new android.widget.EditText(this);
        chat.setHint("Telegram chat ID");
        chat.setText(p.getString("chat", ""));

        box.addView(token);
        box.addView(chat);

        new AlertDialog.Builder(this)
            .setTitle("Telegram alerts")
            .setMessage("Optional. Local notifications work without Telegram.")
            .setView(box)
            .setPositiveButton("SAVE", (d,w) ->
                p.edit().putString("token", token.getText().toString().trim())
                    .putString("chat", chat.getText().toString().trim()).apply())
            .setNegativeButton("CANCEL", null)
            .show();
    }
}