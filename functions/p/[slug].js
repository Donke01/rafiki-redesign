// GET /p/<project id>-<name>: a public share card for one project — the link a donor shares from their
// private page (never the private link itself). Link previews (WhatsApp, Facebook, X…) get the project
// photo, title and a one-line description. Only public feed fields are used, plus a story moment an admin
// approved for the website; no donor names, costs or notes. Not indexed by search engines unless the
// Cloudflare variable PUBLIC_CARDS_INDEX is "on".
import { getFeed } from '../../lib/feed-cache.js';
import { getJson } from '../../lib/pin-store.js';
import { slug } from '../../lib/donor.js';

const esc = s => String(s == null ? '' : s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
const num = n => Math.round(Number(n) || 0).toLocaleString('en-US');
const WATER = { 'Borehole well': 1, 'Water collection': 1 };
const NOUN = { 'Borehole well': 'A borehole well', 'Water collection': 'A rainwater collection project', School: 'A school project', Health: 'A health project', Orphanage: 'A children’s home project', Farming: 'A farming project', Training: 'A training project', Nutrition: 'A nutrition project', Community: 'A community project' };

export function describe(p) {
  const where = [p.area && p.area !== p.title ? p.area : '', p.country].filter(Boolean).join(', ');
  const s = (NOUN[p.type] || 'A project') + (where ? ' in ' + where : '');
  if (p.status === 'Completed') return s + (p.year ? ', finished ' + p.year : '') + (Number(p.people) > 0 ? ', serving about ' + num(p.people) + ' people' : '') + '.';
  if (p.status === 'Under construction') return s + ', being built now' + (Number(p.people) > 0 ? ' for about ' + num(p.people) + ' people' : '') + '.';
  return s + ', looking for funding' + (Number(p.people) > 0 ? ' to serve about ' + num(p.people) + ' people' : '') + '.';
}

export function cardHtml(p, origin, story, index) {
  const path = '/p/' + p.id + '-' + slug(p.title), url = origin + path;
  const photo = (p.photos || [])[0], img = photo ? origin + '/api/photo/' + encodeURIComponent(photo) + '?w=1600' : origin + '/img/og/g-pump.jpg';
  const title = p.title + (p.country ? ', ' + p.country : '') + ' — RAFIKI Outreach', desc = describe(p);
  const gps = Array.isArray(p.gps) ? p.gps : null;
  const facts = [['Type', p.type], ['Where', [p.area, p.country].filter(Boolean).join(', ')], ['Status', p.status === 'Upcoming' ? 'Looking for funding' : p.status], p.year ? ['Year', p.year] : null, Number(p.people) > 0 ? ['People served', 'about ' + num(p.people)] : null, p.depth ? ['Well depth', p.depth + ' m'] : null].filter(Boolean);
  return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${esc(title)}</title>
<meta name="description" content="${esc(desc)}">
${index ? `<link rel="canonical" href="${esc(url)}">` : '<meta name="robots" content="noindex">'}
<meta property="og:type" content="article">
<meta property="og:site_name" content="RAFIKI Outreach">
<meta property="og:title" content="${esc(title)}">
<meta property="og:description" content="${esc(desc)}">
<meta property="og:image" content="${esc(img)}">
<meta property="og:image:alt" content="${esc(photo ? 'Photo of ' + p.title : 'A RAFIKI Outreach water point')}">
<meta property="og:url" content="${esc(url)}">
<meta name="twitter:card" content="summary_large_image">
<meta name="twitter:title" content="${esc(title)}">
<meta name="twitter:description" content="${esc(desc)}">
<meta name="twitter:image" content="${esc(img)}">
<link rel="icon" href="/favicon.svg" type="image/svg+xml">
<meta name="theme-color" content="#098BE1">
<link rel="preconnect" href="https://fonts.googleapis.com"><link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link href="https://fonts.googleapis.com/css2?family=Montserrat:wght@400;600;700;800&display=swap" rel="stylesheet">
<style>
:root{--blue:#0A78C2;--ink:#04263F;--text:#141618;--muted:#5A6066;--bg:#FAF8F3;--card:#FFFFFF;--line:#E3E6E9}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--text);font:16px/1.55 Montserrat,system-ui,sans-serif}
header{display:flex;align-items:center;justify-content:space-between;padding:14px 16px;max-width:860px;margin:0 auto}header img{height:30px;display:block}
header a.give{background:var(--blue);color:#fff;text-decoration:none;font-weight:700;border-radius:999px;padding:10px 18px;min-height:44px;display:inline-flex;align-items:center}
main{max-width:860px;margin:0 auto;padding:0 16px 40px}
.hero{border-radius:28px;overflow:hidden;background:#0A78C2;color:#fff;position:relative;min-height:300px;display:flex;align-items:flex-end}
.hero img{position:absolute;inset:0;width:100%;height:100%;object-fit:cover}.hero::after{content:"";position:absolute;inset:0;background:linear-gradient(180deg,rgba(4,38,63,0) 30%,rgba(4,38,63,.85))}
.hero div{position:relative;z-index:1;padding:22px}.kick{display:inline-block;background:rgba(255,255,255,.2);border-radius:999px;padding:4px 12px;font-size:12px;font-weight:700;letter-spacing:.04em;text-transform:uppercase}
h1{font-size:clamp(28px,6vw,46px);line-height:1.05;letter-spacing:-.03em;margin:10px 0 6px;font-weight:800}.hero p{margin:0;color:#E3F1FC}
.facts{display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));gap:10px;margin:16px 0}.facts div{background:var(--card);border:1px solid var(--line);border-radius:18px;padding:12px 14px}
.facts span{display:block;font-size:12px;font-weight:700;color:var(--muted);text-transform:uppercase;letter-spacing:.04em}.facts b{font-size:17px;color:var(--ink)}
blockquote{margin:16px 0;background:#E3F1FC;border-radius:22px;padding:18px 20px;font-size:18px;font-weight:600;color:var(--ink)}blockquote cite{display:block;font-size:14px;font-style:normal;color:var(--muted);margin-top:6px}
.acts{display:flex;flex-wrap:wrap;gap:10px;margin-top:18px}.acts a{flex:1 1 200px;text-align:center;border-radius:999px;padding:13px 20px;font-weight:700;text-decoration:none;min-height:50px}
.acts .p{background:var(--blue);color:#fff}.acts .l{background:#fff;color:var(--ink);border:1px solid #C9D1D8}
footer{text-align:center;color:var(--muted);font-size:13px;padding:24px 16px}
</style>
</head>
<body>
<header><a href="/" aria-label="RAFIKI Outreach home"><img src="/img/logo-dark-bold.png" alt="RAFIKI Outreach" width="88" height="30"></a><a class="give" href="/donate">Give</a></header>
<main>
<section class="hero">${photo ? `<img src="/api/photo/${esc(encodeURIComponent(photo))}?w=1600" alt="Photo of ${esc(p.title)}">` : ''}<div><span class="kick">${esc(WATER[p.type] ? 'Clean water' : 'Beyond the well')}</span><h1>${esc(p.title)}</h1><p>${esc(desc)}</p></div></section>
<div class="facts">${facts.map(f => `<div><span>${esc(f[0])}</span><b>${esc(f[1])}</b></div>`).join('')}</div>
${story ? `<blockquote>“${esc(story.quote)}”${story.who ? `<cite>— ${esc(story.who)}</cite>` : ''}</blockquote>` : ''}
<div class="acts"><a class="p" href="/donate">Fund the next project</a>${gps ? `<a class="l" href="https://www.google.com/maps/search/?api=1&amp;query=${gps[0]},${gps[1]}" rel="noopener noreferrer" target="_blank">See it on a map</a>` : ''}<a class="l" href="/stories?project=${esc(p.id)}">More RAFIKI stories</a></div>
</main>
<footer>RAFIKI Outreach · 501(c)(3) Nonprofit Organization · Providing clean water and community development in East Africa</footer>
</body>
</html>
`;
}

export async function onRequestGet({ env, params, request, waitUntil }) {
  const m = /^(\d{1,20})(?:-([a-z0-9-]*))?$/.exec(String((params && params.slug) || ''));
  if (!m) return Response.redirect(new URL('/projects?view=all', request.url).toString(), 302);
  let feed;
  try { feed = await getFeed(env, waitUntil); } catch (e) { return Response.redirect(new URL('/projects?project=' + m[1], request.url).toString(), 302); }
  const p = ((feed && feed.projects) || []).find(x => String(x.id) === m[1]);
  if (!p) return Response.redirect(new URL('/projects?view=all', request.url).toString(), 302);
  if ((m[2] || '') !== slug(p.title)) return Response.redirect(new URL('/p/' + p.id + '-' + slug(p.title), request.url).toString(), 301);
  const stories = (await getJson(env, 'pinstories', [])).filter(s => String(s.id) === m[1]);
  // an approved story from the Stories tab first (its quote), else the latest approved story moment
  const story = p.story && p.story.quote ? { quote: p.story.quote.replace(/^[“"]|[”"]$/g, ''), who: p.story.quoteBy || '' } : stories.length ? { quote: stories[stories.length - 1].quote, who: stories[stories.length - 1].who } : null;
  const index = String(env.PUBLIC_CARDS_INDEX || '').toLowerCase() === 'on';
  return new Response(cardHtml(p, new URL(request.url).origin, story, index), {
    headers: { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'public, max-age=300', 'X-Content-Type-Options': 'nosniff', ...(index ? {} : { 'X-Robots-Tag': 'noindex' }) }
  });
}
