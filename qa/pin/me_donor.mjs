// A donor's view with a construction project and an approved story, made by the real lib/donor.js (for qa/pin/me.py).
//   node qa/pin/me_donor.mjs > out.json
import { loadView } from '../../lib/donor.js';
import { startRecord, applyStage } from '../../lib/construction.js';
const m = new Map(), kv = { async get(k, t) { const v = m.get(k); return v == null ? null : t === 'json' ? JSON.parse(v) : v; }, async put(k, v) { m.set(k, String(v)); }, async delete(k) { m.delete(k); } };
const now = Date.now(), iso = d => new Date(now - d * 864e5).toISOString();
const feed = { projects: [
  { id: '301', title: 'Ndalat Girls’ Dormitory', type: 'School', cat: 'school', country: 'Kenya', ck: 'ke', area: 'Ndalat, Nandi', status: 'Under construction', year: 2026, people: 120, photos: [], gps: [0.2, 35.1] },
  { id: '101', title: 'Kapsaret Borehole', type: 'Borehole well', cat: 'water', water: true, country: 'Kenya', ck: 'ke', area: 'Kapsaret, Eldoret', status: 'Completed', year: 2024, done: '2024-05-02', people: 600, photos: [], gps: [0.47, 35.25],
    story: { text: 'Before the borehole, families in Kapsaret walked two hours to a seasonal river.\n\nToday 600 people draw clean water ten minutes from home, and the girls are back in class on time.', quote: 'Now my girls get to school on time.', quoteBy: 'Mary, mother of three', credit: 'Meril Kiprop', after: ['9101', '9102'] } }] };
let r = startRecord('301', 'Meril Kiprop', null, '', '', '1', now - 60 * 864e5);
r = applyStage(r, { id: '301', stage: 'foundation', by: 'Meril Kiprop', now: now - 40 * 864e5 }); r.hist[1].photos = ['9001', '9002'];
r = applyStage(r, { id: '301', stage: 'walls', by: 'Meril Kiprop', pct: 45, due: new Date(now + 70 * 864e5).toISOString().slice(0, 10), now: now - 6 * 864e5 }); r.hist[2].photos = ['9003'];
m.set('pinstage:301', JSON.stringify(r));
const donor = { id: 'd1', name: 'Grace Lee', projects: ['301', '101'], showPhotos: true, photos: { 301: 'on', 101: 'on' } };
const v = await loadView({ FEED: kv }, donor, { name: 'Grace Lee' }, { feed, now });
console.log(JSON.stringify(Object.assign({ ok: true, me: { mail: true }, groupTrips: [] }, v)));
