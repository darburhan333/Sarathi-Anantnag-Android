// Read only explicitly labelled RTO information, never application/name/DOB fields.
globalThis.readSarathiRtoLabel=function(doc){
 const clean=value=>String(value||'').replace(/\s+/g,' ').trim();
 const placeholder=/^(?:[-–—\s]*|(?:please\s+)?select(?:\s+(?:an?\s+)?rto)?|rto(?:\s+(?:name|office))?)$/i;
 const valid=value=>{const v=clean(value);return v.length>=2&&v.length<=100&&!placeholder.test(v)&&!/^\d+$/.test(v)?v:'';};
 const valueOf=el=>{
  if(!el)return '';
  if(el.tagName==='SELECT'){const option=el.selectedOptions?.[0];return option&&!option.disabled&&String(el.value||'').trim()?valid(option.textContent):'';}
  return valid(el.value||el.textContent);
 };
 const candidates=new Set();
 for(const el of doc.querySelectorAll('select,input,span,div,td,dd')){
  const key=String(el.id||el.name||'');
  if(/^(?:(?:selected|application|applicant|booking|licen[cs]ing)?[_-]?)?rto(?:[_-]?(?:name|office|code))?$/i.test(key)){
   // Hidden numeric RTO codes are not a user-readable office name.
   if(el.type==='hidden')continue;
   const value=valueOf(el);if(value)candidates.add(value);
  }
 }
 for(const label of doc.querySelectorAll('label,th,dt,td,span')){
  const inline=clean(label.textContent).match(/^(?:rto(?:\s*(?:name|office))?|licen[cs]ing\s+authority)\s*[:：]\s*(.{2,100})$/i);
  if(inline){const value=valid(inline[1]);if(value)candidates.add(value);continue;}
  if(!/^(?:rto(?:\s*(?:name|office))?|licen[cs]ing\s+authority)\s*[:：]?$/i.test(clean(label.textContent)))continue;
  const linked=label.htmlFor?doc.getElementById(label.htmlFor):null;
  const next=linked||label.nextElementSibling;
  const value=valueOf(next);if(value)candidates.add(value);
 }
 return candidates.size===1?[...candidates][0]:'';
};
