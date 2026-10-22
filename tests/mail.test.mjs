// node --test tests/mail.test.mjs — Office › Send an e-mail (lib/mail.js, functions/api/mail.js): who it goes to,
// unsubscribe and revoked links skipped, {first}/{name}, escaping, one e-mail per person, the cap, the log, admins only.
// fetch is mocked throughout: nothing is ever sent.
import test from 'node:test';
import assert from 'node:assert/strict';
import { resolve, personalise, bodyHtml, renderMail, cleanDraft, sendAll, overCap, usage, safeUrl, caps, teamPeople } from '../lib/mail.js';
import { donorAdminPost } from '../lib/donor.js';
import * as mailApi from '../functions/api/mail.js';

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
const FEED = { projects: [{ id: '101', title: 'Kapsaret Borehole', status: 'Completed', photos: ['900'] }, { id: '102', title: 'Ndalat School', status: 'Completed', photos: [] }] };
// Resend's batch endpoint, mocked: records every e-mail, answers like Resend
let outbox = [], batches = 0, failNext = null;
globalThis.fetch = async (url, o) => {
  const u = String(url);
  if (u === 'https://api.resend.com/emails/batch') {
    batches++;
    const items = JSON.parse(o.body);
    if (failNext) { const f = failNext; failNext = null; return new Response(JSON.stringify(f.body), { status: f.status }); }
    items.forEach(x => outbox.push(x));
    return Response.json({ data: items.map((_, i) => ({ id: 'em_' + outbox.length + '_' + i })) });
  }
  if (u.indexOf('api.resend.com') >= 0) throw new Error('unexpected Resend call ' + u);
  return Response.json({ data: { items: [] } });
};

async function setup(extra) {
  const store = kv();
  store.m.set('feed:v9', JSON.stringify({ at: Date.now(), feed: FEED }));
  store.m.set('pinteam', JSON.stringify([
    { name: 'Reid Anderson', admin: true, email: 'reid@rafiki.example' },
    { name: 'Meril', admin: false, email: 'meril@rafiki.example' },
    { name: 'Bertin', admin: false, email: '' },
    { name: 'Jessica', admin: true, email: 'JESSICA@rafiki.example' }
  ]));
  const env = Object.assign({ FEED: store, MONDAY_API_TOKEN: 't', PIN_USERS: 'Reid Anderson:1111, Jessica:2222, Meril:3333', PIN_ADMINS: 'Reid Anderson, Jessica', RESEND_API_KEY: 're_test', ALERT_FROM: 'RAFIKI <alerts@rafiki.example>', ALERT_EMAILS: 'board@rafiki.example' }, extra || {});
  const add = async (name, email, projects) => (await donorAdminPost(env, { action: 'donor-add', name, email, projects }, 'Reid')).body.donor;
  const grace = await add('Grace Church', 'grace@donor.example', ['101']);
  const john = await add('John Mwangi', 'john@donor.example', ['101', '102']);
  const unsub = await add('Ann Unsub', 'ann@donor.example', ['101']);
  const gone = await add('Revoked Donor', 'gone@donor.example', ['102']);
  const noMail = await add('No Mail', '', ['101']);
  // a second viewer on Grace Church, and the same address on two donors (only one e-mail)
  await donorAdminPost(env, { action: 'donor-viewer-add', id: grace.id, name: 'Pastor <b>Paul</b>', email: 'paul@donor.example' }, 'Reid');
  await donorAdminPost(env, { action: 'donor-viewer-add', id: john.id, name: 'John again', email: 'john@donor.example' }, 'Reid');
  const list = JSON.parse(store.m.get('pindonors'));
  list.find(d => d.id === unsub.id).viewers[0].unsub = new Date().toISOString();
  store.m.set('pindonors', JSON.stringify(list));
  await donorAdminPost(env, { action: 'donor-revoke', id: gone.id }, 'Reid');
  outbox = []; batches = 0;
  return { env, store, ids: { grace: grace.id, john: john.id, unsub: unsub.id, gone: gone.id, noMail: noMail.id } };
}
const req = (path, pin, body) => new Request('https://pin.example' + path, { method: body ? 'POST' : 'GET', headers: Object.assign({ 'X-Pin': pin, 'CF-Connecting-IP': '1.2.3.4' }, body ? { 'Content-Type': 'application/json', Origin: 'https://pin.example' } : {}), body: body ? JSON.stringify(body) : undefined });
const call = async (env, path, pin, body) => { const r = await (body ? mailApi.onRequestPost : mailApi.onRequestGet)({ request: req(path, pin, body), env }); return Object.assign(await r.json(), { status: r.status }); };
const DRAFT = { subject: 'Hello {first}', body: 'Dear {name},\n\nThe pump at Kapsaret works again.\n- 1,200 people\n- clean water', button: null };

test('audiences: donors all / by project / picked, admins, team, picked people', async () => {
  const { env, ids } = await setup();
  const all = await resolve(env, 'donors:all');
  assert.equal(all.kind, 'donor');
  assert.deepEqual(all.list.map(p => p.email).sort(), ['grace@donor.example', 'john@donor.example', 'paul@donor.example']);
  assert.deepEqual(all.skipped, { unsub: 1, revoked: 1, 'no-email': 1, dup: 1 });
  const p102 = await resolve(env, 'donors:project:102');
  assert.deepEqual(p102.list.map(p => p.name), ['John Mwangi']);
  assert.equal(p102.skipped.revoked, 1);
  const pick = await resolve(env, 'donors:pick', [ids.grace, ids.unsub]);
  assert.deepEqual(pick.list.map(p => p.email).sort(), ['grace@donor.example', 'paul@donor.example']);
  assert.equal(pick.skipped.unsub, 1);
  const admins = await resolve(env, 'admins');
  assert.deepEqual(admins.list.map(p => p.email).sort(), ['board@rafiki.example', 'jessica@rafiki.example', 'reid@rafiki.example']);
  const tm = await resolve(env, 'team');
  assert.deepEqual(tm.list.map(p => p.email).sort(), ['board@rafiki.example', 'jessica@rafiki.example', 'meril@rafiki.example', 'reid@rafiki.example']);
  assert.equal(tm.skipped['no-email'], 1); // Bertin
  const ppl = await teamPeople(env), meril = ppl.find(p => p.name === 'Meril');
  assert.deepEqual((await resolve(env, 'people:pick', [meril.key])).list.map(p => p.name), ['Meril']);
  assert.equal(await resolve(env, 'everyone'), null);
  assert.equal(await resolve(env, 'donors:project:abc'), null);
});

test('{first} and {name}, per person', () => {
  assert.equal(personalise('Hi {first}, {name}!', 'Grace Wanjiru'), 'Hi Grace, Grace Wanjiru!');
  assert.equal(personalise('Hi { FIRST }', ''), 'Hi friend');
  const m = renderMail({ site: 'https://pin.example', kind: 'team', person: { name: 'Meril Kip' }, draft: cleanDraft(DRAFT), by: 'Reid' });
  assert.equal(m.subject, 'Hello Meril');
  assert.ok(m.html.includes('Dear Meril Kip,') && m.text.startsWith('Dear Meril Kip,'));
});

test('escaping: subject, message, names, button and links can’t inject HTML', () => {
  const d = cleanDraft({ subject: '<script>x</script> {first}', body: 'a <b>bold</b> "q" & co\n- <img src=x onerror=alert(1)>', button: { label: 'Go <now>', href: 'javascript:alert(1)' } });
  assert.equal(d.button, null);
  assert.equal(safeUrl('https://ok.example/a?b=1'), 'https://ok.example/a?b=1');
  assert.equal(safeUrl('https://x" onmouseover="1'), '');
  const m = renderMail({ site: 'https://pin.example', kind: 'donor', person: { name: '<i>Eve</i> "E"' }, draft: d, by: '<b>Reid</b>', unsub: 'https://pin.example/api/donor?unsub=a.b.c' });
  assert.ok(!/<script|<img src=x|<b>bold|<i>Eve|<b>Reid|javascript:/i.test(m.html), 'nothing typed comes through as HTML');
  assert.ok(m.html.includes('&lt;script&gt;') && m.html.includes('&lt;b&gt;bold&lt;/b&gt;') && m.html.includes('&quot;q&quot; &amp; co') && m.html.includes('&lt;i&gt;Eve&lt;/i&gt;'));
  assert.ok(bodyHtml('one\n\n- a\n- b').includes('<ul') && bodyHtml('one\n\n- a\n- b').includes('<li style="margin:0 0 6px">a</li>'));
  const ok = cleanDraft({ subject: 's', body: 'b', button: { label: 'Read <more>', href: 'https://rafikioutreach.org/stories' } });
  const m2 = renderMail({ site: 'https://pin.example', kind: 'team', person: { name: 'A' }, draft: ok, by: 'R' });
  assert.ok(m2.html.includes('href="https://rafikioutreach.org/stories"') && m2.html.includes('Read &lt;more&gt;'));
});

test('send: one e-mail per person (never CC/BCC), donors get their own unsubscribe, no private link unless asked', async () => {
  const { env } = await setup();
  const pre = await call(env, '/api/mail', '1111', Object.assign({ action: 'preview', audience: 'donors:all' }, DRAFT));
  assert.equal(pre.ok, true); assert.equal(pre.count, 3);
  assert.ok(!JSON.stringify(pre).includes('@donor.example'), 'the preview names people, it never lists addresses');
  assert.ok(pre.html.includes('Unsubscribe') && pre.html.includes('(their-own-link)'), 'previews use sample links');
  const r = await call(env, '/api/mail', '1111', Object.assign({ action: 'send', audience: 'donors:all', expect: 3 }, DRAFT));
  assert.equal(r.ok, true); assert.equal(r.sent, 3); assert.equal(r.failed, 0); assert.equal(r.skipped, 4);
  assert.equal(outbox.length, 3);
  for (const e of outbox) {
    assert.equal(e.to.length, 1); assert.ok(!e.cc && !e.bcc);
    assert.equal(e.reply_to, 'reid@rafiki.example');
    assert.match(e.headers['List-Unsubscribe'], /^<https:\/\/pin\.example\/api\/donor\?unsub=[A-Za-z0-9]+\.[A-Za-z0-9]+\.[A-Za-z0-9_-]{22}>/);
    assert.equal(e.headers['List-Unsubscribe-Post'], 'List-Unsubscribe=One-Click');
    assert.ok(e.html.includes('/api/donor?unsub=') && !e.html.includes('/donor/') || !/\/donor\/[A-Za-z0-9_-]{43}/.test(e.html), 'no private link');
    assert.ok(!/\/donor\/[A-Za-z0-9_-]{43}/.test(e.html + e.text));
    assert.ok(!(e.html + e.text).includes('their-own-link'));
  }
  const john = outbox.find(e => e.to[0] === 'john@donor.example');
  assert.equal(john.subject, 'Hello John'); assert.ok(john.html.includes('Dear John Mwangi,'));
  assert.ok(!outbox.some(e => /ann@|gone@/.test(e.to[0])), 'unsubscribed and revoked are skipped');
  // with "include their private link": each donor gets their own
  outbox = [];
  const r2 = await call(env, '/api/mail', '1111', Object.assign({ action: 'send', audience: 'donors:project:101', expect: 3, includeLink: true }, DRAFT));
  assert.equal(r2.sent, 3);
  const links = outbox.map(e => (/\/donor\/([A-Za-z0-9_-]{43})/.exec(e.html) || [])[1]);
  assert.ok(links.every(Boolean)); assert.equal(new Set(links).size, 3);
  // team e-mails: blue, no unsubscribe headers
  outbox = [];
  const r3 = await call(env, '/api/mail', '1111', Object.assign({ action: 'send', audience: 'team', expect: 4 }, DRAFT));
  assert.equal(r3.sent, 4); assert.ok(outbox.every(e => !e.headers && e.html.includes('#0A78C2')));
  assert.ok(outbox.find(e => e.to[0] === 'meril@rafiki.example').subject === 'Hello Meril');
});

test('send: batches with pauses, failures counted; confirm count must match', async () => {
  const { env } = await setup();
  const people = Array.from({ length: 45 }, (_, i) => ({ name: 'P' + i, email: 'p' + i + '@x.example' }));
  const pauses = [];
  const res = await sendAll(env, people, async p => ({ subject: 's', html: 'h', text: 't' }), { batch: 20, sleep: async ms => { pauses.push(ms); } });
  assert.equal(batches, 3); assert.equal(pauses.length, 2); assert.equal(res.filter(x => x.ok).length, 45);
  failNext = { status: 422, body: { message: 'The domain is not verified' } };
  const bad = await sendAll(env, people.slice(0, 2), async () => ({ subject: 's', html: 'h', text: 't' }), { sleep: async () => {} });
  assert.ok(bad.every(x => !x.ok && /not verified/.test(x.error)));
  const stale = await call(env, '/api/mail', '1111', Object.assign({ action: 'send', audience: 'donors:all', expect: 2 }, DRAFT));
  assert.equal(stale.status, 409); assert.equal(stale.reason, 'changed'); assert.equal(stale.count, 3);
});

test('cap: one send, today and this month (configurable)', async () => {
  assert.deepEqual(caps({}), { per: 80, day: 100, month: 3000 });
  assert.equal(caps({ MAIL_CAP: '500' }).per, 500);
  assert.equal(overCap({}, 81, { today: 0, month: 0 }).reason, 'cap');
  assert.equal(overCap({}, 30, { today: 80, month: 80 }).reason, 'day-cap');
  assert.equal(overCap({}, 30, { today: 80, month: 80 }).left, 20);
  assert.equal(overCap({}, 30, { today: 0, month: 2990 }).reason, 'month-cap');
  assert.equal(overCap({}, 30, { today: 0, month: 0 }), null);
  const { env } = await setup({ MAIL_CAP: '2' });
  const r = await call(env, '/api/mail', '1111', Object.assign({ action: 'send', audience: 'donors:all', expect: 3 }, DRAFT));
  assert.equal(r.status, 409); assert.equal(r.reason, 'cap'); assert.equal(r.cap, 2);
  assert.equal(outbox.length, 0);
});

test('log: who, when, audience, subject, counts and each person’s status; last 50 shown; usage', async () => {
  const { env, store } = await setup();
  failNext = { status: 200, body: { data: [{ id: 'a' }], errors: [{ index: 1, message: 'Invalid `to` field' }] } };
  const r = await call(env, '/api/mail', '2222', Object.assign({ action: 'send', audience: 'donors:project:101', expect: 3 }, DRAFT));
  assert.equal(r.ok, true); assert.equal(r.sent, 2); assert.equal(r.failed, 1);
  const log = JSON.parse(store.m.get('pinmaillog'));
  assert.equal(log.length, 1);
  const e = log[0];
  assert.equal(e.by, 'Jessica'); assert.equal(e.audience, 'donors:project:101'); assert.equal(e.label, 'Donors of Kapsaret Borehole');
  assert.equal(e.subject, 'Hello {first}'); assert.equal(e.n, 3); assert.equal(e.sent, 2); assert.equal(e.failed, 1); assert.ok(e.skipped >= 1);
  assert.equal(e.rcpt.length, 3); assert.equal(e.rcpt.filter(x => x.s === 'failed').length, 1);
  assert.ok(!JSON.stringify(log).includes('@donor.example'), 'the log keeps names, not addresses');
  assert.deepEqual(usage(log, Date.now()), { today: 2, month: 2 });
  // only the last 50 come back
  store.m.set('pinmaillog', JSON.stringify(Array.from({ length: 70 }, (_, i) => ({ id: 'x' + i, at: new Date().toISOString(), sent: 1 }))));
  const ov = await call(env, '/api/mail?view=overview', '1111');
  assert.equal(ov.log.length, 50); assert.equal(ov.log[0].id, 'x69');
  assert.equal(ov.donors.reachable, 3); assert.equal(ov.donors.unsub, 1); assert.equal(ov.team.reachable, 4);
  assert.ok(!JSON.stringify(ov).includes('@donor.example') && !JSON.stringify(ov).includes('@rafiki.example'), 'no addresses in the overview');
});

test('test e-mail goes only to the admin; mail not set up; non-admins refused; rate limit', async () => {
  const { env } = await setup();
  const t = await call(env, '/api/mail', '1111', Object.assign({ action: 'test', audience: 'donors:all', includeLink: true }, DRAFT));
  assert.equal(t.ok, true); assert.equal(outbox.length, 1); assert.deepEqual(outbox[0].to, ['reid@rafiki.example']);
  assert.ok(outbox[0].subject.startsWith('[Test] ') && !/\/donor\/[A-Za-z0-9_-]{43}/.test(outbox[0].html), 'a test never carries a real private link');
  // field team: refused, and nothing about donors comes back
  for (const body of [null, { action: 'preview', audience: 'donors:all' }, Object.assign({ action: 'send', audience: 'donors:all', expect: 3 }, DRAFT)]) {
    const r = await call(env, body ? '/api/mail' : '/api/mail?view=overview', '3333', body);
    assert.equal(r.status, 403); assert.equal(r.reason, 'not-admin'); assert.ok(!JSON.stringify(r).includes('@'));
  }
  assert.equal((await call(env, '/api/mail?view=overview', '9999')).status, 401);
  const off = await setup({ RESEND_API_KEY: '' });
  const n = await call(off.env, '/api/mail', '1111', Object.assign({ action: 'send', audience: 'team', expect: 4 }, DRAFT));
  assert.equal(n.status, 503); assert.equal(n.reason, 'no-mail');
  assert.equal((await call(off.env, '/api/mail', '1111', Object.assign({ action: 'preview', audience: 'team' }, DRAFT))).ok, true, 'preview works before e-mail is set up');
  // at most 6 sends an hour per admin
  const { env: e2 } = await setup();
  let last;
  for (let i = 0; i < 7; i++) last = await call(e2, '/api/mail', '1111', Object.assign({ action: 'send', audience: 'admins', expect: 3 }, DRAFT));
  assert.equal(last.status, 429); assert.equal(last.reason, 'rate');
});

test('photo upload: images only, served back for mail apps', async () => {
  const { env } = await setup();
  const jpg = Buffer.from([0xFF, 0xD8, 0xFF, 0xE0, 0, 16, 74, 70, 73, 70, 0, 1, 1, 2, 3]).toString('base64');
  const up = await call(env, '/api/mail', '1111', { action: 'photo', type: 'image/jpeg', data: jpg });
  assert.equal(up.ok, true); assert.match(up.id, /^[A-Za-z0-9]{16,40}$/);
  const img = await mailApi.onRequestGet({ request: new Request('https://pin.example/api/mail?img=' + up.id), env });
  assert.equal(img.status, 200); assert.equal(img.headers.get('Content-Type'), 'image/jpeg');
  assert.equal((await call(env, '/api/mail', '1111', { action: 'photo', data: Buffer.from('<svg onload=x>').toString('base64') })).reason, 'type');
  assert.equal((await call(env, '/api/mail', '3333', { action: 'photo', data: jpg })).status, 403);
  const m = renderMail({ site: 'https://pin.example', kind: 'team', person: { name: 'A' }, draft: cleanDraft({ subject: 's', body: 'b', photo: { kind: 'upload', id: up.id } }), by: 'R' });
  assert.ok(m.html.includes('/api/mail?img=' + up.id));
  assert.equal(cleanDraft({ photo: { kind: 'upload', id: '../../x' } }).photo, null);
});

test('app invite: preview { ok, subject, html, text } for one person, by name or key; never a PIN', async () => {
  const { env } = await setup();
  const pre = await call(env, '/api/mail', '1111', { action: 'invite', to: 'Meril', preview: true });
  assert.equal(pre.ok, true); assert.equal(pre.to, 'Meril'); assert.equal(pre.email, true);
  assert.equal(pre.subject, 'You’re invited to RAFIKI Pin');
  assert.ok(pre.html.includes('href="https://pin.rafikioutreach.org/pin"') && pre.html.includes('Open RAFIKI Pin'));
  assert.ok(pre.html.includes('Hi Meril,') && pre.html.includes('Add to Home Screen') && pre.html.includes('just open the link') && pre.html.includes('Office tab'));
  assert.ok(pre.html.includes('which I’m sending you separately') && pre.html.includes('Reid Anderson, RAFIKI Outreach'), 'signed by the sender');
  assert.ok(pre.text.startsWith('You’re invited to RAFIKI Pin') && pre.text.includes('https://pin.rafikioutreach.org/pin') && pre.text.includes('Hi Meril,'), 'the same text to share on WhatsApp or SMS');
  assert.equal(outbox.length, 0, 'a preview sends nothing');
  // by key, as the overview lists people
  const k = await call(env, '/api/mail', '1111', { action: 'invite', to: 't:meril', preview: true });
  assert.equal(k.ok, true); assert.equal(k.to, 'Meril');
  // admins are told about the Office tab as their own
  const j = await call(env, '/api/mail', '1111', { action: 'invite', to: 'Jessica', preview: true });
  assert.ok(j.html.includes('You’re an admin'));
  for (const r of [pre, k, j]) assert.ok(!/\b(1111|2222|3333)\b/.test(r.html + r.text) && !/PIN[:\s]+\d{4}/.test(r.html + r.text), 'no PIN, ever');
  assert.equal((await call(env, '/api/mail', '1111', { action: 'invite', to: 'Nobody Here', preview: true })).status, 404);
  assert.equal((await call(env, '/api/mail', '1111', { action: 'invite', to: '' })).status, 400);
});

test('app invite: sent to that one person only, logged; no e-mail → refused; non-admins refused', async () => {
  const { env, store } = await setup();
  const r = await call(env, '/api/mail', '1111', { action: 'invite', to: 'Meril' });
  assert.equal(r.ok, true); assert.equal(r.sent, 1);
  assert.equal(outbox.length, 1); assert.deepEqual(outbox[0].to, ['meril@rafiki.example']); assert.ok(!outbox[0].cc && !outbox[0].bcc);
  assert.equal(outbox[0].subject, 'You’re invited to RAFIKI Pin'); assert.equal(outbox[0].reply_to, 'reid@rafiki.example');
  assert.ok(!/\b(1111|2222|3333)\b/.test(outbox[0].html + outbox[0].text), 'the invite never carries a PIN');
  const log = JSON.parse(store.m.get('pinmaillog'));
  assert.equal(log[log.length - 1].audience, 'invite'); assert.equal(log[log.length - 1].label, 'App invite to Meril'); assert.ok(!JSON.stringify(log).includes('meril@'));
  // Bertin has no e-mail: the preview works, sending is refused
  assert.equal((await call(env, '/api/mail', '1111', { action: 'invite', to: 'Bertin', preview: true })).email, false);
  const nb = await call(env, '/api/mail', '1111', { action: 'invite', to: 'Bertin' });
  assert.equal(nb.status, 409); assert.equal(nb.reason, 'no-email'); assert.equal(outbox.length, 1);
  // field team: refused, preview and send, and nothing comes back
  for (const body of [{ action: 'invite', to: 'Jessica', preview: true }, { action: 'invite', to: 'Jessica' }]) {
    const x = await call(env, '/api/mail', '3333', body);
    assert.equal(x.status, 403); assert.equal(x.reason, 'not-admin'); assert.ok(!x.html && !x.text);
  }
  assert.equal(outbox.length, 1);
  const off = await setup({ RESEND_API_KEY: '' });
  assert.equal((await call(off.env, '/api/mail', '1111', { action: 'invite', to: 'Meril' })).reason, 'no-mail');
});
