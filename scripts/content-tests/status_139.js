const fs=require('fs'),vm=require('vm'),assert=require('assert');
const code=fs.readFileSync(__dirname+'/../content.js','utf8');
const start=code.indexOf('  async function inspectBookingPage()');
const end=code.indexOf('  // Assisted booking:',start);
async function test(stage,locked,green){
 let state={bookingStage:stage,calendarLock:locked,lastStatus:'Keep detailed status'};const alerts=[];
 const ctx={sessionId:'status-test',ownsTab:true,inspectingCalendar:false,bookingPaused:false,calendarLocked:locked,lastAlertKey:'',isRunning:async()=>true,noSlotsTextPresent:()=>false,realCalendar:()=>true,pageText:()=>'',getCovForm:()=>null,visible:()=>true,norm:x=>String(x).trim(),calendarMonth:()=>({key:'2026-12'}),location:{pathname:'/calendar',href:'https://sarathi.parivahan.gov.in/calendar'},document:{querySelectorAll:()=>green?[{textContent:'2'}]:[]},chrome:{storage:{local:{get:async defaults=>({...defaults,...state}),set:async v=>Object.assign(state,v)}},runtime:{sendMessage:async m=>alerts.push(m)}},Date};
 vm.createContext(ctx);vm.runInContext(code.slice(start,end),ctx);await ctx.inspectBookingPage();
 assert.equal(state.bookingStage,locked?stage:'CALENDAR DETECTED');
 if(locked)assert.equal(state.lastStatus,'Keep detailed status');
 assert.equal(alerts[0].kind,green?'GREEN_DATE':'CALENDAR');
}
(async()=>{await test('MONITORING',false,false);await test('MANUAL REVIEW REQUIRED',true,true);await test('GREEN WED/THU SELECTED',true,true);console.log('PASS: calendar status preserves later stages; green alert requires green date');})().catch(e=>{console.error(e);process.exit(1)});

