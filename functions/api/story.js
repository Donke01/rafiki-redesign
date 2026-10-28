// /api/story: the Stories tab of a project in the RAFIKI Pin app (contract: me-contract.md, Stories; lib/story.js).
//   GET  /api/story?id=<project>                     → { ok, story } (answers, quote, photos per row, consent, status, what's missing)
//   GET  /api/story?id=<project>&asset=<id>[&w=]     → one story photo (only assets recorded for that project)
//   POST /api/story { action: answers|consent|draft|doc|text|approve|publish|collecting|photo-remove, id, … }
//     draft: the long story document (lib/story-doc.js: AI when OPENAI_API_KEY is set, else the template) → story.doc
//     doc (admins): { doc: { title, dek, sections: [{ h, body, photo: { id } | null }] } } edits it (photos from story.pool)
//     approve (admins) = approve and publish: the website copy (pubstory:<id>, pubstoryidx, read by /api/stories), the Story
//       status "Published" ("Approved" where the board has no such label), the story in the Story column and a note.
//       Old apps send { text } instead of { doc }; publish is kept (nothing to do once it is on the website).
//     collecting (admins, once approved): back to Collecting, and off the website
//   POST /api/story multipart { action: photo, id, row: before|progress|after, children, cap?, taken? (YYYY-MM-DD from the photo), file } / { action: voice, id, q, file }
//   POST /api/story { action: photo-edit, id, row, asset, cap?, children?, to? } a photo's caption, "shows children", or its row
//   story.newPhotos: photos added since the draft was written (the app offers "Draft again" to use them)
// Field officers collect and draft; only admins edit the final text, approve and publish. Signed in like /api/pin
// (the shared team PIN and donors are refused). The words, files and statuses live in monday.com's story columns.
import { checkPin } from './pin.js';
import { readStoryCols, writeCols, addFileToColumn, createUpdate, addFileToUpdate, assetInfo, readUpdates, columnLabels, STORY_COL, BOARD_ID } from '../../lib/monday.js';
import { QS, ROWS, ST, CONSENT, MAXLEN, loadRec, saveRec, view, blocker, indexStory, clip, colValue } from '../../lib/story.js';
import { hash, opening, PUBIDX, material, templateDraft, aiDraft, editDoc, docFromText, legacyDoc, snapshot, putPublic, dropPublic, getPublic, storyPath, shortText, noteHtml } from '../../lib/story-doc.js';
import { loadStage, hasPhoto } from '../../lib/construction.js';
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
  const stage = await loadStage(env, id).catch(() => null);
  const s = view(id, m, rec, chk.admin);
  // the photos a story may show (story rows and construction stages), for the writer's photo picker
  const pack = material(m.facts.title || m.name, m.facts, s, stage, []);
  s.pool = pack.photos.map(p => ({ id: p.id, row: p.row, cap: p.cap, children: p.children }));
  if (env.FEED) await indexStory(env, s, m.name);
  return { m, rec, s, stage, pack };
}
// where the story lives on the website (the public site's address, as for donor links)
// the new website (the Stories pages) lives on test.rafikioutreach.org until rafikioutreach.org moves off Wix; set
// STORY_LINK_ORIGIN in Cloudflare then. Not the donor-link origin: pin.rafikioutreach.org only serves the app.
const siteOrigin = env => String(env.STORY_LINK_ORIGIN || 'https://test.rafikioutreach.org').replace(/\/+$/, '');
// the Story status label for a published story: "Published", or "Approved" when the board has no "Published" label
async function pubLabel(env, T, B, api) {
  const k = 'pinstorylabels', c = env.FEED ? await env.FEED.get(k, 'json') : null;
  let l = c && Date.now() - c.at < 86400000 ? c.l : undefined;
  if (l === undefined) { l = await columnLabels(T, B, STORY_COL.status, api); if (env.FEED) await env.FEED.put(k, JSON.stringify({ l, at: Date.now() })); }
  return { label: !l || l.some(x => /^published$/i.test(x)) ? ST.published : ST.approved, known: !!l };
}
// the story into the Story column: all of it, or (when monday.com refuses it) the first part and where the rest is
async function writeText(T, B, id, text, tail, status, api) {
  const tries = [];
  [status].concat(status && status.fallback ? [status.fallback] : []).forEach(st => [text].concat([1800, 900, 450].map(n => shortText(text, tail, n))).forEach((t, i, all) => { if (!i || (t !== text && t !== all[i - 1])) tries.push({ st, t, short: !!i }); }));
  let err;
  for (const x of tries) {
    try { await writeCols(T, B, id, Object.assign({ [STORY_COL.text]: { text: x.t } }, x.st ? { [STORY_COL.status]: { label: x.st.label } } : {}), api); return { short: x.short, label: x.st ? x.st.label : '' }; }
    catch (e) { err = e; }
  }
  throw err;
}

// the last drafts written (any project) and the published stories: titles, first words, headings, shapes
const RECENT = 'storyrecent';
async function recentStories(env, id) {
  if (!env.FEED) return [];
  let a = [], b = [];
  try { a = (await env.FEED.get(RECENT, 'json')) || []; } catch (e) { a = []; }
  try { b = (await env.FEED.get(PUBIDX, 'json')) || []; } catch (e) { b = []; }
  const out = a.filter(r => r && r.id !== id), have = new Set(out.map(r => r.id));
  b.forEach(r => { if (r && r.id !== id && !have.has(r.id)) out.push({ id: r.id, title: r.title, open: r.dek || '' }); });
  return out.slice(0, 10);
}
async function rememberStory(env, id, d) {
  if (!env.FEED || !d) return;
  let a = []; try { a = (await env.FEED.get(RECENT, 'json')) || []; } catch (e) { a = []; }
  a = [{ id, title: d.title, open: opening(d), heads: (d.sections || []).map(x => x.h).slice(0, 8), shape: d.shape || '' }].concat(a.filter(r => r && r.id !== id)).slice(0, 12);
  await env.FEED.put(RECENT, JSON.stringify(a));
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
  let ok = known || hasPhoto(await loadStage(env, id).catch(() => null), asset);   // a construction stage photo of this project
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
    const { m, rec } = f; const s = f.s;
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
    const title = m.facts.title || m.name;
    const proj = () => ({ title, area: m.facts.area, country: m.facts.country, ck: m.facts.ck, type: m.facts.type, status: m.facts.status, people: m.facts.people || null, gps: m.facts.gps || null });
    // the website copy, the Story column and a monday.com note; the Story status first, so a refusal publishes nothing
    const publishNow = async (s1) => {
      const prev = await getPublic(env, id), st = await pubLabel(env, T, B, api);
      const snap = snapshot(id, rec.doc, s1, f.pack.photos, proj(), chk.who, prev), path = storyPath(id, snap.slug), url = siteOrigin(env) + path;
      const w = await writeText(T, B, id, rec.doc.text, 'full story on the website: ' + url, { label: st.label, fallback: st.known || st.label !== ST.published ? null : { label: ST.approved } }, api);
      await putPublic(env, snap);
      rec.pub = { at: snap.approvedAt, by: snap.approvedBy, url, path, updated: snap.updatedAt };
      await saveRec(env, id, rec);
      await createUpdate(T, id, noteHtml('📖 Story ' + (prev ? 'updated' : 'approved and published') + ' on the website by ' + chk.who + ': <a href="' + url + '">' + url + '</a>', rec.doc), api);
      return done({ url, monday: w.short ? 'short' : 'full' });
    };
    // an edit to a story already on the website goes there too
    const republish = async () => {
      const snap = snapshot(id, rec.doc, s, f.pack.photos, proj(), chk.who, await getPublic(env, id));
      await putPublic(env, snap);
      rec.pub = Object.assign({}, rec.pub, { path: storyPath(id, snap.slug), url: siteOrigin(env) + storyPath(id, snap.slug), updated: snap.updatedAt });
    };
    const tail = () => rec.pub ? 'full story on the website: ' + rec.pub.url : 'the full draft is in the RAFIKI Pin app';

    if (a === 'draft') {
      if (rec.pub) return json({ ok: false, reason: 'published' }, 409);
      let ups = []; try { ups = await readUpdates(T, id, api); } catch (e) { ups = []; }
      const pack = material(title, m.facts, s, f.stage, ups);
      // variety: the shapes, titles and first words of the latest drafts and published stories, so this one differs
      const recent = await recentStories(env, id);
      const seed = ((Date.now() >>> 0) ^ hash(id)) >>> 0, prevShape = rec.doc && rec.doc.shape || '';
      const ai = await aiDraft(env, pack, s, chk.who, { recent, prevShape, seed }), doc = ai || templateDraft(pack, s, chk.who, seed);
      if (!ai) doc.shape = 'template';
      await rememberStory(env, id, doc);
      if (!doc.sections.length || !doc.text.trim()) return json({ ok: false, reason: 'empty' }, 409);
      await writeText(T, B, id, doc.text, tail(), { label: ST.draft }, api);
      rec.doc = doc; await saveRec(env, id, rec);
      return done({ text: doc.text, how: ai ? 'ai' : 'template', words: doc.words });
    }
    if (a === 'doc' || a === 'text' || a === 'approve' || a === 'publish') {
      if (!chk.admin) return json({ ok: false, reason: 'admin-only' }, 403);
      if (a === 'doc') {
        if (!rec.doc) return json({ ok: false, reason: 'no-text' }, 409);
        const d = editDoc(rec.doc, b.doc, f.pack, chk.who); if (!d) return json({ ok: false, reason: 'bad-request' }, 400);
        rec.doc = d; await writeText(T, B, id, d.text, tail(), null, api);
        if (rec.pub) await republish();
        await saveRec(env, id, rec); return done();
      }
      if (a === 'text') {
        const t = clip(b.text, MAXLEN.text);
        if (!rec.doc) { await writeCols(T, B, id, { [STORY_COL.text]: { text: t } }, api); return done(); }
        if (t !== rec.doc.text) { const d = docFromText(t, rec.doc, f.pack, chk.who); if (!d) return json({ ok: false, reason: 'bad-request' }, 400); rec.doc = d; }
        await writeText(T, B, id, rec.doc.text, tail(), null, api);
        if (rec.pub) await republish();
        await saveRec(env, id, rec); return done();
      }
      if (a === 'publish') {
        if (rec.pub) return done({ url: rec.pub.url });   // already on the website
        if (s.status !== 'approved' && s.status !== 'published') return json({ ok: false, reason: 'not-approved' }, 409);
      }
      // approve (and publish): the edits sent with it first (the new app sends the document, old apps the text)
      let text = s.text;
      if (a === 'approve' && b.doc && typeof b.doc === 'object' && rec.doc) { rec.doc = editDoc(rec.doc, b.doc, f.pack, chk.who) || rec.doc; text = rec.doc.text; }
      else if (a === 'approve' && 'text' in b) {
        text = clip(b.text, MAXLEN.text);
        if (rec.doc && text !== rec.doc.text) rec.doc = docFromText(text, rec.doc, f.pack, chk.who) || rec.doc;
        if (rec.doc) text = rec.doc.text;
      }
      const s1 = Object.assign({}, s, { text });
      const bad = blocker(s1); if (bad) return json({ ok: false, reason: bad }, 409);
      if (!rec.doc) rec.doc = legacyDoc(text, f.pack, s, chk.who);
      if (!rec.doc) return json({ ok: false, reason: 'no-text' }, 409);
      return publishNow(s1);
    }
    if (a === 'collecting') {
      if ((s.status === 'approved' || s.status === 'published' || rec.pub) && !chk.admin) return json({ ok: false, reason: 'admin-only' }, 403);
      await setStatus(env, chk, id, 'collecting');
      if (rec.pub) {   // withdrawn: off the website
        await dropPublic(env, id); rec.pub = null; await saveRec(env, id, rec);
        await createUpdate(T, id, '📖 Story taken off the website by ' + chk.who + ' (back to Collecting).', api);
      }
      return done();
    }
    if (a === 'photo-remove') {
      if (!ROWS.includes(b.row)) return json({ ok: false, reason: 'row' }, 400);
      rec.photos[b.row] = (rec.photos[b.row] || []).filter(p => p.id !== String(b.asset));
      await saveRec(env, id, rec); return done();
    }
    // a photo's caption, "shows children", or its row (Before / In progress / After)
    if (a === 'photo-edit') {
      const from = String(b.row || ''), to = String(b.to || from), aid = String(b.asset || '');
      if (!ROWS.includes(from) || !ROWS.includes(to) || !aid) return json({ ok: false, reason: 'row' }, 400);
      const cur = (s.photos[from] || []).find(p => String(p.id) === aid);
      if (!cur) return json({ ok: false, reason: 'photo' }, 404);
      const p = Object.assign({}, cur);
      if ('cap' in b) p.cap = clip(String(b.cap || '').replace(/\s+/g, ' ').trim(), 120);
      if ('children' in b) p.children = b.children === true || b.children === 'true';
      ROWS.forEach(r => { rec.photos[r] = (rec.photos[r] || []).filter(x => String(x.id) !== aid); });
      rec.photos[to] = (rec.photos[to] || []).concat([p]).slice(-60);
      await saveRec(env, id, rec);
      if (rec.pub && rec.doc) { const g = await fresh(env, chk, id); f.pack = g.pack; await republish(); await saveRec(env, id, rec); }
      return done({ photo: p });
    }
    if (a === 'photo') {
      if (!ROWS.includes(b.row)) return json({ ok: false, reason: 'row' }, 400);
      const file = form && form.get('file'); if (!file || typeof file === 'string') return json({ ok: false, reason: 'bad-request' }, 400);
      if (file.size > MAX_PHOTO) return json({ ok: false, reason: 'too-large' }, 413);
      const type = String(file.type || '').split(';')[0].toLowerCase(); if (!PHOTO[type]) return json({ ok: false, reason: 'type' }, 415);
      const name = 'story-' + b.row + '-' + new Date().toISOString().slice(0, 16).replace(/[:T]/g, '-') + '.' + PHOTO[type];
      const asset = await addFileToColumn(T, id, STORY_COL[b.row], file, name, api);
      const p = { id: asset || '', name, children: b.children === 'true' || b.children === true, by: chk.who, at: new Date().toISOString() };
      const cap = clip(String(b.cap || '').replace(/\s+/g, ' ').trim(), 120); if (cap) p.cap = cap;
      // the day the photo was taken (the app reads it from the photo), if it is a real past date
      const tk = /^(\d{4}-\d{2}-\d{2})/.exec(String(b.taken || '')); if (tk && tk[1] >= '2000-01-01' && tk[1] <= new Date().toISOString().slice(0, 10)) p.taken = tk[1];
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
