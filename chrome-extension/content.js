(() => {
  const POSITIVE_PATTERNS = [
    /slot(?:s)?\s+(?:is|are)?\s*(?:available|open)/i,
    /appointment(?:s)?\s+(?:is|are)?\s*(?:available|open)/i,
    /available\s+slot/i,
    /book(?:ing)?\s+(?:is\s+)?available/i,
    /select\s+slot/i,
    /choose\s+slot/i
  ];

  const NEGATIVE_PATTERNS = [
    /ssl1001/i,
    /slot booking is allowed from sarathi portal only/i,
    /service unavailable/i,
    /temporarily unavailable/i,
    /no slots? available/i,
    /no appointment(?:s)? available/i
  ];

  let lastFingerprint = "";

  function pageText() {
    return (document.body?.innerText || "").replace(/\s+/g, " ").trim();
  }

  function inspect() {
    chrome.storage.local.get({enabled: true}, settings => {
      if (!settings.enabled) return;
      const text = pageText();
    if (!text) return;

    const lower = text.toLowerCase();
    if (!lower.includes("sarathi") && !lower.includes("parivahan")) return;

    const fingerprint = text.slice(0, 12000);
    if (fingerprint === lastFingerprint) return;
    lastFingerprint = fingerprint;

    const ssl1001 = /ssl1001/i.test(text);
    const negative = NEGATIVE_PATTERNS.some(r => r.test(text));
    const lmv = /\bLMV\b/i.test(text) || /light motor vehicle/i.test(text);
    const available = !ssl1001 && !negative && lmv &&
      POSITIVE_PATTERNS.some(r => r.test(text));

    let status;
    if (ssl1001) {
      status = "SSL1001 portal-flow page detected; no availability decision made";
    } else if (available) {
      status = "LMV availability appears present — verify manually";
    } else if (lmv) {
      status = "LMV page detected; no positive availability found";
    } else {
      status = "Sarathi page detected; waiting for LMV appointment information";
    }

      chrome.runtime.sendMessage({type: "SLOT_RESULT", available, status});
    });
  }

  const observer = new MutationObserver(() => {
    clearTimeout(inspect.timer);
    inspect.timer = setTimeout(inspect, 700);
  });

  observer.observe(document.documentElement, {
    subtree: true, childList: true, characterData: true
  });

  inspect();
  setInterval(inspect, 10000);
})();