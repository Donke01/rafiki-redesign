// Chat 2 (one chat): Translate · Tafsiri (/api/translate, lib/translate.js), "Show text" under voice notes (the chat
// proxy's transcribe), the tools flags in the chat list, and "Add to project photos" (/api/pin chat-photo).
// Run: node --test tests/*.test.mjs   (no keys or network needed: OpenAI, monday.com and the chat worker are faked)
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { isSw, target, googleUrl, translateOn } from '../lib/translate.js';

function kv() {
  const m = new Map(), ttl = new Map();
  return { m, ttl, get: async (k, t) => { const v = m.has(k) ? m.get(k) : null; return v == null ? null : t === 'json' ? JSON.parse(v) : v; }, put: async (k, v, o) => { m.set(k, String(v)); if (o && o.expirationTtl) ttl.set(k, o.expirationTtl); }, delete: async k => { m.delete(k); }, list: async () => ({ keys: [...m.keys()].map(name => ({ name })), list_complete: true }) };
}
// one fake fetch for OpenAI and monday.com; each test sets what OpenAI answers
let AI = null, aiCalls = [], notes = [];
globalThis.fetch = async (url, o) => {
  const u = String(url);
  if (u.startsWith('https://api.openai.com/v1/chat/completions')) { aiCalls.push(JSON.parse(o.body)); return AI ? AI(JSON.parse(o.body)) : new Response('{}', { status: 500 }); }
  if (u.startsWith('https://api.openai.com/v1/audio/transcriptions')) { aiCalls.push({ audio: o.body.get('file').size }); return Response.json({ text: 'Pampu imeharibika', language: 'swahili', duration: 3 }); }
  const b = JSON.parse((o && o.body) || '{}');
  if (/create_update/.test(b.query || '')) { notes.push(b.variables.t); return Response.json({ data: { create_update: { id: String(7000 + notes.length) } } }); }
  if (/items\(ids/.test(b.query || '')) return Response.json({ data: { items: [{ id: b.variables.ids[0], name: 'Kapsaret Borehole', board: { id: '9412605754' }, column_values: [{ text: '0.47, 35.25' }] }] } });
  return Response.json({ data: { items: [] } });
};
const env0 = extra => Object.assign({ FEED: kv(), MONDAY_API_TOKEN: 't', PIN_USERS: 'Jessica:2222, Meril:3333', PIN_ADMINS: 'Jessica', PIN_CODE: '9999' }, extra || {});
let ip = 1;
const H = (pin, more) => Object.assign({ 'X-Pin': pin, 'CF-Connecting-IP': '5.5.5.' + (ip++ % 250) }, more || {});

test('Kiswahili or English, and which way to translate', () => {
  assert.equal(isSw('Mwenyekiti anasema watoto sasa wanakunywa maji safi kila siku.'), true);
  assert.equal(isSw('Asante! Nitafika kesho'), true);
  assert.equal(isSw('Yes, go ahead. I’ll approve it in the app.'), false);
  assert.equal(isSw('Quote from Eldoret Hardware for the cylinder: KES 18,500. Can I order?'), false);
  assert.equal(isSw('👍'), false);
  assert.equal(target('Pampu imetengenezwa'), 'en'); assert.equal(target('The pump is fixed'), 'sw'); assert.equal(target('The pump is fixed', 'en'), 'en');
  assert.equal(googleUrl('Habari yako', 'en'), 'https://translate.google.com/?sl=auto&tl=en&text=Habari%20yako&op=translate');
  assert.equal(translateOn({}), false); assert.equal(translateOn({ OPENAI_API_KEY: 'k' }), true); assert.equal(translateOn({ OPENAI_API_KEY: 'k', TRANSLATE_PROVIDER: 'off' }), false);
});

const tr = await import('../functions/api/translate.js');
const tpost = async (env, pin, body, more) => { const r = await tr.onRequestPost({ env, request: new Request('https://pin.example/api/translate', { method: 'POST', headers: H(pin, Object.assign({ 'Content-Type': 'application/json', Origin: 'https://pin.example' }, more || {})), body: JSON.stringify(body) }) }); return Object.assign({ status: r.status }, await r.json()); };

test('/api/translate: off without a key (the app opens Google Translate), signed-in people only', async () => {
  const env = env0();
  const g = await tr.onRequestGet({ env, request: new Request('https://pin.example/api/translate', { headers: H('3333') }) });
  assert.deepEqual(await g.json(), { ok: true, on: false });
  const j = await tpost(env, '3333', { text: 'Mwenyekiti anasema watoto sasa wanakunywa maji safi.' });
  assert.equal(j.ok, false); assert.equal(j.reason, 'off'); assert.equal(j.to, 'en'); assert.match(j.google, /^https:\/\/translate\.google\.com\/\?sl=auto&tl=en&text=Mwenyekiti/);
  assert.equal((await tpost(env, '9999', { text: 'hi' })).reason, 'no-name', 'the shared team PIN has no name');
  assert.equal((await tpost(env, '0000', { text: 'hi' })).status, 401);
  assert.equal((await tpost(env, '3333', { text: 'hi' }, { 'X-Donor-Token': 'd' })).reason, 'donor');
  assert.equal((await tpost(env, '3333', { text: 'hi' }, { Origin: 'https://evil.example' })).reason, 'origin');
  assert.equal((await tpost(env, '3333', { text: '   ' })).reason, 'bad-request');
  assert.equal((await tpost(env, '3333', { text: 'x'.repeat(2001) })).status, 413);
});

test('/api/translate: translates with the key, keeps names, caches 30 days, and is limited per person', async () => {
  const env = env0({ OPENAI_API_KEY: 'sk-test' }); aiCalls = [];
  AI = b => Response.json({ choices: [{ message: { content: '“The chairman says the children now drink clean water every day.”' } }] });
  const j = await tpost(env, '3333', { text: 'Mwenyekiti anasema watoto sasa wanakunywa maji safi kila siku.' });
  assert.deepEqual(j, { status: 200, ok: true, text: 'The chairman says the children now drink clean water every day.', to: 'en', cached: false });
  assert.equal(aiCalls.length, 1); assert.equal(aiCalls[0].temperature, 0); assert.match(aiCalls[0].messages[0].content, /into English/); assert.match(aiCalls[0].messages[0].content, /Keep names/);
  const again = await tpost(env, '2222', { text: 'Mwenyekiti anasema watoto sasa wanakunywa maji safi kila siku.' });
  assert.equal(again.cached, true); assert.equal(aiCalls.length, 1, 'the second time comes from KV');
  const key = [...env.FEED.m.keys()].find(k => k.startsWith('tr:'));
  assert.equal(env.FEED.ttl.get(key), 30 * 86400);
  // English goes to Kiswahili
  AI = b => Response.json({ choices: [{ message: { content: 'Ndiyo, endelea.' } }] });
  assert.equal((await tpost(env, '3333', { text: 'Yes, go ahead.' })).to, 'sw');
  assert.match(aiCalls[aiCalls.length - 1].messages[0].content, /into Kiswahili/);
  // OpenAI down: the Google address instead
  AI = () => new Response('no', { status: 500 });
  const d = await tpost(env, '3333', { text: 'The handle is loose' });
  assert.equal(d.reason, 'down'); assert.match(d.google, /tl=sw/);
  // 60 an hour per person
  AI = b => Response.json({ choices: [{ message: { content: 'sawa' } }] });
  let last;
  for (let i = 0; i < 61; i++) last = await tpost(env, '2222', { text: 'message number ' + i });
  assert.equal(last.status, 429); assert.equal(last.reason, 'rate');
});

// ---- the chat proxy: tools in the list, and Show text under a voice note ------------------------------------
const { onRequest: chatProxy } = await import('../functions/api/chat/[[path]].js');
function fakeChat() {
  const calls = [];
  return {
    calls,
    async fetch(req) {
      const u = new URL(req.url); calls.push(u.pathname + u.search);
      if (u.pathname === '/list') return Response.json({ ok: true, chats: [], me: 'Meril' });
      if (u.pathname === '/msgs') {
        if (u.searchParams.get('c') !== 'g:crew') return Response.json({ ok: false, reason: 'not-member' }, { status: 403 });
        const after = Number(u.searchParams.get('after'));
        const all = [{ id: 4, kind: 'text', text: 'hi' }, { id: 5, kind: 'voice', media: [{ id: 'voiceAAAAAAAAAAAAAA', type: 'voice' }] }];
        return Response.json({ ok: true, msgs: all.filter(m => m.id > after).slice(0, 1), more: false });
      }
      if (u.pathname.startsWith('/media/')) return new Response(new Uint8Array([0x1a, 0x45, 0xdf, 0xa3, 1, 2]), { headers: { 'Content-Type': 'audio/webm' } });
      return Response.json({ ok: true });
    }
  };
}
const creq = (path, pin, body) => new Request('https://pin.example/api/chat/' + path, { method: body ? 'POST' : 'GET', headers: H(pin, body ? { 'Content-Type': 'application/json', Origin: 'https://pin.example' } : {}), body: body ? JSON.stringify(body) : undefined });
const crun = async (env, path, pin, body) => { const r = await chatProxy({ request: creq(path, pin, body), env, params: { path: path.split('?')[0].split('/') } }); return Object.assign({ status: r.status }, await r.json()); };

test('the chat list says which tools are on', async () => {
  assert.deepEqual((await crun(env0({ CHAT: fakeChat() }), 'list', '3333')).tools, { translate: false, transcribe: false });
  assert.deepEqual((await crun(env0({ CHAT: fakeChat(), OPENAI_API_KEY: 'k' }), 'list', '3333')).tools, { translate: true, transcribe: true });
});

test('Show text: off without a key; reads the message as the person, types it out once, keeps the words', async () => {
  assert.equal((await crun(env0({ CHAT: fakeChat() }), 'transcribe', '3333', { c: 'g:crew', id: 5 })).reason, 'off');
  const chat = fakeChat(), env = env0({ CHAT: chat, OPENAI_API_KEY: 'sk' }); aiCalls = [];
  const j = await crun(env, 'transcribe', '3333', { c: 'g:crew', id: 5 });
  assert.equal(j.ok, true); assert.equal(j.text, 'Pampu imeharibika'); assert.equal(j.lang, 'sw'); assert.equal(j.cached, false);
  assert.ok(chat.calls.includes('/msgs?c=g%3Acrew&after=4&limit=1')); assert.ok(chat.calls.includes('/media/voiceAAAAAAAAAAAAAA'));
  assert.equal(aiCalls.length, 1);
  const again = await crun(env, 'transcribe', '3333', { c: 'g:crew', id: 5 });
  assert.equal(again.cached, true); assert.equal(aiCalls.length, 1);
  assert.deepEqual(JSON.parse(env.FEED.m.get('chattx:g:crew:5')), { text: 'Pampu imeharibika', lang: 'sw' });
  // not a voice note, not a member, nonsense
  assert.equal((await crun(env, 'transcribe', '3333', { c: 'g:crew', id: 4 })).reason, 'not-found');
  assert.equal((await crun(env, 'transcribe', '3333', { c: 'g:other', id: 5 })).reason, 'not-member');
  assert.equal((await crun(env, 'transcribe', '3333', { c: '../x', id: 5 })).reason, 'bad-request');
  assert.equal((await crun(env, 'transcribe', '3333', { c: 'g:crew', id: 0 })).reason, 'bad-request');
});

// ---- Add to project photos: the note on the project -------------------------------------------------------
const pin = await import('../functions/api/pin.js');
test('/api/pin chat-photo: a note on the project naming the chat and who sent it; the photos go to that note', async () => {
  const env = env0(); notes = [];
  const r = await pin.onRequestPost({ env, waitUntil: p => p, request: new Request('https://pin.example/api/pin', { method: 'POST', headers: H('3333', { 'Content-Type': 'application/json', Origin: 'https://pin.example' }), body: JSON.stringify({ action: 'chat-photo', id: '101', from: 'Bertin Mwangi' }) }) });
  const j = await r.json();
  assert.equal(j.ok, true); assert.equal(j.id, '101'); assert.ok(j.updateId);
  assert.equal(notes[0], '📷 Photo from the Kapsaret Borehole chat by Bertin Mwangi (added by Meril) (RAFIKI pin app).');
  const r2 = await pin.onRequestPost({ env, waitUntil: p => p, request: new Request('https://pin.example/api/pin', { method: 'POST', headers: H('3333', { 'Content-Type': 'application/json', Origin: 'https://pin.example' }), body: JSON.stringify({ action: 'chat-photo', id: '101', from: 'Meril' }) }) });
  assert.equal((await r2.json()).ok, true);
  assert.equal(notes[1], '📷 Photo from the Kapsaret Borehole chat by Meril (RAFIKI pin app).');
});
