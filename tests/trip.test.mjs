// Trip advice: weather (lib/weather.js), roads (lib/roads.js), road reports (lib/road-reports.js), the trip model
// (lib/trip.js) and the endpoints (/api/trip, /api/trip/week, /api/road-reports). fetch is mocked: no real API calls.
// Run: node --test tests/*.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { levelOf, forecastFor, dayFrom, compact, nairobiDay, addDays, summarize, codeWords, cellOf, WX_TTL, rainySeason } from '../lib/weather.js';
import { parseOrs, routeLeg, surfaceClass, guessFromWaytype, simplify, nearLine, midpoint, metres, routeKey, ROUTE_TTL, FAIL_TTL } from '../lib/roads.js';
import { addReport, confirmReport, clearReport, nearRoute, nearPoint, inBox, isActive, untilOf, allReports, TTL_DAYS } from '../lib/road-reports.js';
import { tripAdvice, weekAdvice, speedModel, penalties, recordActual, legKey, learnedFrom, fallbackLeg, fmtMin, chips, bestOf, MODEL } from '../lib/trip.js';

const NOW = Date.parse('2026-10-22T06:00:00Z');        // 09:00 in Nairobi
const TODAY = '2026-10-22';
const DAY = 864e5;

// ---- a fake KV that remembers expirationTtl ---------------------------------------------------------
function kv(init = {}) {
  const m = new Map(Object.entries(init).map(([k, v]) => [k, JSON.stringify(v)])), ttl = new Map();
  return {
    m, ttl,
    get: async (k, t) => { const v = m.get(k); return v == null ? null : t === 'json' ? JSON.parse(v) : v; },
    put: async (k, v, o) => { m.set(k, String(v)); if (o && o.expirationTtl) ttl.set(k, o.expirationTtl); },
    delete: async k => { m.delete(k); },
    list: async ({ prefix }) => ({ keys: [...m.keys()].filter(k => k.startsWith(prefix)).map(name => ({ name })), list_complete: true })
  };
}

// ---- Open-Meteo answer, in its documented shape (daily + hourly arrays, local times) -----------------
// rain by day offset from today; days run from today-2 to today+15
const RAIN = { '-2': 0, '-1': 0, 0: 0, 1: 25, 2: 12, 3: 0, 4: 0, 5: 0, 6: 0.4 };
const CODE = { 1: 65, 2: 63, 6: 1 };
function meteo(today, rain = RAIN) {
  const daily = { time: [], precipitation_sum: [], precipitation_probability_max: [], weather_code: [], temperature_2m_max: [], temperature_2m_min: [] };
  const hourly = { time: [], precipitation: [] };
  for (let o = -2; o < 16; o++) {
    const d = addDays(today, o), r = rain[o] != null ? rain[o] : 3;
    daily.time.push(d); daily.precipitation_sum.push(r); daily.precipitation_probability_max.push(o < 0 ? null : r >= 1 ? 90 : 10);
    daily.weather_code.push(CODE[o] != null ? CODE[o] : r >= 1 ? 61 : 2); daily.temperature_2m_max.push(24.4); daily.temperature_2m_min.push(13.6);
    for (let h = 0; h < 24; h++) {
      hourly.time.push(d + 'T' + String(h).padStart(2, '0') + ':00');
      // a quarter of the rain in the morning (06–11), the rest in the afternoon (15–18)
      hourly.precipitation.push(h >= 6 && h < 12 ? r * 0.25 / 6 : h >= 15 && h < 19 ? r * 0.75 / 4 : 0);
    }
  }
  return { latitude: 0.4, longitude: 35, timezone: 'Africa/Nairobi', daily, hourly };
}

// ---- an ORS answer in its documented GeoJSON shape: Kitale south to a school, 70 km --------------------
// 62 km asphalt state road, then 4 km dirt track, 1 km untagged track, 3 km gravel track. 1 point a kilometre.
const STEP = 1 / 111.19492664455873;                     // degrees of latitude in 1 km (the haversine radius used here)
const KITALE = [1.015, 35.0];
const LINE = Array.from({ length: 71 }, (_, i) => [KITALE[0] - STEP * i, 35.0]);
const SCHOOL = LINE[70];
function ors(opts = {}) {
  const wkey = opts.plural ? 'waytypes' : 'waytype';
  return {
    type: 'FeatureCollection', bbox: [35, SCHOOL[0], 35, KITALE[0]],
    features: [{
      bbox: [35, SCHOOL[0], 35, KITALE[0]], type: 'Feature',
      properties: {
        segments: [{ distance: 70000, duration: 4800, steps: [
          { distance: 62000, duration: 3720, type: 11, instruction: 'Head south on A1', name: 'A1', way_points: [0, 62] },
          { distance: 8000, duration: 1080, type: 1, instruction: 'Turn right', name: '-', way_points: [62, 70] },
          { distance: 0, duration: 0, type: 10, instruction: 'Arrive', name: '-', way_points: [70, 70] }] }],
        extras: {
          surface: { values: [[0, 62, 3], [62, 66, 11], [66, 67, 0], [67, 70, 10]], summary: [{ value: 3, distance: 62000, amount: 88.57 }, { value: 11, distance: 4000, amount: 5.71 }, { value: 10, distance: 3000, amount: 4.29 }, { value: 0, distance: 1000, amount: 1.43 }] },
          [wkey]: { values: [[0, 62, 1], [62, 70, 5]], summary: [{ value: 1, distance: 62000, amount: 88.57 }, { value: 5, distance: 8000, amount: 11.43 }] }
        },
        way_points: [0, 70], summary: { distance: 70000, duration: 4800 }
      },
      geometry: { coordinates: LINE.map(p => [p[1], p[0]]), type: 'LineString' }
    }],
    metadata: { attribution: 'openrouteservice.org | OpenStreetMap contributors', service: 'routing' }
  };
}

// ---- the mocked internet --------------------------------------------------------------------------
const calls = { meteo: [], ors: [] };
let orsReply = () => new Response(JSON.stringify(ors()), { status: 200, headers: { 'Content-Type': 'application/geo+json' } });
let meteoToday = TODAY;
globalThis.fetch = async (url, o = {}) => {
  const u = String(url);
  if (u.includes('open-meteo.com')) { calls.meteo.push(u); return new Response(JSON.stringify(meteo(meteoToday))); }
  if (u.includes('api.heigit.org/openrouteservice')) { calls.ors.push({ url: u, o }); return orsReply(o); }
  // monday.com (the feed for project ids): nothing
  return new Response(JSON.stringify({ data: {} }));
};
const reset = () => { calls.meteo.length = 0; calls.ors.length = 0; };

// ================================ weather ================================
test('weather levels: documented thresholds', () => {
  assert.equal(levelOf(0), 'dry'); assert.equal(levelOf(0.9), 'dry');
  assert.equal(levelOf(1), 'light'); assert.equal(levelOf(9.9), 'light');
  assert.equal(levelOf(10), 'wet'); assert.equal(levelOf(19.9), 'wet');
  assert.equal(levelOf(20), 'heavy'); assert.equal(levelOf(80), 'heavy');
  assert.equal(levelOf(null), 'dry');
});

test('weather: a day from the table, morning rain, the 2 days before, and murram wetness', () => {
  const t = compact(meteo(TODAY));
  const d1 = dayFrom(t.days, '2026-10-23', TODAY);
  assert.equal(d1.available, true); assert.equal(d1.rainMm, 25); assert.equal(d1.rainChance, 90); assert.equal(d1.level, 'heavy');
  assert.equal(d1.morningRainMm, 6.3); assert.equal(d1.tMax, 24); assert.equal(d1.tMin, 14); assert.equal(d1.code, 65);
  assert.deepEqual(d1.prev, [0, 0]); assert.equal(d1.roadLevel, 'heavy');
  // a dry day after two rainy ones: the day is dry, but murram is still wet (12 × 0.5 + 25 × 0.25 = 12.3 mm)
  const d3 = dayFrom(t.days, '2026-10-25', TODAY);
  assert.equal(d3.level, 'dry'); assert.deepEqual(d3.prev, [12, 25]); assert.equal(d3.prevRainMm, 37); assert.equal(d3.soakMm, 12.3); assert.equal(d3.roadLevel, 'wet');
  assert.equal(dayFrom(t.days, '2026-10-26', TODAY).roadLevel, 'light');
  assert.equal(dayFrom(t.days, '2026-10-27', TODAY).roadLevel, 'dry');
  // beyond the window, in the past, nonsense
  assert.deepEqual([dayFrom(t.days, '2026-11-07', TODAY).available, dayFrom(t.days, '2026-11-07', TODAY).reason], [false, 'too-far']);
  assert.equal(dayFrom(t.days, '2026-10-19', TODAY).reason, 'past');
  assert.equal(dayFrom(t.days, '23/10/2026', TODAY).reason, 'bad-date');
});

test('weather: English and Kiswahili summaries', () => {
  const t = compact(meteo(TODAY));
  const h = dayFrom(t.days, '2026-10-23', TODAY);
  assert.equal(h.summary, 'Heavy rain expected · 25 mm (90%) · 24°/14°');
  assert.equal(h.summarySw, 'Mvua kubwa inatarajiwa · mm 25 (90%) · 24°/14°');
  assert.equal(h.words.sw, 'Mvua kubwa');
  const w = dayFrom(t.days, '2026-10-24', TODAY);
  assert.match(w.summary, /^Rain expected · 12 mm/); assert.match(w.summarySw, /^Mvua inatarajiwa · mm 12/);
  const l = dayFrom(t.days, '2026-11-01', TODAY);
  assert.match(l.summarySw, /^Mvua kidogo inawezekana · mm 3/);
  const s = dayFrom(t.days, '2026-10-28', TODAY);
  assert.equal(s.level, 'dry'); assert.equal(s.summary, 'Dry and sunny · 24°/14°'); assert.equal(s.summarySw, 'Jua na hakuna mvua · 24°/14°');
  assert.equal(summarize({ level: 'dry', code: 3, rainMm: 0 }).summarySw, 'Hakuna mvua inayotarajiwa');
  assert.equal(summarize({ level: 'heavy', code: 95, rainMm: 30, rainChance: 80 }).summarySw, 'Mvua kubwa na radi zinatarajiwa · mm 30 (80%)');
  assert.equal(summarize({ level: 'light', code: 95, rainMm: 4 }).summary, 'Thunderstorms expected · 4 mm');
  assert.deepEqual(codeWords(45), { en: 'Fog', sw: 'Ukungu' });
  assert.deepEqual(codeWords(1234), { en: '', sw: '' });
});

test('weather: forecastFor asks Open-Meteo once per 5 km square per 3 hours, and never beyond 16 days', async () => {
  reset();
  const env = { FEED: kv() };
  const a = await forecastFor(env, 0.3851, 35.0012, '2026-10-23', NOW);
  assert.equal(a.available, true); assert.equal(a.level, 'heavy'); assert.equal(a.cell, '0.40,35.00');
  assert.equal(calls.meteo.length, 1);
  const u = new URL(calls.meteo[0]);
  assert.equal(u.hostname, 'api.open-meteo.com');
  assert.equal(u.searchParams.get('latitude'), '0.40'); assert.equal(u.searchParams.get('timezone'), 'Africa/Nairobi');
  assert.equal(u.searchParams.get('forecast_days'), '16'); assert.equal(u.searchParams.get('past_days'), '2');
  assert.equal(u.searchParams.get('daily'), 'precipitation_sum,precipitation_probability_max,weather_code,temperature_2m_max,temperature_2m_min');
  assert.equal(u.searchParams.get('hourly'), 'precipitation'); assert.equal(u.searchParams.get('apikey'), null);
  // another date and a site 2 km away: same square, from KV
  const b = await forecastFor(env, 0.40, 35.01, '2026-10-30', NOW);
  assert.equal(b.available, true); assert.equal(calls.meteo.length, 1);
  const key = 'wx:v1:0.40,35.00:' + TODAY;
  assert.ok(env.FEED.m.has(key)); assert.equal(env.FEED.ttl.get(key), WX_TTL); assert.equal(WX_TTL, 10800);
  // a different square: a second call
  await forecastFor(env, 0.52, 35.27, '2026-10-23', NOW);
  assert.equal(calls.meteo.length, 2);
  // the next day (Nairobi time) is a new forecast
  await forecastFor(env, 0.3851, 35.0012, '2026-10-24', NOW + DAY);
  assert.equal(calls.meteo.length, 3);
  // 16 days and more ahead: no call, not available
  const far = await forecastFor(env, 0.385, 35.0, '2026-11-07', NOW);
  assert.deepEqual([far.available, far.reason, far.availableFrom], [false, 'too-far', '2026-10-23']);
  assert.equal((await forecastFor(env, 0.385, 35.0, '2026-10-10', NOW)).reason, 'past');
  assert.equal(calls.meteo.length, 3);
  // with a paid key: the customer address, key from env only
  reset();
  await forecastFor({ FEED: kv(), OPEN_METEO_API_KEY: 'k123' }, 1, 35, '2026-10-23', NOW);
  assert.match(calls.meteo[0], /^https:\/\/customer-api\.open-meteo\.com\/v1\/forecast\?.*apikey=k123/);
  // Open-Meteo down: not available, nothing cached
  const save = globalThis.fetch; globalThis.fetch = async () => new Response('busy', { status: 503 });
  const env2 = { FEED: kv() };
  assert.equal((await forecastFor(env2, 1, 35, '2026-10-23', NOW)).reason, 'down');
  assert.equal(env2.FEED.m.size, 0);
  globalThis.fetch = save;
});

test('weather: helpers', () => {
  assert.equal(nairobiDay(Date.parse('2026-10-22T21:30:00Z')), '2026-10-23', 'after 21:00 UTC it is tomorrow in Nairobi');
  assert.equal(cellOf(0.514, 35.27), '0.50,35.25'); assert.equal(cellOf(-0.026, 34.99), '-0.05,35.00');
  assert.equal(rainySeason('2026-04-10'), true); assert.equal(rainySeason('2026-11-10'), true); assert.equal(rainySeason('2026-07-10'), false);
});

// ================================ roads ================================
test('roads: surface and way type codes, as ORS documents them', () => {
  for (const c of [1, 3, 4, 6, 7, 14]) assert.equal(surfaceClass(c), 'paved', 'code ' + c);
  for (const c of [2, 8, 10, 11, 12, 13, 15, 17, 18]) assert.equal(surfaceClass(c), 'unpaved', 'code ' + c);
  assert.equal(surfaceClass(0), 'unknown'); assert.equal(surfaceClass(99), 'unknown');
  // removed in 2024, but older servers may still send them
  assert.equal(surfaceClass(5), 'paved'); assert.equal(surfaceClass(9), 'unpaved'); assert.equal(surfaceClass(16), 'unpaved');
  assert.equal(guessFromWaytype(1), 'paved'); assert.equal(guessFromWaytype(5), 'unpaved'); assert.equal(guessFromWaytype(4), 'unpaved');
  assert.equal(guessFromWaytype(2), 'unknown'); assert.equal(guessFromWaytype(3), 'unknown'); assert.equal(guessFromWaytype(0), 'unknown');
});

test('roads: parse a sample ORS answer into tarmac, murram and unknown', () => {
  const leg = parseOrs(ors(), KITALE, SCHOOL);
  assert.equal(leg.km, 70); assert.equal(leg.baseMin, 80);
  assert.deepEqual(leg.surface, { pavedKm: 62, unpavedKm: 7, unknownKm: 1, likely: { pavedKm: 0, unpavedKm: 1 } });
  // ORS's time split by its steps: 62 min on the A1, 18 min on the track (the untagged km counts as track)
  assert.deepEqual(leg.minutes, { paved: 62, unpaved: 18, unknown: 0 });
  // one murram stretch: the last 8 km (the untagged km joins the dirt and the gravel)
  assert.equal(leg.unpaved.length, 1);
  const u = leg.unpaved[0];
  assert.deepEqual([u.startKm, u.endKm, u.km], [62, 70, 8]);
  assert.deepEqual(u.from, [Math.round(LINE[62][0] * 1e5) / 1e5, 35]); assert.deepEqual(u.to, [Math.round(SCHOOL[0] * 1e5) / 1e5, 35]);
  assert.equal(u.surface, 'Dirt, Gravel'); assert.equal(u.guess, true);
  // 71 points: under 200, kept as they are
  assert.equal(leg.geometry.length, 71); assert.equal(leg.tolM, 0); assert.deepEqual(leg.snapM, [0, 0]);
  // the same with "waytypes" (some ORS versions)
  assert.deepEqual(parseOrs(ors({ plural: true }), KITALE, SCHOOL).surface, leg.surface);
  // no steps: time spread by distance
  const j = ors(); delete j.features[0].properties.segments;
  const t = parseOrs(j, KITALE, SCHOOL).minutes;
  assert.ok(Math.abs(t.paved - 62 * 80 / 70) < 0.2 && Math.abs(t.unpaved - 8 * 80 / 70) < 0.2, JSON.stringify(t));
  // untagged on an ordinary road: stays unknown, not guessed
  const k = ors(); k.features[0].properties.extras.waytype.values = [[0, 62, 1], [62, 70, 2]];
  const lk = parseOrs(k, KITALE, SCHOOL);
  assert.deepEqual(lk.surface.likely, { pavedKm: 0, unpavedKm: 0 }); assert.equal(lk.minutes.unknown, 2.3);
  assert.equal(lk.unpaved.length, 2, 'the untagged km splits the dirt from the gravel');
  assert.equal(parseOrs({ features: [] }), null);
});

test('roads: geometry is simplified under 200 points, and lines measure right', () => {
  const zig = Array.from({ length: 1000 }, (_, i) => [0.5 + i * 0.0005, 35 + (i % 2 ? 0.002 : 0) + Math.sin(i / 40) * 0.01]);
  const s = simplify(zig, 200);
  assert.ok(s.line.length <= 200 && s.line.length >= 2, String(s.line.length)); assert.ok(s.tolM > 0);
  assert.deepEqual(s.line[0], zig[0]); assert.deepEqual(s.line[s.line.length - 1], zig[999]);
  assert.equal(simplify(zig.slice(0, 50)).line.length, 50);
  const n = nearLine([LINE[30][0], 35.001], LINE);
  assert.ok(Math.abs(n.d - 111) < 2, String(n.d)); assert.ok(Math.abs(n.alongKm - 30) < 0.01);
  const mid = midpoint([KITALE, SCHOOL]);
  assert.ok(Math.abs(metres(mid, LINE[35])) < 5);
});

test('roads: routeLeg asks ORS correctly, caches 30 days, and switches off quietly with no key', async () => {
  reset();
  const none = await routeLeg({ FEED: kv() }, KITALE, SCHOOL, NOW);
  assert.deepEqual(none, { ok: false, reason: 'no-key' }); assert.equal(calls.ors.length, 0);
  const env = { FEED: kv(), ORS_API_KEY: 'test-key' };
  const a = await routeLeg(env, KITALE, SCHOOL, NOW);
  assert.equal(a.ok, true); assert.equal(a.cached, false); assert.equal(a.km, 70);
  assert.equal(calls.ors.length, 1);
  const c = calls.ors[0], body = JSON.parse(c.o.body);
  assert.equal(c.url, 'https://api.heigit.org/openrouteservice/v2/directions/driving-car/geojson'); assert.equal(c.o.method, 'POST');
  assert.equal(c.o.headers.Authorization, 'test-key');
  assert.deepEqual(body.extra_info, ['surface', 'waytype']);
  assert.deepEqual(body.coordinates, [[35, 1.015], [SCHOOL[1], SCHOOL[0]]], 'ORS wants [lng, lat]');
  // ends within ~50 m: the same route from KV
  const b = await routeLeg(env, [1.0152, 35.0003], [SCHOOL[0] - 0.0002, 35.0001], NOW);
  assert.equal(b.cached, true); assert.equal(b.km, 70); assert.equal(calls.ors.length, 1);
  const key = routeKey(KITALE, SCHOOL);
  assert.equal(env.FEED.ttl.get(key), ROUTE_TTL); assert.equal(ROUTE_TTL, 30 * 86400);
  assert.equal(env.FEED.m.get('orsday:' + TODAY), '1', 'counted against the daily allowance');
  // the key never ends up in KV
  assert.ok(![...env.FEED.m.values()].some(v => v.includes('test-key')));
  // a point with no road nearby: remembered for a day, not asked again
  orsReply = () => new Response(JSON.stringify({ error: { code: 2010, message: 'Could not find routable point within a radius of 3000.0 meters' } }), { status: 404 });
  assert.deepEqual(await routeLeg(env, [2, 36], [2.1, 36.1], NOW), { ok: false, reason: 'no-route' });
  assert.equal(env.FEED.ttl.get(routeKey([2, 36], [2.1, 36.1])), FAIL_TTL);
  assert.equal((await routeLeg(env, [2, 36], [2.1, 36.1], NOW)).cached, true); assert.equal(calls.ors.length, 2);
  // a wrong key, too many requests, ORS down: nothing cached
  orsReply = () => new Response('{"error":"Access to this API has been disallowed"}', { status: 403 });
  assert.equal((await routeLeg(env, [3, 36], [3.1, 36.1], NOW)).reason, 'key');
  orsReply = () => new Response('{}', { status: 429 });
  assert.equal((await routeLeg(env, [3, 36], [3.1, 36.1], NOW)).reason, 'rate');
  orsReply = () => new Response('oops', { status: 500 });
  assert.equal((await routeLeg(env, [3, 36], [3.1, 36.1], NOW)).reason, 'down');
  assert.equal(env.FEED.m.has(routeKey([3, 36], [3.1, 36.1])), false);
  // the daily allowance: stops before ORS says no
  const lim = { FEED: kv(), ORS_API_KEY: 'k', ORS_DAILY_LIMIT: '1' };
  orsReply = () => new Response(JSON.stringify(ors()));
  assert.equal((await routeLeg(lim, KITALE, SCHOOL, NOW)).ok, true);
  assert.equal((await routeLeg(lim, [0.6, 35.2], [0.7, 35.3], NOW)).reason, 'limit');
  assert.equal((await routeLeg(env, [91, 0], KITALE, NOW)).reason, 'place');
});

// ================================ road reports ================================
const GRACE = { who: 'Grace', admin: false }, MERIL = { who: 'Meril', admin: false }, JESS = { who: 'Jessica', admin: true };

test('road reports: add, check, expiry per kind, still there, clear', async () => {
  const env = { FEED: kv() };
  assert.equal((await addReport(env, GRACE, { kind: 'mud', lat: 1, lng: 35 }, 'ip', NOW)).body.reason, 'kind');
  assert.equal((await addReport(env, GRACE, { kind: 'potholes', lat: 0, lng: 0 }, 'ip', NOW)).body.reason, 'place');
  assert.equal((await addReport(env, GRACE, { kind: 'potholes', lat: 1, lng: 35, photo: '../x' }, 'ip', NOW)).body.reason, 'photo');
  const p = (await addReport(env, GRACE, { kind: 'potholes', note: ' big <b>ones</b> ', lat: 0.412345678, lng: 35.0001, photo: 'upd_123' }, 'ip', NOW)).body.report;
  assert.equal(p.by, 'Grace'); assert.equal(p.note, 'big bones/b', 'no angle brackets, as elsewhere in the app'); assert.equal(p.lat, 0.41235); assert.equal(p.photo, 'upd_123');
  assert.equal(p.until, new Date(NOW + 120 * DAY).toISOString());
  const f = (await addReport(env, MERIL, { kind: 'flooded', lat: 0.5, lng: 35 }, 'ip', NOW)).body.report;
  const i = (await addReport(env, MERIL, { kind: 'impassable_when_wet', lat: 0.6, lng: 35 }, 'ip', NOW)).body.report;
  assert.equal(f.until, new Date(NOW + 7 * DAY).toISOString()); assert.equal(i.until, null);
  assert.deepEqual(TTL_DAYS, { potholes: 120, flooded: 7, impassable_when_wet: null, under_construction: 180, bridge_out: null, good_tarmac: 365, slow_murram: 365 });
  assert.equal(untilOf('bridge_out', new Date(NOW).toISOString()), null);
  const list = await allReports(env);
  const active = t => list.filter(r => isActive(r, t)).map(r => r.kind).sort();
  assert.deepEqual(active(NOW + 6 * DAY), ['flooded', 'impassable_when_wet', 'potholes']);
  assert.deepEqual(active(NOW + 8 * DAY), ['impassable_when_wet', 'potholes']);
  assert.deepEqual(active(NOW + 121 * DAY), ['impassable_when_wet']);
  assert.deepEqual(active(NOW + 1000 * DAY), ['impassable_when_wet'], 'stays until someone clears it');
  // still there (another person, 100 days later): potholes count for 120 days from then
  const c = await confirmReport(env, MERIL, p.rid, NOW + 100 * DAY);
  assert.equal(c.body.report.confirms, 1); assert.equal(c.body.report.until, new Date(NOW + 220 * DAY).toISOString());
  // clearing: not someone else's; yes the reporter; yes an admin
  assert.equal((await clearReport(env, MERIL, p.rid, 'fixed', NOW)).status, 403);
  assert.equal((await clearReport(env, { who: '', admin: false }, p.rid, 'fixed', NOW)).status, 403, 'the shared PIN cannot clear');
  const cl = await clearReport(env, GRACE, p.rid, 'fixed', NOW + DAY);
  assert.equal(cl.status, 200); assert.deepEqual([cl.body.report.cleared.by, cl.body.report.cleared.why], ['Grace', 'fixed']);
  assert.equal((await clearReport(env, GRACE, p.rid, 'fixed', NOW)).status, 404, 'already cleared');
  assert.equal((await clearReport(env, JESS, i.rid, 'odd', NOW)).body.report.cleared.why, 'fixed');
  assert.equal((await confirmReport(env, GRACE, i.rid, NOW)).status, 404);
  // rate limit per connection
  const env2 = { FEED: kv({ 'rl:roadrep:9.9.9.9': 30 }) };
  assert.equal((await addReport(env2, GRACE, { kind: 'potholes', lat: 1, lng: 35 }, '9.9.9.9', NOW)).status, 429);
  assert.equal((await addReport({}, GRACE, { kind: 'potholes', lat: 1, lng: 35 }, 'ip', NOW)).status, 503);
});

test('road reports: near a route (150 m), in a box, near a point', () => {
  const at = new Date(NOW - 12 * DAY).toISOString();
  const mk = (rid, kind, lat, lng, extra) => Object.assign({ rid, kind, lat, lng, by: 'Grace', at, seen: at, until: untilOf(kind, at), cleared: null }, extra);
  const off = m => 35 + m / 111320;             // metres east of the road, near the equator
  const list = [
    mk('a', 'potholes', LINE[67][0], off(100)),                       // 100 m off, 3 km before the end
    mk('b', 'potholes', LINE[40][0], off(200)),                       // 200 m off: too far
    mk('c', 'flooded', LINE[10][0], off(20)),                         // on the road, but flooded 12 days ago: over
    mk('d', 'bridge_out', LINE[20][0], off(10), { cleared: { by: 'Jessica', at, why: 'fixed' } }),
    mk('e', 'slow_murram', LINE[69][0], off(0)),
    mk('f', 'potholes', 2.5, 36.5)
  ];
  const near = nearRoute(list, [KITALE, SCHOOL], { now: NOW });
  assert.deepEqual(near.map(r => r.rid), ['a', 'e'], 'ordered along the route');
  assert.ok(Math.abs(near[0].distM - 100) <= 2); assert.equal(near[0].kmBeforeEnd, 3); assert.equal(near[0].alongKm, 67);
  assert.deepEqual(nearRoute(list, [KITALE, SCHOOL], { now: NOW, m: 250 }).map(r => r.rid), ['b', 'a', 'e']);
  assert.deepEqual(inBox(list, { s: 0.3, w: 34.9, n: 1.1, e: 35.1 }, NOW).map(r => r.rid).sort(), ['a', 'b', 'e']);
  assert.deepEqual(nearPoint(list, SCHOOL, 4, NOW).map(r => r.rid), ['e', 'a']);
  assert.deepEqual(nearPoint(list, SCHOOL, 2, NOW).map(r => r.rid), ['e']);
  assert.deepEqual(nearRoute(list, [], { now: NOW }), []);
});

// ================================ the model ================================
test('speed model: tarmac at ORS speed, murram slower when wet, unknown in between', () => {
  const m = { paved: 62, unpaved: 18, unknown: 0 };
  const close = (a, b) => assert.ok(Math.abs(a - b) < 1e-9, a + ' vs ' + b);
  close(speedModel(m, 'dry').est, 62 + 18 * 1.3);
  close(speedModel(m, 'light').est, 62 + 18 * 1.45);
  close(speedModel(m, 'wet').est, 62 + 18 * 1.8);
  close(speedModel(m, 'heavy').est, 62 + 18 * 2.5);
  close(speedModel(m, 'heavy').max, 62 * 1.3 + 18 * 3.5);
  close(speedModel(m, 'heavy').dryEst, 62 + 18 * 1.3);
  close(speedModel({ paved: 60, unpaved: 0, unknown: 0 }, 'heavy').est, 60);
  close(speedModel({ paved: 0, unpaved: 0, unknown: 10 }, 'wet').est, 10 * (1 + 0.8 * MODEL.unknownShare));
  // no forecast: usual as given, worst as the season says
  close(speedModel(m, 'dry', { maxLevel: 'wet' }).max, 62 * 1.2 + 18 * 2.5);
  assert.deepEqual(penalties([{ kind: 'potholes' }, { kind: 'flooded' }], 'wet'), { est: 25, max: 75 });
  assert.deepEqual(penalties([{ kind: 'impassable_when_wet' }], 'dry'), { est: 0, max: 5 });
  assert.deepEqual(penalties([{ kind: 'impassable_when_wet' }], 'heavy'), { est: 45, max: 120 });
  assert.deepEqual(penalties([{ kind: 'good_tarmac' }], 'heavy'), { est: 0, max: 0 });
  // no road data: straight line × 1.4 at 45 km/h, 30% counted as murram
  const fb = fallbackLeg([0.514, 35.27], KITALE, 'no-key');
  const line = metres([0.514, 35.27], KITALE) / 1000;
  assert.equal(fb.roads, false); assert.equal(fb.km, Math.round(line * 1.4 * 10) / 10); assert.equal(fb.baseMin, Math.round(line * 1.4 / 45 * 60));
  assert.deepEqual(fmtMin(100), { en: '~1 h 40', sw: '~saa 1 dak 40' }); assert.deepEqual(fmtMin(45), { en: '~45 min', sw: '~dakika 45' }); assert.deepEqual(fmtMin(120).en, '~2 h');
});

test('learned times: the median of real trips is blended in from 3 trips', async () => {
  const env = { FEED: kv() };
  const k = legKey(KITALE, SCHOOL);
  assert.equal(k, legKey(SCHOOL, KITALE), 'the same both ways');
  assert.equal(k, '0.385,35.000|1.015,35.000');
  assert.deepEqual(await recordActual(env, 'nonsense', 30), { ok: false, reason: 'leg' });
  assert.deepEqual(await recordActual(env, k, 0), { ok: false, reason: 'minutes' });
  assert.deepEqual(await recordActual(env, k, 9999), { ok: false, reason: 'minutes' });
  await recordActual(env, k, 100, { now: NOW }); await recordActual(env, k, 90, { now: NOW });
  assert.equal(learnedFrom(await env.FEED.get('triplearn:' + k, 'json'), 'dry'), null, 'two trips: not yet');
  assert.deepEqual(await recordActual(env, k, 95, { now: NOW }), { ok: true, n: 3 });
  const l = learnedFrom(await env.FEED.get('triplearn:' + k, 'json'), 'dry');
  assert.deepEqual([l.n, l.median, l.weight, l.sameWeather], [3, 95, 0.5, true]);
  // wet trips: used for wet days once there are 3 of them; until then all trips count
  for (const m of [150, 160]) await recordActual(env, k, m, { level: 'heavy' });
  const all = await env.FEED.get('triplearn:' + k, 'json');
  assert.deepEqual([learnedFrom(all, 'heavy').n, learnedFrom(all, 'heavy').sameWeather], [5, false]);
  await recordActual(env, k, 170, { level: 'wet' });
  const w = learnedFrom(await env.FEED.get('triplearn:' + k, 'json'), 'heavy');
  assert.deepEqual([w.n, w.median, w.sameWeather], [3, 160, true]);
  // at most 15 kept, weight at most 0.8
  for (let i = 0; i < 20; i++) await recordActual(env, k, 100);
  const many = await env.FEED.get('triplearn:' + k, 'json');
  assert.equal(many.length, 15); assert.equal(learnedFrom(many, 'dry').weight, 0.8);
});

test('trip advice: Kitale to the school on a heavy-rain day, with a pothole report', async () => {
  reset(); orsReply = () => new Response(JSON.stringify(ors()));
  const store = kv(), env = { FEED: store, ORS_API_KEY: 'k' };
  await addReport(env, GRACE, { kind: 'potholes', lat: LINE[67][0], lng: 35 + 40 / 111320, note: 'deep' }, 'ip', NOW - 12 * DAY);
  await addReport(env, MERIL, { kind: 'potholes', lat: LINE[20][0], lng: 35.01 }, 'ip', NOW);   // 1 km off the road: not counted
  const r = await tripAdvice(env, { from: { lat: KITALE[0], lng: KITALE[1], name: 'Kitale' }, stops: [{ id: '104', name: 'Bishop Luge Memorial', lat: SCHOOL[0], lng: SCHOOL[1] }], date: '2026-10-23', now: NOW });
  assert.equal(r.ok, true); assert.equal(r.roads, true); assert.equal(r.legs.length, 1);
  const leg = r.legs[0];
  assert.equal(leg.km, 70); assert.equal(leg.baseMin, 80);
  assert.deepEqual(leg.surface, { pavedKm: 62, unpavedKm: 7, unknownKm: 1, likely: { pavedKm: 0, unpavedKm: 1 } });
  assert.equal(leg.weather.roadLevel, 'heavy'); assert.equal(leg.weather.rainMm, 25);
  // 62 + 18 × 2.5 = 107, plus 5 for the potholes; worst 62 × 1.3 + 18 × 3.5 + 15
  assert.equal(leg.estMin, 112); assert.equal(leg.estMaxMin, 159);
  assert.equal(leg.riskLevel, 'difficult');
  const codes = leg.warnings.map(w => w.code);
  assert.deepEqual(codes, ['MURRAM_HEAVY_RAIN', 'MAY_BE_IMPASSABLE', 'REPORT_POTHOLES']);
  const m = leg.warnings[0];
  assert.equal(m.en, 'Last 8 km is murram and 25 mm of rain is forecast; allow about 20 min extra or take a 4x4');
  assert.equal(m.sw, 'Kilomita 8 za mwisho ni murram na mvua ya mm 25 inatarajiwa; ongeza takriban dakika 20 au tumia gari la 4x4');
  assert.equal(m.level, 'difficult'); assert.equal(m.extraMin, 20);
  const p = leg.warnings[2];
  assert.equal(p.en, 'Potholes reported 3 km before the site by Grace, 12 days ago');
  assert.equal(p.sw, 'Ripoti: Mashimo barabarani, km 3 kabla ya kufika — Grace, siku 12 zilizopita');
  assert.equal(leg.reports.length, 1); assert.equal(leg.reports[0].note, 'deep');
  assert.deepEqual(leg.chips.surface, { en: '62 km tarmac · 8 km murram', sw: 'km 62 lami · km 8 murram' });
  assert.deepEqual(leg.chips.rain, { en: 'Rain 25 mm', sw: 'Mvua mm 25' });
  assert.deepEqual(leg.chips.time, { en: '~1 h 52', sw: '~saa 1 dak 52' });
  assert.equal(leg.key, '0.385,35.000|1.015,35.000');
  assert.deepEqual(r.total.estMin, 112); assert.equal(r.total.riskLevel, 'difficult'); assert.equal(r.total.warnings, 3);
  assert.ok(Array.isArray(leg.geometry));
  // one ORS call; Open-Meteo once per 5 km square (the school, and halfway)
  assert.equal(calls.ors.length, 1); assert.equal(calls.meteo.length, 2);
  // a dry day after rain: murram still wet
  const after = (await tripAdvice(env, { from: KITALE, stops: [{ lat: SCHOOL[0], lng: SCHOOL[1] }], date: '2026-10-25', now: NOW })).legs[0];
  assert.equal(after.weather.level, 'dry'); assert.equal(after.weather.roadLevel, 'wet');
  assert.equal(after.warnings[0].code, 'MURRAM_AFTER_RAIN');
  assert.equal(after.warnings[0].en, 'Last 8 km is murram and 37 mm of rain fell in the 2 days before; allow about 10 min extra or take a 4x4');
  assert.equal(after.warnings[0].sw, 'Kilomita 8 za mwisho ni murram na mvua ya mm 37 ilinyesha siku mbili zilizopita; ongeza takriban dakika 10 au tumia gari la 4x4');
  assert.equal(after.riskLevel, 'caution');
  assert.equal(calls.ors.length, 1, 'route from KV'); assert.equal(calls.meteo.length, 2, 'weather from KV');
  // a dry day: ok, just the potholes
  const dry = (await tripAdvice(env, { from: KITALE, stops: [{ lat: SCHOOL[0], lng: SCHOOL[1] }], date: '2026-10-28', now: NOW })).legs[0];
  assert.equal(dry.estMin, Math.round(62 + 18 * 1.3 + 3)); assert.deepEqual(dry.warnings.map(w => w.code), ['REPORT_POTHOLES']); assert.equal(dry.riskLevel, 'caution');
});

test('trip advice: a range of days gives the driest one; beyond 16 days there is no forecast', async () => {
  reset(); orsReply = () => new Response(JSON.stringify(ors()));
  const env = { FEED: kv(), ORS_API_KEY: 'k' };
  const r = await tripAdvice(env, { from: KITALE, stops: [{ lat: SCHOOL[0], lng: SCHOOL[1] }], date: '2026-10-23', until: '2026-10-29', now: NOW, geometry: false });
  assert.equal(r.days.length, 7);
  // road wetness: the 24th has 12 mm on top of the 25 mm the day before
  assert.deepEqual(r.days.map(d => d.roadLevel), ['heavy', 'heavy', 'wet', 'light', 'dry', 'dry', 'light']);
  assert.equal(r.bestDay.date, '2026-10-27'); assert.equal(r.bestDay.riskLevel, 'ok'); assert.equal(r.bestDay.name.en, 'Tue 27 Oct'); assert.equal(r.bestDay.name.sw, 'Jumanne 27/10');
  assert.equal(r.legs[0].geometry, undefined);
  assert.equal(calls.ors.length, 1);
  // far ahead: estimate with no weather, season-aware worst case
  const far = await tripAdvice(env, { from: KITALE, stops: [{ lat: SCHOOL[0], lng: SCHOOL[1] }], date: '2026-11-20', now: NOW });
  const leg = far.legs[0];
  assert.equal(leg.weather.available, false); assert.equal(leg.weather.reason, 'too-far');
  assert.equal(leg.estMin, Math.round(62 + 18 * 1.3)); assert.equal(leg.estMaxMin, Math.round(62 * 1.2 + 18 * 2.5), 'November: short rains');
  assert.equal(leg.warnings[0].code, 'NO_FORECAST'); assert.match(leg.warnings[0].sw, /msimu wa mvua/);
  assert.deepEqual(leg.chips.rain, { en: 'No forecast yet', sw: 'Bado hakuna utabiri' });
  assert.equal(bestOf([]), null);
});

test('trip advice: no ORS key → straight-line estimate, weather still there, roads: false', async () => {
  reset();
  const env = { FEED: kv() };
  const ELD = [0.514, 35.27];
  const r = await tripAdvice(env, { from: { lat: KITALE[0], lng: KITALE[1], name: 'Kitale' }, stops: [{ id: '1', lat: ELD[0], lng: ELD[1], name: 'Eldoret' }, { id: '2', lat: SCHOOL[0], lng: SCHOOL[1] }], date: '2026-10-24', now: NOW });
  assert.equal(r.ok, true); assert.equal(r.roads, false); assert.equal(r.roadsReason, 'no-key');
  assert.equal(calls.ors.length, 0); assert.ok(calls.meteo.length >= 2);
  const leg = r.legs[0];
  assert.equal(leg.roads, false); assert.equal(leg.km, Math.round(metres(KITALE, ELD) / 1000 * 1.4 * 10) / 10);
  assert.equal(leg.weather.available, true); assert.equal(leg.weather.level, 'wet'); assert.equal(leg.weather.roadLevel, 'heavy');
  // 30% counted as murram: base × (0.7 + 0.3 × 2.5)
  assert.equal(leg.estMin, Math.round(leg.km / 45 * 60 * (0.7 + 0.3 * 2.5)));
  assert.equal(leg.warnings[0].code, 'RAIN_NO_ROADS'); assert.equal(leg.warnings[0].sw, 'Mvua ya mm 12: ongeza muda kama barabara ni murram');
  assert.deepEqual(leg.chips.surface.en, Math.round(leg.km) + ' km (estimate)');
  assert.equal(r.legs.length, 2); assert.equal(r.total.km, Math.round((r.legs[0].km + r.legs[1].km) * 10) / 10);
  // ORS failing for one leg only: that leg falls back, the others keep their roads
  orsReply = o => (JSON.parse(o.body).coordinates[0][1] === 1.015 ? new Response(JSON.stringify(ors())) : new Response('{"error":{"code":2010}}', { status: 404 }));
  const mixed = await tripAdvice({ FEED: kv(), ORS_API_KEY: 'k' }, { from: KITALE, stops: [{ lat: SCHOOL[0], lng: SCHOOL[1] }, { lat: 0.3, lng: 35.1 }], date: '2026-10-28', now: NOW });
  assert.deepEqual(mixed.legs.map(l => l.roads), [true, false]); assert.equal(mixed.roads, false); assert.equal(mixed.roadsReason, 'no-route');
  orsReply = () => new Response(JSON.stringify(ors()));
  // bad input
  assert.equal((await tripAdvice(env, { stops: [] })).reason, 'stops');
  assert.equal((await tripAdvice(env, { stops: [{ lat: 1, lng: 35 }] })).reason, 'stops', 'one stop and no start');
  assert.equal((await tripAdvice(env, { stops: Array(13).fill({ lat: 1, lng: 35 }) })).reason, 'too-many');
  assert.equal((await tripAdvice(env, { from: { lat: 'x' }, stops: [{ lat: 1, lng: 35 }] })).reason, 'from');
});

test('trip advice: learned times blend into estMin', async () => {
  const env = { FEED: kv(), ORS_API_KEY: 'k' };
  const k = legKey(KITALE, SCHOOL);
  for (const m of [70, 75, 80]) await recordActual(env, k, m);
  const leg = (await tripAdvice(env, { from: KITALE, stops: [{ lat: SCHOOL[0], lng: SCHOOL[1] }], date: '2026-10-28', now: NOW })).legs[0];
  const model = Math.round(62 + 18 * 1.3);
  assert.equal(leg.learned.modelMin, model); assert.equal(leg.learned.median, 75);
  assert.equal(leg.estMin, Math.round(0.5 * (62 + 18 * 1.3) + 0.5 * 75));
});

test('week: the driest day for a list of projects, and for each planned day', async () => {
  reset();
  const env = { FEED: kv() };
  await addReport(env, GRACE, { kind: 'impassable_when_wet', lat: SCHOOL[0] + 0.005, lng: 35 }, 'ip', NOW);
  const r = await weekAdvice(env, { projects: [{ id: '1', name: 'A', lat: SCHOOL[0], lng: SCHOOL[1] }, { id: '2', name: 'B', lat: 0.514, lng: 35.27 }], start: '2026-10-23', days: 5, groups: [['1'], ['2']], now: NOW });
  assert.equal(r.ok, true); assert.equal(r.days.length, 5);
  assert.equal(r.bestDay.date, '2026-10-27'); assert.equal(r.bestDay.roadLevel, 'dry');
  assert.equal(r.days[0].wetSites, 2);
  const a = r.projects[0];
  assert.equal(a.wetReports.length, 1); assert.equal(a.days[0].score, 3 * 2 + 25 / 20, 'a site that floods counts twice');
  assert.equal(r.groups.length, 2); assert.equal(r.groups[0].bestDay.date, '2026-10-27');
  assert.deepEqual(r.days.map(d => d.roadLevel), ['heavy', 'heavy', 'wet', 'light', 'dry']);
  assert.equal(calls.meteo.length, 2, 'one forecast per 5 km square');
  // days past the forecast: listed, not rated
  const late = await weekAdvice(env, { projects: [{ id: '1', lat: 1, lng: 35 }], start: '2026-11-05', days: 4, now: NOW });
  assert.deepEqual(late.days.map(d => d.available), [true, true, false, false]); assert.ok(late.note.sw);
  assert.equal((await weekAdvice(env, { projects: [], now: NOW })).reason, 'stops');
});

test('chips: tarmac, murram and unknown kilometres', () => {
  const c = chips({ km: 20, roads: true, surface: { pavedKm: 10, unpavedKm: 2, unknownKm: 8, likely: { pavedKm: 3, unpavedKm: 1 } }, estMin: 35, weather: { available: true, rainMm: 0.4 } });
  assert.equal(c.surface.en, '13 km tarmac · 3 km murram · 4 km unknown'); assert.equal(c.surface.sw, 'km 13 lami · km 3 murram · km 4 haijulikani');
  assert.deepEqual(c.rain, { en: 'Dry', sw: 'Hakuna mvua' });
});

// ================================ endpoints ================================
const P = [
  { id: '104', title: '**Bishop Luge Memorial', ck: 'ke', area: 'Kitale', type: 'Educational', gps: SCHOOL },
  { id: '105', title: 'Kapsaret Borehole', ck: 'ke', area: 'Kapsaret, Eldoret', type: 'Borehole well', gps: [0.514, 35.27] },
  { id: '106', title: 'No location', ck: 'ke', area: 'Kitale', type: 'Borehole well', gps: null }
];
function envFor(extra) {
  const store = kv();
  store.m.set('feed:v9', JSON.stringify({ at: Date.now(), feed: { updated: new Date().toISOString(), projects: P } }));
  return Object.assign({ FEED: store, MONDAY_API_TOKEN: 't', PIN_USERS: 'Grace:4444, Meril:3333, Jessica:2222', PIN_ADMINS: 'Jessica' }, extra || {});
}
const trip = await import('../functions/api/trip.js');
const week = await import('../functions/api/trip/week.js');
const reports = await import('../functions/api/road-reports.js');
const req = (path, pin, body) => new Request('https://pin.example' + path, { method: body ? 'POST' : 'GET', headers: Object.assign({ 'CF-Connecting-IP': '1.2.3.4' }, pin ? { 'X-Pin': pin } : {}, body ? { 'Content-Type': 'application/json', Origin: 'https://pin.example' } : {}), body: body ? JSON.stringify(body) : undefined });
const call = async (mod, env, path, pin, body) => { const r = await (body ? mod.onRequestPost : mod.onRequestGet)({ request: req(path, pin, body), env, waitUntil: p => p }); return Object.assign(await r.json(), { status: r.status }); };

test('endpoint /api/trip: sign-in, project ids from the feed, points, and recording real times', async () => {
  reset(); meteoToday = nairobiDay(Date.now()); orsReply = () => new Response(JSON.stringify(ors()));
  const env = envFor();
  assert.equal((await call(trip, env, '/api/trip?stops=104')).status, 401);
  assert.equal((await call(trip, env, '/api/trip?stops=104', '9999')).status, 401);
  // no ORS key: still answers, roads off
  let j = await call(trip, env, '/api/trip?from=' + KITALE.join(',') + '&stops=104,105', '4444');
  assert.equal(j.status, 200); assert.equal(j.ok, true); assert.equal(j.roads, false); assert.equal(j.roadsReason, 'no-key');
  assert.equal(j.legs.length, 2); assert.equal(j.legs[0].to.name, 'Bishop Luge Memorial'); assert.equal(j.legs[0].to.id, '104');
  assert.equal(j.date, meteoToday); assert.equal(j.legs[0].weather.available, true);
  // with the key, a single visit from the person's position
  const envK = envFor({ ORS_API_KEY: 'k' });
  j = await call(trip, envK, '/api/trip?from=' + KITALE.join(',') + '&stops=104&date=' + addDays(meteoToday, 1) + '&until=' + addDays(meteoToday, 4), '4444');
  assert.equal(j.roads, true); assert.equal(j.legs[0].surface.pavedKm, 62); assert.equal(j.days.length, 4); assert.ok(j.bestDay.date);
  // points and ids mixed, and a project id as the start
  j = await call(trip, envK, '/api/trip?from=105&stops=' + encodeURIComponent('104;' + KITALE.join(',')) + '&geometry=0', '4444');
  assert.equal(j.legs.length, 2); assert.equal(j.legs[0].from.name, 'Kapsaret Borehole'); assert.equal(j.legs[0].geometry, undefined);
  // POST, the same
  j = await call(trip, envK, '/api/trip', '4444', { from: { lat: KITALE[0], lng: KITALE[1], name: 'Kitale' }, stops: ['104'], date: meteoToday });
  assert.equal(j.ok, true); assert.equal(j.legs[0].from.name, 'Kitale');
  // bad input
  assert.equal((await call(trip, envK, '/api/trip?stops=106', '4444')).reason, 'stops', 'no location');
  assert.equal((await call(trip, envK, '/api/trip?stops=999', '4444')).status, 400);
  assert.equal((await call(trip, envK, '/api/trip?stops=104,105&date=tomorrow', '4444')).reason, 'date');
  assert.equal((await call(trip, envK, '/api/trip?stops=' + Array(13).fill('104').join(','), '4444')).reason, 'too-many');
  // real trip times
  j = await call(trip, envK, '/api/trip', '3333', { action: 'actual', from: KITALE, to: SCHOOL, minutes: 95, level: 'wet' });
  assert.deepEqual([j.ok, j.leg, j.n], [true, '0.385,35.000|1.015,35.000', 1]);
  j = await call(trip, envK, '/api/trip', '3333', { action: 'actual', leg: '0.385,35.000|1.015,35.000', minutes: 90 });
  assert.equal(j.n, 2);
  assert.equal((await call(trip, envK, '/api/trip', '3333', { action: 'actual', leg: 'x', minutes: 90 })).reason, 'leg');
  assert.equal((await call(trip, envK, '/api/trip', '3333', { action: 'actual', from: KITALE, to: SCHOOL, minutes: -3 })).reason, 'minutes');
  // another site's page can't post
  const evil = new Request('https://pin.example/api/trip', { method: 'POST', headers: { 'X-Pin': '3333', Origin: 'https://evil.example', 'Content-Type': 'application/json' }, body: '{}' });
  assert.equal((await trip.onRequestPost({ request: evil, env: envK })).status, 403);
});

test('endpoint /api/trip/week', async () => {
  reset(); meteoToday = nairobiDay(Date.now());
  const env = envFor();
  assert.equal((await call(week, env, '/api/trip/week?ids=104')).status, 401);
  const j = await call(week, env, '/api/trip/week?ids=104,105,106&days=3&groups=104|105', '4444');
  assert.equal(j.status, 200); assert.equal(j.days.length, 3); assert.deepEqual(j.nogps, ['106']);
  assert.equal(j.projects.length, 2); assert.equal(j.projects[0].name, 'Bishop Luge Memorial'); assert.equal(j.groups.length, 2); assert.ok(j.bestDay);
  assert.equal(calls.ors.length, 0, 'weather only');
  assert.equal((await call(week, env, '/api/trip/week?ids=abc', '4444')).status, 400);
  assert.equal((await call(week, env, '/api/trip/week?ids=106', '4444')).reason, 'stops');
});

test('endpoint /api/road-reports: add, list by box, route and point, confirm, clear', async () => {
  const env = envFor();
  assert.equal((await call(reports, env, '/api/road-reports?near=1,35')).status, 401);
  let j = await call(reports, env, '/api/road-reports', '4444', { action: 'add', kind: 'potholes', note: 'by the market', lat: LINE[67][0], lng: 35.0003, photo: '9001' });
  assert.equal(j.ok, true); assert.equal(j.report.by, 'Grace');
  const rid = j.report.rid;
  assert.equal((await call(reports, env, '/api/road-reports', '4444', { action: 'add', kind: 'lava', lat: 1, lng: 35 })).reason, 'kind');
  j = await call(reports, env, '/api/road-reports?s=0.3&w=34.9&n=0.5&e=35.1', '3333');
  assert.equal(j.reports.length, 1); assert.equal(j.reports[0].words.sw, 'Mashimo barabarani'); assert.equal(j.ttlDays.flooded, 7);
  assert.equal((await call(reports, env, '/api/road-reports?s=0&w=30&n=5&e=40', '3333')).status, 400, 'box too big');
  assert.equal((await call(reports, env, '/api/road-reports?s=0.3&w=34.9', '3333')).status, 400);
  j = await call(reports, env, '/api/road-reports?route=' + encodeURIComponent(KITALE.join(',') + ';' + SCHOOL.join(',')), '3333');
  assert.equal(j.reports.length, 1); assert.equal(j.reports[0].kmBeforeEnd, 3);
  j = await call(reports, env, '/api/road-reports?near=' + SCHOOL.join(',') + '&km=5', '3333');
  assert.equal(j.reports.length, 1); assert.ok(j.reports[0].distM > 2900);
  assert.equal((await call(reports, env, '/api/road-reports?near=' + SCHOOL.join(',') + '&km=1', '3333')).reports.length, 0);
  j = await call(reports, env, '/api/road-reports', '3333', { action: 'confirm', rid });
  assert.equal(j.report.confirms, 1);
  assert.equal((await call(reports, env, '/api/road-reports', '3333', { action: 'clear', rid })).status, 403, 'Meril did not report it');
  j = await call(reports, env, '/api/road-reports', '2222', { action: 'clear', rid, why: 'fixed' });
  assert.equal(j.ok, true); assert.equal(j.report.cleared.by, 'Jessica');
  assert.equal((await call(reports, env, '/api/road-reports?near=' + SCHOOL.join(','), '3333')).reports.length, 0);
  assert.equal((await call(reports, env, '/api/road-reports', '3333', { action: 'nope' })).status, 400);
});
