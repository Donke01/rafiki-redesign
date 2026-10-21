// /api/pin: the RAFIKI pin app (/pin), for the RAFIKI team only.
//   GET  /api/pin               → projects shown on the website (with their point and first photo),
//                                  open repairs, and who the PIN belongs to
//                                  (and tests: {id: when the water was last tested}, kept in KV "pintests")
//   GET  /api/pin?id=<item>     → the last pin made with the app on that project (for undo)
//   GET  /api/pin?history=<item>→ the latest notes on that project in monday.com, with their files
//   GET  /api/pin?activity=1    → what the team did with the app in the last 30 days
//   GET  /api/pin?team=1        → who is on the team: name, home country, phone (if the office added one), last seen
//   POST /api/pin  { action: "pin",   id, lat, lng, acc, how, by }   saves "lat, lng" into "GPS Coordinates"
//                  { action: "undo",  id, by }                       puts back the value from before the last pin
//                  { action: "visit", id, state, note, lat, lng, readings, by } leaves a site-visit note
//                  { action: "fixed", id, note, actual, what, receipt, by }  marks a reported problem as fixed (actual = KES spent)
//                  { action: "parts", id, actual, what, receipt, by } the person on the job (or an admin): parts ordered;
//                                                                     above the approval limit it asks for a second approval first
//                  { action: "new",   name, ck, village, type, lat, lng, note, by }  adds a new site as "Due Diligence"
//                  { action: "here",  lat, lng, acc } / { action: "here", off: true }  your latest position for the office's team map (opt-in)
// Every change also leaves a note on the monday.com item (who, what, the old value). The answer
// carries that note's id, so photos and voice notes can be attached to it with /api/pin-photo.
//
// Signing in: people who signed in with monday.com (lib/pin-auth.js) are recognised by their session
// cookie, and their changes are written with their own monday.com token, so they show as the author.
// PINs (Cloudflare secrets), the fallback: PIN_USERS gives each person their own PIN, written as
// "Meril:1234, Bertin:5678"; removing someone's entry switches off just their access, and their
// name is taken from the PIN. PIN_CODE is an optional shared team PIN. Until one of them is set
// the app stays locked. Wrong PINs are limited per connection.
// Open repairs and the activity log are kept in Workers KV (FEED), not on the board.
// Admins (PIN_ADMINS, or made admin in the app) also get the Office tools in /api/admin.
import { getFeed, dropFeed } from '../../lib/feed-cache.js';
import { parseGps, readGps, writeGps, createUpdate, createSite, readUpdates, tidyTitle, BOARD_ID } from '../../lib/monday.js';
import { session, configured, linkToken, linkedFor, unlink } from '../../lib/pin-auth.js';
import { getJson as gj, putJson, team, saveTeam, hashPin, isAdminName, sameName, seen, addReview, sendMail, alertHtml } from '../../lib/pin-store.js';
import { kes, money, estOf, stepAtOrAfter, stepBlocked, approveLimit, askApproval, costRow, addCost, projectCosts, peopleServed, notePeople, snapPeople, monthKey } from '../../lib/pin-office.js';
import { postHere } from '../../lib/pin-here.js';

const MAX_FAILS = 8; // wrong PINs per connection per hour
const MAX_SAVES = 120; // changes per connection per hour
const COUNTRIES = { ke: ['KE', 'Kenya'], tz: ['TZ', 'Tanzania'], ug: ['UG', 'Uganda'], mw: ['MW', 'Malawi'] };
const TYPES = { borehole: 'Water (Drilling)', collection: 'Water (Collection)', school: 'Educational', health: 'Medical', children: 'Orphanage', farming: 'Agricultural', community: 'Community', other: 'Other' };
const VISIT = { working: '✅ Working well', notworking: '❌ Not working', repair: '⚠️ Needs repair' };
const CLARITY = { clear: 'clear', cloudy: 'cloudy', dirty: 'dirty or coloured' };
const URGENCY = { urgent: 'urgent', month: 'within a month', watch: 'keep an eye on it' };
const ECOLI = { neg: 'not found', pos: 'found' };
const YN = { yes: 1, no: 1, unsure: 1 };
// Projects that aren't water: what the visit is checking, the numbers counted, and who looks after it.
const KINDS = { school: 1, health: 1, home: 1, farm: 1, building: 1, build: 1 };
const BUILD_STATE = { working: '✅ On track', repair: '⚠️ Problems to fix', notworking: '❌ Work stopped' };
const STAGE = { foundation: 'foundation', walls: 'walls going up', roof: 'roofing', finishing: 'finishing (plaster, floor, paint, doors, windows)', done: 'finished' };
const NUMS = { pupils: 'pupils enrolled', teachers: 'teachers', patients: 'patients a week', staff: 'staff on duty', beds: 'beds in use', children: 'children living there',
  carers: 'carers and staff', animals: 'animals now', born: 'born or hatched since the last visit', died: 'died since the last visit', sold: 'sold since the last visit',
  income: 'income from sales since the last visit (KES)', users: 'people using it (estimate)', workers: 'workers on site' };
const CARER = { school: 'Head teacher', health: 'In charge', home: 'Home manager', farm: 'Farmer', building: 'In charge', build: 'Fundi / site foreman' };
const HOME = { KE: 'ke', TZ: 'tz', UG: 'ug', MW: 'mw' };

function json(body, status) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff', 'X-Robots-Tag': 'noindex' }
  });
}

function sameText(a, b) {
  // constant-time comparison so a PIN can't be guessed from response times
  const x = new TextEncoder().encode(String(a)), y = new TextEncoder().encode(String(b));
  let diff = x.length ^ y.length;
  for (let i = 0; i < Math.max(x.length, y.length); i++) diff |= (x[i] || 0) ^ (y[i] || 0);
  return diff === 0;
}
async function count(env, key) { return env.FEED ? Number(await env.FEED.get(key)) || 0 : 0; }
async function bump(env, key) {
  if (!env.FEED) return;
  await env.FEED.put(key, String((Number(await env.FEED.get(key)) || 0) + 1), { expirationTtl: 3600 });
}
// project names saved before names were tidied can start with monday.com's ** or ##
const strip = s => String(s || '').replace(/^[\s*#_-]+|[\s*#_]+$/g, '');
const stripT = x => (x && typeof x.t === 'string' && x.id ? Object.assign({}, x, { t: strip(x.t) }) : x);
const clean = (s, n) => String(s || '').replace(/[<>]/g, '').replace(/\s+/g, ' ').trim().slice(0, n);
const said = s => s ? (/[.!?]$/.test(s) ? s : s + '.') : '';
const fmt = g => g[0].toFixed(6) + ', ' + g[1].toFixed(6);
function metres(a, b) {
  const R = 6371000, r = x => x * Math.PI / 180;
  const dLa = r(b[0] - a[0]), dLo = r(b[1] - a[1]);
  const h = Math.sin(dLa / 2) ** 2 + Math.cos(r(a[0])) * Math.cos(r(b[0])) * Math.sin(dLo / 2) ** 2;
  return 2 * R * Math.asin(Math.sqrt(h));
}
const dist = m => m < 1000 ? Math.round(m) + ' m' : (m / 1000).toFixed(1) + ' km';
const num = (v, max) => { const n = Number(v); return isFinite(n) && n >= 0 && n <= max ? Math.round(n * 10) / 10 : null; };

// Offline map areas (Map › Save this area for offline). The public map servers the app uses don't allow
// bulk downloading (tile.openstreetmap.org, Esri, Google), so by default the app only keeps the tiles you
// look at. If the office runs (or rents) a tile server whose terms allow saving areas for offline use, put
// its address in PIN_OFFLINE_TILES, e.g. "https://tiles.example.org/{z}/{x}/{y}.png" (PIN_OFFLINE_TILES_ATTR:
// the credit line, PIN_OFFLINE_TILES_MAXZOOM: the deepest zoom, default 16); the Android app then downloads
// whole areas from it. Servers known to forbid bulk downloads are refused here.
const NO_BULK = /(^|\.)(openstreetmap\.(org|fr)|arcgisonline\.com|arcgis\.com|googleapis\.com|google\.com|opentopomap\.org|basemaps\.cartocdn\.com|cartocdn\.com)$/i;
export function offlineTiles(env) {
  const url = String(env.PIN_OFFLINE_TILES || '').trim();
  if (!/^https:\/\/[^/]+\/.*\{z\}.*\{x\}.*\{y\}/.test(url)) return null;
  let host = ''; try { host = new URL(url.replace(/\{[a-z]\}/g, '0')).hostname; } catch (e) { return null; }
  if (NO_BULK.test(host)) return null;
  const mz = Math.round(Number(env.PIN_OFFLINE_TILES_MAXZOOM) || 16);
  return { url, attr: clean(env.PIN_OFFLINE_TILES_ATTR || '', 160), maxZoom: Math.min(18, Math.max(10, mz)) };
}

// "Meril:1234, Bertin:5678" → [{ name, pin }]
function people(env) {
  // "Meril:4821" or, with a home country, "Meril:4821:ke"
  return String(env.PIN_USERS || '').split(/[,;\n]/).map(s => {
    const a = s.split(':').map(x => x.trim());
    let home = '';
    if (a.length > 2 && /^(ke|tz|ug|mw)$/i.test(a[a.length - 1])) home = a.pop().toLowerCase();
    return a.length >= 2 ? { name: a.slice(0, -1).join(':'), pin: a[a.length - 1], home } : null;
  }).filter(p => p && p.name && p.pin);
}

// Returns { res } with a Response to send back, or { who, token? } — who is a name ('' for the shared
// team PIN); token is the person's own monday.com token when they signed in with monday.com.
// Shared with /api/pin-photo.
export async function checkPin(request, env) {
  const list = people(env);
  if (!env.MONDAY_API_TOKEN) return { res: json({ ok: false, reason: 'no-monday' }, 503) };
  const s = await session(request, env).catch(() => null);
  if (s) {
    // the cookie is sent automatically, so changes must come from the app's own pages
    if (request.method !== 'GET' && !request.headers.get('Origin') && request.headers.get('Sec-Fetch-Site') !== 'same-origin') return { res: json({ ok: false, reason: 'origin' }, 403) };
    const t = await team(env), mine = t.find(p => sameName(p.name, s.name));
    return { who: s.name, token: s.token, monday: true, kind: 'monday', home: s.home || (mine && mine.home) || '', admin: isAdminName(env, t, s.name) };
  }
  const t = await team(env);
  if (!(request.headers.get('X-Pin') || '').trim() && configured(env)) return { res: json({ ok: false, reason: 'signin' }, 401) };
  if (!env.PIN_CODE && !list.length && !t.length) return { res: json({ ok: false, reason: configured(env) ? 'signin' : 'not-set-up' }, configured(env) ? 401 : 503) };
  const ip = request.headers.get('CF-Connecting-IP') || 'unknown';
  const failKey = 'rl:pinfail:' + ip;
  if (await count(env, failKey) >= MAX_FAILS) return { res: json({ ok: false, reason: 'locked' }, 429) };
  const given = (request.headers.get('X-Pin') || '').trim();
  let who = null, home = '', kind = '';
  // check every entry (no early exit), so timing doesn't reveal which matched
  for (const p of list) if (given && sameText(given, p.pin) && who === null) { who = p.name; home = p.home; kind = 'cloudflare'; }
  // PINs given out in the app (stored hashed)
  if (given && t.length) { const h = await hashPin(env, given); for (const p of t) if (p.h && sameText(h, p.h) && who === null) { who = p.name; home = p.home || ''; kind = 'app'; } }
  if (who === null && given && env.PIN_CODE && sameText(given, String(env.PIN_CODE).trim())) { who = ''; kind = 'shared'; }
  if (who === null) {
    await bump(env, failKey);
    return { res: json({ ok: false, reason: 'pin' }, 401) };
  }
  const admin = !!who && isAdminName(env, t, who);
  // their own monday.com token, if they connected one: changes go in under their own name
  const ln = who ? await linkedFor(env, who).catch(() => null) : null;
  if (ln) return { who, home: home || ln.home || '', admin, kind, token: ln.tok, linked: ln.name };
  return { who, home, admin, kind };
}

// --- open repairs and the activity log (KV) ------------------------------------------------
async function getJson(env, key, dflt) { if (!env.FEED) return dflt; return (await env.FEED.get(key, 'json')) || dflt; }
async function log(env, entry) {
  if (!env.FEED) return;
  const cut = Date.now() - 400 * 864e5; // a year and a bit, for the monthly reports
  const a = (await getJson(env, 'pinact', [])).filter(e => Date.parse(e.at) > cut);
  a.push(Object.assign({ at: new Date().toISOString() }, entry));
  await env.FEED.put('pinact', JSON.stringify(a.slice(-5000)));
}
async function setRepair(env, id, val) {
  if (!env.FEED) return;
  const r = await getJson(env, 'pinrepairs', {});
  if (val) r[id] = val; else delete r[id];
  await env.FEED.put('pinrepairs', JSON.stringify(r));
}

export async function onRequestGet({ request, env, waitUntil }) {
  const chk = await checkPin(request, env);
  if (chk.res) return chk.res;
  const q = new URL(request.url).searchParams;
  // rough position from the internet connection (city level), for computers that can't find themselves
  if (q.get('where')) {
    const cf = request.cf || {}, lat = parseFloat(cf.latitude), lon = parseFloat(cf.longitude);
    return json(isFinite(lat) && isFinite(lon) ? { ok: true, lat, lon, city: String(cf.city || ''), country: String(cf.country || '') } : { ok: false, reason: 'unknown' }, 200);
  }
  const id = (q.get('id') || '').replace(/\D/g, '');
  if (id) {
    const last = env.FEED ? await env.FEED.get('pinlast:' + id, 'json') : null;
    const visits = env.FEED ? ((await env.FEED.get('pinvisit:' + id, 'json')) || []).slice(0, 3) : [];
    // what repairs here cost in the last 12 months, and the latest few entries
    const spend = env.FEED ? (await env.FEED.get('pincost:' + id, 'json')) || [] : [];
    return json({ ok: true, last: last ? { value: last.value, prev: last.prev, by: last.by, at: last.at } : null, canUndo: !!env.FEED, visits, costs: Object.assign(projectCosts(spend), { last: spend.slice(-5).reverse() }) }, 200);
  }
  try {
    const hist = (q.get('history') || '').replace(/\D/g, '');
    if (hist) {
      const cur = await readGps(chk.token || env.MONDAY_API_TOKEN, hist, env.MONDAY_API_URL);
      if (!cur || (cur.board && cur.board !== String(env.MONDAY_BOARD_ID || BOARD_ID))) return json({ ok: false, reason: 'project' }, 404);
      return json({ ok: true, notes: await readUpdates(chk.token || env.MONDAY_API_TOKEN, hist, env.MONDAY_API_URL) }, 200);
    }
    if (q.get('activity')) return json({ ok: true, activity: (await getJson(env, 'pinact', [])).map(stripT) }, 200);
    if (q.get('team')) {
      // the team list for Me & team: no PINs, no e-mails, no admin flags
      const t = await team(env), seenAt = await getJson(env, 'pinseen', {}), prof = await getJson(env, 'pinprofiles', {});
      const list = t.map(p => ({ name: p.name, home: p.home || '', phone: p.phone || '', lastSeen: seenAt[p.name] || null }));
      String(env.PIN_USERS || '').split(/[,;\n]/).forEach(s => {
        const a = s.split(':').map(x => x.trim()); if (a.length < 2) return;
        let home = ''; if (a.length > 2 && /^(ke|tz|ug|mw)$/i.test(a[a.length - 1])) home = a.pop().toLowerCase();
        const name = a.slice(0, -1).join(':'); if (name && !list.some(x => sameName(x.name, name))) list.push({ name, home, phone: '', lastSeen: seenAt[name] || null });
      });
      Object.keys(seenAt).forEach(n => { if (!list.some(x => sameName(x.name, n))) list.push({ name: n, home: '', phone: '', lastSeen: seenAt[n] }); });
      // what people set for themselves in Me › Settings › Personal details
      list.forEach(x => { const k = Object.keys(prof).find(n => sameName(n, x.name)), pr = k && prof[k]; if (pr) { if (pr.phone) x.phone = pr.phone; if (pr.home) x.home = pr.home; if (pr.nick) x.nick = pr.nick; } });
      return json({ ok: true, team: list.sort((a, b) => a.name.localeCompare(b.name)) }, 200);
    }
    const feed = await getFeed(env, waitUntil);
    const projects = (feed.projects || []).map(p => ({
      id: p.id, title: p.title, country: p.country, ck: p.ck, area: p.area || '', type: p.type, status: p.status, year: p.year || null,
      gps: p.gps || null, photo: (p.photos && p.photos[0]) || null, pump: p.pump || ''
    }));
    if (chk.who && waitUntil) waitUntil(seen(env, chk.who));
    const repairs = await getJson(env, 'pinrepairs', {});
    Object.keys(repairs).forEach(k => { if (repairs[k] && repairs[k].t) repairs[k].t = strip(repairs[k].t); });
    const assigns = (await getJson(env, 'pinassign', [])).filter(a => !a.done);
    const extra = {
      admin: !!chk.admin,
      jobs: chk.who ? Object.keys(repairs).filter(k => sameName(repairs[k].assignee, chk.who)) : [],
      assigned: chk.who ? assigns.filter(a => sameName(a.to, chk.who)) : [],
      confirmed: await getJson(env, 'pinconfirmed', {})
    };
    if (chk.admin) extra.reviewOpen = (await getJson(env, 'pinreview', [])).filter(x => x.status === 'open').length;
    // big repairs need a second approval above this (everyone sees it on the repair tracker)
    extra.approveAbove = await approveLimit(env);
    // Office overview: people served, from the latest visit counts and the board (two small KV reads)
    if (chk.admin) {
      const latest = await getJson(env, 'pinpeople', {}), hist = await getJson(env, 'pinpeoplehist', {});
      extra.people = peopleServed(feed.projects || [], latest, hist);
      if (!hist[monthKey(Date.now())] && waitUntil) waitUntil(snapPeople(env, feed.projects || [], latest).catch(() => {}));
    }
    // this person's own details (Me › Settings): how they signed in, and what they've set
    if (chk.who) {
      const prof = await getJson(env, 'pinprofiles', {}), k = Object.keys(prof).find(n => sameName(n, chk.who)), pr = (k && prof[k]) || {};
      const mine = (await team(env)).find(p => sameName(p.name, chk.who)) || {};
      extra.me = { kind: chk.kind || '', nick: pr.nick || '', phone: pr.phone || mine.phone || '', home: pr.home || chk.home || '' };
    } else extra.me = { kind: chk.kind || 'shared', nick: '', phone: '', home: '' };
    return json(Object.assign({ ok: true, who: chk.who, monday: !!chk.monday, linked: chk.linked || '', gmaps: env.GOOGLE_TILES_WEB_KEY || env.GOOGLE_TILES_ANDROID_KEY ? { web: env.GOOGLE_TILES_WEB_KEY || '', android: env.GOOGLE_TILES_ANDROID_KEY || '' } : null, offlineTiles: offlineTiles(env), home: chk.home || '', projects, repairs, visits: await getJson(env, 'pinlastvisit', {}), tests: await getJson(env, 'pintests', {}) }, extra), 200);
  } catch (e) {
    console.error('pin get:', e && e.message);
    return json({ ok: false, reason: 'down' }, 502);
  }
}

export async function onRequestPost({ request, env, waitUntil }) {
  const later = p => (waitUntil ? waitUntil(p) : p);
  const origin = request.headers.get('Origin');
  if (origin && new URL(origin).host !== new URL(request.url).host) return json({ ok: false, reason: 'origin' }, 403);
  const chk = await checkPin(request, env);
  if (chk.res) return chk.res;
  const ip = request.headers.get('CF-Connecting-IP') || 'unknown';
  if (await count(env, 'rl:pinsave:' + ip) >= MAX_SAVES) return json({ ok: false, reason: 'rate' }, 429);

  let b;
  try { b = await request.json(); } catch (e) { return json({ ok: false, reason: 'bad-request' }, 400); }
  if (!b || typeof b !== 'object') return json({ ok: false, reason: 'bad-request' }, 400);
  const action = b.action || 'pin';
  // Me › Connect monday.com: link (or unlink) this person's own monday.com token to their PIN
  if (action === 'monday-link' || action === 'monday-unlink') {
    if (chk.monday) return json({ ok: false, reason: 'already' }, 400);
    if (!chk.who) return json({ ok: false, reason: 'own-pin' }, 400);
    if (!env.FEED) return json({ ok: false, reason: 'not-set-up' }, 503);
    if (action === 'monday-unlink') { await unlink(env, chk.who); return json({ ok: true }, 200); }
    const lk = 'rl:pinlink:' + ip;
    if (await count(env, lk) >= 10) return json({ ok: false, reason: 'rate' }, 429);
    const r = await linkToken(env, chk.who, b.token);
    if (r.fail) { await bump(env, lk); return json({ ok: false, reason: r.fail }, 400); }
    return json({ ok: true, name: r.name, photo: r.photo }, 200);
  }
  // Me › Settings › Personal details: a display name, phone and home country, for your own name
  if (action === 'profile') {
    if (!chk.who) return json({ ok: false, reason: 'own-pin' }, 400);
    if (!env.FEED) return json({ ok: false, reason: 'not-set-up' }, 503);
    const phone = clean(b.phone, 30).replace(/[^\d+ ()-]/g, ''), nick = clean(b.nick, 40), home = /^(ke|tz|ug|mw)$/.test(b.home) ? b.home : '';
    const prof = await getJson(env, 'pinprofiles', {}), k = Object.keys(prof).find(n => sameName(n, chk.who)) || chk.who;
    prof[k] = { nick, phone, home, at: new Date().toISOString() };
    await putJson(env, 'pinprofiles', prof);
    // keep the admin's team list in step for people with an app PIN
    const t = await team(env), mine = t.find(p => sameName(p.name, chk.who));
    if (mine) { mine.phone = phone; if (home) mine.home = home; await saveTeam(env, t); }
    await bump(env, 'rl:pinsave:' + ip);
    return json({ ok: true, me: { kind: chk.kind || '', nick, phone, home } }, 200);
  }
  // Me › Settings › Change my PIN: only for a PIN given out in the app (stored hashed here)
  if (action === 'pin-change') {
    if (chk.kind !== 'app') return json({ ok: false, reason: chk.kind === 'cloudflare' ? 'pin-cloudflare' : 'own-pin' }, 400);
    const ck = 'rl:pinchange:' + ip;
    if (await count(env, ck) >= 6) return json({ ok: false, reason: 'rate' }, 429);
    await bump(env, ck);
    const cur = String(b.current || '').trim(), nw = String(b.pin || '').trim();
    if (!sameText(cur, (request.headers.get('X-Pin') || '').trim())) return json({ ok: false, reason: 'pin-current' }, 400);
    if (!/^\d{4,6}$/.test(nw) || /^(\d)\1+$/.test(nw) || '0123456789'.includes(nw) || '9876543210'.includes(nw)) return json({ ok: false, reason: 'pin-weak' }, 400);
    const t = await team(env), mine = t.find(p => sameName(p.name, chk.who));
    if (!mine) return json({ ok: false, reason: 'own-pin' }, 400);
    const h = await hashPin(env, nw);
    const taken = people(env).some(p => sameText(nw, p.pin)) || (env.PIN_CODE && sameText(nw, String(env.PIN_CODE).trim())) || t.some(p => p !== mine && p.h === h);
    if (taken) return json({ ok: false, reason: 'pin-taken' }, 400);
    mine.h = h; await saveTeam(env, t);
    await log(env, { by: chk.who, a: 'team', t: chk.who + ' changed their PIN' });
    return json({ ok: true }, 200);
  }
  // Me › Settings › Share my location with the office: only the latest point, gone after 12 h (lib/pin-here.js)
  if (action === 'here') { const r = await postHere(env, chk, b, ip); return json(r.body, r.status); }
  // a personal PIN decides the name; with the shared team PIN the app sends it
  const by = chk.who || clean(b.by, 60) || 'someone on the team';
  const token = chk.token || env.MONDAY_API_TOKEN, api = env.MONDAY_API_URL, board = String(env.MONDAY_BOARD_ID || BOARD_ID);
  const id = String(b.id || '').replace(/\D/g, '');

  try {
    // a new site: no existing item needed
    if (action === 'new') {
      const name = clean(b.name, 120), c = COUNTRIES[b.ck];
      if (!name || !c) return json({ ok: false, reason: 'bad-request' }, 400);
      const g = parseGps(Number(b.lat) + ', ' + Number(b.lng));
      if (!g) return json({ ok: false, reason: 'place' }, 400);
      const village = clean(b.village, 120), typeLabel = TYPES[b.type] || '';
      const newId = await createSite(token, board, { name, cc: c[0], countryName: c[1], village, typeLabel, gps: fmt(g) }, api);
      if (!newId) return json({ ok: false, reason: 'down' }, 502);
      const note = '🆕 New site added from the field with the RAFIKI pin app by ' + by + '. Location: ' + fmt(g) + '.' +
        (village ? ' Village/community: ' + village + '.' : '') + (clean(b.note, 1000) ? ' Notes: ' + said(clean(b.note, 1000)) : '') +
        ' It starts as "Due Diligence", so it is not on the website yet.';
      const updateId = await createUpdate(token, newId, note, api);
      await bump(env, 'rl:pinsave:' + ip);
      await log(env, { by, a: 'new', id: newId, t: name });
      if (!chk.admin) await addReview(env, { kind: 'new', id: newId, t: name, by, data: { ck: b.ck, village, gps: fmt(g), type: typeLabel, note: clean(b.note, 300) } });
      return json({ ok: true, id: newId, updateId }, 200);
    }

    if (!id) return json({ ok: false, reason: 'bad-request' }, 400);
    const cur = await readGps(token, id, api);
    if (!cur || (cur.board && cur.board !== board)) return json({ ok: false, reason: 'project' }, 404);
    const title = clean(tidyTitle(cur.name), 120);

    if (action === 'pin') {
      const g = parseGps(Number(b.lat) + ', ' + Number(b.lng));
      if (!g) return json({ ok: false, reason: 'place' }, 400);
      const acc = Math.round(Number(b.acc) || 0);
      const how = b.how === 'gps' ? 'phone GPS' : b.how === 'paste' ? 'pasted coordinates' : b.how === 'search' ? 'place search' + (clean(b.found, 80) ? ': ' + clean(b.found, 80) : '') + ', then placed on the map' : 'map';
      const value = fmt(g);
      // a location an admin has confirmed: field changes wait for approval
      const conf = (await getJson(env, 'pinconfirmed', {}))[id];
      if (conf && !chk.admin) {
        const n2 = '📍 Location change requested with the RAFIKI pin app by ' + by + ': ' + value + ' (' + how + '). The current location was confirmed by ' + conf.by + ', so this waits for an admin to approve it. Current value: ' + (cur.text || 'none') + '.';
        const uid = await createUpdate(token, id, n2, api);
        await addReview(env, { kind: 'request', id, t: title, by, data: { to: value, from: cur.text || '', how } });
        await bump(env, 'rl:pinsave:' + ip);
        await log(env, { by, a: 'request', id, t: title });
        return json({ ok: true, id, pending: true, updateId: uid }, 200);
      }
      const note = '📍 Location pinned with the RAFIKI pin app by ' + by + ': ' + value +
        ' (' + how + (b.how === 'gps' && acc ? ', about ±' + acc + ' m' : '') + ').' +
        (cur.text ? ' Previous value: ' + cur.text + '.' : ' There was no location before.');
      const updateId = await writeGps(token, board, id, value, note, api);
      if (env.FEED) await env.FEED.put('pinlast:' + id, JSON.stringify({ value, prev: cur.text || '', by, at: new Date().toISOString() }), { expirationTtl: 60 * 60 * 24 * 90 });
      await bump(env, 'rl:pinsave:' + ip);
      await dropFeed(env);
      await log(env, { by, a: 'pin', id, t: title });
      const was = parseGps(cur.text);
      if (was && !chk.admin && metres(was, g) > 500) await addReview(env, { kind: 'move', id, t: title, by, data: { from: cur.text, to: value, dist: Math.round(metres(was, g)) } });
      return json({ ok: true, id, gps: g, value, previous: cur.text || '', updateId }, 200);
    }

    if (action === 'undo') {
      const last = env.FEED ? await env.FEED.get('pinlast:' + id, 'json') : null;
      if (!last) return json({ ok: false, reason: 'nothing-to-undo' }, 409);
      if ((cur.text || '').replace(/\s/g, '') !== String(last.value).replace(/\s/g, '')) return json({ ok: false, reason: 'changed' }, 409);
      const note = '↩️ Pin undone with the RAFIKI pin app by ' + by + '. ' + (last.prev ? 'Back to ' + last.prev + '.' : 'The location is empty again.') + ' (It was ' + last.value + '.)';
      const updateId = await writeGps(token, board, id, last.prev || '', note, api);
      await env.FEED.delete('pinlast:' + id);
      await bump(env, 'rl:pinsave:' + ip);
      await dropFeed(env);
      await log(env, { by, a: 'undo', id, t: title });
      return json({ ok: true, id, gps: parseGps(last.prev), value: last.prev || '', updateId }, 200);
    }

    if (action === 'visit') {
      const kind = KINDS[b.kind] ? b.kind : '';
      const state = (kind === 'build' ? BUILD_STATE : VISIT)[b.state];
      if (!state) return json({ ok: false, reason: 'bad-request' }, 400);
      const here = parseGps(Number(b.lat) + ', ' + Number(b.lng)), saved = parseGps(cur.text);
      const L = []; // one line each in the monday.com note
      const urg = URGENCY[b.urgency] && b.state !== 'working' ? b.urgency : '';
      L.push((kind === 'build' ? '🏗️ Construction visit by ' : '🩺 Site visit by ') + by + ' (RAFIKI pin app): ' + state + '.' + (urg ? ' Urgency: ' + URGENCY[urg] + '.' : ''));
      // checked in by scanning the QR sticker on the site (Office › QR codes)
      if (b.qr === true) L.push('Checked in by scanning the site’s QR code.');
      const stage = kind === 'build' && STAGE[b.stage] ? b.stage : '';
      if (stage) L.push('Stage: ' + STAGE[stage] + '.');
      const list = (a, n) => (Array.isArray(a) ? a : []).map(x => clean(x, 60)).filter(Boolean).slice(0, n);
      const bad = list(b.problems, 30), okk = list(b.checked, 30), other = clean(b.other, 300);
      if (bad.length || other) L.push('Problems found: ' + bad.concat(other ? [other] : []).join(', ') + '.');
      if (okk.length) L.push('Checked and OK: ' + okk.join(', ') + '.');
      const partsNeeded = clean(b.parts, 300), cost = num(b.cost, 10000000);
      if (partsNeeded) L.push('Parts needed: ' + said(partsNeeded));
      if (cost) L.push('Estimated cost: KES ' + cost.toLocaleString('en-US') + '.');
      // readings, compared with the last visit
      const r = b.readings && typeof b.readings === 'object' ? b.readings : {};
      const R = { strokes: num(r.strokes, 1000), flow: num(r.flow, 1000), queue: num(r.queue, 2000), users: num(r.users, 100000), clarity: CLARITY[r.clarity] ? r.clarity : '' };
      const parts = [];
      if (R.strokes !== null) parts.push('pump strokes to fill 20 L: ' + R.strokes);
      if (R.flow !== null) parts.push('flow: ' + R.flow + ' L/min');
      if (R.clarity) parts.push('water looks ' + CLARITY[R.clarity]);
      if (R.queue !== null) parts.push('people queuing: ' + R.queue);
      if (R.users !== null) parts.push('people using it (estimate): ' + R.users);
      if (parts.length) L.push('Readings: ' + parts.join('; ') + '.');
      const past = env.FEED ? (await env.FEED.get('pinvisit:' + id, 'json')) || [] : [];
      const prev = past.filter(v => v.readings)[0];
      if (prev) {
        const ch = [];
        [['strokes', 'pump strokes'], ['flow', 'flow'], ['queue', 'queue'], ['users', 'people using it']].forEach(([k, label]) => {
          if (R[k] !== null && prev.readings[k] != null && prev.readings[k] !== R[k]) ch.push(label + ' ' + prev.readings[k] + ' → ' + R[k]);
        });
        if (ch.length) L.push('Since the last visit (' + new Date(prev.at).toISOString().slice(0, 10) + '): ' + ch.join('; ') + '.');
      }
      // numbers for schools, clinics, homes, farms and building sites, compared with the last visit
      const nIn = b.numbers && typeof b.numbers === 'object' ? b.numbers : {}, N = {};
      Object.keys(NUMS).forEach(k => { const v = num(nIn[k], 100000000); if (v !== null) N[k] = v; });
      if (Object.keys(N).length) {
        L.push('Numbers: ' + Object.keys(N).map(k => NUMS[k] + ': ' + (k === 'income' ? 'KES ' + N[k].toLocaleString('en-US') : N[k])).join('; ') + '.');
        const pn = past.filter(v => v.numbers)[0];
        if (pn) {
          const ch = Object.keys(N).filter(k => !['born', 'died', 'sold', 'income'].includes(k) && pn.numbers[k] != null && pn.numbers[k] !== N[k]).map(k => NUMS[k] + ' ' + pn.numbers[k] + ' → ' + N[k]);
          if (ch.length) L.push('Since the last visit (' + new Date(pn.at).toISOString().slice(0, 10) + '): ' + ch.join('; ') + '.');
        }
      }
      const q = b.quality && typeof b.quality === 'object' ? b.quality : {};
      const Q = { ecoli: ECOLI[q.ecoli] ? q.ecoli : '', chlorine: num(q.chlorine, 50), ph: num(q.ph, 14), fluoride: num(q.fluoride, 50), turbidity: num(q.turbidity, 4000) };
      const qs = [];
      if (Q.ecoli) qs.push('E. coli ' + ECOLI[Q.ecoli]);
      if (Q.chlorine !== null) qs.push('free chlorine ' + Q.chlorine + ' mg/L');
      if (Q.ph !== null) qs.push('pH ' + Q.ph);
      if (Q.fluoride !== null) qs.push('fluoride ' + Q.fluoride + ' mg/L' + (Q.fluoride > 1.5 ? ' (above the 1.5 mg/L WHO guideline)' : ''));
      if (Q.turbidity !== null) qs.push('turbidity ' + Q.turbidity + ' NTU');
      if (qs.length) L.push('Water tests: ' + qs.join('; ') + '.' + (Q.ecoli === 'pos' ? ' ⚠️ E. coli found: water is not safe to drink untreated.' : ''));
      // water committee and upkeep
      const c = b.committee && typeof b.committee === 'object' ? b.committee : {};
      const cs = [];
      if (YN[c.active]) cs.push(c.active === 'yes' ? 'active' : c.active === 'no' ? 'not active' : 'not sure if active');
      if (clean(c.chair, 80)) cs.push('chair: ' + clean(c.chair, 80) + (clean(c.phone, 30) ? ' (' + clean(c.phone, 30) + ')' : ''));
      if (YN[c.fee]) cs.push('user fee ' + (c.fee === 'yes' ? 'collected' : c.fee === 'no' ? 'not collected' : 'not sure'));
      const fund = num(c.fund, 100000000);
      if (fund !== null) cs.push('maintenance fund: KES ' + fund.toLocaleString('en-US'));
      if (cs.length) L.push('Water committee: ' + cs.join('; ') + '.');
      const cr = b.carer && typeof b.carer === 'object' ? b.carer : {};
      if (kind && clean(cr.name, 80)) L.push((CARER[kind] || 'In charge') + ': ' + clean(cr.name, 80) + (clean(cr.phone, 30) ? ' (' + clean(cr.phone, 30) + ')' : '') + '.');
      // story moment
      const st = b.story && typeof b.story === 'object' ? b.story : {};
      if (clean(st.quote, 600)) L.push('Story moment: “' + clean(st.quote, 600) + '”' + (clean(st.by, 80) ? ' (' + clean(st.by, 80) + ')' : '') + '. Consent to quote and photograph: ' + (st.consent ? 'given' : 'not given') + '.');
      if (clean(b.note, 2000)) L.push('Notes: ' + said(clean(b.note, 2000)));
      // time on site and where it was sent from
      const tz = b.ck === 'mw' ? 'Africa/Blantyre' : 'Africa/Nairobi';
      const t = iso => { const d = new Date(iso); return isNaN(d) ? '' : d.toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit', timeZone: tz }); };
      const arr = b.arrive ? new Date(b.arrive) : null, lv = b.leave ? new Date(b.leave) : null;
      if (arr && !isNaN(arr) && lv && !isNaN(lv) && lv > arr) L.push('On site ' + t(b.arrive) + '–' + t(b.leave) + ' (' + Math.round((lv - arr) / 60000) + ' min).');
      if (here) {
        const d = saved ? metres(here, saved) : 0;
        L.push('Reported from ' + fmt(here) + (saved ? ', ' + dist(d) + ' from the saved location' : '') + '.' + (saved && d > 500 ? ' ⚠️ This is more than 500 m from the saved location.' : ''));
      }
      const note = L.join('<br>');
      const updateId = await createUpdate(token, id, note, api);
      if (!updateId) return json({ ok: false, reason: 'down' }, 502);
      const at = new Date().toISOString();
      if (b.state === 'working') await setRepair(env, id, null);
      else await setRepair(env, id, { state: b.state, by, at, note: clean(b.note, 300), t: title, urgency: urg, problems: bad.concat(other ? [other] : []).slice(0, 12), parts: partsNeeded, cost: cost || null });
      if (env.FEED) {
        const rec = { at, by, state: b.state, urgency: urg, readings: parts.length ? R : null, quality: qs.length ? Q : null, kind: kind || undefined, stage: stage || undefined, numbers: Object.keys(N).length ? N : null };
        if (b.qr === true) rec.qr = true;
        await env.FEED.put('pinvisit:' + id, JSON.stringify([rec].concat(past).slice(0, 5)));
        // people served: this project's latest count, and this month's total for the trend
        later(notePeople(env, id, [rec].concat(past)).then(m => (m ? getFeed(env).then(f => snapPeople(env, f.projects || [], m)) : null)).catch(e => console.error('people:', e && e.message)));
        const lv2 = await getJson(env, 'pinlastvisit', {}); lv2[id] = { at, by, state: b.state }; await env.FEED.put('pinlastvisit', JSON.stringify(lv2));
        // when the water was last tested (E. coli, chlorine, pH, fluoride, turbidity), one small map for the whole team: "Water test due" after 6 months
        const tested = qs.length ? at : (past.find(v => v.quality) || {}).at;
        if (tested) { const ts = await getJson(env, 'pintests', {}); if (!ts[id] || ts[id] < tested) { ts[id] = tested; await env.FEED.put('pintests', JSON.stringify(ts)); } }
      }
      await bump(env, 'rl:pinsave:' + ip);
      // wq: water tests recorded, st: a story moment (for the Water tester and Story collector stamps)
      await log(env, Object.assign({ by, a: 'visit', s: b.state, u: urg, id, t: title }, qs.length ? { wq: 1 } : {}, clean(st.quote, 600) ? { st: 1 } : {}));
      if (clean(st.quote, 600) && st.consent) await addReview(env, { kind: 'story', id, t: title, by, data: { quote: clean(st.quote, 600), who: clean(st.by, 80), update: updateId } });
      // a visit someone was asked to make is done
      if (env.FEED) {
        const as = await getJson(env, 'pinassign', []); let ch = false;
        as.forEach(a => { if (!a.done && a.id === id && sameName(a.to, by)) { a.done = at; ch = true; } });
        if (ch) await putJson(env, 'pinassign', as);
      }
      // tell the admins straight away about serious problems
      const why = [];
      if (b.state === 'notworking') why.push('reported NOT WORKING');
      if (urg === 'urgent') why.push('marked as an URGENT repair');
      if (Q.ecoli === 'pos') why.push('E. coli FOUND in the water');
      if (why.length) {
        const link = new URL('/pin#p/' + id + '/hist', request.url).toString();
        later(sendMail(env, '⚠️ ' + title + ': ' + why.join(', '), alertHtml(title + ': ' + why.join(', '), L.map(x => x.replace(/<br>/g, ' ')), link), L.join('\n') + '\n' + link).catch(e => console.error('alert:', e && e.message)));
      }
      return json({ ok: true, id, updateId }, 200);
    }

    if (action === 'fixed') {
      const job = (await getJson(env, 'pinrepairs', {}))[id], actual = money(b.actual), at = new Date().toISOString();
      const note = '🔧 Marked as fixed with the RAFIKI pin app by ' + by + '.' + (actual ? ' Spent: ' + kes(actual) + (b.receipt ? ' (receipt attached).' : '.') : '') + (clean(b.note, 1000) ? ' Notes: ' + said(clean(b.note, 1000)) : '');
      const updateId = await createUpdate(token, id, note, api);
      if (!updateId) return json({ ok: false, reason: 'down' }, 502);
      if (actual) await addCost(env, id, costRow({ at, rid: job ? job.at : at, what: clean(b.what, 120) || clean(b.note, 120) || 'Fixed', est: job ? estOf(job) : null, actual, by, step: 'fixed', receipt: !!b.receipt }), { t: title });
      await setRepair(env, id, null);
      await bump(env, 'rl:pinsave:' + ip);
      await log(env, { by, a: 'fixed', id, t: title });
      return json({ ok: true, id, updateId }, 200);
    }

    // parts ordered, from the repair tracker: the person on the job or an admin
    if (action === 'parts') {
      const reps = await getJson(env, 'pinrepairs', {}), job = reps[id];
      if (!job) return json({ ok: false, reason: 'gone' }, 404);
      if (!chk.admin && !sameName(job.assignee, by)) return json({ ok: false, reason: 'not-yours' }, 403);
      const at = new Date().toISOString(), bl = stepBlocked(job, 'parts', await approveLimit(env));
      if (bl) {
        if (bl === 'pending') return json({ ok: true, id, held: 'parts', asked: false, job }, 200);
        const rid = askApproval(job, by, at);
        await addReview(env, { rid, kind: 'cost', id, t: title, by, data: { est: estOf(job), step: 'parts', note: clean(b.note, 300), parts: job.parts || '' } });
        await setRepair(env, id, job);
        await createUpdate(token, id, '🔒 ' + by + ' asked for a second approval of ' + kes(estOf(job)) + ' before ordering parts (RAFIKI pin app).', api);
        await log(env, { by, a: 'ask', k: 'cost', id, t: title });
        await bump(env, 'rl:pinsave:' + ip);
        return json({ ok: true, id, held: 'parts', asked: true, job }, 200);
      }
      const actual = money(b.actual);
      const updateId = await createUpdate(token, id, '📦 Parts ordered, reported by ' + by + ' (RAFIKI pin app).' + (actual ? ' Spent: ' + kes(actual) + (b.receipt ? ' (receipt attached).' : '.') : '') + (clean(b.what, 300) ? ' ' + said(clean(b.what, 300)) : ''), api);
      if (!updateId) return json({ ok: false, reason: 'down' }, 502);
      if (!stepAtOrAfter(job.step, 'parts')) { job.step = 'parts'; job.stepAt = Object.assign({}, job.stepAt, { parts: at }); if (job.assignee) job.stepAt.assigned = job.stepAt.assigned || at; }
      await setRepair(env, id, job);
      if (actual) await addCost(env, id, costRow({ at, rid: job.at, what: clean(b.what, 120) || 'Parts ordered', est: estOf(job), actual, by, step: 'parts', receipt: !!b.receipt }), { t: title });
      await bump(env, 'rl:pinsave:' + ip);
      await log(env, { by, a: 'job', id, t: title, s: 'parts' });
      return json({ ok: true, id, updateId, job }, 200);
    }

    return json({ ok: false, reason: 'bad-request' }, 400);
  } catch (e) {
    console.error('pin ' + action + ':', e && e.message);
    return json({ ok: false, reason: 'down' }, 502);
  }
}

export function onRequest() {
  return json({ ok: false, reason: 'method' }, 405);
}
