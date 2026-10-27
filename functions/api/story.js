// /api/story: the Stories tab of a project in the RAFIKI Pin app (contract: me-contract.md, Stories; lib/story.js).
//   GET  /api/story?id=<project>                     → { ok, story } (answers, quote, photos per row, consent, status, what's missing)
//   GET  /api/story?id=<project>&asset=<id>[&w=]     → one story photo (only assets recorded for that project)
//   POST /api/story { action: answers|consent|draft|text|approve|publish|collecting|photo-remove, id, … }
//   POST /api/story multipart { action: photo, id, row: before|progress|after, children, file } / { action: voice, id, q, file }
// Field officers collect and draft; only admins edit the final text, approve and publish. Signed in like /api/pin
// (the shared team PIN and donors are refused). The words, files and statuses live in monday.com's story columns.
import { checkPin } from './pin.js';
import { readStoryCols, writeCols, addFileToColumn, createUpdate, addFileToUpdate, assetInfo, STORY_COL, BOARD_ID } from '../../lib/monday.js';
import { QS, ROWS, ST, CONSENT, MAXLEN, loadRec, saveRec, view, blocker, indexStory, clip, colValue, templateDraft, aiDraft, words } from '../../lib/story.js';
import { transcribe, transcribeSetup } from '../../lib/transcribe.js';

const PHOTO = { 'image/jpeg': 'jpg', 'image/png': 'png', 'image/webp': 'webp' };
const AUDIO = { 'audio/webm': 'webm', 'audio/mp4': 'm4a', 'audio/x-m4a': 'm4a', 'audio/aac': 'aac', 'audio/mpeg': 'mp3', 'audio/ogg': 'ogg' };
const MAX_PHOTO = 8 * 1024 * 1024, MAX_AUDIO = 4 * 1024 * 1024, PER_HOUR = 200;
function json(body, status) {
  return new Response(JSON.stringify(body), { status: status || 200, headers: { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff', 'X-Robots-Tag': 'noindex' } });
}
async function who(request, env) {
  if (request.headers.get('X-Donor-Token')) return { res: json({ ok: false, reason: 'donor' }, 403) };
  const chk = await checkPin(request, env);
  if (chk.res) return chk;
  return chk;
}
const tok = (chk, env) => chk.token || env.MONDAY_API_TOKEN;
const board = env => String(env.MONDAY_BOARD_ID || BOARD_ID);
async function item(env, chk, id) {
  const m = await readStoryCols(tok(chk, env), id, env.MONDAY_API_URL);
  return m && (!m.board || m.board === board(env)) ? m : null;
}
async function fresh(env, chk, id) {
  const m = await item(env, chk, id), rec = await loadRec(env, id);
  if (!m) return null;
  const s = view(id, m, rec, chk.admin);
  if (env.FEED) await indexStory(env, s, m.name);
  return { m, rec, s };
}

export async function onRequestGet({ request, env }) {
  const chk = await who(request, env);
  if (chk.res) return chk.res;
  const q = new URL(request.url).searchParams, id = String(q.get('id') || '').replace(/\D/g, '');
  if (!id) return json({ ok: false, reason: 'bad-request' }, 400);
  try {
    if (q.get('asset')) return photo(env, chk, id, String(q.get('asset')).replace(/\D/g, ''));
    const f = await fresh(env, chk, id);
    return f ? json({ ok: true, story: f.s }) : json({ ok: false, reason: 'project' }, 404);
  } catch (e) { console.error('story get:', e && e.message); return json({ ok: false, reason: 'down' }, 502); }
}
async function photo(env, chk, id, asset) {
  const rec = await loadRec(env, id), known = ROWS.some(r => (rec.photos[r] || []).some(p => p.id === asset));
  let ok = known;
  if (!ok) { const m = await item(env, chk, id); ok = !!m && ROWS.some(r => m.files(STORY_COL[r]).some(f => f.id === asset)); }
  if (!ok || !asset) return json({ ok: false, reason: 'not-found' }, 404);
  const info = await assetInfo(tok(chk, env), asset, env.MONDAY_API_URL);
  if (!info) return json({ ok: false, reason: 'not-found' }, 404);
  const r = await fetch(info.public_url);
  const type = r.ok ? r.headers.get('Content-Type') || '' : '';
  if (!/^image\//i.test(type)) return json({ ok: false, reason: 'not-found' }, 404);
  return new Response(r.body, { headers: { 'Content-Type': type, 'Cache-Control': 'private, max-age=86400', 'X-Content-Type-Options': 'nosniff' } });
}

async function limited(request, env) {
  if (!env.FEED) return false;
  const k = 'rl:story:' + (request.headers.get('CF-Connecting-IP') || 'unknown'), n = Number(await env.FEED.get(k)) || 0;
  if (n >= PER_HOUR) return true;
  await env.FEED.put(k, String(n + 1), { expirationTtl: 3600 });
  return false;
}
const setStatus = (env, chk, id, key) => writeCols(tok(chk, env), board(env), id, { [STORY_COL.status]: { label: ST[key] } }, env.MONDAY_API_URL);

export async function onRequestPost({ request, env }) {
  const origin = request.headers.get('Origin');
  if (origin && new URL(origin).host !== new URL(request.url).host) return json({ ok: false, reason: 'origin' }, 403);
  const chk = await who(request, env);
  if (chk.res) return chk.res;
  if (!chk.who) return json({ ok: false, reason: 'named-only' }, 403);
  if (!env.FEED) return json({ ok: false, reason: 'not-set-up' }, 503);
  if (await limited(request, env)) return json({ ok: false, reason: 'rate' }, 429);
  const multi = /^multipart\/form-data/i.test(request.headers.get('Content-Type') || '');
  let b = {}, form = null;
  try { if (multi) { form = await request.formData(); form.forEach((v, k) => { if (typeof v === 'string') b[k] = v; }); } else b = await request.json(); } catch (e) { return json({ ok: false, reason: 'bad-request' }, 400); }
  if (!b || typeof b !== 'object') return json({ ok: false, reason: 'bad-request' }, 400);
  const id = String(b.id || '').replace(/\D/g, ''), a = String(b.action || '');
  if (!id) return json({ ok: false, reason: 'bad-request' }, 400);
  try {
    const f = await fresh(env, chk, id);
    if (!f) return json({ ok: false, reason: 'project' }, 404);
    const { m, rec } = f; let s = f.s;
    const T = tok(chk, env), api = env.MONDAY_API_URL, B = board(env);
    const done = async extra => { const g = await fresh(env, chk, id); return json(Object.assign({ ok: true }, extra || {}, { story: g.s })); };
    const toCollecting = async () => { if (s.status === 'not-started') await setStatus(env, chk, id, 'collecting'); };

    if (a === 'answers') {
      const vals = {};
      QS.concat(['quote', 'quoteBy', 'credit']).forEach(k => { if (k in b) vals[STORY_COL[k]] = colValue(k, clip(b[k], MAXLEN[k] || 3000)); });
      if (!Object.keys(vals).length) return json({ ok: false, reason: 'bad-request' }, 400);
      if (s.status === 'not-started') vals[STORY_COL.status] = { label: ST.collecting };
      await writeCols(T, B, id, vals, api);
      return done();
    }
    if (a === 'consent') {
      if (!CONSENT[b.value]) return json({ ok: false, reason: 'bad-request' }, 400);
      await writeCols(T, B, id, Object.assign({ [STORY_COL.consent]: { label: CONSENT[b.value] } }, s.status === 'not-started' ? { [STORY_COL.status]: { label: ST.collecting } } : {}), api);
      return done();
    }
    if (a === 'draft') {
      const ai = await aiDraft(env, m.name, m.facts, s), text = ai || templateDraft(m.name, m.facts, s);
      if (!text.trim()) return json({ ok: false, reason: 'empty' }, 409);
      await writeCols(T, B, id, { [STORY_COL.text]: { text }, [STORY_COL.status]: { label: ST.draft } }, api);
      return done({ text, how: ai ? 'ai' : 'template', words: words(text) });
    }
    if (a === 'text' || a === 'approve' || a === 'publish') {
      if (!chk.admin) return json({ ok: false, reason: 'admin-only' }, 403);
      if (a === 'text') { await writeCols(T, B, id, { [STORY_COL.text]: { text: clip(b.text, MAXLEN.text) } }, api); return done(); }
      if (a === 'approve') {
        if ('text' in b) s = Object.assign({}, s, { text: clip(b.text, MAXLEN.text) });
        const bad = blocker(s); if (bad) return json({ ok: false, reason: bad }, 409);
        await writeCols(T, B, id, Object.assign({ [STORY_COL.status]: { label: ST.approved } }, 'text' in b ? { [STORY_COL.text]: { text: s.text } } : {}), api);
        return done();
      }
      if (s.status !== 'approved' && s.status !== 'published') return json({ ok: false, reason: 'not-approved' }, 409);
      await setStatus(env, chk, id, 'published'); return done();
    }
    if (a === 'collecting') {
      if ((s.status === 'approved' || s.status === 'published') && !chk.admin) return json({ ok: false, reason: 'admin-only' }, 403);
      await setStatus(env, chk, id, 'collecting'); return done();
    }
    if (a === 'photo-remove') {
      if (!ROWS.includes(b.row)) return json({ ok: false, reason: 'row' }, 400);
      rec.photos[b.row] = (rec.photos[b.row] || []).filter(p => p.id !== String(b.asset));
      await saveRec(env, id, rec); return done();
    }
    if (a === 'photo') {
      if (!ROWS.includes(b.row)) return json({ ok: false, reason: 'row' }, 400);
      const file = form && form.get('file'); if (!file || typeof file === 'string') return json({ ok: false, reason: 'bad-request' }, 400);
      if (file.size > MAX_PHOTO) return json({ ok: false, reason: 'too-large' }, 413);
      const type = String(file.type || '').split(';')[0].toLowerCase(); if (!PHOTO[type]) return json({ ok: false, reason: 'type' }, 415);
      const name = 'story-' + b.row + '-' + new Date().toISOString().slice(0, 16).replace(/[:T]/g, '-') + '.' + PHOTO[type];
      const asset = await addFileToColumn(T, id, STORY_COL[b.row], file, name, api);
      const p = { id: asset || '', name, children: b.children === 'true' || b.children === true, by: chk.who, at: new Date().toISOString() };
      if (asset) { rec.photos[b.row] = (rec.photos[b.row] || []).concat([p]).slice(-60); await saveRec(env, id, rec); }
      await toCollecting();
      return done({ photo: p });
    }
    if (a === 'voice') {
      const q = String(b.q || ''); if (!QS.includes(q) && q !== 'quote') return json({ ok: false, reason: 'bad-request' }, 400);
      const file = form && form.get('file'); if (!file || typeof file === 'string') return json({ ok: false, reason: 'bad-request' }, 400);
      if (file.size > MAX_AUDIO) return json({ ok: false, reason: 'too-large' }, 413);
      const type = String(file.type || '').split(';')[0].toLowerCase(); if (!AUDIO[type]) return json({ ok: false, reason: 'type' }, 415);
      const bytes = new Uint8Array(await file.arrayBuffer());
      let text = '', typed = false;
      if (transcribeSetup(env).on) { try { text = (await transcribe(env, bytes, type)).text || ''; typed = !!text; } catch (e) { text = ''; } }
      if (!typed) {   // kept as audio on a monday note, and noted in the answer
        const label = q === 'quote' ? 'the quote' : q.toUpperCase();
        const up = await createUpdate(T, id, '🎙️ Voice answer for ' + label + ' (Stories), by ' + chk.who + ' (RAFIKI Pin app).', api);
        if (up) await addFileToUpdate(T, up, new Blob([bytes], { type }), 'story-' + q + '.' + AUDIO[type], api);
        text = '(voice note on monday.com, ' + new Date().toISOString().slice(0, 10) + ')';
      }
      const cur = q === 'quote' ? s.quote : s.answers[q];
      const val = typed && cur && !/^\(voice note/.test(cur) ? cur + ' ' + text : text;
      await writeCols(T, B, id, Object.assign({ [STORY_COL[q]]: colValue(q, clip(val, MAXLEN[q] || 3000)) }, s.status === 'not-started' ? { [STORY_COL.status]: { label: ST.collecting } } : {}), api);
      return done({ text, typed });
    }
    return json({ ok: false, reason: 'bad-request' }, 400);
  } catch (e) {
    console.error('story ' + a + ':', e && e.message);
    return json({ ok: false, reason: 'down' }, 502);
  }
}
export function onRequest() { return json({ ok: false, reason: 'method' }, 405); }
