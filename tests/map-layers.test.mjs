// The home map's layers: the team map stays for admins only, and the project list carries what the
// heat and year layers need (people served, completion year). Run: node --test tests/*.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';

function kv(init = {}) {
  const m = new Map(Object.entries(init).map(([k, v]) => [k, JSON.stringify(v)]));
  return {
    m, get: async (k, t) => { const v = m.get(k); return v == null ? null : t === 'json' ? JSON.parse(v) : v; },
    put: async (k, v) => { m.set(k, String(v)); }, delete: async k => { m.delete(k); },
    list: async ({ prefix }) => ({ keys: [...m.keys()].filter(k => k.startsWith(prefix)).map(name => ({ name })), list_complete: true })
  };
}
globalThis.fetch = async () => new Response(JSON.stringify({ data: {} }));
const P = [
  { id: '1', title: 'Kapsaret Borehole', country: 'Kenya', ck: 'ke', type: 'Borehole well', status: 'Completed', year: 2016, people: 1200, gps: [0.47, 35.25], photos: [] },
  { id: '2', title: 'Magara Well', country: 'Tanzania', ck: 'tz', type: 'Borehole well', status: 'Completed', year: 2021, people: 800, gps: [-3.6, 35.8], photos: [] },
  { id: '3', title: 'Mzuzu Tank', country: 'Malawi', ck: 'mw', type: 'Water collection', status: 'Under construction', year: null, people: null, photos: [] }
];
const store = kv({ pinpeople: { '2': { n: 950, at: '2026-10-01T08:00:00Z' } }, pinhere: {} });
store.m.set('feed:v9', JSON.stringify({ at: Date.now(), feed: { updated: '2026-10-20T00:00:00Z', projects: P } }));
const E = { FEED: store, MONDAY_API_TOKEN: 't', PIN_USERS: 'Reid Anderson:1111, Meril:3333', PIN_ADMINS: 'Reid Anderson' };
const admin = await import('../functions/api/admin.js');
const pin = await import('../functions/api/pin.js');
const get = async (mod, path, code) => {
  const r = await mod.onRequestGet({ request: new Request('https://pin.example' + path, { headers: { 'X-Pin': code, 'CF-Connecting-IP': '1.2.3.4' } }), env: E, waitUntil: p => p });
  return Object.assign(await r.json(), { status: r.status });
};

test('team map on the home map: field users are refused, admins get it', async () => {
  const no = await get(admin, '/api/admin?view=teammap', '3333');
  assert.equal(no.status, 403); assert.equal(no.ok, false); assert.equal(no.reason, 'not-admin');
  assert.equal(no.people, undefined, 'no positions leak to field users');
  const yes = await get(admin, '/api/admin?view=teammap', '1111');
  assert.equal(yes.ok, true); assert.ok(Array.isArray(yes.people)); assert.ok(Array.isArray(yes.visits));
});

test('project list: people served and the completion year for the heat and year layers', async () => {
  const field = await get(pin, '/api/pin', '3333');
  const f = Object.fromEntries(field.projects.map(p => [p.id, p]));
  assert.equal(f['1'].people, 1200); assert.equal(f['1'].year, 2016); assert.equal(f['1'].status, 'Completed');
  assert.equal(f['2'].people, 800, 'field users get the board’s number');
  assert.equal(f['3'].people, null); assert.equal(f['3'].year, null);
  const office = await get(pin, '/api/pin', '1111');
  const o = Object.fromEntries(office.projects.map(p => [p.id, p]));
  assert.equal(o['2'].people, 950, 'admins get the latest count from site visits');
  assert.equal(o['1'].people, 1200);
});
