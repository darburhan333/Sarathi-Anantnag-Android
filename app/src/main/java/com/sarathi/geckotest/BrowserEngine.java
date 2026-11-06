package com.sarathi.geckotest;

import android.app.Activity;
import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.view.Gravity;
import android.os.SystemClock;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import org.json.JSONObject;
import org.mozilla.geckoview.WebExtension;
import org.mozilla.geckoview.AllowOrDeny;
import android.app.AlertDialog;
import org.mozilla.geckoview.GeckoSession.PromptDelegate;
import org.mozilla.geckoview.GeckoSession.PromptDelegate.ChoicePrompt;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoView;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.WebRequestError;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

final class BrowserEngine {
    static final long STOP_PAGE_PROTECTION_MAX_MS=5L*60L*1000L;
    static final String HOME="https://sarathi.parivahan.gov.in/sarathiservice/stateSelection.do";
    private static GeckoRuntime sharedRuntime;
    final GeckoRuntime runtime;
    final MonitorService service;
    final MonitorRun run;final int profile;final String extensionId,assetPath;
    final java.util.Map<GeckoSession,Boolean> activeStates=new java.util.HashMap<>();
    final java.util.Map<GeckoSession,Integer> priorityHints=new java.util.concurrent.ConcurrentHashMap<>();
    MainActivity activity;
    GeckoSession current, monitorSession;
    final ArrayList<GeckoSession> sessions=new ArrayList<>();
    final ArrayList<String> logs=new ArrayList<>();
    String status="Opening Parivahan…", monitorStatus="Connecting monitor…", monitorUrl;
    boolean canGoBack, foreground, showingMonitor, ready, monitoring, destroyed;
    boolean protectedPage,pageLostProtected,sessionExpired,pageUnloaded,loggedInPage=true;String sessionExpiredHeader="",protectedStage="",protectedUrl="",lastPageReason="COV form missing";long protectedUntilElapsed,lastVisibleAt=SystemClock.elapsedRealtime();
    long loginAtWallClockMs,loginAtElapsedMs;boolean loginRequiredObserved;
    boolean firstPageRecorded;int installAttempts;
    long pageLoadStartedElapsed,lastPageStopElapsed;boolean lastPageLoadSucceeded;
    long bridgeLostAt;
    final long startupAt=SystemClock.elapsedRealtime();
    WebExtension.Port nativePort;
    WebExtension extension;
    final PhoneAlerts alerts;
    Runnable afterStop;
    String pendingId;
    volatile String smokeResult="",smokePhase="";
    volatile boolean monitorReady;
    boolean preparingStartControls;
    private String fillToken="";private GeckoSession fillSession;private GeckoResult<Object> fillReply;private boolean fillCommitted;
    private String committedPageUrl="";private boolean firstSarathiReadyRecorded;private int loadProgress;
    JSONObject lastState=new JSONObject();
    BrowserEngine(MonitorService owner,int number){
      service=owner;profile=number;run=new MonitorRun(this);alerts=new PhoneAlerts(owner,number);
      android.content.SharedPreferences loginPrefs=owner.getSharedPreferences("monitor",0);
      loginAtWallClockMs=loginPrefs.getLong("loginAtWallClockMs"+number,0L);
      loginAtElapsedMs=0L; // elapsedRealtime is process/boot-relative; never restore it from disk.
      extensionId=number==1?"sarathi-android@burhan.local":"sarathi-applicant"+number+"@burhan.local";assetPath=number==1?"extension/":"extension"+number+"/";
      if(sharedRuntime==null)sharedRuntime=GeckoRuntime.create(owner.getApplicationContext());
      runtime=sharedRuntime;
      GeckoSession first=newSession();first.open(runtime);showSession(first);first.loadUri(HOME);
      installExtension();
    }
    void runOnUiThread(Runnable r){service.handler.post(r);}
    void render(){if(activity!=null)activity.render();}
    void attach(MainActivity a){activity=a;ensureUsable();render();}
    void detach(MainActivity a){if(activity==a){cancelFill();activity=null;}}
    void foregroundState(){
      if(!foreground)cancelFill();
      if(foreground)lastVisibleAt=SystemClock.elapsedRealtime();
      if(nativePort!=null){JSONObject o=json("FOREGROUND");try{o.put("active",foreground);}catch(Exception ignored){}nativePort.postMessage(o);}
      render();activate();
    }
    void activate(){
      for(GeckoSession s:sessions)if(s.isOpen()){
        boolean active=((run.armed||protectedPage)&&s==current)||(foreground&&s==current)||(s==monitorSession&&(foreground&&showingMonitor||preparingStartControls&&service.visible));
        boolean changed=!java.util.Objects.equals(activeStates.get(s),active);
        // GeckoView may deactivate a detached/hidden session independently of our cache.
        // Reassert active browser sessions while monitoring, including an older selected tab.
        if(changed||((run.armed||protectedPage)&&s==current)){
          int priority=active?GeckoSession.PRIORITY_HIGH:GeckoSession.PRIORITY_DEFAULT;
          s.setActive(active);s.setPriorityHint(priority);priorityHints.put(s,priority);
        }
        if(changed)activeStates.put(s,active);
      }
    }
    void updateProtection(JSONObject state){updateProtection(state,true);}
    private void updateProtection(JSONObject state,boolean documentVerified){
      String stage=state.optString("bookingStage","");
      boolean bookingStage=stage.contains("SMS")||stage.contains("CALENDAR")||stage.contains("BOOK SLOT")||stage.equals("APPOINTMENT CONFIRMED");
      boolean protectedNow=state.optBoolean("protectPage")||state.optBoolean("calendarLock")||bookingStage;
      boolean hasProtectionInputs=state.has("protectPage")||state.has("calendarLock")||state.has("bookingStage");
      if(protectedNow){
        String observedUrl=state.optString("url","");if(observedUrl.isEmpty())observedUrl=pageUrl;
        protectedPage=true;protectedStage=stage;protectedUrl=safeLocation(observedUrl);
      }else if(hasProtectionInputs){
        // STOP disarms monitoring, but the extension may asynchronously publish a
        // stale STOPPED snapshot after native code has latched manual-page protection.
        // A queued STOPPED snapshot can arrive before native STOP starts its grace
        // timer, as well as afterwards. Neither snapshot proves the protected page
        // disappeared. Explicit navigation, page loss and the STOP deadline own release.
        // Background monitoring snapshots may lag behind the visible manual page.
        // A current document query or explicit terminal expiry owns clearing it.
        boolean retainedStopSnapshot=protectedPage&&((!documentVerified&&!"SESSION_EXPIRED".equals(stage))||"STOPPED".equals(stage));
        if(!retainedStopSnapshot){clearProtectedPage();pageLostProtected=false;}
      }
      if(state.has("loggedIn")){loggedInPage=state.optBoolean("loggedIn",true);if(!loggedInPage){loginRequiredObserved=true;clearLoginAt();}}
      if(state.has("reason"))lastPageReason=state.optString("reason");
      activate();service.refreshForeground(false);
    }
    private void clearProtectedPage(){
      protectedPage=false;protectedStage="";protectedUrl="";protectedUntilElapsed=0;
      activate();
    }
    void beginProtectionRetentionAfterStop(long nowElapsed){
      protectedUntilElapsed=protectedPage?nowElapsed+STOP_PAGE_PROTECTION_MAX_MS:0L;
    }
    boolean expireRetainedProtection(long nowElapsed){
      if(!protectedPage||protectedUntilElapsed<=0L||nowElapsed<protectedUntilElapsed)return false;
      String kind=protectedStage.contains("SMS")?"SMS":"calendar/booking";
      clearProtectedPage();pageLostProtected=false;
      service.record("Applicant "+profile+" retained "+kind+" page protection expired after 5-minute STOP maximum");
      service.refreshForeground(true);return true;
    }
    void expireRetainedProtectionForNavigation(String nextUrl){
      if(!protectedPage||protectedUntilElapsed<=0L)return;
      String next=safeLocation(nextUrl);
      if(!protectedUrl.isEmpty()&&!protectedUrl.equals(next)){
        clearProtectedPage();pageLostProtected=false;
        service.record("Applicant "+profile+" retained page protection expired after navigation away");
        service.refreshForeground(true);
      }
    }
    void markLoginAt(){
      if(loginAtWallClockMs!=0L)return;
      loginAtWallClockMs=System.currentTimeMillis();loginAtElapsedMs=SystemClock.elapsedRealtime();
      service.getSharedPreferences("monitor",0).edit().putLong("loginAtWallClockMs"+profile,loginAtWallClockMs).remove("loginAtElapsedMs"+profile).apply();
      sessionExpired=false;
      service.record("Applicant "+profile+" loginAt="+loginAtWallClockMs+" (first COV page after login)");
    }
    void clearLoginAt(){
      loginAtWallClockMs=0L;loginAtElapsedMs=0L;
      service.getSharedPreferences("monitor",0).edit().remove("loginAtWallClockMs"+profile).remove("loginAtElapsedMs"+profile).apply();
    }
    void recordSessionExpiryEvidence(){
      long now=SystemClock.elapsedRealtime();
      service.record(sessionExpiryEvidence(profile,loginAtWallClockMs,loginAtElapsedMs,System.currentTimeMillis(),now,pageUrl)
        +"; serviceInstance="+service.processId+"; activityVisible="+service.visible+"; sincePauseMs="+service.sincePauseMs(now)
        +"; pageLoading="+pageLoading+"; pendingLoadMs="+(pageLoading?Math.max(0L,now-pageLoadStartedElapsed):0L)
        +"; lastPageStopAgeMs="+(lastPageStopElapsed>0?now-lastPageStopElapsed:-1)
        +"; lastLoadSuccess="+lastPageLoadSucceeded+"; network="+service.networkEvidence()+"; "+run.rawState());
    }
    boolean hasClosedPageNotice(){return service.getSharedPreferences("monitor",0).getBoolean("closedPageNotice"+profile,false);}
    void dismissClosedPageNotice(){service.getSharedPreferences("monitor",0).edit().remove("closedPageNotice"+profile).apply();render();}
    private void rememberClosedPage(){service.getSharedPreferences("monitor",0).edit().putBoolean("closedPageNotice"+profile,true).apply();}
    void setLoginAtForTest(long wall,long elapsed){loginAtWallClockMs=wall;loginAtElapsedMs=elapsed;}
    void setPageUrlForTest(String url){pageUrl=url;}
    static String sessionExpiryEvidence(int profile,long loginWall,long loginElapsed,long nowWall,long nowElapsed,String url){
      String login=loginWall>0L?String.valueOf(loginWall):"unavailable";
      String elapsed=loginElapsed>0L&&nowElapsed>=loginElapsed?String.valueOf(nowElapsed-loginElapsed):
        loginWall>0L&&nowWall>=loginWall?String.valueOf(nowWall-loginWall):"unavailable";
      return "SESSION_EVIDENCE applicant="+profile+" loginAtWallClockMs="+login+" expiredAtWallClockMs="+nowWall+" elapsedSinceLoginMs="+elapsed+
        " endpoint="+safeOriginPathLabel(url)+" httpStatus=unavailable redirect=unavailable cookieExpiryTimestamps=unavailable";
    }
    static String safeOriginPathLabel(String raw){
      if(raw==null||raw.isEmpty())return "unavailable";
      try{
        Uri uri=Uri.parse(raw);if(!"https".equalsIgnoreCase(uri.getScheme())||!"sarathi.parivahan.gov.in".equalsIgnoreCase(uri.getHost()))return "unavailable";
        String path=uri.getPath()==null?"":uri.getPath().toLowerCase(Locale.US);
        String label=path.contains("dlsltprev.do")?"SMS_VERIFICATION":
          path.contains("fetchdltprevmnth.do")||path.contains("fetchdltnextmnth.do")?"CALENDAR":
          path.contains("fetchdlslotdetinfo.do")?"QUOTA":path.contains("dispdlapcntdetform")?"COV_FORM":"SLOT_FLOW";
        return "https://sarathi.parivahan.gov.in#"+label;
      }catch(Exception ignored){return "unavailable";}
    }
    static long idleUnloadTimeoutMsForTest(int minutes){return Math.max(5,Math.min(180,minutes))*60000L;}
    private long idleUnloadTimeoutMs(){int minutes=service.getSharedPreferences("monitor",0).getInt("idleUnloadMinutes",60);return idleUnloadTimeoutMsForTest(minutes);}
    void unloadIdle(long now,boolean pressure){
      if(protectedPage||pageLostProtected||run.armed||foreground)return;
      boolean loggedIn=loggedInPage||lastState.optBoolean("covReady")||lastState.optBoolean("successWatch")||lastState.optBoolean("calendarLock")||lastState.optString("bookingStage").contains("SMS")||lastState.optString("bookingStage").contains("BOOK SLOT");
      if(loggedIn&&!pressure)return;
      if(!pressure&&now-lastVisibleAt<idleUnloadTimeoutMs())return;
      boolean unloaded=false;
      for(GeckoSession s:new ArrayList<>(sessions))if(s!=monitorSession){sessions.remove(s);activeStates.remove(s);priorityHints.remove(s);closeSession(s);if(s==current)current=null;unloaded=true;}
      if(unloaded){setRtoLabel("");pageUnloaded=true;rememberClosedPage();String message="Applicant "+profile+" idle page closed; loggedIn="+loggedIn+"; reopen website and check login; page unloaded";service.record(message+"; memoryPressure="+pressure+"; idleMs="+(now-lastVisibleAt));log(message);}
    }
    void shutdown(){
      destroyed=true;
      if(protectedUntilElapsed>0L)clearProtectedPage();
      if(extension!=null){extension.setMessageDelegate(null,"sarathi");extension.setTabDelegate(null);}
      if(nativePort!=null)nativePort.disconnect();
      for(GeckoSession s:new ArrayList<>(sessions))closeSession(s);
      activeStates.clear();priorityHints.clear();
      alerts.stop();activity=null;
    }
    boolean promptAvailable(){
      if(activity!=null&&foreground&&!activity.isFinishing())return true;
      run.fail("Website needs your input — unlock and reopen the app");return false;
    }
    private JSONObject json(String type){JSONObject o=new JSONObject();try{o.put("type",type);}catch(Exception ignored){}return o;}
    void command(String type){command(type,null);}
    void command(String type,String reason){if(nativePort!=null){JSONObject o=json(type);if(reason!=null)try{o.put("reason",reason);}catch(Exception ignored){}nativePort.postMessage(o);}}
    void stopFromUi(){run.stopByUser();}
    void navigate(String actionName,Runnable action){
      service.confirmProtected(actionName,java.util.Collections.singletonList(this),()->{
        // Only an explicitly confirmed navigation may discard protected-page ownership.
        protectedPage=false;pageLostProtected=false;protectedStage="";protectedUrl="";
        run.stopByUser(false); // Revoke native action permissions before waiting for JavaScript.
        JSONObject protectionState = new JSONObject();
        try {
          protectionState.put("running", false);
          protectionState.put("protectPage", false);
          protectionState.put("calendarLock", false);
          protectionState.put("bookingStage", "STOPPED");
        } catch (org.json.JSONException e) {
          throw new IllegalStateException(e);
        }
        updateProtection(protectionState);
        final String id=String.valueOf(System.nanoTime());pendingId=id;afterStop=action;
        if(nativePort!=null){JSONObject o=json("STOP");try{o.put("id",id);}catch(Exception ignored){}nativePort.postMessage(o);}
        service.handler.postDelayed(()->finishNavigation(id),nativePort==null?0:1200);
      });
    }
    void finishNavigation(String id){if(!destroyed&&id.equals(pendingId)&&afterStop!=null){Runnable r=afterStop;afterStop=null;pendingId=null;r.run();}}
    void home(){navigate("Go Home",()->{setRtoLabel("");GeckoSession previous=current;ensureUsable();showingMonitor=false;if(current!=null&&current==previous)current.loadUri(HOME);render();activate();});}
    void ensureUsable(){
      if(destroyed||pageLostProtected)return;
      if(current==null||!current.isOpen()){
        if(current!=null)pageLost(current,"Browser session unavailable");
        if(current==null&&!pageLostProtected){GeckoSession fresh=newSession();fresh.open(runtime);showSession(fresh);fresh.loadUri(HOME);}
      }
      if(pageUnloaded&&current!=null){pageUnloaded=false;log("Applicant "+profile+" website reopened — check login before starting");}
      if(showingMonitor)prepareMonitor();
    }
    // Test-only instrumentation hook; production code must not call this method.
    void resetProtectionForTest(){
      if(Looper.myLooper()!=Looper.getMainLooper())throw new IllegalStateException("Test reset must run on the main thread");
      protectedPage=false;pageLostProtected=false;sessionExpired=false;sessionExpiredHeader="";loggedInPage=true;protectedStage="";protectedUrl="";protectedUntilElapsed=0L;
      lastPageReason="Test fixture reset";lastState=new JSONObject();monitoring=false;
      JSONObject protectionState = new JSONObject();
        try {
          protectionState.put("running", false);
          protectionState.put("protectPage", false);
          protectionState.put("calendarLock", false);
          protectionState.put("bookingStage", "STOPPED");
        } catch (org.json.JSONException e) {
          throw new IllegalStateException(e);
        }
        updateProtection(protectionState);
      pageQuery="";pageUrl="";pageReady=false;pageLoading=false;showingMonitor=false;canGoBack=false;
      pendingId=null;afterStop=null;activeStates.clear();priorityHints.clear();
      if(current!=null){GeckoSession previous=current;current=null;sessions.remove(previous);closeSession(previous);}
      GeckoSession fresh=newSession();fresh.open(runtime);showSession(fresh);fresh.loadUri(HOME);
      activate();service.refreshForeground(true);
    }
    // Test-only instrumentation hook; production code must not call this method.
    boolean isPriorityHighForTest(GeckoSession session){return java.util.Objects.equals(priorityHints.get(session),GeckoSession.PRIORITY_HIGH);}

    static void releaseView(GeckoView view){
      GeckoSession session=view.getSession();
      if(session!=null)session.getAccessibility().setView(null);
      view.releaseSession();
      // Clear Android's cached virtual focus while this host has no native provider.
      // The GeckoSession is already detached and retains its existing activity policy.
      int visibility=view.getVisibility();
      if(visibility!=View.INVISIBLE){view.setVisibility(View.INVISIBLE);view.setVisibility(visibility);}
    }
    private void closeSession(GeckoSession session){
      View owner=session.getAccessibility().getView();
      session.getAccessibility().setView(null);
      if(owner instanceof GeckoView&&((GeckoView)owner).getSession()==session)releaseView((GeckoView)owner);
      if(session.isOpen())session.close();
    }

    void pageLost(GeckoSession session,String reason){
      if(destroyed||!sessions.remove(session))return;
      boolean website=session==current,monitor=session==monitorSession;
      activeStates.remove(session);priorityHints.remove(session);
      if(website){setRtoLabel("");current=null;if(protectedPage&&protectedUntilElapsed>0L){clearProtectedPage();pageLostProtected=false;service.record("Applicant "+profile+" retained page protection cleared because the page session closed");}else if(protectedPage){pageLostProtected=true;lastPageReason="A"+profile+": page lost during "+protectedStage+", restart the booking flow";status=lastPageReason;service.record(lastPageReason+" url="+protectedUrl);alerts.critical("PROTECTED_PAGE_LOST",lastPageReason);run.stopForBackground(lastPageReason);}else run.fail(reason+" — inspect Sarathi and press START");}
      if(monitor){monitorSession=null;monitorReady=false;}
      closeSession(session);
      service.record("Applicant "+profile+" "+(website?"website":monitor?"dashboard":"unused page")+" closed; active="+run.armed);
      // Recreate only a visible page. Never replay form submissions or resume a lost run.
      if(foreground)service.handler.post(()->{ensureUsable();render();activate();});
    }
    private String monitorPanel="monitor";
    void showMonitorPanel(String panel){
      monitorPanel="alerts-settings".equals(panel)?"alerts-settings":"monitor";
      showingMonitor=true;
      if(monitorSession==null)prepareMonitor();
      else if(ready&&monitorUrl!=null)monitorSession.loadUri(monitorUrl+"#"+monitorPanel);
      render();activate();
    }
    void prepareStartControls(){preparingStartControls=true;prepareMonitor();activate();}
    void finishStartControls(){preparingStartControls=false;activate();}
    void prepareMonitor(){
      if(!ready||monitorUrl==null||monitorSession!=null||destroyed||(!showingMonitor&&!preparingStartControls))return;
      monitorSession=newSession();monitorSession.open(runtime);
      // Load the requested panel once. Rendering must never enqueue a competing URI.
      monitorSession.loadUri(monitorUrl+"#"+monitorPanel);render();activate();
    }
    void toggleMonitor(){
      if(!ready||monitorUrl==null){log("Monitor is still starting");return;}
      if(!showingMonitor)showMonitorPanel("monitor");
      else {showingMonitor=false;render();activate();}
      runtime.getWebExtensionController().setTabActive(showingMonitor?monitorSession:current,true);
    }
    private String pageQuery="",pageUrl="";private boolean pageReady,pageLoading;
    static String pageReadyReason(String supplied,boolean calendar,boolean expired,boolean covReady){
      String value=supplied==null?"":supplied.toLowerCase(Locale.US);
      if(expired||value.contains("session expired"))return "Session expired";
      if(calendar||value.contains("calendar detected"))return "Calendar detected";
      return covReady?"Ready":"COV form missing";
    }
    private void receive(Object message){
      if(!(message instanceof JSONObject))return;
      JSONObject o=(JSONObject)message;
      switch(o.optString("type")){
        case "READY":
          monitorUrl=o.optString("monitorUrl");ready=true;setRtoLabel(rtoLabel);foregroundState();
          log("Monitor connected in "+(SystemClock.elapsedRealtime()-startupAt)+" ms");
          log("Monitor ready — open Sarathi, then MONITOR to start");prepareMonitor();break;
        case "CRITICAL":{
          String kind=o.optString("kind"),criticalMessage=o.optString("message"),eventKey=o.optString("eventKey");
          if("SESSION_EXPIRED".equals(kind))run.sessionExpired(eventKey);
          else alerts.critical(kind,criticalMessage,eventKey);
          break;
        }
        case "ALERT_DELIVERY":service.record("ALERT A"+profile+" kind="+o.optString("kind")+" Telegram="+o.optString("telegram"));break;
        case "STATE":
          monitoring=o.optBoolean("running")||o.optBoolean("successWatch");
          lastState=o;updateProtection(o,false);
          monitorStatus=(monitoring?(foreground?"RUNNING · FOREGROUND":"RUNNING · BACKGROUND"):"STOPPED")+" · Session clicks "+o.optInt("sessionAttempts")+"\n"+o.optString("lastStatus");
          run.onState(o);render();
          break;
        case "ACK":
          finishNavigation(o.optString("id"));break;
        case "FILL_RESULT":finishFill(o.optString("id"),o.optBoolean("ok"));break;
        case "START_RESULT":finishStartControls();if(o.optString("batch").equals(service.startBatch)){log("Start All: "+o.optString("reason"));service.record("Applicant "+profile+" Start All: "+o.optString("reason"));}break;
        case "PAGE_READY":if(!pageLoading&&o.optString("id").equals(pageQuery)&&o.optString("url").equals(pageUrl)){pageReady=true;if(o.optBoolean("covReady"))setRtoLabel(o.optString("rtoLabel"));updateProtection(o);if(o.optBoolean("covReady")&&loggedInPage&&(loginRequiredObserved||(!sessionExpired&&loginAtWallClockMs==0L))){markLoginAt();loginRequiredObserved=false;}String reason=pageReadyReason(o.optString("reason"),o.optBoolean("calendarLock")||o.optBoolean("protectPage")&&o.optString("reason").toLowerCase(Locale.US).contains("calendar"),o.optString("reason").toLowerCase(Locale.US).contains("session expired"),o.optBoolean("covReady"));lastPageReason=reason;log("PAGE_READY: "+reason);}break;
        case "RECOVERED":run.recovered(o.optString("id"),o.optBoolean("safe"),o.optBoolean("terminal"),o.optString("reason","No verification details"));break;
        case "ERROR":log(o.optString("message"));break;
      }
    }
    private void queryPageReadiness(GeckoSession website,String queryId,String expectedUrl,int attempt){
      if(BuildConfig.DEBUG&&expectedUrl.startsWith("http://127.0.0.1"))android.util.Log.i("READINESS_PROBE","A"+profile+" attempt="+attempt+" current="+(website==current)+" query="+queryId.equals(pageQuery)+" url="+expectedUrl.equals(pageUrl)+" loading="+pageLoading+" ready="+pageReady);
      if(destroyed||website!=current||!website.isOpen()||!queryId.equals(pageQuery)||!expectedUrl.equals(pageUrl)||pageLoading||pageReady)return;
      // The document_idle script first proves native session ownership. Its
      // listener can become ready just after onPageStop; inspect the same page
      // again briefly instead of losing the one-shot reply. No navigation/action.
      command("PAGE_QUERY",queryId+"|"+expectedUrl);
      if(attempt<4)service.handler.postDelayed(()->queryPageReadiness(website,queryId,expectedUrl,attempt+1),500);
    }
    private void installExtension(){
      installAttempts++;
      runtime.getWebExtensionController().ensureBuiltIn("resource://android/assets/"+assetPath,extensionId).accept(ext->runOnUiThread(()->{
        if(destroyed)return;
        extension=ext;for(GeckoSession session:sessions)bindProfileGuard(session);
        ext.setTabDelegate(new WebExtension.TabDelegate(){
          @Override public GeckoResult<GeckoSession> onNewTab(WebExtension source,WebExtension.CreateTabDetails details){
            return GeckoResult.fromValue(newSession());
          }
        });
        ext.setMessageDelegate(new WebExtension.MessageDelegate(){
          @Override public void onConnect(WebExtension.Port port){
            nativePort=port;bridgeLostAt=0;service.record("Applicant "+profile+" bridge connected");
            port.setDelegate(new WebExtension.PortDelegate(){
              @Override public void onPortMessage(Object message,WebExtension.Port p){if(nativePort==p&&!destroyed)receive(message);}
              @Override public void onDisconnect(WebExtension.Port p){
                if(nativePort==p){nativePort=null;ready=false;if(!destroyed){bridgeLostAt=SystemClock.elapsedRealtime();service.record("Applicant "+profile+" bridge disconnected; waiting up to 15 seconds for reconnect");}}
              }
            });
          }
          @Override public GeckoResult<Object> onMessage(String app,Object message,WebExtension.MessageSender sender){
            JSONObject result=new JSONObject();
            try{
              if(sender.session!=null)throw new IllegalArgumentException("Only background messages allowed");
              JSONObject m=(JSONObject)message;
              switch(m.optString("type")){
                case "ARM":if(!m.optString("batch").isEmpty()&&!m.optString("batch").equals(service.startBatch))throw new IllegalStateException("Start All cancelled");run.arm();break;
                case "SETTINGS_GET":result.put("values",SettingsBackup.options(service,profile));break;
                case "SETTINGS_SET":if(!service.settingsBusy){JSONObject values=m.getJSONObject("values"),saved=SettingsBackup.options(service,profile);for(String key:new String[]{"telegramEnabled","telegramStatusEnabled"})if(values.has(key)&&values.optBoolean(key)!=saved.optBoolean(key,"telegramEnabled".equals(key)))service.telegramLock.requireUnlocked(profile);SettingsBackup.saveOptions(service,profile,values);}break;
                case "TELEGRAM_LOCK_STATUS":result.put("lock",service.telegramLock.status(profile));break;
                case "TELEGRAM_UNLOCK":return service.telegramLock.request(service,profile,false);
                case "TELEGRAM_PASSWORD":return service.telegramLock.request(service,profile,true);
                case "TELEGRAM_LOCK":service.telegramLock.lockAll();break;
                case "TELEGRAM_EDIT":service.telegramLock.requireUnlocked(profile);break;
                case "TELEGRAM_OPTIONS_SET":service.telegramLock.requireUnlocked(profile);JSONObject options=new JSONObject(),requested=m.getJSONObject("values");for(String key:new String[]{"telegramEnabled","telegramStatusEnabled"})if(requested.has(key))options.put(key,requested.getBoolean(key));SettingsBackup.saveOptions(service,profile,options);break;
                case "SECURE_GET":result.put("values",SecureSettings.read(service,profile));break;
                case "SECURE_SET":service.telegramLock.requireUnlocked(profile);SecureSettings.write(service,profile,m.getJSONObject("values"));service.releaseStatus(profile);break;
                case "SECURE_REMOVE":service.telegramLock.requireUnlocked(profile);SecureSettings.remove(service,profile);service.releaseStatus(profile);break;
                case "SECURE_IMPORT":service.telegramLock.requireUnlocked(profile);SecureSettings.importLegacy(service,profile);service.releaseStatus(profile);break;
                case "STATUS_CLAIM":if(!service.claimStatus(profile))throw new IllegalStateException("This bot already has a status receiver in another applicant. Disable that receiver first.");break;
                case "STATUS_RELEASE":service.releaseStatus(profile);break;
                case "SUMMARY":result.put("text","Applicant "+profile+" · "+(run.armed?"Monitoring":"Stopped")+" · checks "+lastState.optInt("sessionAttempts")+" · "+lastState.optString("bookingStage","Ready")+run.diagnostics());break;
                case "RESTORE":result.put("armed",run.armed);break;
                case "AUTHORIZE":return run.authorization();
                case "SMOKE_PHASE":smokePhase=m.optString("phase");break;
                case "UI_STOP":stopFromUi();break;
                case "CRITICAL":if("SESSION_EXPIRED".equals(m.optString("kind")))run.sessionExpired(m.optString("eventKey"));else alerts.critical(m.optString("kind"),m.optString("message"),m.optString("eventKey"));break;
                case "DISARM":run.disarm();break;
                case "ACK_ALERT":alerts.silence();service.refreshForeground(true);break;
                case "ALERT_DELIVERY":service.record("ALERT A"+profile+" kind="+m.optString("kind")+" Telegram="+m.optString("telegram"));break;
                case "WAIT":return run.waitFor(m.optLong("ms"));
                case "UI_READY":monitorReady=true;break;
                case "SMOKE_RESULT":smokeResult=m.optString("result");break;
                case "AUDIO":
                  JSONObject settings=m.optJSONObject("settings");
                  alerts.audio(m.optString("action"),settings==null||settings.optBoolean("soundEnabled",true),settings==null?85:settings.optInt("volume",85));break;
                case "NOTIFY":alerts.notify(m.optString("title"),m.optString("message"));break;
                case "SHARE":
                  Intent share=new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,m.optString("text"));
                  if(activity==null||!foreground)throw new IllegalStateException("Open the app to share");
                  activity.startActivity(Intent.createChooser(share,"Share monitoring history"));break;
                default:throw new IllegalArgumentException("Unknown native action");
              }
              result.put("ok",true);
            }catch(Exception e){try{result.put("ok",false);result.put("error",e.getMessage());}catch(Exception ignored){}}
            return GeckoResult.fromValue((Object)result.toString());
          }
        },"sarathi");
      }),e->runOnUiThread(()->{ready=false;log("Monitor installation failed: "+e.getClass().getSimpleName());if(!destroyed&&installAttempts<3)service.handler.postDelayed(()->{if(!destroyed)installExtension();},2000L*installAttempts);}));
    }

    boolean canFillApplicant(){return !destroyed&&ready&&nativePort!=null&&foreground&&service.visible&&service.engine==this&&!showingMonitor&&!run.armed&&!protectedPage&&!pageLostProtected&&current!=null&&current.isOpen();}
    GeckoResult<Object> fillApplicant(ApplicantDetails.Record details){
      GeckoResult<Object> result=new GeckoResult<>();
      if(!canFillApplicant()||fillReply!=null||!details.saved()){result.complete("{\"ok\":false}");return result;}
      fillToken=java.util.UUID.randomUUID().toString();fillSession=current;fillReply=result;fillCommitted=false;
      final String id=fillToken;JSONObject request=json("FILL_DETAILS");
      try{request.put("id",id);request.put("applicationNumber",details.applicationNumber);request.put("dob",details.dob);nativePort.postMessage(request);}
      catch(Exception ex){finishFill(id,false);return result;}
      service.handler.postDelayed(()->finishFill(id,false),10000);return result;
    }
    void cancelFill(){if(fillReply!=null)finishFill(fillToken,false);}
    private void finishFill(String id,boolean ok){
      if(fillReply==null||!fillToken.equals(id))return;
      boolean accepted=ok&&fillCommitted&&fillSession==current&&canFillApplicant();
      GeckoResult<Object> reply=fillReply;fillReply=null;fillSession=null;fillToken="";fillCommitted=false;
      reply.complete(accepted?"{\"ok\":true}":"{\"ok\":false}");
    }
    private String rtoLabel="";
    void setRtoLabel(String value){
      rtoLabel=value==null?"":value.replaceAll("[\\p{Cntrl}]"," ").replaceAll("\\s+"," ").trim();
      if(rtoLabel.length()>100)rtoLabel="";
      JSONObject message=json("RTO_SET");try{message.put("label",rtoLabel);}catch(Exception ignored){}if(nativePort!=null)nativePort.postMessage(message);
    }
    String rtoLabelForTest(){return rtoLabel;}
    String websiteLoadingLabel(){return pageLoading?"Loading Sarathi · "+loadProgress+"%":status;}
    private String safeLocation(String url) {
        if (url == null) return "";
        Uri u = Uri.parse(url);
        return (u.getHost() == null ? "" : u.getHost()) + (u.getPath() == null ? "" : u.getPath());
    }
    void recordApplicantSwitch(long milliseconds,boolean retained){
      String time=new SimpleDateFormat("HH:mm:ss",Locale.US).format(new Date());
      logs.add(time+" Applicant switch: attachMs="+milliseconds+"; retained="+retained);if(logs.size()>60)logs.remove(0);
      status=retained?"Sarathi page restored":"Opening Sarathi…";render();
    }
    void log(String message) {
        String time = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        logs.add(time + " " + message);
        if (logs.size() > 60) logs.remove(0);
        status=message;render();
    }
    private void showSession(GeckoSession s) {
        if (current == s) return;
        current=s;lastVisibleAt=SystemClock.elapsedRealtime();canGoBack=false;render();activate();
        runtime.getWebExtensionController().setTabActive(s,true);
    }
    private void flattenChoices(ChoicePrompt.Choice[] input, ArrayList<ChoicePrompt.Choice> output) {
        for (ChoicePrompt.Choice c : input) {
            if (c.disabled || c.separator) continue;
            if (c.items != null) flattenChoices(c.items, output); else output.add(c);
        }
    }
    private void bindProfileGuard(GeckoSession session){
      session.getWebExtensionController().setMessageDelegate(extension,new WebExtension.MessageDelegate(){
        @Override public GeckoResult<Object> onMessage(String app,Object message,WebExtension.MessageSender sender){
          boolean allowed=sender.session==session&&sessions.contains(session)&&message instanceof JSONObject&&"PROFILE".equals(((JSONObject)message).optString("type"));
          if(message instanceof JSONObject){JSONObject request=(JSONObject)message;String type=request.optString("type");
            if(type.equals("PAGE_CHECK"))allowed=sender.session==session&&session==current&&!pageLoading&&pageQuery.equals(request.optString("token"));
            if(type.equals("FILL_CHECK")||type.equals("FILL_COMMIT")){
              allowed=sender.session==session&&session==fillSession&&fillReply!=null&&!fillCommitted&&fillToken.equals(request.optString("token"))&&canFillApplicant();
              if(allowed&&type.equals("FILL_COMMIT"))fillCommitted=true;
            }
          }
          return GeckoResult.fromValue((Object)(allowed?"{\"ok\":true}":"{\"ok\":false}"));
        }
      },"profile");
    }
    private GeckoSession newSession() {
        service.trimIdle();if(service.sessionCount()>=36)throw new IllegalStateException("Live page limit reached; close an unprotected applicant page first");
        GeckoSession s = new GeckoSession(new org.mozilla.geckoview.GeckoSessionSettings.Builder().contextId("applicant-"+profile).build());
        sessions.add(s);if(extension!=null)bindProfileGuard(s);
        s.setPromptDelegate(new PromptDelegate() {
            @Override public GeckoResult<PromptResponse> onAlertPrompt(GeckoSession session, AlertPrompt prompt){
                if(!promptAvailable())return GeckoResult.fromValue(prompt.dismiss());
                GeckoResult<PromptResponse> result=new GeckoResult<>();
                new AlertDialog.Builder(activity).setTitle("Website message").setMessage(prompt.message)
                 .setPositiveButton("OK",(d,w)->{if(!prompt.isComplete())result.complete(prompt.dismiss());})
                 .setOnCancelListener(d->{if(!prompt.isComplete())result.complete(prompt.dismiss());}).show();return result;
            }
            @Override public GeckoResult<PromptResponse> onButtonPrompt(GeckoSession session, ButtonPrompt prompt){
                if(!promptAvailable())return GeckoResult.fromValue(prompt.dismiss());
                GeckoResult<PromptResponse> result=new GeckoResult<>();
                new AlertDialog.Builder(activity).setTitle("Confirm").setMessage(prompt.message)
                 .setPositiveButton("OK",(d,w)->{if(!prompt.isComplete())result.complete(prompt.confirm(ButtonPrompt.Type.POSITIVE));})
                 .setNegativeButton("Cancel",(d,w)->{if(!prompt.isComplete())result.complete(prompt.confirm(ButtonPrompt.Type.NEGATIVE));})
                 .setOnCancelListener(d->{if(!prompt.isComplete())result.complete(prompt.dismiss());}).show();return result;
            }
            @Override public GeckoResult<PromptResponse> onRepostConfirmPrompt(GeckoSession session, RepostConfirmPrompt prompt){
                if(!promptAvailable())return GeckoResult.fromValue(prompt.dismiss());
                GeckoResult<PromptResponse> result=new GeckoResult<>();
                new AlertDialog.Builder(activity).setTitle("Resend form?").setMessage("This repeats the previous form submission. Continue only if you intend to resend it.")
                 .setPositiveButton("Resend",(d,w)->{if(!prompt.isComplete())result.complete(prompt.confirm(AllowOrDeny.ALLOW));})
                 .setNegativeButton("Cancel",(d,w)->{if(!prompt.isComplete())result.complete(prompt.confirm(AllowOrDeny.DENY));})
                 .setOnCancelListener(d->{if(!prompt.isComplete())result.complete(prompt.dismiss());}).show();return result;
            }
            @Override public GeckoResult<PromptResponse> onChoicePrompt(GeckoSession session, ChoicePrompt prompt) {
                if(!promptAvailable())return GeckoResult.fromValue(prompt.dismiss());
                GeckoResult<PromptResponse> result = new GeckoResult<>();
                ArrayList<ChoicePrompt.Choice> options = new ArrayList<>();
                flattenChoices(prompt.choices, options);
                String[] labels = new String[options.size()];
                boolean[] selected = new boolean[options.size()];
                for (int i = 0; i < options.size(); i++) {
                    labels[i] = options.get(i).label;
                    selected[i] = options.get(i).selected;
                }
                AlertDialog.Builder dialog = new AlertDialog.Builder(activity)
                    .setTitle(prompt.title == null ? "Select option" : prompt.title)
                    .setNegativeButton("Cancel", (d, w) -> { if (!prompt.isComplete()) result.complete(prompt.dismiss()); })
                    .setOnCancelListener(d -> { if (!prompt.isComplete()) result.complete(prompt.dismiss()); });
                if (prompt.type == ChoicePrompt.Type.MULTIPLE) {
                    dialog.setMultiChoiceItems(labels, selected, (d, i, checked) -> selected[i] = checked);
                    dialog.setPositiveButton("OK", (d, w) -> {
                        ArrayList<String> ids = new ArrayList<>();
                        for (int i = 0; i < options.size(); i++) if (selected[i]) ids.add(options.get(i).id);
                        if (!prompt.isComplete()) result.complete(prompt.confirm(ids.toArray(new String[0])));
                    });
                } else {
                    dialog.setItems(labels, (d, i) -> { if (!prompt.isComplete()) result.complete(prompt.confirm(options.get(i))); });
                }
                dialog.show();
                return result;
            }
        });
        s.setProgressDelegate(new GeckoSession.ProgressDelegate() {
            @Override public void onPageStart(GeckoSession session, String url) {
                if(!sessions.contains(session)||!session.isOpen()||session==monitorSession)return;
                if(session!=current&&!run.armed)showSession(session);
                if(session!=current)return;
                cancelFill();expireRetainedProtectionForNavigation(url);
                loadProgress=0;pageLoading=true;pageLoadStartedElapsed=SystemClock.elapsedRealtime();pageReady=false;pageUrl=url;pageQuery=java.util.UUID.randomUUID().toString();
                log("Loading " + safeLocation(url));
                final String loadingId=pageQuery;service.handler.postDelayed(()->{if(!destroyed&&pageLoading&&loadingId.equals(pageQuery))log("Connection slow · waiting for Sarathi");},15000);
            }
            @Override public void onProgressChange(GeckoSession session,int progress){
                if(session==current&&Math.abs(progress-loadProgress)>=10){loadProgress=progress;if(pageLoading)render();}
            }
            @Override public void onPageStop(GeckoSession session, boolean success) {
                if (session == current) {
                    pageLoading=false;loadProgress=100;lastPageStopElapsed=SystemClock.elapsedRealtime();lastPageLoadSucceeded=success;
                    log(success?"Ready":"Page could not load — check connection or use Home");
                    if(success)queryPageReadiness(session,pageQuery,pageUrl,0);
                    if(!firstPageRecorded){firstPageRecorded=true;logs.add("First browser document completed in "+(SystemClock.elapsedRealtime()-startupAt)+" ms; success="+success);service.handler.post(BrowserEngine.this::prepareMonitor);}
                    if(success&&!firstSarathiReadyRecorded&&committedPageUrl.equals(pageUrl)&&"sarathi.parivahan.gov.in".equals(Uri.parse(pageUrl).getHost())){firstSarathiReadyRecorded=true;log("First Sarathi document loaded in "+(SystemClock.elapsedRealtime()-startupAt)+" ms; request="+(SystemClock.elapsedRealtime()-pageLoadStartedElapsed)+" ms");}
                }
            }
        });
        s.setContentDelegate(new GeckoSession.ContentDelegate() {
            @Override public void onCloseRequest(GeckoSession session){pageLost(session,"Applicant page closed");}
            @Override public void onCrash(GeckoSession session){pageLost(session,"Browser page crashed");}
            @Override public void onKill(GeckoSession session){pageLost(session,"Android closed the browser page");}
            @Override public void onTitleChange(GeckoSession session, String title) {
                if (session == current) log("Title: " + (title == null ? "" : title.substring(0, Math.min(title.length(), 150))));
            }
        });
        s.setNavigationDelegate(new GeckoSession.NavigationDelegate() {
            @Override public void onLocationChange(GeckoSession session,String uri,java.util.List<GeckoSession.PermissionDelegate.ContentPermission> permissions,Boolean hasUserGesture){
                if(session==current)committedPageUrl=uri==null?"":uri;
            }
            @Override public void onCanGoBack(GeckoSession session, boolean value) {
                if (session == current) canGoBack = value;
            }
            @Override public GeckoResult<GeckoSession> onNewSession(GeckoSession session, String uri) {
                log("New page " + safeLocation(uri));
                // Gecko opens this session; attach it when its page begins loading.
                return GeckoResult.fromValue(newSession());
            }
            @Override public GeckoResult<String> onLoadError(GeckoSession session, String uri, WebRequestError error) {
                log("Navigation error at " + safeLocation(uri));
                if(session==current)run.fail("Website navigation failed — check the connection");
                return null;
            }
        });
        return s;
    }
}





