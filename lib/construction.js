// Construction projects with stages (a school building a dormitory, a clinic adding a ward): the build tracker.
// The project's type never changes; the stages live in Workers KV FEED, the status in monday.com.
//   pinstage:<id> → { id, stage, at, by, pct, due, parent, ptitle, hist: [{ stage, at, by, note, update, photos: [assetId] }] }
//   pinstages     → { <id>: { s, at, by, pct, due, parent, t, n } }   one read for the list, the map, Office and the report
// Contract: me-contract.md (construction). Used by functions/api/pin.js, pin-photo.js and lib/donor.js.
import { getJson, putJson } from './pin-store.js';

export const STAGES = ['planned', 'foundation', 'walls', 'roof', 'finishing', 'handed'];
export const STAGE_EN = { planned: 'Planned', foundation: 'Foundation', walls: 'Walls', roof: 'Roof', finishing: 'Finishing', handed: 'Handed over' };
export const BUILD_STATUS = { building: 'Under Construction', planned: 'Planned and/or Permitted' };
export const DONE_STATUS = 'Completed/Operational';
const DAY = /^\d{4}-\d{2}-\d{2}$/;
const MAX_HIST = 60, MAX_PHOTOS = 40;

export const stageKey = id => 'pinstage:' + String(id).replace(/\D/g, '');
export const loadStage = (env, id) => (env.FEED ? env.FEED.get(stageKey(id), 'json') : null);
export const stageIndex = env => getJson(env, 'pinstages', {});
export function cleanPct(v) { if (v === null || v === '' || v === undefined) return null; const n = Math.round(Number(v)); return isFinite(n) && n >= 0 && n <= 100 ? n : undefined; }
export function cleanDue(v) { if (v === null || v === '' || v === undefined) return null; return DAY.test(String(v)) && !isNaN(Date.parse(v)) ? String(v) : undefined; }
export function behind(r, now) {
  if (!r || !r.due || r.stage === 'handed') return false;
  return Date.parse(r.due + 'T23:59:59Z') < (now || Date.now());
}
export function withBehind(r, now) { return r ? Object.assign({}, r, { behind: behind(r, now) }) : null; }
// field users move one stage on (or stay, to add progress photos); admins anything; handing over is for admins
export function stageAllowed(cur, next, admin) {
  if (!STAGES.includes(next)) return 'stage';
  if (next === 'handed' && !admin) return 'admin-only';
  if (admin) return '';
  const a = STAGES.indexOf(cur || 'planned'), b = STAGES.indexOf(next);
  return b === a || b === a + 1 ? '' : 'not-next';
}
function photosOf(r) { return (r.hist || []).reduce((n, h) => n + (h.photos || []).length, 0); }
async function index(env, r, title) {
  const idx = await stageIndex(env);
  idx[r.id] = { s: r.stage, at: r.at, by: r.by, pct: r.pct, due: r.due, parent: r.parent || null, t: String(title || (idx[r.id] && idx[r.id].t) || '').slice(0, 120), n: photosOf(r) };
  await putJson(env, 'pinstages', idx);
}
export async function saveStage(env, r, title) {
  r.hist = (r.hist || []).slice(-MAX_HIST);
  await env.FEED.put(stageKey(r.id), JSON.stringify(r));
  await index(env, r, title);
  return r;
}
// a new tracked project (New site "Being built now" / "Planned"), starting at the first stage
export function startRecord(id, by, parent, ptitle, note, update, now) {
  const at = new Date(now || Date.now()).toISOString();
  return { id: String(id), stage: 'planned', at, by, pct: null, due: null, parent: parent || null, ptitle: ptitle || '', hist: [{ stage: 'planned', at, by, note: note || '', update: update || '', photos: [] }] };
}
export function applyStage(r, o) {
  const at = new Date(o.now || Date.now()).toISOString();
  r = r || startRecord(o.id, o.by, null, '', '', '', o.now);
  r.hist = r.hist || [];
  r.hist.push({ stage: o.stage, at, by: o.by, note: o.note || '', update: o.update || '', photos: [] });
  r.stage = o.stage; r.at = at; r.by = o.by;
  if (o.pct !== undefined) r.pct = o.pct;
  if (o.due !== undefined) r.due = o.due;
  if (o.stage === 'handed') r.pct = 100;
  return r;
}
// a photo uploaded for a stage change: into the newest entry of that stage (the one the note belongs to, when known)
export async function addStagePhoto(env, id, stage, update, asset) {
  const r = await loadStage(env, id);
  if (!r || !asset) return false;
  const h = (r.hist || []).slice().reverse().find(x => x.stage === stage && (!update || !x.update || String(x.update) === String(update)));
  if (!h) return false;
  h.photos = (h.photos || []).concat([String(asset)]).slice(-MAX_PHOTOS);
  await saveStage(env, r);
  return true;
}
export function hasPhoto(r, asset) { return !!r && (r.hist || []).some(h => (h.photos || []).includes(String(asset))); }
export function linked(idx, id, titleOf) {
  const me = idx[id] || null, t = x => (titleOf && titleOf(x)) || (idx[x] && idx[x].t) || '';
  const parent = me && me.parent ? { id: me.parent, t: t(me.parent), s: idx[me.parent] ? idx[me.parent].s : null } : null;
  const children = Object.keys(idx).filter(k => idx[k] && String(idx[k].parent) === String(id)).map(k => ({ id: k, t: t(k), s: idx[k].s }));
  return { parent, children };
}
// for donors: no names, no notes
export function donorStage(r, now) {
  if (!r) return null;
  const stages = [];
  (r.hist || []).forEach(h => { const last = stages[stages.length - 1]; if (last && last.stage === h.stage) last.photos = last.photos.concat(h.photos || []); else stages.push({ stage: h.stage, at: h.at, photos: (h.photos || []).slice() }); });
  return { stage: r.stage, at: r.at, pct: r.pct == null ? null : r.pct, due: r.due || null, behind: behind(r, now), stages };
}
export function stageNote(o) {
  const L = ['🏗️ Stage: ' + STAGE_EN[o.stage] + (o.was && o.was !== o.stage ? ' (was ' + STAGE_EN[o.was] + ')' : o.was === o.stage ? ' (progress)' : '') + ', by ' + o.by + ' (RAFIKI Pin app).'];
  if (o.note) L.push(o.note + (/[.!?]$/.test(o.note) ? '' : '.'));
  const x = [];
  if (o.pct != null) x.push(o.pct + '% done');
  if (o.due) x.push('expected ' + o.due);
  if (x.length) L.push(x.join(' · ') + '.');
  if (o.stage === 'handed') L.push('Handed over: the status is now Completed/Operational.');
  return L.join(' ');
}
