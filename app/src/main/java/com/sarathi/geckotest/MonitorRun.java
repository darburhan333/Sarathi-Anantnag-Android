package com.sarathi.geckotest;
import android.os.*;import org.json.JSONObject;import org.mozilla.geckoview.GeckoResult;import java.util.*;
/** Main-thread run ownership. The service clock schedules deadlines off the UI thread. */
final class MonitorRun {
 final BrowserEngine engine;final Handler handler;boolean armed,sawRunning,recovering;
 private long armedAt,lastBeat,lastProgress,lastTick,longestGap,longestScreenOffGap,lastHeartbeatValue,lastProgressValue,recoveryAt;
 private long recoveryRetryAt,verifiedAt;
 private int recoveryAttempt;
 private static final long[] RETRY_DELAYS={5000,15000,30000};
 private int generation;private String recoveryId="",lastReason="Ready";
 private final Map<GeckoResult<Object>,Runnable> waits=new IdentityHashMap<>();
 private final Set<GeckoResult<Object>> authorizations=Collections.newSetFromMap(new IdentityHashMap<>());
 private final Set<GeckoResult<Object>> delayed=Collections.newSetFromMap(new IdentityHashMap<>());
 MonitorRun(BrowserEngine e){engine=e;handler=e.service.handler;}
 boolean authorize(){
  if(armed&&SystemClock.elapsedRealtime()-lastTick>30000)recover("Native action scheduler delayed");
  return armed&&!recovering;
 }
 GeckoResult<Object> authorization(){
  GeckoResult<Object> result=new GeckoResult<>();
  if(authorize())result.complete("{\"ok\":true}");
  else if(armed&&recovering)authorizations.add(result);
  else result.complete("{\"ok\":false}");
  return result;
 }
 void arm(){
  if(!engine.service.visible||engine.service.activity==null)throw new IllegalStateException("Open app before START");
  if(armed)return;engine.service.beginForeground();
  armed=true;sawRunning=false;recovering=false;generation++;lastReason="Running";
  armedAt=lastBeat=lastProgress=lastTick=SystemClock.elapsedRealtime();longestGap=0;longestScreenOffGap=0;recoveryAttempt=0;verifiedAt=0;lastHeartbeatValue=lastProgressValue=0;
  engine.alerts.clearNotification();engine.alerts.newRun();engine.alerts.testOnce();engine.service.record("Applicant "+engine.profile+" START run="+generation);
  engine.service.markRunning(engine.profile,true);engine.activate();engine.service.refreshForeground(true);
 }
 void disarm(){disarm(true);}
 private void disarm(boolean notifyBridge){
  boolean wasArmed=armed;if(wasArmed)engine.service.record("Applicant "+engine.profile+" DISARM run="+generation+" reason="+lastReason+diagnostics()+"; "+engine.service.clockDiagnostics());armed=false;sawRunning=false;recovering=false;generation++;
  if(wasArmed&&notifyBridge)engine.command("DISARMED");
  Map<GeckoResult<Object>,Runnable> pending=new IdentityHashMap<>(waits);waits.clear();delayed.clear();
  ArrayList<GeckoResult<Object>> actions=new ArrayList<>(authorizations);authorizations.clear();for(GeckoResult<Object> r:actions)r.complete("{\"ok\":false}");
  for(Map.Entry<GeckoResult<Object>,Runnable> entry:pending.entrySet()){
   engine.service.clock.removeCallbacks(entry.getValue());handler.removeCallbacks(entry.getValue());entry.getKey().complete("{\"ok\":false,\"cancelled\":true}");
  }
  engine.service.markRunning(engine.profile,false);engine.service.refreshForeground(true);engine.activate();engine.render();
 }
 void stopByUser(){engine.service.cancelStartBatch();lastReason="Stopped by user";disarm();engine.command("STOP");engine.command("SILENCE");engine.alerts.stop();engine.alerts.clearNotification();}
 void stopForBackground(String message){
  if(!armed)return;lastReason=message;engine.log(message);
  disarm(false);engine.command("SAFE_STOP",message);
 }
 void fail(String message){
  if(!armed)return; // Idle and duplicate browser callbacks must not alarm.
  lastReason=message;engine.log(message);engine.service.record("Applicant "+engine.profile+" stopped: "+message);
  // Revoke first. FAILURE updates the dashboard; only native code delivers this failure alarm.
  disarm(false);engine.command("FAILURE",message);
  engine.alerts.critical("MONITOR_FAILED",message);
  engine.service.refreshForeground(true);
 }
 void onState(JSONObject state){
  long now=SystemClock.elapsedRealtime();long beat=state.optLong("heartbeatAt"),progress=state.optLong("monitorProgressAt");
  if(beat>lastHeartbeatValue){lastHeartbeatValue=beat;lastBeat=now;}
  if(progress>lastProgressValue){lastProgressValue=progress;lastProgress=now;}
  boolean active=state.optBoolean("running")||state.optBoolean("successWatch");
  String stage=state.optString("bookingStage","");
  // The extension may temporarily publish running=false while it waits between
  // COV-page retries. That is not a STOP command and must not revoke the native
  // foreground run. Explicit DISARM/STOP messages own user-requested shutdown.
  // Only terminal workflow states end the native run from a STATE update.
  boolean terminal="APPOINTMENT CONFIRMED".equals(stage)||"MANUAL REVIEW REQUIRED".equals(stage);
  if(armed){
   if(active)sawRunning=true;
   else if(sawRunning&&terminal){
    lastReason="Extension reached terminal state: "+stage+" / "+state.optString("lastStatus");
    disarm(false);
    return;
   }
   engine.service.refreshForeground(false);
  }
 }
 GeckoResult<Object> waitFor(long ms){
  GeckoResult<Object> result=new GeckoResult<>();
  if(!armed||ms<0||ms>8000){result.complete("{\"ok\":false,\"cancelled\":true}");return result;}
  final int epoch=generation;final long started=SystemClock.elapsedRealtime();
  Runnable completion=()->handler.post(()->{
   if(!waits.containsKey(result))return;
   if(!armed||epoch!=generation){finishWait(result,false);return;}
   long gap=SystemClock.elapsedRealtime()-started;
   if(gap>=30000||recovering){delayed.add(result);recover("Native wait delayed "+gap+"ms");return;}
   finishWait(result,true);
  });
  waits.put(result,completion);engine.service.clock.postDelayed(completion,ms);return result;
 }
 private void finishWait(GeckoResult<Object> r,boolean ok){if(waits.remove(r)==null)return;delayed.remove(r);r.complete(ok?"{\"ok\":true}":"{\"ok\":false,\"cancelled\":true}");}
 void recover(String reason){
  if(!armed||recovering)return;
  recovering=true;recoveryAttempt=0;recoveryRetryAt=0;
  lastReason="Paused: "+reason;engine.service.logStallDiagnostics("Applicant "+engine.profile+" "+reason);
  engine.service.record("Applicant "+engine.profile+" recovery started: "+reason+diagnostics());
  attemptRecovery();
 }
 private void attemptRecovery(){
  if(!armed||!recovering)return;
  recoveryAttempt++;recoveryAt=SystemClock.elapsedRealtime();recoveryRetryAt=0;
  recoveryId=generation+":"+recoveryAt+":"+recoveryAttempt;
  engine.service.record("Applicant "+engine.profile+" verification attempt="+recoveryAttempt+" id="+recoveryId+diagnostics());
  engine.activate();engine.command("RECOVER_CHECK",recoveryId);engine.render();
 }
 private void retryRecovery(String reason){
  if(recoveryRetryAt!=0)return;
  lastReason="Paused: "+reason;
  engine.service.record("Applicant "+engine.profile+" verification failed attempt="+recoveryAttempt+" reason="+reason+diagnostics());
  if(recoveryAttempt>RETRY_DELAYS.length){fail("Recovery exhausted: "+reason+" — inspect Sarathi and press START");return;}
  recoveryRetryAt=SystemClock.elapsedRealtime()+RETRY_DELAYS[recoveryAttempt-1];
 }
 void recovered(String id,boolean safe){recovered(id,safe,false,"Session not yet verified");}
 void recovered(String id,boolean safe,boolean terminal,String reason){
  if(!armed||!recovering||recoveryRetryAt!=0||!recoveryId.equals(id))return;
  if(!safe){if(terminal)fail("Recovery requires manual attention: "+reason);else retryRecovery(reason);return;}
  recovering=false;lastTick=verifiedAt=SystemClock.elapsedRealtime();lastReason="Session verified after delay";
  // Verification is not page progress. Preserve the real heartbeat/progress ages.
  engine.service.record("Applicant "+engine.profile+" recovery verified; no navigation or booking replay");
  for(GeckoResult<Object> r:new ArrayList<>(delayed))finishWait(r,true);
  ArrayList<GeckoResult<Object>> actions=new ArrayList<>(authorizations);authorizations.clear();for(GeckoResult<Object> r:actions)r.complete("{\"ok\":true}");
 }
 void tick(){
  if(!armed)return;long now=SystemClock.elapsedRealtime(),previousTick=lastTick,gap=now-previousTick;lastTick=now;longestGap=Math.max(longestGap,gap);
  // Track the largest observed monitoring-tick interval specifically while the display is off.
  android.os.PowerManager power=engine.service.getSystemService(android.os.PowerManager.class);
  // Count the full tick interval if the display was off at the current tick or a screen transition occurred between ticks.\n  boolean screenTransitionDuringGap=engine.service.screenOffAt>previousTick||engine.service.screenOnAt>previousTick;\n  if(power!=null&&(!power.isInteractive()||screenTransitionDuringGap))longestScreenOffGap=Math.max(longestScreenOffGap,gap);
  if(recovering){if(recoveryRetryAt!=0){if(now>=recoveryRetryAt)attemptRecovery();}else if(now-recoveryAt>15000)retryRecovery("Verification reply timed out");}
  else if(engine.bridgeLostAt>0&&now-engine.bridgeLostAt>15000)recover("Monitor bridge disconnected");
  else if(gap>30000){engine.service.logStallDiagnostics("main-thread tick gap "+gap+"ms; applicant="+engine.profile);recover("Main-thread tick gap "+gap+"ms");}
  else if(!sawRunning&&now-armedAt>25000)fail("Monitoring did not start — return to Sarathi and press START");
  else if(sawRunning&&now-Math.max(lastBeat,verifiedAt)>60000)recover("No page heartbeat for 60 seconds");
  else if(engine.lastState.optBoolean("running")&&!engine.lastState.optBoolean("calendarLock")&&now-Math.max(lastProgress,verifiedAt)>60000)recover("No page progress for 60 seconds");
  if(armed){engine.activate();engine.command("PING");if(engine.foreground)engine.render();}
 }
 String diagnostics(){return "\n"+(recovering?"RECOVERING":armed?(engine.foreground?"FOREGROUND":"BACKGROUND"):"IDLE")+" · run="+generation+" · recovery="+recoveryAttempt+" · longest tick gap "+longestGap/1000+"s · screen-off max gap "+longestScreenOffGap/1000+"s"+(armed?" · last progress "+(SystemClock.elapsedRealtime()-lastProgress)/1000+"s ago · heartbeat "+(SystemClock.elapsedRealtime()-lastBeat)/1000+"s ago":" · "+lastReason);}
}
