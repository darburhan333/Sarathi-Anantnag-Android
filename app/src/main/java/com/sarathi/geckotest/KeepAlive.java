package com.sarathi.geckotest;

import android.content.Context;
import android.graphics.PixelFormat;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

/**
 * Keeps the app process classified as "visible / playing media" by Android and by
 * OEM background managers while monitoring is armed and the Activity is minimized.
 *
 * 1) A 1x1 almost-invisible overlay window (needs "Display over other apps").
 * 2) A silent PCM stream (inaudible, no audio focus requested, other apps unaffected).
 *
 * Both are started only while at least one applicant is armed and stopped otherwise.
 * Neither can bypass anything on the Sarathi website; they only stop the phone from
 * freezing this app's own process.
 */
final class KeepAlive {
  private final MonitorService service;
  private final Context context;
  private final Handler main = new Handler(Looper.getMainLooper());
  private View overlay;
  private volatile boolean audioRunning;
  private Thread audioThread;
  private final java.util.concurrent.atomic.AtomicInteger audioGeneration=new java.util.concurrent.atomic.AtomicInteger();
  private String lastStatus = "";

  KeepAlive(MonitorService service) {
    this.service = service;
    this.context = service.getApplicationContext();
  }

  boolean overlayAllowed() {
    try {
      return Settings.canDrawOverlays(context);
    } catch (Exception e) {
      return false;
    }
  }

  /** Idempotent; safe to call every few seconds. */
  void start() {
    main.post(this::showOverlay);
    startAudio();
    String status = status();
    if (!status.equals(lastStatus)) {
      lastStatus = status;
      service.record("Keep-alive " + status);
    }
  }

  synchronized void stop() {
    audioGeneration.incrementAndGet();audioRunning = false;
    if(audioThread!=null)audioThread.interrupt();
    main.post(this::hideOverlay);
    if (!lastStatus.isEmpty()) {
      lastStatus = "";
      service.record("Keep-alive stopped");
    }
  }

  String status() {
    return "overlay=" + (overlay != null ? "ON" : (overlayAllowed() ? "pending" : "OFF (permission missing)"))
        + "; audio=" + (audioRunning ? "ON" : "OFF");
  }

  private void showOverlay() {
    if (overlay != null || !overlayAllowed()) return;
    try {
      WindowManager wm = context.getSystemService(WindowManager.class);
      View v = new View(context);
      v.setBackgroundColor(0x01000000); // alpha 1/255: effectively invisible but not empty
      WindowManager.LayoutParams p = new WindowManager.LayoutParams(
          1, 1,
          WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
          WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
              | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
              | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
          PixelFormat.TRANSLUCENT);
      p.gravity = Gravity.TOP | Gravity.START;
      p.setTitle("MParivahan keep-alive");
      wm.addView(v, p);
      overlay = v;
    } catch (Exception e) {
      overlay = null;
      service.record("Keep-alive overlay failed: " + e.getClass().getSimpleName());
    }
  }

  private void hideOverlay() {
    View v = overlay;
    overlay = null;
    if (v == null) return;
    try {
      context.getSystemService(WindowManager.class).removeView(v);
    } catch (Exception error) {service.record("Keep-alive overlay removal failed: "+error.getClass().getSimpleName());
    }
  }

  private synchronized void startAudio() {
    if (audioRunning && audioThread != null && audioThread.isAlive()) return;
    try {
      final int generation=audioGeneration.incrementAndGet();
      final int rate = 8000;
      int min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
      final AudioTrack track = new AudioTrack.Builder()
          .setAudioAttributes(new AudioAttributes.Builder()
              .setUsage(AudioAttributes.USAGE_MEDIA)
              .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
              .build())
          .setAudioFormat(new AudioFormat.Builder()
              .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
              .setSampleRate(rate)
              .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
              .build())
          .setBufferSizeInBytes(Math.max(min, rate))
          .setTransferMode(AudioTrack.MODE_STREAM)
          .build();
      track.play();
      audioRunning = true;
      audioThread = new Thread(() -> {
        short[] silence = new short[rate / 2];
        try {
          while (audioRunning&&audioGeneration.get()==generation) {
            if (track.write(silence, 0, silence.length) < 0){service.record("Keep-alive audio write failed");break;}
          }
        } catch (Exception error) {service.record("Keep-alive audio error: "+error.getClass().getSimpleName());
        } finally {
          if(audioGeneration.get()==generation)audioRunning = false;
          try { track.stop(); } catch (Exception ignored) { }
          try { track.release(); } catch (Exception ignored) { }
        }
      }, "MonitorKeepAlive");
      audioThread.setDaemon(true);
      audioThread.start();
    } catch (Exception e) {
      audioRunning = false;
      service.record("Keep-alive audio failed: " + e.getClass().getSimpleName());
    }
  }
}
