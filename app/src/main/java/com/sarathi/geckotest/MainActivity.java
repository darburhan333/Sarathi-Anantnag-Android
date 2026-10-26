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

public class MainActivity extends Activity {
    private BrowserEngine engine;
    private boolean bound,visible;private final Button[] applicantButtons=new Button[3];
    private GeckoView view,monitorView;
    private TextView status,monitorStatus;
    private Button monitorButton;
    private final android.content.ServiceConnection connection=new android.content.ServiceConnection(){
      public void onServiceConnected(android.content.ComponentName name,android.os.IBinder binder){
        engine=((MonitorService.LocalBinder)binder).getService().engine;
        engine.service.activity=MainActivity.this;engine.service.visible=visible;engine.attach(MainActivity.this);engine.foreground=visible;engine.foregroundState();
      }
      public void onServiceDisconnected(android.content.ComponentName name){engine=null;status.setText("Service disconnected — reopen the app");}
    };
    public boolean monitorReadyForTest(){return engine!=null&&engine.monitorReady;}
    public String smokeResultForTest(){return engine==null?"":engine.smokeResult;}
    public void runSmokeForTest(){if(engine!=null)engine.command("SMOKE_RUN");}
    public void openMonitorForTest(){if(engine!=null)engine.toggleMonitor();}
    public boolean extensionReadyForTest(){return engine!=null&&engine.ready;}
    public String monitorStateForTest(){return monitorStatus.getText().toString();}
    public MonitorService serviceForTest(){return engine==null?null:engine.service;}
    private void command(String type){if(engine!=null)engine.command(type);}
    private void toggleMonitor(){if(engine!=null)engine.toggleMonitor();}
    void render(){
      if(engine==null)return;
      if(engine.current!=null&&view.getSession()!=engine.current){view.releaseSession();view.setSession(engine.current);engine.activeStates.remove(engine.current);}
      if(engine.monitorSession!=null&&monitorView.getSession()!=engine.monitorSession){monitorView.releaseSession();monitorView.setSession(engine.monitorSession);engine.activeStates.remove(engine.monitorSession);}
      monitorView.setVisibility(engine.showingMonitor?View.VISIBLE:View.GONE);
      monitorButton.setText(engine.showingMonitor?"Website":"Monitor");
      for(int i=0;i<3;i++)if(applicantButtons[i]!=null){BrowserEngine e=engine.service.profiles[i];String label="Applicant "+(i+1)+(e!=null&&e.run.armed?" ●":"");if(!applicantButtons[i].getText().toString().equals(label))applicantButtons[i].setText(label);applicantButtons[i].setAlpha(engine.profile==i+1?1f:0.65f);}
      if(!status.getText().toString().equals(engine.status))status.setText(engine.status);
      String text="Applicant "+engine.profile+" · "+engine.monitorStatus+engine.run.diagnostics();if(!monitorStatus.getText().toString().equals(text))monitorStatus.setText(text);
      if(visible&&engine.monitoring)getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
      else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
    void selectApplicant(int number){if(engine==null||engine.profile==number)return;MonitorService service=engine.service;view.releaseSession();monitorView.releaseSession();engine.activeStates.clear();engine.foreground=false;engine.detach(this);engine.foregroundState();engine=service.profile(number);service.engine=engine;engine.foreground=visible;engine.attach(this);engine.foregroundState();}
    private void batterySettings(){
      android.os.PowerManager pm=getSystemService(android.os.PowerManager.class);
      if(pm.isIgnoringBatteryOptimizations(getPackageName())){Toast.makeText(this,"Battery optimization exemption is enabled",Toast.LENGTH_LONG).show();return;}
      new AlertDialog.Builder(this).setTitle("Allow locked-screen monitoring")
       .setMessage("Allow battery optimization exemption for monitoring with the screen off. This uses more battery. On Infinix, also allow background activity in the phone's app battery settings.")
       .setPositiveButton("Open settings",(d,w)->{
         try{startActivity(new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,Uri.parse("package:"+getPackageName())));}
         catch(Exception ex){startActivity(new Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));}
       }).setNegativeButton("Later",null).show();
    }
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private Button button(LinearLayout row, String label, int color, Runnable action) {
        Button b = new Button(this);
        b.setText(label);b.setContentDescription(label);
        b.setAllCaps(false);b.setTextSize(13);
        b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        b.setTextColor(Color.WHITE);
        b.setMinHeight(dp(48));b.setMinimumHeight(dp(48));
        b.setMinWidth(0);b.setMinimumWidth(0);
        b.setPadding(dp(4),dp(4),dp(4),dp(4));
        GradientDrawable shape=new GradientDrawable();
        shape.setColor(color);shape.setCornerRadius(dp(12));
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x40FFFFFF),shape,null));
        b.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(0,-2,1);
        params.setMargins(dp(3),dp(4),dp(3),dp(4));
        row.addView(b,params);
        return b;
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(11,20,38));
        getWindow().setStatusBarColor(Color.rgb(11,20,38));
        getWindow().setNavigationBarColor(Color.rgb(11,20,38));
        LinearLayout header=new LinearLayout(this);header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(9),dp(6),dp(9),dp(8));
        root.addView(header);
        TextView heading = new TextView(this);
        heading.setText("MPARIVAHAN SLOT BOOKING -J&K · 3.0\nDEVELOPED BY BURHAN MOHIDIN");
        heading.setTextColor(Color.rgb(89,225,167));heading.setTextSize(13);
        heading.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        heading.setPadding(dp(4),dp(3),dp(4),dp(8));
        android.widget.ImageView logo=new android.widget.ImageView(this);logo.setImageResource(R.drawable.ic_launcher_foreground);logo.setContentDescription("Driving test logo");
        LinearLayout brand=new LinearLayout(this);brand.setGravity(Gravity.CENTER_VERTICAL);brand.addView(logo,new LinearLayout.LayoutParams(dp(42),dp(42)));brand.addView(heading,new LinearLayout.LayoutParams(0,-2,1));header.addView(brand);
        LinearLayout applicants=new LinearLayout(this);for(int i=0;i<3;i++){final int n=i+1;applicantButtons[i]=button(applicants,"Applicant "+n,Color.rgb(37,65,103),()->selectApplicant(n));}header.addView(applicants);
        status = new TextView(this);
        status.setTextSize(12);
        status.setMaxLines(2);status.setTextColor(Color.rgb(157,177,201));
        status.setPadding(dp(4),0,dp(4),dp(3));
        status.setText("Opening Parivahan…");header.addView(status);
        monitorStatus=new TextView(this);monitorStatus.setTextSize(12);monitorStatus.setMaxLines(3);
        monitorStatus.setTextColor(Color.rgb(223,234,248));
        monitorStatus.setPadding(dp(4),0,dp(4),dp(5));
        monitorStatus.setText("Connecting monitor… website opens independently");header.addView(monitorStatus);
        LinearLayout controls=new LinearLayout(this);
        monitorButton=button(controls,"Monitor",Color.rgb(37,99,235),this::toggleMonitor);
        button(controls,"Stop this",Color.rgb(190,42,66),()->{if(engine!=null)engine.run.stopByUser();});
        button(controls,"Silence",Color.rgb(141,76,15),()->{if(engine!=null)engine.alerts.stop();command("SILENCE");});header.addView(controls);
        LinearLayout row = new LinearLayout(this);
        button(row, "Back", Color.rgb(56,73,102), () -> { if(engine!=null){if(engine.showingMonitor)toggleMonitor();else if(engine.current!=null&&engine.canGoBack)engine.navigate(()->engine.current.goBack());} });
        button(row, "Home", Color.rgb(4,120,87), () -> {if(engine!=null&&engine.current!=null)engine.navigate(()->engine.current.loadUri(BrowserEngine.HOME));});
        button(row, "Reload", Color.rgb(14,116,144), () -> {if(engine!=null&&engine.current!=null)engine.navigate(()->engine.current.reload());});
        button(row, "Copy log", Color.rgb(104,65,165), () -> {
            String report = "App: MPARIVAHAN SLOT BOOKING -J&K 3.0\nEngine: GeckoView 158.0.20261021001945\nAndroid API: "
                    + android.os.Build.VERSION.SDK_INT + "\n" + (engine==null?"Connecting":String.join("\n",engine.logs)+engine.run.diagnostics());
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("Sarathi diagnostics", report));
            Toast.makeText(this, "Log copied", Toast.LENGTH_SHORT).show();
        });
        header.addView(row);
        LinearLayout battery=new LinearLayout(this);
        button(battery,"Battery settings",Color.rgb(35,53,76),this::batterySettings);button(battery,"Stop all",Color.rgb(139,38,56),()->{if(engine!=null)engine.service.stopAll();});
        header.addView(battery);
        view = new GeckoView(this);
        FrameLayout pages=new FrameLayout(this);
        pages.addView(view,new FrameLayout.LayoutParams(-1,-1));
        monitorView=new GeckoView(this);monitorView.setVisibility(View.GONE);
        pages.addView(monitorView,new FrameLayout.LayoutParams(-1,-1));
        root.addView(pages,new LinearLayout.LayoutParams(-1,0,1));
        setContentView(root);
        bound=bindService(new Intent(this,MonitorService.class),connection,BIND_AUTO_CREATE);
        if(android.os.Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
          requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},10);
    }
    @Override public void onBackPressed(){
      if(engine!=null&&engine.showingMonitor)toggleMonitor();
      else if(engine!=null&&engine.current!=null&&engine.canGoBack)engine.navigate(()->engine.current.goBack());
      else super.onBackPressed();
    }
    @Override protected void onResume(){super.onResume();visible=true;if(engine!=null){engine.service.activity=this;engine.service.visible=true;engine.foreground=true;engine.foregroundState();}}
    @Override protected void onPause(){visible=false;if(engine!=null){engine.service.visible=false;engine.foreground=false;engine.foregroundState();}super.onPause();}
    @Override protected void onStop(){
      super.onStop();
      if(engine!=null){BrowserEngine e=engine;e.service.handler.post(()->{e.activeStates.clear();e.activate();});}
    }
    @Override protected void onDestroy(){
      view.releaseSession();monitorView.releaseSession();
      if(engine!=null){engine.activeStates.clear();engine.detach(this);if(engine.service.activity==this)engine.service.activity=null;engine.activate();}
      if(bound)unbindService(connection);
      super.onDestroy();
    }
}

