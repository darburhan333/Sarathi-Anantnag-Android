// Exercise the real Android bridge: native Silence must not echo a destructive ACK.
const fs=require('fs'),vm=require('vm'),assert=require('assert'),path=require('path');
(async()=>{
 const data={},nativeCalls=[],states=[];let receive,onChange;
 const port={onMessage:{addListener:f=>receive=f},onDisconnect:{addListener(){}},postMessage:m=>states.push(m)};
 const chrome={storage:{local:{get:async defaults=>({...defaults,...data}),set:async values=>Object.assign(data,values)},onChanged:{addListener:f=>onChange=f}},runtime:{onMessage:{addListener(){}},sendMessage:async()=>({ok:true})},tabs:{sendMessage:async()=>({ok:true}),query:async()=>[]}};
 const ctx={chrome,browser:{storage:chrome.storage,runtime:{connectNative:()=>port,getURL:p=>'moz-extension://fixture/'+p}},nativeRequest:async m=>{nativeCalls.push(m);if(m.type==='RESTORE')return {ok:true,armed:false};if(m.type==='SETTINGS_GET')return {ok:true,values:{}};return {ok:true}},inGateQueue:async work=>work(),setTimeout:()=>1,clearTimeout(){},Date,Math,console};
 vm.createContext(ctx);vm.runInContext(fs.readFileSync(path.resolve(__dirname,'../app/src/main/assets/extension/android-bridge.js'),'utf8'),ctx);
 for(let i=0;i<100&&!receive;i++)await Promise.resolve();
 assert(receive,'Real bridge must connect before receiving native commands');
 async function snapshot(values){
  Object.assign(data,values);const before=states.filter(m=>m.type==='STATE').length;
  onChange({running:{oldValue:false,newValue:values.running}},'local');
  for(let i=0;i<100&&states.filter(m=>m.type==='STATE').length===before;i++)await Promise.resolve();
  return states.filter(m=>m.type==='STATE').at(-1);
 }
 const watching=await snapshot({running:true,successWatch:true,bookingStage:'MONITORING',protectPage:false,calendarLock:false});
 assert.equal(watching.protectPage,false,'Ordinary success watching must not create protected booking-page ownership');
 assert.equal(watching.successWatch,true,'Fixing page protection must preserve success watching');
 for(const bookingStage of ['AWAITING MANUAL SMS VERIFICATION','CALENDAR REVIEW','BOOK SLOT','APPOINTMENT CONFIRMED']){
  assert.equal((await snapshot({running:false,successWatch:true,bookingStage,protectPage:false,calendarLock:false})).protectPage,true,bookingStage+' must retain native page protection');
 }
 assert.equal((await snapshot({running:false,successWatch:false,bookingStage:'STOPPED',protectPage:true,calendarLock:false})).protectPage,true,'Explicit protected-page ownership must survive STOP');
 assert.equal((await snapshot({running:false,successWatch:false,bookingStage:'STOPPED',protectPage:false,calendarLock:true})).protectPage,true,'Calendar lock must remain protected after STOP');
 assert.equal((await snapshot({running:false,successWatch:true,bookingStage:'SESSION_EXPIRED',protectPage:false,calendarLock:false})).protectPage,false,'Expired session must not be protected by a stale success watcher');
 console.log('PASS: ordinary success watching remains unprotected; explicit SMS/calendar/booking ownership stays protected');
 nativeCalls.length=0;data.alarmActive=true;
 receive({type:'SILENCE',id:'silence-fixture'});
 for(let i=0;i<100&&!states.some(m=>m.type==='ACK'&&m.id==='silence-fixture');i++)await Promise.resolve();
 assert(states.some(m=>m.type==='ACK'&&m.id==='silence-fixture'),'Native command must complete its normal acknowledgement');
 assert.equal(data.alarmActive,false,'Native Silence must still clear the extension alarm flag');
 assert(!nativeCalls.some(m=>m.type==='ACK_ALERT'),'Native Silence was already applied: a delayed ACK_ALERT would cancel a newer critical alarm');
 console.log('PASS: native Silence updates extension state without echoing a destructive alert acknowledgement');
})().catch(e=>{console.error(e);process.exitCode=1});
