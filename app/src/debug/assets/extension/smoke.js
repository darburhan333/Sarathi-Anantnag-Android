// Debug build only. Runs against an instrumentation-owned loopback HTTP fixture.
globalThis.runAndroidSmoke=async()=>{
 const wait=ms=>new Promise(r=>setTimeout(r,ms));
 const assert=(ok,msg)=>{if(!ok)throw Error(msg);};
 try{
  await browser.alarms.clear('sarathi-health');
  const audio=await browser.runtime.sendNativeMessage('sarathi',{type:'AUDIO',action:'TEST',settings:{soundEnabled:true,volume:30}});assert(audio?.ok,'Native audio request failed');
  const notification=await browser.runtime.sendNativeMessage('sarathi',{type:'NOTIFY',title:'Integration test',message:'Offline fixture only'});assert(notification?.ok,'Native notification request failed');
  const t=await browser.tabs.create({url:'http://127.0.0.1:8765/slots/test',active:true});
  await wait(2000);
  const evaluate=async code=>(await browser.tabs.executeScript(t.id,{code}))[0];
  await evaluate(`document.body.innerHTML='<form id="dispdlapcntdetform"><label><input type="checkbox" name="iscov" value="4">LMV</label><label><input type="checkbox" name="iscov" value="3">MCWG</label><button type="button" id="prcdbook">Proceed</button></form><div id="dialog" style="display:none">Slots are not available for the next 30 days<button type="button" class="fadeandscale_close">Back</button></div>'; window.fixtureClicks=0; document.getElementById('prcdbook').onclick=()=>{window.fixtureClicks++;document.getElementById('dialog').style.display='block';};document.querySelector('.fadeandscale_close').onclick=()=>{document.getElementById('dialog').style.display='none';};null;`);
  await chrome.scripting.executeScript({target:{tabId:t.id},files:['booking-rules.js','content.js']});
  const start=async sid=>{
   await chrome.storage.local.set({monitoredTabId:t.id,monitorSessionId:sid,running:true,successWatch:true,androidForeground:true,calendarLock:false,sessionLock:false,autoBookingEnabled:false,bookingClickIssued:false,lastProceedGateAt:0,sessionAttempts:0,sessionCounterId:sid});
   const r=await browser.tabs.sendMessage(t.id,{type:'SARATHI_START',sessionId:sid});assert(r?.ok,'START message failed');
  };
  await start('android-smoke-1');
  await wait(22000);
  let d=await browser.storage.local.get({sessionAttempts:0});assert(d.sessionAttempts>=2,'COV loop did not repeat twice: '+d.sessionAttempts);
  assert(await evaluate(`[...document.querySelectorAll('input[name="iscov"]')].every(x=>x.checked)`),'COV selection failed');
  await browser.storage.local.set({running:false,successWatch:false});
  await browser.tabs.sendMessage(t.id,{type:'SARATHI_STOP'});
  const count=await evaluate('window.fixtureClicks');await wait(10000);
  assert(await evaluate('window.fixtureClicks')===count,'STOP allowed another click');
  await start('android-smoke-2');await wait(10000);
  assert(await evaluate('window.fixtureClicks')>count,'Restart failed');
  await evaluate(`document.body.innerHTML='<div id="calview"><table><tr><th>December 2026</th></tr><tr><td class="cal_green">9</td></tr></table></div>';`);
  await wait(2500);d=await chrome.storage.local.get({calendarLock:false});assert(d.calendarLock,'Calendar did not pause COV loop');
  await browser.storage.local.set({running:false,successWatch:false,alarmActive:false,autoBookingEnabled:false});
  await browser.runtime.sendNativeMessage('sarathi',{type:'SMOKE_RESULT',result:'PASS: real Gecko extension, native bridge, COV selection, repeated loop, STOP, restart, calendar pause'});
 }catch(e){await browser.runtime.sendNativeMessage('sarathi',{type:'SMOKE_RESULT',result:'FAIL: '+String(e.message||e)});}
};
