const fs=require('fs');
const assert=require('assert');
const root=process.cwd();
const read=p=>fs.readFileSync(root+'/'+p,'utf8');
const main=read('app/src/main/java/com/sarathi/geckotest/MainActivity.java');
const service=read('app/src/main/java/com/sarathi/geckotest/MonitorService.java');
const alerts=read('app/src/main/java/com/sarathi/geckotest/PhoneAlerts.java');
const engine=read('app/src/main/java/com/sarathi/geckotest/BrowserEngine.java');
const content=read('app/src/main/assets/extension/content.js');
const worker=read('app/src/main/assets/extension/background.js');
const readme=read('README.md');

const row3=main.indexOf('LinearLayout utilities=new LinearLayout(this);');
const row4=main.indexOf('LinearLayout controls=new LinearLayout(this);');
assert(row3>=0&&row4>row3,'Applicant utility row must precede the four-button control row');
for(const label of ['Applicant 9','Background setup · hold: Home','Phone numbers','Copy log · hold: Alerts'])
  assert(main.indexOf(label,row3)>=0&&main.indexOf(label,row3)<row4,'Missing/reordered row-three control: '+label);
for(const label of ['button(controls,"Monitor"','button(controls,"Start All"','button(controls,"Stop"','button(controls,"Silence"'])
  assert(main.indexOf(label,row4)>=0,'Missing row-four control: '+label);
assert(!main.includes('Start All / Home'),'Home must not be merged with Start All');
assert(!main.includes('button(controls,"Alerts"'),'Standalone Alerts control must be removed');
assert(main.includes('lp.height=dp(64)'),'Row-four buttons must be 64dp tall');
assert(main.includes('homeSelectedApplicant()')&&main.includes('Leave protected booking page?'),'Home hold must confirm on protected booking stages');
assert(main.includes('copyLog.setOnLongClickListener')&&main.includes('manageApplicantAlerts()'),'Copy Log hold must open Alerts');

for(const mode of ['ALL','CRITICAL_ONLY','ROUTINE_OFF'])
  assert(main.includes('"'+mode+'"')||alerts.includes('"'+mode+'"'),'Missing alert mode '+mode);
assert(main.includes('Test alert')&&alerts.includes('void testFullAlarm()'),'Full test alarm control missing');
assert(alerts.includes('if(!critical&&!"ALL".equals(mode()))return;'),'Routine Android notifications must respect ALL mode; critical alerts bypass mode filtering');
assert(alerts.includes('boolean isEnabled(){return true;}'),'Critical audio must not be disabled by a routine alert mode');
assert(service.includes('ALERTS: ROUTINE OFF (')&&service.includes('new Notification.BigTextStyle().bigText(summary)'),'Ongoing BigText notification must include ROUTINE OFF status');
assert(main.includes('routineOffSummary()')&&main.includes('ALERTS: ROUTINE OFF ('),'Activity status must show ROUTINE OFF applicants');
assert(main.includes('metricsSummary()')&&service.includes('Cycle metrics: '),'Copy Log header must include per-applicant cadence metrics');

assert(content.includes('const TARGET_PERIOD = 10000;'),'Target cadence must default to 10 seconds');
assert(content.includes('async function waitForSubmitDeadline('),'Deadline scheduler missing');
const loop=content.slice(content.indexOf('async function processLoop()'),content.indexOf('chrome.runtime.onMessage.addListener'));
assert(loop.indexOf('waitForSubmitDeadline(generation)')<loop.indexOf("actionAllowed('PROCEED')"),'Deadline wait must occur immediately before the guarded PROCEED');
assert(loop.includes("actionAllowed('COV_SELECT')")&&loop.includes("actionAllowed('PROCEED_CONFIRM')"),'Existing action authorization gates must remain');
assert(content.includes('lastProceedStartedAt')&&content.includes('averageCycleMs')&&content.includes('lastCycleMs')&&content.includes('covClassCount')&&content.includes('verificationRetries'),'Per-applicant cycle metrics missing');
assert(worker.includes('d.lastProceedGateAt<8000'),'Background gate must enforce the 8-second minimum');
assert(engine.includes('case "METRICS":')&&engine.includes('averageCycleMs=m.optInt'),'Native bridge must retain metrics');
assert(readme.includes('ROUTINE OFF')&&readme.includes('Start All'),'README Controls documentation is stale');
console.log('PASS: requested UI layout, alert modes, protected Home confirmation, cadence deadline, metrics and notification assertions');
