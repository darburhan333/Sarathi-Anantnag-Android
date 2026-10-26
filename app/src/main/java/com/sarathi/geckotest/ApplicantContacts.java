package com.sarathi.geckotest;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Native-only per-applicant phone numbers. Never exposed to website/extension/logs. */
final class ApplicantContacts {
 private static final String ALIAS="mparivahan-applicant-phones";
 static String normalize(String input){
  String value=input.trim().replaceAll("[\\s()\\-]","");
  if(!value.matches("\\+?[0-9]{7,15}"))throw new IllegalArgumentException("Enter 7–15 digits, optionally beginning with +. Do not enter extensions or dial codes.");
  return value;
 }
 private static String entry(int profile){
  if(profile<1||profile>MonitorService.PROFILE_COUNT)throw new IllegalArgumentException("Invalid applicant");
  return "phone"+profile;
 }
 private static SecretKey key()throws Exception{
  KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
  if(!store.containsAlias(ALIAS)){
   KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
   generator.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());generator.generateKey();
  }
  return (SecretKey)store.getKey(ALIAS,null);
 }
 static synchronized String read(Context context,int profile)throws Exception{
  String saved=context.getSharedPreferences("applicant-contacts",0).getString(entry(profile),"");if(saved.isEmpty())return "";
  String[] parts=saved.split(":",2);Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
  cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(parts[0],Base64.NO_WRAP)));
  return new String(cipher.doFinal(Base64.decode(parts[1],Base64.NO_WRAP)),java.nio.charset.StandardCharsets.UTF_8);
 }
 static synchronized void save(Context context,int profile,String input)throws Exception{
  String value=normalize(input);Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());
  String saved=Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP)+":"+Base64.encodeToString(cipher.doFinal(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)),Base64.NO_WRAP);
  if(!context.getSharedPreferences("applicant-contacts",0).edit().putString(entry(profile),saved).commit())throw new IllegalStateException("Could not save phone number");
 }
 static synchronized void remove(Context context,int profile){
  if(!context.getSharedPreferences("applicant-contacts",0).edit().remove(entry(profile)).commit())throw new IllegalStateException("Could not remove phone number");
 }
}
