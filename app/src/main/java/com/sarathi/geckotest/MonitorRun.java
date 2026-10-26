package com.sarathi.geckotest;
import android.app.*;import android.os.*;import org.json.JSONObject;import org.mozilla.geckoview.GeckoResult;import java.util.*;
/** Per-applicant authorization, waits and health. UI visibility never revokes a run. */
final class MonitorRun {
 final BrowserEngine engine;final Handler handler;boolean armed,sawRunning;
 private long armedAt,lastBeat,lastProgress,lastTick,longestGap,lastHeartbeatValue,lastProgressValue;
 private int generation;
 private final Map<GeckoResult<Object>,Runnable> waits=new HashMap<>();
 MonitorRun(BrowserEngine e){engine=e;handler=e.service.handler;}
 boolean authorize(){
  if(armed&&SystemClock.elapsedRealtime()-lastTick>30000)fail("Monitoring timing interrupted — reopen and press START");
  return armed;
 }
 void arm(){
  if(!engine.service.visible||engine.service.activity==null)throw new IllegalStateException("Open app before START");
  if(armed)return;
  engine.service.beginForeground();
  armed=true;sawRunning=false;generation++;armedAt=lastBeat=lastProgress=lastTick=SystemClock.elapsedRealtime();longestGap=0;lastHeartbeatValue=lastProgressValue=0;
  engine.service.record("Applicant "+engine.profile+" START");engine.service.markRunning(engine.profile,true);engine.activate();engine.service.refreshForeground(true);
 }
 void disarm(){
  boolean wasArmed=armed;armed=false;sawRunning=false;generation++;
  if(wasArmed&&engine!=null)engine.command("DISARMED");
  for(Map.Entry<GeckoResult<Object>,Runnable> entry:new ArrayList<>(waits.entrySet())){
   handler.removeCallbacks(entry.getValue());entry.getKey().complete("{\"ok\":false,\"cancelled\":true}");
  }
  waits.clear();
  engine.service.markRunning(engine.profile,false);
  engine.service.refreshForeground(true);
  if(engine!=null){engine.activate();engine.render();}
 }
 void stopByUser(){if(engine!=null){engine.command("STOP");engine.command("SILENCE");engine.alerts.stop();}disarm();}
 void fail(String message){
  if(engine==null)return;
  engine.log(message);engine.service.record("Applicant "+engine.profile+" stopped: "+message);engine.command("FAILURE",message);
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
   engine.service.refreshForeground(false);
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
 void tick(){
  if(armed){
   long now=SystemClock.elapsedRealtime(),gap=now-lastTick;lastTick=now;longestGap=Math.max(longestGap,gap);
   if(engine.bridgeLostAt>0&&now-engine.bridgeLostAt>15000)fail("Monitor bridge did not reconnect within 15 seconds — open app");
   else if(gap>30000)fail("Phone suspended monitoring — check battery settings and press START");
   else if(!sawRunning&&now-armedAt>25000)fail("Monitoring did not start — return to Sarathi and press START");
   else if(sawRunning&&now-lastBeat>60000)fail("No website heartbeat for 60 seconds — open Sarathi");
   else if(engine.lastState.optBoolean("running")&&!engine.lastState.optBoolean("calendarLock")&&now-lastProgress>60000)fail("No monitoring progress for 60 seconds — open Sarathi");
   if(armed){engine.activate();engine.command("PING");if(engine.foreground)engine.render();}
  }
 }
 String diagnostics(){
  if(!armed)return "";
  return "\n"+(engine.foreground?"Foreground service":"Background service")+" · gap max "+(longestGap/1000)+"s · last page update "+((SystemClock.elapsedRealtime()-lastBeat)/1000)+"s ago";
 }
}


