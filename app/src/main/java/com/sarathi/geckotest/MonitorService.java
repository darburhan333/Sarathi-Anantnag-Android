package com.sarathi.geckotest;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.net.*;
import android.os.*;
import org.json.JSONObject;
import org.mozilla.geckoview.GeckoResult;
import java.util.*;

/** Owns Gecko independently of the visible Activity. Never restarts a lost booking run. */
public final class MonitorService extends Service {
 final Handler handler=new Handler(Looper.getMainLooper());
 BrowserEngine engine;
 boolean armed, sawRunning;
 private PowerManager.WakeLock wake;
 private long armedAt,lastBeat,lastProgress,lastTick,longestGap,lastHeartbeatValue,lastProgressValue;
 private int generation;
 private final Map<GeckoResult<Object>,Runnable> waits=new HashMap<>();
 private ConnectivityManager connectivity;
 private Network network;
 private final IBinder binder=new LocalBinder();
 public final class LocalBinder extends Binder {MonitorService getService(){return MonitorService.this;}}
 @Override public IBinder onBind(Intent intent){return binder;}
 private final ConnectivityManager.NetworkCallback networks=new ConnectivityManager.NetworkCallback(){
  @Override public void onAvailable(Network n){handler.post(()->{
   if(armed&&network!=null&&!network.equals(n))fail("Network changed — check Sarathi and press START");network=n;
  });}
  @Override public void onLost(Network n){handler.post(()->{
   if(n.equals(network)){network=null;if(armed)fail("Network lost — reconnect and press START");}
  });}
 };
 @Override public void onCreate(){
  super.onCreate();
  NotificationChannel channel=new NotificationChannel("sarathi-monitor","Background monitoring",NotificationManager.IMPORTANCE_LOW);
  channel.setDescription("Shows monitoring status with a Stop control");
  getSystemService(NotificationManager.class).createNotificationChannel(channel);
  wake=getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"Sarathi:monitor");wake.setReferenceCounted(false);
  connectivity=getSystemService(ConnectivityManager.class);network=connectivity.getActiveNetwork();
  connectivity.registerDefaultNetworkCallback(networks);
  engine=new BrowserEngine(this);
  if(getSharedPreferences("monitor",0).getBoolean("wasRunning",false)){
   engine.log("Previous process ended during monitoring — inspect Sarathi before START");
   try{engine.alerts.notify("Monitoring was interrupted","Previous process ended. Open Sarathi and press START manually.");}catch(Exception ignored){}
   getSharedPreferences("monitor",0).edit().putBoolean("wasRunning",false).apply();
  }
  if(Build.VERSION.SDK_INT>=30){
   for(ApplicationExitInfo info:getSystemService(ActivityManager.class).getHistoricalProcessExitReasons(getPackageName(),0,3))
    engine.log("Previous process exit: reason="+info.getReason()+", time="+info.getTimestamp());
  }
  handler.post(tick);
 }
 private Notification notification(){
  PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
  PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,MonitorService.class).setAction("STOP"),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
  String text=engine==null?"Starting":(engine.foreground?"Foreground":"Background")+" · checks "+engine.lastState.optInt("sessionAttempts")+" · "+engine.lastState.optString("bookingStage","Starting");
  return new Notification.Builder(this,"sarathi-monitor").setSmallIcon(android.R.drawable.ic_popup_sync)
   .setContentTitle("Sarathi monitoring active").setContentText(text).setContentIntent(open)
   .setOngoing(true).setOnlyAlertOnce(true).addAction(new Notification.Action.Builder(null,"STOP",stop).build()).build();
 }
 boolean authorize(){
  if(armed&&SystemClock.elapsedRealtime()-lastTick>30000)fail("Monitoring timing interrupted — reopen and press START");
  return armed;
 }
 void arm(){
  if(engine.activity==null||!engine.foreground)throw new IllegalStateException("Open the app before pressing START");
  if(!getSystemService(NotificationManager.class).areNotificationsEnabled())throw new IllegalStateException("Allow notifications before background monitoring");
  if(armed)return;
  // Begin from a resumed screen; no automatic background/boot starts.
  engine.activity.startForegroundService(new Intent(this,MonitorService.class).setAction("ARM"));
  promote();
 }
 private void promote(){
  if(Build.VERSION.SDK_INT>=34)startForeground(1002,notification(),ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
  else startForeground(1002,notification());
  if(armed)return;
  armed=true;sawRunning=false;generation++;armedAt=lastBeat=lastProgress=lastTick=SystemClock.elapsedRealtime();longestGap=0;
  lastHeartbeatValue=lastProgressValue=0;
  wake.acquire(10*60*1000L); // Renewed only while the watchdog is healthy; bounded if the thread stalls.
  getSharedPreferences("monitor",0).edit().putBoolean("wasRunning",true).apply();
  engine.activate();engine.log("Background service ready; battery exemption="+getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName()));
 }
 @Override public int onStartCommand(Intent intent,int flags,int id){
  if(intent!=null&&"STOP".equals(intent.getAction()))stopByUser();
  else if(intent!=null&&"ARM".equals(intent.getAction())){
   // arm() already promoted synchronously, before its native acknowledgement.
   if(!armed){startForeground(1002,notification());stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();}
  }else {stopSelf();}
  return START_NOT_STICKY;
 }
 void disarm(){
  boolean wasArmed=armed;armed=false;sawRunning=false;generation++;
  if(wasArmed&&engine!=null)engine.command("DISARMED");
  for(Map.Entry<GeckoResult<Object>,Runnable> entry:new ArrayList<>(waits.entrySet())){
   handler.removeCallbacks(entry.getValue());entry.getKey().complete("{\"ok\":false,\"cancelled\":true}");
  }
  waits.clear();if(wake.isHeld())wake.release();
  getSharedPreferences("monitor",0).edit().putBoolean("wasRunning",false).apply();
  stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();
  if(engine!=null){engine.activate();engine.render();}
 }
 void stopByUser(){if(engine!=null){engine.command("STOP");engine.alerts.stop();}disarm();}
 void fail(String message){
  if(engine==null)return;
  engine.log(message);engine.command("FAILURE");
  try{engine.alerts.notify("Monitoring needs attention",message);engine.alerts.audio("TEST",true,85);}catch(Exception ignored){}
  disarm();
 }
 void onState(JSONObject state){
  long now=SystemClock.elapsedRealtime();
  long beat=state.optLong("heartbeatAt"),progress=state.optLong("monitorProgressAt");
  if(beat>lastHeartbeatValue){lastHeartbeatValue=beat;lastBeat=now;}
  if(progress>lastProgressValue){lastProgressValue=progress;lastProgress=now;}
  boolean active=state.optBoolean("running")||state.optBoolean("successWatch");
  if(armed){
   if(active)sawRunning=true;else if(sawRunning){disarm();return;}
   getSystemService(NotificationManager.class).notify(1002,notification());
  }
 }
 GeckoResult<Object> waitFor(long ms){
  GeckoResult<Object> result=new GeckoResult<>();
  if(!armed||ms<0||ms>8000){result.complete("{\"ok\":false,\"cancelled\":true}");return result;}
  final int epoch=generation;final long started=SystemClock.elapsedRealtime();
  Runnable completion=()->{
   waits.remove(result);long gap=SystemClock.elapsedRealtime()-started;
   boolean valid=armed&&epoch==generation&&gap<30000;
   result.complete(valid?"{\"ok\":true}":"{\"ok\":false,\"cancelled\":true}");
   if(!valid&&armed)fail("Monitoring timing interrupted — reopen and press START");
  };
  waits.put(result,completion);handler.postDelayed(completion,ms);return result;
 }
 private final Runnable tick=new Runnable(){public void run(){
  if(armed){
   long now=SystemClock.elapsedRealtime(),gap=now-lastTick;lastTick=now;longestGap=Math.max(longestGap,gap);
   if(gap>30000)fail("Phone suspended monitoring — check battery settings and press START");
   else if(!sawRunning&&now-armedAt>25000)fail("Monitoring did not start — return to Sarathi and press START");
   else if(sawRunning&&now-lastBeat>60000)fail("No website heartbeat for 60 seconds — open Sarathi");
   else if(engine.lastState.optBoolean("running")&&!engine.lastState.optBoolean("calendarLock")&&now-lastProgress>60000)fail("No monitoring progress for 60 seconds — open Sarathi");
   if(armed){wake.acquire(10*60*1000L);engine.activate();engine.command("PING");engine.render();}
  }
  handler.postDelayed(this,2000);
 }};
 String diagnostics(){
  if(!armed)return "";
  return "\n"+(engine.foreground?"Foreground service":"Background service")+" · gap max "+(longestGap/1000)+"s · last page update "+((SystemClock.elapsedRealtime()-lastBeat)/1000)+"s ago";
 }
 public boolean isArmedForTest(){return armed;}
 public int attemptsForTest(){return engine.lastState.optInt("sessionAttempts");}
 @Override public void onDestroy(){
  handler.removeCallbacks(tick);connectivity.unregisterNetworkCallback(networks);
  disarm();if(engine!=null)engine.shutdown();super.onDestroy();
 }
}
