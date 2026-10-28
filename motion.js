/* Rafiki motion: water-line scroll bar, card reveal, counting numbers, photo shimmer.
   Everything here is decoration: if this file fails or the visitor asks for reduced motion,
   the pages look and work exactly the same, just without movement. */
(function () {
  var reduce = window.matchMedia && matchMedia('(prefers-reduced-motion: reduce)').matches;
  var html = document.documentElement;

  // photos that fail to load stop shimmering (the tinted frame stays)
  document.addEventListener('error', function (e) {
    var t = e.target; if (t && t.tagName === 'IMG' && t.parentElement) t.parentElement.classList.add('mo-noimg');
  }, true);
  document.addEventListener('load', function (e) {
    var t = e.target; if (t && t.tagName === 'IMG' && t.parentElement) t.parentElement.classList.add('mo-imgok');
  }, true);

  if (reduce || !('IntersectionObserver' in window)) return;

  // page change: fade the page out, then follow the link (normal clicks to other pages only)
  addEventListener('pageshow', function () { html.classList.remove('mo-leave'); });
  document.addEventListener('click', function (e) {
    if (e.defaultPrevented || e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;
    var a = e.target.closest && e.target.closest('a[href]'); if (!a || (a.target && a.target !== '_self') || a.hasAttribute('download')) return;
    var u; try { u = new URL(a.getAttribute('href'), location.href); } catch (x) { return; }
    if (u.origin !== location.origin || !/^https?:$/.test(u.protocol)) return;
    if (u.pathname === location.pathname && u.search === location.search) return; // same page: handled elsewhere
    if (/^\/(api|project)\//.test(u.pathname)) return;
    e.preventDefault();
    html.classList.add('mo-leave');
    try { sessionStorage.setItem('moNav', '1'); } catch (x) {}
    setTimeout(function () { location.href = u.href; }, 180);
  });
  html.classList.add('mo');

  // 1. water-line scroll bar
  var bar = document.createElement('div');
  bar.className = 'mo-water'; bar.setAttribute('aria-hidden', 'true');
  bar.innerHTML = '<i></i>';
  var fill = bar.firstChild, ticking = false;
  function prog() {
    ticking = false;
    var h = document.documentElement.scrollHeight - innerHeight;
    fill.style.transform = 'scaleX(' + (h > 0 ? Math.min(1, Math.max(0, scrollY / h)) : 0) + ')';
  }
  function addBar() { if (!bar.isConnected) document.body.appendChild(bar); prog(); }
  addEventListener('scroll', function () { if (!ticking) { ticking = true; requestAnimationFrame(prog); } }, { passive: true });
  addEventListener('resize', prog);

  // 9. gentle reveal: cards rise in once, a little after each other
  var CARDS = '.lw-card,.cc-card,.sp-card,.pj-card,.up-card,.pr-it,.ps-it,.sg-well,.hp-card,.tm-card,.xe-card,.vc,.nd-f,.lf-it,.fq-it,.sg-card,.xl-it,.tr-li,.ct-off,.gc-it,.xt-tile';
  var seen = new WeakSet(), queue = [], flushing = false;
  var io = new IntersectionObserver(function (es) {
    es.forEach(function (e) {
      if (!e.isIntersecting) return;
      io.unobserve(e.target); queue.push(e.target);
    });
    if (!flushing) { flushing = true; requestAnimationFrame(flush); }
  }, { rootMargin: '0px 0px -8% 0px', threshold: 0.05 });
  function flush() {
    flushing = false;
    queue.sort(function (a, b) { var ra = a.getBoundingClientRect(), rb = b.getBoundingClientRect(); return (ra.top - rb.top) || (ra.left - rb.left); });
    queue.forEach(function (el, i) { el.style.setProperty('--mo-d', Math.min(i, 6) * 70 + 'ms'); el.classList.add('mo-in'); });
    queue = [];
  }
  function scanCards(root) {
    (root.querySelectorAll ? root : document).querySelectorAll(CARDS).forEach(function (el) {
      if (seen.has(el) || el.closest('.rv') || el.closest('.hd-wrap,footer')) return;
      seen.add(el);
      var r = el.getBoundingClientRect();
      if (r.top < innerHeight * 0.92 && r.bottom > 0) return; // already on screen: leave it alone
      el.classList.add('mo-pre'); io.observe(el);
    });
  }

  // 5. counting numbers: count up when they scroll into view; stop if the page updates the number itself
  var NUMS = '.tk-stat b,.xl-sum b,.nd-f > b,.wd-facts b,.tr-pct,.lf-tot b,.im-facts b';
  var numSeen = new WeakSet();
  var nio = new IntersectionObserver(function (es) {
    es.forEach(function (e) { if (e.isIntersecting) { nio.unobserve(e.target); count(e.target); } });
  }, { threshold: 0.6 });
  function textNode(el) { var w = document.createTreeWalker(el, NodeFilter.SHOW_TEXT); var n; while ((n = w.nextNode())) if (/\d/.test(n.nodeValue)) return n; return null; }
  function count(el) {
    var n = textNode(el); if (!n) return;
    var m = /^([^\d]*)([\d,]+(?:\.\d+)?)(.*)$/.exec(n.nodeValue); if (!m) return;
    var target = parseFloat(m[2].replace(/,/g, '')); if (!(target > 3)) return;
    var dec = (m[2].split('.')[1] || '').length, pre = m[1], post = m[3], t0 = null, last = null, dur = 1200;
    var fmt = function (v) { return pre + v.toLocaleString('en-US', { minimumFractionDigits: dec, maximumFractionDigits: dec }) + post; };
    function step(t) {
      if (last !== null && n.nodeValue !== last) return; // the page changed it: keep the page's value
      if (t0 === null) t0 = t;
      var p = Math.min(1, (t - t0) / dur), e = 1 - Math.pow(1 - p, 3);
      last = fmt(p < 1 ? target * e : target); n.nodeValue = last;
      if (p < 1) requestAnimationFrame(step);
    }
    requestAnimationFrame(step);
  }
  function scanNums(root) {
    (root.querySelectorAll ? root : document).querySelectorAll(NUMS).forEach(function (el) {
      if (numSeen.has(el)) return; numSeen.add(el);
      var r = el.getBoundingClientRect(); if (r.top < innerHeight && r.bottom > 0) return;
      nio.observe(el);
    });
  }

  // 10. a soft light follows the pointer over cards (mouse and trackpad only)
  if (window.matchMedia && matchMedia('(hover: hover)').matches) {
    var LIT = '.pt-card,.pr-it,.fq-it,.ct-off,.sg-well,.hp-card,.cc-card,.gc-it';
    document.addEventListener('pointermove', function (e) {
      var c = e.target.closest && e.target.closest(LIT); if (!c) return;
      var r = c.getBoundingClientRect(); c.style.setProperty('--mx', (e.clientX - r.left) + 'px'); c.style.setProperty('--my', (e.clientY - r.top) + 'px');
    }, { passive: true });
  }

  // pages render after this script runs, and again when live data arrives: keep looking for new cards
  var pending = false;
  function scanAll() {
    pending = false; scanCards(document); scanNums(document);
    document.querySelectorAll('img').forEach(function (im) { if (im.complete && im.parentElement) im.parentElement.classList.add(im.naturalWidth ? 'mo-imgok' : 'mo-noimg'); });
  }
  function start() {
    addBar(); scanAll();
    new MutationObserver(function () { if (!pending) { pending = true; setTimeout(scanAll, 120); } })
      .observe(document.body, { childList: true, subtree: true });
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', function () { setTimeout(start, 60); });
  else setTimeout(start, 60);
})();
