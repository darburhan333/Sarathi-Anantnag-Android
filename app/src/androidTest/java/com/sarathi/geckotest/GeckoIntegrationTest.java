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
   BufferedReader reader=new BufferedReader(new InputStreamReader(socket.getInputStream()));
   String line=reader.readLine();if(line!=null&&line.startsWith("GET /attempt "))requests.incrementAndGet();
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
   shell("input keyevent KEYCODE_HOME");progress(s,"minimized",22);
   shell("input keyevent KEYCODE_SLEEP");progress(s,"screen locked",25);
   shell("dumpsys battery unplug");shell("dumpsys deviceidle force-idle");progress(s,"forced Doze with exemption",22);
   shell("dumpsys deviceidle unforce");shell("dumpsys battery reset");
   main(a::finish);progress(s,"Activity destroyed; session retained",18);
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
   Thread.sleep(1000);
   shell("screencap -p /data/local/tmp/monitor-preview.png");
   assertFalse("STOP must release foreground mode",s.isArmedForTest());
  }finally{
   shell("dumpsys deviceidle unforce");shell("dumpsys battery reset");shell("input keyevent KEYCODE_WAKEUP");
   server.close();
  }
 }
}
