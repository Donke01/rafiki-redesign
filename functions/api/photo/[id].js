// GET /api/photo/<assetId>?w=480|960|1600: a project photo from monday.com. monday.com
// files need a login, so this fetches a short-lived link with the API token and passes
// the image on. Only photos of projects in the public feed are served, never invoices
// or other files.
//
// Phone photos on the board are often 3000-6000 px and 4-9 MB, so the image is made
// smaller on the way through, in this order:
//  1. Cloudflare image resizing (fetch with cf.image). This works where image
//     transformations are switched on for the site's zone (Cloudflare dashboard → Images
//     → Transformations → enable for rafikioutreach.org). Where it is not available
//     (e.g. *.pages.dev previews) the option is ignored or fails, and we carry on.
//  2. monday.com's own larger preview image. These sit behind a monday.com login and
//     may not open with the API token; the answer is only used if it is really an image.
//  3. The original file, as before.
// Copies are kept in Cloudflare's cache and the browser for a week (photos never change).
// An unresized original is kept for a day only, so it is replaced by a small copy once
// resizing is switched on.
import { getFeed } from '../../../lib/feed-cache.js';
import { assetInfo, photoIds } from '../../../lib/monday.js';

const WIDTHS = [480, 960, 1600];
const LONG = 'public, max-age=604800, immutable';
const SHORT = 'public, max-age=86400';
const HEAVY = 600 * 1024; // originals bigger than this are worth replacing with a preview

const notFound = () => new Response('Not found', { status: 404, headers: { 'Cache-Control': 'no-store' } });

function width(v) {
  const n = Number(v) || 1600;
  return WIDTHS.find(w => w >= n) || WIDTHS[WIDTHS.length - 1];
}
const isImage = r => r && r.ok && /^image\//i.test(r.headers.get('Content-Type') || '');

// 1. Cloudflare resizing. Returns the response and whether it really came back smaller.
async function resized(url, w, format, size) {
  let r;
  try {
    r = await fetch(url, { cf: { image: { width: w, fit: 'scale-down', quality: 78, format, metadata: 'none' } } });
  } catch (e) { return null; }
  const flag = r.headers.get('Cf-Resized') || '';
  if (!isImage(r) || /err=/i.test(flag)) { try { r.body && r.body.cancel(); } catch (e) {} return null; }
  const len = Number(r.headers.get('Content-Length')) || 0;
  const light = (!!flag && !/err=/i.test(flag)) || (len > 0 && size > 0 && len < size * 0.9);
  return { r, light };
}

// 2. monday.com's larger preview. Only ever sent to monday.com, and only accepted when
// monday.com answers with an image (without a login it redirects to its sign-in page).
async function preview(thumbUrl, token) {
  let u;
  try { u = new URL(thumbUrl); } catch (e) { return null; }
  if (u.protocol !== 'https:' || !/(^|\.)monday\.com$/i.test(u.hostname)) return null;
  const big = thumbUrl.replace(/thumb_small/g, 'thumb_big');
  if (big === thumbUrl) return null; // only the small preview exists: too small for the site
  try {
    const r = await fetch(big, { headers: { Authorization: token }, redirect: 'manual' });
    if (r.status === 200 && isImage(r)) return r;
    try { r.body && r.body.cancel(); } catch (e) {}
  } catch (e) {}
  return null;
}

function send(body, type, cache, source) {
  return new Response(body, {
    headers: {
      'Content-Type': /^image\//i.test(type || '') ? type : 'image/jpeg',
      'Cache-Control': cache,
      'X-Content-Type-Options': 'nosniff',
      'X-Photo-Source': source
    }
  });
}
function withVary(res) {
  const out = new Response(res.body, res);
  out.headers.set('Vary', 'Accept');
  return out;
}

export async function onRequestGet({ env, params, request, waitUntil }) {
  const id = String(params.id || '');
  if (!/^\d{1,20}$/.test(id) || !env.MONDAY_API_TOKEN) return notFound();

  const here = new URL(request.url);
  const w = width(here.searchParams.get('w'));
  const format = /image\/webp/i.test(request.headers.get('Accept') || '') ? 'webp' : 'jpeg';
  // one cache entry per photo, size and format; other query strings are ignored
  const key = new Request(here.origin + '/api/photo/' + id + '?w=' + w + '&f=' + format);

  const cache = caches.default;
  const hit = await cache.match(key);
  if (hit) return withVary(hit);

  let feed;
  try { feed = await getFeed(env, waitUntil); } catch (e) { return new Response('Unavailable', { status: 503 }); }
  if (!photoIds(feed).has(id)) return notFound();

  let info;
  try { info = await assetInfo(env.MONDAY_API_TOKEN, id, env.MONDAY_API_URL); } catch (e) { return new Response('Unavailable', { status: 502 }); }
  if (!info) return notFound();

  let res;
  const rs = await resized(info.public_url, w, format, info.file_size);
  if (rs && rs.light) {
    res = send(rs.r.body, rs.r.headers.get('Content-Type'), LONG, 'resized');
  } else {
    const pv = info.file_size > HEAVY && w < 1600 && info.url_thumbnail ? await preview(info.url_thumbnail, env.MONDAY_API_TOKEN) : null;
    if (pv) {
      if (rs) { try { rs.r.body && rs.r.body.cancel(); } catch (e) {} }
      res = send(pv.body, pv.headers.get('Content-Type'), LONG, 'preview');
    } else {
      // resizing was ignored (the answer is the original) or failed: pass the original on
      let img = rs && rs.r;
      if (!img) {
        try { img = await fetch(info.public_url); } catch (e) { img = null; }
        if (!img || !img.ok) return new Response('Unavailable', { status: 502 });
      }
      res = send(img.body, img.headers.get('Content-Type'), SHORT, 'original');
    }
  }
  waitUntil(cache.put(key, res.clone()));
  return withVary(res);
}
