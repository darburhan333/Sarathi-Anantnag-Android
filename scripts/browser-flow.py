"""Offline Chromium DOM integration: Calendar -> quota 0 -> quota 13 -> BOOK SLOT -> manual SMS.
Never connects to Sarathi. This harness supplies the same DOM structure as the captured
Sarathi diagnostic and simulates navigation in place (real HTTP navigation is blocked).
"""
import asyncio, pathlib, os, json
from playwright.async_api import async_playwright
R=pathlib.Path(__file__).resolve().parents[1]/'app/src/main/assets/extension'
source=(R/'content.js').read_text().replace('location.pathname','(window.__testPath || location.pathname)')
rules=(R/'booking-rules.js').read_text()

styles='<style>body{font-family:Arial}#calview,fieldset,td,th{border:1px solid #ccc;padding:8px}td.cal_green{color:green}button,a,input{cursor:pointer}</style>'
calendar='''<h1>DL TEST APPOINTMENTS</h1><fieldset><legend>Calendar</legend><div id="calview"><table><tbody><tr><th colspan="2">Prev December 2026 Next</th></tr><tr><td class="cal_green"><a href="#" onclick="window.__testGoto('quota','2026-12-09');return false">9</a></td><td class="cal_green"><a href="#" onclick="window.__testGoto('quota','2026-12-10');return false">10</a></td></tr></tbody></table></div></fieldset>'''
quota='''<h1>DL TEST APPOINTMENTS</h1><fieldset><legend>Available Quota</legend><p>Available Time Slots {date}</p><table><tbody><tr><th>Time Slots</th><th>LMV, MCWG<input id="cov1" type="hidden" /></th></tr><tr><td><span>09.00-18.00</span></td><td><input type="radio" id="LMV, MCWG0" name="LMV, MCWG" {disabled} onchange="document.getElementById('slotbtn').disabled=false"/><span>{count}</span></td></tr></tbody></table><button id="slotbtn" disabled onclick="window.__testGoto('otp')">BOOK SLOT</button><button id="onlinedltestslotform_back" onclick="window.__testGoto('calendar')">Back</button></fieldset>'''
otp='''<h1>SMS Verification</h1><label>SMS verification Code <input id="smsCode" type="text" autocomplete="one-time-code"></label>'''

mock=r'''(() => {
window.__testPath='/slots/bckdlappldet.do';
window.__data={running:true,calendarLock:false,autoBookingEnabled:true,monitorSessionId:'test-1',monitoredTabId:1,successWatch:true,scanMonth:'',scanChecked:[],scanPending:'',bookingClickIssued:false,bookingStage:'MONITORING'};
window.__events=[];window.__dateClicks=[];window.__bookingClicks=0;
window.chrome=window.chrome||{};
const callbacks=[];
window.chrome.storage={local:{
 get:async keys=>{const d=window.__data;if(typeof keys==='string')return {[keys]:d[keys]};if(Array.isArray(keys))return Object.fromEntries(keys.map(k=>[k,d[k]]));return Object.assign({},keys||{},d)},
 set:async updates=>{const changes={};for(const [k,v] of Object.entries(updates)){changes[k]={oldValue:window.__data[k],newValue:v};window.__data[k]=v;}for(const cb of callbacks)cb(changes,'local')}
 },onChanged:{addListener:cb=>callbacks.push(cb)}};
window.chrome.runtime={onMessage:{addListener:cb=>window.__listener=cb},sendMessage:async msg=>{
 if(msg.type==='SARATHI_WAIT'){await new Promise(resolve=>setTimeout(resolve,msg.ms));return {ok:true};}
 if(msg.type==='SARATHI_OWNERSHIP')return {authorized:true,sessionId:'test-1'};
 if(msg.type==='SARATHI_ACTION_GATE'){
   if(!window.__data.running)return {authorized:false};
   if(msg.action==='BOOK_SLOT'){if(window.__data.bookingClickIssued)return {authorized:false};window.__data.bookingClickIssued=true;}
   return {authorized:true};
 }
 window.__events.push({type:msg.type,kind:msg.kind||''});return {ok:true};
}};
})();'''

async def run():
 async with async_playwright() as pw:
  browser=await pw.chromium.launch(headless=True,args=['--no-sandbox'])
  page=await browser.new_page()
  errors=[]
  page.on('pageerror',lambda e:errors.append(str(e)))
  await page.set_content(styles+calendar)
  await page.evaluate('(code)=>{(0,eval)(code)}',mock)
  await page.evaluate('''({calendar,quota,otp})=>{
    window.__testGoto=(place,date)=>{
      if(place==='calendar'){window.__testPath='/slots/bckdlappldet.do';document.body.innerHTML=calendar;return;}
      if(place==='quota'){
        window.__dateClicks.push(date);
        window.__testPath='/slots/fetchdlslotdetinfo.do';
        const c=date.endsWith('10')?'13':'0';
        const d=date.endsWith('10')?'10-12-2026':'09-12-2026';
        document.body.innerHTML=quota.replace('{date}',d).replace('{count}',c).replace('{disabled}',c==='0'?'disabled':'');return;
      }
      if(place==='otp'){
        window.__bookingClicks++;
        window.__testPath='/slots/dlsltprev.do';document.body.innerHTML=otp;
      }
    }
  }''',{'calendar':calendar,'quota':quota,'otp':otp})
  await page.evaluate('(code)=>{(0,eval)(code)}',rules)
  await page.evaluate('(code)=>{(0,eval)(code)}',source)
  try:
   await page.wait_for_function('window.__data.bookingStage==="AWAITING MANUAL SMS VERIFICATION"',timeout=27000)
  except Exception:
   print('DEBUG:',await page.evaluate('''() => ({stage:window.__data.bookingStage,status:window.__data.lastStatus,dates:window.__dateClicks,bookingClicks:window.__bookingClicks,scanChecked:window.__data.scanChecked,events:window.__events})'''))
   print('JS ERRORS:',errors)
   raise
  result=json.loads(await page.evaluate('''() => JSON.stringify({stage:window.__data.bookingStage,running:window.__data.running,dates:window.__dateClicks,scanChecked:window.__data.scanChecked,clicks:window.__bookingClicks,otp:document.getElementById('smsCode')?.value,events:window.__events})'''))
  print('FLOW RESULT:',result)
  assert result['dates']==['2026-12-09','2026-12-10'],result
  assert result['scanChecked']==['2026-12-09'],result  # rejected dates returned from; selected positive remains pending
  assert result['clicks']==1,result
  assert result['running'] is False,result
  assert result['otp']=='',result
  assert sum(e['type']=='SARATHI_SMS_READY' for e in result['events'])==1,result
  assert errors==[],errors
  print('PASS: selected December 9 green date, verified radio+span quota 0, clicked Back')
  print('PASS: selected December 10 green date, verified radio+span quota 13, selected LMV/MCWG, BOOK SLOT once')
  print('PASS: stopped on manual SMS verification, OTP field not modified')
  await browser.close()
if __name__=='__main__':asyncio.run(run())

