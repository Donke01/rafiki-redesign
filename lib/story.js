// Project stories (the pin app's Stories tab): six guided answers, a quote, Before / In progress / After photos,
// photo consent and a 600–1,000-word story with photos (lib/story-doc.js), all in the monday.com story columns (lib/monday.js STORY_COL).
// KV keeps what monday can't: pinstory:<id> → { photos: { before|progress|after: [{ id, name, children, by, at }] }, doc, pub }
// and an index for lists, pinstoryidx → { <id>: { st, n, b, i, a, c, at, t } }. Contract: me-contract.md (Stories).
// doc: the long draft (lib/story-doc.js); pub: { at, by, url, path } once it is on the website (pubstory:<id>).
import { getJson, putJson } from './pin-store.js';
import { STORY_COL, STORY_LONG } from './monday.js';

export const QS = ['q1', 'q2', 'q3', 'q4', 'q5', 'q6'];
export const ROWS = ['before', 'progress', 'after'];
export const ST = { 'not-started': 'Not started', collecting: 'Collecting', draft: 'Draft ready', approved: 'Approved', published: 'Published' };
const ST_KEY = Object.fromEntries(Object.entries(ST).map(([k, v]) => [v.toLowerCase(), k]));
export const CONSENT = { received: 'Received', 'not-yet': 'Not yet' };
// the questions (EN as on the board's column descriptions, and Kiswahili)
export const PROMPTS = {
  q1: { en: 'What was life like here before the project?', sw: 'Maisha yalikuwaje hapa kabla ya mradi?' },
  q2: { en: 'What was the main problem people faced?', sw: 'Tatizo kubwa ambalo watu walikabili lilikuwa nini?' },
  q3: { en: 'Who does this project serve? (who, and how many)', sw: 'Mradi huu unawahudumia nani? (nani, na wangapi)' },
  q4: { en: 'What part did the community play?', sw: 'Jamii ilichangia nini?' },
  q5: { en: 'What has changed since?', sw: 'Nini kimebadilika tangu hapo?' },
  q6: { en: 'Describe one moment that shows the change.', sw: 'Eleza tukio moja linaloonyesha mabadiliko.' },
  quote: { en: 'A quote from someone it serves', sw: 'Nukuu kutoka kwa mtu anayehudumiwa' },
  quoteBy: { en: 'Who said it (name and role)', sw: 'Aliyesema (jina na nafasi yake)' },
  credit: { en: 'Photo credit', sw: 'Picha na' }
};
export const MAXLEN = { q3: 200, quoteBy: 80, credit: 80, text: 9000 };

export const recKey = id => 'pinstory:' + String(id).replace(/\D/g, '');
export async function loadRec(env, id) { return (env.FEED && await env.FEED.get(recKey(id), 'json')) || { photos: { before: [], progress: [], after: [] } }; }
export async function saveRec(env, id, r) { await env.FEED.put(recKey(id), JSON.stringify(r)); }
export const storyIndex = env => getJson(env, 'pinstoryidx', {});

export function statusKey(label) { return ST_KEY[String(label || '').trim().toLowerCase()] || 'not-started'; }
export function consentKey(label) { return /^received$/i.test(String(label || '').trim()) ? 'received' : 'not-yet'; }
export function clip(s, n) { return String(s == null ? '' : s).replace(/\r/g, '').replace(/[<>]/g, '').trim().slice(0, n || 3000); }
// a column write in the shape monday wants
export function colValue(k, v) { return STORY_LONG[k] ? { text: v } : v; }

// the story as the apps see it, from the monday columns (m: readStoryCols) and the KV record
export function view(id, m, rec, admin) {
  const t = k => m.text(STORY_COL[k]);
  const answers = {}; QS.forEach(q => { answers[q] = t(q); });
  const photos = {};
  // a photo moved to another row (photo-edit) lives in that row's KV list; its file stays in the old monday column, so
  // monday files already listed in any row are skipped
  const inKv = new Set(); ROWS.forEach(r => ((rec.photos && rec.photos[r]) || []).forEach(x => inKv.add(String(x.id))));
  ROWS.forEach(r => {
    const kv = (rec.photos && rec.photos[r]) || [];
    photos[r] = kv.slice().concat(m.files(STORY_COL[r]).filter(f => !inKv.has(String(f.id))).map(f => ({ id: f.id, name: f.name, children: false, by: '', at: '' })));
  });
  // a story drafted as a document keeps its full words in KV (the Story column may only hold the first part)
  const doc = rec.doc && Array.isArray(rec.doc.sections) ? rec.doc : null;
  const s = { id: String(id), status: statusKey(t('status')), consent: consentKey(t('consent')), answers, quote: t('quote'), quoteBy: t('quoteBy'), credit: t('credit'), text: doc ? doc.text : t('text'), photos, doc,
    pub: rec.pub && rec.pub.at ? { at: rec.pub.at, by: rec.pub.by || '', url: rec.pub.url || '', path: rec.pub.path || '' } : null };
  // photos added since the draft was written (the draft can't use them until it is drafted again)
  const since = doc && doc.at ? Date.parse(doc.at) : 0;
  s.newPhotos = since ? ROWS.reduce((n, r) => n + photos[r].filter(p => p.at && Date.parse(p.at) > since).length, 0) : 0;
  // photos with no known date (the writer asks for the month, so the story's timeline is right)
  s.undated = ROWS.reduce((n, r) => n + photos[r].filter(p => !p.taken).length, 0);
  s.missing = missing(s);
  s.canApprove = !!admin && !blocker(s);
  s.prompts = PROMPTS;
  return s;
}
export function answered(s) { return QS.filter(q => String(s.answers[q] || '').trim()).length; }
export function needsConsent(s) { return ROWS.some(r => (s.photos[r] || []).some(p => p.children)); }
export function missing(s) {
  const m = [], n = answered(s);
  if (n < 6) m.push('answers:' + n + '/6');
  if (!(s.photos.after || []).length) m.push('after-photos');
  if (s.consent !== 'received') m.push('consent');
  if (!String(s.text || '').trim()) m.push('text');
  return m;
}
// what stops an admin approving it
export function blocker(s) {
  if (!String(s.text || '').trim()) return 'no-text';
  if (needsConsent(s) && s.consent !== 'received') return 'consent';
  return '';
}
export async function indexStory(env, s, title) {
  const idx = await storyIndex(env);
  idx[s.id] = { st: s.status, n: answered(s), b: s.photos.before.length, i: s.photos.progress.length, a: s.photos.after.length, c: s.consent, at: new Date().toISOString(), t: String(title || '').slice(0, 120) };
  await putJson(env, 'pinstoryidx', idx);
}

// ---- the draft: a long story document with photos (lib/story-doc.js) ------------------------------------------
export { templateDraft, aiDraft, words } from './story-doc.js';
