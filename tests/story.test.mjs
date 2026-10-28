// Stories (functions/api/story.js, lib/story.js, the story columns in lib/monday.js): answers, photos per row,
// consent, the draft (template; AI only with a key), approve / publish gates, the website feed and Office › Review.
// monday.com is a fake board kept in memory. Run: node --test tests/*.test.mjs
import test from 'node:test';
import assert from 'node:assert/strict';
import { onRequestGet, onRequestPost } from '../functions/api/story.js';
import { onRequestGet as pinGet } from '../functions/api/pin.js';
import { onRequestGet as adminGet } from '../functions/api/admin.js';
import { STORY_COL, buildFeed, photoIds } from '../lib/monday.js';
import { templateDraft, words } from '../lib/story.js';

function kv() {
  const m = new Map();
  return { m, async get(k, t) { const v = m.has(k) ? m.get(k) : null; return v == null ? null : t === 'json' ? JSON.parse(v) : v; },
    async put(k, v) { m.set(k, String(v)); }, async delete(k) { m.delete(k); }, async list() { return { keys: [], list_complete: true }; } };
}
// the fake board: one item, its columns as { text, value }
let COLS, FILES, ASSET, AI;
function reset() {
  COLS = { color_mkrccgae: { text: 'Educational' }, text_mks3h8ek: { text: 'Ndalat' }, numeric_mks3m7q8: { text: '420' }, datenk2ra6oh: { text: '2026-03-02' }, single_selectxrldna6: { text: 'Completed/Operational' } };
  FILES = {}; ASSET = 5000; AI = null;
}
const fileCol = id => ({ text: '', value: JSON.stringify({ files: (FILES[id] || []).map(f => ({ assetId: f.id, name: f.name })) }) });
globalThis.fetch = async (url, init) => {
  const u = String(url && url.url || url);
  if (u.startsWith('https://api.openai.com/')) return AI ? Response.json({ choices: [{ message: { content: AI } }] }) : new Response('no', { status: 500 });
  if (u.startsWith('https://files.example/')) return new Response(new Uint8Array([1, 2, 3]), { headers: { 'Content-Type': 'image/jpeg' } });
  if (u.includes('/v2/file')) {
    const q = init.body.get('query'), m = /column_id: "([^"]+)"/.exec(q);
    if (m) { const id = String(ASSET++); (FILES[m[1]] = FILES[m[1]] || []).push({ id, name: init.body.get('variables[file]').name }); return Response.json({ data: { add_file_to_column: { id } } }); }
    return Response.json({ data: { add_file_to_update: { id: String(ASSET++) } } });
  }
  const b = JSON.parse(init.body), q = b.query;
  if (q.includes('change_multiple_column_values')) {
    const v = JSON.parse(b.variables.v);
    Object.keys(v).forEach(k => { const x = v[k]; COLS[k] = { text: typeof x === 'string' ? x : x.text != null ? x.text : x.label }; });
    return Response.json({ data: { change_multiple_column_values: { id: '1' } } });
  }
  if (q.includes('column_values(ids: $c)')) {
    const cvs = b.variables.c.map(id => (id.startsWith('file_') ? Object.assign({ id }, fileCol(id)) : { id, text: (COLS[id] || {}).text || '', value: null }));
    return Response.json({ data: { items: b.variables.ids[0] === '404' ? [] : [{ id: b.variables.ids[0], name: 'Ndalat Dormitory', board: { id: '1' }, column_values: cvs }] } });
  }
  if (q.includes('create_update')) return Response.json({ data: { create_update: { id: '77' } } });
  if (q.includes('assets(ids')) return Response.json({ data: { assets: [{ id: b.variables.ids[0], public_url: 'https://files.example/a', url_thumbnail: '', file_size: 3 }] } });
  return Response.json({ data: { items: [] } });
};
function setup(extra) {
  reset(); const store = kv();
  store.m.set('feed:v9', JSON.stringify({ at: Date.now(), feed: { projects: [] } }));
  return { env: Object.assign({ FEED: store, MONDAY_API_TOKEN: 't', MONDAY_BOARD_ID: '1', PIN_USERS: 'Jessica:2222, Meril:3333', PIN_ADMINS: 'Jessica', PIN_CODE: '9999' }, extra || {}), store };
}
let ip = 1;
const H = pin => ({ 'X-Pin': pin, Origin: 'https://pin.example', 'CF-Connecting-IP': '9.9.9.' + (ip++ % 250) });
const post = async (env, pin, body) => { const r = await onRequestPost({ env, request: new Request('https://pin.example/api/story', { method: 'POST', headers: Object.assign({ 'Content-Type': 'application/json' }, H(pin)), body: JSON.stringify(body) }) }); return Object.assign({ status: r.status }, await r.json()); };
const form = async (env, pin, fields, file) => { const fd = new FormData(); Object.keys(fields).forEach(k => fd.append(k, fields[k])); if (file) fd.append('file', file, file.name || 'f'); const r = await onRequestPost({ env, request: new Request('https://pin.example/api/story', { method: 'POST', headers: H(pin), body: fd }) }); return Object.assign({ status: r.status }, await r.json()); };
const get = async (env, pin, q) => { const r = await onRequestGet({ env, request: new Request('https://pin.example/api/story' + q, { headers: H(pin) }) }); return r; };
const jpg = n => Object.assign(new Blob([new Uint8Array([0xff, 0xd8, 0xff])], { type: 'image/jpeg' }), { name: n || 'p.jpg' });

test('a new story: Not started, what is missing, the questions in English and Kiswahili', async () => {
  const { env } = setup();
  const j = await (await get(env, '3333', '?id=301')).json();
  assert.equal(j.story.status, 'not-started'); assert.equal(j.story.consent, 'not-yet');
  assert.deepEqual(j.story.missing, ['answers:0/6', 'after-photos', 'consent', 'text']);
  assert.ok(j.story.prompts.q1.en && j.story.prompts.q1.sw);
  assert.equal((await get(env, '3333', '?id=404')).status, 404);
  assert.equal((await get(env, '', '?id=301')).status, 401);
  assert.equal((await onRequestGet({ env, request: new Request('https://pin.example/api/story?id=301', { headers: { 'X-Pin': '3333', 'X-Donor-Token': 'x' } }) })).status, 403);
});

test('answers, photos and consent: written to the monday columns; Collecting; the index for lists', async () => {
  const { env, store } = setup();
  const a = await post(env, '3333', { action: 'answers', id: '301', q1: 'Girls slept in a crowded classroom.', q3: '120 girls boarding at Ndalat', quote: 'Now we can study at night.', quoteBy: 'Faith, Form 3' });
  assert.equal(a.ok, true); assert.equal(a.story.status, 'collecting');
  assert.equal(COLS[STORY_COL.q1].text, 'Girls slept in a crowded classroom.'); assert.equal(COLS[STORY_COL.q3].text, '120 girls boarding at Ndalat');
  assert.equal(COLS[STORY_COL.status].text, 'Collecting');
  const p = await form(env, '3333', { action: 'photo', id: '301', row: 'after', children: 'true' }, jpg('after.jpg'));
  assert.equal(p.ok, true); assert.equal(p.photo.children, true); assert.equal(FILES[STORY_COL.after].length, 1);
  assert.equal(p.story.photos.after.length, 1); assert.equal(p.story.photos.after[0].by, 'Meril');
  assert.equal((await form(env, '3333', { action: 'photo', id: '301', row: 'side' }, jpg())).status, 400);
  assert.equal((await form(env, '3333', { action: 'photo', id: '301', row: 'before' }, Object.assign(new Blob(['x'], { type: 'image/gif' }), { name: 'x.gif' }))).status, 415);
  // the photo itself, for signed-in team only and only a known asset
  const ok = await get(env, '3333', '?id=301&asset=' + p.photo.id); assert.equal(ok.status, 200);
  assert.equal((await get(env, '3333', '?id=301&asset=999')).status, 404);
  const c = await post(env, '3333', { action: 'consent', id: '301', value: 'received' });
  assert.equal(c.story.consent, 'received'); assert.equal(COLS[STORY_COL.consent].text, 'Received');
  const idx = JSON.parse(store.m.get('pinstoryidx'))['301'];
  assert.deepEqual([idx.st, idx.n, idx.a, idx.c], ['collecting', 2, 1, 'received']);
  // the snapshot carries it
  const s = await (await pinGet({ env, waitUntil: () => {}, request: new Request('https://pin.example/api/pin', { headers: H('3333') }) })).json();
  assert.equal(s.stories['301'].st, 'collecting');
});

test('voice answers: typed out when speech-to-text is on, else kept as audio and noted', async () => {
  const { env } = setup();
  const audio = Object.assign(new Blob([new Uint8Array(10)], { type: 'audio/webm' }), { name: 'v.webm' });
  const off = await form(env, '3333', { action: 'voice', id: '301', q: 'q5' }, audio);
  assert.equal(off.typed, false); assert.match(COLS[STORY_COL.q5].text, /^\(voice note on monday\.com, \d{4}-\d{2}-\d{2}\)$/);
});

test('the draft: a template from the answers when there is no AI key; Draft ready; facts only', async () => {
  const { env } = setup();
  await post(env, '3333', { action: 'answers', id: '301', q1: 'Girls walked 6 km home each night', q2: 'There was no dormitory', q4: 'Parents carried sand and water', q5: 'Girls stay at school and study at night', q6: 'The first night 40 girls slept there', quote: 'Now we can study at night', quoteBy: 'Faith' });
  const d = await post(env, '3333', { action: 'draft', id: '301' });
  assert.equal(d.ok, true); assert.equal(d.how, 'template'); assert.equal(d.story.status, 'draft');
  assert.match(d.text, /Ndalat Dormitory/); assert.match(d.text, /“Now we can study at night” — Faith/); assert.match(d.text, /2 March 2026/);
  assert.equal(COLS[STORY_COL.status].text, 'Draft ready'); assert.equal(COLS[STORY_COL.text].text, d.text);
  assert.equal(/\b(19|20)\d\d\b/.test(d.text.replace(/2026/g, '')), false, 'no invented years');
  // the document: sections in the story's order, kept in KV and shown to the app
  const doc = d.story.doc; assert.equal(doc.v, 1); assert.equal(doc.model, 'template'); assert.equal(doc.by, 'Meril');
  // six sections; their headings come in several versions so drafts don't all read alike
  const HV = ['Life before', 'How it used to be', 'The old routine', 'Before', 'The problem', 'What was missing', 'The hard part', 'The people it serves', 'Who it is for', 'The people',
    'Building it together', 'Many hands', 'The work', 'Side by side', 'What changed', 'Since then', 'Life now', 'Today', 'One moment', 'A day to remember', 'The moment', 'One scene'];
  assert.equal(doc.sections.length, 6); doc.sections.forEach(x => assert.ok(HV.includes(x.h), x.h));
  assert.deepEqual(doc.pull, { quote: 'Now we can study at night', who: 'Faith' }); assert.equal(d.story.text, doc.text);
  // Office › Review lists it
  const rv = await (await adminGet({ env, waitUntil: () => {}, request: new Request('https://pin.example/api/admin?view=review', { headers: H('2222') }) })).json();
  assert.ok(rv.items.some(x => x.kind === 'story-draft' && x.id === '301'));
  // with a key but an answer that is not the story JSON: the template (lib/story-doc.js has the AI tests)
  const { env: env2 } = setup({ OPENAI_API_KEY: 'k' }); AI = Array(120).fill('word').join(' ');
  await post(env2, '3333', { action: 'answers', id: '301', q1: 'a', q2: 'b' });
  const d2 = await post(env2, '3333', { action: 'draft', id: '301' }); assert.equal(d2.how, 'template');
});

test('approve and publish: admins only; consent needed when a photo shows children; on the website; back to Collecting takes it off', async () => {
  const { env, store } = setup();
  await post(env, '3333', { action: 'answers', id: '301', q1: 'a', q2: 'b' });
  await form(env, '3333', { action: 'photo', id: '301', row: 'after', children: 'true' }, jpg());
  await post(env, '3333', { action: 'draft', id: '301' });
  assert.equal((await post(env, '3333', { action: 'approve', id: '301' })).status, 403);
  const c = await post(env, '2222', { action: 'approve', id: '301' }); assert.equal(c.status, 409); assert.equal(c.reason, 'consent');
  assert.equal((await post(env, '2222', { action: 'publish', id: '301' })).reason, 'not-approved');
  assert.equal(store.m.has('pubstory:301'), false);
  await post(env, '3333', { action: 'consent', id: '301', value: 'received' });
  // an old app sends the whole text: it becomes the story
  const ok = await post(env, '2222', { action: 'approve', id: '301', text: 'Edited by the office.' });
  assert.equal(ok.story.status, 'published'); assert.match(COLS[STORY_COL.text].text, /Edited by the office\./);
  assert.ok(ok.story.pub && /\/stories\/301-/.test(ok.story.pub.url)); assert.equal(ok.url, ok.story.pub.url);
  const pub = JSON.parse(store.m.get('pubstory:301')); assert.match(JSON.stringify(pub.sections), /Edited by the office\./);
  assert.equal(JSON.parse(store.m.get('pubstoryidx'))[0].id, '301');
  assert.equal((await post(env, '3333', { action: 'collecting', id: '301' })).status, 403);
  assert.equal((await post(env, '3333', { action: 'draft', id: '301' })).reason, 'published', 'no new draft over a published story');
  const pb = await post(env, '2222', { action: 'publish', id: '301' }); assert.equal(pb.ok, true); assert.equal(pb.story.status, 'published');
  const back = await post(env, '2222', { action: 'collecting', id: '301' }); assert.equal(back.story.status, 'collecting'); assert.equal(back.story.pub, null);
  assert.equal(store.m.has('pubstory:301'), false); assert.deepEqual(JSON.parse(store.m.get('pubstoryidx')), []);
  assert.equal((await post(env, '9999', { action: 'answers', id: '301', q1: 'x' })).status, 403, 'not the shared team PIN');
});

test('the website feed carries a story only when it is Approved or Published, with its after photos', () => {
  const item = st => ({ id: '301', name: 'Ndalat Dormitory', column_values: [
    { id: 'single_selectxrldna6', text: 'Completed/Operational' }, { id: 'country_mkswymy5', text: 'Kenya', value: JSON.stringify({ countryCode: 'KE' }) }, { id: 'color_mkrccgae', text: 'Educational' },
    { id: STORY_COL.status, text: st }, { id: STORY_COL.text, text: 'The story.' }, { id: STORY_COL.quote, text: 'Q' }, { id: STORY_COL.after, text: '', value: JSON.stringify({ files: [{ assetId: 42, name: 'a.jpg' }] }) }] });
  const f1 = buildFeed([], [item('Draft ready')]); assert.equal(f1.projects[0].story, undefined);
  const f2 = buildFeed([], [item('Approved')]); assert.deepEqual(f2.projects[0].story, { text: 'The story.', quote: 'Q', quoteBy: '', credit: '', after: ['42'] });
  assert.ok(photoIds(f2).has('42'));
  assert.ok(buildFeed([], [item('Published')]).projects[0].story);
});

test('templateDraft leaves out what was not given', () => {
  const pack = { project: { title: 'Kapsaret Borehole', type: 'Borehole well', place: '', country: '', ck: '', people: 0, done: '', status: '' }, answers: { q5: 'women spend the mornings at the market' }, quote: '', quoteBy: '', stages: [], visits: [], photos: [] };
  const d = templateDraft(pack, { answers: pack.answers, quote: '', quoteBy: '', consent: 'not-yet', photos: {} }, 'Meril');
  assert.match(d.text, /Kapsaret Borehole/); assert.match(d.text, /Since then, women spend/);
  assert.equal(/people|finished/.test(d.text), false); assert.equal(d.pull, null); assert.equal(d.title, 'Water for Kapsaret Borehole');
});

test('story photos: a caption, "shows children" and another row (photo-edit); new photos since the draft; the website cover', async () => {
  const { env } = setup();
  await post(env, '3333', { action: 'answers', id: '301', q1: 'Girls walked 6 km home each night', q2: 'There was no dormitory', q5: 'Girls stay at school' });
  const b = await form(env, '3333', { action: 'photo', id: '301', row: 'before', cap: '  Girls  outside the old classroom ' }, jpg('b.jpg'));
  assert.equal(b.photo.cap, 'Girls outside the old classroom');
  const aid = b.photo.id;
  // caption and children
  const e = await post(env, '3333', { action: 'photo-edit', id: '301', row: 'before', asset: aid, cap: 'The old classroom at dusk', children: true });
  assert.equal(e.ok, true); assert.equal(e.story.photos.before[0].cap, 'The old classroom at dusk'); assert.equal(e.story.photos.before[0].children, true);
  // moved to After: only there (its file stays in the Before column on monday.com)
  const mv = await post(env, '3333', { action: 'photo-edit', id: '301', row: 'before', asset: aid, to: 'after', children: false });
  assert.equal(mv.story.photos.before.length, 0); assert.deepEqual(mv.story.photos.after.map(p => p.id), [aid]); assert.equal(mv.story.photos.after[0].cap, 'The old classroom at dusk');
  assert.equal(mv.story.pool.find(p => p.id === aid).cap, 'The old classroom at dusk', 'the story uses the typed caption');
  assert.equal((await post(env, '3333', { action: 'photo-edit', id: '301', row: 'before', asset: aid })).status, 404);
  assert.equal((await post(env, '3333', { action: 'photo-edit', id: '301', row: 'after', asset: aid, to: 'side' })).status, 400);
  // a photo added after the draft is counted, so the app can offer "Draft again"
  const d = await post(env, '3333', { action: 'draft', id: '301' }); assert.equal(d.story.newPhotos, 0);
  await new Promise(r => setTimeout(r, 5));
  const late = await form(env, '3333', { action: 'photo', id: '301', row: 'progress' }, jpg('p.jpg')); assert.equal(late.story.newPhotos, 1);
  // approved: the website always gets a cover when any photo is safe, even if the draft placed none
  const ap = await post(env, '2222', { action: 'approve', id: '301', doc: { title: d.story.doc.title, dek: d.story.doc.dek, sections: d.story.doc.sections.map(x => ({ h: x.h, body: x.body, photo: null })) } });
  assert.equal(ap.ok, true);
  const pub = JSON.parse(env.FEED.m.get('pubstory:301'));
  assert.ok(pub.hero && pub.hero.id, 'a cover photo'); assert.ok(pub.photos.includes(pub.hero.id));
});

test('column dates: Before, In progress (from – to) and After each get a month; captions, the AI facts and the website use them', async () => {
  const { env } = setup();
  await post(env, '3333', { action: 'answers', id: '301', q1: 'Girls walked 6 km home each night', q2: 'There was no dormitory', q5: 'Girls stay at school' });
  const b = await form(env, '3333', { action: 'photo', id: '301', row: 'before' }, jpg('b.jpg'));
  assert.ok(b.story.missing.includes('dates'), 'a column with photos but no month');
  const d = await post(env, '3333', { action: 'dates', id: '301', before: '2024-11', progress: '2025-01', progressEnd: '2025-03', after: '2025-06' });
  assert.equal(d.ok, true); assert.deepEqual(d.story.dates, { before: '2024-11', progress: '2025-01', progressEnd: '2025-03', after: '2025-06' });
  assert.equal(d.story.missing.includes('dates'), false);
  assert.match(d.story.pool.find(p => p.id === b.photo.id).cap, /November 2024$/, 'the photo takes its column month');
  // a photo's own date (from the photo) still wins
  const t = await form(env, '3333', { action: 'photo', id: '301', row: 'before', taken: '2024-10-02' }, jpg('t.jpg'));
  assert.match(t.story.pool.find(p => p.id === t.photo.id).cap, /October 2024$/);
  // in order, none in the future, '' clears
  assert.equal((await post(env, '3333', { action: 'dates', id: '301', after: '2024-01' })).status, 400);
  assert.equal((await post(env, '3333', { action: 'dates', id: '301', after: '2099-01' })).status, 400);
  assert.equal((await post(env, '3333', { action: 'dates', id: '301', before: '2025-13' })).status, 400);
  const c = await post(env, '3333', { action: 'dates', id: '301', progressEnd: '' }); assert.equal(c.story.dates.progressEnd, undefined);
  // the draft and the website copy carry them
  const dr = await post(env, '3333', { action: 'draft', id: '301' });
  assert.deepEqual(dr.story.doc.facts.dates, { before: '2024-11', progress: '2025-01', after: '2025-06' });
  assert.match(dr.story.doc.text, /The work took place in January 2025\./);
  await post(env, '2222', { action: 'approve', id: '301', doc: dr.story.doc });
  assert.deepEqual(JSON.parse(env.FEED.m.get('pubstory:301')).facts.dates, { before: '2024-11', progress: '2025-01', after: '2025-06' });
});
