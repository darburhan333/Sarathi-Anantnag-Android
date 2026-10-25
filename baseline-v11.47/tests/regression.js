// Run with: node tests/regression.js (no browser, no Sarathi credentials needed)
const fs=require('fs'), path=require('path'),vm=require('vm'),assert=require('assert');
const root=path.resolve(__dirname,'..');
async function testRules(){
  const scope={};vm.runInNewContext(fs.readFileSync(path.join(root,'booking-rules.js'),'utf8'),scope);
  const r=scope.BookingRules;
  assert.equal(r.quotaHeader('Available Quota'),true);
  assert.equal(r.quotaHeader('S.No.'),false);
  assert.equal(r.select([{date:'2027-02-31',green:true,options:[{label:'LMV',count:1}]}]).status,'NONE');
  assert.equal(r.select([{date:'2026-10-21',green:true,options:[{label:'LMV',count:1}]}]).status,'SIMULATED_SMS');
  assert.equal(r.select([{date:'2026-10-21',green:true,options:[{label:'LMV',count:'unknown'}]}]).status,'MANUAL_REVIEW');
  assert.equal(r.select([{date:'2026-10-21',green:true,options:[{label:'LMV',count:0}]}]).status,'NONE');
  assert.equal(r.select([{date:'2026-10-21',green:true,options:[{label:'LMV',count:1}]}],()=>true).status,'STOPPED');
  console.log('PASS: date validity, positive quota, ambiguous quota, zero quota and STOP rules');
}
async function testBackground(){
  let data={running:true,monitoredTabId:19,monitorSessionId:'session-good',calendarLock:false,autoBookingEnabled:false,bookingClickIssued:false,heartbeatAt:Date.now(),monitorProgressAt:Date.now()-180000,healthMisses:0};
  const listeners={message:[],alarm:[],storage:[]};
  const chrome={
    runtime:{onMessage:{addListener:f=>listeners.message.push(f)},getURL:x=>'chrome-extension://test/'+x,lastError:null},
    storage:{local:{get:async keys=>{
      if(Array.isArray(keys))return Object.fromEntries(keys.map(k=>[k,data[k]]));
      if(typeof keys==='string')return {[keys]:data[keys]};
      return Object.assign({},keys,data);
    },set:async obj=>{const changes=Object.fromEntries(Object.entries(obj).map(([k,v])=>[k,{oldValue:data[k],newValue:v}]));Object.assign(data,obj);listeners.storage.forEach(fn=>fn(changes,'local'));}},onChanged:{addListener:f=>listeners.storage.push(f)}},
    alarms:{create:()=>{},onAlarm:{addListener:f=>listeners.alarm.push(f)}},
    windows:{onBoundsChanged:{addListener:()=>{}}},
    tabs:{get:async()=>({id:19,url:'https://sarathi.parivahan.gov.in/slots/proceeddlapmnt.do',status:'complete'}),
      sendMessage:async(id,msg)=>msg.type==='SARATHI_HEALTH_PING'?{ok:true,owner:true,sessionId:'session-good',loopActive:false,covPresent:true,calendar:false,lastLoopProgress:Date.now()-180000}:{ok:true}},
    scripting:{executeScript:async()=>{throw Error('Unexpected injection');}},
    notifications:{create:()=>{},clear:()=>{}},
    offscreen:{hasDocument:async()=>true},
  };
  vm.runInNewContext(fs.readFileSync(path.join(root,'background.js'),'utf8'),{chrome,console,fetch:()=>{},AbortController,setTimeout,clearTimeout,Date,Math,Promise});
  async function call(msg,tabId=19){
    return new Promise((resolve,reject)=>{
      const listener=listeners.message[0];
      if(!listener)reject(new Error('listener missing'));
      listener(msg,{tab:{id:tabId},url:'https://sarathi.parivahan.gov.in/slots/proceeddlapmnt.do'},resolve);
      setTimeout(()=>reject(new Error('message timed out: '+msg.type)),1500);
    });
  }
  assert.equal((await call({type:'SARATHI_OWNERSHIP'})).authorized,true);
  assert.equal((await call({type:'SARATHI_ACTION_GATE',action:'COV_SELECT',sessionId:'session-good'},21)).authorized,false);
  assert.equal((await call({type:'SARATHI_ACTION_GATE',action:'COV_SELECT',sessionId:'stale'})).authorized,false);
  assert.equal((await call({type:'SARATHI_ACTION_GATE',action:'PROCEED',sessionId:'session-good'})).authorized,true);
  assert.equal((await call({type:'SARATHI_ACTION_GATE',action:'PROCEED',sessionId:'session-good'})).authorized,false);
  await chrome.storage.local.set({calendarLock:true,autoBookingEnabled:true});
  assert.equal((await call({type:'SARATHI_ACTION_GATE',action:'BOOK_SLOT',reservationId:'test-reservation',sessionId:'session-good',date:'2026-10-21',kind:'LMV'})).authorized,true);
  assert.equal(data.bookingClickIssued,true);
  assert.equal((await call({type:'SARATHI_ACTION_GATE',action:'BOOK_SLOT',reservationId:'test-reservation',sessionId:'session-good',date:'2026-10-21',kind:'LMV'})).authorized,false);
  assert.equal((await call({type:'SARATHI_ACTION_GATE',action:'BOOK_SLOT_CONFIRM',reservationId:'test-reservation',sessionId:'session-good'})).authorized,true);
  await chrome.storage.local.set({running:false});
  assert.equal((await call({type:'SARATHI_ACTION_GATE',action:'BOOK_SLOT_CONFIRM',reservationId:'test-reservation',sessionId:'session-good'})).authorized,false);
  await chrome.storage.local.set({running:false,successWatch:true});
  assert.equal((await call({type:'SARATHI_OWNERSHIP'})).authorized,true);
  assert.equal((await call({type:'SARATHI_OWNERSHIP'},21)).authorized,false);
  assert.equal((await call({type:'SARATHI_ACTION_GATE',action:'BOOK_SLOT_CONFIRM',reservationId:'test-reservation',sessionId:'session-good'})).authorized,false);
  await chrome.storage.local.set({successWatch:false});
  assert.equal((await call({type:'SARATHI_OWNERSHIP'})).authorized,false);
  console.log('PASS: stopped receipt observation authorized only for owner; automation remains denied');
  console.log('PASS: owner isolation, stale session, PROCEED throttle, BOOK SLOT idempotency, STOP gating');
  await chrome.storage.local.set({running:true,calendarLock:false,autoBookingEnabled:false,bookingClickIssued:false,heartbeatAt:Date.now()});
  for(const listener of listeners.alarm)await listener({name:'sarathi-health'});
  assert.match(data.lastStatus,/recovery requested/i);
  console.log('PASS: health recovery detects a dead loop with a fresh heartbeat');
}
async function testAlarmRace(){
  let resolvePlay;let intervals=0;let paused=0;
  class FakeAudio {constructor(){} pause(){paused++}set currentTime(_){ }set volume(_){ }get volume(){return .85}play(){return new Promise(resolve=>{resolvePlay=resolve})}}
  const message=[];
  const ctx={chrome:{runtime:{onMessage:{addListener:f=>message.push(f)}}},Audio:FakeAudio,
    URL:{createObjectURL:()=>''},Blob,DataView,Uint8Array,Math,console,
    setInterval:()=>{intervals++;return intervals;},clearInterval:()=>{intervals--;}};
  vm.runInNewContext(fs.readFileSync(path.join(root,'audio.js'),'utf8'),ctx);
  function command(action){return new Promise(resolve=>message[0]({type:'OFFSCREEN_AUDIO',action},{},resolve));}
  const starting=command('START');
  await Promise.resolve();
  await command('STOP');
  resolvePlay();await starting;
  assert.equal(intervals,0,'STOP during play must not recreate recurring timer');
  assert(paused>0);
  console.log('PASS: alarm STOP wins race against asynchronous audio START');
}
(async()=>{await testRules();await testBackground();await testAlarmRace();console.log('PASS: all regression checks');})().catch(e=>{console.error(e);process.exit(1)});
