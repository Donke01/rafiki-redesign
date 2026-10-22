// Road reports from the field: a field officer drops a note about the road where they stand (potholes, a
// flooded dip, a broken bridge…). The trip advice (lib/trip.js) warns about reports near the route.
//
// Kept in KV (FEED) as one list, "roadreports" (newest last, at most MAX_REPORTS). Each report:
//   { rid, kind, note, lat, lng, by, at, seen, photo, until, cleared: { by, at, why } | null, confirms: [names] }
// `seen` is the last time someone saw it (adding it, or "still there"); `until` is when it stops counting.
//
// How long each kind counts, from the last time someone saw it (TTL_DAYS, null = until someone clears it):
//   flooded               7 days   water goes down within days; if it floods again, someone reports it again
//   potholes            120 days   potholes last a season; county patching is slow, but they do get filled
//   under_construction  180 days   road works in Kenya often run for months
//   slow_murram         365 days   murram stays murram until it is graded or tarmacked
//   good_tarmac         365 days   good news, for a year
//   impassable_when_wet   until cleared   a black-cotton-soil stretch or a drift stays a problem until it is fixed
//   bridge_out            until cleared   nobody should drive there until someone says it is repaired
// "Still there" (confirm) restarts the clock. Cleared reports are kept CLEARED_KEEP_DAYS for the record.
// Clearing: the person who reported it, or an admin.
import { getJson, putJson, sameName } from './pin-store.js';
import { nearLine, metres } from './roads.js';

export const KEY = 'roadreports';
export const MAX_REPORTS = 2000;
export const NEAR_M = 150;                 // a report counts for a route within this distance of the line
export const CLEARED_KEEP_DAYS = 60;
export const MAX_ADDS_PER_HOUR = 30;
export const TTL_DAYS = {
  potholes: 120, flooded: 7, impassable_when_wet: null, under_construction: 180, bridge_out: null, good_tarmac: 365, slow_murram: 365
};
export const KINDS = Object.keys(TTL_DAYS);
export const KIND_WORDS = {
  potholes: { en: 'Potholes', sw: 'Mashimo barabarani' },
  flooded: { en: 'Flooded road', sw: 'Barabara imefurika' },
  impassable_when_wet: { en: 'Impassable when wet', sw: 'Haipitiki wakati wa mvua' },
  under_construction: { en: 'Road works', sw: 'Barabara inajengwa' },
  bridge_out: { en: 'Bridge out', sw: 'Daraja limeharibika' },
  good_tarmac: { en: 'Good tarmac', sw: 'Lami nzuri' },
  slow_murram: { en: 'Slow murram', sw: 'Murram mbaya, mwendo wa polepole' }
};
const clean = (s, n) => String(s || '').replace(/[<>]/g, '').replace(/\s+/g, ' ').trim().slice(0, n);
const r5 = v => Math.round(v * 1e5) / 1e5;

export function untilOf(kind, seenISO) {
  const d = TTL_DAYS[kind];
  return d == null ? null : new Date(Date.parse(seenISO) + d * 864e5).toISOString();
}
export const isActive = (r, now) => !!r && !r.cleared && (!r.until || Date.parse(r.until) > (now || Date.now()));

export async function allReports(env) { return getJson(env, KEY, []); }
async function save(env, list, now) {
  const t0 = now || Date.now(), cut = t0 - CLEARED_KEEP_DAYS * 864e5;
  // drop cleared ones after a while, and expired ones a while after they ran out
  const keep = list.filter(r => r.cleared ? Date.parse(r.cleared.at) > cut : !r.until || Date.parse(r.until) > cut);
  await putJson(env, KEY, keep.slice(-MAX_REPORTS));
}
// what a phone gets: no confirm list, just how many
export const pub = r => ({ rid: r.rid, kind: r.kind, note: r.note, lat: r.lat, lng: r.lng, by: r.by, at: r.at, seen: r.seen || r.at, photo: r.photo || null, until: r.until, cleared: r.cleared || null, confirms: (r.confirms || []).length, words: KIND_WORDS[r.kind] });

/** Active reports inside a box (south, west, north, east). */
export function inBox(list, { s, w, n, e }, now) {
  return list.filter(r => isActive(r, now) && r.lat >= s && r.lat <= n && r.lng >= w && r.lng <= e);
}
/** Active reports within `m` metres of a line [[lat,lng]…]: each with distM, alongKm, kmBeforeEnd. Ordered along the line. */
export function nearRoute(list, line, { m = NEAR_M, now, totalKm } = {}) {
  if (!line || !line.length) return [];
  let total = totalKm; if (total == null) { total = 0; for (let i = 1; i < line.length; i++) total += metres(line[i - 1], line[i]) / 1000; }
  // a quick box first (m plus a little), then the exact distance
  const pad = (m + 50) / 111320;
  let s = 90, w = 180, n = -90, e = -180; line.forEach(p => { s = Math.min(s, p[0]); n = Math.max(n, p[0]); w = Math.min(w, p[1]); e = Math.max(e, p[1]); });
  const padLng = pad / Math.max(0.2, Math.cos(((s + n) / 2) * Math.PI / 180));
  return inBox(list, { s: s - pad, w: w - padLng, n: n + pad, e: e + padLng }, now)
    .map(r => { const x = nearLine([r.lat, r.lng], line); return Object.assign({}, r, { distM: Math.round(x.d), alongKm: Math.round(x.alongKm * 10) / 10, kmBeforeEnd: Math.max(0, Math.round((total - x.alongKm) * 10) / 10) }); })
    .filter(r => r.distM <= m)
    .sort((a, b) => a.alongKm - b.alongKm);
}
/** Active reports within `km` of a point (e.g. the last mile to a site), nearest first. */
export function nearPoint(list, p, km, now) {
  return list.filter(r => isActive(r, now)).map(r => Object.assign({}, r, { distM: Math.round(metres(p, [r.lat, r.lng])) }))
    .filter(r => r.distM <= km * 1000).sort((a, b) => a.distM - b.distM);
}

/** Add a report. chk: { who }. b: { kind, note, lat, lng, acc, photo }. → { status, body } */
export async function addReport(env, chk, b, ip, now) {
  if (!env.FEED) return { status: 503, body: { ok: false, reason: 'no-storage' } };
  const kind = String(b.kind || '');
  if (!KINDS.includes(kind)) return { status: 400, body: { ok: false, reason: 'kind' } };
  const lat = Number(b.lat), lng = Number(b.lng);
  if (!isFinite(lat) || !isFinite(lng) || Math.abs(lat) > 90 || Math.abs(lng) > 180 || (lat === 0 && lng === 0)) return { status: 400, body: { ok: false, reason: 'place' } };
  const photo = b.photo == null || b.photo === '' ? null : String(b.photo);
  if (photo && !/^[A-Za-z0-9_-]{1,64}$/.test(photo)) return { status: 400, body: { ok: false, reason: 'photo' } };
  const rk = 'rl:roadrep:' + (ip || 'unknown'), n = Number(await env.FEED.get(rk)) || 0;
  if (n >= MAX_ADDS_PER_HOUR) return { status: 429, body: { ok: false, reason: 'rate' } };
  await env.FEED.put(rk, String(n + 1), { expirationTtl: 3600 });
  const at = new Date(now || Date.now()).toISOString();
  const r = { rid: crypto.randomUUID().slice(0, 12), kind, note: clean(b.note, 300), lat: r5(lat), lng: r5(lng), acc: Math.max(0, Math.min(100000, Math.round(Number(b.acc) || 0))), by: chk.who || '', at, seen: at, photo, until: untilOf(kind, at), cleared: null, confirms: [] };
  const list = await allReports(env); list.push(r); await save(env, list, now);
  return { status: 200, body: { ok: true, report: pub(r) } };
}

/** "Still there": restarts the clock for that kind. */
export async function confirmReport(env, chk, rid, now) {
  const list = await allReports(env), r = list.find(x => x.rid === rid);
  if (!r || !isActive(r, now)) return { status: 404, body: { ok: false, reason: 'gone' } };
  const at = new Date(now || Date.now()).toISOString();
  r.seen = at; r.until = untilOf(r.kind, at);
  const who = chk.who || '';
  if (who && !(r.confirms || []).some(n => sameName(n, who))) r.confirms = (r.confirms || []).concat(who).slice(-20);
  await save(env, list, now);
  return { status: 200, body: { ok: true, report: pub(r) } };
}

/** Clear or mark fixed: the reporter or an admin. why: fixed | gone | wrong */
export async function clearReport(env, chk, rid, why, now) {
  const list = await allReports(env), r = list.find(x => x.rid === rid);
  if (!r || r.cleared) return { status: 404, body: { ok: false, reason: 'gone' } };
  if (!chk.admin && !(chk.who && sameName(chk.who, r.by))) return { status: 403, body: { ok: false, reason: 'not-yours' } };
  r.cleared = { by: chk.who || '', at: new Date(now || Date.now()).toISOString(), why: ['fixed', 'gone', 'wrong'].includes(why) ? why : 'fixed' };
  await save(env, list, now);
  return { status: 200, body: { ok: true, report: pub(r) } };
}
