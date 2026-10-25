(() => {
  // Retain the legacy sentinel only to prevent duplicate injection over an old page.
  if (window.__sarathiAnantnagLoaded || window.__sarathiAnantnagV33ProLoaded) return;
  window.__sarathiAnantnagLoaded = true;
  window.__sarathiAnantnagV33ProLoaded = true;

  const WAIT_MS = 8000;
  let bookingPaused = false;
  let calendarLocked = false;
  let sessionLocked = false;
  const EXPIRY_TEXT = /your session flow was interrupted or has expired\. please return to the slot booking home page to start again\.?/i;
  const calendarPath = () => /\/slots\/fetchdlt(?:prev|next)mnth\.do$/i.test(location.pathname);
  const realCalendar = () => {
    const root = document.querySelector('#calview');
    const dateCells = Array.from((root || document).querySelectorAll('td.cal_green, td.cal_blue, td.cal_gray, td.cal_red, .cal_green')).filter(visible);
    return ((!!root && visible(root)) || calendarPath()) && dateCells.some(el => /^\d{1,2}$/.test(norm(el.textContent)));
  };
  let inFlight = false;
  let bookingActionBusy = false;
  let smsAlertSent = false;
  let loopActive = false;
  let localStop = false;
  // Page actions belong exclusively to the user-selected Sarathi tab.
  // Other Sarathi tabs may load this content script, but must remain observers only.
  let ownsTab = false;
  let cachedRunning = false;
  let lastLoopProgress = 0;
  let sessionId = '';
  let loopGeneration = 0;
  let loopStartedAt = 0;
  let lastOwnerCheck = 0;
  let quotaPageSince = 0;
  let quotaSignature = '';
  let quotaStableAt = 0;
  // Every dangerous click is authorized by the background worker using the active session.
  async function actionAllowed(action, extra={}, expectedSession=sessionId) {
    if (!ownsTab || !cachedRunning || localStop || !sessionId || sessionId!==expectedSession) return false;
    try {
      const response = await chrome.runtime.sendMessage({type:'SARATHI_ACTION_GATE',action,sessionId,...extra});
      if(!response?.authorized){
        if(response?.error === 'Different monitored tab or session') { ownsTab=false; localStop=true; }
        return false;
      }
      return ownsTab && cachedRunning && !localStop && sessionId===expectedSession;
    }catch(_){ return false; }
  }
  async function confirmOwnership() {
    try {
      const r = await chrome.runtime.sendMessage({type:'SARATHI_OWNERSHIP'});
      ownsTab = r?.authorized === true;
      if (ownsTab) { const next=String(r.sessionId || ''); if(next!==sessionId){smsAlertSent=false;lastAlertKey='';quotaPageSince=0;quotaSignature='';} sessionId=next; }
      lastOwnerCheck = Date.now();
      return ownsTab;
    } catch (_) { ownsTab = false; return false; }
  }
  let cycle = 0;
  // Read the persisted submission count so navigation does not reset it.
  // Daily attempts are counted only between 09:00 and 21:00 local time.
  const dayKey = () => {const d=new Date();return `${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}-${String(d.getDate()).padStart(2,'0')}`;};
  chrome.storage.local.get({attempts:0,attemptDay:''}).then(d=>{cycle=d.attemptDay===dayKey()?(Number(d.attempts)||0):0;}).catch(()=>{});
  let popupHandledAt = 0;
  let lastNetworkOnline = navigator.onLine;
  // A heartbeat reports that the monitored page script is alive, not that a slot check succeeded.
  setInterval(() => {
    if (ownsTab && cachedRunning && !localStop)
      chrome.storage.local.set({heartbeatAt:Date.now(),monitorProgressAt:lastLoopProgress,monitorLoopActive:loopActive}).catch(()=>{});
  }, 5000);

  // Worker replies schedule waits independently of hidden-page timer throttling.
  // No clicks run in the worker; every continuation still passes local/session gates.
  async function sleep(ms) {
    const expected=sessionId;
    if(ownsTab && cachedRunning && !localStop && expected){
      try {
        const r=await chrome.runtime.sendMessage({type:'SARATHI_WAIT',sessionId:expected,ms,monitorProgressAt:lastLoopProgress,monitorLoopActive:loopActive});
        if(r?.ok || r?.cancelled)return;
      } catch (_) {}
      // A stopped/reloaded worker may disconnect a message. Keep a bounded,
      // conservative fallback; existing health recovery can retry the loop.
    }
    await new Promise(resolve=>setTimeout(resolve,ms));
  }
  const norm = s => String(s || '').replace(/\s+/g, ' ').trim();
  let textCache='',textCacheAt=-Infinity;
  const pageText = (fresh=false) => {
    if(fresh || Date.now()-textCacheAt>250){textCache=norm(document.body?.innerText || document.documentElement?.innerText || '');textCacheAt=Date.now();}
    return textCache;
  };
  // Sarathi may keep the modal's text in hidden DOM markup even when no popup is open.
  // Treat it as a no-slots popup ONLY when its dedicated popup Back button is visible.
  const noSlotsTextPresent = () => {
    const dialog = document.getElementById('dialog');
    const popupBack = dialog?.querySelector('button.fadeandscale_close');
    if (!dialog || !visible(popupBack)) return false;
    // Reading only the dialog prevents a costly full-page innerText scan every 200ms.
    const noSlotsPattern=/slots\s+are\s+not\s+available\s+for\s+the\s+next\s+\d+\s+days/i;
    // Some Sarathi layouts render the message outside #dialog; only fall back to
    // the full page when the popup Back button is actually visible.
    return noSlotsPattern.test(norm(dialog.innerText || dialog.textContent)) || noSlotsPattern.test(pageText());
  };

  async function isRunning(generation=null) {
    return !localStop && ownsTab && cachedRunning && (generation === null || generation === loopGeneration);
  }

  async function waitWhileRunning(ms, generation=null) {
    const end = Date.now() + ms;
    // The monitor has a single next-retry clock, not a storage write on every tick.
    if(ms>=WAIT_MS) try { await chrome.storage.local.set({nextRetryAt:end}); }catch(_){}
    while (Date.now() < end) {
      if (!(await isRunning(generation))) return false;
      await sleep(Math.min(1000, end-Date.now()));
    }
    if(ms>=WAIT_MS && (generation===null||generation===loopGeneration))try{await chrome.storage.local.set({nextRetryAt:0});}catch(_){}
    return await isRunning(generation);
  }

  function setStatus(text) {
    try { chrome.storage.local.set({ lastStatus: text, lastCheck: Date.now() }); } catch (_) {}
    console.info('[Sarathi V11.47]', text);
  }

  function visible(el) {
    if (!el || !el.isConnected) return false;
    const s = getComputedStyle(el);
    if (s.display === 'none' || s.visibility === 'hidden') return false;
    const r = el.getBoundingClientRect();
    return r.width > 0 && r.height > 0;
  }

  // Only targets the popup's button, never the form-level input submit named BACK.
  function findPopupBack() {
    if (!noSlotsTextPresent()) return null;
    // Sarathi markup duplicates id=btnOk on a DIV and its inner BUTTON.
    // Query the actual clickable button inside the dialog, not the outer DIV.
    const dialog = document.getElementById('dialog');
    const actualBack = dialog?.querySelector('button.fadeandscale_close');
    if (actualBack && visible(actualBack) && /^back$/i.test(norm(actualBack.innerText || actualBack.textContent))) return actualBack;
    // Markup fallback: visible BUTTON whose text is exactly Back. Inputs are excluded.
    for (const button of dialog?.querySelectorAll('button') || []) {
      const text = norm(button.innerText || button.textContent || button.value);
      if (visible(button) && /^back$/i.test(text)) return button;
    }
    return null;
  }

  function getCovForm() {
    const form = document.getElementById('dispdlapcntdetform');
    const proceed = document.getElementById('prcdbook');
    if (!form || !proceed || proceed.form !== form || !visible(proceed)) return null;
    const checkboxes = Array.from(form.querySelectorAll('input[type="checkbox"][name="iscov"]'));
    const selected = checkboxes.filter(el => (el.value === '4' || el.value === '3') && !el.disabled && visible(el));
    if (!selected.length) return null;
    return {form, proceed, selected};
  }

  async function handleNoSlotsPopup(generation=null) {
    const back = findPopupBack();
    if (!back) {
      if (noSlotsTextPresent()) setStatus('No-slots message detected; waiting for its popup Back button to appear');
      return false;
    }
    if (Date.now() - popupHandledAt < 1500) return true;
    popupHandledAt = Date.now();
    setStatus('No-slots popup detected — clicking popup Back');
    if (!await isRunning(generation) || !await actionAllowed('POPUP_BACK') || !await isRunning(generation))return true;
    try { back.click(); }
    catch (e) { setStatus('Could not click popup Back: ' + String(e)); return true; }

    // Wait for the modal text/button to disappear. Never click the page-level BACK input.
    const end = Date.now() + 5000;
    while (Date.now() < end && await isRunning()) {
      await sleep(150);
      if (!noSlotsTextPresent() || !findPopupBack()) break;
    }
    if (!await isRunning()) return true;
    if (noSlotsTextPresent() && findPopupBack()) {
      setStatus('Popup Back was clicked, but the popup remains visible; waiting before retrying safely');
      await waitWhileRunning(1000);
      return true;
    }
    // The main COV loop owns the single 8-second retry countdown.
    // Do not wait here too: that caused two consecutive countdowns (~10s).
    setStatus('Popup dismissed — preparing next 8-second retry');
    return true;
  }

  // Alert only when the booking calendar is genuinely visible, not from hidden modal markup.
  let lastAlertKey = '';
  let inspectingCalendar = false;
  async function inspectBookingPage() {
    const eventSession=sessionId;
    if (inspectingCalendar || !ownsTab) return;
    inspectingCalendar = true;
    try {
    if (!(await isRunning()) || noSlotsTextPresent()) return;
    // Avoid reading the entire document when a recognized calendar already exists.
    const calendar = realCalendar();
    if(!calendar && getCovForm())return;
    const text = calendar ? '' : pageText();
    const quotaLabel = !calendar && /available\s+quota/i.test(text);
    const bookingCalendar = calendar || (quotaLabel && /\b(?:LMV|MCWG)\b/i.test(text) && !getCovForm());
    if (!bookingCalendar) return;
    bookingPaused = true;
    calendarLocked = true;
    // Persist before sending alert; navigation cannot silently restart the retry loop.
    const prior=await chrome.storage.local.get({calendarLock:false,bookingStage:''});
    if(!await isRunning() || sessionId!==eventSession)return;
    if(!prior.calendarLock){
      const initial=!prior.bookingStage||['MONITORING','STANDBY'].includes(prior.bookingStage);
      await chrome.storage.local.set({calendarLock:true,successWatch:true,nextRetryAt:0,...(initial?{bookingStage:'CALENDAR DETECTED',lastStatus:'Booking calendar detected — retry loop paused',lastCheck:Date.now()}: {})});
    }
    // Quota notifications use the same verified table parser as booking.
    if(!calendar){await publishQuotaAlert(quotaOptions(),quotaDisplayedDate(),eventSession);return;}
    const lmvQuota=null,mcwgQuota=null;
    const kind=[...document.querySelectorAll('#calview .cal_green,td.cal_green')].some(el=>visible(el)&&/^\d{1,2}$/.test(norm(el.textContent)))?'GREEN_DATE':'CALENDAR';
    const greenSignature=calendar?Array.from(document.querySelectorAll('#calview .cal_green,td.cal_green')).filter(visible).map(x=>norm(x.textContent)).join(',').slice(0,100):'';
    const key = sessionId + ':' + kind + ':' + location.pathname + ':' + (calendarMonth()?.key||'') + ':' + greenSignature + ':' + (lmvQuota ?? '?') + ':' + (mcwgQuota ?? '?');
    if (key === lastAlertKey) return;
    // Persist the event key across navigation/reloads to avoid repeat alerts.
    const saved = await chrome.storage.local.get({calendarAlertKey:'',calendarAlertAt:0});
    if(saved.calendarAlertKey===key && Date.now()-saved.calendarAlertAt<30*60*1000){lastAlertKey=key;return;}
    lastAlertKey=key;
    await chrome.storage.local.set({calendarAlertKey:key,calendarAlertAt:Date.now()});
    try { chrome.runtime.sendMessage({type:'SARATHI_ALERT',sessionId:eventSession,kind,eventKey:key,quota:lmvQuota,mcwgQuota,url:location.href}).catch(()=>{}); } catch (_) {}
    } finally { inspectingCalendar = false; }
  }

  // Assisted booking: a bounded, same-month Wednesday/Thursday scan.
  // Never infer vehicle classes from a numeric quota or from a radio ID suffix.
  // Only a verified positive numeric quota can authorize BOOK SLOT.
  // Calendar discovery and selection are intentionally separate. The diagnostic
  // capture identified td.cal_green with a child A and a pointer cursor; it did
  // NOT establish an A[href*="fetchdlslotinfo.do"] or a fixed heading layout.
  function calendarMonth() {
    const root=document.querySelector('#calview');
    if(!root || !visible(root))return null;
    // Prefer heading/caption text to calendar dates or applicant details elsewhere.
    const candidates=[...root.querySelectorAll('caption, thead, tr:first-child, [class*="month"], [id*="month"]')]
      .filter(visible).map(el=>norm(el.innerText || el.textContent).slice(0,200));
    // Some Sarathi builds split month, year and Prev/Next between sibling nodes.
    candidates.push(norm(root.innerText || root.textContent).slice(0,260));
    const values=candidates.map(v=>BookingRules.calendarMonthTitle(v)).filter(Boolean);
    const unique=new Map(values.map(v=>[v.key,v]));
    // Conflicting month headers are not safe to resolve automatically.
    return unique.size===1 ? [...unique.values()][0] : null;
  }
  function calendarCandidates() {
    const m=calendarMonth();if(!m)return null;
    const root=document.querySelector('#calview');
    if(!root)return null;
    const today=new Date();today.setHours(0,0,0,0);
    return [...root.querySelectorAll('td.cal_green')].filter(visible).map(td=>{
      const dayText=norm(td.textContent);
      if(!/^\d{1,2}$/.test(dayText))return null;
      const day=Number(dayText);
      const anchor=[...td.querySelectorAll('a')].find(a=>visible(a) && !a.hasAttribute('disabled') && getComputedStyle(a).pointerEvents!=='none');
      // Prefer the actual child link. Some calendar builds delegate clicks to
      // the green TD; accept that only when the cell advertises a pointer.
      const target=anchor || (getComputedStyle(td).cursor==='pointer'?td:null);
      if(!target)return null;
      const date=new Date(m.year,m.month,day);
      if(day<1 || date.getMonth()!==m.month || date.getFullYear()!==m.year)return null;
      return {target,day,key:m.key+'-'+String(day).padStart(2,'0'),weekday:date.getDay(),date};
    }).filter(x=>x && [3,4].includes(x.weekday)&&x.date>=today).sort((x,y)=>x.day-y.day);
  }
  let lastCalendarDiagnostic='';
  function calendarDiagnostic(message) {
    if(message===lastCalendarDiagnostic)return;
    lastCalendarDiagnostic=message;
    setStatus(message);
    chrome.storage.local.set({bookingStage:'CALENDAR REVIEW',lastCalendarDiagnostic:message}).catch(()=>{});
  }
  function classKind(value) {
    const hasLMV=/\bLMV\b/i.test(value),hasMCWG=/\bMCWG\b/i.test(value);
    return hasLMV&&hasMCWG?'BOTH':hasLMV?'LMV':hasMCWG?'MCWG':null;
  }
  // V11.38: The original V7 deep diagnostic records the real quota table:
  // <th>Time Slots</th><th>LMV, MCWG</th>
  // <td>09.00-18.00</td><td><input type="radio" name="LMV, MCWG"><span>0</span></td>
  // The photographed December quota page shows the corresponding number 13.
  // Therefore, a count may legitimately be in the RADIO CELL, not a separate
  // numeric table cell. Never treat random page numbers, radio IDs or serials as quota.
  function quotaTableHeaders(table) {
    if (!table) return null;
    const rows=[...table.querySelectorAll('tr')];
    const header=rows.find(row=>{
      const ths=[...row.children].filter(el=>el.tagName==='TH');
      return ths.length>=2 && /^time\s+slots?$/i.test(norm(ths[0].innerText || ths[0].textContent)) &&
        ths.slice(1).some(th=>BookingRules.classKind(norm(th.innerText || th.textContent))) &&
        ths.slice(1).every(th=>BookingRules.classKind(norm(th.innerText || th.textContent)) || BookingRules.quotaHeader(norm(th.innerText || th.textContent)));
    });
    if(!header)return null;
    const cells=[...header.children].filter(el=>el.tagName==='TH'||el.tagName==='TD');
    if(cells.some(el=>(Number(el.getAttribute('colspan'))||1)!==1))return null;
    return cells;
  }
  function verifiedAvailabilityColumn(cell,row) {
    if(BookingRules.quotaHeader(cell.getAttribute('aria-label') || cell.getAttribute('data-label') || ''))return true;
    const table=row.closest('table');if(!table)return false;
    const rowCells=[...row.children].filter(el=>/^(TD|TH)$/.test(el.tagName));
    const index=rowCells.indexOf(cell);if(index<0)return false;
    const headings=[...table.querySelectorAll('tr')].filter(r=>r!==row&&!r.querySelector('input[type="radio"]')).slice(0,5);
    return headings.some(headerRow=>{
      const columns=[...headerRow.children].filter(el=>/^(TD|TH)$/.test(el.tagName));
      return columns.length===rowCells.length && columns.every(el=>(Number(el.getAttribute('colspan'))||1)===1) && BookingRules.quotaHeader(norm(columns[index]?.innerText || columns[index]?.textContent));
    });
  }
  function quotaOptions() {
    const entries=[];let total=0;
    for(const table of document.querySelectorAll('table')) {
      const headers=quotaTableHeaders(table);
      if(!headers){if(/available\s+quota/i.test(table.closest('fieldset')?.querySelector('legend')?.textContent||''))total+=[...table.querySelectorAll('input[type="radio"]')].filter(r=>r.closest('table')===table).length;continue;}
      // Where the site supplies a fieldset legend, it must identify the
      // Available Quota area; a similarly shaped unrelated table is excluded.
      const fieldset=table.closest('fieldset');
      const legend=fieldset?.querySelector('legend');
      if(legend && !/available\s+quota/i.test(norm(legend.innerText||legend.textContent)))continue;
      total+=[...table.querySelectorAll('input[type="radio"]')].filter(r=>r.closest('table')===table).length;
      for(const row of table.querySelectorAll('tr')) {
        if(row.closest('table')!==table)continue;
        const cells=[...row.children].filter(el=>el.tagName==='TD'||el.tagName==='TH');
        if(cells.length!==headers.length || cells.some(c=>(Number(c.getAttribute('colspan'))||1)!==1))continue;
        // First column must identify a time slot, never a serial-number row.
        if(!/^\s*\d{1,2}[.:]\d{2}\s*[-–]\s*\d{1,2}[.:]\d{2}\s*$/.test(norm(cells[0].innerText||cells[0].textContent)))continue;
        for(let i=1;i<cells.length;i++) {
          const cell=cells[i];
          const radios=[...cell.querySelectorAll('input[type="radio"]')];
          if(!radios.length)continue;
          if(radios.length!==1 || !visible(cell))continue;
          const radio=radios[0];
          const headerKind=BookingRules.classKind(norm(headers[i].innerText||headers[i].textContent));
          const radioKind=BookingRules.classKind(radio.name||'');
          if(!headerKind || (radioKind && radioKind!==headerKind))continue;
          let count=null;
          // A verified radio cell may contain plain text or a span. Its entire
          // visible text must be one nonnegative safe integer, never "13 seats".
          const cellText=norm(cell.innerText||cell.textContent);
          const numericSpans=[...cell.querySelectorAll('span')].filter(x=>/^\d+$/.test(norm(x.innerText||x.textContent)));
          const parsedCount=BookingRules.quota(cellText);
          if(!numericSpans.length || (numericSpans.length===1 && BookingRules.quota(norm(numericSpans[0].innerText||numericSpans[0].textContent))===parsedCount))count=parsedCount;
          // Preserve the previous verified independent availability-column
          // layout if the radio's own cell has no numeric count.
          if(count===null) {
            const numeric=cells.filter(c=>c!==cell&&!c.querySelector('input[type="radio"]')&&
              /^\d+$/.test(norm(c.innerText||c.textContent)) && verifiedAvailabilityColumn(c,row));
            if(numeric.length===1 && cells.filter(c=>c.querySelector('input[type="radio"]')).length===1)count=BookingRules.quota(norm(numeric[0].innerText||numeric[0].textContent));
          }
          if(count===null || (count>0 && (radio.disabled || !visible(radio))))continue;
          entries.push({radio,kind:headerKind,count,slotTime:norm(cells[0].innerText||cells[0].textContent)});
        }
      }
    }
    return {entries,total};
  }
  function quotaDisplayedDate() {
    // Missing, invalid or conflicting labelled dates never authorize a booking.
    const matches=[...pageText(true).matchAll(/available\s+time\s+slots\s*[:\-]?\s*(\d{2})[-/](\d{2})[-/](20\d{2})/ig)];
    const dates=matches.map(m=>`${m[3]}-${m[2]}-${m[1]}`);
    return dates.length && dates.every(d=>BookingRules.validDate(d)) && new Set(dates).size===1 ? dates[0] : null;
  }
  async function publishQuotaAlert(snapshot,date,expectedSession){
    if(!date || !snapshot.total || snapshot.entries.length!==snapshot.total)return;
    const positive=snapshot.entries.filter(o=>o.count>=1);
    if(!positive.length)return;
    const d=await chrome.storage.local.get({running:false,monitorSessionId:'',scanPending:'',quotaAlertKey:''});
    if(!d.running || sessionId!==expectedSession || d.monitorSessionId!==expectedSession || (d.scanPending && d.scanPending!==date))return;
    // Report the highest verified option for each class; do not sum shared counts.
    const lmv=positive.filter(o=>o.kind==='BOTH'||o.kind==='LMV').map(o=>o.count);
    const mcwg=positive.filter(o=>o.kind==='BOTH'||o.kind==='MCWG').map(o=>o.count);
    const quota=lmv.length?Math.max(...lmv):0,mcwgQuota=mcwg.length?Math.max(...mcwg):0;
    const kind=quota>0&&mcwgQuota>0?'BOTH_AVAILABLE':quota>0?'LMV_AVAILABLE':'MCWG_AVAILABLE';
    const eventKey=expectedSession+':'+date+':'+kind+':'+quota+':'+mcwgQuota;
    if(d.quotaAlertKey===eventKey)return;
    await chrome.storage.local.set({quotaAlertKey:eventKey});
    chrome.runtime.sendMessage({type:'SARATHI_ALERT',sessionId:expectedSession,kind,eventKey,quota,mcwgQuota,slotDate:date,url:location.origin+location.pathname}).catch(()=>{});
  }
  async function safeAssistStop(message, expectedSession=sessionId) {
    const eventSession=expectedSession;
    if(!await confirmOwnership() || sessionId!==eventSession)return;
    await chrome.storage.local.set({autoBookingEnabled:false,bookingStage:'MANUAL REVIEW REQUIRED',lastStatus:message});
    // When availability is rare, manual review must not silently go unnoticed.
    // The worker deduplicates warnings; no applicant/OTP values are transmitted.
    chrome.runtime.sendMessage({type:'SARATHI_WARNING',sessionId:eventSession,kind:'BOOKING_REVIEW',message}).catch(()=>{});
  }
  async function assistBooking() {
    const eventSession=sessionId;
    const gate=(action,extra={})=>actionAllowed(action,extra,eventSession);
    const stopAssist=message=>safeAssistStop(message,eventSession);
    const bookingGate=async(action,extra={})=>{
      if(await gate(action,extra))return true;
      const current=await chrome.storage.local.get({running:false,autoBookingEnabled:false,monitorSessionId:''});
      // STOP, disabled assistance and a replaced session are deliberate cancellation.
      if(!localStop && current.running && current.autoBookingEnabled && current.monitorSessionId===eventSession && sessionId===eventSession)
        await stopAssist('Booking authorization refused at '+action+' — no click issued; manual review');
      return false;
    };
    if(bookingActionBusy||localStop||!ownsTab)return;
    bookingActionBusy=true;
    try {
      const d=await chrome.storage.local.get({autoBookingEnabled:false,calendarLock:false,bookingClickIssued:false,scanMonth:'',scanChecked:[],scanFallback:null,scanPending:'',scanPendingAt:0,scanReturning:false,running:false,monitorSessionId:'',bookingStage:'',bookingSubmittedAt:0,bookingResponseWarned:false});
      if(!d.calendarLock||!d.running||localStop||d.monitorSessionId!==eventSession||sessionId!==eventSession)return;
      if(!d.autoBookingEnabled){
        if(realCalendar() && d.bookingStage!=='MANUAL REVIEW REQUIRED')calendarDiagnostic('CALENDAR PAUSED: assisted booking toggle is OFF');
        return;
      }
      if(isSmsVerificationPage()){
        await chrome.storage.local.set({autoBookingEnabled:false,running:false,bookingStage:'AWAITING MANUAL SMS VERIFICATION',nextRetryAt:0,lastStatus:'SMS verification ready — enter code manually'});
        if(!smsAlertSent){smsAlertSent=true;chrome.runtime.sendMessage({type:'SARATHI_SMS_READY',sessionId:eventSession,url:location.pathname}).catch(()=>{});}
        return;
      }
      if(d.bookingClickIssued){
        if(d.bookingSubmittedAt && !d.bookingResponseWarned && Date.now()-d.bookingSubmittedAt>=30000){
          await chrome.storage.local.set({bookingResponseWarned:true});
          await stopAssist('BOOK SLOT response not confirmed after 30 seconds — check Sarathi manually; submission remains locked');
        }
        return;
      }
      const bookingButton=document.querySelector('button#slotbtn');
      if(/\/slots\/fetchdlslotdetinfo\.do$/i.test(location.pathname)&&bookingButton){
        if(!quotaPageSince)quotaPageSince=Date.now();
        if(d.scanReturning){if(Date.now()-d.scanPendingAt>15000)await stopAssist('Return to calendar timed out — inspect page manually');return;}
        if(!d.scanPending){await stopAssist('Quota page reached without a tracked date — manual review');return;}
        const extracted=quotaOptions();
        const options=extracted.entries;
        const shownDate=quotaDisplayedDate();
        const signature=JSON.stringify({radios:extracted.total,options:options.map(o=>[o.kind,o.count,o.slotTime]),buttonDisabled:bookingButton.disabled,shownDate});
        if(signature!==quotaSignature){quotaSignature=signature;quotaStableAt=Date.now();}
        const waited=Date.now()-quotaPageSince;
        // AJAX quota rows and button state can lag behind the initial page DOM.
        if(waited<1800 || Date.now()-quotaStableAt<600)return;
        if(options.length!==extracted.total || !options.length){
          if(waited<9000)return;
          await stopAssist('Quota radio/count mapping remains ambiguous after loading — manual review');return;
        }
        if(!shownDate){if(waited<9000)return;await stopAssist('Quota date is missing, invalid or ambiguous — manual review');return;}
        if(shownDate!==d.scanPending){
          await stopAssist('Quota page date '+shownDate+' does not match selected calendar date '+d.scanPending);return;
        }
        await publishQuotaAlert(extracted,shownDate,eventSession);
        if(sessionId!==eventSession || localStop)return;
        const positive=options.filter(o=>o.count>=1);
        const chosen=positive.find(o=>o.kind==='BOTH')||positive.find(o=>o.kind==='LMV')||positive.find(o=>o.kind==='MCWG');
        const chosenDate=d.scanPending;const checked=[...new Set([...d.scanChecked,chosenDate])];
        await chrome.storage.local.set({bookingStage:chosen?'POSITIVE QUOTA VERIFIED':'CHECKING NEXT GREEN DATE'});
        if(chosen){
          if(!await bookingGate('QUOTA_SELECT'))return;
          if(!chosen.radio.checked)chosen.radio.click();
          // Sarathi may enable BOOK SLOT asynchronously after radio selection.
          const enableUntil=Date.now()+3000;
          while(Date.now()<enableUntil && (bookingButton.disabled||!visible(bookingButton)) && await isRunning())await sleep(150);
          if(!chosen.radio.checked||bookingButton.disabled||!visible(bookingButton)||localStop){await stopAssist('Positive quota found but BOOK SLOT did not become enabled');return;}
          // Token-bound reservation: only a proven pre-click abort may release it.
          const reservationId=eventSession+':'+Date.now()+':'+Math.random().toString(36).slice(2);
          let clickStarted=false;
          try {
            if(!await bookingGate('BOOK_SLOT',{date:chosenDate,kind:chosen.kind,reservationId}))return;
            if(!await bookingGate('BOOK_SLOT_CONFIRM',{reservationId}))return;
            const fresh=quotaOptions(),freshDate=quotaDisplayedDate();
            const stillChosen=fresh.entries.find(o=>o.radio===chosen.radio && o.kind===chosen.kind && o.slotTime===chosen.slotTime && o.count>=1);
            if(localStop || sessionId!==eventSession || !chosen.radio.isConnected || !bookingButton.isConnected || bookingButton.disabled || !chosen.radio.checked || !visible(bookingButton) || !stillChosen || fresh.entries.length!==fresh.total || freshDate!==chosenDate){
              await stopAssist('Quota, date or selection changed before submission — no click issued');return;
            }
            // No asynchronous work between the final DOM checks and click.
            clickStarted=true;
            bookingButton.click();
            chrome.runtime.sendMessage({type:'SARATHI_ACTION_GATE',action:'BOOK_SLOT_DISPATCHED',sessionId:eventSession,reservationId}).catch(()=>{});
            setStatus('Positive '+chosen.kind+' quota '+stillChosen.count+' on '+chosenDate+' ('+chosen.slotTime+'); BOOK SLOT click dispatched');
          } finally {
            if(!clickStarted){
              // Direct cleanup also works after STOP; worker checks session/token/document.
              await chrome.runtime.sendMessage({type:'SARATHI_ACTION_GATE',action:'BOOK_SLOT_CANCEL',sessionId:eventSession,reservationId,noClick:true}).catch(()=>{});
            }
          }
          return;
        }
        const back=document.querySelector('#onlinedltestslotform_back');
        if(!back||!visible(back)){await stopAssist('Cannot return to calendar safely');return;}
        await chrome.storage.local.set({bookingStage:'CHECKING NEXT WED/THU',lastStatus:'No positive quota on '+chosenDate+'; checking next green test date'});
        if(await isRunning() && (await chrome.storage.local.get({autoBookingEnabled:false})).autoBookingEnabled && await gate('QUOTA_BACK')){
          await chrome.storage.local.set({scanChecked:checked,scanReturning:true,scanPendingAt:Date.now()});
          if(await gate('QUOTA_BACK') && back.isConnected && visible(back))back.click();
        }return;
      }
      if(realCalendar()){
        quotaPageSince=0;quotaSignature='';
        if(d.scanReturning){await chrome.storage.local.set({scanPending:'',scanPendingAt:0,scanReturning:false});}
        else if(d.scanPending){if(Date.now()-d.scanPendingAt>15000)await stopAssist('Date navigation timed out — inspect page manually');return;}
        const candidates=calendarCandidates();
        if(!candidates){await stopAssist('CALENDAR PAUSED: month and year cannot be verified from the visible calendar header');return;}
        const month=calendarMonth()?.key;
        if(!month){await stopAssist('CALENDAR PAUSED: month/year unavailable for safe date selection');return;}
        const checked=d.scanMonth===month?d.scanChecked:[];
        if(d.scanMonth!==month)await chrome.storage.local.set({scanMonth:month,scanChecked:[],scanFallback:null,scanPending:''});
        if(!candidates.length){
          const greenCount=[...document.querySelectorAll('#calview td.cal_green')].filter(visible).length;
          await stopAssist('CALENDAR PAUSED: '+greenCount+' green dates visible, but no verified future Wednesday/Thursday click target in '+month);
          return;
        }
        const next=candidates.find(c=>!checked.includes(c.key));
        if(next){
          calendarDiagnostic('Calendar: '+candidates.length+' eligible green Wednesday/Thursday dates; checking '+next.key);
          // Persist the selected date BEFORE page navigation so quota view can
          // associate the following radio group with the actual clicked date.
          await chrome.storage.local.set({scanPending:next.key,scanPendingAt:Date.now(),scanReturning:false,bookingStage:'GREEN WED/THU SELECTED',lastStatus:'Checking '+next.key+' for first positive quota'});
          if(!await isRunning() || !(await chrome.storage.local.get({autoBookingEnabled:false})).autoBookingEnabled){
            calendarDiagnostic('CALENDAR PAUSED: monitoring stopped or assisted booking disabled');return;
          }
          if(!await gate('DATE_SELECT')){
            calendarDiagnostic('CALENDAR PAUSED: date selection not authorized for monitored tab/session');return;
          }
          if(!next.target.isConnected || !visible(next.target)){
            calendarDiagnostic('CALENDAR PAUSED: green date control changed while selecting; waiting to recheck');return;
          }
          next.target.click();return;
        }
        await stopAssist('No positive quota found on verified green Wednesday/Thursday dates in '+month);
      }
    }catch(e){await stopAssist('Assisted booking stopped safely: '+String(e.message||e).slice(0,140));}
    finally{bookingActionBusy=false;}
  }
  // End assisted booking.

  function isSmsVerificationPage(){
    const field=document.querySelector('#smsCode, input[autocomplete="one-time-code"]');
    if(field && visible(field))return true;
    // URL by itself is not proof; insist on a visible code entry and SMS/OTP wording.
    if(!/\/slots\/dlsltprev\.do$/i.test(location.pathname))return false;
    return [...document.querySelectorAll('input[type="text"], input[type="tel"], input[type="number"]')].some(field=>visible(field) && /\b(?:otp|sms\s*(?:code|otp)|verification\s*code)\b/i.test([field.getAttribute('aria-label'),...(field.labels||[])].map(x=>typeof x==='string'?x:x?.textContent||'').join(' ')));
  }
  async function observeSmsPage(){
    const eventSession=sessionId;
    if(!ownsTab || smsAlertSent || !isSmsVerificationPage())return;
    if(!await confirmOwnership() || sessionId!==eventSession)return;
    const d=await chrome.storage.local.get({successWatch:false});if(!d.successWatch)return;
    smsAlertSent=true;await chrome.storage.local.set({running:false,autoBookingEnabled:false,bookingStage:'AWAITING MANUAL SMS VERIFICATION',nextRetryAt:0});
    chrome.runtime.sendMessage({type:'SARATHI_SMS_READY',sessionId:eventSession,url:location.pathname}).catch(()=>{});
  }


  // Read-only receipt observation. Never access OTP fields, application IDs or QR data.
  // Requires both an explicit receipt heading and a booking thank-you statement.
  async function inspectBookingSuccess() {
    const eventSession=sessionId;
    if (!ownsTab) return;
    const settings=await chrome.storage.local.get({successWatch:false});
    if(!settings.successWatch || sessionId!==eventSession) return;
    if(getCovForm() || realCalendar() || document.querySelector('button#slotbtn'))return;
    const text=pageText();
    if(!/provisional\s+appointment\s+for\s+driving\s+licen[cs]e\s+test/i.test(text)) return;
    if(!/thank\s+you\s+for\s+booking\s+an\s+appointment/i.test(text)) return;
    const rows=Array.from(document.querySelectorAll('tr')).filter(visible);
    const header=rows.find(r=>/slot\s+date/i.test(r.innerText||'') && /slot\s+time/i.test(r.innerText||''));
    if(!header) return;
    const cells=Array.from(header.querySelectorAll('th,td'));
    const labels=cells.map(el=>norm(el.innerText).toLowerCase());
    const dateIndex=labels.findIndex(x=>/slot\s+date/.test(x));
    const timeIndex=labels.findIndex(x=>/slot\s+time/.test(x));
    const covIndex=labels.findIndex(x=>/class.*vehicle/.test(x));
    const detailRow=header.nextElementSibling;
    if(!detailRow || !visible(detailRow)) return;
    const values=Array.from(detailRow.querySelectorAll('th,td')).map(el=>norm(el.innerText));
    const slotDate=values[dateIndex]||'';
    const slotTime=values[timeIndex]||'';
    const cov=values[covIndex]||'';
    if(!/^\d{2}[-/]\d{2}[-/]\d{4}$/.test(slotDate) || !slotTime || !/\d/.test(slotTime)) return;
    const bookingKey=[location.pathname,slotDate,slotTime,cov].join('|');
    try { chrome.runtime.sendMessage({type:'SARATHI_BOOKED',sessionId:eventSession,bookingKey,slotDate,slotTime,cov}).catch(()=>{}); } catch(_) {}
  }
  let inspectionBusy=false;
  async function inspectPage(){
    if(inspectionBusy || !ownsTab)return;
    inspectionBusy=true;textCacheAt=-Infinity;
    try{await inspectBookingPage();await observeSmsPage();await assistBooking();await inspectBookingSuccess();}
    finally{inspectionBusy=false;}
  }
  setInterval(()=>inspectPage().catch(()=>{}),1100);

  async function processLoop() {
    const eventSession=sessionId;
    if (loopActive || !ownsTab || !cachedRunning) return;
    loopActive = true;
    const generation=++loopGeneration;
    loopStartedAt=Date.now();
    lastLoopProgress=Date.now();
    try {
      while (await isRunning(generation)) {
        lastLoopProgress=Date.now();
        if (bookingPaused || calendarLocked || sessionLocked) { await inspectPage(); await waitWhileRunning(1000,generation); continue; }
        if (EXPIRY_TEXT.test(pageText())) {
          sessionLocked = true; localStop = true;
          await chrome.storage.local.set({running:false,sessionLock:true,nextRetryAt:0,lastStatus:'SESSION EXPIRED — manual restart required'});
          chrome.runtime.sendMessage({type:'SARATHI_WARNING',sessionId:eventSession,kind:'SESSION_EXPIRED',message:'Sarathi session expired. Return to slot booking home page manually.'}).catch(()=>{});
          break;
        }
        if (realCalendar()) { await inspectBookingPage(); if(!await waitWhileRunning(500,generation))break; continue; }
        if (!navigator.onLine) { if(lastNetworkOnline) setStatus('Internet disconnected — waiting for reconnection'); lastNetworkOnline=false; await waitWhileRunning(2000,generation); continue; }
        if(!lastNetworkOnline) setStatus('Internet restored — resuming monitoring');
        lastNetworkOnline=true;
        // Content scripts cannot know their tab ID reliably: only the chosen tab is started via message.
        if (/captcha|session expired|please login|login again/i.test(pageText()) && !getCovForm()) { setStatus('Login/CAPTCHA attention required — automation paused'); chrome.runtime.sendMessage({type:'SARATHI_WARNING',sessionId:eventSession,kind:'SESSION',message:'Sarathi session/login or CAPTCHA requires attention'}).catch(()=>{}); await chrome.storage.local.set({running:false}); break; }
        if (noSlotsTextPresent()) {
          await handleNoSlotsPopup(generation);
          if (!(await waitWhileRunning(250))) break;
          continue;
        }

        const cov = getCovForm();
        if (!cov) {
          await waitWhileRunning(500,generation);
          continue;
        }

        setStatus(`COV page detected — waiting 8 seconds before next submission`);
        if (!(await waitWhileRunning(WAIT_MS,generation))) break;
        if (noSlotsTextPresent()) continue;
        if (calendarLocked || bookingPaused || realCalendar()) { await inspectBookingPage(); if(!await waitWhileRunning(500,generation))break; continue; }
        const current = getCovForm();
        if (!current) continue;

        try {
          // Native .click() runs the website's own onclick=getValueUsingClass() handler.
          if(!await actionAllowed('COV_SELECT'))break;
          for (const checkbox of current.selected) {
            if(!await isRunning(generation))break;
            if(!checkbox.checked)checkbox.click();
            await sleep(150);
          }
        } catch (e) {
          setStatus('Checkbox interaction failed: ' + String(e));
          await waitWhileRunning(1000,generation);
          continue;
        }

        await sleep(300);
        if (!current.selected.length || current.selected.some(el => !el.isConnected || !el.checked)) {
          setStatus('Vehicle class verification failed — not submitting; retrying check');
          await waitWhileRunning(1000,generation);
          continue;
        }
        if (!(await isRunning(generation)) || bookingPaused || calendarLocked || realCalendar()) { await inspectBookingPage(); break; }
        if (current.proceed.form !== current.form || current.proceed.id !== 'prcdbook') continue;

        // Background verifies session/tab and gates the next PROCEED before counting.
        if(!await actionAllowed('PROCEED'))break;
        if(!(await isRunning(generation)) || !current.proceed.isConnected || !visible(current.proceed))break;
        const saved = await chrome.storage.local.get({attempts:0,attemptDay:'',sessionAttempts:0,sessionCounterId:''});
        const now=new Date(),today=dayKey();
        cycle=saved.attemptDay===today?(Number(saved.attempts)||0):0;
        if(now.getHours()>=9 && now.getHours()<21) cycle++;
        if(!await isRunning(generation) || !await actionAllowed('PROCEED_CONFIRM') || !await isRunning(generation))break;
        if(current.proceed.disabled || !current.proceed.isConnected || !visible(current.proceed) || current.selected.some(el=>!el.checked||el.disabled||!el.isConnected))continue;
        current.proceed.click();
        // Count dispatched clicks, not reservations; navigation may still reject a submission.
        await chrome.storage.local.set({attempts:cycle,attemptDay:today,lastAttemptAt:Date.now(),sessionCounterId:sessionId,sessionAttempts:(saved.sessionCounterId===sessionId?(Number(saved.sessionAttempts)||0):0)+1});
        setStatus(`Proceed click dispatched (attempt ${cycle})`);
        lastLoopProgress=Date.now();

        // Watch promptly for the server's popup; navigation may destroy this script,
        // in which case the persistent running flag lets the next page resume.
        const responseEnd = Date.now() + 12000;
        while (Date.now() < responseEnd && await isRunning(generation)) {
          if (noSlotsTextPresent()) {
            if(!await isRunning(generation))break;
            await handleNoSlotsPopup(generation);
            break;
          }
          if (!getCovForm()) break;
          await sleep(350);
        }
        await waitWhileRunning(250,generation);
      }
    } catch(error) {
      if(generation===loopGeneration && ownsTab && cachedRunning) setStatus('Monitoring loop error — awaiting safe recovery: '+String(error.message||error).slice(0,110));
    } finally {
      if(generation===loopGeneration)loopActive=false;
      // Preserve diagnostic stop reasons (SMS, session expiry, permission warnings).
      if (!(await isRunning()) && cachedRunning && !localStop) setStatus('Monitoring interrupted');
    }
  }

  chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
    if (message?.type === 'SARATHI_HEALTH_PING') {
      (async()=>{
        const owner=await confirmOwnership();
        return {ok:true,page:location.pathname,owner,loopActive,
          lastLoopProgress,loopStartedAt,covPresent:!!getCovForm(),calendar:realCalendar(),
          popup:noSlotsTextPresent(),sessionId};
      })().then(sendResponse).catch(()=>sendResponse({ok:false}));return true;
    }
    if (message?.type === 'SARATHI_START' || message?.type === 'SARATHI_RECOVER') {
      (async () => {
        // Never allow a broadcast or an unrelated Sarathi tab to start monitoring.
        if (!await confirmOwnership()) return {ok:false,error:'Not the monitored Sarathi tab'};
        if(message.sessionId && message.sessionId!==sessionId)return {ok:false,error:'Stale monitoring session'};
        const d=await chrome.storage.local.get({running:false,calendarLock:false,sessionLock:false});
        cachedRunning=!!d.running;
        if(!cachedRunning) return {ok:false,error:'Monitoring is stopped'};
        if(message.type==='SARATHI_START') chrome.storage.local.set({successWatch:true}).catch(()=>{});
        calendarLocked=!!d.calendarLock;sessionLocked=!!d.sessionLock;bookingPaused=calendarLocked;
        localStop=false;
        // If a loop has made no progress for 90s on the COV page, invalidate that generation.
        if(message.type==='SARATHI_RECOVER'&&loopActive&&Date.now()-lastLoopProgress>90000&&getCovForm()&&!calendarLocked){
          loopGeneration++;loopActive=false;
        }
        if(!loopActive&&!calendarLocked&&!sessionLocked){setStatus(message.type==='SARATHI_RECOVER'?'Monitor loop recovered — waiting for COV':'Start received; searching for COV form');processLoop();}
        return {ok:true,status:loopActive?'Already monitoring':'Monitor ready'};
      })().then(sendResponse).catch(e=>sendResponse({ok:false,error:String(e.message||e)}));
      return true;
    }
    if (message?.type === 'SARATHI_STOP') {
      localStop = true; cachedRunning=false;loopGeneration++;loopActive=false;
      sendResponse({ok:true,status:'Stop received'}); return false;
    }
  });

  chrome.storage.onChanged.addListener((changes, area) => {
    if(area!=='local')return;
    if(changes.monitoredTabId||changes.monitorSessionId){
      ownsTab=false;sessionId='';localStop=true;loopGeneration++;loopActive=false;
      if(!changes.running)confirmOwnership().then(ok=>{if(ok&&cachedRunning){localStop=false;processLoop();}}).catch(()=>{});
    }
    if(!changes.running)return;
    cachedRunning=changes.running.newValue === true;
    if (cachedRunning) {
      confirmOwnership().then(authorized => {
        if(!authorized)return;
        chrome.storage.local.get({calendarLock:false,sessionLock:false}).then(d=>{
          calendarLocked=!!d.calendarLock;sessionLocked=!!d.sessionLock;
          bookingPaused=calendarLocked;
          localStop=false;
          if(!calendarLocked&&!sessionLocked)processLoop();
        }).catch(()=>{});
      });
    } else {
      localStop = true;loopGeneration++;loopActive=false;
      // UI/owner stores the specific stop reason. Do not overwrite it here.
    }
  });

  // DOM changes can reveal the error popup between polling intervals.
  try {
    let inspectionScheduled=false;
    const observer = new MutationObserver(() => {
      textCacheAt=-Infinity;
      if (!ownsTab || inspectionScheduled) return;
      inspectionScheduled=true;
      setTimeout(() => {
        inspectionScheduled=false;
        if(!ownsTab) return;
        inspectPage().catch(()=>{});
        if(!loopActive && cachedRunning && noSlotsTextPresent()) processLoop();
      }, 500);
    });
    // Ignore broad attribute changes: they are noisy and often unrelated to slots.
    observer.observe(document.documentElement, { childList: true, subtree: true });
  } catch (_) {}

  chrome.storage.local.get({ running:false,calendarLock:false,sessionLock:false }).then(async data => {
    cachedRunning=!!data.running;
    calendarLocked=!!data.calendarLock;sessionLocked=!!data.sessionLock;bookingPaused=calendarLocked;
    if(await confirmOwnership() && cachedRunning && !calendarLocked && !sessionLocked){localStop=false;processLoop();}
  }).catch(() => {});
})();
