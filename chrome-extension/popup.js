const enabled = document.getElementById("enabled");
const notifications = document.getElementById("notifications");
const status = document.getElementById("status");
const checked = document.getElementById("checked");

async function load() {
  const s = await chrome.storage.local.get({
    enabled: true,
    notifications: true,
    lastStatus: "Waiting for Sarathi appointment page",
    lastChecked: ""
  });
  enabled.checked = s.enabled;
  notifications.checked = s.notifications;
  status.textContent = s.lastStatus;
  checked.textContent = s.lastChecked ? new Date(s.lastChecked).toLocaleString() : "—";
}

enabled.addEventListener("change", () => chrome.storage.local.set({enabled: enabled.checked}));
notifications.addEventListener("change", () => chrome.storage.local.set({notifications: notifications.checked}));
document.getElementById("open").addEventListener("click", () => chrome.tabs.create({url:"https://sarathi.parivahan.gov.in/"}));
chrome.storage.onChanged.addListener(load);
load();