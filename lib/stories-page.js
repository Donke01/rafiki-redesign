// The website's Stories pages (Pages Functions functions/stories/index.js and functions/stories/[id].js).
//  /stories              the stories hub (stories.html). Old project-list links (/stories?cat=water&status=completed,
//                        ?project=<id> …) are sent on to /projects with the same parameters.
//  /stories/<id>-<slug>  one published story: story.html with the title, description and share photo filled in
//                        on the server (link previews on WhatsApp, Facebook, X… read them), and the story itself
//                        inlined so the page draws at once. A wrong or missing slug is sent on to the right one.
// Stories come from the public snapshot pubstory:<id> written when an admin approves a story (contract:
// stories2-brief.md); nothing else is read, and it holds only public fields.
import { isListQuery } from './explorer-url.js';

export const SITE = 'RAFIKI Outreach';
export const FALLBACK_IMAGE = '/img/og/w-field3.jpg';
const ID_SEG = /^(\d{1,20})(?:-([a-z0-9-]{0,120}))?$/i;

export const esc = s => String(s == null ? '' : s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
// JSON that is safe inside a <script> element
export const inlineJson = v => JSON.stringify(v == null ? null : v).replace(/</g, '\\u003c').replace(/>/g, '\\u003e').replace(/\u2028/g, '\\u2028').replace(/\u2029/g, '\\u2029');

export function slugOf(s) {
  return String(s || '').normalize('NFKD').replace(/[\u0300-\u036f]/g, '').toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '').slice(0, 60) || 'story';
}
export function storyPath(st) { return '/stories/' + String(st.id) + '-' + (st.slug || slugOf(st.title)); }
export function photoUrl(origin, id, asset, w) {
  return origin + '/api/stories/photo?id=' + encodeURIComponent(id) + '&asset=' + encodeURIComponent(asset) + '&w=' + (w || 1200);
}
const clip = (s, n) => { const t = String(s || '').replace(/\s+/g, ' ').trim(); return t.length > n ? t.slice(0, n - 1).replace(/\s+\S*$/, '') + '…' : t; };

// <head> tags for one story (or for "not found" when st is null)
export function headTags(st, origin) {
  if (!st) {
    return [
      '<title>Story not found — ' + SITE + '</title>',
      '<meta name="robots" content="noindex">',
      '<meta name="description" content="This story is not on the website. Read the other stories from the communities RAFIKI Outreach works with.">',
      '<script>window.__STORY=null;</script>'
    ].join('\n');
  }
  const url = origin + storyPath(st);
  const place = [st.facts && st.facts.place, st.facts && st.facts.country].filter(Boolean).join(', ');
  const title = clip(st.title, 110) + (place ? ' · ' + place : '') + ' — ' + SITE;
  const desc = clip(st.dek || (st.sections && st.sections[0] && st.sections[0].body) || '', 220);
  const hero = st.hero && st.hero.id ? st.hero : null;
  const img = hero ? photoUrl(origin, st.id, hero.id, 1200) : origin + FALLBACK_IMAGE;
  const alt = hero && hero.cap ? clip(hero.cap, 200) : 'A RAFIKI Outreach project';
  const ld = {
    '@context': 'https://schema.org', '@type': 'Article', headline: clip(st.title, 110), description: desc, image: [img],
    datePublished: st.approvedAt || undefined, dateModified: st.updatedAt || st.approvedAt || undefined, mainEntityOfPage: url,
    publisher: { '@type': 'Organization', name: SITE, logo: { '@type': 'ImageObject', url: origin + '/img/logo-dark-bold.png' } }
  };
  return [
    '<title>' + esc(title) + '</title>',
    '<meta name="description" content="' + esc(desc) + '">',
    '<link rel="canonical" href="' + esc(url) + '">',
    '<meta property="og:type" content="article">',
    '<meta property="og:site_name" content="' + SITE + '">',
    '<meta property="og:title" content="' + esc(clip(st.title, 110)) + '">',
    '<meta property="og:description" content="' + esc(desc) + '">',
    '<meta property="og:url" content="' + esc(url) + '">',
    '<meta property="og:image" content="' + esc(img) + '">',
    '<meta property="og:image:alt" content="' + esc(alt) + '">',
    st.approvedAt ? '<meta property="article:published_time" content="' + esc(st.approvedAt) + '">' : '',
    '<meta name="twitter:card" content="summary_large_image">',
    '<meta name="twitter:title" content="' + esc(clip(st.title, 110)) + '">',
    '<meta name="twitter:description" content="' + esc(desc) + '">',
    '<meta name="twitter:image" content="' + esc(img) + '">',
    hero ? '<link rel="preload" as="image" href="' + esc(photoUrl('', st.id, hero.id, 1920)) + '" imagesrcset="' +
      [640, 960, 1440, 1920].map(w => esc(photoUrl('', st.id, hero.id, w)) + ' ' + w + 'w').join(', ') + '" imagesizes="100vw">' : '',
    '<script type="application/ld+json">' + inlineJson(ld) + '</script>',
    '<script>window.__STORY=' + inlineJson(st) + ';</script>'
  ].filter(Boolean).join('\n');
}

// story.html carries its default head tags between these markers; the server swaps them for the story's own
export const HEAD_START = '<!--story-head-->', HEAD_END = '<!--/story-head-->';
export function fillTemplate(html, st, origin) {
  const a = html.indexOf(HEAD_START), b = html.indexOf(HEAD_END);
  if (a < 0 || b < a) return html;
  return html.slice(0, a) + HEAD_START + '\n' + headTags(st, origin) + '\n' + html.slice(b);
}

function redirect(to, status, cache) {
  return new Response(null, { status: status || 302, headers: { Location: to, 'Cache-Control': cache || 'no-store' } });
}
const htmlHeaders = cache => ({ 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': cache, 'X-Content-Type-Options': 'nosniff' });

// GET /stories
export async function hub({ request, next }) {
  const url = new URL(request.url);
  // the project list used to live here: its links go on to /projects, parameters and all
  if (isListQuery(url.search)) return redirect('/projects' + url.search, 301, 'public, max-age=86400');
  return next();
}

async function template(env, origin) {
  if (!env.ASSETS) return null;
  const r = await env.ASSETS.fetch(new URL('/story', origin));
  return r.ok ? r.text() : null;
}
export async function readStory(env, id) {
  if (!env.FEED) return null;
  try { const st = await env.FEED.get('pubstory:' + id, 'json'); return st && st.title ? Object.assign({}, st, { id: String(st.id || id) }) : null; } catch (e) { return null; }
}

// GET /stories/<id>-<slug>
export async function storyPage({ request, env, params }) {
  const url = new URL(request.url), seg = String((params && params.id) || '');
  const m = ID_SEG.exec(seg);
  if (!m || (Array.isArray(params.id) && params.id.length !== 1)) return redirect('/stories', 302, 'public, max-age=300');
  const st = await readStory(env, m[1]);
  if (st) {
    const want = storyPath(st);
    if (url.pathname !== want) return redirect(want + url.search, 301, 'public, max-age=300');
  }
  const html = await template(env, url.origin);
  if (!html) return st ? new Response('Story page unavailable', { status: 503 }) : redirect('/stories', 302);
  return new Response(fillTemplate(html, st, url.origin), { status: st ? 200 : 404, headers: htmlHeaders(st ? 'public, max-age=300' : 'public, max-age=60') });
}
