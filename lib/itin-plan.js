// The trip planner's maths (pure functions, no storage, tested with node --test):
//   - schedule(): one day's timeline from an ordered list of stops (the rule the apps also use to re-time locally)
//   - orderStops(): nearest-neighbour then 2-opt, on drive minutes plus penalties for time windows and long days
//   - planTrip(): orders all stops, splits them across the days by working hours, and suggests overnight towns
//   - replanDay(): "running late": what still fits today from now, and what moves to tomorrow (or is dropped)
//
// Drive times come from get(a, b) → { min, km, src }: real legs (lib/trip.js) when known, else the ORS matrix, else
// an estimate from the straight line (MODEL.fallback in lib/trip.js: × 1.4 at 45 km/h). Keys: "S" start, "E" end,
// a stop's sid, "T:<town>" an overnight town.
import { MODEL } from './trip.js';
import { metres } from './roads.js';
import { nearestTown } from './towns.js';

export const LIMITS = { stops: 40, days: 14 };
export const PENALTY = { late: 3, over: 2, wait: 0.2 };   // cost per minute late for a window, past the end of the day, waiting
export const OVERNIGHT_AFTER = 90;                        // minutes from the start point: further than this, stay the night
export const NEAR_TOWN_KM = 80;                           // an overnight town must be this close to the next day's first stop
export const STAY_MIN = 15;                               // while splitting days: minutes to an overnight place near the last stop
export const DEFAULTS = {
  hours: { start: '07:30', end: '17:30' }, lunch: { at: '12:30', min: 45, skip: false },
  visitMin: { borehole: 60, collection: 45, school: 60, health: 45, children: 60, farming: 45, community: 45, building: 60, other: 45 },
  windows: { school: { open: '08:00', close: '15:30' }, health: { open: '08:00', close: '17:00' } }
};

// ---- clock -------------------------------------------------------------------------------------------------
export const hm = s => { const m = /^(\d{1,2}):(\d{2})$/.exec(String(s || '').trim()); return m && +m[1] < 24 && +m[2] < 60 ? +m[1] * 60 + +m[2] : null; };
export const fmt = v => { const m = Math.round(v), h = Math.floor(m / 60) % 24, mm = ((m % 60) + 60) % 60; return String(h).padStart(2, '0') + ':' + String(mm).padStart(2, '0'); };
export const okHm = s => hm(s) != null;

// ---- drive times ------------------------------------------------------------------------------------------
/** straight line × road factor at the fallback speed (lib/trip.js MODEL.fallback) */
export function estimate(a, b) {
  if (!a || !b) return { min: 0, km: 0, src: 'estimate' };
  const F = MODEL.fallback, km = metres(a, b) / 1000 * F.roadFactor;
  return { min: Math.round(km / F.kmh * 60), km: Math.round(km * 10) / 10, src: 'estimate' };
}
/**
 * get(a, b) for keys: legs (real, "a>b" or "b>a") → matrix → estimate from pts.
 * pts: { key: [lat, lng] }, matrix: { keys, min: [[…]], km: [[…]], src }, legs: { "a>b": { min, km } }
 */
export function makeGet(pts, matrix, legs) {
  const idx = new Map(((matrix && matrix.keys) || []).map((k, i) => [k, i]));
  const cache = new Map();
  return (a, b) => {
    if (a === b) return { min: 0, km: 0, src: 'same' };
    const ck = a + '>' + b;
    if (cache.has(ck)) return cache.get(ck);
    let r = null;
    const L = legs && (legs[ck] || legs[b + '>' + a]);
    if (L && isFinite(L.min)) r = { min: L.min, km: L.km, src: L.src || 'trip' };
    else if (idx.has(a) && idx.has(b)) {
      const v = matrix.min[idx.get(a)][idx.get(b)], k = matrix.km ? matrix.km[idx.get(a)][idx.get(b)] : null;
      if (v != null && isFinite(v)) r = { min: Math.round(v), km: k == null ? estimate(pts[a], pts[b]).km : k, src: matrix.src || 'matrix' };
    }
    if (!r) r = estimate(pts[a], pts[b]);
    cache.set(ck, r);
    return r;
  };
}

// ---- one day ----------------------------------------------------------------------------------------------
const PLACE = { school: { en: 'the school', sw: 'shule' }, health: { en: 'the clinic', sw: 'kliniki' } };
const W = (code, level, en, sw, extra) => Object.assign({ code, level, en, sw }, extra || {});

/**
 * schedule(day, opts) → { timeline, warnings, endsAt, km, driveMin, cost, late, over, wait }
 * day: { start: key, end: key, stops: [stop] } ; stop: { sid, type, visitMin, window: { open, close } | null, fixed: "HH:MM" | null }
 * opts: { hours, lunch, get, names: { key: name }, startAt? (minutes, for re-planning) }
 */
export function schedule(day, opts) {
  const hours = opts.hours || DEFAULTS.hours, lunch = opts.lunch || DEFAULTS.lunch, get = opts.get, names = opts.names || {};
  const H0 = opts.startAt != null ? opts.startAt : hm(hours.start), H1 = hm(hours.end), L = hm(lunch.at), LM = Number(lunch.min) || 0;
  const tl = [], warnings = [];
  let t = H0, pos = day.start, km = 0, drive = 0, late = 0, wait = 0, lunchDone = !!lunch.skip || L == null || !LM;
  tl.push({ kind: 'leave', at: fmt(t), place: names[pos] || '' });
  const eat = () => { tl.push({ kind: 'lunch', from: fmt(t), to: fmt(t + LM) }); t += LM; lunchDone = true; };
  const go = (to) => {
    if (to === pos) return;
    const g = get(pos, to);
    if (!lunchDone && t < L && t + g.min > L + 60 && t >= L - 60) eat();
    tl.push({ kind: 'drive', from: pos, to, dep: fmt(t), arr: fmt(t + g.min), min: g.min, km: g.km, leg: pos + '>' + to, src: g.src });
    t += g.min; km += g.km || 0; drive += g.min; pos = to;
  };
  for (const s of day.stops) {
    go(s.sid);
    const arrive = t;
    const visit = Math.max(5, Number(s.visitMin) || 45);
    const f = hm(s.fixed), w = s.window && hm(s.window.open) != null && hm(s.window.close) != null ? s.window : null;
    // lunch on arrival when it is well past lunch time, unless eating first would miss the meeting or the closing time
    if (!lunchDone && t >= L + 60 && !(f != null && t + LM > f) && !(w && Math.max(t + LM, hm(w.open)) + visit > hm(w.close))) eat();
    let startAt = t;
    if (f != null) {
      if (t < f) { tl.push({ kind: 'wait', from: fmt(t), to: fmt(f), min: Math.round(f - t), sid: s.sid, why: 'fixed' }); wait += f - t; startAt = f; }
      else if (t > f + 5) { const m = Math.round(t - f); late += m; warnings.push(W('FIXED_LATE', 'difficult', 'Arrives ' + fmt(t) + ', ' + m + ' min after the meeting at ' + s.fixed, 'Inafika ' + fmt(t) + ', dakika ' + m + ' baada ya mkutano wa saa ' + s.fixed, { sid: s.sid, lateMin: m })); }
    } else if (w) {
      const o = hm(w.open), c = hm(w.close), place = PLACE[s.type] || { en: 'the site', sw: 'eneo' };
      if (t < o) { tl.push({ kind: 'wait', from: fmt(t), to: fmt(o), min: Math.round(o - t), sid: s.sid, why: 'opens' }); wait += o - t; if (o - t > 20) warnings.push(W('BEFORE_OPEN', 'info', 'Arrives ' + fmt(t) + ', ' + Math.round(o - t) + ' min before ' + place.en + ' opens', 'Inafika ' + fmt(t) + ', dakika ' + Math.round(o - t) + ' kabla ' + place.sw + ' haijafunguliwa', { sid: s.sid })); startAt = o; }
      if (startAt >= c) { const m = Math.round(startAt + visit - c); late += m; warnings.push(W('AFTER_CLOSE', 'difficult', 'Arrives ' + fmt(arrive) + ', after ' + place.en + ' closes', 'Inafika ' + fmt(arrive) + ', baada ya ' + place.sw + ' kufungwa', { sid: s.sid, lateMin: m })); }
      else if (startAt + visit > c) { const m = Math.round(startAt + visit - c); late += m; warnings.push(W('AFTER_CLOSE', 'caution', 'Visit runs until ' + fmt(startAt + visit) + ', after ' + place.en + ' closes at ' + w.close, 'Ziara inaisha ' + fmt(startAt + visit) + ', baada ya ' + place.sw + ' kufungwa saa ' + w.close, { sid: s.sid, lateMin: m })); }
    }
    t = startAt;
    tl.push({ kind: 'stop', sid: s.sid, arrive: fmt(arrive), start: fmt(startAt), leave: fmt(t + visit), visitMin: visit, fixed: s.fixed || null, late: warnings.some(x => x.sid === s.sid && (x.code === 'AFTER_CLOSE' || x.code === 'FIXED_LATE')) });
    t += visit;
    if (!lunchDone && t >= L) eat();
  }
  go(day.end);
  tl.push(day.endKind === 'overnight' ? { kind: 'overnight', at: fmt(t), town: names[day.end] || '' } : { kind: 'return', at: fmt(t), place: names[day.end] || '' });
  const over = H1 != null && t > H1 ? Math.round(t - H1) : 0;
  if (over) warnings.push(W('OVER_HOURS', over > 60 ? 'difficult' : 'caution', 'Day ends ' + fmt(t) + ', after ' + hours.end, 'Siku inaisha ' + fmt(t) + ', baada ya ' + hours.end, { overMin: over }));
  const cost = drive + PENALTY.late * late + PENALTY.over * over + PENALTY.wait * wait;
  return { timeline: tl, warnings, endsAt: fmt(t), endMin: t, km: Math.round(km * 10) / 10, driveMin: Math.round(drive), late, over, wait: Math.round(wait), cost };
}

// ---- ordering ---------------------------------------------------------------------------------------------
/** Nearest stop next from `start`, then 2-opt; open path ending at `end` (or anywhere when end is null). Drive minutes only. */
export function orderByDrive(sids, start, end, get) {
  const left = sids.slice(), out = [];
  let cur = start;
  while (left.length) {
    let bi = 0, bv = Infinity;
    left.forEach((s, i) => { const v = get(cur, s).min; if (v < bv) { bv = v; bi = i; } });
    cur = left.splice(bi, 1)[0]; out.push(cur);
  }
  const len = a => { let m = 0, p = start; for (const s of a) { m += get(p, s).min; p = s; } return m + (end ? get(p, end).min : 0); };
  return twoOpt(out, len);
}
function twoOpt(a, cost) {
  let best = a.slice(), bc = cost(best), improved = true, rounds = 0;
  while (improved && rounds++ < 50) {
    improved = false;
    for (let i = 0; i < best.length - 1; i++) for (let k = i + 1; k < best.length; k++) {
      const c = best.slice(0, i).concat(best.slice(i, k + 1).reverse(), best.slice(k + 1));
      const v = cost(c);
      if (v < bc - 1e-9) { best = c; bc = v; improved = true; }
    }
  }
  return best;
}
/** A day's stops in the best order for schedule() cost (drive + window lateness + over hours + waiting). Locked stops count via their fixed time. */
export function orderStops(stops, day, opts) {
  if (stops.length < 2) return stops.slice();
  const by = new Map(stops.map(s => [s.sid, s]));
  const seed = orderByDrive(stops.map(s => s.sid), day.start, day.end, opts.get);
  const cost = ids => schedule(Object.assign({}, day, { stops: ids.map(i => by.get(i)) }), opts).cost;
  // also try the seed sorted by window close / fixed time, and keep the better start for 2-opt
  const byTime = stops.slice().sort((a, b) => (hm(a.fixed) ?? hm(a.window && a.window.close) ?? 9999) - (hm(b.fixed) ?? hm(b.window && b.window.close) ?? 9999)).map(s => s.sid);
  const start = cost(byTime) < cost(seed) ? byTime : seed;
  return (stops.length <= 14 ? twoOpt(start, cost) : start).map(i => by.get(i));
}

// ---- the whole trip ---------------------------------------------------------------------------------------
/**
 * planTrip(input) → { days: [{ date, stops: [sid], start, end, endKind, overnight, sched }], unplanned: [sid], pts, names }
 * input: { dates: [iso], stops: [stop with lat, lng, sid, day?, pinDay?], start: { lat, lng, name }, end: { lat, lng, name } | null,
 *          hours, lunch, get (optional; built from pts/matrix/legs), matrix, legs, overnightAfterMin, overnights: { dayIndex: { town, lat, lng } | null },
 *          keepDays: bool (keep every stop on its current day) }
 */
export function planTrip(input) {
  const dates = input.dates.slice(0, LIMITS.days), n = dates.length;
  const pts = { S: [input.start.lat, input.start.lng] }, names = { S: input.start.name || '' };
  const end = input.end && input.end.lat != null ? input.end : input.start;
  pts.E = [end.lat, end.lng]; names.E = end.name || input.start.name || '';
  input.stops.forEach(s => { pts[s.sid] = [s.lat, s.lng]; names[s.sid] = s.name || ''; });
  const legs = input.legs || {};
  let get = input.get || makeGet(pts, input.matrix, legs);
  const T = Number(input.overnightAfterMin) || OVERNIGHT_AFTER;
  const opts = { hours: input.hours || DEFAULTS.hours, lunch: input.lunch || DEFAULTS.lunch, get: (a, b) => get(a, b), names };
  const by = new Map(input.stops.map(s => [s.sid, s]));
  const days = dates.map(date => ({ date, stops: [] }));
  const free = [];
  for (const s of input.stops) {
    const d = Number(s.day);
    if ((s.pinDay || input.keepDays) && Number.isInteger(d) && d >= 0 && d < n) days[d].stops.push(s.sid); else free.push(s.sid);
  }
  // while splitting: a day ends at home, or (when home is far) at a place near its last stop
  const homeOrStay = (last) => (!last || get(last, 'S').min <= T ? { key: 'S', extra: 0 } : { key: last, extra: STAY_MIN });
  const dayFrame = (i, ids) => {
    const prevLast = i > 0 && days[i - 1].stops.length ? days[i - 1].stops[days[i - 1].stops.length - 1] : null;
    const st = i === 0 ? 'S' : homeOrStay(prevLast).key;
    const last = i === n - 1 ? null : ids[ids.length - 1];
    return { start: st, end: i === n - 1 ? 'E' : homeOrStay(last).key };
  };
  const fitDay = (i, ids) => {
    const f0 = dayFrame(i, ids);
    const ordered = orderStops(ids.map(x => by.get(x)), { start: f0.start, end: f0.end }, opts).map(s => s.sid);
    const f = dayFrame(i, ordered);
    const extra = (f.start !== 'S' && i > 0 ? STAY_MIN : 0) + (f.end !== 'S' && f.end !== 'E' ? STAY_MIN : 0);
    const sc = schedule({ start: f.start, end: f.end, stops: ordered.map(x => by.get(x)) }, opts);
    return { ordered, sc, over: Math.max(0, sc.endMin + extra - hm(opts.hours.end)) };
  };
  // free stops in one drive order from the start, then filled into days in that order
  const order = orderByDrive(free, 'S', 'E', get);
  const unplanned = [];
  let di = 0;
  for (const sid of order) {
    let placed = false;
    while (di < n) {
      const before = days[di].stops.length ? fitDay(di, days[di].stops) : null;
      const trial = fitDay(di, days[di].stops.concat(sid));
      const worse = trial.sc.late > (before ? before.sc.late : 0) + 5;
      if ((trial.over === 0 && !worse) || !days[di].stops.length) {
        days[di].stops = trial.ordered; placed = true;
        break;
      }
      di++;
    }
    if (!placed) unplanned.push(sid);
  }
  // final order of each day (pinned ones too)
  days.forEach((d, i) => { if (d.stops.length > 1) d.stops = fitDay(i, d.stops).ordered; });
  // overnight: when the next day starts far from home (or this day ends far from home), stay in the town nearest the next day's first stop
  for (let i = 0; i < n; i++) {
    const d = days[i];
    d.start = i === 0 ? 'S' : days[i - 1].endKind === 'overnight' ? days[i - 1].end : 'S';
    if (i === n - 1) { d.end = 'E'; d.endKind = 'home'; d.overnight = null; continue; }
    const nextFirst = (days.slice(i + 1).find(x => x.stops.length) || {}).stops;
    const f = nextFirst ? nextFirst[0] : null, l = d.stops[d.stops.length - 1] || null;
    const given = input.overnights && Object.prototype.hasOwnProperty.call(input.overnights, i) ? input.overnights[i] : undefined;
    const far = (f && get('S', f).min > T) || (l && get(l, 'S').min > T);
    if (given === null || (given === undefined && !far)) { d.end = 'S'; d.endKind = 'home'; d.overnight = null; continue; }
    let town;
    const anchor = f && get('S', f).min > T ? f : l || f;   // near tomorrow's first stop; or near today's last when tomorrow is free
    if (given) town = { town: given.town || '', lat: given.lat, lng: given.lng, suggested: false, lodging: given.lodging || null };
    else {
      const t = nearestTown(pts[anchor]);
      if (t && t.km <= NEAR_TOWN_KM) {
        const key = 'T:' + t.town; pts[key] = [t.lat, t.lng];
        const m = get(key, anchor).min;
        town = { town: t.town, lat: t.lat, lng: t.lng, suggested: true, why: anchor === f
          ? { en: 'near ' + dayWord(dates[i + 1], 'en') + '’s first stop (' + m + ' min)', sw: 'karibu na kituo cha kwanza cha ' + dayWord(dates[i + 1], 'sw') + ' (dakika ' + m + ')' }
          : { en: 'near today’s last stop (' + m + ' min); home is far', sw: 'karibu na kituo cha mwisho cha leo (dakika ' + m + '); nyumbani ni mbali' } };
      } else {
        town = { town: '', lat: pts[anchor][0], lng: pts[anchor][1], suggested: true, why: { en: 'no listed town near ' + (names[anchor] || 'the stop') + '; stay nearby', sw: 'hakuna mji ulioorodheshwa karibu na ' + (names[anchor] || 'kituo') + '; lala karibu' } };
      }
    }
    const key = town.town ? 'T:' + town.town : 'N:' + i;
    pts[key] = [town.lat, town.lng]; names[key] = town.town || names[f] || '';
    d.end = key; d.endKind = 'overnight'; d.overnight = town;
  }
  days.forEach((d, i) => { if (i > 0) d.start = days[i - 1].endKind === 'overnight' ? days[i - 1].end : 'S'; });
  days.forEach(d => { d.sched = schedule({ start: d.start, end: d.end, endKind: d.endKind, stops: d.stops.map(x => by.get(x)) }, opts); });
  return { days, unplanned, pts, names };
}
const DAY_EN = ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday'], DAY_SW = ['Jumapili', 'Jumatatu', 'Jumanne', 'Jumatano', 'Alhamisi', 'Ijumaa', 'Jumamosi'];
export const dayWord = (iso, lang) => (lang === 'sw' ? DAY_SW : DAY_EN)[new Date(iso + 'T00:00:00Z').getUTCDay()];

/**
 * Running late: re-time the rest of `day` from `now` at `from` (a key); the stops that no longer fit before the end of
 * the working day (or their window) move. → { keep: [sid], move: [sid], sched }
 */
export function replanDay(day, stops, opts, now, from) {
  const by = new Map(stops.map(s => [s.sid, s]));
  // from lunch time on, lunch is not planned again (they will have eaten, or will on the way)
  const lunch = opts.lunch || DEFAULTS.lunch;
  if (hm(lunch.at) != null && now >= hm(lunch.at)) opts = Object.assign({}, opts, { lunch: Object.assign({}, lunch, { skip: true }) });
  const keep = [], move = [];
  for (const sid of day.stops) {
    const trial = keep.concat(sid);
    const sc = schedule({ start: from, end: day.end, endKind: day.endKind, stops: trial.map(x => by.get(x)) }, Object.assign({}, opts, { startAt: now }));
    const newLate = sc.warnings.some(w => w.sid === sid && (w.code === 'AFTER_CLOSE' || w.code === 'FIXED_LATE'));
    if (sc.over > 0 || newLate) move.push(sid); else keep.push(sid);
  }
  const sched = schedule({ start: from, end: day.end, endKind: day.endKind, stops: keep.map(x => by.get(x)) }, Object.assign({}, opts, { startAt: now }));
  return { keep, move, sched };
}

/** Where a new stop fits best: the day and position with the least extra cost that still ends on time (or the least bad). */
export function bestInsert(days, stop, stopsBy, opts) {
  let best = null;
  days.forEach((d, i) => {
    const base = schedule({ start: d.start, end: d.end, stops: d.stops.map(x => stopsBy.get(x)) }, opts).cost;
    for (let k = 0; k <= d.stops.length; k++) {
      const ids = d.stops.slice(0, k).concat(stop.sid, d.stops.slice(k));
      const sc = schedule({ start: d.start, end: d.end, stops: ids.map(x => (x === stop.sid ? stop : stopsBy.get(x))) }, opts);
      const v = sc.cost - base + (sc.over ? 1000 : 0);
      if (!best || v < best.v) best = { day: i, pos: k, v };
    }
  });
  return best;
}
