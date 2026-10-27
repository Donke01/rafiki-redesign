// Team chat, the Pages side: functions/api/chat/[[path]].js and lib/chat.js (the worker is tested in workers/chat).
// Run: node --test tests/*.test.mjs   (the CHAT binding is faked; it records what would reach the worker)
import test from 'node:test';
import assert from 'node:assert/strict';
import { onRequest } from '../functions/api/chat/[[path]].js';
import { roster, chatSys, chatSync, preview, readHead, freshTrips, projectPhotos } from '../lib/chat.js';
import { cardOf } from '../lib/itin.js';

function kv() {
  const m = new Map();
  return {
    m,
    async get(k, t) { const v = m.has(k) ? m.get(k) : null; return v == null ? null : t === 'json' ? JSON.parse(v) : v; },
    async put(k, v) { m.set(k, String(v)); },
    async delete(k) { m.delete(k); },
    async list() { return { keys: [...m.keys()].map(name => ({ name })), list_complete: true }; }
  };
}
const FEED = { projects: [{ id: '101', title: 'Kapsaret Borehole', status: 'Completed', type: 'Borehole well', area: 'Kapsaret', ck: 'ke', photos: ['900'] }] };
globalThis.fetch = async () => Response.json({ data: { items: [] } });

// a stand-in for the rafiki-chat worker
function fakeChat(answer) {
  const calls = [];
  return {
    calls,
    async fetch(req) {
      const body = req.method === 'GET' || req.method === 'HEAD' ? '' : await req.text();
      calls.push({ url: req.url, method: req.method, headers: Object.fromEntries(req.headers), body });
      return answer ? answer(req, body) : Response.json({ ok: true, url: '/pin#chat/join/abc' });
    }
  };
}
function setup(extra) {
  const store = kv();
  store.m.set('feed:v9', JSON.stringify({ at: Date.now(), feed: FEED }));
  store.m.set('pinteam', JSON.stringify([{ name: 'Meril', admin: false, home: 'ke' }, { name: 'Bertin', admin: false, home: 'tz' }]));
  const env = Object.assign({ FEED: store, MONDAY_API_TOKEN: 't', PIN_USERS: 'Jessica:2222, Meril:3333, Grace:4444:ug', PIN_ADMINS: 'Jessica', PIN_CODE: '9999' }, extra || {});
  return { env, store };
}
const req = (path, pin, opts) => {
  const o = opts || {};
  const h = Object.assign({ 'CF-Connecting-IP': '1.2.3.4' }, pin ? { 'X-Pin': pin } : {}, o.body ? { 'Content-Type': 'application/json', Origin: 'https://pin.example' } : {}, o.headers || {});
  return new Request('https://pin.example/api/chat/' + path, { method: o.method || (o.body ? 'POST' : 'GET'), headers: h, body: o.body ? JSON.stringify(o.body) : undefined });
};
const run = (env, path, pin, opts) => onRequest({ request: req(path, pin, opts), env, params: { path: path.split('?')[0].split('/') } });

test('chat-off: without the CHAT binding every endpoint says so, and nothing else breaks', async () => {
  const { env } = setup();
  for (const p of ['list', 'send', 'ws', 'media/abcdefghijklmnopqr']) {
    const r = await run(env, p, '3333', p === 'send' ? { body: { c: 't:all', text: 'hi' } } : {});
    assert.equal(r.status, 200);
    const j = await r.json();
    assert.equal(j.reason, 'chat-off');
    assert.deepEqual(j.need, ['CHAT service binding → rafiki-chat']);
  }
});

test('donor tokens, the shared team PIN, wrong PINs, blocked and signed-out people are refused before the worker', async () => {
  const chat = fakeChat(), { env, store } = setup({ CHAT: chat });
  let r = await run(env, 'list', '', { headers: { 'X-Donor-Token': 'x'.repeat(43) } });
  assert.equal(r.status, 403); assert.equal((await r.json()).reason, 'donor');
  r = await run(env, 'list', '9999');
  assert.equal(r.status, 403); assert.equal((await r.json()).reason, 'no-name');
  r = await run(env, 'list', '0000');
  assert.equal(r.status, 401); assert.equal((await r.json()).reason, 'pin');
  store.m.set('pinguard', JSON.stringify({ meril: { gen: 2, blocked: { at: 'x', by: 'Jessica' } } }));
  r = await run(env, 'list', '3333');
  assert.equal(r.status, 401); assert.equal((await r.json()).reason, 'blocked');
  store.m.set('pinguard', JSON.stringify({ meril: { gen: 2, blocked: null } }));
  r = await run(env, 'list', '3333', { headers: { 'X-Pin-Gen': '1' } });
  assert.equal(r.status, 401); assert.equal((await r.json()).reason, 'signed-out');
  assert.equal(chat.calls.length, 0);
  r = await run(env, 'list', '3333', { headers: { 'X-Pin-Gen': '2' } });
  assert.equal(r.status, 200);
  assert.equal(chat.calls.length, 1);
});

test('forwards who is asking, their role, home, session number and the team roster; never the PIN', async () => {
  const chat = fakeChat(), { env, store } = setup({ CHAT: chat });
  store.m.set('pinguard', JSON.stringify({ meril: { gen: 3 }, outsider: { gen: 1, blocked: { at: 'x' } } }));
  await run(env, 'list?since=5', '2222');
  const c = chat.calls[0];
  assert.equal(c.url, 'https://rafiki-chat/list?since=5');
  assert.equal(decodeURIComponent(c.headers['x-chat-user']), 'Jessica');
  assert.equal(c.headers['x-chat-admin'], '1');
  assert.equal(c.headers['x-pin'], undefined);
  assert.equal(c.headers['x-chat-secret'], undefined);
  const list = JSON.parse(decodeURIComponent(escape(atob(c.headers['x-chat-roster'].replace(/-/g, '+').replace(/_/g, '/')))));
  assert.deepEqual(list.map(x => x.n), ['Bertin', 'Grace', 'Jessica', 'Meril', 'outsider']);
  assert.deepEqual(list.find(x => x.n === 'Meril'), { n: 'Meril', h: 'ke', a: 0, g: 3, b: 0 });
  assert.equal(list.find(x => x.n === 'Grace').h, 'ug');
  assert.equal(list.find(x => x.n === 'Jessica').a, 1);
  assert.equal(list.find(x => x.n === 'outsider').b, 1);
  // with the optional CHAT_SECRET it is passed on
  env.CHAT_SECRET = 's3cret';
  await run(env, 'list', '2222');
  assert.equal(chat.calls[1].headers['x-chat-secret'], 's3cret');
});

test('project threads: named from the feed, unknown projects refused, a client cannot name it', async () => {
  const chat = fakeChat(), { env } = setup({ CHAT: chat });
  let r = await run(env, 'open', '3333', { body: { kind: 'project', project: '999' } });
  assert.equal(r.status, 404);
  r = await run(env, 'open', '3333', { body: { kind: 'project', project: '101', ptitle: 'Fake' } });
  assert.equal(r.status, 200);
  assert.deepEqual(JSON.parse(chat.calls[0].body), { kind: 'project', project: '101', ptitle: 'Kapsaret Borehole' });
  await run(env, 'send', '3333', { body: { c: 'p:101', kind: 'text', text: 'hi', ptitle: 'x' } });
  assert.equal(JSON.parse(chat.calls[1].body).ptitle, undefined);
});

test('the join link gets this site\'s address; other sites\' POSTs are refused', async () => {
  const chat = fakeChat(), { env } = setup({ CHAT: chat });
  const j = await (await run(env, 'link', '3333', { body: { c: 'g:x' } })).json();
  assert.equal(j.url, 'https://pin.example/pin#chat/join/abc');
  const r = await run(env, 'send', '3333', { body: { c: 'g:x' }, headers: { Origin: 'https://evil.example' } });
  assert.equal(r.status, 403);
});

test('WebSocket: the ticket\'s person is checked for blocked / signed out before the worker sees it', async () => {
  const chat = fakeChat(() => new Response('upgraded', { status: 200 })), { env, store } = setup({ CHAT: chat });
  const ticket = n => btoa(JSON.stringify({ n, g: 1, e: Date.now() + 60000 })).replace(/=+$/, '') + '.sig';
  store.m.set('pinguard', JSON.stringify({ meril: { gen: 2 }, bertin: { blocked: { at: 'x' } } }));
  assert.equal((await run(env, 'ws?t=' + ticket('Meril'), '')).status, 401);
  assert.equal((await run(env, 'ws?t=' + ticket('Bertin'), '')).status, 401);
  assert.equal((await run(env, 'ws?t=nonsense', '')).status, 401);
  const r = await run(env, 'ws?t=' + ticket('Grace'), '', { headers: { Upgrade: 'websocket' } });
  assert.equal(await r.text(), 'upgraded');
  assert.equal(chat.calls.length, 1);
  assert.match(chat.calls[0].url, /^https:\/\/rafiki-chat\/ws\?t=/);
});

test('media: signed links pass straight to the worker (it checks the signature); plain links need sign-in', async () => {
  const chat = fakeChat(() => new Response('img', { headers: { 'Content-Type': 'image/jpeg' } })), { env } = setup({ CHAT: chat });
  let r = await run(env, 'media/abcdefghijklmnopqr?e=1&s=x', '');
  assert.equal(await r.text(), 'img');
  assert.equal(chat.calls[0].headers['x-chat-user'], undefined);
  r = await run(env, 'media/abcdefghijklmnopqr', '');
  assert.equal(r.status, 401);
  r = await run(env, 'media/abcdefghijklmnopqr', '3333', { headers: { Range: 'bytes=0-9' } });
  assert.equal(chat.calls[1].headers.range, 'bytes=0-9');
  assert.equal(decodeURIComponent(chat.calls[1].headers['x-chat-user']), 'Meril');
  // uploads bigger than any cap stop here
  r = await onRequest({ request: new Request('https://pin.example/api/chat/media?type=video', { method: 'POST', headers: { 'X-Pin': '3333', 'Content-Length': String(40 * 1024 * 1024), Origin: 'https://pin.example' }, body: 'x' }), env, params: { path: ['media'] } });
  assert.equal(r.status, 413);
});

test('link previews: project links become a project card; other pages are read from their head', async () => {
  const { env } = setup({ CHAT: fakeChat() });
  const { getFeed } = await import('../lib/feed-cache.js');
  const p = await preview(env, 'https://pin.rafikioutreach.org/pin#p/101', getFeed);
  assert.equal(p.title, 'Kapsaret Borehole');
  assert.deepEqual(p.project, { id: '101', title: 'Kapsaret Borehole', country: 'ke', ck: 'ke', status: 'Completed', type: 'Borehole well', area: 'Kapsaret', photo: '/api/photo/900?w=480' });
  assert.equal(p.image, '/api/photo/900?w=480');
  assert.equal((await preview(env, 'http://127.0.0.1/admin', getFeed)).reason, 'bad-request');
  assert.equal((await preview(env, 'file:///etc/passwd', getFeed)).reason, 'bad-request');
  let fetched = 0;
  const html = '<html><head><title>Water.org</title><meta property="og:description" content="Clean &amp; safe water"><meta property="og:image" content="/i.jpg"></head><body>';
  const fake = async () => { fetched++; return new Response(html, { headers: { 'Content-Type': 'text/html' } }); };
  const w = await preview(env, 'https://water.org/x', getFeed, fake);
  assert.deepEqual(w, { ok: true, title: 'Water.org', description: 'Clean & safe water', image: 'https://water.org/i.jpg', site: 'water.org' });
  await preview(env, 'https://water.org/x', getFeed, fake);
  assert.equal(fetched, 1, 'cached');
  assert.equal(readHead('<meta name="og:image" content="javascript:alert(1)">', 'https://a.b/').image, '');
  // through the endpoint
  const r = await (await run(env, 'preview?url=' + encodeURIComponent('https://pin.rafikioutreach.org/pin#p/101'), '3333')).json();
  assert.equal(r.project.id, '101');
});

test('system lines and team sync reach the worker only when chat is on, and never throw', async () => {
  const { env } = setup();
  await chatSys(env, '101', { type: 'fixed', by: 'Meril' }, 'Marked as fixed by Meril.', { title: 'Kapsaret Borehole' });
  await chatSync(env);
  const chat = fakeChat();
  env.CHAT = chat;
  await chatSys(env, '101', { type: 'assigned', to: 'Grace', by: 'Jessica' }, 'Repair assigned to Grace by Jessica.', { title: 'Kapsaret Borehole', add: ['Grace', ''] });
  assert.equal(chat.calls[0].url, 'https://rafiki-chat/sys');
  assert.deepEqual(JSON.parse(chat.calls[0].body), { project: '101', title: 'Kapsaret Borehole', sys: { type: 'assigned', to: 'Grace', by: 'Jessica' }, text: 'Repair assigned to Grace by Jessica.', add: ['Grace'] });
  await chatSync(env);
  assert.equal(chat.calls[1].url, 'https://rafiki-chat/sync');
  assert.ok(chat.calls[1].headers['x-chat-roster']);
  env.CHAT = { fetch: async () => { throw new Error('down'); } };
  await chatSys(env, '101', { type: 'fixed' }, 'x');
  await chatSync(env);
});

test('roster: team list, Cloudflare PINs and PIN_ADMINS, once each, with blocked people', async () => {
  const { env, store } = setup({ PIN_ADMINS: 'Jessica, Reid Anderson' });
  store.m.set('pinprofiles', JSON.stringify({ Bertin: { home: 'mw' } }));
  store.m.set('pinguard', JSON.stringify({ bertin: { gen: 4, blocked: { at: 'x' } } }));
  const r = await roster(env);
  assert.deepEqual(r.map(x => x.n), ['Bertin', 'Grace', 'Jessica', 'Meril', 'Reid Anderson']);
  assert.deepEqual(r.find(x => x.n === 'Bertin'), { n: 'Bertin', h: 'mw', a: 0, g: 4, b: 1 });
  assert.equal(r.find(x => x.n === 'Reid Anderson').a, 1);
});

// ---- chat polish: trip cards, project chat photos ----------------------------------------------------------
const TRIP = {
  id: 'it_trip0001', title: 'Kapsaret loop', status: 'active', owner: 'Meril', people: ['Meril'], viewers: [], ck: 'ke', from: '2026-10-27', to: '2026-10-28',
  stops: [{ sid: 's1', name: 'Kapsaret', status: 'done', checkin: { at: 'x' } }, { sid: 's2', name: 'Chepkongi', status: 'planned' }, { sid: 's3', name: 'Ziwa', status: 'skipped' }, { sid: 's4', name: 'Moi\'s Bridge', status: 'planned' }],
  days: [{ date: '2026-10-27', stops: ['s1', 's2'], timeline: [{ kind: 'stop', sid: 's1', start: '08:30' }, { kind: 'stop', sid: 's2', start: '10:30' }] },
    { date: '2026-10-28', stops: ['s3', 's4'], timeline: [{ kind: 'stop', sid: 's4', start: '09:00' }] }]
};
test('trip card: next stop, done and total from the trip', () => {
  const now = Date.UTC(2026, 9, 27, 6);
  assert.deepEqual(cardOf(TRIP, now), { id: 'it_trip0001', title: 'Kapsaret loop', from: '2026-10-27', to: '2026-10-28', status: 'active', next: { name: 'Chepkongi', date: '2026-10-27', at: '10:30' }, done: 2, total: 4 });
  // the next day: today's leftovers are past, so the next stop is tomorrow's
  assert.deepEqual(cardOf(TRIP, Date.UTC(2026, 9, 28, 6)).next, { name: 'Moi\'s Bridge', date: '2026-10-28', at: '09:00' });
});

test('trip cards in chat: resolved by the proxy when sent, fresh for each viewer, title only without access', async () => {
  const chat = fakeChat((req, body) => {
    const u = new URL(req.url);
    if (u.pathname === '/send') return Response.json({ ok: true, msg: Object.assign({ id: 7, kind: 'trip' }, JSON.parse(body)) });
    if (u.pathname === '/msgs') return Response.json({ ok: true, msgs: [{ id: 7, kind: 'trip', trip: { id: 'it_trip0001', title: 'Old title', done: 0, total: 4, access: true } }, { id: 8, kind: 'trip', trip: { id: 'it_gone0001', title: 'Deleted trip' } }, { id: 9, kind: 'text', text: 'hi' }], more: false });
    return Response.json({ ok: true });
  });
  const { env, store } = setup({ CHAT: chat });
  store.m.set('itin:it_trip0001', JSON.stringify(TRIP));
  // Meril is on the trip: the card is filled here, whatever the client sent
  let r = await run(env, 'send', '3333', { body: { c: 'g:x', cid: 'a', kind: 'trip', trip: { id: 'it_trip0001', title: 'Hacked', done: 99 } } });
  assert.equal(r.status, 200);
  const sent = JSON.parse(chat.calls[0].body).trip;
  assert.equal(sent.title, 'Kapsaret loop');
  assert.equal(sent.done, 2);
  assert.equal(sent.access, true);
  // Grace is not on it: can't share it; unknown trips are 404
  r = await run(env, 'send', '4444', { body: { c: 'g:x', kind: 'trip', trip: { id: 'it_trip0001' } } });
  assert.equal(r.status, 403); assert.equal((await r.json()).reason, 'forbidden');
  r = await run(env, 'send', '3333', { body: { c: 'g:x', kind: 'trip', trip: { id: 'it_none0001' } } });
  assert.equal(r.status, 404);
  // a trip field on other kinds is dropped
  await run(env, 'send', '3333', { body: { c: 'g:x', kind: 'text', text: 'x', trip: { id: 'it_trip0001' } } });
  assert.equal(JSON.parse(chat.calls[chat.calls.length - 1].body).trip, undefined);
  // reading: fresh for Meril, title only for Grace, gone trips keep their title
  let j = await (await run(env, 'msgs?c=g:x', '3333')).json();
  assert.equal(j.msgs[0].trip.title, 'Kapsaret loop');
  assert.equal(j.msgs[0].trip.next.name, 'Chepkongi');
  assert.deepEqual(j.msgs[1].trip, { id: 'it_gone0001', title: 'Deleted trip', access: false, gone: true });
  assert.equal(j.msgs[2].text, 'hi');
  j = await (await run(env, 'msgs?c=g:x', '4444')).json();
  assert.deepEqual(j.msgs[0].trip, { id: 'it_trip0001', title: 'Kapsaret loop', access: false });
  // the admin (Jessica) sees every trip
  j = await (await run(env, 'trip?id=it_trip0001', '2222')).json();
  assert.equal(j.trip.access, true);
  assert.equal((await run(env, 'trip?id=it_nope0001', '2222')).status, 404);
  // the itin card endpoint carries the same fields
  const { onRequestGet } = await import('../functions/api/itin/[[path]].js');
  const c = await (await onRequestGet({ request: new Request('https://pin.example/api/itin/it_trip0001?card=1', { headers: { 'X-Pin': '3333', 'CF-Connecting-IP': '1.2.3.4' } }), env, params: { path: ['it_trip0001'] }, waitUntil() {} })).json();
  assert.equal(c.card.total, 4);
  assert.equal(c.card.stops, 4);
  assert.deepEqual(c.card.people, ['Meril']);
});

test('project chats get the project photo in the list', async () => {
  const chat = fakeChat(() => Response.json({ ok: true, chats: [{ id: 'p:101', kind: 'project', project: '101' }, { id: 't:ke', kind: 'team' }] }));
  const { env } = setup({ CHAT: chat });
  const j = await (await run(env, 'list', '3333')).json();
  assert.equal(j.chats[0].pphoto, '/api/photo/900?w=160');
  assert.equal(j.chats[1].pphoto, undefined);
  assert.deepEqual(projectPhotos([{ kind: 'project', project: '5' }], null), [{ kind: 'project', project: '5', pphoto: '' }]);
  assert.deepEqual(await freshTrips({}, {}, null), null);
});
