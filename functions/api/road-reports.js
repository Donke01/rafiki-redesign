// /api/road-reports: road reports from the field (lib/road-reports.js), for the RAFIKI team (same sign-in as /api/pin).
//   GET  /api/road-reports?s=0.4&w=34.9&n=1.2&e=35.4        active reports in a box (south, west, north, east; at most 3° a side)
//   GET  /api/road-reports?route=0.514,35.27;0.6,35.2;…[&m=150]   active reports within m metres (50–1000) of a line (≤ 400 points)
//   GET  /api/road-reports?near=1.01,35.0[&km=5]             active reports within km (0.1–50) of a point, nearest first
//   POST /api/road-reports { action: "add", kind, note, lat, lng, acc, photo }   kind: potholes | flooded | impassable_when_wet |
//                          under_construction | bridge_out | good_tarmac | slow_murram; photo: an optional photo id
//        { action: "confirm", rid }             still there: restarts the clock
//        { action: "clear", rid, why }          fixed | gone | wrong (the reporter, or an admin)
import { checkPin } from './pin.js';
import { allReports, inBox, nearRoute, nearPoint, addReport, confirmReport, clearReport, pub, KINDS, TTL_DAYS } from '../../lib/road-reports.js';

function json(body, status) {
  return new Response(JSON.stringify(body), { status: status || 200, headers: { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff', 'X-Robots-Tag': 'noindex' } });
}
const bad = () => json({ ok: false, reason: 'bad-request' }, 400);
const pt = s => { const m = /^\s*(-?\d{1,2}(?:\.\d+)?),\s*(-?\d{1,3}(?:\.\d+)?)\s*$/.exec(String(s || '')); return m ? [Number(m[1]), Number(m[2])] : null; };
const okPt = p => p && Math.abs(p[0]) <= 90 && Math.abs(p[1]) <= 180;
const out = (list, extra) => json(Object.assign({ ok: true, kinds: KINDS, ttlDays: TTL_DAYS, reports: list.map(r => Object.assign(pub(r), r.distM != null ? { distM: r.distM } : {}, r.alongKm != null ? { alongKm: r.alongKm, kmBeforeEnd: r.kmBeforeEnd } : {})) }, extra || {}));

export async function onRequestGet({ request, env }) {
  const chk = await checkPin(request, env);
  if (chk.res) return chk.res;
  const q = new URL(request.url).searchParams, list = await allReports(env);
  if (q.get('route')) {
    const parts = String(q.get('route')).split(/[;|]/).filter(Boolean);
    if (parts.length < 1 || parts.length > 400) return bad();
    const line = parts.map(pt);
    if (!line.every(okPt)) return bad();
    const m = q.get('m') ? Number(q.get('m')) : 150;
    if (!isFinite(m) || m < 50 || m > 1000) return bad();
    return out(nearRoute(list, line, { m }));
  }
  if (q.get('near')) {
    const p = pt(q.get('near')), km = q.get('km') ? Number(q.get('km')) : 5;
    if (!okPt(p) || !isFinite(km) || km < 0.1 || km > 50) return bad();
    return out(nearPoint(list, p, km));
  }
  const raw = ['s', 'w', 'n', 'e'].map(k => q.get(k));
  if (raw.some(v => v === null || v.trim() === '')) return bad();
  const b = raw.map(Number);
  if (b.some(v => !isFinite(v))) return bad();
  const [s, w, n, e] = b;
  if (s > n || w > e || n - s > 3 || e - w > 3 || Math.abs(s) > 90 || Math.abs(n) > 90 || Math.abs(w) > 180 || Math.abs(e) > 180) return bad();
  return out(inBox(list, { s, w, n, e }));
}

export async function onRequestPost({ request, env }) {
  const origin = request.headers.get('Origin');
  if (origin && new URL(origin).host !== new URL(request.url).host) return json({ ok: false, reason: 'origin' }, 403);
  const chk = await checkPin(request, env);
  if (chk.res) return chk.res;
  if (!env.FEED) return json({ ok: false, reason: 'no-storage' }, 503);
  let b; try { b = await request.json(); } catch (e) { return bad(); }
  if (!b || typeof b !== 'object') return bad();
  const rid = String(b.rid || '').replace(/[^a-z0-9-]/gi, '').slice(0, 20);
  let r;
  if (b.action === 'add') r = await addReport(env, chk, b, request.headers.get('CF-Connecting-IP') || 'unknown');
  else if (b.action === 'confirm' && rid) r = await confirmReport(env, chk, rid);
  else if (b.action === 'clear' && rid) r = await clearReport(env, chk, rid, String(b.why || 'fixed'));
  else return bad();
  return json(r.body, r.status);
}

export function onRequest() { return json({ ok: false, reason: 'method' }, 405); }
