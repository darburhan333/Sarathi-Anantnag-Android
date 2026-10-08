const DEFAULTS = {
  enabled: true,
  lastStatus: "Waiting for Sarathi appointment page",
  lastChecked: "",
  lastAvailable: false
};

chrome.runtime.onInstalled.addListener(async () => {
  const current = await chrome.storage.local.get(DEFAULTS);
  await chrome.storage.local.set(current);
  chrome.alarms.create("sarathi-status", { periodInMinutes: 1 });
});

chrome.alarms.onAlarm.addListener(async (alarm) => {
  if (alarm.name !== "sarathi-status") return;
  const tabs = await chrome.tabs.query({url: ["https://sarathi.parivahan.gov.in/*"]});
  await chrome.storage.local.set({
    openSarathiTabs: tabs.length,
    backgroundMonitor: "Running"
  });
});

chrome.runtime.onMessage.addListener((message) => {
  if (!message || message.type !== "SLOT_RESULT") return;
  chrome.storage.local.set({
    lastStatus: message.status || "No LMV availability detected",
    lastChecked: new Date().toISOString(),
    lastAvailable: !!message.available
  });
});