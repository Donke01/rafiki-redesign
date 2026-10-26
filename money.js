/* RAFIKI money: shows every amount in the visitor's own currency.
   - Starts in the currency of the country Cloudflare sees the visitor in (USA → USD, Kenya → KES, Uganda → UGX …),
     else the phone's or browser's region; the visitor's own pick is remembered on this device.
   - Amounts stay in the currency they were really set or spent in ($ for gifts and wells, KES for repairs). Converted
     ones show "≈" and keep the original next to them: "≈ KES 645,000 ($5,000)". Short spots (buttons, big numbers)
     show only "≈ KES 645,000", with the original in the tooltip.
   - Rates: /api/fx (refreshed daily), kept on this device for 12 hours; rough built-in rates until then.
   - Pages need nothing special: written amounts ("$5,000", "KES 12,000") are found and converted, also after the page
     changes them. Put data-nofx on anything that must stay as written. A page can set
     <html data-cur-default="KES">, <html data-cur-pill="off"> (no header button) and
     <html data-cur-orig="always"> (always show the original next to a converted amount, as in RAFIKI Pin).
   - window.RFX: cur(), set(code), list(), fmt(amount, from), say(text), text(text), open(), on(fn). */
(function () {
  'use strict';
  if (window.RFX) return;

  var CUR = {
    USD: { n: 'US dollar', p: 'US dollars', cc: 'us', sym: '$', step: 1 },
    KES: { n: 'Kenyan shilling', p: 'Kenyan shillings', cc: 'ke', sym: 'KES ', step: 10 },
    UGX: { n: 'Ugandan shilling', p: 'Ugandan shillings', cc: 'ug', sym: 'UGX ', step: 1000 },
    TZS: { n: 'Tanzanian shilling', p: 'Tanzanian shillings', cc: 'tz', sym: 'TZS ', step: 1000 },
    MWK: { n: 'Malawian kwacha', p: 'Malawian kwacha', cc: 'mw', sym: 'MWK ', step: 100 },
    GBP: { n: 'British pound', p: 'British pounds', cc: 'gb', sym: '£', step: 1 },
    EUR: { n: 'Euro', p: 'euros', cc: 'eu', sym: '€', step: 1 },
    CAD: { n: 'Canadian dollar', p: 'Canadian dollars', cc: 'ca', sym: 'CA$', step: 1 },
    AUD: { n: 'Australian dollar', p: 'Australian dollars', cc: 'au', sym: 'A$', step: 1 }
  };
  var ORDER = ['USD', 'KES', 'UGX', 'TZS', 'MWK', 'GBP', 'EUR', 'CAD', 'AUD'];
  var ROUGH = { USD: 1, KES: 129, UGX: 3650, TZS: 2600, MWK: 1735, GBP: 0.77, EUR: 0.92, CAD: 1.38, AUD: 1.52 };
  var EURO = 'AT BE CY DE EE ES FI FR GR HR IE IT LT LU LV MT NL PT SI SK'.split(' ');
  function forCountry(c) {
    c = String(c || '').toUpperCase();
    var m = { US: 'USD', KE: 'KES', UG: 'UGX', TZ: 'TZS', MW: 'MWK', GB: 'GBP', CA: 'CAD', AU: 'AUD' };
    if (m[c]) return m[c];
    if (EURO.indexOf(c) >= 0) return 'EUR';
    return '';
  }
  function store(k, v) { try { if (v == null) localStorage.removeItem(k); else localStorage.setItem(k, v); } catch (e) {} }
  function load(k) { try { return localStorage.getItem(k); } catch (e) { return null; } }

  var html = document.documentElement;
  var fx = { rates: ROUGH, at: '', fallback: true, country: '' };
  try { var kept = JSON.parse(load('rfFx') || 'null'); if (kept && kept.rates) fx = kept; } catch (e) {}
  function region() {
    var l = (navigator.languages && navigator.languages[0]) || navigator.language || '';
    var m = /[-_]([A-Za-z]{2})$/.exec(l);
    return m ? m[1] : '';
  }
  function startCur() {
    var own = load('rfCur');
    if (own && CUR[own]) return own;
    if (html.getAttribute('data-cur-default') && CUR[html.getAttribute('data-cur-default')]) return html.getAttribute('data-cur-default');
    return forCountry(fx.country) || forCountry(region()) || 'USD';
  }
  var cur = startCur();
  var picked = !!(load('rfCur') && CUR[load('rfCur')]);

  // ---- numbers ----
  function rate(c) { return Number(fx.rates[c]) || ROUGH[c] || 1; }
  function convert(v, from, to) { return v / rate(from) * rate(to); }
  function roundFor(v, to) {
    var st = CUR[to].step;
    if (st > 1 && v < st * 10) st = Math.max(1, st / 10);
    if (v < 1 && to !== 'UGX' && to !== 'TZS') return Math.round(v * 100) / 100;
    return Math.round(v / st) * st;
  }
  function group(v) {
    var d = v < 1 && v > 0 ? 2 : 0;
    return v.toLocaleString('en-US', { minimumFractionDigits: d, maximumFractionDigits: d });
  }
  function short(v) {
    if (v >= 1e9) return (Math.round(v / 1e8) / 10) + 'B';
    if (v >= 1e6) return (Math.round(v / 1e5) / 10) + 'M';
    return group(v);
  }
  /** "KES 645,000" for an amount written in [from]. */
  function fmt(v, from, opts) {
    opts = opts || {};
    var to = opts.to || cur;
    var x = from === to ? v : roundFor(convert(v, from, to), to);
    var num = opts.short && x >= 1e6 ? short(x) : group(x);
    return CUR[to].sym + num;
  }

  // ---- finding amounts in text ----
  // $5, $5,000, $4.50, US$ 20, $9k, $1.2 million · KES 12,000, KSh 500, Ksh. 1,200, KES 3.5M
  var RE = /(?:US\s?\$|\$)\s?(\d{1,3}(?:,\d{3})+|\d+)(\.\d+)?(\s?(?:k|K|M|million|billion)\b)?|\b(?:KES|KSh|Ksh|KSH)\.?\s?(\d{1,3}(?:,\d{3})+|\d+)(\.\d+)?(\s?(?:k|K|M|million)\b)?/g;
  function mult(s) {
    s = (s || '').trim().toLowerCase();
    return s === 'k' ? 1e3 : s === 'm' || s === 'million' ? 1e6 : s === 'billion' ? 1e9 : 1;
  }
  function parts(text) {
    var out = [], m;
    RE.lastIndex = 0;
    while ((m = RE.exec(text))) {
      var usd = m[1] != null;
      var v = parseFloat((usd ? m[1] : m[4]).replace(/,/g, '') + ((usd ? m[2] : m[5]) || '')) * mult(usd ? m[3] : m[6]);
      if (!isFinite(v)) continue;
      out.push({ i: m.index, len: m[0].length, raw: m[0], v: v, from: usd ? 'USD' : 'KES' });
    }
    return out;
  }
  /** The text with each amount shown in the chosen currency. */
  function convertText(text, tight) {
    var ps = parts(text);
    if (!ps.length) return text;
    var s = '', at = 0;
    ps.forEach(function (p) {
      s += text.slice(at, p.i);
      if (p.from === cur) s += p.raw;
      else s += '≈ ' + fmt(p.v, p.from, { short: tight }).replace(/ /g, ' ') + (tight ? '' : ' (' + p.raw + ')');
      at = p.i + p.len;
    });
    return s + text.slice(at);
  }
  /** For reading aloud: "about 645,000 Kenyan shillings". */
  function say(text) {
    var ps = parts(text);
    if (!ps.length) return text;
    var s = '', at = 0;
    ps.forEach(function (p) {
      s += text.slice(at, p.i);
      var x = p.from === cur ? p.v : roundFor(convert(p.v, p.from, cur), cur);
      s += (p.from === cur ? '' : 'about ') + group(x) + ' ' + (x === 1 ? CUR[cur].n : CUR[cur].p);
      at = p.i + p.len;
    });
    return s + text.slice(at);
  }

  // ---- converting the page ----
  var SKIP = /^(SCRIPT|STYLE|TEXTAREA|INPUT|SELECT|OPTION|CODE|PRE|NOSCRIPT|TEMPLATE|svg)$/i;
  var orig = new WeakMap(), shown = new WeakMap(), live = new Set(), writing = false;
  function skip(el) {
    for (var e = el; e && e !== document.body; e = e.parentNode) {
      if (e.nodeType !== 1) continue;
      if (SKIP.test(e.nodeName) || e.isContentEditable || e.hasAttribute('data-nofx') || (e.classList && e.classList.contains('rfx-ui'))) return true;
    }
    return false;
  }
  function isTight(node, text) {
    if (html.getAttribute('data-cur-orig') === 'always') return false;
    var el = node.parentNode;
    if (!el || el.nodeType !== 1) return false;
    if (el.closest && el.closest('button,.btn,.chip,.b,.xb,[role=button],[role=tab],[role=radio],.bignum,.stat b,.nums strong,strong,b,h1,h2,h3')) {
      return parts(text).length > 0 && text.trim().length <= 28;
    }
    return text.trim() === (parts(text)[0] || {}).raw;
  }
  function paint(node) {
    var o = orig.get(node);
    if (o == null) return;
    var want = convertText(o, isTight(node, o));
    if (node.nodeValue !== want) {
      writing = true; node.nodeValue = want; writing = false;
      shown.set(node, want);
      var el = node.parentNode;
      if (el && el.nodeType === 1) {
        if (want !== o) { if (!el.hasAttribute('data-rfx-title')) { el.setAttribute('data-rfx-title', el.getAttribute('title') || ''); } el.setAttribute('title', o.trim()); }
        else if (el.hasAttribute('data-rfx-title')) { var t0 = el.getAttribute('data-rfx-title'); if (t0) el.setAttribute('title', t0); else el.removeAttribute('title'); el.removeAttribute('data-rfx-title'); }
      }
    }
  }
  function look(node) {
    if (node.nodeType === 3) {
      var v = node.nodeValue;
      if (!v || v.length > 4000 || (!/\$|KES|KSh|Ksh|KSH/.test(v) && !orig.has(node))) return;
      if (!orig.has(node)) { if (!parts(v).length || skip(node.parentNode)) return; orig.set(node, v); live.add(node); }
      paint(node);
      return;
    }
    if (node.nodeType !== 1 || SKIP.test(node.nodeName) || node.hasAttribute('data-nofx')) return;
    var w = document.createTreeWalker(node, NodeFilter.SHOW_TEXT, null), n, list = [];
    while ((n = w.nextNode())) list.push(n);
    list.forEach(look);
  }
  function repaintAll() {
    live.forEach(function (n) { if (!n.isConnected) { live.delete(n); return; } paint(n); });
    if (mo) mo.takeRecords();
  }
  var mo = null;
  function watch() {
    if (mo || !window.MutationObserver) return;
    mo = new MutationObserver(function (recs) {
      if (writing) return;
      recs.forEach(function (r) {
        if (r.type === 'characterData') {
          var n = r.target;
          if (orig.has(n)) {
            if (n.nodeValue === shown.get(n)) return; // our own change
            orig.set(n, n.nodeValue); shown.delete(n);
          }
          look(n);
        } else {
          r.addedNodes.forEach(function (a) { if (!(a.classList && a.classList.contains('rfx-ui'))) look(a); });
        }
      });
      mo.takeRecords();
    });
    mo.observe(document.body, { childList: true, subtree: true, characterData: true });
  }

  // ---- the header button and the picker ----
  var css = '.rfx-pill{display:inline-flex;align-items:center;gap:6px;height:44px;padding:0 12px 0 10px;border-radius:999px;border:1px solid #D5DCE3;background:#FFFFFF;color:#04263F;font:700 14px/1 Montserrat,Inter,system-ui,sans-serif;cursor:pointer;white-space:nowrap;flex:none}' +
    '.rfx-pill:hover{background:#F2F7FB}.rfx-pill img{width:22px;height:15px;border-radius:3px;object-fit:cover;box-shadow:0 0 0 1px rgba(0,0,0,.08)}.rfx-pill svg{width:12px;height:12px}' +
    '.rfx-pill:focus-visible,.rfx-row:focus-visible,.rfx-x:focus-visible{outline:3px solid #0A78C2;outline-offset:2px}' +
    '@media (max-width:420px){.rfx-pill{padding:0 9px 0 8px;gap:4px;font-size:13px}.rfx-pill svg{display:none}}' +
    '@media (max-width:359px){.rfx-pill img{display:none}}' +
    '.rfx-bg{position:fixed;inset:0;z-index:99990;background:rgba(4,38,63,.45);display:flex;align-items:flex-end;justify-content:center;animation:rfxIn .2s ease both}' +
    '@media (min-width:640px){.rfx-bg{align-items:center}}' +
    '.rfx-sh{width:100%;max-width:460px;max-height:88vh;overflow:auto;background:#FFFFFF;color:#04263F;border-radius:28px 28px 0 0;padding:18px 16px 22px;box-shadow:0 -10px 40px rgba(4,38,63,.25);font-family:Montserrat,Inter,system-ui,sans-serif;animation:rfxUp .32s cubic-bezier(.2,.8,.2,1) both}' +
    '@media (min-width:640px){.rfx-sh{border-radius:28px;padding:22px 20px}}' +
    '.rfx-hd{display:flex;align-items:flex-start;gap:12px;margin:0 4px 12px}.rfx-hd h2{margin:0;font-size:22px;font-weight:800;letter-spacing:-.01em;flex:1}.rfx-hd p{margin:4px 0 0;color:#5B6670;font-size:14px;font-weight:500;line-height:1.4}' +
    '.rfx-x{width:44px;height:44px;border-radius:50%;border:0;background:#EEF2F5;color:#04263F;font-size:22px;line-height:1;cursor:pointer;flex:none}' +
    '.rfx-row{display:flex;align-items:center;gap:14px;width:100%;min-height:64px;padding:10px 14px;margin:0 0 6px;border:1px solid #E6EAEE;border-radius:18px;background:#FFFFFF;color:#04263F;text-align:left;cursor:pointer;font:inherit}' +
    '.rfx-row:hover{background:#F4F8FB}.rfx-row[aria-checked=true]{border-color:#0A78C2;background:#EEF6FC;box-shadow:inset 0 0 0 1px #0A78C2}' +
    '.rfx-row img{width:34px;height:23px;border-radius:4px;object-fit:cover;box-shadow:0 0 0 1px rgba(0,0,0,.08);flex:none}' +
    '.rfx-row b{display:block;font-size:16px;font-weight:700}.rfx-row small{display:block;color:#5B6670;font-size:13px;font-weight:500;margin-top:1px}.rfx-row .rfx-t{flex:1;min-width:0}' +
    '.rfx-row .rfx-code{font-weight:800;font-size:14px;color:#5B6670}.rfx-row[aria-checked=true] .rfx-code{color:#0A78C2}' +
    '.rfx-ft{margin:12px 6px 0;color:#5B6670;font-size:12.5px;line-height:1.45;text-align:center}' +
    '.rfx-note{display:block;margin:10px auto;max-width:560px;padding:10px 14px;border-radius:14px;background:#EEF6FC;color:#04263F;font:600 14px/1.45 Montserrat,Inter,system-ui,sans-serif;text-align:center}' +
    '.rfx-flash{animation:rfxFlash .7s ease}' +
    '@keyframes rfxIn{from{opacity:0}}@keyframes rfxUp{from{transform:translateY(40px);opacity:0}}@keyframes rfxFlash{0%{opacity:.25}100%{opacity:1}}' +
    '@media (prefers-reduced-motion:reduce){.rfx-bg,.rfx-sh,.rfx-flash{animation:none}}';
  function addCss() {
    if (document.getElementById('rfx-css')) return;
    var st = document.createElement('style'); st.id = 'rfx-css'; st.textContent = css; document.head.appendChild(st);
  }
  // small flags drawn here (no download, so they show with no signal too)
  var FLAG = {
    USD: '<rect width="30" height="20" fill="#B22234"/><path d="M0 2.3h30M0 5.4h30M0 8.5h30M0 11.6h30M0 14.7h30M0 17.8h30" stroke="#fff" stroke-width="1.55"/><rect width="13" height="10.8" fill="#3C3B6E"/><g fill="#fff"><circle cx="2.2" cy="2" r=".6"/><circle cx="5.2" cy="2" r=".6"/><circle cx="8.2" cy="2" r=".6"/><circle cx="11" cy="2" r=".6"/><circle cx="3.7" cy="4.2" r=".6"/><circle cx="6.7" cy="4.2" r=".6"/><circle cx="9.6" cy="4.2" r=".6"/><circle cx="2.2" cy="6.4" r=".6"/><circle cx="5.2" cy="6.4" r=".6"/><circle cx="8.2" cy="6.4" r=".6"/><circle cx="11" cy="6.4" r=".6"/><circle cx="3.7" cy="8.6" r=".6"/><circle cx="6.7" cy="8.6" r=".6"/><circle cx="9.6" cy="8.6" r=".6"/></g>',
    KES: '<rect width="30" height="20" fill="#006600"/><rect width="30" height="13.4" fill="#fff"/><rect width="30" height="6" fill="#000"/><rect y="7.2" width="30" height="5.6" fill="#BB0000"/><path d="M11 17 19 3M11 3l8 14" stroke="#fff" stroke-width=".8"/><ellipse cx="15" cy="10" rx="2.6" ry="5.2" fill="#BB0000" stroke="#fff" stroke-width=".7"/><path d="M15 5v10" stroke="#fff" stroke-width=".5"/>',
    UGX: '<rect width="30" height="20" fill="#000"/><rect y="3.33" width="30" height="3.33" fill="#FCDC04"/><rect y="6.67" width="30" height="3.33" fill="#D90000"/><rect y="13.33" width="30" height="3.33" fill="#FCDC04"/><rect y="16.67" width="30" height="3.33" fill="#D90000"/><circle cx="15" cy="10" r="3.6" fill="#fff"/><circle cx="15" cy="10" r="1.7" fill="#9CA69C"/>',
    TZS: '<rect width="30" height="20" fill="#00A3DD"/><path d="M0 0h30L0 20z" fill="#1EB53A"/><path d="M0 20 30 0" stroke="#FCD116" stroke-width="7"/><path d="M0 20 30 0" stroke="#000" stroke-width="4.6"/>',
    MWK: '<rect width="30" height="20" fill="#339E35"/><rect width="30" height="13.33" fill="#CE1126"/><rect width="30" height="6.67" fill="#000"/><path d="M8.5 6.6a6.5 6.5 0 0 1 13 0z" fill="#CE1126"/><g stroke="#CE1126" stroke-width=".9"><path d="M15 6.6V.8M11.5 6.6 8.8 1.6M18.5 6.6l2.7-5M9.2 6.6 5.3 3M20.8 6.6l3.9-3.6"/></g>',
    GBP: '<rect width="30" height="20" fill="#012169"/><path d="M0 0 30 20M30 0 0 20" stroke="#fff" stroke-width="4"/><path d="M0 0 30 20M30 0 0 20" stroke="#C8102E" stroke-width="1.4"/><path d="M15 0v20M0 10h30" stroke="#fff" stroke-width="6"/><path d="M15 0v20M0 10h30" stroke="#C8102E" stroke-width="3.4"/>',
    EUR: '<rect width="30" height="20" fill="#003399"/><g fill="#FFCC00"><circle cx="15" cy="4" r=".9"/><circle cx="18" cy="4.8" r=".9"/><circle cx="20.2" cy="7" r=".9"/><circle cx="21" cy="10" r=".9"/><circle cx="20.2" cy="13" r=".9"/><circle cx="18" cy="15.2" r=".9"/><circle cx="15" cy="16" r=".9"/><circle cx="12" cy="15.2" r=".9"/><circle cx="9.8" cy="13" r=".9"/><circle cx="9" cy="10" r=".9"/><circle cx="9.8" cy="7" r=".9"/><circle cx="12" cy="4.8" r=".9"/></g>',
    CAD: '<rect width="30" height="20" fill="#fff"/><rect width="7.5" height="20" fill="#D52B1E"/><rect x="22.5" width="7.5" height="20" fill="#D52B1E"/><path d="M15 3.5 16.2 6l1.7-.6-.6 3.6 2-1.6.5 1.4 2-.4-.8 2.2.9.5-3.4 2.6.4 1.4-3.3-.5V18h-.6v-3.4l-3.3.5.4-1.4-3.4-2.6.9-.5-.8-2.2 2 .4.5-1.4 2 1.6-.6-3.6 1.7.6z" fill="#D52B1E"/>',
    AUD: '<rect width="30" height="20" fill="#012169"/><g transform="scale(.5)"><path d="M0 0 30 20M30 0 0 20" stroke="#fff" stroke-width="4"/><path d="M0 0 30 20M30 0 0 20" stroke="#C8102E" stroke-width="1.4"/><path d="M15 0v20M0 10h30" stroke="#fff" stroke-width="6"/><path d="M15 0v20M0 10h30" stroke="#C8102E" stroke-width="3.4"/></g><g fill="#fff"><circle cx="7.5" cy="15.5" r="1.8"/><circle cx="23" cy="4" r=".9"/><circle cx="19.5" cy="9" r=".9"/><circle cx="26" cy="8" r=".9"/><circle cx="23" cy="16.5" r="1"/><circle cx="24.6" cy="11.5" r=".5"/></g>'
  };
  function flag(c) { return 'data:image/svg+xml,' + encodeURIComponent('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 30 20">' + (FLAG[c] || FLAG.USD) + '</svg>'); }
  var CHEV = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M6 9l6 6 6-6"/></svg>';
  function pillHtml() { return '<img src="' + flag(cur) + '" alt="">' + cur + CHEV; }
  function paintPills() {
    document.querySelectorAll('.rfx-pill').forEach(function (b) { b.innerHTML = pillHtml(); b.setAttribute('aria-label', 'Currency: ' + CUR[cur].p + '. Change'); });
  }
  function placePill() {
    if (html.getAttribute('data-cur-pill') === 'off' || document.querySelector('.rfx-pill')) return !!document.querySelector('.rfx-pill');
    var spot = document.querySelector('[data-rfx-pill]'), b = document.createElement('button');
    b.type = 'button'; b.className = 'rfx-pill rfx-ui'; b.setAttribute('aria-haspopup', 'dialog');
    b.addEventListener('click', function () { open(b); });
    if (spot) { spot.appendChild(b); }
    else {
      var act = document.querySelector('header .hd-act') || document.querySelector('header.top .wrap #aa');
      if (!act) return false;
      if (act.id === 'aa') act.parentNode.insertBefore(b, act);
      else act.insertBefore(b, act.firstChild);
    }
    paintPills();
    return true;
  }
  var sheet = null, back = null;
  function open(from) {
    if (sheet) return;
    back = from || document.activeElement;
    var bg = document.createElement('div'); bg.className = 'rfx-bg rfx-ui';
    var date = fx.at ? new Date(fx.at + 'T12:00:00Z').toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric' }) : '';
    bg.innerHTML = '<div class="rfx-sh" role="dialog" aria-modal="true" aria-labelledby="rfxH">' +
      '<div class="rfx-hd"><div><h2 id="rfxH">Show amounts in</h2><p>Amounts change to your choice. Converted ones show ≈ with the original next to them.</p></div><button type="button" class="rfx-x" aria-label="Close">×</button></div>' +
      '<div role="radiogroup" aria-labelledby="rfxH">' + ORDER.map(function (c) {
        var ex = c === 'USD' ? 'Amounts in US dollars' : '$100 ≈ ' + fmt(100, 'USD', { to: c });
        return '<button type="button" class="rfx-row" role="radio" aria-checked="' + (c === cur) + '" data-c="' + c + '"><img src="' + flag(c) + '" alt=""><span class="rfx-t"><b>' + CUR[c].n + '</b><small>' + ex + '</small></span><span class="rfx-code">' + c + '</span></button>';
      }).join('') + '</div>' +
      '<p class="rfx-ft">' + (fx.fallback ? 'Approximate rates.' : 'Rates from ' + date + ', updated daily.') + ' Gifts are charged in US dollars.</p></div>';
    document.body.appendChild(bg); sheet = bg;
    document.documentElement.style.overflow = 'hidden';
    bg.addEventListener('click', function (e) {
      var r = e.target.closest('.rfx-row');
      if (r) { set(r.getAttribute('data-c')); close(); return; }
      if (e.target === bg || e.target.closest('.rfx-x')) close();
    });
    bg.addEventListener('keydown', function (e) {
      if (e.key === 'Escape') close();
      if (e.key === 'Tab') {
        var f = bg.querySelectorAll('button'), first = f[0], last = f[f.length - 1];
        if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
        else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
      }
    });
    var on = bg.querySelector('[aria-checked=true]'); (on || bg.querySelector('.rfx-x')).focus();
  }
  function close() {
    if (!sheet) return;
    sheet.remove(); sheet = null; document.documentElement.style.overflow = '';
    if (back && back.focus) back.focus();
  }

  // gifts go through Zeffy in US dollars: say so wherever there's a way to give, when another currency is shown
  function giftNote() {
    var want = cur !== 'USD';
    document.querySelectorAll('iframe[src*="zeffy"],a[href*="zeffy.com"]').forEach(function (z) {
      var box = z.closest('section,div') || z.parentNode, n = box.querySelector(':scope > .rfx-note');
      if (want && !n) { n = document.createElement('p'); n.className = 'rfx-note rfx-ui'; box.insertBefore(n, z.nodeName === 'IFRAME' ? z : box.firstChild); }
      if (n) { if (want) n.textContent = 'Amounts in ' + CUR[cur].p + ' are approximate. Your gift is charged in US dollars (USD).'; else n.remove(); }
    });
  }

  var subs = [];
  function set(c) {
    if (!CUR[c]) return;
    var was = cur; cur = c; picked = true; store('rfCur', c);
    html.setAttribute('data-cur', c);
    repaintAll(); paintPills(); giftNote();
    if (was !== c) {
      live.forEach(function (n) { var el = n.parentNode; if (el && el.classList && !window.matchMedia('(prefers-reduced-motion: reduce)').matches) { el.classList.remove('rfx-flash'); void el.offsetWidth; el.classList.add('rfx-flash'); } });
      subs.forEach(function (f) { try { f(c, was); } catch (e) {} });
    }
  }

  function getRates() {
    var age = Date.now() - (fx.got || 0);
    if (fx.got && age < 12 * 3600 * 1000 && !fx.fallback) return;
    fetch('/api/fx', { headers: { Accept: 'application/json' } }).then(function (r) { return r.ok ? r.json() : null; }).then(function (j) {
      if (!j || !j.rates) return;
      fx = { rates: j.rates, at: j.at || '', fallback: !!j.fallback, country: j.country || fx.country || '', got: Date.now() };
      store('rfFx', JSON.stringify(fx));
      if (!picked) { var c = startCur(); if (c !== cur) { cur = c; html.setAttribute('data-cur', c); paintPills(); giftNote(); subs.forEach(function (f) { try { f(c); } catch (e) {} }); } }
      repaintAll();
    }).catch(function () {});
  }

  function start() {
    addCss();
    html.setAttribute('data-cur', cur);
    look(document.body); watch(); giftNote();
    if (!placePill()) {
      // the header is drawn by the page's script: try again for a few seconds
      var k = 0, t = setInterval(function () { if (placePill() || ++k > 40) clearInterval(t); }, 250);
    }
    setTimeout(giftNote, 1500);
    getRates();
  }

  window.RFX = {
    cur: function () { return cur; },
    set: set,
    /** Back to the currency of where you are (forgets the pick). */
    auto: function () { store('rfCur', null); picked = false; set(startCur()); picked = false; store('rfCur', null); },
    picked: function () { return picked; },
    list: function () { return ORDER.map(function (c) { return { code: c, name: CUR[c].n, plural: CUR[c].p, flag: flag(c) }; }); },
    fmt: function (v, from, o) { return fmt(Number(v) || 0, from || 'USD', o); },
    /** The rounded number only, in the chosen currency (for counters). */
    num: function (v, from) { from = from || 'USD'; v = Number(v) || 0; return from === cur ? v : roundFor(convert(v, from, cur), cur); },
    text: function (t, tight) { return convertText(String(t || ''), tight); },
    say: function (t) { return say(String(t || '')); },
    rates: function () { return fx; },
    open: function (from) { addCss(); open(from); },
    on: function (f) { subs.push(f); }
  };
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start); else start();
})();
