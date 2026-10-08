const DEFAULTS = {
  enabled: true,
  notifications: true,
  lastStatus: "Waiting for Sarathi appointment page",
  lastChecked: ""
};

chrome.runtime.onInstalled.addListener(async () => {
  const current = await chrome.storage.local.get(DEFAULTS);
  await chrome.storage.local.set(current);
});

chrome.runtime.onMessage.addListener((message) => {
  if (!message || message.type !== "SLOT_RESULT") return;

  chrome.storage.local.set({
    lastStatus: message.status || "No LMV availability detected",
    lastChecked: new Date().toISOString()
  });

  if (message.available) {
    chrome.storage.local.get({ notifications: true }, settings => {
      if (!settings.notifications) return;
      chrome.notifications.create("sarathi-lmv-" + Date.now(), {
        type: "basic",
        title: "Sarathi LMV slot available",
        message: "An LMV appointment appears available on the Anantnag Sarathi page. Open Chrome and verify it now.",
        priority: 2
      });
    });
  }
});