// Exercise the shipped DOM IDs and route handler, without Sarathi or Telegram traffic.
const fs=require('fs'),vm=require('vm'),assert=require('assert'),path=require('path');
(async()=>{
 const root=path.resolve(__dirname,'../app/src/main/assets/extension');
 const html=fs.readFileSync(root+'/monitor.html','utf8'),ids=[...html.matchAll(/\bid="([^"]+)"/g)].map(m=>m[1]);
 assert.equal(new Set(ids).size,ids.length,'All shipped DOM IDs must be unique');
 const events={},elements=new Map();
 function classes(){const values=new Set();return {toggle(k,on){if(on===undefined)on=!values.has(k);on?values.add(k):values.delete(k)},contains:k=>values.has(k)}}
 for(const id of ids)elements.set(id,{value:'',checked:false,textContent:'',className:'',classList:classes(),addEventListener(t,f){(events[id+':'+t]??=[]).push(f)},replaceChildren(){}});
 const state={chatId:'111',chatId2:'222',botToken:'123456:'+'a'.repeat(25),assistedBookingPreference:true,running:false,history:[]};
 const ctx={document:{getElementById:id=>elements.get(id)||null,querySelectorAll:()=>[],querySelector:()=>null,body:{classList:classes()},hidden:false,activeElement:null,createElement:()=>({}),addEventListener(){}},window:{addEventListener(){},confirm:()=>true},chrome:{runtime:{onMessage:{addListener(){}}},storage:{local:{get:async defaults=>({...defaults,...state}),set:async values=>Object.assign(state,values)},onChanged:{addListener(){}}}},navigator:{onLine:true},location:{hash:'#monitor'},setInterval(){},setTimeout(){},Date,console,Blob,URL};
 vm.createContext(ctx);vm.runInContext(fs.readFileSync(root+'/ui.js','utf8'),ctx);
 for(let i=0;i<80;i++)await Promise.resolve();
 const hidden=id=>elements.get(id).classList.contains('hidden');
 assert(!hidden('monitor')&&hidden('telegram')&&hidden('alerts'),'Main route owns the monitoring dashboard');
 ctx.location.hash='#alerts-settings';vm.runInContext('applyFocusRoute()',ctx);
 assert(hidden('monitor')&&!hidden('telegram')&&!hidden('alerts'),'Alerts shows credentials and phone settings, never the monitor');
 assert.equal(elements.get('chatId').value,'111');assert.equal(elements.get('chatId2').value,'222');
 elements.get('chatId').value='999';await events['chatId:input'][0]();
 ctx.location.hash='#monitor';vm.runInContext('applyFocusRoute()',ctx);
 assert(!hidden('monitor')&&hidden('telegram')&&hidden('alerts'),'Back restores the main monitoring dashboard');
 ctx.location.hash='#alerts-settings';vm.runInContext('applyFocusRoute()',ctx);
 assert.equal(elements.get('chatId').value,'999','Route changes must preserve an unsaved field in the same applicant');
 assert.equal(state.chatId,'111','Navigation must never save credentials implicitly');
 assert.equal(state.assistedBookingPreference,true,'Navigation must preserve the booking preference');
 state.detectedRto='SRINAGAR';await vm.runInContext('refresh()',ctx);
 assert(elements.get('focusCov').textContent.includes('SRINAGAR'));
 state.detectedRto='';await vm.runInContext('refresh()',ctx);assert(elements.get('focusCov').textContent.includes('RTO not detected'));
 console.log('PASS: shipped UI DOM, clean main/Alerts routes, two recipients and unsaved form retention');
})().catch(e=>{console.error(e);process.exitCode=1});
