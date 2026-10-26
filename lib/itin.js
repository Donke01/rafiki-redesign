// Field trip itineraries (Plan a trip): storage, who may see and change a trip, building the plan (lib/itin-plan.js
// with real legs and weather from lib/trip.js), costs (lib/fuel-model.js, lib/fuel-prices.js), the packing list,
// live progress, re-planning, the trip summary and private share links. The API is functions/api/itin/[[path]].js;
// the contract the apps use is in docs/itinerary.md.
//
// KV (FEED): itin:<id> (one trip), itinidx (the list), itinshare:<sha256(token)>, itintrack:<id>, itinsettings,
// itinwindows, itinalerts, vehicles, fuellog:<vid>.
import { getJson, putJson, isAdminName, sameName, team, sendMail, alertHtml } from './pin-store.js';
import { tripAdvice, weekAdvice, legKey, recordActual, MAX_STOPS, dayName } from './trip.js';
import { metres } from './roads.js';
import { addDays, daysBetween, nairobiDay, isDay, LEVEL_RANK } from './weather.js';
import { planTrip, schedule, makeGet, replanDay, bestInsert, hm, fmt, okHm, DEFAULTS, LIMITS, dayWord, OVERNIGHT_AFTER } from './itin-plan.js';
import { matrixFor } from './itin-matrix.js';
import { useFor, loadFactor, legLitres, basisWords, TOWN_KM } from './fuel-model.js';
import { priceFor, priceLabel } from './fuel-prices.js';
import { nearestTown, countryOf, TOWNS } from './towns.js';
import { candidates, isWater, hasGps, areaOf } from './pin-plan.js';
import { hereKey } from './pin-here.js';

export const STATUSES = ['draft', 'planned', 'active', 'done', 'cancelled'];
export const TYPE_KEYS = { 'Borehole well': 'borehole', 'Water collection': 'collection', School: 'school', Health: 'health', Orphanage: 'children', Farming: 'farming', Training: 'community', Nutrition: 'community', Community: 'community', Other: 'other' };
export const typeKey = t => TYPE_KEYS[String(t || '').trim()] || (/borehole|drill/i.test(t) ? 'borehole' : /water/i.test(t) ? 'collection' : /school|educ/i.test(t) ? 'school' : /health|medic|clinic/i.test(t) ? 'health' : /orphan|child/i.test(t) ? 'children' : /farm|agri/i.test(t) ? 'farming' : 'other');
export const MAX_INDEX = 1000, MAX_TRACK = 3000, MAX_TITLE = 80;
const TEST_DUE_DAYS = 182;
const clean = (s, n) => String(s == null ? '' : s).replace(/[<>]/g, '').replace(/\s+/g, ' ').trim().slice(0, n);
const strip = s => String(s || '').replace(/^[\s*#_-]+|[\s*#_]+$/g, '');
const r1 = v => Math.round(v * 10) / 10;
const nb = '\u00a0';
export const kesWords = v => 'KES' + nb + Math.round(Number(v) || 0).toLocaleString('en-US');

// ---- ids and tokens ---------------------------------------------------------------------------------------
const b64url = bytes => { let s = ''; bytes.forEach(b => { s += String.fromCharCode(b); }); return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, ''); };
export const newId = prefix => prefix + '_' + b64url(crypto.getRandomValues(new Uint8Array(9))).replace(/[-_]/g, 'x');
export const newToken = () => b64url(crypto.getRandomValues(new Uint8Array(32)));     // 43 characters
export const tokenShape = t => /^[A-Za-z0-9_-]{43}$/.test(String(t || ''));
export async function sha(s) { const d = await crypto.subtle.digest('SHA-256', new TextEncoder().encode('rafiki-share:' + s)); return [...new Uint8Array(d)].map(b => b.toString(16).padStart(2, '0')).join(''); }

// ---- settings ---------------------------------------------------------------------------------------------
// Money defaults are 0 (not set): these are office policy, so the Office sets them; costs list what is missing.
export const SETTINGS_DEFAULT = { lunchKes: 0, perDiemKes: 0, lodgingKes: 0, hours: DEFAULTS.hours, lunch: DEFAULTS.lunch, visitMin: DEFAULTS.visitMin, windows: DEFAULTS.windows, overnightAfterMin: OVERNIGHT_AFTER };
export async function settings(env) {
  const s = await getJson(env, 'itinsettings', {});
  return Object.assign({}, SETTINGS_DEFAULT, s, { visitMin: Object.assign({}, DEFAULTS.visitMin, s.visitMin || {}), windows: Object.assign({}, DEFAULTS.windows, s.windows || {}), hours: Object.assign({}, DEFAULTS.hours, s.hours || {}), lunch: Object.assign({}, DEFAULTS.lunch, s.lunch || {}) });
}
const okWindow = w => w && okHm(w.open) && okHm(w.close) && hm(w.open) < hm(w.close);
export async function saveSettings(env, b, by) {
  const cur = await getJson(env, 'itinsettings', {}), out = Object.assign({}, cur);
  for (const k of ['lunchKes', 'perDiemKes', 'lodgingKes']) if (b[k] != null) { const v = Number(b[k]); if (!isFinite(v) || v < 0 || v > 1e6) return { ok: false, reason: 'bad-amount', field: k }; out[k] = Math.round(v); }
  if (b.hours) { if (!okHm(b.hours.start) || !okHm(b.hours.end) || hm(b.hours.start) >= hm(b.hours.end)) return { ok: false, reason: 'hours' }; out.hours = { start: b.hours.start, end: b.hours.end }; }
  if (b.lunch) { if (!okHm(b.lunch.at)) return { ok: false, reason: 'lunch' }; out.lunch = { at: b.lunch.at, min: Math.max(0, Math.min(120, Math.round(Number(b.lunch.min) || 0))), skip: !!b.lunch.skip }; }
  if (b.visitMin && typeof b.visitMin === 'object') { out.visitMin = Object.assign({}, cur.visitMin || {}); for (const [k, v] of Object.entries(b.visitMin)) if (DEFAULTS.visitMin[k] != null && Number(v) >= 5 && Number(v) <= 480) out.visitMin[k] = Math.round(Number(v)); }
  if (b.windows && typeof b.windows === 'object') { out.windows = Object.assign({}, cur.windows || {}); for (const [k, v] of Object.entries(b.windows)) { if (v === null) delete out.windows[k]; else if (DEFAULTS.visitMin[k] != null && okWindow(v)) out.windows[k] = { open: v.open, close: v.close }; } }
  if (b.overnightAfterMin != null) out.overnightAfterMin = Math.max(30, Math.min(300, Math.round(Number(b.overnightAfterMin) || OVERNIGHT_AFTER)));
  out.updated = new Date().toISOString(); out.by = by;
  await putJson(env, 'itinsettings', out);
  return { ok: true, settings: await settings(env) };
}
export const windowsAll = env => getJson(env, 'itinwindows', {});
export async function saveWindow(env, b) {
  const id = String(b.id || '').replace(/\D/g, '').slice(0, 15);
  if (!id) return { ok: false, reason: 'bad-request' };
  const all = await windowsAll(env);
  const w = b.window === null ? null : b.window ? (okWindow(b.window) ? { open: b.window.open, close: b.window.close } : 'bad') : (all[id] || {}).window || null;
  if (w === 'bad') return { ok: false, reason: 'window' };
  if (b.fixed != null && b.fixed !== '' && !okHm(b.fixed)) return { ok: false, reason: 'fixed' };
  const rec = { open: w ? w.open : null, close: w ? w.close : null, fixed: b.fixed ? b.fixed : null, visitMin: Number(b.visitMin) >= 5 && Number(b.visitMin) <= 480 ? Math.round(Number(b.visitMin)) : null,
    days: Array.isArray(b.days) ? b.days.filter(d => Number.isInteger(d) && d >= 0 && d <= 6) : null };
  if (!rec.open && !rec.fixed && !rec.visitMin) delete all[id]; else all[id] = rec;
  await putJson(env, 'itinwindows', all);
  return { ok: true, windows: all };
}

// ---- storage and permissions ------------------------------------------------------------------------------
export const load = (env, id) => (/^it_[A-Za-z0-9]{6,20}$/.test(String(id || '')) ? getJson(env, 'itin:' + id, null) : Promise.resolve(null));
const indexRow = r => ({ id: r.id, title: r.title, owner: r.owner, people: r.people, viewers: r.viewers || [], from: r.from, to: r.to, status: r.status, ck: r.ck, projects: [...new Set(r.stops.map(s => s.id).filter(Boolean))], stops: r.stops.length, updated: r.updated });
export async function save(env, rec) {
  await putJson(env, 'itin:' + rec.id, rec);
  const idx = (await getJson(env, 'itinidx', [])).filter(x => x.id !== rec.id);
  idx.unshift(indexRow(rec));
  await putJson(env, 'itinidx', idx.slice(0, MAX_INDEX));
}
export async function remove(env, rec) {
  if (env.FEED) { await env.FEED.delete('itin:' + rec.id); await env.FEED.delete('itintrack:' + rec.id); if (rec.share && rec.share.h) await env.FEED.delete('itinshare:' + rec.share.h); }
  await putJson(env, 'itinidx', (await getJson(env, 'itinidx', [])).filter(x => x.id !== rec.id));
}
export function canSee(chk, r) { return !!chk.admin || [r.owner].concat(r.people || [], r.viewers || []).some(n => sameName(n, chk.who)); }
export function canEdit(chk, r) { return !!chk.admin || [r.owner].concat(r.people || []).some(n => sameName(n, chk.who)); }
export async function listFor(env, chk, q) {
  let idx = await getJson(env, 'itinidx', []);
  if (!chk.admin) idx = idx.filter(r => canSee(chk, r));
  const st = String(q.get('status') || '').split(',').filter(s => STATUSES.includes(s));
  if (st.length) idx = idx.filter(r => st.includes(r.status));
  if (q.get('mine') === '1') idx = idx.filter(r => sameName(r.owner, chk.who) || (r.people || []).some(n => sameName(n, chk.who)));
  if (q.get('person') && chk.admin) idx = idx.filter(r => (r.people || []).some(n => sameName(n, q.get('person'))));
  if (q.get('project')) idx = idx.filter(r => (r.projects || []).includes(String(q.get('project'))));
  const m = q.get('month'); if (/^\d{4}-\d{2}$/.test(m || '')) idx = idx.filter(r => r.from.slice(0, 7) <= m && r.to.slice(0, 7) >= m);
  return idx;
}

// ---- stops ------------------------------------------------------------------------------------------------
/** one stop from what the app sent: "104", { id }, or { sid?, id?, name, lat, lng, … } */
export function resolveStop(x, byId, st, wins, sid) {
  let o = typeof x === 'string' || typeof x === 'number' ? { id: String(x) } : x && typeof x === 'object' ? x : null;
  if (!o) return null;
  const id = o.id != null && /^\d{1,15}$/.test(String(o.id)) ? String(o.id) : '';
  const p = id ? byId.get(id) : null;
  let lat = Number(o.lat), lng = Number(o.lng != null ? o.lng : o.lon);
  if (!(isFinite(lat) && isFinite(lng) && Math.abs(lat) <= 90 && Math.abs(lng) <= 180 && !(lat === 0 && lng === 0))) {
    if (!p || !hasGps(p)) return null;
    lat = Number(p.gps[0]); lng = Number(p.gps[1]);
  }
  const type = o.type && DEFAULTS.visitMin[o.type] != null ? o.type : typeKey(p && p.type);
  const pw = id && wins[id] ? wins[id] : null;
  const window = o.window === null ? null : okWindow(o.window) ? { open: o.window.open, close: o.window.close, src: ['type', 'project', 'stop'].includes(o.window.src) ? o.window.src : 'stop' } : pw && pw.open ? { open: pw.open, close: pw.close, src: 'project' } : st.windows[type] ? { open: st.windows[type].open, close: st.windows[type].close, src: 'type' } : null;
  const fixed = okHm(o.fixed) ? o.fixed : o.fixed === null ? null : pw && pw.fixed ? pw.fixed : null;
  const visitMin = Number(o.visitMin) >= 5 && Number(o.visitMin) <= 480 ? Math.round(Number(o.visitMin)) : pw && pw.visitMin ? pw.visitMin : st.visitMin[type] || 45;
  return {
    sid: /^s\d{1,4}$/.test(String(o.sid || '')) ? o.sid : sid, id, name: clean(o.name || (p ? strip(p.title) : ''), 80) || (id ? 'Project ' + id : 'Stop'),
    lat: Math.round(lat * 1e5) / 1e5, lng: Math.round(lng * 1e5) / 1e5, type, area: p ? areaOf(p) : clean(o.area, 60), ck: p ? p.ck : '',
    visitMin, window, fixed, locked: !!(o.locked || fixed), day: Number.isInteger(o.day) ? o.day : null, pinDay: !!o.pinDay,
    why: Array.isArray(o.why) ? o.why.slice(0, 6) : [], status: ['planned', 'done', 'late', 'skipped', 'moved'].includes(o.status) ? o.status : 'planned',
    checkin: o.checkin && typeof o.checkin === 'object' ? o.checkin : null, note: clean(o.note, 300)
  };
}
export function placeOf(x, fallback) {
  if (!x || typeof x !== 'object') return fallback || null;
  const lat = Number(x.lat), lng = Number(x.lng);
  if (!(isFinite(lat) && isFinite(lng) && Math.abs(lat) <= 90 && Math.abs(lng) <= 180 && !(lat === 0 && lng === 0))) return null;
  return { kind: ['office', 'here', 'place', 'same'].includes(x.kind) ? x.kind : 'place', name: clean(x.name, 80), lat: Math.round(lat * 1e5) / 1e5, lng: Math.round(lng * 1e5) / 1e5 };
}
export function datesOf(from, to) {
  if (!isDay(from) || !isDay(to) || to < from) return null;
  const n = daysBetween(from, to) + 1;
  if (n > LIMITS.days) return null;
  const out = []; for (let i = 0; i < n; i++) out.push(addDays(from, i));
  return out;
}

// ---- building the plan ------------------------------------------------------------------------------------
/**
 * build(env, rec, opts) fills rec.days, legs, matrix, unplanned, warnings, cost, packing (rec has stops, dates, places…).
 * opts: { optimise: true (re-order and re-split) | false (keep the days as they are, just re-time), weather: true, now }
 */
export async function build(env, rec, opts = {}) {
  const now = opts.now || Date.now();
  const st = await settings(env);
  const dates = datesOf(rec.from, rec.to);
  const start = rec.start, end = rec.end && rec.end.kind !== 'same' ? rec.end : null;
  const byS = new Map(rec.stops.map(s => [s.sid, s]));
  const active = rec.stops.filter(s => s.status !== 'skipped');
  // the matrix: start, end, every stop
  const pts = { S: [start.lat, start.lng] }; if (end) pts.E = [end.lat, end.lng]; else pts.E = pts.S;
  active.forEach(s => { pts[s.sid] = [s.lat, s.lng]; });
  const keys = ['S'].concat(end ? ['E'] : [], active.map(s => s.sid));
  let matrix = rec.matrix && sameKeys(rec.matrix.keys, keys) && !opts.freshMatrix ? rec.matrix : await matrixFor(env, keys, pts, now);
  if (!end) matrix = withE(matrix);
  rec.matrix = { keys: matrix.keys, min: matrix.min, km: matrix.km, src: matrix.src, reason: matrix.reason || undefined };
  const legs0 = opts.optimise ? {} : (rec.legs || {});
  // the days: re-optimise, or keep each stop on its day (pinned) in the current order
  const overnights = {};
  (rec.days || []).forEach((d, i) => { if (d.overnight && !d.overnight.suggested) overnights[i] = d.overnight; else if (d.home === true) overnights[i] = null; });
  let stops = active;
  if (!opts.optimise && rec.days && rec.days.length) {
    const pos = new Map(); rec.days.forEach((d, i) => d.stops.forEach((sid, k) => pos.set(sid, [i, k])));
    stops = active.filter(s => pos.has(s.sid)).sort((a, b) => pos.get(a.sid)[0] - pos.get(b.sid)[0] || pos.get(a.sid)[1] - pos.get(b.sid)[1]).map(s => Object.assign({}, s, { day: pos.get(s.sid)[0], pinDay: true }));
  }
  const plan = planTrip({ dates, stops, start: { lat: start.lat, lng: start.lng, name: start.name }, end: end ? { lat: end.lat, lng: end.lng, name: end.name } : null,
    hours: rec.hours, lunch: rec.lunch, matrix, legs: legs0, overnightAfterMin: st.overnightAfterMin, overnights, keepDays: !opts.optimise && !!(rec.days && rec.days.length) });
  if (!opts.optimise && rec.days && rec.days.length) {
    // keep the order the person chose within each day
    plan.days.forEach((d, i) => { const want = (rec.days[i] || {}).stops || []; const set = new Set(d.stops); d.stops = want.filter(x => set.has(x)).concat(d.stops.filter(x => !want.includes(x))); });
  }
  const names = plan.names, P = plan.pts;
  // real legs and weather from the trip engine (routes cached 30 days, forecasts 3 hours)
  let legs = Object.assign({}, legs0);
  if (opts.weather !== false) legs = Object.assign(legs, await realLegs(env, plan, P, names, now));
  const get = makeGet(P, matrix, legs);
  const sopts = { hours: rec.hours, lunch: rec.lunch, get, names };
  rec.days = plan.days.map((d, i) => {
    const sc = schedule({ start: d.start, end: d.end, endKind: d.endKind, stops: d.stops.map(x => byS.get(x)) }, sopts);
    return { date: d.date, name: dayName(d.date), stops: d.stops, startAt: placeFrom(d.start, P, names), endAt: Object.assign({ kind: d.endKind }, placeFrom(d.end, P, names)), overnight: d.overnight || null,
      timeline: sc.timeline, km: sc.km, driveMin: sc.driveMin, endsAt: sc.endsAt, warnings: sc.warnings, riskLevel: 'ok', weather: null };
  });
  // keep previously entered lodging on the same day
  (opts.prevDays || []).forEach((pd, i) => { const d = rec.days[i]; if (d && d.overnight && pd && pd.overnight && pd.overnight.lodging && (!d.overnight.lodging)) d.overnight.lodging = pd.overnight.lodging; });
  rec.unplanned = plan.unplanned.concat(rec.stops.filter(s => s.status !== 'skipped' && !plan.days.some(d => d.stops.includes(s.sid)) && !plan.unplanned.includes(s.sid)).map(s => s.sid));
  rec.stops.forEach(s => { const di = rec.days.findIndex(d => d.stops.includes(s.sid)); s.day = di >= 0 ? di : null; });
  rec.legs = trimLegs(legs, rec.days);
  await dayWarnings(env, rec, byS, sopts, now, opts.weather !== false);
  rec.warnings = tripWarnings(rec);
  return rec;
}
const sameKeys = (a, b) => Array.isArray(a) && a.length === b.length && a.every((k, i) => k === b[i]);
function withE(m) { // the end is the start: E shares S's row and column
  if (m.keys.includes('E')) return m;
  const i = m.keys.indexOf('S'), keys = m.keys.concat('E');
  const min = m.min.map(r => r.concat(r[i])); min.push(min[i].slice()); min[min.length - 1][keys.length - 1] = 0;
  const km = m.km.map(r => r.concat(r[i])); km.push(km[i].slice()); km[km.length - 1][keys.length - 1] = 0;
  return Object.assign({}, m, { keys, min, km });
}
function placeFrom(key, P, names) { const p = P[key] || [null, null]; return { key, name: names[key] || '', lat: p[0], lng: p[1] }; }
function trimLegs(legs, days) {
  const used = new Set(); days.forEach(d => d.timeline.forEach(x => { if (x.kind === 'drive') used.add(x.leg); }));
  const out = {}; for (const [k, v] of Object.entries(legs)) if (used.has(k) || used.has(k.split('>').reverse().join('>'))) out[k] = v;
  return out;
}

// the trip engine for each day's sequence (start → stops → end), in pieces of MAX_STOPS
async function realLegs(env, plan, P, names, now) {
  const out = {};
  for (const d of plan.days) {
    const seq = [d.start].concat(d.stops, [d.end]);
    if (seq.length < 2) continue;
    for (let i = 0; i < seq.length - 1; i += MAX_STOPS) {
      const part = seq.slice(i, i + MAX_STOPS + 1);
      if (part.length < 2) break;
      const from = part[0], to = part.slice(1);
      let r;
      try {
        r = await tripAdvice(env, { from: { lat: P[from][0], lng: P[from][1], name: names[from] || '' }, stops: to.map(k => ({ id: k, lat: P[k][0], lng: P[k][1], name: names[k] || '' })), date: d.date, geometry: true, now });
      } catch (e) { console.error('itin legs:', e && e.message); continue; }
      if (!r || !r.ok) continue;
      r.legs.forEach((L, k) => {
        const a = part[k], b = part[k + 1];
        out[a + '>' + b] = { km: L.km, min: L.estMin, maxMin: L.estMaxMin, src: 'trip', key: L.key, roads: L.roads !== false, roadsReason: L.roadsReason, chips: L.chips, riskLevel: L.riskLevel,
          warnings: L.warnings, surface: L.surface, roadLevel: L.weather && L.weather.available ? L.weather.roadLevel : null, rainMm: L.weather && L.weather.available ? L.weather.rainMm : null,
          date: d.date, geometry: Array.isArray(L.geometry) ? thin(L.geometry, 80) : null, to: names[b] || '' };
      });
    }
  }
  return out;
}
const thin = (line, max) => { if (line.length <= max) return line; const step = (line.length - 1) / (max - 1), out = []; for (let i = 0; i < max; i++) out.push(line[Math.round(i * step)]); return out; };

const RANK = { info: 0, ok: 0, caution: 1, difficult: 2 };
const worse = (a, b) => (RANK[a] >= RANK[b] ? a : b);
async function dayWarnings(env, rec, byS, sopts, now, weather) {
  // legs: their own warnings and road reports; the worst risk and rain per day
  rec.days.forEach(d => {
    const ws = [];
    let risk = 'ok', rain = null, lvl = null;
    d.timeline.filter(x => x.kind === 'drive').forEach(x => {
      const L = rec.legs[x.leg]; if (!L) return;
      risk = worse(risk, L.riskLevel || 'ok');
      if (L.rainMm != null) rain = Math.max(rain || 0, L.rainMm);
      if (L.roadLevel) lvl = lvl == null || LEVEL_RANK[L.roadLevel] > LEVEL_RANK[lvl] ? L.roadLevel : lvl;
      (L.warnings || []).forEach(w => {
        if (/^REPORT_/.test(w.code)) ws.push({ code: 'ROAD_REPORT', level: w.level, en: w.en, sw: w.sw, leg: x.leg, rid: w.rid, kind: w.code.slice(7).toLowerCase() });
        else ws.push({ code: 'LEG_' + w.code, level: w.level, en: w.en, sw: w.sw, leg: x.leg });
      });
    });
    d.warnings.forEach(w => { risk = worse(risk, w.level === 'info' ? 'ok' : w.level); });
    d.warnings = d.warnings.concat(ws);
    d.riskLevel = risk; d.weather = rain == null && !lvl ? null : { rainMm: rain, roadLevel: lvl };
  });
  // one-tap fixes: a stop after closing time → a day where it fits; a long day → its last stop to the next day
  rec.days.forEach((d, i) => d.warnings.forEach(w => {
    if (w.code === 'AFTER_CLOSE' && w.sid) {
      const others = rec.days.map((x, j) => ({ start: x.startAt.key, end: x.endAt.key, stops: x.stops, j })).filter(x => x.j !== i);
      const s = byS.get(w.sid), b = others.length ? bestInsert(others, s, byS, sopts) : null;
      if (b && b.v < 1000) w.fix = { action: 'move', sid: w.sid, toDay: others[b.day].j };
    }
    if (w.code === 'OVER_HOURS' && d.stops.length > 1 && i < rec.days.length - 1) w.fix = { action: 'move', sid: d.stops[d.stops.length - 1], toDay: i + 1 };
  }));
  // rain on murram: would another trip day be drier for this day's stops? ("Swap with Tuesday?")
  if (weather && rec.days.length > 1) {
    try {
      const groups = rec.days.map(d => d.stops.map(s => byS.get(s)).filter(Boolean));
      const projects = groups.flat().map(s => ({ id: s.sid, name: s.name, lat: s.lat, lng: s.lng }));
      if (projects.length) {
        const wk = await weekAdvice(env, { projects, start: rec.from, days: rec.days.length, groups: groups.map(g => g.map(s => s.sid)), now });
        if (wk && wk.ok && wk.groups) rec.days.forEach((d, i) => {
          const g = wk.groups[i]; if (!g || !d.stops.length) return;
          const here = g.days.find(x => x.date === d.date);
          if (!here || !here.available || LEVEL_RANK[here.roadLevel] < 2) return;
          let best = null;
          rec.days.forEach((o, j) => {
            if (j === i) return;
            const there = g.days.find(x => x.date === o.date), back = wk.groups[j] && wk.groups[j].days.find(x => x.date === d.date), now2 = wk.groups[j] && wk.groups[j].days.find(x => x.date === o.date);
            if (!there || !there.available || there.score >= here.score - 0.5) return;
            const gain = (here.score + (now2 && now2.available ? now2.score : 0)) - (there.score + (back && back.available ? back.score : 0));
            if (gain > 0.5 && (!best || gain > best.gain)) best = { j, gain };
          });
          if (!best) return;
          const murramLeg = d.timeline.filter(x => x.kind === 'drive').map(x => rec.legs[x.leg]).find(L => L && (L.warnings || []).some(w => /^MURRAM_/.test(w.code)));
          const to = murramLeg ? murramLeg.to : (byS.get(d.stops[0]) || {}).name || '';
          const heavy = here.roadLevel === 'heavy';
          const what = { en: (heavy ? 'Heavy rain ' : 'Rain ') + dayWord(d.date, 'en') + (murramLeg ? ' on the murram road to ' : ' on the way to ') + to + '. Swap with ' + dayWord(rec.days[best.j].date, 'en') + '?',
            sw: (heavy ? 'Mvua kubwa ' : 'Mvua ') + dayWord(d.date, 'sw') + (murramLeg ? ' kwenye barabara ya murram kwenda ' : ' njiani kwenda ') + to + '. Badilisha na ' + dayWord(rec.days[best.j].date, 'sw') + '?' };
          d.warnings.push({ code: 'SWAP_DAY', level: heavy ? 'difficult' : 'caution', en: what.en, sw: what.sw, fix: { action: 'swap', day: i, with: best.j } });
        });
      }
    } catch (e) { console.error('itin swap:', e && e.message); }
  }
}
function tripWarnings(rec) {
  const out = [];
  if (rec.unplanned && rec.unplanned.length) out.push({ code: 'NO_FIT', level: 'caution', en: rec.unplanned.length + (rec.unplanned.length === 1 ? ' stop doesn’t' : ' stops don’t') + ' fit in these days', sw: rec.unplanned.length === 1 ? 'Kituo 1 hakitoshei katika siku hizi' : 'Vituo ' + rec.unplanned.length + ' havitoshei katika siku hizi', fix: { action: 'extend' }, sids: rec.unplanned });
  return out;
}

// ---- costs ------------------------------------------------------------------------------------------------
export const vehicles = env => getJson(env, 'vehicles', []);
export const fuelLogs = (env, vid) => getJson(env, 'fuellog:' + vid, []);
/** the km of a leg inside a town (start, end, overnight towns, and stops inside a town), for the fuel model */
function townKmOf(a, b, P, km) {
  const inTown = k => { const p = P[k]; if (!p) return false; if (/^T:/.test(k)) return true; const t = nearestTown(p); return !!t && t.km <= TOWN_KM; };
  return (inTown(a) ? 1 : 0) * Math.min(TOWN_KM, km / 2) + (inTown(b) ? 1 : 0) * Math.min(TOWN_KM, km / 2);
}
export async function costOf(env, rec, opts = {}) {
  const now = opts.now || Date.now(), st = await settings(env);
  const v = rec.vehicleId ? (await vehicles(env)).find(x => x.vid === rec.vehicleId) || null : null;
  const logs = v ? await fuelLogs(env, v.vid) : [];
  const use = v ? useFor(v, logs) : null;
  const pax = rec.passengers != null ? rec.passengers : Math.max(0, (rec.people || []).length - 1);
  const load = v ? loadFactor(v, pax, rec.cargoKg) : 1;
  const P = { S: [rec.start.lat, rec.start.lng] }; P.E = rec.end && rec.end.kind !== 'same' ? [rec.end.lat, rec.end.lng] : P.S;
  rec.stops.forEach(s => { P[s.sid] = [s.lat, s.lng]; });
  rec.days.forEach(d => { if (d.endAt && d.endAt.key) P[d.endAt.key] = [d.endAt.lat, d.endAt.lng]; });
  const fuelType = v ? (v.type === 'motorbike' ? 'petrol' : v.fuel) : 'petrol';
  const pr = v ? await priceFor(env, { points: [P.S].concat(rec.stops.map(s => P[s.sid])), fuel: fuelType, ck: rec.ck || undefined, now }) : { price: null, reason: 'no-vehicle' };
  const price = pr.price;
  const n = (rec.people || []).length || 1;
  const missing = ['lunchKes', 'perDiemKes', 'lodgingKes'].filter(k => !st[k]);
  let litresAll = 0;
  const days = rec.days.map((d, i) => {
    let litres = 0, km = 0;
    d.timeline.filter(x => x.kind === 'drive').forEach(x => {
      const L = rec.legs[x.leg] || {}, k = x.km || L.km || 0;
      const surface = L.surface || { pavedKm: k * 0.7, unpavedKm: k * 0.3, unknownKm: 0 };
      const [a, b] = x.leg.split('>');
      if (use && use.lPer100) litres += legLitres({ km: k, surface, roadLevel: L.roadLevel || 'dry', townKm: townKmOf(a, b, P, k) }, use.lPer100, load).litres;
      km += k;
    });
    litresAll += litres;
    const extras = (rec.extras || []).filter(x => x.day === i && !x.actual).reduce((s, x) => s + (Number(x.kes) || 0), 0);
    const working = d.stops.length > 0 || d.timeline.some(x => x.kind === 'drive' && x.min > 0);
    const lunchKes = working && !(rec.lunch && rec.lunch.skip) ? st.lunchKes * n : 0;
    const perDiemKes = st.perDiemKes * n;
    const lodgingKes = d.overnight ? (d.overnight.lodging && Number(d.overnight.lodging.kes) > 0 ? Number(d.overnight.lodging.kes) : st.lodgingKes * n) : 0;
    const fuelKes = price && price.kes != null ? Math.round(litres * price.kes) : null;
    return { date: d.date, km: r1(km), litres: r1(litres), fuelKes, lunchKes, perDiemKes, lodgingKes, extrasKes: extras, totalKes: (fuelKes || 0) + lunchKes + perDiemKes + lodgingKes + extras };
  });
  const sum = k => days.reduce((s, d) => s + (Number(d[k]) || 0), 0);
  const tripExtras = (rec.extras || []).filter(x => x.day == null && !x.actual).reduce((s, x) => s + (Number(x.kes) || 0), 0);
  const fuelKes = price && price.kes != null ? Math.round(litresAll * price.kes) : null;
  const est = { fuelKes, lunchKes: sum('lunchKes'), perDiemKes: sum('perDiemKes'), lodgingKes: sum('lodgingKes'), extrasKes: sum('extrasKes') + tripExtras };
  est.totalKes = (fuelKes || 0) + est.lunchKes + est.perDiemKes + est.lodgingKes + est.extrasKes;
  if (fuelKes == null) est.fuelMissing = true;
  // actual: fuel bought on this trip, receipts, lodging marked paid
  const tripLogs = [];
  for (const vv of await vehicles(env)) for (const l of await fuelLogs(env, vv.vid)) if (l.itinId === rec.id) tripLogs.push(l);
  const act = { fuelKes: Math.round(tripLogs.reduce((s, l) => s + (Number(l.totalKes) || 0), 0)), fuelLitres: r1(tripLogs.reduce((s, l) => s + (Number(l.litres) || 0), 0)), logs: tripLogs.length,
    extrasKes: (rec.extras || []).filter(x => x.actual).reduce((s, x) => s + (Number(x.kes) || 0), 0),
    lodgingKes: rec.days.reduce((s, d) => s + (d.overnight && d.overnight.lodging && d.overnight.lodging.paid ? Number(d.overnight.lodging.kes) || 0 : 0), 0) };
  act.totalKes = act.fuelKes + act.extrasKes + act.lodgingKes;
  const label = priceLabel(price);
  return {
    currency: 'KES', missing,
    fuel: { litres: r1(litresAll), lPer100: use ? use.lPer100 : null, use, basis: basisWords(v, use || {}), price, kes: fuelKes, reason: price ? null : pr.reason, label, vehicle: v ? { vid: v.vid, name: v.name, type: v.type, fuel: fuelType } : null },
    days, est, actual: act
  };
}

// ---- packing list -----------------------------------------------------------------------------------------
const STD = [
  ['std:firstaid', 'First aid kit', 'Sanduku la huduma ya kwanza'],
  ['std:charger', 'Phone charger and power bank', 'Chaja ya simu na power bank'],
  ['std:offline', 'Offline map saved for this trip', 'Ramani ya nje ya mtandao imehifadhiwa kwa safari hii'],
  ['std:water', 'Drinking water', 'Maji ya kunywa'],
  ['std:id', 'ID and RAFIKI badge', 'Kitambulisho na beji ya RAFIKI']
];
/** auto items from the stops (water tests due, parts for open repairs, consent forms for story moments) + standard items; keeps ticks and edits */
export async function packingFor(env, rec, prev, now) {
  const t0 = now || Date.now();
  const ids = [...new Set(rec.stops.filter(s => s.id && s.status !== 'skipped').map(s => s.id))];
  const byId = new Map(rec.stops.map(s => [s.id, s]));
  const tests = await getJson(env, 'pintests', {}), repairs = await getJson(env, 'pinrepairs', {}), stories = await getJson(env, 'pinstories', []);
  const act = await getJson(env, 'pinact', []);
  const due = ids.filter(id => { const s = byId.get(id); if (!s || !(s.type === 'borehole' || s.type === 'collection')) return false; const at = tests[id]; return !at || (t0 - Date.parse(at)) / 864e5 > TEST_DUE_DAYS; });
  const storyIds = ids.filter(id => stories.some(x => String(x.id) === id) || act.some(e => e.a === 'visit' && String(e.id) === id && e.st));
  const items = [];
  if (due.length) items.push({ key: 'auto:strips', auto: true, en: 'Water test strips (' + due.length + (due.length === 1 ? ' site)' : ' sites)'), sw: 'Vipande vya kupima maji (' + (due.length === 1 ? 'eneo 1)' : 'maeneo ' + due.length + ')'), why: due });
  ids.forEach(id => {
    const r = repairs[id]; if (!r) return;
    const parts = [r.parts, r.what].filter(Boolean).join(', ') || (r.problems || []).slice(0, 4).join(', ');
    const name = byId.get(id).name;
    items.push({ key: 'auto:parts:' + id, auto: true, en: parts ? 'Parts for ' + name + ': ' + clean(parts, 160) : 'Tools and parts for the repair at ' + name, sw: parts ? 'Vipuri vya ' + name + ': ' + clean(parts, 160) : 'Vifaa na vipuri vya matengenezo ya ' + name, why: [id] });
  });
  if (storyIds.length) items.push({ key: 'auto:consent', auto: true, en: 'Consent forms (' + storyIds.length + (storyIds.length === 1 ? ' site with story moments)' : ' sites with story moments)'), sw: 'Fomu za idhini (' + (storyIds.length === 1 ? 'eneo 1 lenye hadithi)' : 'maeneo ' + storyIds.length + ' yenye hadithi)'), why: storyIds });
  STD.forEach(([key, en, sw]) => items.push({ key, auto: true, en, sw }));
  const old = new Map((prev || []).map(x => [x.key, x]));
  const out = items.map(x => { const o = old.get(x.key); return Object.assign(x, { done: !!(o && o.done), removed: !!(o && o.removed) }, o && o.edited ? { en: o.en, sw: o.sw, edited: true } : {}); });
  (prev || []).filter(x => !x.auto && /^u:/.test(x.key)).forEach(x => out.push(x));
  return out;
}
export function editPacking(list, b) {
  const out = (list || []).map(x => Object.assign({}, x));
  for (const it of Array.isArray(b.items) ? b.items.slice(0, 100) : []) {
    const x = out.find(y => y.key === it.key); if (!x) continue;
    if (it.done != null) x.done = !!it.done;
    if (it.removed != null) x.removed = !!it.removed;
    if (it.en != null && clean(it.en, 160)) { x.en = clean(it.en, 160); x.sw = clean(it.sw != null ? it.sw : it.en, 160); x.edited = true; }
    if (it.delete && !x.auto) out.splice(out.indexOf(x), 1);
  }
  let n = out.filter(x => /^u:/.test(x.key)).reduce((m, x) => Math.max(m, Number(x.key.slice(2)) || 0), 0);
  for (const a of Array.isArray(b.add) ? b.add.slice(0, 20) : []) { const en = clean(a && a.en, 160); if (en) out.push({ key: 'u:' + (++n), auto: false, en, sw: clean(a.sw || en, 160), done: false }); }
  return out.slice(0, 120);
}

// ---- suggestions ------------------------------------------------------------------------------------------
const plural = (n, one, many) => n + ' ' + (n === 1 ? one : many);
export function whyWords(code, extra) {
  const e = extra || {};
  switch (code) {
    case 'repair': return { en: 'open repair', sw: 'matengenezo yaliyo wazi' };
    case 'urgent': return { en: 'open repair · urgent', sw: 'matengenezo yaliyo wazi · ya dharura' };
    case 'notworking': return { en: 'not working', sw: 'haifanyi kazi' };
    case 'overdue': { const m = Math.max(1, Math.round((e.age || 0) / 30)); return { en: 'due a visit · ' + plural(m, 'month', 'months'), sw: 'ziara inahitajika · ' + (m === 1 ? 'mwezi 1' : 'miezi ' + m) }; }
    case 'never': return { en: 'never visited with the app', sw: 'haijatembelewa kwa programu' };
    case 'test': return { en: 'water test due', sw: 'kipimo cha maji kinahitajika' };
    case 'community': { const d = e.days || 0; return { en: 'community report ' + (d === 0 ? 'today' : d === 1 ? 'yesterday' : d + ' days ago'), sw: 'ripoti ya jamii ' + (d === 0 ? 'leo' : d === 1 ? 'jana' : 'siku ' + d + ' zilizopita') }; }
    case 'assigned': return { en: 'assigned to you', sw: 'umepangiwa wewe' };
    default: return { en: code, sw: code };
  }
}
export async function suggest(env, chk, b, feed, now) {
  const t0 = now || Date.now();
  const ck = ['ke', 'tz', 'ug', 'mw'].includes(b.ck) ? b.ck : chk.home || 'ke';
  const all = ((feed && feed.projects) || []).filter(p => p.ck === ck && hasGps(p));
  const visits = await getJson(env, 'pinlastvisit', {}), repairs = await getJson(env, 'pinrepairs', {}), assigned = await getJson(env, 'pinassign', []), tstamp = await getJson(env, 'pintests', {});
  const tests = new Set(all.filter(p => isWater(p) && visits[p.id] && (!tstamp[p.id] || (t0 - Date.parse(tstamp[p.id])) / 864e5 > TEST_DUE_DAYS)).map(p => String(p.id)));
  const areas = Array.isArray(b.areas) ? b.areas.map(a => clean(a, 80)).filter(Boolean).slice(0, 20) : [];
  const cands = candidates({ projects: all, visits, repairs, assigned: [], tests, now: t0, ck, areas });
  const by = new Map(cands.map(c => [c.id, c]));
  // community reports (SMS/WhatsApp) in the last 14 days
  const sms = (await getJson(env, 'pinsms', [])).filter(e => e.id && (t0 - Date.parse(e.at)) / 864e5 <= 14 && !/^ignored|bad-number|duplicate|rate|daily/.test(e.result || ''));
  const latestSms = {}; sms.forEach(e => { if (!latestSms[e.id] || latestSms[e.id].at < e.at) latestSms[e.id] = e; });
  // visits assigned to this person and not done
  const mine = new Set(assigned.filter(a => !a.done && sameName(a.to, chk.who)).map(a => String(a.id)));
  const pool = new Map(all.map(p => [String(p.id), p]));
  const extra = id => { if (!by.has(id) && pool.has(id)) { const p = pool.get(id); by.set(id, { id, t: p.title, area: p.area, gps: p.gps, why: [], score: 0, age: null }); } return by.get(id); };
  Object.keys(latestSms).forEach(id => { const c = extra(id); if (c) { c.why.push('community'); c.score += 35; c.smsDays = Math.floor((t0 - Date.parse(latestSms[id].at)) / 864e5); } });
  mine.forEach(id => { const c = extra(id); if (c) { c.why.unshift('assigned'); c.score += 60; } });
  const wins = await windowsAll(env), st = await settings(env);
  const inAreas = areas.length ? new Set(areas.map(a => a.toLowerCase())) : null;
  let items = [...by.values()].filter(c => !inAreas || inAreas.has(areaOf(c).toLowerCase()));
  const from = placeOf(b.from);
  items = items.map(c => {
    const p = pool.get(c.id) || {}, type = typeKey(p.type), r = repairs[c.id];
    const why = c.why.filter(w => w !== 'urgent' || true).map(w => Object.assign({ code: w }, whyWords(w === 'repair' && r && r.urgency === 'urgent' ? 'urgent' : w, { age: c.age, days: c.smsDays })));
    const w = why.filter((x, i) => !(x.code === 'urgent' && why.some(y => y.code === 'repair')));
    if (r && r.state === 'notworking' && !w.some(x => x.code === 'notworking')) w.push(Object.assign({ code: 'notworking' }, whyWords('notworking')));
    const o = { id: c.id, name: strip(p.title || c.t), area: areaOf(p), ck, lat: Number(p.gps[0]), lng: Number(p.gps[1]), type, score: Math.round(c.score), why: w,
      windows: wins[c.id] || (st.windows[type] ? { open: st.windows[type].open, close: st.windows[type].close } : null), visitMin: (wins[c.id] && wins[c.id].visitMin) || st.visitMin[type] || 45 };
    if (from) o.km = Math.round(metres([from.lat, from.lng], [o.lat, o.lng]) / 100) / 10;
    return o;
  }).sort((a, b2) => b2.score - a.score || a.id.localeCompare(b2.id));
  const limit = Math.max(1, Math.min(100, Number(b.limit) || 30));
  const areaList = [...new Set(all.map(areaOf).filter(Boolean))].sort();
  return { ok: true, items: items.slice(0, limit), total: items.length, areas: areaList, ck };
}

// ---- progress, check-ins, re-planning ---------------------------------------------------------------------
const tzOf = rec => (rec.ck === 'mw' ? 2 : 3);
const localHm = (iso, rec) => { const d = new Date(Date.parse(iso) + tzOf(rec) * 3600e3); return d.getUTCHours() * 60 + d.getUTCMinutes(); };
const localDay = (t, rec) => new Date(t + tzOf(rec) * 3600e3).toISOString().slice(0, 10);
export async function progressOf(env, rec, now) {
  const t0 = now || Date.now(), today = localDay(t0, rec), nowM = localHm(new Date(t0).toISOString(), rec);
  const di = rec.days.findIndex(d => d.date === today);
  const act = (await getJson(env, 'pinact', [])).filter(e => e.a === 'visit' && e.at >= rec.from && (rec.people || []).some(n => sameName(n, e.by)));
  const stops = [];
  let next = null;
  rec.days.forEach((d, i) => d.stops.forEach(sid => {
    const s = rec.stops.find(x => x.sid === sid); if (!s) return;
    const item = d.timeline.find(x => x.kind === 'stop' && x.sid === sid) || {};
    const visit = s.id ? act.filter(e => String(e.id) === s.id && localDay(Date.parse(e.at), rec) === d.date).sort((a, b) => (a.at < b.at ? -1 : 1))[0] : null;
    const at = s.checkin ? s.checkin.at : visit ? visit.at : null;
    let state = s.status === 'skipped' ? 'skipped' : at ? 'done' : 'planned';
    let lateMin = null;
    if (at && item.start) { lateMin = Math.max(0, localHm(at, rec) - hm(item.start)); if (lateMin > 30) state = 'late'; }
    if (!at && state === 'planned' && (d.date < today || (d.date === today && item.leave && nowM > hm(item.leave)))) state = 'due';
    if (!at && state === 'planned' && !next && d.date >= today) { next = sid; if (d.date === today) state = 'next'; }
    stops.push({ sid, day: i, state, planned: item.start || null, at, lateMin, by: s.checkin ? s.checkin.by : visit ? visit.by : null });
  }));
  // where the trip's people are, if they share their location (Team map opt-in)
  const where = [];
  for (const n of rec.people || []) {
    const p = env.FEED ? await env.FEED.get(hereKey(n), 'json') : null;
    if (p && p.at && t0 - Date.parse(p.at) < 12 * 3600e3) where.push({ name: p.name || n, lat: p.lat, lng: p.lng, at: p.at, acc: p.acc || null });
  }
  return { day: di, now: fmt(nowM), next, stops, where };
}
export async function checkin(env, rec, chk, b, now) {
  const s = rec.stops.find(x => x.sid === b.sid);
  if (!s) return { ok: false, reason: 'stop' };
  const at = b.at && !isNaN(Date.parse(b.at)) && Math.abs(Date.parse(b.at) - (now || Date.now())) < 2 * 864e5 ? new Date(b.at).toISOString() : new Date(now || Date.now()).toISOString();
  const lat = Number(b.lat), lng = Number(b.lng);
  s.checkin = { at, by: chk.who, lat: isFinite(lat) ? Math.round(lat * 1e5) / 1e5 : null, lng: isFinite(lng) ? Math.round(lng * 1e5) / 1e5 : null };
  const d = rec.days[s.day] || {}, item = (d.timeline || []).find(x => x.kind === 'stop' && x.sid === s.sid);
  s.status = item && item.start && localHm(at, rec) - hm(item.start) > 30 ? 'late' : 'done';
  // the real time of the leg that ended here (from leaving the previous place), for the trip engine's learning
  let learned = null;
  if (b.left && !isNaN(Date.parse(b.left)) && d.stops) {
    const k = d.stops.indexOf(s.sid), prevKey = k > 0 ? d.stops[k - 1] : d.startAt && d.startAt.key;
    const prev = k > 0 ? rec.stops.find(x => x.sid === prevKey) : d.startAt;
    const minutes = (Date.parse(at) - Date.parse(b.left)) / 60000;
    const leg = rec.legs[prevKey + '>' + s.sid] || {};
    if (prev && prev.lat != null && minutes >= 1 && minutes <= 600) learned = await recordActual(env, legKey([prev.lat, prev.lng], [s.lat, s.lng]), minutes, { level: leg.roadLevel || 'dry' });
  }
  return { ok: true, learned };
}
/** "Running late": re-time the rest of a day from now; stops that no longer fit go to the next day (push) or are skipped (drop). */
export function replan(rec, b, byS, opts, now) {
  const di = Number.isInteger(b.day) ? b.day : rec.days.findIndex(d => d.date === localDay(now || Date.now(), rec));
  const d = rec.days[di];
  if (!d) return { ok: false, reason: 'day' };
  const nowM = okHm(b.now) ? hm(b.now) : localHm(new Date(now || Date.now()).toISOString(), rec);
  const done = d.stops.filter(sid => { const s = byS.get(sid); return s && (s.checkin || s.status === 'skipped'); });
  const rest = d.stops.filter(sid => !done.includes(sid));
  const from = done.length ? done[done.length - 1] : d.startAt.key;
  const r = replanDay({ stops: rest, end: d.endAt.key, endKind: d.endAt.kind }, rest.map(x => byS.get(x)), opts, nowM, from);
  const mode = ['push', 'drop', 'preview'].includes(b.mode) ? b.mode : 'preview';
  return { ok: true, day: di, keep: done.concat(r.keep), move: r.move, mode, endsAt: r.sched.endsAt };
}
export async function notifyOffice(env, rec, entry, site) {
  const list = await getJson(env, 'itinalerts', []);
  list.unshift(Object.assign({ at: new Date().toISOString(), id: rec.id, title: rec.title }, entry));
  await putJson(env, 'itinalerts', list.slice(0, 200));
  try {
    const link = site ? site + '/pin#office/trips/' + rec.id : '';
    await sendMail(env, 'RAFIKI Pin: ' + rec.title + ' — ' + entry.en, alertHtml(rec.title, [{ label: 'Trip', text: entry.en, icon: 'cal-blue' }], link, site, { tone: 'blue', icon: 'cal-blue', eyebrow: 'Trip update', intro: entry.en }), entry.en + (link ? '\n\n' + link : ''));
  } catch (e) { console.error('itin mail:', e && e.message); }
}

// ---- GPS track and summary --------------------------------------------------------------------------------
export async function addTrack(env, id, points) {
  const list = await getJson(env, 'itintrack:' + id, []);
  const add = (Array.isArray(points) ? points.slice(0, 500) : []).map(p => [Number(p[0]), Number(p[1]), Number(p[2])]).filter(p => isFinite(p[0]) && isFinite(p[1]) && Math.abs(p[0]) <= 90 && Math.abs(p[1]) <= 180 && p[2] > 1.6e12);
  let all = list.concat(add).sort((a, b) => a[2] - b[2]);
  while (all.length > MAX_TRACK) all = all.filter((p, i) => i % 2 === 0 || i === all.length - 1);   // thin, keep the ends
  await putJson(env, 'itintrack:' + id, all, { expirationTtl: 400 * 86400 });
  return { ok: true, points: all.length };
}
export function trackKm(points) {
  let m = 0;
  for (let i = 1; i < (points || []).length; i++) {
    const a = points[i - 1], b = points[i], d = metres(a, b), h = (b[2] - a[2]) / 3600e3;
    if (h > 0 && d / 1000 / h < 150) m += d;          // skip GPS jumps faster than 150 km/h
  }
  return Math.round(m / 100) / 10;
}
export async function summaryOf(env, rec, now) {
  const visited = [], skipped = [];
  const prog = await progressOf(env, rec, now);
  prog.stops.forEach(p => { const s = rec.stops.find(x => x.sid === p.sid); if (!s) return; if (p.state === 'done' || p.state === 'late') visited.push({ sid: s.sid, id: s.id, name: s.name, at: p.at }); else if (p.state === 'skipped') skipped.push({ sid: s.sid, id: s.id, name: s.name }); });
  rec.stops.filter(s => s.status === 'skipped' && !skipped.some(x => x.sid === s.sid)).forEach(s => skipped.push({ sid: s.sid, id: s.id, name: s.name }));
  const planned = r1(rec.days.reduce((s, d) => s + (d.km || 0), 0));
  const track = await getJson(env, 'itintrack:' + rec.id, []);
  const gps = track.length >= 10 ? trackKm(track) : null;
  const ids = new Set(rec.stops.map(s => s.id).filter(Boolean)), end = addDays(rec.to, 1);
  const act = (await getJson(env, 'pinact', [])).filter(e => e.at >= rec.from && e.at < end && ids.has(String(e.id)) && (rec.people || []).some(n => sameName(n, e.by)));
  const problems = act.filter(e => e.a === 'visit' && (e.s === 'notworking' || e.s === 'repair')).map(e => ({ id: String(e.id), name: strip(e.t), state: e.s, by: e.by, at: e.at }));
  const cost = rec.cost || {};
  return { at: new Date(now || Date.now()).toISOString(), visited, skipped, km: { value: gps != null ? gps : planned, src: gps != null ? 'gps' : 'planned', planned },
    cost: { estKes: (cost.est || {}).totalKes || 0, actualKes: (cost.actual || {}).totalKes || 0, fuelLitres: (cost.actual || {}).fuelLitres || 0, fuelKes: (cost.actual || {}).fuelKes || 0, estFuelMissing: !!(cost.est || {}).fuelMissing },
    problems, repairsOpened: new Set(problems.map(p => p.id)).size, waterTests: act.filter(e => e.wq).length };
}
/** The "Trips" line of the monthly e-mail: trips done in [from, to). */
export async function tripsMonth(env, from, to) {
  const f = new Date(from).toISOString().slice(0, 10), t = new Date(to).toISOString().slice(0, 10);
  const idx = (await getJson(env, 'itinidx', [])).filter(r => r.status === 'done' && r.to >= f && r.to < t);
  let sites = 0, km = 0, est = 0, act = 0;
  for (const r of idx) { const rec = await load(env, r.id); const s = rec && rec.summary; if (!s) continue; sites += s.visited.length; km += s.km.value || 0; est += s.cost.estKes || 0; act += s.cost.actualKes || 0; }
  return { trips: idx.length, sites, km: Math.round(km), estKes: est, actualKes: act,
    text: idx.length ? idx.length + (idx.length === 1 ? ' trip, ' : ' trips, ') + sites + ' sites visited, ' + Math.round(km).toLocaleString('en-US') + ' km; ' + kesWords(est) + ' estimated, ' + kesWords(act) + ' spent.' : 'No field trips finished.' };
}

// ---- the shared (read-only) page --------------------------------------------------------------------------
const first = n => String(n || '').trim().split(/\s+/)[0] || '';
export function publicItin(rec) {
  const byS = new Map(rec.stops.map(s => [s.sid, s]));
  return {
    title: rec.title, from: rec.from, to: rec.to, people: (rec.people || []).map(first), status: rec.status,
    days: rec.days.map(d => ({ date: d.date, name: dayName(d.date), endsAt: d.endsAt, km: d.km,
      timeline: d.timeline.map(x => x.kind === 'stop' ? { kind: 'stop', name: (byS.get(x.sid) || {}).name || '', arrive: x.start, leave: x.leave }
        : x.kind === 'drive' ? { kind: 'drive', dep: x.dep, arr: x.arr, min: x.min, km: x.km, chips: (rec.legs[x.leg] || {}).chips || null }
        : x.kind === 'lunch' ? { kind: 'lunch', from: x.from, to: x.to } : x.kind === 'wait' ? { kind: 'wait', from: x.from, to: x.to }
        : { kind: x.kind, at: x.at, place: x.place || x.town || '' }),
      overnight: d.overnight ? { town: d.overnight.town, lodging: d.overnight.lodging && d.overnight.lodging.name ? { name: clean(d.overnight.lodging.name, 80) } : null } : null })),
    stops: rec.stops.filter(s => s.status !== 'skipped').map(s => ({ name: s.name, lat: s.lat, lng: s.lng })),
    lines: Object.values(rec.legs || {}).map(L => L.geometry).filter(Boolean)
  };
}
