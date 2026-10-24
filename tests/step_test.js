// Mock-DOM test of app/src/main/assets/step.js (no browser, no network).
// Run: node tests/step_test.js
const fs = require('fs'), assert = require('assert');
const src = fs.readFileSync(__dirname + '/../app/src/main/assets/step.js', 'utf8');

class El {
  constructor(o = {}) {
    Object.assign(this, { children: [], sels: [], text: '', style: { display: 'block', visibility: 'visible' }, clicks: 0, disabled: false, checked: false, parentElement: null, isConnected: true, nodeType: 1, value: '', form: null }, o);
  }
  add(c) { c.parentElement = this; this.children.push(c); return c; }
  get textContent() { return this.text + this.children.map(c => c.textContent).join(' '); }
  get innerText() { return this.textContent; }
  click() { this.clicks++; if (this.type === 'checkbox') this.checked = true; }
  getBoundingClientRect() { return { width: 10, height: 10 }; }
  all() { return this.children.flatMap(c => [c, ...c.all()]); }
  querySelectorAll(sel) { const want = sel.split(',').map(s => s.trim()); return this.all().filter(e => e.sels.some(s => want.includes(s))); }
  querySelector(sel) { return this.querySelectorAll(sel)[0] || null; }
}

const text = (body, t) => body.add(new El({ text: t }));

// 1. session error
const d1 = new El(); const b1 = d1.add(new El()); text(b1, 'Your session flow was interrupted or has expired. Please return to the slot booking home page to start again.');
d1.body = b1; d1.getElementById = () => null; d1.querySelector = () => null; d1.querySelectorAll = () => [];
assert.equal(new Function('document', 'location', 'window', 'getComputedStyle', 'return (' + src + ')')(d1, { pathname: '/x' }, { innerWidth: 1080 }, e => e.style)({}).state, 'EXPIRED');

// 2. calendar: green dates reported, nothing is ever clicked
{
  const d = new El(); const body = d.add(new El({ text: 'Slots Available. Click on preferable date' }));
  const root = body.add(new El({ sels: ['#calview'] }));
  [['2', 1], ['3', 1], ['4', 0], ['9', 1]].forEach(([n, g]) => root.add(new El({ text: n, sels: g ? ['td.cal_green', '.cal_green', '#calview .cal_green'] : ['td.cal_blue'] })));
  d.body = body; d.getElementById = () => null; d.querySelector = s => (s === '#calview' ? root : null);
  d.querySelectorAll = s => d.all().filter(e => e.sels.some(x => s.split(',').map(t => t.trim()).includes(x)));
  const fn = new Function('document', 'location', 'window', 'getComputedStyle', 'return (' + src + ')')(d, { pathname: '/slots/fetchdlslotdetinfo.do' }, { innerWidth: 1080 }, e => e.style);
  const out = fn({ allowProceed: true });
  assert.equal(out.state, 'CALENDAR'); assert.equal(out.greens, '2,3,9'); assert.equal(out.acted, '');
  assert.ok(d.all().every(e => e.clicks === 0), 'calendar must never be clicked');
}

// helper for pages built from ids
function page(build, opts) {
  const d = new El(); const body = d.add(new El()); const ids = {};
  build({ body, ids, d });
  d.body = body; d.getElementById = id => ids[id] || null; d.querySelector = s => (s === '#calview' ? null : null);
  d.querySelectorAll = s => d.all().filter(e => e.sels.some(x => s.split(',').map(t => t.trim()).includes(x)));
  const fn = new Function('document', 'location', 'window', 'getComputedStyle', 'return (' + src + ')')(d, { pathname: '/slots/p.do' }, { innerWidth: 1080 }, e => e.style);
  return { out: fn(opts), d, ids };
}

// 3. no-slots popup: clicks only the popup Back button
const popup = (visible) => ({ body, ids }) => {
  const dialog = body.add(new El({ text: 'Slots are not available for the next 60 days', style: { display: visible ? 'block' : 'none', visibility: 'visible' } }));
  ids.dialog = dialog;
  ids.back = dialog.add(new El({ text: 'Back', sels: ['button.fadeandscale_close'] }));
  dialog.querySelector = s => (s === 'button.fadeandscale_close' ? ids.back : null);
};
let r = page(popup(true), { allowProceed: true });
assert.equal(r.out.state, 'NOSLOTS'); assert.equal(r.out.acted, 'back'); assert.equal(r.ids.back.clicks, 1);
r = page(popup(false), { allowProceed: true });
assert.notEqual(r.out.state, 'NOSLOTS'); assert.equal(r.ids.back.clicks, 0, 'hidden popup is ignored');

// 4. class page
const cov = (checked, disabledProceed) => ({ body, ids }) => {
  const form = body.add(new El({ sels: [] })); ids.dispdlapcntdetform = form;
  const mk = v => { const c = form.add(new El({ type: 'checkbox', value: v, checked, sels: ['input[type="checkbox"][name="iscov"]'] })); return c; };
  ids.lmv = mk('4'); ids.mcwg = mk('3'); ids.other = mk('9');
  ids.prcdbook = form.add(new El({ disabled: disabledProceed, sels: [] })); ids.prcdbook.form = form;
};
r = page(cov(false, false), { allowProceed: false });
assert.equal(r.out.state, 'COV'); assert.equal(r.out.acted, ''); assert.equal(r.ids.lmv.clicks + r.ids.prcdbook.clicks, 0, 'not due: no clicks');
r = page(cov(false, false), { allowProceed: true });
assert.equal(r.out.acted, 'select'); assert.equal(r.ids.lmv.clicks, 1); assert.equal(r.ids.mcwg.clicks, 1); assert.equal(r.ids.other.clicks, 0); assert.equal(r.ids.prcdbook.clicks, 0, 'select first, Proceed next tick');
r = page(cov(true, false), { allowProceed: true });
assert.equal(r.out.acted, 'proceed'); assert.equal(r.ids.prcdbook.clicks, 1); assert.equal(r.ids.lmv.clicks, 0);
r = page(cov(true, true), { allowProceed: true });
assert.equal(r.out.acted, ''); assert.equal(r.ids.prcdbook.clicks, 0, 'disabled Proceed is never clicked');

// 5. other states
r = page(({ body }) => text(body, 'Available Quota  Time Slots LMV, MCWG'), {}); assert.equal(r.out.state, 'QUOTA');
r = page(({ body }) => text(body, 'Enter the CAPTCHA to continue'), {}); assert.equal(r.out.state, 'LOGIN');
r = page(({ body }) => text(body, 'Welcome'), {}); assert.equal(r.out.state, 'UNKNOWN');

console.log('PASS: step.js — expired, calendar (no clicks), popup Back, class page select/proceed, quota, login, unknown');
