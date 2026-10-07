package com.sarathi.anantnag;

import android.webkit.WebView;

public final class SlotMonitor {
    public interface Listener {
        void onCheck();
        void onServerUnavailable();
        void onServerAvailable();
        void onSlotFound(String detail);
    }

    private final WebView web;
    private final Listener listener;

    public SlotMonitor(WebView web, Listener listener) {
        this.web = web;
        this.listener = listener;
    }

    public void check() {
        listener.onCheck();
        web.evaluateJavascript(JS, null);
    }

    /*
     * IMPORTANT:
     * This monitor is deliberately PASSIVE. It never clicks buttons, changes
     * dates, submits forms, or calls /slots/ endpoints itself. The user must
     * navigate through Sarathi's normal portal flow. We only inspect the
     * already-loaded page DOM for an LMV availability indication.
     */
    private static final String JS =
        "(function(){"
        + "function n(x){return String(x||'').toLowerCase().replace(/\\s+/g,' ').trim();}"
        + "var txt=n(document.body&&document.body.innerText);"
        + "if(/ssl1001/.test(txt)||(/slot booking is allowed/.test(txt)&&/sarathi portal/.test(txt))){window.SarathiAndroid&&window.SarathiAndroid.portalFlowRequired();return;}"
        + "if(/\\b503\\b/.test(txt)&&/service unavailable/.test(txt)){window.SarathiAndroid&&window.SarathiAndroid.serverUnavailable();return;}"
        + "window.SarathiAndroid&&window.SarathiAndroid.serverAvailable();"
        + "var rows=[].slice.call(document.querySelectorAll('tr'));"
        + "for(var i=0;i<rows.length;i++){"
        + " var t=n(rows[i].innerText||rows[i].textContent);"
        + " if(/light motor vehicle|\\blmv\\b/.test(t)&&/(available|quota|slot)/.test(t)){"
        + "   var nums=t.match(/(?:available\\s*quota|quota|available)[^0-9]{0,30}(\\d+)/);"
        + "   if(nums&&parseInt(nums[1],10)>0){window.SarathiAndroid&&window.SarathiAndroid.slotFound('LMV availability detected: '+t.slice(0,240));return;}"
        + " }"
        + "}"
        + "var bodyHasLMV=/light motor vehicle|\\blmv\\b/.test(txt);"
        + "var bodyHasAvailable=/(available quota|slot available|available slots)/.test(txt);"
        + "if(bodyHasLMV&&bodyHasAvailable){window.SarathiAndroid&&window.SarathiAndroid.slotFound('LMV availability text detected on the current Sarathi page.');}"
        + "})();";

}