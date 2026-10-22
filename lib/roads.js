// Roads for planned visits (Trip advice: lib/trip.js): the driving route between two places from
// OpenRouteService (https://openrouteservice.org), with how much of it is tarmac and how much is murram.
//
// Key: ORS_API_KEY (a Cloudflare secret; a free account at openrouteservice.org gives about 2,000 routes a
// day and 40 a minute). Without it routeLeg() answers { ok: false, reason: 'no-key' } and the trip advice
// falls back to straight-line distances (lib/trip.js), so weather still works.
//
// The request: POST /v2/directions/driving-car/geojson with extra_info ["surface", "waytype"]. ORS answers
// each extra as values: [[fromIndex, toIndex, code], …], where the indexes are points of the route line,
// and summary: [{ value, distance (m), amount (%) }]. The codes (ORS docs, api-reference/endpoints/
// directions/extra-info/surface and …/waytype):
//   surface: 0 Unknown · 1 Paved · 2 Unpaved · 3 Asphalt · 4 Concrete · 6 Metal · 7 Wood · 8 Compacted gravel ·
//            10 Gravel · 11 Dirt · 12 Ground · 13 Ice · 14 Paving stones · 15 Sand · 17 Grass · 18 Grass paver
//            (5 Cobblestone, 9 Fine gravel and 16 Woodchips were removed in 2024; older servers may still send them)
//   waytype: 0 Unknown · 1 State road (trunk, primary, motorway) · 2 Road (secondary, tertiary, unclassified) ·
//            3 Street (residential, service) · 4 Path · 5 Track · 6 Cycleway · 7 Footway · 8 Steps · 9 Ferry · 10 Construction
// Some ORS versions answer the waytype extra as "waytypes": both are read.
//
// OpenStreetMap often has no surface on rural roads in Kenya, so code 0 is common. Unknown stretches are
// kept as unknown in the breakdown, but also guessed from the way type: an untagged State road is most
// likely tarmac, an untagged track or path is most likely murram (`likely`); the trip model uses the guess.
//
// Each route is kept in KV for ROUTE_TTL (30 days), keyed by both ends rounded to 0.001° (about 110 m).
// Failed routes (no road near a point) are kept for a day so they don't use up the daily allowance.
// ORS_DAILY_LIMIT (default 1800) stops calls for the rest of the day (Nairobi time) before the free plan's 2,000.
import { getJson, putJson } from './pin-store.js';

export const ORS_URL = 'https://api.heigit.org/openrouteservice/v2/directions/driving-car/geojson';
export const ROUTE_TTL = 30 * 86400;
export const FAIL_TTL = 86400;
export const SNAP_M = 3000;                 // ORS looks this far (m) from each end for a road (its default is 350 m)
export const MAX_POINTS = 200;              // simplified geometry
export const DAILY_LIMIT = 1800;

export const SURFACE = { 0: 'Unknown', 1: 'Paved', 2: 'Unpaved', 3: 'Asphalt', 4: 'Concrete', 5: 'Cobblestone', 6: 'Metal', 7: 'Wood', 8: 'Compacted gravel', 9: 'Fine gravel', 10: 'Gravel', 11: 'Dirt', 12: 'Ground', 13: 'Ice', 14: 'Paving stones', 15: 'Sand', 16: 'Woodchips', 17: 'Grass', 18: 'Grass paver' };
export const WAYTYPE = { 0: 'Unknown', 1: 'State road', 2: 'Road', 3: 'Street', 4: 'Path', 5: 'Track', 6: 'Cycleway', 7: 'Footway', 8: 'Steps', 9: 'Ferry', 10: 'Construction' };
// paved: a car goes at road speed. Metal and wood are bridges and decks. Unpaved: murram, gravel, earth.
const PAVED = new Set([1, 3, 4, 5, 6, 7, 14]);
const UNPAVED = new Set([2, 8, 9, 10, 11, 12, 13, 15, 16, 17, 18]);
export const surfaceClass = code => (PAVED.has(Number(code)) ? 'paved' : UNPAVED.has(Number(code)) ? 'unpaved' : 'unknown');
// the guess for an unknown surface, from the way type
export const guessFromWaytype = wt => (Number(wt) === 1 ? 'paved' : [4, 5].includes(Number(wt)) ? 'unpaved' : 'unknown');

// ---- geometry ([lat, lng] everywhere in this app; ORS and GeoJSON use [lng, lat]) ---------------------
export function metres(a, b) {
  const R = 6371000, r = x => x * Math.PI / 180;
  const dLa = r(b[0] - a[0]), dLo = r(b[1] - a[1]);
  const h = Math.sin(dLa / 2) ** 2 + Math.cos(r(a[0])) * Math.cos(r(b[0])) * Math.sin(dLo / 2) ** 2;
  return 2 * R * Math.asin(Math.sqrt(h));
}
// a flat x/y in metres around `ref` (good to well under 1 m over a few km, near the equator)
const flat = (p, ref) => { const k = 111320; return [(p[1] - ref[1]) * k * Math.cos(ref[0] * Math.PI / 180), (p[0] - ref[0]) * k]; };
/** Distance (m) from p to the segment a–b, and how far along the segment (0..1) the nearest point is. */
export function toSegment(p, a, b) {
  const P = flat(p, a), B = flat(b, a), L2 = B[0] * B[0] + B[1] * B[1];
  let t = L2 ? (P[0] * B[0] + P[1] * B[1]) / L2 : 0; t = Math.max(0, Math.min(1, t));
  const dx = P[0] - t * B[0], dy = P[1] - t * B[1];
  return { d: Math.sqrt(dx * dx + dy * dy), t };
}
/** Nearest point of a line [[lat,lng]…] to p: { d (m), alongKm (from the start), i (segment) }. */
export function nearLine(p, line) {
  if (!line || !line.length) return { d: Infinity, alongKm: 0, i: -1 };
  if (line.length === 1) return { d: metres(p, line[0]), alongKm: 0, i: 0 };
  let best = { d: Infinity, alongKm: 0, i: -1 }, run = 0;
  for (let i = 1; i < line.length; i++) {
    const seg = metres(line[i - 1], line[i]), s = toSegment(p, line[i - 1], line[i]);
    if (s.d < best.d) best = { d: s.d, alongKm: (run + s.t * seg) / 1000, i: i - 1 };
    run += seg;
  }
  return best;
}
export function lineKm(line) { let m = 0; for (let i = 1; i < (line || []).length; i++) m += metres(line[i - 1], line[i]); return m / 1000; }
/** The point halfway along a line. */
export function midpoint(line) {
  if (!line || !line.length) return null;
  const half = lineKm(line) * 500; let run = 0;
  for (let i = 1; i < line.length; i++) {
    const seg = metres(line[i - 1], line[i]);
    if (run + seg >= half) { const t = seg ? (half - run) / seg : 0; return [line[i - 1][0] + t * (line[i][0] - line[i - 1][0]), line[i - 1][1] + t * (line[i][1] - line[i - 1][1])]; }
    run += seg;
  }
  return line[line.length - 1];
}
// Douglas–Peucker, keeping the ends; `tol` in metres
function dp(line, tol) {
  const keep = new Uint8Array(line.length); keep[0] = keep[line.length - 1] = 1;
  const stack = [[0, line.length - 1]];
  while (stack.length) {
    const [a, b] = stack.pop(); let bi = -1, bd = tol;
    for (let i = a + 1; i < b; i++) { const d = toSegment(line[i], line[a], line[b]).d; if (d > bd) { bd = d; bi = i; } }
    if (bi > 0) { keep[bi] = 1; stack.push([a, bi], [bi, b]); }
  }
  return line.filter((_, i) => keep[i]);
}
/** A simpler line with at most `max` points; returns { line, tolM } (the largest error allowed, in metres). */
export function simplify(line, max = MAX_POINTS) {
  if (!line || line.length <= max) return { line: (line || []).slice(), tolM: 0 };
  let tol = 5, out = dp(line, tol);
  while (out.length > max && tol < 5000) { tol *= 1.6; out = dp(line, tol); }
  return { line: out, tolM: Math.round(tol) };
}

const r5 = v => Math.round(v * 1e5) / 1e5, r1 = v => Math.round(v * 10) / 10;
const end3 = p => Number(p[0]).toFixed(3) + ',' + Number(p[1]).toFixed(3);
export const routeKey = (from, to) => 'route:v1:' + end3(from) + '>' + end3(to);
export const asPoint = p => (Array.isArray(p) ? [Number(p[0]), Number(p[1])] : p && typeof p === 'object' ? [Number(p.lat), Number(p.lng != null ? p.lng : p.lon)] : null);
export const okPoint = p => !!p && isFinite(p[0]) && isFinite(p[1]) && Math.abs(p[0]) <= 90 && Math.abs(p[1]) <= 180 && !(p[0] === 0 && p[1] === 0);

/**
 * ORS GeoJSON answer → the leg: { km, baseMin, surface: { pavedKm, unpavedKm, unknownKm, likely: { pavedKm, unpavedKm } },
 *   minutes: { paved, unpaved, unknown }, unpaved: [{ from, to, km, startKm, endKm, surface }], geometry, tolM, snapM: [start, end] }
 * `minutes` splits ORS's own driving time between the classes (from its steps; by distance when there are none),
 * with unknown stretches moved to paved/unpaved when the way type makes the guess.
 */
export function parseOrs(j, from, to) {
  const f = j && j.features && j.features[0];
  const coords = f && f.geometry && f.geometry.coordinates;
  if (!f || !Array.isArray(coords) || coords.length < 2) return null;
  const props = f.properties || {}, sum = props.summary || {}, ex = props.extras || {};
  const line = coords.map(c => [Number(c[1]), Number(c[0])]);
  const n = line.length - 1, segM = new Float64Array(n);
  for (let i = 0; i < n; i++) segM[i] = metres(line[i], line[i + 1]);
  const lineM = segM.reduce((s, v) => s + v, 0) || 1;
  const totalM = Number(sum.distance) > 0 ? Number(sum.distance) : lineM;
  const scale = totalM / lineM;                       // ORS's distance is the truth; the line just spreads it
  const fill = (vals, dflt) => {
    const a = new Int16Array(n).fill(dflt);
    (vals || []).forEach(v => { if (!Array.isArray(v) || v.length < 3) return; for (let i = Math.max(0, v[0]); i < Math.min(n, v[1]); i++) a[i] = Number(v[2]); });
    return a;
  };
  const surf = fill(ex.surface && ex.surface.values, 0);
  const wt = fill((ex.waytype || ex.waytypes || {}).values, 0);
  // time per piece: each ORS step's duration spread along its piece of line by length
  const secs = new Float64Array(n), steps = ((props.segments || [])[0] || {}).steps || [];
  let timed = false;
  steps.forEach(s => {
    const wp = s.way_points || [], a = Math.max(0, wp[0] | 0), b = Math.min(n, wp[1] | 0), dur = Number(s.duration) || 0;
    let len = 0; for (let i = a; i < b; i++) len += segM[i];
    if (b > a && len > 0) { for (let i = a; i < b; i++) secs[i] = dur * segM[i] / len; timed = true; }
  });
  const totalS = Number(sum.duration) > 0 ? Number(sum.duration) : 0;
  if (!timed) for (let i = 0; i < n; i++) secs[i] = totalS * segM[i] / lineM;
  const km = { paved: 0, unpaved: 0, unknown: 0 }, likely = { paved: 0, unpaved: 0 }, min = { paved: 0, unpaved: 0, unknown: 0 };
  const cls = new Array(n);
  for (let i = 0; i < n; i++) {
    const c = surfaceClass(surf[i]), k = segM[i] * scale / 1000;
    km[c] += k; cls[i] = c;
    let m = c;
    if (c === 'unknown') { const g = guessFromWaytype(wt[i]); if (g !== 'unknown') { likely[g] += k; m = g; } }
    min[m] += secs[i] / 60;
  }
  // the unpaved stretches (and untagged tracks), joined when less than 300 m apart
  const pieces = []; let run = 0;
  for (let i = 0; i < n; i++) {
    const k = segM[i] * scale / 1000, isU = cls[i] === 'unpaved' || (cls[i] === 'unknown' && guessFromWaytype(wt[i]) === 'unpaved');
    if (isU) {
      const last = pieces[pieces.length - 1];
      if (last && run - last.endKm < 0.3) { last.endKm = run + k; last.to = line[i + 1]; last.km = last.endKm - last.startKm; if (cls[i] === 'unknown') last.guess = true; last.codes.add(surf[i]); }
      else pieces.push({ from: line[i], to: line[i + 1], startKm: run, endKm: run + k, km: k, guess: cls[i] === 'unknown', codes: new Set([surf[i]]) });
    }
    run += k;
  }
  const unpaved = pieces.filter(p => p.km >= 0.05).slice(0, 40).map(p => ({
    from: [r5(p.from[0]), r5(p.from[1])], to: [r5(p.to[0]), r5(p.to[1])], km: r1(p.km), startKm: r1(p.startKm), endKm: r1(p.endKm),
    surface: [...p.codes].filter(c => c).map(c => SURFACE[c] || String(c)).join(', ') || 'Unknown (track)', guess: !!p.guess
  }));
  const simple = simplify(line, MAX_POINTS);
  const snapM = from && to ? [Math.round(metres(from, line[0])), Math.round(metres(to, line[n]))] : [0, 0];
  return {
    km: r1(totalM / 1000), baseMin: Math.round(totalS / 60),
    surface: { pavedKm: r1(km.paved), unpavedKm: r1(km.unpaved), unknownKm: r1(km.unknown), likely: { pavedKm: r1(likely.paved), unpavedKm: r1(likely.unpaved) } },
    minutes: { paved: r1(min.paved), unpaved: r1(min.unpaved), unknown: r1(min.unknown) },
    unpaved, geometry: simple.line.map(p => [r5(p[0]), r5(p[1])]), tolM: simple.tolM, snapM
  };
}

async function spend(env, now) {
  if (!env.FEED) return true;
  const k = 'orsday:' + new Date((now || Date.now()) + 3 * 3600e3).toISOString().slice(0, 10);
  const n = Number(await env.FEED.get(k)) || 0, lim = Number(env.ORS_DAILY_LIMIT) || DAILY_LIMIT;
  if (n >= lim) return false;
  await env.FEED.put(k, String(n + 1), { expirationTtl: 2 * 86400 });
  return true;
}

/**
 * routeLeg(env, from, to) with from/to as [lat, lng] or { lat, lng }.
 * → { ok: true, roads: true, cached, …parseOrs } or { ok: false, reason: 'no-key' | 'place' | 'no-route' | 'key' | 'rate' | 'limit' | 'down' }
 */
export async function routeLeg(env, from, to, now) {
  const a = asPoint(from), b = asPoint(to);
  if (!okPoint(a) || !okPoint(b)) return { ok: false, reason: 'place' };
  const key = String(env.ORS_API_KEY || '').trim();
  if (!key) return { ok: false, reason: 'no-key' };
  const ck = routeKey(a, b), hit = await getJson(env, ck, null);
  if (hit) return hit.ok ? Object.assign({ ok: true, roads: true, cached: true }, hit.leg) : { ok: false, reason: hit.reason, cached: true };
  if (!await spend(env, now)) return { ok: false, reason: 'limit' };
  let r;
  try {
    r = await fetch(ORS_URL, {
      method: 'POST',
      headers: { Authorization: key, 'Content-Type': 'application/json; charset=utf-8', Accept: 'application/geo+json, application/json' },
      body: JSON.stringify({ coordinates: [[a[1], a[0]], [b[1], b[0]]], extra_info: ['surface', 'waytype'], radiuses: [SNAP_M, SNAP_M], units: 'm', language: 'en' })
    });
  } catch (e) { console.error('ors:', e && e.message); return { ok: false, reason: 'down' }; }
  if (r.status === 401 || r.status === 403) return { ok: false, reason: 'key' };
  if (r.status === 429) return { ok: false, reason: 'rate' };
  let j = null; try { j = await r.json(); } catch (e) {}
  if (!r.ok) {
    // 2004 too far, 2009 no route, 2010 no road near a point: the answer won't change tomorrow, so remember it
    const code = j && j.error && Number(j.error.code);
    if (r.status === 404 || [2004, 2009, 2010].includes(code)) { await putJson(env, ck, { ok: false, reason: 'no-route' }, { expirationTtl: FAIL_TTL }); return { ok: false, reason: 'no-route' }; }
    console.error('ors:', r.status, code);
    return { ok: false, reason: 'down' };
  }
  const leg = parseOrs(j, a, b);
  if (!leg) return { ok: false, reason: 'down' };
  await putJson(env, ck, { ok: true, at: new Date(now || Date.now()).toISOString(), leg }, { expirationTtl: ROUTE_TTL });
  return Object.assign({ ok: true, roads: true, cached: false }, leg);
}
