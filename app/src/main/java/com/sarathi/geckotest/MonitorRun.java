package com.sarathi.geckotest;
import android.os.*;import org.json.JSONObject;import org.mozilla.geckoview.GeckoResult;import java.util.*;
/** Main-thread run ownership. The service clock schedules deadlines off the UI thread. */
final class MonitorRun {
 final BrowserEngine engine;final Handler handler;boolean armed,sawRunning,recovering;
 private long armedAt,lastBeat,lastProgress,lastTick,longestGap,lastHeartbeatValue,lastProgressValue,recoveryAt;
 private long recoveryProgressValue=-1;
 private int generation;private String recoveryId="",lastReason="Ready";
 private final Map<GeckoResult<Object>,Runnable> waits=new IdentityHashMap<>();
 private final Set<GeckoResult<Object>> delayed=Collections.newSetFromMap(new IdentityHashMap<>());
 MonitorRun(BrowserEngine e){engine=e;handler=e.service.handler;}
 boolean authorize(){
  if(armed&&SystemClock.elapsedRealtime()-lastTick>30000)recover("Native action scheduler delayed");
  return armed&&!recovering;
 }
 void arm(){
  if(!engine.service.visible||engine.service.activity==null)throw new IllegalStateException("Open app before START");
  if(armed)return;engine.service.beginForeground();
  armed=true;sawRunning=false;recovering=false;generation++;lastReason="Running";
  armedAt=lastBeat=lastProgress=lastTick=SystemClock.elapsedRealtime();longestGap=0;recoveryProgressValue=-1;lastHeartbeatValue=lastProgressValue=0;
  engine.alerts.clearNotification();engine.service.record("Applicant "+engine.profile+" START run="+generation);
  engine.service.markRunning(engine.profile,true);engine.activate();engine.service.refreshForeground(true);
 }
 void disarm(){
  boolean wasArmed=armed;armed=false;sawRunning=false;recovering=false;generation++;
  if(wasArmed)engine.command("DISARMED");
  Map<GeckoResult<Object>,Runnable> pending=new IdentityHashMap<>(waits);waits.clear();delayed.clear();
  for(Map.Entry<GeckoResult<Object>,Runnable> entry:pending.entrySet()){
   engine.service.clock.removeCallbacks(entry.getValue());handler.removeCallbacks(entry.getValue());entry.getKey().complete("{\"ok\":false,\"cancelled\":true}");
  }
  engine.service.markRunning(engine.profile,false);engine.service.refreshForeground(true);engine.activate();engine.render();
 }
 void stopByUser(){lastReason="Stopped by user";disarm();engine.command("STOP");engine.command("SILENCE");engine.alerts.stop();engine.alerts.clearNotification();}
 void fail(String message){
  if(!armed)return; // Idle and duplicate browser callbacks must not alarm.
  lastReason=message;engine.log(message);engine.service.record("Applicant "+engine.profile+" stopped: "+message);
  // Revoke first. FAILURE updates the dashboard; only native code delivers this failure alarm.
  disarm();engine.command("FAILURE",message);
  try{engine.alerts.notify("Monitoring needs attention",message);engine.alerts.audio("TEST",true,85);}catch(Exception ignored){}
 }
 void onState(JSONObject state){
  long now=SystemClock.elapsedRealtime();long beat=state.optLong("heartbeatAt"),progress=state.optLong("monitorProgressAt");
  if(beat>lastHeartbeatValue){lastHeartbeatValue=beat;lastBeat=now;}
  if(progress>lastProgressValue){lastProgressValue=progress;lastProgress=now;}
  boolean active=state.optBoolean("running")||state.optBoolean("successWatch");
  if(armed){if(active)sawRunning=true;else if(sawRunning){lastReason="Monitoring completed or stopped";disarm();return;}engine.service.refreshForeground(false);}
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
  if(recoveryProgressValue==lastProgressValue){fail("No page progress after recovery — inspect Sarathi and press START");return;}
  recoveryProgressValue=lastProgressValue;recovering=true;recoveryAt=SystemClock.elapsedRealtime();recoveryId=generation+":"+recoveryAt;
  lastReason="Checking session after delay";engine.service.record("Applicant "+engine.profile+" recovery: "+reason+"; "+engine.service.clockDiagnostics());
  engine.activate();engine.command("RECOVER_CHECK",recoveryId);engine.render();
 }
 void recovered(String id,boolean safe){
  if(!armed||!recovering||!recoveryId.equals(id))return;
  if(!safe){fail("Session could not be verified after delay — inspect Sarathi and press START");return;}
  recovering=false;lastTick=lastBeat=lastProgress=SystemClock.elapsedRealtime();lastReason="Session verified after delay";
  engine.service.record("Applicant "+engine.profile+" recovery verified; no navigation or booking replay");
  // Only already-pending waits continue. Page gates re-check ownership/document before actions.
  for(GeckoResult<Object> r:new ArrayList<>(delayed))finishWait(r,true);
 }
 void tick(){
  if(!armed)return;long now=SystemClock.elapsedRealtime(),gap=now-lastTick;lastTick=now;longestGap=Math.max(longestGap,gap);
  if(recovering){if(now-recoveryAt>15000)fail("Session recovery timed out — inspect Sarathi and press START");}
  else if(engine.bridgeLostAt>0&&now-engine.bridgeLostAt>15000)fail("Monitor bridge did not reconnect — open app");
  else if(gap>30000)recover("Main-thread tick gap "+gap+"ms");
  else if(!sawRunning&&now-armedAt>25000)fail("Monitoring did not start — return to Sarathi and press START");
  else if(sawRunning&&now-lastBeat>60000)recover("No page heartbeat for 60 seconds");
  else if(engine.lastState.optBoolean("running")&&!engine.lastState.optBoolean("calendarLock")&&now-lastProgress>60000)recover("No page progress for 60 seconds");
  if(armed){engine.activate();engine.command("PING");if(engine.foreground)engine.render();}
 }
 String diagnostics(){return "\n"+(recovering?"RECOVERING":armed?(engine.foreground?"FOREGROUND":"BACKGROUND"):"IDLE")+" · longest tick gap "+longestGap/1000+"s"+(armed?" · last progress "+(SystemClock.elapsedRealtime()-lastProgress)/1000+"s ago":" · "+lastReason);}
}
