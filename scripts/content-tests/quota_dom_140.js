// Minimal DOM fixture tests exercising the production quota parser, not its pure-rule substitute.
const fs=require('fs'),vm=require('vm'),assert=require('assert');
class Element{
 constructor(tag,text='',children=[],attrs={}){this.tagName=tag.toUpperCase();this.text=text;this.children=children;this.attrs=attrs;this.disabled=false;this.name=attrs.name||'';for(const child of children)child.parent=this;}
 get textContent(){return this.text+this.children.map(c=>c.textContent).join(' ')}get innerText(){return this.textContent}
 getAttribute(n){return this.attrs[n]??null}
 matches(selector){return selector==='input[type="radio"]'?this.tagName==='INPUT'&&this.attrs.type==='radio':this.tagName===selector.toUpperCase()}
 querySelectorAll(selector){return this.children.flatMap(c=>[...(c.matches(selector)?[c]:[]),...c.querySelectorAll(selector)])}
 querySelector(selector){return this.querySelectorAll(selector)[0]||null}
 closest(selector){return this.matches(selector)?this:this.parent?.closest(selector)||null}
}
const E=(tag,text='',children=[],attrs={})=>new Element(tag,text,children,attrs);
const src=fs.readFileSync(__dirname+'/../content.js','utf8'),ctx={norm:x=>String(x||'').trim().replace(/\s+/g,' '),visible:()=>true};
vm.createContext(ctx);vm.runInContext(fs.readFileSync(__dirname+'/../booking-rules.js','utf8'),ctx);
vm.runInContext(src.slice(src.indexOf('  function quotaTableHeaders('),src.indexOf('  function quotaDisplayedDate(')),ctx);
function parse(separate,count,labels=['LMV, MCWG'],options={}){
 const headings=['Time Slots',...labels,...(separate?['Available Quota']:[])];
 const cells=[E('td','09.00-18.00'),...labels.map(name=>{const radio=E('input','',[],{type:'radio',name});radio.disabled=!!options.disabled;return E('td',options.plain&&!separate?count:'',[radio,...(separate||options.plain?[]:[E('span',count)])])}),...(separate?[E('td',count)]:[])];
 const table=E('table','',[E('tr','',headings.map(x=>E('th',x))),E('tr','',cells)]);const fieldset=E('fieldset','',[E('legend','Available Quota'),table]);
 ctx.document={querySelectorAll:selector=>fieldset.querySelectorAll(selector)};return ctx.quotaOptions();
}
for(const separate of [false,true])for(const n of ['0','1','13','1000']){const q=parse(separate,n);assert.equal(q.total,1);assert.equal(q.entries.length,1);assert.equal(q.entries[0].count,Number(n));assert.equal(q.entries[0].kind,'BOTH')}
assert.equal(parse(true,'13',['LMV','MCWG']).entries.length,0,'one shared number cannot identify two radio quotas');
assert.equal(parse(false,'9007199254740992').entries.length,0);
assert.equal(parse(false,'13 seats').entries.length,0);
console.log('PASS: real quota parser accepts both layouts and 1000; rejects shared/unsafe/unverified counts');

module.exports={ctx,parse,E};
