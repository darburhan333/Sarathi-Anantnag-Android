(() => {
  // Automatic visible-page monitor. It only clicks controls that are visibly
  // rendered by Sarathi; it never calls /slots/ directly and never fills data.
  const CLICK_DELAY = 3500;
  const SCAN_DELAY = 2500;
  let busy = false;
  let lastAction = 0;
  let found = false;

  const textOf = el => (el?.innerText || el?.textContent || "").replace(/\s+/g, " ").trim();
  const visible = el => {
    if (!el) return false;
    const r = el.getBoundingClientRect();
    const s = getComputedStyle(el);
    return r.width > 0 && r.height > 0 && s.display !== "none" &&
           s.visibility !== "hidden" && s.opacity !== "0";
  };

  function sarathiPage() {
    const t = (document.body?.innerText || "").toLowerCase();
    return t.includes("sarathi") || t.includes("parivahan");
  }

  function setState(status, slot = false) {
    chrome.runtime.sendMessage({
      type: "AUTOMONITOR_STATE",
      status,
      slot,
      url: location.href
    });
    if (slot) {
      found = true;
      document.title = "SLOT AVAILABLE — Sarathi LMV";
    }
  }

  function isGreen(el) {
    const s = getComputedStyle(el);
    const c = (s.backgroundColor + " " + s.color + " " + s.borderColor).toLowerCase();
    return /rgb\(\s*([0-9]{1,3})[ ,]+([0-9]{1,3})[ ,]+([0-9]{1,3})\s*\)/.test(c)
      ? /rgb\(\s*(?:0|[1-9]\d?)\s*,\s*(?:1[2-9]\d|[2-9]\d{2})\s*,\s*(?:0|[1-9]\d?)\s*\)/.test(c)
      : /green|#0[0-9a-f]{2,6}|#1[0-9a-f]{2,6}|#2[0-9a-f]{2,6}/i.test(c);
  }

  function clickElement(el, reason) {
    if (!visible(el) || busy || Date.now() - lastAction < CLICK_DELAY) return false;
    busy = true;
    lastAction = Date.now();
    el.scrollIntoView({block: "center", inline: "center"});
    setState("Clicking " + reason + "…");
    setTimeout(() => {
      if (!found) el.click();
      busy = false;
    }, 250);
    return true;
  }

  function slotAvailable() {
    const t = (document.body?.innerText || "").replace(/\s+/g, " ").trim();
    const lower = t.toLowerCase();

    if (/ssl1001|slot booking is allowed from sarathi portal only/i.test(t)) return false;

    // Strong indicators first: LMV + an available count/status.
    return /\blmv\b/i.test(t) &&
      (
        /lmv\s*[:\-]?\s*1\s*(?:slot|available)/i.test(t) ||
        /1\s*(?:slot|seat)\s*(?:available|open)/i.test(t) ||
        /lmv[^.]{0,80}(?:1|one)\s*(?:slot|available|open)/i.test(t) ||
        /available\s*[:\-]?\s*1\b/i.test(t)
      );
  }

  function findLMVControl() {
    const els = [...document.querySelectorAll("button, a, input[type=button], input[type=submit], label, td, span")];
    return els.find(el => visible(el) && /^\s*LMV\s*$/i.test(textOf(el)));
  }

  function findContinue() {
    const els = [...document.querySelectorAll("button, a, input[type=button], input[type=submit]")];
    return els.find(el => visible(el) && /^(continue|next|proceed)$/i.test(textOf(el)));
  }

  function clickGreenWedThu() {
    const dayWords = /\b(?:wed(?:nesday)?|thu(?:rsday)?)\b/i;
    const candidates = [...document.querySelectorAll(
      "button, a, td, span, div, input[type=button]"
    )].filter(visible);

    const green = candidates.filter(el => isGreen(el));
    for (const el of green) {
      const t = textOf(el);
      if (dayWords.test(t)) {
        return clickElement(el, "green Wednesday/Thursday date");
      }
    }

    // If dates are represented by numbered green cells, use the nearest
    // visible weekday header and its associated green date cells.
    const headers = candidates.filter(el => /wed(?:nesday)?|thu(?:rsday)?/i.test(textOf(el)));
    for (const h of headers) {
      const box = h.getBoundingClientRect();
      const nearby = green.filter(el => {
        const r = el.getBoundingClientRect();
        return Math.abs(r.top - box.top) < 90 && Math.abs(r.left - box.left) < 500;
      });
      if (nearby.length) return clickElement(nearby[0], "green Wednesday/Thursday date");
    }
    return false;
  }

  function step() {
    if (!sarathiPage() || found) return;

    if (slotAvailable()) {
      setState("LMV SLOT AVAILABLE — take over and book it now.", true);
      return;
    }

    const body = (document.body?.innerText || "").toLowerCase();

    if (/select.*(?:class|vehicle)|class.*vehicle|service.*on.*driving/i.test(body)) {
      const lmv = findLMVControl();
      if (lmv) {
        clickElement(lmv, "LMV");
        return;
      }
    }

    if (/appointment|slot|date|calendar/i.test(body)) {
      if (clickGreenWedThu()) return;

      const next = findContinue();
      if (next) {
        clickElement(next, "Continue");
        return;
      }
    }

    setState("Monitoring Sarathi appointment page…");
  }

  const observer = new MutationObserver(() => {
    clearTimeout(observer.timer);
    observer.timer = setTimeout(step, SCAN_DELAY);
  });

  observer.observe(document.documentElement, {
    subtree: true, childList: true, characterData: true
  });

  setInterval(step, 4000);
  step();
})();