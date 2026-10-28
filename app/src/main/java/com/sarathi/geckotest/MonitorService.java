package com.sarathi.geckotest;
import android.app.*;import android.content.*;import android.content.pm.ServiceInfo;import android.net.*;import android.os.*;
/** Shared process owner. Each applicant has independent MonitorRun and browser storage. */
public final class MonitorService extends Service {
 final Handler handler=new Handler(Looper.getMainLooper());
 static final int PROFILE_COUNT=9;
 final BrowserEngine[] profiles=new BrowserEngine[PROFILE_COUNT];BrowserEngine engine;MainActivity activity;boolean visible;
 private volatile boolean promoted,destroying;
 final HandlerThread clockThread=new HandlerThread("MonitoringClock",android.os.Process.THREAD_PRIORITY_DEFAULT);Handler clock;
 private volatile long clockBeat,clockUptime,longestClockGap,mainBeat;private final java.util.concurrent.atomic.AtomicBoolean tickQueued=new java.util.concurrent.atomic.AtomicBoolean();
 private volatile boolean needsCpu;
 final String processId=java.util.UUID.randomUUID().toString().substring(0,8);private volatile long renewAt;private long stopAfter,lastNotification;
 private PowerManager.WakeLock wake;private android.net.wifi.WifiManager.WifiLock wifiLock;private ConnectivityManager connectivity;private Network network;
 private final IBinder binder=new LocalBinder();
 final class LocalBinder extends Binder{MonitorService getService(){return MonitorService.this;}}
 @Override public IBinder onBind(Intent intent){return binder;}
 private final ConnectivityManager.NetworkCallback networks=new ConnectivityManager.NetworkCallback(){
  @Override public void onAvailable(Network n){handler.post(()->{if(network!=null&&!network.equals(n))failAll("Network changed — inspect applicants before START");network=n;});}
  @Override public void onLost(Network n){handler.post(()->{if(n.equals(network)){network=null;failAll("Network lost — reconnect before START");}});}
 };
 @Override public void onCreate(){super.onCreate();clockThread.start();clock=new Handler(clockThread.getLooper());
  getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("sarathi-monitor","Background monitoring",NotificationManager.IMPORTANCE_LOW));
  wake=getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"MParivahan:monitor");wake.setReferenceCounted(false);
  android.net.wifi.WifiManager wifi=(android.net.wifi.WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE);
  if(wifi!=null){wifiLock=wifi.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF,"MParivahan:monitor-wifi");wifiLock.setReferenceCounted(false);}
  connectivity=getSystemService(ConnectivityManager.class);network=connectivity.getActiveNetwork();connectivity.registerDefaultNetworkCallback(networks);
  for(int n=1;n<=PROFILE_COUNT;n++)getSystemService(NotificationManager.class).cancel(1100+n);
  engine=profile(1);record("Service created; process="+processId);
  if(Build.VERSION.SDK_INT>=30)for(ApplicationExitInfo i:getSystemService(ActivityManager.class).getHistoricalProcessExitReasons(getPackageName(),0,3))engine.log("Previous exit: reason="+i.getReason()+" pid="+i.getPid()+" process="+i.getProcessName()+" time="+i.getTimestamp()+" detail="+i.getDescription());
  clock.post(clockTick);
 }
 BrowserEngine profile(int number){int i=number-1;if(i<0||i>=PROFILE_COUNT)throw new IllegalArgumentException("Invalid applicant");
  if(profiles[i]==null){profiles[i]=new BrowserEngine(this,number);if(getSharedPreferences("monitor",0).getBoolean("wasRunning"+number,false)){
   profiles[i].log("Previous run interrupted — inspect before START");markRunning(number,false);
  }}return profiles[i];
 }
 void markRunning(int n,boolean value){getSharedPreferences("monitor",0).edit().putBoolean("wasRunning"+n,value).apply();}
 int activeCount(){int n=0;for(BrowserEngine e:profiles)if(e!=null&&e.run.armed)n++;return n;}
 private boolean alerting(){for(BrowserEngine e:profiles)if(e!=null&&e.alerts.isRepeating())return true;return false;}
 void beginForeground(){
  if(activity==null||!visible)throw new IllegalStateException("Open app before START");
  if(!getSystemService(NotificationManager.class).areNotificationsEnabled())throw new IllegalStateException("Allow notifications before START");
  PowerManager power=getSystemService(PowerManager.class);
  if(!power.isIgnoringBatteryOptimizations(getPackageName()))throw new IllegalStateException("Open Background setup and allow battery optimization exemption before START");
  if(power.isPowerSaveMode())throw new IllegalStateException("Turn off Battery Saver before background monitoring");
  activity.startForegroundService(new Intent(this,MonitorService.class).setAction("ARM"));
  if(Build.VERSION.SDK_INT>=34)startForeground(1002,notification(),ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);else startForeground(1002,notification());
  promoted=true;stopAfter=0;needsCpu=true;synchronized(wake){if(!wake.isHeld())wake.acquire();}
  if(wifiLock!=null&&!wifiLock.isHeld())wifiLock.acquire();
 }
 private Notification notification(){
  PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
  PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,MonitorService.class).setAction("STOP"),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
  StringBuilder text=new StringBuilder();for(BrowserEngine e:profiles)if(e!=null&&e.run.armed){if(text.length()>0)text.append(" · ");text.append("A").append(e.profile).append(": ").append(e.lastState.optInt("sessionAttempts")).append(" checks");}
  if(text.length()==0)text.append(alerting()?"Alert active — open app":"Finishing alerts");
  return new Notification.Builder(this,"sarathi-monitor").setSmallIcon(R.drawable.ic_notification).setContentTitle("MPARIVAHAN SLOT BOOKING -J&K").setContentText(text).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).addAction(new Notification.Action.Builder(null,"STOP ALL",stop).build()).build();
 }
 void refreshForeground(boolean immediate){needsCpu=activeCount()>0||alerting();if(destroying||!promoted)return;long now=SystemClock.elapsedRealtime();
  if(activeCount()>0||alerting())stopAfter=0;else if(stopAfter==0)stopAfter=now+30000;
  if(immediate||now-lastNotification>=10000){getSystemService(NotificationManager.class).notify(1002,notification());lastNotification=now;}
  // Retain a bounded delivery grace period after monitoring stops.
  if(activeCount()==0&&!alerting()&&stopAfter>0&&now>=stopAfter){if(wake.isHeld())wake.release();if(wifiLock!=null&&wifiLock.isHeld())wifiLock.release();}
 }
 private final java.util.Map<Integer,String> statusBots=new java.util.HashMap<>();
 private final java.util.Map<Integer,Long> statusClaims=new java.util.HashMap<>();
 boolean claimStatus(int profile)throws Exception{
  String token=SecureSettings.read(this,profile).optString("botToken").trim();if(token.isEmpty())throw new IllegalStateException("Configure this applicant's bot first");
  String bot=token.split(":",2)[0];long now=SystemClock.elapsedRealtime();
  for(Integer other:new java.util.ArrayList<>(statusBots.keySet()))if(now-statusClaims.getOrDefault(other,0L)>90000){statusBots.remove(other);statusClaims.remove(other);}
  for(java.util.Map.Entry<Integer,String> e:statusBots.entrySet())if(e.getKey()!=profile&&bot.equals(e.getValue()))return false;
  statusBots.put(profile,bot);statusClaims.put(profile,now);return true;
 }
 void releaseStatus(int profile){statusBots.remove(profile);statusClaims.remove(profile);}
 void silenceAll(){for(BrowserEngine e:profiles)if(e!=null){e.alerts.stop();e.command("SILENCE");}refreshForeground(true);}
 void stopForBackground(String detail){
  if(activeCount()==0)return;
  String message="Background monitoring stopped for all applicants — inspect pages and press START";
  record(message+"; cause="+detail);
  for(BrowserEngine e:profiles)if(e!=null&&e.run.armed)e.run.stopForBackground(message);
  refreshForeground(true);
 }
 void stopAll(){for(BrowserEngine e:profiles)if(e!=null)e.run.stopByUser();}
 void failAll(String message){for(BrowserEngine e:profiles)if(e!=null&&e.run.armed)e.run.fail(message);}
 @Override public int onStartCommand(Intent intent,int flags,int id){if(intent!=null&&"STOP".equals(intent.getAction()))stopAll();else if(intent==null)stopSelf();return START_NOT_STICKY;}
 private final Runnable clockTick=new Runnable(){public void run(){if(destroying)return;
  long now=SystemClock.elapsedRealtime(),up=SystemClock.uptimeMillis();
  if(clockBeat>0)longestClockGap=Math.max(longestClockGap,now-clockBeat);clockBeat=now;clockUptime=up;
  synchronized(wake){if(needsCpu&&promoted&&!wake.isHeld())wake.acquire();}
  if(needsCpu&&promoted&&wifiLock!=null&&!wifiLock.isHeld())wifiLock.acquire();
  if(tickQueued.compareAndSet(false,true))handler.post(()->{tickQueued.set(false);if(destroying)return;mainBeat=SystemClock.elapsedRealtime();
   for(BrowserEngine e:profiles)if(e!=null)e.run.tick();
   if(promoted){refreshForeground(false);if(stopAfter>0&&SystemClock.elapsedRealtime()>=stopAfter&&!alerting()&&activeCount()==0){promoted=false;needsCpu=false;synchronized(wake){if(wake.isHeld())wake.release();}if(wifiLock!=null&&wifiLock.isHeld())wifiLock.release();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();}}
  });clock.postDelayed(this,2000);
 }};
 String clockDiagnostics(){long now=SystemClock.elapsedRealtime();return "clock gap max="+longestClockGap+"ms; main age="+(now-mainBeat)+"ms; elapsed="+now+"; uptime="+SystemClock.uptimeMillis()+"; clock uptime="+clockUptime+"; foreground="+promoted+"; wake="+wake.isHeld()+"; wifiLock="+(wifiLock!=null&&wifiLock.isHeld())+"; screen="+getSystemService(PowerManager.class).isInteractive();}
 void logStallDiagnostics(String cause){
  String bucket="unknown";if(Build.VERSION.SDK_INT>=28){try{bucket=String.valueOf(getSystemService(UsageStatsManager.class).getAppStandbyBucket());}catch(Exception ignored){}}
  record("STALL cause="+cause+"; standbyBucket="+bucket+"; "+clockDiagnostics());
  if(Build.VERSION.SDK_INT>=30){try{for(ApplicationExitInfo i:getSystemService(ActivityManager.class).getHistoricalProcessExitReasons(getPackageName(),0,5))record("STALL historicalExit reason="+i.getReason()+" pid="+i.getPid()+" process="+i.getProcessName()+" time="+i.getTimestamp()+" importance="+i.getImportance()+" detail="+i.getDescription());}catch(Exception e){record("STALL exitInfo unavailable="+e.getClass().getSimpleName());}}
 }
 String summary(){StringBuilder out=new StringBuilder("MPARIVAHAN SLOT BOOKING -J&K\n");for(int i=0;i<PROFILE_COUNT;i++){BrowserEngine e=profiles[i];out.append("Applicant ").append(i+1).append(": ");if(e==null)out.append("Not opened");else out.append(e.run.armed?"Monitoring":"Stopped").append(" · checks ").append(e.lastState.optInt("sessionAttempts")).append(" · ").append(e.lastState.optString("bookingStage","Ready")).append(e.run.diagnostics());out.append('\n');}return out.toString();}
 public boolean isArmedForTest(){return engine.run.armed;}public int attemptsForTest(){return engine.lastState.optInt("sessionAttempts");}
 void arm(){engine.run.arm();}org.mozilla.geckoview.GeckoResult<Object> waitFor(long ms){return engine.run.waitFor(ms);}
 String diagnosticReport(){StringBuilder out=new StringBuilder("PID: "+android.os.Process.myPid()+" · service instance: "+processId+" · service promoted: "+promoted+" · wake lock: "+wake.isHeld()+" · wifi lock: "+(wifiLock!=null&&wifiLock.isHeld())+"\n");out.append(clockDiagnostics()).append("\n");out.append(getSharedPreferences("diagnostics",0).getString("events",""));for(BrowserEngine e:profiles)if(e!=null)out.append("\nApplicant ").append(e.profile).append(e.run.diagnostics()).append("\n").append(String.join("\n",e.logs));return out.toString();}
 void record(String message){android.content.SharedPreferences p=getSharedPreferences("diagnostics",0);String old=p.getString("events","");String next=old+"\n"+new java.text.SimpleDateFormat("MM-dd HH:mm:ss",java.util.Locale.US).format(new java.util.Date())+" "+message;if(next.length()>12000)next=next.substring(next.length()-12000);p.edit().putString("events",next).apply();}
 @Override public void onTaskRemoved(Intent rootIntent){record("Task removed; active applicants="+activeCount()+"; monitoring retained");super.onTaskRemoved(rootIntent);}
 @Override public void onDestroy(){record("Service destroyed; active applicants="+activeCount());destroying=true;clock.removeCallbacksAndMessages(null);clockThread.quitSafely();connectivity.unregisterNetworkCallback(networks);for(BrowserEngine e:profiles)if(e!=null){e.run.disarm();e.shutdown();}if(wake.isHeld())wake.release();if(wifiLock!=null&&wifiLock.isHeld())wifiLock.release();super.onDestroy();}
}



