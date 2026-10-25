// The privileged background alone talks to Android. No content-native permission.
(() => {
 let port=null, foreground=false, commandQueue=Promise.resolve();
 const defaults={running:false,successWatch:false,bookingStage:'STOPPED',lastStatus:'Ready',
  attempts:0,sessionAttempts:0,alarmActive:false,bookingClickIssued:false,androidForeground:false};
 async function state(){
  const d=await chrome.storage.local.get(defaults);
  port?.postMessage({type:'STATE',...d});
 }
 async function stop(reason){
  await inGateQueue(()=>chrome.storage.local.set({running:false,successWatch:false,
   autoBookingEnabled:false,nextRetryAt:0,monitorLoopActive:false,bookingStage:'STOPPED',lastStatus:reason}));
  const d=await chrome.storage.local.get({monitoredTabId:null});
  if(d.monitoredTabId!==null)await chrome.tabs.sendMessage(d.monitoredTabId,{type:'SARATHI_STOP'}).catch(()=>{});
 }
 async function command(m){
  if(m.type==='SMOKE_RUN' && typeof globalThis.runAndroidSmoke==='function'){globalThis.runAndroidSmoke();return;}
  if(m.type==='FOREGROUND'){
   foreground=m.active===true;
   await chrome.storage.local.set({androidForeground:foreground});
   // Never silently resume a previous session after Android paused or killed it.
   if(!foreground)await stop('App left or screen locked — stopped; return and press START');
  } else if(m.type==='STOP') await stop('Stopped by user');
  else if(m.type==='SILENCE')await chrome.storage.local.set({alarmActive:false});
  else if(m.type==='FAILURE'){
   const d=await chrome.storage.local.get({monitorSessionId:'',monitoredTabId:null});
   await reportMonitorFailure(d,'ANDROID_PAGE_ERROR','Browser page failed — inspect and restart manually');
  }
  await state();
  if(m.id)port?.postMessage({type:'ACK',id:m.id});
 }
 async function connect(){
  port=browser.runtime.connectNative('sarathi');
  port.onMessage.addListener(m=>{
   commandQueue=commandQueue.then(()=>command(m)).catch(()=>port?.postMessage({type:'ERROR',message:'Android control failed — restart the app'}));
  });
  port.onDisconnect.addListener(()=>{
   port=null;foreground=false;
   chrome.storage.local.set({androidForeground:false}).then(()=>stop('App control disconnected — stopped')).catch(()=>{});
   setTimeout(connect,1500);
  });
  port.postMessage({type:'READY',monitorUrl:browser.runtime.getURL('monitor.html')});
  await state();
 }
 chrome.storage.onChanged.addListener((changes,area)=>{
  if(area==='local' && Object.keys(defaults).some(k=>changes[k]))state().catch(()=>{});
 });
 chrome.runtime.onMessage.addListener((m,sender,reply)=>{
  if(m?.type!=='ANDROID_EXPORT' || !sender.url?.startsWith(browser.runtime.getURL('')))return;
  browser.runtime.sendNativeMessage('sarathi',{type:'SHARE',text:String(m.text||'').slice(0,60000)})
   .then(reply).catch(()=>reply({ok:false}));
  return true;
 });
 // Reset runtime flags only; keep user settings, history, counts and uncertain booking lock.
 chrome.storage.local.set({running:false,successWatch:false,autoBookingEnabled:false,
  androidForeground:false,alarmActive:false,nextRetryAt:0,monitorLoopActive:false,
  bookingStage:'STOPPED',lastStatus:'App opened — navigate to COV and press START'})
  .then(connect);
})();
