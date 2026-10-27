// Read-only Telegram receiver. One enabled receiver per bot, on one Chrome profile.
// One bounded long poll per minute works with Chrome 109's alarm minimum.
(() => {

  const ALARM = 'sarathi-telegram-status';
  const defaults = {telegramStatusEnabled:false,botToken:'',chatId:'',
    telegramStatusOffset:0,telegramStatusBot:'',telegramStatusSince:0,telegramConfigRevision:''};
  let busy=false, epoch=0, activeController=null;
  const config=()=>chrome.storage.local.get(defaults);
  const same=(a,b)=>b.telegramStatusEnabled && a.botToken===b.botToken && a.chatId===b.chatId && a.telegramConfigRevision===b.telegramConfigRevision;
  const validToken=t=>/^\d{6,}:[A-Za-z0-9_-]{20,}$/.test(t);
  const date=t=>t ? new Date(t).toLocaleString('en-GB',{timeZone:'Asia/Kolkata',hour12:true})+' IST' : 'Not recorded';
  async function api(token,method,body,timeout=12000){
    const controller=new AbortController();activeController=controller;
    const timer=setTimeout(()=>controller.abort(),timeout);
    try{
      const response=await fetch('https://api.telegram.org/bot'+token+'/'+method,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body),signal:controller.signal});
      const data=await response.json();
      if(!response.ok || !data.ok){
        const code=Number(data.error_code)||response.status;
        throw new Error(code===409?'Telegram conflict: another receiver or webhook uses this bot. Enable status in ONE profile only.':'Telegram status API error '+code+'. Check token, connection and bot chat.');
      }
      return data.result;
    }finally{clearTimeout(timer);if(activeController===controller)activeController=null;}
  }
  const stateDefaults={running:false,successWatch:false,monitorSessionId:'',monitoredTabId:null,
    bookingStage:'',calendarLock:false,bookingClickIssued:false,monitorFailureKey:'',
    attempts:0,attemptDay:'',sessionAttempts:0,sessionCounterId:'',lastAttemptAt:0,lastCheck:0,monitorStartedAt:0};
  async function report(){
    if(globalThis.PROFILE_MODE){const r=await nativeRequest({type:"SUMMARY"});if(!r?.ok)throw Error("Status unavailable");return r.text+"\nCounts are dispatched clicks, not confirmed bookings.";}
    const before=await chrome.storage.local.get(stateDefaults);
    let ping=null,tabState='Not checked (monitor stopped)',timer;
    if(before.running || before.successWatch){
      try{
        const tab=await chrome.tabs.get(before.monitoredTabId);
        if(!tab?.url?.startsWith('https://sarathi.parivahan.gov.in/'))throw Error('Missing');
        ping=await Promise.race([chrome.tabs.sendMessage(tab.id,{type:'SARATHI_HEALTH_PING',sessionId:before.monitorSessionId}),new Promise(r=>{timer=setTimeout(()=>r(null),5000)})]);
        tabState=ping?.ok && ping.owner && ping.sessionId===before.monitorSessionId?'Responding':'No verified response';
      }catch(_){tabState='Unavailable or not responding';}finally{clearTimeout(timer);}
    }
    const d=await chrome.storage.local.get(stateDefaults),now=Date.now();
    const changed=d.monitorSessionId!==before.monitorSessionId || d.monitoredTabId!==before.monitoredTabId || d.running!==before.running || d.successWatch!==before.successWatch || d.bookingStage!==before.bookingStage;
    let status='STOPPED';
    if(d.monitorFailureKey)status='ERROR / MANUAL REVIEW';
    else if(/CONFIRMED/.test(d.bookingStage))status='APPOINTMENT CONFIRMED';
    else if(d.running || d.successWatch){
      if(changed)status='STATE CHANGED DURING CHECK — request /status again';
      else if(tabState!=='Responding')status='ACTIVITY UNCONFIRMED';
      else if(/SMS|OTP/.test(d.bookingStage))status='WAITING FOR MANUAL OTP';
      else if(d.bookingClickIssued)status='BOOK SLOT INITIATED — check outcome manually';
      else if(d.calendarLock || ping.calendar)status='CALENDAR / QUOTA STAGE — COV loop paused';
      else if(d.running && ping.covPresent && ping.loopActive && ping.lastLoopProgress>0 && now-ping.lastLoopProgress>=0 && now-ping.lastLoopProgress<=45000)status='RUNNING — recent COV loop progress verified';
      else status='WAITING / DELAYED — tab responds; active COV progress not verified';
    }
    if(changed)tabState='State changed during check';
    const localDay=new Date(now);const key=localDay.getFullYear()+'-'+String(localDay.getMonth()+1).padStart(2,'0')+'-'+String(localDay.getDate()).padStart(2,'0');
    // Only controlled stage text; never send DOM text, cookies, URLs, applicant details or OTP.
    const stage=String(d.bookingStage||'STANDBY').replace(/[^A-Za-z0-9 _/-]/g,'').slice(0,80);
    return ['MPARIVAHAN SLOT BOOKING -J&K | 3.3','Monitor: '+status,
      'Attempts today (9 AM–9 PM, phone local time): '+(d.attemptDay===key?Number(d.attempts)||0:0),
      'Attempts this session: '+(d.sessionCounterId===d.monitorSessionId && d.monitorSessionId?Number(d.sessionAttempts)||0:'Not recorded for this older session'),
      'Last recorded Proceed click: '+date(d.lastAttemptAt), 'Last status update: '+date(d.lastCheck),
      'Stage: '+stage,'Sarathi tab: '+tabState,'Report time: '+date(now),
      'Counts are recorded Proceed clicks, not successful bookings.'].join('\n');
  }
  async function poll(){
    if(busy)return;busy=true;const generation=epoch;
    try{
      let c=await config();
      if(!c.telegramStatusEnabled){if(globalThis.PROFILE_MODE)await nativeRequest({type:'STATUS_RELEASE'});return;}
      if(globalThis.PROFILE_MODE){const claim=await nativeRequest({type:'STATUS_CLAIM'});if(!claim?.ok)throw Error(claim?.error||'Status receiver unavailable');}
      c.botToken=c.botToken.trim();c.chatId=String(c.chatId||'').trim();
      if(!validToken(c.botToken)||!/^\d+$/.test(c.chatId))throw Error('Status needs a valid saved token and a private primary Chat ID.');
      const current=async()=>generation===epoch && same(c,await config());
      const bot=c.botToken.split(':')[0];
      if(c.telegramStatusBot!==bot || !c.telegramStatusSince){
        // Do not discard Telegram's update queue; ignore messages predating this activation.
        await chrome.storage.local.set({telegramStatusBot:bot,telegramStatusOffset:0,telegramStatusSince:Date.now()});
        c=await config();
      }
      const updates=await api(c.botToken,'getUpdates',{offset:c.telegramStatusOffset,limit:100,timeout:20,allowed_updates:['message']},25000);
      if(!await current())return;
      if(!Array.isArray(updates))throw Error('Unexpected Telegram response.');
      let request=null,offset=c.telegramStatusOffset;
      for(const u of updates){
        if(!Number.isSafeInteger(u.update_id)||u.update_id<c.telegramStatusOffset)continue;
        offset=Math.max(offset,u.update_id+1);
        const m=u.message;
        if(m?.chat?.type!=='private' || String(m.chat.id)!==c.chatId || String(m.from?.id)!==c.chatId || m.from?.is_bot)continue;
        if(!Number.isFinite(m.date)||m.date*1000<c.telegramStatusSince-1000 || Date.now()-m.date*1000>5*60000 || m.date*1000>Date.now()+60000)continue;
        if(/^\/(status|today|help)\s*$/i.test(m.text||''))request=m.text.trim().toLowerCase();
      }
      // Persist before sending: no repeated stale reply after worker restart. A failed reply
      // is not replayed automatically; the user can send a new /status command.
      await chrome.storage.local.set({telegramStatusOffset:offset,telegramStatusLastPoll:Date.now(),telegramStatusError:''});
      if(request){
        const text=request==='/help'?'Send /status or /today for a fresh monitor report. Read-only: no remote start, stop or booking. Keep this receiver enabled in ONE Chrome profile per bot.':await report();
        if(!await current())return;
        await api(c.botToken,'sendMessage',{chat_id:c.chatId,text,disable_notification:false});
        if(await current())await chrome.storage.local.set({telegramStatusLastReply:Date.now(),telegramStatusError:''});
      }
    }catch(e){
      if(generation===epoch)await chrome.storage.local.set({telegramStatusError:e.name==='AbortError'?'Telegram status request timed out; next scheduled check will retry.':String(e.message||e).replace(/\d{6,}:[A-Za-z0-9_-]{20,}/g,'[REDACTED]').slice(0,220)}).catch(()=>{});
    }finally{busy=false;if(generation!==epoch)setTimeout(()=>poll(),0);}
  }
  chrome.alarms.create(ALARM,{periodInMinutes:1});
  chrome.alarms.onAlarm.addListener(a=>{if(a.name===ALARM)poll();});
  chrome.storage.onChanged.addListener((changes,area)=>{
    if(area!=='local'||!['telegramStatusEnabled','botToken','chatId','telegramConfigRevision'].some(k=>changes[k]))return;
    epoch++;activeController?.abort();
    if(globalThis.PROFILE_MODE)nativeRequest({type:'STATUS_RELEASE'}).catch(()=>{});
    // Alarm remains available if changing settings interrupts an in-flight poll.
    poll();
  });
  poll();
  // Test hook only in Node VM; absent in Chrome's service worker.
  if(typeof module!=='undefined')module.exports={poll,report};
})();


