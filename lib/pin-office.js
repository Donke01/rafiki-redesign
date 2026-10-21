// Money and reach for the RAFIKI pin app's Office (Workers KV, binding FEED):
//
//  - Repair costs: what was actually spent on a repair, entered when "Parts ordered" or "Fixed" is saved.
//    Every entry is kept per project (pincost:<id>, append-only: date, what, estimate, actual, who), and a
//    small index of all entries (pincostidx, last 25 months) feeds Office › Costs with one KV read.
//  - People served: the latest "people using it" count from site visits per project (pinpeople), falling
//    back to the "people" column on the board (the website feed). A monthly total (pinpeoplehist) gives the
//    12-month trend; it is updated when visits are saved and by the monthly cron.
//  - Two-step approval for big repairs: above a limit (pinsettings.approveAbove, default KES 50,000) a repair
//    can't move to "Parts ordered" until a second admin, not the one who asked, approves the cost.
//
// The functions without `env` are pure (tests/office.test.mjs).
import { getJson, putJson, sameName } from './pin-store.js';

export const APPROVE_DEFAULT = 50000;
const DAY = 864e5;
const ORDER = ['reported', 'assigned', 'parts', 'fixed'];

export const kes = n => 'KES ' + Math.round(Number(n) || 0).toLocaleString('en-US');
// a whole number of shillings as typed ("12,500", " 800 "), or null
export function money(v, max = 100000000) {
  if (v === '' || v == null) return null;
  const n = Number(String(v).replace(/[,\s]/g, ''));
  return isFinite(n) && n >= 0 && n <= max ? Math.round(n) : null;
}
export const monthKey = t => new Date(t).toISOString().slice(0, 7);

// --- two-step approval ------------------------------------------------------------------------
// The repair's estimate: the field estimate or the approved budget, whichever is higher.
export function estOf(job) { return Math.max(Number(job && job.cost) || 0, Number(job && job.budget) || 0); }
export const stepAtOrAfter = (step, ref) => ORDER.indexOf(step || 'reported') >= ORDER.indexOf(ref);
// 'none' (small enough), 'needed', 'pending', 'approved' or 'declined'
export function approvalState(job, limit) {
  const est = estOf(job), a = job && job.approval;
  if (!(est > limit)) return 'none';
  if (a && a.status === 'pending') return 'pending';
  // an approval covers the amount approved; a higher estimate needs a new one
  if (a && (a.status === 'approved' || a.status === 'declined') && Number(a.est) >= est) return a.status;
  return 'needed';
}
// Why `step` can't be set on this job yet ('' = it can). Parts ordered (and Fixed from the Office,
// which skips over it) wait for the second approval; a job already past that point is not held back.
export function stepBlocked(job, step, limit) {
  if (!stepAtOrAfter(step, 'parts') || stepAtOrAfter(job && job.step, 'parts')) return '';
  const s = approvalState(job, limit);
  return s === 'none' || s === 'approved' ? '' : s;
}
// the person who asked can't approve their own request
export function canDecide(item, who) { return !!String(who || '').trim() && !sameName(item && item.by, who); }
export async function approveLimit(env) {
  const s = await getJson(env, 'pinsettings', {}), n = Number(s.approveAbove);
  return s.approveAbove != null && isFinite(n) && n >= 0 ? n : APPROVE_DEFAULT;
}
export async function setApproveLimit(env, n, by) {
  const s = await getJson(env, 'pinsettings', {});
  s.approveAbove = n; s.approveBy = by; s.approveAt = new Date().toISOString();
  await putJson(env, 'pinsettings', s);
}
// a pending approval on a job, and the review item for it
export function askApproval(job, by, now) {
  const rid = 'c' + crypto.randomUUID().slice(0, 11);
  job.approval = { status: 'pending', rid, by, at: now, est: estOf(job) };
  return rid;
}

// --- repair costs -----------------------------------------------------------------------------
// one cost entry: when, which repair (rid = when it was reported), what, the estimate then, what was spent, who
export function costRow(o) {
  const r = { at: o.at || new Date().toISOString(), rid: o.rid || o.at, what: String(o.what || '').slice(0, 120), est: o.est == null ? null : Math.round(Number(o.est) || 0), actual: Math.round(Number(o.actual) || 0), by: String(o.by || '').slice(0, 60) };
  if (o.step) r.step = o.step;
  if (o.receipt) r.receipt = true;
  return r;
}
export async function addCost(env, id, row, info) {
  if (!env.FEED) return;
  const k = 'pincost:' + id, list = await getJson(env, k, []);
  list.push(row); await putJson(env, k, list.slice(-500));
  const cut = Date.now() - 760 * DAY;
  const idx = (await getJson(env, 'pincostidx', [])).filter(x => Date.parse(x.at) > cut);
  idx.push(Object.assign({ id: String(id) }, row, info || {}));
  await putJson(env, 'pincostidx', idx.slice(-6000));
}
const isWater = p => !!(p && (p.water || p.cat === 'water' || /borehole|collection|water/i.test(p.type || '')));
// Office › Costs: spent this month and this year (UTC calendar), this year by country, the 10 water points
// that cost most to keep running (last 12 months), and the average spent per repair (last 12 months).
export function costStats(rows, projects, now = Date.now()) {
  const P = {}; (projects || []).forEach(p => { P[String(p.id)] = p; });
  const m = monthKey(now), y = m.slice(0, 4), from = now - 365 * DAY;
  let month = 0, year = 0, sum12 = 0;
  const byCountry = {}, per = {}, reps = new Set();
  (rows || []).forEach(r => {
    const a = Number(r.actual) || 0, t = Date.parse(r.at);
    if (!a || !isFinite(t) || t > now + DAY) return;
    const p = P[String(r.id)], ck = (p && p.ck) || r.ck || '';
    if (r.at.slice(0, 7) === m) month += a;
    if (r.at.slice(0, 4) === y) { year += a; if (ck) byCountry[ck] = (byCountry[ck] || 0) + a; }
    if (t >= from) {
      sum12 += a; reps.add(r.id + '|' + (r.rid || r.at));
      const w = per[r.id] || (per[r.id] = { id: String(r.id), t: (p && p.title) || r.t || '', ck, sum: 0, reps: new Set(), water: p ? isWater(p) : true });
      w.sum += a; w.reps.add(r.rid || r.at);
    }
  });
  const top = Object.values(per).filter(w => w.water).sort((a, b) => b.sum - a.sum || a.t.localeCompare(b.t)).slice(0, 10)
    .map(w => ({ id: w.id, t: w.t, ck: w.ck, sum: w.sum, n: w.reps.size }));
  return { month, year, sum12, repairs: reps.size, avg: reps.size ? Math.round(sum12 / reps.size) : 0, byCountry, top, monthKey: m, yearKey: Number(y) };
}
// "Repairs: KES N in the last 12 months (3 repairs)" on a project
export function projectCosts(rows, now = Date.now()) {
  const from = now - 365 * DAY, reps = new Set(); let sum = 0;
  (rows || []).forEach(r => { const t = Date.parse(r.at), a = Number(r.actual) || 0; if (a && t >= from && t <= now + DAY) { sum += a; reps.add(r.rid || r.at); } });
  return { sum, n: reps.size };
}

// --- people served ----------------------------------------------------------------------------
// The count a visit gives: people using a water point, or pupils, children living there, patients a week.
const COUNTS = ['users', 'pupils', 'children', 'patients'];
export function usersOf(v) {
  if (!v) return null;
  if (v.readings && v.readings.users != null && isFinite(v.readings.users)) return Number(v.readings.users);
  const n = v.numbers || {};
  for (const k of COUNTS) if (n[k] != null && isFinite(n[k])) return Number(n[k]);
  return null;
}
// visits newest first, as pinvisit:<id> keeps them
export function latestUsers(visits) {
  for (const v of visits || []) { const n = usersOf(v); if (n != null) return { n: Math.round(n), at: v.at }; }
  return null;
}
export const GROUPS = ['water', 'health', 'school', 'children', 'livelihoods', 'community'];
export function groupOf(p) {
  if (p.cat && GROUPS.includes(p.cat)) return p.cat;
  if (isWater(p)) return 'water';
  const t = String(p.type || '').toLowerCase();
  return /health|medical|clinic/.test(t) ? 'health' : /school|educ/.test(t) ? 'school' : /orphan|children/.test(t) ? 'children' : /farm|train|nutri|agri/.test(t) ? 'livelihoods' : 'community';
}
// Completed projects, and any project a visit counted people at. Each counts once: the visit's number,
// or the board's "people" when no visit has counted yet.
export function peopleNow(projects, latest) {
  let total = 0, counted = 0, measured = 0;
  const byCountry = {}, byType = {};
  (projects || []).forEach(p => {
    const l = latest && latest[String(p.id)];
    if (p.status !== 'Completed' && !l) return;
    const n = l ? Number(l.n) || 0 : Number(p.people) || 0;
    if (!n) return;
    total += n; counted++; if (l) measured++;
    if (p.ck) byCountry[p.ck] = (byCountry[p.ck] || 0) + n;
    const g = groupOf(p); byType[g] = (byType[g] || 0) + n;
  });
  return { total, counted, measured, byCountry, byType };
}
// the last 12 months, oldest first; a month with no snapshot carries the one before it; this month is live
export function peopleTrend(hist, total, now = Date.now()) {
  const d = new Date(now), H = hist || {}, val = h => (typeof h === 'number' ? h : Number(h && h.n) || 0);
  const months = []; for (let i = 11; i >= 0; i--) months.push(new Date(Date.UTC(d.getUTCFullYear(), d.getUTCMonth() - i, 1)).toISOString().slice(0, 7));
  const older = Object.keys(H).sort().filter(k => k < months[0]).pop();
  let last = older ? val(H[older]) : null;
  return months.map((k, i) => {
    if (i === months.length - 1) return { m: k, n: total };
    if (H[k] != null) last = val(H[k]);
    return { m: k, n: last };
  });
}
export function peopleServed(projects, latest, hist, now = Date.now()) {
  const r = peopleNow(projects, latest);
  r.trend = peopleTrend(hist, r.total, now);
  return r;
}
// after a visit: keep this project's latest count (one read, at most one write)
export async function notePeople(env, id, visits) {
  if (!env.FEED) return null;
  const u = latestUsers(visits); if (!u) return null;
  const m = await getJson(env, 'pinpeople', {}), cur = m[String(id)];
  if (cur && cur.n === u.n && cur.at === u.at) return m;
  m[String(id)] = u; await putJson(env, 'pinpeople', m);
  return m;
}
// this month's total, kept for the trend (24 months)
export async function snapPeople(env, projects, latest, now = Date.now()) {
  if (!env.FEED) return null;
  const hist = await getJson(env, 'pinpeoplehist', {}), k = monthKey(now), r = peopleNow(projects, latest || await getJson(env, 'pinpeople', {}));
  if (hist[k] && hist[k].n === r.total) return hist;
  hist[k] = { n: r.total, ck: r.byCountry, at: new Date(now).toISOString() };
  const keep = Object.keys(hist).sort().slice(-24), out = {};
  keep.forEach(x => { out[x] = hist[x]; });
  await putJson(env, 'pinpeoplehist', out);
  return out;
}
