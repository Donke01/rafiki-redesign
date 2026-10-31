// Linked devices (chatfx): every phone or browser signed in as a person, for Settings › Linked devices.
//
// How a device is known:
//  · monday.com sign-in: its session (the HttpOnly cookie). The id comes from the session's KV hash, and signing the
//    device out deletes that session, so the cookie stops working at once.
//  · PIN sign-in: PINs have no session of their own (the app sends X-Pin with every request), so each app install
//    sends its own random id in X-Pin-Device-Id (16–64 of A–Z a–z 0–9 _ -, made once and kept). Signing that device
//    out marks the id signed out: its requests are refused with 401 "signed-out" until the PIN is typed again on it
//    (X-Pin-New: 1), exactly like "Sign out of all phones" does today with the session number.
//  · Older apps that send no id are still listed (from the app and User-Agent, marked legacy) but can only be signed
//    out with "Sign out of all other devices" (the session number goes up; this device keeps working).
// Apps may also send X-Pin-App (ios | android | web) and X-Pin-Device ("iPhone 17 Pro Max"); otherwise both come
// from the User-Agent. The city is Cloudflare's guess from the connection (request.cf.city).
//
// KV FEED: pindev:<lower-case name> → [{ id, app, name, city, first, last, kind, legacy?, s?, out?, g? }]
//   s: the monday.com session hash · out: when it was signed out · g: the session number this device may keep using
// Last active is written at most every 10 minutes per device (KV writes are limited per day).
import { getJson, putJson, guardKey } from './pin-store.js';

export const DEV_ID = /^[A-Za-z0-9_-]{16,64}$/;
export const TOUCH_MS = 10 * 60 * 1000;
const KEEP_OUT_MS = 60 * 864e5, MAX_ACTIVE = 20;
const b64u = buf => btoa(String.fromCharCode(...new Uint8Array(buf))).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
const sha = async s => b64u(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(s)));
const tidy = (s, n) => String(s == null ? '' : s).replace(/[\u0000-\u001F\u007F<>"]/g, '').replace(/\s+/g, ' ').trim().slice(0, n);
export const devKey = name => 'pindev:' + guardKey(name);

export function appOf(request) {
  const a = String(request.headers.get('X-Pin-App') || '').toLowerCase();
  if (a === 'ios' || a === 'android' || a === 'web') return a;
  const ua = request.headers.get('User-Agent') || '';
  if (/okhttp|Dalvik/i.test(ua)) return 'android';
  if (/CFNetwork|Darwin\//i.test(ua)) return 'ios';
  return 'web';
}
// "Chrome on Windows", "Safari on iPhone", "Samsung Galaxy A15" (from the app)
export function uaName(ua, app) {
  const u = String(ua || '');
  if (app === 'android' && /okhttp|Dalvik/i.test(u)) { const m = /;\s*([^;()]+?)\s+Build\//.exec(u); return m ? tidy(m[1], 60) : 'Android phone'; }
  if (app === 'ios' && /CFNetwork|Darwin\//i.test(u)) return /iPad/i.test(u) ? 'iPad' : 'iPhone';
  const browser = /Edg(e|A|iOS)?\//.test(u) ? 'Edge' : /OPR\/|Opera/.test(u) ? 'Opera' : /SamsungBrowser/.test(u) ? 'Samsung Internet' : /Firefox\/|FxiOS/.test(u) ? 'Firefox'
    : /Chrome\/|CriOS/.test(u) ? 'Chrome' : /Safari\//.test(u) ? 'Safari' : '';
  const os = /iPhone/.test(u) ? 'iPhone' : /iPad/.test(u) ? 'iPad' : /Android/.test(u) ? 'Android' : /Windows/.test(u) ? 'Windows' : /CrOS/.test(u) ? 'Chromebook'
    : /Mac OS X|Macintosh/.test(u) ? 'Mac' : /Linux/.test(u) ? 'Linux' : '';
  if (browser && os) return browser + ' on ' + os;
  return browser || os || (app === 'ios' ? 'iPhone' : app === 'android' ? 'Android phone' : 'Web browser');
}
export function deviceName(request, app) { return tidy(request.headers.get('X-Pin-Device'), 60) || uaName(request.headers.get('User-Agent'), app); }

// this request's device: { id, kind, legacy, s } (s: the monday.com session hash)
export async function deviceOf(request, sessHash) {
  const app = appOf(request);
  if (sessHash) return { id: 'm' + sessHash.slice(0, 23), kind: 'monday', legacy: false, s: sessHash, app };
  const h = String(request.headers.get('X-Pin-Device-Id') || '').trim();
  if (DEV_ID.test(h)) return { id: h, kind: 'pin', legacy: false, app };
  return { id: 'l' + (await sha(app + '\n' + (request.headers.get('User-Agent') || ''))).slice(0, 23), kind: 'pin', legacy: true, app };
}

export async function readDevices(env, name) { const a = await getJson(env, devKey(name), []); return Array.isArray(a) ? a : []; }
export async function saveDevices(env, name, list) {
  const now = Date.now();
  let keep = list.filter(d => !d.out || now - d.out < KEEP_OUT_MS);
  const active = keep.filter(d => !d.out).sort((a, b) => b.last - a.last);
  if (active.length > MAX_ACTIVE) { const drop = new Set(active.slice(MAX_ACTIVE).map(d => d.id)); keep = keep.filter(d => !drop.has(d.id)); }
  await putJson(env, devKey(name), keep);
}

// Called by checkPin for every named sign-in. → { res: 'signed-out' } when this device was signed out, otherwise
// { dev: record, list } (the record is created or refreshed; KV is written only when something changed, and
// "last active" at most every 10 minutes). `fresh`: the PIN was just typed on this device (X-Pin-New: 1).
export async function touchDevice(request, env, name, d, fresh) {
  if (!env.FEED || !name) return { dev: null };
  const now = Date.now(), list = await readDevices(env, name);
  let rec = list.find(x => x.id === d.id), dirty = false;
  if (rec && rec.out) {
    if (!fresh || d.kind === 'monday') return { res: 'signed-out' };
    // the PIN typed again on a signed-out device: a new sign-in
    delete rec.out; delete rec.g; rec.first = now; dirty = true;
  }
  const city = tidy(request.cf && request.cf.city, 60), dname = deviceName(request, d.app);
  if (!rec) {
    rec = { id: d.id, app: d.app, name: dname, city, first: now, last: now, kind: d.kind };
    if (d.legacy) rec.legacy = true;
    if (d.s) rec.s = d.s;
    list.push(rec); dirty = true;
  } else {
    if (rec.app !== d.app) { rec.app = d.app; dirty = true; }
    if (dname && rec.name !== dname) { rec.name = dname; dirty = true; }
    if (city && rec.city !== city) { rec.city = city; dirty = true; }
    if (fresh || now - (Number(rec.last) || 0) >= TOUCH_MS) { rec.last = now; dirty = true; }
  }
  if (dirty) { try { await saveDevices(env, name, list); } catch (e) { console.error('pin devices:', e && e.message); } }
  return { dev: rec, list };
}

// what the person sees in Settings › Linked devices
// (`since`: the Office's "Sign out of all phones" time; devices not seen after it are signed out, so not listed)
export function deviceList(list, currentId, since) {
  const cut = Date.parse(since || '') || 0;
  return (list || []).filter(d => !d.out && (d.id === currentId || !cut || d.last > cut)).map(d => ({ id: d.id, app: d.app, name: d.name, city: d.city || '', first: d.first, last: d.last, kind: d.kind, legacy: !!d.legacy, current: d.id === currentId }))
    .sort((a, b) => (b.current - a.current) || (b.last - a.last));
}
