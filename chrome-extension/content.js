(() => {
  const POSITIVE = [
    /slot(?:s)?\s+(?:is|are)?\s*(?:available|open)/i,
    /appointment(?:s)?\s+(?:is|are)?\s*(?:available|open)/i,
    /available\s+slot/i,
    /book(?:ing)?\s+(?:is\s+)?available/i,
    /select\s+slot/i,
    /choose\s+slot/i
  ];

  const NEGATIVE = [
    /ssl1001/i,
    /slot booking is allowed from sarathi portal only/i,
    /service unavailable/i,
    /temporarily unavailable/i,
    /no slots? available/i,
    /no appointment(?:s)? available/i
  ];

  let lastFingerprint = "";
  let timer = null;

  function inspect() {
    chrome.storage.local.get({enabled: true}, settings => {
      if (!settings.enabled) return;

      const text = (document.body?.innerText || "").replace(/\s+/g, " ").trim();
      if (!text) return;

      const lower = text.toLowerCase();
      if (!lower.includes("sarathi") && !lower.includes("parivahan")) return;

      const fingerprint = text.slice(0, 20000);
      if (fingerprint === lastFingerprint) return;
      lastFingerprint = fingerprint;

      const ssl1001 = /ssl1001/i.test(text);
      const negative = NEGATIVE.some(r => r.test(text));
      const lmv = /\bLMV\b/i.test(text) || /light motor vehicle/i.test(text);
      const available = !ssl1001 && !negative && lmv && POSITIVE.some(r => r.test(text));

      let status;
      if (ssl1001) {
        status = "Sarathi returned SSL1001/503; waiting for the normal portal page";
      } else if (available) {
        status = "LMV SLOT APPEARS AVAILABLE — check this Sarathi tab";
      } else if (lmv) {
        status = "LMV appointment page detected; no slot detected";
      } else {
        status = "Sarathi page detected; waiting for LMV appointment information";
      }

      chrome.runtime.sendMessage({type: "SLOT_RESULT", available, status});
    });
  }

  const observer = new MutationObserver(() => {
    clearTimeout(timer);
    timer = setTimeout(inspect, 1000);
  });

  observer.observe(document.documentElement, {
    subtree: true, childList: true, characterData: true
  });

  inspect();
  setInterval(inspect, 15000);
})();