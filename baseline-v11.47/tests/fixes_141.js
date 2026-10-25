// Production parser -> content alert -> background handler; no real network calls.
const fs=require('fs'),vm=require('vm'),assert=require('assert');
const {background,assistHarness,flush}=require('./audit_140');
const {ctx:parser,parse}=require('./quota_dom_140');
const code=fs.readFileSync(__dirname+'/../content.js','utf8');
function section(start,end){const a=code.indexOf(start);return code.slice(a,code.indexOf(end,a));}
async function testQuotaAlerts(){
 const b=background({monitorSessionId:'s',calendarLock:true,scanPending:'2026-12-02'});
 const ctx={sessionId:'s',chrome:{storage:b.ctx.chrome.storage,runtime:{sendMessage:b.call}},location:{origin:'https://sarathi.parivahan.gov.in',pathname:'/slots/fetchdlslotdetinfo.do'}};
 vm.createContext(ctx);vm.runInContext(section('  async function publishQuotaAlert(','  async function safeAssistStop'),ctx);
 for(const [labels,expected,count] of [[['LMV'],'LMV_AVAILABLE','1'],[['MCWG'],'MCWG_AVAILABLE','13'],[['LMV, MCWG'],'BOTH_AVAILABLE','1000']]){
  await ctx.publishQuotaAlert(parse(false,count,labels),'2026-12-02','s');await flush();assert.equal(b.state.history[0].kind,expected);
 }
 const n=b.state.history.length;
 await ctx.publishQuotaAlert(parse(false,'1000'),'2026-12-02','s');await flush();assert.equal(b.state.history.length,n,'duplicate suppressed');
 for(const [snapshot,date,session] of [[parse(false,'0'),'2026-12-02','s'],[parse(false,'13 seats'),'2026-12-02','s'],[parse(false,'13'),null,'s'],[parse(false,'13'),'2026-12-03','s'],[parse(false,'13'),'2026-12-02','old']])await ctx.publishQuotaAlert(snapshot,date,session);
 await flush();assert.equal(b.state.history.length,n,'zero, ambiguous, missing/wrong date, stale session produce no quota alert');
 console.log('PASS: real quota parser -> LMV/MCWG/BOTH alert history; duplicate and invalid alert rejection');
}
function testDates(){
 let text='';const ctx={BookingRules:parser.BookingRules,pageText:()=>text};vm.createContext(ctx);vm.runInContext(section('  function quotaDisplayedDate()','  async function publishQuotaAlert'),ctx);
 for(const value of ['','DOB 02-12-2026','Available Time Slots 31-02-2026','Available Time Slots 02-12-2026 Available Time Slots 03-12-2026']){text=value;assert.equal(ctx.quotaDisplayedDate(),null)}
 text='Available Time Slots 02-12-2026';assert.equal(ctx.quotaDisplayedDate(),'2026-12-02');
 console.log('PASS: absent, unrelated, invalid and conflicting date labels rejected');
}
async function integrated(mode){
 const h=assistHarness(),b=background(h.state);h.ctx.chrome.storage.local=b.ctx.chrome.storage.local;h.ctx.chrome.runtime.sendMessage=b.call;
 h.ctx.actionAllowed=async(action,extra={})=>{const r=await b.call({type:'SARATHI_ACTION_GATE',action,sessionId:'s',...extra});if(action==='BOOK_SLOT_CONFIRM'&&mode==='change')h.quota(0);return r.authorized};
 await h.step();h.page(true);h.quota(13);
 if(mode==='missing')h.ctx.quotaDisplayedDate=()=>null;
 if(mode==='wrong')h.ctx.quotaDisplayedDate=()=> '2026-12-03';
 if(mode==='throw')h.ctx.document.querySelector('button#slotbtn').click=()=>{throw Error('Unknown click outcome')};
 await h.step();h.tick(10000);await h.step();await flush();return {h,b};
}
async function testReservations(){
 for(const mode of ['missing','wrong']){const {h}=await integrated(mode);assert.equal(h.counts().books,0);assert.equal(!!h.state.bookingClickIssued,false);assert.ok(h.state.stopped)}
 const aborted=await integrated('change');assert.equal(aborted.h.counts().books,0);assert.equal(aborted.h.state.bookingClickIssued,false,'proven no-click releases lock');assert.equal(aborted.h.state.autoBookingEnabled,false,'manual review remains required');
 const ok=await integrated('normal');assert.equal(ok.h.counts().books,1);assert.equal(ok.h.state.bookingClickIssued,true);await ok.h.step();assert.equal(ok.h.counts().books,1);
 assert.equal((await ok.b.call({type:'SARATHI_ACTION_GATE',action:'BOOK_SLOT_CANCEL',sessionId:'s',reservationId:ok.b.state.bookingReservationId,noClick:true})).authorized,false,'dispatched booking cannot be cancelled');
 const uncertain=await integrated('throw');assert.equal(uncertain.h.state.bookingClickIssued,true,'throwing click remains locked');
 const b=background({calendarLock:true,autoBookingEnabled:true});const gate=(action,extra={},doc='doc1')=>b.call({type:'SARATHI_ACTION_GATE',action,sessionId:'one',reservationId:'reservation-one',...extra},doc);
 assert.equal((await gate('BOOK_SLOT',{date:'2026-12-02',kind:'LMV'})).authorized,true);
 assert.equal((await gate('BOOK_SLOT_CANCEL',{noClick:true},'other-document')).authorized,false);
 assert.equal((await gate('BOOK_SLOT_CANCEL',{noClick:true,reservationId:'old-token'})).authorized,false);
 b.state.running=false;assert.equal((await gate('BOOK_SLOT_CANCEL',{noClick:true})).authorized,true,'no-click cleanup works after STOP');assert.equal(b.state.bookingClickIssued,false);
 console.log('PASS: missing/wrong dates block booking; no-click cancellation; success/uncertain locks; token/document isolation and STOP cleanup');
}
async function testNotification(){
 const b=background();await b.ctx.showDesktopNotification({type:'basic',title:'test',message:'test',iconUrl:'icon.png'},'offline-test');
 const alarm=b.createdAlarms.find(a=>a.name==='notification-clear:offline-test');assert.equal(alarm.info.delayInMinutes,1);
 // Simulate missing timer after a worker suspension; dispatch only the durable alarm.
 for(const fn of b.alarmListeners)await fn({name:alarm.name});await flush();assert.ok(b.clearedNotifications.includes('offline-test'));
 console.log('PASS: notification cleanup alarm works without timeout callback');
}
(async()=>{testDates();await testQuotaAlerts();await testReservations();await testNotification()})().catch(e=>{console.error(e);process.exitCode=1});
