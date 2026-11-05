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
import android.widget.PopupMenu;
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
    private Button monitorButton,batteryButton,callButton;
    private android.widget.Spinner applicantPicker;
    private TextView activeSummary;
    private final Button[] signatureButtons=new Button[6];
    private android.widget.ScrollView headerScroll;
    private final LinearLayout[] applicantRows=new LinearLayout[2];
    private int lockedApplicant=1;
    private AlertDialog settingsMenuDialog,logsDialog;
    private LinearLayout signatureControls,settingsContext;
    private TextView settingsTitle;
    private View appLogo;
    private String activePanel="monitor";
    private String pendingMonitorPanel;
    private FrameLayout websitePages;
    private final Button[] settingsApplicantButtons=new Button[MonitorService.PROFILE_COUNT];
    private final android.content.ServiceConnection connection=new android.content.ServiceConnection(){
      public void onServiceConnected(android.content.ComponentName name,android.os.IBinder binder){
        MonitorService service=((MonitorService.LocalBinder)binder).getService();
        lockedApplicant=Math.max(1,Math.min(MonitorService.PROFILE_COUNT,getSharedPreferences("monitor",0).getInt("lockedApplicant",1)));
        if(getIntent().hasExtra("applicant"))lockedApplicant=notificationApplicant;
        engine=service.profile(lockedApplicant);service.engine=engine;if(engine.monitorSession==null)engine.showingMonitor=true;
        engine.service.activity=MainActivity.this;engine.service.visible=visible;engine.attach(MainActivity.this);engine.foreground=visible;engine.foregroundState();
      }
      public void onServiceDisconnected(android.content.ComponentName name){engine=null;status.setText("Service disconnected — reopen the app");status.setVisibility(View.VISIBLE);}
    };
    // Test-only instrumentation hooks; production code must not call these methods.
 public boolean monitorReadyForTest(){return engine!=null&&engine.monitorReady;}
 public int lockedApplicantForTest(){return lockedApplicant;}
 public void openApplicantSettingsForTest(){showApplicantSettings();}
 public void closeApplicantSettingsForTest(){closeApplicantSettings();}
 public boolean applicantSettingsOpenForTest(){return !activePanel.equals("monitor");}
 public boolean logoIsBrandingOnlyForTest(){return appLogo!=null&&!appLogo.hasOnClickListeners()&&!appLogo.isLongClickable();}
 public boolean controlsAboveMonitorForTest(){int[] buttons=new int[2],page=new int[2];signatureControls.getLocationOnScreen(buttons);monitorView.getLocationOnScreen(page);return buttons[1]+signatureControls.getHeight()<=page[1];}
 public void openLogsForTest(){showLogs();}
 public void closeLogsForTest(){if(logsDialog!=null)logsDialog.dismiss();}
 public boolean logsHaveBrowserForTest(){return logsDialog!=null&&containsBrowser(logsDialog.getWindow().getDecorView());}
 private boolean containsBrowser(View view){if(view instanceof GeckoView)return true;if(view instanceof android.view.ViewGroup){android.view.ViewGroup group=(android.view.ViewGroup)view;for(int i=0;i<group.getChildCount();i++)if(containsBrowser(group.getChildAt(i)))return true;}return false;}
 public void setApplicantsVisibleForTest(boolean show){getSharedPreferences("monitor",0).edit().putBoolean("showApplicants",show).apply();applyApplicantVisibility(show);}
 public boolean applicantsVisibleForTest(){return applicantRows[0]!=null&&applicantRows[0].getVisibility()==View.VISIBLE&&applicantButtons[8]!=null&&applicantButtons[8].getVisibility()==View.VISIBLE;}
    public String smokeResultForTest(){return engine==null?"":engine.smokeResult;}
    public void runSmokeForTest(){if(engine!=null){engine.smokeResult="";engine.smokePhase="";engine.command("SMOKE_RUN");}}
    public void openMonitorForTest(){if(engine!=null&&!engine.showingMonitor)engine.toggleMonitor();}
    public boolean extensionReadyForTest(){return engine!=null&&engine.ready;}
    public String monitorStateForTest(){return monitorStatus.getText().toString();}
    public String signatureControlLabelForTest(int index){return signatureButtons[index].getText().toString();}
    public void clickSignatureControlForTest(int index){signatureButtons[index].performClick();}
    public MonitorService serviceForTest(){return engine==null?null:engine.service;}
    private void command(String type){if(engine!=null)engine.command(type);}
    private void toggleMonitor(){if(!activePanel.equals("monitor")){closeApplicantSettings();return;}if(engine!=null)engine.toggleMonitor();}
    void render(){
      if(engine==null||!visible)return;
      if(engine.current!=null&&engine.current.isOpen()&&view.getSession()!=engine.current){view.releaseSession();view.setSession(engine.current);engine.activeStates.remove(engine.current);}
      if(engine.monitorSession!=null&&engine.monitorSession.isOpen()&&monitorView.getSession()!=engine.monitorSession){monitorView.releaseSession();monitorView.setSession(engine.monitorSession);engine.activeStates.remove(engine.monitorSession);}
      monitorView.setVisibility(engine.showingMonitor?View.VISIBLE:View.GONE);
      if(pendingMonitorPanel!=null&&engine.monitorSession!=null){String panel=pendingMonitorPanel;pendingMonitorPanel=null;engine.monitorSession.loadUri(engine.monitorUrl+"#"+panel);}
      monitorButton.setText(engine.showingMonitor?"Website":"Monitor");monitorButton.setContentDescription(engine.showingMonitor?"Switch to website":"Switch to monitor");
      if(applicantPicker!=null&&applicantPicker.getSelectedItemPosition()!=engine.profile-1)applicantPicker.setSelection(engine.profile-1);
      if(activeSummary!=null)activeSummary.setText(engine.service.activeCount()+" of 9 monitoring");
      if(callButton!=null){callButton.setText("Call A"+engine.profile);callButton.setContentDescription("Call Applicant "+engine.profile);}
      if(settingsContext!=null)settingsContext.setVisibility(activePanel.equals("monitor")?View.GONE:View.VISIBLE);
      if(signatureControls!=null)signatureControls.setVisibility(activePanel.equals("monitor")?View.VISIBLE:View.GONE);
      if(settingsTitle!=null)settingsTitle.setText("Alerts · Applicant "+engine.profile);
      monitorButton.setBackgroundTintList(ColorStateList.valueOf(0xFFB4F1DA));
      for(int i=0;i<MonitorService.PROFILE_COUNT;i++)if(applicantButtons[i]!=null){BrowserEngine e=engine.service.profiles[i];boolean running=e!=null&&e.run.armed;String label="Applicant "+(i+1)+"\n"+(running?"● RUNNING":"○ STOPPED");if(!applicantButtons[i].getText().toString().equals(label))applicantButtons[i].setText(label);applicantButtons[i].setAlpha(lockedApplicant==i+1?1f:0.72f);applicantButtons[i].setBackgroundTintList(ColorStateList.valueOf(lockedApplicant==i+1?0xFF0F766E:0xFF254167));}
      for(int i=0;i<settingsApplicantButtons.length;i++)if(settingsApplicantButtons[i]!=null){BrowserEngine profile=engine.service.profiles[i];settingsApplicantButtons[i].setText("Applicant "+(i+1)+(profile!=null&&profile.run.armed?" ● RUNNING":" ○ STOPPED")+(i+1==lockedApplicant?" · LOCKED":""));}
      applyApplicantVisibility(getSharedPreferences("monitor",0).getBoolean("showApplicants",false));
      if(!status.getText().toString().equals(engine.status))status.setText(engine.status);status.setVisibility(engine.showingMonitor?View.GONE:View.VISIBLE);
      android.os.PowerManager power=getSystemService(android.os.PowerManager.class);
      boolean exempt=power.isIgnoringBatteryOptimizations(getPackageName());
      if(batteryButton!=null)batteryButton.setText((exempt&&android.provider.Settings.canDrawOverlays(this)&&!power.isPowerSaveMode()&&getSystemService(android.app.NotificationManager.class).areNotificationsEnabled())?"Background\nsetup ✓":"Background\nsetup !");
      String text=engine.ready?"Applicant "+engine.profile+" · "+(engine.monitoring?"RUNNING":"STOPPED")+engine.service.routineWarning():"Connecting Applicant "+engine.profile+"…";if(!monitorStatus.getText().toString().equals(text))monitorStatus.setText(text);
      if(visible&&engine.monitoring)getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
      else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
    void selectApplicant(int number){if(engine==null||number<1||number>MonitorService.PROFILE_COUNT)return;lockedApplicant=number;getSharedPreferences("monitor",0).edit().putInt("lockedApplicant",number).apply();if(engine.profile==number){render();return;}MonitorService service=engine.service;view.releaseSession();monitorView.releaseSession();engine.activeStates.clear();engine.foreground=false;engine.detach(this);engine.foregroundState();engine=service.profile(number);if(engine.monitorSession==null)engine.showingMonitor=true;engine.run.resumeGraceUntil=SystemClock.elapsedRealtime()+30000;service.engine=engine;engine.foreground=visible;pendingMonitorPanel=activePanel;if(!activePanel.equals("monitor")){engine.showingMonitor=true;engine.prepareMonitor();}engine.attach(this);engine.foregroundState();}
    private boolean directCalls(){return getSharedPreferences("calling",0).getBoolean("direct",false);}
    static Intent applicantCallIntent(String phone,boolean direct,boolean permitted){return new Intent(direct&&permitted?Intent.ACTION_CALL:Intent.ACTION_DIAL,Uri.fromParts("tel",phone,null));}
    private void openDialer(String phone){startActivity(new Intent(Intent.ACTION_DIAL,Uri.fromParts("tel",phone,null)));}
    private TextView text(String value,int size,int color){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);return t;}
    private android.widget.ArrayAdapter<String> choices(String[] values){return new android.widget.ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,values){private View style(View v){TextView t=(TextView)v;t.setTextColor(0xFFEDF4FA);t.setTextSize(13);t.setBackgroundColor(0xFF132131);t.setPadding(dp(10),dp(9),dp(10),dp(9));return t;}@Override public View getView(int position,View converted,android.view.ViewGroup parent){return style(super.getView(position,converted,parent));}@Override public View getDropDownView(int position,View converted,android.view.ViewGroup parent){return style(super.getDropDownView(position,converted,parent));}};}
    private void themePanel(View view){if(view instanceof TextView)((TextView)view).setTextColor(0xFFEDF4FA);if(view instanceof android.view.ViewGroup){android.view.ViewGroup group=(android.view.ViewGroup)view;for(int i=0;i<group.getChildCount();i++)themePanel(group.getChildAt(i));}}
    private LinearLayout darkPanel(){LinearLayout p=new LinearLayout(this);p.setOrientation(LinearLayout.VERTICAL);p.setBackgroundColor(0xFF0B1420);p.setPadding(dp(18),dp(16),dp(18),dp(16));return p;}
    private AlertDialog darkDialog(LinearLayout panel,boolean full){AlertDialog dialog=new AlertDialog.Builder(this,android.R.style.Theme_Material_Dialog_Alert).setView(panel).create();dialog.show();if(dialog.getWindow()!=null){dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0xFF0B1420));dialog.getWindow().setLayout(-1,full?-1:-2);}return dialog;}
    private void menuEntry(LinearLayout panel,String title,String description,Runnable action){
      LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.VERTICAL);row.setPadding(dp(15),dp(12),dp(15),dp(12));
      GradientDrawable shape=new GradientDrawable();shape.setColor(0xFF132131);shape.setCornerRadius(dp(12));shape.setStroke(dp(1),0xFF304454);row.setBackground(new RippleDrawable(ColorStateList.valueOf(0x224EE2AC),shape,null));
      TextView name=text(title,15,0xFFEDF4FA);name.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));row.addView(name);TextView detail=text(description,12,0xFFA5B6C7);detail.setPadding(0,dp(3),0,0);row.addView(detail);
      row.setOnClickListener(v->{if(settingsMenuDialog!=null)settingsMenuDialog.dismiss();action.run();});row.setContentDescription(title);LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.setMargins(0,dp(8),0,0);panel.addView(row,params);
    }
    private void showQuickMenu(View anchor){
      LinearLayout panel=darkPanel();TextView title=text("Settings",21,0xFFEDF4FA);title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);panel.addView(title);panel.addView(text("App preferences · selected applicant A"+lockedApplicant,12,0xFFA5B6C7));
      menuEntry(panel,"Phone numbers","Applicant contacts and direct calling",this::managePhones);
      menuEntry(panel,"Alerts","Telegram token, recipients, sound and vibration",this::manageApplicantAlerts);
      menuEntry(panel,"Logs","Activity and diagnostics only",this::showLogs);
      menuEntry(panel,"Background setup","Battery and service permissions",this::batterySettings);
      LinearLayout footer=new LinearLayout(this);button(footer,"Close",0xFF192B3D,()->{if(settingsMenuDialog!=null)settingsMenuDialog.dismiss();});panel.addView(footer);
      settingsMenuDialog=darkDialog(panel,false);settingsMenuDialog.setOnDismissListener(d->settingsMenuDialog=null);
    }
    private void applyApplicantVisibility(boolean show){for(LinearLayout row:applicantRows)if(row!=null)row.setVisibility(show?View.VISIBLE:View.GONE);if(applicantButtons[8]!=null){applicantButtons[8].setVisibility(show?View.VISIBLE:View.GONE);if(applicantButtons[8].getParent() instanceof View)((View)applicantButtons[8].getParent()).setVisibility(show?View.VISIBLE:View.GONE);}}
    private void toggleApplicantsVisibility(){boolean show=!getSharedPreferences("monitor",0).getBoolean("showApplicants",false);getSharedPreferences("monitor",0).edit().putBoolean("showApplicants",show).apply();applyApplicantVisibility(show);Toast.makeText(this,show?"Applicants shown":"Applicants hidden — open logo menu to restore",Toast.LENGTH_SHORT).show();}
    private void showLogs(){
      if(engine==null)return;final MonitorService service=engine.service;
      LinearLayout panel=darkPanel();TextView title=text("Logs",22,0xFFEDF4FA);title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);panel.addView(title);panel.addView(text("Activity & diagnostics",12,0xFFA5B6C7));
      android.widget.Spinner filter=new android.widget.Spinner(this);String[] labels=new String[MonitorService.PROFILE_COUNT+1];labels[0]="All applicants";for(int n=1;n<labels.length;n++)labels[n]="Applicant "+n;
      filter.setAdapter(choices(labels));filter.setBackgroundTintList(ColorStateList.valueOf(0xFFA5B6C7));filter.setContentDescription("Filter logs by applicant");panel.addView(filter);
      TextView report=text(service.diagnosticReport(),11,0xFFCAD7E4);report.setTypeface(Typeface.MONOSPACE);report.setTextIsSelectable(true);report.setPadding(dp(10),dp(10),dp(10),dp(10));
      android.widget.ScrollView scroll=new android.widget.ScrollView(this);scroll.setBackgroundColor(0xFF132131);scroll.addView(report);panel.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
      filter.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){public void onNothingSelected(android.widget.AdapterView<?> parent){}public void onItemSelected(android.widget.AdapterView<?> parent,View child,int position,long id){BrowserEngine profile=position==0?null:service.profiles[position-1];report.setText(position==0?service.diagnosticReport():profile==null?"No activity for Applicant "+position:"Applicant "+position+profile.run.diagnostics()+"\n"+String.join("\n",profile.logs));}});
      LinearLayout actions=new LinearLayout(this);button(actions,"Copy logs",0xFF192B3D,()->{((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Sarathi logs",report.getText()));Toast.makeText(this,"Logs copied",Toast.LENGTH_SHORT).show();});button(actions,"Share",0xFF173B32,()->startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,report.getText().toString()),"Share logs")));button(actions,"Close",0xFF192B3D,()->{if(logsDialog!=null)logsDialog.dismiss();});panel.addView(actions);
      logsDialog=darkDialog(panel,true);logsDialog.setOnDismissListener(d->logsDialog=null);
    }
    private void showApplicantSettings(){showApplicantSettings("alerts-settings");}
    private void showApplicantSettings(String panelName){
      if(engine==null)return;activePanel="alerts-settings";pendingMonitorPanel="alerts-settings";engine.showingMonitor=true;engine.prepareMonitor();render();engine.activate();
    }
    private void closeApplicantSettings(){
      activePanel="monitor";pendingMonitorPanel="monitor";if(engine!=null){engine.showingMonitor=true;engine.prepareMonitor();render();engine.activate();}
    }
    private void copyDiagnosticLog(){
      String report="App: MPARIVAHAN SLOT BOOKING -J&K "+BuildConfig.VERSION_NAME+" · SHA "+BuildConfig.GIT_SHA+"\nProtected page retention after STOP: "+BrowserEngine.STOP_PAGE_PROTECTION_MAX_MS+" ms (5 minutes)\nEngine: GeckoView 158.0.20261021001945\nAndroid API: "
        +android.os.Build.VERSION.SDK_INT+"\n"+(engine==null?"Connecting":engine.service.diagnosticReport()+"\nManufacturer: "+android.os.Build.MANUFACTURER+"\nDoze exemption: "+getSystemService(android.os.PowerManager.class).isIgnoringBatteryOptimizations(getPackageName())+"\nNotifications: "+(getSystemService(android.app.NotificationManager.class).areNotificationsEnabled()?"ALLOWED":"REQUIRED")+"\nBattery Saver: "+getSystemService(android.os.PowerManager.class).isPowerSaveMode()+"\nActive applicants: "+engine.service.activeCount());
      ClipboardManager cm=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
      cm.setPrimaryClip(ClipData.newPlainText("Sarathi diagnostics",report));
      Toast.makeText(this,"Log copied",Toast.LENGTH_SHORT).show();
    }
    private void applicantPhone(int number){
      try{String phone=ApplicantContacts.read(this,number);
        if(phone.isEmpty()){editApplicantPhone(number,false);return;}
        if(directCalls()&&checkSelfPermission(Manifest.permission.CALL_PHONE)==PackageManager.PERMISSION_GRANTED){
          try{startActivity(applicantCallIntent(phone,true,true));}
          catch(SecurityException|android.content.ActivityNotFoundException e){openDialer(phone);}
        }else openDialer(phone);
      }catch(android.content.ActivityNotFoundException e){Toast.makeText(this,"No calling app is available",Toast.LENGTH_LONG).show();}
      catch(Exception e){Toast.makeText(this,"Could not read phone number. Open Phone numbers to update it.",Toast.LENGTH_LONG).show();}
    }
    private char[] backupPassword;
    private void transferSettings(boolean exporting){
      if(engine==null)return;
      if(engine.service.activeCount()>0||engine.service.protectedCount()>0){Toast.makeText(this,"Finish protected booking pages and stop monitoring before transferring settings",Toast.LENGTH_LONG).show();return;}
      android.widget.EditText password=new android.widget.EditText(this);password.setInputType(129);password.setHint("Passphrase (10+ characters)");
      new AlertDialog.Builder(this).setTitle(exporting?"Export encrypted settings":"Import encrypted settings").setView(password).setMessage("Includes all applicants’ Telegram credentials and phone numbers. Keep this file and passphrase private. Import replaces saved settings; no login sessions are transferred.").setPositiveButton("Choose file",(d,w)->{
        backupPassword=password.getText().toString().toCharArray();password.setText("");if(backupPassword.length<10){java.util.Arrays.fill(backupPassword,'\0');backupPassword=null;Toast.makeText(this,"Use at least 10 characters",Toast.LENGTH_LONG).show();return;}
        Intent intent=new Intent(exporting?Intent.ACTION_CREATE_DOCUMENT:Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(exporting?"application/octet-stream":"*/*");if(exporting)intent.putExtra(Intent.EXTRA_TITLE,"MParivahan-settings.enc");startActivityForResult(intent,exporting?81:82);
      }).setNegativeButton("Cancel",null).show();
    }
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request!=81&&request!=82)return;
      final char[] password=backupPassword;backupPassword=null;if(password==null)return;
      if(result!=RESULT_OK||data==null||data.getData()==null){java.util.Arrays.fill(password,'\0');return;}
      final MonitorService service=engine==null?null:engine.service;if(service==null||service.activeCount()>0||service.protectedCount()>0){java.util.Arrays.fill(password,'\0');Toast.makeText(this,"Stop monitoring and finish protected pages first",Toast.LENGTH_LONG).show();return;}
      service.settingsBusy=true;final Uri uri=data.getData();
      new Thread(()->{String message;
        try{if(request==81){byte[] encrypted=SettingsBackup.encrypt(SettingsBackup.snapshot(service),password);try(java.io.OutputStream out=getContentResolver().openOutputStream(uri,"wt")){if(out==null)throw new java.io.IOException();out.write(encrypted);}message="Encrypted settings exported";}
        else{java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();try(java.io.InputStream in=getContentResolver().openInputStream(uri)){if(in==null)throw new java.io.IOException();byte[] block=new byte[4096];int count;while((count=in.read(block))!=-1){bytes.write(block,0,count);if(bytes.size()>SettingsBackup.MAX_BYTES)throw new java.io.IOException("File too large");}}SettingsBackup.apply(service,SettingsBackup.decrypt(bytes.toByteArray(),password));message="Settings imported; sessions remain stopped";}
        }catch(Exception ex){message="Settings transfer failed: wrong passphrase, damaged file, or unavailable storage. Existing settings were not intentionally cleared.";}finally{java.util.Arrays.fill(password,'\0');}
        final String feedback=message;service.handler.post(()->{service.settingsBusy=false;for(BrowserEngine profile:service.profiles)if(profile!=null)profile.command("SETTINGS_RESTORE");if(!isDestroyed())Toast.makeText(this,feedback,Toast.LENGTH_LONG).show();});
      },"SettingsTransfer").start();
    }
    private void manageApplicantAlerts(){showApplicantSettings();}
    private void manageDeviceAlertOptions(){
      if(engine==null)return;
      MonitorService service=engine.service;
      LinearLayout panel=new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(dp(14),dp(8),dp(14),dp(8));
      TextView hint=new TextView(this);hint.setText("Critical alarms pulse every 2 seconds for 60 seconds, then every 30 seconds until Silence. Modes affect routine notices only. ALL includes routine sound; CRITICAL ONLY keeps critical alerts; ROUTINE OFF also shows a persistent reminder.\n"+PhoneAlerts.alarmEnvironmentWarning(this));hint.setTextSize(12);hint.setPadding(0,0,0,dp(8));panel.addView(hint);
      TextView idleLabel=new TextView(this);int idleMinutes=getSharedPreferences("monitor",0).getInt("idleUnloadMinutes",60);idleLabel.setText("Unload unprotected idle pages after "+idleMinutes+" minutes (logged-in pages are retained unless Android reports real memory pressure)");panel.addView(idleLabel);
      android.widget.SeekBar idleTimeout=new android.widget.SeekBar(this);idleTimeout.setMax(175);idleTimeout.setProgress(Math.max(0,Math.min(175,idleMinutes-5)));panel.addView(idleTimeout);
      idleTimeout.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(android.widget.SeekBar bar,int progress,boolean fromUser){idleLabel.setText("Unload unprotected idle pages after "+(progress+5)+" minutes (logged-in pages are retained unless Android reports real memory pressure)");}public void onStartTrackingTouch(android.widget.SeekBar bar){}public void onStopTrackingTouch(android.widget.SeekBar bar){getSharedPreferences("monitor",0).edit().putInt("idleUnloadMinutes",bar.getProgress()+5).apply();}});

      android.widget.ScrollView scroll=new android.widget.ScrollView(this);LinearLayout rows=new LinearLayout(this);rows.setOrientation(LinearLayout.VERTICAL);scroll.addView(rows);panel.addView(scroll,new LinearLayout.LayoutParams(-1,dp(240)));
      for(int n=1;n<=MonitorService.PROFILE_COUNT;n++){
        final int number=n;LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.VERTICAL);
        TextView label=new TextView(this);label.setText("Applicant "+n);row.addView(label);
        android.widget.Spinner modes=new android.widget.Spinner(this);modes.setAdapter(new android.widget.ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,PhoneAlerts.MODES));
        String saved=getSharedPreferences("monitor",0).getString("alertMode"+n,"CRITICAL ONLY");modes.setSelection(java.util.Arrays.asList(PhoneAlerts.MODES).indexOf(saved));
        modes.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){public void onNothingSelected(android.widget.AdapterView<?> v){}public void onItemSelected(android.widget.AdapterView<?> v,View child,int position,long id){getSharedPreferences("monitor",0).edit().putString("alertMode"+number,PhoneAlerts.MODES[position]).apply();service.refreshForeground(true);render();}});row.addView(modes);
        Button test=new Button(this);test.setText("Test alert");test.setOnClickListener(v->{BrowserEngine existing=service.profiles[number-1];PhoneAlerts alerts=existing==null?new PhoneAlerts(service,number):existing.alerts;alerts.testOnce();});row.addView(test);rows.addView(row);
      }

      Button export=new Button(this);export.setText("Export encrypted settings");export.setOnClickListener(v->transferSettings(true));panel.addView(export);Button restore=new Button(this);restore.setText("Import encrypted settings");restore.setOnClickListener(v->transferSettings(false));panel.addView(restore);
      themePanel(panel);android.widget.ScrollView alertsScroll=new android.widget.ScrollView(this);alertsScroll.setBackgroundColor(0xFF0B1420);alertsScroll.addView(panel);
      new AlertDialog.Builder(this,android.R.style.Theme_Material_Dialog_Alert).setTitle("Device alert options").setView(alertsScroll).setPositiveButton("Done",null).setNeutralButton("Silence all now",(d,w)->service.silenceAll()).show();
    }
    private void managePhones(){
      LinearLayout panel=darkPanel();
      android.widget.Switch direct=new android.widget.Switch(this);direct.setText("Direct call from Call button");direct.setTextSize(14);direct.setMinHeight(dp(48));
      direct.setChecked(directCalls()&&checkSelfPermission(Manifest.permission.CALL_PHONE)==PackageManager.PERMISSION_GRANTED);panel.addView(direct);
      TextView hint=new TextView(this);hint.setText("OFF: opens dialer. ON: calls immediately. Your phone may show its call screen.");hint.setTextSize(12);panel.addView(hint);
      android.widget.ScrollView scroll=new android.widget.ScrollView(this);LinearLayout entries=new LinearLayout(this);entries.setOrientation(LinearLayout.VERTICAL);scroll.addView(entries);panel.addView(scroll,new LinearLayout.LayoutParams(-1,dp(340)));
      AlertDialog list=new AlertDialog.Builder(this,android.R.style.Theme_Material_Dialog_Alert).setTitle("Applicant phone numbers").setView(panel).setNegativeButton("Close",null).create();
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
      themePanel(panel);list.show();
    }
    private void editApplicantPhone(int number,boolean returnToList){
      android.widget.EditText input=new android.widget.EditText(this);input.setTextColor(0xFFEDF4FA);input.setHintTextColor(0xFFA5B6C7);input.setInputType(android.text.InputType.TYPE_CLASS_PHONE);input.setSingleLine(true);input.setHint("Phone number, e.g. +91…");
      try{input.setText(ApplicantContacts.read(this,number));}catch(Exception ignored){}
      AlertDialog dialog=new AlertDialog.Builder(this,android.R.style.Theme_Material_Dialog_Alert).setTitle("Applicant "+number+" phone number").setMessage("Use Call on this applicant’s main screen. Direct calling can be enabled in Phone numbers.").setView(input)
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
      String message="Android Doze exemption: "+(exempt?"ALLOWED":"REQUIRED")+"\nNotifications: "+(getSystemService(android.app.NotificationManager.class).areNotificationsEnabled()?"ALLOWED":"REQUIRED")+"\nBattery Saver: "+(power.isPowerSaveMode()?"ON — turn it off":"OFF")+"\nDisplay over other apps: "+(overlayAllowed?"ALLOWED":"REQUIRED — keeps monitoring running while minimized")+"\n\nAllow background activity and autostart in your "+android.os.Build.MANUFACTURER+" phone settings. Manufacturer battery managers can still stop the app. Android supplies the displaying-over-other-apps notification; its visibility may be adjustable in Android notification settings. Keep overlay permission ON. Keep the ongoing monitoring notification visible. Force-stop or reboot ends monitoring; START again manually.";
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
    private Button signatureButton(LinearLayout row,String label,int fill,int ink,Runnable action){
      Button result=button(row,label,fill,action);result.setTextColor(ink);result.setTextSize(12);result.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));result.setMinHeight(dp(48));result.setMinimumHeight(dp(48));result.setPadding(dp(4),dp(8),dp(4),dp(8));result.getLayoutParams().height=Math.max(dp(42),(int)Math.ceil(getResources().getDisplayMetrics().scaledDensity*26)+dp(10));result.setMinHeight(dp(40));result.setMinimumHeight(dp(40));return result;
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);getSharedPreferences("monitor",0).edit().putBoolean("showApplicants",false).apply();notificationApplicant=Math.max(1,Math.min(MonitorService.PROFILE_COUNT,getIntent().getIntExtra("applicant",1)));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0B1420);
        getWindow().setStatusBarColor(0xFF0B1420);
        getWindow().setNavigationBarColor(0xFF132131);
        LinearLayout header=new LinearLayout(this);header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(9),dp(3),dp(9),dp(4));
        headerScroll=new android.widget.ScrollView(this);headerScroll.setFillViewport(false);headerScroll.addView(header);
        boolean landscape=getResources().getConfiguration().orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        root.addView(headerScroll,new LinearLayout.LayoutParams(-1,landscape?Math.round(getResources().getDisplayMetrics().heightPixels*0.44f):-2));
        LinearLayout brand=new LinearLayout(this);brand.setGravity(Gravity.CENTER_VERTICAL);brand.setPadding(dp(5),dp(5),dp(5),dp(6));
        android.widget.ImageView logo=new android.widget.ImageView(this);appLogo=logo;logo.setImageResource(R.drawable.ic_launcher_foreground);logo.setContentDescription("MPARIVAHAN app logo");brand.addView(logo,new LinearLayout.LayoutParams(dp(30),dp(30)));
        LinearLayout names=new LinearLayout(this);names.setOrientation(LinearLayout.VERTICAL);names.setPadding(dp(9),0,dp(4),0);
        TextView heading=new TextView(this);heading.setText("MPARIVAHAN · J&K");heading.setTextColor(0xFFB4F1DA);heading.setTextSize(16);heading.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));heading.setLetterSpacing(0.01f);names.addView(heading);
        TextView developer=new TextView(this);developer.setText("By Burhan Mohidin");developer.setTextColor(0xFF80E2BF);developer.setTextSize(10);developer.setPadding(0,dp(2),0,0);names.addView(developer);brand.addView(names,new LinearLayout.LayoutParams(0,-2,1));
        Button settingsMenu=new Button(this);settingsMenu.setText("☰");settingsMenu.setTextSize(23);settingsMenu.setAllCaps(false);settingsMenu.setTextColor(0xFFEDF4FA);settingsMenu.setBackgroundColor(Color.TRANSPARENT);settingsMenu.setPadding(0,0,0,0);settingsMenu.setMinWidth(0);settingsMenu.setMinimumWidth(0);settingsMenu.setContentDescription("Open all settings");settingsMenu.setOnClickListener(v->showQuickMenu(settingsMenu));brand.addView(settingsMenu,new LinearLayout.LayoutParams(dp(44),dp(44)));header.addView(brand);
        LinearLayout selection=new LinearLayout(this);selection.setGravity(Gravity.CENTER_VERTICAL);selection.setPadding(dp(5),dp(2),dp(5),dp(4));GradientDrawable selectorShape=new GradientDrawable();selectorShape.setColor(0xFF102031);selectorShape.setCornerRadius(dp(10));selectorShape.setStroke(dp(1),0xFF2C4051);selection.setBackground(selectorShape);
        applicantPicker=new android.widget.Spinner(this);String[] applicantNames=new String[MonitorService.PROFILE_COUNT];for(int n=0;n<applicantNames.length;n++)applicantNames[n]="Applicant "+(n+1);

        applicantPicker.setAdapter(choices(applicantNames));applicantPicker.setBackgroundTintList(ColorStateList.valueOf(0xFFA5B6C7));applicantPicker.setContentDescription("Choose applicant");selection.addView(applicantPicker,new LinearLayout.LayoutParams(0,dp(40),1));
        applicantPicker.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){public void onNothingSelected(android.widget.AdapterView<?> parent){}public void onItemSelected(android.widget.AdapterView<?> parent,View item,int position,long id){if(engine!=null&&engine.profile!=position+1)selectApplicant(position+1);}});
        activeSummary=new TextView(this);activeSummary.setText("0 of 9 monitoring");activeSummary.setTextSize(11);activeSummary.setTextColor(0xFFA5B6C7);activeSummary.setPadding(dp(10),0,dp(5),0);selection.addView(activeSummary);header.addView(selection);
        for(int group=0;group<2;group++){LinearLayout applicants=new LinearLayout(this);applicantRows[group]=applicants;for(int k=0;k<4;k++){final int n=group*4+k+1;applicantButtons[n-1]=button(applicants,"Applicant "+n,Color.rgb(37,65,103),()->selectApplicant(n));applicantButtons[n-1].setOnLongClickListener(v->{applicantPhone(n);return true;});}header.addView(applicants);}
        LinearLayout utilities=new LinearLayout(this);
        applicantButtons[8]=button(utilities,"Applicant 9",Color.rgb(37,65,103),()->selectApplicant(9));
        applicantButtons[8].setOnLongClickListener(v->{applicantPhone(9);return true;});
        batteryButton=button(utilities,"Background\nsetup",Color.rgb(35,53,76),this::batterySettings);
        button(utilities,"Phone\nnumbers",Color.rgb(14,116,144),this::managePhones);
        header.addView(utilities);
        status = new TextView(this);
        status.setTextSize(12);
        status.setMaxLines(2);status.setTextColor(Color.rgb(157,177,201));
        status.setPadding(dp(4),0,dp(4),dp(3));
        status.setText("Opening Parivahan…");
        monitorStatus=new TextView(this);monitorStatus.setTextSize(12);monitorStatus.setMaxLines(3);
        monitorStatus.setTextColor(Color.rgb(223,234,248));
        monitorStatus.setPadding(dp(4),0,dp(4),dp(5));
        monitorStatus.setText("Connecting monitor… website opens independently");
        monitorStatus.setTextSize(11);monitorStatus.setMaxLines(1);monitorStatus.setTextColor(0xFFA5B6C7);monitorStatus.setPadding(dp(9),0,dp(9),dp(3));header.addView(monitorStatus);header.addView(status);
        utilities.setVisibility(View.GONE);
        view = new GeckoView(this);
        FrameLayout pages=new FrameLayout(this);websitePages=pages;
        pages.addView(view,new FrameLayout.LayoutParams(-1,-1));
        monitorView=new GeckoView(this);monitorView.setVisibility(View.GONE);
        pages.addView(monitorView,new FrameLayout.LayoutParams(-1,-1));
        root.addView(pages,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout controls=new LinearLayout(this);signatureControls=controls;controls.setOrientation(LinearLayout.VERTICAL);controls.setBackgroundColor(0xFF132131);controls.setPadding(0,dp(3),0,dp(3));
        LinearLayout firstRow=new LinearLayout(this),secondRow=new LinearLayout(this);controls.addView(firstRow);controls.addView(secondRow);
        monitorButton=signatureButton(firstRow,"Monitor",0xFFB4F1DA,0xFF092F24,this::toggleMonitor);signatureButtons[0]=monitorButton;
        signatureButtons[1]=signatureButton(firstRow,"Start All",0xFF192B3D,0xFFEDF4FA,()->{if(engine!=null)engine.service.startAllReady();});
        signatureButtons[2]=signatureButton(firstRow,"Silence All",0xFF192B3D,0xFFEDF4FA,()->{if(engine!=null)engine.service.silenceAll();});
        signatureButtons[3]=signatureButton(secondRow,"Stop All",0xFF35212C,0xFFFFACB8,()->{if(engine!=null)engine.service.stopAll();});
        signatureButtons[4]=signatureButton(secondRow,"Home",0xFF192B3D,0xFFEDF4FA,()->{if(engine!=null)engine.home();});
        callButton=signatureButton(secondRow,"Call",0xFF173B32,0xFF80E2BF,()->{if(engine!=null)applicantPhone(engine.profile);});signatureButtons[5]=callButton;
        header.addView(controls,2,new LinearLayout.LayoutParams(-1,-2));
        settingsContext=new LinearLayout(this);settingsContext.setOrientation(LinearLayout.VERTICAL);settingsContext.setVisibility(View.GONE);
        LinearLayout settingsHeading=new LinearLayout(this);signatureButton(settingsHeading,"Back",0xFF192B3D,0xFFEDF4FA,this::closeApplicantSettings);settingsTitle=text("Alerts",15,0xFFB4F1DA);settingsTitle.setGravity(Gravity.CENTER_VERTICAL);settingsHeading.addView(settingsTitle,new LinearLayout.LayoutParams(0,dp(44),3));settingsContext.addView(settingsHeading);
        LinearLayout settingsActions=new LinearLayout(this);signatureButton(settingsActions,"Device options",0xFF192B3D,0xFFEDF4FA,this::manageDeviceAlertOptions);settingsContext.addView(settingsActions);header.addView(settingsContext);
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
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);notificationApplicant=Math.max(1,Math.min(MonitorService.PROFILE_COUNT,intent.getIntExtra("applicant",1)));if(engine!=null&&intent.hasExtra("applicant")){if(!activePanel.equals("monitor"))closeApplicantSettings();selectApplicant(notificationApplicant);}}
    @Override public void onBackPressed(){
      if(!activePanel.equals("monitor")){closeApplicantSettings();return;}
      if(engine!=null&&engine.showingMonitor)toggleMonitor();
      else if(engine!=null&&engine.current!=null&&engine.canGoBack)engine.navigate("Go Back",()->engine.current.goBack());
      else super.onBackPressed();
    }
    @Override protected void onResume(){super.onResume();visible=true;if(engine!=null){engine.service.activity=this;for(BrowserEngine profile:engine.service.profiles)if(profile!=null)profile.run.resumeGraceUntil=SystemClock.elapsedRealtime()+30000;if(!engine.service.visible)for(BrowserEngine e:engine.service.profiles)if(e!=null&&e.run.armed)e.run.tick();engine.service.visible=true;engine.service.record("Activity resumed; "+engine.service.clockDiagnostics());engine.foreground=true;engine.ensureUsable();engine.foregroundState();}}
    @Override protected void onPause(){visible=false;if(engine!=null){engine.service.visible=false;engine.service.record("Activity paused; retaining GeckoView sessions for armed background monitoring; screen="+getSystemService(android.os.PowerManager.class).isInteractive()+"; active="+engine.service.activeCount());engine.foreground=false;engine.activeStates.clear();engine.foregroundState();}super.onPause();}
    @Override protected void onStop(){
      super.onStop();
      if(engine!=null){BrowserEngine e=engine;e.service.handler.post(()->{e.activeStates.clear();e.activate();});}
    }
    @Override protected void onDestroy(){
      if(settingsMenuDialog!=null)settingsMenuDialog.dismiss();if(logsDialog!=null)logsDialog.dismiss();
      view.releaseSession();monitorView.releaseSession();
      if(engine!=null){engine.activeStates.clear();engine.detach(this);if(engine.service.activity==this)engine.service.activity=null;engine.activate();}
      if(bound)unbindService(connection);
      super.onDestroy();
    }
}





