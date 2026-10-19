// POST /api/pin-photo: attaches one photo or voice note from the Rafiki pin app to a note on a monday.com
// project (the note returned by /api/pin). Photos go on the note, not into the public
// "Photos of the project" column, so nothing from the field reaches the website by itself.
// Form fields: update (note id), file (JPEG/PNG/HEIC/WebP photo, or a WebM/MP4/AAC/MP3/OGG voice note; up to 8 MB). Needs the team PIN in "X-Pin".
import { checkPin } from './pin.js';
import { addFileToUpdate } from '../../lib/monday.js';

const MAX_BYTES = 8 * 1024 * 1024;
const EXT = { 'image/jpeg': 'jpg', 'image/png': 'png', 'image/webp': 'webp', 'image/heic': 'heic', 'image/heif': 'heif',
  'audio/webm': 'webm', 'audio/mp4': 'm4a', 'audio/x-m4a': 'm4a', 'audio/aac': 'aac', 'audio/mpeg': 'mp3', 'audio/ogg': 'ogg' };

function json(body, status) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' } });
}

export async function onRequestPost({ request, env }) {
  const origin = request.headers.get('Origin');
  if (origin && new URL(origin).host !== new URL(request.url).host) return json({ ok: false, reason: 'origin' }, 403);
  const chk = await checkPin(request, env);
  if (chk.res) return chk.res;
  if (Number(request.headers.get('Content-Length') || 0) > MAX_BYTES + 100000) return json({ ok: false, reason: 'too-large' }, 413);
  let form;
  try { form = await request.formData(); } catch (e) { return json({ ok: false, reason: 'bad-request' }, 400); }
  const update = String(form.get('update') || '').replace(/\D/g, '');
  const file = form.get('file');
  if (!update || !file || typeof file === 'string') return json({ ok: false, reason: 'bad-request' }, 400);
  if (file.size > MAX_BYTES) return json({ ok: false, reason: 'too-large' }, 413);
  const type = String(file.type || '').split(';')[0].trim().toLowerCase(), ext = EXT[type];
  if (!ext) return json({ ok: false, reason: 'type' }, 415);
  const name = (type.indexOf('audio/') === 0 ? 'voice-note-' : 'site-photo-') + new Date().toISOString().slice(0, 16).replace(/[:T]/g, '-') + '.' + ext;
  try {
    await addFileToUpdate(chk.token || env.MONDAY_API_TOKEN, update, file, name, env.MONDAY_API_URL);
    return json({ ok: true }, 200);
  } catch (e) {
    console.error('pin photo:', e && e.message);
    return json({ ok: false, reason: 'down' }, 502);
  }
}

export function onRequest() { return json({ ok: false, reason: 'method' }, 405); }
