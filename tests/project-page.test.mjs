// Checks functions/project/[id].js (the /project/<id> share page) with a fake FEED KV.
// Run: node --test tests/   (Node 22+; no Cloudflare needed)
// Set FEED_FIXTURE=/path/to/feed.json to also run it against a full copy of the live feed.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const FIXTURE = {
  updated: '2026-10-16T10:00:00Z',
  summary: { total: {}, countries: {} },
  projects: [
    { id: '11030694394', title: "ACK St John's Sigowet Church", country: 'Kenya', ck: 'ke', area: 'Sigowet', type: 'Borehole well', water: true, pump: 'Electric', people: 5000, year: 2026, status: 'Completed', photos: ['3210378757', '3210378761'] },
    { id: '18291679383', title: 'Malawi Project 1', country: 'Malawi', ck: 'mw', area: '', type: 'Borehole well', water: true, pump: '', people: 0, year: 2025, status: 'Upcoming', photos: [], needed: 5000 },
    { id: '222', title: 'Tank <b>"Mama"</b> & sons', country: 'Tanzania', ck: 'tz', area: "O'Brien <script>", type: 'Water collection', water: true, people: 1200, year: 2025, status: 'Under construction', photos: [] },
    { id: '333', title: 'A school', country: 'Kenya', ck: 'ke', area: 'Somewhere', type: 'School', water: false, people: 400, year: 2024, status: 'Completed', photos: [] }
  ]
};

const feed = process.env.FEED_FIXTURE ? JSON.parse(readFileSync(process.env.FEED_FIXTURE, 'utf8')) : FIXTURE;
// lib/feed-cache.js reads the KV key 'feed:v4' as { at, feed }; a fresh `at` means no monday.com call
const env = { MONDAY_API_TOKEN: 'test', FEED: { get: async () => ({ at: Date.now(), feed }), put: async () => {} } };
const { onRequestGet } = await import('../functions/project/[id].js');
const run = (id, e = env) => onRequestGet({ env: e, params: { id }, request: new Request('https://example.org/project/' + id), waitUntil: () => {} });
const meta = (html, attr, name) => { const m = new RegExp('<meta ' + attr + '="' + name + '" content="([^"]*)"').exec(html); return m && m[1]; };

test('known completed project: preview tags and redirect to /stories', async () => {
  const r = await run('11030694394');
  assert.equal(r.status, 200);
  assert.match(r.headers.get('Content-Type'), /text\/html/);
  assert.match(r.headers.get('Cache-Control'), /max-age=300/);
  const h = await r.text();
  assert.match(h, /<title>ACK St John&#39;s Sigowet Church, Kenya — Rafiki Outreach<\/title>/);
  if (!process.env.FEED_FIXTURE) assert.equal(meta(h, 'name', 'description'), 'Borehole well in Sigowet, Kenya, finished 2026, serving about 5,000 people.');
  assert.equal(meta(h, 'property', 'og:description'), meta(h, 'name', 'description'));
  assert.equal(meta(h, 'property', 'og:image'), 'https://example.org/api/photo/3210378757?w=1600');
  assert.equal(meta(h, 'property', 'og:url'), 'https://example.org/project/11030694394');
  assert.equal(meta(h, 'name', 'twitter:card'), 'summary_large_image');
  assert.match(h, /<link rel="canonical" href="https:\/\/example.org\/stories\?project=11030694394">/);
  assert.match(h, /<meta http-equiv="refresh" content="0; url=\/stories\?project=11030694394">/);
  assert.match(h, /location\.replace\("\/stories\?project=11030694394"\)/);
  assert.match(h, /<a href="\/stories\?project=11030694394">/);
  assert.doesNotMatch(h, /\$\d/); // no cost on a completed project
});

test('upcoming project: shows the amount still needed, fallback image', async () => {
  const r = await run('18291679383');
  assert.equal(r.status, 200);
  const h = await r.text();
  assert.match(meta(h, 'name', 'description'), /looking for funding in Malawi/);
  assert.match(meta(h, 'name', 'description'), /\$5,000 still to raise/);
  assert.equal(meta(h, 'property', 'og:image'), 'https://example.org/img/w-field3.jpg');
});

test('escapes HTML from the feed', { skip: !!process.env.FEED_FIXTURE }, async () => {
  const h = await (await run('222')).text();
  assert.doesNotMatch(h, /<b>|<script>\s*$|O'Brien <script>/m);
  assert.match(h, /Tank &lt;b&gt;&quot;Mama&quot;&lt;\/b&gt; &amp; sons/);
  assert.match(meta(h, 'name', 'description'), /^Water collection under construction in O&#39;Brien &lt;script&gt;, Tanzania, expected to serve about 1,200 people\.$/);
});

test('unknown, non-water and malformed ids go to /stories', async () => {
  for (const id of ['999999', '333', 'abc', '1<2']) {
    const r = await run(id);
    assert.equal(r.status, 302, id);
    assert.equal(r.headers.get('Location'), '/stories', id);
  }
});

test('no feed configured: straight to the stories page for that project', async () => {
  const r = await run('11030694394', {});
  assert.equal(r.status, 302);
  assert.equal(r.headers.get('Location'), '/stories?project=11030694394');
});
