// Community reports by SMS / WhatsApp: the message reader, phone numbers, webhook checks (Africa's Talking
// token, Twilio signature) and the whole webhook with a fake KV and a fake monday.com.
// Run: node --test tests/*.test.mjs   (Node 22+; no Cloudflare needed)
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createHmac } from 'node:crypto';
import { parseSms, normPhone, maskPhone, twilioSignature, verifyTwilio, verifyToken, replyText, twiml, PER_HOUR } from '../lib/sms.js';
import * as webhook from '../functions/api/sms.js';
import * as smsAdmin from '../functions/api/sms-admin.js';

// ---- the message reader ----
test('reads English and Kiswahili reports', () => {
  assert.deepEqual(parseSms('BROKEN 104'), { kind: 'broken', id: '104', note: '', lang: 'en' });
  const sw = parseSms('IMEHARIBIKA 104 pampu');
  assert.equal(sw.kind, 'broken'); assert.equal(sw.id, '104'); assert.equal(sw.lang, 'sw'); assert.equal(sw.note, 'pampu');
  assert.equal(parseSms('haifanyi kazi 104').kind, 'broken');
  assert.equal(parseSms('Pampu ya 104 haifanyi kazi tangu jana').kind, 'broken');
  assert.equal(parseSms('104 not working since Monday').kind, 'broken');
  assert.equal(parseSms('the tap is leaking 104').kind, 'repair');
  assert.equal(parseSms('MATENGENEZO 104').kind, 'repair');
  assert.equal(parseSms('FIXED 104').kind, 'fixed');
  assert.equal(parseSms('imerekebishwa 104').lang, 'sw');
  assert.equal(parseSms('104 inafanya kazi sasa').kind, 'fixed');
  assert.equal(parseSms('104 is working again').kind, 'fixed');
  assert.equal(parseSms('not working again 104').kind, 'broken'); // "not working" wins over "working"
  assert.equal(parseSms('HELP').kind, 'help'); assert.equal(parseSms('msaada').lang, 'sw');
  assert.equal(parseSms('MSAADA 104 bomba').id, '104');
  assert.equal(parseSms('hello there').kind, 'unknown');
  assert.equal(parseSms('').kind, 'unknown');
});
test('finds the project number, not a phone number, and skips the shortcode keyword', () => {
  assert.equal(parseSms('BROKEN #10084384191').id, '10084384191');
  assert.equal(parseSms('broken call 0712345678 project 104').id, '104');
  assert.equal(parseSms('broken 254712345678').id, '');
  const k = parseSms('RAFIKI BROKEN 104'); assert.equal(k.kind, 'broken'); assert.equal(k.note, '');
  assert.equal(parseSms('MAJI imeharibika 55', 'MAJI').id, '55');
});
test('phone numbers: tidy, local numbers, masked', () => {
  assert.equal(normPhone('+254 712 345 678'), '+254712345678');
  assert.equal(normPhone('0712345678'), '+254712345678');
  assert.equal(normPhone('0712345678', 'tz'), '+255712345678');
  assert.equal(normPhone('whatsapp:+256701234567'), '+256701234567');
  assert.equal(normPhone('254712345678'), '+254712345678');
  assert.equal(normPhone('00265991234567'), '+265991234567');
  assert.equal(normPhone('12'), '');
  assert.equal(normPhone('call me'), '');
  assert.equal(maskPhone('+254712345678'), '+254•••678');
  assert.ok(!maskPhone('+254712345678').includes('712345'));
});
test('answers fit one SMS and are escaped for TwiML', () => {
  for (const k of ['broken', 'repair', 'already', 'fixed', 'fixed-check', 'no-project', 'help']) for (const l of ['en', 'sw']) {
    const t = replyText(k, l, { t: 'A very long project name that goes on and on and on', ref: 'AB12C', id: '999' });
    assert.ok(t.length <= 160, k + '/' + l + ' is ' + t.length + ' long: ' + t);
  }
  assert.equal(twiml('A & <b>'), '<?xml version="1.0" encoding="UTF-8"?><Response><Message>A &amp; &lt;b&gt;</Message></Response>');
  assert.equal(twiml(''), '<?xml version="1.0" encoding="UTF-8"?><Response></Response>');
});

// ---- checking the sender ----
test('Twilio signature matches the documented algorithm', async () => {
  const url = 'https://mycompany.com/myapp.php?foo=1&bar=2';
  const p = { CallSid: 'CA1234567890ABCDE', Caller: '+12349013030', Digits: '1234', From: '+12349013030', To: '+18005551212' };
  const ref = createHmac('sha1', '12345').update(url + Object.keys(p).sort().map(k => k + p[k]).join('')).digest('base64');
  assert.equal(await twilioSignature('12345', url, p), ref);
  assert.equal(await verifyTwilio('12345', url, p, ref), true);
  assert.equal(await verifyTwilio('12345', url, Object.assign({}, p, { Digits: '9999' }), ref), false);
  assert.equal(await verifyTwilio('', url, p, ref), false);
  assert.equal(await verifyTwilio('12345', url, p, ''), false);
});
test('Africa\'s Talking token: needed, long enough, exact', () => {
  assert.equal(verifyToken('s3cret-token-for-sms', 's3cret-token-for-sms'), true);
  assert.equal(verifyToken('s3cret-token-for-sms', 's3cret-token-for-smS'), false);
  assert.equal(verifyToken('short', 'short'), false);
  assert.equal(verifyToken('', ''), false);
});

// ---- the whole webhook ----
function kv() {
  const m = new Map();
  return { m, get: async (k, t) => { const v = m.has(k) ? m.get(k) : null; return v == null ? null : t === 'json' ? JSON.parse(v) : v; }, put: async (k, v) => { m.set(k, String(v)); }, delete: async k => { m.delete(k); }, list: async () => ({ keys: [], list_complete: true }) };
}
const TOKEN = 'at-webhook-token-123456';
function setup(extra) {
  const env = Object.assign({ FEED: kv(), MONDAY_API_TOKEN: 'mon', SMS_WEBHOOK_TOKEN: TOKEN, TWILIO_AUTH_TOKEN: 'tw-secret' }, extra || {});
  const calls = { notes: [], at: [] };
  globalThis.fetch = async (url, init) => {
    url = String(url);
    if (url.includes('africastalking.com')) { calls.at.push(Object.fromEntries(new URLSearchParams(init.body))); return new Response('{"SMSMessageData":{}}', { status: 201 }); }
    const b = JSON.parse(init.body);
    if (/items\(ids/.test(b.query)) {
      const id = b.variables.ids[0];
      const items = id === '104' ? [{ id: '104', name: 'KAPSARET BOREHOLE', board: { id: '9412605754' }, column_values: [{ text: '0.47, 35.25' }] }] : id === '105' ? [{ id: '105', name: 'Turbo Spring', board: { id: '9412605754' }, column_values: [{ text: '' }] }] : [];
      return Response.json({ data: { items } });
    }
    if (/create_update/.test(b.query)) { calls.notes.push(b.variables); return Response.json({ data: { create_update: { id: String(9000 + calls.notes.length) } } }); }
    return Response.json({ data: {} });
  };
  return { env, calls };
}
const atReq = (form, token) => new Request('https://rafiki.example/api/sms' + (token === undefined ? '?token=' + TOKEN : token ? '?token=' + token : ''), { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: new URLSearchParams(form).toString() });
const run = async (env, req) => { const later = []; const r = await webhook.onRequestPost({ request: req, env, waitUntil: p => later.push(p) }); await Promise.all(later); return r; };
const jget = async (env, k) => env.FEED.get(k, 'json');

test('Africa\'s Talking: off until the token is set, wrong token refused', async () => {
  const { env } = setup({ SMS_WEBHOOK_TOKEN: '' });
  assert.equal((await run(env, atReq({ from: '+254712345678', text: 'BROKEN 104' }))).status, 503);
  const s2 = setup();
  assert.equal((await run(s2.env, atReq({ from: '+254712345678', text: 'BROKEN 104' }, 'wrong-token-wrong-token'))).status, 403);
  assert.equal((await run(s2.env, atReq({ from: '+254712345678', text: 'BROKEN 104' }, ''))).status, 403);
  assert.equal(await jget(s2.env, 'pinrepairs'), null, 'nothing changed');
});

test('Africa\'s Talking: "BROKEN 104" becomes a repair report, logged and answered', async () => {
  const { env, calls } = setup({ AT_USERNAME: 'rafiki', AT_API_KEY: 'k' });
  const r = await run(env, atReq({ from: '+254712345678', to: '40123', text: 'BROKEN 104', id: 'ATXid_1', date: '2026-10-21 08:00:00' }));
  assert.equal(r.status, 200);
  const body = await r.text();
  assert.match(body, /Report received: Kapsaret Borehole is not working/);
  const rep = (await jget(env, 'pinrepairs'))['104'];
  assert.equal(rep.state, 'notworking'); assert.equal(rep.by, 'SMS from +254•••678'); assert.equal(rep.src, 'sms');
  const act = await jget(env, 'pinact');
  assert.deepEqual([act[0].a, act[0].s, act[0].by, act[0].id], ['visit', 'notworking', 'SMS from +254•••678', '104']);
  assert.equal(calls.notes.length, 1); assert.match(calls.notes[0].t, /Community report by SMS from \+254712345678: ❌ Not working/);
  assert.equal(calls.at.length, 1); assert.equal(calls.at[0].to, '+254712345678'); assert.equal(calls.at[0].username, 'rafiki');
  const log = await jget(env, 'pinsms');
  assert.equal(log[0].result, 'reported-broken'); assert.equal(log[0].t, 'Kapsaret Borehole');
  // the same message again (AT retry) does nothing
  await run(env, atReq({ from: '+254712345678', text: 'BROKEN 104', id: 'ATXid_1' }));
  assert.equal((await jget(env, 'pinsms')).length, 1);
  // a second report while the repair is open: a note, the repair stays as it was
  const r2 = await run(env, atReq({ from: '+254712345678', text: 'IMEHARIBIKA 104 pampu', id: 'ATXid_2' }));
  assert.match(await r2.text(), /Tayari tunajua/);
  assert.equal((await jget(env, 'pinrepairs'))['104'].at, rep.at);
});

test('numbers not on the list are ignored unless they name a real project', async () => {
  const { env, calls } = setup();
  const a = await run(env, atReq({ from: '+254700000001', text: 'BROKEN', id: 'x1' }));
  assert.equal(await a.text(), 'OK');
  const b = await run(env, atReq({ from: '+254700000001', text: 'BROKEN 999', id: 'x2' }));
  assert.equal(await b.text(), 'OK');
  const log = await jget(env, 'pinsms');
  assert.deepEqual(log.map(x => x.result), ['ignored-unregistered', 'ignored-no-project']);
  assert.equal(calls.notes.length, 0); assert.equal(await jget(env, 'pinrepairs'), null);
});

test('registered committee phones: no number needed, help and unknown are answered but not acted on', async () => {
  const { env, calls } = setup();
  await env.FEED.put('pinsmsphones', JSON.stringify([{ phone: '+255712000111', id: '105', t: 'Turbo Spring', name: 'Turbo water committee' }]));
  const h = await run(env, atReq({ from: '0712000111', text: 'msaada', id: 'h1' }));   // local format is Kenya by default: not this phone
  assert.equal(await h.text(), 'OK');
  const h2 = await run(env, atReq({ from: '+255712000111', text: 'msaada', id: 'h2' }));
  assert.match(await h2.text(), /IMEHARIBIKA <namba ya mradi>/);
  const u = await run(env, atReq({ from: '+255712000111', text: 'hello?', id: 'h3' }));
  assert.match(await u.text(), /send BROKEN <project number>/);
  assert.equal(calls.notes.length, 0);
  const r = await run(env, atReq({ from: '+255712000111', text: 'inavuja', id: 'h4' }));
  assert.match(await r.text(), /Turbo Spring \(inahitaji ukarabati\)/);
  assert.equal((await jget(env, 'pinrepairs'))['105'].state, 'repair');
  // a wrong project number from a registered phone gets told
  const w = await run(env, atReq({ from: '+255712000111', text: 'BROKEN 999', id: 'h5' }));
  assert.match(await w.text(), /could not find project 999/);
  // "fixed" from the registered phone closes the repair
  const f = await run(env, atReq({ from: '+255712000111', text: 'IMEREKEBISHWA', id: 'h6' }));
  assert.match(await f.text(), /inafanya kazi tena/);
  assert.equal((await jget(env, 'pinrepairs'))['105'], undefined);
  const act = await jget(env, 'pinact');
  assert.equal(act[act.length - 1].a, 'fixed');
});

test('"fixed" from a number that is not registered for the project leaves the repair open', async () => {
  const { env } = setup();
  await run(env, atReq({ from: '+254712345678', text: 'BROKEN 104', id: 'f1' }));
  const f = await run(env, atReq({ from: '+254799999999', text: 'FIXED 104', id: 'f2' }));
  assert.match(await f.text(), /Our team will check/);
  assert.ok((await jget(env, 'pinrepairs'))['104']);
});

test('rate limit per number', async () => {
  const { env } = setup();
  for (let i = 0; i < PER_HOUR + 2; i++) await run(env, atReq({ from: '+254711111111', text: 'hello 104', id: 'r' + i }));
  const log = await jget(env, 'pinsms');
  assert.equal(log.filter(x => x.result === 'rate-limited').length, 2);
});

test('Twilio WhatsApp: signature checked, answer in TwiML', async () => {
  const { env } = setup();
  const url = 'https://rafiki.example/api/sms';
  const form = { From: 'whatsapp:+254712345678', To: 'whatsapp:+14155238886', Body: 'IMEHARIBIKA 104', MessageSid: 'SM1', AccountSid: 'AC1' };
  const req = sig => new Request(url, { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded', 'X-Twilio-Signature': sig }, body: new URLSearchParams(form).toString() });
  assert.equal((await run(env, req('bad'))).status, 403);
  const r = await run(env, req(await twilioSignature('tw-secret', url, form)));
  assert.equal(r.status, 200); assert.match(r.headers.get('Content-Type'), /xml/);
  assert.match(await r.text(), /<Response><Message>RAFIKI: Asante\. Ripoti ya Kapsaret Borehole \(haifanyi kazi\)/);
  assert.equal((await jget(env, 'pinsms'))[0].ch, 'whatsapp');
  const off = setup({ TWILIO_AUTH_TOKEN: '' });
  assert.equal((await run(off.env, req('x'))).status, 503);
});

// ---- the Office ----
test('Office: admins only; setup shows names, never secret values; phone list', async () => {
  const { env } = setup({ PIN_USERS: 'Reid:4821, Meril:5555', PIN_ADMINS: 'Reid', OPENAI_API_KEY: 'sk-test-secret' });
  const get = pin => smsAdmin.onRequestGet({ request: new Request('https://rafiki.example/api/sms-admin', { headers: { 'X-Pin': pin } }), env });
  assert.equal((await get('5555')).status, 403);
  const r = await get('4821'), j = await r.json();
  assert.equal(j.ok, true);
  const text = JSON.stringify(j);
  assert.ok(!text.includes(TOKEN) && !text.includes('tw-secret') && !text.includes('sk-test-secret'), 'no secrets');
  assert.equal(j.setup.at.on, true); assert.equal(j.setup.twilio.on, true); assert.equal(j.setup.at.replies, false);
  assert.equal(j.voice.on, true); assert.equal(j.voice.provider, 'openai');
  assert.equal(j.urls.at, 'https://rafiki.example/api/sms?token=YOUR_SMS_WEBHOOK_TOKEN');
  const post = body => smsAdmin.onRequestPost({ request: new Request('https://rafiki.example/api/sms-admin', { method: 'POST', headers: { 'X-Pin': '4821', 'Content-Type': 'application/json', Origin: 'https://rafiki.example' }, body: JSON.stringify(body) }), env });
  assert.equal((await post({ action: 'phone-add', phone: '0712 345 678', id: '999' })).status, 404);
  const ok = await (await post({ action: 'phone-add', phone: '0712 345 678', id: '104', name: 'Kapsaret committee' })).json();
  assert.equal(ok.phone.phone, '+254712345678'); assert.equal(ok.phone.t, 'Kapsaret Borehole');
  assert.equal((await jget(env, 'pinsmsphones')).length, 1);
  await post({ action: 'phone-remove', phone: '+254712345678' });
  assert.equal((await jget(env, 'pinsmsphones')).length, 0);
});
