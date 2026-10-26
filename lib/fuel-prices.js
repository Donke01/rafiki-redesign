// Official fuel pump prices for trip costs: fetched from the regulators, kept in KV with their source and dates.
//
// Sources (researched 26 Oct 2026; see docs/itinerary.md for the details and how fresh each one is):
//   Kenya     EPRA, https://www.epra.go.ke/pump-prices: an HTML table of maximum pump prices per town (≈230 towns) for each
//             monthly period (15th to 14th), newest first, plus a ticker with Mombasa. The page is ~7 MB (every period since
//             2024), so it is read as a stream and dropped once the newest period is complete (~350 KB).
//   Tanzania  EWURA, https://www.ewura.go.tz/publications/petroleum-price lists the monthly "Cap Prices" notices (PDF,
//             effective the first Wednesday of the month). Table 3 of the PDF gives retail caps per town (≈190) in TZS.
//   Malawi    MERA, https://mera.mw/download/fuel-pump-prices/ (a PDF, "Pump Prices in MK/L" by date of revision, one
//             national price). MERA updates that file late (in Oct 2026 it still showed April 2026), so a newer admin
//             price always wins over it (see priceFor).
//   Uganda    no official price list: pump prices are deregulated and the Ministry of Energy's site could not be reached.
//             Admins enter prices (Office › Vehicles › Fuel prices).
// Nothing here ever makes a price up: no list → the last good list (with its dates) → an admin's price → none.
import { getJson, putJson } from './pin-store.js';
import { pdfText, textLines } from './pdf-text.js';
import { TOWNS, CAPITAL, townPoint, nearestTown, countryOf, normTown, townKeys } from './towns.js';
import { metres } from './roads.js';
import { fxRates, convert } from './fx-rates.js';

export const SOURCES = {
  ke: { source: 'EPRA', sourceName: 'Energy and Petroleum Regulatory Authority', url: 'https://www.epra.go.ke/pump-prices', currency: 'KES', range: [80, 600] },
  tz: { source: 'EWURA', sourceName: 'Energy and Water Utilities Regulatory Authority', url: 'https://www.ewura.go.tz/publications/petroleum-price', currency: 'TZS', range: [1500, 9000] },
  mw: { source: 'MERA', sourceName: 'Malawi Energy Regulatory Authority', url: 'https://mera.mw/download/fuel-pump-prices/?wpdmdl=3633', page: 'https://mera.mw/download/fuel-pump-prices/', currency: 'MWK', range: [500, 30000] },
  ug: { source: '', sourceName: 'No official list (prices are deregulated)', url: '', currency: 'UGX', range: [2000, 12000] }
};
export const COUNTRIES = ['ke', 'tz', 'ug', 'mw'];
export const FUELS = ['petrol', 'diesel'];
export const MAX_EPRA_BYTES = 1.5e6;           // stop reading the EPRA page after this much
export const STALE_DAYS = { ke: 40, tz: 45, mw: 150, ug: 60 }; // a list older than this is shown as "may be out of date"
const UA = 'Mozilla/5.0 (compatible; RAFIKI-Pin/1.0; +https://www.rafikioutreach.org)';

const MON = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
const MON_SW = ['Jan', 'Feb', 'Mac', 'Apr', 'Mei', 'Jun', 'Jul', 'Ago', 'Sep', 'Okt', 'Nov', 'Des'];
const MONTHS = { jan: 1, feb: 2, mar: 3, apr: 4, may: 5, jun: 6, jul: 7, aug: 8, sep: 9, sept: 9, oct: 10, nov: 11, dec: 12, january: 1, february: 2, march: 3, april: 4, june: 6, july: 7, august: 8, september: 9, october: 10, november: 11, december: 12 };
const pad = n => String(n).padStart(2, '0');
const iso = (y, m, d) => y + '-' + pad(m) + '-' + pad(d);
const num = s => { const v = Number(String(s).replace(/,/g, '')); return isFinite(v) ? v : null; };
const ent = s => String(s || '').replace(/&#0?39;|&apos;/g, "'").replace(/&amp;/g, '&').replace(/&nbsp;|&#160;/g, ' ').replace(/&quot;/g, '"').replace(/<[^>]+>/g, '').replace(/\s+/g, ' ').trim();
const inRange = (ck, v) => v != null && v >= SOURCES[ck].range[0] && v <= SOURCES[ck].range[1];

// ---- Kenya: EPRA ------------------------------------------------------------------------------------------
/** The newest period in the EPRA pump prices page (also works on a page cut off part-way). */
export function parseEpra(html) {
  const s = String(html || '');
  const t0 = s.indexOf('<table');
  if (t0 < 0) return { ok: false, reason: 'parse' };
  const rows = [], re = /<tr[^>]*>([\s\S]*?)<\/tr>/g; re.lastIndex = t0;
  let m;
  while ((m = re.exec(s))) {
    const td = [...m[1].matchAll(/<td[^>]*>([\s\S]*?)<\/td>/g)].map(x => ent(x[1]));
    if (td.length < 6) continue;
    const f = /^(\d{1,2})-(\d{1,2})-(\d{4})$/.exec(td[0]), t = /^(\d{1,2})-(\d{1,2})-(\d{4})$/.exec(td[1]);
    if (!f || !t) continue;
    rows.push({ from: iso(f[3], f[2], f[1]), to: iso(t[3], t[2], t[1]), town: td[2], petrol: num(td[3]), diesel: num(td[4]), kerosene: num(td[5]) });
  }
  if (!rows.length) return { ok: false, reason: 'parse' };
  const from = rows.map(r => r.from).sort().pop(), cur = rows.filter(r => r.from === from);
  const towns = {};
  for (const r of cur) if (r.town && inRange('ke', r.petrol) && inRange('ke', r.diesel)) towns[r.town] = { petrol: r.petrol, diesel: r.diesel, kerosene: inRange('ke', r.kerosene) ? r.kerosene : null };
  // the ticker at the top ("Mombasa PMS 223.81 … Mombasa AGO 224.71 …") has Mombasa, which the table leaves out
  const tick = /FUEL PRICE INDEX[\s\S]{0,4000}?<\/marquee>/i.exec(s);
  if (tick) {
    const txt = ent(tick[0]);
    for (const x of txt.matchAll(/([A-Z][a-z']+(?: [A-Z][a-z']+)?) (PMS|AGO|IK) (\d{2,3}\.\d{1,2})/g)) {
      const town = x[1].trim(), k = { PMS: 'petrol', AGO: 'diesel', IK: 'kerosene' }[x[2]], v = num(x[3]);
      if (!inRange('ke', v)) continue;
      if (!towns[town]) towns[town] = { petrol: null, diesel: null, kerosene: null, ticker: true };
      if (towns[town].ticker) towns[town][k] = v;
    }
    for (const [k, v] of Object.entries(towns)) if (v.ticker && (v.petrol == null || v.diesel == null)) delete towns[k];
  }
  const n = Object.keys(towns).length;
  if (n < 10 || !towns.Nairobi) return { ok: false, reason: 'parse', rows: rows.length };
  return { ok: true, from, to: cur[0].to, towns, national: Object.assign({ town: 'Nairobi' }, strip(towns.Nairobi)) };
}
const strip = p => ({ petrol: p.petrol, diesel: p.diesel, kerosene: p.kerosene == null ? null : p.kerosene });

// read a response as text until stop(text) says the part we need is complete, or maxBytes
export async function readSome(res, stop, maxBytes) {
  if (!res.body || !res.body.getReader) return await res.text();
  const rd = res.body.getReader(), dec = new TextDecoder();
  let txt = '', n = 0;
  for (;;) {
    const { value, done } = await rd.read();
    if (done) break;
    n += value.length; txt += dec.decode(value, { stream: true });
    if (n >= maxBytes || stop(txt)) { try { await rd.cancel(); } catch (e) {} break; }
  }
  return txt;
}
// the newest period is complete once a row of an older period has started
export function epraDone(txt) {
  const t0 = txt.indexOf('<tbody'); if (t0 < 0) return false;
  const ds = txt.slice(t0).match(/>\s*(\d{2}-\d{2}-\d{4})\s*</g) || [];
  if (ds.length < 3) return false;
  const first = ds[0].replace(/[^\d-]/g, '');
  // dates alternate From, To; a later From that differs from the first means the next period
  for (let i = 2; i < ds.length; i += 2) if (ds[i].replace(/[^\d-]/g, '') !== first) return true;
  return false;
}
async function fetchEpra(env, F) {
  const url = env.FUEL_EPRA_URL || SOURCES.ke.url;
  const r = await F(url, { headers: { 'User-Agent': UA, Accept: 'text/html' } });
  if (r.status === 403 || r.status === 429 || r.status === 503) return { ok: false, reason: 'blocked', status: r.status, url };
  if (!r.ok) return { ok: false, reason: 'fetch', status: r.status, url };
  const html = await readSome(r, epraDone, MAX_EPRA_BYTES);
  return Object.assign(parseEpra(html), { url });
}

// ---- Tanzania: EWURA ---------------------------------------------------------------------------------------
/** The cap price notices listed on the publications page, newest first: [{ url, title, date }] */
export function parseEwuraList(html, base) {
  const s = String(html || ''), out = [];
  for (const m of s.matchAll(/href="([^"]*\/uploads\/documents\/[^"]+\.pdf)"/gi)) {
    let u = m[1]; try { u = new URL(u, base || 'https://www.ewura.go.tz/').href; } catch (e) { continue; }
    const name = decodeURIComponent(u.split('/').pop()).toLowerCase();
    if (!/cap/.test(name) || /batch|notice batch/.test(name)) continue;
    if (!out.some(x => x.url === u)) out.push({ url: u, name });
  }
  return out;
}
const dateWords = (d, mon, y) => { const m = MONTHS[String(mon).toLowerCase()]; return m && Number(d) >= 1 && Number(d) <= 31 ? iso(y, m, Number(d)) : null; };
/** Table 3 (retail caps per town) and the effective date from the notice's text lines. */
export function parseEwura(lines) {
  const all = lines.join('\n');
  const eff = /EFFECTIVE[^0-9]{0,40}?(\d{1,2})\s*(?:st|nd|rd|th)?\s*([A-Za-z]{3,9})\s*,?\s*(\d{4})/i.exec(all);
  const from = eff ? dateWords(eff[1], eff[2], eff[3]) : null;
  const towns = {};
  let inTable3 = false;
  for (const l of lines) {
    if (/Table\s*3/i.test(l)) inTable3 = true;
    const m = /^(\d{1,3})\s+(.+?)\s+(\d{1,2},\d{3}(?:\.\d+)?)\s+(\d{1,2},\d{3}(?:\.\d+)?)\s+(\d{1,2},\d{3}(?:\.\d+)?|-)\s*$/.exec(l);
    if (!m) continue;
    inTable3 = true;
    const p = num(m[3]), d = num(m[4]), k = m[5] === '-' ? null : num(m[5]);
    if (inRange('tz', p) && inRange('tz', d)) towns[m[2].trim()] = { petrol: p, diesel: d, kerosene: inRange('tz', k) ? k : null };
  }
  if (!from) return { ok: false, reason: 'parse', why: 'date' };
  if (Object.keys(towns).length < 10 || !towns['Dar es Salaam']) return { ok: false, reason: 'parse', why: 'table', towns: Object.keys(towns).length };
  return { ok: true, from, to: null, towns, national: Object.assign({ town: 'Dar es Salaam' }, towns['Dar es Salaam']), tableOk: inTable3 };
}
async function pdfLines(bytes) { const pages = await pdfText(bytes, { maxPages: 12 }); return pages.flatMap(textLines); }
async function fetchEwura(env, F) {
  const listUrl = env.FUEL_EWURA_URL || SOURCES.tz.url;
  const r = await F(listUrl, { headers: { 'User-Agent': UA, Accept: 'text/html' } });
  if (!r.ok) return { ok: false, reason: r.status === 403 ? 'blocked' : 'fetch', status: r.status, url: listUrl };
  const list = parseEwuraList(await r.text(), listUrl);
  if (!list.length) return { ok: false, reason: 'parse', why: 'no-pdf', url: listUrl };
  const doc = list[0].url;
  const p = await F(doc.replace(/ /g, '%20'), { headers: { 'User-Agent': UA, Accept: 'application/pdf' } });
  if (!p.ok) return { ok: false, reason: 'fetch', status: p.status, url: doc };
  let out;
  try { out = parseEwura(await pdfLines(new Uint8Array(await p.arrayBuffer()))); } catch (e) { return { ok: false, reason: 'parse', why: String(e && e.message || e).slice(0, 60), url: doc }; }
  return Object.assign(out, { url: listUrl, docUrl: doc });
}

// ---- Malawi: MERA ------------------------------------------------------------------------------------------
/** "Apr-26 6,672.00 6,687.00 5,824.00" rows → the newest revision. */
export function parseMera(lines) {
  const rows = [];
  for (const l of lines) {
    const m = /^([A-Z][a-z]{2})-(\d{2})\s*([\d,]+\.\d{1,2})\s*([\d,]+\.\d{1,2})\s*([\d,]+\.\d{1,2})/.exec(l.trim());
    if (!m || !MONTHS[m[1].toLowerCase()]) continue;
    const y = 2000 + Number(m[2]);
    rows.push({ from: iso(y, MONTHS[m[1].toLowerCase()], 1), petrol: num(m[3]), diesel: num(m[4]), kerosene: num(m[5]), month: true });
  }
  const ok = rows.filter(r => inRange('mw', r.petrol) && inRange('mw', r.diesel));
  if (!ok.length) return { ok: false, reason: 'parse' };
  const top = ok.sort((a, b) => (a.from < b.from ? 1 : -1))[0];
  const nat = { petrol: top.petrol, diesel: top.diesel, kerosene: inRange('mw', top.kerosene) ? top.kerosene : null };
  return { ok: true, from: top.from, to: null, month: true, towns: { Malawi: nat }, national: Object.assign({ town: 'Malawi' }, nat) };
}
async function fetchMera(env, F) {
  const url = env.FUEL_MERA_URL || SOURCES.mw.url;
  const r = await F(url, { headers: { 'User-Agent': UA, Accept: 'application/pdf' } });
  if (!r.ok) return { ok: false, reason: r.status === 403 ? 'blocked' : 'fetch', status: r.status, url };
  const b = new Uint8Array(await r.arrayBuffer());
  try { return Object.assign(parseMera(await pdfLines(b)), { url: SOURCES.mw.page, docUrl: url }); }
  catch (e) { return { ok: false, reason: 'parse', why: String(e && e.message || e).slice(0, 60), url }; }
}

// ---- fetching and keeping ---------------------------------------------------------------------------------
const FETCH = { ke: fetchEpra, tz: fetchEwura, mw: fetchMera };
export const priceKey = ck => 'fuelprice:' + ck;
/** Fetch one country's official list and keep it when it is new. → { ok, reason?, from, to, towns } */
export async function refreshCountry(env, ck, opts = {}) {
  const now = opts.now || Date.now(), F = opts.fetch || fetch, src = SOURCES[ck];
  let res;
  if (!FETCH[ck]) res = { ok: false, reason: 'no-source' };
  else { try { res = await FETCH[ck](env, F); } catch (e) { res = { ok: false, reason: 'fetch', why: String(e && e.message || e).slice(0, 80) }; } }
  const status = await getJson(env, 'fuelprice:status', {});
  const cur = await getJson(env, priceKey(ck), null);
  let out = { ok: res.ok, reason: res.ok ? null : res.reason };
  if (res.ok) {
    if (cur && cur.from > res.from) out = { ok: false, reason: 'stale', from: res.from, kept: cur.from };
    else {
      const rec = { ck, source: src.source, sourceName: src.sourceName, url: res.url || src.url, docUrl: res.docUrl || null, from: res.from, to: res.to || null, month: !!res.month,
        fetchedAt: new Date(now).toISOString(), currency: src.currency, national: res.national, towns: res.towns };
      if (cur && cur.from < res.from) await putJson(env, 'fuelprice:prev:' + ck, cur);
      await putJson(env, priceKey(ck), rec);
      out = { ok: true, from: rec.from, to: rec.to, towns: Object.keys(rec.towns).length };
    }
  }
  status[ck] = { at: new Date(now).toISOString(), ok: out.ok, reason: out.reason || null, url: res.docUrl || res.url || src.url || null, from: res.from || null, status: res.status || null };
  await putJson(env, 'fuelprice:status', status);
  return out;
}
export async function refreshAll(env, opts = {}) {
  const out = {};
  for (const ck of opts.only ? [opts.only] : COUNTRIES) out[ck] = await refreshCountry(env, ck, opts);
  return out;
}

// ---- admin prices ---------------------------------------------------------------------------------------
export const overrides = env => getJson(env, 'fuelprice:override', {});
export async function setOverride(env, b, by, now) {
  const ck = String(b.ck || '').toLowerCase();
  if (!COUNTRIES.includes(ck)) return { ok: false, reason: 'bad-country' };
  const town = String(b.town || '').replace(/[<>]/g, '').trim().slice(0, 60);
  if (!town) return { ok: false, reason: 'bad-town' };
  const p = b.petrol === '' || b.petrol == null ? null : Number(b.petrol), d = b.diesel === '' || b.diesel == null ? null : Number(b.diesel);
  if ((p == null && d == null) || [p, d].some(v => v != null && !inRange(ck, v))) return { ok: false, reason: 'bad-price', range: SOURCES[ck].range, currency: SOURCES[ck].currency };
  const until = b.until && /^\d{4}-\d{2}-\d{2}$/.test(b.until) ? b.until : null;
  const all = await overrides(env); all[ck] = all[ck] || {};
  all[ck][town] = { petrol: p, diesel: d, at: new Date(now || Date.now()).toISOString(), by: String(by || '').slice(0, 60), until };
  await putJson(env, 'fuelprice:override', all);
  return { ok: true, override: all[ck][town] };
}
export async function removeOverride(env, b) {
  const ck = String(b.ck || '').toLowerCase(), all = await overrides(env);
  if (!all[ck] || !all[ck][b.town]) return { ok: false, reason: 'not-found' };
  delete all[ck][b.town]; await putJson(env, 'fuelprice:override', all);
  return { ok: true };
}

// ---- what the app sees --------------------------------------------------------------------------------------
export async function pricesView(env, only, now) {
  const t = now || Date.now(), ov = await overrides(env), status = await getJson(env, 'fuelprice:status', {}), fx = await fxRates(env, t).catch(() => null), out = {};
  for (const ck of only ? [only] : COUNTRIES) {
    const rec = await getJson(env, priceKey(ck), null), src = SOURCES[ck];
    const v = rec ? Object.assign({}, rec, { kind: kindOf(rec, ck, t), stale: staleOf(rec, ck, t) }) : { ck, source: src.source, sourceName: src.sourceName, url: src.url, currency: src.currency, kind: 'none', towns: {}, national: null };
    v.overrides = ov[ck] || {}; v.status = status[ck] || null;
    if (src.currency !== 'KES' && fx) v.fx = { rate: convert(1, src.currency, 'KES', fx), at: fx.at };
    out[ck] = v;
  }
  return out;
}
const today = t => new Date(t + 3 * 3600e3).toISOString().slice(0, 10);
function kindOf(rec, ck, t) { const d = today(t); return rec.to && rec.to < d ? 'cached' : 'official'; }
function staleOf(rec, ck, t) { const d = today(t); return (!!rec.to && rec.to < d) || (Date.parse(d) - Date.parse(rec.from)) / 864e5 > STALE_DAYS[ck]; }

function dateLabel(rec, sw) {
  const M = sw ? MON_SW : MON, f = new Date(rec.from + 'T00:00:00Z');
  if (rec.month) return M[f.getUTCMonth()] + ' ' + f.getUTCFullYear();
  const a = f.getUTCDate() + ' ' + M[f.getUTCMonth()];
  if (!rec.to) return (sw ? 'kuanzia ' : 'from ') + a;
  const e = new Date(rec.to + 'T00:00:00Z');
  return a + '–' + e.getUTCDate() + ' ' + M[e.getUTCMonth()];
}
const FUEL_SW = { petrol: 'petroli', diesel: 'dizeli', kerosene: 'mafuta ya taa' };
const nb = ' ';
const money2 = (cur, v) => cur + nb + (cur === 'KES' ? Number(v).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 }) : Math.round(v).toLocaleString('en-US'));
/** "Fuel: KES 189.84/L petrol, Eldoret, EPRA 15 Oct–14 Nov" and the Kiswahili */
export function priceLabel(p) {
  if (!p) return { en: 'Fuel: add a fuel price', sw: 'Mafuta: weka bei ya mafuta' };
  const local = money2(p.currency, p.local) + '/L', kes = p.currency !== 'KES' && p.kes != null ? ' (' + money2('KES', p.kes) + ')' : '';
  const src = p.kind === 'override' ? { en: 'office price, ' + p.at.slice(0, 10), sw: 'bei ya ofisi, ' + p.at.slice(0, 10) } : { en: p.source + ' ' + dateLabel(p, false), sw: p.source + ' ' + dateLabel(p, true) };
  const old = p.stale ? { en: ' (may be out of date)', sw: ' (huenda imepitwa na wakati)' } : { en: '', sw: '' };
  return { en: 'Fuel: ' + local + kes + ' ' + p.fuel + ', ' + p.town + ', ' + src.en + old.en, sw: 'Mafuta: ' + local + kes + ' ' + (FUEL_SW[p.fuel] || p.fuel) + ', ' + p.town + ', ' + src.sw + old.sw };
}

/**
 * The fuel price for a trip: the nearest listed town to the point(s).
 * opts: { points: [[lat,lng]…] | lat,lng, ck?, town?, fuel, now }
 * Order: an admin price for that town entered after the official list began → the official (or last cached) price of the
 * nearest town with a known position → the official national price → the admin "*" (whole country) price → null.
 * → { price, reason } ; price: { kes, local, currency, fuel, town, distKm, source, from, to, kind, at?, stale, label }
 */
export async function priceFor(env, opts) {
  const now = opts.now || Date.now(), fuel = FUELS.includes(opts.fuel) ? opts.fuel : 'petrol';
  const pts = (opts.points || (opts.lat != null ? [[Number(opts.lat), Number(opts.lng)]] : [])).filter(p => p && isFinite(p[0]) && isFinite(p[1]));
  const ck = COUNTRIES.includes(opts.ck) ? opts.ck : pts.length ? countryOf(pts[0]) : '';
  if (!ck) return { price: null, reason: 'no-country' };
  const rec = await getJson(env, priceKey(ck), null), ov = ((await overrides(env))[ck]) || {}, src = SOURCES[ck], d = today(now);
  const ovOk = o => o && o[fuel] != null && (!o.until || o.until >= d);
  // nearest town (of the official list, or of the admin list) to any of the points
  const near = (names) => {
    let best = null;
    for (const name of names) {
      const q = townPoint(ck, name); if (!q) continue;
      for (const p of pts) { const km = metres(p, q) / 1000; if (!best || km < best.km) best = { town: name, km }; }
    }
    return best;
  };
  let pick = null;
  if (opts.town) {
    const want = townKeys(opts.town), match = n => townKeys(n).some(k => want.includes(k));
    const o = Object.keys(ov).find(match), t = rec && Object.keys(rec.towns || {}).find(match);
    if (t || o) pick = { town: t || o, km: 0, ovTown: o };
  }
  if (!pick && pts.length) {
    const offTowns = rec ? Object.keys(rec.towns || {}).filter(n => rec.towns[n][fuel] != null) : [];
    const a = near(offTowns), b = near(Object.keys(ov).filter(n => n !== '*' && ovOk(ov[n])));
    pick = a && (!b || a.km <= b.km) ? a : b;
    if (pick && b && pick.town !== b.town && Math.abs(b.km - pick.km) < 1) pick = b;
    if (pick) pick.ovTown = Object.keys(ov).find(n => townKeys(n).some(k => townKeys(pick.town).includes(k)));
  }
  const fx = src.currency === 'KES' ? null : await fxRates(env, now).catch(() => null);
  const make = (local, base) => {
    const kes = src.currency === 'KES' ? local : convert(local, src.currency, 'KES', fx);
    const p = Object.assign({ local: Math.round(local * 100) / 100, kes: kes == null ? null : Math.round(kes * 100) / 100, currency: src.currency, fuel }, base);
    if (src.currency !== 'KES') p.fx = fx ? { rate: Math.round(convert(1, src.currency, 'KES', fx) * 1e5) / 1e5, at: fx.at } : null;
    p.label = priceLabel(p);
    return { price: p, reason: null };
  };
  const official = (town, km) => {
    const v = rec.towns[town][fuel];
    return make(v, { town, distKm: km == null ? null : Math.round(km), source: rec.source, from: rec.from, to: rec.to, month: rec.month, kind: kindOf(rec, ck, now), stale: staleOf(rec, ck, now), url: rec.docUrl || rec.url });
  };
  const admin = (town, km) => {
    const o = ov[town];
    return make(o[fuel], { town: town === '*' ? countryName(ck) : town, distKm: km == null ? null : Math.round(km), source: 'office', at: o.at, by: o.by, kind: 'override', stale: false });
  };
  if (pick) {
    const o = pick.ovTown && ovOk(ov[pick.ovTown]) ? ov[pick.ovTown] : null;
    const off = rec && rec.towns[pick.town] && rec.towns[pick.town][fuel] != null;
    // an office price wins when it was entered after the official list started (or there is no official one)
    if (o && (!off || o.at.slice(0, 10) >= rec.from)) return admin(pick.ovTown, pick.km);
    if (off) return official(pick.town, pick.km);
  }
  const star = ovOk(ov['*']) ? ov['*'] : null;
  if (rec && rec.national && rec.national[fuel] != null && !(star && star.at.slice(0, 10) >= rec.from)) {
    const t = rec.national.town;
    return official(rec.towns[t] ? t : Object.keys(rec.towns)[0], pts.length && townPoint(ck, t) ? Math.min(...pts.map(p => metres(p, townPoint(ck, t)) / 1000)) : null);
  }
  if (star) return admin('*', null);
  return { price: null, reason: 'no-price' };
}
const countryName = ck => ({ ke: 'Kenya', tz: 'Tanzania', ug: 'Uganda', mw: 'Malawi' }[ck] || ck);
export { normTown, TOWNS };
