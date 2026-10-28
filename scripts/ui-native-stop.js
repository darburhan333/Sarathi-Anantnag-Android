const fs=require('fs'),vm=require('vm'),assert=require('assert'),path=require('path');
(async()=>{
 const state={androidForeground:true,running:false},changes=[];let releaseInjection;
 const els=new Map();const el=id=>{if(!els.has(id))els.set(id,{value:'',checked:false,dataset:{},classList:{toggle(){}},addEventListener(){},replaceChildren(){}});return els.get(id);};
 const chrome={
  storage:{local:{get:async k=>typeof k==='string'?{[k]:state[k]}:{...k,...state},set:async p=>{Object.assign(state,p);changes.forEach(f=>f(Object.fromEntries(Object.entries(p).map(([k,v])=>[k,{newValue:v}])),'local'));}},onChanged:{addListener:f=>changes.push(f)}},
  tabs:{query:async()=>[{id:7,active:true}],sendMessage:async()=>({ok:true,ready:true,covReady:true})},
  scripting:{executeScript:()=>new Promise(r=>releaseInjection=r)},
  runtime:{onMessage:{addListener(){}},sendMessage:async()=>({ok:true})}
 };
 const ctx={chrome,document:{getElementById:el,querySelectorAll:()=>[],addEventListener(){},createElement:()=>({})},window:{addEventListener(){},confirm:()=>true},navigator:{onLine:true},Date,Math,crypto:require('crypto').webcrypto,setInterval(){},setTimeout,console,Blob,URL};
 vm.createContext(ctx);vm.runInContext(fs.readFileSync(path.join(__dirname,'../app/src/main/assets/extension/ui.js'),'utf8'),ctx);
 const starting=ctx.run(true);
 for(let i=0;i<100&&!releaseInjection;i++)await Promise.resolve();
 assert(releaseInjection,'START must be awaiting injection');
 await chrome.storage.local.set({androidStopEpoch:'native-stop-1',running:false});
 releaseInjection([]);await starting;
 assert.equal(state.running,false,'Native STOP must cancel pending UI START');
 assert(!state.monitorSessionId,'Cancelled START must not create a new session');
 console.log('PASS: native STOP cancels pending monitor START');
})().catch(e=>{console.error(e);process.exitCode=1});

