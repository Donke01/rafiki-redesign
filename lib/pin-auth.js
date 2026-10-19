// "Sign in with monday.com" for the Rafiki pin app.
// Each person approves the app once on monday.com (OAuth 2.1 with PKCE). Their monday.com token
// is kept on the server only, in Workers KV, encrypted with a key derived from the app's client
// secret; the phone gets an HttpOnly cookie that names the session. Pins and notes are then
// written with that person's own token, so monday.com shows them as the author.
// Cloudflare settings: MONDAY_CLIENT_ID and MONDAY_CLIENT_SECRET (secrets), plus the KV binding FEED.
import { whoAmI, BOARD_ID } from './monday.js';

export const COOKIE = 'rfpin';
const AUTH = 'https://auth.monday.com/oauth2/authorize';
const TOKEN_NEW = 'https://auth.monday.com/oauth_ms/oauth/token';
const TOKEN_OLD = 'https://auth.monday.com/oauth2/token';
const SESSION_DAYS = 180;

export const configured = env => !!(env.MONDAY_CLIENT_ID && env.MONDAY_CLIENT_SECRET && env.FEED);

const b64u = buf => btoa(String.fromCharCode(...new Uint8Array(buf))).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
const unb64u = s => Uint8Array.from(atob(s.replace(/-/g, '+').replace(/_/g, '/') + '==='.slice((s.length + 3) % 4)), c => c.charCodeAt(0));
const rand = n => b64u(crypto.getRandomValues(new Uint8Array(n)));
const sha = async s => b64u(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(s)));

async function key(env) {
  const raw = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(String(env.MONDAY_CLIENT_SECRET) + ':rafiki-pin-sessions'));
  return crypto.subtle.importKey('raw', raw, 'AES-GCM', false, ['encrypt', 'decrypt']);
}
async function seal(env, obj) {
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const ct = await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, await key(env), new TextEncoder().encode(JSON.stringify(obj)));
  return b64u(iv) + '.' + b64u(ct);
}
async function unseal(env, s) {
  try {
    const [iv, ct] = String(s).split('.');
    const pt = await crypto.subtle.decrypt({ name: 'AES-GCM', iv: unb64u(iv) }, await key(env), unb64u(ct));
    return JSON.parse(new TextDecoder().decode(pt));
  } catch (e) { return null; }
}

export function cookieOf(request) {
  const m = /(?:^|;\s*)rfpin=([A-Za-z0-9_-]{20,})/.exec(request.headers.get('Cookie') || '');
  return m ? m[1] : '';
}
export function setCookie(value, maxAge) {
  return COOKIE + '=' + value + '; Path=/; HttpOnly; Secure; SameSite=Lax; Max-Age=' + maxAge;
}
const redirectUri = request => new URL('/api/pin-auth/callback', request.url).toString();

// The monday.com account the app belongs to (the one behind MONDAY_API_TOKEN), so people can only
// sign in with that account.
async function homeAccount(env) {
  const hit = await env.FEED.get('pinacct', 'json');
  if (hit) return hit;
  const me = await whoAmI(env.MONDAY_API_TOKEN, env.MONDAY_BOARD_ID || BOARD_ID, env.MONDAY_API_URL);
  const a = me ? { id: me.account, slug: me.slug } : null;
  if (a) await env.FEED.put('pinacct', JSON.stringify(a), { expirationTtl: 86400 });
  return a;
}

// 1. start: remember a one-time state and PKCE verifier, then send the person to monday.com
export async function start(request, env) {
  const state = rand(24), verifier = rand(48);
  const ret = (new URL(request.url).searchParams.get('return') || '').replace(/[^#/a-z0-9]/gi, '').slice(0, 40);
  await env.FEED.put('oauthst:' + state, JSON.stringify({ verifier, ret }), { expirationTtl: 600 });
  const u = new URL(AUTH);
  u.searchParams.set('client_id', env.MONDAY_CLIENT_ID);
  u.searchParams.set('redirect_uri', redirectUri(request));
  u.searchParams.set('state', state);
  u.searchParams.set('code_challenge', await sha(verifier));
  u.searchParams.set('code_challenge_method', 'S256');
  u.searchParams.set('force_install_if_needed', 'true');
  try { const a = await homeAccount(env); if (a && a.slug) u.searchParams.set('subdomain', a.slug); } catch (e) {}
  return u.toString();
}

function expiry(tok) {
  if (tok.expires_in) return Date.now() + Number(tok.expires_in) * 1000;
  try { const p = JSON.parse(new TextDecoder().decode(unb64u(String(tok.access_token).split('.')[1]))); if (p.exp) return p.exp * 1000; } catch (e) {}
  return 0; // older monday.com tokens don't expire
}
async function tokenCall(env, body) {
  const form = new URLSearchParams(Object.assign({ client_id: env.MONDAY_CLIENT_ID, client_secret: env.MONDAY_CLIENT_SECRET }, body));
  for (const url of [TOKEN_NEW, TOKEN_OLD]) {
    if (url === TOKEN_OLD && body.grant_type === 'refresh_token') break;
    try {
      const r = await fetch(url, { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded', Accept: 'application/json' }, body: form });
      const j = await r.json().catch(() => null);
      if (r.ok && j && j.access_token) return j;
    } catch (e) {}
  }
  return null;
}

// 2. callback: swap the code for a token, check the person and the board, start a session
export async function callback(request, env) {
  const q = new URL(request.url).searchParams;
  const state = q.get('state') || '', code = q.get('code') || '';
  if (q.get('error')) return { fail: 'denied' };
  const st = state && await env.FEED.get('oauthst:' + state, 'json');
  if (!st || !code) return { fail: 'expired' };
  await env.FEED.delete('oauthst:' + state);
  const tok = await tokenCall(env, { grant_type: 'authorization_code', code, redirect_uri: redirectUri(request), code_verifier: st.verifier });
  if (!tok) return { fail: 'token', ret: st.ret };
  const me = await whoAmI(tok.access_token, env.MONDAY_BOARD_ID || BOARD_ID, env.MONDAY_API_URL).catch(() => null);
  if (!me) return { fail: 'token', ret: st.ret };
  const home = await homeAccount(env).catch(() => null);
  if (home && home.id && me.account !== home.id) return { fail: 'account', ret: st.ret };
  if (!me.board) return { fail: 'board', ret: st.ret };
  const sid = rand(32);
  const sess = { uid: me.id, name: me.name, photo: me.photo, at: new Date().toISOString(), tok: tok.access_token, rt: tok.refresh_token || '', exp: expiry(tok) };
  await env.FEED.put('pinsess:' + await sha(sid), await seal(env, sess), { expirationTtl: SESSION_DAYS * 86400 });
  return { sid, ret: st.ret, maxAge: SESSION_DAYS * 86400 };
}

// Reads the session behind the cookie; refreshes the monday.com token when it is about to expire.
// Returns { name, uid, photo, token } or null.
export async function session(request, env) {
  if (!configured(env)) return null;
  const sid = cookieOf(request);
  if (!sid) return null;
  const k = 'pinsess:' + await sha(sid);
  const raw = await env.FEED.get(k);
  const s = raw && await unseal(env, raw);
  if (!s) return null;
  if (s.exp && s.exp - Date.now() < 5 * 60 * 1000) {
    const t = s.rt && await tokenCall(env, { grant_type: 'refresh_token', refresh_token: s.rt });
    if (!t) { await env.FEED.delete(k); return null; }
    s.tok = t.access_token; s.rt = t.refresh_token || s.rt; s.exp = expiry(t);
    await env.FEED.put(k, await seal(env, s), { expirationTtl: SESSION_DAYS * 86400 });
  }
  return { name: s.name, uid: s.uid, photo: s.photo, token: s.tok };
}

export async function signOut(request, env) {
  const sid = cookieOf(request);
  if (sid && env.FEED) await env.FEED.delete('pinsess:' + await sha(sid));
}
