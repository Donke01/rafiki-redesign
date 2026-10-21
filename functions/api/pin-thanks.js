// POST /api/pin-thanks: a thank-you photo or short video (up to 60 seconds) from the community, for the
// project's donors. Sent from the project screen of the RAFIKI pin app (web and Android) with the team PIN or
// monday.com sign-in, like /api/pin-photo. It leaves a note on the project in monday.com with the file
// attached ("donor-thanks-…"), and keeps the file id in KV (dthanks:<project id>) so donor pages show it —
// only where that donor's photo rule allows photos for the project (lib/donor.js photosAllowed).
// Form fields: id (project), file (JPEG/PNG/WebP photo up to 8 MB, or MP4/WebM/MOV video up to 40 MB),
// caption (optional, up to 140 characters, shown to donors), seconds (video length, checked on the phone).
import { checkPin } from './pin.js';
import { API_URL, createUpdate, readGps, tidyTitle, BOARD_ID } from '../../lib/monday.js';

const PHOTO_MAX = 8 * 1024 * 1024, VIDEO_MAX = 40 * 1024 * 1024, MAX_SECONDS = 60;
const EXT = { 'image/jpeg': 'jpg', 'image/png': 'png', 'image/webp': 'webp', 'video/mp4': 'mp4', 'video/webm': 'webm', 'video/quicktime': 'mov', 'video/3gpp': '3gp' };

function json(body, status) {
  return new Response(JSON.stringify(body), { status: status || 200, headers: { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' } });
}
export const clean = (s, n) => String(s || '').replace(/[<>“”"]/g, '').replace(/\s+/g, ' ').trim().slice(0, n);
export function check(type, size, seconds) {
  const ext = EXT[type];
  if (!ext) return { fail: 'type', status: 415 };
  const video = type.indexOf('video/') === 0;
  if (size > (video ? VIDEO_MAX : PHOTO_MAX)) return { fail: 'too-large', status: 413 };
  if (video && Number(seconds) > MAX_SECONDS + 1) return { fail: 'too-long', status: 400 };
  return { ext, video };
}
// Attaches the file to the note and returns monday.com's file id
async function attach(token, updateId, blob, filename, apiUrl) {
  const base = (apiUrl || API_URL).replace(/\/v2\/?$/, '/v2');
  const form = new FormData();
  form.append('query', 'mutation ($file: File!) { add_file_to_update(update_id: ' + Number(updateId) + ', file: $file) { id } }');
  form.append('variables[file]', blob, filename);
  const res = await fetch(base + '/file', { method: 'POST', headers: { Authorization: token }, body: form });
  if (!res.ok) throw new Error('monday.com file upload answered ' + res.status);
  const body = await res.json();
  if (body.errors && body.errors.length) throw new Error('monday.com: ' + (body.errors[0].message || 'upload failed'));
  return body.data && body.data.add_file_to_update ? String(body.data.add_file_to_update.id) : '';
}

export async function onRequestPost({ request, env }) {
  const origin = request.headers.get('Origin');
  if (origin && new URL(origin).host !== new URL(request.url).host) return json({ ok: false, reason: 'origin' }, 403);
  const chk = await checkPin(request, env);
  if (chk.res) return chk.res;
  if (Number(request.headers.get('Content-Length') || 0) > VIDEO_MAX + 200000) return json({ ok: false, reason: 'too-large' }, 413);
  let form;
  try { form = await request.formData(); } catch (e) { return json({ ok: false, reason: 'bad-request' }, 400); }
  const id = String(form.get('id') || '').replace(/\D/g, ''), file = form.get('file');
  if (!id || !file || typeof file === 'string') return json({ ok: false, reason: 'bad-request' }, 400);
  const type = String(file.type || '').split(';')[0].trim().toLowerCase();
  const c = check(type, file.size, form.get('seconds'));
  if (c.fail) return json({ ok: false, reason: c.fail }, c.status);
  const caption = clean(form.get('caption'), 140), by = chk.who || clean(form.get('by'), 60) || 'someone on the team';
  const token = chk.token || env.MONDAY_API_TOKEN, api = env.MONDAY_API_URL;
  try {
    const cur = await readGps(token, id, api);
    if (!cur || (cur.board && cur.board !== String(env.MONDAY_BOARD_ID || BOARD_ID))) return json({ ok: false, reason: 'project' }, 404);
    const note = '💛 Thank-you for donors from the community at ' + tidyTitle(cur.name) + ', added by ' + by + ' (RAFIKI pin app)' + (caption ? ': “' + caption + '”' : '.') + ' It shows on the private pages of this project’s donors (where their photo setting allows).';
    const updateId = await createUpdate(token, id, note, api);
    if (!updateId) return json({ ok: false, reason: 'down' }, 502);
    const name = 'donor-thanks-' + new Date().toISOString().slice(0, 16).replace(/[:T]/g, '-') + '.' + c.ext;
    const asset = await attach(token, updateId, file, name, api);
    if (env.FEED && asset) {
      const k = 'dthanks:' + id, list = (await env.FEED.get(k, 'json')) || [];
      list.push({ id: asset, at: new Date().toISOString(), media: c.video ? 'video' : 'photo', caption, update: updateId });
      await env.FEED.put(k, JSON.stringify(list.slice(-30)));
      await env.FEED.delete('donorupd:' + id);
    }
    return json({ ok: true, id, updateId, asset });
  } catch (e) {
    console.error('pin thanks:', e && e.message);
    return json({ ok: false, reason: 'down' }, 502);
  }
}

export function onRequest() { return json({ ok: false, reason: 'method' }, 405); }
