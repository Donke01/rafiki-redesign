// /api/chat/*: RAFIKI Pin team chat. Signs people in exactly like /api/pin (PIN or monday.com session, the
// blocked and "signed out of every phone" checks with X-Pin-Gen), refuses donors and the shared team PIN, then
// passes the request to the chat worker "rafiki-chat" through the Service binding CHAT (lib/chat.js,
// workers/chat). The full list of endpoints is in workers/chat/README.md.
// Not set up yet (no CHAT binding): every endpoint answers { ok: false, reason: 'chat-off', need: [...] }.
import { checkPin } from '../pin.js';
import { guards, guardFor } from '../../../lib/pin-store.js';
import { getFeed } from '../../../lib/feed-cache.js';
import { chatOn, NEED, roster, chatHeaders, preview, tripFor, freshTrips, projectPhotos } from '../../../lib/chat.js';
import { transcribe, transcribeSetup } from '../../../lib/transcribe.js';
import { translateOn } from '../../../lib/translate.js';

const HEAD = { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff', 'X-Robots-Tag': 'noindex' };
const json = (body, status) => new Response(JSON.stringify(body), { status: status || 200, headers: HEAD });
const MAX_UPLOAD = 16 * 1024 * 1024 + 64 * 1024;
const TX_PER_HOUR = 30, TX_KEEP = 365 * 86400;

// chat2: "Show text" under a voice note. The message is read through the worker as this person (so only members
// get it), its audio typed out with lib/transcribe.js, and the words kept in KV against the message (chattx:<c>:<id>).
async function voiceText(env, chk, h, b) {
  const c = String((b && b.c) || '').slice(0, 80), id = Number(b && b.id);
  if (!/^[a-z]:[A-Za-z0-9_-]{1,64}$/.test(c) || !(id > 0)) return json({ ok: false, reason: 'bad-request' }, 400);
  if (!transcribeSetup(env).on) return json({ ok: false, reason: 'off' });
  const key = 'chattx:' + c + ':' + id, had = env.FEED ? await env.FEED.get(key, 'json') : null;
  const r = await env.CHAT.fetch(new Request('https://rafiki-chat/msgs?c=' + encodeURIComponent(c) + '&after=' + (id - 1) + '&limit=1', { headers: h }));
  const j = await r.json().catch(() => ({}));
  if (!r.ok || !j.ok) return json({ ok: false, reason: j.reason || 'not-found' }, r.status === 200 ? 404 : r.status);
  const m = (j.msgs || []).find(x => Number(x.id) === id), md = m && m.kind === 'voice' && !m.deleted && Array.isArray(m.media) && m.media[0];
  if (!md || !md.id) return json({ ok: false, reason: 'not-found' }, 404);
  if (had && had.text != null) return json({ ok: true, text: had.text, lang: had.lang || '', cached: true });
  if (env.FEED) {
    const rk = 'rl:tx:' + String(chk.who).toLowerCase() + ':' + Math.floor(Date.now() / 3600e3), n = Number(await env.FEED.get(rk)) || 0;
    if (n >= TX_PER_HOUR) return json({ ok: false, reason: 'rate' }, 429);
    await env.FEED.put(rk, String(n + 1), { expirationTtl: 3700 });
  }
  const a = await env.CHAT.fetch(new Request('https://rafiki-chat/media/' + md.id, { headers: h }));
  if (!a.ok) return json({ ok: false, reason: 'not-found' }, 404);
  const bytes = new Uint8Array(await a.arrayBuffer());
  let out;
  try { out = await transcribe(env, bytes, a.headers.get('Content-Type') || 'audio/webm'); }
  catch (e) { console.error('chat voice text:', e && e.message); return json({ ok: false, reason: e && e.code === 'rate' ? 'rate' : e && e.code === 'format' ? 'format' : 'down' }, 502); }
  const v = { text: out.text || '', lang: out.lang || '' };
  if (env.FEED) await env.FEED.put(key, JSON.stringify(v), { expirationTtl: TX_KEEP });
  return json(Object.assign({ ok: true, cached: false }, v));
}

function peek(t) {
  try {
    const p = String(t || '').split('.')[0];
    return JSON.parse(decodeURIComponent(escape(atob(p.replace(/-/g, '+').replace(/_/g, '/') + '==='.slice((p.length + 3) % 4)))));
  } catch (e) { return null; }
}
function target(path, url) { return 'https://rafiki-chat/' + path + url.search; }
function secretOnly(env) { const h = {}; if (env.CHAT_SECRET) h['X-Chat-Secret'] = String(env.CHAT_SECRET); return h; }

export async function onRequest({ request, env, params }) {
  const url = new URL(request.url);
  const path = (Array.isArray(params && params.path) ? params.path.join('/') : String((params && params.path) || url.pathname.replace(/^\/api\/chat\/?/, ''))).replace(/^\/+|\/+$/g, '');
  // donors never see chat
  if (request.headers.get('X-Donor-Token')) return json({ ok: false, reason: 'donor' }, 403);
  if (!chatOn(env)) return json({ ok: false, reason: 'chat-off', need: NEED });
  if (request.method !== 'GET' && request.method !== 'HEAD') {
    const origin = request.headers.get('Origin');
    if (origin) { let host = ''; try { host = new URL(origin).host; } catch (e) {} if (host !== url.host) return json({ ok: false, reason: 'origin' }, 403); }
  }

  // the WebSocket: browsers can't send headers, so a 60-second ticket from POST /api/chat/ticket
  if (path === 'ws') {
    const v = peek(url.searchParams.get('t'));
    if (!v || !v.n) return json({ ok: false, reason: 'ticket' }, 401);
    const g = guardFor(await guards(env), v.n);
    if (g && g.blocked) return json({ ok: false, reason: 'blocked' }, 401);
    if (g && Number(g.gen) > (Number(v.g) || 0)) return json({ ok: false, reason: 'signed-out' }, 401);
    const h = new Headers(request.headers); Object.entries(secretOnly(env)).forEach(([k, x]) => h.set(k, x));
    return env.CHAT.fetch(new Request(target('ws', url), { method: 'GET', headers: h }));
  }
  // media links signed by the worker work in <img>/<video>/<audio> without headers
  const media = /^media\/([A-Za-z0-9_-]{16,40})$/.exec(path);
  if (media && url.searchParams.get('s') && (request.method === 'GET' || request.method === 'HEAD')) {
    const h = secretOnly(env), range = request.headers.get('Range');
    if (range) h.Range = range;
    return env.CHAT.fetch(new Request(target(path, url), { method: request.method, headers: h }));
  }

  const chk = await checkPin(request, env);
  if (chk.res) return chk.res;
  if (!chk.who) return json({ ok: false, reason: 'no-name' }, 403);
  const who = { name: chk.who, admin: !!chk.admin, home: chk.home || '' };

  try {
    if (path === 'preview') {
      if (request.method !== 'GET') return json({ ok: false, reason: 'method' }, 405);
      return json(await preview(env, url.searchParams.get('url'), getFeed));
    }
    // a trip card, fresh, for this viewer (title only for people not on the trip)
    if (path === 'trip') {
      if (request.method !== 'GET') return json({ ok: false, reason: 'method' }, 405);
      const t = await tripFor(env, chk, url.searchParams.get('id'));
      return t ? json({ ok: true, trip: t.card }) : json({ ok: false, reason: 'not-found' }, 404);
    }
    const list = await roster(env), h = chatHeaders(env, who, list);
    if (path === 'transcribe') {
      if (request.method !== 'POST') return json({ ok: false, reason: 'method' }, 405);
      let b; try { b = await request.json(); } catch (e) { return json({ ok: false, reason: 'bad-request' }, 400); }
      return voiceText(env, chk, h, b);
    }
    if (media) {
      const range = request.headers.get('Range'); if (range) h.Range = range;
      return env.CHAT.fetch(new Request(target(path, url), { method: request.method === 'HEAD' ? 'HEAD' : 'GET', headers: h }));
    }
    if (path === 'media') {
      if (request.method !== 'POST') return json({ ok: false, reason: 'method' }, 405);
      if (Number(request.headers.get('Content-Length')) > MAX_UPLOAD) return json({ ok: false, reason: 'too-large' }, 413);
      ['Content-Type', 'Content-Length'].forEach(k => { const v = request.headers.get(k); if (v) h[k] = v; });
      const r = await env.CHAT.fetch(new Request(target(path, url), { method: 'POST', headers: h, body: request.body }));
      return new Response(r.body, { status: r.status, headers: HEAD });
    }
    let body;
    if (request.method === 'POST') {
      let b; try { b = await request.json(); } catch (e) { return json({ ok: false, reason: 'bad-request' }, 400); }
      if (!b || typeof b !== 'object' || Array.isArray(b)) return json({ ok: false, reason: 'bad-request' }, 400);
      // a project thread is named after the project (from the feed); unknown projects are refused
      if (path === 'open' && b.kind === 'project') {
        const id = String(b.project || '').replace(/\D/g, '');
        let p = null; try { const f = await getFeed(env); p = (f.projects || []).find(x => String(x.id) === id); } catch (e) {}
        if (!p) return json({ ok: false, reason: 'not-found' }, 404);
        b = { kind: 'project', project: id, ptitle: String(p.title || '') };
      } else delete b.ptitle;
      // a trip card: loaded here with the itin code; the sender must be able to see the trip
      if (path === 'send' && b.kind === 'trip') {
        const t = await tripFor(env, chk, b.trip && b.trip.id);
        if (!t) return json({ ok: false, reason: 'not-found' }, 404);
        if (!t.card.access) return json({ ok: false, reason: 'forbidden' }, 403);
        b.trip = t.card;
      } else delete b.trip;
      body = JSON.stringify(b);
      h['Content-Type'] = 'application/json';
    } else if (request.method !== 'GET') return json({ ok: false, reason: 'method' }, 405);
    const r = await env.CHAT.fetch(new Request(target(path, url), { method: request.method, headers: h, body }));
    let text = await r.text();
    // the join link with this site's address
    // trip cards re-resolved for this viewer; project chats get their project's photo
    if (r.ok && (path === 'msgs' || path === 'starred' || path === 'list')) {
      try {
        const j = JSON.parse(text);
        if (path === 'list') {
          if ((j.chats || []).some(c => c.kind === 'project')) { let f = null; try { f = await getFeed(env); } catch (e) {} projectPhotos(j.chats, f); }
          // chat2: what the app can offer under messages (Translate · Tafsiri, "Show text" under voice notes)
          j.tools = { translate: translateOn(env), transcribe: transcribeSetup(env).on };
        }
        else await freshTrips(env, chk, j.msgs);
        text = JSON.stringify(j);
      } catch (e) {}
    }
    if (path === 'link' && r.ok) { try { const j = JSON.parse(text); if (j.url) { j.url = url.origin + j.url; text = JSON.stringify(j); } } catch (e) {} }
    return new Response(text, { status: r.status, headers: HEAD });
  } catch (e) {
    console.error('chat ' + path + ':', e && e.message);
    return json({ ok: false, reason: 'down' }, 502);
  }
}
