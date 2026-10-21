// Repair costs, people served and the two-step approval for big repairs (lib/pin-office.js, /api/admin, /api/pin).
// Run: node --test tests/*.test.mjs   (Node 22+; monday.com and KV are faked)
import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  money, estOf, approvalState, stepBlocked, canDecide, costStats, projectCosts, usersOf, latestUsers, peopleNow, peopleTrend, peopleServed, groupOf, APPROVE_DEFAULT
} from '../lib/pin-office.js';

const NOW = Date.parse('2026-10-21T10:00:00Z');
const P = [
  { id: '1', title: 'Kapsaret Borehole', ck: 'ke', type: 'Borehole well', water: true, cat: 'water', status: 'Completed', people: 1200 },
  { id: '2', title: 'Magara Well', ck: 'tz', type: 'Borehole well', water: true, cat: 'water', status: 'Completed', people: 800 },
  { id: '3', title: 'Chepkongi Primary', ck: 'ke', type: 'School', water: false, cat: 'school', status: 'Completed', people: 400 },
  { id: '4', title: 'Mzuzu Tank', ck: 'mw', type: 'Water collection', water: true, cat: 'water', status: 'Under construction', people: 900 },
  { id: '5', title: 'Paradiso Orphanage', ck: 'tz', type: 'Orphanage', water: false, cat: 'children', status: 'Completed', people: 0 }
];

test('money: shillings as typed', () => {
  assert.equal(money('12,500'), 12500); assert.equal(money(' 800 '), 800); assert.equal(money(''), null);
  assert.equal(money('-5'), null); assert.equal(money('abc'), null); assert.equal(money(99.6), 100);
});

test('approval: estimate is the higher of field estimate and budget; only above the limit', () => {
  assert.equal(estOf({ cost: 30000, budget: 60000 }), 60000);
  assert.equal(estOf({ cost: 70000, budget: null }), 70000);
  assert.equal(approvalState({ cost: 50000 }, 50000), 'none', 'at the limit is not above it');
  assert.equal(approvalState({ cost: 50001 }, 50000), 'needed');
  assert.equal(approvalState({ cost: 80000, approval: { status: 'pending', est: 80000 } }, 50000), 'pending');
  assert.equal(approvalState({ cost: 80000, approval: { status: 'approved', est: 80000 } }, 50000), 'approved');
  assert.equal(approvalState({ cost: 80000, budget: 95000, approval: { status: 'approved', est: 80000 } }, 50000), 'needed', 'a higher estimate needs a new approval');
  assert.equal(approvalState({ cost: 80000, approval: { status: 'declined', est: 80000 } }, 50000), 'declined');
});

test('approval: parts ordered (and fixed from the Office) are held back; earlier steps are not', () => {
  const big = { cost: 120000, step: 'assigned' };
  assert.equal(stepBlocked(big, 'assigned', APPROVE_DEFAULT), '');
  assert.equal(stepBlocked(big, 'parts', APPROVE_DEFAULT), 'needed');
  assert.equal(stepBlocked(big, 'fixed', APPROVE_DEFAULT), 'needed');
  assert.equal(stepBlocked({ cost: 120000, step: 'parts' }, 'fixed', APPROVE_DEFAULT), '', 'a job already past parts is not held back');
  assert.equal(stepBlocked(Object.assign({ approval: { status: 'approved', est: 120000 } }, big), 'parts', APPROVE_DEFAULT), '');
  assert.equal(stepBlocked({ cost: 4000, step: 'reported' }, 'parts', APPROVE_DEFAULT), '');
  assert.equal(stepBlocked(big, 'parts', 200000), '', 'a higher limit lets it through');
});

test('approval: the person who asked can never approve their own', () => {
  assert.equal(canDecide({ by: 'Reid Anderson' }, 'reid anderson '), false);
  assert.equal(canDecide({ by: 'Reid Anderson' }, 'Jessica'), true);
  assert.equal(canDecide({ by: '' }, ''), false, 'a shared PIN has no name to tell people apart');
});

test('costs: month, year, by country, top 10 water points, average per repair', () => {
  const rows = [
    { id: '1', rid: 'r1', at: '2026-10-02T08:00:00Z', actual: 30000 },          // this month, KE
    { id: '1', rid: 'r1', at: '2026-10-05T08:00:00Z', actual: 5000 },           // same repair, labour
    { id: '2', rid: 'r2', at: '2026-03-10T08:00:00Z', actual: 12000 },          // this year, TZ
    { id: '3', rid: 'r3', at: '2026-09-01T08:00:00Z', actual: 90000 },          // a school: not a water point
    { id: '1', rid: 'r0', at: '2025-12-20T08:00:00Z', actual: 8000 },           // last year, within 12 months
    { id: '2', rid: 'rx', at: '2025-06-01T08:00:00Z', actual: 99999 },          // older than 12 months
    { id: '9', rid: 'r9', at: '2026-10-10T08:00:00Z', actual: 0, est: 4000 }    // no money spent: ignored
  ];
  const s = costStats(rows, P, NOW);
  assert.equal(s.month, 35000);
  assert.equal(s.year, 30000 + 5000 + 12000 + 90000);
  assert.deepEqual(s.byCountry, { ke: 125000, tz: 12000 });
  assert.equal(s.sum12, 145000);
  assert.equal(s.repairs, 4, 'two entries on one repair count once');
  assert.equal(s.avg, Math.round(145000 / 4));
  assert.deepEqual(s.top.map(t => [t.id, t.sum, t.n]), [['1', 43000, 2], ['2', 12000, 1]]);
  const many = Array.from({ length: 14 }, (_, i) => ({ id: 'w' + i, rid: 'a', at: '2026-08-01T00:00:00Z', actual: 1000 * (i + 1) }));
  assert.equal(costStats(many, [], NOW).top.length, 10);
  assert.equal(costStats(many, [], NOW).top[0].sum, 14000);
});

test('costs: one project, last 12 months', () => {
  const rows = [{ rid: 'a', at: '2026-01-01T00:00:00Z', actual: 1000 }, { rid: 'a', at: '2026-01-04T00:00:00Z', actual: 500 }, { rid: 'b', at: '2026-07-01T00:00:00Z', actual: 2500 }, { rid: 'c', at: '2025-01-01T00:00:00Z', actual: 9000 }];
  assert.deepEqual(projectCosts(rows, NOW), { sum: 4000, n: 2 });
  assert.deepEqual(projectCosts([], NOW), { sum: 0, n: 0 });
});

test('people: what a visit counts', () => {
  assert.equal(usersOf({ readings: { users: 350, flow: 12 } }), 350);
  assert.equal(usersOf({ readings: { users: null }, numbers: { pupils: 610, teachers: 14 } }), 610);
  assert.equal(usersOf({ numbers: { children: 42 } }), 42);
  assert.equal(usersOf({ numbers: { patients: 120 } }), 120);
  assert.equal(usersOf({ readings: { flow: 3 } }), null);
  assert.deepEqual(latestUsers([{ at: 'b', readings: { flow: 1 } }, { at: 'a', readings: { users: 90 } }]), { n: 90, at: 'a' }, 'newest visit that counted');
});

test('people: latest visit count, else the board; completed projects or counted ones; by country and type', () => {
  const latest = { '1': { n: 1500, at: '2026-10-01' }, '4': { n: 300, at: '2026-09-01' } };
  const r = peopleNow(P, latest);
  // 1: 1500 (visit), 2: 800 (board), 3: 400 (board), 4: 300 (under construction but counted on a visit), 5: none
  assert.equal(r.total, 3000); assert.equal(r.counted, 4); assert.equal(r.measured, 2);
  assert.deepEqual(r.byCountry, { ke: 1900, tz: 800, mw: 300 });
  assert.deepEqual(r.byType, { water: 2600, school: 400 });
  assert.equal(groupOf({ type: 'Health' }), 'health'); assert.equal(groupOf({ type: 'Borehole well' }), 'water');
});

test('people: 12-month trend carries months without a snapshot, this month is live', () => {
  const hist = { '2025-08': { n: 900 }, '2026-01': { n: 1000 }, '2026-04': { n: 1400 }, '2026-10': { n: 1 } };
  const t = peopleTrend(hist, 2000, NOW);
  assert.equal(t.length, 12); assert.equal(t[0].m, '2025-11'); assert.equal(t[11].m, '2026-10');
  assert.equal(t[0].n, 900, 'carried from before the window');
  assert.equal(t[2].n, 1000); assert.equal(t[4].n, 1000); assert.equal(t[5].n, 1400); assert.equal(t[10].n, 1400);
  assert.equal(t[11].n, 2000, 'this month is the live total');
  assert.equal(peopleTrend({}, 50, NOW)[0].n, null, 'nothing known yet');
  assert.equal(peopleServed(P, {}, {}, NOW).trend[11].n, 2400);
});

// ---- the server: admin job steps, review queue, field "parts" and "fixed", snapshot ----------------
function kv(init = {}) {
  const m = new Map(Object.entries(init).map(([k, v]) => [k, JSON.stringify(v)]));
  return {
    m, get: async (k, t) => { const v = m.get(k); return v == null ? null : t === 'json' ? JSON.parse(v) : v; },
    put: async (k, v) => { m.set(k, String(v)); }, delete: async k => { m.delete(k); },
    list: async ({ prefix }) => ({ keys: [...m.keys()].filter(k => k.startsWith(prefix)).map(name => ({ name })), list_complete: true })
  };
}
const notes = [];
globalThis.fetch = async (url, o) => {
  const b = JSON.parse(o.body || '{}');
  if (/create_update/.test(b.query || '')) { notes.push(b.variables.t); return new Response(JSON.stringify({ data: { create_update: { id: String(9000 + notes.length) } } })); }
  if (/items\(ids/.test(b.query || '')) return new Response(JSON.stringify({ data: { items: [{ id: (b.variables.ids || ['1'])[0], name: (P.find(p => p.id === (b.variables.ids || [])[0]) || P[0]).title, board: { id: '9412605754' }, column_values: [{ text: '0.47, 35.25' }] }] } }));
  return new Response(JSON.stringify({ data: {} }));
};
const feed = { updated: '2026-10-20T00:00:00Z', projects: P };
function env(store) {
  store.m.set('feed:v9', JSON.stringify({ at: Date.now(), feed }));
  return { FEED: store, MONDAY_API_TOKEN: 't', PIN_USERS: 'Reid Anderson:1111, Jessica:2222, Meril:3333', PIN_ADMINS: 'Reid Anderson, Jessica' };
}
const admin = await import('../functions/api/admin.js');
const pin = await import('../functions/api/pin.js');
const req = (path, pinCode, body) => new Request('https://pin.example' + path, { method: body ? 'POST' : 'GET', headers: Object.assign({ 'X-Pin': pinCode, 'CF-Connecting-IP': '1.2.3.4' }, body ? { 'Content-Type': 'application/json', Origin: 'https://pin.example' } : {}), body: body ? JSON.stringify(body) : undefined });
const call = async (mod, path, pinCode, body) => { const r = await (body ? mod.onRequestPost : mod.onRequestGet)({ request: req(path, pinCode, body), env: E, waitUntil: p => p }); return Object.assign(await r.json(), { status: r.status }); };
const REPAIR = { state: 'notworking', by: 'Meril', at: '2026-10-18T08:00:00.000Z', t: 'Kapsaret Borehole', cost: 85000, step: 'assigned', assignee: 'Meril', parts: 'Rising main pipes' };
const store = kv({ pinrepairs: { '1': Object.assign({}, REPAIR) } });
const E = env(store);

test('server: parts ordered on a big repair waits for a second, different admin', async () => {
  // Reid tries "Parts ordered" on an KES 85,000 repair: held, and a review item is made
  let j = await call(admin, '/api/admin', '1111', { action: 'job', id: '1', step: 'parts', actual: 30000, note: 'pipes from Eldoret' });
  assert.equal(j.ok, true); assert.equal(j.held, 'parts'); assert.equal(j.asked, true);
  assert.equal(j.job.step, 'assigned'); assert.equal(j.job.approval.status, 'pending'); assert.equal(j.job.approval.by, 'Reid Anderson');
  assert.equal((await store.get('pincostidx', 'json') || []).length, 0, 'no spend recorded while held back');
  const rv = (await call(admin, '/api/admin?view=review', '1111')).items.find(x => x.kind === 'cost');
  assert.equal(rv.data.est, 85000); assert.equal(rv.by, 'Reid Anderson'); assert.equal(rv.status, 'open');
  // asking again doesn't make a second request
  j = await call(admin, '/api/admin', '1111', { action: 'job', id: '1', step: 'parts' });
  assert.equal(j.held, 'parts'); assert.equal(j.asked, false);
  assert.equal((await store.get('pinreview', 'json')).filter(x => x.kind === 'cost').length, 1);
  // the field app sees it on the repair tracker
  const snap = await call(pin, '/api/pin', '3333');
  assert.equal(snap.repairs['1'].approval.status, 'pending'); assert.equal(snap.approveAbove, 50000);
  assert.equal(snap.people, undefined, 'people served is for admins');
  // Reid can't approve his own request
  j = await call(admin, '/api/admin', '1111', { action: 'review', rid: rv.rid, decision: 'approve' });
  assert.equal(j.ok, false); assert.equal(j.status, 409); assert.equal(j.reason, 'own');
  // Jessica approves with a note: logged as approve / cost
  j = await call(admin, '/api/admin', '2222', { action: 'review', rid: rv.rid, decision: 'approve', reason: 'Quote checked' });
  assert.equal(j.ok, true);
  const job = (await store.get('pinrepairs', 'json'))['1'];
  assert.equal(job.approval.status, 'approved'); assert.equal(job.approval.dec, 'Jessica'); assert.equal(job.approval.note, 'Quote checked');
  const act = await store.get('pinact', 'json');
  assert.ok(act.some(e => e.a === 'approve' && e.k === 'cost' && e.by === 'Jessica' && e.id === '1'));
  assert.ok(act.some(e => e.a === 'ask' && e.k === 'cost' && e.by === 'Reid Anderson'));
  assert.ok(notes.some(n => /approved by Jessica \(second approval; asked by Reid Anderson\)/.test(n)));
  // now parts ordered goes through, with the money spent and a receipt
  j = await call(admin, '/api/admin', '1111', { action: 'job', id: '1', step: 'parts', actual: '30,000', what: 'Rising main pipes', receipt: true });
  assert.equal(j.ok, true); assert.equal(j.held, ''); assert.equal(j.job.step, 'parts'); assert.ok(j.updateId, 'a note id for the receipt photo');
  const rows = await store.get('pincost:1', 'json');
  assert.equal(rows.length, 1); assert.equal(rows[0].actual, 30000); assert.equal(rows[0].est, 85000); assert.equal(rows[0].by, 'Reid Anderson'); assert.equal(rows[0].receipt, true); assert.equal(rows[0].what, 'Rising main pipes');
});

test('server: the fixer marks it fixed with the actual cost; Costs and the project see it', async () => {
  const j = await call(pin, '/api/pin', '3333', { action: 'fixed', id: '1', actual: 6500, note: 'new pipes fitted' });
  assert.equal(j.ok, true);
  const rows = await store.get('pincost:1', 'json');
  assert.equal(rows.length, 2); assert.equal(rows[1].actual, 6500); assert.equal(rows[1].by, 'Meril'); assert.equal(rows[1].step, 'fixed'); assert.equal(rows[1].rid, REPAIR.at, 'same repair as the parts');
  const c = await call(admin, '/api/admin?view=costs', '2222');
  assert.equal(c.ok, true); assert.equal(c.rows.length, 2); assert.equal(c.approveAbove, 50000);
  assert.equal(c.stats.sum12, 36500); assert.equal(c.stats.repairs, 1); assert.equal(c.stats.top[0].t, 'Kapsaret Borehole');
  const one = await call(pin, '/api/pin?id=1', '3333');
  assert.equal(one.costs.sum, 36500); assert.equal(one.costs.n, 1); assert.equal(one.costs.last[0].actual, 6500);
});

test('server: field "parts" from the person on the job; others are refused; the limit is a setting', async () => {
  await store.put('pinrepairs', JSON.stringify({ '2': Object.assign({}, REPAIR, { t: 'Magara Well', cost: 20000 }) }));
  let j = await call(pin, '/api/pin', '1111', { action: 'parts', id: '2', actual: 18000 });   // Reid is an admin: allowed
  assert.equal(j.ok, true); assert.equal(j.job.step, 'parts');
  await store.put('pinrepairs', JSON.stringify({ '3': Object.assign({}, REPAIR, { t: 'Chepkongi Primary', assignee: 'Jessica', cost: 20000 }) }));
  j = await call(pin, '/api/pin', '3333', { action: 'parts', id: '3' });
  assert.equal(j.status, 403); assert.equal(j.reason, 'not-yours');
  // admins set a lower limit; Meril's next big job asks for approval from the field
  j = await call(admin, '/api/admin', '2222', { action: 'settings', approveAbove: '10,000' });
  assert.equal(j.ok, true); assert.equal(j.approveAbove, 10000);
  assert.equal((await call(pin, '/api/pin', '3333')).approveAbove, 10000);
  await store.put('pinrepairs', JSON.stringify({ '4': Object.assign({}, REPAIR, { t: 'Mzuzu Tank', cost: 15000 }) }));
  j = await call(pin, '/api/pin', '3333', { action: 'parts', id: '4', actual: 15000 });
  assert.equal(j.held, 'parts'); assert.equal(j.asked, true); assert.equal(j.job.approval.by, 'Meril');
  assert.equal((await store.get('pinrepairs', 'json'))['4'].step, 'assigned');
  // a non-admin can't change the setting
  assert.equal((await call(admin, '/api/admin', '3333', { action: 'settings', approveAbove: 1 })).status, 403);
});

test('server: a visit keeps the latest people count and this month\'s total; admins see it in the snapshot', async () => {
  const j = await call(pin, '/api/pin', '3333', { action: 'visit', id: '1', state: 'working', readings: { users: 1700 } });
  assert.equal(j.ok, true);
  await new Promise(r => setTimeout(r, 20));
  assert.deepEqual((await store.get('pinpeople', 'json'))['1'].n, 1700);
  const hist = await store.get('pinpeoplehist', 'json');
  assert.equal(hist[new Date().toISOString().slice(0, 7)].n, 1700 + 800 + 400);
  const snap = await call(pin, '/api/pin', '2222');
  assert.equal(snap.people.total, 2900); assert.equal(snap.people.measured, 1); assert.equal(snap.people.trend.length, 12);
  assert.deepEqual(snap.people.byType, { water: 2500, school: 400 });
});
