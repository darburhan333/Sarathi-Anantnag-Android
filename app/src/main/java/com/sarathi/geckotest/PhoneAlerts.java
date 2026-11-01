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
 private long criticalStartedAt;
 private final java.util.Map<String,CriticalStamp> criticalKeys=new java.util.HashMap<>();
 private volatile int notificationGeneration;
 private static final long CRITICAL_REARM_MS=60000L;
 private static final long CRITICAL_POST_GAP_MS=250L;
 private static final long CRITICAL_FAST_PULSE_MS=2000L;
 private static final long CRITICAL_FAST_WINDOW_MS=60000L;
 private static final long CRITICAL_SLOW_PULSE_MS=30000L;
 private static final Object CRITICAL_QUEUE_LOCK=new Object();
 private static final java.util.ArrayDeque<QueuedCritical> CRITICAL_QUEUE=new java.util.ArrayDeque<>();
 private static final Handler CRITICAL_HANDLER=new Handler(Looper.getMainLooper());
 private static boolean criticalDrainScheduled;
 private static final class CriticalStamp {
  final String eventKey;final long at;
  CriticalStamp(String key,long time){eventKey=key;at=time;}
 }
 private static final class QueuedCritical {
  final PhoneAlerts owner;final int generation;final String kind,message;final int id;
  QueuedCritical(PhoneAlerts o,int g,String k,String m,int n){owner=o;generation=g;kind=k;message=m;id=n;}
 }
 void newRun(){synchronized(criticalKeys){criticalKeys.clear();}}
 void critical(String kind,String message){critical(kind,message,message);}
 boolean critical(String kind,String message,String eventKey){return criticalAt(kind,message,eventKey,SystemClock.elapsedRealtime());}
 // Explicit clock injection keeps the 60-second re-arm rule deterministic in instrumentation tests.
 boolean criticalAt(String kind,String message,String eventKey,long now){
  int id=criticalNotificationId(kind);
  String key=eventKey==null||eventKey.isEmpty()?String.valueOf(message):eventKey;
  synchronized(criticalKeys){
   CriticalStamp previous=criticalKeys.get(kind);
   if(previous!=null&&previous.eventKey.equals(key)&&now-previous.at<CRITICAL_REARM_MS)return false;
   criticalKeys.put(kind,new CriticalStamp(key,now));
  }
  enqueueCriticalNotification(kind,message,id);
  runAudioSync(()->{if(!criticalActive){criticalActive=true;criticalStartedAt=SystemClock.elapsedRealtime();volume=100;startAudio(true);}});
  if(activity instanceof MonitorService){MonitorService s=(MonitorService)activity;s.record("ALERT A"+profile+" kind="+kind+" event="+key+" notification=queued audio=active");s.refreshForeground(true);}
  return true;
 }
 private void enqueueCriticalNotification(String kind,String message,int id){
  synchronized(CRITICAL_QUEUE_LOCK){
   CRITICAL_QUEUE.addLast(new QueuedCritical(this,notificationGeneration,kind,message,id));
   if(!criticalDrainScheduled){criticalDrainScheduled=true;CRITICAL_HANDLER.post(PhoneAlerts::drainCriticalQueue);}
  }
 }
 private static void drainCriticalQueue(){
  QueuedCritical item;
  synchronized(CRITICAL_QUEUE_LOCK){
   item=CRITICAL_QUEUE.pollFirst();
   if(item==null){criticalDrainScheduled=false;return;}
  }
  if(item.generation==item.owner.notificationGeneration){
   try{item.owner.postNotification(item.kind,item.message,"sarathi-critical-v36",item.id);}
   catch(Exception ex){if(item.owner.activity instanceof MonitorService)((MonitorService)item.owner.activity).record("ALERT A"+item.owner.profile+" kind="+item.kind+" notification=failed:"+ex.getClass().getSimpleName());}
  }
  synchronized(CRITICAL_QUEUE_LOCK){if(CRITICAL_QUEUE.isEmpty()){criticalDrainScheduled=false;return;}}
  CRITICAL_HANDLER.postDelayed(PhoneAlerts::drainCriticalQueue,CRITICAL_POST_GAP_MS);
 }
 void testOnce(){runAudioSync(()->{if(tone==null)tone=new ToneGenerator(AudioManager.STREAM_ALARM,85);beep();});}

 private final Handler handler=new Handler(Looper.getMainLooper());
 private ToneGenerator tone;
 private Runnable pulse;
 private void runAudioSync(Runnable action){
  if(Looper.myLooper()==Looper.getMainLooper()){action.run();return;}
  java.util.concurrent.CountDownLatch done=new java.util.concurrent.CountDownLatch(1);
  handler.post(()->{try{action.run();}finally{done.countDown();}});
  try{if(!done.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("Timed out waiting for alert audio thread");}
  catch(InterruptedException ex){Thread.currentThread().interrupt();throw new IllegalStateException("Interrupted waiting for alert audio thread",ex);}
 }
 private int audioGeneration;
 private String prefKey(){return "alertsEnabled"+profile;}
 boolean isEnabled(){return activity.getSharedPreferences("monitor",Context.MODE_PRIVATE).getBoolean(prefKey(),true) && !mode().equals("ROUTINE OFF");}
 void setEnabled(boolean enabled){activity.getSharedPreferences("monitor",Context.MODE_PRIVATE).edit().putBoolean(prefKey(),enabled).apply();if(!enabled && !criticalActive)stop();}
 private final Vibrator vibrator;
 private boolean repeating;
 private int volume=85;
 static long criticalPulseDelayForTest(long elapsedSinceStart){return elapsedSinceStart<CRITICAL_FAST_WINDOW_MS?CRITICAL_FAST_PULSE_MS:CRITICAL_SLOW_PULSE_MS;}
 static int criticalVibrationAmplitudeForTest(){return 255;}
 private void schedulePulse(int generation){
  Runnable next=new Runnable(){public void run(){
   if(generation!=audioGeneration||!repeating||tone==null)return;
   beep();
   schedulePulse(generation);
  }};
  pulse=next;long delay=criticalActive?criticalPulseDelayForTest(Math.max(0,SystemClock.elapsedRealtime()-criticalStartedAt)):1800L;handler.postDelayed(next,delay);
 }
 private void startAudio(boolean critical){
  audioGeneration++;handler.removeCallbacks(pulse);pulse=null;
  if(tone!=null){ToneGenerator old=tone;tone=null;old.release();}
  volume=critical?100:volume;
  tone=new ToneGenerator(AudioManager.STREAM_ALARM,volume);
  repeating=true;beep();schedulePulse(audioGeneration);
 }
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
  if(vibrator!=null && vibrator.hasVibrator()){
   if(criticalActive)vibrator.vibrate(VibrationEffect.createWaveform(new long[]{0,900,200,900},new int[]{0,criticalVibrationAmplitudeForTest(),0,criticalVibrationAmplitudeForTest()},-1));
   else vibrator.vibrate(VibrationEffect.createOneShot(500,VibrationEffect.DEFAULT_AMPLITUDE));
  }
 }
 void audio(String action,boolean enabled,int requestedVolume){
  runAudioSync(()->{
   if(criticalActive)return;
   volume=Math.max(0,Math.min(100,requestedVolume));
   if(!isEnabled() && !action.equals("STOP") && !action.equals("TEST") && !action.equals("ARM")){stopOnMain();return;}
   if(action.equals("STOP") || (!enabled && (action.equals("START")||action.equals("UPDATE")))){stopOnMain();return;}
   if(action.equals("UPDATE") && !repeating)return;
   if(action.equals("START")||action.equals("UPDATE"))startAudio(false);
   else if(action.equals("TEST")||action.equals("ARM")){
    audioGeneration++;handler.removeCallbacks(pulse);pulse=null;
    if(tone!=null){ToneGenerator old=tone;tone=null;old.release();}
    tone=new ToneGenerator(AudioManager.STREAM_ALARM,volume);beep();
   }
  });
 }
 private void stopOnMain(){
  audioGeneration++;handler.removeCallbacks(pulse);pulse=null;repeating=false;criticalActive=false;criticalStartedAt=0;
  if(tone!=null){ToneGenerator old=tone;tone=null;old.release();}
  if(vibrator!=null)vibrator.cancel();
 }
 void stop(){runAudioSync(this::stopOnMain);}
 void silence(){
  synchronized(criticalKeys){criticalKeys.clear();}
  stop();
  clearNotification();
 }
 private void cancelQueuedCriticalPosts(){
  synchronized(CRITICAL_QUEUE_LOCK){
   notificationGeneration++;
   CRITICAL_QUEUE.removeIf(item->item.owner==this);
  }
 }
 static final String[] CRITICAL_KINDS={"SMS_READY","GREEN_DATE","CALENDAR","SESSION_EXPIRED","MONITOR_FAILED","PROTECTED_PAGE_LOST","BOOKED","LMV_AVAILABLE","MCWG_AVAILABLE","BOTH_AVAILABLE","SESSION","BOOKING_REVIEW","CONFIRM_REQUIRED"};
 int criticalNotificationId(String kind){
  for(int i=0;i<CRITICAL_KINDS.length;i++)if(CRITICAL_KINDS[i].equals(kind))return 3000+profile*20+i;
  throw new IllegalArgumentException("Unknown critical alert kind: "+kind);
 }
 static String buildAlarmEnvironmentWarning(int currentVolume,int maxVolume,boolean dndBlocksAlarms){
  java.util.ArrayList<String> warnings=new java.util.ArrayList<>();
  if(maxVolume<=0||currentVolume<=0)warnings.add("STREAM_ALARM volume is 0 — critical sound may be inaudible");
  else if(currentVolume*5<=maxVolume)warnings.add("STREAM_ALARM volume is low — increase alarm volume");
  if(dndBlocksAlarms)warnings.add("Do Not Disturb is blocking alarms — allow alarms in DND settings");
  return warnings.isEmpty()?"Alarm volume and Do Not Disturb settings look OK": "WARNING: "+String.join("; ",warnings);
 }
 static String alarmEnvironmentWarning(Context context){
  AudioManager audio=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
  int current=audio==null?0:audio.getStreamVolume(AudioManager.STREAM_ALARM);
  int max=audio==null?0:audio.getStreamMaxVolume(AudioManager.STREAM_ALARM);
  boolean blocks=false;
  try{
   NotificationManager nm=context.getSystemService(NotificationManager.class);
   int filter=nm.getCurrentInterruptionFilter();
   if(filter==NotificationManager.INTERRUPTION_FILTER_NONE)blocks=true;
   else if(filter==NotificationManager.INTERRUPTION_FILTER_PRIORITY){
    if(nm.isNotificationPolicyAccessGranted())blocks=(nm.getNotificationPolicy().priorityCategories&NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS)==0;
    else blocks=true;
   }
  }catch(Exception ignored){}
  return buildAlarmEnvironmentWarning(current,max,blocks);
 }
 void clearNotification(){
  cancelQueuedCriticalPosts();
  NotificationManager nm=activity.getSystemService(NotificationManager.class);
  nm.cancel(1100+profile);
  for(String kind:CRITICAL_KINDS)nm.cancel(criticalNotificationId(kind));
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


