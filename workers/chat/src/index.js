// rafiki-chat: the RAFIKI Pin team chat worker. Reached only through the Pages project's Service binding CHAT
// (functions/api/chat), which signs people in and passes who they are in X-Chat-* headers. This worker has no
// public address (workers_dev = false, no routes). If a CHAT_SECRET is set on both sides it is also checked.
//
//   /media            POST  upload a photo, video, voice note or document to R2 (CHAT_MEDIA)
//   /media/<id>       GET   stream it back (Range requests for video); ?e=&s= signed links are checked here
//   /ws?t=<ticket>    GET   WebSocket (to the Directory)
//   everything else         the Directory Durable Object (src/directory.js)
import { Directory } from './directory.js';
import { Conversation } from './conversation.js';
import { json, fail, rand, same, mediaUrl, mediaOk, keyOf } from './util.js';

export { Directory, Conversation };

// size caps (bytes) after the phone has compressed photos; video ≤ 60 s and voice ≤ 5 min (checked from `dur`)
export const CAPS = { photo: 2 * 1024 * 1024, video: 16 * 1024 * 1024, voice: 6 * 1024 * 1024, doc: 10 * 1024 * 1024 };
const DUR = { video: 60, voice: 300 };
// documents people may send, by extension (anything else is kept as a plain download)
const DOCS = { pdf: 'application/pdf', doc: 'application/msword', docx: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
  xls: 'application/vnd.ms-excel', xlsx: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', ppt: 'application/vnd.ms-powerpoint',
  pptx: 'application/vnd.openxmlformats-officedocument.presentationml.presentation', odt: 'application/vnd.oasis.opendocument.text',
  ods: 'application/vnd.oasis.opendocument.spreadsheet', txt: 'text/plain', csv: 'text/csv', zip: 'application/zip', kml: 'application/vnd.google-earth.kml+xml', gpx: 'application/gpx+xml' };

let KEY = null;
const dir = env => env.DIR.get(env.DIR.idFromName('directory'));
async function key(env) { if (env.CHAT_SECRET) return String(env.CHAT_SECRET); if (!KEY) KEY = await dir(env).key(); return KEY; }

// what the first bytes say the file is
export function sniff(b, type) {
  const s = (o, str) => [...str].every((c, i) => b[o + i] === c.charCodeAt(0));
  if (type === 'photo') {
    if (b[0] === 0xFF && b[1] === 0xD8 && b[2] === 0xFF) return 'image/jpeg';
    if (s(0, '\x89PNG')) return 'image/png';
    if (s(0, 'RIFF') && s(8, 'WEBP')) return 'image/webp';
    return '';
  }
  if (type === 'video') {
    if (s(4, 'ftyp')) return s(8, 'qt  ') ? 'video/quicktime' : s(8, '3gp') ? 'video/3gpp' : 'video/mp4';
    if (b[0] === 0x1A && b[1] === 0x45 && b[2] === 0xDF && b[3] === 0xA3) return 'video/webm';
    return '';
  }
  if (type === 'voice') {
    if (b[0] === 0x1A && b[1] === 0x45 && b[2] === 0xDF && b[3] === 0xA3) return 'audio/webm';
    if (s(0, 'OggS')) return 'audio/ogg';
    if (s(4, 'ftyp')) return 'audio/mp4';
    if (s(0, '#!AMR')) return 'audio/amr';
    if (s(0, 'ID3') || (b[0] === 0xFF && (b[1] & 0xE0) === 0xE0 && (b[1] & 0x06) !== 0)) return 'audio/mpeg';
    if (b[0] === 0xFF && (b[1] & 0xF6) === 0xF0) return 'audio/aac';
    if (s(0, 'RIFF') && s(8, 'WAVE')) return 'audio/wav';
    return '';
  }
  return 'application/octet-stream';
}

async function upload(request, env) {
  const q = new URL(request.url).searchParams;
  let type = q.get('type') || '', name = q.get('name') || '', dur = Number(q.get('dur')) || 0, body;
  const ct = request.headers.get('Content-Type') || '';
  const len = Number(request.headers.get('Content-Length')) || 0;
  if (len > CAPS.video + 64 * 1024) return fail('too-large', 413);
  if (/^multipart\/form-data/i.test(ct)) {
    let fd; try { fd = await request.formData(); } catch (e) { return fail('bad-request'); }
    const f = fd.get('file') || [...fd.values()].find(v => typeof v === 'object' && v && 'arrayBuffer' in v);
    if (!f || typeof f === 'string') return fail('bad-request');
    type = String(fd.get('type') || type); name = String(fd.get('name') || f.name || name); dur = Number(fd.get('dur')) || dur;
    body = new Uint8Array(await f.arrayBuffer());
  } else body = new Uint8Array(await request.arrayBuffer());
  if (!CAPS[type]) return fail('bad-request');
  if (!body.length) return fail('bad-request');
  if (body.length > CAPS[type]) return fail('too-large', 413, { max: CAPS[type] });
  if (DUR[type] && dur > DUR[type]) return fail('too-long', 413, { max: DUR[type] });
  name = String(name).replace(/[\u0000-\u001F\u007F/\\]/g, '').trim().slice(0, 120);
  let mime = sniff(body, type);
  if (!mime) return fail('type', 415);
  if (type === 'doc') { const ext = (/\.([a-z0-9]{1,5})$/i.exec(name) || [])[1]; mime = (ext && DOCS[ext.toLowerCase()]) || 'application/octet-stream'; }
  // one at a time per person is plenty; the Directory counts uploads per hour
  const hd = { 'Content-Type': 'application/json' };
  for (const [k, v] of request.headers) if (/^x-chat-/i.test(k)) hd[k] = v;
  const allow = await dir(env).fetch(new Request('https://chat/allow', { method: 'POST', headers: hd, body: JSON.stringify({ what: 'media' }) }));
  if (!allow.ok) return allow;
  const id = rand(18);
  let by = ''; try { by = decodeURIComponent(request.headers.get('X-Chat-User') || ''); } catch (e) {}
  await env.CHAT_MEDIA.put('m/' + id, body, { httpMetadata: { contentType: mime }, customMetadata: { by: keyOf(by), type, name, dur: String(dur || ''), at: String(Date.now()) } });
  return json({ ok: true, id, url: await mediaUrl(await key(env), id), type, size: body.length, name, mime });
}

async function download(request, env, id) {
  const q = new URL(request.url).searchParams;
  // either Pages signed the person in (X-Chat-User), or the link carries a valid signature
  if (!request.headers.get('X-Chat-User') && !(await mediaOk(await key(env), id, q.get('e'), q.get('s')))) return new Response('Not found', { status: 404 });
  const range = request.headers.get('Range');
  const obj = await env.CHAT_MEDIA.get('m/' + id, range ? { range: request.headers } : {});
  if (!obj) return new Response('Not found', { status: 404, headers: { 'Cache-Control': 'no-store' } });
  const h = new Headers({ 'Accept-Ranges': 'bytes', 'X-Content-Type-Options': 'nosniff', 'Cache-Control': 'private, max-age=86400', ETag: obj.httpEtag });
  const mime = (obj.httpMetadata && obj.httpMetadata.contentType) || 'application/octet-stream', meta = obj.customMetadata || {};
  h.set('Content-Type', mime);
  if (meta.type === 'doc' || !/^(image|video|audio)\//.test(mime)) {
    h.set('Content-Disposition', 'attachment; filename*=UTF-8\'\'' + encodeURIComponent(meta.name || 'file'));
    h.set('Content-Security-Policy', 'sandbox');
  }
  if (range && obj.range && obj.body) {
    const off = obj.range.offset != null ? obj.range.offset : obj.size - obj.range.suffix, n = obj.range.length != null ? obj.range.length : obj.size - off;
    h.set('Content-Range', 'bytes ' + off + '-' + (off + n - 1) + '/' + obj.size);
    h.set('Content-Length', String(n));
    return new Response(obj.body, { status: 206, headers: h });
  }
  h.set('Content-Length', String(obj.size));
  return new Response(obj.body, { status: 200, headers: h });
}

export default {
  async fetch(request, env) {
    if (env.CHAT_SECRET && !same(request.headers.get('X-Chat-Secret') || '', env.CHAT_SECRET)) return fail('secret', 403);
    if (!env.DIR || !env.CONV) return fail('not-set-up', 503);
    const url = new URL(request.url), path = url.pathname.replace(/^\/+(api\/chat\/)?/, '');
    const m = /^media\/([A-Za-z0-9_-]{16,40})$/.exec(path);
    if (m) {
      if (request.method !== 'GET' && request.method !== 'HEAD') return fail('method', 405);
      if (!env.CHAT_MEDIA) return fail('no-media', 503);
      return download(request, env, m[1]);
    }
    if (path === 'media') {
      if (request.method !== 'POST') return fail('method', 405);
      if (!env.CHAT_MEDIA) return fail('no-media', 503);
      if (!request.headers.get('X-Chat-User')) return fail('no-name', 403);
      return upload(request, env);
    }
    const u = new URL(request.url); u.pathname = '/' + path;
    return dir(env).fetch(new Request(u.toString(), request));
  }
};
