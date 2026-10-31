// node --test tests/chatfx.test.mjs — chatfx, the Pages side: /api/account (About, privacy, the RAFIKI PIN check for
// Locked chats, Linked devices with per-device sign-out), privacy in /api/pin and /api/team-photo, and the /api/chat
// proxy's device header, internal-path guard and WebSocket device check.
import test from 'node:test';
import assert from 'node:assert/strict';
import { tokenSession } from '../lib/pin-auth.js';
import { mergePrivacy, allowed, readPrivacy } from '../lib/privacy.js';
import { uaName, appOf } from '../lib/pin-devices.js';

function kv() {
  const m = new Map();
  return {
    m, get: async (k, t) => { const v = m.get(k); return v == null ? null : t === 'json' ? JSON.parse(v) : v; },
    put: async (k, v) => { m.set(k, String(v)); }, delete: async k => { m.delete(k); },
    list: async ({ prefix } = {}) => ({ keys: [...m.keys()].filter(k => !prefix || k.startsWith(prefix)).map(name => ({ name })), list_complete: true })
  };
}
globalThis.fetch = async (url, o) => {
  const b = JSON.parse((o && o.body) || '{}');
  if (/me \{ id name/.test(b.query || '')) return new Response(JSON.stringify({ data: { me: { id: '77', name: 'Bertin', photo_thumb_small: '', account: { id: '1', slug: 'rafiki' } }, boards: [{ id: '9412605754' }] } }));
  return new Response(JSON.stringify({ data: {} }));
};
function fakeChat() {
  const calls = [];
  return { calls, async fetch(req) { const body = req.method === 'GET' ? '' : await req.text(); calls.push({ path: new URL(req.url).pathname, headers: Object.fromEntries(req.headers), body }); return Response.json({ ok: true }); } };
}
function env(extra) {
  const store = kv();
  store.m.set('pinacct', JSON.stringify({ id: '1', slug: 'rafiki' }));
  store.m.set('feed:v9', JSON.stringify({ at: Date.now(), feed: { projects: [] } }));
  store.m.set('pinteam', JSON.stringify([{ name: 'Grace', home: 'ke' }]));
  return Object.assign({ FEED: store, MONDAY_API_TOKEN: 't', PIN_USERS: 'Morgan:4821, Meril:7364:ke, Jessica:2580', PIN_ADMINS: 'Morgan', PIN_CODE: '9999' }, extra || {});
}
const account = await import('../functions/api/account/[[path]].js');
const pin = await import('../functions/api/pin.js');
const photo = await import('../functions/api/team-photo.js');
const chat = await import('../functions/api/chat/[[path]].js');
let ip = 1;
function req(path, h, body) {
  return new Request('https://pin.example' + path, { method: body ? 'POST' : 'GET', headers: Object.assign({ 'CF-Connecting-IP': '10.1.0.' + (ip++ % 250) }, h || {}, body ? { 'Content-Type': 'application/json', Origin: 'https://pin.example' } : {}), body: body ? JSON.stringify(body) : undefined });
}
const out = async r => Object.assign(await r.json().catch(() => ({})), { status: r.status });
const acct = async (E, sub, h, body) => {
  const request = req('/api/account' + (sub ? '/' + sub : ''), h, body);
  if (h && h.city) Object.defineProperty(request, 'cf', { value: { city: h.city } });
  return out(await account.onRequest({ request, env: E, params: { path: sub ? sub.split('/') : [] }, waitUntil: p => p }));
};
const pinGet = async (E, h) => out(await pin.onRequestGet({ request: req('/api/pin', h), env: E, waitUntil: p => p }));
const flush = () => new Promise(r => setTimeout(r, 10));

test('privacy settings: merged change by change, invalid changes refused whole', () => {
  const p = mergePrivacy(null, { lastSeen: { mode: 'except', names: ['Meril'] }, readReceipts: false });
  assert.deepEqual(p.lastSeen, { mode: 'except', names: ['Meril'] });
  assert.equal(p.readReceipts, false);
  assert.equal(p.photo.mode, 'all');
  assert.equal(mergePrivacy(p, { status: { mode: 'none' } }), null);
  assert.equal(mergePrivacy(p, { colour: 'red' }), null);
  assert.equal(mergePrivacy(p, { groupAdd: 'admins' }).groupAdd, 'admins');
  assert.equal(allowed(readPrivacy({ photo: { mode: 'only', names: ['Grace'] } }).photo, 'Morgan', 'grace'), true);
  assert.equal(allowed(readPrivacy({ about: { mode: 'none' } }).about, 'Morgan', 'Morgan'), true);
  assert.equal(allowed(readPrivacy({ about: { mode: 'none' } }).about, 'Morgan', 'Grace'), false);
});

test('device names from the app headers or the User-Agent', () => {
  assert.equal(uaName('Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0 Safari/537.36', 'web'), 'Chrome on Windows');
  assert.equal(uaName('Mozilla/5.0 (Macintosh; Intel Mac OS X 14_5) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Safari/605.1.15', 'web'), 'Safari on Mac');
  assert.equal(uaName('Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 Version/18.0 Mobile/15E148 Safari/604.1', 'web'), 'Safari on iPhone');
  assert.equal(uaName('okhttp/4.12.0', 'android'), 'Android phone');
  assert.equal(uaName('RAFIKI%20Pin/229 CFNetwork/1568 Darwin/24.0.0', 'ios'), 'iPhone');
  assert.equal(appOf(new Request('https://x/', { headers: { 'User-Agent': 'okhttp/4.12.0' } })), 'android');
  assert.equal(appOf(new Request('https://x/', { headers: { 'X-Pin-App': 'ios' } })), 'ios');
});

test('About: set by the person, ≤ 140 characters, shown in the team list as their privacy allows', async () => {
  const E = env();
  assert.equal((await acct(E, 'about', { 'X-Pin': '7364' }, { about: 'x'.repeat(141) })).reason, 'too-long');
  assert.equal((await acct(E, 'about', { 'X-Pin': '7364' }, { about: '  Clean water for every school\n in Trans Nzoia 💧 ' })).about, 'Clean water for every school in Trans Nzoia 💧');
  // the Personal details form keeps it
  await pin.onRequestPost({ request: req('/api/pin', { 'X-Pin': '7364' }, { action: 'profile', phone: '+254 700 000000', nick: 'Meri' }), env: E, waitUntil: p => p });
  const me = await acct(E, '', { 'X-Pin': '7364' });
  assert.equal(me.about, 'Clean water for every school in Trans Nzoia 💧');
  assert.equal(me.hasPin, true);
  const team = async p => (await out(await pin.onRequestGet({ request: req('/api/pin?team=1', { 'X-Pin': p }), env: E, waitUntil: x => x }))).team;
  assert.equal((await team('4821')).find(x => x.name === 'Meril').about, 'Clean water for every school in Trans Nzoia 💧');
  await acct(E, 'privacy', { 'X-Pin': '7364' }, { about: { mode: 'except', names: ['Morgan'] } });
  assert.equal((await team('4821')).find(x => x.name === 'Meril').about, undefined);
  assert.ok((await team('2580')).find(x => x.name === 'Meril').about);
  // the shared team PIN and donors are refused
  assert.equal((await acct(E, '', { 'X-Pin': '9999' })).reason, 'named-only');
  assert.equal((await acct(E, '', { 'X-Donor-Token': 'x' })).reason, 'donor');
});

test('privacy: saved, sent to the chat worker, and profile photos hidden where they must be', async () => {
  const chatW = fakeChat(), E = env({ CHAT: chatW });
  E.FEED.m.set('teamphotos', JSON.stringify({ meril: { n: 'Meril', v: 'v1' } }));
  E.FEED.m.set('teamphoto:meril', JSON.stringify({ t: 'image/jpeg', d: btoa('jpeg'), v: 'v1' }));
  assert.equal((await acct(E, 'privacy', { 'X-Pin': '7364' }, { lastSeen: { mode: 'sometimes' } })).reason, 'bad-request');
  const r = await acct(E, 'privacy', { 'X-Pin': '7364' }, { photo: { mode: 'only', names: ['Jessica'] }, readReceipts: false });
  assert.equal(r.ok, true); assert.equal(r.chat, true);
  assert.deepEqual(r.privacy.photo, { mode: 'only', names: ['Jessica'] });
  const sent = chatW.calls.find(c => c.path === '/people');
  assert.deepEqual(JSON.parse(sent.body).list[0], { n: 'Meril', priv: r.privacy });
  assert.equal(sent.headers['x-chat-user'], undefined);
  assert.equal((await acct(E, '', { 'X-Pin': '7364' })).privacy.readReceipts, false);
  // /api/pin: the photos map and team list leave it out for Morgan, not for Jessica or Meril herself
  assert.equal((await pinGet(E, { 'X-Pin': '2580' })).photos.Meril, '/api/team-photo?name=Meril&v=v1');
  assert.equal((await pinGet(E, { 'X-Pin': '4821' })).photos.Meril, undefined);
  assert.equal((await pinGet(E, { 'X-Pin': '7364' })).me.photo, '/api/team-photo?name=Meril&v=v1');
  // the image itself: Grace (not an admin, not chosen) gets 404; admins (Office) and Jessica get it
  E.FEED.m.set('pinteam', JSON.stringify([{ name: 'Grace', home: 'ke', h: await (await import('../lib/pin-store.js')).hashPin(E, '5821') }]));
  const img = async p => (await photo.onRequestGet({ request: req('/api/team-photo?name=Meril&v=v1', { 'X-Pin': p }), env: E })).status;
  assert.equal(await img('5821'), 404);
  assert.equal(await img('2580'), 200);
  assert.equal(await img('4821'), 200);
});

test('verify-pin for Locked chats: right, wrong (limited), and monday.com sign-ins without a PIN', async () => {
  const E = env({ MONDAY_CLIENT_ID: 'c', MONDAY_CLIENT_SECRET: 's'.repeat(32) });
  assert.deepEqual(await acct(E, 'verify-pin', { 'X-Pin': '7364' }, { pin: '7364' }), { ok: true, status: 200 });
  const w = await acct(E, 'verify-pin', { 'X-Pin': '7364' }, { pin: '1111' });
  assert.equal(w.status, 200); assert.equal(w.reason, 'pin'); assert.equal(w.left, 4);
  for (let i = 0; i < 4; i++) await acct(E, 'verify-pin', { 'X-Pin': '7364' }, { pin: '1111' });
  assert.equal((await acct(E, 'verify-pin', { 'X-Pin': '7364' }, { pin: '7364' })).reason, 'locked');
  // an app PIN (hashed in the team list) works too
  const { hashPin } = await import('../lib/pin-store.js');
  E.FEED.m.set('pinteam', JSON.stringify([{ name: 'Grace', home: 'ke', h: await hashPin(E, '58204') }]));
  assert.equal((await acct(E, 'verify-pin', { 'X-Pin': '58204' }, { pin: '58204' })).ok, true);
  // Bertin signs in with monday.com only: no PIN to check, the app uses Face ID / fingerprint alone
  const s = await tokenSession(E, 'x'.repeat(48));
  const m = await acct(E, 'verify-pin', { Cookie: 'rfpin=' + s.sid }, { pin: '1234' });
  assert.deepEqual(m, { ok: false, reason: 'no-pin', status: 200 });
  assert.equal((await acct(E, '', { Cookie: 'rfpin=' + s.sid })).hasPin, false);
});

test('linked devices: listed with app, name and city; one signed out for real; "all others" keeps this one', async () => {
  const chatW = fakeChat(), E = env({ CHAT: chatW });
  const PHONE = { 'X-Pin': '7364', 'X-Pin-Gen': '0', 'X-Pin-Device-Id': 'phone-aaaaaaaaaaaaaaaa', 'X-Pin-App': 'android', 'X-Pin-Device': 'Samsung Galaxy A15', city: 'Kitale' };
  const WEB = { 'X-Pin': '7364', 'X-Pin-Gen': '0', 'X-Pin-Device-Id': 'web-bbbbbbbbbbbbbbbbbb', 'User-Agent': 'Mozilla/5.0 (Windows NT 10.0) Chrome/141.0 Safari/537.36' };
  const IPHONE = { 'X-Pin': '7364', 'X-Pin-Gen': '0', 'X-Pin-Device-Id': 'ios-cccccccccccccccccc', 'X-Pin-App': 'ios', 'X-Pin-Device': 'iPhone 17 Pro Max' };
  const OLD = { 'X-Pin': '7364', 'X-Pin-Gen': '0', 'User-Agent': 'okhttp/4.9.0' };
  for (const h of [WEB, IPHONE, OLD]) assert.equal((await pinGet(E, h)).ok, true);
  const l = await acct(E, 'devices', PHONE);
  assert.equal(l.current, 'phone-aaaaaaaaaaaaaaaa');
  assert.deepEqual(l.devices[0], Object.assign({}, l.devices[0], { name: 'Samsung Galaxy A15', app: 'android', current: true, kind: 'pin', legacy: false }));
  assert.deepEqual(l.devices.slice(1).map(d => d.name + ' · ' + d.app).sort(), ['Android phone · android', 'Chrome on Windows · web', 'iPhone 17 Pro Max · ios']);
  assert.equal(l.devices[0].city, 'Kitale');
  assert.equal(l.devices.find(d => d.name === 'Android phone').legacy, true);
  // last active is written at most every 10 minutes
  const before = E.FEED.m.get('pindev:meril'); await pinGet(E, WEB); assert.equal(E.FEED.m.get('pindev:meril'), before);
  // one device: refused from then on, its chat connection closed; the others carry on
  const legacy = l.devices.find(d => d.legacy).id, web = l.devices.find(d => d.name === 'Chrome on Windows').id;
  assert.equal((await acct(E, 'devices/sign-out', PHONE, { id: legacy })).reason, 'old-app');
  assert.equal((await acct(E, 'devices/sign-out', PHONE, { id: 'phone-aaaaaaaaaaaaaaaa' })).reason, 'current');
  assert.equal((await acct(E, 'devices/sign-out', PHONE, { id: web })).ok, true);
  await flush();
  assert.deepEqual(JSON.parse(chatW.calls.find(c => c.path === '/kick').body), { name: 'Meril', dev: web });
  let r = await pinGet(E, WEB); assert.equal(r.status, 401); assert.equal(r.reason, 'signed-out');
  assert.equal((await pinGet(E, IPHONE)).ok, true);
  // the PIN typed again on that browser: a new sign-in
  assert.equal((await pinGet(E, Object.assign({ 'X-Pin-New': '1' }, WEB))).ok, true);
  assert.equal((await pinGet(E, WEB)).ok, true);
  // all other devices: the session number goes up; this phone keeps working even with the old number
  const so = await acct(E, 'devices/sign-out-others', PHONE, {});
  assert.equal(so.ok, true); assert.equal(so.gen, 1); assert.equal(so.signedOut, 3);
  await flush();
  assert.deepEqual(JSON.parse(chatW.calls.filter(c => c.path === '/kick').pop().body), { name: 'Meril', keep: 'phone-aaaaaaaaaaaaaaaa', gen: 1 });
  assert.equal((await pinGet(E, PHONE)).ok, true);
  assert.equal((await pinGet(E, Object.assign({}, PHONE, { 'X-Pin-Gen': '1' }))).ok, true);
  for (const h of [WEB, IPHONE, OLD]) { r = await pinGet(E, h); assert.equal(r.status, 401, JSON.stringify(h)); assert.equal(r.reason, 'signed-out'); }
  assert.deepEqual((await acct(E, 'devices', PHONE)).devices.map(d => d.id), ['phone-aaaaaaaaaaaaaaaa']);
});

test('linked devices with monday.com: signing a browser out ends its session; "all others" keeps this session', async () => {
  const E = env({ MONDAY_CLIENT_ID: 'c', MONDAY_CLIENT_SECRET: 's'.repeat(32) });
  const s1 = await tokenSession(E, 'x'.repeat(48)), s2 = await tokenSession(E, 'y'.repeat(48)), s3 = await tokenSession(E, 'z'.repeat(48));
  const A = { Cookie: 'rfpin=' + s1.sid }, B = { Cookie: 'rfpin=' + s2.sid }, C = { Cookie: 'rfpin=' + s3.sid };
  for (const h of [A, B, C]) assert.equal((await pinGet(E, h)).who, 'Bertin');
  const l = await acct(E, 'devices', A);
  assert.equal(l.devices.length, 3);
  assert.equal(l.devices.every(d => d.kind === 'monday'), true);
  const b = l.devices.find(d => !d.current && d.id !== l.current);
  // which of B or C is `b` doesn't matter: sign it out, then the other with "all others"
  assert.equal((await acct(E, 'devices/sign-out', A, { id: b.id })).ok, true);
  const rb = [await pinGet(E, B), await pinGet(E, C)];
  assert.equal(rb.filter(r => r.status === 401).length, 1);
  assert.equal(rb.find(r => r.status === 401).reason, 'signin', 'the session is gone');
  await new Promise(r => setTimeout(r, 5));
  assert.equal((await acct(E, 'devices/sign-out-others', A, {})).ok, true);
  assert.equal((await pinGet(E, A)).who, 'Bertin');
  for (const h of [B, C]) assert.equal((await pinGet(E, h)).status, 401);
});

test('/api/chat: the device goes to the worker, internal paths are refused, signed-out devices cannot reconnect', async () => {
  const chatW = fakeChat(), E = env({ CHAT: chatW });
  const H = { 'X-Pin': '7364', 'X-Pin-Device-Id': 'phone-aaaaaaaaaaaaaaaa' };
  const run = (path, h, body) => chat.onRequest({ request: req('/api/chat/' + path, h, body), env: E, params: { path: path.split('?')[0].split('/') } }).then(out);
  await run('list', H);
  assert.equal(chatW.calls.pop().headers['x-chat-dev'], 'phone-aaaaaaaaaaaaaaaa');
  for (const p of ['sys', 'sync', 'people', 'kick', 'key', 'allow']) assert.equal((await run(p, H, {})).status, 404, p);
  assert.equal(chatW.calls.length, 0);
  const tk = p => btoa(JSON.stringify(p)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '') + '.sig';
  await pinGet(E, { 'X-Pin': '7364', 'X-Pin-Device-Id': 'web-bbbbbbbbbbbbbbbbbb' });
  await acct(E, 'devices/sign-out', H, { id: 'web-bbbbbbbbbbbbbbbbbb' });
  assert.equal((await run('ws?t=' + tk({ n: 'Meril', g: 0, d: 'web-bbbbbbbbbbbbbbbbbb' }), {})).reason, 'signed-out');
  chatW.calls.length = 0;
  await run('ws?t=' + tk({ n: 'Meril', g: 0, d: 'phone-aaaaaaaaaaaaaaaa' }), {});
  assert.equal(chatW.calls[0].path, '/ws');
});
