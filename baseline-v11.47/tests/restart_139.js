// Synthetic COV-page longevity test. Does not connect to Sarathi or perform bookings.
const fs=require('fs'),vm=require('vm'),path=require('path'),assert=require('assert');
(async()=>{
 let now=new Date(2026,9,20,10,0,0).getTime(),clicks=0;
 class Clock extends Date {constructor(...args){super(...(args.length?args:[now]))}static now(){return now}}
 const callbacks={change:[],message:[]};
 const settings={running:true,monitorSessionId:'synthetic',monitoredTabId:1,attempts:0,attemptDay:'2026-10-20',calendarLock:false,sessionLock:false};
 const box={isConnected:true,disabled:false,checked:false,value:'4',click(){this.checked=true},getBoundingClientRect(){return {width:3,height:3}}};
 const bike={...box,value:'3',checked:false,click(){this.checked=true}};
 const form={querySelectorAll:()=>[box,bike]};
 const proceed={isConnected:true,id:'prcdbook',form,click(){
  clicks++;
  if(clicks===2||clicks===3){
   settings.running=false;
   for(const fn of callbacks.change)fn({running:{oldValue:true,newValue:false}},'local');
  }
  if(clicks>310)throw new Error('Unbounded clicking');
 },getBoundingClientRect(){return {width:3,height:3}}};
 const element={innerText:'LMV and MCWG',getBoundingClientRect(){return {width:3,height:3}}};
 const document={body:element,documentElement:element,getElementById(id){return id==='dispdlapcntdetform'?form:id==='prcdbook'?proceed:null},querySelector:()=>null,querySelectorAll:()=>[]};
 let intervals=0;
 const chrome={storage:{local:{get:async arg=>typeof arg==='string'?{[arg]:settings[arg]}:Array.isArray(arg)?Object.fromEntries(arg.map(k=>[k,settings[k]])):Object.assign({},arg,settings),set:async vals=>{Object.assign(settings,vals)}},onChanged:{addListener:f=>callbacks.change.push(f)}},runtime:{sendMessage:async msg=>msg.type==='SARATHI_OWNERSHIP'?{authorized:true,sessionId:'synthetic'}:msg.type==='SARATHI_ACTION_GATE'?{authorized:settings.running}:null,onMessage:{addListener:f=>callbacks.message.push(f)}}};
 const context={window:{},document,chrome,location:{pathname:'/slots/proceeddlapmnt.do'},navigator:{onLine:true},Date:Clock,console:{info(){},warn(){},error(){}},getComputedStyle:()=>({display:'block',visibility:'visible'}),setInterval:()=>++intervals,setTimeout:(f,ms)=>{now+=Math.max(0,ms);Promise.resolve().then(f);return 1},MutationObserver:class{observe(){}},Promise};
 vm.runInNewContext(fs.readFileSync(path.join(__dirname,'..','booking-rules.js'),'utf8'),context);
 vm.runInNewContext(fs.readFileSync(path.join(__dirname,'..','content.js'),'utf8'),context);
 // Drain microtasks long enough to complete 301 loops, with a finite limit.
 for(let i=0;i<100000&&clicks<2;i++)await Promise.resolve();
 assert.equal(clicks,2);
 for(let i=0;i<100;i++)await Promise.resolve();
 settings.running=true;
 for(const fn of callbacks.change)fn({running:{oldValue:false,newValue:true}},'local');
 for(let i=0;i<100000&&clicks<3;i++)await Promise.resolve();
 assert.equal(clicks,3,'STOP -> START must launch a fresh loop without waiting for health recovery');
 assert.equal(settings.running,false);
 console.log('PASS: real content script STOP -> START resumes without recovery alarm');

})().catch(e=>{console.error(e);process.exit(1)});
