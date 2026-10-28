// The website's Stories (public, no sign-in): the stories an admin approved in the RAFIKI Pin app (lib/story-doc.js).
//   GET /api/stories           → { ok, stories: [{ id, slug, title, dek, hero, place, country, ck, type, year, approvedAt, words }] } newest first
//   GET /api/stories?id=<id>   → { ok, story: { id, slug, title, dek, sections, pull, facts, hero, photos, approvedAt, approvedBy, updatedAt, project } }
//                                 404 { ok: false, reason: "not-found" } when it is not (or no longer) on the website
//   GET /api/stories/photo?id=<project>&asset=<asset>&w=<320..1920> → one of that story's photos (functions/api/stories/photo.js)
// Only the approved copies are read (pubstory:<id>, pubstoryidx); a story sent back to Collecting is removed from both.
import { getPublic, publicIndex } from '../../lib/story-doc.js';

function json(body, status, cache) {
  return new Response(JSON.stringify(body), { status: status || 200, headers: { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': cache || 'public, max-age=120, s-maxage=120', 'X-Content-Type-Options': 'nosniff', 'Access-Control-Allow-Origin': '*' } });
}
export async function onRequestGet({ request, env }) {
  if (!env.FEED) return json({ ok: true, stories: [] });
  const q = new URL(request.url).searchParams;
  try {
    if (q.has('id')) {
      const id = String(q.get('id') || '').replace(/\D/g, '');
      const s = id ? await getPublic(env, id) : null;
      return s ? json({ ok: true, story: s }) : json({ ok: false, reason: 'not-found' }, 404, 'public, max-age=30');
    }
    const list = await publicIndex(env);
    return json({ ok: true, stories: Array.isArray(list) ? list : [] });
  } catch (e) {
    console.error('stories:', e && e.message);
    return json({ ok: false, reason: 'down' }, 502, 'no-store');
  }
}
export function onRequest() { return json({ ok: false, reason: 'method' }, 405, 'no-store'); }
