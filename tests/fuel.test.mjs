// Fuel: official price lists (lib/fuel-prices.js, lib/pdf-text.js) parsed from saved copies of the real pages and PDFs,
// the price for a trip (nearest town, office prices, fallbacks), and the consumption model (lib/fuel-model.js).
// The fixtures in tests/fixtures/fuel/ were downloaded on 26 Oct 2026 from the regulators' sites:
//   epra-pump-prices-2026-10-26.html.gz        https://www.epra.go.ke/pump-prices (the page cut after the newest period
//                                              and 3 rows of the one before; the full page is ~7 MB)
//   ewura-cap-prices-list-2026-10-26.html.gz   https://www.ewura.go.tz/publications/petroleum-price
//   ewura-cap-prices-2026-10-07-p1-3-4.pdf     the October 2026 cap prices notice, pages 1, 3 and 4 (images and embedded
//                                              font programs removed to keep it small; text, fonts' ToUnicode maps and widths kept)
//   mera-fuel-pump-prices-page-2026-10-26.html.gz, mera-fuel-pump-prices-2026-04.pdf   https://mera.mw/download/fuel-pump-prices/
// Run: node --test tests/*.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { gunzipSync } from 'node:zlib';
import { parseEpra, epraDone, parseEwuraList, parseEwura, parseMera, refreshCountry, refreshAll, priceFor, priceLabel, setOverride, pricesView, MAX_EPRA_BYTES } from '../lib/fuel-prices.js';
import { pdfText, textLines } from '../lib/pdf-text.js';
import { modelFor, realFromLogs, useFor, loadFactor, legLitres, CONSUMPTION, basisWords } from '../lib/fuel-model.js';
import { townPoint, nearestTown, countryOf } from '../lib/towns.js';

const F = new URL('./fixtures/fuel/', import.meta.url);
const gz = n => gunzipSync(readFileSync(new URL(n, F))).toString('utf8');
const bin = n => new Uint8Array(readFileSync(new URL(n, F)));
const EPRA = gz('epra-pump-prices-2026-10-26.html.gz');
const NOW = Date.parse('2026-10-26T09:00:00Z');

function kv(init = {}) {
  const m = new Map(Object.entries(init).map(([k, v]) => [k, JSON.stringify(v)]));
  return { m, get: async (k, t) => { const v = m.get(k); return v == null ? null : t === 'json' ? JSON.parse(v) : v; }, put: async (k, v) => { m.set(k, String(v)); }, delete: async k => { m.delete(k); } };
}
// a Response whose body arrives in 64 KB chunks, and that remembers how much was read
function streamed(text) {
  const bytes = new TextEncoder().encode(text); let sent = 0, cancelled = false;
  const body = new ReadableStream({ pull(c) { if (sent >= bytes.length) { c.close(); return; } const n = Math.min(65536, bytes.length - sent); c.enqueue(bytes.slice(sent, sent + n)); sent += n; }, cancel() { cancelled = true; } });
  const r = new Response(body, { status: 200, headers: { 'Content-Type': 'text/html' } });
  r.stats = () => ({ sent, cancelled });
  return r;
}
const FX = { result: 'success', time_last_update_unix: Date.parse('2026-10-26T00:00:00Z') / 1000, rates: { USD: 1, KES: 129.2, TZS: 2450, UGX: 3700, MWK: 1735, GBP: 0.77, EUR: 0.92, CAD: 1.38, AUD: 1.52 } };

// ---- Kenya: EPRA ----------------------------------------------------------------------------------------------
test('EPRA: the newest period, every town, Mombasa from the ticker, and the national price', () => {
  const r = parseEpra(EPRA);
  assert.equal(r.ok, true);
  assert.equal(r.from, '2026-10-15'); assert.equal(r.to, '2026-11-14');
  assert.equal(Object.keys(r.towns).length, 229);              // 228 table rows + Mombasa from the ticker
  assert.deepEqual(r.towns.Eldoret, { petrol: 226.51, diesel: 227.71, kerosene: 219.83 });
  assert.deepEqual(r.towns.Kitale, { petrol: 226.76, diesel: 227.96, kerosene: 220.08 });
  assert.equal(r.towns.Mombasa.petrol, 223.81); assert.equal(r.towns.Mombasa.diesel, 224.71);
  assert.equal(r.towns["Moi's Bridge"].petrol > 200, true);   // &#039; decoded
  assert.deepEqual(r.national, { town: 'Nairobi', petrol: 226.96, diesel: 227.76, kerosene: 219.87 });
  // the older period's rows (15 Sep–14 Oct) are not mixed in
  assert.equal(Object.values(r.towns).some(t => t.petrol === 214.03), false);
});
test('EPRA: a changed or empty page is refused, never guessed', () => {
  assert.equal(parseEpra('<html><body>Maintenance</body></html>').ok, false);
  assert.equal(parseEpra(EPRA.replace(/<td[^>]*views-field-field-super-pms[^>]*>[^<]*<\/td>/g, '<td>n/a</td>')).ok, false);
  const crazy = EPRA.replace(/(views-field-field-super-pms">\s*)226\.51/g, '$12265.1');   // a price far out of range is dropped, not stored
  assert.equal(parseEpra(crazy).towns.Iten, undefined);              // (Eldoret would come back from the ticker)
  assert.equal(parseEpra(crazy).towns.Eldoret.ticker, true);
});
test('EPRA: the 7 MB page is read as a stream and dropped after the newest period', async () => {
  const filler = '<tr><td>15-08-2024 </td><td>14-09-2024 </td><td>Old </td><td>190.00 </td><td>180.00 </td><td>170.00 </td></tr>\n';
  const big = EPRA.replace('</tbody>', filler.repeat(40000) + '</tbody>');   // ~4 MB more of older periods
  assert.equal(epraDone(EPRA.slice(0, 200000)), false);
  assert.equal(epraDone(EPRA), true);
  let res;
  const env = { FEED: kv() };
  const out = await refreshCountry(env, 'ke', { now: NOW, fetch: async () => (res = streamed(big)) });
  assert.equal(out.ok, true); assert.equal(out.towns, 229);
  assert.ok(res.stats().sent < 600000 && res.stats().cancelled, 'stopped early: ' + JSON.stringify(res.stats()));
  assert.ok(MAX_EPRA_BYTES >= 1e6);
  const rec = JSON.parse(env.FEED.m.get('fuelprice:ke'));
  assert.equal(rec.source, 'EPRA'); assert.equal(rec.from, '2026-10-15'); assert.equal(rec.url, 'https://www.epra.go.ke/pump-prices'); assert.ok(rec.fetchedAt);
});

// ---- Tanzania: EWURA -------------------------------------------------------------------------------------------
test('EWURA: the newest cap price notice from the publications page', () => {
  const list = parseEwuraList(gz('ewura-cap-prices-list-2026-10-26.html.gz'), 'https://www.ewura.go.tz/publications/petroleum-price');
  assert.ok(list.length >= 8);
  assert.match(list[0].url, /^https:\/\/www\.ewura\.go\.tz\/uploads\/documents\/en-1791322203-Petroleum%20Cap%20Prices%20for%20%20October%202026/);
  assert.ok(list.every(x => !/batch/i.test(x.name)));
});
test('EWURA: Table 3 of the PDF notice (subset fonts with ToUnicode maps)', async () => {
  const pages = await pdfText(bin('ewura-cap-prices-2026-10-07-p1-3-4.pdf'));
  assert.equal(pages.length, 3);
  const lines = pages.flatMap(textLines);
  assert.ok(lines.includes('1 Dar es Salaam 4,057 4,086 4,067'), lines.slice(0, 5).join(' | '));
  const r = parseEwura(lines);
  assert.equal(r.ok, true);
  assert.equal(r.from, '2026-10-07');
  assert.equal(Object.keys(r.towns).length, 92);                // rows 1–92 are on pages 3 and 4
  assert.deepEqual(r.towns['Dar es Salaam'], { petrol: 4057, diesel: 4086, kerosene: 4067 });
  assert.deepEqual(r.towns.Arusha, { petrol: 4186, diesel: 4215, kerosene: 4196 });
  assert.deepEqual(r.towns['Kilimanjaro (Moshi)'], { petrol: 4171, diesel: 4200, kerosene: 4181 });
  // Table 1 (ports, no row number) and Table 2 (wholesale) are not read as towns
  assert.equal(r.towns.Tanga, undefined);
});
test('EWURA: fetch the list, then the PDF; a PDF that is not one is a parse failure', async () => {
  const env = { FEED: kv() }, seen = [];
  const fetchF = async u => { seen.push(u); return /publications/.test(u) ? new Response(gz('ewura-cap-prices-list-2026-10-26.html.gz')) : new Response(bin('ewura-cap-prices-2026-10-07-p1-3-4.pdf')); };
  const r = await refreshCountry(env, 'tz', { now: NOW, fetch: fetchF });
  assert.equal(r.ok, true); assert.equal(r.from, '2026-10-07');
  assert.match(seen[1], /%20Cap%20Prices%20for%20%20October%202026/);
  const rec = JSON.parse(env.FEED.m.get('fuelprice:tz'));
  assert.equal(rec.currency, 'TZS'); assert.equal(rec.to, null); assert.match(rec.docUrl, /October%202026/);
  const bad = await refreshCountry(env, 'tz', { now: NOW, fetch: async u => (/publications/.test(u) ? new Response(gz('ewura-cap-prices-list-2026-10-26.html.gz')) : new Response('<html>not a pdf</html>')) });
  assert.equal(bad.ok, false); assert.equal(bad.reason, 'parse');
  assert.equal(JSON.parse(env.FEED.m.get('fuelprice:tz')).from, '2026-10-07', 'the good list is kept');
  assert.equal(JSON.parse(env.FEED.m.get('fuelprice:status')).tz.reason, 'parse');
});

// ---- Malawi: MERA ------------------------------------------------------------------------------------------------
test('MERA: the newest revision in the pump prices PDF', async () => {
  const lines = (await pdfText(bin('mera-fuel-pump-prices-2026-04.pdf'))).flatMap(textLines);
  const r = parseMera(lines);
  assert.equal(r.ok, true);
  assert.equal(r.from, '2026-04-01'); assert.equal(r.month, true);
  assert.deepEqual(r.national, { town: 'Malawi', petrol: 6672, diesel: 6687, kerosene: 5824 });
});

// ---- keeping lists: fallbacks and failures --------------------------------------------------------------------------
test('refresh: a failed or blocked fetch keeps the last list; an older period never replaces a newer one; Uganda has no source', async () => {
  const env = { FEED: kv() };
  await refreshCountry(env, 'ke', { now: NOW, fetch: async () => new Response(EPRA) });
  let r = await refreshCountry(env, 'ke', { now: NOW, fetch: async () => new Response('Forbidden', { status: 403 }) });
  assert.deepEqual([r.ok, r.reason], [false, 'blocked']);
  r = await refreshCountry(env, 'ke', { now: NOW, fetch: async () => { throw new Error('connection reset'); } });
  assert.deepEqual([r.ok, r.reason], [false, 'fetch']);
  const older = EPRA.replace(/15-10-2026/g, '15-06-2026').replace(/14-11-2026/g, '14-07-2026').replace(/15-09-2026/g, '15-05-2026').replace(/14-10-2026/g, '14-06-2026');
  r = await refreshCountry(env, 'ke', { now: NOW, fetch: async () => new Response(older) });
  assert.deepEqual([r.ok, r.reason], [false, 'stale']);
  assert.equal(JSON.parse(env.FEED.m.get('fuelprice:ke')).from, '2026-10-15');
  // a newer period moves the old one to fuelprice:prev:ke
  r = await refreshCountry(env, 'ke', { now: NOW, fetch: async () => new Response(EPRA.replace(/15-10-2026/g, '15-11-2026').replace(/14-11-2026/g, '14-12-2026')) });
  assert.equal(r.ok, true);
  assert.equal(JSON.parse(env.FEED.m.get('fuelprice:prev:ke')).from, '2026-10-15');
  const all = await refreshAll(env, { only: 'ug', now: NOW });
  assert.deepEqual(all.ug, { ok: false, reason: 'no-source' });
});

// ---- the price for a trip ----------------------------------------------------------------------------------------
async function envWithLists() {
  const env = { FEED: kv({ 'fx:usd': { rates: FX.rates, at: '2026-10-26', got: NOW } }) };
  await refreshCountry(env, 'ke', { now: NOW, fetch: async () => new Response(EPRA) });
  await refreshCountry(env, 'tz', { now: NOW, fetch: async u => (/publications/.test(u) ? new Response(gz('ewura-cap-prices-list-2026-10-26.html.gz')) : new Response(bin('ewura-cap-prices-2026-10-07-p1-3-4.pdf'))) });
  return env;
}
test('price: the nearest listed town, with source and dates in the label', async () => {
  const env = await envWithLists();
  // a trip around Kitale
  let r = await priceFor(env, { points: [[1.02, 35.0], [0.95, 34.95]], fuel: 'petrol', now: NOW });
  assert.equal(r.price.town, 'Kitale'); assert.equal(r.price.kes, 226.76); assert.equal(r.price.kind, 'official'); assert.equal(r.price.stale, false);
  assert.equal(r.price.label.en, 'Fuel: KES 226.76/L petrol, Kitale, EPRA 15 Oct–14 Nov');
  assert.equal(r.price.label.sw, 'Mafuta: KES 226.76/L petroli, Kitale, EPRA 15 Okt–14 Nov');
  // near Eldoret, diesel
  r = await priceFor(env, { lat: 0.52, lng: 35.28, fuel: 'diesel', now: NOW });
  assert.equal(r.price.town, 'Eldoret'); assert.equal(r.price.kes, 227.71);
  // by name
  r = await priceFor(env, { ck: 'ke', town: 'Mombasa', fuel: 'petrol', now: NOW });
  assert.equal(r.price.kes, 223.81);
  // Tanzania: TZS, with KES from the day's rate
  r = await priceFor(env, { lat: -3.36, lng: 37.33, fuel: 'diesel', now: NOW });
  assert.equal(r.price.town, 'Kilimanjaro (Moshi)'); assert.equal(r.price.local, 4200); assert.equal(r.price.currency, 'TZS');
  assert.equal(r.price.kes, Math.round(4200 / 2450 * 129.2 * 100) / 100);
  assert.equal(r.price.label.en, 'Fuel: TZS\u00a04,200/L (KES\u00a0' + r.price.kes.toFixed(2) + ') diesel, Kilimanjaro (Moshi), EWURA from 7 Oct');
  assert.equal(r.price.label.sw, 'Mafuta: TZS\u00a04,200/L (KES\u00a0' + r.price.kes.toFixed(2) + ') dizeli, Kilimanjaro (Moshi), EWURA kuanzia 7 Okt');
  // after the period ends, the same price is "cached" and marked as possibly out of date
  r = await priceFor(env, { lat: 1.02, lng: 35.0, fuel: 'petrol', now: Date.parse('2026-11-20T09:00:00Z') });
  assert.equal(r.price.kind, 'cached'); assert.equal(r.price.stale, true); assert.match(r.price.label.en, /\(may be out of date\)$/);
});
test('price: office prices win only when newer than the official list; no list and no office price → none', async () => {
  const env = await envWithLists();
  // entered before the official period began: the official price is used
  await setOverride(env, { ck: 'ke', town: 'Kitale', petrol: 189.84 }, 'Jessica', Date.parse('2026-10-10T08:00:00Z'));
  let r = await priceFor(env, { lat: 1.02, lng: 35.0, fuel: 'petrol', now: NOW });
  assert.equal(r.price.kind, 'official'); assert.equal(r.price.kes, 226.76);
  // entered after: the office price
  await setOverride(env, { ck: 'ke', town: 'Kitale', petrol: 229.5 }, 'Jessica', Date.parse('2026-10-20T08:00:00Z'));
  r = await priceFor(env, { lat: 1.02, lng: 35.0, fuel: 'petrol', now: NOW });
  assert.equal(r.price.kind, 'override'); assert.equal(r.price.kes, 229.5); assert.match(r.price.label.en, /^Fuel: KES 229\.50\/L petrol, Kitale, office price, 2026-10-20$/);
  // out-of-range office prices are refused
  assert.equal((await setOverride(env, { ck: 'ke', town: 'Kitale', petrol: 22950 }, 'Jessica')).reason, 'bad-price');
  // Uganda: nothing official; no office price → no price at all (never a made-up one)
  r = await priceFor(env, { lat: 0.35, lng: 32.58, fuel: 'petrol', now: NOW });
  assert.deepEqual(r, { price: null, reason: 'no-price' });
  assert.deepEqual(priceLabel(null), { en: 'Fuel: add a fuel price', sw: 'Mafuta: weka bei ya mafuta' });
  // a whole-country office price for Uganda
  await setOverride(env, { ck: 'ug', town: '*', petrol: 5200, diesel: 5100 }, 'Jessica', NOW);
  r = await priceFor(env, { lat: 0.35, lng: 32.58, fuel: 'diesel', now: NOW });
  assert.equal(r.price.local, 5100); assert.equal(r.price.town, 'Uganda'); assert.equal(r.price.kind, 'override');
  const v = await pricesView(env, null, NOW);
  assert.equal(v.ke.kind, 'official'); assert.equal(v.ug.kind, 'none'); assert.equal(v.ug.overrides['*'].diesel, 5100); assert.ok(v.tz.fx.rate > 0);
});
test('towns: positions and the country of a point', () => {
  assert.deepEqual(townPoint('tz', 'Moshi'), townPoint('tz', 'Kilimanjaro (Moshi)'));
  assert.equal(nearestTown([0.52, 35.28]).town, 'Eldoret');
  assert.equal(countryOf([-15.78, 35.0]), 'mw'); assert.equal(countryOf([0.35, 32.58]), 'ug'); assert.equal(countryOf([51.5, -0.1]), '');
});

// ---- the consumption model -------------------------------------------------------------------------------------
test('model: base by type and engine size, diesel factor', () => {
  assert.deepEqual(modelFor({ type: 'pickup', cc: 2400, fuel: 'diesel' }), { lPer100: 8.5, base: 10.0, fuelFactor: 0.85, band: '2001–2500 cc' });
  assert.equal(modelFor({ type: 'pickup', cc: 2400, fuel: 'petrol' }).lPer100, 10);
  assert.equal(modelFor({ type: 'motorbike', cc: 125, fuel: 'diesel' }).lPer100, 2.4);   // no diesel factor for motorbikes
  assert.equal(modelFor({ type: '4x4', cc: 4500, fuel: 'diesel' }).band, 'over 4000 cc');
  assert.equal(modelFor({ type: 'saloon', cc: 1300, fuel: 'petrol' }).lPer100, 6);
  assert.equal(modelFor({ type: 'boat', cc: 1000 }), null);
});
test('model: murram, rain, load and towns', () => {
  const tarmac = legLitres({ km: 100, surface: { pavedKm: 100 }, roadLevel: 'dry' }, 10, 1);
  assert.equal(tarmac.litres, 10);
  const murram = legLitres({ km: 100, surface: { unpavedKm: 100 }, roadLevel: 'dry' }, 10, 1);
  assert.equal(murram.litres, 12.5);                                                   // +25%
  assert.equal(legLitres({ km: 100, surface: { unpavedKm: 100 }, roadLevel: 'heavy' }, 10, 1).litres, 16);   // wet murram: +60%
  const mixed = legLitres({ km: 70, surface: { pavedKm: 62, unpavedKm: 8 }, roadLevel: 'wet' }, 10, 1);
  assert.equal(mixed.factors.surface, Math.round((62 * 1.03 + 8 * 1.42) / 70 * 100) / 100);
  const unknown = legLitres({ km: 100, surface: { unknownKm: 100 }, roadLevel: 'dry' }, 10, 1);
  assert.equal(unknown.litres, 11.25);                                                // halfway
  assert.equal(legLitres({ km: 100, surface: { pavedKm: 100 }, roadLevel: 'dry', townKm: 20 }, 10, 1).litres, 10.3);   // 20 km × +15%
  assert.equal(loadFactor({ type: 'pickup' }, 3, 200), 1 + 0.06 + 0.03);
  assert.equal(loadFactor({ type: 'motorbike' }, 1, 0), 1.08);
  assert.ok(CONSUMPTION.surface.unpaved.heavy > CONSUMPTION.surface.unpaved.wet);
});
test('model: real consumption from fuel logs, blended from 3 logs', () => {
  const v = { name: 'Hilux', type: 'pickup', cc: 2400, fuel: 'diesel' };
  const logs = [{ odometer: 10000, litres: 50, full: true, at: '1' }, { odometer: 10400, litres: 44, full: true, at: '2' }];
  assert.equal(useFor(v, logs).src, 'model');                                        // 2 logs: not yet
  logs.push({ odometer: 10650, litres: 12, full: false, at: '3' }, { odometer: 10900, litres: 46.8, full: true, at: '4' });
  const real = realFromLogs(logs);
  assert.deepEqual(real, { lPer100: Math.round((44 + 12 + 46.8) / 900 * 10000) / 100, logs: 4, km: 900 });
  const u = useFor(v, logs);
  assert.equal(u.src, 'blend'); assert.equal(u.weight, 0.57);
  assert.equal(u.lPer100, Math.round(((1 - 0.57) * 8.5 + 0.57 * real.lPer100 / 1.12) * 100) / 100);
  // an entered figure replaces the model base
  assert.equal(useFor(Object.assign({}, v, { lPer100: 9.5 }), []).src, 'entered');
  assert.equal(realFromLogs([{ odometer: 1000, litres: 5 }, { odometer: 1010, litres: 1 }]), null);  // under 50 km
  assert.match(basisWords(v, u).en, /^Hilux · 2400 cc diesel pickup · \d+\.\d L\/100 km \(model and 4 fuel logs\)/);
});
