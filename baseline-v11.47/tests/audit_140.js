// Targeted regression coverage for the V11.40 audit. All APIs/network are mocks.
const fs=require('fs'),vm=require('vm'),assert=require('assert');
const root=__dirname+'/../',content=fs.readFileSync(root+'content.js','utf8');
const rules={};vm.runInNewContext(fs.readFileSync(root+'booking-rules.js','utf8'),rules);
function extract(name,end){const a=content.indexOf('  '+name);return content.slice(a,content.indexOf(end,a));}
const flush=async()=>{for(let i=0;i<100;i++)await Promise.resolve();};
function background(initial={}){
 const state=Object.assign(initial,{running:true,successWatch:true,monitorSessionId:'one',monitoredTabId:1,telegramEnabled:false,desktopEnabled:false,...initial});
 let message;const timers=[],requests=[],alarmListeners=[],createdAlarms=[],clearedNotifications=[];
 const local={get:async keys=>typeof keys==='string'?{[keys]:state[keys]}:Array.isArray(keys)?Object.fromEntries(keys.map(k=>[k,state[k]])):{...keys,...state},set:async v=>Object.assign(state,v)};
 const chrome={storage:{local,onChanged:{addListener(){}}},runtime:{getURL:x=>'chrome-extension://test/'+x,onMessage:{addListener:f=>message=f},sendMessage:async()=>({ok:true})},offscreen:{createDocument:async()=>{state.created=(state.created||0)+1}},alarms:{create:(name,info)=>createdAlarms.push({name,info}),clear:async()=>true,onAlarm:{addListener:f=>alarmListeners.push(f)}},windows:{onBoundsChanged:{addListener(){}}},notifications:{create:(...args)=>args.at(-1)('n'),clear:(...args)=>{clearedNotifications.push(args[0]);args.at(-1)(true)}}};
 const ctx={chrome,clients:{matchAll:async()=>[]},AbortController,console,Date,Math,Promise,setTimeout:(f,ms)=>{const t={f,ms,cleared:false};timers.push(t);return t},clearTimeout:t=>t.cleared=true,fetch:async(...args)=>{requests.push(args);return {ok:true,status:200,json:async()=>({ok:true})}}};
 vm.createContext(ctx);ctx.importScripts=file=>vm.runInContext(fs.readFileSync(root+file,'utf8'),ctx);vm.runInContext(fs.readFileSync(root+'background.js','utf8'),ctx);
 assert(createdAlarms.some(a=>a.name==='sarathi-telegram-status'),'production worker loads status module');
 const call=(msg,documentId='doc1')=>new Promise(resolve=>message(msg,{tab:{id:1},documentId,url:'https://sarathi.parivahan.gov.in/slots/test'},resolve));
 return {ctx,state,call,timers,requests,alarmListeners,createdAlarms,clearedNotifications};
}
async function testAlerts(){
 const b=background();await b.ctx.ensureAudioDocument();assert.equal(b.state.created,1,'Chrome109 fallback creates audio');
 b.ctx.clients.matchAll=async()=>[{url:'chrome-extension://test/audio.html'}];await b.ctx.ensureAudioDocument();assert.equal(b.state.created,1,'fallback reuses audio');
 await b.call({type:'SARATHI_BOOKED',sessionId:'one',bookingKey:'same-date-time-cov'});assert.equal(b.state.history.length,1);
 Object.assign(b.state,{monitorSessionId:'two',successWatch:true,running:true});
 await b.call({type:'SARATHI_BOOKED',sessionId:'two',bookingKey:'same-date-time-cov'});assert.equal(b.state.history.length,2,'separate sessions both notify');
 Object.assign(b.state,{monitorSessionId:'three',successWatch:true,running:true,lastStatus:'new run'});
 await b.call({type:'SARATHI_WARNING',sessionId:'two',kind:'SESSION',message:'old'});assert.equal(b.state.lastStatus,'new run');
 await b.call({type:'SARATHI_BOOKED',sessionId:'two',bookingKey:'different'});assert.equal(b.state.running,true);
 Object.assign(b.state,{running:false,successWatch:false});await b.call({type:'SARATHI_SMS_READY',sessionId:'three'});assert.equal(b.state.smsAlertKey,undefined);
 const q=background({botToken:'123456:'+ 'a'.repeat(25)});let release;
 q.ctx.fetch=async()=>({ok:true,status:200,json:()=>new Promise(r=>release=r)});
 const held=q.call({type:'TEST_TELEGRAM'});await flush();
 await q.call({type:'SARATHI_SMS_READY',sessionId:'one',url:'/sms'});assert.equal(q.state.history[0].kind,'SMS_READY','SMS state bypasses slow network delivery');
 const timer=q.timers.find(t=>t.ms===12000);assert.equal(timer.cleared,false,'timeout remains active while body pending');release({ok:true});await held;assert.equal(timer.cleared,true);
 const t=background({botToken:'123456:'+ 'a'.repeat(25)});
 t.ctx.fetch=async(url,opts)=>({ok:true,status:200,json:()=>new Promise((resolve,reject)=>opts.signal.addEventListener('abort',()=>reject(Error('aborted body'))))});
 const timed=t.ctx.sendTelegram('test').then(()=>false,()=>true);await flush();t.timers.find(x=>x.ms===12000).f();assert.equal(await timed,true,'body timeout aborts delivery');
 console.log('PASS: Chrome109 audio fallback, receipt session dedup, stale/stopped alerts, nonblocking alert queue, body timeout');
}
async function testAudio(){
 let listener,timers=0,plays=0;
 const ctx={URL:{createObjectURL:()=>''},Blob,Audio:class{pause(){}async play(){plays++}},setInterval:()=>++timers,clearInterval:()=>{},chrome:{runtime:{onMessage:{addListener:f=>listener=f}}}};
 vm.runInNewContext(fs.readFileSync(root+'audio.js','utf8'),ctx);
 const call=m=>new Promise(r=>listener({type:'OFFSCREEN_AUDIO',...m},{},r));
 assert.equal((await call({action:'START',settings:{volume:0}})).ok,false);
 assert.equal((await call({action:'UPDATE',settings:{volume:85}})).ok,true);assert.equal(timers,1);assert.equal(plays,1);
 await call({action:'UPDATE',settings:{volume:65}});assert.equal(timers,1,'volume updates preserve existing timer');
 await call({action:'STOP'});await call({action:'UPDATE',settings:{soundEnabled:false}});assert.equal(plays,1);
 console.log('PASS: volume zero recovery, volume updates, disabled sound');
}
function assistHarness(){
 let now=10000,clicks=0,books=0,backs=0,calendar=true,quota=0;
 const state={autoBookingEnabled:true,calendarLock:true,running:true,monitorSessionId:'s',scanMonth:'2026-12',scanChecked:[],scanPending:''};
 const radio={checked:false,disabled:false,isConnected:true,click(){this.checked=true}};
 const button={disabled:false,isConnected:true,click(){books++}},back={isConnected:true,click(){backs++}};
 const ctx={sessionId:'s',bookingActionBusy:false,localStop:false,ownsTab:true,quotaPageSince:0,quotaSignature:'',quotaStableAt:0,smsAlertSent:false,Date:{now:()=>now},chrome:{storage:{local:{get:async defaults=>({...defaults,...state}),set:async x=>Object.assign(state,x)}},runtime:{sendMessage:async()=>{}}},publishQuotaAlert:async()=>{},isSmsVerificationPage:()=>false,document:{querySelector:s=>s==='button#slotbtn'?(calendar?null:button):s==='#onlinedltestslotform_back'?back:null},location:{pathname:'/calendar'},realCalendar:()=>calendar,calendarCandidates:()=>[{key:'2026-12-02',target:{isConnected:true,click:()=>clicks++}},{key:'2026-12-03',target:{isConnected:true,click:()=>clicks++}}],calendarMonth:()=>({key:'2026-12'}),calendarDiagnostic:()=>{},isRunning:async()=>true,actionAllowed:async()=>true,visible:()=>true,safeAssistStop:async m=>{state.stopped=m;state.autoBookingEnabled=false},quotaOptions:()=>({entries:[{radio,kind:'BOTH',count:quota,slotTime:'09.00-18.00'}],total:1}),quotaDisplayedDate:()=>state.scanPending,setStatus:()=>{},sleep:async ms=>{now+=ms}};
 vm.createContext(ctx);vm.runInContext(extract('async function assistBooking()','  // End assisted booking.'),ctx);
 return {ctx,state,radio,step:()=>ctx.assistBooking(),tick:ms=>now+=ms,page:q=>{calendar=!q;ctx.location.pathname=q?'/slots/fetchdlslotdetinfo.do':'/calendar'},quota:n=>quota=n,counts:()=>({clicks,books,backs})};
}
async function testAssist(){
 const h=assistHarness();await h.step();await h.step();assert.equal(h.counts().clicks,1);h.tick(16000);await h.step();assert.match(h.state.stopped,/timed out/);assert.equal(h.counts().clicks,1);
 const z=assistHarness();await z.step();z.page(true);await z.step();z.tick(2000);await z.step();await z.step();assert.equal(z.counts().backs,1);assert.equal(z.state.scanPending,'2026-12-02');z.page(false);await z.step();assert.equal(z.state.scanPending,'2026-12-03');assert.equal(z.counts().clicks,2);
 const denied=assistHarness();await denied.step();denied.page(true);denied.quota(13);await denied.step();denied.tick(2000);denied.ctx.actionAllowed=async()=>false;await denied.step();assert.equal(denied.state.scanPending,'2026-12-02');assert.equal(denied.counts().books,0);
 const changed=assistHarness();await changed.step();changed.page(true);changed.quota(13);await changed.step();changed.tick(2000);changed.ctx.actionAllowed=async action=>{if(action==='BOOK_SLOT_CONFIRM')changed.quota(0);return true};await changed.step();assert.equal(changed.counts().books,0);assert.match(changed.state.stopped,/changed/);
 const ok=assistHarness();await ok.step();ok.page(true);ok.quota(1000);await ok.step();ok.tick(2000);ok.ctx.actionAllowed=async action=>{if(action==='BOOK_SLOT')ok.state.bookingClickIssued=true;return true};await ok.step();await ok.step();assert.equal(ok.counts().books,1);
 console.log('PASS: one date click, timeout pause, one Back click, next date, pending retention, final quota revalidation, one BOOK SLOT');
}
function testQuota(){
 const r=rules.BookingRules;for(const n of ['0','1','13','999','1000','100000'])assert.equal(r.quota(n),Number(n));for(const n of ['-1','1.2','NaN','1e3','9007199254740992'])assert.equal(r.quota(n),null);
 const ctx={BookingRules:r,norm:s=>String(s||'').trim()};vm.createContext(ctx);vm.runInContext(extract('function quotaTableHeaders(','  function verifiedAvailabilityColumn'),ctx);
 const table=labels=>({querySelectorAll:()=>[{children:labels.map(textContent=>({tagName:'TH',textContent,getAttribute:()=>null}))}]});
 assert.equal(ctx.quotaTableHeaders(table(['Time Slots','LMV','Available Quota'])).length,3);
 assert.equal(ctx.quotaTableHeaders(table(['Time Slots','LMV, MCWG'])).length,2);
 assert.equal(ctx.quotaTableHeaders(table(['Time Slots','LMV','S.No.'])),null);
 console.log('PASS: safe integer quota and verified header layouts');
}
module.exports={background,assistHarness,flush};
if(require.main===module)(async()=>{testQuota();await testAssist();await testAlerts();await testAudio();})().catch(e=>{console.error(e);process.exitCode=1});
