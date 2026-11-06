const fs=require('fs'),vm=require('vm'),assert=require('assert'),path=require('path');
const root=path.resolve(__dirname,'../app/src/main/assets/extension');
(async()=>{
 const listeners=[],changes=[],native=[];
 const data={androidServiceReady:true,running:true,monitoredTabId:7,monitorSessionId:'s1',calendarLock:true,autoBookingEnabled:true,bookingClickIssued:false,successWatch:false,telegramEnabled:true,desktopEnabled:true,chatId:'111',chatId2:'222',botToken:'123456:TEST_TOKEN_NOT_REAL_abcdefghijklmn',telegramConfigRevision:'adapter-test-revision',telegramOutbox:[],history:[],smsAlertKey:''};
 let failCritical=true,fetchCalls=0;
 const browser={
  storage:{local:{get:async k=>typeof k==='string'?{[k]:data[k]}:Array.isArray(k)?Object.fromEntries(k.map(x=>[x,data[x]])):Object.fromEntries(Object.entries(k).map(([x,v])=>[x,data[x]===undefined?v:data[x]])),set:async p=>{const c={};for(const [k,v]of Object.entries(p)){c[k]={oldValue:data[k],newValue:v};data[k]=v;}changes.forEach(f=>f(c,'local'));}},onChanged:{addListener:f=>changes.push(f)}},
  runtime:{getURL:p=>'moz-extension://test/'+p,onMessage:{addListener:f=>listeners.push(f)},sendNativeMessage:async(app,m)=>{native.push(m);if(failCritical&&m.type==='CRITICAL')throw Error('fixture native CRITICAL failure');return {ok:true};}},
  tabs:{onRemoved:{addListener(){}},executeScript:async(id,o)=>[o.file],sendMessage:async()=>({ok:true})},
  alarms:{create(){},onAlarm:{addListener(){}}},
  webNavigation:{onBeforeNavigate:{addListener(){}},onCompleted:{addListener(){}},onErrorOccurred:{addListener(){}}}
 };
 const ctx={browser,console,URL,Date,Math,Promise,setTimeout,clearTimeout,AbortController,navigator:{onLine:true},fetch:async()=>{fetchCalls++;return {ok:true,status:200,json:async()=>({ok:true})};}};vm.createContext(ctx);
 for(const f of ['compat.js','background.js'])vm.runInContext(fs.readFileSync(path.join(root,f),'utf8'),ctx);
 const call=m=>new Promise(resolve=>listeners[0]({type:'SARATHI_ACTION_GATE',sessionId:'s1',documentNonce:'doc1',...m},{tab:{id:7},url:'https://sarathi.parivahan.gov.in/slots/x'},resolve));
 const bookingActions=['DATE_SELECT','QUOTA_SELECT','QUOTA_BACK','BOOK_SLOT','BOOK_SLOT_CONFIRM'];
 for(const action of bookingActions){
  for(const [field,blocked] of [['running',false],['calendarLock',false],['autoBookingEnabled',false]]){
   const prior=data[field];data[field]=blocked;
   const denied=await call({action,reservationId:'reservation-gate',date:'2026-12-09',kind:'BOTH'});
   assert(!denied.authorized,action+' must be denied when '+field+' is false');
   data[field]=prior;
  }
 }
 for(const action of ['DATE_SELECT','QUOTA_SELECT','QUOTA_BACK'])assert((await call({action})).authorized,action+' must authorize only with all three gates true');
 assert((await call({action:'BOOK_SLOT',reservationId:'reservation-1',date:'2026-12-09',kind:'BOTH'})).authorized);
 assert.equal(data.bookingReservationDocument,'doc1');
 assert(!(await call({action:'BOOK_SLOT_CONFIRM',reservationId:'reservation-1',documentNonce:'doc2'})).authorized);
 assert((await call({action:'BOOK_SLOT_CONFIRM',reservationId:'reservation-1'})).authorized);
 data.androidServiceReady=false;
 assert(!(await call({action:'BOOK_SLOT_CONFIRM',reservationId:'reservation-1'})).authorized);
 assert((await call({action:'BOOK_SLOT_CANCEL',reservationId:'reservation-1',noClick:true})).authorized);
 assert.equal(data.bookingClickIssued,false);
 await ctx.audioCommand('TEST');assert.equal(native.at(-1).type,'AUDIO');
 await ctx.showDesktopNotification({title:'Test',message:'Safe'});assert.equal(native.at(-1).type,'NOTIFY');
 const result=await ctx.chrome.scripting.executeScript({target:{tabId:7},files:['a.js','b.js']});assert.equal(result.join(','),'a.js,b.js');
 const smsReply=await new Promise(resolve=>{
  const keep=listeners[0]({type:'SARATHI_SMS_READY',sessionId:'s1',url:'/sms'}, {tab:{id:7},url:'https://sarathi.parivahan.gov.in/slots/x'}, resolve);
  assert.equal(keep,true,'Alert listener must keep the response channel open');
 });
 assert.equal(smsReply.ok,true,'Remote delivery must finish even when native CRITICAL fails');
 assert(native.some(m=>m.type==='CRITICAL'),'Native CRITICAL attempt must be made');
 assert(native.some(m=>m.type==='NOTIFY'),'Desktop notification must still be attempted after native CRITICAL failure');
 assert(fetchCalls>=2,'Telegram must still be delivered to both configured recipients after native CRITICAL failure');
 assert.equal(data.smsAlertKey,'s1:7:/sms');
 const acknowledgementCount=native.filter(m=>m.type==='ACK_ALERT').length;
 const silenceReply=await new Promise(resolve=>listeners[0]({type:'AUDIO_CONTROL',action:'STOP'},{url:'moz-extension://test/monitor.html'},resolve));
 assert.equal(silenceReply.ok,true,'Explicit UI alarm acknowledgement must still succeed');
 assert.equal(native.filter(m=>m.type==='ACK_ALERT').length,acknowledgementCount+1,'An explicit user Silence action must still acknowledge native alarms');
 // Exercise both real background authorization boundaries with an exact fake clock.
 let gateNow=2000000;
 ctx.Date=class extends Date{constructor(...args){super(...(args.length?args:[gateNow]));}static now(){return gateNow;}};
 data.androidServiceReady=true;data.running=true;data.calendarLock=false;
 for(const [action,field] of [['PROCEED','lastProceedGateAt'],['PROCEED_CONFIRM','lastProceedConfirmedAt']]){
  data[field]=gateNow-6999;
  assert(!(await call({action})).authorized,action+' must reject a retry before seven seconds');
  gateNow++;
  assert((await call({action})).authorized,action+' must authorize at the seven-second boundary');
  assert.equal(data[field],gateNow,action+' must record the reservation clock');
  assert(!(await call({action})).authorized,action+' must reject a duplicate immediately');
 }
 console.log('PASS: Firefox injection, native audio/notification, document-bound reservation, service authorization, stopped cleanup, remote alerts survive native CRITICAL failure');
})().catch(e=>{console.error(e);process.exitCode=1});

