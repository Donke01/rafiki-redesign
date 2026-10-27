// Push notifications through Firebase Cloud Messaging (HTTP v1 API).
// Switched on only when BOTH Cloudflare secrets are set on this worker:
//   FCM_PROJECT_ID       the Firebase project id (e.g. rafiki-pin)
//   FCM_SERVICE_ACCOUNT  the service account key JSON downloaded from Google Cloud (pasted as is, or base64)
// Without them fcmOn() is false and nothing is ever sent. The OAuth token is made here with WebCrypto (RS256 JWT,
// the "JWT bearer" grant) and kept in memory until shortly before it runs out.
import { b64u } from './util.js';

const SCOPE = 'https://www.googleapis.com/auth/firebase.messaging';
const TOKEN_URL = 'https://oauth2.googleapis.com/token';
let CACHE = null; // { tok, exp, who }

export const fcmOn = env => !!(env && env.FCM_PROJECT_ID && env.FCM_SERVICE_ACCOUNT);

export function account(env) {
  let raw = String(env.FCM_SERVICE_ACCOUNT || '').trim();
  if (!raw) return null;
  if (raw[0] !== '{') { try { raw = atob(raw.replace(/-/g, '+').replace(/_/g, '/')); } catch (e) { return null; } }
  try {
    const sa = JSON.parse(raw);
    return sa && sa.client_email && sa.private_key ? sa : null;
  } catch (e) { return null; }
}

async function signKey(pem) {
  const body = String(pem).replace(/-----[^-]+-----/g, '').replace(/\\n/g, '').replace(/\s+/g, '');
  const der = Uint8Array.from(atob(body), c => c.charCodeAt(0));
  return crypto.subtle.importKey('pkcs8', der, { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' }, false, ['sign']);
}

// the signed JWT that is swapped for an access token
export async function makeJwt(sa, now) {
  const iat = Math.floor((now || Date.now()) / 1000);
  const enc = o => b64u(new TextEncoder().encode(JSON.stringify(o)));
  const head = enc({ alg: 'RS256', typ: 'JWT' }), claims = enc({ iss: sa.client_email, scope: SCOPE, aud: sa.token_uri || TOKEN_URL, iat, exp: iat + 3600 });
  const sig = await crypto.subtle.sign('RSASSA-PKCS1-v1_5', await signKey(sa.private_key), new TextEncoder().encode(head + '.' + claims));
  return head + '.' + claims + '.' + b64u(sig);
}

export async function accessToken(env, fetcher, now) {
  const t = now || Date.now(), sa = account(env);
  if (!sa) throw new Error('FCM_SERVICE_ACCOUNT is not a service account key');
  if (CACHE && CACHE.who === sa.client_email && CACHE.exp > t + 60e3) return CACHE.tok;
  const r = await (fetcher || fetch)(sa.token_uri || TOKEN_URL, {
    method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: 'grant_type=' + encodeURIComponent('urn:ietf:params:oauth:grant-type:jwt-bearer') + '&assertion=' + (await makeJwt(sa, t))
  });
  const j = await r.json().catch(() => ({}));
  if (!r.ok || !j.access_token) throw new Error('FCM token: ' + r.status + ' ' + (j.error || ''));
  CACHE = { tok: j.access_token, exp: t + (Number(j.expires_in) || 3600) * 1000, who: sa.client_email };
  return CACHE.tok;
}
export function forgetToken() { CACHE = null; }

// Do-Not-Disturb: { on, from:'HH:MM', to:'HH:MM', tz: minutes east of UTC }. from > to wraps past midnight.
const mins = s => { const m = /^(\d{2}):(\d{2})$/.exec(String(s || '')); return m ? Number(m[1]) * 60 + Number(m[2]) : null; };
export function inDnd(dnd, now) {
  if (!dnd || !dnd.on) return false;
  const a = mins(dnd.from), b = mins(dnd.to);
  if (a == null || b == null || a === b) return false;
  const t = Math.floor(((now || Date.now()) / 60000 + (Number(dnd.tz) || 0)) % 1440 + 1440) % 1440;
  return a < b ? t >= a && t < b : t >= a || t < b;
}

// one FCM message: data-only for Android (the app builds the notification), a notification for web tokens
export function message(dev, data) {
  const d = {};
  for (const [k, v] of Object.entries(data)) d[k] = v == null ? '' : String(v);
  const m = { token: dev.token, data: d };
  if (dev.platform === 'web') {
    m.webpush = { headers: { TTL: '86400', Urgency: 'high' }, notification: { title: d.title, body: d.body, tag: d.c, renotify: true }, fcm_options: { link: '/pin#chat/' + encodeURIComponent(d.c) } };
  } else {
    m.android = { priority: 'high', collapse_key: d.c.slice(0, 64), ttl: '86400s' };
  }
  return m;
}

// → { ok } | { ok:false, gone:true } (token no longer valid: forget it) | { ok:false }
export async function sendOne(env, msg, fetcher) {
  const f = fetcher || fetch, pid = String(env.FCM_PROJECT_ID || '').trim();
  const go = async () => f('https://fcm.googleapis.com/v1/projects/' + encodeURIComponent(pid) + '/messages:send', {
    method: 'POST', headers: { Authorization: 'Bearer ' + (await accessToken(env, f)), 'Content-Type': 'application/json' }, body: JSON.stringify({ message: msg })
  });
  let r = await go();
  if (r.status === 401) { forgetToken(); r = await go(); }
  if (r.ok) return { ok: true };
  const j = await r.json().catch(() => ({}));
  const codes = JSON.stringify((j.error && j.error.details) || []) + ' ' + ((j.error && j.error.status) || '');
  if (r.status === 404 || /UNREGISTERED/.test(codes) || (r.status === 400 && /INVALID_ARGUMENT/.test(codes) && /token/i.test((j.error && j.error.message) || ''))) return { ok: false, gone: true };
  console.error('fcm send:', r.status, codes.slice(0, 200));
  return { ok: false };
}
