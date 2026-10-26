// Drive times between every pair of places on a trip, for ordering the stops: one OpenRouteService matrix request
// (POST /v2/matrix/driving-car, durations and distances) instead of a route per pair. Same key as the routes
// (ORS_API_KEY). The free plan allows about 500 matrix requests a day (up to 3,500 pairs each); ORS_MATRIX_DAILY_LIMIT
// (default 400) stops before that. Kept in KV for 30 days, keyed by the places rounded to ~110 m.
// Without a key, over the limit, or when ORS fails, the matrix is built from straight lines (src: "estimate"),
// the same fallback the trip advice uses; the day plans are then refined with real legs from lib/trip.js.
import { getJson, putJson } from './pin-store.js';
import { estimate } from './itin-plan.js';

export const MATRIX_URL = 'https://api.heigit.org/openrouteservice/v2/matrix/driving-car';
export const MATRIX_TTL = 30 * 86400;
export const MATRIX_MAX = 50;              // places per request (50 × 50 = 2,500 pairs)
export const MATRIX_DAILY = 400;

const r3 = v => Number(v).toFixed(3);
async function hash(s) { const d = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(s)); return [...new Uint8Array(d)].slice(0, 12).map(b => b.toString(16).padStart(2, '0')).join(''); }

function estimated(keys, pts) {
  const min = keys.map(a => keys.map(b => (a === b ? 0 : estimate(pts[a], pts[b]).min)));
  const km = keys.map(a => keys.map(b => (a === b ? 0 : estimate(pts[a], pts[b]).km)));
  return { keys, min, km, src: 'estimate' };
}
async function spend(env, now) {
  if (!env.FEED) return true;
  const k = 'orsmatrix:' + new Date((now || Date.now()) + 3 * 3600e3).toISOString().slice(0, 10);
  const n = Number(await env.FEED.get(k)) || 0, lim = Number(env.ORS_MATRIX_DAILY_LIMIT) || MATRIX_DAILY;
  if (n >= lim) return false;
  await env.FEED.put(k, String(n + 1), { expirationTtl: 2 * 86400 });
  return true;
}

/** matrixFor(env, keys, pts) → { keys, min: [[minutes]], km: [[km]], src: "ors"|"estimate", reason? } */
export async function matrixFor(env, keys, pts, now) {
  const key = String(env.ORS_API_KEY || '').trim();
  if (!key || keys.length < 2) return Object.assign(estimated(keys, pts), key ? {} : { reason: 'no-key' });
  if (keys.length > MATRIX_MAX) return Object.assign(estimated(keys, pts), { reason: 'too-many' });
  const ck = 'itinmatrix:' + await hash(keys.map(k => r3(pts[k][0]) + ',' + r3(pts[k][1])).join(';'));
  const hit = await getJson(env, ck, null);
  if (hit && hit.min && hit.min.length === keys.length) return { keys, min: hit.min, km: hit.km, src: 'ors', cached: true };
  if (!await spend(env, now)) return Object.assign(estimated(keys, pts), { reason: 'limit' });
  let r;
  try {
    r = await fetch(MATRIX_URL, { method: 'POST', headers: { Authorization: key, 'Content-Type': 'application/json; charset=utf-8', Accept: 'application/json' },
      body: JSON.stringify({ locations: keys.map(k => [pts[k][1], pts[k][0]]), metrics: ['duration', 'distance'], units: 'km' }) });
  } catch (e) { return Object.assign(estimated(keys, pts), { reason: 'down' }); }
  if (!r.ok) return Object.assign(estimated(keys, pts), { reason: r.status === 401 || r.status === 403 ? 'key' : r.status === 429 ? 'rate' : 'down' });
  let j = null; try { j = await r.json(); } catch (e) {}
  if (!j || !Array.isArray(j.durations) || j.durations.length !== keys.length) return Object.assign(estimated(keys, pts), { reason: 'down' });
  // a pair ORS can't route (null) falls back to the straight-line estimate for that pair only
  const min = j.durations.map((row, i) => row.map((v, k) => (v == null ? estimate(pts[keys[i]], pts[keys[k]]).min : Math.round(v / 60))));
  const km = (j.distances || j.durations).map((row, i) => row.map((v, k) => (j.distances && v != null ? Math.round(v * 10) / 10 : estimate(pts[keys[i]], pts[keys[k]]).km)));
  await putJson(env, ck, { min, km, at: new Date(now || Date.now()).toISOString() }, { expirationTtl: MATRIX_TTL });
  return { keys, min, km, src: 'ors', cached: false };
}
