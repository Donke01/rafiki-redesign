// Group trips (volunteers and donors): /api/group-trips (admins only), the group's shared page (/api/trip-share, /pin-trip),
// privacy (no medical or diet notes, e-mails, phones or payments on the shared page; passport numbers never stored),
// totals in USD and KES, reminders with Preview + Send, the public list for trips.html and donor mode.
// Run: node --test tests/*.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import * as api from '../functions/api/group-trips/[[path]].js';
import * as shareApi from '../functions/api/trip-share.js';
import * as pinTrip from '../functions/pin-trip.js';
import { hasPassportNumber, totals, defaultExpect } from '../lib/group-trips.js';

function kv(init = {}) {
  const m = new Map(Object.entries(init).map(([k, v]) => [k, JSON.stringify(v)]));
  return { m, get: async (k, t) => { const v = m.get(k); return v == null ? null : t === 'json' ? JSON.parse(v) : v; }, put: async (k, v) => { m.set(k, String(v)); }, delete: async k => { m.delete(k); } };
}
const FX = { rates: { USD: 1, KES: 129.2, GBP: 0.77, EUR: 0.92, TZS: 2450, UGX: 3700, MWK: 1735, CAD: 1.38, AUD: 1.52 }, at: '2026-10-26', got: Date.now() };
const sent = [];
globalThis.fetch = async (u, o) => {
  if (String(u).includes('resend.com/emails/batch')) { const items = JSON.parse(o.body); sent.push(...items); return Response.json({ data: items.map((_, i) => ({ id: 'm' + i })) }); }
  return new Response('down', { status: 503 });
};
const env = () => ({ FEED: kv({ 'fx:usd': FX }), MONDAY_API_TOKEN: 't', PIN_USERS: 'Jessica:2222, Meril:3333', PIN_ADMINS: 'Jessica', RESEND_API_KEY: 'k', ALERT_FROM: 'trips@example.org' });
async function call(mod, E, path, pin, body, headers) {
  const url = new URL('https://pin.example' + path), segs = url.pathname.split('/').filter(Boolean).slice(2);
  const h = Object.assign({ 'CF-Connecting-IP': '10.1.1.1' }, pin ? { 'X-Pin': pin } : {}, body ? { 'Content-Type': 'application/json', Origin: 'https://pin.example' } : {}, headers || {});
  const r = await (body ? mod.onRequestPost : mod.onRequestGet)({ request: new Request(url, { method: body ? 'POST' : 'GET', headers: h, body: body ? JSON.stringify(body) : undefined }), env: E, params: { path: segs } });
  const ct = r.headers.get('Content-Type') || '';
  return Object.assign(ct.includes('json') ? await r.json() : { text: await r.text() }, { status: r.status });
}
const TRIP = { name: 'Kenya water trip, July 2027', from: '2027-07-03', to: '2027-07-14', country: 'ke', destination: 'Kitale, Trans Nzoia', summary: 'Help drill a borehole and visit schools.', leaders: ['Jessica'],
  emergency: [{ name: 'Jessica', phone: '+254 712 000 111', role: 'Trip leader' }] };
async function setup(E) {
  let r = await call(api, E, '/api/group-trips', '2222', TRIP);
  assert.equal(r.status, 201, JSON.stringify(r));
  let t = r.trip;
  const add = async (action, body) => { const x = await call(api, E, '/api/group-trips/' + t.id, '2222', Object.assign({ action, v: t.v }, body)); assert.equal(x.status, 200, action + ' ' + JSON.stringify(x)); t = x.trip; return x; };
  await add('participant-add', { participant: { name: 'Sam Lee', email: 'Sam@Example.org', phone: '+1 555 010 2030', role: 'volunteer', diet: 'vegetarian, no nuts', medical: 'asthma — inhaler in bag', passportReminder: true, passportExpiry: '2027-02' } });
  await add('participant-add', { participant: { name: 'Ana Ruiz', email: 'ana@example.org', role: 'donor', medical: 'diabetic' } });
  await add('item-add', { item: { date: '2027-07-03', kind: 'flight', title: 'Fly to Eldoret', start: '12:40', end: '13:40', flight: { airline: 'Kenya Airways', number: 'kq 640', from: 'NBO', to: 'EDL', depart: '12:40', arrive: '13:40', checkIn: '11:40' }, location: { name: 'Eldoret Intl Airport', lat: 0.4045, lng: 35.2389 }, remind: { before: '24h' } } });
  await add('item-add', { item: { date: '2027-07-05', kind: 'project', title: 'Drilling day', projectId: '104', notes: 'Wear old clothes.', location: { name: 'Bishop Luge', lat: 0.95, lng: 34.95 } } });
  await add('item-add', { item: { date: '2027-07-03', kind: 'lodging', title: 'Check in', lodging: { name: 'Boma Inn', address: 'Eldoret', checkIn: '15:00', checkOut: '10:00', contact: '+254 700 000 000' } } });
  await add('cost-add', { cost: { label: 'Lodging', amount: 450, currency: 'USD', per: 'person' } });
  await add('cost-add', { cost: { label: 'Bus hire', amount: 129200, currency: 'KES', per: 'group' } });
  await add('payment-add', { payment: { pid: 'p1', amount: 300, currency: 'USD', at: '2027-03-01' } });
  return t;
}

test('group trips: admins only; details, group, items, costs and payments; totals in USD and KES', async () => {
  const E = env();
  assert.equal((await call(api, E, '/api/group-trips', '3333')).reason, 'not-admin');
  assert.equal((await call(api, E, '/api/group-trips', '3333', TRIP)).reason, 'not-admin');
  const t = await setup(E);
  assert.match(t.id, /^gt_/);
  assert.equal(t.participants[0].email, 'sam@example.org'); assert.equal(t.participants[0].medical, 'asthma — inhaler in bag');   // admins see it
  assert.equal(t.items[0].flight.number, 'KQ 640');
  assert.match(t.packing, /Passport/); assert.match(t.expect, /wwwnc\.cdc\.gov\/travel\/destinations\/traveler\/none\/kenya/);
  // totals: 450 USD a person + 129,200 KES / 2 people = 450 + 500 USD
  assert.equal(t.totals.participants, 2);
  assert.equal(t.totals.perPerson.USD, 950); assert.equal(t.totals.perPerson.KES, Math.round(950 * 129.2));
  assert.equal(t.totals.group.USD, 1900);
  assert.equal(t.totals.paid.p1.USD, 300); assert.equal(t.totals.due.p1.USD, 650); assert.equal(t.totals.due.p2.USD, 950);
  assert.deepEqual(t.totals.fx, { at: '2026-10-26', USDKES: 129.2 });
  // refusals
  assert.equal((await call(api, E, '/api/group-trips/' + t.id, '2222', { action: 'item-add', item: { date: '2027-08-01', title: 'Late' } })).why, 'outside-trip');
  assert.equal((await call(api, E, '/api/group-trips/' + t.id, '2222', { action: 'cost-add', cost: { amount: 5, currency: 'XYZ' } })).reason, 'bad-currency');
  assert.equal((await call(api, E, '/api/group-trips/' + t.id, '2222', { action: 'participant-add', participant: { name: 'X', email: 'not-an-email' } })).reason, 'bad-email');
  assert.equal((await call(api, E, '/api/group-trips/' + t.id, '2222', { action: 'update', v: 1, name: 'Old' })).reason, 'conflict');
  // without rates: totals stay per currency, no made-up conversion
  const t2 = totals({ participants: [{ pid: 'p1', role: 'volunteer' }], costs: [{ amount: 10, currency: 'USD', per: 'person' }], payments: [] }, null);
  assert.equal(t2.perPerson.USD, undefined); assert.deepEqual(t2.perPerson.byCurrency, { USD: 10 }); assert.equal(t2.fx, null);
});
test('group trips: passport numbers are never stored', async () => {
  const E = env();
  const t = await setup(E);
  const add = p => call(api, E, '/api/group-trips/' + t.id, '2222', { action: 'participant-add', participant: p });
  assert.equal((await add({ name: 'Kim', passportNumber: 'X1234567' })).reason, 'passport-number');
  assert.equal((await add({ name: 'Kim', passport_no: 'X1234567' })).reason, 'passport-number');
  assert.equal((await add({ name: 'Kim', medical: 'Passport no. X1234567 in safe' })).reason, 'passport-number');
  assert.equal((await add({ name: 'Kim', passportExpiry: 'X1234567' })).reason, 'bad-date');
  assert.equal((await call(api, E, '/api/group-trips/' + t.id, '2222', { action: 'item-add', item: { date: '2027-07-04', title: 'Visa', notes: 'passport number: AB123456' } })).reason, 'passport-number');
  assert.equal((await add({ name: 'Kim', passportReminder: true, passportExpiry: '2028-01-31' })).status, 200);
  assert.equal(hasPassportNumber({ notes: 'Bring your passport and 2 photos' }), false);
  const all = [...E.FEED.m.values()].join(' ');
  assert.ok(!/X1234567|AB123456/.test(all), 'never written to storage');
});
test('group trips: the shared page shows the plan, packing, what to expect, emergency contacts and map — nothing private', async () => {
  const E = env();
  let t = await setup(E);
  const s = await call(api, E, '/api/group-trips/' + t.id, '2222', { action: 'share' });
  assert.match(s.url, /^https:\/\/pin\.example\/pin-trip\?t=[A-Za-z0-9_-]{43}$/);
  const r = await call(shareApi, E, '/api/trip-share?t=' + s.token);
  assert.equal(r.kind, 'group');
  const g = r.trip;
  assert.equal(g.name, TRIP.name); assert.deepEqual(g.days.map(d => d.date), ['2027-07-03', '2027-07-05']);
  assert.equal(g.days[0].items[0].flight.number, 'KQ 640'); assert.equal(g.days[1].items[0].projectUrl, 'https://pin.example/project/104');
  assert.equal(g.map.length, 2); assert.equal(g.emergency[0].phone, '+254 712 000 111');
  assert.match(g.packing, /Passport/); assert.match(g.expect, /Malaria/);
  const json = JSON.stringify(r);
  for (const bad of ['asthma', 'diabetic', 'vegetarian', 'sam@example.org', 'ana@example.org', '555 010', 'Sam Lee', 'Ana Ruiz', 'passport', 'payments', '"medical"', '"diet"', '+254 700 000 000', '2027-02'])
    assert.ok(!json.toLowerCase().includes(bad.toLowerCase()) || (bad === 'passport' && /Passport \(valid/.test(json)), 'leaks ' + bad);
  // the page itself
  const page = await call(pinTrip, E, '/pin-trip?t=' + s.token);
  assert.equal(page.status, 200);
  assert.match(page.text, /<h1>Kenya water trip, July 2027<\/h1>/); assert.match(page.text, /Packing list/); assert.match(page.text, /What to expect/); assert.match(page.text, /Emergency contacts/);
  assert.match(page.text, /tel:\+254712000111/); assert.match(page.text, /id="map"/); assert.match(page.text, /Add to home screen/);
  for (const bad of ['asthma', 'diabetic', 'vegetarian', 'sam@example.org', 'Sam Lee']) assert.ok(!page.text.includes(bad), 'page leaks ' + bad);
  // live: an admin's change shows up
  t = (await call(api, E, '/api/group-trips/' + t.id, '2222')).trip;
  await call(api, E, '/api/group-trips/' + t.id, '2222', { action: 'item-add', v: t.v, item: { date: '2027-07-06', kind: 'safari', title: 'Safari day' } });
  assert.ok((await call(shareApi, E, '/api/trip-share?t=' + s.token)).trip.days.some(d => d.items.some(i => i.title === 'Safari day')));
});
test('group trips: reminders with Preview + Send, the public list and donor mode', async () => {
  const E = env();
  let t = await setup(E);
  await call(api, E, '/api/group-trips/' + t.id, '2222', { action: 'share' });
  t = (await call(api, E, '/api/group-trips/' + t.id, '2222')).trip;
  const p = await call(api, E, '/api/group-trips/' + t.id, '2222', { action: 'remind', iid: 'i1' });
  assert.equal(p.to, 2); assert.equal(p.missing, 0);
  assert.equal(p.preview.subject, 'Reminder: Fly to Eldoret — Sat 3 Jul, 12:40');
  assert.match(p.preview.text, /^Hi Sam, a reminder for Kenya water trip, July 2027:/); assert.match(p.preview.text, /check in by 11:40/);
  assert.equal(sent.length, 0, 'a preview sends nothing');
  const r = await call(api, E, '/api/group-trips/' + t.id, '2222', { action: 'remind', iid: 'i1', send: true, v: t.v });
  assert.equal(r.sent, 2); assert.deepEqual(sent.map(m => m.to[0]).sort(), ['ana@example.org', 'sam@example.org']);
  assert.match(sent.find(m => m.to[0] === 'ana@example.org').text, /^Hi Ana,/);
  assert.ok(!sent.some(m => /asthma|diabetic/.test(m.text + m.html)));
  // public on the website only when marked public
  assert.deepEqual((await call(shareApi, E, '/api/trip-share?public=1')).trips, []);
  t = (await call(api, E, '/api/group-trips/' + t.id, '2222')).trip;
  await call(api, E, '/api/group-trips/' + t.id, '2222', { action: 'update', v: t.v, public: true, donors: ['d_abc'] });
  const pub = await call(shareApi, E, '/api/trip-share?public=1');
  assert.deepEqual(pub.trips, [{ name: TRIP.name, from: '2027-07-03', to: '2027-07-14', country: 'ke', summary: TRIP.summary, contact: 'info@rafikioutreach.org' }]);
  const { forDonor } = await import('../lib/group-trips.js');
  const mine = await forDonor(E, 'd_abc', 'https://pin.example');
  assert.equal(mine.length, 1); assert.ok(!JSON.stringify(mine).includes('asthma')); assert.match(mine[0].url, /pin-trip\?t=/);
  assert.deepEqual(await forDonor(E, 'd_other', 'https://pin.example'), []);
  assert.match(defaultExpect('mw'), /traveler\/none\/malawi/);
});
