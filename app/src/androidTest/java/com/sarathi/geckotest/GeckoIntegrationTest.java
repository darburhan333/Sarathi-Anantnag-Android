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
 private void progress(MonitorService s,String label,int seconds)throws Exception{
  int before=s.attemptsForTest(),beforeRequests=requests.get();Thread.sleep(seconds*1000L);
  assertTrue(label+": foreground service stopped",s.isArmedForTest());
  assertTrue(label+": no actual COV attempts: "+s.engine.lastState,s.attemptsForTest()>before);
  assertTrue(label+": page did not make another HTTP request",requests.get()>beforeRequests);
  System.out.println("PASS "+label+": "+before+" -> "+s.attemptsForTest());
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
   shell("dumpsys deviceidle whitelist +com.sarathi.anantnag.gecko");
   main(a::runSmokeForTest);
   end=System.currentTimeMillis()+30000;
   while(!s.engine.smokePhase.equals("background-ready")&&System.currentTimeMillis()<end)Thread.sleep(200);
   assertEquals("background-ready",s.engine.smokePhase);
   Thread.sleep(750); // Let the new run snapshot cross the asynchronous native port before sampling counters.
   shell("input keyevent KEYCODE_HOME");progress(s,"minimized",22);
   shell("input keyevent KEYCODE_SLEEP");progress(s,"screen locked",45);
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
 @Test public void sixApplicantIsolationAndBackground()throws Exception{
  ServerSocket server=new ServerSocket(8765);java.util.concurrent.atomic.AtomicIntegerArray counts=new java.util.concurrent.atomic.AtomicIntegerArray(6);
  Thread thread=new Thread(()->{while(!server.isClosed())try(Socket socket=server.accept()){
   socket.setSoTimeout(1000);BufferedReader reader=new BufferedReader(new InputStreamReader(socket.getInputStream()));String line=reader.readLine();
   if(line!=null)for(int i=0;i<6;i++)if(line.contains("/attempt?profile="+(i+1)+"&"))counts.incrementAndGet(i);
   while((line=reader.readLine())!=null&&!line.isEmpty()){}
   byte[] body="<!doctype html><html><body>Profile test</body></html>".getBytes("UTF-8");OutputStream out=socket.getOutputStream();out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));out.write(body);out.flush();
  }catch(IOException ignored){}});thread.setDaemon(true);thread.start();
  try{
   MainActivity a=activity.getActivity();long end=System.currentTimeMillis()+60000;
   while(a.serviceForTest()==null&&System.currentTimeMillis()<end)Thread.sleep(200);MonitorService service=a.serviceForTest();assertNotNull(service);
   main(()->{for(int n=2;n<=6;n++)service.profile(n);});end=System.currentTimeMillis()+60000;
   while(System.currentTimeMillis()<end&&!java.util.Arrays.stream(service.profiles).allMatch(e->e.ready))Thread.sleep(200);
   for(BrowserEngine e:service.profiles)assertTrue("Extension ready "+e.profile,e.ready);
   shell("dumpsys deviceidle whitelist +com.sarathi.anantnag.gecko");
   main(()->{for(BrowserEngine e:service.profiles){e.smokeResult="";e.command("MULTI_SMOKE");}});end=System.currentTimeMillis()+45000;
   while(System.currentTimeMillis()<end&&java.util.Arrays.stream(service.profiles).anyMatch(e->e.smokeResult.isEmpty()))Thread.sleep(250);
   for(BrowserEngine e:service.profiles){assertTrue("Applicant "+e.profile+": "+e.smokeResult,e.smokeResult.startsWith("PASS:"));System.out.println("Applicant "+e.profile+" "+e.smokeResult);}
   int[] before=new int[6];for(int i=0;i<6;i++)before[i]=counts.get(i);shell("input keyevent KEYCODE_HOME");shell("input keyevent KEYCODE_SLEEP");
   // Reproduce GeckoView deactivation after detachment, without changing our cached state.
   main(()->{for(BrowserEngine e:service.profiles)for(org.mozilla.geckoview.GeckoSession page:e.sessions)if(page!=e.monitorSession&&page.isOpen())page.setActive(false);});
   for(int minute=1;minute<=5;minute++){
    Thread.sleep(60000);
    for(int i=0;i<6;i++){assertTrue("Locked HTTP applicant "+i+" minute "+minute,counts.get(i)>before[i]);assertTrue("Run alive applicant "+i,service.profiles[i].run.armed);before[i]=counts.get(i);}
    System.out.println("PASS all six applicants locked, minute "+minute+": "+counts);
   }
   shell("dumpsys battery unplug");shell("dumpsys deviceidle force-idle");Thread.sleep(120000);
   for(int i=0;i<6;i++)assertTrue("Doze HTTP applicant "+i,counts.get(i)>before[i]);
   main(()->service.profiles[1].run.stopByUser());Thread.sleep(1500);int stopped=counts.get(1),first=counts.get(0),third=counts.get(2);Thread.sleep(22000);
   assertEquals("Applicant 2 must stop",stopped,counts.get(1));assertTrue("Applicant 1 continues",counts.get(0)>first);assertTrue("Applicant 3 continues",counts.get(2)>third);
   shell("dumpsys deviceidle unforce");shell("dumpsys battery reset");shell("input keyevent KEYCODE_WAKEUP");shell("wm dismiss-keyguard");shell("am start -n com.sarathi.anantnag.gecko/com.sarathi.geckotest.MainActivity");Thread.sleep(1500);
   main(()->{a.selectApplicant(3);a.selectApplicant(1);service.stopAll();});assertEquals("STOP ALL",0,service.activeCount());
   System.out.println("PASS six applicants: storage isolation, native ownership, locked and Doze HTTP, independent STOP, switching, STOP ALL");
  }finally{shell("dumpsys deviceidle unforce");shell("dumpsys battery reset");shell("input keyevent KEYCODE_WAKEUP");server.close();}
 }

 @Test public void encryptedSettingsRoundTrip()throws Exception{
  android.content.Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();
  org.json.JSONObject original=SecureSettings.read(c);
  String marker="TEST_ONLY_NOT_A_TELEGRAM_TOKEN";
  try{
   SecureSettings.write(c,new org.json.JSONObject().put("botToken",marker).put("chatId","12345").put("chatId2","67890"));
   assertEquals(marker,SecureSettings.read(c).getString("botToken"));
   String persisted=c.getSharedPreferences("secure",0).getString("config","");
   assertFalse("Token must not be persisted as plaintext",persisted.contains(marker));
   assertFalse("Chat IDs must not be persisted as plaintext",persisted.contains("12345"));
   System.out.println("PASS Android Keystore encrypted credential round-trip");
  }finally{for(String k:new String[]{"botToken","chatId","chatId2"})if(!original.has(k))original.put(k,"");SecureSettings.write(c,original);}
 }

}



