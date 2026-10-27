// Profile photos: /api/team-photo (functions/api/team-photo.js, lib/team-photo.js) and where the URL shows up
// (/api/pin, /api/pin?team=1, /api/admin?view=team|teammap, the chat roster).
// Run: node --test tests/*.test.mjs
import test from 'node:test';
import assert from 'node:assert/strict';
import { onRequestGet, onRequestPost, onRequestDelete } from '../functions/api/team-photo.js';
import { onRequestGet as pinGet } from '../functions/api/pin.js';
import { onRequestGet as adminGet, onRequestPost as adminPost } from '../functions/api/admin.js';
import { roster } from '../lib/chat.js';
import { photoUrl } from '../lib/team-photo.js';

function kv() {
  const m = new Map();
  return {
    m,
    async get(k, t) { const v = m.has(k) ? m.get(k) : null; return v == null ? null : t === 'json' ? JSON.parse(v) : v; },
    async put(k, v) { m.set(k, String(v)); },
    async delete(k) { m.delete(k); },
    async list(o) { const p = (o && o.prefix) || ''; return { keys: [...m.keys()].filter(k => k.startsWith(p)).map(name => ({ name })), list_complete: true }; }
  };
}
globalThis.fetch = async () => Response.json({ data: { items: [] } });
function setup(extra) {
  const store = kv();
  store.m.set('feed:v9', JSON.stringify({ at: Date.now(), feed: { projects: [] } }));
  store.m.set('pinteam', JSON.stringify([{ name: 'Meril', home: 'ke' }, { name: 'Bertin', home: 'tz' }]));
  const sync = [];
  const env = Object.assign({ FEED: store, MONDAY_API_TOKEN: 't', PIN_USERS: 'Jessica:2222, Meril:3333, Grace:4444:ug', PIN_ADMINS: 'Jessica', PIN_CODE: '9999',
    CHAT: { async fetch(r) { sync.push(r.url); return Response.json({ ok: true }); } } }, extra || {});
  return { env, store, sync };
}
const JPEG = new Uint8Array([0xff, 0xd8, 0xff, 0xe0, 1, 2, 3, 4, 5, 0xff, 0xd9]);
const U = (q) => 'https://pin.example/api/team-photo' + (q || '');
function req(method, q, pin, opts) {
  const o = opts || {};
  const h = Object.assign({ 'CF-Connecting-IP': '1.2.3.4' }, pin ? { 'X-Pin': pin } : {}, method !== 'GET' ? { Origin: 'https://pin.example' } : {}, o.headers || {});
  return new Request(U(q), { method, headers: h, body: o.body });
}
const waits = [];
const ctx = (env, request) => ({ env, request, waitUntil: p => waits.push(p) });
const post = (env, q, pin, body, type) => onRequestPost(ctx(env, req('POST', q, pin, { body, headers: type ? { 'Content-Type': type } : {} })));
const get = (env, q, pin, headers) => onRequestGet(ctx(env, req('GET', q, pin, { headers })));

test('you set your own photo (raw JPEG), get a versioned URL, and anyone signed in can load it with caching', async () => {
  const { env, store, sync } = setup();
  const r = await post(env, '', '3333', JPEG, 'image/jpeg');
  assert.equal(r.status, 200);
  const j = await r.json();
  assert.equal(j.ok, true);
  assert.equal(j.name, 'Meril');
  assert.match(j.photo, /^\/api\/team-photo\?name=Meril&v=[a-z0-9]+$/);
  const v = new URL('https://x' + j.photo).searchParams.get('v');
  assert.deepEqual(JSON.parse(store.m.get('teamphotos')), { meril: { n: 'Meril', v } });
  await Promise.all(waits.splice(0));
  assert.ok(sync.some(u => u.endsWith('/sync')), 'the chat worker is told straight away');
  // someone else (Grace) loads it: the bytes, an ETag, immutable for the current version
  const g = await get(env, '?name=Meril&v=' + v, '4444');
  assert.equal(g.status, 200);
  assert.equal(g.headers.get('Content-Type'), 'image/jpeg');
  assert.equal(g.headers.get('ETag'), '"' + v + '"');
  assert.match(g.headers.get('Cache-Control'), /immutable/);
  assert.deepEqual(new Uint8Array(await g.arrayBuffer()), JPEG);
  // an old version: the current photo, not cached for long
  const old = await get(env, '?name=meril&v=zzz', '4444');
  assert.equal(old.status, 200);
  assert.match(old.headers.get('Cache-Control'), /no-cache/);
  // If-None-Match → 304
  const nm = await get(env, '?name=Meril&v=' + v, '4444', { 'If-None-Match': '"' + v + '"' });
  assert.equal(nm.status, 304);
  // nobody else has one
  assert.equal((await get(env, '?name=Bertin', '4444')).status, 404);
});

test('multipart upload works too; a new photo gets a new version', async () => {
  const { env } = setup();
  const a = await (await post(env, '', '4444', JPEG, 'image/jpeg')).json();
  const fd = new FormData(); fd.append('file', new Blob([JPEG], { type: 'image/jpeg' }), 'me.jpg');
  const b = await (await onRequestPost(ctx(env, new Request(U(), { method: 'POST', headers: { 'X-Pin': '4444', Origin: 'https://pin.example' }, body: fd })))).json();
  assert.equal(b.ok, true);
  assert.notEqual(a.photo, b.photo);
});

test('only yourself, or an admin for anyone on the team', async () => {
  const { env } = setup();
  const r = await post(env, '?name=Bertin', '3333', JPEG, 'image/jpeg');
  assert.equal(r.status, 403);
  assert.equal((await r.json()).reason, 'forbidden');
  const ok = await post(env, '?name=bertin', '2222', JPEG, 'image/jpeg');
  assert.equal(ok.status, 200);
  assert.equal((await ok.json()).name, 'Bertin');
  const nobody = await post(env, '?name=Stranger', '2222', JPEG, 'image/jpeg');
  assert.equal(nobody.status, 404);
  assert.equal((await nobody.json()).reason, 'no-person');
  // admin removes it (DELETE), Meril can't remove Bertin's
  assert.equal((await onRequestDelete(ctx(env, req('DELETE', '?name=Bertin', '3333')))).status, 403);
  const d = await onRequestDelete(ctx(env, req('DELETE', '?name=Bertin', '2222')));
  assert.deepEqual(await d.json(), { ok: true, name: 'Bertin', photo: '' });
  assert.equal((await get(env, '?name=Bertin', '4444')).status, 404);
});

test('{ remove: true } removes your own; removing nothing is fine', async () => {
  const { env, store } = setup();
  await post(env, '', '3333', JPEG, 'image/jpeg');
  const r = await post(env, '', '3333', JSON.stringify({ remove: true }), 'application/json');
  assert.deepEqual(await r.json(), { ok: true, name: 'Meril', photo: '' });
  assert.equal(store.m.has('teamphoto:meril'), false);
  assert.deepEqual(JSON.parse(store.m.get('teamphotos')), {});
  assert.equal((await post(env, '', '3333', JSON.stringify({ remove: true }), 'application/json')).status, 200);
});

test('refused: signed out, wrong PIN, shared team PIN, donors, blocked, cross-site, too big, wrong type, too many', async () => {
  const { env, store } = setup();
  assert.equal((await post(env, '', '', JPEG, 'image/jpeg')).status, 401);
  assert.equal((await post(env, '', '1111', JPEG, 'image/jpeg')).status, 401);
  const shared = await post(env, '', '9999', JPEG, 'image/jpeg');
  assert.equal(shared.status, 403); assert.equal((await shared.json()).reason, 'named-only');
  const donor = await onRequestGet(ctx(env, req('GET', '?name=Meril', '3333', { headers: { 'X-Donor-Token': 'abc' } })));
  assert.equal(donor.status, 403); assert.equal((await donor.json()).reason, 'donor');
  assert.equal((await get(env, '?name=Meril', '')).status, 401);
  store.m.set('pinguard', JSON.stringify({ meril: { blocked: { at: 'x', by: 'Jessica' } } }));
  assert.equal((await post(env, '', '3333', JPEG, 'image/jpeg')).status, 401);
  assert.equal((await get(env, '?name=Grace', '3333')).status, 401);
  store.m.delete('pinguard');
  const x = await onRequestPost(ctx(env, req('POST', '', '3333', { body: JPEG, headers: { 'Content-Type': 'image/jpeg', Origin: 'https://evil.example' } })));
  assert.equal(x.status, 403);
  const big = await post(env, '', '3333', new Uint8Array(300 * 1024 + 1), 'image/jpeg');
  assert.equal(big.status, 413);
  assert.equal((await post(env, '', '3333', JPEG, 'image/gif')).status, 415);
  assert.equal((await post(env, '', '3333', new Uint8Array(0), 'image/jpeg')).status, 400);
  let last;
  for (let i = 0; i < 31; i++) last = await post(env, '', '4444', JPEG, 'image/jpeg');
  assert.equal(last.status, 429);
});

test('the URL shows up in /api/pin (photos, me.photo), ?team=1, Office › Team, the team map and the chat roster', async () => {
  const { env, store } = setup();
  const mine = (await (await post(env, '', '3333', JPEG, 'image/jpeg')).json()).photo;
  const pinReq = q => new Request('https://pin.example/api/pin' + q, { headers: { 'X-Pin': '4444', 'CF-Connecting-IP': '1.2.3.5' } });
  const snap = await (await pinGet({ request: pinReq(''), env, waitUntil: () => {} })).json();
  assert.equal(snap.ok, true);
  assert.deepEqual(snap.photos, { Meril: mine });
  assert.equal(snap.me.photo, '');
  const t = await (await pinGet({ request: pinReq('?team=1'), env, waitUntil: () => {} })).json();
  assert.equal(t.team.find(p => p.name === 'Meril').photo, mine);
  assert.equal(t.team.find(p => p.name === 'Bertin').photo, '');
  const aReq = q => new Request('https://pin.example/api/admin' + q, { headers: { 'X-Pin': '2222', 'CF-Connecting-IP': '1.2.3.6' } });
  const at = await (await adminGet({ request: aReq('?view=team'), env, waitUntil: () => {} })).json();
  assert.equal(at.team.find(p => p.name === 'Meril').photo, mine);
  store.m.set('pinhere:meril', JSON.stringify({ name: 'Meril', lat: 0.5, lng: 35.2, at: new Date().toISOString() }));
  const tm = await (await adminGet({ request: aReq('?view=teammap'), env, waitUntil: () => {} })).json();
  assert.equal(tm.people[0].photo, mine);
  const ro = await roster(env);
  assert.equal(ro.find(p => p.n === 'Meril').p, new URL('https://x' + mine).searchParams.get('v'));
  assert.equal('p' in ro.find(p => p.n === 'Bertin'), false);
});

test('renaming someone in Office › Team moves their photo; removing them deletes it', async () => {
  const { env, store } = setup();
  await post(env, '?name=Bertin', '2222', JPEG, 'image/jpeg');
  const aPost = b => adminPost({ request: new Request('https://pin.example/api/admin', { method: 'POST', headers: { 'X-Pin': '2222', 'Content-Type': 'application/json', Origin: 'https://pin.example', 'CF-Connecting-IP': '1.2.3.7' }, body: JSON.stringify(b) }), env, waitUntil: () => {} });
  const r = await aPost({ action: 'team-rename', name: 'Bertin', to: 'Bertin K' });
  assert.equal(r.status, 200);
  assert.equal(store.m.has('teamphoto:bertin'), false);
  assert.ok(store.m.has('teamphoto:bertin k'));
  const idx = JSON.parse(store.m.get('teamphotos'));
  assert.equal(idx['bertin k'].n, 'Bertin K');
  assert.equal((await get(env, '?name=Bertin%20K', '4444')).status, 200);
  await aPost({ action: 'team-remove', name: 'Bertin K' });
  assert.equal(store.m.has('teamphoto:bertin k'), false);
});

test('photoUrl encodes names', () => {
  assert.equal(photoUrl('Mary Ann', 'abc'), '/api/team-photo?name=Mary%20Ann&v=abc');
  assert.equal(photoUrl('Mary', ''), '');
});
