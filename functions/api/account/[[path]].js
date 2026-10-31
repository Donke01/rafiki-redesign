// /api/account/*: the signed-in person's own account settings (chatfx: Settings › Profile, Privacy, Linked devices,
// and the RAFIKI PIN check behind Locked chats). The full contract with examples is in the chatfx api.md.
//   GET  /api/account                          → { ok, name, kind, about, privacy, hasPin, device }
//   POST /api/account/about       { about }    → { ok, about }                 (≤ 140 characters; "" clears it)
//   POST /api/account/privacy     { …changes } → { ok, privacy, chat }
//   POST /api/account/verify-pin  { pin }      → { ok: true } | { ok: false, reason: 'pin' | 'no-pin' | 'locked' } (HTTP 200)
//   GET  /api/account/devices                  → { ok, current, devices: [...] }
//   POST /api/account/devices/sign-out { id }  → { ok }
//   POST /api/account/devices/sign-out-others  → { ok, gen, signedOut }
// Signed in like /api/pin (checkPin: PIN or monday.com session, blocked and signed-out refused); donors and the shared
// team PIN are refused. Stored in KV FEED: pinprofiles (about, priv) and pindev:<name> (lib/pin-devices.js).
import { checkPin } from '../pin.js';
import { getJson, putJson, team, hashPin, sameName, guards, saveGuards, guardKey } from '../../../lib/pin-store.js';
import { readPrivacy, mergePrivacy, cleanAbout, ABOUT_MAX } from '../../../lib/privacy.js';
import { readDevices, saveDevices, deviceList } from '../../../lib/pin-devices.js';
import { dropSession, restamp } from '../../../lib/pin-auth.js';
import { chatPrivacy, chatKick } from '../../../lib/chat.js';

const HEAD = { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff', 'X-Robots-Tag': 'noindex' };
const json = (body, status) => new Response(JSON.stringify(body), { status: status || 200, headers: HEAD });
const VERIFY_TRIES = 5, VERIFY_WINDOW = 900, WRITES_PER_HOUR = 60;

function sameText(a, b) {
  const x = new TextEncoder().encode(String(a)), y = new TextEncoder().encode(String(b));
  let d = x.length ^ y.length;
  for (let i = 0; i < Math.max(x.length, y.length); i++) d |= (x[i] || 0) ^ (y[i] || 0);
  return d === 0;
}
// this person's own PINs: Cloudflare PIN_USERS entries for their name, and the app PIN (hashed) in the team list
async function ownPins(env, name) {
  const cf = String(env.PIN_USERS || '').split(/[,;\n]/).map(s => {
    const a = s.split(':').map(x => x.trim());
    if (a.length > 2 && /^(ke|tz|ug|mw)$/i.test(a[a.length - 1])) a.pop();
    return a.length >= 2 ? { name: a.slice(0, -1).join(':'), pin: a[a.length - 1] } : null;
  }).filter(p => p && p.pin && sameName(p.name, name)).map(p => p.pin);
  const mine = (await team(env)).find(p => sameName(p.name, name));
  return { cf, h: mine && mine.h ? mine.h : '' };
}
async function count(env, key) { return Number(await env.FEED.get(key)) || 0; }
async function bump(env, key, ttl) { await env.FEED.put(key, String((await count(env, key)) + 1), { expirationTtl: ttl }); }

async function profile(env, name) {
  const prof = await getJson(env, 'pinprofiles', {}), k = Object.keys(prof).find(n => sameName(n, name)) || name;
  return { prof, k, pr: prof[k] || {} };
}
async function saveProfile(env, p, change) {
  p.prof[p.k] = Object.assign({}, p.pr, change);
  await putJson(env, 'pinprofiles', p.prof);
  return p.prof[p.k];
}

export async function onRequest({ request, env, params, waitUntil }) {
  const url = new URL(request.url);
  const path = (Array.isArray(params && params.path) ? params.path.join('/') : String((params && params.path) || url.pathname.replace(/^\/api\/account\/?/, ''))).replace(/^\/+|\/+$/g, '');
  if (request.headers.get('X-Donor-Token')) return json({ ok: false, reason: 'donor' }, 403);
  if (request.method === 'POST') {
    const origin = request.headers.get('Origin');
    if (origin) { let host = ''; try { host = new URL(origin).host; } catch (e) {} if (host !== url.host) return json({ ok: false, reason: 'origin' }, 403); }
  } else if (request.method !== 'GET') return json({ ok: false, reason: 'method' }, 405);
  const chk = await checkPin(request, env);
  if (chk.res) return chk.res;
  if (!chk.who) return json({ ok: false, reason: 'named-only' }, 403);
  if (!env.FEED) return json({ ok: false, reason: 'not-set-up' }, 503);
  const name = chk.who, later = p => (waitUntil ? waitUntil(p) : p);
  let b = {};
  if (request.method === 'POST') {
    try { b = await request.json(); } catch (e) { return json({ ok: false, reason: 'bad-request' }, 400); }
    if (!b || typeof b !== 'object' || Array.isArray(b)) return json({ ok: false, reason: 'bad-request' }, 400);
  }
  const route = request.method + ' ' + path;
  const writes = async () => {
    const k = 'rl:acct:' + guardKey(name) + ':' + Math.floor(Date.now() / 3600e3);
    if (await count(env, k) >= WRITES_PER_HOUR) return true;
    await bump(env, k, 3700); return false;
  };
  try {
    switch (route) {
      case 'GET ': {
        const p = await profile(env, name), own = await ownPins(env, name);
        // a privacy change the chat worker missed (chat was off, or busy): sent again
        if (p.pr.priv && p.pr.privSync === false && await chatPrivacy(env, name, readPrivacy(p.pr.priv))) later(saveProfile(env, p, { privSync: true }));
        return json({ ok: true, name, kind: chk.kind || '', about: cleanAbout(p.pr.about), privacy: readPrivacy(p.pr.priv), hasPin: !!(own.cf.length || own.h), device: chk.dev || '' });
      }
      case 'POST about': {
        const about = cleanAbout(b.about);
        if (about.length > ABOUT_MAX) return json({ ok: false, reason: 'too-long', max: ABOUT_MAX }, 400);
        if (await writes()) return json({ ok: false, reason: 'rate' }, 429);
        await saveProfile(env, await profile(env, name), { about });
        return json({ ok: true, about });
      }
      case 'POST privacy': {
        const p = await profile(env, name), priv = mergePrivacy(p.pr.priv, b);
        if (!priv) return json({ ok: false, reason: 'bad-request' }, 400);
        if (await writes()) return json({ ok: false, reason: 'rate' }, 429);
        const synced = await chatPrivacy(env, name, priv);
        await saveProfile(env, p, { priv, privSync: synced });
        return json({ ok: true, privacy: priv, chat: synced });
      }
      case 'POST verify-pin': {
        const rk = 'rl:pinverify:' + guardKey(name);
        // (always HTTP 200: a 401 or 429 here must not look like the app's own sign-in failing)
        if (await count(env, rk) >= VERIFY_TRIES) return json({ ok: false, reason: 'locked', retryIn: VERIFY_WINDOW });
        const own = await ownPins(env, name);
        if (!own.cf.length && !own.h) return json({ ok: false, reason: 'no-pin' });
        const pin = String(b.pin == null ? '' : b.pin).trim();
        let ok = false;
        if (/^\d{4,8}$/.test(pin)) {
          for (const x of own.cf) if (sameText(pin, x)) ok = true;
          if (own.h && sameText(await hashPin(env, pin), own.h)) ok = true;
        }
        if (!ok) { await bump(env, rk, VERIFY_WINDOW); return json({ ok: false, reason: 'pin', left: Math.max(0, VERIFY_TRIES - (await count(env, rk))) }); }
        await env.FEED.delete(rk);
        return json({ ok: true });
      }
      case 'GET devices': {
        const g = (await guards(env))[guardKey(name)];
        return json({ ok: true, current: chk.dev || '', devices: deviceList(await readDevices(env, name), chk.dev, g && g.out) });
      }
      case 'POST devices/sign-out': {
        const list = await readDevices(env, name), d = list.find(x => x.id === String(b.id || '') && !x.out);
        if (!d) return json({ ok: false, reason: 'not-found' }, 404);
        if (d.id === chk.dev) return json({ ok: false, reason: 'current' }, 409);
        if (d.legacy) return json({ ok: false, reason: 'old-app' }, 409);
        d.out = Date.now();
        if (d.s) await dropSession(env, d.s);
        await saveDevices(env, name, list);
        later(chatKick(env, { name, dev: d.id }));
        return json({ ok: true });
      }
      case 'POST devices/sign-out-others': {
        // the session number goes up (every other phone, old apps too, is sent to the lock screen), monday.com sessions
        // from before now end, and this device carries on: its record keeps the new number, its session is restamped
        const gd = await guards(env), gk = guardKey(name), g = Object.assign({ gen: 0, out: null, blocked: null }, gd[gk]), now = Date.now();
        g.gen = (Number(g.gen) || 0) + 1; g.out = new Date(now).toISOString(); gd[gk] = g;
        const list = await readDevices(env, name);
        let n = 0;
        for (const d of list) {
          if (d.out) continue;
          if (d.id === chk.dev) { d.g = g.gen; continue; }
          d.out = now; n++;
          if (d.s) await dropSession(env, d.s);
        }
        // this device's record first, so its own requests in the meantime are never refused
        await saveDevices(env, name, list);
        await saveGuards(env, gd);
        if (chk.devKind === 'monday') await restamp(request, env, new Date(now + 1000).toISOString());
        later(chatKick(env, { name, keep: chk.dev, gen: g.gen }));
        return json({ ok: true, gen: g.gen, signedOut: n });
      }
    }
    return json({ ok: false, reason: 'not-found' }, 404);
  } catch (e) {
    console.error('account ' + path + ':', e && e.message);
    return json({ ok: false, reason: 'down' }, 502);
  }
}
