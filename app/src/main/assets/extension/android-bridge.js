// The privileged background alone talks to Android. No content-native permission.
(() => {
 let port=null, foreground=false, commandQueue=Promise.resolve();
 const defaults={running:false,successWatch:false,bookingStage:'STOPPED',lastStatus:'Ready',
  attempts:0,sessionAttempts:0,alarmActive:false,bookingClickIssued:false,androidForeground:false,androidServiceReady:false,heartbeatAt:0,monitorProgressAt:0,lastCheck:0,calendarLock:false};
 let stateTimer=null;
 function scheduleState(){if(stateTimer!==null)return;stateTimer=setTimeout(()=>{stateTimer=null;state().catch(()=>{});},250);}
 async function state(){
  const d=await chrome.storage.local.get(defaults);
  port?.postMessage({type:'STATE',...d});
 }
 async function stop(reason){
  await inGateQueue(()=>chrome.storage.local.set({androidStopEpoch:String(Date.now())+'-'+Math.random(),running:false,successWatch:false,
   autoBookingEnabled:false,nextRetryAt:0,monitorLoopActive:false,bookingStage:'STOPPED',lastStatus:reason}));
  const d=await chrome.storage.local.get({monitoredTabId:null});
  if(d.monitoredTabId!==null)await chrome.tabs.sendMessage(d.monitoredTabId,{type:'SARATHI_STOP'}).catch(()=>{});
 }
 async function command(m){
  if(m.type==='DISARMED'){await chrome.storage.local.set({androidServiceReady:false});await stop('Background service stopped — press START to begin again');return;}
  if(m.type==='MULTI_SMOKE'&&typeof globalThis.runMultiSmoke==='function'){globalThis.runMultiSmoke();return;}
  if(m.type==='SMOKE_RUN' && typeof globalThis.runAndroidSmoke==='function'){globalThis.runAndroidSmoke();return;}
  if(m.type==='FOREGROUND'){
   foreground=m.active===true;
   await chrome.storage.local.set({androidForeground:foreground});
   // Visibility is independent of service authorization. Minimize/lock keeps the run alive.
  } else if(m.type==='STOP'){await chrome.storage.local.set({androidServiceReady:false});await stop('Stopped by user');await nativeRequest({type:'DISARM'});}
  else if(m.type==='SILENCE')await chrome.storage.local.set({alarmActive:false});
  else if(m.type==='FAILURE'){
   await chrome.storage.local.set({androidServiceReady:false});
   const d=await chrome.storage.local.get({monitorSessionId:'',monitoredTabId:null});
   await reportMonitorFailure(d,'ANDROID_PAGE_ERROR','Browser page failed — inspect and restart manually');
  }
  await state();
  if(m.id)port?.postMessage({type:'ACK',id:m.id});
 }
 async function connect(){
  port=browser.runtime.connectNative('sarathi');
  let pingBusy=false;
  port.onMessage.addListener(m=>{
   if(m.type==='PING'){
    if(pingBusy)return;pingBusy=true;
    (async()=>{
     const d=await chrome.storage.local.get({monitoredTabId:null,monitorSessionId:'',running:false,successWatch:false});
     if(!(d.running||d.successWatch)||d.monitoredTabId===null)return;
     const r=await chrome.tabs.sendMessage(d.monitoredTabId,{type:'SARATHI_HEALTH_PING',sessionId:d.monitorSessionId});
     const now=await chrome.storage.local.get({monitorSessionId:'',running:false,successWatch:false});
     if(r?.ok&&r.owner&&r.sessionId===d.monitorSessionId&&now.monitorSessionId===d.monitorSessionId&&(now.running||now.successWatch))
      await chrome.storage.local.set({heartbeatAt:Date.now(),monitorProgressAt:r.lastLoopProgress||0,monitorLoopActive:r.loopActive===true});
    })().catch(()=>{}).finally(()=>{pingBusy=false;});return;
   }
   commandQueue=commandQueue.then(()=>command(m)).catch(()=>port?.postMessage({type:'ERROR',message:'Android control failed — restart the app'}));
  });
  port.onDisconnect.addListener(()=>{
   port=null;foreground=false;
   chrome.storage.local.set({androidForeground:false,androidServiceReady:false}).then(()=>stop('App control disconnected — stopped')).catch(()=>{});
   setTimeout(connect,1500);
  });
  port.postMessage({type:'READY',monitorUrl:browser.runtime.getURL('monitor.html')});
  await state();
 }
 chrome.storage.onChanged.addListener((changes,area)=>{
  if(area==='local' && Object.keys(defaults).some(k=>changes[k]))scheduleState();
 });
 chrome.runtime.onMessage.addListener((m,sender,reply)=>{
  if(!sender.url?.startsWith(browser.runtime.getURL('')))return;
  if(m?.type==='ANDROID_ARM'){nativeRequest({type:'ARM'}).then(async r=>{if(r?.ok)await chrome.storage.local.set({androidServiceReady:true});reply(r);}).catch(e=>reply({ok:false,error:String(e.message||e)}));return true;}
  if(m?.type==='ANDROID_UI_READY'){nativeRequest({type:'UI_READY'}).then(reply);return true;}
  if(m?.type!=='ANDROID_EXPORT')return;
  nativeRequest({type:'SHARE',text:String(m.text||'').slice(0,60000)})
   .then(reply).catch(()=>reply({ok:false}));
  return true;
 });
 // Reset runtime flags only; keep user settings, history, counts and uncertain booking lock.
 chrome.storage.local.set({running:false,successWatch:false,autoBookingEnabled:false,
  androidForeground:false,androidServiceReady:false,alarmActive:false,nextRetryAt:0,monitorLoopActive:false,
  bookingStage:'STOPPED',lastStatus:'App opened — navigate to COV and press START'})
  .then(connect);
})();

