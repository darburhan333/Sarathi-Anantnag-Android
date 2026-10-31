package com.sarathi.geckotest;

import android.app.*;
import android.content.*;
import android.media.*;
import android.os.*;

final class PhoneAlerts {
 private final Context activity;private final int profile;boolean isRepeating(){return repeating;}
 static final String[] MODES={"ALL","CRITICAL ONLY","ROUTINE OFF"};
 String mode(){return activity.getSharedPreferences("monitor",0).getString("alertMode"+profile,"CRITICAL ONLY");}
 void setMode(String mode){if(!java.util.Arrays.asList(MODES).contains(mode))throw new IllegalArgumentException("Invalid alert mode");activity.getSharedPreferences("monitor",0).edit().putString("alertMode"+profile,mode).apply();}
 private boolean criticalActive;
 private final java.util.Set<String> criticalKeys=new java.util.HashSet<>();
 void newRun(){criticalKeys.clear();}
 void critical(String kind,String message){
  if(!criticalKeys.add(kind))return;
  String notification="failed",audio="failed";
  try{postNotification(kind,message,"sarathi-critical-v36",criticalNotificationId(kind));notification="posted";}catch(Exception ex){notification="failed:"+ex.getClass().getSimpleName();}
  try{criticalActive=true;volume=85;if(tone!=null)tone.release();tone=new ToneGenerator(AudioManager.STREAM_ALARM,volume);repeating=true;handler.removeCallbacks(pulse);handler.post(pulse);audio="started (audibility depends on device)";}catch(Exception ex){audio="failed:"+ex.getClass().getSimpleName();}
  if(activity instanceof MonitorService){MonitorService s=(MonitorService)activity;s.record("ALERT A"+profile+" kind="+kind+" notification="+notification+" audio="+audio+" Telegram=not requested by native; see extension delivery log");s.refreshForeground(true);}
 }
 void testOnce(){if(tone==null)tone=new ToneGenerator(AudioManager.STREAM_ALARM,85);beep();}

 private final Handler handler=new Handler(Looper.getMainLooper());
 private ToneGenerator tone;
 private String prefKey(){return "alertsEnabled"+profile;}
 boolean isEnabled(){return activity.getSharedPreferences("monitor",Context.MODE_PRIVATE).getBoolean(prefKey(),true) && !mode().equals("ROUTINE OFF");}
 void setEnabled(boolean enabled){activity.getSharedPreferences("monitor",Context.MODE_PRIVATE).edit().putBoolean(prefKey(),enabled).apply();if(!enabled && !criticalActive)stop();}
 private final Vibrator vibrator;
 private boolean repeating;
 private int volume=85;
 private final Runnable pulse=new Runnable(){public void run(){
  if(!repeating)return;
  beep();handler.postDelayed(this,criticalActive?30000:1800);
 }};
 PhoneAlerts(Context a,int number){profile=number;
  activity=a;vibrator=(Vibrator)a.getSystemService(Context.VIBRATOR_SERVICE);
  NotificationChannel channel=new NotificationChannel("sarathi-alerts","Slot alerts",NotificationManager.IMPORTANCE_HIGH);
  channel.setDescription("Calendar, booking and monitoring alerts");
  channel.enableVibration(true);
  a.getSystemService(NotificationManager.class).createNotificationChannel(channel);
  NotificationChannel critical=new NotificationChannel("sarathi-critical-v36","Critical booking alarms",NotificationManager.IMPORTANCE_HIGH);
  critical.setSound(android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM),new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build());critical.enableVibration(true);a.getSystemService(NotificationManager.class).createNotificationChannel(critical);
 }
 private void beep(){
  if(tone!=null)tone.startTone(ToneGenerator.TONE_PROP_BEEP2,650);
  if(vibrator!=null && vibrator.hasVibrator())vibrator.vibrate(VibrationEffect.createOneShot(500,VibrationEffect.DEFAULT_AMPLITUDE));
 }
 void audio(String action,boolean enabled,int requestedVolume){
  if(criticalActive)return;
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
 void stop(){criticalActive=false;repeating=false;handler.removeCallbacks(pulse);if(tone!=null){tone.release();tone=null;}if(vibrator!=null)vibrator.cancel();}
 int criticalNotificationId(String kind){
  String[] kinds={"SMS_READY","GREEN_DATE","CALENDAR","SESSION_EXPIRED","MONITOR_FAILED","PROTECTED_PAGE_LOST"};
  for(int i=0;i<kinds.length;i++)if(kinds[i].equals(kind))return 2000+profile*10+i;
  throw new IllegalArgumentException("Unknown critical alert kind: "+kind);
 }
 void clearNotification(){
  NotificationManager nm=activity.getSystemService(NotificationManager.class);
  nm.cancel(1100+profile);
  for(String kind:new String[]{"SMS_READY","GREEN_DATE","CALENDAR","SESSION_EXPIRED","MONITOR_FAILED","PROTECTED_PAGE_LOST"})nm.cancel(criticalNotificationId(kind));
 }
 void notify(String title,String message){
  if(!isEnabled())return;
  postNotification(title,message,"sarathi-alerts",1100+profile);
 }
 private void postNotification(String title,String message,String channel,int notificationId){
  NotificationManager nm=activity.getSystemService(NotificationManager.class);
  if(!nm.areNotificationsEnabled())throw new IllegalStateException("Allow Sarathi notifications in Android Settings");
  PendingIntent open=PendingIntent.getActivity(activity,100+profile,new Intent(activity,MainActivity.class).putExtra("applicant",profile).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
  Notification n=new Notification.Builder(activity,channel).setSmallIcon(R.drawable.ic_notification)
   .setContentTitle("Applicant "+profile+" · "+title).setContentText(message).setStyle(new Notification.BigTextStyle().bigText(message))
   .setCategory(Notification.CATEGORY_ALARM).setContentIntent(open).setAutoCancel(true).build();
  nm.notify(notificationId,n);
 }
}


