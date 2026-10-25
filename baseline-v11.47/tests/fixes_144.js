// Deterministic API/DOM mocks; not live Chrome or Sarathi tests.
const fs=require('fs'),vm=require('vm'),assert=require('assert');
const {background,assistHarness,flush}=require('./audit_140');
(async()=>{
 const b=background();let release;
 b.ctx.chrome.tabs={get:()=>new Promise(r=>release=r)};
 const check=b.alarmListeners[1]({name:'sarathi-health'});await flush();
 Object.assign(b.state,{monitorSessionId:'new',monitoredTabId:2,running:true,lastStatus:'New session running'});
 release(null);await check;assert.equal(b.state.running,true);assert.equal(b.state.lastStatus,'New session running');
 console.log('PASS: stale health check cannot stop replacement session');
 const w=background();
 const pending=w.call({type:'SARATHI_WAIT',sessionId:'one',ms:1000});await flush();
 assert.equal(w.timers.length,1);w.timers[0].f();assert.equal((await pending).ok,true);
 const cancelled=w.call({type:'SARATHI_WAIT',sessionId:'one',ms:500});await flush();w.state.running=false;w.timers.at(-1).f();assert.equal((await cancelled).cancelled,true);
 assert.equal((await w.call({type:'SARATHI_WAIT',sessionId:'old',ms:500})).cancelled,true);
 assert.equal((await w.call({type:'SARATHI_WAIT',sessionId:'one',ms:9000})).cancelled,true);
 console.log('PASS: worker waits respond; STOP, stale session and invalid duration cancel');
 const h=assistHarness();await h.step();h.page(true);h.quota(13);await h.step();h.tick(3000);
 h.ctx.actionAllowed=async action=>{if(action==='BOOK_SLOT'){h.state.bookingClickIssued=true;h.state.bookingSubmittedAt=h.ctx.Date.now();}return true};
 await h.step();h.tick(30000);await h.step();assert.equal(h.counts().books,1);assert.match(h.state.stopped,/30 seconds/);assert.equal(h.state.bookingClickIssued,true);assert.equal(h.state.bookingResponseWarned,true);
 await h.step();assert.equal(h.counts().books,1);
 console.log('PASS: missing booking response warns once and retains booking lock');
 const ui=fs.readFileSync(__dirname+'/../ui.js','utf8');
 for(const phase of ['inject','message']){
   let releaseStart;const state={};const calls=[];
   const ctx={target:async()=>({id:1}),feedback:()=>{},refresh:()=>{},crypto:{randomUUID:()=> 'new-session'},window:{confirm:()=>true},chrome:{storage:{local:{get:async d=>({...d,...state}),set:async d=>Object.assign(state,d)}},scripting:{executeScript:()=>phase==='inject'?new Promise(r=>releaseStart=r):Promise.resolve()},tabs:{sendMessage:async(id,msg)=>{calls.push(msg.type);if(msg.type==='SARATHI_START'&&phase==='message')await new Promise(r=>releaseStart=r);return {ok:true}}}}};
   vm.createContext(ctx);vm.runInContext(ui.slice(ui.indexOf('let runBusy='),ui.indexOf("$('autoBookingEnabled').addEventListener")),ctx);
   const starting=ctx.run(true);await flush();await ctx.run(false);releaseStart();await starting;
   assert.equal(state.running,false);assert.equal(state.successWatch,false);assert(calls.includes('SARATHI_STOP'));
   if(phase==='inject')assert(!calls.includes('SARATHI_START'));
 }
 console.log('PASS: STOP cancels START during injection and while awaiting page response');
 assert(ui.includes('Retry overdue'));assert(!ui.includes('Keep the monitor open for a fallback alarm'));
 console.log('PASS: overdue retry wording and misleading audio fallback advice corrected');
})().catch(e=>{console.error(e);process.exitCode=1});
