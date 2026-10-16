// GET /api/projects: the public project feed from monday.com (see lib/monday.js).
import { getFeed } from '../../lib/feed-cache.js';

function json(body, status, cache) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': cache, 'X-Content-Type-Options': 'nosniff' }
  });
}

export async function onRequestGet({ env, waitUntil }) {
  // no token yet: the pages keep showing their sample data
  if (!env.MONDAY_API_TOKEN) return json({ configured: false }, 200, 'no-store');
  try {
    return json(await getFeed(env, waitUntil), 200, 'public, max-age=300');
  } catch (e) {
    console.error('project feed:', e && e.message);
    return json({ error: 'feed unavailable' }, 502, 'no-store');
  }
}
