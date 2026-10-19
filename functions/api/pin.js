// /api/pin: the Rafiki pin app (/pin), for the Rafiki team only.
//   GET  /api/pin            → the projects shown on the website, with their current point
//   GET  /api/pin?id=<item>  → the last change made with the app on that project (for undo)
//   POST /api/pin  { action: "pin",   id, lat, lng, acc, how, by }   saves "lat, lng" into "GPS Coordinates"
//                  { action: "undo",  id, by }                       puts back the value from before the last pin
//                  { action: "visit", id, state, note, lat, lng, by } leaves a site-visit note on the project
//                  { action: "new",   name, ck, village, type, lat, lng, note, by }  adds a new site as "Due Diligence"
// Every change also leaves a note on the monday.com item (who, what, the old value). The answer
// carries that note's id, so a photo can be attached to it with /api/pin-photo.
// All calls need the team PIN in the "X-Pin" header: the Cloudflare secret PIN_CODE. Until it
// is set the app stays locked. Wrong PINs are limited per connection.
import { getFeed, dropFeed } from '../../lib/feed-cache.js';
import { parseGps, readGps, writeGps, createUpdate, createSite, BOARD_ID } from '../../lib/monday.js';

const MAX_FAILS = 8; // wrong PINs per connection per hour
const MAX_SAVES = 120; // changes per connection per hour
const COUNTRIES = { ke: ['KE', 'Kenya'], tz: ['TZ', 'Tanzania'], ug: ['UG', 'Uganda'], mw: ['MW', 'Malawi'] };
const TYPES = { borehole: 'Water (Drilling)', collection: 'Water (Collection)', school: 'Educational', health: 'Medical', children: 'Orphanage', farming: 'Agricultural', community: 'Community', other: 'Other' };
const VISIT = { working: '✅ Working well', notworking: '❌ Not working', repair: '⚠️ Needs repair' };

function json(body, status) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff', 'X-Robots-Tag': 'noindex' }
  });
}

function sameText(a, b) {
  // constant-time comparison so the PIN can't be guessed from response times
  const x = new TextEncoder().encode(String(a)), y = new TextEncoder().encode(String(b));
  let diff = x.length ^ y.length;
  for (let i = 0; i < Math.max(x.length, y.length); i++) diff |= (x[i] || 0) ^ (y[i] || 0);
  return diff === 0;
}
async function count(env, key) { return env.FEED ? Number(await env.FEED.get(key)) || 0 : 0; }
async function bump(env, key) {
  if (!env.FEED) return;
  await env.FEED.put(key, String((Number(await env.FEED.get(key)) || 0) + 1), { expirationTtl: 3600 });
}
const clean = (s, n) => String(s || '').replace(/[<>]/g, '').replace(/\s+/g, ' ').trim().slice(0, n);
const said = s => s ? (/[.!?]$/.test(s) ? s : s + '.') : '';
const fmt = g => g[0].toFixed(6) + ', ' + g[1].toFixed(6);
function metres(a, b) {
  const R = 6371000, r = x => x * Math.PI / 180;
  const dLa = r(b[0] - a[0]), dLo = r(b[1] - a[1]);
  const h = Math.sin(dLa / 2) ** 2 + Math.cos(r(a[0])) * Math.cos(r(b[0])) * Math.sin(dLo / 2) ** 2;
  return 2 * R * Math.asin(Math.sqrt(h));
}
const dist = m => m < 1000 ? Math.round(m) + ' m' : (m / 1000).toFixed(1) + ' km';

// Returns null when the PIN is fine, or a Response to send back. Shared with /api/pin-photo.
export async function checkPin(request, env) {
  if (!env.PIN_CODE) return json({ ok: false, reason: 'not-set-up' }, 503);
  if (!env.MONDAY_API_TOKEN) return json({ ok: false, reason: 'no-monday' }, 503);
  const ip = request.headers.get('CF-Connecting-IP') || 'unknown';
  const failKey = 'rl:pinfail:' + ip;
  if (await count(env, failKey) >= MAX_FAILS) return json({ ok: false, reason: 'locked' }, 429);
  const given = (request.headers.get('X-Pin') || '').trim();
  if (!given || !sameText(given, String(env.PIN_CODE).trim())) {
    await bump(env, failKey);
    return json({ ok: false, reason: 'pin' }, 401);
  }
  return null;
}

export async function onRequestGet({ request, env, waitUntil }) {
  const bad = await checkPin(request, env);
  if (bad) return bad;
  const id = (new URL(request.url).searchParams.get('id') || '').replace(/\D/g, '');
  if (id) {
    const last = env.FEED ? await env.FEED.get('pinlast:' + id, 'json') : null;
    return json({ ok: true, last: last ? { value: last.value, prev: last.prev, by: last.by, at: last.at } : null, canUndo: !!env.FEED }, 200);
  }
  try {
    const feed = await getFeed(env, waitUntil);
    const projects = (feed.projects || []).map(p => ({
      id: p.id, title: p.title, country: p.country, ck: p.ck, area: p.area || '', type: p.type, status: p.status, year: p.year || null, gps: p.gps || null
    }));
    return json({ ok: true, projects }, 200);
  } catch (e) {
    console.error('pin list:', e && e.message);
    return json({ ok: false, reason: 'down' }, 502);
  }
}

export async function onRequestPost({ request, env }) {
  const origin = request.headers.get('Origin');
  if (origin && new URL(origin).host !== new URL(request.url).host) return json({ ok: false, reason: 'origin' }, 403);
  const bad = await checkPin(request, env);
  if (bad) return bad;
  const ip = request.headers.get('CF-Connecting-IP') || 'unknown';
  if (await count(env, 'rl:pinsave:' + ip) >= MAX_SAVES) return json({ ok: false, reason: 'rate' }, 429);

  let b;
  try { b = await request.json(); } catch (e) { return json({ ok: false, reason: 'bad-request' }, 400); }
  if (!b || typeof b !== 'object') return json({ ok: false, reason: 'bad-request' }, 400);
  const action = b.action || 'pin';
  const by = clean(b.by, 60) || 'someone on the team';
  const token = env.MONDAY_API_TOKEN, api = env.MONDAY_API_URL, board = String(env.MONDAY_BOARD_ID || BOARD_ID);
  const id = String(b.id || '').replace(/\D/g, '');

  try {
    // a new site: no existing item needed
    if (action === 'new') {
      const name = clean(b.name, 120), c = COUNTRIES[b.ck];
      if (!name || !c) return json({ ok: false, reason: 'bad-request' }, 400);
      const g = parseGps(Number(b.lat) + ', ' + Number(b.lng));
      if (!g) return json({ ok: false, reason: 'place' }, 400);
      const village = clean(b.village, 120), typeLabel = TYPES[b.type] || '';
      const newId = await createSite(token, board, { name, cc: c[0], countryName: c[1], village, typeLabel, gps: fmt(g) }, api);
      if (!newId) return json({ ok: false, reason: 'down' }, 502);
      const note = '🆕 New site added from the field with the Rafiki pin app by ' + by + '. Location: ' + fmt(g) + '.' +
        (village ? ' Village/community: ' + village + '.' : '') + (clean(b.note, 1000) ? ' Notes: ' + said(clean(b.note, 1000)) : '') +
        ' It starts as "Due Diligence", so it is not on the website yet.';
      const updateId = await createUpdate(token, newId, note, api);
      await bump(env, 'rl:pinsave:' + ip);
      return json({ ok: true, id: newId, updateId }, 200);
    }

    if (!id) return json({ ok: false, reason: 'bad-request' }, 400);
    const cur = await readGps(token, id, api);
    if (!cur || (cur.board && cur.board !== board)) return json({ ok: false, reason: 'project' }, 404);

    if (action === 'pin') {
      const g = parseGps(Number(b.lat) + ', ' + Number(b.lng));
      if (!g) return json({ ok: false, reason: 'place' }, 400);
      const acc = Math.round(Number(b.acc) || 0);
      const how = b.how === 'gps' ? 'phone GPS' : b.how === 'paste' ? 'pasted coordinates' : 'map';
      const value = fmt(g);
      const note = '📍 Location pinned with the Rafiki pin app by ' + by + ': ' + value +
        ' (' + how + (b.how === 'gps' && acc ? ', about ±' + acc + ' m' : '') + ').' +
        (cur.text ? ' Previous value: ' + cur.text + '.' : ' There was no location before.');
      const updateId = await writeGps(token, board, id, value, note, api);
      if (env.FEED) await env.FEED.put('pinlast:' + id, JSON.stringify({ value, prev: cur.text || '', by, at: new Date().toISOString() }), { expirationTtl: 60 * 60 * 24 * 90 });
      await bump(env, 'rl:pinsave:' + ip);
      await dropFeed(env);
      return json({ ok: true, id, gps: g, value, previous: cur.text || '', updateId }, 200);
    }

    if (action === 'undo') {
      const last = env.FEED ? await env.FEED.get('pinlast:' + id, 'json') : null;
      if (!last) return json({ ok: false, reason: 'nothing-to-undo' }, 409);
      if ((cur.text || '').replace(/\s/g, '') !== String(last.value).replace(/\s/g, '')) return json({ ok: false, reason: 'changed' }, 409);
      const note = '↩️ Pin undone with the Rafiki pin app by ' + by + '. ' + (last.prev ? 'Back to ' + last.prev + '.' : 'The location is empty again.') + ' (It was ' + last.value + '.)';
      const updateId = await writeGps(token, board, id, last.prev || '', note, api);
      await env.FEED.delete('pinlast:' + id);
      await bump(env, 'rl:pinsave:' + ip);
      await dropFeed(env);
      return json({ ok: true, id, gps: parseGps(last.prev), value: last.prev || '', updateId }, 200);
    }

    if (action === 'visit') {
      const state = VISIT[b.state];
      if (!state) return json({ ok: false, reason: 'bad-request' }, 400);
      const here = parseGps(Number(b.lat) + ', ' + Number(b.lng)), saved = parseGps(cur.text);
      const note = '🩺 Site visit by ' + by + ' (Rafiki pin app): ' + state + '.' +
        (clean(b.note, 2000) ? ' Notes: ' + said(clean(b.note, 2000)) : '') +
        (here ? ' Reported from ' + fmt(here) + (saved ? ', ' + dist(metres(here, saved)) + ' from the saved location' : '') + '.' : '');
      const updateId = await createUpdate(token, id, note, api);
      if (!updateId) return json({ ok: false, reason: 'down' }, 502);
      await bump(env, 'rl:pinsave:' + ip);
      return json({ ok: true, id, updateId }, 200);
    }

    return json({ ok: false, reason: 'bad-request' }, 400);
  } catch (e) {
    console.error('pin ' + action + ':', e && e.message);
    return json({ ok: false, reason: 'down' }, 502);
  }
}

export function onRequest() {
  return json({ ok: false, reason: 'method' }, 405);
}
