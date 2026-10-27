// Run: node tests/calendar_rules.js
const fs=require('fs'),vm=require('vm'),assert=require('assert');
const context={};vm.createContext(context);
vm.runInContext(fs.readFileSync(__dirname+'/../booking-rules.js','utf8'),context);
const p=context.BookingRules.calendarMonthTitle;
for(const title of ['October 2026','October, 2026','Prev October Next 2026','2026 - Oct','10/2026','2026-10']){
 assert.equal(p(title)?.key,'2026-10',title);
}
assert.equal(p('October'),null);
assert.equal(p('October 2026 November 2026'),null);
assert.equal(p('14/10/2026'),null);
console.log('PASS: calendar heading variants, missing year, ambiguous months');

