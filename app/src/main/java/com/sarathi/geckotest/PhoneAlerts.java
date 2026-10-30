package com.sarathi.geckotest;

import android.app.*;
import android.content.*;
import android.media.*;
import android.os.*;

final class PhoneAlerts {
 private final Context activity;private final int profile;boolean isRepeating(){return repeating;}
 private final Handler handler=new Handler(Looper.getMainLooper());
 private ToneGenerator tone;
 private String prefKey(){return "alertsEnabled"+profile;}
 String mode(){return activity.getSharedPreferences("monitor",Context.MODE_PRIVATE).getString("alertMode"+profile,"CRITICAL_ONLY");}
 void setMode(String mode){if(!"ALL".equals(mode)&&!"CRITICAL_ONLY".equals(mode)&&!"ROUTINE_OFF".equals(mode))throw new IllegalArgumentException("Unknown alert mode");activity.getSharedPreferences("monitor",Context.MODE_PRIVATE).edit().putString("alertMode"+profile,mode).apply();}
 // Existing Android alarm actions are emitted only for critical events. They must ring in every mode.
 boolean isEnabled(){return true;}
 void setEnabled(boolean enabled){activity.getSharedPreferences("monitor",Context.MODE_PRIVATE).edit().putBoolean(prefKey(),enabled).apply();}
 private final Vibrator vibrator;
 private boolean repeating;
 private int volume=85;
 private final Runnable pulse=new Runnable(){public void run(){
  if(!repeating)return;
  beep();handler.postDelayed(this,1800);
 }};
 private final Runnable testStop=()->stop();
 void testFullAlarm(){stop();audio("START",true,85);handler.removeCallbacks(testStop);handler.postDelayed(testStop,5600);}
 PhoneAlerts(Context a,int number){profile=number;
  activity=a;vibrator=(Vibrator)a.getSystemService(Context.VIBRATOR_SERVICE);
  NotificationChannel channel=new NotificationChannel("sarathi-alerts","Slot alerts",NotificationManager.IMPORTANCE_HIGH);
  channel.setDescription("Calendar, booking and monitoring alerts");
  channel.enableVibration(true);
  a.getSystemService(NotificationManager.class).createNotificationChannel(channel);
 }
 private void beep(){
  if(tone!=null)tone.startTone(ToneGenerator.TONE_PROP_BEEP2,650);
  if(vibrator!=null && vibrator.hasVibrator())vibrator.vibrate(VibrationEffect.createOneShot(500,VibrationEffect.DEFAULT_AMPLITUDE));
 }
 void audio(String action,boolean enabled,int requestedVolume){
  volume=Math.max(0,Math.min(100,requestedVolume));
  if(!isEnabled() && !action.equals("STOP") && !action.equals("TEST") && !action.equals("ARM")){stop();return;}
  if(action.equals("STOP") || (!enabled && (action.equals("START")||action.equals("UPDATE")))){stop();return;}
  if(action.equals("UPDATE") && !repeating)return;
  if(tone!=null)tone.release();
  tone=new ToneGenerator(AudioManager.STREAM_ALARM,volume);
  if(action.equals("START")||action.equals("UPDATE")){
   repeating=true;handler.removeCallbacks(pulse);handler.post(pulse);
  }else if(action.equals("TEST")||action.equals("ARM"))beep();
 }
 void stop(){repeating=false;handler.removeCallbacks(pulse);if(tone!=null){tone.release();tone=null;}if(vibrator!=null)vibrator.cancel();}
 void clearNotification(){activity.getSystemService(NotificationManager.class).cancel(1100+profile);}
 void notify(String title,String message){
  if(!isEnabled())return;
  NotificationManager nm=activity.getSystemService(NotificationManager.class);
  if(!nm.areNotificationsEnabled())throw new IllegalStateException("Allow Sarathi notifications in Android Settings");
  PendingIntent open=PendingIntent.getActivity(activity,100+profile,new Intent(activity,MainActivity.class).putExtra("applicant",profile).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
  Notification n=new Notification.Builder(activity,"sarathi-alerts").setSmallIcon(R.drawable.ic_notification)
   .setContentTitle("Applicant "+profile+" · "+title).setContentText(message).setStyle(new Notification.BigTextStyle().bigText(message))
   .setCategory(Notification.CATEGORY_ALARM).setContentIntent(open).setAutoCancel(true).build();
  nm.notify(1100+profile,n);
 }
}


