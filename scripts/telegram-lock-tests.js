// Exercise the real credential adapter: rejected native edits cannot reach storage.
const fs=require('fs'),vm=require('vm'),assert=require('assert'),path=require('path');
(async()=>{
 let unlocked=false;const data={telegramEnabled:true,soundEnabled:true},requests=[];
 const local={get:async defaults=>({...defaults,...data}),set:async values=>Object.assign(data,values),remove:async()=>{},clear:async()=>{}};
 const browser={storage:{local},runtime:{sendNativeMessage:async(app,m)=>{
  requests.push(m.type);
  if(m.type==='SECURE_GET')return {ok:true,values:{botToken:'fixture-token',chatId:'123'}};
  if(['SECURE_SET','TELEGRAM_OPTIONS_SET'].includes(m.type))return {ok:unlocked,error:'locked'};
  return {ok:true};
 }}};
 const ctx={browser,PROFILE_MODE:true,location:{protocol:'moz-extension:',pathname:'/background.html'},Date,Math};
 vm.createContext(ctx);vm.runInContext(fs.readFileSync(path.join(__dirname,'../app/src/main/assets/extension/compat.js'),'utf8'),ctx);
 assert.equal((await ctx.chrome.storage.local.get({botToken:''})).botToken,'fixture-token','Alert delivery must retain read access while locked');
 await assert.rejects(()=>ctx.chrome.storage.local.set({telegramEnabled:false}),/locked/);
 assert.equal(data.telegramEnabled,true,'Rejected disable must leave alerts enabled');
 await assert.rejects(()=>ctx.chrome.storage.local.set({chatId:'999'}),/locked/);
 assert(!('chatId'in data),'Rejected credential edits must never reach plaintext storage');
 await ctx.chrome.storage.local.set({soundEnabled:false});assert.equal(data.soundEnabled,false,'Phone alerts remain outside the Telegram edit lock');
 unlocked=true;await ctx.chrome.storage.local.set({telegramEnabled:false});assert.equal(data.telegramEnabled,false);
 assert(requests.includes('TELEGRAM_OPTIONS_SET')&&requests.includes('SECURE_SET'));
 console.log('PASS: locked edits rejected before mutation; encrypted reads and phone options remain available');
})().catch(e=>{console.error(e);process.exitCode=1});
