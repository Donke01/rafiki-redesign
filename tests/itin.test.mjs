// Field trip itineraries: the planner (lib/itin-plan.js), costs, packing, progress and summary (lib/itin.js), and the
// endpoints /api/itin, /api/vehicles, /api/fuel, /api/trip-share and the /pin-trip page: sign-in, permissions and share links.
// fetch is mocked: no ORS key, and Open-Meteo/ORS answer 503, so legs use the straight-line estimate (as without a key).
// Run: node --test tests/*.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { schedule, planTrip, makeGet, orderStops, replanDay, bestInsert, estimate, hm, fmt } from '../lib/itin-plan.js';
import { costOf, packingFor, editPacking, tripsMonth, trackKm, publicItin, typeKey } from '../lib/itin.js';
import * as itinApi from '../functions/api/itin/[[path]].js';
import * as vehApi from '../functions/api/vehicles.js';
import * as fuelApi from '../functions/api/fuel.js';
import * as shareApi from '../functions/api/trip-share.js';
import * as pinTrip from '../functions/pin-trip.js';
import * as adminApi from '../functions/api/admin.js';

function kv(init = {}) {
  const m = new Map(Object.entries(init).map(([k, v]) => [k, JSON.stringify(v)]));
  return { m, get: async (k, t) => { const v = m.get(k); return v == null ? null : t === 'json' ? JSON.parse(v) : v; }, put: async (k, v) => { m.set(k, String(v)); }, delete: async k => { m.delete(k); },
    list: async ({ prefix }) => ({ keys: [...m.keys()].filter(k => k.startsWith(prefix)).map(name => ({ name })), list_complete: true }) };
}
const mails = [];
globalThis.fetch = async (u, o) => {
  if (String(u).includes('api.resend.com')) { mails.push(JSON.parse(o.body)); return Response.json({ id: 'm1' }); }
  return new Response('down', { status: 503 });
};
console.error = () => {};   // the weather module logs its 503s

// ---- the planner ----------------------------------------------------------------------------------------------
const KITALE = { lat: 1.015, lng: 35.006, name: 'Kitale office' };
const stop = (sid, lat, lng, extra) => Object.assign({ sid, name: 'Site ' + sid, lat, lng, type: 'borehole', visitMin: 60, window: null, fixed: null }, extra || {});
// a fixed travel-time table for exact tests: every pair 30 min, 20 km, unless set
function table(pairs) { return (a, b) => { const k = [a, b].sort().join('|'); return pairs[k] ? { min: pairs[k], km: pairs[k] * 0.8, src: 'test' } : a === b ? { min: 0, km: 0 } : { min: 30, km: 20, src: 'test' }; }; }

test('schedule: drive, wait for opening, visit, lunch, return; times as HH:MM', () => {
  const get = table({});
  const s = schedule({ start: 'S', end: 'S', stops: [stop('a', 0, 0, { type: 'school', window: { open: '08:00', close: '15:30' } }), stop('b', 0, 0), stop('c', 0, 0), stop('d', 0, 0)] }, { hours: { start: '07:00', end: '17:30' }, lunch: { at: '12:30', min: 45 }, get, names: { S: 'Office' } });
  const kinds = s.timeline.map(x => x.kind);
  assert.deepEqual(kinds, ['leave', 'drive', 'wait', 'stop', 'drive', 'stop', 'drive', 'stop', 'drive', 'stop', 'lunch', 'drive', 'return']);
  assert.equal(s.timeline[2].from, '07:30'); assert.equal(s.timeline[2].to, '08:00');          // waits for the school to open
  assert.equal(s.timeline[3].leave, '09:00');
  assert.deepEqual(s.timeline[10], { kind: 'lunch', from: '13:30', to: '14:15' });            // after the stop that spans 12:30
  assert.equal(s.endsAt, '14:45'); assert.equal(s.driveMin, 150); assert.equal(s.over, 0);
  // a long drive just before lunch time: lunch first
  const s2 = schedule({ start: 'S', end: 'S', stops: [stop('a', 0, 0)] }, { hours: { start: '11:45', end: '17:30' }, lunch: { at: '12:30', min: 45 }, get: table({ 'S|a': 120 }) });
  assert.deepEqual(s2.timeline.slice(0, 3).map(x => x.kind), ['leave', 'lunch', 'drive']);
  assert.deepEqual(s.warnings.map(w => [w.code, w.level, w.en]), [['BEFORE_OPEN', 'info', 'Arrives 07:30, 30 min before the school opens']]);
});
test('schedule: "Arrives 15:50, after school closes", a locked meeting, and a long day', () => {
  const get = table({ 'S|a': 500 });
  const s = schedule({ start: 'S', end: 'S', stops: [stop('a', 0, 0, { type: 'school', window: { open: '08:00', close: '15:30' } })] }, { hours: { start: '07:30', end: '17:30' }, lunch: { at: '12:30', min: 45 }, get });
  const w = s.warnings.find(x => x.code === 'AFTER_CLOSE');
  assert.equal(w.en, 'Arrives 15:50, after the school closes'); assert.equal(w.sw, 'Inafika 15:50, baada ya shule kufungwa'); assert.equal(w.level, 'difficult');
  const o = s.warnings.find(x => x.code === 'OVER_HOURS'); assert.match(o.en, /^Day ends \d\d:\d\d, after 17:30$/);
  const m = schedule({ start: 'S', end: 'S', stops: [stop('a', 0, 0), stop('b', 0, 0, { fixed: '09:00', locked: true })] }, { hours: { start: '07:30', end: '17:30' }, get: table({}) });
  const late = m.warnings.find(x => x.code === 'FIXED_LATE');
  assert.equal(late.en, 'Arrives 09:30, 30 min after the meeting at 09:00');
  assert.equal(hm('07:30'), 450); assert.equal(fmt(1430), '23:50');
});
test('order: nearest-neighbour + 2-opt, and a school that closes early goes first', () => {
  const pts = { S: [1.0, 35.0], a: [1.0, 35.1], b: [1.0, 35.2], c: [1.0, 35.3] };
  const get = makeGet(pts, null, null);
  const by = sid => stop(sid, pts[sid][0], pts[sid][1]);
  const o = orderStops([by('c'), by('a'), by('b')], { start: 'S', end: 'S' }, { hours: { start: '07:30', end: '17:30' }, get });
  assert.deepEqual(o.map(s => s.sid), ['a', 'b', 'c']);
  // c is a school closing at 09:30: visiting it last would be too late, so it goes first even though it's furthest
  const school = Object.assign(by('c'), { type: 'school', window: { open: '07:00', close: '09:30' }, visitMin: 30 });
  const o2 = orderStops([school, by('a'), by('b')], { start: 'S', end: 'S' }, { hours: { start: '07:30', end: '17:30' }, get });
  assert.equal(o2[0].sid, 'c');
  assert.equal(schedule({ start: 'S', end: 'S', stops: o2 }, { get }).warnings.filter(w => w.code === 'AFTER_CLOSE').length, 0);
});
test('plan: stops split across days by working hours; pinned stops stay; overnight town near the next day’s first stop', () => {
  const stops = [stop('a', 1.10, 35.10), stop('b', 0.95, 34.95, { type: 'school', window: { open: '08:00', close: '15:30' } }), stop('c', 1.20, 35.00), stop('d', 0.52, 35.27), stop('e', 0.60, 35.40),
    stop('f', 0.45, 35.30, { type: 'health', fixed: '10:00', locked: true, pinDay: true, day: 1, visitMin: 90 })];
  const p = planTrip({ dates: ['2026-10-28', '2026-10-29'], stops, start: KITALE, end: null, hours: { start: '07:30', end: '17:30' } });
  const all = p.days.flatMap(d => d.stops);
  assert.equal(all.length + p.unplanned.length, 6);
  assert.ok(p.days[1].stops.includes('f'));                                 // pinned to day 2
  p.days.forEach(d => assert.equal(d.sched.over, 0, d.date + ' ends ' + d.sched.endsAt));
  // f (Eldoret) is far from Kitale, so day 1 ends at a town near it
  assert.equal(p.days[0].endKind, 'overnight'); assert.equal(p.days[0].overnight.town, 'Eldoret');
  assert.match(p.days[0].overnight.why.en, /^near Thursday’s first stop \(\d+ min\)$/);
  assert.equal(p.days[1].start, 'T:Eldoret'); assert.equal(p.days[1].end, 'E');
  const f = p.days[1].sched.timeline.find(x => x.kind === 'stop' && x.sid === 'f');
  assert.equal(f.start, '10:00');                                           // the locked meeting time is kept
});
test('plan: more than fits → unplanned; a far last day stays near its last stop', () => {
  const many = Array.from({ length: 14 }, (_, i) => stop('s' + i, 1.0 + i * 0.05, 35.0 + (i % 3) * 0.05, { visitMin: 90 }));
  const p = planTrip({ dates: ['2026-10-28'], stops: many, start: KITALE, end: null });
  assert.ok(p.unplanned.length > 0);
  assert.equal(p.days[0].sched.over, 0);
  const far = planTrip({ dates: ['2026-10-28', '2026-10-29', '2026-10-30'], stops: [stop('x', 3.1, 35.6), stop('y', 3.7, 34.85), stop('z', 2.9, 35.5), stop('w', 3.3, 35.3)], start: KITALE, end: null });
  assert.equal(far.days[1].endKind, 'overnight'); assert.match(far.days[1].overnight.why.en, /^near today’s last stop/);
  assert.equal(far.days[2].stops.length, 0); assert.equal(far.days[2].end, 'E');
});
test('re-plan: running late moves what no longer fits; best insert for a new stop', () => {
  const get = table({});
  const ss = ['a', 'b', 'c', 'd'].map(s => stop(s, 0, 0));
  const r = replanDay({ stops: ['a', 'b', 'c', 'd'], end: 'S' }, ss, { hours: { start: '07:30', end: '17:30' }, lunch: { at: '12:30', min: 45 }, get }, hm('13:30'), 'S');
  assert.deepEqual(r.keep, ['a', 'b']); assert.deepEqual(r.move, ['c', 'd']);
  const by = new Map(ss.map(s => [s.sid, s]));
  const b = bestInsert([{ start: 'S', end: 'S', stops: ['a', 'b', 'c', 'd', 'b'].slice(0, 4) }, { start: 'S', end: 'S', stops: [] }], stop('n', 0, 0), by, { hours: { start: '07:30', end: '12:00' }, get });
  assert.equal(b.day, 1);                                                   // the first day is already full
  assert.equal(estimate([1, 35], [1, 35]).min, 0);
});

// ---- the endpoints ------------------------------------------------------------------------------------------------
const P = [
  { id: '104', title: '**Bishop Luge School', ck: 'ke', area: 'Kitale', type: 'School', gps: [0.95, 34.95] },
  { id: '105', title: 'Moiben borehole', ck: 'ke', area: 'Moiben', type: 'Borehole well', gps: [0.81, 35.38] },
  { id: '106', title: 'Kiminini well', ck: 'ke', area: 'Kiminini', type: 'Borehole well', gps: [0.89, 34.92] },
  { id: '107', title: 'Saboti clinic', ck: 'ke', area: 'Saboti', type: 'Health', gps: [1.03, 34.85] },
  { id: '108', title: 'No GPS project', ck: 'ke', area: 'Kitale', type: 'Borehole well', gps: [null, null] }
];
const NOW = Date.now(), DAY = 864e5, iso = t => new Date(t).toISOString();
const today = new Date(NOW + 3 * 3600e3).toISOString().slice(0, 10), plus = n => new Date(Date.parse(today) + n * DAY).toISOString().slice(0, 10);
function makeEnv(extra) {
  const store = kv({
    'feed:v9': { at: Date.now(), feed: { projects: P } },
    'fuelprice:ke': { ck: 'ke', source: 'EPRA', sourceName: 'Energy and Petroleum Regulatory Authority', url: 'https://www.epra.go.ke/pump-prices', from: plus(-11), to: plus(19), currency: 'KES', fetchedAt: iso(NOW), national: { town: 'Nairobi', petrol: 226.96, diesel: 227.76 },
      towns: { Kitale: { petrol: 226.76, diesel: 227.96 }, Nairobi: { petrol: 226.96, diesel: 227.76 }, Eldoret: { petrol: 226.51, diesel: 227.71 } } },
    itinsettings: { lunchKes: 500, perDiemKes: 1000, lodgingKes: 3000 },
    pinrepairs: { 105: { state: 'notworking', urgency: 'urgent', parts: 'pump handle, chain', t: 'Moiben borehole', at: iso(NOW - 3 * DAY) } },
    pinlastvisit: { 104: { at: iso(NOW - 250 * DAY) }, 105: { at: iso(NOW - 20 * DAY) }, 106: { at: iso(NOW - 400 * DAY) } },
    pintests: { 106: iso(NOW - 300 * DAY) },
    pinsms: [{ at: iso(NOW - 2 * DAY), id: '107', result: 'repair' }],
    pinassign: [{ aid: 'x', id: '106', to: 'Meril', done: null }],
    pinstories: [{ id: '104', quote: 'We have water' }]
  });
  return Object.assign({ FEED: store, MONDAY_API_TOKEN: 't', PIN_USERS: 'Jessica:2222, Meril:3333, Grace:4444', PIN_ADMINS: 'Jessica', PIN_CODE: '9999', CRON_KEY: 'cron-key-0123456789' }, extra || {});
}
function req(path, pin, body, headers) {
  const h = Object.assign({ 'CF-Connecting-IP': '10.0.0.' + (pin || '0').slice(0, 2) }, pin ? { 'X-Pin': pin } : {}, body ? { 'Content-Type': 'application/json', Origin: 'https://pin.example' } : {}, headers || {});
  return new Request('https://pin.example' + path, { method: body ? 'POST' : 'GET', headers: h, body: body ? JSON.stringify(body) : undefined });
}
async function call(mod, env, path, pin, body, headers) {
  const url = new URL('https://pin.example' + path), segs = url.pathname.split('/').filter(Boolean).slice(2);
  const fn = body ? mod.onRequestPost : mod.onRequestGet;
  const r = await fn({ request: req(path, pin, body, headers), env, params: { path: segs }, waitUntil: () => {} });
  const ct = r.headers.get('Content-Type') || '';
  const out = ct.includes('json') && r.status !== 304 ? await r.json() : { text: r.status === 304 ? '' : await r.text() };
  return Object.assign(out, { status: r.status, headers: r.headers });
}
const START = { kind: 'office', name: 'Kitale office', lat: 1.015, lng: 35.006 };
async function newTrip(env, pin, extra) {
  return call(itinApi, env, '/api/itin', pin, Object.assign({ title: 'Moiben repairs', from: plus(2), to: plus(3), start: START, stops: ['104', '105', '106'] }, extra || {}));
}

test('itin: create plans the trip (timeline, legs, matrix, packing) for a field officer, who is always on it', async () => {
  const env = makeEnv();
  const r = await newTrip(env, '3333', { people: ['Grace'] });
  assert.equal(r.status, 201, JSON.stringify(r));
  const t = r.itin;
  assert.match(t.id, /^it_/); assert.equal(t.v, 1); assert.equal(t.owner, 'Meril'); assert.deepEqual(t.people, ['Meril', 'Grace']);
  assert.equal(t.stops.length, 3); assert.equal(t.stops[0].name, 'Bishop Luge School');        // monday.com's ** tidied
  assert.equal(t.stops.find(s => s.id === '104').window.open, '08:00');                         // schools: 8:00–15:30
  assert.equal(t.days.length, 2); assert.equal(t.matrix.src, 'estimate');
  const tl = t.days[0].timeline; assert.equal(tl[0].kind, 'leave'); assert.equal(tl[0].at, '07:30'); assert.equal(tl[tl.length - 1].kind, 'return');
  assert.ok(Object.keys(t.legs).length >= 2);
  assert.deepEqual(r.can, { edit: true, admin: false });
  // packing from the stops: strips for the overdue water test, parts for the repair, consent forms for the story
  const keys = t.packing.map(p => p.key);
  assert.ok(keys.includes('auto:strips') && keys.includes('auto:parts:105') && keys.includes('auto:consent') && keys.includes('std:offline'));
  assert.equal(t.packing.find(p => p.key === 'auto:parts:105').en, 'Parts for Moiben borehole: pump handle, chain');
  // no vehicle yet
  assert.equal(t.cost.fuel.reason, 'no-vehicle'); assert.ok(t.warnings.some(w => w.code === 'NO_VEHICLE'));
  // refusals
  assert.equal((await newTrip(env, '3333', { stops: ['108'] })).reason, 'stops');                // no location
  assert.equal((await newTrip(env, '3333', { from: plus(2), to: plus(30) })).reason, 'dates');
  assert.equal((await newTrip(env, '3333', { start: { lat: 'x' } })).reason, 'start');
  assert.equal((await newTrip(env, '3333', { stops: Array.from({ length: 41 }, () => '104') })).reason, 'too-many');
});
test('itin: suggest ranks stops and explains why', async () => {
  const env = makeEnv();
  const r = await call(itinApi, env, '/api/itin/suggest', '3333', { ck: 'ke', from: { lat: 1.015, lng: 35.006 } });
  assert.equal(r.ok, true);
  const by = id => r.items.find(x => x.id === id);
  assert.deepEqual(by('105').why.map(w => w.en), ['open repair · urgent', 'water test due', 'not working']);
  assert.ok(by('106').why.some(w => w.en === 'assigned to you'));
  assert.ok(by('106').why.some(w => w.en === 'water test due'));
  assert.ok(by('104').why.some(w => /^due a visit · \d+ months$/.test(w.en) && /^ziara inahitajika · miezi \d+$/.test(w.sw)));
  const c107 = by('107').why.find(w => w.code === 'community');
  assert.equal(c107.en, 'community report 2 days ago'); assert.equal(c107.sw, 'ripoti ya jamii siku 2 zilizopita');
  assert.equal(r.items[0].id, '105');
  assert.ok(r.areas.includes('Moiben'));
  const a = await call(itinApi, env, '/api/itin/suggest', '3333', { ck: 'ke', areas: ['Moiben'] });
  assert.deepEqual(a.items.map(x => x.id), ['105']);
});
test('itin: costs — fuel from the vehicle and the nearest town, lunch, per diem, lodging; estimate vs actual', async () => {
  const env = makeEnv();
  const v = await call(vehApi, env, '/api/vehicles', '2222', { action: 'add', name: 'Hilux', plate: 'kda 123x', type: 'pickup', cc: 2400, fuel: 'diesel' });
  assert.equal(v.status, 201); assert.equal(v.vehicle.plate, 'KDA 123X'); assert.equal(v.vehicle.use.lPer100, 8.5);
  const r = await newTrip(env, '2222', { vehicleId: v.vehicle.vid, people: ['Meril'] });
  const c = r.itin.cost;
  assert.equal(c.fuel.price.town, 'Kitale'); assert.equal(c.fuel.price.kes, 227.96);
  assert.match(c.fuel.label.en, /^Fuel: KES\u00a0227\.96\/L diesel, Kitale, EPRA \d+ \w{3}–\d+ \w{3}$/);
  assert.ok(c.fuel.litres > 0);
  assert.ok(Math.abs(c.est.fuelKes - c.fuel.litres * 227.96) < 0.1 * 227.96, c.est.fuelKes + ' vs ' + c.fuel.litres);
  const sumDays = k => c.days.reduce((s, d) => s + d[k], 0);
  assert.equal(c.est.lunchKes, sumDays('lunchKes'));
  assert.deepEqual(r.itin.people, ['Meril']);                                                    // an admin plans for others
  assert.equal(c.est.perDiemKes, 1 * 2 * 1000);                                                   // 1 person × 2 days
  assert.equal(c.est.totalKes, c.est.fuelKes + c.est.lunchKes + c.est.perDiemKes + c.est.lodgingKes + c.est.extrasKes);
  assert.equal(c.days.reduce((s, d) => s + d.totalKes, 0), c.est.totalKes);
  // extras and a fuel log make the actual
  let t = r.itin;
  const x = await call(itinApi, env, '/api/itin/' + t.id, '2222', { action: 'extras', v: t.v, extras: [{ day: 0, kind: 'parking', label: 'Parking Eldoret', kes: 200 }, { day: 0, kind: 'toll', label: 'Toll', kes: 150, actual: true }] });
  assert.equal(x.itin.cost.est.extrasKes, 200); assert.equal(x.itin.cost.actual.extrasKes, 150);
  const log = await call(fuelApi, env, '/api/fuel', '3333', { action: 'log', vid: v.vehicle.vid, itinId: t.id, litres: 30, kesPerL: 227.96, odometer: 1000 });
  assert.equal(log.status, 201); assert.equal(log.log.totalKes, Math.round(30 * 227.96));
  const ref = await call(itinApi, env, '/api/itin/' + t.id, '2222', { action: 'refresh', v: x.itin.v });
  assert.equal(ref.itin.cost.actual.fuelKes, Math.round(30 * 227.96)); assert.equal(ref.itin.cost.actual.logs, 1);
  assert.equal(ref.itin.cost.actual.totalKes, Math.round(30 * 227.96) + 150);
  // no price anywhere → "add a fuel price", fuel left out of the estimate
  env.FEED.m.delete('fuelprice:ke');
  const np = await call(itinApi, env, '/api/itin/' + t.id, '2222', { action: 'refresh', v: ref.itin.v });
  assert.equal(np.itin.cost.fuel.kes, null); assert.equal(np.itin.cost.fuel.reason, 'no-price'); assert.equal(np.itin.cost.est.fuelMissing, true);
  assert.deepEqual(np.itin.cost.fuel.label, { en: 'Fuel: add a fuel price', sw: 'Mafuta: weka bei ya mafuta' });
  assert.ok(np.itin.warnings.some(w => w.code === 'NO_FUEL_PRICE'));
  // fuel log refusals
  assert.equal((await call(fuelApi, env, '/api/fuel', '3333', { action: 'log', vid: v.vehicle.vid, litres: 0.1, kesPerL: 200 })).reason, 'bad-litres');
  assert.equal((await call(fuelApi, env, '/api/fuel', '3333', { action: 'log', vid: v.vehicle.vid, litres: 20, kesPerL: 5 })).reason, 'bad-price');
  assert.equal((await call(fuelApi, env, '/api/fuel', '3333', { action: 'log', vid: v.vehicle.vid, litres: 20, kesPerL: 200, odometer: 900 })).reason, 'bad-odometer');
});
test('itin: editing — reorder, move between days, lock a time, swap days, packing ticks, optimistic version', async () => {
  const env = makeEnv();
  let t = (await newTrip(env, '3333')).itin;
  const all = t.days.flatMap(d => d.stops);
  // everything on day 2, in a chosen order; s3 locked at 11:00
  let r = await call(itinApi, env, '/api/itin/' + t.id, '3333', { action: 'stops', v: t.v, days: [{ stops: [] }, { stops: all.slice().reverse() }], stops: [{ sid: all[0], fixed: '11:00' }] });
  assert.equal(r.status, 200, JSON.stringify(r));
  assert.deepEqual(r.itin.days[1].stops, all.slice().reverse());
  const locked = r.itin.days[1].timeline.find(x => x.kind === 'stop' && x.sid === all[0]);
  assert.ok(hm(locked.start) >= hm('11:00'));
  assert.equal(r.itin.stops.find(s => s.sid === all[0]).locked, true);
  // an old version is refused with the current one
  const old = await call(itinApi, env, '/api/itin/' + t.id, '3333', { action: 'stops', v: t.v, days: [{ stops: all }, { stops: [] }] });
  assert.equal(old.status, 409); assert.equal(old.reason, 'conflict'); assert.equal(old.current.v, r.itin.v);
  // swap the days
  r = await call(itinApi, env, '/api/itin/' + t.id, '3333', { action: 'swap-days', v: r.itin.v, day: 0, with: 1 });
  assert.deepEqual(r.itin.days[0].stops, all.slice().reverse()); assert.deepEqual(r.itin.days[1].stops, []);
  // removing a stop (left out of every day)
  r = await call(itinApi, env, '/api/itin/' + t.id, '3333', { action: 'stops', v: r.itin.v, days: [{ stops: all.slice(0, 2) }, { stops: [] }] });
  assert.equal(r.itin.stops.length, 2);
  // add a stop: it goes where it costs least
  r = await call(itinApi, env, '/api/itin/' + t.id, '3333', { action: 'add-stop', v: r.itin.v, stop: { id: '107' } });
  assert.equal(r.itin.stops.length, 3); assert.ok(r.itin.days.some(d => d.stops.includes('s4')));
  // packing: tick, edit, add, remove
  r = await call(itinApi, env, '/api/itin/' + t.id, '3333', { action: 'packing', v: r.itin.v, items: [{ key: 'std:firstaid', done: true }, { key: 'std:water', removed: true }], add: [{ en: 'Spare tyre checked' }] });
  assert.equal(r.itin.packing.find(p => p.key === 'std:firstaid').done, true);
  assert.equal(r.itin.packing.find(p => p.key === 'u:1').en, 'Spare tyre checked');
  // auto items keep their ticks when the plan is rebuilt
  r = await call(itinApi, env, '/api/itin/' + t.id, '3333', { action: 'refresh', v: r.itin.v });
  assert.equal(r.itin.packing.find(p => p.key === 'std:firstaid').done, true); assert.equal(r.itin.packing.find(p => p.key === 'std:water').removed, true);
  assert.equal(r.itin.packing.find(p => p.key === 'u:1').en, 'Spare tyre checked');
  // lodging for a night
  r = await call(itinApi, env, '/api/itin/' + t.id, '3333', { action: 'lodging', v: r.itin.v, day: 0, overnight: { town: 'Eldoret', lat: 0.514, lng: 35.27, lodging: { name: 'Boma Inn', kes: 6000, paid: true } } });
  assert.equal(r.itin.days[0].overnight.lodging.name, 'Boma Inn'); assert.equal(r.itin.cost.days[0].lodgingKes, 6000); assert.equal(r.itin.cost.actual.lodgingKes, 6000);
  assert.equal(r.itin.days[1].startAt.name, 'Eldoret');
});
test('itin: check-in, progress for the office, running late (push / drop) with a notice, GPS track and summary', async () => {
  const env = makeEnv({ RESEND_API_KEY: 'k', ALERT_FROM: 'pin@example.org', ALERT_EMAILS: 'office@example.org' });
  const t0 = (await newTrip(env, '3333', { from: today, to: plus(1) })).itin;
  const first = t0.days[0].stops[0];
  let r = await call(itinApi, env, '/api/itin/' + t0.id, '3333', { action: 'checkin', v: t0.v, sid: first, at: iso(NOW), lat: 0.95, lng: 34.95, left: iso(NOW - 40 * 60000) });
  assert.equal(r.status, 200);
  assert.ok(['done', 'late'].includes(r.itin.stops.find(s => s.sid === first).status)); assert.equal(r.itin.status, 'active');
  assert.ok([...env.FEED.m.keys()].some(k => k.startsWith('triplearn:')), 'the real leg time was recorded');
  const g = await call(itinApi, env, '/api/itin/' + t0.id + '?progress=1', '2222');
  assert.equal(g.progress.day, 0);
  assert.ok(['done', 'late'].includes(g.progress.stops.find(s => s.sid === first).state));
  // running late: preview, then push the rest to tomorrow
  let p = await call(itinApi, env, '/api/itin/' + t0.id, '3333', { action: 'replan', day: 0, now: '16:40', mode: 'preview' });
  assert.equal(p.preview, true); assert.ok(p.move.length >= 1);
  const v0 = r.itin.v;
  p = await call(itinApi, env, '/api/itin/' + t0.id, '3333', { action: 'replan', v: v0, day: 0, now: '16:40', mode: 'push' });
  assert.ok(p.moved.length >= 1); assert.ok(p.itin.days[1].stops.includes(p.moved[0]));
  const alerts = await call(itinApi, env, '/api/itin?alerts=1', '2222');
  assert.match(alerts.alerts[0].en, /^Meril is running late: \d+ stops? moved to tomorrow$/);
  assert.ok(mails.some(m => /running late/.test(m.subject)));
  assert.equal((await call(itinApi, env, '/api/itin?alerts=1', '3333')).reason, 'not-admin');
  // GPS track → km driven in the summary
  const pts = Array.from({ length: 30 }, (_, i) => [1.0 + i * 0.01, 35.0, NOW - (30 - i) * 60000]);
  assert.equal((await call(itinApi, env, '/api/itin/' + t0.id, '3333', { action: 'track', points: pts })).points, 30);
  assert.ok(Math.abs(trackKm(pts) - 32.2) < 0.5);
  // a reported visit by a trip member counts as visited; finishing the trip writes the summary
  env.FEED.m.set('pinact', JSON.stringify([{ at: iso(NOW), by: 'Meril', a: 'visit', s: 'repair', id: '105', t: 'Moiben borehole', wq: 1 }]));
  const cur = await call(itinApi, env, '/api/itin/' + t0.id, '3333');
  const d = await call(itinApi, env, '/api/itin/' + t0.id, '3333', { action: 'update', v: cur.itin.v, status: 'done' });
  const s = d.itin.summary;
  assert.equal(s.km.src, 'gps'); assert.ok(s.visited.length >= 1);
  assert.deepEqual(s.problems.map(x => [x.id, x.state]), [['105', 'repair']]); assert.equal(s.repairsOpened, 1); assert.equal(s.waterTests, 1);
  // the monthly "Trips" line
  const m = await tripsMonth(env, Date.parse(today) - 40 * DAY, Date.parse(today) + 40 * DAY);
  assert.equal(m.trips, 1); assert.match(m.text, /^1 trip, \d+ sites? visited, [\d,]+ km; KES\u00a0[\d,]+ estimated, KES\u00a0[\d,]+ spent\.$/);
  // and the project history
  const h = await call(itinApi, env, '/api/itin?project=105', '2222');
  assert.deepEqual(h.items.map(x => x.id), [t0.id]);
});
test('itin: permissions — own trips, trips you are on, viewers read-only, admins everything, shared PIN, blocked, signed out', async () => {
  const env = makeEnv();
  const mine = (await newTrip(env, '3333')).itin;                // Meril's
  const other = (await newTrip(env, '4444')).itin;               // Grace's
  // Meril sees her own, not Grace's
  let l = await call(itinApi, env, '/api/itin', '3333');
  assert.deepEqual(l.items.map(x => x.id), [mine.id]);
  assert.equal((await call(itinApi, env, '/api/itin/' + other.id, '3333')).status, 403);
  assert.equal((await call(itinApi, env, '/api/itin/' + other.id, '3333', { action: 'refresh' })).status, 403);
  // the admin sees both and can edit
  l = await call(itinApi, env, '/api/itin', '2222'); assert.equal(l.items.length, 2); assert.equal(l.admin, true);
  assert.equal((await call(itinApi, env, '/api/itin/' + other.id, '2222', { action: 'refresh' })).status, 200);
  // Grace shares hers with Meril as a viewer: read-only
  let s = await call(itinApi, env, '/api/itin/' + other.id, '4444', { action: 'share-team', names: ['meril'] });
  assert.deepEqual(s.itin.viewers, ['Meril']);
  const seen = await call(itinApi, env, '/api/itin/' + other.id, '3333');
  assert.equal(seen.status, 200); assert.deepEqual(seen.can, { edit: false, admin: false });
  assert.equal((await call(itinApi, env, '/api/itin/' + other.id, '3333', { action: 'refresh' })).reason, 'read-only');
  // people on a trip can edit it
  s = await call(itinApi, env, '/api/itin/' + other.id, '4444', { action: 'update', v: s.itin.v, people: ['Grace', 'Meril'] });
  assert.equal((await call(itinApi, env, '/api/itin/' + other.id, '3333', { action: 'refresh', v: s.itin.v })).status, 200);
  // only the owner or an admin deletes
  assert.equal((await call(itinApi, env, '/api/itin/' + other.id, '3333', { action: 'delete' })).status, 403);
  // the shared team PIN can't plan trips; settings and windows are admin-only
  assert.equal((await newTrip(env, '9999')).reason, 'named-only');
  assert.equal((await call(itinApi, env, '/api/itin/settings', '3333', { lunchKes: 1 })).reason, 'not-admin');
  assert.equal((await call(itinApi, env, '/api/itin/settings', '2222', { lunchKes: 600, hours: { start: '08:00', end: '17:00' } })).settings.lunchKes, 600);
  assert.equal((await call(itinApi, env, '/api/itin/windows', '2222', { id: '107', window: { open: '09:00', close: '16:00' } })).windows['107'].open, '09:00');
  // blocked and signed out (the same checks as /api/pin)
  env.FEED.m.set('pinguard', JSON.stringify({ meril: { blocked: true } }));
  assert.equal((await call(itinApi, env, '/api/itin', '3333')).reason, 'blocked');
  env.FEED.m.set('pinguard', JSON.stringify({ meril: { gen: 2 } }));
  assert.equal((await call(itinApi, env, '/api/itin', '3333', null, { 'X-Pin-Gen': '1' })).reason, 'signed-out');
  assert.equal((await call(itinApi, env, '/api/itin', '3333', null, { 'X-Pin-Gen': '2' })).status, 200);
  assert.equal((await call(itinApi, env, '/api/itin', '')).status, 401);
  // vehicles: field officers add their own motorbike only
  assert.equal((await call(vehApi, env, '/api/vehicles', '4444', { action: 'add', name: 'Car', type: 'saloon', cc: 1500, fuel: 'petrol' })).reason, 'not-admin');
  const bike = await call(vehApi, env, '/api/vehicles', '4444', { action: 'add', name: 'My bike', type: 'motorbike', cc: 150 });
  assert.equal(bike.status, 201); assert.equal(bike.vehicle.owner, 'Grace'); assert.equal(bike.vehicle.fuel, 'petrol');
  assert.equal((await call(vehApi, env, '/api/vehicles', '3333', { action: 'remove', vid: bike.vehicle.vid })).reason, 'forbidden');
  assert.equal((await call(vehApi, env, '/api/vehicles', '2222', { action: 'add', name: 'X', type: 'pickup', cc: 20, fuel: 'diesel' })).reason, 'bad-cc');
  // fuel prices: admin-only changes
  assert.equal((await call(fuelApi, env, '/api/fuel', '3333', { action: 'override', ck: 'ke', town: 'Kitale', petrol: 230 })).reason, 'not-admin');
  assert.equal((await call(fuelApi, env, '/api/fuel', '2222', { action: 'override', ck: 'ke', town: 'Kitale', petrol: 230 })).ok, true);
  const pr = await call(fuelApi, env, '/api/fuel?price=1&lat=1.02&lng=35.0&fuel=petrol', '3333');
  assert.equal(pr.price.kes, 230); assert.equal(pr.price.kind, 'override');
  // the scheduled fetch needs the cron key
  assert.equal((await fuelApi.onRequestPost({ request: new Request('https://pin.example/api/fuel?cron=prices', { method: 'POST', headers: { 'X-Cron-Key': 'wrong-key-0123456789' } }), env })).status, 401);
});
test('share links: a private read-only page, live updates by ETag, rotate and turn off; nothing private on it', async () => {
  const env = makeEnv();
  const t = (await newTrip(env, '3333')).itin;
  await call(itinApi, env, '/api/itin/' + t.id, '3333', { action: 'extras', v: t.v, extras: [{ day: 0, kind: 'other', label: 'Secret envelope', kes: 99999 }] });
  let s = await call(itinApi, env, '/api/itin/' + t.id, '3333', { action: 'share' });
  assert.match(s.token, /^[A-Za-z0-9_-]{43}$/); assert.equal(s.url, 'https://pin.example/pin-trip?t=' + s.token);
  assert.equal((await call(itinApi, env, '/api/itin/' + t.id, '3333', { action: 'share' })).token, s.token);   // the same link again
  // the stored record only keeps a hash index; the token is not in the list answer
  assert.ok([...env.FEED.m.keys()].some(k => /^itinshare:[0-9a-f]{64}$/.test(k)));
  const g = await call(itinApi, env, '/api/itin/' + t.id, '3333'); assert.equal(g.itin.share.t, undefined); assert.equal(g.itin.share.url, s.url);
  let r = await call(shareApi, env, '/api/trip-share?t=' + s.token);
  assert.equal(r.status, 200); assert.equal(r.kind, 'itin'); assert.equal(r.trip.title, 'Moiben repairs');
  const body = JSON.stringify(r.trip);
  for (const bad of ['3333', 'Secret envelope', '99999', 'kesPerL', 'cost', 'pin']) assert.ok(!body.includes(bad), 'leaks ' + bad);
  assert.deepEqual(r.trip.people, ['Meril']);
  const tag = r.headers.get('ETag');
  assert.equal((await call(shareApi, env, '/api/trip-share?t=' + s.token, null, null, { 'If-None-Match': tag })).status, 304);
  // the page
  const page = await call(pinTrip, env, '/pin-trip?t=' + s.token);
  assert.equal(page.status, 200); assert.match(page.text, /<h1>Moiben repairs<\/h1>/); assert.match(page.text, /rel="manifest"/);
  assert.ok(!page.text.includes('99999')); assert.equal(page.headers.get('X-Robots-Tag'), 'noindex, nofollow');
  assert.match((await call(pinTrip, env, '/pin-trip?t=' + s.token + '&lang=sw')).text, /Siku 1/);
  const man = await call(pinTrip, env, '/pin-trip?t=' + s.token + '&manifest=1');
  assert.equal(man.start_url, '/pin-trip?t=' + s.token); assert.equal(man.display, 'standalone');
  // a change makes a new ETag
  const cur = await call(itinApi, env, '/api/itin/' + t.id, '3333');
  await call(itinApi, env, '/api/itin/' + t.id, '3333', { action: 'update', v: cur.itin.v, title: 'Moiben repairs (new)' });
  assert.equal((await call(shareApi, env, '/api/trip-share?t=' + s.token, null, null, { 'If-None-Match': tag })).status, 200);
  // rotate: the old link stops working
  const s2 = await call(itinApi, env, '/api/itin/' + t.id, '3333', { action: 'share', rotate: true });
  assert.notEqual(s2.token, s.token);
  assert.equal((await call(shareApi, env, '/api/trip-share?t=' + s.token)).status, 404);
  assert.equal((await call(shareApi, env, '/api/trip-share?t=' + s2.token)).status, 200);
  // turn it off
  const c2 = await call(itinApi, env, '/api/itin/' + t.id, '3333');
  await call(itinApi, env, '/api/itin/' + t.id, '3333', { action: 'unshare', v: c2.itin.v });
  assert.equal((await call(shareApi, env, '/api/trip-share?t=' + s2.token)).reason, 'not-found');
  assert.equal((await call(pinTrip, env, '/pin-trip?t=' + s2.token)).status, 404);
  assert.equal((await call(shareApi, env, '/api/trip-share?t=short')).status, 404);
  // the chat hook's card
  const card = await call(itinApi, env, '/api/itin/' + t.id + '?card=1', '3333');
  assert.deepEqual(Object.keys(card.card), ['id', 'title', 'from', 'to', 'stops', 'people', 'url']);
});
test('monthly report: a "Trips" line when trips were finished', async () => {
  const env = makeEnv({ RESEND_API_KEY: 'k', ALERT_FROM: 'pin@example.org', ALERT_EMAILS: 'office@example.org' });
  const lastMonth = new Date(Date.UTC(new Date().getUTCFullYear(), new Date().getUTCMonth() - 1, 10)).toISOString().slice(0, 10);
  env.FEED.m.set('itinidx', JSON.stringify([{ id: 'it_abcdefgh', title: 'T', from: lastMonth, to: lastMonth, status: 'done', people: ['Meril'], projects: [] }]));
  env.FEED.m.set('itin:it_abcdefgh', JSON.stringify({ id: 'it_abcdefgh', summary: { visited: [{}, {}], km: { value: 120 }, cost: { estKes: 9000, actualKes: 8500 } } }));
  mails.length = 0;
  const r = await adminApi.onRequestPost({ request: new Request('https://pin.example/api/admin?cron=monthly', { method: 'POST', headers: { 'X-Cron-Key': 'cron-key-0123456789' } }), env });
  assert.equal(r.status, 200);
  const m = mails.find(x => /summary/.test(x.subject));
  assert.match(m.text, /1 trip, 2 sites visited, 120 km; KES\u00a09,000 estimated, KES\u00a08,500 spent\./);
});
test('helpers: project types, public view has no costs', () => {
  assert.equal(typeKey('Borehole well'), 'borehole'); assert.equal(typeKey('School'), 'school'); assert.equal(typeKey('Health'), 'health'); assert.equal(typeKey('Water (Drilling)'), 'borehole');
  const v = publicItin({ title: 'T', from: '2026-10-28', to: '2026-10-28', people: ['Meril Wanjala'], stops: [], days: [], legs: {}, cost: { est: { totalKes: 5 } } });
  assert.deepEqual(v.people, ['Meril']); assert.equal(v.cost, undefined);
  assert.equal(editPacking([{ key: 'u:1', auto: false, en: 'x' }], { items: [{ key: 'u:1', delete: true }] }).length, 0);
});
