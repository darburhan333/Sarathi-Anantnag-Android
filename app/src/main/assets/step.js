function (opts) {
  try {
    var norm = function (s) { return String(s || '').replace(/\s+/g, ' ').trim(); };
    // Works for a WebView that is not on screen: walks computed styles, and only uses
    // layout size when the page reports a real viewport.
    var shown = function (el) {
      if (!el || !el.isConnected) return false;
      for (var n = el; n && n.nodeType === 1; n = n.parentElement) {
        var st = getComputedStyle(n);
        if (st.display === 'none' || st.visibility === 'hidden') return false;
      }
      if (window.innerWidth > 0) {
        var r = el.getBoundingClientRect();
        if (r.width <= 0 || r.height <= 0) return false;
      }
      return true;
    };
    var all = function (sel, root) { return Array.prototype.slice.call((root || document).querySelectorAll(sel)); };
    var text = norm((document.body && document.body.innerText) || '');
    var out = { state: 'UNKNOWN', acted: '', greens: '', path: location.pathname };

    // 1. Sarathi's session-flow error (same text the Chrome extension watches).
    if (/your session flow was interrupted or has expired\.? please return to the slot booking home page to start again\.?/i.test(text)) {
      out.state = 'EXPIRED';
      return out;
    }

    // 2. Booking calendar (read only: this app never clicks a date or BOOK SLOT).
    var root = document.querySelector('#calview');
    var cells = all('td.cal_green, td.cal_blue, td.cal_gray, td.cal_red, .cal_green', root || document).filter(shown);
    var calPath = /\/slots\/fetchdlt(?:prev|next)mnth\.do$/i.test(location.pathname);
    if (((root && shown(root)) || calPath) && cells.some(function (el) { return /^\d{1,2}$/.test(norm(el.textContent)); })) {
      out.greens = all('#calview .cal_green, td.cal_green').filter(shown)
        .map(function (x) { return norm(x.textContent); })
        .filter(function (t) { return /^\d{1,2}$/.test(t); }).join(',');
      out.state = 'CALENDAR';
      return out;
    }

    // 3. "No slots" popup: click only the popup's own Back button.
    var dialog = document.getElementById('dialog');
    var pb = dialog && dialog.querySelector('button.fadeandscale_close');
    if (pb && shown(pb)) {
      var noSlots = /slots\s+are\s+not\s+available\s+for\s+the\s+next\s+\d+\s+days/i;
      if (noSlots.test(norm(dialog.innerText || dialog.textContent)) || noSlots.test(text)) {
        out.state = 'NOSLOTS';
        if (/^back$/i.test(norm(pb.innerText || pb.textContent))) { pb.click(); out.acted = 'back'; }
        return out;
      }
    }

    // 4. Class-of-vehicle page: select LMV/MCWG and press Proceed, only when native says it is due.
    var form = document.getElementById('dispdlapcntdetform');
    var proceed = document.getElementById('prcdbook');
    if (form && proceed && proceed.form === form && shown(proceed)) {
      var sel = all('input[type="checkbox"][name="iscov"]', form)
        .filter(function (el) { return (el.value === '4' || el.value === '3') && !el.disabled && shown(el); });
      if (sel.length) {
        out.state = 'COV';
        if (opts && opts.allowProceed) {
          var pending = sel.filter(function (el) { return !el.checked; });
          if (pending.length) {
            pending.forEach(function (el) { el.click(); });
            out.acted = 'select';
            return out;
          }
          if (sel.every(function (el) { return el.checked && el.isConnected; }) && !proceed.disabled) {
            proceed.click();
            out.acted = 'proceed';
            return out;
          }
        }
        return out;
      }
    }

    // 5. A booking/quota page without a recognised calendar.
    if (/available\s+quota/i.test(text) && /\b(?:LMV|MCWG)\b/i.test(text)) {
      out.state = 'QUOTA';
      return out;
    }

    // 6. Login / CAPTCHA wording.
    if (/captcha|session expired|please login|login again/i.test(text)) {
      out.state = 'LOGIN';
      return out;
    }
    return out;
  } catch (e) {
    return { state: 'ERROR', acted: '', greens: '', path: '', error: String(e && e.message || e).slice(0, 80) };
  }
}
