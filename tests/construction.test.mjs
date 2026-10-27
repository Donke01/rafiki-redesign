// Construction projects with stages (lib/construction.js, /api/pin actions new/stage/stage-meta, /api/pin-photo stage
// photos, donors). monday.com is faked: every GraphQL call is recorded. Run: node --test tests/*.test.mjs
import test from 'node:test';
import assert from 'node:assert/strict';
import { onRequestGet as pinGet, onRequestPost as pinPost } from '../functions/api/pin.js';
import { onRequestPost as photoPost, onRequestGet as photoGet } from '../functions/api/pin-photo.js';
import { stageAllowed, behind, donorStage, applyStage, startRecord, STAGES } from '../lib/construction.js';
import { loadView, donorFile } from '../lib/donor.js';

function kv() {
  const m = new Map();
  return { m, async get(k, t) { const v = m.has(k) ? m.get(k) : null; return v == null ? null : t === 'json' ? JSON.parse(v) : v; },
    async put(k, v) { m.set(k, String(v)); }, async delete(k) { m.delete(k); },
    async list(o) { const p = (o && o.prefix) || ''; return { keys: [...m.keys()].filter(k => k.startsWith(p)).map(name => ({ name })), list_complete: true }; } };
}
const FEED = { projects: [{ id: '205', title: 'Ndalat Jobs Outreach', status: 'Completed', type: 'School', ck: 'ke', country: 'Kenya', photos: [] }, { id: '101', title: 'Kapsaret Borehole', status: 'Completed', type: 'Borehole well', ck: 'ke', photos: [] }] };
const CALLS = [];
let NEXT = 900;
globalThis.fetch = async (url, init) => {
  const u = String(url && url.url || url);
  if (u.includes('/v2/file')) { CALLS.push({ file: true }); return Response.json({ data: { add_file_to_update: { id: '7001' } } }); }
  if (u.startsWith('https://files.example/')) return new Response(new Uint8Array([0xff, 0xd8, 0xff]), { headers: { 'Content-Type': 'image/jpeg' } });
  const b = init && init.body ? JSON.parse(init.body) : {}; const q = b.query || '';
  CALLS.push({ q, v: b.variables });
  if (q.includes('items(ids') && q.includes('board { id }')) return Response.json({ data: { items: [{ id: b.variables.ids[0], name: 'Ndalat Dormitory', board: { id: '1' }, column_values: [{ text: '' }] }] } });
  if (q.includes('create_update')) return Response.json({ data: { create_update: { id: String(NEXT++) } } });
  if (q.includes('create_item')) return Response.json({ data: { create_item: { id: '301' } } });
  if (q.includes('change_column_value')) return Response.json({ data: { change_column_value: { id: '1' } } });
  if (q.includes('assets(ids')) return Response.json({ data: { assets: [{ id: b.variables.ids[0], public_url: 'https://files.example/' + b.variables.ids[0], url_thumbnail: '', file_size: 1000 }] } });
  return Response.json({ data: { items: [], boards: [] } });
};
function setup() {
  const store = kv();
  store.m.set('feed:v9', JSON.stringify({ at: Date.now(), feed: FEED }));
  store.m.set('pinteam', JSON.stringify([{ name: 'Meril' }]));
  return { env: { FEED: store, MONDAY_API_TOKEN: 't', MONDAY_BOARD_ID: '1', PIN_USERS: 'Jessica:2222, Meril:3333', PIN_ADMINS: 'Jessica', PIN_CODE: '9999' }, store };
}
let ip = 10;
const post = (env, pin, body) => pinPost({ env, waitUntil: () => {}, request: new Request('https://pin.example/api/pin', { method: 'POST', headers: { 'X-Pin': pin, 'Content-Type': 'application/json', Origin: 'https://pin.example', 'CF-Connecting-IP': '1.1.1.' + (ip++) }, body: JSON.stringify(body) }) });
const get = (env, pin, q) => pinGet({ env, waitUntil: () => {}, request: new Request('https://pin.example/api/pin' + (q || ''), { headers: { 'X-Pin': pin, 'CF-Connecting-IP': '1.1.2.' + (ip++) } }) });
const statusSet = () => CALLS.filter(c => c.q && c.q.includes('change_column_value')).map(c => JSON.parse(c.v.v).label);

test('the rules: field users one stage on (or the same, for progress photos); admins anything; handing over is for admins', () => {
  assert.deepEqual(STAGES, ['planned', 'foundation', 'walls', 'roof', 'finishing', 'handed']);
  assert.equal(stageAllowed('planned', 'foundation', false), '');
  assert.equal(stageAllowed('walls', 'walls', false), '');
  assert.equal(stageAllowed('planned', 'roof', false), 'not-next');
  assert.equal(stageAllowed('roof', 'walls', false), 'not-next');
  assert.equal(stageAllowed('finishing', 'handed', false), 'admin-only');
  assert.equal(stageAllowed('planned', 'handed', true), '');
  assert.equal(stageAllowed('planned', 'basement', true), 'stage');
  assert.equal(behind({ stage: 'walls', due: '2020-01-01' }), true);
  assert.equal(behind({ stage: 'handed', due: '2020-01-01' }), false);
  assert.equal(behind({ stage: 'walls', due: '2999-01-01' }), false);
});

test('New site “Being built now”, part of Ndalat: Under Construction, tracker at Planned, linked both ways', async () => {
  const { env, store } = setup(); CALLS.length = 0;
  const r = await post(env, '3333', { action: 'new', name: 'Ndalat Dormitory', ck: 'ke', type: 'school', lat: 0.1, lng: 35.1, build: 'building', parent: '205' });
  const j = await r.json();
  assert.equal(j.ok, true); assert.equal(j.id, '301');
  assert.equal(j.stage.stage, 'planned'); assert.equal(j.stage.parent, '205'); assert.equal(j.stage.ptitle, 'Ndalat Jobs Outreach');
  const item = CALLS.find(c => c.q.includes('create_item'));
  assert.match(item.v.v, /Under Construction/);
  assert.match(item.v.v, /Educational/, 'the type stays School (Educational)');
  const idx = JSON.parse(store.m.get('pinstages'));
  assert.equal(idx['301'].s, 'planned'); assert.equal(idx['301'].parent, '205');
  // the parent's answer lists it, the child's names the parent
  const p = await (await get(env, '3333', '?id=205')).json();
  assert.deepEqual(p.linked.children.map(x => [x.id, x.s]), [['301', 'planned']]);
  const c = await (await get(env, '3333', '?id=301')).json();
  assert.equal(c.linked.parent.id, '205'); assert.equal(c.linked.parent.t, 'Ndalat Jobs Outreach');
  // planned / unknown parent
  const pl = await (await post(env, '3333', { action: 'new', name: 'Clinic ward', ck: 'ke', lat: 0.1, lng: 35.1, build: 'planned' })).json();
  assert.equal(pl.ok, true); assert.match(CALLS.filter(x => x.q.includes('create_item')).pop().v.v, /Planned and\/or Permitted/);
  const bad = await post(env, '3333', { action: 'new', name: 'X', ck: 'ke', lat: 0.1, lng: 35.1, build: 'building', parent: '999' });
  assert.equal(bad.status, 400); assert.equal((await bad.json()).reason, 'parent');
  // already built: as before (Due Diligence, no tracker)
  CALLS.length = 0; const old = await (await post(env, '3333', { action: 'new', name: 'Y', ck: 'ke', lat: 0.1, lng: 35.1 })).json();
  assert.equal(old.stage, undefined); assert.match(CALLS.find(x => x.q.includes('create_item')).v.v, /Due Diligence/);
});

test('stage changes: a monday note, Under Construction, history, the team feed; photos land in the stage gallery', async () => {
  const { env, store } = setup(); CALLS.length = 0;
  const r = await (await post(env, '3333', { action: 'stage', id: '301', stage: 'foundation', note: 'Trenches dug', pct: 10, due: '2027-03-01' })).json();
  assert.equal(r.ok, true); assert.equal(r.stage.stage, 'foundation'); assert.equal(r.stage.pct, 10); assert.equal(r.stage.due, '2027-03-01'); assert.equal(r.stage.behind, false);
  const note = CALLS.find(c => c.q.includes('create_update')).v.t;
  assert.match(note, /Stage: Foundation/); assert.match(note, /Trenches dug/); assert.match(note, /10% done/); assert.match(note, /Meril/);
  assert.deepEqual(statusSet(), ['Under Construction']);
  assert.equal(JSON.parse(store.m.get('pinact')).pop().a, 'stage');
  // the photo, uploaded like a visit photo with stage + project
  const fd = new FormData(); fd.append('update', r.updateId); fd.append('label', 'Construction: Foundation'); fd.append('stage', 'foundation'); fd.append('project', '301'); fd.append('file', new Blob([new Uint8Array([0xff, 0xd8, 0xff])], { type: 'image/jpeg' }), 'p.jpg');
  const ph = await (await photoPost({ env, waitUntil: () => {}, request: new Request('https://pin.example/api/pin-photo', { method: 'POST', headers: { 'X-Pin': '3333', Origin: 'https://pin.example' }, body: fd }) })).json();
  assert.deepEqual(ph, { ok: true, asset: '7001' });
  const rec = JSON.parse(store.m.get('pinstage:301'));
  assert.deepEqual(rec.hist.pop().photos, ['7001']);
  assert.equal(JSON.parse(store.m.get('pinstages'))['301'].n, 1);
  // the gallery photo: signed-in team only, only assets in the record
  const ok = await photoGet({ env, request: new Request('https://pin.example/api/pin-photo?stage=301&asset=7001', { headers: { 'X-Pin': '3333' } }) });
  assert.equal(ok.status, 200); assert.equal(ok.headers.get('Content-Type'), 'image/jpeg');
  assert.equal((await photoGet({ env, request: new Request('https://pin.example/api/pin-photo?stage=301&asset=7002', { headers: { 'X-Pin': '3333' } }) })).status, 404);
  assert.equal((await photoGet({ env, request: new Request('https://pin.example/api/pin-photo?stage=301&asset=7001') })).status, 401);
  // field user: skipping is refused, handing over is admins only
  assert.equal((await post(env, '3333', { action: 'stage', id: '301', stage: 'roof' })).status, 409);
  assert.equal((await post(env, '3333', { action: 'stage', id: '301', stage: 'handed' })).status, 403);
  assert.equal((await post(env, '3333', { action: 'stage', id: '301', stage: 'attic' })).status, 400);
  // the snapshot carries the index
  const s = await (await get(env, '3333')).json();
  assert.equal(s.stages['301'].s, 'foundation'); assert.equal(s.stages['301'].behind, false);
  // % done and expected finish
  const m = await (await post(env, '3333', { action: 'stage-meta', id: '301', pct: 35, due: '2020-01-01' })).json();
  assert.equal(m.stage.pct, 35); assert.equal(m.stage.behind, true);
  assert.equal((await post(env, '3333', { action: 'stage-meta', id: '301', pct: 140 })).status, 400);
  // an admin hands it over: Completed/Operational
  CALLS.length = 0;
  const h = await (await post(env, '2222', { action: 'stage', id: '301', stage: 'handed', note: 'Keys to the head teacher' })).json();
  assert.equal(h.stage.stage, 'handed'); assert.equal(h.stage.pct, 100); assert.equal(h.stage.behind, false);
  assert.deepEqual(statusSet(), ['Completed/Operational']);
});

test('donors see the stages and photos of projects they fund, with no names or notes', async () => {
  const { env, store } = setup();
  let r = startRecord('205', 'Meril', null, '', 'start', '1');
  r = applyStage(r, { id: '205', stage: 'walls', by: 'Meril', note: 'secret note', update: '2' }); r.hist[1].photos = ['8001'];
  store.m.set('pinstage:205', JSON.stringify(r));
  const d = donorStage(r);
  assert.deepEqual(Object.keys(d).sort(), ['at', 'behind', 'due', 'pct', 'stage', 'stages']);
  assert.equal(JSON.stringify(d).includes('Meril') || JSON.stringify(d).includes('secret'), false);
  const donor = { id: 'd1', name: 'A', projects: ['205'], showPhotos: true, photos: { 205: 'on' } };
  const v = await loadView(env, donor, {}, { feed: FEED });
  assert.equal(v.projects[0].construction.stage, 'walls');
  assert.deepEqual(v.projects[0].construction.stages.map(x => [x.stage, x.photos]), [['planned', []], ['walls', ['8001']]]);
  assert.ok(await donorFile(env, donor, '8001', FEED));
  const shy = Object.assign({}, donor, { showPhotos: false });
  const v2 = await loadView(env, shy, {}, { feed: FEED });
  assert.deepEqual(v2.projects[0].construction.stages.map(x => x.photos), [[], []]);
  assert.equal(await donorFile(env, shy, '8001', FEED), null);
});
