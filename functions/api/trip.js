// /api/trip: weather and road conditions for a planned visit or a day of visits (lib/trip.js), for the RAFIKI
// team (same sign-in as /api/pin). See docs/trip-weather-roads.md for the answer's shape.
//   GET  /api/trip?date=2026-10-24&stops=104,105,106[&from=0.514,35.27][&until=2026-10-30][&geometry=0]
//        stops: project ids (comma-separated), or points and ids separated by ";" ("1.01,35.0;104;0.52,35.28")
//        from:  where the day starts ("lat,lng" or a project id); without it the first stop is the start
//        until: also rate every day from date to until (at most 16 days) → days[] and bestDay
//   POST /api/trip { from, stops: [{ id, lat, lng, name } | id], date, until, geometry }   the same, as JSON
//   POST /api/trip { action: "actual", from: [lat, lng], to: [lat, lng], minutes, level }  a real trip time for a leg
//        (or { action: "actual", leg: "0.514,35.270|1.015,35.000", minutes }); from 3 trips it is blended into estMin
// The week view (driest day for a list of projects) is /api/trip/week (functions/api/trip/week.js).
import { checkPin } from './pin.js';
import { getFeed } from '../../lib/feed-cache.js';
import { tripAdvice, recordActual, legKey, okLegKey, MAX_STOPS } from '../../lib/trip.js';
import { asPoint, okPoint } from '../../lib/roads.js';

export function json(body, status) {
  return new Response(JSON.stringify(body), { status: status || 200, headers: { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff', 'X-Robots-Tag': 'noindex' } });
}
const strip = s => String(s || '').replace(/^[\s*#_-]+|[\s*#_]+$/g, '');
const MAX_ACTUAL_PER_HOUR = 60;

// "104" or "0.514,35.27" or { id } / { lat, lng } → a stop, using the feed for project ids. null when unknown.
export function resolveStop(x, byId) {
  if (x == null) return null;
  if (typeof x === 'string' || typeof x === 'number') {
    const s = String(x).trim();
    if (/^\d{1,15}$/.test(s)) { const p = byId.get(s); return p && Array.isArray(p.gps) && p.gps[0] != null ? { id: s, name: strip(p.title), lat: Number(p.gps[0]), lng: Number(p.gps[1]) } : null; }
    const m = /^(-?\d{1,2}(?:\.\d+)?),\s*(-?\d{1,3}(?:\.\d+)?)$/.exec(s);
    return m && okPoint([Number(m[1]), Number(m[2])]) ? { id: '', name: '', lat: Number(m[1]), lng: Number(m[2]) } : null;
  }
  if (Array.isArray(x)) return okPoint(asPoint(x)) ? { id: '', name: '', lat: Number(x[0]), lng: Number(x[1]) } : null;
  if (typeof x === 'object') {
    const p = asPoint(x);
    if (okPoint(p)) return { id: x.id != null ? String(x.id).slice(0, 20) : '', name: String(x.name || '').replace(/[<>]/g, '').slice(0, 80), lat: p[0], lng: p[1] };
    if (x.id != null) return resolveStop(String(x.id), byId);
  }
  return null;
}
// the stops= parameter: "104,105" (ids) or "1.0,35.0;104;0.52,35.28"
export function splitStops(raw) {
  const s = String(raw || '').trim();
  if (!s) return [];
  if (/^\d{1,15}(,\d{1,15})*$/.test(s)) return s.split(',');
  return s.split(/[;|]/).map(x => x.trim()).filter(Boolean);
}
export async function projectsById(env, waitUntil, need) {
  if (!need) return new Map();
  let feed = null; try { feed = await getFeed(env, waitUntil); } catch (e) {}
  return new Map(((feed && feed.projects) || []).map(p => [String(p.id), p]));
}

async function advise(env, waitUntil, b) {
  const raw = Array.isArray(b.stops) ? b.stops : splitStops(b.stops);
  if (!raw.length) return json({ ok: false, reason: 'stops' }, 400);
  if (raw.length > MAX_STOPS) return json({ ok: false, reason: 'too-many', max: MAX_STOPS }, 400);
  const needFeed = raw.concat(b.from != null ? [b.from] : []).some(x => /^\d+$/.test(String(typeof x === 'object' && x && x.lat == null ? x.id : x)));
  const byId = await projectsById(env, waitUntil, needFeed);
  const stops = raw.map(x => resolveStop(x, byId));
  const unknown = raw.filter((x, i) => !stops[i]).map(x => String(typeof x === 'object' ? JSON.stringify(x) : x).slice(0, 40));
  if (unknown.length) return json({ ok: false, reason: 'stops', unknown }, 400);
  let from = null;
  if (b.from != null && b.from !== '') { from = resolveStop(b.from, byId); if (!from) return json({ ok: false, reason: 'from' }, 400); }
  for (const d of [b.date, b.until]) if (d != null && d !== '' && !/^\d{4}-\d{2}-\d{2}$/.test(String(d))) return json({ ok: false, reason: 'date' }, 400);
  const r = await tripAdvice(env, { from, stops, date: b.date || '', until: b.until || '', geometry: !(b.geometry === false || b.geometry === '0' || b.geometry === 0) });
  return json(r, r.ok ? 200 : 400);
}

export async function onRequestGet({ request, env, waitUntil }) {
  const chk = await checkPin(request, env);
  if (chk.res) return chk.res;
  const q = new URL(request.url).searchParams;
  try {
    return await advise(env, waitUntil, { stops: q.get('stops') || '', from: q.get('from') || null, date: q.get('date') || '', until: q.get('until') || '', geometry: q.get('geometry') });
  } catch (e) { console.error('trip:', e && e.message); return json({ ok: false, reason: 'down' }, 502); }
}

export async function onRequestPost({ request, env, waitUntil }) {
  const origin = request.headers.get('Origin');
  if (origin && new URL(origin).host !== new URL(request.url).host) return json({ ok: false, reason: 'origin' }, 403);
  const chk = await checkPin(request, env);
  if (chk.res) return chk.res;
  let b; try { b = await request.json(); } catch (e) { return json({ ok: false, reason: 'bad-request' }, 400); }
  if (!b || typeof b !== 'object') return json({ ok: false, reason: 'bad-request' }, 400);
  try {
    if (b.action === 'actual') {
      let key = String(b.leg || '');
      if (!key) { const a = asPoint(b.from), c = asPoint(b.to); if (!okPoint(a) || !okPoint(c)) return json({ ok: false, reason: 'place' }, 400); key = legKey(a, c); }
      if (!okLegKey(key)) return json({ ok: false, reason: 'leg' }, 400);
      if (env.FEED) {
        const rk = 'rl:tripact:' + (request.headers.get('CF-Connecting-IP') || 'unknown'), n = Number(await env.FEED.get(rk)) || 0;
        if (n >= MAX_ACTUAL_PER_HOUR) return json({ ok: false, reason: 'rate' }, 429);
        await env.FEED.put(rk, String(n + 1), { expirationTtl: 3600 });
      }
      const r = await recordActual(env, key, b.minutes, { level: ['dry', 'light', 'wet', 'heavy'].includes(b.level) ? b.level : 'dry' });
      return json(Object.assign({ leg: key }, r), r.ok ? 200 : r.reason === 'no-storage' ? 503 : 400);
    }
    return await advise(env, waitUntil, b);
  } catch (e) { console.error('trip:', e && e.message); return json({ ok: false, reason: 'down' }, 502); }
}

export function onRequest() { return json({ ok: false, reason: 'method' }, 405); }
