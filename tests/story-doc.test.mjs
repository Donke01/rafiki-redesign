// Stories 2, the long story document (lib/story-doc.js): the material it is built from, the AI draft checked against
// the facts (mocked OpenAI), the template, edits from the app and from old apps. Run: node --test tests/*.test.mjs
import test from 'node:test';
import assert from 'node:assert/strict';
import { material, templateDraft, aiDraft, checkAi, pickShape, SHAPES, opening, target, editDoc, docFromText, scrub, visitNotes, photoLabel, snapshot, words } from '../lib/story-doc.js';

const S = () => ({ consent: 'not-yet', answers: { q1: 'Girls walked 6 km home each night after evening classes', q2: 'There was no dormitory, so girls could not stay for evening study', q3: '120 girls boarding at Ndalat Girls Secondary', q4: 'Parents carried sand and water to the site and cooked for the builders every day', q5: 'Girls stay at school and study at night. Their results improved this term', q6: 'On the first night 40 girls slept there and sang until lights out' },
  quote: 'Now we can study at night', quoteBy: 'Faith, Form 3',
  photos: { before: [{ id: '1', name: 'story-before-2026-01-10-09-00.jpg', at: '2026-01-10T09:00:00Z' }], progress: [{ id: '2', name: 'site-photo-walls-2026-02-01.jpg' }, { id: '5', name: 'story-progress-2026-02-03.jpg' }], after: [{ id: '3', name: 'story-after-2026-03-05.jpg', children: true }, { id: '4', name: 'girls-in-dorm.jpg' }] } });
const STAGE = { hist: [{ stage: 'planned', at: '2025-11-01T00:00:00Z', note: '', photos: [] }, { stage: 'foundation', at: '2026-01-12T00:00:00Z', note: 'Foundation dug by 12 local workers. Paid the fundi KSh 20,000.', photos: ['11'] },
  { stage: 'walls', at: '2026-02-01T00:00:00Z', note: 'Walls up to window level', photos: ['12', '13'] }, { stage: 'roof', at: '2026-02-20T00:00:00Z', note: 'Iron sheets on', photos: ['14'] }, { stage: 'handed', at: '2026-03-02T00:00:00Z', note: 'Handed over to the principal. Call her on 0712 345 678.', photos: ['15'] }] };
const FACTS = { type: 'School', area: 'Ndalat, Nandi', country: 'Kenya', ck: 'ke', people: 420, done: '2026-03-02', status: 'Completed' };
const UPD = [{ text: 'Visited the dorm: beds are in and the matron keeps it clean. We still need 20 mattresses at 3,500 each.', at: '2026-03-10T08:00:00Z' }, { text: '🏗️ Stage: Roof, by Meril (RAFIKI Pin app).', at: '2026-02-20T00:00:00Z' }];
const pack0 = s => material('Ndalat Girls Dormitory', FACTS, s || S(), STAGE, UPD);

test('the material: photos from the rows and the stages with captions from the facts; children only with consent; notes cleaned', () => {
  const p = pack0();
  assert.deepEqual(p.photos.map(x => x.row + ':' + x.id), ['before:1', 'progress:5', 'stage:foundation:11', 'stage:walls:12', 'stage:walls:13', 'stage:roof:14', 'stage:handed:15', 'after:4'], 'the stage copy (site-photo-…) and the children photo without consent are left out');
  assert.equal(p.photos[0].cap, 'Before the work began, January 2026'); assert.equal(p.photos[2].cap, 'Laying the foundation, January 2026'); assert.equal(p.photos[7].cap, 'Girls in dorm');
  assert.ok(material('X', FACTS, Object.assign(S(), { consent: 'received' }), STAGE, []).photos.some(x => x.id === '3'));
  assert.deepEqual(p.stages.map(g => g.key), ['planned', 'foundation', 'walls', 'roof', 'handed']);
  assert.deepEqual(p.stages[1].notes, ['Foundation dug by 12 local workers.']); assert.deepEqual(p.stages[4].notes, ['Handed over to the principal.']);
  assert.deepEqual(p.visits, [{ date: '10 March 2026', note: 'Visited the dorm: beds are in and the matron keeps it clean.' }], 'no costs, no app notes');
  assert.equal(scrub('Reach Grace on +254 712 345 678 or grace@x.org. Water flows at 900 litres an hour. The pump cost $400.'), 'Water flows at 900 litres an hour.');
  assert.equal(photoLabel('site-photo-water-flowing-2026-03-12-09-30.jpg'), 'water flowing'); assert.equal(photoLabel('IMG_2034.jpg'), ''); assert.equal(photoLabel('story-after-2026-03-05.jpg'), '');
  assert.equal(visitNotes([{ text: 'ok', at: '2026-01-01' }]).length, 0);
});

test('the template: the same document, sections in the story order, one per stage, photos placed, the quote word for word', () => {
  const s = S(), d = templateDraft(pack0(s), s, 'Meril');
  assert.equal(d.v, 1); assert.equal(d.model, 'template'); assert.equal(d.by, 'Meril'); assert.equal(d.title, 'Learning at Ndalat');
  assert.deepEqual(d.sections.map(x => x.h), ['Life before', 'The people it serves', 'Laying the foundation', 'Raising the walls', 'Putting on the roof', 'Handing over', 'What changed', 'One moment']);
  assert.ok(d.sections.length >= 5 && d.sections.length <= 8);
  assert.deepEqual(d.sections.map(x => x.photo && x.photo.id), ['1', '5', '11', '12', '14', '15', '4', null]);
  assert.deepEqual(d.pull, { quote: 'Now we can study at night', who: 'Faith, Form 3' });
  assert.match(d.sections[2].body, /^The community played its part: parents carried sand/);
  assert.match(d.text, /^Learning at Ndalat\n\nGirls stay at school and study at night\.\n\nLife before\n\nAt Ndalat Girls Dormitory, Ndalat, Nandi, RAFIKI Outreach/);
  assert.equal(/KSh|0712|20,000|fundi/.test(d.text), false, 'nothing private from the notes');
  assert.equal(d.words, words(d.title) + words(d.dek) + d.sections.reduce((n, x) => n + words(x.body), 0));
  assert.equal(d.facts.year, 2026); assert.equal(d.facts.people, 420); assert.equal(d.facts.stages[2].photo.id, '12');
  // without stages: life before, the problem, the people, building it together, what changed, one moment
  const d2 = templateDraft(material('Kapsaret Borehole', { type: 'Borehole well', area: 'Kapsaret' }, s, null, []), s, 'Meril');
  assert.deepEqual(d2.sections.map(x => x.h), ['Life before', 'The problem', 'The people it serves', 'Building it together', 'What changed', 'One moment']);
  assert.equal(d2.title, 'Water for Kapsaret');
});

// ---- the AI draft with a mocked OpenAI ----------------------------------------------------------------------------
let CALLS = [], REPLIES = [];
globalThis.fetch = async (url, init) => {
  if (String(url).startsWith('https://api.openai.com/')) { const b = JSON.parse(init.body); CALLS.push(b); const r = REPLIES.shift(); return r == null ? new Response('no', { status: 500 }) : Response.json({ choices: [{ message: { content: typeof r === 'string' ? r : JSON.stringify(r) } }] }); }
  return new Response('?', { status: 404 });
};
const fill = (lead, n) => { const w = 'Water and learning came together here as the girls and their parents worked side by side every single day'.split(' '); const out = [lead]; for (let i = 0; out.join(' ').split(' ').length < n; i++) out.push(w[i % w.length]); return out.join(' ') + '.'; };
function good(over) {
  const secs = [['Long walks home', '1'], ['No place to stay', null], ['Building it together', '11'], ['The walls rise', '12'], ['A roof overhead', '14'], ['What changed', '4'], ['The first night', null]]
    .map(([h, ph], i) => ({ heading: h, body: fill(i === 6 ? 'On the first night 40 girls slept there.' : 'In March 2026 the work went on.', 95) + '\n\n' + fill('Then', 15), photo: ph }));
  return Object.assign({ title: 'A dormitory for Ndalat', dek: 'How 120 girls stopped walking home in the dark.', sections: secs, pull: { quote: 'Now we can study at night', who: 'Faith, Form 3' } }, over || {});
}
const ENV = { OPENAI_API_KEY: 'k' };

test('the AI draft: valid JSON is accepted, photos from the list with their captions, the quote kept word for word', async () => {
  CALLS = []; REPLIES = [good()];
  const s = S(), d = await aiDraft(ENV, pack0(s), s, 'Jessica');
  assert.ok(d, 'accepted'); assert.equal(CALLS.length, 1); assert.equal(d.model, 'openai:gpt-4o-mini'); assert.equal(d.by, 'Jessica');
  assert.deepEqual(CALLS[0].response_format, { type: 'json_object' });
  const facts = JSON.parse(CALLS[0].messages[1].content);
  assert.equal(facts.project.people_served, 420); assert.equal(facts.quote.words, 'Now we can study at night'); assert.equal(facts.construction_stages[1].stage, 'Laying the foundation');
  assert.equal(JSON.stringify(facts).includes('0712'), false); assert.equal(JSON.stringify(facts).includes('3,500'), false);
  assert.match(CALLS[0].messages[0].content, /ONLY the facts/);
  assert.equal(d.title, 'A dormitory for Ndalat'); assert.equal(d.sections.length, 7); assert.equal(d.sections[0].h, 'Long walks home');
  assert.deepEqual(d.sections[0].photo, { row: 'before', id: '1', cap: 'Before the work began, January 2026' }); assert.equal(d.sections[1].photo, null);
  assert.deepEqual(d.pull, { quote: 'Now we can study at night', who: 'Faith, Form 3' });
  assert.ok(d.words >= 600 && d.words <= 1100); assert.match(d.text, /^A dormitory for Ndalat\n\nHow 120 girls/);
});

test('the AI draft: an invented photo id is sent back once with the problem, then accepted when fixed', async () => {
  CALLS = []; REPLIES = [good({ sections: good().sections.map((x, i) => i === 2 ? Object.assign({}, x, { photo: '999' }) : x) }), good()];
  const s = S(), d = await aiDraft(ENV, pack0(s), s, 'Jessica');
  assert.ok(d); assert.equal(CALLS.length, 2);
  assert.match(CALLS[1].messages[3].content, /photo 999 is not in the photos list/);
});

test('the AI draft: still wrong after the retry (or no key, or OpenAI down) → null, so the template is used', async () => {
  const s = S(), bad = good({ sections: good().sections.map((x, i) => i === 2 ? Object.assign({}, x, { photo: '999' }) : x) });
  CALLS = []; REPLIES = [bad, bad]; assert.equal(await aiDraft(ENV, pack0(s), s, 'J'), null); assert.equal(CALLS.length, 2);
  CALLS = []; REPLIES = []; assert.equal(await aiDraft(ENV, pack0(s), s, 'J'), null);
  CALLS = []; assert.equal(await aiDraft({}, pack0(s), s, 'J'), null); assert.equal(CALLS.length, 0);
  // a photo of children is not offered without consent
  CALLS = []; REPLIES = [good({ sections: good().sections.map((x, i) => i === 5 ? Object.assign({}, x, { photo: '3' }) : x) }), null];
  assert.equal(await aiDraft(ENV, pack0(s), s, 'J'), null);
});

test('the AI draft is checked against the facts: the quote, invented quotes and numbers, length, photos', () => {
  const s = S(), p = pack0(s), tg = target(p);
  assert.ok(checkAi(good(), p, s, tg).doc);
  const errs = o => checkAi(o, p, s, tg).errors || [];
  assert.match(errs(good({ pull: { quote: 'Now we can finally study at night', who: 'Faith' } })).join(), /word for word/);
  assert.match(errs(good({ dek: 'How 37 families found water at last today.' })).join(), /number 37 is not in the facts/);
  const q = good(); q.sections[3].body += ' “We will never forget this day,” said the principal.';
  assert.match(errs(q).join(), /nobody said it/);
  assert.match(errs(good({ sections: good().sections.slice(0, 3) })).join(), /5 to 8 sections/);
  const short = good(); short.sections.forEach(x => { x.body = fill('Then', 20); });
  assert.match(errs(short).join(), new RegExp('write ' + tg.min + ' to 1000'));
  const twice = good(); twice.sections[4].photo = '1';
  assert.match(errs(twice).join(), /used twice/);
  const none = good(); none.sections.forEach(x => { x.photo = null; });
  assert.match(errs(none).join(), /use the photos/);
  // no quote given: nobody may be quoted
  const s2 = Object.assign(S(), { quote: '', quoteBy: '' }), p2 = material('N', FACTS, s2, STAGE, []);
  assert.match((checkAi(good(), p2, s2, target(p2)).errors || []).join(), /pull must be null/);
  const ok2 = checkAi(good({ pull: null }), p2, s2, target(p2)); assert.ok(ok2.doc); assert.equal(ok2.doc.pull, null);
  // rich facts: 600 to 1,000 words; thin facts: a shorter story is fine
  const rich = Object.assign(S(), { answers: Object.fromEntries(Object.entries(S().answers).map(([k, v]) => [k, (v + '. ').repeat(4)])) });
  assert.deepEqual([target(pack0(rich)).min, target(pack0(rich)).max], [600, 1000]);
  const s3 = { consent: 'received', answers: { q1: 'People walked far for water' }, quote: '', quoteBy: '', photos: {} };
  assert.deepEqual([target(material('W', {}, s3, null, [])).min, target(material('W', {}, s3, null, [])).sections[0]], [250, 4]);
});

test('edits: admins change the words and photos (only from the pool); old apps edit the plain text', () => {
  const s = S(), p = pack0(s), d = templateDraft(p, s, 'Meril');
  const e = editDoc(d, { title: '  A dormitory <b>for</b> Ndalat ', dek: 'New dek.', sections: d.sections.map((x, i) => ({ h: x.h, body: i === 0 ? 'First.\n\n\n\nSecond.' : x.body, photo: i === 0 ? { id: '999' } : i === 1 ? { id: '13' } : x.photo })) }, p, 'Jessica');
  assert.equal(e.title, 'A dormitory for Ndalat', 'tags are removed, the words kept');
  assert.equal(e.sections[0].body, 'First.\n\nSecond.'); assert.equal(e.sections[0].photo, null, 'unknown photo dropped');
  assert.equal(e.sections[1].photo.id, '13'); assert.equal(e.sections[1].photo.cap, 'Raising the walls, February 2026');
  assert.equal(e.model, 'template'); assert.equal(e.by, 'Meril'); assert.equal(e.edited.by, 'Jessica'); assert.equal(e.pull.quote, d.pull.quote);
  assert.equal(editDoc(d, { sections: [] }, p, 'J'), null);
  // the plain text back onto the document by its headings
  const t = d.text.replace('Before it, girls walked 6 km', 'Before it, the girls walked 6 km');
  const back = docFromText(t, d, p, 'Jessica');
  assert.deepEqual(back.sections.map(x => x.h), d.sections.map(x => x.h)); assert.deepEqual(back.sections.map(x => x.photo), d.sections.map(x => x.photo));
  assert.match(back.sections[0].body, /the girls walked/); assert.equal(back.title, d.title); assert.equal(back.dek, d.dek); assert.equal(back.text, t);
  const free = docFromText('Something else entirely.\n\nA second paragraph.', d, p, 'J');
  assert.equal(free.sections.length, 1); assert.equal(free.sections[0].body, 'Something else entirely.\n\nA second paragraph.');
});

test('the public copy: consent-safe photos only, nothing private, a hero and the journey', () => {
  const s = Object.assign(S(), { consent: 'received' }), p = material('Ndalat Girls Dormitory', FACTS, s, STAGE, []), d = templateDraft(p, s, 'Meril');
  d.sections[0].body += ' Call 0712 345 678 or write to x@y.org.';
  const proj = { title: 'Ndalat Girls Dormitory', area: 'Ndalat, Nandi', country: 'Kenya', ck: 'ke', type: 'School', status: 'Completed', people: 420, gps: [0.1, 35.1] };
  const pub = snapshot('301', d, s, p.photos, proj, 'Jessica', null);
  assert.deepEqual(Object.keys(pub).sort(), ['approvedAt', 'approvedBy', 'before', 'dek', 'facts', 'hero', 'id', 'photos', 'project', 'pull', 'sections', 'slug', 'title', 'updatedAt', 'words'].sort());
  assert.equal(pub.slug, 'learning-at-ndalat'); assert.equal(pub.hero.row, 'after');
  assert.equal(/0712|x@y\.org/.test(JSON.stringify(pub)), false);
  for (const ph of pub.sections.map(x => x.photo).filter(Boolean).concat([pub.hero])) assert.ok(pub.photos.includes(ph.id));
  assert.ok(pub.facts.stages.every(g => !g.photo || pub.photos.includes(g.photo.id)));
  // the consent is withdrawn: a photo of children is left out everywhere
  const s2 = Object.assign({}, s, { consent: 'not-yet' }), d2 = JSON.parse(JSON.stringify(d)); d2.sections[6].photo = { row: 'after', id: '3', cap: 'x' };
  const pub2 = snapshot('301', d2, s2, material('N', FACTS, Object.assign({}, s, { consent: 'received' }), STAGE, []).photos, proj, 'J', null);
  assert.equal(pub2.photos.includes('3'), false); assert.equal(pub2.sections[6].photo, null);
});

test('variety: each draft gets its own shape and voice, avoids the last ones, and never repeats a recent title or opening', async () => {
  const p = pack0();
  // every shape the facts can carry is offered; a re-draft never gets the shape it had; recent shapes are skipped
  const seen = new Set(); for (let i = 0; i < 400; i++) seen.add(pickShape(p, { seed: i * 7919 }).shape.k);
  assert.ok(seen.size >= 6, [...seen].join(','));
  for (let i = 0; i < 50; i++) assert.notEqual(pickShape(p, { seed: i, prevShape: 'moment' }).shape.k, 'moment');
  const rec = [{ shape: 'moment' }, { shape: 'day' }, { shape: 'hands' }, { shape: 'diary' }];
  for (let i = 0; i < 50; i++) assert.ok(!['moment', 'day', 'hands', 'diary'].includes(pickShape(p, { seed: i, recent: rec }).shape.k));
  assert.ok(pickShape(p, { seed: -5 }).shape, 'any seed works');
  // a thin story can only take the shapes its facts carry
  const thin = material('X', FACTS, { answers: { q2: 'No water' }, photos: {} }, null, []);
  for (let i = 0; i < 30; i++) assert.ok(['plain', 'arc'].includes(pickShape(thin, { seed: i }).shape.k));
  // the instructions carry the shape, the voice and the recent stories to stay away from
  CALLS = []; REPLIES = [good()];
  const s = S(), d = await aiDraft(ENV, pack0(s), s, 'J', { seed: 3, recent: [{ title: 'Water at the school gate', open: 'Every evening the girls walked home', heads: ['Long nights'] }] });
  const sys = CALLS[0].messages[0].content;
  assert.match(sys, /SHAPE for this story: /); assert.match(sys, /VOICE for this story: /); assert.match(sys, /Water at the school gate/); assert.match(sys, /"Life before"/);
  assert.equal(CALLS[0].temperature, 0.9); assert.ok(SHAPES.some(x => x.k === d.shape));
  // the same title as a recent story is sent back
  CALLS = []; REPLIES = [good(), good({ title: 'Nights of study at Ndalat' })];
  const d2 = await aiDraft(ENV, pack0(s), s, 'J', { seed: 1, recent: [{ title: 'A dormitory for Ndalat' }] });
  assert.equal(CALLS.length, 2); assert.match(CALLS[1].messages[3].content, /already used by another story/); assert.equal(d2.title, 'Nights of study at Ndalat');
  assert.ok(opening(d2).split(' ').length <= 12);
  // the template: titles and headings come in versions; without a number it keeps the first ones
  const t0 = templateDraft(p, S(), 'M'), titles = new Set(), heads = new Set();
  for (let i = 0; i < 40; i++) { const t = templateDraft(p, S(), 'M', i); titles.add(t.title); heads.add(t.sections.map(x => x.h).join('|')); }
  assert.equal(t0.title, 'Learning at Ndalat'); assert.ok(titles.size >= 3, [...titles].join(' / ')); assert.ok(heads.size >= 5);
});
