package com.sarathi.geckotest;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class TelegramSettingsLockTest {
 @Test public void saltedVerifierRejectsWrongPasswordWithoutStoringPlaintext()throws Exception{
  char[] password="test-passphrase-391".toCharArray();
  try{
   String first=TelegramSettingsLock.deriveRecord(password),second=TelegramSettingsLock.deriveRecord(password);
   assertNotEquals("Each password save needs a fresh salt",first,second);
   assertFalse(first.contains(new String(password)));
   assertTrue(TelegramSettingsLock.matches(password,first));
   assertFalse(TelegramSettingsLock.matches("wrong-password".toCharArray(),first));
  }finally{java.util.Arrays.fill(password,'\0');}
 }
 @Test public void editingAuthorizationIsPerApplicantExpiresAndRelocks()throws Exception{
  Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
  SharedPreferences prefs=context.getSharedPreferences("telegram-lock",0);String before=prefs.getString("verifier","");
  TelegramSettingsLock lock=new TelegramSettingsLock(context);
  try{
   prefs.edit().putString("verifier",TelegramSettingsLock.deriveRecord("test-passphrase-391".toCharArray())).commit();
   assertFalse(lock.unlocked(1));
   java.lang.reflect.Field field=TelegramSettingsLock.class.getDeclaredField("unlockedUntil");field.setAccessible(true);
   long[] grants=(long[])field.get(lock);grants[1]=SystemClock.elapsedRealtime()+TelegramSettingsLock.UNLOCK_MS;
   lock.requireUnlocked(1);assertFalse("Unlocking A1 must not unlock A2",lock.unlocked(2));
   try{lock.requireUnlocked(2);fail("Locked Telegram mutation must be refused");}catch(IllegalStateException expected){}
   grants[1]=SystemClock.elapsedRealtime()-1;assertFalse("Expired grant must be refused",lock.unlocked(1));
   grants[1]=SystemClock.elapsedRealtime()+TelegramSettingsLock.UNLOCK_MS;
   lock.lockAll();assertFalse("Leaving context must revoke the grant",lock.unlocked(1));
   assertFalse("A new service lock must not inherit old authorization",new TelegramSettingsLock(context).unlocked(1));
  }finally{prefs.edit().putString("verifier",before).commit();lock.close();}
 }
 @Test public void failedPasswordRetryDelaySurvivesLockRecreation()throws Exception{
  Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
  SharedPreferences prefs=context.getSharedPreferences("telegram-lock",0);
  String before=prefs.getString("verifier","");int failures=prefs.getInt("failures",0);long retry=prefs.getLong("retryAt",0);
  TelegramSettingsLock lock=new TelegramSettingsLock(context),recreated=null;
  try{
   prefs.edit().putString("verifier",TelegramSettingsLock.deriveRecord("test-passphrase-391".toCharArray())).putInt("failures",0).putLong("retryAt",0).commit();
   assertFalse(lock.verify("wrong-password".toCharArray()));
   recreated=new TelegramSettingsLock(context);assertTrue("Retry delay must persist across recreation",recreated.retryAfterMs()>0);
   try{recreated.verify("test-passphrase-391".toCharArray());fail("Rate limit must also block a correct early retry");}catch(IllegalStateException expected){}
   prefs.edit().putLong("retryAt",0).commit();assertTrue(recreated.verify("test-passphrase-391".toCharArray()));
   assertEquals(0,prefs.getInt("failures",-1));assertFalse("Verification alone must not grant UI authorization",recreated.unlocked(1));
  }finally{prefs.edit().putString("verifier",before).putInt("failures",failures).putLong("retryAt",retry).commit();lock.close();if(recreated!=null)recreated.close();}
 }
}
