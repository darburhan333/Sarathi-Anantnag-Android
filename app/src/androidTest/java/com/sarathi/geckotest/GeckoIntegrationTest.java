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
 @Test public void realExtensionLoop() throws Exception {
  ServerSocket server=new ServerSocket(8765);
  Thread thread=new Thread(()->{while(!server.isClosed())try(Socket socket=server.accept()){
   BufferedReader reader=new BufferedReader(new InputStreamReader(socket.getInputStream()));
   String line;while((line=reader.readLine())!=null&&!line.isEmpty()){}
   byte[] body="<!doctype html><html><body>Gecko test fixture</body></html>".getBytes("UTF-8");
   OutputStream out=socket.getOutputStream();out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));out.write(body);out.flush();
  }catch(IOException ignored){}});thread.setDaemon(true);thread.start();
  try{
   MainActivity a=activity.getActivity();
   long end=System.currentTimeMillis()+60000;
   while(!a.extensionReadyForTest()&&System.currentTimeMillis()<end)Thread.sleep(300);
   assertTrue("Built-in extension did not connect",a.extensionReadyForTest());
   InstrumentationRegistry.getInstrumentation().runOnMainSync(a::runSmokeForTest);
   end=System.currentTimeMillis()+110000;
   while(a.smokeResultForTest().isEmpty()&&System.currentTimeMillis()<end)Thread.sleep(500);
   assertTrue(a.smokeResultForTest(),a.smokeResultForTest().startsWith("PASS:"));
   InstrumentationRegistry.getInstrumentation().runOnMainSync(a::openMonitorForTest);
   end=System.currentTimeMillis()+20000;
   while(!a.monitorReadyForTest()&&System.currentTimeMillis()<end)Thread.sleep(300);
   assertTrue("Monitor UI failed to initialize",a.monitorReadyForTest());
   Thread.sleep(800);
   android.graphics.Bitmap screenshot=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
   assertNotNull("Screenshot unavailable",screenshot);
   try(FileOutputStream out=new FileOutputStream(new File(a.getExternalFilesDir(null),"monitor-preview.png"))){
    screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);
   }finally{screenshot.recycle();}
  }finally{server.close();}
 }
}
