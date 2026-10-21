// Live team map (Office › Team map), privacy first.
//
// Each person chooses in Me › Settings › Security and alerts to share their location with the office during
// their work hours (off unless they turn it on). Their app then sends where it is at most every 10 minutes,
// only while it is open (the Android app also from its background check, only with the location permission
// it already has). Only the LATEST point per person is kept, as one KV key that expires by itself after
// 12 hours: there is never a history of where anyone has been. Turning sharing off deletes the point.
// Only admins can read the points (GET /api/admin?view=teammap); field users never see anyone's position.
//
// Also here: Office › Plan a week (GET /api/admin?view=plan), built on lib/pin-plan.js.
import { getJson, team, sameName } from './pin-store.js';
import { candidates, planWeek, isWater } from './pin-plan.js';

export const HERE_TTL = 12 * 3600;            // seconds a point is kept
const MIN_GAP = 120 * 1000;                   // a second point from the same person within 2 minutes is ignored
const MAX_PER_HOUR = 40;                      // points per connection per hour
const key = name => 'pinhere:' + String(name || '').trim().toLowerCase().replace(/[^\p{L}\p{N} ._'-]/gu, '').slice(0, 60);
const round = (v, n) => Math.round(v * 10 ** n) / 10 ** n;

// POST /api/pin { action: "here", lat, lng, acc }  or  { action: "here", off: true }
export async function postHere(env, chk, b, ip) {
  if (!chk.who) return { status: 400, body: { ok: false, reason: 'own-pin' } };
  if (!env.FEED) return { status: 503, body: { ok: false, reason: 'not-set-up' } };
  const k = key(chk.who);
  if (b.off) { await env.FEED.delete(k); return { status: 200, body: { ok: true, off: true } }; }
  const lat = Number(b.lat), lng = Number(b.lng), acc = Number(b.acc) || 0;
  if (!isFinite(lat) || !isFinite(lng) || Math.abs(lat) > 90 || Math.abs(lng) > 180 || (lat === 0 && lng === 0) || acc < 0 || acc > 100000) return { status: 400, body: { ok: false, reason: 'place' } };
  const rk = 'rl:pinhere:' + ip, n = Number(await env.FEED.get(rk)) || 0;
  if (n >= MAX_PER_HOUR) return { status: 429, body: { ok: false, reason: 'rate' } };
  const prev = await env.FEED.get(k, 'json');
  if (prev && Date.now() - Date.parse(prev.at) < MIN_GAP) return { status: 200, body: { ok: true, skipped: true, at: prev.at } };
  const at = new Date().toISOString();
  await env.FEED.put(k, JSON.stringify({ name: chk.who, lat: round(lat, 5), lng: round(lng, 5), acc: Math.round(acc), at }), { expirationTtl: HERE_TTL });
  await env.FEED.put(rk, String(n + 1), { expirationTtl: 3600 });
  return { status: 200, body: { ok: true, at } };
}

// midnight in East Africa (UTC+3), as an ISO time: "today" for the visit pins
function todayFrom(now) { const d = new Date(now + 3 * 3600e3); d.setUTCHours(0, 0, 0, 0); return new Date(d.getTime() - 3 * 3600e3).toISOString(); }

// GET /api/admin?view=teammap → { people: [{ name, lat, lng, acc, at, phone, home }], visits: [{ id, by, at, s, t }] }
export async function teamMap(env, now) {
  const t0 = now || Date.now(), people = [];
  let cursor;
  for (let i = 0; i < 5; i++) {
    const page = await env.FEED.list({ prefix: 'pinhere:', cursor });
    for (const k of page.keys) {
      const p = await env.FEED.get(k.name, 'json');
      if (p && p.name && t0 - Date.parse(p.at) < HERE_TTL * 1000) people.push(p);
    }
    if (page.list_complete) break; cursor = page.cursor;
  }
  // phone numbers: what each person set in Settings, or what the office added in Team
  const t = await team(env), prof = await getJson(env, 'pinprofiles', {});
  people.forEach(p => {
    const m = t.find(x => sameName(x.name, p.name)) || {}, pk = Object.keys(prof).find(n => sameName(n, p.name)), pr = (pk && prof[pk]) || {};
    p.phone = pr.phone || m.phone || ''; p.home = pr.home || m.home || '';
  });
  const from = todayFrom(t0);
  const visits = (await getJson(env, 'pinact', [])).filter(e => e.a === 'visit' && e.at >= from).map(e => ({ id: e.id, by: e.by, at: e.at, s: e.s || '', t: e.t || '' }));
  return { people: people.sort((a, b) => a.name.localeCompare(b.name)), visits, since: from };
}

// GET /api/admin?view=plan&ck=ke&area=Eldoret&days=5&per=5
export async function planView(env, q, feed) {
  const ck = /^(ke|tz|ug|mw)$/.test(q.get('ck') || '') ? q.get('ck') : '';
  if (!ck) return { status: 400, body: { ok: false, reason: 'bad-request' } };
  const area = String(q.get('area') || '').replace(/[<>]/g, '').trim().slice(0, 60);
  const projects = (feed && feed.projects || []).filter(p => p.ck === ck && p.gps);
  const visits = await getJson(env, 'pinlastvisit', {}), repairs = await getJson(env, 'pinrepairs', {}), assigned = await getJson(env, 'pinassign', []);
  // water test due: a water point visited with the app whose last 5 visits have no water test in the last year
  const tests = new Set(), cut = Date.now() - 365 * 864e5;
  const water = projects.filter(p => isWater(p) && visits[p.id] && (!area || String(p.area || '').toLowerCase().indexOf(area.toLowerCase()) >= 0)).slice(0, 60);
  for (const p of water) {
    const recs = (await env.FEED.get('pinvisit:' + p.id, 'json')) || [];
    if (!recs.some(r => r.quality && Date.parse(r.at) > cut)) tests.add(String(p.id));
  }
  const cands = candidates({ projects, visits, repairs, assigned, tests, ck, area });
  const plan = planWeek(cands, { days: q.get('days'), per: q.get('per') });
  return { status: 200, body: Object.assign({ ok: true, total: cands.length }, plan) };
}
