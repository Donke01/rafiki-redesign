// node --test tests/donor-tags.test.mjs — projects by donor (dnr-tags): the board's "Donors" dropdown
// (dropdown_mm8j9drr) read into the feed as `donors`, kept out of everything public (website feed, share pages,
// donor pages, photos), given to admins in /api/pin, and the donor record remembering which tags it came from.
import test from 'node:test';
import assert from 'node:assert/strict';
import { buildFeed, parseDonors, dropdownLabels, publicFeed, COL } from '../lib/monday.js';
import { donorAdminPost, donorAdminGet } from '../lib/donor.js';

function kv() {
  const m = new Map();
  return {
    m,
    async get(k, t) { const v = m.has(k) ? m.get(k) : null; return v == null ? null : t === 'json' ? JSON.parse(v) : v; },
    async put(k, v) { m.set(k, String(v)); },
    async delete(k) { m.delete(k); },
    async list({ prefix } = {}) { return { keys: [...m.keys()].filter(k => !prefix || k.startsWith(prefix)).map(name => ({ name })), list_complete: true }; }
  };
}
// made-up donors, so the private names of real ones are not kept in the code
const A = 'Grace Valley Church', B = 'Mwangi & Otieno Family', C = 'Kito Foundation';
const LABELS = [{ id: 0, name: A }, { id: 1, name: B }, { id: 2, name: C }];
const cv = (id, text, value, extra) => Object.assign({ id, text, value: value == null ? null : JSON.stringify(value) }, extra || {});
function item(id, name, donorCv) {
  const cols = [cv(COL.status, 'Completed/Operational'), cv(COL.country, 'Kenya', { countryCode: 'KE', countryName: 'Kenya' }), cv(COL.type, 'Water (Drilling)'),
    cv(COL.village, 'Kapsaret'), cv(COL.people, '1200'), cv(COL.photos, '', { files: [{ assetId: 900 + Number(id.slice(-1)), name: 'a.jpg' }] }), cv(COL.gps, '0.47, 35.25')];
  if (donorCv) cols.push(donorCv);
  return { id, name, column_values: cols };
}

test('parseDonors: dropdown labels, ids with the column labels, the write format, text, empty', () => {
  const labels = dropdownLabels({ id: COL.donors, settings_str: JSON.stringify({ labels: LABELS }) });
  assert.deepEqual(labels, { 0: A, 1: B, 2: C });
  // the API's DropdownValue answer (… on DropdownValue { values { label } })
  assert.deepEqual(parseDonors({ text: A + ', ' + B, value: '{"ids":[0,1]}', values: [{ id: '0', label: A }, { id: '1', label: B }] }), [A, B]);
  // only ids in `value`: names from the column's labels (a name with a comma stays whole)
  assert.deepEqual(parseDonors({ text: 'x', value: JSON.stringify({ ids: [2, 0], changed_at: '2026-10-28T09:12:23Z' }) }, labels), [C, A]);
  assert.deepEqual(parseDonors({ text: '', value: '{"ids":[3]}' }, { 3: 'Smith, John and Ann' }), ['Smith, John and Ann']);
  // the write format ({ labels: [...] })
  assert.deepEqual(parseDonors({ value: JSON.stringify({ labels: [B, ' ' + A + ' '] }) }), [B, A]);
  // ids without known labels: falls back to the text
  assert.deepEqual(parseDonors({ text: A + ', ' + C, value: '{"ids":[0,2]}' }), [A, C]);
  assert.deepEqual(parseDonors({ text: B, value: null }), [B]);
  // empty, cleared, missing
  assert.deepEqual(parseDonors({ text: '', value: null }), []);
  assert.deepEqual(parseDonors({ text: '', value: '{"ids":[]}' }, labels), []);
  assert.deepEqual(parseDonors({ text: null, value: null, values: [] }), []);
  assert.deepEqual(parseDonors(undefined), []);
  // tidy: no doubles (any case), no markup, no blank names
  assert.deepEqual(parseDonors({ values: [{ label: A }, { label: A.toUpperCase() }, { label: '<b>X</b>' }, { label: '  ' }] }), [A, 'bX/b']);
});

const ITEMS = [
  item('101', 'Kapsaret Borehole', cv(COL.donors, A + ', ' + B, { ids: [0, 1] }, { values: [{ label: A }, { label: B }] })),
  item('102', 'Ndalat Well', cv(COL.donors, C, { ids: [2] })),
  item('103', 'Sigowet Well', cv(COL.donors, '', null)),
  item('104', 'Chepkongi Well')
];
const COLUMNS = [{ id: COL.donors, title: 'Donors', type: 'dropdown', settings_str: JSON.stringify({ labels: LABELS }) }];
const FEED = buildFeed(COLUMNS, ITEMS);

test('buildFeed: each tagged project carries donors; untagged ones none', () => {
  const by = Object.fromEntries(FEED.projects.map(p => [p.id, p]));
  assert.deepEqual(by['101'].donors, [A, B]);
  assert.deepEqual(by['102'].donors, [C]);
  assert.equal(by['103'].donors, undefined);
  assert.equal(by['104'].donors, undefined);
  // publicFeed: the same projects, no donors, the original untouched
  const pub = publicFeed(FEED);
  assert.equal(pub.projects.length, FEED.projects.length);
  assert.ok(pub.projects.every(p => !('donors' in p)));
  assert.deepEqual(FEED.projects.find(p => p.id === '101').donors, [A, B]);
  assert.equal(publicFeed(FEED), pub, 'kept, not copied on every request');
  for (const n of [A, B, C]) assert.ok(!JSON.stringify(pub).includes(n));
});

// every public reader of the feed, with the tagged feed in the cache (the KV copy has the donors in it)
function env(extra) {
  const store = kv(); store.m.set('feed:v9', JSON.stringify({ at: Date.now(), feed: FEED }));
  return Object.assign({ FEED: store, MONDAY_API_TOKEN: 't', PIN_USERS: 'Reid Anderson:1111, Meril:3333', PIN_ADMINS: 'Reid Anderson' }, extra || {});
}
const E = env();
const noNames = (s, where) => { for (const n of [A, B, C, 'Mwangi &amp; Otieno', 'donors']) assert.ok(!s.includes(n), where + ' must not include ' + n); };

test('public: /api/projects, /project/<id>, /p/<id>, /api/photo and the donor page never show donors', async () => {
  const projects = await import('../functions/api/projects.js');
  const r = await projects.onRequestGet({ env: E, waitUntil: () => {} });
  const body = await r.text();
  assert.equal(JSON.parse(body).projects.length, 4);
  noNames(body, '/api/projects');

  const share = await import('../functions/project/[id].js');
  for (const id of ['101', '102']) {
    const h = await (await share.onRequestGet({ env: E, params: { id }, request: new Request('https://example.org/project/' + id), waitUntil: () => {} })).text();
    assert.match(h, /og:title/); noNames(h, '/project/' + id);
  }
  const card = await import('../functions/p/[slug].js');
  const c = await card.onRequestGet({ env: E, params: { slug: '101-kapsaret-borehole' }, request: new Request('https://example.org/p/101-kapsaret-borehole'), waitUntil: () => {} });
  assert.equal(c.status, 200); noNames(await c.text(), '/p/101');

  // a donor's private page: their own projects, nobody's donor tags (not even their own)
  const add = await donorAdminPost(E, { action: 'donor-add', name: 'Someone', projects: ['101', '102'], mondayDonors: [A] }, 'Reid');
  const token = add.body.link.split('/donor/')[1];
  const donor = await import('../functions/api/donor.js');
  const d = await donor.onRequestGet({ request: new Request('https://pin.example/api/donor', { headers: { 'X-Donor-Token': token, 'CF-Connecting-IP': '9.9.9.9' } }), env: E, waitUntil: () => {} });
  const dv = await d.text();
  assert.equal(JSON.parse(dv).projects.length, 2);
  noNames(dv, '/api/donor'); assert.ok(!dv.includes('mondayDonors') && !dv.includes('mondayKnown'));
});

test('public: the cached feed handed to any default reader has no donors', async () => {
  const { getFeed } = await import('../lib/feed-cache.js');
  const pub = await getFeed(E);
  assert.ok(pub.projects.every(p => !('donors' in p)));
  const team = await getFeed(E, null, { donors: true });
  assert.deepEqual(team.projects.find(p => p.id === '101').donors, [A, B]);
});

test('/api/pin: admins get donors on each project, the field team does not', async () => {
  const pin = await import('../functions/api/pin.js');
  const get = async code => (await pin.onRequestGet({ request: new Request('https://pin.example/api/pin', { headers: { 'X-Pin': code, 'CF-Connecting-IP': '1.2.3.' + code.slice(0, 1) } }), env: E, waitUntil: p => p })).json();
  const a = await get('1111');
  assert.equal(a.admin, true);
  assert.deepEqual(a.projects.find(p => p.id === '101').donors, [A, B]);
  assert.deepEqual(a.projects.find(p => p.id === '102').donors, [C]);
  assert.equal(a.projects.find(p => p.id === '104').donors, undefined);
  const f = await get('3333');
  assert.equal(f.admin, false);
  assert.ok(f.projects.length === 4 && f.projects.every(p => !('donors' in p)));
  noNames(JSON.stringify(f), '/api/pin (field)');
});

test('donor record: remembers the monday.com tags and the tagged projects already offered', async () => {
  const e = env();
  const add = await donorAdminPost(e, { action: 'donor-add', name: A + ' and ' + B, projects: ['101', '102'], mondayDonors: [A, B, A.toLowerCase(), '', '<i>'], mondayKnown: ['101', '102', 'x'] }, 'Reid');
  assert.deepEqual(add.body.donor.mondayDonors, [A, B, 'i']);
  assert.deepEqual(add.body.donor.mondayKnown, ['101', '102']);
  const id = add.body.donor.id;
  // "Update from monday.com": newly tagged projects added and remembered
  const up = await donorAdminPost(e, { action: 'donor-update', id, projects: ['101', '102', '104'], mondayKnown: ['101', '102', '104', '103'] }, 'Reid');
  assert.deepEqual(up.body.donor.projects, ['101', '102', '104']);
  assert.deepEqual(up.body.donor.mondayKnown, ['101', '102', '104', '103']);
  assert.deepEqual(up.body.donor.mondayDonors, [A, B, 'i'], 'left as it was when not sent');
  // unlinked from monday.com
  const off = await donorAdminPost(e, { action: 'donor-update', id, mondayDonors: [] }, 'Reid');
  assert.deepEqual(off.body.donor.mondayDonors, []);
  // older records (no fields) read as empty lists
  const g = await donorAdminGet(e);
  assert.ok(g.donors.every(d => Array.isArray(d.mondayDonors) && Array.isArray(d.mondayKnown)));
  const old = await donorAdminPost(e, { action: 'donor-add', name: 'Plain donor', projects: ['103'] }, 'Reid');
  assert.deepEqual(old.body.donor.mondayDonors, []); assert.deepEqual(old.body.donor.mondayKnown, []);
});
