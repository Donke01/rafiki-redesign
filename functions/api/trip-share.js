// /api/trip-share: the read-only data behind a private trip link (no sign-in). See docs/itinerary.md.
//   GET /api/trip-share?t=<token>   → { kind: "itin" | "group", updated, trip }   (ETag; 304 when unchanged, for live updates)
//   GET /api/trip-share?public=1    → { trips }  group trips the Office marked public (for trips.html)
// Field trips: names, times, places, km; no PINs, phone numbers, costs or notes. Group trips: the itinerary, packing list,
// what to expect, emergency contacts and the map; never participants' e-mails, phones, diet or medical notes, or payments.
// Unknown and revoked links get the same 404. Wrong links are limited per connection.
import { getJson } from '../../lib/pin-store.js';
import { load, publicItin, tokenShape, sha } from '../../lib/itin.js';
import { loadGroup, publicGroup, publicList } from '../../lib/group-trips.js';

const HEAD = { 'Content-Type': 'application/json; charset=utf-8', 'X-Content-Type-Options': 'nosniff', 'X-Robots-Tag': 'noindex, nofollow', 'Referrer-Policy': 'no-referrer' };
const json = (b, s, extra) => new Response(JSON.stringify(b), { status: s || 200, headers: Object.assign({ 'Cache-Control': 'private, no-store' }, HEAD, extra || {}) });
const MAX_BAD = 30, BURST = 120;
const hits = new Map();
function burst(ip) {
  const now = Date.now(), h = hits.get(ip);
  if (!h || now - h.t > 60000) { hits.set(ip, { t: now, n: 1 }); if (hits.size > 5000) hits.clear(); return false; }
  return ++h.n > BURST;
}

/** token → { kind, rec } or { res } (an answer to send) */
export async function lookupShare(request, env, token) {
  const ip = request.headers.get('CF-Connecting-IP') || 'unknown';
  if (burst(ip)) return { res: json({ ok: false, reason: 'rate' }, 429) };
  if (!env.FEED) return { res: json({ ok: false, reason: 'no-storage' }, 503) };
  const bk = 'rl:tripshare:' + ip, nbad = Number(await env.FEED.get(bk)) || 0;
  if (nbad >= MAX_BAD) return { res: json({ ok: false, reason: 'rate' }, 429) };
  const miss = async () => { await env.FEED.put(bk, String(nbad + 1), { expirationTtl: 3600 }); return { res: json({ ok: false, reason: 'not-found' }, 404) }; };
  if (!tokenShape(token)) return miss();
  const ref = await getJson(env, 'itinshare:' + await sha(token), null);
  if (!ref) return miss();
  const rec = ref.kind === 'group' ? await loadGroup(env, ref.id) : await load(env, ref.id);
  if (!rec || !rec.share || rec.share.t !== token) return miss();
  return { kind: ref.kind, rec };
}
export const etagOf = (kind, rec) => 'W/"' + kind + '-' + rec.id + '-' + (rec.v || 0) + '"';

export async function onRequestGet({ request, env }) {
  const q = new URL(request.url).searchParams, site = new URL(request.url).origin;
  if (q.get('public')) {
    const trips = env.FEED ? await publicList(env) : [];
    return new Response(JSON.stringify({ ok: true, trips }), { headers: Object.assign({}, HEAD, { 'Cache-Control': 'public, max-age=600', 'Access-Control-Allow-Origin': '*' }) });
  }
  const r = await lookupShare(request, env, q.get('t') || '');
  if (r.res) return r.res;
  const tag = etagOf(r.kind, r.rec);
  if ((request.headers.get('If-None-Match') || '') === tag) return new Response(null, { status: 304, headers: Object.assign({ ETag: tag, 'Cache-Control': 'private, no-store' }, HEAD) });
  const trip = r.kind === 'group' ? publicGroup(r.rec, site) : publicItin(r.rec);
  return json({ ok: true, kind: r.kind, updated: r.rec.updated, trip }, 200, { ETag: tag });
}

export function onRequest() { return json({ ok: false, reason: 'method' }, 405); }
