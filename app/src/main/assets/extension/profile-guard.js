// Read-only native delegate proves the actual GeckoSession belongs to this extension.
globalThis.profileAllowed=(async()=>{for(let i=0;i<3;i++){try{const raw=await browser.runtime.sendNativeMessage('profile',{type:'PROFILE'});const r=typeof raw==='string'?JSON.parse(raw):raw;if(r?.ok)return true;}catch(_){}await new Promise(r=>setTimeout(r,250));}return false;})();

