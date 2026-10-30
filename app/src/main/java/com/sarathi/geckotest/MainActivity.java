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
    private int notificationApplicant=1;
    private boolean bound,visible;private final Button[] applicantButtons=new Button[MonitorService.PROFILE_COUNT];
    private GeckoView view,monitorView;
    private TextView status,monitorStatus;
    private Button monitorButton,batteryButton;
    private android.widget.ScrollView headerScroll;
    private final android.content.ServiceConnection connection=new android.content.ServiceConnection(){
      public void onServiceConnected(android.content.ComponentName name,android.os.IBinder binder){
        engine=((MonitorService.LocalBinder)binder).getService().engine;
        if(getIntent().hasExtra("applicant")){engine=engine.service.profile(notificationApplicant);engine.service.engine=engine;}
        engine.service.activity=MainActivity.this;engine.service.visible=visible;engine.attach(MainActivity.this);engine.foreground=visible;engine.foregroundState();
      }
      public void onServiceDisconnected(android.content.ComponentName name){engine=null;status.setText("Service disconnected — reopen the app");}
    };
    public boolean monitorReadyForTest(){return engine!=null&&engine.monitorReady;}
    public String smokeResultForTest(){return engine==null?"":engine.smokeResult;}
    public void runSmokeForTest(){if(engine!=null){engine.smokeResult="";engine.smokePhase="";engine.command("SMOKE_RUN");}}
    public void openMonitorForTest(){if(engine!=null)engine.toggleMonitor();}
    public boolean extensionReadyForTest(){return engine!=null&&engine.ready;}
    public String monitorStateForTest(){return monitorStatus.getText().toString();}
    public MonitorService serviceForTest(){return engine==null?null:engine.service;}
    private void command(String type){if(engine!=null)engine.command(type);}
    private void toggleMonitor(){if(engine!=null)engine.toggleMonitor();}
    void render(){
      if(engine==null||!visible)return;
      if(engine.current!=null&&engine.current.isOpen()&&view.getSession()!=engine.current){view.releaseSession();view.setSession(engine.current);engine.activeStates.remove(engine.current);}
      if(engine.monitorSession!=null&&engine.monitorSession.isOpen()&&monitorView.getSession()!=engine.monitorSession){monitorView.releaseSession();monitorView.setSession(engine.monitorSession);engine.activeStates.remove(engine.monitorSession);}
      monitorView.setVisibility(engine.showingMonitor?View.VISIBLE:View.GONE);
      monitorButton.setText(engine.showingMonitor?"Website":"Monitor");
      monitorButton.setBackgroundTintList(ColorStateList.valueOf(engine.showingMonitor?0xFF0F766E:0xFF2563EB));
      for(int i=0;i<MonitorService.PROFILE_COUNT;i++)if(applicantButtons[i]!=null){BrowserEngine e=engine.service.profiles[i];String label="Applicant "+(i+1)+(e!=null&&e.run.armed?" ●":"");if(!applicantButtons[i].getText().toString().equals(label))applicantButtons[i].setText(label);applicantButtons[i].setAlpha(engine.profile==i+1?1f:0.65f);}
      String routineOff=engine.service.routineOffSummary();
      String displayStatus=engine.status+(routineOff.isEmpty()?"":"\nALERTS: ROUTINE OFF ("+routineOff+")");
      if(!status.getText().toString().equals(displayStatus))status.setText(displayStatus);
      android.os.PowerManager power=getSystemService(android.os.PowerManager.class);
      boolean exempt=power.isIgnoringBatteryOptimizations(getPackageName());
      if(batteryButton!=null)batteryButton.setText((exempt&&!power.isPowerSaveMode()&&getSystemService(android.app.NotificationManager.class).areNotificationsEnabled())?"Background\nsetup ✓":"Background\nsetup !");
      String text="Applicant "+engine.profile+" · "+engine.monitorStatus+engine.run.diagnostics();if(!monitorStatus.getText().toString().equals(text))monitorStatus.setText(text);
      if(visible&&engine.monitoring)getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
      else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
    void selectApplicant(int number){if(engine==null||engine.profile==number)return;MonitorService service=engine.service;view.releaseSession();monitorView.releaseSession();engine.activeStates.clear();engine.foreground=false;engine.detach(this);engine.foregroundState();engine=service.profile(number);service.engine=engine;engine.foreground=visible;engine.attach(this);engine.foregroundState();}
    private boolean directCalls(){return getSharedPreferences("calling",0).getBoolean("direct",false);}
    private void openDialer(String phone){startActivity(new Intent(Intent.ACTION_DIAL,Uri.fromParts("tel",phone,null)));}
    private void applicantPhone(int number){
      try{String phone=ApplicantContacts.read(this,number);
        if(phone.isEmpty()){editApplicantPhone(number,false);return;}
        if(directCalls()&&checkSelfPermission(Manifest.permission.CALL_PHONE)==PackageManager.PERMISSION_GRANTED){
          try{startActivity(new Intent(Intent.ACTION_CALL,Uri.fromParts("tel",phone,null)));}
          catch(SecurityException|android.content.ActivityNotFoundException e){openDialer(phone);}
        }else openDialer(phone);
      }catch(android.content.ActivityNotFoundException e){Toast.makeText(this,"No calling app is available",Toast.LENGTH_LONG).show();}
      catch(Exception e){Toast.makeText(this,"Could not read phone number. Open Phone numbers to update it.",Toast.LENGTH_LONG).show();}
    }
    private void manageApplicantAlerts(){
      if(engine==null)return;
      MonitorService service=engine.service;
      LinearLayout panel=new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(dp(12),dp(6),dp(12),dp(6));
      TextView hint=new TextView(this);hint.setText("Choose an alert mode for each applicant. Critical events always ring in every mode.");hint.setTextSize(12);hint.setPadding(0,0,0,dp(8));panel.addView(hint);
      android.widget.ScrollView scroll=new android.widget.ScrollView(this);
      LinearLayout rows=new LinearLayout(this);rows.setOrientation(LinearLayout.VERTICAL);scroll.addView(rows);
      panel.addView(scroll,new LinearLayout.LayoutParams(-1,dp(350)));
      for(int n=1;n<=MonitorService.PROFILE_COUNT;n++){
        final int number=n;
        String saved=getSharedPreferences("monitor",MODE_PRIVATE).getString("alertMode"+n,"CRITICAL_ONLY");
        LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.VERTICAL);row.setPadding(0,dp(5),0,dp(5));
        TextView label=new TextView(this);label.setText("Applicant "+n);label.setTextSize(14);label.setTypeface(Typeface.DEFAULT,Typeface.BOLD);row.addView(label);
        android.widget.RadioGroup choices=new android.widget.RadioGroup(this);choices.setOrientation(android.widget.RadioGroup.HORIZONTAL);
        String[] modes={"ALL","CRITICAL_ONLY","ROUTINE_OFF"};String[] labels={"ALL","CRITICAL ONLY","ROUTINE OFF"};
        for(int k=0;k<modes.length;k++){
          android.widget.RadioButton option=new android.widget.RadioButton(this);option.setText(labels[k]);option.setTextSize(10);option.setTag(modes[k]);option.setId(View.generateViewId());choices.addView(option,new android.widget.RadioGroup.LayoutParams(0,-2,1));
          if(modes[k].equals(saved))choices.check(option.getId());
        }
        choices.setOnCheckedChangeListener((group,id)->{
          View selected=group.findViewById(id);if(selected==null)return;String mode=String.valueOf(selected.getTag());
          getSharedPreferences("monitor",MODE_PRIVATE).edit().putString("alertMode"+number,mode).apply();
          BrowserEngine applicant=service.profiles[number-1];if(applicant!=null)applicant.alerts.setMode(mode);
          service.record("Applicant "+number+" alert mode="+mode);service.refreshForeground(true);render();
        });
        row.addView(choices);rows.addView(row);
      }
      Button test=new Button(this);test.setAllCaps(false);test.setText("Test alert");test.setOnClickListener(v->{if(engine!=null){engine.alerts.testFullAlarm();service.record("Applicant "+engine.profile+" full test alarm requested");}});
      panel.addView(test,new LinearLayout.LayoutParams(-1,dp(48)));
      new AlertDialog.Builder(this).setTitle("Applicant alert modes").setView(panel).setPositiveButton("Done",null).show();
    }
    private void homeSelectedApplicant(){
      if(engine==null)return;
      String stage=engine.lastState.optString("bookingStage","").trim();
      boolean protectedStage=engine.lastState.optBoolean("calendarLock",false)||(!stage.isEmpty()&&!stage.equalsIgnoreCase("MONITORING")&&!stage.equalsIgnoreCase("STANDBY")&&!stage.equalsIgnoreCase("READY"));
      Runnable goHome=()->{if(engine!=null)engine.home();};
      if(engine.run.armed&&protectedStage){
        new AlertDialog.Builder(this).setTitle("Leave protected booking page?")
          .setMessage("Applicant "+engine.profile+" is armed and currently at "+stage+". Going Home will leave this page. Continue?")
          .setPositiveButton("Go Home",(d,w)->goHome.run()).setNegativeButton("Cancel",null).show();
      }else goHome.run();
    }
    private void copyLog(){
      String report="App: MPARIVAHAN SLOT BOOKING -J&K 3.6-v11.47-safety\nEngine: GeckoView 158.0.20261021001945\nAndroid API: "
        +android.os.Build.VERSION.SDK_INT+"\n"+(engine==null?"Connecting":engine.service.metricsSummary()+"\n"+engine.service.diagnosticReport()+"\nManufacturer: "+android.os.Build.MANUFACTURER+"\nDoze exemption: "+getSystemService(android.os.PowerManager.class).isIgnoringBatteryOptimizations(getPackageName())+"\nNotifications: "+(getSystemService(android.app.NotificationManager.class).areNotificationsEnabled()?"ALLOWED":"REQUIRED")+"\nBattery Saver: "+getSystemService(android.os.PowerManager.class).isPowerSaveMode()+"\nActive applicants: "+engine.service.activeCount());
      ClipboardManager cm=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);cm.setPrimaryClip(ClipData.newPlainText("Sarathi diagnostics",report));
      Toast.makeText(this,"Log copied",Toast.LENGTH_SHORT).show();
    }
    private void managePhones(){
      LinearLayout panel=new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(dp(12),dp(8),dp(12),dp(8));
      android.widget.Switch direct=new android.widget.Switch(this);direct.setText("Direct call on long-press");direct.setTextSize(14);direct.setMinHeight(dp(48));
      direct.setChecked(directCalls()&&checkSelfPermission(Manifest.permission.CALL_PHONE)==PackageManager.PERMISSION_GRANTED);panel.addView(direct);
      TextView hint=new TextView(this);hint.setText("OFF: opens dialer. ON: calls immediately. Your phone may show its call screen.");hint.setTextSize(12);panel.addView(hint);
      android.widget.ScrollView scroll=new android.widget.ScrollView(this);LinearLayout entries=new LinearLayout(this);entries.setOrientation(LinearLayout.VERTICAL);scroll.addView(entries);panel.addView(scroll,new LinearLayout.LayoutParams(-1,dp(340)));
      AlertDialog list=new AlertDialog.Builder(this).setTitle("Applicant phone numbers").setView(panel).setNegativeButton("Close",null).create();
      direct.setOnCheckedChangeListener((v,checked)->{
        if(checked&&checkSelfPermission(Manifest.permission.CALL_PHONE)!=PackageManager.PERMISSION_GRANTED){direct.setChecked(false);requestPermissions(new String[]{Manifest.permission.CALL_PHONE},20);Toast.makeText(this,"After allowing Phone permission, turn the switch on.",Toast.LENGTH_LONG).show();return;}
        getSharedPreferences("calling",0).edit().putBoolean("direct",checked).apply();
      });
      for(int n=1;n<=MonitorService.PROFILE_COUNT;n++){
        final int number=n;String phone="";boolean readable=true;try{phone=ApplicantContacts.read(this,n);}catch(Exception e){readable=false;}
        LinearLayout row=new LinearLayout(this);row.setBaselineAligned(false);
        final String label="Applicant "+n+"  ·  "+(!readable?"Needs attention":phone.isEmpty()?"No number added":"Saved · …"+phone.substring(Math.max(0,phone.length()-4)));
        button(row,label,phone.isEmpty()?Color.rgb(37,65,103):Color.rgb(14,116,110),()->{list.dismiss();editApplicantPhone(number,true);});entries.addView(row);
      }
      list.show();
    }
    private void editApplicantPhone(int number,boolean returnToList){
      android.widget.EditText input=new android.widget.EditText(this);input.setInputType(android.text.InputType.TYPE_CLASS_PHONE);input.setSingleLine(true);input.setHint("Phone number, e.g. +91…");
      try{input.setText(ApplicantContacts.read(this,number));}catch(Exception ignored){}
      AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Applicant "+number+" phone number").setMessage("Long-press this applicant to use the saved number.").setView(input)
       .setPositiveButton("Save",null).setNeutralButton("Remove",null).setNegativeButton("Cancel",null).create();
      dialog.setOnDismissListener(unused->{if(returnToList&&!isFinishing()&&!isDestroyed())managePhones();});
      dialog.setOnShowListener(unused->{
       dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{ApplicantContacts.save(this,number,input.getText().toString());dialog.dismiss();Toast.makeText(this,"Phone number saved",Toast.LENGTH_SHORT).show();}catch(IllegalArgumentException e){input.setError(e.getMessage());}catch(Exception e){input.setError("Could not save securely. Please try again.");}});
       dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v->{try{ApplicantContacts.remove(this,number);dialog.dismiss();Toast.makeText(this,"Phone number removed",Toast.LENGTH_SHORT).show();}catch(Exception e){input.setError("Could not remove number. Please try again.");}});
      });dialog.show();
    }
    private void batterySettings(){
      android.os.PowerManager power=getSystemService(android.os.PowerManager.class);
      boolean exempt=power.isIgnoringBatteryOptimizations(getPackageName());
      boolean overlayAllowed=android.provider.Settings.canDrawOverlays(this);
      String message="Android Doze exemption: "+(exempt?"ALLOWED":"REQUIRED")+"\nNotifications: "+(getSystemService(android.app.NotificationManager.class).areNotificationsEnabled()?"ALLOWED":"REQUIRED")+"\nBattery Saver: "+(power.isPowerSaveMode()?"ON — turn it off":"OFF")+"\nDisplay over other apps: "+(overlayAllowed?"ALLOWED":"REQUIRED — keeps monitoring running while minimized")+"\n\nThe \"displaying over other apps\" notification is generated by Android and can be hidden in Android notification settings. The overlay permission itself must remain enabled.\n\nAllow background activity and autostart in your "+android.os.Build.MANUFACTURER+" phone settings. Manufacturer battery managers can still stop the app. Keep the ongoing monitoring notification visible. Force-stop or reboot ends monitoring; START again manually.";
      new AlertDialog.Builder(this).setTitle("Locked-screen monitoring setup").setMessage(message)
       .setPositiveButton(exempt?"App settings":"Allow exemption",(d,w)->{
         try{startActivity(new Intent(exempt?android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS:android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,Uri.parse("package:"+getPackageName())));}
         catch(Exception ex){try{startActivity(new Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));}catch(Exception ignored){Toast.makeText(this,"Could not open battery settings",Toast.LENGTH_LONG).show();}}
       }).setNeutralButton(overlayAllowed?"Overlay OK":"Allow overlay",(d,w)->{
         try{startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+getPackageName())));}
         catch(Exception ex){Toast.makeText(this,"Could not open overlay settings. Open Settings > Apps > Special app access > Display over other apps.",Toast.LENGTH_LONG).show();}
       }).setNegativeButton("Close",null).show();
    }
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private Button button(LinearLayout row, String label, int color, Runnable action) {
        Button b = new Button(this);
        b.setText(label);b.setContentDescription(label);
        b.setAllCaps(false);b.setTextSize(11);b.setGravity(Gravity.CENTER);b.setMaxLines(2);b.setEllipsize(android.text.TextUtils.TruncateAt.END);row.setBaselineAligned(false);
        b.setTypeface(Typeface.DEFAULT,Typeface.NORMAL);
        b.setTextColor(Color.WHITE);
        b.setMinHeight(dp(48));b.setMinimumHeight(dp(48));
        b.setMinWidth(0);b.setMinimumWidth(0);
        b.setPadding(dp(4),dp(4),dp(4),dp(4));
        GradientDrawable shape=new GradientDrawable();
        shape.setColor(color);shape.setCornerRadius(dp(12));
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x40FFFFFF),shape,null));
        b.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(0,Math.max(dp(52),(int)Math.ceil(getResources().getDisplayMetrics().scaledDensity*30)+dp(10)),1);
        params.setMargins(dp(2),dp(2),dp(2),dp(2));
        row.addView(b,params);
        return b;
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);notificationApplicant=Math.max(1,Math.min(MonitorService.PROFILE_COUNT,getIntent().getIntExtra("applicant",1)));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(11,20,38));
        getWindow().setStatusBarColor(Color.rgb(11,20,38));
        getWindow().setNavigationBarColor(Color.rgb(11,20,38));
        LinearLayout header=new LinearLayout(this);header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(9),dp(6),dp(9),dp(8));
        headerScroll=new android.widget.ScrollView(this);headerScroll.setFillViewport(false);headerScroll.addView(header);
        boolean landscape=getResources().getConfiguration().orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        root.addView(headerScroll,new LinearLayout.LayoutParams(-1,landscape?Math.round(getResources().getDisplayMetrics().heightPixels*0.44f):-2));
        TextView heading = new TextView(this);
        heading.setText("MPARIVAHAN SLOT BOOKING -J&K");
        heading.setTextColor(Color.rgb(87,211,255));heading.setTextSize(19);
        heading.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        heading.setPadding(dp(4),dp(3),dp(4),dp(8));
        android.widget.ImageView logo=new android.widget.ImageView(this);logo.setImageResource(R.drawable.ic_launcher_foreground);logo.setContentDescription("Driving test logo");
        LinearLayout brand=new LinearLayout(this);brand.setGravity(Gravity.CENTER_VERTICAL);brand.addView(logo,new LinearLayout.LayoutParams(dp(42),dp(42)));LinearLayout names=new LinearLayout(this);names.setOrientation(LinearLayout.VERTICAL);names.addView(heading);TextView developer=new TextView(this);developer.setText("V3.5 · DEVELOPED BY BURHAN MOHIDIN");developer.setTextSize(10);developer.setTextColor(Color.rgb(89,225,167));developer.setPadding(dp(4),0,0,dp(5));names.addView(developer);brand.addView(names,new LinearLayout.LayoutParams(0,-2,1));header.addView(brand);
        for(int group=0;group<2;group++){LinearLayout applicants=new LinearLayout(this);for(int k=0;k<4;k++){final int n=group*4+k+1;applicantButtons[n-1]=button(applicants,"Applicant "+n,Color.rgb(37,65,103),()->selectApplicant(n));applicantButtons[n-1].setOnLongClickListener(v->{applicantPhone(n);return true;});}header.addView(applicants);}
        LinearLayout utilities=new LinearLayout(this);
        applicantButtons[8]=button(utilities,"Applicant 9",Color.rgb(37,65,103),()->selectApplicant(9));
        applicantButtons[8].setOnLongClickListener(v->{applicantPhone(9);return true;});
        batteryButton=button(utilities,"Background setup · hold: Home",Color.rgb(35,53,76),this::batterySettings);
        batteryButton.setTextSize(10);batteryButton.setOnLongClickListener(v->{homeSelectedApplicant();return true;});
        button(utilities,"Phone numbers",Color.rgb(14,116,144),this::managePhones);
        Button copyLog=button(utilities,"Copy log · hold: Alerts",Color.rgb(104,65,165),this::copyLog);
        copyLog.setTextSize(10);copyLog.setOnLongClickListener(v->{manageApplicantAlerts();return true;});
        header.addView(utilities);
        status = new TextView(this);
        status.setTextSize(12);
        status.setMaxLines(3);status.setTextColor(Color.rgb(157,177,201));
        status.setPadding(dp(4),0,dp(4),dp(3));
        status.setText("Opening Parivahan…");
        monitorStatus=new TextView(this);monitorStatus.setTextSize(12);monitorStatus.setMaxLines(3);
        monitorStatus.setTextColor(Color.rgb(223,234,248));
        monitorStatus.setPadding(dp(4),0,dp(4),dp(5));
        monitorStatus.setText("Connecting monitor… website opens independently");
        LinearLayout controls=new LinearLayout(this);
        monitorButton=button(controls,"Monitor",Color.rgb(37,99,235),this::toggleMonitor);
        Button startAllButton=button(controls,"Start All",Color.rgb(5,150,105),()->{if(engine!=null)engine.service.startAllReady();});
        Button stopButton=button(controls,"Stop",Color.rgb(190,42,66),()->{if(engine!=null)engine.run.stopByUser();});
        Button silenceButton=button(controls,"Silence",Color.rgb(141,76,15),()->{if(engine!=null){engine.alerts.stop();command("SILENCE");}});
        monitorButton.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        for(Button b:new Button[]{monitorButton,startAllButton,stopButton,silenceButton}){b.setTextSize(15);b.setMinHeight(dp(64));b.setMinimumHeight(dp(64));LinearLayout.LayoutParams lp=(LinearLayout.LayoutParams)b.getLayoutParams();lp.height=dp(64);b.setLayoutParams(lp);}
        silenceButton.setOnLongClickListener(v->{if(engine!=null){engine.service.silenceAll();Toast.makeText(this,"All applicant alarms silenced",Toast.LENGTH_SHORT).show();}return true;});
        stopButton.setOnLongClickListener(v->{new AlertDialog.Builder(this).setTitle("Stop all applicants?").setPositiveButton("Stop all",(d,w)->{if(engine!=null)engine.service.stopAll();}).setNegativeButton("Cancel",null).show();return true;});
        header.addView(controls);
        LinearLayout details=new LinearLayout(this);details.setOrientation(LinearLayout.VERTICAL);details.setPadding(dp(9),dp(6),dp(9),dp(6));
        GradientDrawable card=new GradientDrawable();card.setColor(Color.rgb(21,43,66));card.setCornerRadius(dp(10));details.setBackground(card);
        status.setTextColor(Color.rgb(125,211,252));monitorStatus.setTextColor(Color.rgb(110,231,183));details.addView(status);details.addView(monitorStatus);header.addView(details);
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
    @Override public void onConfigurationChanged(android.content.res.Configuration config){
      super.onConfigurationChanged(config);
      if(headerScroll!=null){android.view.ViewGroup.LayoutParams params=headerScroll.getLayoutParams();
       params.height=config.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE?dp(Math.round(config.screenHeightDp*0.44f)):android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
       headerScroll.setLayoutParams(params);
      }
    }
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);notificationApplicant=Math.max(1,Math.min(MonitorService.PROFILE_COUNT,intent.getIntExtra("applicant",1)));if(engine!=null&&intent.hasExtra("applicant"))selectApplicant(notificationApplicant);}
    @Override public void onBackPressed(){
      if(engine!=null&&engine.showingMonitor)toggleMonitor();
      else if(engine!=null&&engine.current!=null&&engine.canGoBack)engine.navigate(()->engine.current.goBack());
      else super.onBackPressed();
    }
    @Override protected void onResume(){super.onResume();visible=true;if(engine!=null){engine.service.activity=this;if(!engine.service.visible)for(BrowserEngine e:engine.service.profiles)if(e!=null&&e.run.armed)e.run.tick();engine.service.visible=true;engine.service.record("Activity resumed; "+engine.service.clockDiagnostics());engine.foreground=true;engine.ensureUsable();engine.foregroundState();}}
    @Override protected void onPause(){view.releaseSession();monitorView.releaseSession();visible=false;if(engine!=null){engine.service.visible=false;engine.service.record("Activity minimized; screen="+getSystemService(android.os.PowerManager.class).isInteractive()+"; active="+engine.service.activeCount());engine.foreground=false;engine.activeStates.clear();engine.foregroundState();}super.onPause();}
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





