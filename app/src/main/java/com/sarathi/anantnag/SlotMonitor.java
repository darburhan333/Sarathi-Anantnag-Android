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

    private static final String JS =
        "(function(){"
        + "function n(x){return String(x||'').toLowerCase().replace(/\\s+/g,' ').trim();}"
        + "function fire(e){try{e.scrollIntoView({block:'center'});e.click();return true}catch(x){}return false;}"
        + "function row(e){var r=e&&e.closest&&e.closest('tr');return n(r?r.innerText:(e&&e.parentElement?e.parentElement.innerText:''));}"
        + "function lmv(){var a=[].slice.call(document.querySelectorAll('input[type=checkbox]'));for(var i=0;i<a.length;i++){var t=row(a[i]);if(t.indexOf('light motor vehicle')>=0||/\\blmv\\b/.test(t))return a[i]}return null;}"
        + "function green(e){var x=e;for(var i=0;i<3&&x;i++,x=x.parentElement){var s=getComputedStyle(x),c=s.backgroundColor+','+s.color;if(/green|available/.test(n(x.className)+' '+c))return true;}return false;}"
        + "function dates(){var a=[];var cells=[].slice.call(document.querySelectorAll('td'));for(var i=0;i<cells.length;i++){var t=n(cells[i].innerText||cells[i].textContent);if(/^\\d{1,2}$/.test(t)&&green(cells[i]))a.push(cells[i].querySelector('a,button,span')||cells[i]);}return a;}"
        + "function lmvOne(){var rs=[].slice.call(document.querySelectorAll('tr'));for(var i=0;i<rs.length;i++){var t=n(rs[i].innerText||rs[i].textContent);if(/\\blmv\\b/.test(t)&&/(^|\\s)1(\\s|$)/.test(t))return true;}return false;}"
        + "var txt=n(document.body&&document.body.innerText);"
        + "if(/ssl1001/.test(txt)||(/slot booking is allowed/.test(txt)&&/sarathi portal/.test(txt))){return;}"
        + "if(/\\b503\\b/.test(txt)&&/service unavailable/.test(txt)){window.SarathiAndroid&&window.SarathiAndroid.serverUnavailable();return;}"
        + "window.SarathiAndroid&&window.SarathiAndroid.serverAvailable();"
        + "if(txt.indexOf('select covs')>=0){var c=lmv();if(c&&!c.checked){fire(c);try{c.dispatchEvent(new Event('change',{bubbles:true}))}catch(x){}}return;}"
        + "if(txt.indexOf('calendar indicator')>=0&&txt.indexOf('available quota')>=0){var ds=dates();if(ds.length){var idx=window.__sarathiDateIndex||0;if(idx>=ds.length)idx=0;window.__sarathiDateIndex=idx+1;fire(ds[idx]);setTimeout(function(){if(lmvOne()){window.SarathiAndroid&&window.SarathiAndroid.slotFound('LMV quota is 1 on an available date.')}} ,1200);}}"
        + "})();";
}