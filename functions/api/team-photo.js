// /api/team-photo: profile photos for the RAFIKI Pin team (contract: the "Profile photos" section of the apps' brief).
//   GET    /api/team-photo?name=<Name>[&v=<version>]  → the image (ETag "<version>"; immutable when v is current), 404 none
//   POST   /api/team-photo[?name=<Name>]               raw image body (image/jpeg|png|webp) or multipart field "file";
//                                                       JSON { remove: true } removes it   → { ok, name, photo: url | "" }
//   DELETE /api/team-photo[?name=<Name>]               removes it                        → { ok, name, photo: "" }
// Your own photo, or anyone's for admins. Signed in like /api/pin (PIN or monday.com session; blocked and signed-out
// sessions refused). The shared team PIN and donors are refused. Stored in KV FEED (lib/team-photo.js), at most 300 KB.
import { checkPin } from './pin.js';
import { getJson, sameName } from '../../lib/pin-store.js';
import { roster, chatSync } from '../../lib/chat.js';
import { PHOTO_MAX, PHOTO_TYPES, readPhoto, savePhoto, dropPhoto } from '../../lib/team-photo.js';

const PER_HOUR = 30;
function json(body, status) {
  return new Response(JSON.stringify(body), { status: status || 200, headers: { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff', 'X-Robots-Tag': 'noindex' } });
}
const clean = s => String(s || '').replace(/[<>]/g, '').replace(/\s+/g, ' ').trim().slice(0, 60);

// who is asking: a named, signed-in team member (not a donor, not the shared team PIN)
async function who(request, env) {
  if (request.headers.get('X-Donor-Token')) return { res: json({ ok: false, reason: 'donor' }, 403) };
  const chk = await checkPin(request, env);
  if (chk.res) return chk;
  if (!chk.who) return { res: json({ ok: false, reason: 'named-only' }, 403) };
  return chk;
}

export async function onRequestGet({ request, env }) {
  const chk = await who(request, env);
  if (chk.res) return chk.res;
  const q = new URL(request.url).searchParams, name = clean(q.get('name'));
  const ph = name ? await readPhoto(env, name) : null;
  if (!ph || !ph.d) return json({ ok: false, reason: 'not-found' }, 404);
  const etag = '"' + ph.v + '"', current = q.get('v') === ph.v;
  const head = { 'Content-Type': ph.t || 'image/jpeg', ETag: etag, 'Cache-Control': current ? 'private, max-age=31536000, immutable' : 'private, no-cache', 'X-Content-Type-Options': 'nosniff', 'X-Robots-Tag': 'noindex' };
  const inm = request.headers.get('If-None-Match') || '';
  if (inm && inm.split(',').map(s => s.trim().replace(/^W\//, '')).includes(etag)) return new Response(null, { status: 304, headers: head });
  const bin = atob(ph.d), out = new Uint8Array(bin.length); for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return new Response(out, { headers: head });
}

// the person a change is for: yourself, or (admins) anyone on the team
async function target(request, env, chk, formName) {
  const q = new URL(request.url).searchParams, asked = clean(q.get('name') || formName);
  if (!asked || sameName(asked, chk.who)) return { name: chk.who };
  if (!chk.admin) return { res: json({ ok: false, reason: 'forbidden' }, 403) };
  const list = await roster(env), seenAt = await getJson(env, 'pinseen', {});
  const hit = list.find(p => sameName(p.n, asked)) || Object.keys(seenAt).filter(n => sameName(n, asked)).map(n => ({ n }))[0];
  if (!hit) return { res: json({ ok: false, reason: 'no-person' }, 404) };
  return { name: hit.n };
}
async function limited(request, env) {
  const k = 'rl:teamphoto:' + (request.headers.get('CF-Connecting-IP') || 'unknown'), n = Number(await env.FEED.get(k)) || 0;
  if (n >= PER_HOUR) return true;
  await env.FEED.put(k, String(n + 1), { expirationTtl: 3600 });
  return false;
}
function originOk(request) {
  const origin = request.headers.get('Origin');
  if (!origin) return true;
  try { return new URL(origin).host === new URL(request.url).host; } catch (e) { return false; }
}
async function remove(request, env, chk, formName, waitUntil) {
  const t = await target(request, env, chk, formName);
  if (t.res) return t.res;
  if (await limited(request, env)) return json({ ok: false, reason: 'rate' }, 429);
  if (await dropPhoto(env, t.name)) later(waitUntil, chatSync(env));
  return json({ ok: true, name: t.name, photo: '' });
}
const later = (waitUntil, p) => (waitUntil ? waitUntil(p) : p);

export async function onRequestPost({ request, env, waitUntil }) {
  if (!originOk(request)) return json({ ok: false, reason: 'origin' }, 403);
  const chk = await who(request, env);
  if (chk.res) return chk.res;
  if (!env.FEED) return json({ ok: false, reason: 'no-storage' }, 503);
  if (Number(request.headers.get('Content-Length') || 0) > PHOTO_MAX + 64 * 1024) return json({ ok: false, reason: 'too-large', max: PHOTO_MAX }, 413);
  const ct = String(request.headers.get('Content-Type') || '').split(';')[0].trim().toLowerCase();
  let type = ct, bytes = null, formName = '';
  if (ct === 'application/json') {
    let b; try { b = await request.json(); } catch (e) { return json({ ok: false, reason: 'bad-request' }, 400); }
    if (b && b.remove === true) return remove(request, env, chk, clean(b.name), waitUntil);
    return json({ ok: false, reason: 'bad-request' }, 400);
  }
  if (ct === 'multipart/form-data') {
    let form; try { form = await request.formData(); } catch (e) { return json({ ok: false, reason: 'bad-request' }, 400); }
    formName = clean(form.get('name'));
    if (String(form.get('remove') || '') === 'true') return remove(request, env, chk, formName, waitUntil);
    const file = form.get('file');
    if (!file || typeof file === 'string') return json({ ok: false, reason: 'bad-request' }, 400);
    type = String(file.type || '').split(';')[0].trim().toLowerCase();
    if (file.size > PHOTO_MAX) return json({ ok: false, reason: 'too-large', max: PHOTO_MAX }, 413);
    if (PHOTO_TYPES[type]) bytes = new Uint8Array(await file.arrayBuffer());
  } else if (PHOTO_TYPES[ct]) {
    bytes = new Uint8Array(await request.arrayBuffer());
  }
  if (!PHOTO_TYPES[type]) return json({ ok: false, reason: 'type' }, 415);
  if (!bytes || !bytes.length) return json({ ok: false, reason: 'bad-request' }, 400);
  if (bytes.length > PHOTO_MAX) return json({ ok: false, reason: 'too-large', max: PHOTO_MAX }, 413);
  const t = await target(request, env, chk, formName);
  if (t.res) return t.res;
  if (await limited(request, env)) return json({ ok: false, reason: 'rate' }, 429);
  const photo = await savePhoto(env, t.name, type, bytes, chk.who);
  later(waitUntil, chatSync(env));
  return json({ ok: true, name: t.name, photo });
}

export async function onRequestDelete({ request, env, waitUntil }) {
  if (!originOk(request)) return json({ ok: false, reason: 'origin' }, 403);
  const chk = await who(request, env);
  if (chk.res) return chk.res;
  if (!env.FEED) return json({ ok: false, reason: 'no-storage' }, 503);
  return remove(request, env, chk, '', waitUntil);
}

export function onRequest() { return json({ ok: false, reason: 'method' }, 405); }
