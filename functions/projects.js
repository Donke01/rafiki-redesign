// GET /projects: the "Our projects" overview page, or, with any project-list parameter
// (/projects?cat=water&status=completed, ?project=<id>, ?view=all …), the list of every project
// (project-list.html), answered at the same address so links stay clean and shareable.
import { isListQuery } from '../lib/explorer-url.js';

export async function onRequestGet({ request, env, next }) {
  const url = new URL(request.url);
  if (!isListQuery(url.search) || !env.ASSETS) return next();
  const res = await env.ASSETS.fetch(new URL('/project-list', url.origin));
  if (!res.ok) return next();
  const out = new Response(res.body, res);
  out.headers.set('Content-Type', 'text/html; charset=utf-8');
  return out;
}
