const fs=require('fs'),vm=require('vm'),assert=require('assert'),path=require('path');
(async()=>{
 const vaults=new Map(),contexts=[];
 for(let profile=1;profile<=9;profile++){
  const data={},events=[];
  const local={get:async keys=>keys==null?{...data}:typeof keys==='string'?{[keys]:data[keys]}:Array.isArray(keys)?Object.fromEntries(keys.map(k=>[k,data[k]])):Object.assign({},keys,data),set:async values=>{Object.assign(data,values);events.push(Object.keys(values));},remove:async keys=>{for(const k of Array.isArray(keys)?keys:[keys])delete data[k];},clear:async()=>{for(const k of Object.keys(data))delete data[k];}};
  const browser={storage:{local,onChanged:{addListener(){}}},runtime:{sendNativeMessage:async(app,m)=>{if(m.type==='SECURE_GET')return {ok:true,values:vaults.get(profile)||{}};if(m.type==='SECURE_SET'){vaults.set(profile,{...vaults.get(profile),...m.values});return {ok:true};}if(m.type==='SECURE_REMOVE'){vaults.delete(profile);return {ok:true};}}}};
  const ctx={browser,PROFILE_MODE:true,location:{protocol:'moz-extension:',pathname:'/background.html'},Date,Math,console};vm.createContext(ctx);vm.runInContext(fs.readFileSync(path.join(__dirname,'../app/src/main/assets/extension/compat.js'),'utf8'),ctx);contexts.push({ctx,data,events});
  await ctx.chrome.storage.local.set({botToken:'fake-token-'+profile,chatId:String(profile)});
 }
 for(let i=0;i<9;i++){const c=contexts[i],d=await c.ctx.chrome.storage.local.get({botToken:'',chatId:''});assert.equal(d.botToken,'fake-token-'+(i+1));assert(!('botToken'in c.data));assert(c.events.some(keys=>keys.includes('telegramConfigRevision')));}
 await contexts[3].ctx.nativeRequest({type:'SECURE_REMOVE'});
 assert.equal((await contexts[3].ctx.chrome.storage.local.get({botToken:''})).botToken,'');
 assert.equal((await contexts[4].ctx.chrome.storage.local.get({botToken:''})).botToken,'fake-token-5');
 console.log('PASS: nine independent credential adapters, no plaintext local tokens, configuration revision events, remove affects one profile');
})().catch(e=>{console.error(e);process.exitCode=1});
