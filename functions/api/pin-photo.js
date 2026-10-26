// POST /api/pin-photo: attaches one photo or voice note from the RAFIKI pin app to a note on a monday.com
// project (the note returned by /api/pin). Photos go on the note, not into the public
// "Photos of the project" column, so nothing from the field reaches the website by itself.
// Form fields: update (note id), label (optional, e.g. "Water flowing", used in the file name), file (JPEG/PNG/HEIC/WebP photo, or a WebM/MP4/AAC/MP3/OGG voice note; up to 8 MB). Needs the team PIN in "X-Pin".
// Trip receipts (fuel, tolls, lodging): form fields kind=receipt, itin=<itinerary id>, file (a photo, up to 5 MB), no update.
//   The caller must be able to edit that trip. Kept in KV (itinphoto:<id>, 400 days), not on monday.com.
//   → { ok, id: "rc_…", url: "/api/pin-photo?receipt=rc_…" }; the id goes in a fuel log's or an extra's `receipt`.
// GET /api/pin-photo?receipt=<id> → the image, for people who can see that trip.
import { checkPin } from './pin.js';
import { load as loadItin, canEdit, canSee, newId } from '../../lib/itin.js';
import { addFileToUpdate } from '../../lib/monday.js';
// voice notes are typed out in the background (Kiswahili or English) and added to the same note: lib/voice-note.js
import { typeOutVoiceNote } from '../../lib/voice-note.js';

const MAX_BYTES = 8 * 1024 * 1024;
const EXT = { 'image/jpeg': 'jpg', 'image/png': 'png', 'image/webp': 'webp', 'image/heic': 'heic', 'image/heif': 'heif',
  'audio/webm': 'webm', 'audio/mp4': 'm4a', 'audio/x-m4a': 'm4a', 'audio/aac': 'aac', 'audio/mpeg': 'mp3', 'audio/ogg': 'ogg' };

function json(body, status) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' } });
}

export async function onRequestPost({ request, env, waitUntil }) {
  const origin = request.headers.get('Origin');
  if (origin && new URL(origin).host !== new URL(request.url).host) return json({ ok: false, reason: 'origin' }, 403);
  const chk = await checkPin(request, env);
  if (chk.res) return chk.res;
  if (Number(request.headers.get('Content-Length') || 0) > MAX_BYTES + 100000) return json({ ok: false, reason: 'too-large' }, 413);
  let form;
  try { form = await request.formData(); } catch (e) { return json({ ok: false, reason: 'bad-request' }, 400); }
  if (String(form.get('kind') || '') === 'receipt') return receipt(request, env, chk, form);
  const update = String(form.get('update') || '').replace(/\D/g, '');
  const file = form.get('file');
  if (!update || !file || typeof file === 'string') return json({ ok: false, reason: 'bad-request' }, 400);
  if (file.size > MAX_BYTES) return json({ ok: false, reason: 'too-large' }, 413);
  const type = String(file.type || '').split(';')[0].trim().toLowerCase(), ext = EXT[type];
  if (!ext) return json({ ok: false, reason: 'type' }, 415);
  const label = String(form.get('label') || '').toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '').slice(0, 40);
  const name = (type.indexOf('audio/') === 0 ? 'voice-note-' : 'site-photo-' + (label ? label + '-' : '')) + new Date().toISOString().slice(0, 16).replace(/[:T]/g, '-') + '.' + ext;
  try {
    const voice = type.indexOf('audio/') === 0, bytes = voice ? await file.arrayBuffer() : null;
    await addFileToUpdate(chk.token || env.MONDAY_API_TOKEN, update, file, name, env.MONDAY_API_URL);
    if (voice && waitUntil) waitUntil(typeOutVoiceNote(env, { token: chk.token || env.MONDAY_API_TOKEN, update, bytes, mime: type, ip: request.headers.get('CF-Connecting-IP') || 'unknown', by: chk.who || '', apiUrl: env.MONDAY_API_URL, secs: Number(form.get('secs')) || 0 }).catch(e => console.error('voice note typing:', e && e.message)));
    return json({ ok: true }, 200);
  } catch (e) {
    console.error('pin photo:', e && e.message);
    return json({ ok: false, reason: 'down' }, 502);
  }
}

// ---- trip receipts ----------------------------------------------------------------------------------------
export const RECEIPT_MAX = 5 * 1024 * 1024, RECEIPT_TTL = 400 * 86400, RECEIPTS_PER_HOUR = 60;
const PHOTO = { 'image/jpeg': 1, 'image/png': 1, 'image/webp': 1, 'image/heic': 1, 'image/heif': 1 };
async function receipt(request, env, chk, form) {
  if (!chk.who) return json({ ok: false, reason: 'named-only' }, 403);
  if (!env.FEED) return json({ ok: false, reason: 'no-storage' }, 503);
  const rec = await loadItin(env, String(form.get('itin') || ''));
  if (!rec) return json({ ok: false, reason: 'not-found' }, 404);
  if (!canEdit(chk, rec)) return json({ ok: false, reason: canSee(chk, rec) ? 'read-only' : 'forbidden' }, 403);
  const file = form.get('file');
  if (!file || typeof file === 'string') return json({ ok: false, reason: 'bad-request' }, 400);
  if (file.size > RECEIPT_MAX) return json({ ok: false, reason: 'too-large', max: RECEIPT_MAX }, 413);
  const type = String(file.type || '').split(';')[0].trim().toLowerCase();
  if (!PHOTO[type]) return json({ ok: false, reason: 'type' }, 415);
  const rk = 'rl:receipt:' + (request.headers.get('CF-Connecting-IP') || 'unknown'), n = Number(await env.FEED.get(rk)) || 0;
  if (n >= RECEIPTS_PER_HOUR) return json({ ok: false, reason: 'rate' }, 429);
  await env.FEED.put(rk, String(n + 1), { expirationTtl: 3600 });
  const buf = new Uint8Array(await file.arrayBuffer());
  let bin = ''; for (let i = 0; i < buf.length; i += 0x8000) bin += String.fromCharCode.apply(null, buf.subarray(i, i + 0x8000));
  const id = newId('rc');
  await env.FEED.put('itinphoto:' + id, JSON.stringify({ t: type, d: btoa(bin), itin: rec.id, by: chk.who, at: new Date().toISOString() }), { expirationTtl: RECEIPT_TTL });
  return json({ ok: true, id, url: '/api/pin-photo?receipt=' + id }, 201);
}
export async function onRequestGet({ request, env }) {
  const chk = await checkPin(request, env);
  if (chk.res) return chk.res;
  const id = new URL(request.url).searchParams.get('receipt') || '';
  if (!/^rc_[A-Za-z0-9]{6,20}$/.test(id) || !env.FEED) return json({ ok: false, reason: 'not-found' }, 404);
  const ph = await env.FEED.get('itinphoto:' + id, 'json');
  const rec = ph ? await loadItin(env, ph.itin) : null;
  if (!ph || !rec || !canSee(chk, rec)) return json({ ok: false, reason: 'not-found' }, 404);
  const bin = atob(ph.d), out = new Uint8Array(bin.length); for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return new Response(out, { headers: { 'Content-Type': ph.t, 'Cache-Control': 'private, max-age=86400', 'X-Content-Type-Options': 'nosniff' } });
}

export function onRequest() { return json({ ok: false, reason: 'method' }, 405); }
