// node --test tests/itin-ui.test.mjs — the trip planner's day timeline in pin.html (itw-core). After a drag, a lock or a
// "Move the rest?", the phone re-times the days at once with the server's schedule rule (itin contract): cached legs, then
// the matrix, then a straight-line guess; waits for opening or a fixed time; lunch once; "Arrives 15:50, after school
// closes"; back after working hours. The block is pure, so it is lifted out of pin.html and run here as it is.
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const html = readFileSync(new URL('../pin.html', import.meta.url), 'utf8');
const a = html.indexOf('// itw-core:start'), b = html.indexOf('// itw-core:end');
assert.ok(a > 0 && b > a, 'the itw-core block is in pin.html');
const ITC = new Function(html.slice(a, b) + '\nreturn ITC;')();

const trip = () => ({
  hours: { start: '07:30', end: '17:30' }, lunch: { at: '12:30', min: 45 },
  start: { kind: 'office', name: 'Eldoret office', lat: 0.514, lng: 35.27 }, end: { kind: 'same' },
  stops: [
    { sid: 's1', id: '101', name: 'Kapsaret Borehole', lat: 0.47, lng: 35.25, type: 'borehole', visitMin: 45, window: null },
    { sid: 's2', id: '104', name: 'Nzoia Bridge Primary School', lat: 0.58, lng: 34.62, type: 'school', visitMin: 60, window: { open: '08:00', close: '15:30' } },
    { sid: 's3', id: '102', name: 'Chepkongi Dispensary', lat: 0.53, lng: 35.29, type: 'health', visitMin: 45, window: { open: '08:00', close: '17:00' } }
  ],
  days: [{ date: '2026-10-27', stops: ['s1', 's2'] }, { date: '2026-10-28', stops: ['s3'] }],
  legs: { 'S>s1': { min: 30, km: 18 }, 's1>s2': { min: 120, km: 95 }, 's2>S': { min: 110, km: 90 }, 'S>s3': { min: 10, km: 3 }, 's3>E': { min: 10, km: 3 } }
});
const kinds = r => r.timeline.map(x => x.kind);

test('clock: "07:30" ⇄ 450', () => {
  assert.equal(ITC.hm('07:30'), 450); assert.equal(ITC.clock(450), '07:30'); assert.equal(ITC.hm('bad'), null); assert.equal(ITC.clock(1500), '01:00');
});

test('a day: leave 07:30, the cached legs, visit lengths, lunch before a long drive, back at the office', () => {
  const r = ITC.day(trip(), 0);
  assert.deepEqual(kinds(r), ['leave', 'drive', 'stop', 'drive', 'stop', 'lunch', 'drive', 'return']);
  const st = r.timeline.filter(x => x.kind === 'stop');
  assert.equal(st[0].arrive, '08:00'); assert.equal(st[0].leave, '08:45');
  assert.equal(st[1].arrive, '10:45'); assert.equal(st[1].leave, '11:45');
  assert.equal(r.km, 18 + 95 + 90); assert.equal(r.driveMin, 260); assert.equal(r.guess, false);
  assert.equal(r.endsAt, '14:20'); assert.deepEqual(r.warnings, []);
});

test('the last day ends at E; a leg the other way round is the same leg', () => {
  const it = trip(); delete it.legs['s3>E']; it.legs['E>s3'] = { min: 12, km: 3 };
  const r = ITC.day(it, 1); assert.equal(r.timeline.at(-1).kind, 'return'); assert.equal(r.driveMin, 22);
});

test('"Arrives 15:50, after school closes"', () => {
  const it = trip(); it.legs['s1>s2'] = { min: 380, km: 95 }; // lunch comes first, before the long drive;
  const r = ITC.day(it, 0), w = r.warnings.find(x => x.code === 'AFTER_CLOSE');
  assert.ok(w); assert.equal(w.sid, 's2'); assert.equal(w.at, '15:50'); assert.equal(w.close, '15:30');
  assert.ok(r.warnings.some(x => x.code === 'OVER_HOURS'));
});

test('early: wait for opening (and an info note after 20 min); a fixed meeting time waits, or is late', () => {
  const it = trip(); it.hours.start = '06:00'; it.days[0].stops = ['s2']; it.legs['S>s2'] = { min: 60, km: 80 };
  const r = ITC.day(it, 0);
  assert.deepEqual(kinds(r).slice(0, 4), ['leave', 'drive', 'wait', 'stop']);
  assert.equal(r.timeline[3].arrive, '08:00'); assert.equal(r.warnings[0].code, 'BEFORE_OPEN');
  const f = trip(); f.stops[0].fixed = '09:30';
  const r2 = ITC.day(f, 0); assert.equal(r2.timeline.find(x => x.kind === 'stop').arrive, '09:30');
  f.stops[0].fixed = '07:40'; assert.equal(ITC.day(f, 0).warnings[0].code, 'FIXED_LATE');
});

test('no cached leg: the matrix, then a straight-line guess', () => {
  const it = trip(); it.legs = {}; it.matrix = { keys: ['S', 's1'], min: [[0, 33], [33, 0]], km: [[0, 20], [20, 0]], src: 'ors' };
  const r = ITC.day(it, 0); assert.equal(r.timeline[1].min, 33); assert.equal(r.timeline[1].km, 20);
  assert.equal(r.guess, true, 'the legs after s1 are guesses'); assert.ok(r.km > 20);
});

test('overnight: day 1 sleeps in a town, day 2 starts there', () => {
  const it = trip(); it.days[0].overnight = { town: 'Iten', lat: 0.67, lng: 35.51 }; it.legs['s2>T:Iten'] = { min: 90, km: 70 }; it.legs['T:Iten>s3'] = { min: 50, km: 40 };
  assert.deepEqual(ITC.ends(it, 0), { a: 'S', b: 'T:Iten' }); assert.deepEqual(ITC.ends(it, 1), { a: 'T:Iten', b: 'E' });
  assert.equal(ITC.day(it, 0).timeline.at(-1).kind, 'overnight'); assert.equal(ITC.day(it, 1).timeline[1].min, 50);
});

test('drag: reorder, move to another day, to unplanned; retime keeps the server’s road warnings', () => {
  const it = trip(); it.days[0].warnings = [{ code: 'LEG_STORM', en: 'Thunderstorms', sw: 'Radi' }, { code: 'AFTER_CLOSE', en: 'old' }];
  ITC.move(it, 's2', 0, 0); assert.deepEqual(it.days[0].stops, ['s2', 's1']);
  ITC.move(it, 's1', 1, 9); assert.deepEqual(it.days[1].stops, ['s3', 's1']);
  ITC.move(it, 's3', -1); assert.deepEqual(it.unplanned, ['s3']); assert.deepEqual(it.days[1].stops, ['s1']);
  ITC.retime(it); assert.ok(it.days[0].local); assert.deepEqual(it.days[0].warnings.map(w => w.code), ['LEG_STORM']);
});

test('next stop and the counts: done, skipped, late', () => {
  const it = ITC.retime(trip()); it.stops[0].status = 'done'; it.stops[1].status = 'skipped';
  assert.equal(ITC.next(it, '2026-10-27').s.sid, 's3');
  it.stops[1].status = 'planned';
  const n = ITC.count(it, '2026-10-27', ITC.hm('12:00'));
  assert.deepEqual(n, { done: 1, late: 1, skipped: 0, left: 1, all: 3 });
});
