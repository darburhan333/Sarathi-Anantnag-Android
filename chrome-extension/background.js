const DEFAULTS = {
  enabled: true,
  lastStatus: "Waiting for Sarathi appointment page",
  lastChecked: "",
  lastAvailable: false
};

chrome.runtime.onInstalled.addListener(async () => {
  const current = await chrome.storage.local.get(DEFAULTS);
  await chrome.storage.local.set(current);
  chrome.action.setBadgeBackgroundColor({color: "#188038"});
  chrome.action.setBadgeText({text: ""});
});

chrome.runtime.onMessage.addListener((message) => {
  if (!message || message.type !== "AUTOMONITOR_STATE") return;

  const slot = !!message.slot;
  chrome.storage.local.set({
    lastStatus: message.status || "Monitoring Sarathi appointment page",
    lastChecked: new Date().toISOString(),
    lastAvailable: slot,
    lastUrl: message.url || ""
  });

  chrome.action.setBadgeText({text: slot ? "SLOT" : ""});
  if (slot) {
    chrome.action.setTitle({title: "Sarathi LMV: SLOT AVAILABLE — switch to the Sarathi tab"});
  } else {
    chrome.action.setTitle({title: "Sarathi LMV Monitor running"});
  }
});