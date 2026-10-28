// GET /api/stories/photo?id=<project>&asset=<asset>&w=<320..1920>: a photo of a story on the website (public).
// Only an asset listed in that approved story's photos (pubstory:<id>.photos, consent already checked when it was
// approved) is served: fetched from monday.com with the server's token, made smaller where Cloudflare image resizing is
// on, and kept by browsers and Cloudflare for a day. A story taken off the website stops serving its photos at once.
import { getPublic } from '../../../lib/story-doc.js';
import { assetInfo } from '../../../lib/monday.js';

const WIDTHS = [320, 480, 640, 960, 1280, 1600, 1920];
const CACHE = 'public, max-age=86400';
const notFound = () => new Response('Not found', { status: 404, headers: { 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' } });
const isImage = r => r && r.ok && /^image\//i.test(r.headers.get('Content-Type') || '');
export const width = v => { const n = Number(v) || 1280; return WIDTHS.find(w => w >= n) || WIDTHS[WIDTHS.length - 1]; };

export async function onRequestGet({ request, env, waitUntil }) {
  const here = new URL(request.url), q = here.searchParams;
  const id = String(q.get('id') || ''), asset = String(q.get('asset') || '');
  if (!/^\d{1,20}$/.test(id) || !/^\d{1,20}$/.test(asset) || !env.FEED || !env.MONDAY_API_TOKEN) return notFound();
  const story = await getPublic(env, id).catch(() => null);
  if (!story || !(story.photos || []).map(String).includes(asset)) return notFound();
  const w = width(q.get('w')), format = /image\/webp/i.test(request.headers.get('Accept') || '') ? 'webp' : 'jpeg';
  const etag = '"s' + asset + '-' + w + '-' + format + '"';
  if ((request.headers.get('If-None-Match') || '').split(/\s*,\s*/).includes(etag)) return new Response(null, { status: 304, headers: { ETag: etag, 'Cache-Control': CACHE } });
  const cache = typeof caches !== 'undefined' ? caches.default : null;
  const key = new Request(here.origin + '/api/stories/photo?asset=' + asset + '&w=' + w + '&f=' + format);
  const hit = cache ? await cache.match(key).catch(() => null) : null;
  if (hit) return hit;
  let info;
  try { info = await assetInfo(env.MONDAY_API_TOKEN, asset, env.MONDAY_API_URL); } catch (e) { return new Response('Unavailable', { status: 502, headers: { 'Cache-Control': 'no-store' } }); }
  if (!info) return notFound();
  let img = null;
  try { img = await fetch(info.public_url, { cf: { image: { width: w, fit: 'scale-down', quality: 80, format, metadata: 'none' } } }); } catch (e) { img = null; }
  if (!isImage(img) || /err=/i.test((img && img.headers.get('Cf-Resized')) || '')) { try { img = await fetch(info.public_url); } catch (e) { img = null; } }
  if (!isImage(img)) return new Response('Unavailable', { status: 502, headers: { 'Cache-Control': 'no-store' } });
  const res = new Response(img.body, { headers: { 'Content-Type': img.headers.get('Content-Type'), 'Cache-Control': CACHE, ETag: etag, Vary: 'Accept', 'X-Content-Type-Options': 'nosniff' } });
  if (cache && waitUntil) waitUntil(cache.put(key, res.clone()).catch(() => {}));
  return res;
}
export function onRequest() { return new Response('Method not allowed', { status: 405 }); }
