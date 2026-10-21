// Donor pages: a private page per donor at /donor/<token> with their projects, an exact-location map,
// tailored updates (visits, repairs, consented story moments, approved photos, thank-you messages) and
// impact numbers. Admins manage donors in the pin app (Office › Donors, /api/admin), the page reads
// /api/donor. Everything here is stored in Workers KV (binding FEED):
//   pindonors              → [donor]   name, e-mail, organisation, project ids, photo switches, viewers
//   donortok:<sha256>      → { d, v }  index from a link's hash to its donor and viewer (the link itself is never stored)
//   donorkey               → random key the links are made from, unless the secret DONOR_LINK_SECRET is set
//   donorupd:<project id>  → monday.com notes for that project, already cut down to donor-safe fields (10 min cache)
//   dthanks:<project id>   → thank-you photos and videos from the community, added with the pin app
//
// A link is HMAC-SHA256(key, donor id + viewer id + generation), 256 bits, base64url (43 characters).
// Only its SHA-256 hash is kept, so the KV data alone doesn't reveal anyone's link; admins can still copy
// a donor's link again because the server can make it again from the key. "Rotate" bumps the generation
// (the old link stops working), "Revoke" removes the hash.
//
// Group pages (a church, a company): one donor record, several viewers, each with their own private link.
// Monthly digest: e-mail per viewer through Resend (RESEND_API_KEY + ALERT_FROM), run from the monthly cron in /api/admin.
import { getFeed } from './feed-cache.js';
import { getJson, putJson, escHtml, letterheadHtml } from './pin-store.js';
import { API_URL, assetInfo } from './monday.js';

export const CONTACT = 'info@rafikioutreach.org';
export const DEFAULT_ORIGIN = 'https://www.rafikioutreach.org';
const UPD_TTL = 600000; // monday.com notes are read at most every 10 minutes per project
const MAX_DONORS = 500, MAX_VIEWERS = 20, MAX_PROJECTS = 200;

// ---- links -----------------------------------------------------------------------------------------
const te = new TextEncoder();
const hex = buf => [...new Uint8Array(buf)].map(b => b.toString(16).padStart(2, '0')).join('');
export function b64url(bytes) {
  let s = ''; for (const b of new Uint8Array(bytes)) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}
export function randomId(n) { return b64url(crypto.getRandomValues(new Uint8Array(n || 9))).replace(/[-_]/g, 'x'); }
export async function hmac(key, msg) {
  const k = await crypto.subtle.importKey('raw', te.encode(String(key)), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
  return new Uint8Array(await crypto.subtle.sign('HMAC', k, te.encode(String(msg))));
}
// The key links are made from: the Cloudflare secret DONOR_LINK_SECRET, or a random one kept in KV.
export async function linkKey(env) {
  if (env.DONOR_LINK_SECRET && String(env.DONOR_LINK_SECRET).length >= 16) return String(env.DONOR_LINK_SECRET);
  let k = await env.FEED.get('donorkey');
  if (!k) { k = hex(crypto.getRandomValues(new Uint8Array(32))); await env.FEED.put('donorkey', k); }
  return k;
}
export async function makeToken(key, did, vid, gen) { return b64url(await hmac(key, 'donor-link:' + did + ':' + vid + ':' + gen)); }
export async function hashToken(t) { return hex(await crypto.subtle.digest('SHA-256', te.encode('rafiki-donor:' + String(t)))); }
export const tokenShape = t => /^[A-Za-z0-9_-]{43}$/.test(String(t || ''));
export function sameText(a, b) {
  const x = te.encode(String(a)), y = te.encode(String(b));
  let d = x.length ^ y.length;
  for (let i = 0; i < Math.max(x.length, y.length); i++) d |= (x[i] || 0) ^ (y[i] || 0);
  return d === 0;
}
export function linkOrigin(env) { return String(env.DONOR_LINK_ORIGIN || DEFAULT_ORIGIN).replace(/\/+$/, ''); }
export async function unsubSig(key, did, vid) { return b64url(await hmac(key, 'donor-unsub:' + did + ':' + vid)).slice(0, 22); }

// ---- storage -----------------------------------------------------------------------------------------
export const donors = env => getJson(env, 'pindonors', []);
export const saveDonors = (env, list) => putJson(env, 'pindonors', list);

// The donor and viewer a link belongs to, or null (unknown, malformed, revoked or rotated away).
export async function findByToken(env, token) {
  if (!tokenShape(token) || !env.FEED) return null;
  const h = await hashToken(token);
  const idx = await env.FEED.get('donortok:' + h, 'json');
  if (!idx || !idx.d) return null;
  const list = await donors(env), d = list.find(x => x.id === idx.d);
  const v = d && (d.viewers || []).find(x => x.vid === idx.v);
  if (!v || v.revoked || !v.h || !sameText(v.h, h)) return null;
  return { donor: d, viewer: v, list };
}

// Gives a viewer a fresh link (new generation); the old one stops working.
async function issue(env, key, d, v) {
  if (v.h) await env.FEED.delete('donortok:' + v.h);
  v.gen = (Number(v.gen) || 0) + 1;
  const token = await makeToken(key, d.id, v.vid, v.gen);
  v.h = await hashToken(token); v.revoked = null; v.rotated = new Date().toISOString();
  await env.FEED.put('donortok:' + v.h, JSON.stringify({ d: d.id, v: v.vid }));
  return token;
}
async function revoke(env, v) {
  if (v.h) await env.FEED.delete('donortok:' + v.h);
  v.h = ''; v.revoked = new Date().toISOString();
}
// The current link of a viewer, made again from the key (checked against the stored hash).
async function currentLink(env, key, d, v) {
  if (!v.h || v.revoked) return null;
  const token = await makeToken(key, d.id, v.vid, v.gen);
  return sameText(await hashToken(token), v.h) ? token : null;
}

// ---- admin (Office › Donors) ------------------------------------------------------------------------------
const clean = (s, n) => String(s || '').replace(/[<>]/g, '').replace(/\s+/g, ' ').trim().slice(0, n);
const email = s => { const e = clean(s, 120).toLowerCase(); return /^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(e) ? e : ''; };
const ids = a => [...new Set((Array.isArray(a) ? a : []).map(x => String(x).replace(/\D/g, '')).filter(Boolean))].slice(0, MAX_PROJECTS);
function photoMap(o) {
  const out = {};
  if (o && typeof o === 'object') Object.keys(o).slice(0, MAX_PROJECTS).forEach(k => { const id = k.replace(/\D/g, ''); if (id && (o[k] === 'on' || o[k] === 'off')) out[id] = o[k]; });
  return out;
}
// What admins see: no hashes, no keys.
export function adminView(d) {
  const vs = (d.viewers || []).map(v => ({ vid: v.vid, name: v.name || '', email: v.email || '', primary: !!v.primary, added: v.added || null, lastOpen: v.lastOpen || null, opens: v.opens || 0, revoked: v.revoked || null, active: !!(v.h && !v.revoked), unsub: v.unsub || null }));
  const last = vs.map(v => v.lastOpen).filter(Boolean).sort().pop() || null;
  return { id: d.id, name: d.name, email: d.email || '', org: d.org || '', projects: d.projects || [], showPhotos: d.showPhotos !== false, photos: d.photos || {}, digest: d.digest !== false,
    added: d.added || null, by: d.by || '', lastOpen: last, opens: vs.reduce((s, v) => s + v.opens, 0), revoked: !vs.some(v => v.active), viewers: vs };
}
export async function donorAdminGet(env) {
  const list = await donors(env);
  return { ok: true, donors: list.map(adminView), origin: linkOrigin(env), mail: { ready: mailReady(env) }, cron: !!env.CRON_KEY, secret: !!env.DONOR_LINK_SECRET };
}
export async function donorAdminPost(env, b, by) {
  const key = await linkKey(env), list = await donors(env), now = new Date().toISOString();
  const link = t => linkOrigin(env) + '/donor/' + t;
  const d = b.id ? list.find(x => x.id === String(b.id)) : null;
  const viewer = () => (d.viewers || []).find(v => (b.vid ? v.vid === String(b.vid) : v.primary)) || (d.viewers || [])[0];
  if (b.action !== 'donor-add' && !d) return { status: 404, body: { ok: false, reason: 'gone' } };
  switch (b.action) {
    case 'donor-add': {
      const name = clean(b.name, 80);
      if (!name) return { status: 400, body: { ok: false, reason: 'bad-request' } };
      if (list.length >= MAX_DONORS) return { status: 409, body: { ok: false, reason: 'full' } };
      const nd = { id: randomId(9), name, email: email(b.email), org: clean(b.org, 100), projects: ids(b.projects), showPhotos: b.showPhotos !== false, photos: photoMap(b.photos), digest: b.digest !== false, added: now, by,
        viewers: [{ vid: randomId(6), name, email: email(b.email), primary: true, added: now, gen: 0 }] };
      const token = await issue(env, key, nd, nd.viewers[0]);
      list.push(nd); await saveDonors(env, list);
      return { body: { ok: true, donor: adminView(nd), link: link(token) } };
    }
    case 'donor-update': {
      if ('name' in b && clean(b.name, 80)) d.name = clean(b.name, 80);
      if ('email' in b) d.email = email(b.email);
      if ('org' in b) d.org = clean(b.org, 100);
      if ('projects' in b) d.projects = ids(b.projects);
      if ('showPhotos' in b) d.showPhotos = !!b.showPhotos;
      if ('photos' in b) d.photos = photoMap(b.photos);
      if ('digest' in b) d.digest = !!b.digest;
      const p = (d.viewers || []).find(v => v.primary); if (p) { p.name = d.name; p.email = d.email; }
      d.updated = now; d.updatedBy = by;
      await saveDonors(env, list);
      return { body: { ok: true, donor: adminView(d) } };
    }
    case 'donor-link': {
      const v = viewer(); if (!v) return { status: 404, body: { ok: false, reason: 'gone' } };
      const t = await currentLink(env, key, d, v);
      return t ? { body: { ok: true, link: link(t) } } : { status: 409, body: { ok: false, reason: v.revoked ? 'revoked' : 'rotate' } };
    }
    case 'donor-rotate': {
      const v = viewer(); if (!v) return { status: 404, body: { ok: false, reason: 'gone' } };
      const t = await issue(env, key, d, v); await saveDonors(env, list);
      return { body: { ok: true, link: link(t), donor: adminView(d) } };
    }
    case 'donor-revoke': {
      for (const v of (d.viewers || [])) if (!b.vid || v.vid === String(b.vid)) await revoke(env, v);
      await saveDonors(env, list);
      return { body: { ok: true, donor: adminView(d) } };
    }
    case 'donor-viewer-add': {
      const name = clean(b.name, 80);
      if (!name) return { status: 400, body: { ok: false, reason: 'bad-request' } };
      if ((d.viewers || []).length >= MAX_VIEWERS) return { status: 409, body: { ok: false, reason: 'full' } };
      const v = { vid: randomId(6), name, email: email(b.email), primary: false, added: now, gen: 0 };
      d.viewers = (d.viewers || []).concat([v]);
      const t = await issue(env, key, d, v); await saveDonors(env, list);
      return { body: { ok: true, link: link(t), donor: adminView(d) } };
    }
    case 'donor-viewer-remove': {
      const v = (d.viewers || []).find(x => x.vid === String(b.vid));
      if (!v || v.primary) return { status: 400, body: { ok: false, reason: 'bad-request' } };
      await revoke(env, v); d.viewers = d.viewers.filter(x => x !== v); await saveDonors(env, list);
      return { body: { ok: true, donor: adminView(d) } };
    }
    case 'donor-remove': {
      for (const v of (d.viewers || [])) await revoke(env, v);
      await saveDonors(env, list.filter(x => x !== d));
      return { body: { ok: true } };
    }
  }
  return { status: 400, body: { ok: false, reason: 'bad-request' } };
}

// ---- photos --------------------------------------------------------------------------------------------
// Visit photos of schools, children's homes and clinics can show children and patients: hidden from donors
// until an admin ticks "photos OK" for that project. Water points are shown unless switched off.
export const SENSITIVE = { school: 1, children: 1, health: 1 };
export function photosAllowed(d, p) {
  if (!d || !p || d.showPhotos === false) return false;
  const o = d.photos && d.photos[String(p.id)];
  if (o === 'on') return true;
  if (o === 'off') return false;
  return !SENSITIVE[p.cat || catOf(p)];
}
const CAT = { 'Borehole well': 'water', 'Water collection': 'water', Health: 'health', School: 'school', Orphanage: 'children', Farming: 'livelihoods', Training: 'livelihoods', Nutrition: 'livelihoods' };
export const catOf = p => p.cat || CAT[p.type] || 'community';

// ---- reading monday.com notes -----------------------------------------------------------------------------
// Checklist items from the pin app's visit form (English labels). Only these are shown as "what broke":
// anything typed freely by the field team stays internal.
export const PARTS = new Set(['Pump handle and pivot pins', 'Chain or rods, pump head', 'Spout and water flow', 'Pump works', 'Concrete apron or platform (no cracks)', 'Drainage and soak pit',
  'Fence and gate', 'Lock and opening hours kept', 'Area clean, no standing water', 'Power supply (mains or generator)', 'Solar panels clean and undamaged', 'Pump starts and runs smoothly',
  'Control box and wiring', 'Storage tank and tower', 'Taps and water kiosk', 'Pipes and joints (no leaks)', 'Water meter', 'Gutters and downpipes', 'First-flush diverter',
  'Tank walls (no cracks or leaks)', 'Tap', 'Tank cover and inlet screen', 'Tank base or stand', 'Water point works', 'Handwashing station (water and soap)', 'Latrines clean and usable',
  'Water storage tank', 'Roof and building', 'Roof, gutters and ceiling', 'Walls and floor (no cracks)', 'Doors, windows and locks', 'Desks and furniture', 'Chalkboards and paint',
  'Classrooms in use', 'Building, roof and walls', 'Equipment and beds working', 'Medicines and supplies stocked and in date', 'Water and washrooms', 'Power and lighting',
  'Incinerator and waste disposal', 'Dormitories', 'Beds, mattresses and bedding', 'Kitchen and food store', 'Wall, fence and gate', 'Pen or coop: roof, walls and floor', 'Animals healthy',
  'Feed and water for the animals', 'Clean and drained', 'Equipment and furniture', 'Being used as planned', 'Materials on site and stored safely', 'Walls straight, no cracks', 'Roof and timber',
  'Doors and windows fitted', 'Built to the plan and size agreed', 'Site safe and tidy', 'On schedule']);
const STATE = { '✅ Working well': 'working', '❌ Not working': 'notworking', '⚠️ Needs repair': 'repair', '✅ On track': 'working', '⚠️ Problems to fix': 'repair', '❌ Work stopped': 'notworking' };
const PHOTO = /^(jpe?g|png|webp)$/i, VIDEO = /^(mp4|webm|mov|m4v|3gp)$/i;
const kes = s => Number(String(s || '').replace(/[^\d]/g, '')) || 0;

// Story moments in the notes, only those with consent:
//   Story moment: “…” (Mary). Consent to quote and photograph: given.
//   ✨ Story moment approved for the website by Reid: “…” (Mary).
export function parseStories(text) {
  const t = String(text || '').replace(/<br\s*\/?>/gi, '\n'), out = [];
  const re = /Story moment:\s*[“"]([^”"]{1,600})[”"]\s*(?:\(([^)\n]{1,80})\))?\.?\s*Consent to quote and photograph:\s*(given|not given)/gi;
  let m;
  while ((m = re.exec(t))) if (m[3].toLowerCase() === 'given') out.push({ quote: m[1].trim(), who: (m[2] || '').trim() });
  const ap = /Story moment approved for the website by [^:\n]{1,80}:\s*[“"]([^”"]{1,600})[”"]\s*(?:\(([^)\n]{1,80})\))?/gi;
  while ((m = ap.exec(t))) if (!out.some(s => s.quote === m[1].trim())) out.push({ quote: m[1].trim(), who: (m[2] || '').trim() });
  return out;
}

// One monday.com note → the donor-safe facts in it.
export function parseNote(u) {
  const t = String(u.text || '').replace(/<br\s*\/?>/gi, '\n'), at = u.at, ev = [];
  const v = /(Site visit|Construction visit) by [^\n:]*?:\s*(✅ Working well|❌ Not working|⚠️ Needs repair|✅ On track|⚠️ Problems to fix|❌ Work stopped)/.exec(t);
  if (v) {
    const e = { kind: 'visit', at, state: STATE[v[2]], build: v[1] === 'Construction visit' };
    const pf = /Problems found:\s*([^\n]*?)\.(?:\s|$)/.exec(t);
    if (pf) e.problems = pf[1].split(/,\s*/).map(s => s.trim()).filter(s => PARTS.has(s)).slice(0, 8);
    const c = /Estimated cost:\s*KES\s*([\d,]+)/.exec(t); if (c) e.cost = kes(c[1]);
    ev.push(e);
  }
  if (/🔧 Marked as fixed/.test(t) || /Repair job updated[^\n]*step: Fixed/.test(t)) ev.push({ kind: 'fixed', at });
  const bud = /budget approved:\s*KES\s*([\d,]+)/.exec(t); if (bud) ev.push({ kind: 'budget', at, amount: kes(bud[1]) });
  for (const s of parseStories(t)) ev.push({ kind: 'story', at, quote: s.quote, who: s.who });
  const files = u.files || [];
  for (const f of files) {
    const name = String(f.name || ''), ext = String(f.ext || '').replace(/^\./, '').toLowerCase();
    if (/^site-photo-/i.test(name) && PHOTO.test(ext) && !/receipt|invoice/i.test(name)) {
      const lm = /^site-photo-(.*?)-?\d{4}-\d{2}-\d{2}/i.exec(name);
      ev.push({ kind: 'photo', at, id: String(f.id), label: lm ? lm[1].replace(/-+/g, ' ').trim() : '' });
    }
    if (/^donor-thanks-/i.test(name) && (PHOTO.test(ext) || VIDEO.test(ext))) {
      const cap = /Thank-you for donors[^\n“"]*[“"]([^”"]{1,200})[”"]/.exec(t);
      ev.push({ kind: 'thanks', at, id: String(f.id), media: VIDEO.test(ext) ? 'video' : 'photo', caption: cap ? cap[1].trim() : '' });
    }
  }
  return ev;
}

async function gql(token, query, variables, apiUrl) {
  const res = await fetch(apiUrl || API_URL, { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: token }, body: JSON.stringify({ query, variables }) });
  if (!res.ok) throw new Error('monday.com answered ' + res.status);
  const body = await res.json();
  if (body.errors && body.errors.length) throw new Error('monday.com: ' + (body.errors[0].message || 'query failed'));
  return body.data;
}
// The latest 40 notes on a project with their files' ids (readUpdates in monday.js gives 15, with links that expire).
export async function readNotes(token, itemId, apiUrl) {
  const d = await gql(token, 'query ($ids: [ID!]) { items(ids: $ids) { updates(limit: 40) { id text_body created_at assets { id name file_extension } } } }', { ids: [String(itemId)] }, apiUrl);
  const it = d.items && d.items[0];
  return ((it && it.updates) || []).map(u => ({ id: String(u.id), text: u.text_body || '', at: u.created_at, files: (u.assets || []).map(a => ({ id: String(a.id), name: a.name || '', ext: a.file_extension || '' })) }));
}
// A project's notes as donor-safe events, from KV when read in the last 10 minutes.
export async function projectEvents(env, pid, fresh) {
  const key = 'donorupd:' + pid, have = await env.FEED.get(key, 'json');
  if (have && (!fresh || Date.now() - have.at < UPD_TTL)) return have.ev || [];
  if (!env.MONDAY_API_TOKEN) return (have && have.ev) || [];
  try {
    const ev = (await readNotes(env.MONDAY_API_TOKEN, pid, env.MONDAY_API_URL)).flatMap(parseNote);
    await env.FEED.put(key, JSON.stringify({ at: Date.now(), ev }), { expirationTtl: 60 * 60 * 24 * 60 });
    return ev;
  } catch (e) {
    console.error('donor notes ' + pid + ':', e && e.message);
    return (have && have.ev) || [];
  }
}

// ---- building the page -------------------------------------------------------------------------------------
const NUMS = ['pupils', 'teachers', 'patients', 'beds', 'children', 'users', 'animals'];
const CLARITY = { clear: 'clear', cloudy: 'cloudy', dirty: 'dirty' };
const BUILD = { foundation: 'Foundation', walls: 'Walls going up', roof: 'Roofing', finishing: 'Finishing', done: 'Finished' };
const DAY = 864e5;
const t0 = s => Date.parse(s) || 0;
// A visit kept by the pin app (pinvisit:<id>) → the fields a donor sees. No names, phones, notes or costs.
export function publicVisit(v) {
  const r = v.readings || {}, q = v.quality || {}, n = v.numbers || {};
  const out = { at: v.at, state: STATE[v.state] || (['working', 'repair', 'notworking'].includes(v.state) ? v.state : null) };
  if (v.kind === 'build') { out.build = true; if (BUILD[v.stage]) out.stage = BUILD[v.stage]; }
  if (r.flow != null) out.flow = Number(r.flow);
  const users = r.users != null ? r.users : n.users;
  if (users != null) out.users = Number(users);
  if (CLARITY[r.clarity]) out.clarity = CLARITY[r.clarity];
  if (q.ecoli === 'neg' || q.ecoli === 'pos') out.water = q.ecoli === 'neg' ? 'safe' : 'unsafe';
  const nums = {}; NUMS.forEach(k => { if (k !== 'users' && n[k] != null && isFinite(n[k])) nums[k] = Number(n[k]); });
  if (Object.keys(nums).length) out.numbers = nums;
  return out;
}
// The completion date of a project (YYYY-MM-DD from the board when known, else the middle of its year).
function doneAt(p) { if (p.done && /^\d{4}-\d{2}-\d{2}$/.test(p.done)) return Date.parse(p.done + 'T00:00:00Z'); return p.year ? Date.UTC(p.year, 6, 1) : 0; }
// "One year of water": within 30 days after a completion anniversary (needs the exact completion date).
export function anniversary(p, now) {
  if (p.status !== 'Completed' || !p.done || !/^\d{4}-\d{2}-\d{2}$/.test(p.done)) return null;
  const d = new Date(p.done + 'T00:00:00Z'), n = new Date(now);
  let years = n.getUTCFullYear() - d.getUTCFullYear();
  let a = Date.UTC(d.getUTCFullYear() + years, d.getUTCMonth(), d.getUTCDate());
  if (a > now) { years--; a = Date.UTC(d.getUTCFullYear() + years, d.getUTCMonth(), d.getUTCDate()); }
  if (years < 1 || now - a > 30 * DAY) return null;
  return { years, at: new Date(a).toISOString().slice(0, 10) };
}
// Repairs from notes and the open repair list: what broke, when, what it cost, when it was fixed.
export function repairTimeline(events, open) {
  const out = []; let cur = null;
  events.slice().sort((a, b) => t0(a.at) - t0(b.at)).forEach(e => {
    if (e.kind === 'visit' && e.state && e.state !== 'working') {
      if (!cur) { cur = { at: e.at, state: e.state, problems: [], cost: 0, budget: 0, fixedAt: null }; out.push(cur); }
      cur.state = e.state;
      (e.problems || []).forEach(x => { if (!cur.problems.includes(x)) cur.problems.push(x); });
      if (e.cost) cur.cost = e.cost;
    } else if (e.kind === 'budget' && cur) cur.budget = e.amount;
    else if ((e.kind === 'fixed' || (e.kind === 'visit' && e.state === 'working')) && cur) { cur.fixedAt = e.at; cur = null; }
  });
  if (open) {
    if (!cur) { cur = { at: open.at, state: open.state, problems: [], cost: 0, budget: 0, fixedAt: null }; out.push(cur); }
    (open.problems || []).forEach(x => { if (PARTS.has(x) && !cur.problems.includes(x)) cur.problems.push(x); });
    if (open.cost) cur.cost = Number(open.cost) || cur.cost;
    if (open.budget != null && open.budget !== '') cur.budget = Number(open.budget) || cur.budget;
    if (open.step && open.step !== 'reported') cur.step = open.step;
  } else if (cur && cur === out[out.length - 1] && !events.some(e => e.kind === 'fixed')) {
    // not in the open repair list any more: it was dealt with, date unknown
    cur.closed = true;
  }
  return out.map(r => ({ at: r.at, state: r.state, problems: r.problems.slice(0, 8), cost: r.budget || r.cost || 0, costKind: r.budget ? 'approved' : r.cost ? 'estimate' : '', fixedAt: r.fixedAt, open: !r.fixedAt && !r.closed, step: r.step || '' })).reverse();
}

// Everything the donor page shows, from the feed and KV. `src` is plain data so this can be tested.
// src: { donor, viewer, feed, visits: {pid: [pinvisit]}, last: pinlastvisit, repairs: pinrepairs, events: {pid: [event]}, thanks: {pid: [..]}, now }
export function buildView(src) {
  const d = src.donor, now = src.now || Date.now(), feed = src.feed || {}, all = feed.projects || [];
  const projects = [], updates = [];
  for (const pid of d.projects || []) {
    const p = all.find(x => String(x.id) === String(pid));
    if (!p) continue;
    const cat = catOf(p), water = cat === 'water', ev = (src.events && src.events[p.id]) || [], allowed = photosAllowed(d, p);
    // visits: the app's own records first, then notes not already covered
    const vis = ((src.visits && src.visits[p.id]) || []).map(publicVisit);
    ev.filter(e => e.kind === 'visit').forEach(e => { if (!vis.some(v => Math.abs(t0(v.at) - t0(e.at)) < 2 * 3600e3)) vis.push({ at: e.at, state: e.state, build: !!e.build }); });
    vis.sort((a, b) => t0(b.at) - t0(a.at));
    const repairs = repairTimeline(ev, src.repairs && src.repairs[p.id]);
    const openR = repairs.find(r => r.open);
    const lastV = vis[0] || (src.last && src.last[p.id] ? { at: src.last[p.id].at, state: src.last[p.id].state } : null);
    let health = null;
    // Working / Being repaired / Not working: for finished projects (a building site shows its stage instead)
    if (lastV && lastV.state && p.status === 'Completed') {
      const st = lastV.state === 'working' && !openR ? 'working' : (openR && openR.step) || lastV.state === 'repair' ? 'repairing' : 'notworking';
      health = { state: st, at: lastV.at };
    }
    // impact
    const latest = k => { const v = vis.find(x => x[k] != null); return v ? v[k] : null; };
    const latestNum = k => { const v = vis.find(x => x.numbers && x.numbers[k] != null); return v ? v.numbers[k] : null; };
    const people = latest('users') || p.people || 0;
    const flowLpm = latest('flow');
    const lpd = water ? (flowLpm ? Math.round(flowLpm * 60 * 8 / 10) * 10 : p.flow ? Math.round(p.flow * 8 / 10) * 10 : 0) : 0;
    const done = doneAt(p), years = water && p.status === 'Completed' && done ? Math.max(0, (now - done) / (365.25 * DAY)) : 0;
    const impact = { people, litresPerDay: lpd, years: Math.round(years * 10) / 10 };
    if (cat === 'school' && latestNum('pupils')) impact.pupils = latestNum('pupils');
    if (cat === 'health' && latestNum('patients')) impact.patients = latestNum('patients');
    if (cat === 'children' && latestNum('children')) impact.children = latestNum('children');
    const gallery = allowed ? ev.filter(e => e.kind === 'photo').sort((a, b) => t0(b.at) - t0(a.at)).slice(0, 24).map(e => ({ id: e.id, at: e.at, label: e.label || '' })) : [];
    const th = []; const seen = new Set();
    if (allowed) ((src.thanks && src.thanks[p.id]) || []).concat(ev.filter(e => e.kind === 'thanks')).forEach(x => { if (x.id && !seen.has(x.id)) { seen.add(x.id); th.push({ id: String(x.id), at: x.at, media: x.media === 'video' ? 'video' : 'photo', caption: x.caption || '' }); } });
    th.sort((a, b) => t0(b.at) - t0(a.at));
    const stories = []; ev.filter(e => e.kind === 'story').forEach(e => { if (!stories.some(s => s.quote === e.quote)) stories.push({ at: e.at, quote: e.quote, who: e.who || '' }); });
    stories.sort((a, b) => t0(b.at) - t0(a.at));
    // before and after: the same photo slot at its first and latest visit, else the project photo and the latest visit photo
    let ba = null;
    if (gallery.length) {
      const by = {}; gallery.forEach(g => { if (g.label) (by[g.label] = by[g.label] || []).push(g); });
      const slot = Object.keys(by).filter(k => by[k].length > 1 && t0(by[k][0].at) - t0(by[k][by[k].length - 1].at) > DAY).sort((a, b) => by[b].length - by[a].length)[0];
      if (slot) ba = { before: { photo: by[slot][by[slot].length - 1].id, at: by[slot][by[slot].length - 1].at }, after: { photo: by[slot][0].id, at: by[slot][0].at }, label: slot };
      else if ((p.photos || []).length) ba = { before: { board: String(p.photos[0]), at: null }, after: { photo: gallery[0].id, at: gallery[0].at }, label: '' };
    }
    const ann = anniversary(p, now);
    const out = {
      id: String(p.id), title: p.title, type: p.type, cat, water, country: p.country, ck: p.ck, area: p.area || '', status: p.status, year: p.year || null,
      done: p.done || null, people: p.people || 0, gps: Array.isArray(p.gps) ? p.gps : null, depth: p.depth || null, flow: p.flow || null, pump: p.pump || '',
      photos: (p.photos || []).map(String), photosShown: allowed, health, impact, visits: vis.slice(0, 12), repairs: repairs.slice(0, 10), stories: stories.slice(0, 10),
      gallery, thanks: th.slice(0, 12), beforeAfter: ba, anniversary: ann, share: '/p/' + p.id + '-' + slug(p.title)
    };
    projects.push(out);
    vis.slice(0, 12).forEach(v => updates.push(Object.assign({ type: 'visit', pid: out.id }, v)));
    repairs.forEach(r => updates.push(Object.assign({ type: 'repair', pid: out.id }, r, { reported: r.at, at: r.fixedAt || r.at })));
    stories.forEach(s => updates.push({ type: 'story', pid: out.id, at: s.at, quote: s.quote, who: s.who }));
    const days = {}; gallery.forEach(g => { const k = String(g.at).slice(0, 10); (days[k] = days[k] || []).push(g); });
    Object.keys(days).forEach(k => updates.push({ type: 'photos', pid: out.id, at: days[k][0].at, photos: days[k].slice(0, 6).map(g => g.id), n: days[k].length }));
    th.forEach(x => updates.push({ type: 'thanks', pid: out.id, at: x.at, id: x.id, media: x.media, caption: x.caption }));
    if (ann) updates.push({ type: 'anniversary', pid: out.id, at: ann.at + 'T09:00:00Z', years: ann.years });
  }
  updates.sort((a, b) => t0(b.at) - t0(a.at));
  // totals across the donor's projects
  const W = projects.filter(p => p.water), checked = W.filter(p => p.health);
  // people, pupils and so on count finished projects only (the others will serve them)
  const sum = k => projects.filter(p => p.status === 'Completed').reduce((s, p) => s + (Number(p.impact[k]) || 0), 0);
  const totals = {
    projects: projects.length, water: W.length, people: sum('people'), litresPerDay: sum('litresPerDay'), years: Math.round(sum('years') * 10) / 10,
    pupils: sum('pupils'), patients: sum('patients'), children: sum('children'),
    checked: checked.length, working: checked.filter(p => p.health.state === 'working').length,
    workingPct: checked.length ? Math.round(checked.filter(p => p.health.state === 'working').length / checked.length * 100) : null
  };
  const v = src.viewer || {};
  return {
    ok: true, donor: { name: d.name, org: d.org || '', viewer: v.name || d.name, group: (d.viewers || []).length > 1 },
    generated: new Date(now).toISOString(), projects, updates: updates.slice(0, 120), totals, contact: CONTACT
  };
}
export function slug(s) { return String(s || '').normalize('NFKD').replace(/[̀-ͯ]/g, '').toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '').slice(0, 60) || 'project'; }

// Loads the KV data for a donor and builds the page data. `fresh` re-reads monday.com notes older than 10 minutes.
export async function loadView(env, donor, viewer, opts) {
  const o = opts || {};
  const feed = o.feed || await getFeed(env, o.waitUntil);
  const pids = (donor.projects || []).filter(id => (feed.projects || []).some(p => String(p.id) === String(id)));
  const visits = {}, events = {}, thanks = {};
  await Promise.all(pids.map(async pid => {
    visits[pid] = (await env.FEED.get('pinvisit:' + pid, 'json')) || [];
    thanks[pid] = (await env.FEED.get('dthanks:' + pid, 'json')) || [];
    events[pid] = await projectEvents(env, pid, !!o.fresh);
  }));
  return buildView({ donor, viewer, feed, visits, thanks, events, last: await getJson(env, 'pinlastvisit', {}), repairs: await getJson(env, 'pinrepairs', {}), now: o.now });
}

// Is this monday.com file one the donor may see? (a visit photo or thank-you of one of their projects, photos allowed)
export async function donorFile(env, donor, assetId, feed) {
  const all = (feed && feed.projects) || [];
  for (const pid of donor.projects || []) {
    const p = all.find(x => String(x.id) === String(pid));
    if (!p || !photosAllowed(donor, p)) continue;
    const ev = await projectEvents(env, pid, false);
    const hit = ev.find(e => (e.kind === 'photo' || e.kind === 'thanks') && e.id === assetId);
    if (hit) return hit;
    const th = (await env.FEED.get('dthanks:' + pid, 'json')) || [];
    const t = th.find(x => String(x.id) === assetId);
    if (t) return Object.assign({ kind: 'thanks' }, t);
  }
  return null;
}
export { assetInfo };

// ---- monthly e-mail digest ---------------------------------------------------------------------------------
const STATUS_TXT = { working: 'working', repairing: 'being repaired', notworking: 'not working', repair: 'needs repair' };
const fmtDay = s => new Date(s).toLocaleDateString('en-GB', { day: 'numeric', month: 'long', timeZone: 'UTC' });
// What happened on a donor's projects between from and to (ms), as lines for the e-mail.
export function digestLines(view, from, to) {
  const byId = {}; view.projects.forEach(p => { byId[p.id] = p; });
  const lines = [];
  view.updates.filter(u => { const t = t0(u.at); return t >= from && t < to; }).slice().reverse().forEach(u => {
    const p = byId[u.pid]; if (!p) return;
    if (u.type === 'visit') lines.push(fmtDay(u.at) + ': site visit to ' + p.title + (u.state ? ' — ' + (STATUS_TXT[u.state] || u.state) : '') + (u.users ? ', about ' + u.users.toLocaleString('en-US') + ' people using it' : '') + (u.water === 'safe' ? ', water tested safe' : '') + '.');
    else if (u.type === 'repair') lines.push(u.fixedAt ? fmtDay(u.at) + ': ' + p.title + ' repaired' + (u.problems.length ? ' (' + u.problems.join(', ').toLowerCase() + ')' : '') + (u.cost ? ', cost KES ' + u.cost.toLocaleString('en-US') : '') + '.' : fmtDay(u.at) + ': a problem was found at ' + p.title + (u.problems.length ? ' (' + u.problems.join(', ').toLowerCase() + ')' : '') + '; the team is on it.');
    else if (u.type === 'story') lines.push('From ' + p.title + ': “' + u.quote + '”' + (u.who ? ' — ' + u.who : ''));
    else if (u.type === 'photos') lines.push(fmtDay(u.at) + ': ' + u.n + ' new photo' + (u.n === 1 ? '' : 's') + ' from ' + p.title + '.');
    else if (u.type === 'thanks') lines.push('A thank-you ' + (u.media === 'video' ? 'video' : 'photo') + ' from the community at ' + p.title + (u.caption ? ': “' + u.caption + '”' : '') + '.');
    else if (u.type === 'anniversary') lines.push('🎉 ' + p.title + ': ' + (u.years === 1 ? 'one year' : u.years + ' years') + ' of clean water!');
  });
  return lines;
}
export function digestHtml(o) {
  const btn = 'background:#0A78C2;color:#ffffff;padding:12px 22px;border-radius:999px;text-decoration:none;font-weight:bold;display:inline-block';
  return '<div style="font-family:Arial,sans-serif;max-width:600px;color:#141618">' + letterheadHtml(o.site) +
    '<h2 style="color:#04263F;margin:0 0 6px">' + escHtml(o.title) + '</h2>' +
    '<p style="margin:0 0 14px;line-height:1.5">Dear ' + escHtml(o.name) + ', here is what happened on ' + (o.group ? 'the projects your group funded' : 'the projects you funded') + ' in ' + escHtml(o.month) + '.</p>' +
    (o.totals ? '<p style="margin:0 0 14px;line-height:1.5;color:#04263F"><b>' + escHtml(o.totals) + '</b></p>' : '') +
    '<ul style="padding-left:18px;margin:0 0 16px">' + o.lines.map(l => '<li style="margin:0 0 8px;line-height:1.5">' + escHtml(l) + '</li>').join('') + '</ul>' +
    '<p style="margin:18px 0"><a href="' + escHtml(o.link) + '" style="' + btn + '">See your projects, map and photos</a></p>' +
    '<p style="color:#5A6066;font-size:12px;line-height:1.5;margin-top:18px">This link is private to you; please don’t forward it. Questions? Reply to <a href="mailto:' + CONTACT + '">' + CONTACT + '</a>.<br>' +
    '<a href="' + escHtml(o.unsub) + '" style="color:#5A6066">Stop these monthly e-mails</a></p>' +
    '<p style="border-top:1px solid #1A365D;padding-top:6px;color:#808080;font-size:11px;margin-top:18px">501(c)(3) Nonprofit Organization&nbsp; |&nbsp; Providing clean water and community development in East Africa</p></div>';
}
// Sends last month's digest to every viewer with an e-mail who hasn't opted out. Returns a summary.
export const mailReady = env => !!(env.RESEND_API_KEY && env.ALERT_FROM);
// One e-mail through Resend (as sendMail in pin-store.js), with the List-Unsubscribe headers mail apps show as "Unsubscribe".
async function resend(env, to, subject, html, text, headers) {
  const r = await fetch('https://api.resend.com/emails', { method: 'POST', headers: { Authorization: 'Bearer ' + env.RESEND_API_KEY, 'Content-Type': 'application/json' }, body: JSON.stringify({ from: env.ALERT_FROM, to, subject, html, text, headers }) });
  if (!r.ok) { console.error('donor digest:', r.status, (await r.text()).slice(0, 200)); return { ok: false }; }
  return { ok: true };
}
export async function runDigest(env, from, to, site) {
  if (!mailReady(env)) return { ok: false, reason: 'no-mail' };
  if (!env.FEED) return { ok: false, reason: 'no-storage' };
  const list = await donors(env); if (!list.length) return { ok: true, sent: 0 };
  const key = await linkKey(env), feed = await getFeed(env), origin = linkOrigin(env), apiOrigin = site || origin;
  const month = new Date(from).toLocaleDateString('en-GB', { month: 'long', year: 'numeric', timeZone: 'UTC' });
  let sent = 0, failed = 0;
  for (const d of list) {
    if (d.digest === false) continue;
    const vs = (d.viewers || []).filter(v => v.email && v.h && !v.revoked && !v.unsub);
    if (!vs.length) continue;
    const view = await loadView(env, d, vs[0], { feed, now: to });
    const lines = digestLines(view, from, to);
    if (!lines.length) continue;
    const t = view.totals, totals = t.people ? 'Your ' + (t.projects === 1 ? 'project serves' : t.projects + ' projects serve') + ' about ' + t.people.toLocaleString('en-US') + ' people.' : '';
    for (const v of vs) {
      const token = await currentLink(env, key, d, v); if (!token) continue;
      const unsub = apiOrigin + '/api/donor?unsub=' + d.id + '.' + v.vid + '.' + await unsubSig(key, d.id, v.vid);
      const link = origin + '/donor/' + token;
      const html = digestHtml({ site: apiOrigin, title: 'Your RAFIKI projects: ' + month, name: v.name || d.name, month, group: (d.viewers || []).length > 1, totals, lines, link, unsub });
      const text = 'Dear ' + (v.name || d.name) + ',\n\n' + lines.join('\n') + '\n\nSee your projects: ' + link + '\n\nStop these e-mails: ' + unsub;
      const r = await resend(env, [v.email], 'Your RAFIKI projects: ' + month, html, text, { 'List-Unsubscribe': '<' + unsub + '>', 'List-Unsubscribe-Post': 'List-Unsubscribe=One-Click' });
      if (r.ok) sent++; else failed++;
    }
  }
  return { ok: !failed, sent, failed };
}
// Opt-out link from a digest: "<donor id>.<viewer id>.<signature>"
export async function unsubscribe(env, code, dry) {
  const m = /^([A-Za-z0-9]{6,20})\.([A-Za-z0-9]{4,20})\.([A-Za-z0-9_-]{22})$/.exec(String(code || ''));
  if (!m || !env.FEED) return { ok: false };
  const key = await linkKey(env);
  if (!sameText(await unsubSig(key, m[1], m[2]), m[3])) return { ok: false };
  const list = await donors(env), d = list.find(x => x.id === m[1]), v = d && (d.viewers || []).find(x => x.vid === m[2]);
  if (!v) return { ok: false };
  if (!dry && !v.unsub) { v.unsub = new Date().toISOString(); await saveDonors(env, list); }
  return { ok: true, name: v.name || d.name };
}
