// Shared pure selection rules: NO DOM, no network, no Chrome APIs.
(function(root){
'use strict';
function classKind(label){const v=String(label||'');const l=/\bLMV\b/i.test(v),m=/\bMCWG\b/i.test(v);return l&&m?'BOTH':l?'LMV':m?'MCWG':null;}
// A bare table number could be a serial number: require an availability heading.
function quotaHeader(label){return /\b(?:available\s*(?:quota|slots?|seats?|count)|quota\s*available|vacant\s*(?:slots?|seats?|count))\b/i.test(String(label||''));}
function quota(value){if(typeof value==='number')return Number.isSafeInteger(value)&&value>=0?value:null;if(typeof value==='string'&&/^\d+$/.test(value.trim())){const n=Number(value.trim());return Number.isSafeInteger(n)?n:null;}return null;}
function validDate(value){
 if(!/^\d{4}-\d{2}-\d{2}$/.test(value))return false;
 const [y,m,d]=value.split('-').map(Number);
 const parsed=new Date(y,m-1,d);
 return parsed.getFullYear()===y && parsed.getMonth()===m-1 && parsed.getDate()===d;
}
// Parse only a visible CALENDAR heading (not whole-page applicant data).
// Allow "October 2026", "Oct, 2026", "2026 - Oct", "Prev October Next 2026"
// and numeric "10 / 2026". Reject ambiguous multiple month/year pairs.
function calendarMonthTitle(title){
 const raw=String(title||'').replace(/\s+/g,' ').trim().slice(0,260);
 if(!raw)return null;
 const names=['january','february','march','april','may','june','july','august','september','october','november','december'];
 const tokens=[];
 const word=/\b(January|February|March|April|May|June|July|August|September|October|November|December|Jan|Feb|Mar|Apr|Jun|Jul|Aug|Sep|Sept|Oct|Nov|Dec)\b/ig;
 for(const match of raw.matchAll(word)){
   const month=names.findIndex(n=>n.startsWith(match[1].toLowerCase().slice(0,3)));
   if(month>=0)tokens.push({month,index:match.index});
 }
 const years=[...raw.matchAll(/\b(20\d{2})\b/g)].map(m=>({year:Number(m[1]),index:m.index}));
 const hits=[];
 for(const m of tokens)for(const y of years){
   if(Math.abs(m.index-y.index)<=60)hits.push({month:m.month,year:y.year});
 }
 if(!hits.length){
   // A full date is not proof of the calendar's displayed month.
   if(/\b(?:\d{1,2}[-/.]\d{1,2}[-/.]20\d{2}|20\d{2}[-/.]\d{1,2}[-/.]\d{1,2})\b/.test(raw))return null;
   for(const m of raw.matchAll(/\b(0?[1-9]|1[0-2])\s*[-/.]\s*(20\d{2})\b/g)){
     hits.push({month:Number(m[1])-1,year:Number(m[2])});
   }
   for(const m of raw.matchAll(/\b(20\d{2})\s*[-/.]\s*(0?[1-9]|1[0-2])\b/g)){
     hits.push({year:Number(m[1]),month:Number(m[2])-1});
   }
 }
 const unique=[...new Map(hits.map(v=>[v.year+'-'+v.month,v])).values()];
 if(unique.length!==1)return null;
 const found=unique[0];
 return {...found,key:found.year+'-'+String(found.month+1).padStart(2,'0')};
}
function select(dates,stopped){
 const log=[];const rank={BOTH:0,LMV:1,MCWG:2};
 const sorted=[...dates].filter(d=>d.green&&validDate(d.date)&&[3,4].includes(new Date(d.date+'T12:00:00').getDay())).sort((a,b)=>a.date.localeCompare(b.date));
 for(const d of sorted){
  if(stopped?.()){log.push('STOP — no simulated booking');return {status:'STOPPED',log};}
  const valid=(d.options||[]).map(o=>({kind:classKind(o.label),count:quota(o.count)})).filter(o=>o.kind&&o.count!==null);
  if(valid.length!==(d.options||[]).length){log.push(d.date+': ambiguous quota or label — stop');return {status:'MANUAL_REVIEW',log};}
  const positive=valid.filter(o=>o.count>=1).sort((a,b)=>rank[a.kind]-rank[b.kind]);
  if(positive.length){const chosen=positive[0];log.push(d.date+': '+chosen.kind+' quota '+chosen.count+' — SIMULATED BOOK SLOT');log.push('SIMULATED SMS VERIFICATION READY — manual OTP in live mode');return {status:'SIMULATED_SMS',date:d.date,...chosen,log};}
  log.push(d.date+': all verified quotas zero — next eligible date');
 }
 log.push('No positive eligible quota in simulated dates');return {status:'NONE',log};
}
root.BookingRules={classKind,validDate,quota,quotaHeader,calendarMonthTitle,select};
})(typeof globalThis!=='undefined'?globalThis:this);

