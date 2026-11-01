package com.sarathi.geckotest;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.ActivityTestRule;
import androidx.test.rule.GrantPermissionRule;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;
import java.net.*;
import java.io.*;
import static org.junit.Assert.*;
@RunWith(AndroidJUnit4.class)
public class GeckoIntegrationTest {
 // Lifecycle tests use separate applicant profiles where page-loss latches are part of the scenario.
 @Rule public GrantPermissionRule permissions=GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS);
 @Rule public ActivityTestRule<MainActivity> activity=new ActivityTestRule<>(MainActivity.class);
 @Rule public TestWatcher diagnosticWatcher=new TestWatcher(){
  @Override protected void failed(Throwable failure,Description description){writeDiagnosticFile("TEST FAILURE "+description.getClassName()+"#"+description.getMethodName()+": "+failure);}
 };
 private final java.util.concurrent.atomic.AtomicInteger requests=new java.util.concurrent.atomic.AtomicInteger();
 private void shell(String command)throws Exception{
  try(android.os.ParcelFileDescriptor fd=InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(command);
      InputStream in=new android.os.ParcelFileDescriptor.AutoCloseInputStream(fd)){while(in.read()!=-1){}}
 }
 private void main(Runnable r){InstrumentationRegistry.getInstrumentation().runOnMainSync(r);}
 private MonitorService fixtureService;
 @Before public void resetFixturesBefore()throws Exception{
  MainActivity a=activity.getActivity();long end=System.currentTimeMillis()+60000;
  while((a.serviceForTest()==null||!a.extensionReadyForTest())&&System.currentTimeMillis()<end)Thread.sleep(200);
  fixtureService=a.serviceForTest();assertNotNull("Monitor service did not initialize",fixtureService);
  resetApplicantFixtures("before");
 }
 @After public void resetFixturesAfter()throws Exception{
  try{writeDiagnosticFile("TEST TEARDOWN");}
  finally{if(fixtureService!=null&&!fixtureService.isDestroyingForTest())resetApplicantFixtures("after");}
 }
 private void writeDiagnosticFile(String label){
  StringBuilder report=new StringBuilder("MPARIVAHAN MONITOR DIAGNOSTICS\n").append(label).append('\n');
  try{if(fixtureService!=null)report.append(fixtureService.diagnosticReport());else report.append("MonitorService unavailable\n");}
  catch(Exception e){report.append("diagnosticReport failed: ").append(e).append('\n');}
  byte[] diagnosticBytes=report.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
  try(java.io.FileOutputStream out=InstrumentationRegistry.getInstrumentation().getTargetContext().openFileOutput("monitor-copy-log.txt",android.content.Context.MODE_PRIVATE)){
   out.write(diagnosticBytes);out.flush();
  }catch(Exception e){System.err.println("DIAGNOSTIC_INTERNAL_FILE_WRITE_FAILED "+e);}
  try{
   java.io.File external=InstrumentationRegistry.getInstrumentation().getTargetContext().getExternalFilesDir(null);
   if(external==null)throw new java.io.IOException("External app files directory unavailable");
   try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(external,"monitor-copy-log.txt"))){out.write(diagnosticBytes);out.flush();}
  }catch(Exception e){System.err.println("DIAGNOSTIC_EXTERNAL_FILE_WRITE_FAILED "+e);}
  try{
   String encoded=android.util.Base64.encodeToString(diagnosticBytes,android.util.Base64.NO_WRAP);
   for(int offset=0;offset<encoded.length();offset+=8000){
    String chunk=encoded.substring(offset,Math.min(encoded.length(),offset+8000));
    String redirect=offset==0?">":">>";
    String command="printf '%s' '"+chunk+"' | base64 -d "+redirect+" /data/local/tmp/monitor-copy-log.txt";
    try(android.os.ParcelFileDescriptor fd=InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(command);
        InputStream in=new android.os.ParcelFileDescriptor.AutoCloseInputStream(fd)){while(in.read()!=-1){}}
   }
  }catch(Exception e){System.err.println("DIAGNOSTIC_SHELL_FILE_WRITE_FAILED "+e);}
 }
 private void resetApplicantFixtures(String phase)throws Exception{
  java.util.ArrayList<BrowserEngine> existing=new java.util.ArrayList<>();
  main(()->{
   fixtureService.stopAll();
   for(BrowserEngine e:fixtureService.profiles)if(e!=null){
    e.alerts.stop();e.alerts.clearNotification();e.alerts.newRun();e.resetProtectionForTest();existing.add(e);
   }
  });
  for(BrowserEngine e:existing){
   long end=System.currentTimeMillis()+60000;java.util.concurrent.atomic.AtomicBoolean ready=new java.util.concurrent.atomic.AtomicBoolean();
   while(System.currentTimeMillis()<end){main(()->ready.set(e.ready));if(ready.get())break;Thread.sleep(200);}
   assertTrue("Applicant "+e.profile+" extension did not become ready during "+phase+" fixture reset",ready.get());
  }
 }
 private BrowserEngine readyApplicant(int number)throws Exception{
  final BrowserEngine[] selected=new BrowserEngine[1];main(()->selected[0]=fixtureService.profile(number));
  long end=System.currentTimeMillis()+60000;java.util.concurrent.atomic.AtomicBoolean ready=new java.util.concurrent.atomic.AtomicBoolean();
  while(System.currentTimeMillis()<end){main(()->ready.set(selected[0].ready));if(ready.get())break;Thread.sleep(200);}
  assertTrue("Applicant "+number+" extension did not connect",ready.get());return selected[0];
 }
 private static org.json.JSONObject json(Object... pairs){
  if(pairs.length%2!=0)throw new IllegalArgumentException("JSON helper requires key/value pairs");
  org.json.JSONObject value=new org.json.JSONObject();
  try{for(int i=0;i<pairs.length;i+=2)value.put(String.valueOf(pairs[i]),pairs[i+1]);}
  catch(org.json.JSONException e){throw new AssertionError("Unable to construct test JSON",e);}
  return value;
 }
 private String shellText(String command)throws Exception{
  try(android.os.ParcelFileDescriptor fd=InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(command);
      InputStream in=new android.os.ParcelFileDescriptor.AutoCloseInputStream(fd);
      ByteArrayOutputStream out=new ByteArrayOutputStream()){
   byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);
   return out.toString("UTF-8");
  }
 }
 private String stopSnapshot(MonitorService s){
  org.json.JSONObject state=s.engine.lastState;
  org.mozilla.geckoview.GeckoSession current=s.engine.current;
  String currentState=current==null?"null":"present";
  return "protectedPage="+s.engine.protectedPage+
   ", protectedStage="+s.engine.protectedStage+
   ", pageLostProtected="+s.engine.pageLostProtected+
   ", armed="+s.engine.run.armed+
   ", current="+currentState+
   ", successWatch="+state.optBoolean("successWatch")+
   ", calendarLock="+state.optBoolean("calendarLock");
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
  writeDiagnosticFile(label);
  System.out.println("MONITOR_COPY_LOG\n"+s.diagnosticReport());
  System.out.println("EVIDENCE_END "+label);
 }
 private void progress(MonitorService s,String label,int seconds)throws Exception{
  int before=s.attemptsForTest(),beforeRequests=requests.get();Thread.sleep(seconds*1000L);
  if(!s.isArmedForTest()||!s.isPromotedForTest())captureEvidence(s,label+" run/service failure");
  if(label.contains("screen locked")||label.contains("forced Doze"))assertTrue(label+": KeepAlive silent audio must remain running",s.isKeepAliveAudioRunningForTest());
  String events=s.getSharedPreferences("diagnostics",0).getString("events","");String[] lines=events.split("\n");StringBuilder tail=new StringBuilder();String disarm="No DISARM recorded";for(String line:lines)if(line.contains("DISARM"))disarm=line;for(int i=Math.max(0,lines.length-40);i<lines.length;i++)tail.append(lines[i]).append('\n');
  String detail="; "+disarm+"\n"+tail+"\n"+s.engine.run.rawState()+"\nengine.lastState="+s.engine.lastState;
  assertTrue(label+": service not promoted"+detail,s.isPromotedForTest());
  assertTrue(label+": run disarmed"+detail,s.isArmedForTest());
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
   main(()->assertTrue("Re-attached GeckoSession must remain open",s.engine.current!=null&&s.engine.current.isOpen()));
   main(()->s.engine.command("SMOKE_CONTINUE"));
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
   System.out.println("realExtensionLoop: 10-minute locked, forced-Doze and Activity-destroyed phases completed; next exercising notification STOP");
   java.util.concurrent.atomic.AtomicBoolean waitCompleted=new java.util.concurrent.atomic.AtomicBoolean();
   java.util.concurrent.atomic.AtomicBoolean cancelled=new java.util.concurrent.atomic.AtomicBoolean();
   main(()->{s.arm();s.waitFor(8000).accept(result->{String payload=result==null?"":result.toString();cancelled.set(payload.contains("\"cancelled\":true"));waitCompleted.set(true);});});
   main(reopened::finish);
   java.util.concurrent.atomic.AtomicBoolean hidden=new java.util.concurrent.atomic.AtomicBoolean();
   long hiddenDeadline=System.currentTimeMillis()+5000;
   while(System.currentTimeMillis()<hiddenDeadline){main(()->hidden.set(!s.visible));if(hidden.get())break;Thread.sleep(50);}
   assertTrue("Activity must be hidden before notification STOP",hidden.get());
   ctx.startService(new android.content.Intent(ctx,MonitorService.class).setAction("STOP"));
   java.util.concurrent.atomic.AtomicBoolean disarmed=new java.util.concurrent.atomic.AtomicBoolean();
   long stopDeadline=System.currentTimeMillis()+5000;
   while(System.currentTimeMillis()<stopDeadline){main(()->disarmed.set(!s.isArmedForTest()));if(disarmed.get())break;Thread.sleep(50);}
   assertTrue("Notification STOP action must disarm when no Activity is visible; "+stopSnapshot(s),disarmed.get());
   assertFalse("STOP must disarm automation; "+stopSnapshot(s),s.isArmedForTest());
   long waitCallbackDeadline=System.currentTimeMillis()+5000;
   while(!waitCompleted.get()&&System.currentTimeMillis()<waitCallbackDeadline)Thread.sleep(25);
   assertTrue("STOP must complete the pending native wait callback",waitCompleted.get());
   assertTrue("STOP must cancel pending native wait; callback completed="+waitCompleted.get(),cancelled.get());
   java.util.concurrent.atomic.AtomicBoolean unprotectedStop=new java.util.concurrent.atomic.AtomicBoolean();
   main(()->{
    if(s.engine.protectedPage){
     assertTrue("Notification STOP must retain protected foreground service; "+stopSnapshot(s),s.isPromotedForTest());
     assertTrue("Notification STOP must retain KeepAlive; "+stopSnapshot(s),s.isKeepAliveRunningForTest());
     assertNotNull("Notification STOP must retain protected current session; "+stopSnapshot(s),s.engine.current);
     assertTrue("Notification STOP must retain open protected session; "+stopSnapshot(s),s.engine.current.isOpen());
     assertTrue("Notification STOP must retain KeepAlive silent audio; "+stopSnapshot(s),s.isKeepAliveAudioRunningForTest());
     assertTrue("Notification STOP must retain active protected session; "+stopSnapshot(s),Boolean.TRUE.equals(s.engine.activeStates.get(s.engine.current)));
     assertTrue("Notification STOP must retain PRIORITY_HIGH; "+stopSnapshot(s),s.engine.isPriorityHighForTest(s.engine.current));
    }else{
     assertFalse("Unprotected applicant must not become protected after STOP; "+stopSnapshot(s),s.engine.protectedPage);
     assertFalse("KeepAlive must stop for unprotected applicant after STOP; "+stopSnapshot(s),s.isKeepAliveRunningForTest());
     unprotectedStop.set(true);
    }
   });
   if(unprotectedStop.get()){
    Thread.sleep(31000L);
    assertFalse("KeepAlive silent audio must stop after unprotected STOP grace; "+stopSnapshot(s),s.isKeepAliveAudioRunningForTest());
   }
   System.out.println("PASS realExtensionLoop: locked, forced-Doze, Activity-destroyed and STOP phases");
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

 @Test public void normalMonitoringStartThenStopDoesNotRequireProtectionConfirmation()throws Exception{
  MainActivity a=activity.getActivity();MonitorService service=fixtureService;BrowserEngine e=service.engine;
  main(()->{
   // KeepAlive is shared by all nine applicants. Isolate this assertion so a
   // different protected applicant cannot legitimately keep the shared service alive.
   service.stopAll();
   for(BrowserEngine applicant:service.profiles)if(applicant!=null&&(applicant.protectedPage||applicant.pageLostProtected))applicant.resetProtectionForTest();
   assertEquals("STOP fixture must have no active applicants",0,service.activeCount());
   assertEquals("STOP fixture must have no protected applicants",0,service.protectedCount());
   service.engine=e;service.activity=a;service.visible=true;e.run.arm();
   long stateAt=android.os.SystemClock.elapsedRealtime();org.json.JSONObject runningState=json("running",true,"bookingStage","MONITORING","successWatch",true,"heartbeatAt",stateAt,"monitorProgressAt",stateAt);e.run.onState(runningState);e.updateProtection(runningState);
   assertFalse("Unprotected applicant must remain unprotected",e.protectedPage);
   assertEquals("Only the target applicant may be armed",1,service.activeCount());
   assertEquals("No applicant may hold page protection",0,service.protectedCount());
   e.stopFromUi();
   assertFalse("STOP must disarm the unprotected applicant",e.run.armed);
   assertFalse("STOP must not create page protection",e.protectedPage);
   assertFalse("KeepAlive must stop for an unprotected stopped applicant; active="+service.activeCount()+" protected="+service.protectedCount()+" promoted="+service.isPromotedForTest()+" report="+service.diagnosticReport(),service.isKeepAliveRunningForTest());
   assertTrue("Service must remain foreground during its stop grace period",service.isPromotedForTest());
  });
  long deadline=System.currentTimeMillis()+40000;
  while(service.isPromotedForTest()&&System.currentTimeMillis()<deadline)Thread.sleep(250);
  assertFalse("Unprotected service must leave foreground after the grace period",service.isPromotedForTest());
  assertFalse("KeepAlive must remain stopped after the grace period",service.isKeepAliveRunningForTest());
 }
 @Test public void stopDuringCalendarReviewDisarmsAndKeepsProtectedPageActive()throws Exception{
  MainActivity a=activity.getActivity();MonitorService service=fixtureService;BrowserEngine e=readyApplicant(3);
  main(()->{
   service.activity=a;service.visible=true;e.run.arm();
   e.updateProtection(json("running",true,"bookingStage","CALENDAR REVIEW","calendarLock",true,"protectPage",true));
   e.stopFromUi();
   assertFalse("STOP during CALENDAR REVIEW must disarm immediately",e.run.armed);
   assertTrue("STOP must preserve calendar protection",e.protectedPage);
   assertTrue("Protected calendar page must keep service foreground",service.isPromotedForTest());
   assertTrue("Protected calendar page must keep KeepAlive running",service.isKeepAliveRunningForTest());
   assertTrue("Protected calendar page must keep silent audio running",service.isKeepAliveAudioRunningForTest());
   assertNotNull("Protected calendar session must remain",e.current);
   assertTrue("Protected calendar session must remain active",Boolean.TRUE.equals(e.activeStates.get(e.current)));
   assertTrue("Protected calendar session must retain PRIORITY_HIGH",e.isPriorityHighForTest(e.current));
  });
 }
 @Test public void stopDuringSmsStageKeepsProtectedPageActiveAndForeground()throws Exception{
  MainActivity a=activity.getActivity();MonitorService service=fixtureService;BrowserEngine e=readyApplicant(4);
  main(()->{
   service.activity=a;service.visible=true;e.run.arm();
   e.updateProtection(json("running",true,"bookingStage","AWAITING MANUAL SMS VERIFICATION","protectPage",true));
   assertTrue("SMS stage must establish protection before STOP",e.protectedPage);
   e.stopFromUi();
   assertFalse("STOP must disarm SMS applicant immediately",e.run.armed);
   assertTrue("STOP must preserve SMS page protection",e.protectedPage);
   assertTrue("Protected SMS page must keep service foreground",service.isPromotedForTest());
   assertTrue("Protected SMS page must keep KeepAlive running",service.isKeepAliveRunningForTest());
   assertTrue("Protected SMS page must keep silent audio running",service.isKeepAliveAudioRunningForTest());
   assertNotNull("Protected SMS page session must remain",e.current);
   assertTrue("Protected SMS page session must stay active",Boolean.TRUE.equals(e.activeStates.get(e.current)));
   assertTrue("Protected SMS page must retain PRIORITY_HIGH",e.isPriorityHighForTest(e.current));
  });
 }
 @Test public void notificationStopWorksWithoutVisibleActivity()throws Exception{
  MonitorService service=fixtureService;BrowserEngine protectedApplicant=readyApplicant(5),normalApplicant=readyApplicant(6);
  main(()->{
   service.activity=activity.getActivity();service.visible=true;
   protectedApplicant.run.arm();normalApplicant.run.arm();
   protectedApplicant.updateProtection(json("running",true,"bookingStage","AWAITING MANUAL SMS VERIFICATION","protectPage",true));
   service.visible=false;service.activity=null;
  });
  android.content.Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
  context.startService(new android.content.Intent(context,MonitorService.class).setAction("STOP"));
  long deadline=System.currentTimeMillis()+5000;
  while((protectedApplicant.run.armed||normalApplicant.run.armed)&&System.currentTimeMillis()<deadline)Thread.sleep(50);
  assertFalse("Notification STOP ALL must disarm protected applicant without Activity",protectedApplicant.run.armed);
  assertFalse("Notification STOP ALL must disarm unprotected applicant without Activity",normalApplicant.run.armed);
  main(()->{
   assertTrue("Notification STOP must preserve SMS page protection",protectedApplicant.protectedPage);
   assertTrue("Protected page must keep service foreground",service.isPromotedForTest());
   assertTrue("Protected page must keep KeepAlive running",service.isKeepAliveRunningForTest());
   assertTrue("Protected page must keep silent audio running",service.isKeepAliveAudioRunningForTest());
   assertNotNull(protectedApplicant.current);
   assertTrue("Protected page session must stay active",Boolean.TRUE.equals(protectedApplicant.activeStates.get(protectedApplicant.current)));
   assertTrue("Protected page must retain PRIORITY_HIGH",protectedApplicant.isPriorityHighForTest(protectedApplicant.current));
  });
 }
 @Test public void backAndHomeConfirmOnlyWhenPageProtected()throws Exception{
  MainActivity a=activity.getActivity();MonitorService service=fixtureService;BrowserEngine e=service.engine;
  final org.mozilla.geckoview.WebExtension.Port[] savedPort=new org.mozilla.geckoview.WebExtension.Port[1];
  main(()->{
   a.selectApplicant(1);service.activity=a;service.visible=false;savedPort[0]=e.nativePort;e.nativePort=null;e.protectedPage=false;e.pageLostProtected=false;e.canGoBack=true;
   a.onBackPressed();
   assertNotNull("Back without protection must follow the existing immediate navigation path",e.pendingId);
   assertFalse("Back on an unprotected page must not request confirmation",e.alerts.isRepeating());
   assertFalse("Back on an unprotected page must preserve unprotected state",e.protectedPage);
  });
  Thread.sleep(100);
  main(()->{
   e.home();
   assertNotNull("Home without protection must navigate immediately",e.pendingId);
   assertFalse("Home on an unprotected page must not request confirmation",e.alerts.isRepeating());
   assertFalse("Home on an unprotected page must preserve unprotected state",e.protectedPage);
   e.nativePort=savedPort[0];
  });
  Thread.sleep(100);
  main(()->{
   e.updateProtection(json("running",true,"bookingStage","AWAITING MANUAL SMS VERIFICATION","protectPage",true));
   e.alerts.stop();e.alerts.clearNotification();e.alerts.newRun();
   e.canGoBack=true;a.onBackPressed();
   assertNull("Back on a protected page must wait for confirmation",e.pendingId);
   assertTrue("Back on a protected page must request confirmation",e.alerts.isRepeating());
   assertTrue("Rejected Back confirmation must leave protected page intact",e.protectedPage);
   e.alerts.stop();e.alerts.clearNotification();e.alerts.newRun();
   e.home();
   assertNull("Home on a protected page must wait for confirmation",e.pendingId);
   assertTrue("Home on a protected page must request confirmation",e.alerts.isRepeating());
   assertTrue("Rejected Home confirmation must leave protected page intact",e.protectedPage);
  });
 }
 @Test public void lostNormalPageRecoversButLostProtectedPageDoesNotReplace()throws Exception{
  MainActivity a=activity.getActivity();long end=System.currentTimeMillis()+60000;
  while(!a.extensionReadyForTest()&&System.currentTimeMillis()<end)Thread.sleep(200);
  assertTrue(a.extensionReadyForTest());MonitorService service=a.serviceForTest();
  // Use a dedicated applicant so a protected-page latch from another integration
  // test cannot leak into this normal-page recovery scenario.
  final BrowserEngine[] isolated=new BrowserEngine[1];main(()->isolated[0]=service.profile(2));
  BrowserEngine e=isolated[0];end=System.currentTimeMillis()+60000;
  while(!e.ready&&System.currentTimeMillis()<end)Thread.sleep(200);
  assertTrue("Isolated applicant extension did not connect",e.ready);
  android.app.NotificationManager nm=service.getSystemService(android.app.NotificationManager.class);
  java.util.concurrent.atomic.AtomicReference<org.mozilla.geckoview.GeckoSession> normalSession=new java.util.concurrent.atomic.AtomicReference<>();
  main(()->{
   service.stopAll();e.alerts.clearNotification();service.visible=true;e.foreground=true;
   e.updateProtection(json("running",true,"bookingStage","MONITORING","successWatch",true));
   normalSession.set(e.current);normalSession.get().close();e.pageLost(normalSession.get(),"Injected normal COV page loss");e.ensureUsable();
  });
  end=System.currentTimeMillis()+10000;java.util.concurrent.atomic.AtomicBoolean replacementOpen=new java.util.concurrent.atomic.AtomicBoolean();
  while(System.currentTimeMillis()<end){main(()->replacementOpen.set(e.current!=null&&e.current.isOpen()));if(replacementOpen.get())break;Thread.sleep(100);}
  main(()->{
   assertNotNull("Normal page should be replaced; protectedPage="+e.protectedPage+" protectedStage="+e.protectedStage+" pageLostProtected="+e.pageLostProtected,e.current);
   assertTrue("Replacement normal page must be open",e.current!=null&&e.current.isOpen());
   assertFalse("Normal page loss must not be protected",e.pageLostProtected);
  });
  for(android.service.notification.StatusBarNotification n:nm.getActiveNotifications())
   assertNotEquals("Normal page loss must not raise protected-page alert",e.alerts.criticalNotificationId("PROTECTED_PAGE_LOST"),n.getId());
  main(()->{
   e.updateProtection(json("running",true,"bookingStage","AWAITING MANUAL SMS VERIFICATION"));
   org.mozilla.geckoview.GeckoSession protectedSession=e.current;
   protectedSession.close();e.pageLost(protectedSession,"Injected protected SMS page loss");e.ensureUsable();
   assertTrue("Protected page loss must be latched: protectedPage="+e.protectedPage+" protectedStage="+e.protectedStage+" pageLostProtected="+e.pageLostProtected,e.pageLostProtected);
   assertNull("Lost protected page must not be replaced; armed="+e.run.armed,e.current);
  });
  int protectedAlertId=e.alerts.criticalNotificationId("PROTECTED_PAGE_LOST");
  boolean protectedAlert=false;long alertDeadline=System.currentTimeMillis()+5000;
  while(System.currentTimeMillis()<alertDeadline&&!protectedAlert){
   for(android.service.notification.StatusBarNotification n:nm.getActiveNotifications())
    if(n.getId()==protectedAlertId)protectedAlert=true;
   if(!protectedAlert)Thread.sleep(50);
  }
  assertTrue("Protected-page loss must raise critical notification after queued delivery; id="+protectedAlertId+" diagnostics="+service.diagnosticReport(),protectedAlert);
  main(()->{e.alerts.stop();e.alerts.clearNotification();e.foreground=false;service.visible=true;service.stopAll();});
 }

 @Test public void idleUnloadRetainsLoggedInPagesUnlessRealMemoryPressure()throws Exception{
  BrowserEngine e=readyApplicant(8);long now=android.os.SystemClock.elapsedRealtime();
  main(()->{
   e.foreground=false;e.run.disarm();e.lastVisibleAt=now-2*60*60*1000L;e.lastState=json("covReady",true,"successWatch",true,"bookingStage","MONITORING");
   org.mozilla.geckoview.GeckoSession loggedIn=e.current;e.unloadIdle(now,false);
   assertSame("Logged-in applicant page must not unload without real memory pressure",loggedIn,e.current);
   assertTrue("Logged-in session must remain open",loggedIn.isOpen());
   assertFalse("No unload banner before actual unload",e.pageUnloaded);
   e.unloadIdle(now,true);
   assertNull("Real memory pressure may unload an unprotected logged-in page",e.current);
   assertTrue("Unload state must be retained for next open",e.pageUnloaded);
   assertTrue("Diagnostic must record login requirement",fixtureService.diagnosticReport().contains("Applicant 8 page unloaded, login will be needed"));
   e.foreground=true;e.ensureUsable();
   assertFalse("Opening the applicant must consume the unload banner state",e.pageUnloaded);
   assertTrue("Unloaded applicant must reopen a usable home page",e.current!=null&&e.current.isOpen());
   e.foreground=false;e.loggedInPage=false;e.lastState=new org.json.JSONObject();e.lastVisibleAt=now-60L*60*1000;
   e.unloadIdle(now,false);
   assertNull("Known logged-out page may unload after the configured 60-minute timeout",e.current);
   assertTrue("Timed idle unload must retain the login-needed banner",e.pageUnloaded);
  });
 }
 @Test public void idleUnloadTimeoutDefaultsToSixtyMinutesAndCanBeConfigured(){
  assertEquals(60L*60*1000,BrowserEngine.idleUnloadTimeoutMsForTest(60));
  assertEquals(15L*60*1000,BrowserEngine.idleUnloadTimeoutMsForTest(15));
  assertEquals(180L*60*1000,BrowserEngine.idleUnloadTimeoutMsForTest(240));
 }
 @Test public void sessionExpiredIsTerminalAndAppearsInNotificationAndCopyLog()throws Exception{
  BrowserEngine e=readyApplicant(9);MonitorService service=fixtureService;
  main(()->{
   service.activity=activity.getActivity();service.visible=true;e.run.arm();
   try{java.lang.reflect.Method receive=BrowserEngine.class.getDeclaredMethod("receive",Object.class);receive.setAccessible(true);receive.invoke(e,json("type","CRITICAL","kind","SESSION_EXPIRED","message","ignored source message","eventKey","session-expired-test"));}
   catch(Exception ex){throw new AssertionError(ex);}
  });
  assertFalse("SESSION_EXPIRED must disarm the applicant",e.run.armed);
  assertEquals("Session expired",e.run.lastReasonForTest());
  assertFalse("Session expiry must also stop the receipt watcher",e.monitoring);
  assertTrue("Monitor status must show terminal session expiry",e.monitorStatus.contains("SESSION EXPIRED"));
  String expected="A9: SESSION EXPIRED, restart from slot booking home";
  assertTrue("Copy-log header must show terminal session expiry",service.diagnosticReport().startsWith(expected));
  int id=e.alerts.criticalNotificationId("SESSION_EXPIRED");boolean found=false;long deadline=System.currentTimeMillis()+5000;
  while(System.currentTimeMillis()<deadline&&!found){for(android.service.notification.StatusBarNotification n:service.getSystemService(android.app.NotificationManager.class).getActiveNotifications())if(n.getId()==id&&expected.equals(n.getNotification().extras.getString(android.app.Notification.EXTRA_TEXT)))found=true;if(!found)Thread.sleep(50);}
  assertTrue("SESSION_EXPIRED critical notification must show applicant restart instruction",found);
 }
 @Test public void pageReadyReasonsUseOnlyActionableSarathiStates(){
  assertEquals("Calendar detected",BrowserEngine.pageReadyReason("Calendar detected",false,false,false));
  assertEquals("Session expired",BrowserEngine.pageReadyReason("Session expired",false,false,false));
  assertEquals("COV form missing",BrowserEngine.pageReadyReason("Navigate to COV to start",false,false,false));
  assertEquals("COV selection ready",BrowserEngine.pageReadyReason("COV selection",false,false,true));
 }
 @Test public void idlePageLossRecoversWithoutAlarmAndHomeWorksWithoutBridge()throws Exception{
  MainActivity a=activity.getActivity();long end=System.currentTimeMillis()+60000;
  while(!a.extensionReadyForTest()&&System.currentTimeMillis()<end)Thread.sleep(200);
  assertTrue(a.extensionReadyForTest());MonitorService service=a.serviceForTest();
  // Keep idle-recovery assertions independent from the protected-page tests.
  final BrowserEngine[] isolated=new BrowserEngine[0+1];main(()->isolated[0]=service.profile(3));
  BrowserEngine e=isolated[0];end=System.currentTimeMillis()+60000;
  while(!e.ready&&System.currentTimeMillis()<end)Thread.sleep(200);
  assertTrue("Isolated applicant extension did not connect",e.ready);
  android.app.NotificationManager nm=service.getSystemService(android.app.NotificationManager.class);
  main(()->{service.stopAll();e.alerts.clearNotification();});
  java.util.concurrent.atomic.AtomicReference<org.mozilla.geckoview.GeckoSession> old=new java.util.concurrent.atomic.AtomicReference<>();
  main(()->{old.set(e.current);old.get().close();e.pageLost(old.get(),"Injected idle content kill");e.pageLost(old.get(),"Duplicate kill");e.ensureUsable();});
  Thread.sleep(1500);main(()->{
   assertNotSame("Dead browser must be replaced; protectedPage="+e.protectedPage+" protectedStage="+e.protectedStage+" pageLostProtected="+e.pageLostProtected+" armed="+e.run.armed+" current="+(e.current!=null),old.get(),e.current);
   assertTrue("Replacement must be open; protectedPage="+e.protectedPage+" protectedStage="+e.protectedStage+" pageLostProtected="+e.pageLostProtected+" armed="+e.run.armed+" current="+(e.current!=null),e.current!=null&&e.current.isOpen());
   assertFalse("Idle page loss must not leave run armed; protectedPage="+e.protectedPage+" protectedStage="+e.protectedStage+" pageLostProtected="+e.pageLostProtected+" armed="+e.run.armed+" current="+(e.current!=null),e.run.armed);
  });
  for(android.service.notification.StatusBarNotification n:nm.getActiveNotifications())assertNotEquals("Idle page loss must not notify",1100+e.profile,n.getId());
  java.util.concurrent.atomic.AtomicBoolean navigated=new java.util.concurrent.atomic.AtomicBoolean();
  main(()->{org.mozilla.geckoview.WebExtension.Port saved=e.nativePort;e.nativePort=null;e.navigate("Go Home",()->navigated.set(true));e.nativePort=saved;});
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




