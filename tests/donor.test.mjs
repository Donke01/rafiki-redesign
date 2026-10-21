// node --test tests/donor.test.mjs — donor pages: private links, field whitelisting, story consent, photos,
// repairs, anniversaries, the monthly digest, the public share card and thank-you uploads.
import test from 'node:test';
import assert from 'node:assert/strict';
import {
  makeToken, hashToken, tokenShape, sameText, findByToken, donorAdminPost, donorAdminGet, buildView, parseStories, parseNote,
  photosAllowed, repairTimeline, anniversary, digestLines, digestHtml, runDigest, unsubscribe, publicVisit, slug, PARTS
} from '../lib/donor.js';
import { cardHtml, describe } from '../functions/p/[slug].js';
import { check as thanksCheck } from '../functions/api/pin-thanks.js';
import * as donorApi from '../functions/api/donor.js';

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
const NOW = Date.parse('2026-10-20T10:00:00Z');
const FEED = {
  projects: [
    { id: '101', title: 'Kapsaret Borehole', country: 'Kenya', ck: 'ke', area: 'Kapsaret, Eldoret', type: 'Borehole well', water: true, cat: 'water', pump: 'Manual', people: 1200, year: 2023, done: '2025-10-05', status: 'Completed', photos: ['900'], gps: [0.470123, 35.250456], depth: 80, flow: 900 },
    { id: '102', title: 'Ndalat Primary School', country: 'Kenya', ck: 'ke', area: 'Ndalat', type: 'School', water: false, cat: 'school', people: 600, year: 2024, status: 'Completed', photos: [], gps: [0.2, 35.1] },
    { id: '103', title: 'Turbo Spring', country: 'Kenya', ck: 'ke', area: 'Turbo', type: 'Borehole well', water: true, cat: 'water', people: 600, year: 2022, status: 'Completed', photos: [], gps: [0.63, 35.05] },
    { id: '999', title: 'Not theirs', country: 'Kenya', ck: 'ke', type: 'Borehole well', status: 'Completed', photos: [] }
  ]
};
function env() { const FEED_KV = kv(); FEED_KV.m.set('feed:v9', JSON.stringify({ at: Date.now(), feed: FEED })); return { FEED: FEED_KV }; }

test('links: 256-bit, url-safe, deterministic per generation, hashed', async () => {
  const a = await makeToken('k'.repeat(32), 'd1', 'v1', 1), b = await makeToken('k'.repeat(32), 'd1', 'v1', 2);
  assert.equal(a.length, 43); assert.ok(tokenShape(a)); assert.notEqual(a, b);
  assert.equal(a, await makeToken('k'.repeat(32), 'd1', 'v1', 1));
  assert.notEqual(a, await makeToken('x'.repeat(32), 'd1', 'v1', 1));
  const h = await hashToken(a); assert.match(h, /^[0-9a-f]{64}$/); assert.notEqual(h, a);
  assert.ok(sameText('abc', 'abc')); assert.ok(!sameText('abc', 'abd')); assert.ok(!sameText('abc', 'abcd'));
  assert.ok(!tokenShape('short')); assert.ok(!tokenShape(a + '!'));
});

test('admin: add, find by link, copy again, rotate, revoke, group viewers', async () => {
  const e = env();
  const add = await donorAdminPost(e, { action: 'donor-add', name: 'Grace <b>Church</b>', email: 'GRACE@example.org', org: 'Grace Church', projects: ['101', '102', 'x'] }, 'Reid');
  assert.ok(add.body.ok); assert.equal(add.body.donor.name, 'Grace bChurch/b');
  assert.deepEqual(add.body.donor.projects, ['101', '102']);
  const t1 = add.body.link.split('/donor/')[1];
  assert.ok(add.body.link.startsWith('https://www.rafikioutreach.org/donor/')); assert.ok(tokenShape(t1));
  // the link itself is never stored
  for (const v of e.FEED.m.values()) assert.ok(!v.includes(t1));
  assert.equal((await findByToken(e, t1)).donor.email, 'grace@example.org');
  const id = add.body.donor.id;
  const again = await donorAdminPost(e, { action: 'donor-link', id }, 'Reid');
  assert.equal(again.body.link, add.body.link);
  const rot = await donorAdminPost(e, { action: 'donor-rotate', id }, 'Reid');
  const t2 = rot.body.link.split('/donor/')[1];
  assert.notEqual(t2, t1); assert.equal(await findByToken(e, t1), null); assert.ok(await findByToken(e, t2));
  // a second viewer (group page) has their own link
  const va = await donorAdminPost(e, { action: 'donor-viewer-add', id, name: 'Pastor John', email: 'john@example.org' }, 'Reid');
  const t3 = va.body.link.split('/donor/')[1];
  const hit = await findByToken(e, t3); assert.equal(hit.viewer.name, 'Pastor John'); assert.equal(hit.donor.id, id);
  // revoke just that viewer
  const vid = va.body.donor.viewers.find(v => !v.primary).vid;
  await donorAdminPost(e, { action: 'donor-revoke', id, vid }, 'Reid');
  assert.equal(await findByToken(e, t3), null); assert.ok(await findByToken(e, t2));
  assert.equal((await donorAdminPost(e, { action: 'donor-link', id, vid }, 'Reid')).body.reason, 'revoked');
  // rotating a revoked viewer gives them a new working link
  const back = await donorAdminPost(e, { action: 'donor-rotate', id, vid }, 'Reid');
  assert.ok(await findByToken(e, back.body.link.split('/donor/')[1]));
  await donorAdminPost(e, { action: 'donor-viewer-remove', id, vid }, 'Reid');
  // revoke everyone
  await donorAdminPost(e, { action: 'donor-revoke', id }, 'Reid');
  assert.equal(await findByToken(e, t2), null);
  const list = await donorAdminGet(e);
  assert.equal(list.donors[0].revoked, true); assert.equal(list.mail.ready, false);
  assert.ok(!JSON.stringify(list).match(/"h"|gen|donorkey/));
  assert.equal((await donorAdminPost(e, { action: 'donor-update', id: 'nope' }, 'R')).status, 404);
  assert.equal((await donorAdminPost(e, { action: 'donor-add', name: '' }, 'R')).status, 400);
});

test('links use DONOR_LINK_SECRET when set, and DONOR_LINK_ORIGIN', async () => {
  const e = Object.assign(env(), { DONOR_LINK_SECRET: 's'.repeat(40), DONOR_LINK_ORIGIN: 'https://test.rafikioutreach.org/' });
  const add = await donorAdminPost(e, { action: 'donor-add', name: 'A' }, 'R');
  assert.ok(add.body.link.startsWith('https://test.rafikioutreach.org/donor/'));
  assert.equal(await e.FEED.get('donorkey'), null);
});

test('story moments: only with consent', () => {
  const t = 'Site visit…<br>Story moment: “Now my girls get to school on time.” (Mary). Consent to quote and photograph: given.<br>' +
    'Story moment: “Please do not share this.” (Ann). Consent to quote and photograph: not given.';
  assert.deepEqual(parseStories(t), [{ quote: 'Now my girls get to school on time.', who: 'Mary' }]);
  assert.deepEqual(parseStories('✨ Story moment approved for the website by Reid: “Clean water at last” (Joseph).'), [{ quote: 'Clean water at last', who: 'Joseph' }]);
  assert.deepEqual(parseStories('Story moment: “No name”. Consent to quote and photograph: given.'), [{ quote: 'No name', who: '' }]);
  assert.deepEqual(parseStories('Notes: nothing'), []);
});

test('notes → donor-safe events; free-typed problems are dropped', () => {
  const ev = parseNote({ at: '2026-09-01T08:00:00Z', text: '🩺 Site visit by Meril (RAFIKI pin app): ⚠️ Needs repair. Urgency: urgent.\nProblems found: Pump handle and pivot pins, the chairman owes us money.\nEstimated cost: KES 4,500.\nWater committee: chair: Paul (0712 345 678).',
    files: [{ id: '501', name: 'site-photo-water-flowing-2026-09-01-08-10.jpg', ext: 'jpg' }, { id: '502', name: 'invoice.jpg', ext: 'jpg' }, { id: '503', name: 'voice-note-2026.m4a', ext: 'm4a' }] });
  const v = ev.find(x => x.kind === 'visit');
  assert.equal(v.state, 'repair'); assert.deepEqual(v.problems, ['Pump handle and pivot pins']); assert.equal(v.cost, 4500);
  assert.deepEqual(ev.filter(x => x.kind === 'photo').map(x => [x.id, x.label]), [['501', 'water flowing']]);
  assert.ok(!JSON.stringify(ev).includes('0712') && !JSON.stringify(ev).includes('Meril') && !JSON.stringify(ev).includes('owes'));
  assert.deepEqual(parseNote({ at: 'x', text: '🔧 Marked as fixed with the RAFIKI pin app by Bertin.' }).map(e => e.kind), ['fixed']);
  assert.equal(parseNote({ at: 'x', text: '🔧 Repair job updated by Reid (RAFIKI pin app): budget approved: KES 6,000.' })[0].amount, 6000);
  const th = parseNote({ at: 'x', text: '💛 Thank-you for donors from the community at Kapsaret, added by Meril (RAFIKI pin app): “Asante sana!”', files: [{ id: '700', name: 'donor-thanks-2026-10-01-09-00.mp4', ext: 'mp4' }] });
  assert.deepEqual(th, [{ kind: 'thanks', at: 'x', id: '700', media: 'video', caption: 'Asante sana!' }]);
  assert.ok(PARTS.has('Fence and gate'));
});

test('photo rule: water on, schools / children / clinics off until approved, master switch', () => {
  const d = { showPhotos: true, photos: {} };
  assert.ok(photosAllowed(d, FEED.projects[0])); assert.ok(!photosAllowed(d, FEED.projects[1]));
  assert.ok(!photosAllowed(d, { id: '5', type: 'Orphanage' })); assert.ok(!photosAllowed(d, { id: '6', type: 'Health' }));
  assert.ok(photosAllowed({ photos: { 102: 'on' } }, FEED.projects[1]));
  assert.ok(!photosAllowed({ photos: { 101: 'off' } }, FEED.projects[0]));
  assert.ok(!photosAllowed({ showPhotos: false, photos: { 102: 'on' } }, FEED.projects[1]));
});

test('repairs: what broke, cost, when fixed', () => {
  const r = repairTimeline([
    { kind: 'visit', at: '2026-05-01T00:00:00Z', state: 'repair', problems: ['Pump handle and pivot pins'], cost: 4500 },
    { kind: 'budget', at: '2026-05-02T00:00:00Z', amount: 5000 },
    { kind: 'fixed', at: '2026-05-10T00:00:00Z' },
    { kind: 'visit', at: '2026-09-01T00:00:00Z', state: 'notworking' }
  ], { at: '2026-09-01T00:00:00Z', state: 'notworking', problems: ['Chain or rods, pump head', 'free text'], cost: 8000, step: 'parts' });
  assert.equal(r.length, 2);
  assert.deepEqual(r[0], { at: '2026-09-01T00:00:00Z', state: 'notworking', problems: ['Chain or rods, pump head'], cost: 8000, costKind: 'estimate', fixedAt: null, open: true, step: 'parts' });
  assert.equal(r[1].fixedAt, '2026-05-10T00:00:00Z'); assert.equal(r[1].cost, 5000); assert.equal(r[1].costKind, 'approved'); assert.equal(r[1].open, false);
});

test('anniversary card: within 30 days after the completion date', () => {
  const p = { status: 'Completed', done: '2025-10-05' };
  assert.deepEqual(anniversary(p, NOW), { years: 1, at: '2025-10-05'.replace('2025', '2026') });
  assert.equal(anniversary(p, Date.parse('2026-12-01T00:00:00Z')), null);
  assert.equal(anniversary({ status: 'Completed', done: '2026-01-01' }, NOW), null);
  assert.deepEqual(anniversary({ status: 'Completed', done: '2021-10-19' }, NOW), { years: 5, at: '2026-10-19' });
  assert.equal(anniversary({ status: 'Completed', year: 2020 }, NOW), null);
});

test('page data: only whitelisted fields, impact, health, photos by rule', () => {
  const donor = { id: 'd1', name: 'Grace Church', org: 'Grace Church', projects: ['101', '102', '103', '404'], showPhotos: true, photos: {}, viewers: [{ vid: 'v1', primary: true, h: 'secret-hash', email: 'g@x.org' }, { vid: 'v2', name: 'John', h: 'h2' }] };
  const visits = {
    101: [{ at: '2026-10-01T08:00:00Z', by: 'Meril Kiprop', state: 'working', urgency: '', readings: { strokes: 12, flow: 20, queue: 4, users: 1500, clarity: 'clear' }, quality: { ecoli: 'neg', chlorine: 0.2 }, numbers: null }],
    102: [{ at: '2026-09-10T08:00:00Z', by: 'Bertin', state: 'working', kind: 'school', numbers: { pupils: 640, teachers: 12, income: 50000, died: 1 } }]
  };
  const events = {
    101: [{ kind: 'photo', at: '2026-10-01T08:00:00Z', id: '601', label: 'water flowing' }, { kind: 'photo', at: '2025-01-01T08:00:00Z', id: '600', label: 'water flowing' },
      { kind: 'story', at: '2026-10-01T08:00:00Z', quote: 'Clean water!', who: 'Mary' }],
    102: [{ kind: 'photo', at: '2026-09-10T08:00:00Z', id: '602', label: 'pupils' }]
  };
  const repairs = { 103: { state: 'notworking', by: 'Meril', at: '2026-10-10T00:00:00Z', note: 'call 0712 000 111', problems: ['Pump works'], cost: 3000, assignee: 'Bertin', step: 'assigned' } };
  const thanks = { 101: [{ id: '700', at: '2026-10-02T00:00:00Z', media: 'video', caption: 'Asante!', update: '1' }] };
  const v = buildView({ donor, viewer: donor.viewers[1], feed: FEED, visits, events, repairs, thanks, last: { 103: { at: '2026-10-10T00:00:00Z', by: 'Meril', state: 'notworking' } }, now: NOW });
  const s = JSON.stringify(v);
  for (const bad of ['Meril', 'Bertin', '0712', 'secret-hash', 'g@x.org', 'income', 'died', 'strokes', 'chlorine', 'assignee', 'Not theirs', '"by"', 'note']) assert.ok(!s.includes(bad), 'leaked ' + bad);
  assert.deepEqual(v.projects.map(p => p.id), ['101', '102', '103']);
  const k = v.projects[0];
  assert.deepEqual(k.gps, [0.470123, 35.250456]);
  assert.deepEqual(k.health, { state: 'working', at: '2026-10-01T08:00:00Z' });
  assert.equal(k.impact.people, 1500); assert.equal(k.impact.litresPerDay, 9600);
  assert.ok(k.impact.years > 0.9 && k.impact.years < 1.2);
  assert.deepEqual(k.visits[0], { at: '2026-10-01T08:00:00Z', state: 'working', flow: 20, users: 1500, clarity: 'clear', water: 'safe' });
  assert.equal(k.gallery.length, 2); assert.equal(k.thanks[0].media, 'video');
  assert.deepEqual(k.beforeAfter, { before: { photo: '600', at: '2025-01-01T08:00:00Z' }, after: { photo: '601', at: '2026-10-01T08:00:00Z' }, label: 'water flowing' });
  assert.deepEqual(k.anniversary, { years: 1, at: '2026-10-05' });
  assert.equal(k.share, '/p/101-kapsaret-borehole');
  const sch = v.projects[1];
  assert.equal(sch.gallery.length, 0); assert.equal(sch.photosShown, false); assert.equal(sch.impact.pupils, 640);
  assert.deepEqual(sch.visits[0].numbers, { pupils: 640, teachers: 12 });
  assert.equal(v.projects[2].health.state, 'repairing');
  assert.equal(v.projects[2].repairs[0].cost, 3000);
  assert.equal(v.totals.people, 1500 + 600 + 600); assert.equal(v.totals.water, 2); assert.equal(v.totals.workingPct, 50);
  assert.equal(v.donor.viewer, 'John'); assert.equal(v.donor.group, true);
  assert.deepEqual([...new Set(v.updates.map(u => u.type))].sort(), ['anniversary', 'photos', 'repair', 'story', 'thanks', 'visit']);
  assert.ok(Date.parse(v.updates[0].at) >= Date.parse(v.updates[v.updates.length - 1].at));
  assert.equal(publicVisit({ at: 'x', state: 'repair', kind: 'build', stage: 'roof', numbers: { workers: 9 } }).stage, 'Roofing');
});

test('digest: lines for the month, letterhead, unsubscribe; off without Resend', async () => {
  const donor = { id: 'd1', name: 'Grace', projects: ['101'], viewers: [] };
  const v = buildView({ donor, feed: FEED, visits: { 101: [{ at: '2026-09-20T08:00:00Z', state: 'working', readings: { users: 900 }, quality: { ecoli: 'neg' } }] }, events: {}, now: NOW });
  const lines = digestLines(v, Date.parse('2026-09-01T00:00:00Z'), Date.parse('2026-10-01T00:00:00Z'));
  assert.deepEqual(lines, ['20 September: site visit to Kapsaret Borehole — working, about 900 people using it, water tested safe.']);
  const html = digestHtml({ site: 'https://x.org', title: 'T', name: 'Grace <3', month: 'September 2026', lines, link: 'https://x.org/donor/abc', unsub: 'https://x.org/api/donor?unsub=1' });
  assert.ok(html.includes('1817 N. Beck Street') && html.includes('Grace &lt;3') && html.includes('unsub=1') && html.includes('/donor/abc'));
  assert.deepEqual(await runDigest(env(), 0, 1), { ok: false, reason: 'no-mail' });
});

test('digest sends through Resend with List-Unsubscribe, and the opt-out works', async () => {
  const e = Object.assign(env(), { RESEND_API_KEY: 're_test', ALERT_FROM: 'RAFIKI <updates@rafikioutreach.org>' });
  const add = await donorAdminPost(e, { action: 'donor-add', name: 'Grace', email: 'grace@example.org', projects: ['101'] }, 'R');
  await e.FEED.put('pinvisit:101', JSON.stringify([{ at: '2026-09-20T08:00:00Z', state: 'working' }]));
  const sent = [], real = globalThis.fetch;
  globalThis.fetch = async (url, o) => { sent.push({ url, body: JSON.parse(o.body) }); return new Response('{"id":"1"}', { status: 200 }); };
  try {
    const r = await runDigest(e, Date.parse('2026-09-01T00:00:00Z'), Date.parse('2026-10-01T00:00:00Z'), 'https://rafiki-redesign.pages.dev');
    assert.deepEqual(r, { ok: true, sent: 1, failed: 0 });
  } finally { globalThis.fetch = real; }
  assert.equal(sent[0].url, 'https://api.resend.com/emails');
  const m = sent[0].body;
  assert.deepEqual(m.to, ['grace@example.org']); assert.equal(m.from, 'RAFIKI <updates@rafikioutreach.org>');
  assert.ok(m.html.includes(add.body.link));
  const unsub = /<(https:\/\/rafiki-redesign\.pages\.dev\/api\/donor\?unsub=([^>]+))>/.exec(m.headers['List-Unsubscribe']);
  assert.ok(unsub && m.headers['List-Unsubscribe-Post']);
  assert.equal((await unsubscribe(e, unsub[2].replace(/.$/, 'x'), false)).ok, false);
  assert.equal((await unsubscribe(e, unsub[2], false)).ok, true);
  assert.ok((await donorAdminGet(e)).donors[0].viewers[0].unsub);
  globalThis.fetch = async () => { throw new Error('should not send'); };
  try { assert.deepEqual(await runDigest(e, Date.parse('2026-09-01T00:00:00Z'), Date.parse('2026-10-01T00:00:00Z')), { ok: true, sent: 0, failed: 0 }); } finally { globalThis.fetch = real; }
});

test('GET /api/donor: right link → whitelisted data; wrong links are limited', async () => {
  const e = env();
  const add = await donorAdminPost(e, { action: 'donor-add', name: 'Grace', projects: ['101'] }, 'R');
  const token = add.body.link.split('/donor/')[1];
  const req = (t, ip) => new Request('https://x.org/api/donor', { headers: { 'X-Donor-Token': t, 'CF-Connecting-IP': ip || '1.1.1.1' } });
  const ok = await donorApi.onRequestGet({ request: req(token), env: e });
  assert.equal(ok.status, 200); assert.equal(ok.headers.get('X-Robots-Tag'), 'noindex, nofollow'); assert.match(ok.headers.get('Cache-Control'), /no-store/);
  const j = await ok.json(); assert.equal(j.donor.name, 'Grace'); assert.equal(j.projects[0].title, 'Kapsaret Borehole');
  assert.ok((await donorAdminGet(e)).donors[0].lastOpen);
  for (let i = 0; i < 20; i++) assert.equal((await donorApi.onRequestGet({ request: req('A'.repeat(43), '2.2.2.2'), env: e })).status, 404);
  assert.equal((await donorApi.onRequestGet({ request: req(token, '2.2.2.2'), env: e })).status, 429);
});

test('public share card: noindex by default, no private data', () => {
  const p = FEED.projects[0];
  const h = cardHtml(p, 'https://www.rafikioutreach.org', { quote: 'Clean water!', who: 'Mary' }, false);
  assert.ok(h.includes('<meta name="robots" content="noindex">') && h.includes('og:image') && h.includes('/api/photo/900?w=1600') && h.includes('Clean water!'));
  assert.ok(h.includes('https://www.rafikioutreach.org/p/101-kapsaret-borehole'));
  assert.ok(!h.includes('/donor/'));
  assert.ok(cardHtml(p, 'https://x', null, true).includes('rel="canonical"'));
  assert.equal(describe(p), 'A borehole well in Kapsaret, Eldoret, Kenya, finished 2023, serving about 1,200 people.');
  assert.equal(slug('Chepkongi Dispensary - Well & Tower #2'), 'chepkongi-dispensary-well-tower-2');
});

test('thank-you uploads: photo and video limits', () => {
  assert.equal(thanksCheck('image/jpeg', 1e6).ext, 'jpg');
  assert.equal(thanksCheck('video/mp4', 30e6, 45).video, true);
  assert.equal(thanksCheck('video/mp4', 30e6, 75).fail, 'too-long');
  assert.equal(thanksCheck('video/mp4', 50e6, 30).fail, 'too-large');
  assert.equal(thanksCheck('image/png', 9e6).fail, 'too-large');
  assert.equal(thanksCheck('application/pdf', 1).fail, 'type');
});
