// RAFIKI Pin team chat, the Pages side (see functions/api/chat and workers/chat/README.md).
// The chat itself runs in the separate worker "rafiki-chat" (Durable Objects + R2). This project reaches it
// through a Service binding named CHAT. Until that binding exists every /api/chat/* answer is
// { ok: false, reason: 'chat-off' } and nothing else in the app changes.
// Optional: a secret CHAT_SECRET set on both this project and the worker; it is then sent with every call.
import { team, guards, guardFor, isAdminName, adminNames, sameName, getJson, putJson } from './pin-store.js';

export const NEED = ['CHAT service binding → rafiki-chat'];
export const chatOn = env => !!(env && env.CHAT && typeof env.CHAT.fetch === 'function');

const b64u = s => btoa(unescape(encodeURIComponent(s))).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');

// Everyone on the team, for the Team chats and for cutting off blocked or removed people:
// [{ n: name, h: home country, a: admin, g: session number, b: blocked }]
export async function roster(env) {
  const t = await team(env), gd = await guards(env), prof = await getJson(env, 'pinprofiles', {});
  const out = [], seen = new Set();
  const add = (name, home) => {
    name = String(name || '').trim();
    const k = name.toLowerCase();
    if (!name || seen.has(k)) return;
    seen.add(k);
    const pk = Object.keys(prof).find(n => sameName(n, name)), pr = (pk && prof[pk]) || {};
    const g = guardFor(gd, name);
    out.push({ n: name, h: pr.home || home || '', a: isAdminName(env, t, name) ? 1 : 0, g: (g && Number(g.gen)) || 0, b: g && g.blocked ? 1 : 0 });
  };
  t.forEach(p => add(p.name, p.home));
  String(env.PIN_USERS || '').split(/[,;\n]/).forEach(s => {
    const a = s.split(':').map(x => x.trim());
    if (a.length < 2) return;
    let home = ''; if (a.length > 2 && /^(ke|tz|ug|mw)$/i.test(a[a.length - 1])) home = a.pop().toLowerCase();
    add(a.slice(0, -1).join(':'), home);
  });
  // admins named in PIN_ADMINS who sign in with monday.com only
  adminNames(env).forEach(n => { if (!seen.has(n)) { const p = t.find(x => sameName(x.name, n)); add(p ? p.name : n.replace(/\b\w/g, c => c.toUpperCase()), ''); } });
  // blocked people the lists above don't name (e.g. monday.com sign-ins), so their phones are cut off too
  Object.keys(gd || {}).forEach(k => { const g = gd[k]; if (g && g.blocked && !seen.has(k)) { seen.add(k); out.push({ n: k, h: '', a: 0, g: Number(g.gen) || 0, b: 1 }); } });
  return out.sort((x, y) => x.n.localeCompare(y.n));
}

// the headers that tell the worker who is asking
export function chatHeaders(env, who, list) {
  const r = list.find(p => sameName(p.n, who.name)) || {};
  const h = { 'X-Chat-User': encodeURIComponent(who.name), 'X-Chat-Admin': who.admin ? '1' : '0', 'X-Chat-Home': who.home || r.h || '', 'X-Chat-Gen': String(r.g || 0), 'X-Chat-Roster': b64u(JSON.stringify(list)) };
  if (env.CHAT_SECRET) h['X-Chat-Secret'] = String(env.CHAT_SECRET);
  return h;
}

async function call(env, path, body) {
  const h = { 'Content-Type': 'application/json' };
  if (env.CHAT_SECRET) h['X-Chat-Secret'] = String(env.CHAT_SECRET);
  return env.CHAT.fetch(new Request('https://rafiki-chat/' + path, { method: 'POST', headers: h, body: JSON.stringify(body || {}) }));
}

// After a change in Office › Team (block, remove, sign out of all phones, admin, home country): the worker
// syncs the Team chats and closes the phones of anyone blocked, removed or signed out, straight away.
export async function chatSync(env) {
  if (!chatOn(env)) return;
  try {
    const list = await roster(env), h = { 'X-Chat-Roster': b64u(JSON.stringify(list)) };
    if (env.CHAT_SECRET) h['X-Chat-Secret'] = String(env.CHAT_SECRET);
    await env.CHAT.fetch(new Request('https://rafiki-chat/sync', { method: 'POST', headers: h, body: '{}' }));
  } catch (e) { console.error('chat sync:', e && e.message); }
}

// A system line in a project's chat thread: repair reported, assigned, step changed, fixed, a community (SMS)
// report, a visit that found it "Not working". `add`: teammates to bring into the thread (the person assigned).
// Never throws, never slows the caller down (use it inside waitUntil).
export async function chatSys(env, project, sys, text, opts) {
  if (!chatOn(env) || !project) return;
  const o = opts || {};
  try {
    await call(env, 'sys', { project: String(project), title: o.title || '', sys, text, add: (o.add || []).filter(Boolean) });
  } catch (e) { console.error('chat sys:', e && e.message); }
}

// ---- link previews (GET /api/chat/preview?url=) -------------------------------------------------------
// Project links (…/pin#p/ID) become a project card from the feed; other pages are fetched (4 s, 256 KB) and
// their title, description and image read from the <head>. Kept in KV for a week.
const PROJECT = /^https?:\/\/(?:pin\.rafikioutreach\.org|[a-z0-9.-]+\.pages\.dev|localhost(?::\d+)?)\/pin\/?#p\/(\d{1,15})\b/i;
const PRIVATE = /^(localhost|127\.|10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.|169\.254\.|0\.|\[|::1|.*\.local$|.*\.internal$)/i;
const ent = s => String(s || '').replace(/&amp;/g, '&').replace(/&quot;/g, '"').replace(/&#39;|&#x27;/g, "'").replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/\s+/g, ' ').trim();
export function readHead(html, base) {
  const meta = name => {
    const re = new RegExp('<meta[^>]+(?:property|name)=["\']' + name + '["\'][^>]*>', 'i'), m = re.exec(html);
    const c = m && /content=["']([^"']*)["']/i.exec(m[0]);
    return c ? ent(c[1]) : '';
  };
  const title = meta('og:title') || meta('twitter:title') || ent((/<title[^>]*>([^<]*)<\/title>/i.exec(html) || [])[1]);
  let image = meta('og:image') || meta('twitter:image');
  try { image = image ? new URL(image, base).toString() : ''; } catch (e) { image = ''; }
  if (image && !/^https:\/\//.test(image)) image = '';
  let site = meta('og:site_name'); if (!site) { try { site = new URL(base).hostname.replace(/^www\./, ''); } catch (e) {} }
  return { title: title.slice(0, 200), description: (meta('og:description') || meta('description') || meta('twitter:description')).slice(0, 300), image, site: site.slice(0, 80) };
}
export async function preview(env, raw, getFeed, fetcher) {
  let u; try { u = new URL(String(raw || '').trim()); } catch (e) { return { ok: false, reason: 'bad-request' }; }
  if (!/^https?:$/.test(u.protocol) || u.username || u.password || (u.port && !['80', '443'].includes(u.port))) return { ok: false, reason: 'bad-request' };
  const pm = PROJECT.exec(u.toString());
  if (pm) {
    let p = null; try { const f = await getFeed(env); p = (f.projects || []).find(x => String(x.id) === pm[1]); } catch (e) {}
    if (!p) return { ok: false, reason: 'not-found' };
    return { ok: true, title: p.title, description: [p.type, p.area, p.status].filter(Boolean).join(' · '), image: p.photos && p.photos[0] ? '/api/photo/' + p.photos[0] + '?w=480' : '', site: 'RAFIKI Pin',
      project: { id: String(p.id), title: p.title, country: p.ck || p.country || '', status: p.status || '', type: p.type || '', area: p.area || '' } };
  }
  if (PRIVATE.test(u.hostname) || /^\d+\.\d+\.\d+\.\d+$/.test(u.hostname)) return { ok: false, reason: 'bad-request' };
  const ck = 'chatprev:' + [...new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(u.toString())))].slice(0, 12).map(b => b.toString(16).padStart(2, '0')).join('');
  const hit = await getJson(env, ck, null);
  if (hit) return Object.assign({ ok: true }, hit);
  let out = { title: '', description: '', image: '', site: u.hostname.replace(/^www\./, '') };
  try {
    const ac = new AbortController(), timer = setTimeout(() => ac.abort(), 4000);
    const r = await (fetcher || fetch)(u.toString(), { signal: ac.signal, redirect: 'follow', headers: { 'User-Agent': 'RAFIKI-Pin-LinkPreview/1.0', Accept: 'text/html' } });
    if (r.ok && /text\/html|application\/xhtml/i.test(r.headers.get('Content-Type') || '') && r.body) {
      const rd = r.body.getReader(), parts = []; let n = 0;
      while (n < 256 * 1024) { const { done, value } = await rd.read(); if (done) break; parts.push(value); n += value.length; if (/<\/head>/i.test(new TextDecoder().decode(value))) break; }
      try { rd.cancel(); } catch (e) {}
      const buf = new Uint8Array(n); let o = 0; parts.forEach(p => { buf.set(p.subarray(0, Math.min(p.length, n - o)), o); o += p.length; });
      out = readHead(new TextDecoder().decode(buf), u.toString());
    }
    clearTimeout(timer);
  } catch (e) { /* unreachable or too slow: just the site name */ }
  await putJson(env, ck, out, { expirationTtl: 7 * 86400 });
  return Object.assign({ ok: true }, out);
}
