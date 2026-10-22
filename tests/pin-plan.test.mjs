// Office › Plan a week (lib/pin-plan.js) and the live team map (lib/pin-here.js), with a fake FEED KV.
// Run: node --test tests/*.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { candidates, cluster, orderDay, routeKm, planWeek, planChosen, chosenStops, metres, DUE_DAYS } from '../lib/pin-plan.js';
import { postHere, teamMap, planView, HERE_TTL } from '../lib/pin-here.js';

const NOW = Date.parse('2026-10-21T09:00:00Z');
const ago = d => new Date(NOW - d * 864e5).toISOString();
// three towns far apart in western Kenya, a few sites around each
const TOWNS = { eld: [0.514, 35.27], kit: [1.015, 35.0], bun: [0.563, 34.56] };
let n = 0;
const site = (town, dLat, dLng, extra) => Object.assign({ id: String(++n), title: town + ' site ' + n, ck: 'ke', area: town === 'eld' ? 'Kapsaret, Eldoret' : town === 'kit' ? 'Kitale' : 'Bungoma', type: 'Borehole well', gps: [TOWNS[town][0] + dLat, TOWNS[town][1] + dLng] }, extra);
const P = [
  site('eld', 0, 0), site('eld', 0.02, 0.01), site('eld', -0.03, 0.02), site('eld', 0.01, -0.02),
  site('kit', 0, 0), site('kit', 0.02, 0.02), site('kit', -0.01, 0.03),
  site('bun', 0, 0), site('bun', 0.03, -0.01),
  site('eld', 0.005, 0.005, { gps: null }),                 // no location: never planned
  site('eld', 0.015, 0.015, { ck: 'tz' }),                  // other country
];
const visits = {}; P.forEach((p, i) => { visits[p.id] = { at: ago(i % 2 ? 400 : 30), state: 'working' }; });
visits['5'] = { at: ago(10), state: 'notworking' };
const repairs = { 5: { state: 'notworking', urgency: 'urgent' }, 3: { state: 'repair', urgency: 'month' } };

test('candidates: repairs first, overdue next, recent and assigned ones left out', () => {
  const c = candidates({ projects: P, visits, repairs, assigned: [{ id: '2', done: null }], tests: new Set(['7']), now: NOW, ck: 'ke' });
  const ids = c.map(x => x.id);
  assert.equal(c[0].id, '5'); assert.deepEqual(c[0].why, ['repair', 'urgent']);
  assert.ok(ids.includes('3'));                      // a repair, even though visited recently
  assert.ok(!ids.includes('1'));                     // visited 30 days ago, nothing wrong
  assert.ok(!ids.includes('2'));                     // already assigned
  assert.ok(!ids.includes('10') && !ids.includes('11'));
  assert.ok(c.find(x => x.id === '7').why.includes('test'));
  assert.ok(c.every(x => x.why.length && x.score > 0));
  assert.ok(c.filter(x => x.why.includes('overdue')).every(x => x.age >= DUE_DAYS));
  // area filter
  const kit = candidates({ projects: P, visits, repairs, now: NOW, ck: 'ke', area: 'kitale' });
  assert.ok(kit.length >= 1 && kit.every(x => x.area === 'Kitale'));
  // never visited counts as due
  assert.deepEqual(candidates({ projects: [site('eld', 0, 0)], visits: {}, now: NOW, ck: 'ke' })[0].why, ['never']);
});

test('cluster: each day stays in one town, the most urgent day first, capacity kept', () => {
  const all = P.filter(p => p.gps && p.ck === 'ke').map(p => ({ id: p.id, gps: p.gps, score: repairs[p.id] ? 100 : 40, town: p.title.split(' ')[0] }));
  const { days, spare } = cluster(all, { days: 3, per: 4 });
  assert.equal(days.length, 3);
  days.forEach(d => { assert.ok(d.length <= 4); assert.equal(new Set(d.map(x => x.town)).size, 1, 'one town per day: ' + d.map(x => x.town)); });
  assert.ok(days[0].some(x => x.id === '5' || x.id === '3'));
  assert.equal(days.flat().length + spare.length, all.length);
  // not enough days: the rest are spare, never dropped silently
  const two = cluster(all, { days: 1, per: 3 });
  assert.equal(two.days[0].length, 3); assert.equal(two.spare.length, all.length - 3);
});

test('orderDay: nearest next and no crossings; km adds up', () => {
  const line = [0, 3, 1, 4, 2].map(i => ({ id: 'p' + i, gps: [0, i * 0.01] }));
  const o = orderDay(line, [0, -0.01]);
  assert.deepEqual(o.map(x => x.id), ['p0', 'p1', 'p2', 'p3', 'p4']);
  const km = routeKm(o);
  assert.ok(Math.abs(km - metres([0, 0], [0, 0.04]) / 1000) < 0.15, 'km ' + km);
  assert.equal(routeKm([]), 0);
});

test('planWeek: days in driving order with km, spare list, limits', () => {
  const c = candidates({ projects: P, visits, repairs, now: NOW, ck: 'ke' });
  const plan = planWeek(c, { days: 9, per: 50 });
  assert.ok(plan.days.length >= 1 && plan.days.length <= 6);
  plan.days.forEach(d => { assert.ok(d.stops.length >= 1 && d.stops.length <= 8); assert.equal(typeof d.km, 'number'); });
  assert.equal(new Set(plan.days.flatMap(d => d.stops.map(s => s.id))).size, plan.days.flatMap(d => d.stops).length, 'no stop twice');
});

// --- the team map, with a fake KV -----------------------------------------------------------------------
function kv(init) {
  const m = new Map(Object.entries(init || {}).map(([k, v]) => [k, { v: typeof v === 'string' ? v : JSON.stringify(v) }]));
  return {
    m,
    async get(k, t) { const e = m.get(k); if (!e) return null; return t === 'json' ? JSON.parse(e.v) : e.v; },
    async put(k, v, o) { m.set(k, { v, ttl: o && o.expirationTtl }); },
    async delete(k) { m.delete(k); },
    async list({ prefix }) { return { keys: [...m.keys()].filter(k => k.startsWith(prefix)).map(name => ({ name })), list_complete: true }; }
  };
}

test('here: only your own latest point, expiring after 12 h; off deletes it', async () => {
  const env = { FEED: kv() };
  assert.equal((await postHere(env, { who: '' }, { lat: 0.5, lng: 35.2 }, 'ip')).body.reason, 'own-pin');
  assert.equal((await postHere(env, { who: 'Meril' }, { lat: 'x', lng: 35.2 }, 'ip')).status, 400);
  assert.equal((await postHere(env, { who: 'Meril' }, { lat: 0, lng: 0 }, 'ip')).status, 400);
  const r = await postHere(env, { who: 'Meril' }, { lat: 0.5123456, lng: 35.2654321, acc: 12.4 }, 'ip');
  assert.equal(r.status, 200);
  const e = env.FEED.m.get('pinhere:meril');
  assert.equal(e.ttl, HERE_TTL); assert.equal(HERE_TTL, 43200);
  assert.deepEqual(Object.keys(JSON.parse(e.v)).sort(), ['acc', 'at', 'lat', 'lng', 'name']);
  assert.equal(JSON.parse(e.v).lat, 0.51235);
  // a second point straight away is ignored (no history, no flood)
  const again = await postHere(env, { who: 'Meril' }, { lat: 0.6, lng: 35.3 }, 'ip');
  assert.equal(again.body.skipped, true); assert.equal(JSON.parse(env.FEED.m.get('pinhere:meril').v).lat, 0.51235);
  assert.equal([...env.FEED.m.keys()].filter(k => k.startsWith('pinhere:')).length, 1);
  await postHere(env, { who: 'Meril' }, { off: true }, 'ip');
  assert.equal(env.FEED.m.has('pinhere:meril'), false);
});

test('here: rate limited per connection', async () => {
  const env = { FEED: kv({ 'rl:pinhere:ip9': '40' }) };
  assert.equal((await postHere(env, { who: 'Bertin' }, { lat: 1, lng: 35 }, 'ip9')).status, 429);
});

test('teamMap: sharers with phones, today’s visits only, stale points dropped', async () => {
  const now = Date.parse('2026-10-21T09:00:00Z');
  const env = { FEED: kv({
    'pinhere:meril': { name: 'Meril', lat: 0.51, lng: 35.27, acc: 10, at: '2026-10-21T08:48:00Z' },
    'pinhere:old': { name: 'Old', lat: 1, lng: 35, acc: 10, at: '2026-10-20T08:00:00Z' },
    pinteam: [{ name: 'Meril', phone: '+254 712 000 111', home: 'ke' }],
    pinprofiles: {},
    pinact: [{ a: 'visit', by: 'Meril', id: '5', at: '2026-10-21T07:10:00Z', s: 'working', t: 'Kitale site' }, { a: 'visit', by: 'Meril', id: '6', at: '2026-10-20T15:00:00Z' }, { a: 'pin', by: 'Meril', id: '7', at: '2026-10-21T07:30:00Z' }]
  }) };
  const r = await teamMap(env, now);
  assert.deepEqual(r.people.map(p => p.name), ['Meril']);
  assert.equal(r.people[0].phone, '+254 712 000 111');
  assert.deepEqual(r.visits.map(v => v.id), ['5']);
  // "today" starts at midnight East Africa time (21:00 UTC the day before)
  assert.equal(r.since, '2026-10-20T21:00:00.000Z');
});

test('planView: needs a country; finds water tests due', async () => {
  const env = { FEED: kv({ pinlastvisit: { 7: { at: ago(30), state: 'working' } }, pinrepairs: {}, pinassign: [], 'pinvisit:7': [{ at: ago(30), quality: null }] }) };
  const feed = { projects: [site('kit', 0.05, 0.05, { id: '7' })] };
  assert.equal((await planView(env, new URLSearchParams(''), feed)).status, 400);
  const r = await planView(env, new URLSearchParams('ck=ke&days=2&per=3'), feed);
  assert.equal(r.status, 200); assert.equal(r.body.total, 1);
  assert.deepEqual(r.body.days[0].stops[0].why, ['test']);
});

test('planChosen: every chosen project is planned, grouped by distance, the rest filled with suggestions', () => {
  const c = candidates({ projects: P, visits, repairs, now: NOW, ck: 'ke' });
  // 1 (Eldoret, nothing due), 6 (Kitale) and 9 (Bungoma): far apart, so three days
  const ch = chosenStops(P, ['1', '6', '9', '10', '999'], { visits, repairs, now: NOW });
  assert.deepEqual(ch.map(x => x.id), ['1', '6', '9'], 'no location and unknown ones are not stops');
  assert.ok(ch.every(x => x.chosen) && ch[0].why.length === 0);
  const plan = planChosen(ch, c, { days: 3, per: 3 });
  const where = id => plan.days.findIndex(d => d.stops.some(s => s.id === id));
  ['1', '6', '9'].forEach(id => assert.ok(where(id) >= 0, 'chosen ' + id + ' is planned'));
  assert.equal(new Set(['1', '6', '9'].map(where)).size, 3, 'far apart: one day each');
  assert.ok(plan.days.flatMap(d => d.stops).some(s => !s.chosen), 'room left is filled with suggestions');
  plan.days.forEach(d => assert.ok(d.stops.length <= 3));
  assert.equal(new Set(plan.days.flatMap(d => d.stops.map(s => s.id))).size, plan.days.flatMap(d => d.stops).length, 'no stop twice');
  // only these projects: no suggestions at all
  const only = planChosen(ch, c, { days: 3, per: 3, only: true });
  assert.deepEqual(only.days.flatMap(d => d.stops.map(s => s.id)).sort(), ['1', '6', '9']);
  assert.equal(only.spare.length, 0);
  // more chosen than fit: more days, then more stops a day, never dropped
  const many = chosenStops(P, P.map(p => p.id), { now: NOW });
  const big = planChosen(many, [], { days: 1, per: 2 });
  assert.equal(big.days.flatMap(d => d.stops).length, many.length);
  assert.ok(big.dayCount * big.perDay >= many.length);
});

test('candidates: several areas ticked (any case); none ticked means every area', () => {
  const two = candidates({ projects: P, visits, repairs, now: NOW, ck: 'ke', areas: ['kapsaret', 'Bungoma'] });
  assert.ok(two.length > 0 && two.every(x => /^(Kapsaret|Bungoma)/.test(x.area)), two.map(x => x.area).join('|'));
  assert.ok(two.some(x => x.area === 'Bungoma') && two.some(x => x.area.startsWith('Kapsaret')));
  assert.deepEqual(candidates({ projects: P, visits, repairs, now: NOW, ck: 'ke', areas: [] }).map(x => x.id), candidates({ projects: P, visits, repairs, now: NOW, ck: 'ke' }).map(x => x.id));
  // "Eldoret" is the second part of "Kapsaret, Eldoret": not an area of its own
  assert.equal(candidates({ projects: P, visits, repairs, now: NOW, ck: 'ke', areas: ['Eldoret'] }).length, 0);
});

test('planView: areas, chosen projects (ids=…) and only=1 are checked on the server', async () => {
  const env = { FEED: kv({ pinlastvisit: { 7: { at: ago(30), state: 'working' }, 8: { at: ago(30), state: 'working' } }, pinrepairs: {}, pinassign: [], 'pinvisit:7': [{ at: ago(30), quality: { ecoli: 'neg' } }] }) };
  const many = Array.from({ length: 70 }, (_, i) => site('bun', 0.001 * i, 0, { id: String(1000 + i), area: 'Bungoma' }));
  const feed = { projects: [site('kit', 0, 0, { id: '7' }), site('kit', 0.01, 0.01, { id: '8' }), site('kit', 0.02, 0, { id: '9' }), site('kit', 0.03, 0, { id: '11', gps: null, title: 'Kitale Dispensary' })].concat(many) };
  const st = async s => (await planView(env, new URLSearchParams('ck=ke&' + s), feed)).status;
  // ids: digits only, at most 60, each one a project in the feed
  assert.equal(await st('ids=7,abc'), 400);
  assert.equal(await st('ids=7;8'), 400);
  assert.equal(await st('ids=7,,8'), 400);
  assert.equal(await st('ids=-7'), 400);
  assert.equal(await st('ids=7,12345'), 400, 'not in the feed');
  assert.equal(await st('ids=' + many.slice(0, 61).map(p => p.id).join(',')), 400, '61 ids');
  assert.equal(await st('ids=' + many.slice(0, 60).map(p => p.id).join(',')), 200, '60 ids');
  // areas: at most 20, each at most 80 characters
  assert.equal(await st('areas=' + Array.from({ length: 21 }, (_, i) => 'A' + i).join(',')), 400, '21 areas');
  assert.equal(await st('areas=' + Array.from({ length: 20 }, (_, i) => 'A' + i).join(',')), 200, '20 areas');
  assert.equal(await st('areas=' + encodeURIComponent('x'.repeat(81))), 400, 'an area of 81 characters');
  assert.equal(await st('areas=' + encodeURIComponent('x'.repeat(80))), 200);
  assert.equal(await st('areas=' + encodeURIComponent('<b>')), 400);
  // areas narrow the suggestions; none ticked means the whole country
  const ar = await planView(env, new URLSearchParams('ck=ke&days=6&per=8&areas=Kitale'), feed);
  assert.deepEqual(ar.body.days.flatMap(d => d.stops).concat(ar.body.spare).map(s => s.area).filter(a => a !== 'Kitale'), []);
  assert.equal(ar.body.total, 2, 'in Kitale: 9 (never visited) and 8 (water test due)');
  const whole = await planView(env, new URLSearchParams('ck=ke&days=6&per=8'), feed);
  assert.equal(whole.body.total, 72);
  // chosen: always planned, even with nothing due; the days filled with suggestions from the ticked areas
  const r = await planView(env, new URLSearchParams('ck=ke&days=2&per=3&areas=Kitale&ids=7,11'), feed);
  assert.equal(r.status, 200);
  const stops = r.body.days.flatMap(d => d.stops);
  assert.ok(stops.some(s => s.id === '7' && s.chosen), 'chosen 7 is planned even with nothing due');
  assert.ok(stops.some(s => s.id === '9' && !s.chosen), 'suggestions fill the day (9 was never visited)');
  assert.ok(stops.every(s => s.area === 'Kitale'), 'suggestions only from the ticked areas');
  assert.deepEqual(r.body.nogps, [{ id: '11', t: 'Kitale Dispensary' }], 'no location: listed, not dropped');
  assert.equal(r.body.chosen, 1);
  const o = await planView(env, new URLSearchParams('ck=ke&days=2&per=3&ids=7,8&only=1'), feed);
  assert.deepEqual(o.body.days.flatMap(d => d.stops.map(s => s.id)).sort(), ['7', '8']);
  assert.equal(o.body.total, 2); assert.equal(o.body.only, true); assert.equal(o.body.spare.length, 0);
  // only=1 without ids changes nothing
  assert.equal((await planView(env, new URLSearchParams('ck=ke&days=6&per=8&only=1'), feed)).body.total, 72);
});
