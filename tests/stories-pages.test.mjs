// The website Stories routes: /stories (hub; old project-list links go on to /projects), /stories/<id>-<slug>
// (one story with its share tags filled in on the server) and /projects?<filters> (the list of every project).
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { onRequestGet as hub } from '../functions/stories/index.js';
import { onRequestGet as story } from '../functions/stories/[id].js';
import { onRequestGet as projects } from '../functions/projects.js';
import { headTags, slugOf, storyPath } from '../lib/stories-page.js';

const fix = JSON.parse(readFileSync(new URL('../qa/web/stories-fixture.json', import.meta.url)));
const TEMPLATE = readFileSync(new URL('../story.html', import.meta.url), 'utf8');
const LIST = '<!doctype html><title>All projects</title>';
const assets = { fetch: async u => { const p = new URL(u).pathname; return p === '/story' ? new Response(TEMPLATE) : p === '/project-list' ? new Response(LIST, { headers: { 'Content-Type': 'text/html' } }) : new Response('nf', { status: 404 }); } };
const kv = extra => ({ get: async (k, t) => { const v = Object.assign({}, Object.fromEntries(Object.entries(fix.stories).map(([id, s]) => ['pubstory:' + id, s])), extra || {})[k]; return v == null ? null : (t === 'json' ? v : JSON.stringify(v)); } });
const env = (x) => ({ FEED: kv(x), ASSETS: assets });
const NEXT = new Response('static', { status: 200 });
const run = (fn, url, params, e) => fn({ request: new Request('https://example.org' + url), env: e || env(), params: params || {}, next: async () => NEXT });

test('hub: old project-list links go on to /projects with the same parameters', async () => {
  for (const q of ['?cat=water&status=completed', '?kind=borehole&status=completed', '?project=11030694394', '?country=tz&status=funding', '?q=well']) {
    const r = await run(hub, '/stories' + q);
    assert.equal(r.status, 301, q);
    assert.equal(r.headers.get('Location'), '/projects' + q, q);
  }
});

test('hub: no parameters, or its own filters, is the stories page', async () => {
  for (const q of ['', '?where=ke', '?topic=water&where=tz', '?live']) assert.equal(await run(hub, '/stories' + q), NEXT, q);
});

test('projects: list parameters are answered with the project list, plain /projects is the overview', async () => {
  const r = await run(projects, '/projects?cat=water&status=completed');
  assert.equal(r.status, 200);
  assert.match(r.headers.get('Content-Type'), /text\/html/);
  assert.equal(await r.text(), LIST);
  assert.equal(await (await run(projects, '/projects?view=all')).text(), LIST);
  assert.equal(await run(projects, '/projects'), NEXT);
  assert.equal(await run(projects, '/projects?live'), NEXT);
});

test('story: published story gets its title, description, share photo and inlined data', async () => {
  const r = await run(story, '/stories/9100000001-water-at-the-school-gate', { id: '9100000001-water-at-the-school-gate' });
  assert.equal(r.status, 200);
  assert.match(r.headers.get('Cache-Control'), /public, max-age=300/);
  const h = await r.text();
  assert.match(h, /<title>Water at the school gate · Kapsaret, Kenya — RAFIKI Outreach<\/title>/);
  assert.match(h, /<meta property="og:image" content="https:\/\/example.org\/api\/stories\/photo\?id=9100000001&amp;asset=5004&amp;w=1200">/);
  assert.match(h, /<meta name="description" content="How Kapsaret&#39;s 1,500 pupils stopped walking to the river before lessons.">/);
  assert.match(h, /<link rel="canonical" href="https:\/\/example.org\/stories\/9100000001-water-at-the-school-gate">/);
  assert.match(h, /<meta property="og:type" content="article">/);
  assert.match(h, /"@type":"Article"/);
  assert.doesNotMatch(h, /noindex/);
  const m = /window\.__STORY=(.*?);<\/script>/.exec(h);
  assert.ok(m);
  assert.equal(JSON.parse(m[1]).title, 'Water at the school gate');
  assert.equal((h.match(/<title>/g) || []).length, 1, 'one title only');
});

test('story: a wrong or missing slug goes to the right address', async () => {
  for (const seg of ['9100000001', '9100000001-old-name']) {
    const r = await run(story, '/stories/' + seg + '?live', { id: seg });
    assert.equal(r.status, 301, seg);
    assert.equal(r.headers.get('Location'), '/stories/9100000001-water-at-the-school-gate?live', seg);
  }
});

test('story: not published (or withdrawn) is a 404 page that says so and is not indexed', async () => {
  const r = await run(story, '/stories/42-gone', { id: '42-gone' });
  assert.equal(r.status, 404);
  const h = await r.text();
  assert.match(h, /window\.__STORY=null;/);
  assert.match(h, /noindex/);
  assert.match(h, /<title>Story not found — RAFIKI Outreach<\/title>/);
});

test('story: bad addresses go to the hub', async () => {
  for (const seg of ['abc', '12<script>', '../x']) {
    const r = await run(story, '/stories/' + encodeURIComponent(seg), { id: seg });
    assert.equal(r.status, 302, seg);
    assert.equal(r.headers.get('Location'), '/stories', seg);
  }
});

test('story: text from the story cannot break out of the head', async () => {
  const evil = Object.assign({}, fix.stories['9100000001'], { id: '77', slug: 'x', title: 'A </script><script>alert(1)</script> "quoted"', dek: '<img src=x onerror=alert(1)>' });
  const r = await run(story, '/stories/77-x', { id: '77-x' }, env({ 'pubstory:77': evil }));
  const h = await r.text();
  assert.equal(r.status, 200);
  assert.doesNotMatch(h, /<script>alert/);
  assert.doesNotMatch(h, /<img src=x/);
  assert.match(h, /&lt;\/script&gt;/);
  assert.match(h, /\\u003c\/script\\u003e/);
});

test('story: no hero photo falls back to the site photo; helpers', () => {
  const h = headTags(Object.assign({}, fix.stories['9100000003'], { hero: null }), 'https://example.org');
  assert.match(h, /og:image" content="https:\/\/example.org\/img\/og\/w-field3.jpg"/);
  assert.equal(slugOf('Children’s home in Busia!'), 'children-s-home-in-busia');
  assert.equal(storyPath({ id: 5, title: 'Water, at last' }), '/stories/5-water-at-last');
});
