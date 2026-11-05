package com.sarathi.geckotest;

import android.app.AlertDialog;
import android.content.Context;
import android.os.SystemClock;
import android.util.Base64;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONObject;
import org.mozilla.geckoview.GeckoResult;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** Device-local edit lock. Monitoring can keep reading encrypted credentials while locked. */
final class TelegramSettingsLock {
 static final int ITERATIONS=310000;
 static final long UNLOCK_MS=120000L;
 private final Context context;
 private final long[] unlockedUntil=new long[10];
 private int epoch;
 private AlertDialog dialog;
 private final java.util.concurrent.ExecutorService worker=java.util.concurrent.Executors.newSingleThreadExecutor();
 TelegramSettingsLock(Context context){this.context=context;}
 private android.content.SharedPreferences prefs(){return context.getSharedPreferences("telegram-lock",0);}
 boolean configured(){return !prefs().getString("verifier","").isEmpty();}
 boolean unlocked(int profile){return !configured()||(profile>=1&&profile<=9&&SystemClock.elapsedRealtime()<unlockedUntil[profile]);}
 void requireUnlocked(int profile){if(!unlocked(profile))throw new IllegalStateException("Unlock Telegram settings before making changes");}
 void lockAll(){epoch++;Arrays.fill(unlockedUntil,0L);if(dialog!=null)dialog.dismiss();}
 void close(){lockAll();worker.shutdown();}
 JSONObject status(int profile)throws Exception{return new JSONObject().put("configured",configured()).put("unlocked",unlocked(profile)).put("retryAfterMs",retryAfterMs());}
 long retryAfterMs(){return Math.max(0L,Math.min(60000L,prefs().getLong("retryAt",0L)-System.currentTimeMillis()));}
 static String deriveRecord(char[] password)throws Exception{
  if(password.length<10)throw new IllegalArgumentException("Use at least 10 characters");
  byte[] salt=new byte[16];new SecureRandom().nextBytes(salt);
  return Base64.encodeToString(salt,Base64.NO_WRAP)+":"+Base64.encodeToString(hash(password,salt),Base64.NO_WRAP);
 }
 private static byte[] hash(char[] password,byte[] salt)throws Exception{
  PBEKeySpec spec=new PBEKeySpec(password,salt,ITERATIONS,256);
  try{return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();}finally{spec.clearPassword();}
 }
 static boolean matches(char[] password,String record)throws Exception{
  String[] parts=record.split(":",-1);if(parts.length!=2)return false;
  byte[] salt=Base64.decode(parts[0],Base64.NO_WRAP),expected=Base64.decode(parts[1],Base64.NO_WRAP);
  return salt.length==16&&expected.length==32&&MessageDigest.isEqual(expected,hash(password,salt));
 }
 synchronized boolean verify(char[] password)throws Exception{
  if(retryAfterMs()>0)throw new IllegalStateException("Wait before trying again");
  boolean ok=matches(password,prefs().getString("verifier",""));
  int failures=ok?0:Math.min(8,prefs().getInt("failures",0)+1);
  long delay=ok?0:Math.min(60000L,1000L<<(failures-1));
  if(!prefs().edit().putInt("failures",failures).putLong("retryAt",ok?0:System.currentTimeMillis()+delay).commit())throw new IllegalStateException("Could not save lock state");
  return ok;
 }
 GeckoResult<Object> request(MonitorService service,int profile,boolean changePassword){
  GeckoResult<Object> result=new GeckoResult<>();
  if(service.activity==null||!service.visible||service.activity.isFinishing()||dialog!=null){result.complete("{\"ok\":false,\"error\":\"Open the app and close any password dialog first\"}");return result;}
  if(changePassword&&configured()&&!unlocked(profile)){result.complete("{\"ok\":false,\"error\":\"Unlock settings before changing the password\"}");return result;}
  if(retryAfterMs()>0){result.complete("{\"ok\":false,\"error\":\"Wait before trying again\"}");return result;}
  final boolean settingUp=!configured()||changePassword;final int requestEpoch=epoch;
  android.app.Activity activity=service.activity;
  LinearLayout panel=new LinearLayout(activity);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(32,16,32,8);
  TextView hint=new TextView(activity);hint.setText(settingUp?"Protect Telegram edits for all applicants with a device-local password (10+ characters). Keep it safely: it cannot be recovered. Phone numbers and alert modes stay available.":"Unlock edits for Applicant "+profile+" for 2 minutes. Leaving the app or changing applicant locks settings again.");panel.addView(hint);
  EditText first=new EditText(activity);first.setInputType(129);first.setSingleLine(true);first.setHint(settingUp?"New password":"Password");first.setAutofillHints((String[])null);panel.addView(first);
  EditText second=new EditText(activity);second.setInputType(129);second.setSingleLine(true);second.setHint("Repeat password");second.setAutofillHints((String[])null);if(settingUp)panel.addView(second);
  java.util.concurrent.atomic.AtomicBoolean done=new java.util.concurrent.atomic.AtomicBoolean();
  java.util.function.BiConsumer<Boolean,String> finish=(ok,error)->{if(done.compareAndSet(false,true)){JSONObject response=new JSONObject();try{response.put("ok",ok);if(!ok)response.put("error",error);}catch(Exception ignored){}result.complete(response.toString());}};
  AlertDialog prompt=new AlertDialog.Builder(activity,android.R.style.Theme_Material_Dialog_Alert).setTitle(settingUp?"Set Telegram password":"Unlock Telegram settings").setView(panel).setPositiveButton(settingUp?"Save password":"Unlock",null).setNegativeButton("Cancel",null).create();
  dialog=prompt;
  prompt.setOnDismissListener(d->{first.setText("");second.setText("");if(dialog==prompt)dialog=null;finish.accept(false,"Password dialog closed");});
  prompt.show();prompt.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
  prompt.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
   char[] password=first.getText().toString().toCharArray(),confirmation=second.getText().toString().toCharArray();
   if(settingUp&&(password.length<10||!Arrays.equals(password,confirmation))){Arrays.fill(password,'\0');Arrays.fill(confirmation,'\0');hint.setText("Use at least 10 characters and enter the same password twice.");return;}
   Arrays.fill(confirmation,'\0');first.setText("");second.setText("");prompt.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
   worker.execute(()->{
    String error="Password was not accepted";String record=null;boolean accepted=false;
    try{if(settingUp){record=deriveRecord(password);accepted=true;}else accepted=verify(password);}catch(Exception ex){error="Could not unlock settings. Wait and try again.";}finally{Arrays.fill(password,'\0');}
    final String saved=record,reason=error;final boolean verified=accepted;
    service.handler.post(()->{
     if(done.get()||requestEpoch!=epoch||!service.visible||service.activity!=activity){finish.accept(false,"App left foreground; settings remain locked");prompt.dismiss();return;}
     if(verified){
      if(settingUp&&!prefs().edit().putString("verifier",saved).putInt("failures",0).putLong("retryAt",0).commit()){finish.accept(false,"Could not save password");prompt.dismiss();return;}
      Arrays.fill(unlockedUntil,0L);unlockedUntil[profile]=SystemClock.elapsedRealtime()+UNLOCK_MS;
      finish.accept(true,"");prompt.dismiss();
     }else{finish.accept(false,reason+"; wait before trying again");prompt.dismiss();}
    });
   });
  });
  return result;
 }
}
