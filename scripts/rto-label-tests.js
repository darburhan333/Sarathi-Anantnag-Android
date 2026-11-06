const fs=require('fs'),vm=require('vm'),assert=require('assert');
const ctx={};vm.createContext(ctx);vm.runInContext(fs.readFileSync('app/src/main/assets/extension/rto-label.js','utf8'),ctx);
const node=(tag,text,extra={})=>({tagName:tag,textContent:text,...extra});
function read(fields=[],labels=[]){return ctx.readSarathiRtoLabel({querySelectorAll:s=>s.startsWith('select')?fields:labels,getElementById:id=>fields.find(n=>n.id===id)});}
const selected=name=>node('SELECT','',{id:'rtoCode',value:'JK03',selectedOptions:[node('OPTION',name)]});
assert.equal(read([selected('ANANTNAG')]),'ANANTNAG');
assert.equal(read([selected('SRINAGAR')]),'SRINAGAR','Each document must supply its own RTO');
assert.equal(read([selected('Select RTO')]),'');
assert.equal(read([node('INPUT','',{id:'rtoCode',type:'hidden',value:'JK03'})]),'');
assert.equal(read([], [node('TD','RTO :',{nextElementSibling:node('TD','BARAMULLA')})]),'BARAMULLA');
assert.equal(read([], [node('LABEL','RTO Name: KUPWARA')]),'KUPWARA');
assert.equal(read([node('INPUT','',{id:'applicantName',value:'PRIVATE NAME'})]),'');
assert.equal(read([selected('ANANTNAG'),selected('SRINAGAR')]),'','Ambiguous RTOs must not guess');
assert.equal(read(),'', 'Absent RTO must not fall back to a fixed district');
console.log('PASS: RTO selected options, explicit labels, placeholders, hidden codes, applicant privacy and ambiguity');
