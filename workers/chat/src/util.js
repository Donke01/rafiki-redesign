// Small helpers shared by the chat worker's modules.
export const HEAD = { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' };
export const json = (body, status) => new Response(JSON.stringify(body), { status: status || 200, headers: HEAD });
export const fail = (reason, status, extra) => json(Object.assign({ ok: false, reason }, extra || {}), status || 400);

export const b64u = buf => btoa(String.fromCharCode(...new Uint8Array(buf))).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
export const rand = n => b64u(crypto.getRandomValues(new Uint8Array(n)));
export const keyOf = name => String(name || '').trim().toLowerCase();
export const clean = (s, n) => String(s == null ? '' : s).replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]/g, '').trim().slice(0, n);
export async function sha(s) { return [...new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(s)))].map(b => b.toString(16).padStart(2, '0')).join(''); }

// constant-time string comparison
export function same(a, b) {
  const x = new TextEncoder().encode(String(a)), y = new TextEncoder().encode(String(b));
  let d = x.length ^ y.length;
  for (let i = 0; i < Math.max(x.length, y.length); i++) d |= (x[i] || 0) ^ (y[i] || 0);
  return d === 0;
}

// HMAC-SHA256, base64url
export async function hmac(key, text) {
  const k = await crypto.subtle.importKey('raw', new TextEncoder().encode(key), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
  return b64u(await crypto.subtle.sign('HMAC', k, new TextEncoder().encode(text)));
}

// Signed media links, so <img>/<video>/<audio> work without headers. They last until the end of the next
// UTC day (whole days, so the same link is reused and cached).
export async function mediaUrl(key, id, now) {
  const e = (Math.floor((now || Date.now()) / 864e5) + 2) * 864e5;
  return '/api/chat/media/' + id + '?e=' + e + '&s=' + (await hmac(key, 'm:' + id + ':' + e)).slice(0, 32);
}
export async function mediaOk(key, id, e, s, now) {
  if (!/^\d{10,16}$/.test(String(e || '')) || Number(e) < (now || Date.now())) return false;
  return same((await hmac(key, 'm:' + id + ':' + e)).slice(0, 32), String(s || ''));
}

// View once (chatfx): a link that works for a few minutes only, signed differently ('o:'), so a day link or a
// signed-in request can never fetch view-once media. Handed out once per receiver by POST open-once.
export const ONCE_MS = 5 * 60 * 1000;
export async function onceUrl(key, id, now) {
  const e = (now || Date.now()) + ONCE_MS;
  return { url: '/api/chat/media/' + id + '?e=' + e + '&o=1&s=' + (await hmac(key, 'o:' + id + ':' + e)).slice(0, 32), exp: e };
}
export async function onceOk(key, id, e, s, now) {
  const t = now || Date.now();
  if (!/^\d{10,16}$/.test(String(e || '')) || Number(e) < t || Number(e) > t + ONCE_MS + 60000) return false;
  return same((await hmac(key, 'o:' + id + ':' + e)).slice(0, 32), String(s || ''));
}

// WebSocket tickets: name, home, admin, session number and (chatfx) the signed-in device, for 60 seconds
export async function makeTicket(key, who, now) {
  const p = b64u(new TextEncoder().encode(JSON.stringify(Object.assign({ n: who.name, h: who.home || '', a: who.admin ? 1 : 0, g: who.gen || 0, e: (now || Date.now()) + 60000 }, who.dev ? { d: who.dev } : {}))));
  return { t: p + '.' + (await hmac(key, 't:' + p)), exp: (now || Date.now()) + 60000 };
}
export function peekTicket(t) {
  try {
    const p = String(t || '').split('.')[0];
    return JSON.parse(new TextDecoder().decode(Uint8Array.from(atob(p.replace(/-/g, '+').replace(/_/g, '/') + '==='.slice((p.length + 3) % 4)), c => c.charCodeAt(0))));
  } catch (e) { return null; }
}
export async function readTicket(key, t, now) {
  const [p, s] = String(t || '').split('.');
  if (!p || !s || !same(await hmac(key, 't:' + p), s)) return null;
  const v = peekTicket(t);
  return v && v.n && v.e > (now || Date.now()) ? v : null;
}
