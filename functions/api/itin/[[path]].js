// /api/itin: field trip itineraries (Office › Plan a trip, Me › Plan a trip). Same sign-in as /api/pin.
// The full contract (bodies, answers, refusals) is in docs/itinerary.md; the logic is in lib/itin.js and lib/itin-plan.js.
//   GET  /api/itin                         the trips you can see (?status= ?mine=1 ?person= ?project= ?month=) · ?alerts=1 · ?settings=1 · ?windows=1
//   GET  /api/itin/<id>                    one trip (?progress=1 adds live progress, ?card=1 a small card for sharing into a chat)
//   POST /api/itin                         create (plans it too)
//   POST /api/itin/suggest                 ranked stops with reasons
//   POST /api/itin/optimise                plan without saving
//   POST /api/itin/settings                Office settings (admins)
//   POST /api/itin/windows                 a project's time window (admins)
//   POST /api/itin/<id> { action, v, … }   change a trip (update, stops, add-stop, optimise, refresh, swap-days, lodging, extras, packing,
//                                          checkin, skip, replan, track, share, unshare, share-team, summary, delete)
import { checkPin } from '../pin.js';
import { getFeed } from '../../../lib/feed-cache.js';
import { getJson, putJson, team, sameName } from '../../../lib/pin-store.js';
import {
  STATUSES, MAX_TITLE, officeFor, settings, saveSettings, windowsAll, saveWindow, load, save, remove, canSee, canEdit, listFor, resolveStop, placeOf, datesOf,
  build, costOf, packingFor, editPacking, suggest, progressOf, checkin, replan, notifyOffice, addTrack, summaryOf, newId, newToken, sha, vehicles
} from '../../../lib/itin.js';
import { makeGet, bestInsert, LIMITS, okHm, hm } from '../../../lib/itin-plan.js';
import { countryOf } from '../../../lib/towns.js';

const HEAD = { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff', 'X-Robots-Tag': 'noindex' };
export const json = (body, status) => new Response(JSON.stringify(body), { status: status || 200, headers: HEAD });
const bad = (reason, status, extra) => json(Object.assign({ ok: false, reason }, extra || {}), status || 400);
const clean = (s, n) => String(s == null ? '' : s).replace(/[<>]/g, '').replace(/\s+/g, ' ').trim().slice(0, n);
const MAX_WRITES = 300; // changes per connection per hour

async function rate(env, request) {
  if (!env.FEED) return false;
  const k = 'rl:itin:' + (request.headers.get('CF-Connecting-IP') || 'unknown'), n = Number(await env.FEED.get(k)) || 0;
  if (n >= MAX_WRITES) return true;
  await env.FEED.put(k, String(n + 1), { expirationTtl: 3600 });
  return false;
}
async function byIdOf(env, waitUntil) { let feed = null; try { feed = await getFeed(env, waitUntil); } catch (e) {} return { feed, byId: new Map(((feed && feed.projects) || []).map(p => [String(p.id), p])) }; }
async function names(env, list, chk) {
  // people on a trip: teammates by name (as typed in the app); unknown names are kept as given
  const known = (await team(env)).map(p => p.name).concat(String(env.PIN_USERS || '').split(/[,;\n]/).map(x => x.split(':')[0].trim()).filter(Boolean)), out = [];
  for (const n of (Array.isArray(list) ? list : []).slice(0, 12)) { const c = clean(n, 60); if (!c) continue; const m = known.find(p => sameName(p, c)); const v = m || c; if (!out.some(x => sameName(x, v))) out.push(v); }
  if (chk && !chk.admin && !out.some(x => sameName(x, chk.who))) out.unshift(chk.who);
  return out;
}

// a new trip (or a plan) from the body
async function fromBody(env, chk, b, byId, prev) {
  const st = await settings(env);
  const from = b.from || (prev && prev.from), to = b.to || (prev && prev.to) || from;
  const dates = datesOf(from, to);
  if (!dates) return { err: bad('dates', 400, { max: LIMITS.days }) };
  // { kind: "office" } alone: the office from Office settings, for the trip's country
  const ck0 = ['ke', 'tz', 'ug', 'mw'].includes(b.ck) ? b.ck : (prev && prev.ck) || chk.home || 'ke';
  const office = x => (x && x.kind === 'office' && (x.lat == null || x.lng == null) ? officeFor(st, ck0) : undefined);
  const sOff = office(b.start), eOff = office(b.end);
  if (sOff === null || eOff === null) return { err: bad('no-office', 400, { ck: ck0 }) };
  const start = sOff || placeOf(b.start, prev && prev.start);
  if (!start) return { err: bad('start') };
  const end = eOff || (b.end && b.end.kind && b.end.kind !== 'same' ? placeOf(b.end) : { kind: 'same' });
  if (!end) return { err: bad('end') };
  const hours = b.hours ? b.hours : (prev && prev.hours) || st.hours;
  if (!okHm(hours.start) || !okHm(hours.end) || hm(hours.start) >= hm(hours.end)) return { err: bad('hours') };
  const lunch = Object.assign({}, st.lunch, (prev && prev.lunch) || {}, b.lunch || {});
  if (!okHm(lunch.at)) return { err: bad('lunch') };
  const raw = Array.isArray(b.stops) ? b.stops : prev ? prev.stops : [];
  if (raw.length > LIMITS.stops) return { err: bad('too-many', 400, { max: LIMITS.stops }) };
  const wins = await windowsAll(env);
  const stops = [], unknown = [];
  let n = 0; raw.forEach(x => { const m = /^s(\d+)$/.exec((x && x.sid) || ''); if (m) n = Math.max(n, Number(m[1])); });
  raw.forEach(x => { const s = resolveStop(x, byId, st, wins, 's' + (n + 1)); if (!s) unknown.push(String(typeof x === 'object' ? (x && (x.id || x.name)) : x).slice(0, 40)); else { if (s.sid === 's' + (n + 1)) n++; if (!stops.some(y => y.sid === s.sid)) stops.push(s); } });
  if (unknown.length) return { err: bad('stops', 400, { unknown }) };
  const people = await names(env, b.people || (prev && prev.people) || [chk.who], chk);
  const ck = ['ke', 'tz', 'ug', 'mw'].includes(b.ck) ? b.ck : (prev && prev.ck) || countryOf([start.lat, start.lng]) || chk.home || 'ke';
  const vid = b.vehicleId !== undefined ? (b.vehicleId ? String(b.vehicleId).slice(0, 30) : null) : prev ? prev.vehicleId : null;
  if (vid && !(await vehicles(env)).some(v => v.vid === vid)) return { err: bad('no-vehicle') };
  const rec = {
    title: clean(b.title != null ? b.title : prev ? prev.title : '', MAX_TITLE) || 'Field trip ' + from, status: prev ? prev.status : 'planned',
    owner: prev ? prev.owner : chk.who, people, viewers: prev ? prev.viewers || [] : [], ck, tz: ck === 'mw' ? 'Africa/Blantyre' : 'Africa/Nairobi',
    from, to, hours: { start: hours.start, end: hours.end }, lunch: { at: lunch.at, min: Math.max(0, Math.min(120, Math.round(Number(lunch.min) || 0))), skip: !!lunch.skip },
    start, end, vehicleId: vid, passengers: b.passengers != null ? Math.max(0, Math.min(30, Math.round(Number(b.passengers) || 0))) : prev ? prev.passengers : null,
    cargoKg: b.cargoKg != null ? Math.max(0, Math.min(5000, Math.round(Number(b.cargoKg) || 0))) : prev ? prev.cargoKg || 0 : 0,
    stops, days: prev ? prev.days : null, legs: prev ? prev.legs : null, matrix: prev ? prev.matrix : null, extras: prev ? prev.extras || [] : [], packing: prev ? prev.packing || [] : []
  };
  return { rec };
}
async function finish(env, rec, opts) {
  await build(env, rec, opts);
  rec.cost = await costOf(env, rec, opts);
  rec.packing = await packingFor(env, rec, rec.packing, opts.now);
  if (rec.cost.fuel.price == null && rec.vehicleId) rec.warnings.push({ code: 'NO_FUEL_PRICE', level: 'info', en: 'No fuel price for this area yet: add one in Office › Vehicles › Fuel prices', sw: 'Hakuna bei ya mafuta ya eneo hili bado: iweke katika Ofisi › Magari › Bei za mafuta' });
  if (!rec.vehicleId) rec.warnings.push({ code: 'NO_VEHICLE', level: 'info', en: 'Choose a vehicle to see the fuel cost', sw: 'Chagua gari ili kuona gharama ya mafuta' });
  return rec;
}
const view = (rec, chk) => ({ ok: true, itin: strip(rec), can: { edit: canEdit(chk, rec), admin: !!chk.admin } });
function strip(rec) { const r = Object.assign({}, rec); if (r.share) r.share = { url: r.share.url, at: r.share.at, by: r.share.by }; return r; }

export async function onRequestGet({ request, env, waitUntil, params }) {
  const chk = await checkPin(request, env);
  if (chk.res) return chk.res;
  if (!env.FEED) return bad('no-storage', 503);
  const q = new URL(request.url).searchParams, path = (params && params.path) || [];
  try {
    if (!path.length) {
      if (q.get('settings')) return json({ ok: true, settings: await settings(env), admin: !!chk.admin });
      if (q.get('windows')) return json({ ok: true, windows: await windowsAll(env) });
      if (q.get('alerts')) { if (!chk.admin) return bad('not-admin', 403); return json({ ok: true, alerts: await getJson(env, 'itinalerts', []) }); }
      return json({ ok: true, items: await listFor(env, chk, q), admin: !!chk.admin });
    }
    const rec = await load(env, path[0]);
    if (!rec) return bad('not-found', 404);
    if (!canSee(chk, rec)) return bad('forbidden', 403);
    if (q.get('card')) return json({ ok: true, card: { id: rec.id, title: rec.title, from: rec.from, to: rec.to, stops: rec.stops.length, people: rec.people, url: rec.share ? rec.share.url : null } });
    const out = view(rec, chk);
    if (q.get('progress')) out.progress = await progressOf(env, rec);
    return json(out);
  } catch (e) { console.error('itin get:', e && e.stack || e); return bad('down', 502); }
}

export async function onRequestPost({ request, env, waitUntil, params }) {
  const origin = request.headers.get('Origin');
  if (origin && new URL(origin).host !== new URL(request.url).host) return bad('origin', 403);
  const chk = await checkPin(request, env);
  if (chk.res) return chk.res;
  if (!chk.who) return bad('named-only', 403);
  if (!env.FEED) return bad('no-storage', 503);
  if (await rate(env, request)) return bad('rate', 429);
  let b; try { b = await request.json(); } catch (e) { return bad('bad-request'); }
  if (!b || typeof b !== 'object') return bad('bad-request');
  const path = (params && params.path) || [], site = new URL(request.url).origin;
  try {
    const head = path[0] || '';
    if (head === 'settings') { if (!chk.admin) return bad('not-admin', 403); const r = await saveSettings(env, b, chk.who); return json(r, r.ok ? 200 : 400); }
    if (head === 'windows') { if (!chk.admin) return bad('not-admin', 403); const r = await saveWindow(env, b); return json(r, r.ok ? 200 : 400); }
    if (head === 'suggest') { const { feed } = await byIdOf(env, waitUntil); return json(await suggest(env, chk, b, feed)); }
    if (head === 'optimise' || head === '') {
      const { byId } = await byIdOf(env, waitUntil);
      const f = await fromBody(env, chk, b, byId, null);
      if (f.err) return f.err;
      const rec = f.rec;
      if (b.plan && Array.isArray(b.plan.days)) rec.days = b.plan.days.map(d => ({ stops: (d.stops || []).filter(x => rec.stops.some(s => s.sid === x)), overnight: d.overnight || null }));
      await finish(env, rec, { optimise: !rec.days, weather: b.weather !== false });
      if (head === 'optimise') { delete rec.owner; return json({ ok: true, plan: rec }); }
      Object.assign(rec, { id: newId('it'), v: 1, created: new Date().toISOString(), updated: new Date().toISOString(), by: chk.who, share: null });
      if (b.status && STATUSES.includes(b.status)) rec.status = b.status;
      await save(env, rec);
      return json(view(rec, chk), 201);
    }
    // a saved trip
    const rec = await load(env, head);
    if (!rec) return bad('not-found', 404);
    if (!canSee(chk, rec)) return bad('forbidden', 403);
    if (!canEdit(chk, rec)) return bad('read-only', 403);
    if (b.v != null && Number(b.v) !== rec.v) return json({ ok: false, reason: 'conflict', current: strip(rec) }, 409);
    return await change(env, chk, rec, b, site, waitUntil);
  } catch (e) { console.error('itin post:', e && e.stack || e); return bad('down', 502); }
}

async function change(env, chk, rec, b, site, waitUntil) {
  const byS = () => new Map(rec.stops.map(s => [s.sid, s]));
  const done = async (opts) => {
    if (opts) await finish(env, rec, opts);
    rec.v = (rec.v || 0) + 1; rec.updated = new Date().toISOString(); rec.by = chk.who;
    await save(env, rec);
    return json(view(rec, chk));
  };
  const retime = () => ({ optimise: false, weather: false, prevDays: rec.days });
  switch (b.action) {
    case 'update': {
      const { byId } = await byIdOf(env, waitUntil);
      const keep = { status: rec.status };
      const f = await fromBody(env, chk, Object.assign({}, b, { stops: undefined }), byId, rec);
      if (f.err) return f.err;
      const datesChanged = f.rec.from !== rec.from || f.rec.to !== rec.to;
      Object.assign(rec, f.rec, { id: rec.id, v: rec.v, created: rec.created, share: rec.share, summary: rec.summary });
      if (Array.isArray(b.viewers)) rec.viewers = await names(env, b.viewers);
      if (b.status) { if (!STATUSES.includes(b.status)) return bad('status'); rec.status = b.status; } else rec.status = keep.status;
      if (datesChanged) rec.days = (rec.days || []).slice(0, (datesOf(rec.from, rec.to) || []).length);
      if (rec.status === 'done' && keep.status !== 'done') { await finish(env, rec, retime()); rec.summary = await summaryOf(env, rec); return done(); }
      return done(datesChanged || b.start || b.end || b.hours || b.lunch ? { optimise: false, weather: true, prevDays: rec.days } : retime());
    }
    case 'stops': {
      if (!Array.isArray(b.days)) return bad('bad-request');
      const ids = new Set(rec.stops.map(s => s.sid));
      if (Array.isArray(b.stops)) for (const e of b.stops.slice(0, LIMITS.stops)) {
        const s = rec.stops.find(x => x.sid === e.sid); if (!s) continue;
        if (Number(e.visitMin) >= 5 && Number(e.visitMin) <= 480) s.visitMin = Math.round(Number(e.visitMin));
        if (e.window === null) s.window = null; else if (e.window && okHm(e.window.open) && okHm(e.window.close)) s.window = { open: e.window.open, close: e.window.close, src: 'stop' };
        if (e.fixed === null || e.fixed === '') { s.fixed = null; s.locked = !!e.locked; } else if (okHm(e.fixed)) { s.fixed = e.fixed; s.locked = true; }
        if (e.locked != null && !s.fixed) s.locked = !!e.locked;
        if (e.note != null) s.note = clean(e.note, 300);
      }
      const used = new Set();
      const days = (rec.days || []).map((d, i) => Object.assign({}, d, { stops: ((b.days[i] || {}).stops || []).filter(x => ids.has(x) && !used.has(x) && used.add(x)) }));
      // stops left out of every day are removed from the trip (unless listed in unplanned)
      const keepOut = new Set((b.unplanned || []).filter(x => ids.has(x) && !used.has(x)));
      rec.stops = rec.stops.filter(s => used.has(s.sid) || keepOut.has(s.sid) || s.status === 'skipped');
      rec.days = days;
      return done(retime());
    }
    case 'add-stop': {
      if (rec.stops.length >= LIMITS.stops) return bad('too-many', 400, { max: LIMITS.stops });
      const { byId } = await byIdOf(env, waitUntil);
      const st = await settings(env), wins = await windowsAll(env);
      const n = rec.stops.reduce((m, s) => Math.max(m, Number(s.sid.slice(1)) || 0), 0);
      const s = resolveStop(Object.assign({}, b.stop, { sid: undefined }), byId, st, wins, 's' + (n + 1));
      if (!s) return bad('stops');
      rec.stops.push(s);
      let di = Number.isInteger(b.day) && b.day >= 0 && b.day < rec.days.length ? b.day : null;
      if (di == null) {
        const P = { S: [rec.start.lat, rec.start.lng] }; P.E = rec.end && rec.end.kind !== 'same' ? [rec.end.lat, rec.end.lng] : P.S; rec.stops.forEach(x => { P[x.sid] = [x.lat, x.lng]; }); rec.days.forEach(d => { P[d.endAt.key] = [d.endAt.lat, d.endAt.lng]; P[d.startAt.key] = [d.startAt.lat, d.startAt.lng]; });
        const get = makeGet(P, rec.matrix, rec.legs);
        const best = bestInsert(rec.days.map(d => ({ start: d.startAt.key, end: d.endAt.key, stops: d.stops })), s, byS(), { hours: rec.hours, lunch: rec.lunch, get });
        di = best ? best.day : 0;
        rec.days[di].stops.splice(best ? best.pos : rec.days[di].stops.length, 0, s.sid);
      } else rec.days[di].stops.push(s.sid);
      rec.matrix = null; // a new place: the matrix is fetched again
      return done({ optimise: false, weather: true, prevDays: rec.days });
    }
    case 'optimise': return done(b.keepDays ? { optimise: false, weather: true, prevDays: rec.days, freshMatrix: true } : { optimise: true, weather: true, prevDays: rec.days });
    case 'refresh': return done({ optimise: false, weather: true, prevDays: rec.days });
    case 'swap-days': {
      const i = Number(b.day), j = Number(b.with);
      if (!rec.days[i] || !rec.days[j] || i === j) return bad('day');
      const t = rec.days[i].stops; rec.days[i].stops = rec.days[j].stops; rec.days[j].stops = t;
      return done({ optimise: false, weather: true, prevDays: rec.days });
    }
    case 'lodging': {
      const d = rec.days[Number(b.day)];
      if (!d || Number(b.day) >= rec.days.length - 1) return bad('day');
      if (b.overnight === null) { d.overnight = null; d.home = true; }
      else {
        const o = b.overnight || {}, lat = Number(o.lat), lng = Number(o.lng);
        const L = o.lodging || {};
        const lodging = { name: clean(L.name, 80), phone: clean(L.phone, 30), kes: Math.max(0, Math.min(1e6, Math.round(Number(L.kes) || 0))), note: clean(L.note, 200), paid: !!L.paid };
        if (isFinite(lat) && isFinite(lng)) d.overnight = { town: clean(o.town, 60), lat, lng, suggested: false, lodging };
        else if (d.overnight) d.overnight = Object.assign({}, d.overnight, { lodging, suggested: false });
        else return bad('place');
        d.home = false;
      }
      return done({ optimise: false, weather: true, prevDays: rec.days });
    }
    case 'extras': {
      if (!Array.isArray(b.extras)) return bad('bad-request');
      rec.extras = b.extras.slice(0, 100).map((x, k) => ({ xid: /^x\d{1,4}$/.test(x.xid || '') ? x.xid : 'x' + (k + 1), day: Number.isInteger(x.day) && x.day >= 0 && x.day < rec.days.length ? x.day : null,
        kind: ['toll', 'parking', 'lodging', 'lunch', 'other'].includes(x.kind) ? x.kind : 'other', label: clean(x.label, 80), kes: Math.max(0, Math.min(1e7, Math.round(Number(x.kes) || 0))), actual: !!x.actual,
        receipt: /^[A-Za-z0-9_-]{1,64}$/.test(String(x.receipt || '')) ? x.receipt : null }));
      rec.cost = await costOf(env, rec);
      return done();
    }
    case 'packing': rec.packing = editPacking(rec.packing, b); return done();
    case 'checkin': {
      const r = await checkin(env, rec, chk, b);
      if (!r.ok) return bad(r.reason);
      if (rec.status === 'planned') rec.status = 'active';
      return done();
    }
    case 'skip': {
      const s = rec.stops.find(x => x.sid === b.sid); if (!s) return bad('stop');
      s.status = 'skipped'; s.note = clean(b.why || s.note, 300);
      await notifyOffice(env, rec, { kind: 'skip', by: chk.who, en: chk.who + ' skipped ' + s.name, sw: chk.who + ' aliruka ' + s.name }, site);
      return done(retime());
    }
    case 'replan': {
      const P = { S: [rec.start.lat, rec.start.lng] }; P.E = rec.end && rec.end.kind !== 'same' ? [rec.end.lat, rec.end.lng] : P.S; rec.stops.forEach(x => { P[x.sid] = [x.lat, x.lng]; }); rec.days.forEach(d => { P[d.endAt.key] = [d.endAt.lat, d.endAt.lng]; P[d.startAt.key] = [d.startAt.lat, d.startAt.lng]; });
      const r = replan(rec, b, byS(), { hours: rec.hours, lunch: rec.lunch, get: makeGet(P, rec.matrix, rec.legs) });
      if (!r.ok) return bad(r.reason);
      if (r.mode === 'preview') return json({ ok: true, preview: true, day: r.day, keep: r.keep, move: r.move, endsAt: r.endsAt });
      const d = rec.days[r.day];
      d.stops = r.keep;
      let note;
      if (r.mode === 'push') {
        if (rec.days[r.day + 1]) rec.days[r.day + 1].stops = r.move.concat(rec.days[r.day + 1].stops);
        else { rec.unplanned = (rec.unplanned || []).concat(r.move); }
        r.move.forEach(sid => { const s = rec.stops.find(x => x.sid === sid); if (s) s.status = rec.days[r.day + 1] ? 'moved' : 'planned'; });
        note = { en: chk.who + ' is running late: ' + r.move.length + (r.move.length === 1 ? ' stop moved' : ' stops moved') + (rec.days[r.day + 1] ? ' to tomorrow' : ' out of the plan'), sw: chk.who + ' amechelewa: vituo ' + r.move.length + ' vimehamishwa' + (rec.days[r.day + 1] ? ' hadi kesho' : ' nje ya mpango') };
      } else {
        r.move.forEach(sid => { const s = rec.stops.find(x => x.sid === sid); if (s) s.status = 'skipped'; });
        note = { en: chk.who + ' is running late: ' + r.move.length + (r.move.length === 1 ? ' stop dropped' : ' stops dropped'), sw: chk.who + ' amechelewa: vituo ' + r.move.length + ' vimeachwa' };
      }
      if (r.move.length) await notifyOffice(env, rec, { kind: 'replan', by: chk.who, en: note.en, sw: note.sw }, site);
      const res = await done(retime());
      const body = await res.json();
      return json(Object.assign(body, { moved: r.mode === 'push' ? r.move : [], dropped: r.mode === 'drop' ? r.move : [] }));
    }
    case 'track': { const r = await addTrack(env, rec.id, b.points); return json(r); }
    case 'share': {
      if (rec.share && rec.share.h && !b.rotate) return json({ ok: true, url: rec.share.url, token: rec.share.t });
      if (rec.share && rec.share.h) await env.FEED.delete('itinshare:' + rec.share.h);
      const t = newToken(), h = await sha(t);
      await putJson(env, 'itinshare:' + h, { kind: 'itin', id: rec.id });
      rec.share = { t, h, url: new URL('/pin-trip?t=' + t, site).toString(), at: new Date().toISOString(), by: chk.who };
      rec.v++; rec.updated = new Date().toISOString(); await save(env, rec);
      return json({ ok: true, url: rec.share.url, token: t, v: rec.v });
    }
    case 'unshare': { if (rec.share && rec.share.h) await env.FEED.delete('itinshare:' + rec.share.h); rec.share = null; return done(); }
    case 'share-team': { rec.viewers = (await names(env, (rec.viewers || []).concat(b.names || []))).filter(n => !rec.people.some(p => sameName(p, n))); return done(); }
    case 'summary': await finish(env, rec, retime()); rec.summary = await summaryOf(env, rec); return done();
    case 'delete': {
      if (!chk.admin && !sameName(rec.owner, chk.who)) return bad('forbidden', 403);
      await remove(env, rec);
      return json({ ok: true, deleted: rec.id });
    }
    default: return bad('action');
  }
}

export function onRequest() { return json({ ok: false, reason: 'method' }, 405); }
