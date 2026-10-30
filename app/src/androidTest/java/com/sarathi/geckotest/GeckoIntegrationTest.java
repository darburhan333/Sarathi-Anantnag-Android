package com.sarathi.geckotest;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.ActivityTestRule;
import androidx.test.rule.GrantPermissionRule;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import java.net.*;
import java.io.*;
import static org.junit.Assert.*;
@RunWith(AndroidJUnit4.class)
public class GeckoIntegrationTest {
 @Rule public GrantPermissionRule permissions=GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS);
 @Rule public ActivityTestRule<MainActivity> activity=new ActivityTestRule<>(MainActivity.class);
 private final java.util.concurrent.atomic.AtomicInteger requests=new java.util.concurrent.atomic.AtomicInteger();
 private void shell(String command)throws Exception{
  try(android.os.ParcelFileDescriptor fd=InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(command);
      InputStream in=new android.os.ParcelFileDescriptor.AutoCloseInputStream(fd)){while(in.read()!=-1){}}
 }
 private void main(Runnable r){InstrumentationRegistry.getInstrumentation().runOnMainSync(r);}
 private String shellText(String command)throws Exception{
  try(android.os.ParcelFileDescriptor fd=InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(command);
      InputStream in=new android.os.ParcelFileDescriptor.AutoCloseInputStream(fd);
      ByteArrayOutputStream out=new ByteArrayOutputStream()){
   byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);
   return out.toString("UTF-8");
  }
 }
 private void captureEvidence(MonitorService s,String label)throws Exception{
  System.out.println("EVIDENCE_BEGIN "+label);
  for(String command:new String[]{
   "dumpsys activity services com.sarathi.anantnag.gecko.v33",
   "dumpsys power",
   "dumpsys deviceidle",
   "dumpsys battery",
   "dumpsys notification --noredact",
   "logcat -d -t 1200"
  })try{System.out.println("COMMAND "+command+"\\n"+shellText(command));}catch(Exception e){System.out.println("EVIDENCE_COMMAND_FAILED "+command+" "+e);}
  try(java.io.FileOutputStream out=InstrumentationRegistry.getInstrumentation().getTargetContext().openFileOutput("monitor-copy-log.txt",android.content.Context.MODE_PRIVATE)){
   out.write(s.diagnosticReport().getBytes("UTF-8"));
  }
  System.out.println("MONITOR_COPY_LOG\\n"+s.diagnosticReport());
  System.out.println("EVIDENCE_END "+label);
 }
 private void progress(MonitorService s,String label,int seconds)throws Exception{
  int before=s.attemptsForTest(),beforeRequests=requests.get();Thread.sleep(seconds*1000L);
  if(!s.isArmedForTest())captureEvidence(s,label+" foreground service stopped");
  assertTrue(label+": foreground service stopped; see captured dumpsys, logcat and monitor-copy-log.txt",s.isArmedForTest());
  assertTrue(label+": no actual COV attempts: "+s.engine.lastState,s.attemptsForTest()>before);
  assertTrue(label+": page did not make another HTTP request",requests.get()>beforeRequests);
  System.out.println("PASS "+label+": "+before+" -> "+s.attemptsForTest());
 }
 @Test public void backgroundStallRecoversWithoutClosingPages()throws Exception{
  MainActivity a=activity.getActivity();long end=System.currentTimeMillis()+60000;
  while(a.serviceForTest()==null&&System.currentTimeMillis()<end)Thread.sleep(200);
  MonitorService service=a.serviceForTest();assertNotNull(service);
  shell("dumpsys deviceidle whitelist +com.sarathi.anantnag.gecko.v33");
  java.util.concurrent.CountDownLatch completed=new java.util.concurrent.CountDownLatch(2);
  java.util.concurrent.atomic.AtomicInteger cancelled=new java.util.concurrent.atomic.AtomicInteger();
  main(()->{
   service.stopAll();BrowserEngine one=service.profile(1),two=service.profile(2);
   one.run.arm();two.run.arm();Object page=one.current;
   for(BrowserEngine e:new BrowserEngine[]{one,two})e.run.waitFor(8000).accept(r->{if(r.toString().contains("\"cancelled\":true"))cancelled.incrementAndGet();completed.countDown();});
   service.visible=false;
   try{
    one.run.recover("Main-thread tick gap 45000ms");
    two.run.recover("Main-thread tick gap 45000ms");
    assertEquals("Minimizing must not stop active applicants",2,service.activeCount());
    assertSame("Keep the existing page",page,one.current);
    assertTrue("Applicant 1 should enter verification",one.run.recovering);
    assertTrue("Applicant 2 should enter verification",two.run.recovering);
    assertFalse(one.alerts.isRepeating());assertFalse(two.alerts.isRepeating());
    service.stopAll();
    assertEquals(0,service.activeCount());
   }finally{service.visible=true;}
  });
  assertTrue("Both wait callbacks must arrive",completed.await(5,java.util.concurrent.TimeUnit.SECONDS));
  assertEquals("Explicit STOP must cancel every pending native wait",2,cancelled.get());
 }
 @Test public void backgroundRecoveryRetriesAndIsolatesFailure()throws Exception{
  MainActivity a=activity.getActivity();long end=System.currentTimeMillis()+60000;
  while(a.serviceForTest()==null&&System.currentTimeMillis()<end)Thread.sleep(200);
  MonitorService service=a.serviceForTest();assertNotNull(service);
  shell("dumpsys deviceidle whitelist +com.sarathi.anantnag.gecko.v33");
  java.lang.reflect.Field id=MonitorRun.class.getDeclaredField("recoveryId");id.setAccessible(true);
  java.lang.reflect.Field retry=MonitorRun.class.getDeclaredField("recoveryRetryAt");retry.setAccessible(true);
  main(()->{
   service.stopAll();BrowserEngine one=service.profile(1),two=service.profile(2);
   one.run.arm();two.run.arm();service.visible=false;
   try{
    one.run.recover("Injected screen-off stall");
    for(int i=0;i<4;i++){
     String attempt=(String)id.get(one.run);
     one.run.recovered(attempt,false,false,"Page reply timed out");
     if(i<3){assertTrue("Transient verification failure must retain run",one.run.armed);assertFalse(one.run.authorize());assertTrue(two.run.armed);retry.setLong(one.run,1);one.run.tick();}
    }
    assertFalse("Exhausted recovery must revoke affected applicant",one.run.armed);
    assertTrue("Other applicants must continue",two.run.armed);
    assertTrue("Failure must alarm while minimized",one.alerts.isRepeating());
   }catch(IllegalAccessException e){throw new AssertionError(e);}finally{service.visible=true;service.stopAll();}
  });
 }

 @Test public void realExtensionLoop() throws Exception {
  ServerSocket server=new ServerSocket(8765);
  Thread thread=new Thread(()->{while(!server.isClosed())try(Socket socket=server.accept()){
   socket.setSoTimeout(1000);
   BufferedReader reader=new BufferedReader(new InputStreamReader(socket.getInputStream()));
   String line=reader.readLine();if(line!=null&&line.startsWith("GET /attempt?"))requests.incrementAndGet();
   while((line=reader.readLine())!=null&&!line.isEmpty()){}
   byte[] body="<!doctype html><html><body>Gecko test fixture</body></html>".getBytes("UTF-8");
   OutputStream out=socket.getOutputStream();out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));out.write(body);out.flush();
  }catch(IOException ignored){}});thread.setDaemon(true);thread.start();
  try{
   MainActivity a=activity.getActivity();
   long end=System.currentTimeMillis()+60000;
   while(!a.extensionReadyForTest()&&System.currentTimeMillis()<end)Thread.sleep(300);
   assertTrue("Built-in extension did not connect",a.extensionReadyForTest());
   MonitorService s=a.serviceForTest();
   shell("dumpsys deviceidle whitelist +com.sarathi.anantnag.gecko.v33");
   main(a::runSmokeForTest);
   end=System.currentTimeMillis()+30000;
   while(!s.engine.smokePhase.equals("background-ready")&&System.currentTimeMillis()<end)Thread.sleep(200);
   assertEquals("background-ready",s.engine.smokePhase);
   Thread.sleep(750); // Let the new run snapshot cross the asynchronous native port before sampling counters.
   main(()->s.engine.run.recover("Injected delay on live COV fixture"));
   end=System.currentTimeMillis()+10000;
   while(s.engine.run.recovering&&System.currentTimeMillis()<end)Thread.sleep(100);
   assertTrue("Verified COV recovery must preserve the run",s.engine.run.armed);
   assertFalse("Verified COV recovery must finish",s.engine.run.recovering);
   shell("input keyevent KEYCODE_HOME");// Screen remains ON. Exercise the reported 50–60s boundary repeatedly.
   for(int minute=0;minute<10;minute++)progress(s,"minimized screen-on minute "+minute,60);
   shell("input keyevent KEYCODE_SLEEP");progress(s,"screen locked for 10 minutes",600);
   shell("dumpsys battery unplug");shell("dumpsys deviceidle force-idle");progress(s,"forced Doze with exemption",22);
   shell("dumpsys deviceidle unforce");shell("dumpsys battery reset");
   main(a::finish);progress(s,"Activity destroyed; session retained",30);
   shell("input keyevent KEYCODE_WAKEUP");shell("wm dismiss-keyguard");
   android.content.Context ctx=InstrumentationRegistry.getInstrumentation().getTargetContext();
   MainActivity reopened=(MainActivity)InstrumentationRegistry.getInstrumentation().startActivitySync(new android.content.Intent(ctx,MainActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
   end=System.currentTimeMillis()+10000;
   while(reopened.serviceForTest()==null&&System.currentTimeMillis()<end)Thread.sleep(200);
   assertSame("Must reuse same service and browser",s,reopened.serviceForTest());
   end=System.currentTimeMillis()+100000;
   while(s.engine.smokeResult.isEmpty()&&System.currentTimeMillis()<end)Thread.sleep(500);
   assertTrue(s.engine.smokeResult,s.engine.smokeResult.startsWith("PASS:"));
   main(reopened::openMonitorForTest);
   end=System.currentTimeMillis()+20000;
   while(!reopened.monitorReadyForTest()&&System.currentTimeMillis()<end)Thread.sleep(300);
   assertTrue("Monitor UI failed to initialize",reopened.monitorReadyForTest());
   int colors=0;end=System.currentTimeMillis()+10000;
   while(colors<30&&System.currentTimeMillis()<end){
    Thread.sleep(500);
    android.graphics.Bitmap bmp=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
    java.util.Set<Integer> pixels=new java.util.HashSet<>();
    for(int y=bmp.getHeight()/2;y<bmp.getHeight()*4/5;y+=4)
     for(int x=bmp.getWidth()/10;x<bmp.getWidth()*9/10;x+=4)pixels.add(bmp.getPixel(x,y));
    colors=pixels.size();bmp.recycle();
   }
   shell("screencap -p /data/local/tmp/monitor-preview.png");
   assertTrue("Monitor viewport remained blank after re-attachment; colors="+colors,colors>=30);
   assertFalse("STOP must release foreground mode",s.isArmedForTest());
   java.util.concurrent.atomic.AtomicBoolean cancelled=new java.util.concurrent.atomic.AtomicBoolean();
   main(()->{s.arm();s.waitFor(8000).accept(result->cancelled.set(result.toString().contains("cancelled")));});
   ctx.startService(new android.content.Intent(ctx,MonitorService.class).setAction("STOP"));
   Thread.sleep(1000);
   assertFalse("Notification STOP action must stop service",s.isArmedForTest());
   assertTrue("STOP must cancel pending native wait",cancelled.get());
  }finally{
   shell("dumpsys deviceidle unforce");shell("dumpsys battery reset");shell("input keyevent KEYCODE_WAKEUP");
   server.close();
  }
 }
 @Test public void nineApplicantIsolationAndBackground()throws Exception{
  ServerSocket server=new ServerSocket(8765);java.util.concurrent.atomic.AtomicIntegerArray counts=new java.util.concurrent.atomic.AtomicIntegerArray(9);
  Thread thread=new Thread(()->{while(!server.isClosed())try(Socket socket=server.accept()){
   socket.setSoTimeout(1000);BufferedReader reader=new BufferedReader(new InputStreamReader(socket.getInputStream()));String line=reader.readLine();
   if(line!=null)for(int i=0;i<9;i++)if(line.contains("/attempt?profile="+(i+1)+"&"))counts.incrementAndGet(i);
   while((line=reader.readLine())!=null&&!line.isEmpty()){}
   byte[] body="<!doctype html><html><body>Profile test</body></html>".getBytes("UTF-8");OutputStream out=socket.getOutputStream();out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));out.write(body);out.flush();
  }catch(IOException ignored){}});thread.setDaemon(true);thread.start();
  try{
   MainActivity a=activity.getActivity();long end=System.currentTimeMillis()+60000;
   while(a.serviceForTest()==null&&System.currentTimeMillis()<end)Thread.sleep(200);MonitorService service=a.serviceForTest();assertNotNull(service);
   shell("dumpsys deviceidle whitelist +com.sarathi.anantnag.gecko.v33");
   main(service::stopAll);
   // Follow the real user flow: open and start one applicant at a time. All nine
   // still run concurrently for every locked-screen and Doze assertion below.
   for(int n=1;n<=9;n++){
    final int number=n;main(()->a.selectApplicant(number));BrowserEngine e=service.profiles[n-1];
    end=System.currentTimeMillis()+60000;while(!e.ready&&System.currentTimeMillis()<end)Thread.sleep(200);
    assertTrue("Extension ready "+n,e.ready);
    main(()->{e.smokeResult="";e.command("MULTI_SMOKE");});end=System.currentTimeMillis()+45000;
    while(e.smokeResult.isEmpty()&&System.currentTimeMillis()<end)Thread.sleep(250);
    assertTrue("Applicant "+n+": "+e.smokeResult,e.smokeResult.startsWith("PASS:"));
    System.out.println("Applicant "+n+" "+e.smokeResult);
   }
   main(()->{for(BrowserEngine e:service.profiles){e.smokeResult="";e.command("AUDIT_SMOKE");}});
   end=System.currentTimeMillis()+45000;
   while(System.currentTimeMillis()<end&&java.util.Arrays.stream(service.profiles).anyMatch(e->e.smokeResult.isEmpty()))Thread.sleep(250);
   for(BrowserEngine e:service.profiles)assertTrue("Concurrent isolation "+e.profile+": "+e.smokeResult,e.smokeResult.startsWith("PASS:"));
   int[] before=new int[9];for(int i=0;i<9;i++)before[i]=counts.get(i);shell("input keyevent KEYCODE_HOME");shell("input keyevent KEYCODE_SLEEP");
   // Reproduce GeckoView deactivation after detachment, without changing our cached state.
   main(()->{for(BrowserEngine e:service.profiles)for(org.mozilla.geckoview.GeckoSession page:e.sessions)if(page!=e.monitorSession&&page.isOpen())page.setActive(false);});
   for(int minute=1;minute<=5;minute++){
    Thread.sleep(60000);
    for(int i=0;i<9;i++){assertTrue("Locked HTTP applicant "+i+" minute "+minute,counts.get(i)>before[i]);assertTrue("Run alive applicant "+i,service.profiles[i].run.armed);before[i]=counts.get(i);}
    System.out.println("PASS all nine applicants locked, minute "+minute+": "+counts);
   }
   shell("dumpsys battery unplug");shell("dumpsys deviceidle force-idle");Thread.sleep(120000);
   for(int i=0;i<9;i++)assertTrue("Doze HTTP applicant "+i,counts.get(i)>before[i]);
   main(()->service.profiles[1].run.stopByUser());Thread.sleep(1500);int stopped=counts.get(1),first=counts.get(0),third=counts.get(2);Thread.sleep(22000);
   assertEquals("Applicant 2 must stop",stopped,counts.get(1));assertTrue("Applicant 1 continues",counts.get(0)>first);assertTrue("Applicant 3 continues",counts.get(2)>third);
   shell("dumpsys deviceidle unforce");shell("dumpsys battery reset");shell("input keyevent KEYCODE_WAKEUP");shell("wm dismiss-keyguard");shell("am start -n com.sarathi.anantnag.gecko.v33/com.sarathi.geckotest.MainActivity");Thread.sleep(1500);
   main(()->{a.selectApplicant(3);a.selectApplicant(1);service.stopAll();});assertEquals("STOP ALL",0,service.activeCount());
   System.out.println("PASS nine applicants: storage isolation, native ownership, locked and Doze HTTP, independent STOP, switching, STOP ALL");
  }finally{main(()->{MonitorService owner=activity.getActivity().serviceForTest();if(owner!=null)owner.stopAll();});shell("dumpsys deviceidle unforce");shell("dumpsys battery reset");shell("input keyevent KEYCODE_WAKEUP");server.close();}
 }

 @Test public void encryptedSettingsRoundTrip()throws Exception{
  android.content.Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();
  org.json.JSONObject original=SecureSettings.read(c,1);
  String marker="TEST_ONLY_NOT_A_TELEGRAM_TOKEN";
  try{
   SecureSettings.write(c,1,new org.json.JSONObject().put("botToken",marker).put("chatId","12345").put("chatId2","67890"));
   assertEquals(marker,SecureSettings.read(c,1).getString("botToken"));
   String persisted=c.getSharedPreferences("secure",0).getString("config-profile-1","");
   assertFalse("Token must not be persisted as plaintext",persisted.contains(marker));
   assertFalse("Chat IDs must not be persisted as plaintext",persisted.contains("12345"));
   System.out.println("PASS Android Keystore encrypted credential round-trip");
  }finally{for(String k:new String[]{"botToken","chatId","chatId2"})if(!original.has(k))original.put(k,"");SecureSettings.write(c,1,original);}
 }

 @Test public void applicantPhonesAreSeparateAndEncrypted()throws Exception{
  android.content.Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();
  String[] original=new String[9];for(int n=1;n<=9;n++)original[n-1]=ApplicantContacts.read(c,n);
  try{
   for(int n=1;n<=9;n++)ApplicantContacts.save(c,n,"+1 (202) 555-010"+n);
   for(int n=1;n<=9;n++){
    assertEquals("+1202555010"+n,ApplicantContacts.read(c,n));
    assertFalse(c.getSharedPreferences("applicant-contacts",0).getString("phone"+n,"").contains("1202555010"));
   }
   ApplicantContacts.remove(c,2);assertEquals("",ApplicantContacts.read(c,2));assertEquals("+12025550101",ApplicantContacts.read(c,1));
   for(String bad:new String[]{"","123","*123#","1234567,99","tel:1234567890","1234567890123456"}){
    try{ApplicantContacts.normalize(bad);fail("Unsafe or invalid number accepted");}catch(IllegalArgumentException expected){}
   }
  }finally{for(int n=1;n<=9;n++){if(original[n-1].isEmpty())ApplicantContacts.remove(c,n);else ApplicantContacts.save(c,n,original[n-1]);}}
 }

 @Test public void stopCancelsEveryConcurrentWaitOnce()throws Exception{
  MainActivity a=activity.getActivity();long deadline=System.currentTimeMillis()+60000;
  while(!a.extensionReadyForTest()&&System.currentTimeMillis()<deadline)Thread.sleep(200);
  assertTrue(a.extensionReadyForTest());MonitorService service=a.serviceForTest();
  shell("dumpsys deviceidle whitelist +com.sarathi.anantnag.gecko.v33");
  java.util.concurrent.atomic.AtomicInteger completions=new java.util.concurrent.atomic.AtomicInteger();
  java.util.concurrent.atomic.AtomicInteger cancelled=new java.util.concurrent.atomic.AtomicInteger();
  main(()->{service.arm();for(int i=0;i<20;i++)service.waitFor(200).accept(value->{completions.incrementAndGet();if(value.toString().contains("cancelled"))cancelled.incrementAndGet();});service.engine.run.disarm();service.engine.run.disarm();});
  Thread.sleep(1000);
  assertEquals("Each pending result completes once",20,completions.get());assertEquals("All waits cancelled",20,cancelled.get());
 }

 @Test public void idlePageLossRecoversWithoutAlarmAndHomeWorksWithoutBridge()throws Exception{
  MainActivity a=activity.getActivity();long end=System.currentTimeMillis()+60000;
  while(!a.extensionReadyForTest()&&System.currentTimeMillis()<end)Thread.sleep(200);
  assertTrue(a.extensionReadyForTest());MonitorService service=a.serviceForTest();BrowserEngine e=service.engine;
  android.app.NotificationManager nm=service.getSystemService(android.app.NotificationManager.class);
  main(()->{service.stopAll();e.alerts.clearNotification();});
  org.mozilla.geckoview.GeckoSession old=e.current;
  main(()->{old.close();e.pageLost(old,"Injected idle content kill");e.pageLost(old,"Duplicate kill");e.ensureUsable();});
  Thread.sleep(1500);assertNotSame("Dead browser must be replaced",old,e.current);main(()->assertTrue(e.current.isOpen()));assertFalse(e.run.armed);
  for(android.service.notification.StatusBarNotification n:nm.getActiveNotifications())assertNotEquals("Idle page loss must not notify",1100+e.profile,n.getId());
  java.util.concurrent.atomic.AtomicBoolean navigated=new java.util.concurrent.atomic.AtomicBoolean();
  main(()->{org.mozilla.geckoview.WebExtension.Port saved=e.nativePort;e.nativePort=null;e.navigate(()->navigated.set(true));e.nativePort=saved;});
  Thread.sleep(1600);assertTrue("Home action must not depend on extension ACK",navigated.get());
  main(e::home);Thread.sleep(1800);main(()->assertTrue("Home must keep a usable session",e.current.isOpen()));
 }
 @Test public void recoveryRequiresMatchingRunAndCannotResumeAfterStop()throws Exception{
  MainActivity a=activity.getActivity();long end=System.currentTimeMillis()+60000;
  while(!a.extensionReadyForTest()&&System.currentTimeMillis()<end)Thread.sleep(200);
  assertTrue(a.extensionReadyForTest());MonitorService service=a.serviceForTest();
  shell("dumpsys deviceidle whitelist +com.sarathi.anantnag.gecko.v33");
  main(()->{
   service.arm();MonitorRun run=service.engine.run;run.recover("Injected scheduling stall");
   assertFalse("Recovery must revoke action permission",run.authorize());
   java.util.concurrent.atomic.AtomicBoolean resolved=new java.util.concurrent.atomic.AtomicBoolean();
   run.authorization().accept(value->{assertTrue(value.toString().contains("false"));resolved.set(true);});
   assertFalse("Action must await recovery",resolved.get());
   run.recovered("stale-other-run",true);assertTrue("Stale reply must not resume",run.recovering);
   run.stopByUser();run.recovered("stale-other-run",true);assertFalse("STOP must win over recovery",run.armed);
  });
 }

 @Test public void telegramCredentialsAreIsolated()throws Exception{
  android.content.Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();
  org.json.JSONObject[] originals=new org.json.JSONObject[9];
  for(int n=1;n<=9;n++)originals[n-1]=SecureSettings.read(c,n);
  try{
   for(int n=1;n<=9;n++)SecureSettings.write(c,n,new org.json.JSONObject().put("botToken","TEST_TOKEN_"+n).put("chatId","CHAT_"+n));
   SecureSettings.remove(c,4);
   for(int n=1;n<=9;n++){if(n==4)assertEquals("",SecureSettings.read(c,n).optString("botToken"));else assertEquals("TEST_TOKEN_"+n,SecureSettings.read(c,n).getString("botToken"));}
  }finally{for(int n=1;n<=9;n++){SecureSettings.remove(c,n);SecureSettings.write(c,n,originals[n-1]);}}
 }
 @Test public void silenceAllPreservesRuns()throws Exception{
  MainActivity a=activity.getActivity();long end=System.currentTimeMillis()+60000;while(a.serviceForTest()==null&&System.currentTimeMillis()<end)Thread.sleep(200);
  MonitorService s=a.serviceForTest();assertNotNull(s);shell("dumpsys deviceidle whitelist +com.sarathi.anantnag.gecko.v33");
  main(()->{BrowserEngine one=s.profile(1),two=s.profile(2);one.run.arm();two.run.arm();one.alerts.audio("START",true,0);two.alerts.audio("START",true,0);s.silenceAll();assertTrue(one.run.armed);assertTrue(two.run.armed);assertFalse(one.alerts.isRepeating());assertFalse(two.alerts.isRepeating());s.stopAll();});
 }

}




