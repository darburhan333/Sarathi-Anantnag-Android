const fs=require('fs'),vm=require('vm'),assert=require('assert'),path=require('path');
(async()=>{
 const data={},changes=[],received=[],sent=[],native=[];let onPort,onDisconnect;
 const storage={get:async d=>Object.fromEntries(Object.entries(d).map(([k,v])=>[k,k in data?data[k]:v])),set:async p=>{Object.assign(data,p);for(const f of changes)f(Object.fromEntries(Object.entries(p).map(([k,v])=>[k,{newValue:v}])),'local');}};
 const chrome={storage:{local:storage,onChanged:{addListener:f=>changes.push(f)}},runtime:{onMessage:{addListener:f=>received.push(f)}},tabs:{sendMessage:async(id,m)=>({ok:true,owner:true,sessionId:data.monitorSessionId,lastLoopProgress:123,loopActive:true,safeToResume:true})}};
 const browser={runtime:{getURL:p=>'moz-extension://test/'+p,connectNative:()=>({postMessage:m=>sent.push(m),onMessage:{addListener:f=>onPort=f},onDisconnect:{addListener:f=>onDisconnect=f}})}};
 const ctx={chrome,browser,console,Date,Math,Promise,setTimeout,inGateQueue:f=>f(),nativeRequest:async m=>{native.push(m);return {ok:true};},reportMonitorFailure:async()=>storage.set({running:false,successWatch:false})};vm.createContext(ctx);
 vm.runInContext(fs.readFileSync(path.join(__dirname,'../app/src/main/assets/extension/android-bridge.js'),'utf8'),ctx);
 const flush=async()=>{for(let i=0;i<50;i++)await Promise.resolve();};await flush();
 await storage.set({running:true,successWatch:true,androidServiceReady:true,monitoredTabId:7,monitorSessionId:'session1'});
 onPort({type:'FOREGROUND',active:false});await flush();
 assert.equal(data.running,true,'Minimizing must preserve the run');assert.equal(data.androidServiceReady,true);assert.equal(data.androidForeground,false);
 onPort({type:'PING'});await flush();assert(data.heartbeatAt>0);assert.equal(data.monitorProgressAt,123);
 onPort({type:'RECOVER_CHECK',reason:'valid-run'});await flush();assert(sent.some(m=>m.type==='RECOVERED'&&m.id==='valid-run'&&m.safe));
 await storage.set({bookingClickIssued:true});onPort({type:'RECOVER_CHECK',reason:'uncertain-booking'});await flush();assert(sent.some(m=>m.id==='uncertain-booking'&&m.safe===false));await storage.set({bookingClickIssued:false});
 onDisconnect();await flush();assert.equal(data.running,true,'Transient port disconnect must not stop service-owned monitoring');
 onPort({type:'STOP'});await flush();assert.equal(data.running,false);assert.equal(data.androidServiceReady,false);assert(native.some(x=>x.type==='DISARM'));
 await storage.set({running:true,successWatch:true,androidServiceReady:true});onPort({type:'DISARMED'});await flush();assert.equal(data.running,false);assert.equal(data.successWatch,false);
 console.log('PASS: minimizing preserves service-authorized run; page heartbeat; native STOP and service loss revoke automation');
})().catch(e=>{console.error(e);process.exitCode=1;});


