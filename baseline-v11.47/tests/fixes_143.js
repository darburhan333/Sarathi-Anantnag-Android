const fs=require('fs'),vm=require('vm'),assert=require('assert');
const {ctx:parser,parse,E}=require('./quota_dom_140');
const {background,assistHarness,flush}=require('./audit_140');
const source=fs.readFileSync(__dirname+'/../content.js','utf8');
function testPlain(){
 for(const value of ['0','1','13','1000']){
  const q=parse(false,value,['LMV, MCWG'],{plain:true});assert.equal(q.total,1);assert.equal(q.entries.length,1);assert.equal(q.entries[0].count,Number(value));
 }
 for(const value of ['13 seats','13 14','-1','1.5','9007199254740992'])assert.equal(parse(false,value,['LMV'],{plain:true}).entries.length,0,value);
 assert.equal(parse(false,'13',['LMV'],{plain:true,disabled:true}).entries.length,0);
 assert.equal(parse(false,'0',['LMV'],{plain:true,disabled:true}).entries[0].count,0);
 const table=E('table','',[E('tr','',[E('th','Time Slots'),E('th','LMV')]),E('tr','',[E('td','09.00-18.00'),E('td','',[E('input','',[],{type:'radio',name:'LMV'}),E('span','1'),E('span','3')])])]);
 const cell=table.children[1].children[1];Object.defineProperty(cell,'innerText',{get:()=> '13'});parser.document={querySelectorAll:()=>[table]};assert.equal(parser.quotaOptions().entries.length,0,'two adjacent numeric spans must not concatenate into one count');
 console.log('PASS: plain-text integer quotas, disabled-positive rejection, malformed-count rejection');
}
async function testPlainAlert(){
 const b=background({monitorSessionId:'s',calendarLock:true,scanPending:'2026-12-02'});
 const ctx={sessionId:'s',chrome:{storage:b.ctx.chrome.storage,runtime:{sendMessage:b.call}},location:{origin:'https://sarathi.parivahan.gov.in',pathname:'/slots/fetchdlslotdetinfo.do'}};
 vm.createContext(ctx);const a=source.indexOf('  async function publishQuotaAlert('),end=source.indexOf('  async function safeAssistStop',a);vm.runInContext(source.slice(a,end),ctx);
 await ctx.publishQuotaAlert(parse(false,'13',['LMV, MCWG'],{plain:true}),'2026-12-02','s');await flush();assert.equal(b.state.history[0].kind,'BOTH_AVAILABLE');
 console.log('PASS: plain-text quota reaches production combined-availability alert handler');
}
async function testRefusal(mode){
 const h=assistHarness(),b=background(h.state);h.ctx.chrome.storage.local=b.ctx.chrome.storage.local;h.ctx.chrome.runtime.sendMessage=b.call;
 h.ctx.actionAllowed=async(action,extra={})=>{
  if(action==='BOOK_SLOT_CONFIRM'){
   if(mode==='stop'){h.state.running=false;h.ctx.localStop=true;}
   if(mode==='session')h.state.monitorSessionId='new-session';
   if(mode==='disabled')h.state.autoBookingEnabled=false;
   return false;
  }
  return (await b.call({type:'SARATHI_ACTION_GATE',action,sessionId:'s',...extra})).authorized;
 };
 h.ctx.safeAssistStop=async message=>{h.state.autoBookingEnabled=false;h.state.stopped=message;await b.call({type:'SARATHI_WARNING',sessionId:'s',kind:'BOOKING_REVIEW',message})};
 await h.step();h.page(true);h.quota(13);await h.step();h.tick(3000);await h.step();await flush();
 assert.equal(h.counts().books,0);
 if(mode!=='session')assert.equal(h.state.bookingClickIssued,false,'same-session no-click reservation released');
 if(mode==='unexpected'){assert.match(h.state.stopped,/BOOK_SLOT_CONFIRM/);assert.equal(h.state.history.length,1)}else{assert.equal(h.state.stopped,undefined);assert.equal((h.state.history||[]).length,0)}
}
async function testWarnings(){
 const b=background({scanPending:'2026-12-02'});
 const send=message=>b.call({type:'SARATHI_WARNING',kind:'BOOKING_REVIEW',sessionId:b.state.monitorSessionId,message});
 await send('Quota changed');await send('Button disabled');await send('Quota changed');await send('Button disabled');assert.equal(b.state.history.length,2,'A B A B sends two alerts');
 b.state.scanPending='2026-12-03';await send('Quota changed');assert.equal(b.state.history.length,3);
 b.state.monitorSessionId='two';await send('Quota changed');assert.equal(b.state.history.length,4);
 b.state.warningEvents=b.state.warningEvents.map(e=>({...e,time:Date.now()-600001}));await send('Quota changed');assert.equal(b.state.history.length,5);
 console.log('PASS: warnings deduplicate independently by reason/date/session and expire after 10 minutes');
}
async function testCalendarWait(stopAfter){
 let inspections=0,checks=0;const waits=[];
 const ctx={sessionId:'s',loopActive:false,ownsTab:true,cachedRunning:true,loopGeneration:0,loopStartedAt:0,lastLoopProgress:0,bookingPaused:false,calendarLocked:false,sessionLocked:false,localStop:false,Date,EXPIRY_TEXT:/never-match/,pageText:()=>'',realCalendar:()=>true,inspectBookingPage:async()=>{inspections++},isRunning:async()=>{if(++checks>20)throw Error('Busy loop');return true},waitWhileRunning:async ms=>{waits.push(ms);return waits.length<stopAfter},setStatus:()=>{}};
 vm.createContext(ctx);const a=source.indexOf('  async function processLoop()'),b=source.indexOf('  chrome.runtime.onMessage',a);vm.runInContext(source.slice(a,b),ctx);
 await ctx.processLoop();assert.equal(inspections,stopAfter);assert.deepEqual(waits,Array(stopAfter).fill(500));assert.equal(ctx.loopActive,false);
}
(async()=>{testPlain();await testPlainAlert();for(const mode of ['unexpected','stop','session','disabled'])await testRefusal(mode);console.log('PASS: unexpected confirmation refusal alerts/releases; STOP, disabled assistance and new session stay quiet');await testWarnings();await testCalendarWait(3);await testCalendarWait(1);console.log('PASS: calendar inspection always yields 500ms; STOP-aware wait exits loop')})().catch(e=>{console.error(e);process.exitCode=1});
