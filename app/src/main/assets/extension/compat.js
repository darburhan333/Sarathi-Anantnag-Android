// Firefox promise APIs; only the Chrome-specific injection entry point is adapted.
// Loaded in each isolated extension context, never injected into the site's JS world.
var chrome = Object.assign({}, browser, {
  scripting: {
    async executeScript({target, files}) {
      const results=[];
      for(const file of files) {
        results.push(...await browser.tabs.executeScript(target.tabId,{file,runAt:'document_idle'}));
      }
      return results;
    }
  }
});

async function nativeRequest(message){
 if(globalThis.PROFILE_MODE && location.pathname.endsWith("/monitor.html") && ["SECURE_GET","SECURE_SET","SECURE_REMOVE","SECURE_IMPORT"].includes(message.type))return browser.runtime.sendMessage({type:"SECURE_PROXY",request:message});
 const response=await browser.runtime.sendNativeMessage('sarathi',message);
 return typeof response==='string'?JSON.parse(response):response;
}


// Only trusted extension pages use the vault. Website scripts never receive this adapter.
if(globalThis.PROFILE_MODE && location.protocol==='moz-extension:'){
 const local=browser.storage.local,secretKeys=['botToken','chatId','chatId2'];let migration;
 async function vault(){
  // Pre-v3.3 shared credentials are retained encrypted for explicit import only.
  if(!migration)migration=local.remove(secretKeys).catch(e=>{migration=null;throw e;});
  await migration;const r=await nativeRequest({type:'SECURE_GET'});if(!r?.ok)throw Error(r?.error||'Secure settings unavailable');return r.values||{};
 }
 chrome.storage=Object.assign({},browser.storage,{local:{
  async get(keys){const d=await local.get(keys);const requested=keys==null?secretKeys:typeof keys==='string'?[keys]:Array.isArray(keys)?keys:Object.keys(keys);if(requested.some(k=>secretKeys.includes(k))){const v=await vault();for(const k of secretKeys)if(requested.includes(k))d[k]=v[k]||'';}return d;},
  async set(values){const plain={...values},secret={};for(const k of secretKeys)if(k in plain){secret[k]=plain[k];delete plain[k];}if(Object.keys(secret).length){await vault();const r=await nativeRequest({type:'SECURE_SET',values:secret});if(!r?.ok)throw Error(r?.error||'Secure save failed');plain.telegramConfigRevision=Date.now()+'-'+Math.random();}if(Object.keys(plain).length)await local.set(plain);},
  remove:keys=>local.remove(keys),clear:()=>local.clear()
 }});
}

