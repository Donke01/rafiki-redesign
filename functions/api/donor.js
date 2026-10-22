// /api/donor: the data behind a donor's private page (/donor/<token>, donor.html). See lib/donor.js.
//   GET  /api/donor                 header X-Donor-Token: <token>  → the donor's projects, updates and impact (whitelisted fields only)
//   GET  /api/donor?photo=<asset>&w=960&t=<token>                   → one visit photo or thank-you photo/video of one of their projects
//   GET  /api/donor?unsub=<code>    → "stop the monthly e-mail?" page;  POST the same → done (also one-click from mail apps)
// The RAFIKI Pin app in donor mode (feat-donorapp) uses the same token:
//   GET  /api/donor?bg=1            the app's background check for news: same answer, doesn't count as "opened"
//   POST /api/donor { code }        a short donor code (K7QM4-XR9TB) → { token, name }; wrong codes limited per connection and overall
//   POST /api/donor { action: 'mail', on }   header X-Donor-Token → e-mail updates on/off (off = the e-mail's unsubscribe)
// Unknown, revoked and rotated links all get the same answer. Wrong links are limited per connection.
import { getFeed } from '../../lib/feed-cache.js';
import { findByToken, loadView, donorFile, assetInfo, unsubscribe, saveDonors, redeemCode, mailState, setMail, CONTACT } from '../../lib/donor.js';

const MAX_BAD = 20;          // wrong links per connection per hour
const MAX_CODE = 10;         // wrong app codes per connection per hour
const MAX_CODE_ALL = 300;    // wrong app codes from everywhere per hour (stops a spread-out guessing run)
const BURST = 120;           // requests per connection per minute (this worker)
const hits = new Map();

const HEAD = { 'Cache-Control': 'private, no-store', 'X-Content-Type-Options': 'nosniff', 'X-Robots-Tag': 'noindex, nofollow', 'Referrer-Policy': 'strict-origin' };
function json(body, status) { return new Response(JSON.stringify(body), { status: status || 200, headers: Object.assign({ 'Content-Type': 'application/json; charset=utf-8' }, HEAD) }); }
function page(title, text, status) {
  const esc = s => String(s).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
  return new Response('<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><meta name="robots" content="noindex"><title>' + esc(title) + ' — RAFIKI Outreach</title>' +
    '<style>body{margin:0;min-height:100vh;display:grid;place-items:center;background:#FAF8F3;color:#141618;font:16px/1.6 Montserrat,system-ui,sans-serif;padding:24px;box-sizing:border-box;text-align:center}main{max-width:460px}img{height:44px}h1{font-size:24px;margin:18px 0 8px;color:#04263F}button{font:inherit;font-weight:700;background:#0A78C2;color:#fff;border:0;border-radius:999px;padding:12px 24px;min-height:48px;cursor:pointer}a{color:#076DB1;font-weight:700}</style></head>' +
    '<body><main><img src="/img/logo-dark-bold.png" alt="RAFIKI Outreach">' + text + '</main></body></html>', { status: status || 200, headers: Object.assign({ 'Content-Type': 'text/html; charset=utf-8' }, HEAD) });
}
const ipOf = r => r.headers.get('CF-Connecting-IP') || 'unknown';
function burst(ip) {
  const now = Date.now(), h = hits.get(ip);
  if (!h || now - h.t > 60000) { hits.set(ip, { t: now, n: 1 }); if (hits.size > 5000) hits.clear(); return false; }
  return ++h.n > BURST;
}
async function bad(env, ip) { if (!env.FEED) return 0; return Number(await env.FEED.get('rl:donorbad:' + ip)) || 0; }
async function bump(env, ip) { if (env.FEED) await env.FEED.put('rl:donorbad:' + ip, String((await bad(env, ip)) + 1), { expirationTtl: 3600 }); }
async function cnt(env, k) { return Number(await env.FEED.get(k)) || 0; }
async function inc(env, k) { await env.FEED.put(k, String((await cnt(env, k)) + 1), { expirationTtl: 3600 }); }

async function lookup(request, env, token) {
  const ip = ipOf(request);
  if (burst(ip)) return { res: json({ ok: false, reason: 'rate' }, 429) };
  if (!env.FEED) return { res: json({ ok: false, reason: 'not-set-up' }, 503) };
  if (await bad(env, ip) >= MAX_BAD) return { res: json({ ok: false, reason: 'rate' }, 429) };
  const hit = await findByToken(env, token);
  if (!hit) { await bump(env, ip); return { res: json({ ok: false, reason: 'link', contact: CONTACT }, 404) }; }
  return hit;
}

export async function onRequestGet({ request, env, waitUntil }) {
  const url = new URL(request.url), q = url.searchParams;
  if (q.get('unsub')) {
    const r = await unsubscribe(env, q.get('unsub'), true);
    if (!r.ok) return page('Link not recognised', '<h1>That link isn’t recognised</h1><p>To stop the e-mails, write to <a href="mailto:' + CONTACT + '">' + CONTACT + '</a>.</p>', 404);
    return page('Monthly e-mail', '<h1>Stop the monthly e-mail?</h1><p>You won’t get the monthly project update any more. Your private page keeps working.</p><form method="post"><button type="submit">Yes, stop the e-mails</button></form>');
  }
  const photo = (q.get('photo') || '').replace(/\D/g, '');
  const token = photo ? q.get('t') || '' : request.headers.get('X-Donor-Token') || q.get('t') || '';
  const hit = await lookup(request, env, token);
  if (hit.res) return hit.res;
  const { donor, viewer, list } = hit;
  let feed;
  try { feed = await getFeed(env, waitUntil); } catch (e) { return json({ ok: false, reason: 'down' }, 502); }
  if (photo) return file(request, env, donor, photo, feed, q.get('w'), waitUntil);
  // when they last opened it (at most one write every 10 minutes); the app's background check doesn't count
  const now = new Date();
  if (q.get('bg') !== '1' && (!viewer.lastOpen || now - Date.parse(viewer.lastOpen) > 600000)) {
    viewer.lastOpen = now.toISOString(); viewer.opens = (viewer.opens || 0) + 1;
    const save = saveDonors(env, list).catch(() => {});
    if (waitUntil) waitUntil(save); else await save;
  }
  try {
    return json(Object.assign(await loadView(env, donor, viewer, { feed, fresh: true, waitUntil }), { me: mailState(donor, viewer) }));
  } catch (e) {
    console.error('donor view:', e && e.message);
    return json({ ok: false, reason: 'down' }, 502);
  }
}

export async function onRequestPost({ request, env }) {
  const code = new URL(request.url).searchParams.get('unsub');
  if (!code) return appPost(request, env);
  const r = await unsubscribe(env, code, false);
  if (!r.ok) return page('Link not recognised', '<h1>That link isn’t recognised</h1><p>To stop the e-mails, write to <a href="mailto:' + CONTACT + '">' + CONTACT + '</a>.</p>', 404);
  return page('Monthly e-mail', '<h1>Done</h1><p>You won’t get the monthly e-mail any more. Changed your mind? Write to <a href="mailto:' + CONTACT + '">' + CONTACT + '</a>.</p>');
}

// The app: a donor code swapped for the link token, or e-mail updates on/off.
async function appPost(request, env) {
  let b = null;
  try { b = await request.json(); } catch (e) {}
  if (!b || typeof b !== 'object') return json({ ok: false, reason: 'bad-request' }, 400);
  const ip = ipOf(request);
  if (burst(ip)) return json({ ok: false, reason: 'rate' }, 429);
  if (!env.FEED) return json({ ok: false, reason: 'not-set-up' }, 503);
  if ('code' in b) {
    const k = 'rl:donorcode:' + ip, all = 'rl:donorcode:all';
    if (await cnt(env, k) >= MAX_CODE || await cnt(env, all) >= MAX_CODE_ALL) return json({ ok: false, reason: 'rate' }, 429);
    const r = await redeemCode(env, b.code);
    if (!r) { await inc(env, k); await inc(env, all); return json({ ok: false, reason: 'code', contact: CONTACT }, 404); }
    return json({ ok: true, token: r.token, name: r.name });
  }
  if (b.action === 'mail') {
    const hit = await lookup(request, env, request.headers.get('X-Donor-Token') || '');
    if (hit.res) return hit.res;
    return json({ ok: true, me: await setMail(env, hit, !!b.on) });
  }
  return json({ ok: false, reason: 'bad-request' }, 400);
}

// A visit photo, or a thank-you photo or video, of one of the donor's projects (only where photos are allowed).
async function file(request, env, donor, id, feed, w, waitUntil) {
  const hit = await donorFile(env, donor, id, feed);
  if (!hit) return new Response('Not found', { status: 404, headers: HEAD });
  const video = hit.media === 'video';
  const width = [480, 960, 1600].find(x => x >= (Number(w) || 960)) || 1600;
  const key = new Request('https://rafiki-donor-file.cache/' + id + '?w=' + (video ? 'v' : width));
  if (!video) { const c = await caches.default.match(key); if (c) { const out = new Response(c.body, c); out.headers.set('Cache-Control', 'private, max-age=86400'); return out; } }
  let info;
  try { info = await assetInfo(env.MONDAY_API_TOKEN, id, env.MONDAY_API_URL); } catch (e) { return new Response('Unavailable', { status: 502, headers: HEAD }); }
  if (!info) return new Response('Not found', { status: 404, headers: HEAD });
  const range = request.headers.get('Range');
  let r;
  try {
    r = video ? await fetch(info.public_url, { headers: range ? { Range: range } : {} })
      : await fetch(info.public_url, { cf: { image: { width, fit: 'scale-down', quality: 78, metadata: 'none' } } });
  } catch (e) { return new Response('Unavailable', { status: 502, headers: HEAD }); }
  if (!r.ok && r.status !== 206) return new Response('Unavailable', { status: 502, headers: HEAD });
  const type = r.headers.get('Content-Type') || '';
  if (video ? !/^video\//i.test(type) && !/octet-stream/i.test(type) : !/^image\//i.test(type)) return new Response('Not found', { status: 404, headers: HEAD });
  const h = { 'Content-Type': video && !/^video\//i.test(type) ? 'video/mp4' : type, 'Cache-Control': 'private, max-age=86400', 'X-Content-Type-Options': 'nosniff', 'Referrer-Policy': 'strict-origin' };
  ['Content-Length', 'Content-Range', 'Accept-Ranges'].forEach(k => { if (r.headers.get(k)) h[k] = r.headers.get(k); });
  const res = new Response(r.body, { status: r.status, headers: h });
  // kept in this data centre's cache under a key only reached after the link check above
  if (!video && r.status === 200 && waitUntil) waitUntil(caches.default.put(key, new Response(res.clone().body, { headers: Object.assign({}, h, { 'Cache-Control': 'public, max-age=604800' }) })));
  return res;
}

export function onRequest() { return json({ ok: false, reason: 'method' }, 405); }
