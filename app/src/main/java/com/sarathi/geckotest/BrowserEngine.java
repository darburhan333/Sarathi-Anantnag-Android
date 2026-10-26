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
    static final String HOME="https://parivahan.gov.in/";
    private static GeckoRuntime sharedRuntime;
    final GeckoRuntime runtime;
    final MonitorService service;
    final MonitorRun run;final int profile;final String extensionId,assetPath;
    final java.util.Map<GeckoSession,Boolean> activeStates=new java.util.HashMap<>();
    MainActivity activity;
    GeckoSession current, monitorSession;
    final ArrayList<GeckoSession> sessions=new ArrayList<>();
    final ArrayList<String> logs=new ArrayList<>();
    String status="Opening Parivahan…", monitorStatus="Connecting monitor…", monitorUrl;
    boolean canGoBack, foreground, showingMonitor, ready, monitoring, destroyed;
    boolean firstPageRecorded;
    final long startupAt=SystemClock.elapsedRealtime();
    WebExtension.Port nativePort;
    WebExtension extension;
    final PhoneAlerts alerts;
    Runnable afterStop;
    String pendingId;
    volatile String smokeResult="",smokePhase="";
    volatile boolean monitorReady;
    JSONObject lastState=new JSONObject();
    BrowserEngine(MonitorService owner,int number){
      service=owner;profile=number;run=new MonitorRun(this);alerts=new PhoneAlerts(owner,number);
      extensionId=number==1?"sarathi-android@burhan.local":"sarathi-applicant"+number+"@burhan.local";assetPath=number==1?"extension/":"extension"+number+"/";
      if(sharedRuntime==null)sharedRuntime=GeckoRuntime.create(owner.getApplicationContext());
      runtime=sharedRuntime;
      GeckoSession first=newSession();first.open(runtime);showSession(first);first.loadUri(HOME);
      installExtension();
    }
    void runOnUiThread(Runnable r){service.handler.post(r);}
    void render(){if(activity!=null)activity.render();}
    void attach(MainActivity a){activity=a;render();}
    void detach(MainActivity a){if(activity==a)activity=null;}
    void foregroundState(){
      if(nativePort!=null){JSONObject o=json("FOREGROUND");try{o.put("active",foreground);}catch(Exception ignored){}nativePort.postMessage(o);}
      activate();render();
    }
    void activate(){
      for(GeckoSession s:sessions)if(s.isOpen()){
        boolean active=(run.armed&&s==current)||(foreground&&(s==current||(s==monitorSession&&showingMonitor)));
        if(!java.util.Objects.equals(activeStates.get(s),active)){s.setActive(active);s.setPriorityHint(active?GeckoSession.PRIORITY_HIGH:GeckoSession.PRIORITY_DEFAULT);activeStates.put(s,active);}
      }
    }
    void shutdown(){
      destroyed=true;
      if(extension!=null){extension.setMessageDelegate(null,"sarathi");extension.setTabDelegate(null);}
      if(nativePort!=null)nativePort.disconnect();
      for(GeckoSession s:sessions)if(s.isOpen())s.close();
      alerts.stop();activity=null;
    }
    boolean promptAvailable(){
      if(activity!=null&&foreground&&!activity.isFinishing())return true;
      run.fail("Website needs your input — unlock and reopen the app");return false;
    }
    private JSONObject json(String type){JSONObject o=new JSONObject();try{o.put("type",type);}catch(Exception ignored){}return o;}
    void command(String type){if(nativePort!=null)nativePort.postMessage(json(type));}
    void navigate(Runnable action){
      if(nativePort==null){log("Monitor not connected — wait or restart app");return;}
      pendingId=String.valueOf(System.nanoTime());afterStop=action;
      JSONObject o=json("STOP");try{o.put("id",pendingId);}catch(Exception ignored){}nativePort.postMessage(o);
    }
    void prepareMonitor(){if(!ready||monitorUrl==null||monitorSession!=null||destroyed)return;monitorSession=newSession();monitorSession.open(runtime);monitorSession.loadUri(monitorUrl);render();activate();}
    void toggleMonitor(){
      if(!ready||monitorUrl==null){log("Monitor is still starting");return;}
      if(monitorSession==null){
        monitorSession=newSession();monitorSession.open(runtime);
        monitorSession.loadUri(monitorUrl);
      }
      showingMonitor=!showingMonitor;
      render();activate();
      runtime.getWebExtensionController().setTabActive(showingMonitor?monitorSession:current,true);
    }
    private void receive(Object message){
      if(!(message instanceof JSONObject))return;
      JSONObject o=(JSONObject)message;
      switch(o.optString("type")){
        case "READY":
          monitorUrl=o.optString("monitorUrl");ready=true;foregroundState();
          log("Monitor connected in "+(SystemClock.elapsedRealtime()-startupAt)+" ms");
          log("Monitor ready — open Sarathi, then MONITOR to start");if(firstPageRecorded)prepareMonitor();break;
        case "STATE":
          monitoring=o.optBoolean("running")||o.optBoolean("successWatch");
          lastState=o;
          monitorStatus=(monitoring?(foreground?"RUNNING · FOREGROUND":"RUNNING · BACKGROUND"):"STOPPED")+" · Session clicks "+o.optInt("sessionAttempts")+"\n"+o.optString("lastStatus");
          run.onState(o);render();
          break;
        case "ACK":
          if(o.optString("id").equals(pendingId)&&afterStop!=null){Runnable r=afterStop;afterStop=null;r.run();}break;
        case "ERROR":log(o.optString("message"));break;
      }
    }
    private void installExtension(){
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
            nativePort=port;
            port.setDelegate(new WebExtension.PortDelegate(){
              @Override public void onPortMessage(Object message,WebExtension.Port p){receive(message);}
              @Override public void onDisconnect(WebExtension.Port p){
                if(nativePort==p){nativePort=null;ready=false;run.fail("Monitor connection lost — reopen and press START");}
              }
            });
          }
          @Override public GeckoResult<Object> onMessage(String app,Object message,WebExtension.MessageSender sender){
            JSONObject result=new JSONObject();
            try{
              if(sender.session!=null)throw new IllegalArgumentException("Only background messages allowed");
              JSONObject m=(JSONObject)message;
              switch(m.optString("type")){
                case "ARM":run.arm();break;
                case "SECURE_GET":result.put("values",SecureSettings.read(service));break;
                case "SECURE_SET":SecureSettings.write(service,m.getJSONObject("values"));break;
                case "SUMMARY":result.put("text",service.summary());break;
                case "AUTHORIZE":if(!run.authorize())throw new IllegalStateException("Monitoring stopped");break;
                case "SMOKE_PHASE":smokePhase=m.optString("phase");break;
                case "DISARM":run.disarm();break;
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
      }),e->runOnUiThread(()->{ready=false;log("Monitor installation failed: "+e.getClass().getSimpleName());}));
    }

    private String safeLocation(String url) {
        if (url == null) return "";
        Uri u = Uri.parse(url);
        return (u.getHost() == null ? "" : u.getHost()) + (u.getPath() == null ? "" : u.getPath());
    }
    void log(String message) {
        String time = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        logs.add(time + " " + message);
        if (logs.size() > 60) logs.remove(0);
        status=message;render();
    }
    private void showSession(GeckoSession s) {
        if (current == s) return;
        current=s;canGoBack=false;render();activate();
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
          return GeckoResult.fromValue((Object)(allowed?"{\"ok\":true}":"{\"ok\":false}"));
        }
      },"profile");
    }
    private GeckoSession newSession() {
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
                if(session==monitorSession)return;
                if(session!=current)showSession(session);
                log("Loading " + safeLocation(url));
            }
            @Override public void onPageStop(GeckoSession session, boolean success) {
                if (session == current) {
                    log(success?"Page ready":"Page could not load — try Reload");
                    if(!firstPageRecorded){firstPageRecorded=true;logs.add("First page completed in "+(SystemClock.elapsedRealtime()-startupAt)+" ms; success="+success);service.handler.post(BrowserEngine.this::prepareMonitor);}
                }
            }
        });
        s.setContentDelegate(new GeckoSession.ContentDelegate() {
            @Override public void onCloseRequest(GeckoSession session){if(run.armed)run.fail("Applicant page closed — inspect before START");sessions.remove(session);activeStates.remove(session);session.close();if(current==session){current=null;for(GeckoSession other:sessions)if(other!=monitorSession&&other.isOpen()){showSession(other);break;}}render();}
            @Override public void onCrash(GeckoSession session){run.fail("Browser page crashed — reopen and press START");}
            @Override public void onKill(GeckoSession session){run.fail("Android closed the browser page — reopen and press START");}
            @Override public void onTitleChange(GeckoSession session, String title) {
                if (session == current) log("Title: " + (title == null ? "" : title.substring(0, Math.min(title.length(), 150))));
            }
        });
        s.setNavigationDelegate(new GeckoSession.NavigationDelegate() {
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

