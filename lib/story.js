// Project stories (the pin app's Stories tab): six guided answers, a quote, Before / In progress / After photos,
// photo consent and a 150–300-word story, all in the monday.com story columns (lib/monday.js STORY_COL).
// KV keeps what monday can't: pinstory:<id> → { photos: { before|progress|after: [{ id, name, children, by, at }] } }
// and an index for lists, pinstoryidx → { <id>: { st, n, b, i, a, c, at, t } }. Contract: me-contract.md (Stories).
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
export const MAXLEN = { q3: 200, quoteBy: 80, credit: 80, text: 4000 };

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
  ROWS.forEach(r => {
    const kv = (rec.photos && rec.photos[r]) || [], seen = new Set(kv.map(x => x.id));
    photos[r] = kv.slice().concat(m.files(STORY_COL[r]).filter(f => !seen.has(f.id)).map(f => ({ id: f.id, name: f.name, children: false, by: '', at: '' })));
  });
  const s = { id: String(id), status: statusKey(t('status')), consent: consentKey(t('consent')), answers, quote: t('quote'), quoteBy: t('quoteBy'), credit: t('credit'), text: t('text'), photos };
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

// ---- the draft --------------------------------------------------------------------------------------------
const sent = s => { s = String(s || '').trim().replace(/\s+/g, ' '); return !s ? '' : /[.!?”"]$/.test(s) ? s : s + '.'; };
const low = s => s ? s.charAt(0).toLowerCase() + s.slice(1) : s;
// a structured draft from the answers alone: nothing added that wasn't given
export function templateDraft(title, facts, s) {
  const a = s.answers, f = facts || {}, P = [];
  const where = [title, f.area].filter(Boolean).join(', ');
  const kind = { 'Borehole well': 'borehole', 'Water collection': 'water project', School: 'school project', Health: 'health project', Orphanage: "children's home project", Farming: 'farming project' }[f.type] || 'project';
  P.push(sent((where ? 'At ' + where : 'Here') + ', RAFIKI Outreach and the community worked together on a ' + kind + (f.done ? ', finished on ' + f.done : '')) + (a.q1 ? ' ' + sent('Before it, ' + low(a.q1)) : ''));
  if (a.q2) P.push(sent('The main problem: ' + low(a.q2)));
  const who = a.q3 ? sent('It serves ' + low(a.q3)) : f.people ? sent('It serves about ' + Number(f.people).toLocaleString('en-US') + ' people') : '';
  if (who || a.q4) P.push([who, a.q4 ? sent('The community played its part: ' + low(a.q4)) : ''].filter(Boolean).join(' '));
  if (a.q5) P.push(sent('Since then, ' + low(a.q5)));
  if (a.q6) P.push(sent(a.q6));
  if (s.quote) P.push('“' + s.quote.replace(/^[“"]|[”"]$/g, '').trim() + '”' + (s.quoteBy ? ' — ' + s.quoteBy : ''));
  return P.filter(Boolean).join('\n\n');
}
export function words(t) { return String(t || '').trim().split(/\s+/).filter(Boolean).length; }
// with an AI text key already in the env (OPENAI_API_KEY, the one speech-to-text uses): a careful prompt that may only use
// the facts given; any problem → the template
export async function aiDraft(env, title, facts, s) {
  if (!env.OPENAI_API_KEY) return null;
  const facts1 = { project: title, type: facts.type, place: facts.area, people_served: facts.people || null, completed: facts.done || null,
    answers: { life_before: s.answers.q1, main_problem: s.answers.q2, who_it_serves: s.answers.q3, community_part: s.answers.q4, what_changed: s.answers.q5, one_moment: s.answers.q6 },
    quote: s.quote ? { text: s.quote, by: s.quoteBy } : null };
  const sys = 'You write short project stories for RAFIKI Outreach, a charity building water points, schools and clinics in East Africa. ' +
    'Write 150 to 300 words of plain, warm English for the website, in the third person, in 3 to 5 short paragraphs. ' +
    'Use ONLY the facts in the JSON. Do not invent names, numbers, dates, places, feelings or quotes. If a fact is missing, leave it out. ' +
    'Include the quote word for word if one is given, with who said it. No headings, no hashtags, no emojis.';
  try {
    const r = await fetch('https://api.openai.com/v1/chat/completions', { method: 'POST', headers: { Authorization: 'Bearer ' + env.OPENAI_API_KEY, 'Content-Type': 'application/json' },
      body: JSON.stringify({ model: String(env.STORY_MODEL || 'gpt-4o-mini'), temperature: 0.3, max_tokens: 700, messages: [{ role: 'system', content: sys }, { role: 'user', content: JSON.stringify(facts1) }] }) });
    if (!r.ok) return null;
    const j = await r.json(), t = j && j.choices && j.choices[0] && j.choices[0].message && String(j.choices[0].message.content || '').trim();
    return t && words(t) >= 60 ? clip(t, MAXLEN.text) : null;
  } catch (e) { return null; }
}
