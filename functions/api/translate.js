// /api/translate: Translate · Tafsiri for chat messages, English ↔ Kiswahili (chat2; lib/translate.js).
//   GET  /api/translate                    → { ok, on }  (on: a translation key is set; else the app opens Google Translate)
//   POST /api/translate { text, to? }      → { ok, text, to, cached }   to: 'en' | 'sw' (default: the other language)
//        without a key                     → { ok: false, reason: 'off', to, google }   (the Google Translate address)
// Signed in like /api/pin, with your own name (the shared team PIN and donors are refused). 60 a person an hour;
// answers are kept in KV (FEED) for 30 days, by a hash of the language and the text.
import { checkPin } from './pin.js';
import { translateOn, translate, target, googleUrl, MAX_TEXT } from '../../lib/translate.js';

const PER_HOUR = 60, KEEP = 30 * 86400;
function json(body, status) {
  return new Response(JSON.stringify(body), { status: status || 200, headers: { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff', 'X-Robots-Tag': 'noindex' } });
}
async function sha(s) { return [...new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(s)))].map(b => b.toString(16).padStart(2, '0')).join(''); }
async function who(request, env) {
  if (request.headers.get('X-Donor-Token')) return { res: json({ ok: false, reason: 'donor' }, 403) };
  const chk = await checkPin(request, env);
  if (chk.res) return chk;
  if (!chk.who) return { res: json({ ok: false, reason: 'no-name' }, 403) };
  return chk;
}

export async function onRequestGet({ request, env }) {
  const chk = await who(request, env);
  if (chk.res) return chk.res;
  return json({ ok: true, on: translateOn(env) });
}

export async function onRequestPost({ request, env }) {
  const origin = request.headers.get('Origin');
  if (origin) { let host = ''; try { host = new URL(origin).host; } catch (e) {} if (host !== new URL(request.url).host) return json({ ok: false, reason: 'origin' }, 403); }
  const chk = await who(request, env);
  if (chk.res) return chk.res;
  let b; try { b = await request.json(); } catch (e) { return json({ ok: false, reason: 'bad-request' }, 400); }
  const text = String((b && b.text) || '').replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]/g, '').trim();
  if (!text) return json({ ok: false, reason: 'bad-request' }, 400);
  if (text.length > MAX_TEXT) return json({ ok: false, reason: 'too-long' }, 413);
  const to = target(text, b.to);
  if (!translateOn(env)) return json({ ok: false, reason: 'off', to, google: googleUrl(text, to) });
  const key = 'tr:' + (await sha(to + '\n' + text)).slice(0, 40);
  const had = env.FEED ? await env.FEED.get(key) : null;
  if (had) return json({ ok: true, text: had, to, cached: true });
  if (env.FEED) {
    const rk = 'rl:tr:' + String(chk.who).toLowerCase().replace(/\s+/g, ' ') + ':' + Math.floor(Date.now() / 3600e3), n = Number(await env.FEED.get(rk)) || 0;
    if (n >= PER_HOUR) return json({ ok: false, reason: 'rate', to, google: googleUrl(text, to) }, 429);
    await env.FEED.put(rk, String(n + 1), { expirationTtl: 3700 });
  }
  try {
    const r = await translate(env, text, to);
    if (env.FEED) await env.FEED.put(key, r.text, { expirationTtl: KEEP });
    return json({ ok: true, text: r.text, to, cached: false });
  } catch (e) {
    console.error('translate:', e && e.message);
    return json({ ok: false, reason: e && e.code === 'rate' ? 'rate' : 'down', to, google: googleUrl(text, to) }, 502);
  }
}
export function onRequest() { return json({ ok: false, reason: 'method' }, 405); }
