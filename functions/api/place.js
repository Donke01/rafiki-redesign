// /api/place: place search for the pin app (team only, same sign-in as /api/pin).
//   GET /api/place?q=Kiptoo primary school&cc=ke  → up to 8 places in Kenya/Tanzania/Uganda/Malawi
//   GET /api/place?resolve=<short map link>        → the full link a Google/Apple Maps share link points to,
//                                                     so the app can read the coordinates out of it
// Search uses OpenStreetMap: Nominatim first (exact names), then Photon (forgiving of spelling).
// Following their usage rules: searches are sent only when someone presses Search (no type-ahead),
// they carry a User-Agent naming the app, results are cached for 30 days, and each connection is
// limited. Google's and Apple's search results can't be shown on this (OpenStreetMap) map under
// their terms, so the app opens those apps instead and reads back the link that is pasted in.
import { checkPin } from './pin.js';

const UA = 'RafikiPin/1.0 (+https://rafikioutreach.org; field app for Rafiki Outreach)';
const CCS = ['ke', 'tz', 'ug', 'mw'];
const BOX = { ke: [33.9, -4.8, 41.9, 5.1], tz: [29.3, -11.8, 40.5, -0.9], ug: [29.5, -1.5, 35.1, 4.3], mw: [32.6, -17.2, 36, -9.3], all: [28, -18, 42, 6] };
const SHORT_HOSTS = /^(maps\.app\.goo\.gl|goo\.gl|maps\.google\.[a-z.]+|www\.google\.[a-z.]+|google\.[a-z.]+|maps\.apple\.com|maps\.apple)$/i;
const MAX_PER_HOUR = 120;

function json(body, status) {
  return new Response(JSON.stringify(body), { status: status || 200, headers: { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' } });
}
const inAfrica = (lat, lng) => lat >= -18 && lat <= 6 && lng >= 28 && lng <= 42;

async function nominatim(q, cc) {
  const u = new URL('https://nominatim.openstreetmap.org/search');
  u.searchParams.set('q', q); u.searchParams.set('format', 'jsonv2'); u.searchParams.set('limit', '8');
  u.searchParams.set('countrycodes', cc ? cc : CCS.join(',')); u.searchParams.set('accept-language', 'en');
  const r = await fetch(u, { headers: { 'User-Agent': UA, Accept: 'application/json' } });
  if (!r.ok) return [];
  const a = await r.json();
  return (a || []).map(x => ({ name: x.name || String(x.display_name || '').split(',')[0], where: String(x.display_name || '').split(',').slice(1, 4).map(s => s.trim()).join(', '), type: (x.type || x.category || '').replace(/_/g, ' '), lat: +x.lat, lng: +x.lon, src: 'osm' }));
}
async function photon(q, cc, near) {
  const b = BOX[cc] || BOX.all;
  const u = new URL('https://photon.komoot.io/api/');
  u.searchParams.set('q', q); u.searchParams.set('limit', '8'); u.searchParams.set('lang', 'en'); u.searchParams.set('bbox', b.join(','));
  if (near) { u.searchParams.set('lat', near[0]); u.searchParams.set('lon', near[1]); }
  const r = await fetch(u, { headers: { 'User-Agent': UA, Accept: 'application/json' } });
  if (!r.ok) return [];
  const j = await r.json();
  return ((j && j.features) || []).map(f => {
    const p = f.properties || {}, c = (f.geometry && f.geometry.coordinates) || [];
    return { name: p.name || p.street || '', where: [p.city || p.county, p.state, p.country].filter(Boolean).join(', '), type: String(p.osm_value || p.type || '').replace(/_/g, ' '), lat: +c[1], lng: +c[0], src: 'osm' };
  }).filter(x => x.name);
}

export async function onRequestGet({ request, env }) {
  const chk = await checkPin(request, env);
  if (chk.res) return chk.res;
  const ip = request.headers.get('CF-Connecting-IP') || 'unknown';
  if (env.FEED) {
    const k = 'rl:place:' + ip, n = Number(await env.FEED.get(k)) || 0;
    if (n >= MAX_PER_HOUR) return json({ ok: false, reason: 'rate' }, 429);
    await env.FEED.put(k, String(n + 1), { expirationTtl: 3600 });
  }
  const url = new URL(request.url);

  // a share link from Google or Apple Maps: follow its redirects (only to map sites) and return the final address
  const link = url.searchParams.get('resolve');
  if (link) {
    let u;
    try { u = new URL(link); } catch (e) { return json({ ok: false, reason: 'bad-request' }, 400); }
    for (let i = 0; i < 5; i++) {
      if (u.protocol !== 'https:' || !SHORT_HOSTS.test(u.hostname)) return json({ ok: false, reason: 'bad-request' }, 400);
      let r;
      try { r = await fetch(u, { redirect: 'manual', headers: { 'User-Agent': 'Mozilla/5.0 (RafikiPin)' } }); } catch (e) { return json({ ok: false, reason: 'down' }, 502); }
      const to = r.headers.get('Location');
      if (r.status >= 300 && r.status < 400 && to) { u = new URL(to, u); continue; }
      return json({ ok: true, url: u.toString() });
    }
    return json({ ok: true, url: u.toString() });
  }

  const q = String(url.searchParams.get('q') || '').replace(/[<>]/g, '').replace(/\s+/g, ' ').trim().slice(0, 120);
  const cc = CCS.includes(url.searchParams.get('cc')) ? url.searchParams.get('cc') : '';
  if (q.length < 2) return json({ ok: false, reason: 'bad-request' }, 400);
  const nm = /^(-?\d+\.\d+),(-?\d+\.\d+)$/.exec(url.searchParams.get('near') || ''), near = nm && inAfrica(+nm[1], +nm[2]) ? [(+nm[1]).toFixed(2), (+nm[2]).toFixed(2)] : null;
  const key = 'place:' + cc + ':' + (near ? near.join(',') + ':' : '') + q.toLowerCase();
  if (env.FEED) { const hit = await env.FEED.get(key, 'json'); if (hit) return json({ ok: true, places: hit, cached: true }); }
  let places = [];
  try { places = await nominatim(q, cc); } catch (e) { places = []; }
  if (places.length < 3) {
    try {
      const more = await photon(q, cc, near);
      more.forEach(m => { if (!places.some(p => Math.abs(p.lat - m.lat) < 0.002 && Math.abs(p.lng - m.lng) < 0.002)) places.push(m); });
    } catch (e) {}
  }
  // flag look-alike names (the forgiving search can return "Mambani" for "Mengech")
  const words = q.toLowerCase().split(/[\s,]+/).filter(w => w.length >= 4 && !/^(primary|secondary|school|church|clinic|health|centre|center|village|well)$/.test(w));
  places = places.filter(p => isFinite(p.lat) && isFinite(p.lng) && inAfrica(p.lat, p.lng)).map(p => Object.assign(p, { similar: words.length > 0 && !words.some(w => p.name.toLowerCase().includes(w)) }));
  places.sort((a, b) => (a.similar - b.similar));
  places = places.slice(0, 8);
  if (env.FEED && places.length) await env.FEED.put(key, JSON.stringify(places), { expirationTtl: 60 * 60 * 24 * 30 });
  return json({ ok: true, places });
}

export function onRequest() { return json({ ok: false, reason: 'method' }, 405); }
