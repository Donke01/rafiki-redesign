// /api/pin: the Rafiki pin app (/pin). Lets the team set a project's GPS point.
//   GET  /api/pin           → the projects shown on the website, with their current point
//   POST /api/pin           → { id, lat, lng, acc, how, by } saves "lat, lng" into the
//                             project's "GPS Coordinates" column on monday.com and leaves a
//                             note on the item (who, how, and the old value).
// Both need the team PIN in the "X-Pin" header. The PIN is the Cloudflare secret PIN_CODE;
// until it is set the app stays locked. Wrong PINs are limited per connection.
import { getFeed, dropFeed } from '../../lib/feed-cache.js';
import { parseGps, readGps, writeGps, BOARD_ID } from '../../lib/monday.js';

const MAX_FAILS = 8; // wrong PINs per connection per hour
const MAX_SAVES = 60; // saves per connection per hour

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

async function count(env, key) {
  if (!env.FEED) return 0;
  return Number(await env.FEED.get(key)) || 0;
}
async function bump(env, key) {
  if (!env.FEED) return;
  const n = (Number(await env.FEED.get(key)) || 0) + 1;
  await env.FEED.put(key, String(n), { expirationTtl: 3600 });
}

// Returns null when the PIN is fine, or a Response to send back.
async function checkPin(request, env) {
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
  const id = String((b && b.id) || '').replace(/\D/g, '');
  if (!id) return json({ ok: false, reason: 'bad-request' }, 400);
  const gps = parseGps(Number(b.lat) + ', ' + Number(b.lng));
  if (!gps) return json({ ok: false, reason: 'place' }, 400);
  const acc = Math.round(Number(b.acc) || 0);
  const how = b.how === 'gps' ? 'phone GPS' : b.how === 'paste' ? 'pasted coordinates' : 'map';
  const by = String(b.by || '').replace(/[<>]/g, '').trim().slice(0, 60) || 'someone on the team';

  const token = env.MONDAY_API_TOKEN, api = env.MONDAY_API_URL, board = env.MONDAY_BOARD_ID || BOARD_ID;
  try {
    const cur = await readGps(token, id, api);
    if (!cur || (cur.board && cur.board !== String(board))) return json({ ok: false, reason: 'project' }, 404);
    const value = gps[0].toFixed(6) + ', ' + gps[1].toFixed(6);
    const note = '📍 Location pinned with the Rafiki pin app by ' + by + ': ' + value +
      ' (' + how + (b.how === 'gps' && acc ? ', about ±' + acc + ' m' : '') + ').' +
      (cur.text ? ' Previous value: ' + cur.text + '.' : ' There was no location before.');
    await writeGps(token, board, id, value, note, api);
    await bump(env, 'rl:pinsave:' + ip);
    await dropFeed(env);
    return json({ ok: true, id, gps, value, previous: cur.text || '' }, 200);
  } catch (e) {
    console.error('pin save:', e && e.message);
    return json({ ok: false, reason: 'down' }, 502);
  }
}

export function onRequest() {
  return json({ ok: false, reason: 'method' }, 405);
}
