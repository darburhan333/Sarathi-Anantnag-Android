package com.sarathi.geckotest;

import android.app.Activity;
import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
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

public class MainActivity extends Activity {
    private static final String HOME = "https://parivahan.gov.in/";
    private static GeckoRuntime runtime;
    private GeckoView view;
    private GeckoSession current;
    private TextView status;
    private final ArrayList<GeckoSession> sessions = new ArrayList<>();
    private final ArrayList<String> logs = new ArrayList<>();
    private boolean canGoBack;
    private GeckoView monitorView;
    private GeckoSession monitorSession;
    private String monitorUrl;
    private WebExtension.Port nativePort;
    private PhoneAlerts alerts;
    private TextView monitorStatus;
    private boolean foreground, showingMonitor, ready, monitoring;
    private Runnable afterStop;
    private String pendingId;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private volatile String smokeResult="";
    public String smokeResultForTest(){return smokeResult;}
    public void runSmokeForTest(){command("SMOKE_RUN");}
    public void openMonitorForTest(){toggleMonitor();}
    public boolean extensionReadyForTest(){return ready;}
    public String monitorStateForTest(){return monitorStatus.getText().toString();}
    private JSONObject json(String type){JSONObject o=new JSONObject();try{o.put("type",type);}catch(Exception ignored){}return o;}
    private void command(String type){if(nativePort!=null)nativePort.postMessage(json(type));}
    private void foregroundState(){if(nativePort!=null){JSONObject o=json("FOREGROUND");try{o.put("active",foreground);}catch(Exception ignored){}nativePort.postMessage(o);}}
    private void navigate(Runnable action){
      if(nativePort==null){log("Monitor not connected — wait or restart app");return;}
      pendingId=String.valueOf(System.nanoTime());afterStop=action;
      JSONObject o=json("STOP");try{o.put("id",pendingId);}catch(Exception ignored){}nativePort.postMessage(o);
    }
    private void toggleMonitor(){
      if(!ready||monitorUrl==null){log("Monitor is still starting");return;}
      if(monitorSession==null){
        monitorSession=newSession();monitorSession.open(runtime);
        monitorView.setSession(monitorSession);monitorSession.loadUri(monitorUrl);
      }
      showingMonitor=!showingMonitor;
      monitorView.setVisibility(showingMonitor?View.VISIBLE:View.GONE);
      if(current!=null)current.setActive(foreground);
      monitorSession.setActive(foreground);
      runtime.getWebExtensionController().setTabActive(showingMonitor?monitorSession:current,true);
    }
    private void receive(Object message){
      if(!(message instanceof JSONObject))return;
      JSONObject o=(JSONObject)message;
      switch(o.optString("type")){
        case "READY":
          monitorUrl=o.optString("monitorUrl");ready=true;foregroundState();
          if(current==null){GeckoSession first=newSession();first.open(runtime);showSession(first);first.loadUri(HOME);}
          log("Monitor ready — open Sarathi, then MONITOR to start");break;
        case "STATE":
          monitoring=o.optBoolean("running")||o.optBoolean("successWatch");
          monitorStatus.setText((monitoring?"ACTIVE":"STOPPED")+" · Session clicks "+o.optInt("sessionAttempts")+" · "+o.optString("bookingStage")+"\n"+o.optString("lastStatus"));
          if(foreground&&monitoring)getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
          else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
          break;
        case "ACK":
          if(o.optString("id").equals(pendingId)&&afterStop!=null){Runnable r=afterStop;afterStop=null;r.run();}break;
        case "ERROR":log(o.optString("message"));break;
      }
    }
    private void installExtension(){
      runtime.getWebExtensionController().ensureBuiltIn("resource://android/assets/extension/","sarathi-android@burhan.local").accept(ext->{
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
                if(nativePort==p){nativePort=null;ready=false;monitorStatus.setText("DISCONNECTED — monitoring stopped; restart the app");getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);}
              }
            });
          }
          @Override public GeckoResult<Object> onMessage(String app,Object message,WebExtension.MessageSender sender){
            JSONObject result=new JSONObject();
            try{
              if(sender.session!=null)throw new IllegalArgumentException("Only background messages allowed");
              JSONObject m=(JSONObject)message;
              switch(m.optString("type")){
                case "SMOKE_RESULT":smokeResult=m.optString("result");break;
                case "AUDIO":
                  JSONObject settings=m.optJSONObject("settings");
                  alerts.audio(m.optString("action"),settings==null||settings.optBoolean("soundEnabled",true),settings==null?85:settings.optInt("volume",85));break;
                case "NOTIFY":alerts.notify(m.optString("title"),m.optString("message"));break;
                case "SHARE":
                  Intent share=new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,m.optString("text"));
                  startActivity(Intent.createChooser(share,"Share monitoring history"));break;
                default:throw new IllegalArgumentException("Unknown native action");
              }
              result.put("ok",true);
            }catch(Exception e){try{result.put("ok",false);result.put("error",e.getMessage());}catch(Exception ignored){}}
            return GeckoResult.fromValue((Object)result);
          }
        },"sarathi");
      },e->{ready=false;log("Monitor installation failed: "+e.getClass().getSimpleName());});
    }

    private String safeLocation(String url) {
        if (url == null) return "";
        Uri u = Uri.parse(url);
        return (u.getHost() == null ? "" : u.getHost()) + (u.getPath() == null ? "" : u.getPath());
    }
    private void log(String message) {
        String time = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        logs.add(time + " " + message);
        if (logs.size() > 60) logs.remove(0);
        status.setText(message);
    }
    private void button(LinearLayout row, String label, Runnable action) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(12);
        b.setOnClickListener(v -> action.run());
        row.addView(b, new LinearLayout.LayoutParams(0, -2, 1));
    }
    private void showSession(GeckoSession s) {
        if (current == s) return;
        if (current != null) { current.setActive(false); view.releaseSession(); }
        current = s;
        canGoBack = false;
        view.setSession(s);
        s.setActive(foreground);
        runtime.getWebExtensionController().setTabActive(s,true);
    }
    private void flattenChoices(ChoicePrompt.Choice[] input, ArrayList<ChoicePrompt.Choice> output) {
        for (ChoicePrompt.Choice c : input) {
            if (c.disabled || c.separator) continue;
            if (c.items != null) flattenChoices(c.items, output); else output.add(c);
        }
    }
    private GeckoSession newSession() {
        GeckoSession s = new GeckoSession();
        sessions.add(s);
        s.setPromptDelegate(new PromptDelegate() {
            @Override public GeckoResult<PromptResponse> onAlertPrompt(GeckoSession session, AlertPrompt prompt){
                GeckoResult<PromptResponse> result=new GeckoResult<>();
                new AlertDialog.Builder(MainActivity.this).setTitle("Website message").setMessage(prompt.message)
                 .setPositiveButton("OK",(d,w)->{if(!prompt.isComplete())result.complete(prompt.dismiss());})
                 .setOnCancelListener(d->{if(!prompt.isComplete())result.complete(prompt.dismiss());}).show();return result;
            }
            @Override public GeckoResult<PromptResponse> onButtonPrompt(GeckoSession session, ButtonPrompt prompt){
                GeckoResult<PromptResponse> result=new GeckoResult<>();
                new AlertDialog.Builder(MainActivity.this).setTitle("Confirm").setMessage(prompt.message)
                 .setPositiveButton("OK",(d,w)->{if(!prompt.isComplete())result.complete(prompt.confirm(ButtonPrompt.Type.POSITIVE));})
                 .setNegativeButton("Cancel",(d,w)->{if(!prompt.isComplete())result.complete(prompt.confirm(ButtonPrompt.Type.NEGATIVE));})
                 .setOnCancelListener(d->{if(!prompt.isComplete())result.complete(prompt.dismiss());}).show();return result;
            }
            @Override public GeckoResult<PromptResponse> onRepostConfirmPrompt(GeckoSession session, RepostConfirmPrompt prompt){
                GeckoResult<PromptResponse> result=new GeckoResult<>();
                new AlertDialog.Builder(MainActivity.this).setTitle("Resend form?").setMessage("This repeats the previous form submission. Continue only if you intend to resend it.")
                 .setPositiveButton("Resend",(d,w)->{if(!prompt.isComplete())result.complete(prompt.confirm(AllowOrDeny.ALLOW));})
                 .setNegativeButton("Cancel",(d,w)->{if(!prompt.isComplete())result.complete(prompt.confirm(AllowOrDeny.DENY));})
                 .setOnCancelListener(d->{if(!prompt.isComplete())result.complete(prompt.dismiss());}).show();return result;
            }
            @Override public GeckoResult<PromptResponse> onChoicePrompt(GeckoSession session, ChoicePrompt prompt) {
                GeckoResult<PromptResponse> result = new GeckoResult<>();
                ArrayList<ChoicePrompt.Choice> options = new ArrayList<>();
                flattenChoices(prompt.choices, options);
                String[] labels = new String[options.size()];
                boolean[] selected = new boolean[options.size()];
                for (int i = 0; i < options.size(); i++) {
                    labels[i] = options.get(i).label;
                    selected[i] = options.get(i).selected;
                }
                AlertDialog.Builder dialog = new AlertDialog.Builder(MainActivity.this)
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
                if (session == current) log("Load completed: " + success + " (check the displayed page)");
            }
        });
        s.setContentDelegate(new GeckoSession.ContentDelegate() {
            @Override public void onCrash(GeckoSession session){log("Browser page crashed — restart app");command("FAILURE");}
            @Override public void onKill(GeckoSession session){log("Android closed the browser page — restart app");command("FAILURE");}
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
                if(session!=monitorSession)command("FAILURE");
                return null;
            }
        });
        return s;
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        alerts=new PhoneAlerts(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        TextView heading = new TextView(this);
        heading.setText("SARATHI ANANTNAG · 2.0 / V11.47\nDEVELOPED BY BURHAN MOHIDIN");
        heading.setTextColor(Color.rgb(0,130,70));heading.setTextSize(13);
        heading.setPadding(12, 8, 12, 8);
        root.addView(heading);
        status = new TextView(this);
        status.setTextSize(12);
        status.setMaxLines(3);
        root.addView(status);
        monitorStatus=new TextView(this);monitorStatus.setTextSize(12);monitorStatus.setMaxLines(3);
        monitorStatus.setText("Starting monitor…");root.addView(monitorStatus);
        LinearLayout controls=new LinearLayout(this);
        button(controls,"Monitor / Website",this::toggleMonitor);
        button(controls,"STOP",()->command("STOP"));
        button(controls,"Silence",()->{alerts.stop();command("SILENCE");});root.addView(controls);
        LinearLayout row = new LinearLayout(this);
        button(row, "Back", () -> { if(showingMonitor)toggleMonitor();else if(current!=null&&canGoBack)navigate(()->current.goBack()); });
        button(row, "Home", () -> {if(current!=null)navigate(()->current.loadUri(HOME));});
        button(row, "Reload", () -> {if(current!=null)navigate(()->current.reload());});
        button(row, "Copy log", () -> {
            String report = "App: Sarathi Anantnag 2.0 / V11.47\nEngine: GeckoView 158.0.20261021001945\nAndroid API: "
                    + android.os.Build.VERSION.SDK_INT + "\n" + String.join("\n", logs);
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("Sarathi diagnostics", report));
            Toast.makeText(this, "Log copied", Toast.LENGTH_SHORT).show();
        });
        root.addView(row);
        view = new GeckoView(this);
        FrameLayout pages=new FrameLayout(this);
        pages.addView(view,new FrameLayout.LayoutParams(-1,-1));
        monitorView=new GeckoView(this);monitorView.setVisibility(View.GONE);
        pages.addView(monitorView,new FrameLayout.LayoutParams(-1,-1));
        root.addView(pages,new LinearLayout.LayoutParams(-1,0,1));
        setContentView(root);
        if (runtime == null) runtime = GeckoRuntime.create(getApplicationContext());
        installExtension();
        if(android.os.Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
          requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},10);
    }
    @Override public void onBackPressed() {
        if(showingMonitor)toggleMonitor();
        else if(current!=null&&canGoBack)navigate(()->current.goBack());
        else super.onBackPressed();
    }
    @Override protected void onPause() {
        foreground=false;foregroundState();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if(current!=null)current.setActive(false);
        if(monitorSession!=null)monitorSession.setActive(false);
        super.onPause();
    }
    @Override protected void onResume() {
        super.onResume();foreground=true;foregroundState();
        if(current!=null)current.setActive(true);
        if(monitorSession!=null)monitorSession.setActive(true);
    }
    @Override protected void onDestroy() {
        foreground=false;foregroundState();alerts.stop();
        if(nativePort!=null)nativePort.disconnect();
        view.releaseSession();monitorView.releaseSession();
        for(GeckoSession s:sessions)if(s.isOpen())s.close();
        super.onDestroy();
    }
}
