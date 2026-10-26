// Debug build only. Runs against an instrumentation-owned loopback HTTP fixture.
globalThis.runAndroidSmoke=async()=>{
 const wait=ms=>new Promise(r=>setTimeout(r,ms));
 const assert=(ok,msg)=>{if(!ok)throw Error(msg);};
 let stage="begin";
 try{
  stage='clear-health';
  await browser.alarms.clear('sarathi-health');
  stage='native-audio';
  const audio=await nativeRequest({type:'AUDIO',action:'TEST',settings:{soundEnabled:true,volume:30}});assert(audio?.ok,'Native audio request failed');
  stage='native-notification';
  const notification=await nativeRequest({type:'NOTIFY',title:'Integration test',message:'Offline fixture only'});assert(notification?.ok,'Native notification request failed');
  stage='create-tab';
  const t=await browser.tabs.create({url:'http://127.0.0.1:8765/slots/test',active:true});
  await wait(2000);
  const evaluate=async code=>(await browser.tabs.executeScript(t.id,{code}))[0];
  stage='set-cov-fixture';
  await evaluate(`document.body.innerHTML='<form id="dispdlapcntdetform"><label><input type="checkbox" name="iscov" value="4">LMV</label><label><input type="checkbox" name="iscov" value="3">MCWG</label><button type="button" id="prcdbook">Proceed</button></form><div id="dialog" style="display:none">Slots are not available for the next 30 days<button type="button" class="fadeandscale_close">Back</button></div>'; window.fixtureClicks=0; document.getElementById('prcdbook').onclick=()=>{window.fixtureClicks++;fetch('http://127.0.0.1:8765/attempt?click='+window.fixtureClicks,{cache:'no-store'}).catch(e=>console.error('FIXTURE FETCH',String(e)));document.getElementById('dialog').style.display='block';};document.querySelector('.fadeandscale_close').onclick=()=>{document.getElementById('dialog').style.display='none';};null;`);
  stage='inject-content';
  await chrome.scripting.executeScript({target:{tabId:t.id},files:['booking-rules.js','content.js']});
  const start=async sid=>{
   const armed=await nativeRequest({type:'ARM'});assert(armed?.ok,'Native service did not arm');
   await chrome.storage.local.set({androidServiceReady:true,monitoredTabId:t.id,monitorSessionId:sid,running:true,successWatch:true,androidForeground:true,calendarLock:false,sessionLock:false,autoBookingEnabled:false,bookingClickIssued:false,lastProceedGateAt:0,sessionAttempts:0,sessionCounterId:sid});
   const r=await browser.tabs.sendMessage(t.id,{type:'SARATHI_START',sessionId:sid});assert(r?.ok,'START message failed');
  };
  stage='start-1';
  await start('android-smoke-1');
  stage='wait-loop';
  await nativeRequest({type:'SMOKE_PHASE',phase:'background-ready'});
  await wait(150000);
  let d=await browser.storage.local.get({sessionAttempts:0});assert(d.sessionAttempts>=2,'COV loop did not repeat twice: '+d.sessionAttempts);
  assert(await evaluate(`[...document.querySelectorAll('input[name="iscov"]')].every(x=>x.checked)`),'COV selection failed');
  await browser.storage.local.set({running:false,successWatch:false});
  await browser.tabs.sendMessage(t.id,{type:'SARATHI_STOP'});
  stage='stop-check';
  const count=await evaluate('window.fixtureClicks');await wait(10000);
  assert(await evaluate('window.fixtureClicks')===count,'STOP allowed another click');
  await nativeRequest({type:'SMOKE_PHASE',phase:'resume-needed'});
  await wait(5000);
  stage='restart';
  await start('android-smoke-2');await wait(10000);
  assert(await evaluate('window.fixtureClicks')>count,'Restart failed');
  stage='calendar';
  await evaluate(`document.body.innerHTML='<div id="calview"><table><tr><th>December 2026</th></tr><tr><td class="cal_green">9</td></tr></table></div>';`);
  await wait(2500);d=await chrome.storage.local.get({calendarLock:false});assert(d.calendarLock,'Calendar did not pause COV loop');
  await browser.storage.local.set({running:false,successWatch:false,alarmActive:false,autoBookingEnabled:false});
  await nativeRequest({type:'SMOKE_RESULT',result:'PASS: real Gecko extension, native bridge, COV selection, repeated loop, STOP, restart, calendar pause'});
 }catch(e){await nativeRequest({type:'SMOKE_RESULT',result:'FAIL at '+stage+': '+String(e.message||e)+' '+String(e.stack||'').slice(0,700)});}
};


globalThis.runMultiSmoke=async()=>{
 const wait=ms=>new Promise(r=>setTimeout(r,ms));const check=(ok,msg)=>{if(!ok)throw Error(msg);};
 try{
  await browser.alarms.clear('sarathi-health');
  const t=await browser.tabs.create({url:'http://127.0.0.1:8765/profile'+APPLICANT,active:true});await wait(2500);
  const evaluate=async code=>(await browser.tabs.executeScript(t.id,{code}))[0];
  await evaluate(`document.cookie='profile=${APPLICANT};path=/';localStorage.setItem('profile','${APPLICANT}');null;`);
  await evaluate(`new Promise((resolve,reject)=>{const r=indexedDB.open('isolation',1);r.onupgradeneeded=()=>r.result.createObjectStore('values');r.onsuccess=()=>{const db=r.result;const tx=db.transaction('values','readwrite');tx.objectStore('values').put('${APPLICANT}','profile');tx.oncomplete=()=>{db.close();resolve(true)};tx.onerror=()=>reject(Error('IDB write'));};r.onerror=()=>reject(Error('IDB open'));})`);
  await evaluate(`document.body.innerHTML='<form id="dispdlapcntdetform"><label><input type="checkbox" name="iscov" value="4">LMV</label><label><input type="checkbox" name="iscov" value="3">MCWG</label><button type="button" id="prcdbook">Proceed</button></form><div id="dialog" style="display:none">Slots are not available for the next 30 days<button type="button" class="fadeandscale_close">Back</button></div>';document.getElementById('prcdbook').onclick=()=>{fetch('http://127.0.0.1:8765/attempt?profile=${APPLICANT}&t='+Date.now(),{cache:'no-store'});document.getElementById('dialog').style.display='block';};document.querySelector('.fadeandscale_close').onclick=()=>document.getElementById('dialog').style.display='none';null;`);
  await chrome.scripting.executeScript({target:{tabId:t.id},files:['profile-guard.js','booking-rules.js','content.js']});
  const armed=await nativeRequest({type:'ARM'});check(armed?.ok,'Native ARM: '+armed?.error);
  const sid='multi-'+APPLICANT;
  await chrome.storage.local.set({androidServiceReady:true,monitoredTabId:t.id,monitorSessionId:sid,running:true,successWatch:true,calendarLock:false,sessionLock:false,autoBookingEnabled:false,bookingClickIssued:false,lastProceedGateAt:0,sessionAttempts:0,sessionCounterId:sid,bookingStage:'MONITORING'});
  const start=await browser.tabs.sendMessage(t.id,{type:'SARATHI_START',sessionId:sid});check(start?.ok,'START refused');
  await nativeRequest({type:'SMOKE_PHASE',phase:'multi-running'});await wait(10000);
  check(await evaluate(`document.cookie.split('; ').includes('profile=${APPLICANT}')`),'Cookie isolation');
  check(await evaluate(`localStorage.getItem('profile')==='${APPLICANT}'`),'localStorage isolation');
  check(await evaluate(`new Promise((resolve,reject)=>{const r=indexedDB.open('isolation',1);r.onsuccess=()=>{const db=r.result;const q=db.transaction('values').objectStore('values').get('profile');q.onsuccess=()=>{db.close();resolve(q.result==='${APPLICANT}')};q.onerror=reject;};r.onerror=reject;})`),'IndexedDB isolation');
  for(const other of await browser.tabs.query({url:'http://127.0.0.1/*'})){if(other.id===t.id||!other.url?.includes('/profile')||other.url.endsWith('/profile'+APPLICANT))continue;let claimed=false;try{claimed=(await browser.tabs.sendMessage(other.id,{type:'SARATHI_PROFILE_QUERY'}))?.ok;}catch(_){}check(!claimed,'Other applicant tab claimed');}
  check((await chrome.storage.local.get({monitorSessionId:''})).monitorSessionId===sid,'Extension storage isolation');
  await nativeRequest({type:'SMOKE_RESULT',result:'PASS: isolated cookies, localStorage, IndexedDB, extension state and native ownership'});
 }catch(e){await nativeRequest({type:'SMOKE_RESULT',result:'FAIL multi: '+String(e.message||e)});}
};
