const fs=require('fs'),vm=require('vm'),assert=require('assert'),path=require('path');
(async()=>{
 const data={botToken:'123456:TEST_TOKEN_NOT_REAL_abcdefghijklmn',chatId:'111',chatId2:'222',telegramEnabled:true,telegramConfigRevision:'first',telegramOutbox:[]};let calls=[],mode='rate';
 const storage={get:async defaults=>Object.assign({},defaults,data),set:async patch=>Object.assign(data,structuredClone(patch))};
 const ctx={chrome:{storage:{local:storage},alarms:{create(){},onAlarm:{addListener(){}}}},console,Date,Math,Promise,setTimeout,clearTimeout,AbortController,navigator:{onLine:true},api:'https://example.invalid/',fetch:async(url,options)=>{const body=JSON.parse(options.body);calls.push(body);if(mode==='rate'&&body.chat_id==='222')return {ok:false,status:429,json:async()=>({ok:false,error_code:429,parameters:{retry_after:30}})};if(mode==='timeout')throw Object.assign(Error('timeout'),{name:'AbortError'});return {ok:true,status:200,json:async()=>({ok:true})};}};
 vm.createContext(ctx);const source=fs.readFileSync(path.join(__dirname,'../app/src/main/assets/extension/background.js'),'utf8');vm.runInContext(source.slice(source.indexOf('// Persistent bounded outbox.'),source.indexOf('\nasync function audioCommand')),ctx);
 await assert.rejects(()=>ctx.sendTelegram('Fixture alert'));
 assert.equal(calls.length,2);assert.deepEqual(Array.from(data.telegramOutbox[0].pending),['222']);
 data.telegramOutbox[0].nextAt=0;mode='ok';await ctx.drainTelegram();assert.equal(calls.length,3);assert.equal(calls[2].chat_id,'222');assert.equal(data.telegramOutbox.length,0);
 mode='timeout';await assert.rejects(()=>ctx.sendTelegram('Ambiguous delivery'));assert.equal(data.telegramOutbox.length,0,'Timeout must not replay potentially accepted message');
 ctx.navigator.onLine=false;await assert.rejects(()=>ctx.sendTelegram('Offline fixture'));assert.equal(data.telegramOutbox.length,1);const before=calls.length;
 data.telegramConfigRevision='changed';ctx.navigator.onLine=true;await ctx.drainTelegram();assert.equal(data.telegramOutbox.length,0);assert.equal(calls.length,before,'Old messages must not go to changed credentials');
 console.log('PASS: Telegram per-recipient accepted deduplication, bounded 429 retry, no ambiguous replay, configuration change cancels queue');
})().catch(e=>{console.error(e);process.exitCode=1});
