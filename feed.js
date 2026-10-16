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
})();
