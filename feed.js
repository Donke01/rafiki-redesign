/* Live project data from monday.com (served by functions/api/projects.js).
   rafikiFeed() resolves to the feed, or to null when it is not set up or not reachable;
   pages then keep their built-in sample data. Kept for 5 minutes per browser tab. */
(function () {
  var KEY = 'rfFeed', TTL = 5 * 60 * 1000, pending = null;
  function live(d) { if (d) document.documentElement.className += ' feed-live'; return d; }
  window.rafikiFeed = function () {
    if (pending) return pending;
    pending = new Promise(function (done) {
      try {
        var c = JSON.parse(sessionStorage.getItem(KEY) || 'null');
        if (c && Date.now() - c.t < TTL) return done(c.d);
      } catch (e) {}
      if (!window.fetch) return done(null);
      fetch('/api/projects', { headers: { Accept: 'application/json' } })
        .then(function (r) { return r.text().then(function (t) { return r.ok ? JSON.parse(t) : null; }); })
        .then(function (d) {
          if (!d || !d.projects) return done(null);
          try { sessionStorage.setItem(KEY, JSON.stringify({ t: Date.now(), d: d })); } catch (e) {}
          done(d);
        })
        .catch(function () { done(null); });
    }).then(live);
    return pending;
  };
  window.rafikiPhoto = function (id) { return '/api/photo/' + encodeURIComponent(id); };

  // Header pop-ups: Escape closes the country selector or the menu and puts focus back on
  // its button; a click outside the country selector closes it too.
  function openBtn(sel) { return document.querySelector(sel + '[aria-expanded="true"]'); }
  document.addEventListener('keydown', function (e) {
    if (e.key !== 'Escape' && e.key !== 'Esc') return;
    var b = openBtn('.cs-btn') || openBtn('button[aria-controls="bento-menu"]');
    if (!b) return;
    b.click(); setTimeout(function () { b.focus(); }, 0);
  });
  document.addEventListener('pointerdown', function (e) {
    var b = openBtn('.cs-btn'), pop = document.querySelector('.cs-pop');
    if (b && !b.contains(e.target) && !(pop && pop.contains(e.target))) b.click();
  });

  // Page position: a page opened from a link always starts at the top (or exactly at the
  // section a link points to, e.g. /#where-we-work, below the header). Back/forward keeps
  // the place you were. The pages draw themselves after loading, so this is re-applied
  // until the page has settled, unless the visitor has already started scrolling.
  addEventListener('pagehide', function () { try { sessionStorage.setItem('rfY:' + location.pathname + location.search, String(Math.round(scrollY))); } catch (e) {} });
  (function () {
    var nav = (performance.getEntriesByType && performance.getEntriesByType('navigation')[0]) || {};
    if (nav.type === 'back_forward' || nav.type === 'reload') {
      // Back/forward: return to where the visitor was, once the page has drawn itself
      var key = 'rfY:' + location.pathname + location.search, y = 0;
      try { y = Number(sessionStorage.getItem(key)) || 0; } catch (e) {}
      if (y) [0, 200, 600, 1200].forEach(function (t) { setTimeout(function () { if (Math.abs(scrollY - y) > 40 && scrollY < 40) scrollTo(0, y); }, t); });
      return;
    }
    try { history.scrollRestoration = 'manual'; } catch (e) {}
    var touched = false, want = null, stop = function () { touched = true; };
    addEventListener('scroll', function () { if (want !== null && Math.abs(scrollY - want) > 4) touched = true; }, { passive: true });
    ['wheel', 'touchstart', 'keydown', 'mousedown'].forEach(function (t) { addEventListener(t, stop, { passive: true, once: true }); });
    function place() {
      if (touched) return;
      var id = decodeURIComponent((location.hash || '').slice(1)), el = id && document.getElementById(id);
      if (el) {
        var hd = document.querySelector('.hd-wrap'), off = (hd ? hd.getBoundingClientRect().height : 0) + 16;
        want = Math.max(0, Math.round(el.getBoundingClientRect().top + scrollY - off)); scrollTo(0, want);
      } else if (!id) { want = 0; scrollTo(0, 0); }
    }
    place();
    document.addEventListener('DOMContentLoaded', place);
    addEventListener('load', place);
    [150, 400, 900, 1600].forEach(function (t) { setTimeout(place, t); });
    // give the browser back its normal behaviour, so Back returns to where you were
    var auto = function () { try { history.scrollRestoration = 'auto'; } catch (e) {} };
    setTimeout(auto, 2000); addEventListener('pagehide', auto);
  })();

  // A slower, eased scroll (used by the hero's scroll-down arrow); stops if the visitor scrolls
  function glide(to, dur) {
    var from = scrollY, t0 = null, stopped = false;
    var halt = function () { stopped = true; };
    ['wheel', 'touchstart', 'keydown'].forEach(function (t) { addEventListener(t, halt, { passive: true, once: true }); });
    document.documentElement.style.scrollBehavior = 'auto';
    (function step(t) {
      if (stopped) return;
      if (t0 === null) t0 = t;
      var p = Math.min(1, (t - t0) / dur), e = p < .5 ? 4 * p * p * p : 1 - Math.pow(-2 * p + 2, 3) / 2;
      scrollTo(0, from + (to - from) * e);
      if (p < 1) requestAnimationFrame(step); else document.documentElement.style.scrollBehavior = '';
    })(performance.now());
  }

  // Links to a place on the same page scroll there smoothly (below the header) instead of
  // jumping; a link to the page you are already on goes back to its top. The menu closes first.
  document.addEventListener('click', function (e) {
    if (e.defaultPrevented || e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;
    var a = e.target.closest && e.target.closest('a[href]'); if (!a || a.target === '_blank' || a.hasAttribute('download')) return;
    var u; try { u = new URL(a.getAttribute('href'), location.href); } catch (x) { return; }
    if (u.origin !== location.origin || u.pathname.replace(/\.html$/, '') !== location.pathname.replace(/\.html$/, '') || u.search !== location.search) return;
    var id = decodeURIComponent(u.hash.slice(1)), el = id && document.getElementById(id);
    if (id && !el) return;
    e.preventDefault();
    var m = document.querySelector('button[aria-controls="bento-menu"][aria-expanded="true"]'); if (m) m.click();
    var reduce = window.matchMedia && matchMedia('(prefers-reduced-motion: reduce)').matches;
    setTimeout(function () {
      var hd = document.querySelector('.hd-wrap'), off = (hd ? hd.getBoundingClientRect().height : 0) + 16;
      var y = el ? Math.max(0, el.getBoundingClientRect().top + scrollY - off) : 0;
      if (a.classList.contains('zh-down') && !reduce) glide(y, 900);
      else scrollTo({ top: y, behavior: reduce ? 'auto' : 'smooth' });
      if (u.hash !== location.hash) history.pushState(null, '', u.hash || location.pathname + location.search);
    }, m ? 60 : 0);
  });
})();

// Header on scroll: slims down once the page moves, slides away while reading down,
// and comes back as soon as the visitor scrolls up (never while a menu is open).
(function () {
  var html = document.documentElement, last = window.scrollY || 0, ticking = false;
  function menuOpen() { return !!document.querySelector('button[aria-controls="bento-menu"][aria-expanded="true"],.cs-btn[aria-expanded="true"]'); }
  function update() {
    ticking = false;
    var y = window.scrollY || 0, dy = y - last;
    html.classList.toggle('hd-compact', y > 40);
    if (menuOpen() || y < 140) html.classList.remove('hd-hide');
    else if (dy > 8) html.classList.add('hd-hide');
    else if (dy < -8) html.classList.remove('hd-hide');
    if (Math.abs(dy) > 8) last = y;
  }
  addEventListener('scroll', function () { if (!ticking) { ticking = true; requestAnimationFrame(update); } }, { passive: true });
  document.addEventListener('focusin', function (e) { if (e.target.closest && e.target.closest('.hd-wrap')) html.classList.remove('hd-hide'); });
  document.addEventListener('click', function () { setTimeout(update, 0); });
  update();
})();
