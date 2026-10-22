// node --test tests/donor-app.test.mjs — the RAFIKI Pin app in donor mode (feat-donorapp): short donor codes
// (issue, verify, rotate, revoke, rate limits), the same token as the private link, e-mail on/off, donor answers
// holding only that donor's projects, and staff endpoints refusing donor tokens.
import test from 'node:test';
import assert from 'node:assert/strict';
import { donorAdminPost, donorAdminGet, makeCode, normCode, fmtCode, findByCode, redeemCode, findByToken, CODE_ALPHA, CODE_LEN } from '../lib/donor.js';
import * as donorApi from '../functions/api/donor.js';
import * as pinApi from '../functions/api/pin.js';
import * as adminApi from '../functions/api/admin.js';
import { onRequest as middleware } from '../functions/_middleware.js';

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
const FEED = { projects: [
  { id: '101', title: 'Kapsaret Borehole', country: 'Kenya', ck: 'ke', area: 'Kapsaret', type: 'Borehole well', cat: 'water', people: 1200, year: 2023, done: '2025-10-05', status: 'Completed', photos: ['900'], gps: [0.47, 35.25] },
  { id: '102', title: 'Ndalat Primary School', country: 'Kenya', ck: 'ke', area: 'Ndalat', type: 'School', cat: 'school', people: 600, year: 2024, status: 'Completed', photos: [], gps: [0.2, 35.1] },
  { id: '999', title: 'Someone else’s well', country: 'Kenya', ck: 'ke', area: 'Elsewhere', type: 'Borehole well', status: 'Completed', photos: [], gps: [0.1, 35.0] }
] };
function env(extra) {
  const FEED_KV = kv(); FEED_KV.m.set('feed:v9', JSON.stringify({ at: Date.now(), feed: FEED }));
  FEED_KV.m.set('pinvisit:999', JSON.stringify([{ at: '2026-10-01T08:00:00Z', state: 'working', by: 'Meril', note: 'staff-only note' }]));
  FEED_KV.m.set('pinvisit:101', JSON.stringify([{ at: new Date().toISOString(), state: 'working', by: 'Meril Kiprop', note: 'gate key with chairman', readings: { users: 1500 } }]));
  return Object.assign({ FEED: FEED_KV }, extra || {});
}
const tok = link => link.split('/donor/')[1];
const post = (body, ip, token) => new Request('https://pin.example/api/donor', { method: 'POST', headers: Object.assign({ 'Content-Type': 'application/json', 'CF-Connecting-IP': ip || '1.1.1.1' }, token ? { 'X-Donor-Token': token } : {}), body: JSON.stringify(body) });
const get = (token, ip, q) => new Request('https://pin.example/api/donor' + (q || ''), { headers: { 'X-Donor-Token': token, 'CF-Connecting-IP': ip || '1.1.1.1' } });

test('codes: 10 unambiguous characters, deterministic per generation, forgiving to type', async () => {
  const a = await makeCode('k'.repeat(32), 'd1', 'v1', 1), b = await makeCode('k'.repeat(32), 'd1', 'v1', 2);
  assert.equal(a.length, CODE_LEN); assert.notEqual(a, b);
  assert.ok([...a].every(c => CODE_ALPHA.includes(c)));
  assert.ok(!/[01OILU]/.test(CODE_ALPHA), 'no look-alike characters');
  assert.equal(a, await makeCode('k'.repeat(32), 'd1', 'v1', 1));
  assert.equal(fmtCode(a), a.slice(0, 5) + '-' + a.slice(5));
  assert.equal(normCode(' ' + fmtCode(a).toLowerCase() + ' '), a);
  assert.equal(normCode(a.slice(0, 9)), ''); assert.equal(normCode(a.slice(0, 9) + 'O'), ''); assert.equal(normCode(''), '');
});

test('codes: issued with the link, shown to admins, map to the same viewer, rotate and revoke with it', async () => {
  const e = env();
  const add = await donorAdminPost(e, { action: 'donor-add', name: 'Grace Church', email: 'grace@example.org', projects: ['101', '102'] }, 'Reid');
  const code = add.body.code; assert.match(code, /^[2-9A-Z]{5}-[2-9A-Z]{5}$/);
  assert.equal(add.body.donor.viewers[0].code, code);
  // only hashes are stored
  for (const v of e.FEED.m.values()) { assert.ok(!v.includes(code.replace('-', ''))); assert.ok(!v.includes(tok(add.body.link))); }
  const id = add.body.donor.id;
  assert.equal((await donorAdminGet(e)).donors[0].viewers[0].code, code);
  const ln = await donorAdminPost(e, { action: 'donor-link', id }, 'Reid');
  assert.equal(ln.body.code, code); assert.equal(ln.body.app, 'https://pin.rafikioutreach.org/pin#donor=' + tok(add.body.link));
  // the code gives the same token as the link, so the app signs in like the link
  const r = await redeemCode(e, code.toLowerCase());
  assert.equal(r.token, tok(add.body.link)); assert.equal(r.name, 'Grace Church');
  assert.equal((await findByCode(e, code)).viewer.vid, (await findByToken(e, r.token)).viewer.vid);
  // a second viewer has their own code
  const v2 = await donorAdminPost(e, { action: 'donor-viewer-add', id, name: 'Pastor John' }, 'Reid');
  const c2 = v2.body.code; assert.notEqual(c2, code);
  assert.equal((await redeemCode(e, c2)).token, tok(v2.body.link));
  // rotating the main link changes its code; the old one stops working
  const rot = await donorAdminPost(e, { action: 'donor-rotate', id }, 'Reid');
  assert.notEqual(rot.body.code, code); assert.equal(await redeemCode(e, code), null);
  assert.equal((await redeemCode(e, rot.body.code)).token, tok(rot.body.link));
  // revoking one viewer: their code stops, the other still works
  const vid2 = v2.body.donor.viewers.find(v => v.name === 'Pastor John').vid;
  const rv = await donorAdminPost(e, { action: 'donor-revoke', id, vid: vid2 }, 'Reid');
  assert.equal(await redeemCode(e, c2), null); assert.ok(await redeemCode(e, rot.body.code));
  assert.equal(rv.body.donor.viewers.find(v => v.vid === vid2).code, '');
  // deleting the donor drops every code
  await donorAdminPost(e, { action: 'donor-remove', id }, 'Reid');
  assert.equal(await redeemCode(e, rot.body.code), null);
  assert.equal([...e.FEED.m.keys()].filter(k => k.startsWith('donorcode:')).length, 0);
});

test('codes: viewers made before codes existed get one when an admin opens Donors', async () => {
  const e = env();
  const add = await donorAdminPost(e, { action: 'donor-add', name: 'Old Donor', projects: ['101'] }, 'Reid');
  // as stored before feat-donorapp: no code hash, no index
  const list = JSON.parse(e.FEED.m.get('pindonors')); delete list[0].viewers[0].ch; e.FEED.m.set('pindonors', JSON.stringify(list));
  [...e.FEED.m.keys()].filter(k => k.startsWith('donorcode:')).forEach(k => e.FEED.m.delete(k));
  assert.equal(await redeemCode(e, add.body.code), null);
  const g = await donorAdminGet(e);
  assert.equal(g.donors[0].viewers[0].code, add.body.code); assert.equal(g.app.apk, 'https://pin.rafikioutreach.org/app/rafiki-pin.apk');
  assert.ok(await redeemCode(e, add.body.code));
});

test('POST /api/donor { code }: right code → token; wrong codes limited per connection and overall', async () => {
  const e = env();
  const add = await donorAdminPost(e, { action: 'donor-add', name: 'Grace', projects: ['101'] }, 'R');
  const ok = await donorApi.onRequestPost({ request: post({ code: add.body.code }), env: e });
  assert.equal(ok.status, 200); assert.match(ok.headers.get('Cache-Control'), /no-store/);
  assert.equal((await ok.json()).token, tok(add.body.link));
  // ten wrong codes from one connection, then even the right one waits an hour there
  for (let i = 0; i < 10; i++) { const r = await donorApi.onRequestPost({ request: post({ code: 'ZZZZZ-ZZZZ' + (i % 8 + 2) }, '2.2.2.2'), env: e }); assert.equal(r.status, 404); assert.equal((await r.json()).reason, 'code'); }
  assert.equal((await donorApi.onRequestPost({ request: post({ code: add.body.code }, '2.2.2.2'), env: e })).status, 429);
  assert.equal((await donorApi.onRequestPost({ request: post({ code: add.body.code }, '3.3.3.3'), env: e })).status, 200);
  // spread over many connections: stopped overall after 300 wrong codes in the hour
  e.FEED.m.set('rl:donorcode:all', '300');
  assert.equal((await donorApi.onRequestPost({ request: post({ code: add.body.code }, '4.4.4.4'), env: e })).status, 429);
  // nonsense
  assert.equal((await donorApi.onRequestPost({ request: new Request('https://pin.example/api/donor', { method: 'POST', body: 'x' }), env: e })).status, 400);
});

test('donor answers: only their own projects, no staff fields; revoked → signed out; e-mail on/off', async () => {
  const e = env();
  const add = await donorAdminPost(e, { action: 'donor-add', name: 'Grace', email: 'g@example.org', projects: ['101'] }, 'R');
  const t = tok(add.body.link);
  const r = await donorApi.onRequestGet({ request: get(t), env: e, waitUntil: p => p });
  assert.equal(r.status, 200);
  const raw = await r.clone().text(), j = await r.json();
  assert.deepEqual(j.projects.map(p => p.id), ['101']);
  assert.ok(!j.updates.some(u => u.pid !== '101'));
  for (const bad of ['Someone else', '999', 'Meril', 'staff-only', 'gate key', 'g@example.org', 'pindonors', '"h":', '"ch":', '"vid"']) assert.ok(!raw.includes(bad), 'leaked ' + bad);
  assert.equal(j.totals.visitsYear, 1); assert.deepEqual(j.me, { email: true, digest: true, on: true });
  // the app's background check doesn't count as opening the page
  const before = (await donorAdminGet(e)).donors[0].opens;
  e.FEED.m.set('pindonors', JSON.stringify(JSON.parse(e.FEED.m.get('pindonors')).map(d => Object.assign(d, { viewers: d.viewers.map(v => Object.assign(v, { lastOpen: null })) }))));
  await donorApi.onRequestGet({ request: get(t, '1.1.1.1', '?bg=1'), env: e, waitUntil: p => p });
  assert.equal((await donorAdminGet(e)).donors[0].opens, before);
  // e-mail updates off (= unsubscribe) and on again
  const off = await donorApi.onRequestPost({ request: post({ action: 'mail', on: false }, '1.1.1.1', t), env: e });
  assert.deepEqual((await off.json()).me, { email: true, digest: true, on: false });
  assert.ok((await donorAdminGet(e)).donors[0].viewers[0].unsub);
  const on = await donorApi.onRequestPost({ request: post({ action: 'mail', on: true }, '1.1.1.1', t), env: e });
  assert.equal((await on.json()).me.on, true); assert.equal((await donorAdminGet(e)).donors[0].viewers[0].unsub, null);
  assert.equal((await donorApi.onRequestPost({ request: post({ action: 'mail', on: true }, '1.1.1.1', 'B'.repeat(43)), env: e })).status, 404);
  // revoked: the app's next check gets "link", the same as an unknown link
  await donorAdminPost(e, { action: 'donor-revoke', id: add.body.donor.id }, 'R');
  const gone = await donorApi.onRequestGet({ request: get(t), env: e, waitUntil: p => p });
  assert.equal(gone.status, 404); assert.equal((await gone.json()).reason, 'link');
});

test('staff endpoints refuse donor tokens', async () => {
  const e = env({ MONDAY_API_TOKEN: 't', PIN_USERS: 'Reid Anderson:1111', PIN_ADMINS: 'Reid Anderson' });
  const add = await donorAdminPost(e, { action: 'donor-add', name: 'Grace', projects: ['101'] }, 'R');
  const t = tok(add.body.link), code = add.body.code.replace('-', '');
  const reqs = [
    () => new Request('https://pin.example/api/pin', { headers: { 'X-Donor-Token': t, 'CF-Connecting-IP': '5.5.5.5' } }),
    () => new Request('https://pin.example/api/pin', { headers: { 'X-Pin': t, 'CF-Connecting-IP': '5.5.5.6' } }),
    () => new Request('https://pin.example/api/pin', { headers: { 'X-Pin': code, 'CF-Connecting-IP': '5.5.5.7' } }),
    () => new Request('https://pin.example/api/pin', { headers: { Cookie: 'rfpin=' + t, 'CF-Connecting-IP': '5.5.5.8' } })
  ];
  for (const r of reqs) assert.equal((await pinApi.onRequestGet({ request: r(), env: e, waitUntil: p => p })).status, 401);
  for (const view of ['donors', 'team', 'export']) {
    const r = await adminApi.onRequestGet({ request: new Request('https://pin.example/api/admin?view=' + view, { headers: { 'X-Donor-Token': t, 'X-Pin': t, 'CF-Connecting-IP': '5.5.5.9' } }), env: e });
    assert.equal(r.status, 401, view);
  }
  const p = await adminApi.onRequestPost({ request: new Request('https://pin.example/api/admin', { method: 'POST', headers: { 'X-Donor-Token': t, 'Content-Type': 'application/json', 'CF-Connecting-IP': '5.5.5.10' }, body: JSON.stringify({ action: 'donor-link', id: add.body.donor.id }) }), env: e, waitUntil: q => q });
  assert.equal(p.status, 401);
  // and the staff PIN still works, so the 401s above are about the donor token
  assert.equal((await pinApi.onRequestGet({ request: new Request('https://pin.example/api/pin', { headers: { 'X-Pin': '1111', 'CF-Connecting-IP': '5.5.5.11' } }), env: e, waitUntil: q => q })).status, 200);
});

test('a donor link on pin.rafikioutreach.org opens the app in donor mode; others are unchanged', async () => {
  const t = 'Q7mF2kLx9PzR4tYc8VbN1sWq6HjD3gUe5AoKi0ZlM_x';
  const r = await middleware({ request: new Request('https://pin.rafikioutreach.org/donor/' + t), next: async () => new Response('page'), env: {} });
  assert.equal(r.status, 302); assert.equal(r.headers.get('Location'), 'https://pin.rafikioutreach.org/pin#donor=' + t);
  const other = await middleware({ request: new Request('https://pin.rafikioutreach.org/about'), next: async () => new Response('page'), env: {} });
  assert.equal(other.headers.get('Location'), 'https://pin.rafikioutreach.org/pin');
  // the website's own donor links keep working as before
  const www = await middleware({ request: new Request('https://www.rafikioutreach.org/donor/' + t), next: async () => new Response('donor page', { headers: { 'Content-Type': 'text/plain' } }), env: {} });
  assert.equal(await www.text(), 'donor page');
});
