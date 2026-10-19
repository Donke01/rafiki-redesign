// Rafiki Pin: keeps the app itself (page, map code, logo) on the phone so it opens with no
// signal. Data (/api/*) is never cached here: changes wait in the app's own outbox instead.
// Map tiles already seen are kept too (up to ~400), so a site visited before still shows a map.
var APP = 'rafiki-pin-app-v5', TILES = 'rafiki-pin-tiles-v1';
var SHELL = ['/pin', '/vendor/leaflet/leaflet.js', '/vendor/leaflet/leaflet.css', '/img/logo-dark-bold.webp', '/pin.webmanifest', '/favicon.svg', '/img/logo-light-bold.webp', '/img/pin/icon-192.png'];

self.addEventListener('install', function (e) {
  e.waitUntil(caches.open(APP).then(function (c) { return c.addAll(SHELL); }).then(function () { return self.skipWaiting(); }));
});
self.addEventListener('activate', function (e) {
  e.waitUntil(caches.keys().then(function (ks) { return Promise.all(ks.filter(function (k) { return k !== APP && k !== TILES; }).map(function (k) { return caches.delete(k); })); }).then(function () { return self.clients.claim(); }));
});
function trim(c) { c.keys().then(function (ks) { if (ks.length > 400) ks.slice(0, ks.length - 400).forEach(function (k) { c.delete(k); }); }); }
self.addEventListener('fetch', function (e) {
  var r = e.request, u = new URL(r.url);
  if (r.method !== 'GET') return;
  if (u.origin === location.origin && u.pathname.indexOf('/api/') === 0) return;
  // tiles: from the phone first, then the network
  if (/tile\.openstreetmap\.org$|arcgisonline\.com$/.test(u.hostname)) {
    e.respondWith(caches.open(TILES).then(function (c) {
      return c.match(r).then(function (hit) {
        return hit || fetch(r).then(function (res) { if (res.ok || res.type === 'opaque') { c.put(r, res.clone()); trim(c); } return res; });
      });
    }));
    return;
  }
  if (u.origin !== location.origin) return;
  // the page and its files: network first (so updates arrive), the saved copy when offline
  e.respondWith(fetch(r).then(function (res) {
    if (res.ok) { var copy = res.clone(); caches.open(APP).then(function (c) { c.put(u.pathname === '/pin.html' ? '/pin' : r, copy); }); }
    return res;
  }).catch(function () { return caches.match(u.pathname === '/pin.html' ? '/pin' : r, { ignoreSearch: true }); }));
});
