// /api/pin: a visit sent with qr: true says so in its monday.com note; offlineTiles only for servers that allow it.
// Run: node qa/pin/field/visit-qr.mjs
import { onRequestPost, offlineTiles } from '../../../functions/api/pin.js';
const notes = [];
globalThis.fetch = async (url, o) => {
  const b = JSON.parse(o.body);
  if (b.query.includes('items(ids')) return new Response(JSON.stringify({ data: { items: [{ id: '104', name: 'Kapsaret Borehole', board: { id: '1' }, column_values: [{ text: '0.47, 35.25' }] }] } }));
  if (b.query.includes('create_update')) { notes.push(b.variables.t); return new Response(JSON.stringify({ data: { create_update: { id: String(9000 + notes.length) } } })); }
  return new Response(JSON.stringify({ data: {} }));
};
const kv = new Map();
const env = { MONDAY_API_TOKEN: 't', MONDAY_BOARD_ID: '1', PIN_USERS: 'Meril:4821:ke', FEED: { get: async (k, t) => { const v = kv.get(k); return v == null ? null : t === 'json' ? JSON.parse(v) : v; }, put: async (k, v) => { kv.set(k, v); }, delete: async k => { kv.delete(k); } } };
const req = body => new Request('https://pin.rafikioutreach.org/api/pin', { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-Pin': '4821', Origin: 'https://pin.rafikioutreach.org' }, body: JSON.stringify(body) });
let fails = 0; const ok = (c, w) => { console.log((c ? 'ok   ' : 'FAIL ') + w); if (!c) fails++; };
let r = await onRequestPost({ request: req({ action: 'visit', id: '104', state: 'working', qr: true, lat: 0.47, lng: 35.25 }), env, waitUntil: () => {} });
ok(r.status === 200, 'visit with qr accepted');
ok(notes[0].includes('Checked in by scanning the site’s QR code.') && notes[0].split('<br>')[1] === 'Checked in by scanning the site’s QR code.', 'note line 2: ' + notes[0].split('<br>')[1]);
ok(JSON.parse(kv.get('pinvisit:104'))[0].qr === true, 'kept on the visit record');
r = await onRequestPost({ request: req({ action: 'visit', id: '104', state: 'working', qr: 'yes' }), env, waitUntil: () => {} });
ok(!notes[1].includes('QR code'), 'only qr: true counts');
ok(offlineTiles({ PIN_OFFLINE_TILES: 'https://tiles.example.org/{z}/{x}/{y}.png' }).maxZoom === 16, 'office tile server accepted');
ok(offlineTiles({ PIN_OFFLINE_TILES: 'https://tile.openstreetmap.org/{z}/{x}/{y}.png' }) === null && offlineTiles({ PIN_OFFLINE_TILES: 'https://a.tile.openstreetmap.fr/hot/{z}/{x}/{y}.png' }) === null && offlineTiles({ PIN_OFFLINE_TILES: 'https://server.arcgisonline.com/x/{z}/{y}/{x}' }) === null && offlineTiles({}) === null, 'no-bulk servers and unset: null');
console.log(fails ? 'PROBLEMS ' + fails : 'PROBLEMS []');
process.exit(fails ? 1 : 0);
