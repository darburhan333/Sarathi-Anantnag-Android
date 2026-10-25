const assert=require('assert'),fs=require('fs'),vm=require('vm');
const flush=async()=>{for(let i=0;i<100;i++)await Promise.resolve()};
function harness(extra={}){
 const now=Date.now(),token='123456:'+ 'a'.repeat(25);
 const state={telegramStatusEnabled:false,botToken:token,chatId:'7930718570',telegramStatusBot:'123456',telegramStatusSince:now-60000,telegramStatusOffset:0,running:true,successWatch:true,monitorSessionId:'A',monitoredTabId:7,bookingStage:'MONITORING',attempts:153,attemptDay:new Date(now).toLocaleDateString('en-CA'),sessionAttempts:42,sessionCounterId:'A',lastAttemptAt:now-8000,...extra};
 const events=[],alarms=[],requests=[];let updates=[];
 const local={get:async d=>Object.fromEntries(Object.entries(d).map(([k,v])=>[k,state[k]===undefined?v:state[k]])),set:async p=>{const changes={};for(const [k,v] of Object.entries(p)){if(state[k]!==v)changes[k]={newValue:v};state[k]=v;}for(const f of events)f(changes,'local');}};
 const chrome={storage:{local,onChanged:{addListener:f=>events.push(f)}},alarms:{create(){},onAlarm:{addListener:f=>alarms.push(f)}},tabs:{get:async()=>({id:7,url:'https://sarathi.parivahan.gov.in/slots/a'}),sendMessage:async()=>({ok:true,owner:true,sessionId:'A',covPresent:true,loopActive:true,lastLoopProgress:Date.now()-1000})}};
 const ctx={chrome,module:{exports:{}},Date,console,AbortController,setTimeout,clearTimeout,fetch:async(url,o)=>{const body=JSON.parse(o.body);requests.push({url,body});return {ok:true,json:async()=>({ok:true,result:url.endsWith('getUpdates')?updates:true})}}};vm.createContext(ctx);vm.runInContext(fs.readFileSync(__dirname+'/../telegram-status.js','utf8'),ctx);
 const msg=(id=1,patch={})=>({update_id:id,message:{chat:{id:7930718570,type:'private'},from:{id:7930718570,is_bot:false},date:Math.floor(Date.now()/1000),text:'/status',...patch}});
 return {ctx,state,chrome,requests,alarms,msg,setUpdates:x=>updates=x,api:ctx.module.exports};
}
(async()=>{
 let h=harness();await flush();assert.equal(h.requests.length,0);
 h.state.telegramStatusEnabled=true;h.setUpdates([h.msg()]);await h.api.poll();assert.equal(h.requests.length,2);assert.match(h.requests[1].body.text,/RUNNING — recent/);assert.match(h.requests[1].body.text,/session: 42/);assert.equal(h.state.telegramStatusOffset,2);assert(h.state.telegramStatusLastReply);assert(!h.requests[1].body.text.includes(h.state.botToken));
 await h.api.poll();assert.equal(h.requests.filter(x=>x.url.endsWith('sendMessage')).length,1,'no replay');
 for(const patch of [{chat:{id:123,type:'private'}},{from:{id:123}},{chat:{id:7930718570,type:'group'}},{date:1},{text:'/stop'},{text:'/status@unknown_bot'},{from:{id:7930718570,is_bot:true}}]){
  h=harness();await flush();h.state.telegramStatusEnabled=true;h.setUpdates([h.msg(2,patch)]);await h.api.poll();assert.equal(h.requests.length,1,'unauthorized/stale/unsupported ignored');
 }
 h=harness();await flush();h.state.telegramStatusEnabled=true;h.setUpdates([h.msg(1),h.msg(2),h.msg(3)]);await h.api.poll();assert.equal(h.requests.length,2,'burst coalesced');assert.equal(h.state.telegramStatusOffset,4);
 h=harness();await flush();h.state.telegramStatusEnabled=true;h.ctx.fetch=async()=>({ok:false,status:409,json:async()=>({ok:false,error_code:409})});await h.api.poll();assert.match(h.state.telegramStatusError,/ONE profile/);
 h=harness();await flush();h.state.telegramStatusEnabled=true;h.ctx.fetch=async()=>{throw Error('offline '+h.state.botToken)};await h.api.poll();assert(!h.state.telegramStatusError.includes(h.state.botToken));
 h=harness();await flush();h.chrome.tabs.sendMessage=async()=>({ok:true,owner:true,sessionId:'A',covPresent:true,loopActive:false,lastLoopProgress:Date.now()});assert.match(await h.api.report(),/WAITING \/ DELAYED/);
 h.chrome.tabs.sendMessage=async()=>{throw Error('gone')};assert.match(await h.api.report(),/ACTIVITY UNCONFIRMED/);
 h.state.running=false;h.state.successWatch=false;assert.match(await h.api.report(),/Monitor: STOPPED/);
 h.state.monitorFailureKey='err';assert.match(await h.api.report(),/ERROR \/ MANUAL/);
 h=harness({running:false,successWatch:true,bookingStage:'AWAITING MANUAL SMS VERIFICATION',bookingClickIssued:true});await flush();assert.match(await h.api.report(),/WAITING FOR MANUAL OTP/);
 h=harness({calendarLock:true});await flush();assert.match(await h.api.report(),/COV loop paused/);
 h=harness();await flush();h.chrome.tabs.sendMessage=async()=>{h.state.monitorSessionId='B';return {ok:true,owner:true,sessionId:'A'}};assert.match(await h.api.report(),/STATE CHANGED/);
 h=harness({attemptDay:'2000-01-01'});await flush();assert.match(await h.api.report(),/PC local time\): 0/);
 h=harness();await flush();h.state.telegramStatusEnabled=true;let release;h.ctx.fetch=()=>new Promise(r=>release=r);const p=h.api.poll();await flush();await h.api.poll();await h.chrome.storage.local.set({telegramStatusEnabled:false});release({ok:true,json:async()=>({ok:true,result:[h.msg()]})});await p;assert.equal(h.state.telegramStatusOffset,0,'disable invalidates pending response');
 h=harness();await flush();h.state.telegramStatusEnabled=true;h.setUpdates([h.msg()]);const original=h.ctx.fetch;h.ctx.fetch=async(url,o)=>{if(url.endsWith('sendMessage'))throw Error('offline');return original(url,o)};await h.api.poll();assert.equal(h.state.telegramStatusOffset,2);assert.match(h.state.telegramStatusError,/offline/);assert(!h.state.telegramStatusLastReply);
 h=harness({telegramStatusBot:'old',telegramStatusSince:0});await flush();h.state.telegramStatusEnabled=true;h.setUpdates([h.msg(1,{date:Math.floor(Date.now()/1000)-60})]);await h.api.poll();assert.equal(h.requests.length,1,'old messages on activation ignored');assert.equal(h.state.telegramStatusBot,'123456');
 console.log('PASS Telegram: authorization, read-only commands, fresh/stale health, OTP/calendar/stopped/error states, day rollover, sessions, poll overlap, disable race, cursor dedup, burst limit, conflict, network failure, redaction, activation backlog.');
})().catch(e=>{console.error(e);process.exitCode=1});
