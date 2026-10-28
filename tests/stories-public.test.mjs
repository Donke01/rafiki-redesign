// Stories 2 on the website: approve = publish (functions/api/story.js), the public copy and list (pubstory:<id>,
// pubstoryidx) read by /api/stories and /api/stories/photo, the copy to monday.com, and withdrawing a story.
// monday.com is a fake board kept in memory. Run: node --test tests/*.test.mjs
import test from 'node:test';
import assert from 'node:assert/strict';
import { onRequestPost, onRequestGet } from '../functions/api/story.js';
import { onRequestGet as storiesGet } from '../functions/api/stories.js';
import { onRequestGet as photoGet, width } from '../functions/api/stories/photo.js';
import { STORY_COL } from '../lib/monday.js';

function kv() {
  const m = new Map();
  return { m, async get(k, t) { const v = m.has(k) ? m.get(k) : null; return v == null ? null : t === 'json' ? JSON.parse(v) : v; },
    async put(k, v) { m.set(k, String(v)); }, async delete(k) { m.delete(k); }, async list() { return { keys: [], list_complete: true }; } };
}
let COLS, FILES, ASSET, NOTES, LABELS, MAXTEXT, FETCHED;
function reset() {
  COLS = { color_mkrccgae: { text: 'Educational' }, text_mks3h8ek: { text: 'Ndalat' }, numeric_mks3m7q8: { text: '420' }, datenk2ra6oh: { text: '2026-03-02' }, single_selectxrldna6: { text: 'Completed/Operational' },
    country_mkswymy5: { text: 'Kenya', value: JSON.stringify({ countryCode: 'KE', countryName: 'Kenya' }) }, text_mks3437k: { text: '0.21, 35.12' } };
  FILES = {}; ASSET = 5000; NOTES = []; LABELS = null; MAXTEXT = 100000; FETCHED = [];
}
const fileCol = id => ({ text: '', value: JSON.stringify({ files: (FILES[id] || []).map(f => ({ assetId: f.id, name: f.name })) }) });
globalThis.fetch = async (url, init) => {
  const u = String(url && url.url || url);
  if (u.startsWith('https://files.example/')) { FETCHED.push(u); return new Response(new Uint8Array([1, 2, 3]), { headers: { 'Content-Type': 'image/jpeg' } }); }
  if (u.includes('/v2/file')) {
    const q = init.body.get('query'), m = /column_id: "([^"]+)"/.exec(q);
    const id = String(ASSET++); (FILES[m[1]] = FILES[m[1]] || []).push({ id, name: init.body.get('variables[file]').name }); return Response.json({ data: { add_file_to_column: { id } } });
  }
  const b = JSON.parse(init.body), q = b.query;
  if (q.includes('change_multiple_column_values')) {
    const v = JSON.parse(b.variables.v);
    if (v[STORY_COL.text] && v[STORY_COL.text].text.length > MAXTEXT) return Response.json({ errors: [{ message: 'value too long' }] });
    if (v[STORY_COL.status] && LABELS && !LABELS.includes(v[STORY_COL.status].label)) return Response.json({ errors: [{ message: 'label not found' }] });
    Object.keys(v).forEach(k => { const x = v[k]; COLS[k] = { text: typeof x === 'string' ? x : x.text != null ? x.text : x.label }; });
    return Response.json({ data: { change_multiple_column_values: { id: '1' } } });
  }
  if (q.includes('settings_str')) return Response.json({ data: { boards: [{ columns: LABELS ? [{ id: STORY_COL.status, settings_str: JSON.stringify({ labels: Object.fromEntries(LABELS.map((l, i) => [String(i), l])) }) }] : [] }] } });
  if (q.includes('column_values(ids: $c)')) {
    const cvs = b.variables.c.map(id => (id.startsWith('file_') ? Object.assign({ id }, fileCol(id)) : { id, text: (COLS[id] || {}).text || '', value: (COLS[id] || {}).value || null }));
    return Response.json({ data: { items: [{ id: b.variables.ids[0], name: 'NDALAT GIRLS DORMITORY', board: { id: '1' }, column_values: cvs }] } });
  }
  if (q.includes('create_update')) { NOTES.push(b.variables.t); return Response.json({ data: { create_update: { id: String(70 + NOTES.length) } } }); }
  if (q.includes('updates(limit: 15)')) return Response.json({ data: { items: [{ updates: [{ id: '9', text_body: 'Visited: the girls sleep there now. Paid the matron KSh 5,000. Her number is 0712 345 678.', created_at: '2026-03-10T08:00:00Z', creator: { name: 'Meril' }, assets: [] }] }] } });
  if (q.includes('assets(ids')) return Response.json({ data: { assets: [{ id: b.variables.ids[0], public_url: 'https://files.example/' + b.variables.ids[0], url_thumbnail: '', file_size: 3 }] } });
  return Response.json({ data: { items: [] } });
};
function setup() {
  reset(); const store = kv();
  store.m.set('feed:v9', JSON.stringify({ at: Date.now(), feed: { projects: [] } }));
  return { env: { FEED: store, MONDAY_API_TOKEN: 't', MONDAY_BOARD_ID: '1', PIN_USERS: 'Jessica:2222, Meril:3333', PIN_ADMINS: 'Jessica', PIN_CODE: '9999', STORY_LINK_ORIGIN: 'https://site.example' }, store };
}
let ip = 1;
const H = pin => ({ 'X-Pin': pin, Origin: 'https://pin.example', 'CF-Connecting-IP': '8.8.8.' + (ip++ % 250) });
const post = async (env, pin, body) => { const r = await onRequestPost({ env, request: new Request('https://pin.example/api/story', { method: 'POST', headers: Object.assign({ 'Content-Type': 'application/json' }, H(pin)), body: JSON.stringify(body) }) }); return Object.assign({ status: r.status }, await r.json()); };
const form = async (env, pin, fields, file) => { const fd = new FormData(); Object.keys(fields).forEach(k => fd.append(k, fields[k])); fd.append('file', file, file.name); const r = await onRequestPost({ env, request: new Request('https://pin.example/api/story', { method: 'POST', headers: H(pin), body: fd }) }); return Object.assign({ status: r.status }, await r.json()); };
const jpg = n => Object.assign(new Blob([new Uint8Array([0xff, 0xd8, 0xff])], { type: 'image/jpeg' }), { name: n });
const pub = async (env, q) => { const r = await storiesGet({ env, request: new Request('https://site.example/api/stories' + (q || '')) }); return Object.assign({ status: r.status, cache: r.headers.get('Cache-Control') }, await r.json()); };
const photo = (env, q, h) => photoGet({ env, waitUntil: () => {}, request: new Request('https://site.example/api/stories/photo' + q, { headers: h || {} }) });

async function collected(env, kids) {
  await post(env, '3333', { action: 'answers', id: '301', q1: 'Girls walked 6 km home each night', q2: 'There was no dormitory', q3: '120 girls', q4: 'Parents carried sand and water', q5: 'Girls stay at school and study at night', q6: 'The first night 40 girls slept there', quote: 'Now we can study at night', quoteBy: 'Faith, Form 3' });
  const b = await form(env, '3333', { action: 'photo', id: '301', row: 'before', children: 'false' }, jpg('before.jpg'));
  const a = await form(env, '3333', { action: 'photo', id: '301', row: 'after', children: kids ? 'true' : 'false' }, jpg('after.jpg'));
  await post(env, '3333', { action: 'consent', id: '301', value: 'received' });
  const d = await post(env, '3333', { action: 'draft', id: '301' });
  return { before: b.photo.id, after: a.photo.id, d };
}

test('nothing published: an empty list, 404 for a story and its photos', async () => {
  const { env } = setup();
  const l = await pub(env); assert.equal(l.ok, true); assert.deepEqual(l.stories, []); assert.match(l.cache, /^public/);
  assert.equal((await pub(env, '?id=301')).status, 404);
  assert.equal((await photo(env, '?id=301&asset=5000')).status, 404);
  const { store } = setup(); delete store.m; assert.deepEqual((await pub({})).stories, []);
});

test('approve and publish: the public copy and list, monday.com gets the status, the story and a note with the link', async () => {
  const { env, store } = setup();
  const { before, after, d } = await collected(env, true);
  assert.equal(d.story.status, 'draft'); assert.ok(d.story.doc.sections.length >= 5);
  assert.ok(d.story.pool.some(p => p.id === after), 'the writer can pick from the photos');
  assert.equal(/0712|5,000|KSh/.test(JSON.stringify(d.story.doc)), false, 'the visit note was cleaned');
  NOTES = [];
  // the new app sends the edited document with the approval
  const doc = JSON.parse(JSON.stringify(d.story.doc)); doc.title = 'A dormitory for Ndalat'; doc.sections[0].body += '\n\nAdded by the office.';
  const r = await post(env, '2222', { action: 'approve', id: '301', doc });
  assert.equal(r.ok, true); assert.equal(r.story.status, 'published'); assert.equal(COLS[STORY_COL.status].text, 'Published');
  assert.equal(r.url, 'https://site.example/stories/301-a-dormitory-for-ndalat'); assert.equal(r.story.pub.url, r.url); assert.equal(r.monday, 'full');
  assert.equal(COLS[STORY_COL.text].text, r.story.doc.text); assert.match(COLS[STORY_COL.text].text, /^A dormitory for Ndalat\n\n/);
  assert.equal(NOTES.length, 1); assert.match(NOTES[0], /approved and published on the website by Jessica/); assert.match(NOTES[0], /href="https:\/\/site\.example\/stories\/301-a-dormitory-for-ndalat"/); assert.match(NOTES[0], /Added by the office\./);
  // the public copy, as the contract has it
  const s = (await pub(env, '?id=301')).story;
  assert.equal(s.id, '301'); assert.equal(s.slug, 'a-dormitory-for-ndalat'); assert.equal(s.title, 'A dormitory for Ndalat'); assert.equal(s.approvedBy, 'Jessica');
  assert.deepEqual(s.project, { title: 'Ndalat Girls Dormitory', area: 'Ndalat', country: 'Kenya', ck: 'ke', type: 'School', status: 'Completed', people: 420, gps: [0.21, 35.12] });
  assert.equal(s.facts.country, 'Kenya'); assert.equal(s.facts.year, 2026); assert.deepEqual(s.pull, { quote: 'Now we can study at night', who: 'Faith, Form 3' });
  assert.deepEqual(s.hero, { id: after, row: 'after', cap: s.hero.cap }); assert.ok(s.photos.includes(after) && s.photos.includes(before));
  const txt = JSON.stringify(s);
  for (const no of ['0712', 'KSh', '5,000', 'Meril', 'model', '"by"', 'children', '"text"']) assert.equal(txt.includes(no), false, 'nothing private: ' + no);
  const l = await pub(env); assert.equal(l.stories.length, 1);
  assert.deepEqual(Object.keys(l.stories[0]).sort(), ['approvedAt', 'ck', 'country', 'dek', 'hero', 'id', 'place', 'slug', 'title', 'type', 'words', 'year'].sort());
  // the photo: only one of that story's photos; resized width within 320..1920; cached publicly with an ETag
  const ok = await photo(env, '?id=301&asset=' + after + '&w=700');
  assert.equal(ok.status, 200); assert.equal(ok.headers.get('Content-Type'), 'image/jpeg'); assert.equal(ok.headers.get('Cache-Control'), 'public, max-age=86400');
  const tag = ok.headers.get('ETag'); assert.equal(tag, '"s' + after + '-960-jpeg"');
  assert.equal((await photo(env, '?id=301&asset=' + after + '&w=700', { 'If-None-Match': tag })).status, 304);
  assert.equal((await photo(env, '?id=301&asset=99999')).status, 404, 'an asset not in the story');
  assert.equal((await photo(env, '?id=302&asset=' + after)).status, 404, 'another project');
  assert.deepEqual([width(10), width(320), width(321), width(5000), width('x')], [320, 320, 480, 1920, 1280]);
  // publish again: nothing to do; a field user cannot withdraw it
  NOTES = []; assert.equal((await post(env, '2222', { action: 'publish', id: '301' })).ok, true); assert.equal(NOTES.length, 0);
  assert.equal((await post(env, '3333', { action: 'collecting', id: '301' })).status, 403);
  // an admin's edit after publishing goes to the website too
  const doc2 = JSON.parse(JSON.stringify(r.story.doc)); doc2.dek = 'A new standfirst.';
  const e = await post(env, '2222', { action: 'doc', id: '301', doc: doc2 }); assert.equal(e.story.doc.dek, 'A new standfirst.');
  assert.equal((await pub(env, '?id=301')).story.dek, 'A new standfirst.'); assert.equal((await pub(env)).stories[0].dek, 'A new standfirst.');
  // back to Collecting: off the website (story, list, photos), and noted on monday.com
  const w = await post(env, '2222', { action: 'collecting', id: '301' });
  assert.equal(w.story.status, 'collecting'); assert.equal(w.story.pub, null);
  assert.equal((await pub(env, '?id=301')).status, 404); assert.deepEqual((await pub(env)).stories, []);
  assert.equal((await photo(env, '?id=301&asset=' + after)).status, 404);
  assert.match(NOTES[NOTES.length - 1], /taken off the website by Jessica/);
  assert.equal(store.m.has('pubstory:301'), false);
});

test('a photo of children reaches the website only with consent; the old app’s approve gives the same result', async () => {
  const { env } = setup();
  const { after } = await collected(env, true);
  await post(env, '3333', { action: 'consent', id: '301', value: 'not-yet' });
  assert.equal((await post(env, '2222', { action: 'approve', id: '301' })).reason, 'consent');
  await post(env, '3333', { action: 'consent', id: '301', value: 'received' });
  // Android 2.26 sends the whole text back with approve
  const cur = (await post(env, '3333', { action: 'answers', id: '301', credit: 'Meril' })).story;
  const r = await post(env, '2222', { action: 'approve', id: '301', text: cur.text });
  assert.equal(r.story.status, 'published'); assert.ok(r.story.pub);
  const s = (await pub(env, '?id=301')).story; assert.ok(s.photos.includes(after));
  assert.deepEqual(s.sections.map(x => x.h), cur.doc.sections.map(x => x.h), 'the sections are kept');
});

test('the board has no "Published" label: "Approved"; the Story column refuses a long text: the first part and the link', async () => {
  const { env } = setup();
  await collected(env, false);
  LABELS = ['Not started', 'Collecting', 'Draft ready', 'Approved']; MAXTEXT = 600;
  const r = await post(env, '2222', { action: 'approve', id: '301' });
  assert.equal(r.ok, true); assert.equal(COLS[STORY_COL.status].text, 'Approved'); assert.equal(r.story.status, 'approved'); assert.ok(r.story.pub);
  assert.equal(r.monday, 'short'); assert.match(COLS[STORY_COL.text].text, /\n\n… \(full story on the website: https:\/\/site\.example\/stories\/301-[a-z-]+\)$/);
  assert.ok(COLS[STORY_COL.text].text.length < r.story.doc.text.length);
  assert.equal(r.story.text, r.story.doc.text, 'the app still shows all of it');
  assert.equal((await pub(env)).stories.length, 1);
});

test('a story approved before Stories 2 (plain text, no document) is published as one section on Publish', async () => {
  const { env, store } = setup();
  await collected(env, false);
  const rec = JSON.parse(store.m.get('pinstory:301')); delete rec.doc; store.m.set('pinstory:301', JSON.stringify(rec));
  COLS[STORY_COL.text] = { text: 'Old words, paragraph one.\n\nParagraph two.\n\n“Now we can study at night” — Faith, Form 3' }; COLS[STORY_COL.status] = { text: 'Approved' };
  const r = await post(env, '2222', { action: 'publish', id: '301' });
  assert.equal(r.ok, true); const s = (await pub(env, '?id=301')).story;
  assert.equal(s.sections.length, 1); assert.equal(s.sections[0].body, 'Old words, paragraph one.\n\nParagraph two.'); assert.ok(s.pull);
});

test('building-stage photos: offered to the writer, shown in the app, placed in the draft and published', async () => {
  const { env, store } = setup();
  store.m.set('pinstage:301', JSON.stringify({ id: '301', stage: 'handed', hist: [{ stage: 'planned', at: '2025-11-01T00:00:00Z', photos: [] }, { stage: 'walls', at: '2026-02-01T00:00:00Z', note: 'Walls up to window level', photos: ['7701'] }, { stage: 'handed', at: '2026-03-02T00:00:00Z', note: '', photos: ['7702'] }] }));
  const { d } = await collected(env, false);
  assert.ok(d.story.pool.some(p => p.id === '7701' && p.row === 'stage:walls' && p.cap === 'Raising the walls, February 2026'));
  assert.ok(d.story.doc.sections.some(x => x.h === 'Raising the walls' && x.photo && x.photo.id === '7701'));
  const r = await onRequestGet({ env, request: new Request('https://pin.example/api/story?id=301&asset=7701', { headers: H('3333') }) });
  assert.equal(r.status, 200, 'the app can show a stage photo');
  await post(env, '2222', { action: 'approve', id: '301' });
  const s = (await pub(env, '?id=301')).story;
  assert.ok(s.photos.includes('7701')); assert.equal(s.facts.stages.find(g => g.stage === 'walls').photo.id, '7701');
  assert.equal((await photo(env, '?id=301&asset=7701')).status, 200);
});
