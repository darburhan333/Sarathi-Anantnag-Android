package com.sarathi.geckotest;
import android.app.*;import android.content.*;import android.content.pm.ServiceInfo;import android.net.*;import android.os.*;
/** Shared process owner. Each applicant has independent MonitorRun and browser storage. */
public final class MonitorService extends Service {
 final Handler handler=new Handler(Looper.getMainLooper());
 final BrowserEngine[] profiles=new BrowserEngine[3];BrowserEngine engine;MainActivity activity;boolean visible;
 private boolean promoted,destroying;private long renewAt,stopAfter,lastNotification;
 private PowerManager.WakeLock wake;private ConnectivityManager connectivity;private Network network;
 private final IBinder binder=new LocalBinder();
 final class LocalBinder extends Binder{MonitorService getService(){return MonitorService.this;}}
 @Override public IBinder onBind(Intent intent){return binder;}
 private final ConnectivityManager.NetworkCallback networks=new ConnectivityManager.NetworkCallback(){
  @Override public void onAvailable(Network n){handler.post(()->{if(network!=null&&!network.equals(n))failAll("Network changed — inspect applicants before START");network=n;});}
  @Override public void onLost(Network n){handler.post(()->{if(n.equals(network)){network=null;failAll("Network lost — reconnect before START");}});}
 };
 @Override public void onCreate(){super.onCreate();
  getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("sarathi-monitor","Background monitoring",NotificationManager.IMPORTANCE_LOW));
  wake=getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"MParivahan:monitor");wake.setReferenceCounted(false);
  connectivity=getSystemService(ConnectivityManager.class);network=connectivity.getActiveNetwork();connectivity.registerDefaultNetworkCallback(networks);
  engine=profile(1);
  if(Build.VERSION.SDK_INT>=30)for(ApplicationExitInfo i:getSystemService(ActivityManager.class).getHistoricalProcessExitReasons(getPackageName(),0,3))engine.log("Previous process exit reason="+i.getReason());
  handler.post(tick);
 }
 BrowserEngine profile(int number){int i=number-1;if(i<0||i>2)throw new IllegalArgumentException("Invalid applicant");
  if(profiles[i]==null){profiles[i]=new BrowserEngine(this,number);if(getSharedPreferences("monitor",0).getBoolean("wasRunning"+number,false)){
   profiles[i].log("Previous run interrupted — inspect before START");try{profiles[i].alerts.notify("Run interrupted","Inspect Sarathi and START manually");}catch(Exception ignored){}markRunning(number,false);
  }}return profiles[i];
 }
 void markRunning(int n,boolean value){getSharedPreferences("monitor",0).edit().putBoolean("wasRunning"+n,value).apply();}
 int activeCount(){int n=0;for(BrowserEngine e:profiles)if(e!=null&&e.run.armed)n++;return n;}
 private boolean alerting(){for(BrowserEngine e:profiles)if(e!=null&&e.alerts.isRepeating())return true;return false;}
 void beginForeground(){
  if(activity==null||!visible)throw new IllegalStateException("Open app before START");
  if(!getSystemService(NotificationManager.class).areNotificationsEnabled())throw new IllegalStateException("Allow notifications before START");
  activity.startForegroundService(new Intent(this,MonitorService.class).setAction("ARM"));
  if(Build.VERSION.SDK_INT>=34)startForeground(1002,notification(),ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);else startForeground(1002,notification());
  promoted=true;stopAfter=0;if(!wake.isHeld()){wake.acquire(600000L);renewAt=SystemClock.elapsedRealtime()+300000L;}
 }
 private Notification notification(){
  PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
  PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,MonitorService.class).setAction("STOP"),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
  StringBuilder text=new StringBuilder();for(BrowserEngine e:profiles)if(e!=null&&e.run.armed){if(text.length()>0)text.append(" · ");text.append("A").append(e.profile).append(": ").append(e.lastState.optInt("sessionAttempts")).append(" checks");}
  if(text.length()==0)text.append(alerting()?"Alert active — open app":"Finishing alerts");
  return new Notification.Builder(this,"sarathi-monitor").setSmallIcon(R.drawable.ic_notification).setContentTitle("MPARIVAHAN SLOT BOOKING -J&K").setContentText(text).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).addAction(new Notification.Action.Builder(null,"STOP ALL",stop).build()).build();
 }
 void refreshForeground(boolean immediate){if(destroying||!promoted)return;long now=SystemClock.elapsedRealtime();
  if(activeCount()>0||alerting())stopAfter=0;else if(stopAfter==0)stopAfter=now+30000;
  if(immediate||now-lastNotification>=10000){getSystemService(NotificationManager.class).notify(1002,notification());lastNotification=now;}
  // Retain a bounded delivery grace period after monitoring stops.
  if(activeCount()==0&&!alerting()&&stopAfter>0&&now>=stopAfter&&wake.isHeld())wake.release();
 }
 void stopAll(){for(BrowserEngine e:profiles)if(e!=null)e.run.stopByUser();}
 void failAll(String message){for(BrowserEngine e:profiles)if(e!=null&&e.run.armed)e.run.fail(message);}
 @Override public int onStartCommand(Intent intent,int flags,int id){if(intent!=null&&"STOP".equals(intent.getAction()))stopAll();else if(intent==null)stopSelf();return START_NOT_STICKY;}
 private final Runnable tick=new Runnable(){public void run(){if(destroying)return;
  for(BrowserEngine e:profiles)if(e!=null)e.run.tick();long now=SystemClock.elapsedRealtime();
  if((activeCount()>0||alerting())&&(!wake.isHeld()||now>=renewAt)){wake.acquire(600000L);renewAt=now+300000L;}
  if(promoted){refreshForeground(false);if(stopAfter>0&&now>=stopAfter&&!alerting()&&activeCount()==0){promoted=false;if(wake.isHeld())wake.release();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();}}
  handler.postDelayed(this,2000);
 }};
 String summary(){StringBuilder out=new StringBuilder("MPARIVAHAN SLOT BOOKING -J&K\n");for(int i=0;i<3;i++){BrowserEngine e=profiles[i];out.append("Applicant ").append(i+1).append(": ");if(e==null)out.append("Not opened");else out.append(e.run.armed?"Monitoring":"Stopped").append(" · checks ").append(e.lastState.optInt("sessionAttempts")).append(" · ").append(e.lastState.optString("bookingStage","Ready")).append(e.run.diagnostics());out.append('\n');}return out.toString();}
 public boolean isArmedForTest(){return engine.run.armed;}public int attemptsForTest(){return engine.lastState.optInt("sessionAttempts");}
 void arm(){engine.run.arm();}org.mozilla.geckoview.GeckoResult<Object> waitFor(long ms){return engine.run.waitFor(ms);}
 @Override public void onDestroy(){destroying=true;handler.removeCallbacks(tick);connectivity.unregisterNetworkCallback(networks);for(BrowserEngine e:profiles)if(e!=null){e.run.disarm();e.shutdown();}if(wake.isHeld())wake.release();super.onDestroy();}
}
