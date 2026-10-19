// Sign in with monday.com for the pin app (/pin). See lib/pin-auth.js.
//   GET  /api/pin-auth/me        → { ok, available, user: { name, photo } | null }
//   GET  /api/pin-auth/start     → sends the person to monday.com to approve the app
//   GET  /api/pin-auth/callback  → monday.com sends them back here; sets the session cookie
//   POST /api/pin-auth/logout    → ends the session on this phone
//   POST /api/pin-auth/exchange  → the Android app swaps its one-time code for a session { code, verifier } → { sid }
import { configured, start, callback, session, signOut, setCookie, exchange } from '../../../lib/pin-auth.js';

const H = { 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff', 'X-Robots-Tag': 'noindex' };
const json = (b, s, extra) => new Response(JSON.stringify(b), { status: s || 200, headers: Object.assign({ 'Content-Type': 'application/json; charset=utf-8' }, H, extra || {}) });
const go = (to, cookie) => new Response(null, { status: 302, headers: Object.assign({ Location: to }, H, cookie ? { 'Set-Cookie': cookie } : {}) });
const APP = 'org.rafikioutreach.pin://signin';
const back = (ret, extra) => '/pin' + (extra ? '?signin=' + extra : '') + (ret && ret.charAt(0) === '#' ? ret : '');

export async function onRequestGet({ request, env, params }) {
  const a = params.action;
  if (a === 'me') {
    if (!configured(env)) return json({ ok: true, available: false, user: null });
    const s = await session(request, env).catch(() => null);
    return json({ ok: true, available: true, user: s ? { name: s.name, photo: s.photo } : null });
  }
  if (!configured(env)) return go('/pin?signin=off');
  if (a === 'start') return go(await start(request, env));
  if (a === 'callback') {
    let r;
    try { r = await callback(request, env); } catch (e) { console.error('pin sign-in:', e && e.message); r = { fail: 'token' }; }
    if (r.app) return go(r.fail ? APP + '?fail=' + r.fail : APP + '?code=' + r.code);
    if (r.fail) return go(back(r.ret, r.fail));
    return go(back(r.ret, 'ok'), setCookie(r.sid, r.maxAge));
  }
  return json({ ok: false, reason: 'method' }, 404);
}

export async function onRequestPost({ request, env, params }) {
  const origin = request.headers.get('Origin');
  if (!origin || new URL(origin).host !== new URL(request.url).host) return json({ ok: false, reason: 'origin' }, 403);
  if (params.action === 'exchange') {
    if (!configured(env)) return json({ ok: false, reason: 'off' }, 404);
    let b = {}; try { b = await request.json(); } catch (e) {}
    const r = await exchange(env, b.code, b.verifier);
    return r ? json({ ok: true, sid: r.sid, maxAge: r.maxAge }) : json({ ok: false, reason: 'expired' }, 400);
  }
  if (params.action !== 'logout') return json({ ok: false, reason: 'method' }, 404);
  await signOut(request, env);
  return json({ ok: true }, 200, { 'Set-Cookie': setCookie('x', 0) });
}
