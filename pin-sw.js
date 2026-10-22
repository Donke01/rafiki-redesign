// Rafiki Pin: keeps the app itself (page, map code, logo) on the phone so it opens with no
// signal. Data (/api/*) is never cached here: changes wait in the app's own outbox instead.
// Map tiles already seen are kept too (offline areas and the storage limit: see the end of this file), so a site
// visited before still shows a map.
// Whole-country maps (pin.html, feat-cmap) live in their own caches, rafiki-pin-cmap-<file>, kept until deleted in the app.
var APP = 'rafiki-pin-app-v60', TILES = 'rafiki-pin-tiles-v1';
var SHELL = ['/pin', '/vendor/leaflet/leaflet.js', '/vendor/leaflet/leaflet.css', '/img/logo-dark-bold.webp', '/pin.webmanifest', '/favicon.svg', '/img/logo-light-bold.webp', '/img/pin/icon-192.png'];

self.addEventListener('install', function (e) {
  e.waitUntil(caches.open(APP).then(function (c) { return c.addAll(SHELL); }).then(function () { return self.skipWaiting(); }));
});
self.addEventListener('activate', function (e) {
  e.waitUntil(caches.keys().then(function (ks) { return Promise.all(ks.filter(function (k) { return k !== APP && k !== TILES && !/^rafiki-pin-(tiles-w|area-|fld$|cmap-)/.test(k); }).map(function (k) { return caches.delete(k); })); }).then(function () { return self.clients.claim(); }));
});
self.addEventListener('fetch', function (e) {
  var r = e.request, u = new URL(r.url);
  if (r.method !== 'GET') return;
  if (u.origin === location.origin && u.pathname.indexOf('/api/') === 0) return;
  // tiles: from the phone first, then the network
  if (/tile\.openstreetmap\.org$|arcgisonline\.com$/.test(u.hostname) || (u.hostname === 'www.google.com' && u.pathname === '/s2/favicons')) {
    e.respondWith(fldTile(r, u));
    return;
  }
  if (u.origin !== location.origin) return;
  // the page and its files: network first (so updates arrive), the saved copy when offline
  e.respondWith(fetch(r).then(function (res) {
    if (res.ok) { var copy = res.clone(); caches.open(APP).then(function (c) { c.put(u.pathname === '/pin.html' ? '/pin' : r, copy); }); }
    return res;
  }).catch(function () { return caches.match(u.pathname === '/pin.html' ? '/pin' : r, { ignoreSearch: true }); }));
});

// --- offline map areas (Map › Save this area for offline; see the field tools in pin.html) ------------
// The map servers don't allow bulk downloads, so nothing is fetched ahead: tiles are kept as they are viewed.
// A tile inside a saved area goes into that area's own cache (rafiki-pin-area-<id>) and stays until the area
// is deleted. Other tiles go into a cache per week (rafiki-pin-tiles-w<n>); weeks older than the chosen
// number of days are dropped, and the newest weeks are trimmed to the storage limit. Google tiles never come here.
var FLDC = 'rafiki-pin-fld', fldCfg = null, fldAt = 0, fldCleanAt = 0;
function fldConf() {
  if (fldCfg && Date.now() - fldAt < 60000) return Promise.resolve(fldCfg);
  return caches.open(FLDC).then(function (c) { return c.match('/__fld/cfg'); }).then(function (r) { return r ? r.json() : null; })
    .catch(function () { return null; }).then(function (j) { fldCfg = j || { areas: [], keep: 30, max: 12800 }; fldAt = Date.now(); return fldCfg; });
}
self.addEventListener('message', function (e) { if (e.data && e.data.fld) { fldCfg = null; fldConf().then(fldClean); } });
function fldZXY(u) {
  var m = /^\/(\d+)\/(\d+)\/(\d+)\.png$/.exec(u.pathname); if (m && /openstreetmap\.org$/.test(u.hostname)) return [+m[1], +m[2], +m[3]];
  m = /\/tile\/(\d+)\/(\d+)\/(\d+)$/.exec(u.pathname); if (m && /arcgisonline\.com$/.test(u.hostname)) return [+m[1], +m[3], +m[2]];
  return null;
}
function fldTX(lon, z) { return Math.min(Math.pow(2, z) - 1, Math.max(0, Math.floor((lon + 180) / 360 * Math.pow(2, z)))); }
function fldTY(lat, z) { var r = Math.max(-85, Math.min(85, lat)) * Math.PI / 180; return Math.min(Math.pow(2, z) - 1, Math.max(0, Math.floor((1 - Math.log(Math.tan(r) + 1 / Math.cos(r)) / Math.PI) / 2 * Math.pow(2, z)))); }
function fldArea(cfg, t) {
  if (!t) return null;
  for (var i = 0; i < cfg.areas.length; i++) {
    var a = cfg.areas[i], z = t[0];
    if (z <= a.maxZ + 2 && t[1] >= fldTX(a.w, z) && t[1] <= fldTX(a.e, z) && t[2] >= fldTY(a.n, z) && t[2] <= fldTY(a.s, z)) return a;
  }
  return null;
}
function fldWeek() { return 'rafiki-pin-tiles-w' + Math.floor(Date.now() / (7 * 864e5)); }
function fldTile(r, u) {
  return caches.match(r).then(function (hit) {
    if (hit) return hit;
    return fetch(r).then(function (res) {
      if (res.ok || res.type === 'opaque') {
        var copy = res.clone();
        fldConf().then(function (cfg) {
          var a = fldArea(cfg, fldZXY(u));
          return caches.open(a ? 'rafiki-pin-area-' + a.id : fldWeek()).then(function (c) { return c.put(r, copy); }).then(function () { if (!a && Date.now() - fldCleanAt > 60000) fldClean(cfg); });
        }).catch(function () {});
      }
      return res;
    });
  });
}
// drop weeks older than the chosen days; keep the viewed tiles under the limit (newest weeks first); drop deleted areas
function fldClean(cfg) {
  fldCleanAt = Date.now(); cfg = cfg || fldCfg || { areas: [], keep: 30, max: 12800 };
  var now = Math.floor(Date.now() / (7 * 864e5)), keepW = Math.ceil((cfg.keep || 30) / 7), ids = {};
  (cfg.areas || []).forEach(function (a) { ids['rafiki-pin-area-' + a.id] = 1; });
  return caches.keys().then(function (ks) {
    var weeks = ks.filter(function (k) { return /^rafiki-pin-tiles-w\d+$/.test(k); }).sort(function (a, b) { return +b.slice(18) - +a.slice(18); }), left = cfg.max || 12800;
    ks.forEach(function (k) { if (/^rafiki-pin-area-/.test(k) && !ids[k]) caches.delete(k); });
    // tiles kept before areas existed (the old cache) count as the oldest week
    if (ks.indexOf(TILES) >= 0) weeks.push(TILES);
    return weeks.reduce(function (p, k) {
      return p.then(function () {
        if (k !== TILES && now - +k.slice(18) > keepW) return caches.delete(k);
        return caches.open(k).then(function (c) { return c.keys().then(function (rs) { var over = rs.length - Math.max(0, left); left -= rs.length; if (over > 0) return Promise.all(rs.slice(0, over).map(function (x) { return c.delete(x); })); }); });
      });
    }, Promise.resolve());
  }).catch(function () {});
}
