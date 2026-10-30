// The privileged background alone talks to Android. No content-native permission.
(() => {
 let port=null, foreground=false, commandQueue=Promise.resolve();
 const defaults={running:false,successWatch:false,bookingStage:'STOPPED',lastStatus:'Ready',
  attempts:0,sessionAttempts:0,alarmActive:false,bookingClickIssued:false,androidForeground:false,androidServiceReady:false,heartbeatAt:0,monitorProgressAt:0,lastCheck:0,calendarLock:false,lastCycleMs:0,cycleTotal:0,cycleCount:0,selectedCovCount:0,verificationRetries:0,protectPage:false,pageReason:"",url:""};
 async function tabRequest(id,message){
  let timer;try{return await Promise.race([chrome.tabs.sendMessage(id,message),new Promise((_,reject)=>{timer=setTimeout(()=>reject(Error('Page reply timed out')),5000);})]);}finally{clearTimeout(timer);}
 }
 let stateTimer=null;
 function scheduleState(){if(stateTimer!==null)return;stateTimer=setTimeout(()=>{stateTimer=null;state().catch(()=>{});},250);}
 async function state(){
  const d=await chrome.storage.local.get(defaults);
  port?.postMessage({type:'STATE',...d,protectPage:!!(d.protectPage||d.calendarLock||d.successWatch)});
 }
 async function stop(reason){
  await inGateQueue(()=>chrome.storage.local.set({androidStopEpoch:String(Date.now())+'-'+Math.random(),running:false,successWatch:false,
   autoBookingEnabled:false,nextRetryAt:0,monitorLoopActive:false,bookingStage:'STOPPED',lastStatus:reason}));
  const d=await chrome.storage.local.get({monitoredTabId:null});
  if(d.monitoredTabId!==null)chrome.tabs.sendMessage(d.monitoredTabId,{type:'SARATHI_STOP'}).catch(()=>{});
 }
 async function restoreSettings(){const r=await nativeRequest({type:"SETTINGS_GET"});if(r?.ok&&r.values)await chrome.storage.local.set({...r.values,autoBookingEnabled:false,telegramConfigRevision:Date.now()+"-import"});}
 async function command(m){
  if(m.type==="SETTINGS_RESTORE"){await restoreSettings();return;}
  if(m.type==='START_READY'){
   const result=await chrome.runtime.sendMessage({type:'ANDROID_START_READY',batch:m.reason});
   port?.postMessage({type:'START_RESULT',batch:m.reason,ok:result?.ok===true,reason:result?.reason||'Monitor page unavailable'});return;
  }
  if(m.type==='PAGE_QUERY'){
   const separator=String(m.reason).indexOf('|'),queryId=String(m.reason).slice(0,separator),queryUrl=String(m.reason).slice(separator+1);
   const tabs=await chrome.tabs.query({url:'https://sarathi.parivahan.gov.in/*'});
   for(const tab of tabs){try{const r=await tabRequest(tab.id,{type:'SARATHI_PROFILE_QUERY'});if(r?.ok&&r.ready&&r.page===queryUrl){port?.postMessage({type:'PAGE_READY',id:queryId,url:r.page,covReady:r.covReady,protectPage:r.protectPage,reason:r.reason});return;}}catch(_) {}}
   return;
  }
  if(m.type==='DISARMED'){await chrome.storage.local.set({androidServiceReady:false});const d=await chrome.storage.local.get({running:false,successWatch:false});if(d.running||d.successWatch)await stop('Background service stopped — press START to begin again');return;}
  if(m.type==='AUDIT_SMOKE'&&typeof globalThis.auditProfileIsolation==='function'){globalThis.auditProfileIsolation();return;}
  if(m.type==='MULTI_SMOKE'&&typeof globalThis.runMultiSmoke==='function'){globalThis.runMultiSmoke();return;}
  if(m.type==='SMOKE_RUN' && typeof globalThis.runAndroidSmoke==='function'){globalThis.runAndroidSmoke();return;}
  if(m.type==='FOREGROUND'){
   foreground=m.active===true;
   await chrome.storage.local.set({androidForeground:foreground});
   // Visibility is independent of service authorization. Minimize/lock keeps the run alive.
  } else if(m.type==='STOP'){await chrome.storage.local.set({androidServiceReady:false});await stop('Stopped by user');await nativeRequest({type:'DISARM'});}
  else if(m.type==='SAFE_STOP'){await chrome.storage.local.set({androidServiceReady:false});await stop(m.reason||'Monitoring stopped — press START');}
  else if(m.type==='SILENCE'){await nativeRequest({type:'ACK_ALERT'});await chrome.storage.local.set({alarmActive:false});}
  else if(m.type==='FAILURE'){
   await chrome.storage.local.set({androidServiceReady:false});
   const d=await chrome.storage.local.get({monitorSessionId:'',monitoredTabId:null});
   await reportMonitorFailure(d,'ANDROID_PAGE_ERROR',m.reason||'Browser page failed — inspect and restart manually',0,true);
  }
  await state();
  if(m.id)port?.postMessage({type:'ACK',id:m.id});
 }
 async function connect(){
  port=browser.runtime.connectNative('sarathi');
  let pingBusy=false;
  port.onMessage.addListener(m=>{
   // Page readiness and a pending START must never hold up STOP/SILENCE.
   if(m.type==='START_READY'||m.type==='PAGE_QUERY'){command(m).catch(e=>port?.postMessage({type:'ERROR',message:String(e.message||e)}));return;}
   if(m.type==='RECOVER_CHECK'){
    // Out of the control queue: a stale tab message must not block recovery or STOP.
    (async()=>{
     const d=await chrome.storage.local.get({monitoredTabId:null,monitorSessionId:'',running:false,successWatch:false,calendarLock:false,bookingClickIssued:false});
     let safe=false,terminal=false,reason='Page has not confirmed a safe COV state';
     if(d.running&&!d.calendarLock&&!d.bookingClickIssued&&d.monitoredTabId!==null&&d.monitorSessionId){
      let r=await tabRequest(d.monitoredTabId,{type:'SARATHI_HEALTH_PING',sessionId:d.monitorSessionId});
      if(r?.ok&&r.owner&&r.sessionId===d.monitorSessionId&&r.safeToRestart){
       const live=await chrome.storage.local.get({running:false,monitorSessionId:'',monitoredTabId:null,calendarLock:false,bookingClickIssued:false});
       if(live.running&&live.monitorSessionId===d.monitorSessionId&&live.monitoredTabId===d.monitoredTabId&&!live.calendarLock&&!live.bookingClickIssued){
        await tabRequest(d.monitoredTabId,{type:'SARATHI_RECOVER',sessionId:d.monitorSessionId});
        r=await tabRequest(d.monitoredTabId,{type:'SARATHI_HEALTH_PING',sessionId:d.monitorSessionId});
       }
      }
      if(r?.expired||r?.locked||r?.calendar){terminal=true;reason='Session expired or calendar/manual input requires attention';}
      const now=await chrome.storage.local.get({monitoredTabId:null,monitorSessionId:'',running:false,calendarLock:false,bookingClickIssued:false});
      safe=!!(r?.ok&&r.owner&&r.safeToResume&&r.sessionId===d.monitorSessionId&&now.running&&!now.calendarLock&&!now.bookingClickIssued&&now.monitoredTabId===d.monitoredTabId&&now.monitorSessionId===d.monitorSessionId);
     }
     if(d.bookingClickIssued||d.calendarLock){terminal=true;reason='Booking/calendar lock preserved; inspect manually';}
     port?.postMessage({type:'RECOVERED',id:m.reason,safe,terminal,reason});
    })().catch(e=>port?.postMessage({type:'RECOVERED',id:m.reason,safe:false,terminal:false,reason:String(e.message||e)}));return;
   }
   if(m.type==='PING'){
    if(pingBusy)return;pingBusy=true;
    (async()=>{
     const d=await chrome.storage.local.get({monitoredTabId:null,monitorSessionId:'',running:false,successWatch:false});
     if(!(d.running||d.successWatch)||d.monitoredTabId===null)return;
     let timer;const r=await Promise.race([chrome.tabs.sendMessage(d.monitoredTabId,{type:'SARATHI_HEALTH_PING',sessionId:d.monitorSessionId}),new Promise(resolve=>{timer=setTimeout(()=>resolve(null),5000);})]).finally(()=>clearTimeout(timer));
     const now=await chrome.storage.local.get({monitorSessionId:'',running:false,successWatch:false});
     if(r?.ok&&r.owner&&r.sessionId===d.monitorSessionId&&now.monitorSessionId===d.monitorSessionId&&(now.running||now.successWatch))
      await chrome.storage.local.set({heartbeatAt:Date.now(),monitorProgressAt:r.lastLoopProgress||0,monitorLoopActive:r.loopActive===true,protectPage:r.protectPage===true,pageReason:r.pageReason||"",url:r.page||""});
    })().catch(()=>{}).finally(()=>{pingBusy=false;});return;
   }
   commandQueue=commandQueue.then(()=>command(m)).catch(()=>port?.postMessage({type:'ERROR',message:'Android control failed — restart the app'}));
  });
  port.onDisconnect.addListener(()=>{
   port=null;foreground=false;
   // A transient port reconnect does not erase a service-owned run. Native ownership
   // still gates every action; the service fails closed if reconnect takes >15s.
   setTimeout(connect,1500);
  });
  port.postMessage({type:'READY',monitorUrl:browser.runtime.getURL('monitor.html')});
  await state();
 }
 chrome.storage.onChanged.addListener((changes,area)=>{
  const optionKeys=['covSelection','assistedBookingPreference','telegramEnabled','telegramStatusEnabled','soundEnabled','desktopEnabled','volume'];
  if(area==='local'&&optionKeys.some(k=>changes[k])){const values={};for(const k of optionKeys)if(changes[k])values[k]=changes[k].newValue;nativeRequest({type:'SETTINGS_SET',values}).catch(()=>{});}
  if(area==='local' && Object.keys(defaults).some(k=>changes[k])){if(changes.running||changes.successWatch)state().catch(()=>{});else scheduleState();}
 });
 chrome.runtime.onMessage.addListener((m,sender,reply)=>{
  if(!sender.url?.startsWith(browser.runtime.getURL('')))return;
  if(m?.type==='ANDROID_STOP'){nativeRequest({type:'DISARM'}).then(reply);return true;}
  if(m?.type==='ANDROID_ARM'){nativeRequest({type:'ARM',batch:m.batch||''}).then(async r=>{if(r?.ok)await chrome.storage.local.set({androidServiceReady:true});reply(r);}).catch(e=>reply({ok:false,error:String(e.message||e)}));return true;}
  if(m?.type==='ANDROID_UI_READY'){nativeRequest({type:'UI_READY'}).then(reply);return true;}
  if(m?.type!=='ANDROID_EXPORT')return;
  nativeRequest({type:'SHARE',text:String(m.text||'').slice(0,60000)})
   .then(reply).catch(()=>reply({ok:false}));
  return true;
 });
 // Reset runtime flags only; keep user settings, history, counts and uncertain booking lock.
 (async()=>{const owner=await nativeRequest({type:'RESTORE'}).catch(()=>null);if(owner?.ok&&owner.armed){await chrome.storage.local.set({androidServiceReady:true});return;}await chrome.storage.local.set({running:false,successWatch:false,autoBookingEnabled:false,
  androidForeground:false,androidServiceReady:false,alarmActive:false,nextRetryAt:0,monitorLoopActive:false,
  bookingStage:'STOPPED',lastStatus:'App opened — navigate to COV and press START'});})()
  .then(restoreSettings).then(connect);
})();




