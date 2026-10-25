// Android delivery replaces Chrome desktop notifications and offscreen audio.
async function showDesktopNotification(options, id) {
 const result=await nativeRequest({type:'NOTIFY',title:options.title,message:options.message});
 if(!result?.ok)throw Error(result?.error||'Android notifications unavailable');
 return id||'android-alert';
}
const PRIMARY_CHAT_ID = '';
// Serialize critical authorization and one-time booking reservations in the MV3 worker.
let gateQueue=Promise.resolve();
let alertQueue=Promise.resolve();
function inAlertQueue(work){const run=alertQueue.then(work,work);alertQueue=run.catch(()=>{});return run;}
function inGateQueue(work){
 const run=gateQueue.then(work,work);
 gateQueue=run.catch(()=>{});
 return run;
}
async function authorizeAction(msg,sender){
 const d=await chrome.storage.local.get({androidServiceReady:false,running:false,monitoredTabId:null,monitorSessionId:'',calendarLock:false,bookingClickIssued:false,autoBookingEnabled:false,lastProceedGateAt:0,bookingReservationId:'',bookingReservationDocument:'',bookingDispatchStarted:false,bookingSubmittedAt:0,bookingResponseWarned:false});
 if(sender.tab?.id!==d.monitoredTabId || !d.monitorSessionId || msg.sessionId!==d.monitorSessionId)
   return {authorized:false,error:'Different monitored tab or session'};
 const action=String(msg.action||'');
 if(['BOOK_SLOT_CANCEL','BOOK_SLOT_DISPATCHED'].includes(action)){
   if(!msg.reservationId || msg.reservationId!==d.bookingReservationId || (d.bookingReservationDocument && (sender.documentId || msg.documentNonce)!==d.bookingReservationDocument))return {authorized:false,error:'Reservation mismatch'};
   if(action==='BOOK_SLOT_DISPATCHED'){await chrome.storage.local.set({bookingDispatchStarted:true,bookingSubmittedAt:Date.now(),bookingResponseWarned:false});return {authorized:true};}
   if(msg.noClick!==true || d.bookingDispatchStarted)return {authorized:false,error:'Cannot release dispatched booking'};
   await chrome.storage.local.set({bookingClickIssued:false,bookingReservationId:'',bookingReservationDocument:'',bookingDispatchStarted:false,bookingSubmittedAt:0,bookingResponseWarned:false});
   return {authorized:true};
 }
 if(!d.androidServiceReady)return {authorized:false,error:'Background service is not ready'};
 if(!(await nativeRequest({type:'AUTHORIZE'}))?.ok)return {authorized:false,error:'Native service stopped'};
 if(!d.running)return {authorized:false,error:'Different monitored tab or session'};
 const booking=['DATE_SELECT','QUOTA_SELECT','QUOTA_BACK','BOOK_SLOT','BOOK_SLOT_CONFIRM'].includes(action);
 if(booking && (!d.calendarLock||!d.autoBookingEnabled))return {authorized:false,error:'Assisted booking disabled or calendar not locked'};
 if(!booking && d.calendarLock)return {authorized:false,error:'Calendar detected: monitoring click cancelled'};
 if(action==='BOOK_SLOT_CONFIRM')return {authorized:!!d.bookingClickIssued&&!!msg.reservationId&&msg.reservationId===d.bookingReservationId&&(!d.bookingReservationDocument||(sender.documentId || msg.documentNonce)===d.bookingReservationDocument)&&!d.bookingDispatchStarted&&d.calendarLock&&d.autoBookingEnabled,error:'Booking intent not authorized'};
 if(action==='PROCEED_CONFIRM')return {authorized:true};
 if(action==='BOOK_SLOT'){
   if(d.bookingClickIssued)return {authorized:false,error:'BOOK SLOT has already been submitted or its outcome is uncertain'};
   const date=String(msg.date||'');
   if(typeof msg.reservationId!=='string'||msg.reservationId.length<8||msg.reservationId.length>200)return {authorized:false,error:'Missing reservation token'};
   if(!/^\d{4}-\d{2}-\d{2}$/.test(date) || !['LMV','MCWG','BOTH'].includes(msg.kind))return {authorized:false,error:'Missing verified booking target'};
   await chrome.storage.local.set({bookingClickIssued:true,bookingReservationId:msg.reservationId,bookingReservationDocument:(sender.documentId || msg.documentNonce)||'',bookingDispatchStarted:false,bookingSubmittedAt:Date.now(),bookingResponseWarned:false,bookingStage:'BOOK SLOT SUBMISSION PENDING',lastStatus:'BOOK SLOT authorized once; outcome awaiting Sarathi response',nextRetryAt:0});
   return {authorized:true};
 }
 if(action==='PROCEED'){
   if(Date.now()-d.lastProceedGateAt<6000)return {authorized:false,error:'Proceed already authorized recently'};
   await chrome.storage.local.set({lastProceedGateAt:Date.now()});
   return {authorized:true};
 }
 if(!['COV_SELECT','POPUP_BACK','DATE_SELECT','QUOTA_SELECT','QUOTA_BACK'].includes(action))return {authorized:false,error:'Unknown page action'};
 return {authorized:true};
}
const api = 'https://api.telegram.org/bot';
chrome.storage.local.get({chatId:''}).then(d=>{if(!d.chatId) return chrome.storage.local.set({chatId:PRIMARY_CHAT_ID});}).catch(()=>{});
// Configure the bot token locally in the TELEGRAM tab. Never distribute a live token.

async function sendTelegram(message) {
  const {chatId='',chatId2='',botToken=''} = await chrome.storage.local.get({chatId:'',chatId2:'',botToken:''});
  if (!/^\d{6,}:[A-Za-z0-9_-]{20,}$/.test(botToken.trim())) throw Error('Configure Telegram bot token in the TELEGRAM tab.');
  const ids = [...new Set([(chatId.trim() || PRIMARY_CHAT_ID), chatId2.trim()].filter(Boolean))];
  if (!ids.length || ids.some(id => !/^-?\d+$/.test(id))) throw Error('Enter valid numeric Chat IDs.');
  const results = await Promise.allSettled(ids.map(async id => {
    const started=Date.now();
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 12000);
    let response,result;
    try {
      response = await fetch(api + botToken.trim() + '/sendMessage', {
        method:'POST', headers:{'Content-Type':'application/json'}, signal:controller.signal,
        body:JSON.stringify({chat_id:id,text:message,disable_notification:false})
      });
      result = await response.json();
    } finally { clearTimeout(timeout); }
    if (!response.ok || !result.ok) throw Error('Telegram API rejected recipient '+(ids.indexOf(id)+1)+' (HTTP '+response.status+', error '+(result.error_code||'unknown')+'). Check token, recipient and whether bot was started.');
    return {id,ms:Date.now()-started};
  }));
  const errors = results.filter(r=>r.status==='rejected').map(r=>String(r.reason));
  await chrome.storage.local.set({telegramDeliveryStatus: results.map((r,i)=>'Recipient '+(i+1)+': '+(r.status==='fulfilled'?'API accepted in '+r.value.ms+'ms':'failed: '+String(r.reason?.message||r.reason).replace(/\d{6,}:[A-Za-z0-9_-]{20,}/g,'[REDACTED]').slice(0,100))).join(' | '),telegramLastAttempt:Date.now()});
  if (errors.length) throw Error(errors.join('; '));
  return true;
}

async function audioCommand(action){
 const settings=await chrome.storage.local.get({soundEnabled:true,volume:85});
 const result=await nativeRequest({type:'AUDIO',action,settings});
 if(!result?.ok)throw Error(result?.error||'Android audio unavailable');
 return result;
}
let audioStateQueue=Promise.resolve();
chrome.storage.onChanged.addListener((changes,area)=>{
 if(area!=='local'||!(changes.alarmActive||changes.soundEnabled||changes.volume))return;
 const shouldRestart=!!(changes.alarmActive||changes.soundEnabled);
 audioStateQueue=audioStateQueue.then(async()=>{
   const d=await chrome.storage.local.get({alarmActive:false,soundEnabled:true,volume:85});
   // Changes to volume alone need not restart an already running repeating alarm.
   if(shouldRestart)await audioCommand(d.alarmActive&&d.soundEnabled?'START':'STOP');
   else if(d.alarmActive)await audioCommand('UPDATE');
   await chrome.storage.local.set({audioError:''});
 }).catch(e=>chrome.storage.local.set({audioError:String(e.message||e)}).catch(()=>{}));
});

chrome.runtime.onMessage.addListener((msg,sender,reply)=>{
  if(msg?.type==='SARATHI_WAIT'){
    (async()=>{
      const valid=async()=>{
        const d=await chrome.storage.local.get({running:false,monitoredTabId:null,monitorSessionId:''});
        return d.running && sender.tab?.id===d.monitoredTabId && !!msg.sessionId && msg.sessionId===d.monitorSessionId;
      };
      if(!Number.isFinite(msg.ms)||msg.ms<0||msg.ms>8000||!await valid())return {cancelled:true};
      const nativeWait=await nativeRequest({type:"WAIT",ms:msg.ms});
      if(!nativeWait?.ok)return {cancelled:true};
      if(!await valid())return {cancelled:true};
      await chrome.storage.local.set({heartbeatAt:Date.now(),...(Number.isFinite(msg.monitorProgressAt)?{monitorProgressAt:msg.monitorProgressAt,monitorLoopActive:msg.monitorLoopActive===true}:{})});
      return {ok:true};
    })().then(reply).catch(()=>reply({cancelled:true}));
    return true;
  }
  if(msg?.type==='SARATHI_OWNERSHIP'){
    chrome.storage.local.get({monitoredTabId:null,running:false,successWatch:false,monitorSessionId:''}).then(d=>reply({authorized:!!d.monitorSessionId&&(!!d.running||!!d.successWatch)&&sender.tab?.id===d.monitoredTabId,sessionId:d.monitorSessionId})).catch(()=>reply({authorized:false}));
    return true;
  }
  if(msg?.type==='SARATHI_ACTION_GATE'){
    inGateQueue(()=>authorizeAction(msg,sender)).then(reply).catch(e=>reply({authorized:false,error:String(e.message||e)}));
    return true;
  }
  if(msg?.type==='AUDIO_CONTROL'){(async()=>{if(msg.action==='ARM')await audioCommand('ARM');else if(msg.action==='TEST')await audioCommand('TEST');else if(msg.action==='STOP')await audioCommand('STOP');else throw Error('Unknown audio action');})().then(()=>reply({ok:true})).catch(e=>reply({ok:false,error:String(e.message||e)}));return true;}

  if (!['SARATHI_ALERT','TEST_TELEGRAM','TEST_DESKTOP','SARATHI_BOOKED','SARATHI_WARNING','SARATHI_SMS_READY'].includes(msg?.type)) return;
  const processAlert=async()=>{
    if(msg.type==='TEST_DESKTOP'){return {delivery:showDesktopNotification({type:'basic',iconUrl:'icon.png',title:'Sarathi TEST Desktop Alert',message:'Desktop notifications are working. This is a test, not a real slot.'},'sarathi-test-'+Date.now())};}
    if(msg.type==='TEST_TELEGRAM'){return {delivery:sendTelegram('🔔 Sarathi Anantnag TEST ALERT — phone notifications are connected.')}}
    if (!sender.url?.startsWith('https://sarathi.parivahan.gov.in/')) return;
    const owner=await chrome.storage.local.get({monitoredTabId:null,monitorSessionId:'',running:false,successWatch:false});
    if(!msg.sessionId || msg.sessionId!==owner.monitorSessionId || sender.tab?.id!==owner.monitoredTabId || (!owner.running&&!owner.successWatch))return;
    if(msg.type==='SARATHI_SMS_READY') {
      const d=await chrome.storage.local.get({monitoredTabId:null,monitorSessionId:'',telegramEnabled:true,desktopEnabled:true,history:[],smsAlertKey:''});
      if(sender.tab?.id!==d.monitoredTabId)return;
      const key=String(d.monitorSessionId)+':'+String(sender.tab.id)+':'+String(msg.url||'');
      if(d.smsAlertKey===key)return;
      const body='🔐 SARATHI ANANTNAG: SMS VERIFICATION READY — enter the code and finish confirmation manually.';
      await chrome.storage.local.set({smsAlertKey:key,alarmActive:true,alarmMessage:'SMS verification ready',history:[{time:Date.now(),kind:'SMS_READY',message:body},...d.history].slice(0,100)});
      return {delivery:Promise.allSettled([d.desktopEnabled?showDesktopNotification({type:'basic',iconUrl:'icon.png',title:'Sarathi SMS Verification Ready',message:body}):Promise.resolve(),d.telegramEnabled?sendTelegram(body).catch(e=>chrome.storage.local.set({telegramError:String(e.message||e)})):Promise.resolve()])};
    }
    if(msg.type==='SARATHI_WARNING') {
      const d=await chrome.storage.local.get({monitoredTabId:null,telegramEnabled:true,desktopEnabled:true,warningAt:0,warningKind:'',warningEvents:[],scanPending:'',history:[]});
      if(sender.tab?.id!==d.monitoredTabId)return;
      const now=Date.now(),kind=String(msg.kind||'SESSION').slice(0,30);
      const reason=String(msg.message||'Monitoring requires attention').replace(/\s+/g,' ').trim().slice(0,180);
      const warningKey=JSON.stringify([msg.sessionId,kind,reason,d.scanPending]);
      const recent=(Array.isArray(d.warningEvents)?d.warningEvents:[]).filter(e=>e&&Number.isFinite(e.time)&&now-e.time<10*60*1000);
      if(recent.some(e=>e.key===warningKey))return;
      const warningEvents=[...recent,{key:warningKey,time:now}].slice(-100);
      const body='⚠️ SARATHI ANANTNAG — '+reason;
      await chrome.storage.local.set({warningAt:now,warningKind:kind,warningEvents,lastStatus:body,...(['SESSION_EXPIRED','SESSION','BOOKING_REVIEW'].includes(kind)?{alarmActive:true,alarmMessage:reason}:{}),history:[{time:now,kind:'WARNING',message:body},...d.history].slice(0,100)});
      return {delivery:Promise.allSettled([
        d.desktopEnabled?showDesktopNotification({type:'basic',iconUrl:'icon.png',title:'Sarathi Monitoring Warning',message:body}):Promise.resolve(),
        d.telegramEnabled?sendTelegram(body).catch(e=>chrome.storage.local.set({telegramError:String(e.message||e)})):Promise.resolve()
      ])};
    }
    if(msg.type==='SARATHI_BOOKED') {
      const settings=await chrome.storage.local.get({monitoredTabId:null,successWatch:false,telegramEnabled:true,desktopEnabled:true,history:[]});
      if(sender.tab?.id!==settings.monitoredTabId || !settings.successWatch) return;
      if(!msg.bookingKey)return;
      const bookingKey=msg.sessionId+':'+String(msg.bookingKey).slice(0,160);
      const previous=await chrome.storage.local.get({bookingAlertKeys:[]});
      if(previous.bookingAlertKeys.includes(bookingKey)) return;
      // Persist deduplication BEFORE network I/O to avoid duplicate alerts during navigation.
      await chrome.storage.local.set({bookingAlertKeys:[...previous.bookingAlertKeys,bookingKey].slice(-30),successWatch:false,running:false,alarmActive:true,alarmMessage:'Appointment booked successfully',lastStatus:'BOOKING CONFIRMED — manual appointment completed',bookingStage:'APPOINTMENT CONFIRMED'});
      const detail=[msg.cov&&('COV: '+msg.cov),msg.slotDate&&('Date: '+msg.slotDate),msg.slotTime&&('Time: '+msg.slotTime)].filter(Boolean).join('\n');
      const body='✅ SARATHI ANANTNAG — SLOT BOOKED SUCCESSFULLY!\nConfirmed by Sarathi appointment receipt.\n'+detail;
      const history=[{time:Date.now(),kind:'BOOKING_SUCCESS',message:body},...settings.history].slice(0,100);
      await chrome.storage.local.set({history,lastAlertTime:Date.now()});
      return {delivery:Promise.allSettled([
        settings.desktopEnabled?showDesktopNotification({type:'basic',iconUrl:'icon.png',title:'Sarathi Appointment BOOKED',message:body}):Promise.resolve(),
        settings.telegramEnabled?sendTelegram(body).then(()=>chrome.storage.local.set({telegramError:''})).catch(e=>chrome.storage.local.set({telegramError:String(e.message||e)})):Promise.resolve()
      ])};
    }
    const {running=false,calendarLock=false}=await chrome.storage.local.get(['running','calendarLock']);if(!running&&!calendarLock)return;
    if(sender.tab?.id !== (await chrome.storage.local.get('monitoredTabId')).monitoredTabId)return;
    const kind=['BOTH_AVAILABLE','LMV_AVAILABLE','MCWG_AVAILABLE','GREEN_DATE','CALENDAR'].includes(msg.kind)?msg.kind:'CALENDAR';
    const sid=(await chrome.storage.local.get({monitorSessionId:''})).monitorSessionId;
    const key=sid+':'+kind+':'+sender.tab?.id+':'+String(msg.eventKey||msg.url||'')+':'+(msg.quota??'?')+':'+(msg.mcwgQuota??'?');
    const now=Date.now();const stored=await chrome.storage.local.get(['alertKey','alertTime']);
    if(stored.alertKey===key && now-(stored.alertTime||0)<30*60*1000)return;
    await chrome.storage.local.set({alertKey:key,alertTime:now,alarmActive:true,alarmMessage:kind==='LMV_AVAILABLE'?'LMV slot detected':'Booking calendar detected'});
    const body=kind==='BOTH_AVAILABLE'?'🚨 SARATHI ANANTNAG: LMV and MCWG quota detected! Check the app NOW.':kind==='MCWG_AVAILABLE'?'🚨 SARATHI ANANTNAG: MCWG quota detected! Check the app NOW.':kind==='LMV_AVAILABLE'?`🚨 SARATHI ANANTNAG: LMV quota ${msg.quota} detected! Check the app NOW.`:kind==='GREEN_DATE'?'🟢 SARATHI ANANTNAG: Green date detected. Check the app NOW; LMV quota not yet confirmed.':'📅 SARATHI ANANTNAG: Booking calendar detected. Check the app NOW; slot not yet confirmed.';
    const hist=(await chrome.storage.local.get({history:[]})).history;hist.unshift({time:now,kind,message:body});await chrome.storage.local.set({history:hist.slice(0,100),lastAlertTime:now});
    const settings=await chrome.storage.local.get({desktopEnabled:true,telegramEnabled:true});
    return {delivery:Promise.allSettled([
      settings.desktopEnabled?showDesktopNotification({type:'basic',iconUrl:'icon.png',title:'Sarathi Anantnag Alert',message:body}):Promise.resolve(),
      settings.telegramEnabled?sendTelegram(body+'\n'+'https://sarathi.parivahan.gov.in/').then(()=>chrome.storage.local.set({telegramError:''})).catch(e=>chrome.storage.local.set({telegramError:String(e.message||e)})):Promise.resolve()
    ])}; 
  };
  // Events are processed sequentially so history and deduplication cannot overwrite
  // each other when several page-stage alerts arrive simultaneously.
  inAlertQueue(processAlert).then(result=>result?.delivery).then(()=>reply({ok:true})).catch(e=>{
    chrome.storage.local.set({telegramError:String(e.message||e)}).catch(()=>{});
    reply({ok:false,error:String(e.message||e)});
  });
  return true;
});

// Periodic health check independent of heartbeat freshness. A content script may be
// alive while its monitoring loop is stopped, or the browser may throttle timers.
chrome.alarms.create('sarathi-health', {periodInMinutes:1});
chrome.alarms.onAlarm.addListener(async alarm=>{
 if(alarm.name!=='sarathi-health')return;
 const d=await chrome.storage.local.get({running:false,successWatch:false,calendarLock:false,bookingClickIssued:false,heartbeatAt:0,monitorProgressAt:0,monitoredTabId:null,healthMisses:0,healthWarningAt:0,monitorSessionId:''});
 const current=async()=>{
   const now=await chrome.storage.local.get({running:false,successWatch:false,monitorSessionId:'',monitoredTabId:null,calendarLock:false,bookingClickIssued:false});
   return (now.running || now.successWatch) && now.running===d.running && now.calendarLock===d.calendarLock && now.bookingClickIssued===d.bookingClickIssued && now.monitorSessionId===d.monitorSessionId && now.monitoredTabId===d.monitoredTabId;
 };
 const update=async patch=>{if(await current())await chrome.storage.local.set(patch);};
 if(!d.running && !d.successWatch)return;
 let tab=null;
 try{if(d.monitoredTabId)tab=await chrome.tabs.get(d.monitoredTabId);}catch(_){}
 if(!await current())return;
 if(!tab || !tab.url?.startsWith('https://sarathi.parivahan.gov.in/')){
   await reportMonitorFailure(d,'TAB_DISCONNECTED','Monitored Sarathi tab is missing or has left the website. Reopen Sarathi and restart manually.');
   return;
 }
 const age=Date.now()-(d.heartbeatAt||0);
 let response=null;
 try{response=await Promise.race([chrome.tabs.sendMessage(tab.id,{type:'SARATHI_HEALTH_PING',sessionId:d.monitorSessionId}),new Promise(resolve=>setTimeout(()=>resolve(null),5000))]);}catch(_){}
 if(!await current())return;
 if(response?.ok && response.owner && response.sessionId===d.monitorSessionId){
   const progressAge=Date.now()-(response.lastLoopProgress||0);
   if(d.running && !d.calendarLock && !d.bookingClickIssued && response.covPresent && !response.calendar && (!response.loopActive || progressAge>90000)){
     try{
       const restarted=await chrome.tabs.sendMessage(tab.id,{type:'SARATHI_RECOVER',sessionId:d.monitorSessionId});
       if(restarted?.ok){
         await update({healthMisses:0,heartbeatAt:Date.now(),lastStatus:'COV monitoring recovery requested; verifying next cycle'});
         return;
       }
     }catch(_){}
   } else {
     if(d.healthMisses)await update({healthMisses:0});
     if(d.running && !d.calendarLock && response.loopActive && response.covPresent && progressAge>45000)
       await update({lastStatus:'COV loop progress delayed — waiting for page response; recovery checking'});
     return;
   }
 }
 if(!response?.ok && tab.status==='complete' && d.running && !d.calendarLock && !d.bookingClickIssued){
   try{
     // Host permission is required. This cannot bypass Chrome's user site-access controls.
     await chrome.scripting.executeScript({target:{tabId:tab.id},files:['booking-rules.js','content.js']});
     if(!await current())return;
     const restored=await chrome.tabs.sendMessage(tab.id,{type:'SARATHI_RECOVER',sessionId:d.monitorSessionId});
     if(restored?.ok){await update({healthMisses:0,lastStatus:'Content script reattached; verifying monitoring progress'});return;}
   }catch(error){
     const msg=String(error.message||error).slice(0,110);
     await update({lastStatus:'Sarathi access or content script unavailable — check Site access: '+msg});
   }
 }
 const misses=(d.healthMisses||0)+1;
 await update({healthMisses:misses,lastStatus:'Sarathi monitoring response missing — check '+misses+'/3'});
 if(misses<3 || Date.now()-d.healthWarningAt<15*60*1000)return;
 await reportMonitorFailure(d,'MONITOR_STALLED','Sarathi has not responded to three health checks. Monitoring stopped; check the page and restart manually.');
});

// V11.46: browser-owned error pages cannot report through content.js.
// Failure state is committed before independent desktop / Telegram delivery.
function sarathiUrl(value){
 try{const u=new URL(value);return u.protocol==='https:' && u.hostname==='sarathi.parivahan.gov.in';}catch(_){return false;}
}
async function reportMonitorFailure(expected,code,reason,eventTime=0){
 const result=await inAlertQueue(()=>inGateQueue(async()=>{
  const d=await chrome.storage.local.get({running:false,successWatch:false,monitoredTabId:null,monitorSessionId:'',navigationSessionId:'',navigationLastAt:0,navigationCompletedAt:0,monitorFailureKey:'',bookingClickIssued:false,history:[],desktopEnabled:true,telegramEnabled:true});
  if(!d.monitorSessionId || d.monitorSessionId!==expected.monitorSessionId || d.monitoredTabId!==expected.monitoredTabId || (!d.running&&!d.successWatch))return null;
  // Bind navigation to its monitoring session; compare browser timestamps only.
  // Chrome documents that event timestamps may differ from extension Date.now().
  if(eventTime && (d.navigationSessionId!==d.monitorSessionId || eventTime<d.navigationLastAt || eventTime<=d.navigationCompletedAt))return null;
  const key=d.monitorSessionId+':'+code;
  if(d.monitorFailureKey===key)return null;
  const body='⚠️ SARATHI ANANTNAG — '+reason+(d.bookingClickIssued?' BOOK SLOT outcome may be uncertain; inspect manually. Submission lock retained.':'');
  await chrome.storage.local.set({running:false,successWatch:false,autoBookingEnabled:false,nextRetryAt:0,monitorLoopActive:false,healthMisses:0,monitorFailureKey:key,monitorFailureAt:Date.now(),bookingStage:'MANUAL REVIEW REQUIRED',lastStatus:body,alarmActive:true,alarmMessage:reason,history:[{time:Date.now(),kind:code,message:body},...d.history].slice(0,100)});
  return {key,body,desktopEnabled:d.desktopEnabled,telegramEnabled:d.telegramEnabled};
 }));
 if(!result)return false;
 // Do not let a slow/failed Telegram request block another event or local alarm.
 const delivery=await Promise.allSettled([
  result.desktopEnabled?showDesktopNotification({type:'basic',iconUrl:'icon.png',title:'Sarathi monitoring needs attention',message:result.body}):Promise.resolve(),
  result.telegramEnabled?sendTelegram(result.body):Promise.resolve()
 ]);
 const still=await chrome.storage.local.get({monitorFailureKey:''});
 if(still.monitorFailureKey===result.key){
  await chrome.storage.local.set({failureDesktopError:delivery[0].status==='rejected'?String(delivery[0].reason?.message||'Desktop alert failed').slice(0,160):'',failureTelegramError:delivery[1].status==='rejected'?String(delivery[1].reason?.message||'Telegram alert failed').replace(/\d{6,}:[A-Za-z0-9_-]{20,}/g,'[REDACTED]').slice(0,160):''});
 }
 return true;
}
async function recordNavigation(details,completed){
 if(details.frameId!==0 || !sarathiUrl(details.url) || !Number.isFinite(details.timeStamp))return;
 await inAlertQueue(async()=>{
  const d=await chrome.storage.local.get({running:false,successWatch:false,monitoredTabId:null,monitorSessionId:'',navigationSessionId:'',navigationLastAt:0,navigationCompletedAt:0});
  if(details.tabId!==d.monitoredTabId || (!d.running&&!d.successWatch) || !d.monitorSessionId)return;
  if(completed && d.navigationSessionId!==d.monitorSessionId)return;
  const field=completed?'navigationCompletedAt':'navigationLastAt';
  if(details.timeStamp>d[field] || (!completed && d.navigationSessionId!==d.monitorSessionId))await chrome.storage.local.set({[field]:details.timeStamp,...(!completed?{navigationSessionId:d.monitorSessionId}:{})});
 });
}
async function navigationFailure(details){
 if(details.frameId!==0 || !sarathiUrl(details.url) || !Number.isFinite(details.timeStamp))return;
 const error=String(details.error||'').replace(/^net::/,'');
 if(['ERR_ABORTED','NS_BINDING_ABORTED'].includes(error))return; // ordinary cancelled/replaced navigation
 const code=/^ERR_[A-Z0-9_]{1,80}$/.test(error)?error:'NAVIGATION_ERROR';
 const d=await chrome.storage.local.get({running:false,successWatch:false,monitoredTabId:null,monitorSessionId:''});
 if(details.tabId!==d.monitoredTabId || (!d.running&&!d.successWatch))return;
 await reportMonitorFailure(d,code,'Sarathi page failed: '+code+'. Monitoring stopped; check the page and restart manually.',details.timeStamp);
}
// Optional guards keep the baseline offline harness compatible; permission is in manifest.
chrome.webNavigation?.onBeforeNavigate.addListener(d=>{recordNavigation(d,false).catch(()=>{});},{url:[{schemes:['https'],hostEquals:'sarathi.parivahan.gov.in'}]});
chrome.webNavigation?.onCompleted.addListener(d=>{recordNavigation(d,true).catch(()=>{});},{url:[{schemes:['https'],hostEquals:'sarathi.parivahan.gov.in'}]});
chrome.webNavigation?.onErrorOccurred.addListener(d=>{navigationFailure(d).catch(e=>console.warn('Navigation error alert failed',e));},{url:[{schemes:['https'],hostEquals:'sarathi.parivahan.gov.in'}]});
chrome.tabs?.onRemoved?.addListener(tabId=>{
 chrome.storage.local.get({monitoredTabId:null,monitorSessionId:'',running:false,successWatch:false}).then(d=>{
  if(d.monitoredTabId===tabId && (d.running||d.successWatch))return reportMonitorFailure(d,'TAB_CLOSED','Monitored Sarathi tab was closed. Monitoring stopped; reopen it and restart manually.');
 }).catch(()=>{});
});

// Optional guard permits historical standalone VM tests; Chrome always supplies importScripts.
// telegram-status.js is loaded by the persistent Firefox background manifest.
