// The long project story (Stories 2): the draft document, how it is written, and the public copy on the website.
//   pinstory:<id>.doc → { v: 1, title, dek, sections: [{ h, body, photo: { row, id, cap } | null }], pull: { quote, who } | null,
//                         facts: { place, country, ck, type, people, year, stages: [{ stage, name, date, photo }] },
//                         words, at, by, model: "openai:<model>" | "template", text }
//   pubstory:<id>  → the approved story for the website (snapshot below); pubstoryidx → the list, newest first.
// Photos are only ever ones already recorded for the project: the story photo rows (before | progress | after) and the
// construction stage photos (row "stage:<key>", lib/construction.js). A photo showing children is only used once the
// story's photo consent is "Received". The words may only use the facts collected: nothing invented.
import { STAGES } from './construction.js';
import { getJson, putJson } from './pin-store.js';

export const STAGE_NAME = { planned: 'Planning', foundation: 'Laying the foundation', walls: 'Raising the walls', roof: 'Putting on the roof', finishing: 'Finishing the work', handed: 'Handing over' };
const STAGE_SAYS = {
  planned: d => 'Planning began' + (d ? ' on ' + d : '') + '.',
  foundation: d => 'Work on the foundation began' + (d ? ' on ' + d : '') + '.',
  walls: d => 'The walls began to go up' + (d ? ' on ' + d : '') + '.',
  roof: d => 'Work on the roof began' + (d ? ' on ' + d : '') + '.',
  finishing: d => 'The finishing work began' + (d ? ' on ' + d : '') + '.',
  handed: d => 'The project was handed over' + (d ? ' on ' + d : '') + '.'
};
const ROW_CAP = { before: 'Before the work began', progress: 'The work under way', after: 'The finished project' };
const MONTHS = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December'];
const KIND = { 'Borehole well': 'borehole', 'Water collection': 'water project', School: 'school project', Health: 'health project', Orphanage: "children's home project", Farming: 'farming project', Training: 'training project', Nutrition: 'nutrition project', Community: 'community project' };
export const LIMITS = { title: 90, dek: 240, h: 70, body: 3000, cap: 120, sections: 8, words: 1100 };

const iso = v => { const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(String(v || '')); return m && +m[2] >= 1 && +m[2] <= 12 ? m[1] + '-' + m[2] + '-' + m[3] : ''; };
export function day(v) { const d = iso(v); return d ? Number(d.slice(8)) + ' ' + MONTHS[+d.slice(5, 7) - 1] + ' ' + d.slice(0, 4) : ''; }
export function month(v) { const d = iso(v); return d ? MONTHS[+d.slice(5, 7) - 1] + ' ' + d.slice(0, 4) : ''; }
export function words(t) { return String(t || '').trim().split(/\s+/).filter(Boolean).length; }
const untag = s => String(s == null ? '' : s).replace(/<[^>]{0,200}>/g, '').replace(/[<>]/g, '');
const one = s => untag(s).replace(/\r/g, '').replace(/\s+/g, ' ').trim();
const para = s => untag(s).replace(/\r/g, '').split(/\n\s*\n/).map(x => x.replace(/\s+/g, ' ').trim()).filter(Boolean).join('\n\n');
const cut = (s, n) => { s = String(s || ''); if (s.length <= n) return s; const c = s.slice(0, n), i = Math.max(c.lastIndexOf(' '), n - 12); return c.slice(0, i).replace(/[\s,;:.–—-]+$/, '') + '…'; };
const sent = s => { s = one(s); return !s ? '' : /[.!?…”"’)]$/.test(s) ? s : s + '.'; };
const cap1 = s => s ? s.charAt(0).toUpperCase() + s.slice(1) : s;
// lower-case the first letter only for an ordinary word, never a name ("Faith", "Kapsaret")
const COMMON = new Set('the a an we they there it its our their most many some people women men children pupils students girls boys families mothers fathers parents everyone every each life water no nothing all few several one two three villagers residents farmers patients teachers nurses this that these those'.split(' '));
const low = s => { s = one(s); const w = (s.match(/^[A-Za-z']+/) || [''])[0]; return w && COMMON.has(w.toLowerCase()) && w.slice(1) === w.slice(1).toLowerCase() ? s.charAt(0).toLowerCase() + s.slice(1) : s; };

// ---- the material: photos, stages and visit notes --------------------------------------------------------------
// A word or two from the file name ("site-photo-water-flowing-2026-03-12-09-30.jpg" → "water flowing"), else ''
export function photoLabel(name) {
  let n = String(name || '').replace(/\.[a-z0-9]{2,5}$/i, '').replace(/^(story-(before|progress|after)|site-photo)-?/i, '').replace(/-?\d{4}-\d{2}-\d{2}([-T]\d{2}[-:]\d{2})?.*$/, '');
  n = n.replace(/[-_]+/g, ' ').trim();
  if (!n || /^(img|dsc|pxl|photo|image|whatsapp|screenshot|story|signal|wa\d|\d)/i.test(n)) return '';
  return n.length >= 3 && n.length <= 40 && /^[a-z ]+$/i.test(n) ? n.toLowerCase() : '';
}
const nameDate = name => (/(\d{4}-\d{2}-\d{2})/.exec(String(name || '')) || [])[1] || '';
function caption(row, label, date) {
  const base = label ? cap1(label) : row.indexOf('stage:') === 0 ? STAGE_NAME[row.slice(6)] || 'The work under way' : ROW_CAP[row] || '';
  const m = month(date);
  return cut(base + (m ? ', ' + m : ''), LIMITS.cap);
}
// the construction record grouped by stage, in order: [{ key, name, date, notes, photos }]
export function stageGroups(stageRec) {
  const out = [];
  ((stageRec && stageRec.hist) || []).forEach(h => {
    if (!STAGES.includes(h.stage)) return;
    let g = out[out.length - 1];
    if (!g || g.key !== h.stage) { g = { key: h.stage, name: STAGE_NAME[h.stage], date: iso(h.at), notes: [], photos: [] }; out.push(g); }
    const n = scrub(h.note); if (n) g.notes.push(n);
    (h.photos || []).forEach(p => { if (p && !g.photos.some(x => x.id === String(p))) g.photos.push({ id: String(p), at: iso(h.at) }); });
  });
  return out;
}
// every photo the story may use, with its row and a caption from the facts: [{ id, row, cap, children, date }]
// consent: the story's photo consent; children's photos are left out until it is "received" (unless all is asked for)
export function photoPool(s, stageRec, opts) {
  const o = opts || {}, out = [], seen = new Set(), groups = stageGroups(stageRec);
  const fromStages = groups.some(g => g.photos.length);
  const add = (row, p, date) => {
    const id = String(p.id || ''); if (!id || seen.has(id)) return;
    if (p.children && s.consent !== 'received' && !o.all) return;
    const own = one(p.cap);   // a caption typed in the app wins over the one made from the file name
    seen.add(id); out.push({ id, row, cap: own ? cut(own, LIMITS.cap) : caption(row, photoLabel(p.name), date), children: !!p.children, date: iso(date) });
  };
  const rows = (s && s.photos) || {};
  (rows.before || []).slice(0, 8).forEach(p => add('before', p, p.at || nameDate(p.name)));
  // construction photos are copied into the In-progress row as "site-photo-…": the stage keeps the original, so skip the copy
  (rows.progress || []).filter(p => !(fromStages && /^site-photo-/i.test(p.name || ''))).slice(-8).forEach(p => add('progress', p, p.at || nameDate(p.name)));
  groups.forEach(g => g.photos.slice(-4).forEach(p => add('stage:' + g.key, { id: p.id }, p.at)));
  (rows.after || []).slice(0, 10).forEach(p => add('after', p, p.at || nameDate(p.name)));
  return out;
}

// Visit notes are written for the team: no phone numbers, e-mails, links or money, and no sentence about costs.
const PHONE = /(\+\d[\d\s-]{7,}\d|\b0[1-9]\d{2}[\s-]?\d{3}[\s-]?\d{3,4}\b|\b\d{9,}\b)/g;
const EMAIL = /[^\s@]+@[^\s@]+\.[a-z]{2,}/gi, LINK = /\bhttps?:\/\/\S+/gi;
const MONEY = /(\b(KSh|KES|Kshs?|TSh|TZS|UGX|USh|MWK|MK|USD|US\$)\s?\d[\d,.]*\s?(k|m|million|thousand)?\b|[$€£]\s?\d[\d,.]*\s?(k|m|million|thousand)?\b|\b\d[\d,.]*\s?(k|m)?\s?(\/=|shillings?|bob|dollars?|ksh|kshs|kes|tzs|ugx|usd|mwk)\b|\bat\s\d[\d,.]*\s?(k|m)?\s(each|per|a piece)\b)/gi;
const COSTY = /\b(cost|costs|costed|paid|pay|paying|payment|payments|budget|invoice|invoices|price|prices|amount|balance|money|cash|quotation|quote of|receipt|receipts|fuel|allowance|m-?pesa|bank|loan|debt|salary|wages?)\b/i;
export function scrub(t) {
  const s = String(t || '').replace(/\r/g, '').replace(/\(RAFIKI Pin app\)\.?/gi, '').replace(/[\u{1F000}-\u{1FAFF}\u{2600}-\u{27BF}\u{FE0F}\u{200D}]/gu, '');
  const bad = [MONEY, PHONE, EMAIL, LINK].map(r => new RegExp(r.source, 'iu'));
  // a sentence with a phone number, an e-mail, a link, an amount of money or a word about costs is left out whole
  return s.split(/(?<=[.!?])\s+|\n+/).map(x => x.replace(/\s+/g, ' ').trim()).filter(x => x.length > 2 && !COSTY.test(x) && !bad.some(r => r.test(x))).join(' ').trim();
}
// the latest visit notes from monday.com, oldest first, cleaned; the app's own stage, story and voice notes are left out
export function visitNotes(updates) {
  return (updates || []).filter(u => u && u.text && !/^\s*(🏗️|🎙️|📖|✨|⛔|🆕)/u.test(u.text) && !/^\s*(Stage:|Voice answer|Story )/i.test(u.text))
    .map(u => ({ date: day(u.at), note: cut(scrub(u.text), 500) })).filter(u => u.note.length >= 15).slice(0, 6).reverse();
}

// everything the story may be built from (the AI and the template get the same)
export function material(title, facts, s, stageRec, updates) {
  const f = facts || {}, groups = stageGroups(stageRec);
  return {
    project: { title: one(title), type: f.type || '', place: f.area || '', country: f.country || '', ck: f.ck || '', people: Number(f.people) || 0, done: iso(f.done), status: f.status || '' },
    answers: Object.assign({}, s.answers), quote: one(s.quote), quoteBy: one(s.quoteBy),
    stages: groups.map(g => ({ key: g.key, name: g.name, date: g.date, notes: g.notes, photos: g.photos.map(p => p.id) })),
    visits: visitNotes(updates),
    photos: photoPool(s, stageRec)
  };
}
// how long a story the facts can honestly carry
export function target(pack) {
  const a = pack.answers || {}, n = words(Object.values(a).join(' ')) + words(pack.quote) + pack.stages.reduce((x, g) => x + words(g.notes.join(' ')) + 8, 0) + words(pack.visits.map(v => v.note).join(' '));
  const min = n >= 220 ? 600 : n >= 120 ? 400 : 250;
  return { min, max: 1000, sections: n >= 120 ? [5, 8] : [4, 8], factWords: n };
}
export function storyFacts(pack, sections) {
  const p = pack.project, used = new Map();
  (sections || []).forEach(x => { if (x.photo) used.set(x.photo.row, used.get(x.photo.row) || x.photo); });
  const year = Number((p.done || '').slice(0, 4)) || Number(((pack.stages.filter(g => g.key === 'handed')[0] || {}).date || '').slice(0, 4)) || null;
  return { place: p.place || p.title, country: p.country, ck: p.ck, type: p.type, people: p.people || null, year,
    stages: pack.stages.map(g => {
      const ph = used.get('stage:' + g.key) || (g.photos.length ? pack.photos.find(x => x.row === 'stage:' + g.key) || null : null);
      return { stage: g.key, name: g.name, date: g.date, photo: ph ? { row: ph.row, id: ph.id, cap: ph.cap } : null };
    }) };
}

// ---- the document ---------------------------------------------------------------------------------------------------
// the plain text (title, standfirst, headings on their own lines, the quote): the Story column and the monday.com note
export function docText(d) {
  const P = [d.title, d.dek].filter(Boolean);
  d.sections.forEach(x => { if (x.h) P.push(x.h); if (x.body) P.push(x.body); });
  if (d.pull && d.pull.quote) P.push('“' + d.pull.quote + '”' + (d.pull.who ? ' — ' + d.pull.who : ''));
  return P.join('\n\n');
}
export function finish(d, pack, by, model) {
  d.v = 1; d.words = d.sections.reduce((n, x) => n + words(x.body), 0) + words(d.title) + words(d.dek);
  d.facts = storyFacts(pack, d.sections); d.at = new Date().toISOString(); d.by = String(by || ''); d.model = model;
  d.text = docText(d);
  return d;
}
// ---- variety: every story gets its own shape, voice, title and headings, so no two read alike ------------------
export const hash = t => {
  let h = 2166136261; for (const c of String(t)) { h ^= c.codePointAt(0); h = Math.imul(h, 16777619); }
  h ^= h >>> 16; h = Math.imul(h, 0x85ebca6b); h ^= h >>> 13; h = Math.imul(h, 0xc2b2ae35); h ^= h >>> 16;   // mix, so every bit counts
  return h >>> 0;
};
// the shapes a story can take; a shape is only offered when the facts can carry it
export const SHAPES = [
  { k: 'moment', need: p => p.answers.q6, say: 'Open in the middle of the one moment (one_moment), told closely and slowly; then step back to how life was before and how the project came about; end on what is true now.' },
  { k: 'day', need: p => p.answers.q1, say: 'Open with an ordinary day before the project, told from the life_before facts, so the reader feels the old routine; then the problem and the work; close by coming back to the same routine as it is now.' },
  { k: 'hands', need: p => p.answers.q4, say: 'Tell it as the community\'s own achievement: open with what the community did (what_the_community_did), let the building carry the middle, keep RAFIKI Outreach in the background.' },
  { k: 'people', need: p => p.answers.q3, say: 'Open with the people it serves (who_it_serves) and what matters to them; then how the project came to be; end with what has changed for them.' },
  { k: 'diary', need: p => p.stages.length >= 2, say: 'Tell it as a build diary: a short opening on why it was needed, then one section per construction stage in order, each beginning with its date, then the handover and what changed.' },
  { k: 'voice', need: p => p.quote, say: 'Open with the quote, word for word, and who said it; then tell the story that explains why those words matter, and end by coming back to them.' },
  { k: 'contrast', need: p => p.answers.q1 && p.answers.q5, say: 'Build it on contrast: move between then (life_before, main_problem) and now (what_has_changed), with the building in the middle as the turning point.' },
  { k: 'plain', need: p => p.answers.q2, say: 'Open plainly with the main problem in one strong sentence; then the people it touched, the turning point, the work, and the result.' },
  { k: 'arc', need: () => true, say: 'Follow the arc: life before → the problem → the people → building it together (one section per main construction stage when stages are given) → what changed → one moment.' }
];
export const VOICES = ['warm and close, as if telling a friend', 'quiet and observant: let the concrete details speak, few adjectives', 'bright and lively, with short punchy sentences', 'reflective and unhurried, with longer flowing sentences', 'like a good newspaper feature: clear, precise, human'];
// a shape for this draft: not the one this project had last time, and not one of the last few drafts' shapes
export function pickShape(pack, o) {
  o = o || {};
  const ok = SHAPES.filter(x => { try { return !!x.need(pack); } catch (e) { return false; } });
  const recent = new Set((o.recent || []).slice(0, 4).map(r => r && r.shape).concat(o.prevShape || []));
  const fresh = ok.filter(x => !recent.has(x.k) && x.k !== 'arc');
  const list = fresh.length ? fresh : ok.filter(x => x.k !== o.prevShape).length ? ok.filter(x => x.k !== o.prevShape) : ok;
  const seed = (o.seed != null ? Number(o.seed) || 0 : hash(pack.project.title + '|' + (o.prevShape || ''))) >>> 0;
  return { shape: list[seed % list.length], voice: VOICES[hash(String(seed) + 'v') % VOICES.length] };
}
// the template's words come in a few versions; vary = a number picks them, undefined keeps the first (the old) version
const pick = (list, vary, salt) => vary == null ? list[0] : list[hash(String(vary) + salt) % list.length];
const HEADS = {
  'Life before': ['Life before', 'How it used to be', 'The old routine', 'Before'],
  'The project': ['The project', 'How it began', 'Where it started'],
  'The problem': ['The problem', 'What was missing', 'The hard part'],
  'The people it serves': ['The people it serves', 'Who it is for', 'The people'],
  'Building it together': ['Building it together', 'Many hands', 'The work', 'Side by side'],
  'What changed': ['What changed', 'Since then', 'Life now', 'Today'],
  'One moment': ['One moment', 'A day to remember', 'The moment', 'One scene']
};
const TITLES = {
  'Borehole well': ['Water for {at}', 'The well at {at}', 'Clean water in {at}', 'A borehole for {at}'],
  'Water collection': ['Water for {at}', 'Clean water in {at}', 'A water point for {at}', 'Catching water in {at}'],
  School: ['Learning at {at}', 'A place to learn in {at}', 'School days at {at}'],
  Health: ['Health care in {at}', 'Care close to home in {at}', 'A clinic for {at}'],
  Orphanage: ['A home for children in {at}', 'Home at {at}', 'A safe place in {at}'],
  Farming: ['Farming in {at}', 'Growing food in {at}', 'The fields of {at}']
};
const pullOf = s => s.quote ? { quote: one(s.quote).replace(/^[“"]+|[”"]+$/g, '').trim(), who: one(s.quoteBy) } : null;
const kindTitle = (type, place, title, vary) => {
  const at = place.split(',')[0].trim() || title, t = TITLES[type];
  return t ? pick(t, vary, 't').replace('{at}', at) : 'The story of ' + title;
};

// A structured draft from the facts alone, with no AI: the same shape, sections in the story's order, photos placed
export function templateDraft(pack, s, by, vary) {
  const H = h => HEADS[h] ? pick(HEADS[h], vary, h) : h;
  const a = pack.answers || {}, p = pack.project, pool = pack.photos.slice(), used = new Set();
  const take = test => { const x = pool.find(y => !used.has(y.id) && test(y)); if (!x) return null; used.add(x.id); return { row: x.row, id: x.id, cap: x.cap }; };
  const row = r => y => y.row === r;
  const where = [p.title, p.place].filter(Boolean).join(', ');
  const S = [];
  const intro = sent((where ? 'At ' + where : 'Here') + ', RAFIKI Outreach and the community worked together on a ' + (KIND[p.type] || 'project') + (p.country ? ' in ' + p.country : '') + (p.done ? ', finished on ' + day(p.done) : ''));
  const who = a.q3 ? sent('It serves ' + low(a.q3)) + (p.people ? ' ' + sent('In all, about ' + p.people.toLocaleString('en-US') + ' people are served') : '') : p.people ? sent('It serves about ' + p.people.toLocaleString('en-US') + ' people') : '';
  const helped = a.q4 ? sent('The community played its part: ' + low(a.q4)) : '';
  // the build: one section per stage (8 sections at most: the problem joins life before, the community's part opens
  // the first stage), else one section that tells every stage
  const st = pack.stages.filter(g => g.notes.length || g.photos.length || g.key !== 'planned');
  const tell = g => [STAGE_SAYS[g.key](day(g.date))].concat(g.notes.map(sent)).join(' ');
  const tail = (a.q5 ? 1 : 0) + (a.q6 ? 1 : 0) + (who ? 1 : 0);
  const split = st.length && 2 + tail + (a.q4 ? 1 : 0) + st.length <= LIMITS.sections, perStage = st.length && 1 + tail + st.length <= LIMITS.sections;
  const join = !split && perStage;   // the problem goes with life before, the community's part with the first stage
  S.push({ h: a.q1 ? 'Life before' : 'The project', body: [intro, a.q1 ? sent('Before it, ' + low(a.q1)) : '', join && a.q2 ? sent('The main problem: ' + low(a.q2)) : ''].filter(Boolean).join('\n\n'), photo: take(row('before')) });
  if (a.q2 && !join) S.push({ h: 'The problem', body: sent(a.q2), photo: take(row('before')) });
  if (who) S.push({ h: 'The people it serves', body: who, photo: null });
  if (perStage) {
    if (a.q4 && !join) S.push({ h: 'Building it together', body: helped, photo: take(row('progress')) });
    st.forEach((g, i) => S.push({ h: g.name, body: [join && !i ? helped : '', tell(g)].filter(Boolean).join('\n\n'), photo: take(row('stage:' + g.key)) || take(row('progress')) }));
  } else if (st.length || a.q4) {
    S.push({ h: 'Building it together', body: [helped].concat(st.map(tell)).filter(Boolean).join('\n\n'), photo: take(y => y.row.indexOf('stage:') === 0) || take(row('progress')) });
  }
  if (a.q5) S.push({ h: 'What changed', body: sent('Since then, ' + low(a.q5)), photo: take(row('after')) });
  if (a.q6) S.push({ h: 'One moment', body: sent(a.q6), photo: take(row('after')) });
  S.forEach(x => { if (!x.photo && x.h === 'The people it serves') x.photo = take(row('after')) || take(row('progress')); });
  // a varied draft sometimes opens on the one moment
  if (vary != null && a.q6 && hash(String(vary) + 'o') % 2) { const i = S.findIndex(x => x.h === 'One moment'); if (i > 0) S.unshift(S.splice(i, 1)[0]); }
  S.forEach(x => { x.h = H(x.h); });
  const title = kindTitle(p.type, p.place, p.title, vary);
  const dek = a.q5 ? cut(sent(cap1(one(a.q5).split(/(?<=[.!?])\s/)[0])), LIMITS.dek) : p.people ? sent(p.title + ' serves about ' + p.people.toLocaleString('en-US') + ' people') : sent('How ' + p.title + ' came to be');
  const d = { title: cut(title, LIMITS.title), dek, sections: S.slice(0, LIMITS.sections).map(x => ({ h: x.h, body: x.body, photo: x.photo || null })), pull: pullOf(s) };
  return finish(d, pack, by, 'template');
}

// ---- the AI draft -------------------------------------------------------------------------------------------------
const SYS = [
  'You write true stories about community projects for the website of RAFIKI Outreach, a charity that builds water points, schools, clinics and children\'s homes in East Africa with local communities.',
  'Write a captivating, warm, vivid story in plain English that a donor reads to the end. Third person, past tense for what happened, present tense for what is true now. Short sentences mixed with longer ones. Concrete details from the facts. No clichés ("in the heart of", "a beacon of hope", "transformative", "testament"), no exaggeration, no sermon, no appeals for money, no hashtags, no emojis, no markdown.',
  'STRICT TRUTH: use ONLY the facts in the JSON you are given. Never invent or guess names, numbers, ages, distances, times, dates, places, events, feelings, thoughts, weather, sounds, or quotes. Do not describe what is in a photo beyond its caption. Do not name RAFIKI staff or the people who wrote the visit notes; name only people who are named in the answers or the quote. If a fact is missing, leave it out; a shorter true story is better than a longer invented one. Every number you write must appear in the facts.',
  'STRUCTURE: a title (a short human headline of 3 to 9 words, not just the project name), a dek (one sentence, under 30 words, that makes people want to read on), and sections in the SHAPE given below. When construction stages are given, tell the building from their notes and dates. Each section has a short human heading (2 to 6 words, no colon) that belongs to THIS story, and a body of 1 to 3 short paragraphs separated by a blank line ("\\n\\n").',
  'PHOTOS: each section may show one photo: give its id from the photos list (or null). Use each photo at most once, choose the photo whose row and caption fit the section (before photos for life before and the problem, stage photos for their stage, after photos for what changed and the moment). Use as many fitting photos as there are sections.',
  'QUOTE: if a quote is given, return it word for word as pull.quote with pull.who exactly as given, and do not change a word of it; you may also quote it once in the text, word for word. If no quote is given, pull is null and nobody is quoted.',
  'Reply with JSON only: {"title": string, "dek": string, "sections": [{"heading": string, "body": string, "photo": string|null}], "pull": {"quote": string, "who": string}|null}.'
].join('\n\n');
// the part of the instructions that makes this story its own
const GENERIC = ['Life before', 'The problem', 'The people it serves', 'Building it together', 'What changed', 'One moment', 'A new beginning', 'A brighter future', 'Hope'];
function sysFor(sh, recent) {
  const R = (recent || []).filter(r => r && (r.title || r.open)).slice(0, 10);
  return SYS + '\n\n' + ['SHAPE for this story: ' + sh.shape.say, 'VOICE for this story: ' + sh.voice + '.',
    'VARIETY: every RAFIKI story must sound different from the others. Find the detail that only this project has and build the story around it. Do not use these stock headings: ' + GENERIC.map(x => '"' + x + '"').join(', ') + '.' +
    (R.length ? ' These are other recent stories; do not reuse or echo their titles, their first words or their headings: ' + JSON.stringify(R.map(r => ({ title: r.title, opening: r.open, headings: r.heads || [] }))) : '')].join('\n\n');
}
// the first words of a story (to keep the next ones from starting the same way)
export const opening = d => one(((d && d.sections && d.sections[0]) || {}).body).split(/(?<=[.!?])\s/)[0].split(' ').slice(0, 12).join(' ');

function promptFacts(pack, tg) {
  const p = pack.project, a = pack.answers || {};
  const o = {
    project: { name: p.title, kind: p.type || null, place: p.place || null, country: p.country || null, people_served: p.people || null, completed_on: day(p.done) || null, status: p.status || null },
    answers: { life_before: a.q1 || null, main_problem: a.q2 || null, who_it_serves: a.q3 || null, what_the_community_did: a.q4 || null, what_has_changed: a.q5 || null, one_moment: a.q6 || null },
    quote: pack.quote ? { words: pack.quote, said_by: pack.quoteBy || null } : null,
    construction_stages: pack.stages.map(g => ({ stage: g.name, started_on: day(g.date) || null, notes: g.notes, photos: g.photos.filter(id => pack.photos.some(x => x.id === id)) })),
    visit_notes: pack.visits,
    photos: pack.photos.map(x => ({ id: x.id, row: x.row, caption: x.cap })),
    length: { words_min: tg.min, words_max: tg.max, sections_min: tg.sections[0], sections_max: tg.sections[1] }
  };
  return JSON.stringify(o);
}
const norm = t => String(t || '').toLowerCase().replace(/[“”"‘’'`]/g, '').replace(/[^\p{L}\p{N}]+/gu, ' ').trim();
const nums = t => (String(t || '').replace(/(\d),(?=\d{3}\b)/g, '$1').match(/\d+(?:\.\d+)?/g) || []);
// checks an AI answer against the facts; returns { doc } or { errors }
export function checkAi(out, pack, s, tg, recent) {
  const E = [], o = out && typeof out === 'object' ? out : {};
  const title = one(o.title), dek = one(o.dek), secs = Array.isArray(o.sections) ? o.sections : [];
  if (words(title) < 2 || title.length > LIMITS.title) E.push('title must be 2 to 12 words');
  if (words(dek) < 5 || dek.length > LIMITS.dek) E.push('dek must be one sentence of 5 to 35 words');
  if (secs.length < tg.sections[0] || secs.length > tg.sections[1]) E.push('there must be ' + tg.sections[0] + ' to ' + tg.sections[1] + ' sections');
  const ids = new Map(pack.photos.map(x => [x.id, x])), seen = new Set();
  const S = secs.map((x, i) => {
    const h = one(x && (x.heading || x.h)).replace(/[:.]+$/, ''), body = para(x && x.body);
    if (!h || h.length > LIMITS.h) E.push('section ' + (i + 1) + ' needs a short heading');
    if (words(body) < 15) E.push('section ' + (i + 1) + ' is too short');
    let ph = null; const pid = x && x.photo != null && x.photo !== '' ? String(typeof x.photo === 'object' ? x.photo.id : x.photo) : '';
    if (pid && pid !== 'null') {
      if (!ids.has(pid)) E.push('photo ' + pid + ' is not in the photos list');
      else if (seen.has(pid)) E.push('photo ' + pid + ' is used twice');
      else { seen.add(pid); const p = ids.get(pid); ph = { row: p.row, id: p.id, cap: p.cap }; }
    }
    return { h: cut(h, LIMITS.h), body: cut(body, LIMITS.body), photo: ph };
  });
  const n = S.reduce((a, x) => a + words(x.body), 0);
  if (n < tg.min || n > tg.max + 100) E.push('the sections have ' + n + ' words; write ' + tg.min + ' to ' + tg.max);
  if (pack.photos.length >= 3 && seen.size < Math.min(3, S.length)) E.push('use the photos: give fitting sections a photo id');
  // the quote: word for word, or none at all
  const pq = o.pull && typeof o.pull === 'object' ? o.pull : null;
  if (pack.quote) { if (pq && norm(pq.quote) !== norm(pack.quote)) E.push('pull.quote must be the given quote word for word'); }
  else if (pq && one(pq.quote)) E.push('there is no quote: pull must be null');
  // nothing in quotation marks that nobody said
  const said = norm([pack.quote].concat(Object.values(pack.answers || {}), pack.visits.map(v => v.note), pack.stages.map(g => g.notes.join(' '))).join(' '));
  const all = [title, dek].concat(S.map(x => x.h + ' ' + x.body)).join('\n');
  (all.match(/[“"]([^”"]{12,})[”"]/g) || []).forEach(q => { const t = norm(q); if (words(t) >= 3 && said.indexOf(t) < 0) E.push('this is quoted but nobody said it: ' + q.slice(0, 80)); });
  // every number must come from the facts
  const known = new Set(nums(promptFacts(pack, tg).replace(/"length":\{[^}]*\}/, '')).concat(['1']));
  nums(all).forEach(x => { if (!known.has(x) && !known.has(String(Number(x)))) E.push('the number ' + x + ' is not in the facts'); });
  if (/[#*_]{2,}|^#/m.test(all)) E.push('no markdown');
  // not the same as a recent story
  const first6 = t => norm(t).split(' ').slice(0, 6).join(' ');
  (recent || []).forEach(r => {
    if (r && r.title && norm(r.title) === norm(title)) E.push('the title "' + title + '" is already used by another story: write a new one');
    if (r && r.open && S[0] && first6(r.open) && first6(r.open) === first6(S[0].body)) E.push('the story opens with the same words as another story: open differently');
  });
  if (E.length) return { errors: Array.from(new Set(E)).slice(0, 12) };
  return { doc: { title: cut(title, LIMITS.title), dek: cut(dek, LIMITS.dek), sections: S, pull: pullOf(s) } };
}
function parseJson(t) { try { return JSON.parse(String(t || '').replace(/^```(json)?\s*|\s*```$/g, '')); } catch (e) { return null; } }

// With the AI text key (OPENAI_API_KEY): a long, true story as JSON, checked against the facts; one retry with the
// problems listed; null when there is no key or it still fails (the caller then uses the template)
export async function aiDraft(env, pack, s, by, o) {
  if (!env.OPENAI_API_KEY) return null;
  o = o || {};
  const model = String(env.STORY_MODEL || 'gpt-4o-mini'), tg = target(pack), sh = pickShape(pack, o);
  const msgs = [{ role: 'system', content: sysFor(sh, o.recent) }, { role: 'user', content: promptFacts(pack, tg) }];
  for (let round = 0; round < 2; round++) {
    let out = null, raw = '';
    try {
      const r = await fetch('https://api.openai.com/v1/chat/completions', { method: 'POST', headers: { Authorization: 'Bearer ' + env.OPENAI_API_KEY, 'Content-Type': 'application/json' },
        body: JSON.stringify({ model, temperature: 0.9, max_tokens: 2600, response_format: { type: 'json_object' }, messages: msgs }) });
      if (!r.ok) return null;
      const j = await r.json(); raw = j && j.choices && j.choices[0] && j.choices[0].message && String(j.choices[0].message.content || '') || '';
      out = parseJson(raw);
    } catch (e) { return null; }
    const c = out ? checkAi(out, pack, s, tg, o.recent) : { errors: ['the reply was not JSON'] };
    if (c.doc) { const d = finish(c.doc, pack, by, 'openai:' + model); d.shape = sh.shape.k; return d; }
    msgs.push({ role: 'assistant', content: raw.slice(0, 12000) }, { role: 'user', content: 'Your answer broke these rules: ' + c.errors.join('; ') + '. Write it again, fixing every one, using only the facts given. JSON only.' });
  }
  return null;
}

// ---- editing ------------------------------------------------------------------------------------------------------
// An edit from the app (admins): title, dek, the sections' headings and words, and each section's photo (from the pool)
export function editDoc(base, inp, pack, by) {
  const o = inp && typeof inp === 'object' ? inp : {}, pool = new Map(pack.photos.map(x => [x.id, x])), seen = new Set();
  const secs = (Array.isArray(o.sections) ? o.sections : []).slice(0, LIMITS.sections).map(x => {
    const pid = x && x.photo ? String(typeof x.photo === 'object' ? x.photo.id : x.photo) : '';
    let ph = null; if (pid && pool.has(pid) && !seen.has(pid)) { seen.add(pid); const p = pool.get(pid); ph = { row: p.row, id: p.id, cap: cut(one(x.photo && x.photo.cap) || p.cap, LIMITS.cap) }; }
    return { h: cut(one(x && x.h), LIMITS.h), body: cut(para(x && x.body), LIMITS.body), photo: ph };
  }).filter(x => x.h || x.body);
  if (!secs.length) return null;
  const d = { title: cut(one(o.title), LIMITS.title) || (base && base.title) || '', dek: cut(one(o.dek), LIMITS.dek), sections: secs, pull: base ? base.pull : null };
  const keep = base ? { by: base.by, model: base.model } : { by, model: 'template' };
  const r = finish(d, pack, keep.by, keep.model); r.edited = { at: r.at, by: String(by || '') };
  if (base && base.shape) r.shape = base.shape;
  return r;
}
// The old apps edit the plain text: map it back onto the document by its headings (anything unrecognised → one section)
export function docFromText(text, base, pack, by) {
  const P = String(text || '').replace(/\r/g, '').split(/\n\s*\n/).map(x => x.trim()).filter(Boolean);
  if (!P.length) return null;
  const heads = new Map(((base && base.sections) || []).map(x => [x.h, x]));
  const pull = base ? base.pull : null, pullLine = pull ? '“' + pull.quote + '”' + (pull.who ? ' — ' + pull.who : '') : null;
  let title = '', dek = '', i = 0;
  if (base && P[0] === base.title) { title = P[0]; i = 1; if (P[1] && !heads.has(P[1]) && base.dek && P[1] === base.dek) { dek = P[1]; i = 2; } }
  const secs = []; let cur = null;
  for (; i < P.length; i++) {
    if (pullLine && P[i] === pullLine) continue;
    if (heads.has(P[i])) { cur = { h: P[i], body: '', photo: heads.get(P[i]).photo }; secs.push(cur); continue; }
    if (!cur) { cur = { h: secs.length || !base ? 'The story' : (base.sections[0] || {}).h || 'The story', body: '', photo: base && base.sections[0] ? base.sections[0].photo : null }; secs.push(cur); }
    cur.body = cur.body ? cur.body + '\n\n' + P[i] : P[i];
  }
  const d = { title: title || (base && base.title) || '', dek: dek || (base && !title ? base.dek : '') || '', sections: secs.filter(x => x.body).map(x => ({ h: cut(one(x.h), LIMITS.h), body: cut(para(x.body), LIMITS.body), photo: x.photo || null })), pull };
  if (!d.sections.length) return null;
  const r = finish(d, pack, base ? base.by : by, base ? base.model : 'template'); r.edited = { at: r.at, by: String(by || '') };
  return r;
}

// ---- the public copy ---------------------------------------------------------------------------------------------
export const PUB = id => 'pubstory:' + String(id).replace(/\D/g, '');
export const PUBIDX = 'pubstoryidx';
export function slugify(t) {
  return String(t || '').normalize('NFKD').replace(/[̀-ͯ]/g, '').toLowerCase().replace(/['’]/g, '').replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 60).replace(/-+$/, '') || 'story';
}
export const storyPath = (id, slug) => '/stories/' + String(id).replace(/\D/g, '') + '-' + slug;
const clean = t => String(t || '').replace(EMAIL, '').replace(PHONE, '').replace(/[ \t]{2,}/g, ' ').trim();
// the approved story for the website: only consent-safe photos, nothing private
export function snapshot(id, doc, s, pool, project, who, prev) {
  const ok = new Map(pool.filter(p => !p.children || s.consent === 'received').map(p => [p.id, p]));
  const safe = ph => ph && ok.has(String(ph.id)) ? { id: String(ph.id), row: ok.get(String(ph.id)).row, cap: ok.get(String(ph.id)).cap || clean(one(ph.cap)) } : null;   // the latest caption
  const sections = doc.sections.map(x => ({ h: clean(x.h), body: clean(x.body), photo: safe(x.photo) }));
  const stages = ((doc.facts && doc.facts.stages) || []).map(g => ({ stage: g.stage, name: g.name || STAGE_NAME[g.stage] || '', date: g.date || '', photo: safe(g.photo) }));
  const used = sections.map(x => x.photo).filter(Boolean);
  const firstAfter = pool.find(p => p.row === 'after' && ok.has(p.id));
  const anySafe = pool.find(p => ok.has(p.id));   // a story always gets a cover when any photo is safe to show
  const hero = used.find(p => p.row === 'after') || (firstAfter ? safe(firstAfter) : null) || used[used.length - 1] || (anySafe ? safe(anySafe) : null);
  const before = pool.find(p => p.row === 'before' && ok.has(p.id));
  const photos = Array.from(new Set(used.concat(stages.map(g => g.photo).filter(Boolean), hero ? [hero] : [], before ? [before] : []).map(p => String(p.id))));
  const f = doc.facts || {}, pr = project || {}, now = new Date().toISOString();
  const title = clean(doc.title) || pr.title || 'A RAFIKI story';
  return {
    id: String(id), slug: slugify(title), title, dek: clean(doc.dek), sections,
    pull: doc.pull && doc.pull.quote ? { quote: clean(doc.pull.quote), who: clean(doc.pull.who) } : null,
    facts: { place: f.place || pr.area || pr.title || '', country: f.country || pr.country || '', ck: f.ck || pr.ck || '', type: f.type || pr.type || '', people: f.people || pr.people || null, year: f.year || Number(now.slice(0, 4)), stages },
    hero, before: before ? { id: before.id, row: 'before', cap: before.cap } : null, photos,
    approvedAt: (prev && prev.approvedAt) || now, approvedBy: (prev && prev.approvedBy) || String(who || ''), updatedAt: now, words: doc.words || 0,
    project: { title: pr.title || '', area: pr.area || '', country: pr.country || '', ck: pr.ck || '', type: pr.type || '', status: pr.status || '', people: pr.people || null, gps: pr.gps || null }
  };
}
export function indexEntry(p) {
  return { id: p.id, slug: p.slug, title: p.title, dek: p.dek, hero: p.hero, place: p.facts.place, country: p.facts.country, ck: p.facts.ck, type: p.facts.type, year: p.facts.year, approvedAt: p.approvedAt, words: p.words };
}
export async function putPublic(env, p) {
  await env.FEED.put(PUB(p.id), JSON.stringify(p));
  const idx = (await getJson(env, PUBIDX, [])).filter(x => x && x.id !== p.id);
  idx.unshift(indexEntry(p));
  idx.sort((a, b) => String(b.approvedAt).localeCompare(String(a.approvedAt)));
  await putJson(env, PUBIDX, idx.slice(0, 500));
}
export async function dropPublic(env, id) {
  id = String(id).replace(/\D/g, '');
  await env.FEED.delete(PUB(id));
  const idx = await getJson(env, PUBIDX, []);
  if (idx.some(x => x && x.id === id)) await putJson(env, PUBIDX, idx.filter(x => x && x.id !== id));
}
export const getPublic = async (env, id) => (env.FEED ? env.FEED.get(PUB(id), 'json') : null);
export const publicIndex = env => getJson(env, PUBIDX, []);

// The Story column holds long text; when monday.com refuses the whole story, the first part and a pointer
export function shortText(text, tail, max) {
  const m = max || 1800, t = String(text || '');
  if (t.length <= m) return t;
  let c = t.slice(0, m); const i = Math.max(c.lastIndexOf('\n\n'), c.lastIndexOf('. '));
  if (i > m * 0.5) c = c.slice(0, i + 1);
  return c.trim() + '\n\n… (' + tail + ')';
}
// the monday.com note: the full story as simple HTML
export function noteHtml(lead, d) {
  const e = t => String(t || '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  const P = [lead, '<b>' + e(d.title) + '</b>' + (d.dek ? '<br><i>' + e(d.dek) + '</i>' : '')];
  d.sections.forEach(x => P.push((x.h ? '<b>' + e(x.h) + '</b><br>' : '') + e(x.body).replace(/\n\n/g, '<br><br>')));
  if (d.pull) P.push('“' + e(d.pull.quote) + '”' + (d.pull.who ? ' — ' + e(d.pull.who) : ''));
  return P.join('<br><br>');
}
// A story approved before Stories 2 (plain text only): one section under the template's title, with an after photo
export function legacyDoc(text, pack, s, by) {
  const t = templateDraft(pack, s, by), P = String(text || '').replace(/\r/g, '').split(/\n\s*\n/).map(x => x.trim()).filter(Boolean);
  if (s.quote && P.length > 1 && /^“[^”]+”( — .*)?$/.test(P[P.length - 1])) P.pop();
  if (!P.length) return null;
  const ph = pack.photos.find(x => x.row === 'after') || pack.photos[0] || null;
  return finish({ title: t.title, dek: t.dek, sections: [{ h: 'The story', body: P.join('\n\n'), photo: ph ? { row: ph.row, id: ph.id, cap: ph.cap } : null }], pull: t.pull }, pack, by, 'text');
}
