const fs=require('fs'),vm=require('vm'),assert=require('assert'),path=require('path');
const root=path.resolve(__dirname,'../app/src/main/assets/extension');
(async()=>{
 const listeners=[],changes=[],native=[];
 const data={androidServiceReady:true,running:true,monitoredTabId:7,monitorSessionId:'s1',calendarLock:true,autoBookingEnabled:true,bookingClickIssued:false};
 const browser={
  storage:{local:{get:async k=>typeof k==='string'?{[k]:data[k]}:Array.isArray(k)?Object.fromEntries(k.map(x=>[x,data[x]])):Object.fromEntries(Object.entries(k).map(([x,v])=>[x,data[x]===undefined?v:data[x]])),set:async p=>{const c={};for(const [k,v]of Object.entries(p)){c[k]={oldValue:data[k],newValue:v};data[k]=v;}changes.forEach(f=>f(c,'local'));}},onChanged:{addListener:f=>changes.push(f)}},
  runtime:{getURL:p=>'moz-extension://test/'+p,onMessage:{addListener:f=>listeners.push(f)},sendNativeMessage:async(app,m)=>{native.push(m);return {ok:true};}},
  tabs:{onRemoved:{addListener(){}},executeScript:async(id,o)=>[o.file],sendMessage:async()=>({ok:true})},
  alarms:{create(){},onAlarm:{addListener(){}}},
  webNavigation:{onBeforeNavigate:{addListener(){}},onCompleted:{addListener(){}},onErrorOccurred:{addListener(){}}}
 };
 const ctx={browser,console,URL,Date,Math,Promise,setTimeout,clearTimeout,AbortController,fetch:()=>{throw Error('Network forbidden in tests')}};vm.createContext(ctx);
 for(const f of ['compat.js','background.js'])vm.runInContext(fs.readFileSync(path.join(root,f),'utf8'),ctx);
 const call=m=>new Promise(resolve=>listeners[0]({type:'SARATHI_ACTION_GATE',sessionId:'s1',documentNonce:'doc1',...m},{tab:{id:7},url:'https://sarathi.parivahan.gov.in/slots/x'},resolve));
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
 console.log('PASS: Firefox injection, native audio/notification, document-bound reservation, service authorization, stopped cleanup');
})().catch(e=>{console.error(e);process.exitCode=1});
