// GET /project/<id>: the share link for one project (water or Beyond the well). Link previews (WhatsApp,
// Facebook, X, Slack...) read the title, description and photo from this small page;
// people are sent straight on to the project in the project list, /projects?project=<id>.
// The project comes from the same cached monday.com feed as /api/projects
// (lib/feed-cache.js, bindings FEED and MONDAY_API_TOKEN). Only public feed fields are
// used, and no project cost except `needed` on projects still looking for funding.
import { getFeed } from '../../lib/feed-cache.js';

const SITE = 'RAFIKI Outreach';
const FALLBACK_IMAGE = '/img/w-field3.jpg'; // the og:image of the project list
const WATER = { 'Borehole well': true, 'Water collection': true };
// Beyond the well: how each group is named in a description ("School project in Ndalat, Kenya, ...")
const GROUP = { health: 'Health project', school: 'School project', children: "Children's home project", livelihoods: 'Livelihoods project', community: 'Community project' };
const CAT_OF_TYPE = { Health: 'health', School: 'school', Orphanage: 'children', Farming: 'livelihoods' };
const isWater = p => p.cat ? p.cat === 'water' : !!(p.water || WATER[p.type]);
const catOf = p => (p.cat && (p.cat === 'water' || GROUP[p.cat])) ? p.cat : (isWater(p) ? 'water' : CAT_OF_TYPE[p.type] || 'community');

const esc = s => String(s == null ? '' : s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
const num = n => Math.round(Number(n) || 0).toLocaleString('en-US');

function redirect(to, cache) {
  return new Response(null, { status: 302, headers: { Location: to, 'Cache-Control': cache || 'no-store' } });
}

// e.g. "Borehole well in Sigowet, Kenya, finished 2026, serving about 5,000 people."
function describe(p) {
  const area = p.area && p.area !== p.title ? p.area : '';
  const where = [area, p.country].filter(Boolean).join(', ');
  const people = Number(p.people) > 0 ? num(p.people) : '';
  const parts = [];
  const cat = catOf(p);
  if (cat !== 'water') {
    // e.g. "School project in Ndalat, Kenya, under construction." / "Health project in Kapsaret, Kenya, finished 2025."
    parts.push(GROUP[cat] + (where ? ' in ' + where : ''));
    if (p.status === 'Completed') {
      parts.push(p.year ? 'finished ' + p.year : 'completed');
      if (people) parts.push('serving about ' + people + ' people');
    } else if (p.status === 'Under construction') {
      parts.push('under construction');
      if (people) parts.push('expected to serve about ' + people + ' people');
    } else {
      parts.push('looking for funding');
      if (people) parts.push('expected to serve about ' + people + ' people');
      if (Number(p.needed) > 0) parts.push('$' + num(p.needed) + ' still to raise');
    }
    return parts.join(', ') + '.';
  }
  const type = p.type || 'Water project';
  if (p.status === 'Completed') {
    parts.push(type + (where ? ' in ' + where : ''));
    if (p.year) parts.push('finished ' + p.year);
    if (people) parts.push('serving about ' + people + ' people');
  } else if (p.status === 'Under construction') {
    parts.push(type + ' under construction' + (where ? ' in ' + where : ''));
    if (people) parts.push('expected to serve about ' + people + ' people');
  } else {
    parts.push(type + ' looking for funding' + (where ? ' in ' + where : ''));
    if (people) parts.push('expected to serve about ' + people + ' people');
    if (Number(p.needed) > 0) parts.push('$' + num(p.needed) + ' still to raise');
  }
  return parts.join(', ') + '.';
}

function page(p, origin) {
  const id = String(p.id);
  const target = '/projects?project=' + encodeURIComponent(id);
  const title = p.title + (p.country ? ', ' + p.country : '') + ' — ' + SITE;
  const desc = describe(p);
  const photo = (p.photos || [])[0];
  const image = origin + (photo ? '/api/photo/' + encodeURIComponent(photo) + '?w=1600' : FALLBACK_IMAGE);
  const alt = photo ? 'Photo from ' + p.title : (isWater(p) ? 'A RAFIKI Outreach water project' : 'A RAFIKI Outreach project');
  const share = origin + '/project/' + encodeURIComponent(id);
  return `<!doctype html>
<html lang="en-GB">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${esc(title)}</title>
<meta name="description" content="${esc(desc)}">
<link rel="canonical" href="${esc(origin + target)}">
<meta property="og:type" content="website">
<meta property="og:site_name" content="${esc(SITE)}">
<meta property="og:title" content="${esc(title)}">
<meta property="og:description" content="${esc(desc)}">
<meta property="og:image" content="${esc(image)}">
<meta property="og:image:alt" content="${esc(alt)}">
<meta property="og:url" content="${esc(share)}">
<meta name="twitter:card" content="summary_large_image">
<meta name="twitter:title" content="${esc(title)}">
<meta name="twitter:description" content="${esc(desc)}">
<meta name="twitter:image" content="${esc(image)}">
<meta http-equiv="refresh" content="0; url=${esc(target)}">
<link rel="icon" href="/favicon.svg" type="image/svg+xml">
<style>body{margin:0;min-height:100vh;display:grid;place-items:center;background:#FAF8F3;color:#0A0A0A;font:16px/1.6 system-ui,-apple-system,"Segoe UI",sans-serif;text-align:center;padding:24px;box-sizing:border-box}a{color:#076DB1;font-weight:700}</style>
<script>location.replace(${JSON.stringify(target).replace(/</g, '\\u003c')});</script>
</head>
<body>
<main>
<h1 style="font-size:22px;margin:0 0 8px">${esc(p.title)}</h1>
<p style="margin:0 0 12px">${esc(desc)}</p>
<p style="margin:0"><a href="${esc(target)}">See this project on RAFIKI Outreach</a></p>
</main>
</body>
</html>
`;
}

export async function onRequestGet({ env, params, request, waitUntil }) {
  const id = String((params && params.id) || '');
  if (!/^\d{1,20}$/.test(id)) return redirect('/projects?view=all', 'public, max-age=300');
  const target = '/projects?project=' + id;
  // no feed set up, or monday.com unreachable with nothing cached: let the project list deal with it
  if (!env.MONDAY_API_TOKEN) return redirect(target);
  let feed;
  try { feed = await getFeed(env, waitUntil); } catch (e) {
    console.error('project page feed:', e && e.message);
    return redirect(target);
  }
  // the same projects the project list shows: every project in the feed, water and Beyond the well
  const p = ((feed && feed.projects) || []).find(x => String(x.id) === id);
  if (!p) return redirect('/projects?view=all');
  const origin = new URL(request.url).origin;
  return new Response(page(p, origin), {
    status: 200,
    headers: {
      'Content-Type': 'text/html; charset=utf-8',
      'Cache-Control': 'public, max-age=300',
      'X-Content-Type-Options': 'nosniff'
    }
  });
}
