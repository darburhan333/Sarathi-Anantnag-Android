// Production background.js with mock Chrome event delivery; no network/site calls.
const assert=require('assert'),fs=require('fs'),vm=require('vm');
const flush=async()=>{for(let i=0;i<200;i++)await Promise.resolve()};
function harness(extra={}){
 const state={running:true,successWatch:true,monitoredTabId:7,monitorSessionId:'run-A',navigationSessionId:'run-A',navigationLastAt:1000,desktopEnabled:true,telegramEnabled:true,soundEnabled:true,botToken:'123456:'+ 'a'.repeat(25),chatId:'7930718570',history:[],...extra};
 const nav={},alarms=[],notices=[],requests=[],changes=[],tabsRemoved=[],timers=[];
 const local={get:async k=>typeof k==='string'?{[k]:state[k]}:Array.isArray(k)?Object.fromEntries(k.map(x=>[x,state[x]])):Object.fromEntries(Object.entries(k).map(([x,v])=>[x,state[x]===undefined?v:state[x]])),set:async p=>{const c={};for(const [k,v] of Object.entries(p)){if(state[k]!==v)c[k]={oldValue:state[k],newValue:v};state[k]=v;}for(const f of changes)f(c,'local');}};
 const chrome={storage:{local,onChanged:{addListener:f=>changes.push(f)}},runtime:{getURL:p=>'chrome-extension://test/'+p,onMessage:{addListener(){}},sendMessage:async m=>{requests.push(m);return {ok:true}}},webNavigation:Object.fromEntries(['onBeforeNavigate','onCompleted','onErrorOccurred'].map(k=>[k,{addListener:f=>nav[k]=f}])),tabs:{get:async()=>({id:7,url:'https://sarathi.parivahan.gov.in/slots/proceeddlapmnt.do',status:'complete'}),sendMessage:async()=>{throw Error('No receiver')},onRemoved:{addListener:f=>tabsRemoved.push(f)}},scripting:{executeScript:async()=>{throw Error('Cannot access Chrome error page')}},alarms:{create(){},clear:async()=>true,onAlarm:{addListener:f=>alarms.push(f)}},windows:{onBoundsChanged:{addListener(){}}},notifications:{create:(id,o,cb)=>{notices.push(o);cb(id)},clear:(id,cb)=>cb(true)},offscreen:{hasDocument:async()=>true}};
 const ctx={chrome,URL,Date,Math,Promise,console,AbortController,setTimeout:(f,ms)=>{const t={f,ms};timers.push(t);return t},clearTimeout(){},fetch:async(url,o)=>{requests.push(JSON.parse(o.body));return {ok:true,json:async()=>({ok:true})}}};
 vm.createContext(ctx);vm.runInContext(fs.readFileSync(__dirname+'/../background.js','utf8'),ctx);
 const error=(patch={})=>({frameId:0,tabId:7,url:'https://sarathi.parivahan.gov.in/slots/proceeddlapmnt.do?private=value',error:'net::ERR_HTTP2_PROTOCOL_ERROR',timeStamp:2000,...patch});
 return {ctx,state,chrome,nav,alarms,notices,requests,tabsRemoved,error};
}
(async()=>{
 let h=harness({navigationSessionId:'',navigationLastAt:0});h.nav.onBeforeNavigate(h.error({timeStamp:1500}));h.nav.onErrorOccurred(h.error());await flush();
 assert.equal(h.state.running,false);assert.equal(h.state.successWatch,false);assert.equal(h.state.alarmActive,true);assert.equal(h.state.nextRetryAt,0);assert.match(h.state.lastStatus,/ERR_HTTP2_PROTOCOL_ERROR/);assert.equal(h.notices.length,1);assert.equal(h.requests.filter(x=>x.chat_id).length,1);assert(h.requests.some(x=>x.action==='START'));assert(!JSON.stringify(h.state.history).includes('private=value'));
 h.nav.onErrorOccurred(h.error());await flush();assert.equal(h.notices.length,1);
 for(const patch of [{frameId:1},{tabId:8},{error:'net::ERR_ABORTED'},{url:'https://other.example/'},{timeStamp:999}]){h=harness();h.nav.onErrorOccurred(h.error(patch));await flush();assert(h.state.running);assert.equal(h.notices.length,0);}
 for(const code of ['ERR_INTERNET_DISCONNECTED','ERR_NAME_NOT_RESOLVED','ERR_CONNECTION_RESET','ERR_TIMED_OUT','ERR_CERT_DATE_INVALID']){h=harness();await h.ctx.navigationFailure(h.error({error:'net::'+code}));assert.match(h.state.lastStatus,new RegExp(code));}
 h=harness({navigationSessionId:'old-session'});await h.ctx.navigationFailure(h.error());assert(h.state.running);
 h=harness({monitorStartedAt:Date.now()+999999});await h.ctx.navigationFailure(h.error());assert(!h.state.running,'browser clock not compared to extension clock');
 h=harness({running:false,successWatch:false});await h.ctx.navigationFailure(h.error());assert(!h.state.alarmActive);
 h=harness({running:false,successWatch:true,calendarLock:true,bookingClickIssued:true,bookingReservationId:'keep',bookingDispatchStarted:true});await h.ctx.navigationFailure(h.error());assert(h.state.bookingClickIssued);assert.equal(h.state.bookingReservationId,'keep');assert(h.state.bookingDispatchStarted);assert.match(h.state.lastStatus,/uncertain/);
 h=harness();await h.ctx.recordNavigation(h.error({timeStamp:3000}),false);await h.ctx.navigationFailure(h.error());assert(h.state.running);
 h=harness();await h.ctx.recordNavigation(h.error({timeStamp:3000}),true);await h.ctx.navigationFailure(h.error());assert(h.state.running);
 h=harness();await h.ctx.reportMonitorFailure({monitorSessionId:'old',monitoredTabId:7},'TEST','old error');assert(h.state.running);
 h=harness();let release;const oldGet=h.chrome.storage.local.get;h.chrome.storage.local.get=async k=>{if(k.monitorFailureKey!==undefined)await new Promise(r=>release=r);return oldGet(k)};
 const pending=h.ctx.reportMonitorFailure({monitorSessionId:'run-A',monitoredTabId:7},'TEST','stale');await flush();h.state.monitorSessionId='run-B';release();await pending;assert(h.state.running);assert.equal(h.notices.length,0);
 h=harness({desktopEnabled:false,telegramEnabled:false,soundEnabled:false});await h.ctx.navigationFailure(h.error());await flush();assert.equal(h.notices.length,0);assert.equal(h.requests.filter(x=>x.chat_id).length,0);assert(!h.requests.some(x=>x.action==='START'));
 h=harness();h.ctx.fetch=async()=>{throw Error('offline')};await h.ctx.navigationFailure(h.error());assert.equal(h.notices.length,1);assert(h.state.alarmActive);assert.match(h.state.failureTelegramError,/offline/);
 h=harness();h.tabsRemoved[0](7);await flush();assert.match(h.state.lastStatus,/closed/);assert.equal(h.notices.length,1);
 h=harness();h.chrome.tabs.get=async()=>null;await h.alarms[1]({name:'sarathi-health'});assert.equal(h.state.running,false);assert.equal(h.notices.length,1);
 h=harness({calendarLock:true,bookingClickIssued:true,healthMisses:2});await h.alarms[1]({name:'sarathi-health'});assert.equal(h.state.running,false);assert(h.state.bookingClickIssued);assert(h.state.alarmActive);assert.equal(h.notices.length,1);
 h=harness({running:false,successWatch:true,calendarLock:true,healthMisses:2});await h.alarms[1]({name:'sarathi-health'});assert.equal(h.state.successWatch,false);assert.equal(h.notices.length,1);
 h=harness();let releasePing;h.chrome.tabs.sendMessage=()=>new Promise(r=>releasePing=r);let injections=0;h.chrome.scripting.executeScript=async()=>{injections++};
 const health=h.alarms[1]({name:'sarathi-health'});await flush();Object.assign(h.state,{running:false,successWatch:true,calendarLock:true,bookingClickIssued:true,bookingStage:'AWAITING MANUAL SMS VERIFICATION'});releasePing(null);await health;assert.equal(injections,0);assert.equal(h.notices.length,0);assert.equal(h.state.bookingStage,'AWAITING MANUAL SMS VERIFICATION');
 h=harness({running:false,successWatch:true,calendarLock:true});h.chrome.tabs.sendMessage=async()=>({ok:true,owner:true,sessionId:'run-A',calendar:false,covPresent:false});await h.alarms[1]({name:'sarathi-health'});assert(h.state.successWatch);assert.equal(h.notices.length,0);
 const legacy=require('./audit_140');for(const kind of ['BOOKING_REVIEW','SESSION']){const b=legacy.background();await b.call({type:'SARATHI_WARNING',sessionId:'one',kind,message:'Manual attention'});assert.equal(b.state.alarmActive,true);}
 console.log('PASS: HTTP2 and five other errors; event registration; owner/main-frame filtering; aborted/stopped/stale events; dedup; completed/new navigation; disabled delivery; partial failure; no URL leakage; tab closure/disconnection; calendar and OTP health; lock retention; sound request.');
})().catch(e=>{console.error(e);process.exitCode=1});
