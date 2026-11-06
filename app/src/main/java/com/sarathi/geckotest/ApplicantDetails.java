package com.sarathi.geckotest;

import android.content.Context;
import android.security.keystore.*;
import android.util.Base64;
import java.security.KeyStore;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;

/** Native encrypted storage. Applicant values never enter diagnostics or extension storage. */
final class ApplicantDetails {
 private static final String ALIAS="mparivahan-applicant-details";
 static final class Record {
  final String applicationNumber,dob;
  Record(String number,String birth){applicationNumber=number;dob=birth;}
  boolean saved(){return !applicationNumber.isEmpty()&&!dob.isEmpty();}
 }
 static String normalizeNumber(String value){
  String number=value.trim();
  if(!number.matches("[0-9]{1,25}"))throw new IllegalArgumentException("Enter an application number using digits only (up to 25).");
  return number;
 }
 static String normalizeDob(String value){
  String birth=value.trim();
  if(!birth.matches("[0-9]{2}-[0-9]{2}-[0-9]{4}"))throw new IllegalArgumentException("Use DD-MM-YYYY for date of birth.");
  try {
   LocalDate date=LocalDate.parse(birth,DateTimeFormatter.ofPattern("dd-MM-uuuu").withResolverStyle(ResolverStyle.STRICT));
   if(date.getYear()<1900||date.isAfter(LocalDate.now()))throw new IllegalArgumentException();
  }catch(Exception ex){throw new IllegalArgumentException("Enter a valid date of birth in DD-MM-YYYY format.");}
  return birth;
 }
 private static String entry(int applicant){if(applicant<1||applicant>9)throw new IllegalArgumentException("Invalid applicant");return "applicant"+applicant;}
 private static javax.crypto.SecretKey key()throws Exception{
  KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
  if(!store.containsAlias(ALIAS)){KeyGenerator gen=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");gen.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());gen.generateKey();}
  return (javax.crypto.SecretKey)store.getKey(ALIAS,null);
 }
 static synchronized Record read(Context context,int applicant)throws Exception{
  String saved=context.getSharedPreferences("applicant-details",0).getString(entry(applicant),"");
  if(saved.isEmpty())return new Record("","");
  String[] parts=saved.split(":",2);Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(parts[0],Base64.NO_WRAP)));
  JSONObject data=new JSONObject(new String(cipher.doFinal(Base64.decode(parts[1],Base64.NO_WRAP)),java.nio.charset.StandardCharsets.UTF_8));
  return new Record(normalizeNumber(data.getString("applicationNumber")),normalizeDob(data.getString("dob")));
 }
 static synchronized void save(Context context,int applicant,String number,String birth)throws Exception{
  JSONObject data=new JSONObject().put("applicationNumber",normalizeNumber(number)).put("dob",normalizeDob(birth));
  Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());
  String saved=Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP)+":"+Base64.encodeToString(cipher.doFinal(data.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)),Base64.NO_WRAP);
  if(!context.getSharedPreferences("applicant-details",0).edit().putString(entry(applicant),saved).commit())throw new IllegalStateException("Could not save applicant details.");
 }
 static synchronized void remove(Context context,int applicant){if(!context.getSharedPreferences("applicant-details",0).edit().remove(entry(applicant)).commit())throw new IllegalStateException("Could not remove applicant details.");}
}
