// /api/fx: exchange rates for Settings › Currency (web app, Android, iPhone), with a fake cache and a fake rates service.
// Run: node --test tests/*.test.mjs   (Node 22+; no Cloudflare needed)
import { test } from 'node:test';
import assert from 'node:assert/strict';
import * as fx from '../functions/api/fx.js';

function fakeCache() {
  const m = new Map();
  return { m, match: async (k) => { const v = m.get(k.url); return v ? new Response(v) : undefined; }, put: async (k, r) => { m.set(k.url, await r.text()); } };
}
function req(country) { const r = new Request('https://x/api/fx'); Object.defineProperty(r, 'cf', { value: country ? { country } : undefined }); return r; }
const LIVE = { result: 'success', time_last_update_unix: 1793000000, rates: { USD: 1, KES: 129.5, UGX: 3700, TZS: 2650, MWK: 1740, GBP: 0.76, EUR: 0.91, CAD: 1.39, AUD: 1.51, JPY: 150 } };

test('live rates, the visitor country, and only our currencies', async () => {
  globalThis.caches = { default: fakeCache() };
  let calls = 0;
  globalThis.fetch = async () => { calls++; return new Response(JSON.stringify(LIVE)); };
  const j = await (await fx.onRequestGet({ request: req('US'), waitUntil() {} })).json();
  assert.equal(j.country, 'US'); assert.equal(j.fallback, false); assert.equal(j.rates.KES, 129.5); assert.equal(j.rates.JPY, undefined);
  assert.match(j.at, /^\d{4}-\d{2}-\d{2}$/);
  // the second visitor gets the kept rates, no new call
  const k = await (await fx.onRequestGet({ request: req('KE'), waitUntil() {} })).json();
  assert.equal(k.country, 'KE'); assert.equal(k.rates.KES, 129.5); assert.equal(calls, 1);
});

test('rates service down: rough rates, marked as fallback', async () => {
  globalThis.caches = { default: fakeCache() };
  globalThis.fetch = async () => { throw new Error('offline'); };
  const r = await fx.onRequestGet({ request: req('UG'), waitUntil() {} });
  const j = await r.json();
  assert.equal(j.fallback, true); assert.ok(j.rates.UGX > 1000); assert.equal(j.country, 'UG');
  assert.match(r.headers.get('Cache-Control'), /private/);
});

test('a bad answer from the rates service is not kept', async () => {
  globalThis.caches = { default: fakeCache() };
  globalThis.fetch = async () => new Response(JSON.stringify({ result: 'error' }));
  const j = await (await fx.onRequestGet({ request: req(''), waitUntil() {} })).json();
  assert.equal(j.fallback, true); assert.equal(j.country, '');
  assert.equal(globalThis.caches.default.m.size, 0);
});
