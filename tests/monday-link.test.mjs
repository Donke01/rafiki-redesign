// Me › Settings › "Your name on monday.com": connecting has its own limit, per person, so a busy
// office network (the shared hourly change limit) never blocks it. functions/api/pin.js
// Run: node --test tests/*.test.mjs
import test from 'node:test';
import assert from 'node:assert/strict';
import { onRequestPost } from '../functions/api/pin.js';

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
// monday.com turns every token down
globalThis.fetch = async () => new Response(JSON.stringify({ errors: [{ message: 'Not Authenticated' }] }), { status: 401, headers: { 'Content-Type': 'application/json' } });
function setup() {
  const store = kv();
  const env = { FEED: store, MONDAY_API_TOKEN: 't', PIN_USERS: 'Jessica:2222, Meril:3333, Grace:4444:ug', PIN_ADMINS: 'Jessica', PIN_CODE: '9999' };
  return { env, store };
}
const post = (env, pin, body, ip) => onRequestPost({ env, waitUntil: () => {}, request: new Request('https://pin.example/api/pin', {
  method: 'POST', headers: { 'CF-Connecting-IP': ip || '1.2.3.4', 'X-Pin': pin, Origin: 'https://pin.example', 'Content-Type': 'application/json' }, body: JSON.stringify(body) }) });
const TOKEN = 'eyJhbGciOiJIUzI1NiJ9.' + 'a'.repeat(60);

test('a busy network that used up its hourly changes can still connect monday.com', async () => {
  const { env, store } = setup();
  store.m.set('rl:pinsave:1.2.3.4', '500');
  const j = await (await post(env, '3333', { action: 'monday-link', token: TOKEN })).json();
  assert.notEqual(j.reason, 'rate', 'not blocked by the shared change limit');
  assert.equal(j.reason, 'token-bad');
  // other changes from that network are still limited
  const k = await post(env, '3333', { action: 'profile', nick: 'M' });
  assert.equal(k.status, 429);
});

test('a paste that is not a token is not counted; ten wrong tokens wait 15 minutes, for that person only', async () => {
  const { env, store } = setup();
  for (let i = 0; i < 15; i++) {
    const j = await (await post(env, '3333', { action: 'monday-link', token: 'hello' })).json();
    assert.equal(j.reason, 'token-format');
  }
  for (let i = 0; i < 10; i++) assert.equal((await (await post(env, '3333', { action: 'monday-link', token: TOKEN })).json()).reason, 'token-bad');
  const r = await post(env, '3333', { action: 'monday-link', token: TOKEN });
  assert.equal(r.status, 429);
  assert.deepEqual(await r.json(), { ok: false, reason: 'rate', what: 'connect' });
  assert.equal(store.m.get('rl:pinlink:meril'), '10');
  // Grace, on the same office network, is not affected
  assert.equal((await (await post(env, '4444', { action: 'monday-link', token: TOKEN })).json()).reason, 'token-bad');
});

test('a good token connects on today’s monday.com API, where photo_thumb_small no longer exists', async () => {
  const { env } = setup();
  const was = globalThis.fetch;
  globalThis.fetch = async (u, o) => {
    const q = JSON.parse(o.body).query;
    const ok = data => new Response(JSON.stringify({ data }), { headers: { 'Content-Type': 'application/json' } });
    if (/photo_thumb_small/.test(q)) return new Response(JSON.stringify({ errors: [{ message: 'Cannot query field "photo_thumb_small" on type "User".' }] }), { headers: { 'Content-Type': 'application/json' } });
    if (/photo_url/.test(q)) return ok({ me: { photo_url: { thumb_small: 'https://files.monday.com/p.png' } } });
    if (/country_code/.test(q)) return ok({ me: { country_code: 'KE' } });
    return ok({ me: { id: '5', name: 'Meril Adoyo', account: { id: '1', slug: 'rafikicap-company' } }, boards: [{ id: '9412605754' }] });
  };
  try {
    const j = await (await post(env, '3333', { action: 'monday-link', token: TOKEN })).json();
    assert.deepEqual(j, { ok: true, name: 'Meril Adoyo', photo: 'https://files.monday.com/p.png' });
  } finally { globalThis.fetch = was; }
});
