package com.sarathi.geckotest;

import android.content.Context;
import android.net.Uri;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

/** Append-only critical evidence on one worker; only a bounded tail is rendered. */
final class CriticalHistory {
 private static final int TAIL_BYTES=65536;
 private final Context context;private final File archive;
 private final ExecutorService writer=Executors.newSingleThreadExecutor();
 private String tail="",failure="",pending="";private boolean loaded;
 CriticalHistory(Context c){this(c,"critical-history.log");}
 CriticalHistory(Context c,String name){
  context=c.getApplicationContext();archive=new File(context.getFilesDir(),name);
  String legacy=name.equals("critical-history.log")?context.getSharedPreferences("diagnostics",0).getString("criticalEvents",""):"";
  // Seed the view immediately while disk loading/migration happens off the UI thread.
  tail=MonitorService.trimDiagnosticHistory(legacy,TAIL_BYTES);
  writer.execute(()->{
   try{
    if(!legacy.isEmpty()){
     // Before this worker starts appending, an existing archive proves migration
     // already completed. Replacement is atomic; a restart cannot duplicate legacy data.
     if(!archive.exists()){
      File staging=new File(archive.getPath()+".migration");
      try(OutputStream out=new FileOutputStream(staging)){out.write((legacy+"\n").getBytes(StandardCharsets.UTF_8));}
      java.nio.file.Files.move(staging.toPath(),archive.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
     }
     context.getSharedPreferences("diagnostics",0).edit().remove("criticalEvents").commit();
    }
    String disk=readTail();synchronized(this){tail=MonitorService.trimDiagnosticHistory(disk+"\n"+pending,TAIL_BYTES);pending="";loaded=true;}
   }catch(Exception ex){failed();}
  });
 }
 private String readTail()throws IOException{
  if(!archive.exists())return "";
  try(RandomAccessFile file=new RandomAccessFile(archive,"r")){
   long offset=Math.max(0,file.length()-TAIL_BYTES);file.seek(offset);
   if(offset>0){int b;while((b=file.read())!=-1&&b!='\n'){} }
   byte[] bytes=new byte[(int)(file.length()-file.getFilePointer())];file.readFully(bytes);return new String(bytes,StandardCharsets.UTF_8).trim();
  }
 }
 synchronized void append(String line){
  tail=MonitorService.trimDiagnosticHistory(tail.isEmpty()?line:tail+"\n"+line,TAIL_BYTES);
  if(!loaded)pending=MonitorService.trimDiagnosticHistory(pending+"\n"+line,TAIL_BYTES);
  writer.execute(()->{try(OutputStream out=new FileOutputStream(archive,true)){out.write((line+"\n").getBytes(StandardCharsets.UTF_8));}catch(Exception ex){failed();}});
 }
 private synchronized void failed(){failure="Critical archive write failed — export available history and check storage.";}
 synchronized String recent(){return tail+(failure.isEmpty()?"":"\n"+failure);}
 void export(Uri destination,java.util.function.Consumer<Boolean> complete){
  writer.execute(()->{boolean ok=false;try(OutputStream out=context.getContentResolver().openOutputStream(destination,"wt")){if(out==null)throw new IOException();if(archive.exists())java.nio.file.Files.copy(archive.toPath(),out);ok=true;}catch(Exception ex){failed();}complete.accept(ok);});
 }
 void flushForTest()throws Exception{writer.submit(()->{}).get(10,TimeUnit.SECONDS);}
 void close(){writer.shutdown();}
}
