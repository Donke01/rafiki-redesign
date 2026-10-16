// GET /api/photo/<assetId>: a project photo from monday.com. monday.com files need a
// login, so this fetches a short-lived link with the API token and passes the image on.
// Only photos of projects in the public feed are served, never invoices or other files.
import { getFeed } from '../../../lib/feed-cache.js';
import { assetUrl, photoIds } from '../../../lib/monday.js';

const notFound = () => new Response('Not found', { status: 404, headers: { 'Cache-Control': 'no-store' } });

export async function onRequestGet({ env, params, request, waitUntil }) {
  const id = String(params.id || '');
  if (!/^\d{1,20}$/.test(id) || !env.MONDAY_API_TOKEN) return notFound();

  const cache = caches.default;
  const hit = await cache.match(request);
  if (hit) return hit;

  let feed;
  try { feed = await getFeed(env, waitUntil); } catch (e) { return new Response('Unavailable', { status: 503 }); }
  if (!photoIds(feed).has(id)) return notFound();

  let url;
  try { url = await assetUrl(env.MONDAY_API_TOKEN, id, env.MONDAY_API_URL); } catch (e) { return new Response('Unavailable', { status: 502 }); }
  if (!url) return notFound();
  const img = await fetch(url);
  if (!img.ok) return new Response('Unavailable', { status: 502 });

  const type = img.headers.get('Content-Type') || '';
  const res = new Response(img.body, {
    headers: {
      'Content-Type': /^image\//.test(type) ? type : 'image/jpeg',
      'Cache-Control': 'public, max-age=604800',
      'X-Content-Type-Options': 'nosniff'
    }
  });
  waitUntil(cache.put(request, res.clone()));
  return res;
}
