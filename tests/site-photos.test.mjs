// New sites from the field: their photos sit on the "New site added" note (never public by themselves). Approving the
// site in Office › Review copies them into "Photos of the project", the column the website shows; approved sites from
// before are caught up when Review is opened; a site an admin adds (no review) gets its photos there as they upload.
// Run: node --test tests/*.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';

function kv(init = {}) {
  const m = new Map(Object.entries(init).map(([k, v]) => [k, typeof v === 'string' ? v : JSON.stringify(v)]));
  return { m, get: async (k, t) => { const v = m.get(k); return v == null ? null : t === 'json' ? JSON.parse(v) : v; },
    put: async (k, v) => { m.set(k, String(v)); }, delete: async k => { m.delete(k); },
    list: async ({ prefix }) => ({ keys: [...m.keys()].filter(k => k.startsWith(prefix)).map(name => ({ name })), list_complete: true }) };
}
const NOTE = '🆕 New site added from the field with the RAFIKI pin app by Meril. Location: 0.51234, 35.27011.';
const W = { column: {}, uploads: [], notes: [], updates: {} };   // the fake monday.com
function reset() { W.column = {}; W.uploads = []; W.notes = []; W.updates = {
  '109': [{ text_body: 'Visit later', assets: [{ id: '7', name: 'other.jpg', public_url: 'https://s3/other.jpg' }] },
          { text_body: NOTE, assets: [{ id: '1', name: 'site-photo-tank.jpg', public_url: 'https://s3/1.jpg' }, { id: '2', name: 'voice-note.m4a', public_url: 'https://s3/2.m4a' }, { id: '3', name: 'site-photo-2.png', public_url: 'https://s3/3.png' }] }],
  '110': [{ text_body: NOTE, assets: [{ id: '4', name: 'site-photo-old.jpg', public_url: 'https://s3/4.jpg' }] }],
  '120': [{ id: '501', text_body: '🩺 Site visit by Meril. Story moment: “Clean water at school” (Headteacher). Consent to quote and photograph: given.', assets: [{ id: '8', name: 'site-photo-water-flowing.jpg', public_url: 'https://s3/8.jpg' }] },
          { id: '500', text_body: '🩺 Site visit by Meril, last month.', assets: [{ id: '9', name: 'site-photo-older-visit.jpg', public_url: 'https://s3/9.jpg' }] }] }; }
const ok = data => new Response(JSON.stringify({ data }), { headers: { 'Content-Type': 'application/json' } });
globalThis.fetch = async (url, o) => {
  url = String(url);
  if (url.startsWith('https://s3/')) return new Response(new Uint8Array([0xff, 0xd8, 1, 2]), { headers: { 'Content-Type': 'image/jpeg' } });
  if (url.endsWith('/file')) {
    const q = o.body.get('query'), f = o.body.get('variables[file]');
    const m = /add_file_to_column\(item_id: (\d+), column_id: "([a-z0-9_]+)"/.exec(q);
    if (m) { W.uploads.push({ item: m[1], col: m[2], name: f.name }); (W.column[m[1]] = W.column[m[1]] || []).push(f.name); return ok({ add_file_to_column: { id: '99' } }); }
    return ok({ add_file_to_update: { id: '55' } });
  }
  const b = JSON.parse(o.body || '{}'), q = b.query || '';
  if (/create_update/.test(q)) { W.notes.push(b.variables.t); return ok({ create_update: { id: String(8000 + W.notes.length) } }); }
  if (/create_item/.test(q)) return ok({ create_item: { id: '111' } });
  if (/updates\(limit/.test(q)) { const id = b.variables.ids[0]; return ok({ items: [{ column_values: [{ value: JSON.stringify({ files: (W.column[id] || []).map(n => ({ name: n })) }) }], updates: W.updates[id] || [] }] }); }
  return ok({});
};
function env(store) { store.m.set('feed:v9', JSON.stringify({ at: Date.now(), feed: { updated: 'x', projects: [] } })); return { FEED: store, MONDAY_API_TOKEN: 't', PIN_USERS: 'Reid Anderson:1111, Meril:3333', PIN_ADMINS: 'Reid Anderson' }; }
const admin = await import('../functions/api/admin.js');
const pin = await import('../functions/api/pin.js');
const photo = await import('../functions/api/pin-photo.js');
const req = (path, p, body) => new Request('https://pin.example' + path, { method: body ? 'POST' : 'GET', headers: Object.assign({ 'X-Pin': p, 'CF-Connecting-IP': '1.2.3.4' }, body ? { 'Content-Type': 'application/json', Origin: 'https://pin.example' } : {}), body: body ? JSON.stringify(body) : undefined });
const waits = [];
const call = async (E, mod, path, p, body) => { const r = await (body ? mod.onRequestPost : mod.onRequestGet)({ request: req(path, p, body), env: E, waitUntil: x => waits.push(x) }); return r.json(); };

test('approving a new site puts its photos (not voice notes, not other notes) in "Photos of the project"', async () => {
  reset();
  const store = kv({ pinreview: [{ rid: 'r2', kind: 'new', id: '109', t: 'Kipsangui Spring', by: 'Meril', at: '2026-10-25T08:00:00Z', status: 'open', data: {} }] }), E = env(store);
  const j = await call(E, admin, '/api/admin', '1111', { action: 'review', rid: 'r2', decision: 'approve' });
  assert.equal(j.ok, true);
  assert.deepEqual(W.uploads, [{ item: '109', col: 'file_mkrcpk9c', name: 'site-photo-tank.jpg' }, { item: '109', col: 'file_mkrcpk9c', name: 'site-photo-2.png' }]);
  const it = JSON.parse(store.m.get('pinreview'))[0];
  assert.equal(it.photos, 2); assert.ok(it.pub);
  assert.ok(W.notes.some(n => /New site reviewed and approved/.test(n)));
});

test('turning a site down publishes nothing', async () => {
  reset();
  const store = kv({ pinreview: [{ rid: 'r2', kind: 'new', id: '109', t: 'Kipsangui Spring', by: 'Meril', at: '2026-10-25T08:00:00Z', status: 'open', data: {} }] }), E = env(store);
  await call(E, admin, '/api/admin', '1111', { action: 'review', rid: 'r2', decision: 'reject', reason: 'Wrong place' });
  assert.deepEqual(W.uploads, []);
});

test('sites approved before: opening Review copies their photos once', async () => {
  reset(); W.column['109'] = ['site-photo-tank.jpg'];   // one already there
  const store = kv({ pinreview: [
    { rid: 'a', kind: 'new', id: '110', t: 'Old site', by: 'Meril', at: 'x', status: 'approved' },
    { rid: 'b', kind: 'new', id: '109', t: 'Kipsangui Spring', by: 'Meril', at: 'x', status: 'approved' },
    { rid: 'c', kind: 'new', id: '112', t: 'Turned down', by: 'Meril', at: 'x', status: 'rejected' }] }), E = env(store);
  await call(E, admin, '/api/admin?view=review', '1111'); await Promise.all(waits.splice(0));
  assert.deepEqual(W.uploads.map(u => u.item + ':' + u.name), ['110:site-photo-old.jpg', '109:site-photo-2.png']);
  const rv = JSON.parse(store.m.get('pinreview'));
  assert.ok(rv[0].pub && rv[1].pub && !rv[2].pub);
  W.uploads = []; await call(E, admin, '/api/admin?view=review', '1111'); await Promise.all(waits.splice(0));
  assert.deepEqual(W.uploads, [], 'not again');
});

test('a site an admin adds (no review): its photos go to the column as they upload; a field user’s do not', async () => {
  reset();
  const store = kv(), E = env(store);
  const site = { action: 'new', name: 'Kaptebee Spring', ck: 'ke', lat: 0.5, lng: 35.2, type: 'spring' };
  const a = await call(E, pin, '/api/pin', '1111', site);
  assert.equal(a.ok, true);
  const up = async (p, u) => { const fd = new FormData(); fd.append('update', u); fd.append('file', new File([new Uint8Array([0xff, 0xd8, 1])], 'x.jpg', { type: 'image/jpeg' }));
    return (await photo.onRequestPost({ request: new Request('https://pin.example/api/pin-photo', { method: 'POST', headers: { 'X-Pin': p, Origin: 'https://pin.example' }, body: fd }), env: E, waitUntil: () => {} })).json(); };
  assert.equal((await up('1111', a.updateId)).ok, true);
  assert.equal(W.uploads.length, 1); assert.equal(W.uploads[0].item, '111'); assert.equal(W.uploads[0].col, 'file_mkrcpk9c');
  W.uploads = [];
  const f = await call(E, pin, '/api/pin', '3333', site);
  assert.equal(f.ok, true);
  await up('3333', f.updateId);
  assert.deepEqual(W.uploads, [], 'waits for an admin to approve');
});

test('approving a story moment (told with consent) puts that visit’s photos, and only those, on the website', async () => {
  reset();
  const store = kv({ pinreview: [{ rid: 's1', kind: 'story', id: '120', t: 'Kapsaret School', by: 'Meril', at: 'x', status: 'open', data: { quote: 'Clean water at school', who: 'Headteacher', update: '501' } }] }), E = env(store);
  const j = await call(E, admin, '/api/admin', '1111', { action: 'review', rid: 's1', decision: 'approve' });
  assert.equal(j.ok, true);
  assert.deepEqual(W.uploads, [{ item: '120', col: 'file_mkrcpk9c', name: 'site-photo-water-flowing.jpg' }]);
  assert.ok(W.notes.some(n => /Story moment approved for the website/.test(n)));
});

test('stories approved before are caught up too', async () => {
  reset();
  const store = kv({ pinreview: [{ rid: 's1', kind: 'story', id: '120', t: 'Kapsaret School', by: 'Meril', at: 'x', status: 'approved', data: { quote: 'q', update: '501' } }] }), E = env(store);
  await call(E, admin, '/api/admin?view=review', '1111'); await Promise.all(waits.splice(0));
  assert.deepEqual(W.uploads.map(u => u.name), ['site-photo-water-flowing.jpg']);
});
