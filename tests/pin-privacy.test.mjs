// node --test tests/pin-privacy.test.mjs — the RAFIKI Pin privacy policy (pin-privacy.html) that Google Play links to:
// it opens on the app's own address without signing in (the middleware sends other website pages to /pin), it is
// shipped by build:pages, and the apps link to it.
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { onRequest as middleware } from '../functions/_middleware.js';

const html = fs.readFileSync(new URL('../pin-privacy.html', import.meta.url), 'utf8');
// Cloudflare's HTMLRewriter isn't in Node: a stand-in that passes the page through (the app's address adds nothing)
if (!globalThis.HTMLRewriter) globalThis.HTMLRewriter = class { on() { return this; } transform(r) { return r; } };
const page = () => new Response(html, { headers: { 'Content-Type': 'text/html; charset=utf-8' } });

test('pin.rafikioutreach.org/pin-privacy is served as it is, with no redirect and no sign-in', async () => {
  for (const path of ['/pin-privacy', '/pin-privacy.html']) {
    let served = false;
    const res = await middleware({ request: new Request('https://pin.rafikioutreach.org' + path), env: {}, next: async () => { served = true; return page(); } });
    assert.equal(served, true, path + ' reaches the page');
    assert.equal(res.status, 200, path);
    assert.match(await res.text(), /RAFIKI Pin privacy policy/);
  }
  // for comparison: a website page on the app's address goes to the app
  const other = await middleware({ request: new Request('https://pin.rafikioutreach.org/privacy-policy'), env: {}, next: async () => page() });
  assert.equal(other.status, 302);
});

test('the policy covers what Google Play asks for, marks itself as a draft, and holds no secrets', () => {
  assert.match(html, /DRAFT for the owner to review/);
  assert.match(html, /not legal advice/);
  for (const part of ['What we collect', 'Where it is stored', 'Services we use', 'How long we keep it', 'deleting your data', 'not meant for children', 'Donor mode', 'info@rafikioutreach.org'])
    assert.ok(html.includes(part), part);
  assert.doesNotMatch(html, /\b(sk|pk|re)_[A-Za-z0-9]{16,}|AIza[0-9A-Za-z_-]{20,}|\bPIN[:\s]+\d{4}\b/);
});

test('build:pages ships it, and the web and Android apps link to it', () => {
  const pkg = JSON.parse(fs.readFileSync(new URL('../package.json', import.meta.url), 'utf8'));
  assert.match(pkg.scripts['build:pages'], /\*\.html/);
  assert.match(fs.readFileSync(new URL('../pin.html', import.meta.url), 'utf8'), /'\/pin-privacy'/);
  assert.match(fs.readFileSync(new URL('../apps/android-native/app/src/main/java/org/rafikioutreach/pin/ui/Settings.kt', import.meta.url), 'utf8'), /https:\/\/pin\.rafikioutreach\.org\/pin-privacy/);
});
