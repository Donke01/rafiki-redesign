// Voice notes typed out: the provider switch (lib/transcribe.js), the OpenAI and Google calls with a fake
// fetch, and adding the text to the visit's monday.com note (lib/voice-note.js), with its limits.
// Run: node --test tests/*.test.mjs   (Node 22+; no keys or network needed)
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { transcribeSetup, transcribe, langCode, guessLang } from '../lib/transcribe.js';
import { typeOutVoiceNote, typedLine, MAX_BYTES } from '../lib/voice-note.js';

function kv() {
  const m = new Map();
  return { m, get: async (k, t) => { const v = m.has(k) ? m.get(k) : null; return v == null ? null : t === 'json' ? JSON.parse(v) : v; }, put: async (k, v) => { m.set(k, String(v)); }, delete: async k => { m.delete(k); } };
}
const AUDIO = new Uint8Array([0x1a, 0x45, 0xdf, 0xa3, 1, 2, 3, 4]);

test('off until a key is set, and says which variable to add', () => {
  assert.deepEqual([transcribeSetup({}).on, transcribeSetup({}).missing], [false, ['OPENAI_API_KEY']]);
  assert.equal(transcribeSetup({ OPENAI_API_KEY: 'k' }).provider, 'openai');
  assert.equal(transcribeSetup({ OPENAI_API_KEY: 'k' }).model, 'whisper-1');
  assert.equal(transcribeSetup({ OPENAI_API_KEY: 'k', TRANSCRIBE_PROVIDER: 'off' }).on, false);
  assert.equal(transcribeSetup({ GOOGLE_STT_KEY: 'g' }).provider, 'google');
  assert.deepEqual(transcribeSetup({ TRANSCRIBE_PROVIDER: 'google' }).missing, ['GOOGLE_STT_KEY']);
  assert.equal(transcribeSetup({ OPENAI_API_KEY: 'k', TRANSCRIBE_PROVIDER: 'nope' }).on, false);
  assert.ok(!JSON.stringify(transcribeSetup({ OPENAI_API_KEY: 'sk-secret' })).includes('sk-secret'));
});

test('language codes and a fallback guess', () => {
  assert.equal(langCode('swahili'), 'sw'); assert.equal(langCode('sw-KE'), 'sw'); assert.equal(langCode('english'), 'en'); assert.equal(langCode('french'), '');
  assert.equal(guessLang('Pampu ya kisima haifanyi kazi na watu wanakosa maji'), 'sw');
  assert.equal(guessLang('The pump handle is loose and the apron is cracked'), 'en');
});

test('OpenAI: sends the audio with the key, reads text and language', async () => {
  let seen;
  globalThis.fetch = async (url, init) => { seen = { url: String(url), init }; return Response.json({ text: '  Pampu  imeharibika,  tunahitaji fundi. ', language: 'swahili', duration: 41.2 }); };
  const r = await transcribe({ OPENAI_API_KEY: 'sk-test' }, AUDIO, 'audio/webm;codecs=opus');
  assert.deepEqual(r, { text: 'Pampu imeharibika, tunahitaji fundi.', lang: 'sw', secs: 41.2 });
  assert.equal(seen.url, 'https://api.openai.com/v1/audio/transcriptions');
  assert.equal(seen.init.headers.Authorization, 'Bearer sk-test');
  const f = seen.init.body;
  assert.equal(f.get('model'), 'whisper-1'); assert.equal(f.get('response_format'), 'verbose_json');
  assert.equal(f.get('file').name, 'voice-note.webm'); assert.equal(f.get('file').size, AUDIO.length);
  assert.equal(f.get('language'), null, 'no language forced: detected automatically');
  // a newer model only answers in json, without the language
  globalThis.fetch = async (url, init) => { seen = init; return Response.json({ text: 'The handle is loose' }); };
  const r2 = await transcribe({ OPENAI_API_KEY: 'k', TRANSCRIBE_MODEL: 'gpt-4o-mini-transcribe' }, AUDIO, 'audio/mp4');
  assert.equal(seen.body.get('response_format'), 'json'); assert.equal(seen.body.get('file').name, 'voice-note.m4a'); assert.equal(r2.lang, 'en');
});

test('OpenAI errors become a short code, not a crash in the upload', async () => {
  globalThis.fetch = async () => new Response('{"error":"slow down"}', { status: 429 });
  await assert.rejects(transcribe({ OPENAI_API_KEY: 'k' }, AUDIO, 'audio/webm'), e => e.code === 'rate');
  await assert.rejects(transcribe({}, AUDIO, 'audio/webm'), e => e.code === 'off');
});

test('Google: Kiswahili first with English as an alternative; M4A is refused', async () => {
  let seen;
  globalThis.fetch = async (url, init) => { seen = { url: String(url), body: JSON.parse(init.body) }; return Response.json({ results: [{ alternatives: [{ transcript: 'maji ni safi' }], languageCode: 'sw-ke' }, { alternatives: [{ transcript: 'na tank imejaa' }] }] }); };
  const r = await transcribe({ GOOGLE_STT_KEY: 'g-key' }, AUDIO, 'audio/ogg');
  assert.deepEqual([r.text, r.lang], ['maji ni safi na tank imejaa', 'sw']);
  assert.match(seen.url, /speech:recognize\?key=g-key$/);
  assert.equal(seen.body.config.encoding, 'OGG_OPUS'); assert.equal(seen.body.config.languageCode, 'sw-KE'); assert.ok(seen.body.config.alternativeLanguageCodes.includes('en-KE'));
  assert.equal(Buffer.from(seen.body.audio.content, 'base64').length, AUDIO.length);
  await assert.rejects(transcribe({ GOOGLE_STT_KEY: 'g' }, AUDIO, 'audio/mp4'), e => e.code === 'format');
});

// a fake OpenAI + monday.com
function fake(opts) {
  const o = Object.assign({ body: '🩺 Site visit by Meril (RAFIKI pin app): ❌ Not working.', edit: true, text: 'Pampu imeharibika', lang: 'swahili' }, opts);
  const calls = [];
  globalThis.fetch = async (url, init) => {
    url = String(url);
    if (url.includes('openai.com')) { calls.push({ kind: 'stt' }); return Response.json({ text: o.text, language: o.lang, duration: 20 }); }
    const b = JSON.parse(init.body); calls.push({ kind: 'monday', q: b.query, v: b.variables, auth: init.headers.Authorization });
    if (/updates\(ids/.test(b.query)) return Response.json({ data: { updates: [{ id: '777', body: o.body, item_id: '104' }] } });
    if (/edit_update/.test(b.query)) return o.edit ? Response.json({ data: { edit_update: { id: '777' } } }) : Response.json({ errors: [{ message: 'Field edit_update does not exist' }] });
    if (/create_update/.test(b.query)) return Response.json({ data: { create_update: { id: '778' } } });
    return Response.json({ data: {} });
  };
  return calls;
}

test('the text is added to the visit\'s own note (edit), with the person\'s own token', async () => {
  const env = { FEED: kv(), OPENAI_API_KEY: 'k', MONDAY_API_TOKEN: 'office' };
  const calls = fake();
  const r = await typeOutVoiceNote(env, { token: 'meril-token', update: '777', bytes: AUDIO, mime: 'audio/webm', ip: '1.2.3.4', by: 'Meril' });
  assert.deepEqual([r.done, r.how, r.lang], [true, 'edit', 'sw']);
  const ed = calls.find(c => c.kind === 'monday' && /edit_update/.test(c.q));
  assert.equal(ed.auth, 'meril-token'); assert.equal(ed.v.i, '777');
  assert.equal(ed.v.b, '🩺 Site visit by Meril (RAFIKI pin app): ❌ Not working.<br><br>🎙️ Voice note (typed out): Pampu imeharibika (Kiswahili, typed out automatically, may have small mistakes)');
  const kept = await env.FEED.get('pinvoicetx', 'json');
  assert.equal(kept[0].text, 'Pampu imeharibika'); assert.equal(kept[0].item, '104');
  // the same voice note sent twice (retry): not added twice
  const again = await typeOutVoiceNote(env, { update: '777', bytes: AUDIO, mime: 'audio/webm', ip: '1.2.3.4' });
  assert.equal(again.how, 'edit');
  fake({ body: ed.v.b });
  const third = await typeOutVoiceNote(env, { update: '777', bytes: AUDIO, mime: 'audio/webm', ip: '1.2.3.4' });
  assert.equal(third.how, 'already');
});

test('when the note can\'t be edited, a follow-up note goes on the project', async () => {
  const env = { FEED: kv(), OPENAI_API_KEY: 'k', MONDAY_API_TOKEN: 'office' };
  const calls = fake({ edit: false, text: 'The <apron> is cracked & leaking', lang: 'english' });
  const r = await typeOutVoiceNote(env, { update: '777', bytes: AUDIO, mime: 'audio/mp4', ip: 'x', by: 'Bertin' });
  assert.equal(r.how, 'note');
  const c = calls.find(x => x.kind === 'monday' && /create_update/.test(x.q));
  assert.equal(c.v.i, '104');
  assert.equal(c.v.t, '🎙️ Voice note (typed out): The &lt;apron&gt; is cracked &amp; leaking (English, typed out automatically, may have small mistakes) (from the visit note by Bertin)');
});

test('limits: off, too big, per connection per hour, per day', async () => {
  const calls = fake();
  assert.equal((await typeOutVoiceNote({ FEED: kv() }, { update: '1', bytes: AUDIO, mime: 'audio/webm' })).why, 'off');
  const env = { FEED: kv(), OPENAI_API_KEY: 'k', TRANSCRIBE_DAILY_MAX: '3' };
  assert.equal((await typeOutVoiceNote(env, { update: '1', bytes: new Uint8Array(MAX_BYTES + 1), mime: 'audio/webm' })).why, 'too-long');
  assert.equal((await typeOutVoiceNote(env, { update: '1', bytes: AUDIO, mime: 'audio/webm', secs: 400 })).why, 'too-long');
  assert.equal((await typeOutVoiceNote(env, { update: '1', bytes: new Uint8Array(0), mime: 'audio/webm' })).why, 'empty');
  for (let i = 0; i < 3; i++) assert.equal((await typeOutVoiceNote(env, { update: '1', bytes: AUDIO, mime: 'audio/webm', ip: 'ip' + i })).done, true);
  assert.equal((await typeOutVoiceNote(env, { update: '1', bytes: AUDIO, mime: 'audio/webm', ip: 'ip9' })).why, 'daily-limit');
  const env2 = { FEED: kv(), OPENAI_API_KEY: 'k' };
  let last;
  for (let i = 0; i < 21; i++) last = await typeOutVoiceNote(env2, { update: '1', bytes: AUDIO, mime: 'audio/webm', ip: 'same' });
  assert.equal(last.why, 'rate');
  assert.ok(calls.filter(c => c.kind === 'stt').length <= 23);
});

test('silence: nothing is added', async () => {
  const env = { FEED: kv(), OPENAI_API_KEY: 'k' };
  const calls = fake({ text: '   ' });
  assert.equal((await typeOutVoiceNote(env, { update: '1', bytes: AUDIO, mime: 'audio/webm' })).why, 'no-speech');
  assert.equal(calls.filter(c => c.kind === 'monday').length, 0);
  assert.match(typedLine('a<b', 'en'), /^🎙️ Voice note \(typed out\): a&lt;b \(English, /);
});

test('the upload answers straight away; typing out runs in waitUntil', async () => {
  const mod = await import('../functions/api/pin-photo.js');
  const env = { FEED: kv(), OPENAI_API_KEY: 'k', MONDAY_API_TOKEN: 'office', PIN_USERS: 'Meril:4821' };
  const calls = fake();
  const realFetch = globalThis.fetch;
  globalThis.fetch = async (url, init) => { if (String(url).endsWith('/v2/file')) { calls.push({ kind: 'upload' }); return Response.json({ data: { add_file_to_update: { id: '1' } } }); } return realFetch(url, init); };
  const fd = new FormData(); fd.append('update', '777'); fd.append('file', new Blob([AUDIO], { type: 'audio/webm' }), 'voice.webm');
  const later = [];
  const r = await mod.onRequestPost({ request: new Request('https://rafiki.example/api/pin-photo', { method: 'POST', headers: { 'X-Pin': '4821' }, body: fd }), env, waitUntil: p => later.push(p) });
  assert.equal(r.status, 200);
  assert.equal(later.length, 1, 'typing out was handed to waitUntil');
  assert.equal(calls.filter(c => c.kind === 'stt').length, 0, 'not before the answer');
  await Promise.all(later);
  assert.equal(calls.filter(c => c.kind === 'stt').length, 1);
  assert.ok(calls.some(c => c.kind === 'monday' && /edit_update/.test(c.q)));
  // photos are not typed out
  const fd2 = new FormData(); fd2.append('update', '777'); fd2.append('file', new Blob([AUDIO], { type: 'image/jpeg' }), 'p.jpg');
  const later2 = [];
  await mod.onRequestPost({ request: new Request('https://rafiki.example/api/pin-photo', { method: 'POST', headers: { 'X-Pin': '4821' }, body: fd2 }), env, waitUntil: p => later2.push(p) });
  assert.equal(later2.length, 0);
});
