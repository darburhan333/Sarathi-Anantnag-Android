const fs=require('fs'),vm=require('vm'),assert=require('assert'),path=require('path');
const source=fs.readFileSync(path.join(__dirname,'../app/src/main/assets/extension/content.js'),'utf8');
const start=source.indexOf('  function getCovForm()'),end=source.indexOf('\n  async function handleNoSlotsPopup',start);
function checkbox(rowText,disabled=false,ownLabel=''){
 const row={textContent:rowText,querySelectorAll:()=>[box]};
 const cell={textContent:'',querySelectorAll:()=>[box]};
 const box={labels:ownLabel?[{textContent:ownLabel}]:[],disabled,value:'not-3-or-4',getAttribute:()=>'',nextSibling:null,closest:tag=>tag==='td'?cell:tag==='tr'?row:null};return box;
}
for(const label of ['Motor cycle without Gear (Non Transport) MCWOG','Transport Vehicle-M/HMV (Goods and Passenger) TRANS','LMV','MCWG']){
 const wanted=checkbox(label),unrelated=checkbox('TRACTOR'),disabled=checkbox(label,true);
 const form={querySelectorAll:()=>[wanted,unrelated,disabled]},proceed={form};
 const ctx={document:{getElementById:id=>id==='dispdlapcntdetform'?form:proceed},visible:()=>true,norm:s=>String(s||'').replace(/\s+/g,' ').trim()};vm.createContext(ctx);vm.runInContext(source.slice(start,end),ctx);
 const result=ctx.getCovForm();assert(result);assert.deepEqual([...result.selected],[wanted],label+' selects only own enabled row');
}
console.log('PASS: separate checkbox/description/COV cells: MCWOG, TRANS, LMV, MCWG; unrelated and disabled excluded');
