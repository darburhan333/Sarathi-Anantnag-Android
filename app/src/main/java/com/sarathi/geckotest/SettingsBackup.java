package com.sarathi.geckotest;

import android.content.Context;
import android.util.Base64;
import org.json.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import javax.crypto.*;
import javax.crypto.spec.*;

/** Portable settings only. Never exports cookies, booking state, or Android Keystore keys. */
final class SettingsBackup {
 static final int ITERATIONS=310000,MAX_BYTES=262144;
 static final String[] OPTION_KEYS={"covSelection","assistedBookingPreference","telegramEnabled","telegramStatusEnabled","soundEnabled","desktopEnabled","volume"};
 static JSONObject options(Context c,int n)throws Exception{return new JSONObject(c.getSharedPreferences("profile-options",0).getString("options"+n,"{}"));}
 static JSONObject filter(JSONObject raw)throws Exception{JSONObject out=new JSONObject();for(String key:OPTION_KEYS)if(raw.has(key))out.put(key,raw.get(key));return out;}
 static void saveOptions(Context c,int n,JSONObject raw)throws Exception{JSONObject current=options(c,n),filtered=filter(raw);for(String key:OPTION_KEYS)if(filtered.has(key))current.put(key,filtered.get(key));if(!c.getSharedPreferences("profile-options",0).edit().putString("options"+n,current.toString()).commit())throw new IllegalStateException("Settings write failed");}
 static JSONObject snapshot(Context c)throws Exception{
  JSONArray profiles=new JSONArray();for(int n=1;n<=9;n++)profiles.put(new JSONObject().put("applicant",n).put("telegram",SecureSettings.read(c,n)).put("phone",ApplicantContacts.read(c,n)).put("mode",c.getSharedPreferences("monitor",0).getString("alertMode"+n,"CRITICAL ONLY")).put("options",options(c,n)));
  return new JSONObject().put("schema",1).put("profiles",profiles);
 }
 static void validate(JSONObject data)throws Exception{
  if(data.getInt("schema")!=1||data.getJSONArray("profiles").length()!=9)throw new IllegalArgumentException("Unsupported settings file");
  boolean[] seen=new boolean[10];for(int i=0;i<9;i++){JSONObject p=data.getJSONArray("profiles").getJSONObject(i);int n=p.getInt("applicant");if(n<1||n>9||seen[n])throw new IllegalArgumentException("Invalid applicant list");seen[n]=true;
   if(!java.util.Arrays.asList(PhoneAlerts.MODES).contains(p.getString("mode")))throw new IllegalArgumentException("Invalid alert mode");
   String phone=p.getString("phone");if(!phone.isEmpty())ApplicantContacts.normalize(phone);
   JSONObject tg=p.getJSONObject("telegram");for(String k:new String[]{"botToken","chatId","chatId2"})if(tg.optString(k).length()>256)throw new IllegalArgumentException("Invalid Telegram setting");
   JSONObject opts=p.getJSONObject("options");if(opts.has("volume")&&(opts.getInt("volume")<0||opts.getInt("volume")>100))throw new IllegalArgumentException("Invalid volume");
   if(opts.has("covSelection")){JSONArray cov=opts.getJSONArray("covSelection");if(cov.length()>5)throw new IllegalArgumentException("Invalid COV list");for(int k=0;k<cov.length();k++)if(!java.util.Arrays.asList("LMV","MCWG","MCWOG","HMV","TRANS").contains(cov.getString(k)))throw new IllegalArgumentException("Unknown COV");}
  }
 }
 static void apply(Context c,JSONObject data)throws Exception{validate(data);JSONObject before=snapshot(c);try{write(c,data);}catch(Exception e){try{write(c,before);}catch(Exception ignored){}throw new IllegalStateException("Import failed; inspect settings before starting",e);}}
 private static void write(Context c,JSONObject data)throws Exception{for(int i=0;i<9;i++){JSONObject p=data.getJSONArray("profiles").getJSONObject(i);int n=p.getInt("applicant");JSONObject tg=p.getJSONObject("telegram");JSONObject clean=new JSONObject();for(String key:new String[]{"botToken","chatId","chatId2"})clean.put(key,tg.optString(key));SecureSettings.write(c,n,clean);if(p.getString("phone").isEmpty())ApplicantContacts.remove(c,n);else ApplicantContacts.save(c,n,p.getString("phone"));c.getSharedPreferences("monitor",0).edit().putString("alertMode"+n,p.getString("mode")).commit();saveOptions(c,n,p.getJSONObject("options"));}}
 static byte[] encrypt(JSONObject plain,char[] password)throws Exception{
  if(password.length<10)throw new IllegalArgumentException("Use a passphrase of at least 10 characters");
  byte[] salt=new byte[16],iv=new byte[12];new SecureRandom().nextBytes(salt);new SecureRandom().nextBytes(iv);
  Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,derive(password,salt),new GCMParameterSpec(128,iv));c.updateAAD("MParivahan-settings-v1".getBytes(StandardCharsets.UTF_8));
  JSONObject envelope=new JSONObject().put("format","MParivahan-settings-v1").put("iterations",ITERATIONS).put("salt",b64(salt)).put("iv",b64(iv)).put("data",b64(c.doFinal(plain.toString().getBytes(StandardCharsets.UTF_8))));return envelope.toString().getBytes(StandardCharsets.UTF_8);
 }
 static JSONObject decrypt(byte[] file,char[] password)throws Exception{
  if(file.length>MAX_BYTES)throw new IllegalArgumentException("Settings file too large");JSONObject e=new JSONObject(new String(file,StandardCharsets.UTF_8));if(!"MParivahan-settings-v1".equals(e.getString("format"))||e.getInt("iterations")!=ITERATIONS)throw new IllegalArgumentException("Unsupported encrypted file");
  byte[] salt=Base64.decode(e.getString("salt"),Base64.NO_WRAP),iv=Base64.decode(e.getString("iv"),Base64.NO_WRAP);if(salt.length!=16||iv.length!=12)throw new IllegalArgumentException("Invalid encrypted file");
  Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,derive(password,salt),new GCMParameterSpec(128,iv));c.updateAAD("MParivahan-settings-v1".getBytes(StandardCharsets.UTF_8));JSONObject result=new JSONObject(new String(c.doFinal(Base64.decode(e.getString("data"),Base64.NO_WRAP)),StandardCharsets.UTF_8));validate(result);return result;
 }
 private static javax.crypto.SecretKey derive(char[] password,byte[] salt)throws Exception{PBEKeySpec spec=new PBEKeySpec(password,salt,ITERATIONS,256);try{return new SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(),"AES");}finally{spec.clearPassword();}}
 private static String b64(byte[] b){return Base64.encodeToString(b,Base64.NO_WRAP);}
}
