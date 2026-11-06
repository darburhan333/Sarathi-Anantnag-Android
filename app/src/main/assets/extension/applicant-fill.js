// Explicit native user action only. Never submits a form or reads/writes CAPTCHA or OTP.
(async()=>{
 if(!await globalThis.profileAllowed)return;
 function fields(){
  if(location.protocol!=='https:'||location.hostname!=='sarathi.parivahan.gov.in'||location.pathname!=='/slots/dlslotbook.do')return null;
  const application=document.getElementById('applno'),dob=document.getElementById('dob'),radio=document.getElementById('dlslotipform_subtype1');
  if(!application||!dob||!radio||!application.form||dob.form!==application.form||radio.form!==application.form)return null;
  const action=new URL(application.form.action,location.href);
  if(action.origin!==location.origin||action.pathname!=='/slots/dldetsubmit.do')return null;
  if(application.type!=='text'||dob.type!=='text'||radio.type!=='radio')return null;
  if([application,dob,radio].some(el=>!el.isConnected||el.disabled||el.readOnly))return null;
  return {application,dob,radio};
 }
 async function permitted(token,consume=false){
  try{let r=await browser.runtime.sendNativeMessage('profile',{type:consume?'FILL_COMMIT':'FILL_CHECK',token});if(typeof r==='string')r=JSON.parse(r);return r?.ok===true;}catch(_){return false;}
 }
 function valid(number,dob){
  if(!/^[0-9]{1,25}$/.test(number)||!/^\d{2}-\d{2}-\d{4}$/.test(dob))return false;
  const [day,month,year]=dob.split('-').map(Number),d=new Date(year,month-1,day);
  return year>=1900&&d.getFullYear()===year&&d.getMonth()===month-1&&d.getDate()===day&&d<=new Date();
 }
 function writable(f,number,dob){return (!f.application.value||f.application.value===number)&&(!f.dob.value||f.dob.value===dob);}
 let busy=false;
 browser.runtime.onMessage.addListener((m,sender,reply)=>{
  if(!sender.url?.startsWith(browser.runtime.getURL(''))||!['SARATHI_FILL_CHECK','SARATHI_FILL_DETAILS'].includes(m?.type))return;
  (async()=>{
   if(!fields()||!await permitted(m.token))return {ok:false,error:'Open the selected applicant details page first.'};
   if(m.type==='SARATHI_FILL_CHECK')return {ok:true};
   if(busy)return {ok:false,error:'A fill is already in progress.'};
   busy=true;
   try{
    const number=String(m.applicationNumber||''),birth=String(m.dob||'');let f=fields();
    if(!valid(number,birth)||!f)return {ok:false,error:'Saved details or page fields are invalid.'};
    if(!writable(f,number,birth))return {ok:false,error:'Existing details differ. Clear them before filling.'};
    if(!await permitted(m.token,true))return {ok:false,error:'Applicant changed or fill request expired.'};
    f=fields();if(!f||!writable(f,number,birth))return {ok:false,error:'The page changed. Review it and try again.'};
    if(!f.radio.checked)f.radio.click();
    f=fields();
    if(!f||!f.radio.checked||!writable(f,number,birth))return {ok:false,error:'Application Number option is not available.'};
    for(const [el,value]of [[f.application,number],[f.dob,birth]]){
     const rect=el.getBoundingClientRect();if(rect.width<=0||rect.height<=0)return {ok:false,error:'Applicant fields are not visible.'};
    }
    const setter=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set;
    for(const [el,value]of [[f.application,number],[f.dob,birth]]){setter.call(el,value);el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}));}
    const current=fields();
    return current&&current.application.value===number&&current.dob.value===birth?{ok:true}:{ok:false,error:'The website did not retain the entered details. Please enter them manually.'};
   }finally{busy=false;}
  })().then(reply).catch(()=>reply({ok:false,error:'Could not fill this page. Please enter details manually.'}));return true;
 });
})();
