// Builds the mocked /api/donor answer for qa/pin/front36.py with the real lib/donor.js (prints JSON).
import { buildView } from '../../lib/donor.js';
const now = Date.parse('2026-10-21T09:00:00Z');
const feed = { projects: [
  { id: '101', title: 'Kapsaret Borehole', country: 'Kenya', ck: 'ke', area: 'Kapsaret, Eldoret', type: 'Borehole well', cat: 'water', water: true, pump: 'Manual', people: 1200, year: 2025, done: '2025-10-05', status: 'Completed', photos: ['900'], gps: [0.470123, 35.250456], depth: 80, flow: 900 },
  { id: '102', title: 'Ndalat Primary School', country: 'Kenya', ck: 'ke', area: 'Ndalat, Nandi', type: 'School', cat: 'school', people: 600, year: 2024, done: '2024-03-12', status: 'Completed', photos: ['901'], gps: [0.201544, 35.104221] },
  { id: '103', title: 'Turbo Spring Well', country: 'Kenya', ck: 'ke', area: 'Turbo', type: 'Borehole well', cat: 'water', pump: 'Manual', people: 650, year: 2022, done: '2022-06-01', status: 'Completed', photos: ['902'], gps: [0.630871, 35.050093] },
  { id: '104', title: 'Magara Water Tank', country: 'Tanzania', ck: 'tz', area: 'Magara, Manyara', type: 'Water collection', cat: 'water', people: 300, year: 2026, status: 'Under construction', photos: [], gps: [-3.71234, 35.83721] }
] };
const donor = { id: 'd1', name: 'Grace Community Church', org: 'Grace Community Church', projects: ['101', '102', '103', '104'], showPhotos: true, photos: {}, viewers: [{ vid: 'v1', primary: true, name: 'Grace Community Church' }, { vid: 'v2', name: 'Pastor John' }] };
const visits = {
  101: [{ at: '2026-10-14T08:00:00Z', state: 'working', readings: { flow: 22, users: 1450, clarity: 'clear' }, quality: { ecoli: 'neg' } }, { at: '2026-04-02T08:00:00Z', state: 'repair', readings: { flow: 9, users: 1300 } }],
  102: [{ at: '2026-09-18T08:00:00Z', state: 'working', kind: 'school', numbers: { pupils: 640, teachers: 14 } }],
  103: [{ at: '2026-10-09T08:00:00Z', state: 'notworking', readings: { users: 650 } }],
  104: [{ at: '2026-10-02T08:00:00Z', state: 'working', kind: 'build', stage: 'roof' }]
};
const events = {
  101: [{ kind: 'photo', at: '2026-10-14T08:00:00Z', id: '601', label: 'water flowing' }, { kind: 'photo', at: '2026-10-14T08:00:00Z', id: '603', label: 'the site with people' }, { kind: 'photo', at: '2025-10-07T08:00:00Z', id: '600', label: 'water flowing' },
    { kind: 'story', at: '2026-10-14T08:00:00Z', quote: 'My daughters used to walk two hours for water. Now they are in class before eight.', who: 'Mary Chebet' },
    { kind: 'visit', at: '2026-04-02T08:00:00Z', state: 'repair', problems: ['Pump handle and pivot pins'], cost: 4500 }, { kind: 'budget', at: '2026-04-03T08:00:00Z', amount: 5200 }, { kind: 'fixed', at: '2026-04-11T08:00:00Z' }],
  102: [{ kind: 'photo', at: '2026-09-18T08:00:00Z', id: '602', label: 'classrooms' }, { kind: 'story', at: '2026-09-18T08:00:00Z', quote: 'The new classrooms stay dry in the rains.', who: 'Head teacher' }],
  103: [{ kind: 'visit', at: '2026-10-09T08:00:00Z', state: 'notworking', problems: ['Chain or rods, pump head'], cost: 8000 }]
};
const repairs = { 103: { state: 'notworking', at: '2026-10-09T08:00:00Z', problems: ['Chain or rods, pump head'], cost: 8000, step: 'parts' } };
const thanks = { 101: [{ id: '700', at: '2026-10-15T10:00:00Z', media: 'photo', caption: 'Asante sana from the Kapsaret water committee!' }] };
process.stdout.write(JSON.stringify(buildView({ donor, viewer: donor.viewers[0], feed, visits, events, repairs, thanks, now })));
