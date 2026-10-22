// Trip advice: weather and road conditions for a planned visit or a day of visits.
// For each leg (start → stop 1 → stop 2 …): the route and its tarmac/murram split (lib/roads.js), the weather
// at the destination and halfway (lib/weather.js), and field reports near the route (lib/road-reports.js),
// turned into a time estimate, warnings in English and Kiswahili, and a risk level. See docs/trip-weather-roads.md.
//
// The speed model (MODEL below; every number can be tuned there):
//   ORS gives its own driving time; it is split into tarmac, murram and unknown minutes (lib/roads.js).
//   Each part is multiplied by a factor for the road wetness (`roadLevel` from lib/weather.js, which counts the
//   2 days before too). [usual, worst] factors:
//                  dry          light         wet          heavy
//     tarmac      1.0  / 1.15   1.0  / 1.15   1.0 / 1.2    1.0 / 1.3     (ORS speed; the worst case allows for traffic and rain)
//     murram      1.3  / 1.5    1.45 / 1.8    1.8 / 2.5    2.5 / 3.5     (ORS assumes a dry graded road; real murram is slower)
//   An unknown surface counts as half murram (unknownShare 0.5); ORS's way type turns untagged trunk roads into
//   tarmac and untagged tracks into murram first. Then a penalty for each active road report near the route
//   (PENALTY, [usual, worst] minutes by wetness). estMin uses the usual numbers, estMaxMin the worst.
//   No forecast yet (more than 16 days ahead): usual = dry; worst = wet in the rainy seasons (Mar–May, Oct–Dec), else light.
//
// Without ORS_API_KEY (or when ORS fails for a leg): km = straight line × 1.4, at 45 km/h, with 30% of it counted
// as murram for the weather; the leg says roads: false and only reports within 1 km of the destination are used.
//
// Learned times: recordActual(env, legKey, minutes) keeps the last 15 real trip times per leg (both directions
// share one key). From 3 trips the median is blended in: weight n / (n + 3), at most 0.8; trips in the same kind of
// weather (dry/light vs wet/heavy) are used when there are 3 of them.
import { getJson, putJson } from './pin-store.js';
import { routeLeg, metres, midpoint, asPoint, okPoint } from './roads.js';
import { forecastTable, dayFrom, cellOf, nairobiDay, addDays, daysBetween, isDay, isStorm, LEVEL_RANK, WX_DAYS, PAST_DAYS, rainySeason, worseLevel } from './weather.js';
import { allReports, nearRoute, nearPoint, NEAR_M, KIND_WORDS } from './road-reports.js';

export const MODEL = {
  paved: { dry: [1.0, 1.15], light: [1.0, 1.15], wet: [1.0, 1.2], heavy: [1.0, 1.3] },
  unpaved: { dry: [1.3, 1.5], light: [1.45, 1.8], wet: [1.8, 2.5], heavy: [2.5, 3.5] },
  unknownShare: 0.5,
  fallback: { roadFactor: 1.4, kmh: 45, murramShare: 0.3 },
  learn: { min: 3, keep: 15, maxWeight: 0.8 }
};
// minutes added per report near the route, [usual, worst], by road wetness
export const PENALTY = {
  potholes: { dry: [3, 8], light: [3, 8], wet: [5, 15], heavy: [5, 15] },
  flooded: { dry: [10, 30], light: [10, 30], wet: [20, 60], heavy: [20, 60] },
  impassable_when_wet: { dry: [0, 5], light: [10, 30], wet: [45, 120], heavy: [45, 120] },
  under_construction: { dry: [10, 25], light: [10, 25], wet: [10, 30], heavy: [15, 40] },
  bridge_out: { dry: [30, 90], light: [30, 90], wet: [30, 90], heavy: [30, 90] },
  slow_murram: { dry: [5, 15], light: [5, 15], wet: [15, 40], heavy: [15, 40] },
  good_tarmac: { dry: [0, 0], light: [0, 0], wet: [0, 0], heavy: [0, 0] }
};
export const MAX_STOPS = 12;
export const RISK = ['ok', 'caution', 'difficult'];
const RANK = { info: 0, ok: 0, caution: 1, difficult: 2 };
const worseRisk = (a, b) => (RANK[a] >= RANK[b] ? a : b);
const r1 = v => Math.round(v * 10) / 10;
const round5 = v => Math.max(5, Math.round(v / 5) * 5);

// ---- words ------------------------------------------------------------------------------------------
export function fmtMin(m) {
  const v = Math.max(1, Math.round(m)), h = Math.floor(v / 60), mm = v % 60;
  return { en: h ? '~' + h + ' h' + (mm ? ' ' + String(mm).padStart(2, '0') : '') : '~' + v + ' min', sw: h ? '~saa ' + h + (mm ? ' dak ' + mm : '') : '~dakika ' + v };
}
const kmTxt = v => (v >= 10 ? Math.round(v) : r1(v));
export function ago(iso, now) {
  const d = Math.max(0, Math.floor(((now || Date.now()) - Date.parse(iso)) / 864e5));
  if (d === 0) return { en: 'today', sw: 'leo' };
  if (d === 1) return { en: 'yesterday', sw: 'jana' };
  if (d < 60) return { en: d + ' days ago', sw: 'siku ' + d + ' zilizopita' };
  const m = Math.round(d / 30);
  return { en: m + ' months ago', sw: 'miezi ' + m + ' iliyopita' };
}
const DAY_SW = ['Jumapili', 'Jumatatu', 'Jumanne', 'Jumatano', 'Alhamisi', 'Ijumaa', 'Jumamosi'];
const DAY_EN = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
const MON = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
export function dayName(iso) { const d = new Date(iso + 'T00:00:00Z'); return { en: DAY_EN[d.getUTCDay()] + ' ' + d.getUTCDate() + ' ' + MON[d.getUTCMonth()], sw: DAY_SW[d.getUTCDay()] + ' ' + d.getUTCDate() + '/' + (d.getUTCMonth() + 1) }; }

// "62 km tarmac · 8 km murram", "Rain 25 mm", "~1 h 40" (and Kiswahili) for the UI's chips
export function chips(leg) {
  const s = leg.surface || {}, lk = s.likely || {};
  const pav = (s.pavedKm || 0) + (lk.pavedKm || 0), unp = (s.unpavedKm || 0) + (lk.unpavedKm || 0);
  const unk = Math.max(0, (s.unknownKm || 0) - (lk.pavedKm || 0) - (lk.unpavedKm || 0));
  const en = [], sw = [];
  if (leg.roads === false) { en.push(kmTxt(leg.km) + ' km (estimate)'); sw.push('km ' + kmTxt(leg.km) + ' (makadirio)'); }
  else {
    if (pav >= 0.5) { en.push(kmTxt(pav) + ' km tarmac'); sw.push('km ' + kmTxt(pav) + ' lami'); }
    if (unp >= 0.5) { en.push(kmTxt(unp) + ' km murram'); sw.push('km ' + kmTxt(unp) + ' murram'); }
    if (unk >= 1) { en.push(kmTxt(unk) + ' km unknown'); sw.push('km ' + kmTxt(unk) + ' haijulikani'); }
  }
  const w = leg.weather || {};
  const rain = !w.available ? { en: 'No forecast yet', sw: 'Bado hakuna utabiri' }
    : w.rainMm >= 1 ? { en: 'Rain ' + Math.round(w.rainMm) + ' mm', sw: 'Mvua mm ' + Math.round(w.rainMm) } : { en: 'Dry', sw: 'Hakuna mvua' };
  const t = fmtMin(leg.estMin);
  return { surface: { en: en.join(' · '), sw: sw.join(' · ') }, rain, time: t };
}

// ---- the model --------------------------------------------------------------------------------------
/** Minutes for a leg in given road wetness: { est, max, dryEst }. leg: { minutes: { paved, unpaved, unknown } } */
export function speedModel(minutes, level, opts = {}) {
  const M = opts.model || MODEL, lv = MODEL.paved[level] ? level : 'dry';
  const maxLv = opts.maxLevel && MODEL.paved[opts.maxLevel] ? opts.maxLevel : lv;
  const share = M.unknownShare;
  const f = (lvl, k) => {
    const p = M.paved[lvl][k], u = M.unpaved[lvl][k];
    return (minutes.paved || 0) * p + (minutes.unpaved || 0) * u + (minutes.unknown || 0) * (p + (u - p) * share);
  };
  return { est: f(lv, 0), max: Math.max(f(maxLv, 1), f(lv, 1)), dryEst: f('dry', 0) };
}
export function penalties(reports, level) {
  let est = 0, max = 0;
  (reports || []).forEach(r => { const p = (PENALTY[r.kind] || {})[MODEL.paved[level] ? level : 'dry']; if (p) { est += p[0]; max += p[1]; } });
  return { est, max };
}

// a leg with no road data: straight line × road factor at an assumed speed
export function fallbackLeg(a, b, reason) {
  const F = MODEL.fallback, km = metres(a, b) / 1000 * F.roadFactor, min = km / F.kmh * 60;
  return {
    roads: false, roadsReason: reason || 'no-key', km: r1(km), baseMin: Math.round(min),
    surface: { pavedKm: 0, unpavedKm: 0, unknownKm: r1(km), likely: { pavedKm: 0, unpavedKm: 0 } },
    minutes: { paved: min * (1 - F.murramShare), unpaved: min * F.murramShare, unknown: 0 },
    unpaved: [], geometry: [[a[0], a[1]], [b[0], b[1]]], tolM: 0, snapM: [0, 0], straight: true
  };
}

// ---- learned times ----------------------------------------------------------------------------------
const end3 = p => Number(p[0]).toFixed(3) + ',' + Number(p[1]).toFixed(3);
/** The same key both ways: "0.514,35.270|1.015,35.000" (ends rounded to ~110 m, sorted). */
export function legKey(from, to) { const a = asPoint(from), b = asPoint(to); return [end3(a), end3(b)].sort().join('|'); }
export const okLegKey = k => /^-?\d{1,2}\.\d{3},-?\d{1,3}\.\d{3}\|-?\d{1,2}\.\d{3},-?\d{1,3}\.\d{3}$/.test(String(k || ''));
const wetGroup = lv => (LEVEL_RANK[lv] >= 2 ? 1 : 0);
/** Record a real trip time for a leg (e.g. from the visit tracker's check-ins). opts.level: the road wetness that day. */
export async function recordActual(env, key, minutes, opts = {}) {
  const m = Number(minutes);
  if (!okLegKey(key)) return { ok: false, reason: 'leg' };
  if (!isFinite(m) || m < 1 || m > 600) return { ok: false, reason: 'minutes' };
  if (!env.FEED) return { ok: false, reason: 'no-storage' };
  const k = 'triplearn:' + key, list = await getJson(env, k, []);
  list.push({ m: Math.round(m), w: wetGroup(opts.level || 'dry'), at: new Date(opts.now || Date.now()).toISOString() });
  const keep = list.slice(-MODEL.learn.keep);
  await putJson(env, k, keep);
  return { ok: true, n: keep.length };
}
const median = a => { const s = a.slice().sort((x, y) => x - y), n = s.length; return n ? (n % 2 ? s[(n - 1) / 2] : (s[n / 2 - 1] + s[n / 2]) / 2) : null; };
/** { n, median, weight } or null (fewer than 3 trips). */
export function learnedFrom(list, level) {
  const L = MODEL.learn, same = (list || []).filter(x => x.w === wetGroup(level));
  const use = same.length >= L.min ? same : (list || []).length >= L.min ? list : null;
  if (!use) return null;
  const n = use.length;
  return { n, median: median(use.map(x => x.m)), weight: r1(Math.min(L.maxWeight, n / (n + 3)) * 100) / 100, sameWeather: use === same };
}

// ---- warnings ---------------------------------------------------------------------------------------
const W = (code, level, en, sw, extra) => Object.assign({ code, level, en, sw }, extra || {});
function murramTail(leg) {
  const p = leg.unpaved || []; if (!p.length) return 0;
  const last = p[p.length - 1]; if (last.endKm < leg.km - 1) return 0;
  let start = last.startKm;
  for (let i = p.length - 2; i >= 0 && p[i].endKm >= start - 1; i--) start = p[i].startKm;
  return leg.km - start;
}
function where(r, fromName) {
  if (r.kmBeforeEnd != null && r.kmBeforeEnd < 0.3) return { en: 'at the site', sw: 'karibu na eneo' };
  if (r.kmBeforeEnd != null && r.kmBeforeEnd <= r.alongKm) return { en: kmTxt(r.kmBeforeEnd) + ' km before the site', sw: 'km ' + kmTxt(r.kmBeforeEnd) + ' kabla ya kufika' };
  if (r.alongKm != null) return fromName ? { en: kmTxt(r.alongKm) + ' km after ' + fromName, sw: 'km ' + kmTxt(r.alongKm) + ' baada ya ' + fromName } : { en: kmTxt(r.alongKm) + ' km from the start', sw: 'km ' + kmTxt(r.alongKm) + ' kutoka mwanzo' };
  return { en: kmTxt(r.distM / 1000) + ' km from the site', sw: 'km ' + kmTxt(r.distM / 1000) + ' kutoka eneo' };
}
function reportWarning(r, level, fromName, now) {
  const k = KIND_WORDS[r.kind] || { en: r.kind, sw: r.kind }, at = where(r, fromName), when = ago(r.at, now);
  const by = r.by ? { en: ' by ' + r.by, sw: r.by } : { en: '', sw: 'timu' };
  let seen = { en: '', sw: '' };
  if (r.seen && Date.parse(r.seen) - Date.parse(r.at) > 864e5) { const s = ago(r.seen, now); seen = { en: '; seen again ' + s.en, sw: '; ilionekana tena ' + s.sw }; }
  const wet = LEVEL_RANK[level] >= 2;
  let lv = 'caution';
  if (r.kind === 'good_tarmac') lv = 'info';
  if (r.kind === 'bridge_out') lv = 'difficult';
  if (r.kind === 'impassable_when_wet') lv = wet ? 'difficult' : level === 'light' ? 'caution' : 'info';
  if (r.kind === 'flooded' && wet) lv = 'difficult';
  if (r.kind === 'slow_murram' && !wet) lv = 'info';
  const en = k.en + ' reported ' + at.en + by.en + ', ' + when.en + seen.en;
  const sw = 'Ripoti: ' + k.sw + ', ' + at.sw + ' — ' + by.sw + ', ' + when.sw + seen.sw;
  return W('REPORT_' + r.kind.toUpperCase(), lv, en, sw, { rid: r.rid });
}

/** Warnings for one evaluated leg. ev: { leg, wx, est, dryEst, reports, fromName, now } */
export function legWarnings(ev) {
  const { leg, wx, reports, fromName, now } = ev, out = [];
  const s = leg.surface || {}, murram = r1((s.unpavedKm || 0) + ((s.likely || {}).unpavedKm || 0));
  const lv = wx && wx.available ? wx.roadLevel : null;
  if (leg.roads !== false && murram >= 1 && lv && lv !== 'dry') {
    const tail = murramTail(leg), last = tail >= 0.6 * murram;
    const extra = round5(ev.est - ev.dryEst);
    const bit = last ? { en: 'Last ' + kmTxt(tail) + ' km is murram', sw: 'Kilomita ' + kmTxt(tail) + ' za mwisho ni murram' }
      : { en: kmTxt(murram) + ' km of this leg is murram', sw: 'Kilomita ' + kmTxt(murram) + ' za safari hii ni murram' };
    const rainNow = wx.rainMm >= 1, mm = Math.round(rainNow ? wx.rainMm : wx.prevRainMm);
    const why = rainNow ? { en: ' and ' + mm + ' mm of rain is forecast', sw: ' na mvua ya mm ' + mm + ' inatarajiwa' }
      : { en: ' and ' + mm + ' mm of rain fell in the 2 days before', sw: ' na mvua ya mm ' + mm + ' ilinyesha siku mbili zilizopita' };
    const fourBy4 = lv === 'heavy' || (lv === 'wet' && murram >= 3);
    const tip = fourBy4 ? { en: '; allow about ' + extra + ' min extra or take a 4x4', sw: '; ongeza takriban dakika ' + extra + ' au tumia gari la 4x4' }
      : { en: '; allow about ' + extra + ' min extra', sw: '; ongeza takriban dakika ' + extra };
    const level = lv === 'heavy' ? (murram >= 2 ? 'difficult' : 'caution') : lv === 'wet' ? 'caution' : murram >= 5 ? 'caution' : 'info';
    const code = lv === 'heavy' ? 'MURRAM_HEAVY_RAIN' : lv === 'wet' ? 'MURRAM_WET' : 'MURRAM_LIGHT_RAIN';
    out.push(W(rainNow ? code : 'MURRAM_AFTER_RAIN', level, bit.en + why.en + tip.en, bit.sw + why.sw + tip.sw, { murramKm: murram, extraMin: extra }));
    if (lv === 'heavy' && murram >= 2) out.push(W('MAY_BE_IMPASSABLE', 'difficult', 'Murram may be impassable after this much rain; check with someone local before you go', 'Murram huenda isipitike baada ya mvua hii; uliza mtu wa eneo hilo kabla ya kwenda'));
  }
  if (leg.roads === false && lv && LEVEL_RANK[lv] >= 2) {
    out.push(W('RAIN_NO_ROADS', 'caution', Math.round(wx.rainMm >= 1 ? wx.rainMm : wx.soakMm) + ' mm of rain: allow extra time if the road is murram', 'Mvua ya mm ' + Math.round(wx.rainMm >= 1 ? wx.rainMm : wx.soakMm) + ': ongeza muda kama barabara ni murram'));
  }
  if (wx && wx.available && isStorm(wx.code)) out.push(W('STORM', 'caution', 'Thunderstorms forecast; travel in the morning if you can', 'Radi zinatarajiwa; safiri asubuhi ikiwezekana'));
  if (wx && !wx.available && wx.reason === 'too-far') {
    const season = rainySeason(wx.date);
    out.push(W('NO_FORECAST', 'info', season ? 'No forecast yet (it comes 16 days ahead); it is the rainy season, so allow extra time on murram' : 'No forecast yet (it comes 16 days ahead)',
      season ? 'Bado hakuna utabiri (unakuja siku 16 kabla); ni msimu wa mvua, kwa hiyo ongeza muda kwenye murram' : 'Bado hakuna utabiri (unakuja siku 16 kabla)'));
  }
  if (leg.roads !== false) {
    const unk = Math.max(0, (s.unknownKm || 0) - ((s.likely || {}).pavedKm || 0) - ((s.likely || {}).unpavedKm || 0));
    if (unk >= 5 && unk >= 0.3 * leg.km) out.push(W('SURFACE_UNKNOWN', 'info', 'Road surface not known for ' + kmTxt(unk) + ' km of this leg; it may be murram', 'Aina ya barabara haijulikani kwa km ' + kmTxt(unk) + '; huenda ni murram'));
    const off = (leg.snapM || [])[1] || 0;
    if (off >= 500) out.push(W('UNMAPPED_END', 'info', 'The site is ' + kmTxt(off / 1000) + ' km from the nearest mapped road; the last stretch is probably a track', 'Eneo liko km ' + kmTxt(off / 1000) + ' kutoka barabara iliyo kwenye ramani; sehemu ya mwisho huenda ni njia ya murram'));
  }
  (reports || []).forEach(r => out.push(reportWarning(r, lv || 'dry', fromName, now)));
  return out;
}

// ---- one leg on one day -----------------------------------------------------------------------------
function evaluate(base, wx, reports, learned, opts) {
  const known = wx && wx.available;
  const lv = known ? wx.roadLevel : 'dry';
  const maxLv = known ? lv : rainySeason(opts.date) ? 'wet' : 'light';
  const sm = speedModel(base.minutes, lv, { maxLevel: maxLv });
  const pen = penalties(reports, known ? lv : maxLv);
  const penUsual = known ? pen.est : penalties(reports, 'dry').est;
  let est = sm.est + penUsual, max = sm.max + pen.max;
  const model = Math.round(est);
  let learn = null;
  if (learned) {
    learn = learnedFrom(learned, lv);
    if (learn) { est = (1 - learn.weight) * est + learn.weight * learn.median; max = Math.max(max, est * 1.15, learn.median); }
  }
  const leg = Object.assign({}, base, { estMin: Math.round(est), estMaxMin: Math.max(Math.round(est), Math.round(max)) });
  if (learn) leg.learned = Object.assign({ modelMin: model }, learn);
  leg.warnings = legWarnings({ leg, wx, est: sm.est, dryEst: sm.dryEst, reports, fromName: opts.fromName, now: opts.now });
  leg.riskLevel = leg.warnings.reduce((a, w) => worseRisk(a, w.level === 'info' ? 'ok' : w.level), 'ok');
  leg.chips = chips(leg);
  return leg;
}

// the weather for a leg: the worse of the destination and the halfway point
function legWeather(dest, mid) {
  if (!dest || !dest.available) return dest && mid && mid.available ? Object.assign({}, mid, { at: 'mid' }) : dest || mid || { available: false };
  if (!mid || !mid.available) return Object.assign({}, dest, { at: 'dest' });
  const wd = LEVEL_RANK[dest.roadLevel] >= LEVEL_RANK[mid.roadLevel] && dest.rainMm >= mid.rainMm;
  const w = wd ? dest : mid;
  return Object.assign({}, w, {
    at: wd ? 'dest' : 'mid', roadLevel: worseLevel(dest.roadLevel, mid.roadLevel), level: worseLevel(dest.level, mid.level),
    dest: { rainMm: dest.rainMm, level: dest.level, summary: dest.summary, summarySw: dest.summarySw },
    mid: { rainMm: mid.rainMm, level: mid.level, summary: mid.summary, summarySw: mid.summarySw }
  });
}

function stopOf(s) {
  if (!s) return null;
  const p = asPoint(s);
  if (!okPoint(p)) return null;
  return { id: s.id != null ? String(s.id).slice(0, 20) : '', name: String(s.name || s.title || '').replace(/[<>]/g, '').slice(0, 80), lat: p[0], lng: p[1] };
}

// Promise.all in small batches (ORS allows 40 routes a minute)
async function batched(items, n, fn) { const out = []; for (let i = 0; i < items.length; i += n) out.push(...await Promise.all(items.slice(i, i + n).map(fn))); return out; }

/**
 * tripAdvice(env, { from, stops: [{ id, lat, lng, name }], date, until, geometry, now })
 *   from: optional start ({ lat, lng, name } or [lat, lng]); without it the first stop is the start.
 *   date: "YYYY-MM-DD" (default today in Nairobi); until: optional last day of a range → bestDay and days[].
 * → { ok, date, roads, roadsReason?, legs: [...], total: {...}, bestDay?, days? }
 */
export async function tripAdvice(env, opts) {
  const now = opts.now || Date.now(), today = nairobiDay(now);
  const date = isDay(opts.date) ? opts.date : today;
  const stops = (opts.stops || []).map(stopOf);
  if (!stops.length || stops.some(s => !s)) return { ok: false, reason: 'stops' };
  if (stops.length > MAX_STOPS) return { ok: false, reason: 'too-many' };
  const start = opts.from ? stopOf(Object.assign({ name: (opts.from && opts.from.name) || '' }, Array.isArray(opts.from) ? { lat: opts.from[0], lng: opts.from[1] } : opts.from)) : null;
  if (opts.from && !start) return { ok: false, reason: 'from' };
  const pts = start ? [start].concat(stops) : stops;
  if (pts.length < 2) return { ok: false, reason: 'stops' };
  const pairs = []; for (let i = 1; i < pts.length; i++) pairs.push([pts[i - 1], pts[i]]);

  // routes (cached 30 days) and reports, once for every day
  const routes = await batched(pairs, 4, ([a, b]) => routeLeg(env, [a.lat, a.lng], [b.lat, b.lng], now));
  const list = await allReports(env);
  const learnt = await Promise.all(pairs.map(([a, b]) => getJson(env, 'triplearn:' + legKey([a.lat, a.lng], [b.lat, b.lng]), null)));
  const bases = pairs.map(([a, b], i) => {
    const r = routes[i], A = [a.lat, a.lng], B = [b.lat, b.lng];
    const base = r.ok ? Object.assign({ roads: true }, r) : fallbackLeg(A, B, r.reason);
    delete base.ok; delete base.cached;
    let reps;
    if (base.roads) {
      reps = nearRoute(list, base.geometry, { m: NEAR_M + Math.min(100, base.tolM || 0), now, totalKm: base.km });
      // the last mile may not be on the map: also anything within 500 m of the site
      nearPoint(list, B, 0.5, now).forEach(x => { if (!reps.some(y => y.rid === x.rid)) reps.push(Object.assign(x, { kmBeforeEnd: r1(x.distM / 1000), alongKm: base.km })); });
    } else reps = nearPoint(list, B, 1, now).map(x => Object.assign(x, { kmBeforeEnd: r1(x.distM / 1000), alongKm: null }));
    const mid = base.roads ? midpoint(base.geometry) : [(a.lat + b.lat) / 2, (a.lng + b.lng) / 2];
    return { from: a, to: b, base, reps, mid, key: legKey(A, B), learned: learnt[i] };
  });
  const roads = bases.every(x => x.base.roads), roadsReason = roads ? undefined : (bases.find(x => !x.base.roads) || {}).base.roadsReason;

  // weather: one table per 5 km square, fetched once per request (and cached 3 hours)
  const tables = new Map();
  const table = async p => { const c = cellOf(p[0], p[1]); if (!tables.has(c)) tables.set(c, forecastTable(env, p[0], p[1], now)); return tables.get(c); };
  const wxFor = async (p, d) => {
    const off = daysBetween(today, d);
    if (off >= WX_DAYS) return { available: false, date: d, reason: 'too-far' };
    if (off < -PAST_DAYS) return { available: false, date: d, reason: 'past' };
    const t = await table(p); return t ? dayFrom(t.days, d, today) : { available: false, date: d, reason: 'down' };
  };
  const dayPlan = async (d, geometry) => {
    const legs = [];
    for (const x of bases) {
      const wx = legWeather(await wxFor([x.to.lat, x.to.lng], d), await wxFor(x.mid, d));
      const leg = evaluate(Object.assign({}, x.base, { key: x.key, from: x.from, to: x.to, weather: wx }), wx, x.reps, x.learned, { date: d, fromName: x.from.name, now });
      leg.reports = x.reps.map(r => ({ rid: r.rid, kind: r.kind, note: r.note, by: r.by, at: r.at, seen: r.seen, lat: r.lat, lng: r.lng, distM: r.distM, alongKm: r.alongKm, kmBeforeEnd: r.kmBeforeEnd, photo: r.photo || null }));
      if (!geometry) delete leg.geometry;
      legs.push(leg);
    }
    return { date: d, legs, total: totals(legs) };
  };

  const main = await dayPlan(date, opts.geometry !== false);
  const out = { ok: true, date, today, roads, legs: main.legs, total: main.total };
  if (!roads) out.roadsReason = roadsReason;
  if (isDay(opts.until) && opts.until > date) {
    const last = [opts.until, addDays(today, WX_DAYS - 1)].sort()[0], days = [];
    for (let d = date; d <= last && days.length < WX_DAYS; d = addDays(d, 1)) {
      const p = d === date ? main : await dayPlan(d, false);
      days.push({ date: d, name: dayName(d), estMin: p.total.estMin, estMaxMin: p.total.estMaxMin, riskLevel: p.total.riskLevel, rainMm: p.total.rainMm, level: p.total.level, warnings: p.total.warnings });
    }
    out.days = days;
    const best = bestOf(days);
    if (best) out.bestDay = best;
  }
  return out;
}

/** The driest, easiest day: lowest risk, then least rain on the roads, then shortest time; ties go to the earliest. */
export function bestOf(days) {
  const ok = (days || []).filter(d => d.level != null);
  if (!ok.length) return null;
  const b = ok.slice().sort((x, y) => RANK[x.riskLevel] - RANK[y.riskLevel] || LEVEL_RANK[x.level] - LEVEL_RANK[y.level] || x.rainMm - y.rainMm || x.estMin - y.estMin || (x.date < y.date ? -1 : 1))[0];
  return { date: b.date, name: b.name || dayName(b.date), estMin: b.estMin, rainMm: b.rainMm, level: b.level, riskLevel: b.riskLevel };
}

export function totals(legs) {
  const sum = k => legs.reduce((s, l) => s + (Number(l[k]) || 0), 0);
  const ss = k => r1(legs.reduce((s, l) => s + (Number((l.surface || {})[k]) || 0), 0));
  const sl = k => r1(legs.reduce((s, l) => s + (Number(((l.surface || {}).likely || {})[k]) || 0), 0));
  const known = legs.filter(l => l.weather && l.weather.available);
  const level = known.length ? known.reduce((a, l) => worseLevel(a, l.weather.roadLevel), 'dry') : null;
  const t = {
    km: r1(sum('km')), baseMin: Math.round(sum('baseMin')), estMin: Math.round(sum('estMin')), estMaxMin: Math.round(sum('estMaxMin')),
    surface: { pavedKm: ss('pavedKm'), unpavedKm: ss('unpavedKm'), unknownKm: ss('unknownKm'), likely: { pavedKm: sl('pavedKm'), unpavedKm: sl('unpavedKm') } },
    rainMm: known.length ? Math.max(...known.map(l => l.weather.rainMm)) : null, level,
    riskLevel: legs.reduce((a, l) => worseRisk(a, l.riskLevel), 'ok'),
    warnings: legs.reduce((s, l) => s + l.warnings.filter(w => w.level !== 'info').length, 0)
  };
  t.chips = { time: fmtMin(t.estMin), surface: chips({ km: t.km, surface: t.surface, roads: legs.every(l => l.roads !== false), estMin: t.estMin, weather: {} }).surface };
  return t;
}

/**
 * The driest day for a list of projects (Plan a week): weatherOnly, one forecast per 5 km square.
 * weekAdvice(env, { projects: [{ id, name, lat, lng }], start, days, groups: [[ids]] })
 * Each project-day gets a score: road wetness (0 dry … 3 heavy) × 2 when a report near the site says the road is
 * bad in rain (impassable_when_wet, slow_murram, flooded, within 2 km), plus rain mm / 20. Lowest total wins.
 */
export async function weekAdvice(env, opts) {
  const now = opts.now || Date.now(), today = nairobiDay(now);
  const start = isDay(opts.start) && opts.start >= today ? opts.start : today;
  const n = Math.max(1, Math.min(WX_DAYS, Math.round(Number(opts.days) || 5)));
  const dates = []; for (let i = 0; i < n; i++) dates.push(addDays(start, i));
  const projects = (opts.projects || []).map(stopOf).filter(Boolean).slice(0, 60);
  if (!projects.length) return { ok: false, reason: 'stops' };
  const list = await allReports(env), tables = new Map();
  const per = [];
  for (const p of projects) {
    const c = cellOf(p.lat, p.lng);
    if (!tables.has(c)) tables.set(c, tables.size < 40 ? await forecastTable(env, p.lat, p.lng, now) : null);
    const t = tables.get(c);
    const bad = nearPoint(list, [p.lat, p.lng], 2, now).filter(r => ['impassable_when_wet', 'slow_murram', 'flooded', 'bridge_out'].includes(r.kind));
    const days = dates.map(d => {
      const w = t ? dayFrom(t.days, d, today) : { available: false, reason: daysBetween(today, d) >= WX_DAYS ? 'too-far' : 'down' };
      if (!w.available) return { date: d, available: false, reason: w.reason };
      const score = LEVEL_RANK[w.roadLevel] * (bad.length ? 2 : 1) + w.rainMm / 20;
      return { date: d, available: true, rainMm: w.rainMm, rainChance: w.rainChance, level: w.level, roadLevel: w.roadLevel, morningRainMm: w.morningRainMm, summary: w.summary, summarySw: w.summarySw, tMax: w.tMax, tMin: w.tMin, code: w.code, score: Math.round(score * 100) / 100 };
    });
    const best = days.filter(d => d.available).sort((a, b) => a.score - b.score || (a.date < b.date ? -1 : 1))[0];
    per.push({ id: p.id, name: p.name, lat: p.lat, lng: p.lng, wetReports: bad.map(r => ({ rid: r.rid, kind: r.kind, distM: r.distM })), bestDay: best ? best.date : null, days });
  }
  const rank = ids => {
    const set = ids ? new Set(ids.map(String)) : null, ps = per.filter(p => !set || set.has(p.id));
    return dates.map(d => {
      const ds = ps.map(p => p.days.find(x => x.date === d)).filter(x => x && x.available);
      if (!ds.length) return { date: d, name: dayName(d), available: false };
      return { date: d, name: dayName(d), available: true, score: Math.round(ds.reduce((s, x) => s + x.score, 0) * 100) / 100, rainMaxMm: Math.max(...ds.map(x => x.rainMm)), level: ds.reduce((a, x) => worseLevel(a, x.roadLevel), 'dry'), wetSites: ds.filter(x => LEVEL_RANK[x.roadLevel] >= 2).length };
    });
  };
  const pick = r => { const a = r.filter(x => x.available).sort((x, y) => x.score - y.score || (x.date < y.date ? -1 : 1)); return a[0] ? { date: a[0].date, name: a[0].name, rainMaxMm: a[0].rainMaxMm, level: a[0].level } : null; };
  const days = rank(null);
  const out = { ok: true, today, start, days, bestDay: pick(days), projects: per };
  if (Array.isArray(opts.groups) && opts.groups.length) out.groups = opts.groups.slice(0, 7).map(g => { const r = rank(g); return { ids: g.map(String), bestDay: pick(r), days: r }; });
  if (dates.some(d => daysBetween(today, d) >= WX_DAYS)) out.note = { en: 'Forecasts reach 16 days ahead; later days have no weather yet', sw: 'Utabiri unafika siku 16 mbele; siku za baadaye bado hazina hali ya hewa' };
  return out;
}
