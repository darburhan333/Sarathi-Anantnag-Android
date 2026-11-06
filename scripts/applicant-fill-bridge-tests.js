const fs=require('fs'),vm=require('vm'),assert=require('assert');
const source=fs.readFileSync(__dirname+'/../app/src/main/assets/extension/android-bridge.js','utf8');
const start=source.indexOf(' async function command(m){'),end=source.indexOf(' async function connect()',start);
assert(start>=0&&end>start);
assert(source.includes("if(m.type==='START_READY'||m.type==='PAGE_QUERY'||m.type==='FILL_DETAILS'){"),'Fill must bypass the STOP/SILENCE control queue');
async function scenario(eligible){
 const sent=[],results=[];
 const ctx={chrome:{tabs:{query:async()=>[{id:1},{id:2},{id:3}]}},tabRequest:async(id,m)=>{sent.push({id,m});return {ok:m.type==='SARATHI_FILL_CHECK'?eligible.includes(id):true};},port:{postMessage:m=>results.push(m)}};
 vm.createContext(ctx);vm.runInContext(source.slice(start,end),ctx);
 await ctx.command({type:'FILL_DETAILS',id:'one-use-token',applicationNumber:'123456',dob:'15-08-1998'});
 const checks=sent.filter(x=>x.m.type==='SARATHI_FILL_CHECK'),fills=sent.filter(x=>x.m.type==='SARATHI_FILL_DETAILS');
 assert.equal(checks.length,3);for(const x of checks){assert(!('applicationNumber'in x.m));assert(!('dob'in x.m));}
 assert.equal(fills.length,eligible.length===1?1:0);if(fills.length)assert.equal(fills[0].id,eligible[0]);
 assert.equal(results.at(-1).ok,eligible.length===1);
}
(async()=>{await scenario([2]);await scenario([]);await scenario([1,3]);console.log('PASS: fill bypasses Stop queue; only one natively owned eligible tab receives values; absent/ambiguous ownership fails closed');})().catch(e=>{console.error(e);process.exit(1)});
