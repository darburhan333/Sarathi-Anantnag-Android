const fs=require('fs'),vm=require('vm'),assert=require('assert');
(async()=>{
 const els=new Map(),events={};
 function el(id){if(!els.has(id))els.set(id,{value:'',checked:false,textContent:'',className:'',disabled:false,dataset:{},classList:{toggle(){}},addEventListener(type,f){(events[id+':'+type]??=[]).push(f)},replaceChildren(){}});return els.get(id)}
 const document={getElementById:el,querySelectorAll:()=>[],addEventListener(){},activeElement:null,createElement:()=>({}),hidden:false};
 const state={assistedBookingPreference:true,autoBookingEnabled:false,running:false,bookingClickIssued:false,bookingStage:'STOPPED',botToken:'123456:'+ 'a'.repeat(25),chatId:'111',chatId2:'222',telegramEnabled:true,desktopEnabled:true,soundEnabled:true,volume:85};const changes=[];
 const chrome={runtime:{onMessage:{addListener(){}}},storage:{local:{get:async defaults=>({...defaults,...state}),set:async values=>{Object.assign(state,values);for(const f of changes)f(Object.fromEntries(Object.entries(values).map(([k,v])=>[k,{newValue:v}])),'local')}},onChanged:{addListener:f=>changes.push(f)}}};
 const ctx={chrome,document,window:{addEventListener(){}},navigator:{onLine:true},Date,setInterval(){},setTimeout(){},console,Blob,URL};vm.createContext(ctx);vm.runInContext(fs.readFileSync(__dirname+'/../ui.js','utf8'),ctx);
 for(let i=0;i<50;i++)await Promise.resolve();
 assert.equal(el('autoBookingEnabled').checked,true,'checkbox reflects saved preference, not runtime gate');
 await chrome.storage.local.set({autoBookingEnabled:false,bookingStage:'STOPPED'});assert.equal(el('autoBookingEnabled').checked,true,'runtime stop does not erase saved preference');
 el('autoBookingEnabled').checked=false;await events['autoBookingEnabled:change'][0]();assert.equal(state.assistedBookingPreference,false);assert.equal(state.autoBookingEnabled,false);
 el('autoBookingEnabled').checked=true;await events['autoBookingEnabled:change'][0]();assert.equal(state.assistedBookingPreference,true);assert.equal(state.autoBookingEnabled,false,'preference alone must not arm automation while stopped');
 state.running=true;await chrome.storage.local.set({running:true,bookingStage:'MONITORING'});el('autoBookingEnabled').checked=true;await events['autoBookingEnabled:change'][0]();assert.equal(state.autoBookingEnabled,true,'explicit user toggle can enable the active monitoring session');
 state.running=false;await chrome.storage.local.set({running:false,autoBookingEnabled:false,bookingStage:'STOPPED'});assert.equal(state.assistedBookingPreference,true);assert.equal(el('autoBookingEnabled').checked,true);
 await chrome.storage.local.set({chatId2:'333',botToken:'123456:'+ 'b'.repeat(25),volume:60,soundEnabled:false,successWatch:true,running:false});
 assert.equal(el('chatId2').value,'333');assert.equal(el('soundEnabled').checked,false);assert.equal(el('volume').value,60);assert.equal(el('stop').disabled,false);assert.equal(el('badge').textContent,'WATCHING');
 await chrome.storage.local.set({bookingStage:'AWAITING MANUAL SMS VERIFICATION'});assert.equal(el('badge').textContent,'MANUAL OTP');
 await chrome.storage.local.set({telegramStatusEnabled:true,telegramStatusError:'Receiver conflict'});assert.equal(el('telegramStatusEnabled').checked,true);assert.equal(el('telegramStatusInfo').textContent,'Receiver conflict');
 el('chatId').value='444';await events['chatId:input'][0]();
 // A stale non-edited field cannot overwrite settings saved by another window.
 el('chatId2').value='222';await events['saveTelegram:click'][0]();assert.equal(state.chatId,'444');assert.equal(state.chatId2,'333');assert.equal(state.botToken,'123456:'+ 'b'.repeat(25));
 console.log('PASS: live UI settings sync, dirty-only saves, receipt watcher STOP control');
})().catch(e=>{console.error(e);process.exitCode=1});

