const $=id=>document.getElementById(id);const on=(id,fn)=>{const el=$(id);if(el)el.addEventListener('click',fn);};const feedback=t=>{const el=$('feedback');if(el)el.textContent=t;};
const PRIMARY_CHAT_ID = '';
const time=t=>t?new Date(t).toLocaleTimeString():'—';
document.querySelectorAll('[data-panel]').forEach(btn=>btn.addEventListener('click',()=>{document.querySelectorAll('[data-panel]').forEach(b=>b.classList.toggle('active',b===btn));['monitor','standby','telegram','alerts','history'].forEach(id=>{const el=$(id);if(el)el.classList.toggle('hidden',id!==btn.dataset.panel);});}));
async function openWindow(){return;}
on('openWindow',()=>openWindow().catch(e=>feedback(e.message)));
async function target(){const d=await chrome.storage.local.get('monitoredTabId');const tabs=await chrome.tabs.query({url:'https://sarathi.parivahan.gov.in/*'});return tabs.find(t=>t.id===d.monitoredTabId)||tabs.find(t=>t.active)||tabs[0];}
let runBusy=false,runEpoch=0;
async function run(start){
 if(start && runBusy)return;
 const epoch=++runEpoch;
 if(start)runBusy=true;
 try{await runAction(start,epoch);}finally{if(start)runBusy=false;}
}
async function runAction(start,epoch){
 if(start && !(await chrome.storage.local.get({androidForeground:false})).androidForeground){feedback('Return to the app before starting.');return;}
 const valid=()=>epoch===runEpoch;
 if(!start){
   await chrome.storage.local.set({running:false,successWatch:false,autoBookingEnabled:false,nextRetryAt:0,bookingStage:'STOPPED',lastStatus:'Stopped by user'});
   const t=await target();
   if(valid()&&t)try{await chrome.tabs.sendMessage(t.id,{type:'SARATHI_STOP'});}catch(_){}
   if(valid())feedback('Monitoring stopped.');
   refresh();return;
 }
 const t=await target();if(!valid())return;
 if(!t){feedback('Open and log into Sarathi first.');return;}
 const prior=await chrome.storage.local.get({bookingClickIssued:false});if(!valid())return;
 if(prior.bookingClickIssued && !window.confirm('A previous BOOK SLOT submission was initiated. Verify its result on Sarathi before starting a new monitoring session. Start a NEW session and reset the booking lock?')){feedback('Start cancelled — previous booking submission stays locked.');return;}
 const session=crypto.randomUUID?crypto.randomUUID():String(Date.now())+'-'+Math.random().toString(36).slice(2);
 try{
   await chrome.scripting.executeScript({target:{tabId:t.id},files:['booking-rules.js','content.js']});if(!valid())return;
   await chrome.storage.local.set({monitoredTabId:t.id,monitorSessionId:session,monitorStartedAt:Date.now(),sessionAttempts:0,sessionCounterId:session,navigationSessionId:'',navigationLastAt:0,navigationCompletedAt:0,monitorFailureKey:'',monitorFailureAt:0,failureDesktopError:'',failureTelegramError:'',successWatch:true,calendarLock:false,sessionLock:false,bookingStage:'MONITORING',bookingClickIssued:false,bookingReservationId:'',bookingReservationDocument:'',bookingDispatchStarted:false,bookingSubmittedAt:0,bookingResponseWarned:false,quotaAlertKey:'',scanMonth:'',scanChecked:[],scanFallback:null,scanPending:'',scanPendingAt:0,scanReturning:false,warningKind:'',warningAt:0,warningEvents:[],calendarAlertKey:'',calendarAlertAt:0,running:true,lastStatus:'Connecting to Sarathi…',heartbeatAt:0,smsAlertKey:'',healthMisses:0,healthWarningAt:0,lastProceedGateAt:0,monitorProgressAt:0,monitorLoopActive:false,nextRetryAt:0});
   if(!valid()){await chrome.storage.local.set({running:false,successWatch:false,nextRetryAt:0});return;}
   const response=await chrome.tabs.sendMessage(t.id,{type:'SARATHI_START',sessionId:session});if(!valid())return;
   if(!response?.ok)throw Error(response?.error||'Page did not confirm start');
   feedback('Monitoring started in Sarathi tab '+t.id);
 }catch(e){if(valid()){await chrome.storage.local.set({running:false,successWatch:false,nextRetryAt:0,bookingStage:'START FAILED',lastStatus:'Could not start: '+e.message});feedback('Could not start: '+e.message);}}
 if(valid())refresh();
}
$('autoBookingEnabled').addEventListener('change',async()=>{await chrome.storage.local.set({autoBookingEnabled:$('autoBookingEnabled').checked});feedback($('autoBookingEnabled').checked?'Assisted booking enabled for the next calendar. SMS verification stays manual.':'Assisted booking disabled.');});on('start',()=>run(true));on('stop',()=>run(false));
const dirtyTelegram=new Set();
['chatId','chatId2','botToken'].forEach(id=>$(id)?.addEventListener('input',()=>dirtyTelegram.add(id)));
on('saveTelegram',async()=>{const chatId2=$('chatId2').value.trim(),chatId=$('chatId')?$('chatId').value.trim():((await chrome.storage.local.get({chatId:''})).chatId || PRIMARY_CHAT_ID),botToken=$('botToken').value.trim() || (await chrome.storage.local.get({botToken:''})).botToken;if(!/^[1-9]\d*$/.test(chatId)){feedback('Chat ID 1 must be numeric.');return;}if(chatId2&&!/^-?\d+$/.test(chatId2)){feedback('Chat ID 2 must be numeric.');return;}if(botToken&&!/^\d{6,}:[A-Za-z0-9_-]{20,}$/.test(botToken)){feedback('Bot token format is invalid. Check the token from BotFather.');return;}if((chatId||chatId2)&&!botToken){feedback('Enter the bot token before saving Chat IDs.');return;}const values={chatId:chatId||PRIMARY_CHAT_ID,chatId2,botToken};const patch=Object.fromEntries([...dirtyTelegram].map(k=>[k,values[k]]));await chrome.storage.local.set(patch);dirtyTelegram.clear();await refresh();feedback('Telegram settings saved locally. Test the phone alert.');if($('telegramResult'))$('telegramResult').textContent='Settings saved; test not yet run.';});
async function send(type){try{const r=await chrome.runtime.sendMessage({type});const msg=r?.ok?(type==='TEST_DESKTOP'?'Android notification request accepted. Check your notifications.':'Telegram API accepted the test message. Check your phone.'):'Test failed: '+(r?.error||'Unknown error');feedback(msg);if(type==='TEST_TELEGRAM'&&$('telegramResult'))$('telegramResult').textContent=msg;}catch(e){feedback('Test failed: '+e.message);}}
on('testTelegram',()=>send('TEST_TELEGRAM'));on('testDesktop',()=>send('TEST_DESKTOP'));on('testAll',async()=>{const results=await Promise.allSettled([chrome.runtime.sendMessage({type:'TEST_DESKTOP'}),chrome.runtime.sendMessage({type:'TEST_TELEGRAM'}),soundCommand('TEST')]);const names=['Android notification','Telegram','Sound'];feedback(results.map((r,i)=>names[i]+': '+(r.status==='rejected'?'FAILED '+r.reason:(r.value===false||r.value?.ok===false?'FAILED '+(r.value?.error||'audio could not start'):'OK'))).join(' | '));});
async function soundCommand(action){try{const r=await chrome.runtime.sendMessage({type:'AUDIO_CONTROL',action});if(!r?.ok)throw Error(r?.error||'Audio failed');return true;}catch(e){feedback('Sound: '+e.message);return false;}}
on('enableAlarm',async()=>{if(await soundCommand('ARM'))feedback('Android sound and vibration test requested.');});
on('testSound',async()=>{if(await soundCommand('TEST'))feedback('Android sound and vibration test requested.');});
on('stopAlarm',async()=>{await chrome.storage.local.set({alarmActive:false});await soundCommand('STOP');feedback('Alarm silenced. Monitoring state is unchanged.');});
async function syncAlarm(d){$('alarmMessage').textContent=d.audioError?'Sound needs attention: '+d.audioError:d.alarmActive?'ALERT — background alarm active; click STOP ALARM to silence':'Android alarm ready. Keep the app open while monitoring.';}
on('desktopEnabled',()=>{});['desktopEnabled','soundEnabled'].forEach(id=>$(id).addEventListener('change',async()=>{await chrome.storage.local.set({[id]:$(id).checked});refresh();}));if($('telegramEnabled'))$('telegramEnabled').addEventListener('change',async()=>{await chrome.storage.local.set({telegramEnabled:$('telegramEnabled').checked});refresh();});$('volume').addEventListener('input',async()=>{$('volumeValue').textContent=$('volume').value+'%';await chrome.storage.local.set({volume:Number($('volume').value)});});
on('exportHistory',async()=>{const d=await chrome.storage.local.get({history:[]});const lines=['Time,Type,Message',...d.history.map(x=>[new Date(x.time).toISOString(),x.kind,x.message].map(v=>'"'+String(v||'').replace(/"/g,'""')+'"').join(','))];await chrome.runtime.sendMessage({type:'ANDROID_EXPORT',text:lines.join('\n')});});
const progressLabels=['COV selection','Calendar detected','Green date selected','Quota selected','BOOK SLOT submitted','SMS verification (manual)','Appointment confirmed'];
let previousTrackerStage=0;
function renderBookingSteps(stage){
 const text=String(stage||'').toUpperCase();
 let active=0;
 if(text.includes('CALENDAR'))active=1;
 if(text.includes('GREEN DATE')||text==='GREEN WED/THU SELECTED'||text==='CHECKING NEXT WED/THU')active=2;
 if(text.includes('QUOTA'))active=3;
 if(text.includes('BOOK SLOT'))active=4;
 if(text.includes('SMS'))active=5;
 if(text.includes('CONFIRM'))active=6;
 if(/STOPPED|MANUAL REVIEW|CHECK CONNECTION/.test(text))active=previousTrackerStage;else previousTrackerStage=active;
 const root=$('bookingSteps');if(!root)return;
 root.replaceChildren(...progressLabels.map((label,i)=>{
   const el=document.createElement('div');el.className='booking-step '+(i<active?'done':i===active?'current':'');
   el.textContent=(i<active?'✓ ':i===active?'● ':'○ ')+(i+1)+'. '+label;return el;
 }));
}
// V11.34.1: storage changes are applied to a single in-memory view snapshot.
// This avoids concurrent asynchronous refreshes overwriting newer status updates.
let lastAlarm=false;
let viewState=null;
let pendingViewChanges=[];
let viewEpoch=0;
let viewRequest=0;
let lastStageRendered=null;
let lastHistoryRendered=null;
const viewDefaults={telegramStatusEnabled:false,telegramStatusError:'',telegramStatusLastPoll:0,telegramStatusLastReply:0,nextRetryAt:0,running:false,lastCheck:0,lastStatus:'Stopped',heartbeatAt:0,monitorProgressAt:0,monitorLoopActive:false,calendarLock:false,telegramEnabled:true,desktopEnabled:true,soundEnabled:true,volume:85,history:[],attempts:0,attemptDay:'',alarmActive:false,telegramDeliveryStatus:'',telegramLastAttempt:0,botToken:'',chatId:'',chatId2:'',successWatch:false,audioError:'',bookingStage:'',autoBookingEnabled:false,failureDesktopError:'',failureTelegramError:''};
function applyViewChanges(changes){
  if(!viewState){pendingViewChanges.push(changes);return;}
  for(const [key,entry] of Object.entries(changes)){
    if(Object.prototype.hasOwnProperty.call(entry,'newValue'))viewState[key]=entry.newValue;
    else if(Object.prototype.hasOwnProperty.call(viewDefaults,key))viewState[key]=viewDefaults[key];
    else delete viewState[key];
  }
  viewEpoch++;
  paintView();
}
function paintView(){
  if(!viewState)return;
  const d=viewState;
  if($('telegramStatusEnabled'))$('telegramStatusEnabled').checked=!!d.telegramStatusEnabled;
  if($('telegramStatusInfo'))$('telegramStatusInfo').textContent=!d.telegramStatusEnabled?'Status commands disabled':d.telegramStatusError||('Last command check: '+time(d.telegramStatusLastPoll)+' | Last reply: '+time(d.telegramStatusLastReply));
  if($('failureDelivery'))$('failureDelivery').textContent=[d.failureDesktopError&&('Desktop: '+d.failureDesktopError),d.failureTelegramError&&('Telegram: '+d.failureTelegramError)].filter(Boolean).join(' | ')||'No failure-delivery error reported';
  ['chatId','chatId2','botToken'].forEach(id=>{if($(id)&&!dirtyTelegram.has(id))$(id).value=d[id]||(id==='chatId'?PRIMARY_CHAT_ID:'');});
  ['desktopEnabled','soundEnabled','telegramEnabled'].forEach(id=>{if($(id))$(id).checked=!!d[id];});
  if(document.activeElement!==$('volume')){$('volume').value=d.volume;$('volumeValue').textContent=d.volume+'%';}
  const assistToggle=$('autoBookingEnabled');
  if(assistToggle && assistToggle.checked!==!!d.autoBookingEnabled)assistToggle.checked=!!d.autoBookingEnabled;
$('retryCountdown').textContent=d.running && d.nextRetryAt ? (d.nextRetryAt<Date.now()?'Retry overdue '+Math.floor((Date.now()-d.nextRetryAt)/1000)+'s':Math.ceil((d.nextRetryAt-Date.now())/1000)+'s') : '—';$('networkState').textContent=navigator.onLine?'Online':'Offline';$('telegramDelivery').textContent=d.telegramDeliveryStatus ? d.telegramDeliveryStatus + (d.telegramLastAttempt ? ' • '+time(d.telegramLastAttempt) : '') : 'Not tested';$('heartbeatTime').textContent=time(d.heartbeatAt);const age=Date.now()-(d.heartbeatAt||0);const progressAge=Date.now()-(d.monitorProgressAt||0);const stalled=d.running&&!d.calendarLock&&!String(d.bookingStage||'').toUpperCase().includes('CALENDAR')&&!String(d.bookingStage||'').toUpperCase().includes('SMS')&&!!d.heartbeatAt&&(!d.monitorLoopActive||progressAge>90000);$('healthState').textContent=!d.running?'Stopped':!navigator.onLine?'Offline — auto recovery':!d.heartbeatAt?'Waiting for Sarathi tab':stalled?'Monitoring loop stalled — recovery checking':age>15000?'No updates — check Sarathi tab':age>10000?'Delayed updates':'Healthy';$('healthState').className=!d.running?'':stalled||age>15000?'bad':age>10000?'warn':'good';$('badge').textContent=d.running?'RUNNING':'STOPPED';$('badge').className='pill '+(d.running?'good':'');$('lastCheck').textContent=time(d.lastCheck);$('attempts').textContent=d.attemptDay===(()=>{const x=new Date();return x.getFullYear()+'-'+String(x.getMonth()+1).padStart(2,'0')+'-'+String(x.getDate()).padStart(2,'0')})()?(d.attempts||0):0;$('sarathiState').textContent=!d.running?'Stopped':!navigator.onLine?'Offline':!d.heartbeatAt?'Connecting':stalled?'Loop stalled / recovering':age>15000?'Delayed / checking':'Connected';$('latestActivity').textContent=d.lastStatus||'Waiting';$('bookingStage').textContent=d.bookingStage|| (d.running?'MONITORING':'STANDBY');const bs=String(d.bookingStage||'').toUpperCase();$('covState').textContent=bs.includes('COV')?'COV page detected':bs.includes('QUOTA')?'Quota selected':bs.includes('CALENDAR')?'Calendar open':d.running?'Monitoring COV':'Standby';$('pageState').textContent=bs|| (d.running?'Monitoring':'Standby');$('recoveryState').textContent=!d.running?'Inactive':stalled?'Recovering loop':age>15000?'Checking connection':'Normal';if(lastStageRendered!==(d.bookingStage||'MONITORING')){lastStageRendered=d.bookingStage||'MONITORING';renderBookingSteps(lastStageRendered);}$('telegramState').textContent=!d.telegramEnabled?'Disabled':d.botToken&&d.chatId?'Configured':'Setup required';$('desktopState').textContent=d.desktopEnabled?'Enabled':'Off';$('alarmState').textContent=d.soundEnabled?'Enabled':'Off';$('start').disabled=d.running;$('stop').disabled=!d.running&&!d.successWatch;if(lastHistoryRendered!==d.history){lastHistoryRendered=d.history;$('log').textContent=d.history.length?d.history.slice(0,20).map(x=>time(x.time)+' • '+x.kind+'\n'+x.message).join('\n\n'):'No events yet';}lastAlarm=d.alarmActive;syncAlarm(d);
// A paused COV loop can still be watching the manual OTP/receipt flow.
if(!d.running && d.successWatch){
 $('badge').textContent=/SMS|OTP/.test(bs)?'MANUAL OTP':'WATCHING';
 $('badge').className='pill warn';
 $('healthState').textContent='Receipt watcher active — see current stage';
 $('healthState').className='warn';
 $('sarathiState').textContent='COV loop paused; receipt watcher active';
}
}
async function refresh(){
  const epoch=viewEpoch,requestId=++viewRequest;
  const data=await chrome.storage.local.get(viewDefaults);
  if(requestId!==viewRequest)return;
  if(!viewState){
    viewState=data;
    for(const changes of pendingViewChanges){
      for(const [key,entry] of Object.entries(changes)){
        if(Object.prototype.hasOwnProperty.call(entry,'newValue'))viewState[key]=entry.newValue;
        else if(Object.prototype.hasOwnProperty.call(viewDefaults,key))viewState[key]=viewDefaults[key];
        else delete viewState[key];
      }
    }
    pendingViewChanges=[];
  } else if(epoch===viewEpoch){
    Object.assign(viewState,data);
  }
  paintView();
}
function tickStatus(){
  // No storage read: Chrome throttles background timers; focus handlers restore immediately.
  paintView();
}
async function init(){const d=await chrome.storage.local.get({chatId:'',chatId2:'',botToken:'',telegramEnabled:true,desktopEnabled:true,soundEnabled:true,volume:85,autoBookingEnabled:false});$('autoBookingEnabled').checked=!!d.autoBookingEnabled;if($('chatId'))$('chatId').value=d.chatId||PRIMARY_CHAT_ID;if($('chatId2'))$('chatId2').value=d.chatId2||'';if($('botToken'))$('botToken').value=d.botToken||'';if($('tokenStatus'))$('tokenStatus').textContent='Bot token is stored locally in this app, not hard-coded.';if($('telegramEnabled'))$('telegramEnabled').checked=d.telegramEnabled;$('desktopEnabled').checked=d.desktopEnabled;$('soundEnabled').checked=d.soundEnabled;$('volume').value=d.volume;$('volumeValue').textContent=d.volume+'%';refresh();}
chrome.storage.onChanged.addListener((changes,area)=>{if(area==='local'){if(changes.androidStopEpoch)runEpoch++;applyViewChanges(changes);}});
// Chrome may pause timers in background windows: update immediately on return.
window.addEventListener('focus',()=>{refresh().catch(()=>{});});
document.addEventListener('visibilitychange',()=>{if(!document.hidden)refresh().catch(()=>{});});
// Tab buttons remain synchronous; refresh locally, with no network or storage wait.
document.querySelectorAll('[data-panel]').forEach(btn=>btn.addEventListener('click',()=>tickStatus()));
init().then(()=>{if(chrome.runtime?.sendMessage)return chrome.runtime.sendMessage({type:'ANDROID_UI_READY'});}).catch(e=>feedback('Monitor setup failed: '+e.message));
setInterval(tickStatus,500);

$('telegramStatusEnabled')?.addEventListener('change',async()=>{await chrome.storage.local.set({telegramStatusEnabled:$('telegramStatusEnabled').checked,telegramStatusSince:Date.now(),telegramStatusError:''});refresh();});
