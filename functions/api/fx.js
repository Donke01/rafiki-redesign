// /api/fx: exchange rates for showing amounts in the visitor's own currency (website, donor pages and the apps).
//   GET /api/fx → { base: "USD", at: "2026-10-26", rates: { USD: 1, KES: 129.2, … }, country: "KE", fallback: false }
// Rates come once a day from open.er-api.com (free, no key) and are kept in Cloudflare's cache, so a page never
// waits on them. If that service can't be reached, the last good rates are used, and failing that the rough rates
// below (marked fallback: true, so the pages say "approximate"). `country` is where Cloudflare sees the visitor,
// which the pages use to pick the starting currency (USA → USD, Kenya → KES …); nothing about the visitor is stored.

const CURRENCIES = ['USD', 'KES', 'UGX', 'TZS', 'MWK', 'GBP', 'EUR', 'CAD', 'AUD'];
// rough rates (1 US dollar), used only when no live rates have ever been fetched
const FALLBACK = { USD: 1, KES: 129, UGX: 3650, TZS: 2600, MWK: 1735, GBP: 0.77, EUR: 0.92, CAD: 1.38, AUD: 1.52 };
const SOURCE = 'https://open.er-api.com/v6/latest/USD';
const DAY = 24 * 3600;

function out(body, maxAge) {
  return new Response(JSON.stringify(body), {
    headers: {
      'Content-Type': 'application/json; charset=utf-8',
      // the rates are the same for everyone, but `country` is per visitor, so browsers may keep it, shared caches not
      'Cache-Control': 'private, max-age=' + (maxAge || 3600),
      'X-Content-Type-Options': 'nosniff',
      'Access-Control-Allow-Origin': '*',
    },
  });
}

async function live(ctx) {
  const cache = caches.default;
  const key = new Request('https://fx.rafiki.internal/usd-v1');
  const hit = await cache.match(key);
  if (hit) {
    const j = await hit.json().catch(() => null);
    if (j && j.rates && Date.now() - (j.got || 0) < DAY * 1000) return j;
    // older than a day: try to refresh, keep the old ones if that fails
    const fresh = await fetchRates();
    if (fresh) { put(cache, key, fresh, ctx); return fresh; }
    return j && j.rates ? j : null;
  }
  const fresh = await fetchRates();
  if (fresh) put(cache, key, fresh, ctx);
  return fresh;
}

function put(cache, key, j, ctx) {
  // kept for a week; refreshed once it's a day old
  const p = cache.put(key, new Response(JSON.stringify(j), { headers: { 'Content-Type': 'application/json', 'Cache-Control': 'public, max-age=' + 7 * DAY } }));
  if (ctx && ctx.waitUntil) ctx.waitUntil(p);
}

async function fetchRates() {
  try {
    const r = await fetch(SOURCE, { headers: { Accept: 'application/json' }, cf: { cacheTtl: 3600 } });
    if (!r.ok) return null;
    const j = await r.json();
    if (!j || j.result !== 'success' || !j.rates) return null;
    const rates = {};
    for (const c of CURRENCIES) { const v = Number(j.rates[c]); if (v > 0) rates[c] = v; }
    if (Object.keys(rates).length < CURRENCIES.length - 2) return null;
    const at = j.time_last_update_unix ? new Date(j.time_last_update_unix * 1000).toISOString().slice(0, 10) : new Date().toISOString().slice(0, 10);
    return { rates, at, got: Date.now() };
  } catch (e) {
    return null;
  }
}

export async function onRequestGet(ctx) {
  const { request } = ctx;
  const country = String((request.cf && request.cf.country) || request.headers.get('CF-IPCountry') || '').toUpperCase().replace(/[^A-Z]/g, '').slice(0, 2);
  const j = await live(ctx);
  if (j) return out({ base: 'USD', at: j.at, rates: Object.assign({}, FALLBACK, j.rates), country, fallback: false }, 6 * 3600);
  return out({ base: 'USD', at: '', rates: FALLBACK, country, fallback: true }, 600);
}
