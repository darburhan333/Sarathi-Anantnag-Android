const fs=require('fs'),vm=require('vm'),assert=require('assert');
const source=fs.readFileSync(__dirname+'/../app/src/main/assets/extension/applicant-fill.js','utf8');
async function fixture(options={}){
 const events=[],native=[],listeners=[];let consumed=false,number='',birth='',captcha='manual-captcha',submits=0;
 class Input{constructor(id,type='text'){this.id=id;this.type=type;this.isConnected=true;this.disabled=false;this.readOnly=false;this.checked=false;this.form=form;}get value(){return this.id==='applno'?number:birth;}set value(v){if(this.id==='applno')number=v;else birth=v;}getBoundingClientRect(){return {width:100,height:30};}click(){this.checked=true;}dispatchEvent(e){events.push(this.id+':'+e.type);}}
 const form={action:'https://sarathi.parivahan.gov.in/slots/dldetsubmit.do',submit(){submits++;}};
 const fields={applno:new Input('applno'),dob:new Input('dob'),dlslotipform_subtype1:new Input('radio','radio'),captcha:{value:captcha}};
 if(options.readonly)fields.dob.readOnly=true;
 if(options.disabled)fields.applno.disabled=true;
 if(options.conflict)number='999999';
 if(options.formMismatch)fields.dob.form={};
 if(options.actionMismatch)form.action='https://example.test/slots/dldetsubmit.do';
 const location=new URL(options.url||'https://sarathi.parivahan.gov.in/slots/dlslotbook.do');
 const ctx={profileAllowed:Promise.resolve(options.owner!==false),location,URL,Date,HTMLInputElement:Input,Event:class{constructor(type){this.type=type;}},document:{getElementById:id=>fields[id]},browser:{runtime:{getURL:p=>'moz-extension://fixture/'+p,onMessage:{addListener:f=>listeners.push(f)},sendNativeMessage:async(app,m)=>{native.push(m.type);const ok=options.nativeDenied!==true&&m.token==='nonce'&&!consumed;if(ok&&m.type==='FILL_COMMIT')consumed=true;return {ok};}}}};
 vm.runInNewContext(source,ctx);for(let n=0;n<10;n++)await Promise.resolve();
 async function call(type='SARATHI_FILL_DETAILS',extra={}){if(!listeners.length)return {ok:false};return await new Promise(resolve=>{const keep=listeners[0]({type,token:'nonce',applicationNumber:'123456789012',dob:'15-08-1998',...extra},{url:'moz-extension://fixture/background.html'},resolve);if(keep!==true)resolve({ok:false});});}
 return {call,fields,native,events,values:()=>({number,birth,captcha:fields.captcha.value,submits})};
}
(async()=>{
 let f=await fixture();assert((await f.call('SARATHI_FILL_CHECK')).ok);assert((await f.call()).ok);assert.deepEqual(f.values(),{number:'123456789012',birth:'15-08-1998',captcha:'manual-captcha',submits:0});assert.deepEqual(f.events,['applno:input','applno:change','dob:input','dob:change']);assert(!(await f.call()).ok,'Consumed fill tokens cannot be replayed');
 for(const opts of [{readonly:true},{disabled:true},{conflict:true},{owner:false},{nativeDenied:true},{formMismatch:true},{actionMismatch:true},{url:'https://example.test/slots/dlslotbook.do'},{url:'https://sarathi.parivahan.gov.in/slots/dldetsubmit.do'}]){
  f=await fixture(opts);assert(!(await f.call()).ok,JSON.stringify(opts));assert.equal(f.values().birth,'');assert.equal(f.values().submits,0);assert.equal(f.values().captcha,'manual-captcha');
 }
 f=await fixture();assert(!(await f.call('SARATHI_FILL_DETAILS',{dob:'31-02-2000'})).ok);assert(!f.native.includes('FILL_COMMIT'),'Invalid DOB must not consume permission');
 console.log('PASS: selected-owned applicant fill, exact Sarathi form, valid DOB, paste-independent entry, no CAPTCHA/Submit, no conflict overwrite, one-use token and cross-profile/read-only guards');
})().catch(e=>{console.error(e);process.exit(1)});
