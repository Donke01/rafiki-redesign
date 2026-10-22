// Weather for planned visits (Trip advice: lib/trip.js), from Open-Meteo (https://open-meteo.com), which needs no key.
//
// One call per place gives the last 2 days and the next 16 days (daily rain, chance of rain, weather code,
// highest and lowest temperature, and hourly rain for the morning). The whole table is kept in KV (FEED) for
// WX_TTL (3 hours), keyed by the place rounded to 0.05° (about 5.5 km) and the day the forecast was fetched
// (Nairobi time), so every date in the window, and every site in the same 5 km square, shares one call.
//
// Rain levels for one day (rain in mm, from the daily total):
//   dry    under 1 mm            (the usual "rain day" line: under 1 mm the ground stays dry)
//   light  1 mm to under 10 mm   (showers: tarmac fine, murram gets slippery)
//   wet    10 mm to under 20 mm  (proper rain: murram muddy and slow)
//   heavy  20 mm or more         (murram can become impassable, rivers rise)
// Murram stays bad after rain, so `roadLevel` also counts the 2 days before:
//   soak = rain that day + 0.5 × rain the day before + 0.25 × rain two days before, and roadLevel = levelOf(soak).
// The thresholds are in LEVELS and SOAK below; change them there.
//
// Optional: OPEN_METEO_API_KEY (a paid Open-Meteo plan; the free API is for non-commercial use). When set, the
// customer address is used. The key is only read from env.
import { getJson, putJson } from './pin-store.js';

export const WX_TTL = 3 * 3600;              // seconds a forecast is kept
export const WX_DAYS = 16;                   // days ahead Open-Meteo forecasts
export const PAST_DAYS = 2;                  // days before today we also ask for (murram dries slowly)
export const GRID = 0.05;                    // degrees: places in the same square share one forecast
export const LEVELS = [['heavy', 20], ['wet', 10], ['light', 1]]; // mm a day, highest first; below the last: dry
export const SOAK = [1, 0.5, 0.25];          // weights for the day itself, the day before, two days before
export const MORNING = [6, 12];              // hours counted as "morning" (06:00 to 11:59, Nairobi time)
export const LEVEL_RANK = { dry: 0, light: 1, wet: 2, heavy: 3 };

export function levelOf(mm) {
  const v = Number(mm) || 0;
  for (const [name, min] of LEVELS) if (v >= min) return name;
  return 'dry';
}
export const worseLevel = (a, b) => ((LEVEL_RANK[a] ?? -1) >= (LEVEL_RANK[b] ?? -1) ? a : b);

// today in Nairobi (UTC+3, no daylight saving) as "YYYY-MM-DD"
export const nairobiDay = now => new Date((now || Date.now()) + 3 * 3600e3).toISOString().slice(0, 10);
export function addDays(iso, n) { const d = new Date(iso + 'T00:00:00Z'); d.setUTCDate(d.getUTCDate() + n); return d.toISOString().slice(0, 10); }
export const daysBetween = (a, b) => Math.round((Date.parse(b + 'T00:00:00Z') - Date.parse(a + 'T00:00:00Z')) / 864e5);
export const isDay = s => /^\d{4}-\d{2}-\d{2}$/.test(String(s || '')) && !isNaN(Date.parse(s + 'T00:00:00Z'));

const snap = v => (Math.round(v / GRID) * GRID).toFixed(2);
export const cellOf = (lat, lng) => snap(Number(lat)) + ',' + snap(Number(lng));

// WMO weather codes (as Open-Meteo gives them): English and Kiswahili words
const CODES = [
  [[0], 'Clear sky', 'Anga safi'],
  [[1], 'Mostly sunny', 'Jua kwa sehemu kubwa'],
  [[2], 'Partly cloudy', 'Mawingu kiasi'],
  [[3], 'Cloudy', 'Mawingu'],
  [[45, 48], 'Fog', 'Ukungu'],
  [[51, 53, 55, 56, 57], 'Drizzle', 'Manyunyu'],
  [[61, 66], 'Light rain', 'Mvua kidogo'],
  [[63], 'Rain', 'Mvua'],
  [[65, 67], 'Heavy rain', 'Mvua kubwa'],
  [[71, 73, 75, 77, 85, 86], 'Snow', 'Theluji'],
  [[80], 'Showers', 'Manyunyu ya mvua'],
  [[81], 'Rain showers', 'Mvua za rasharasha'],
  [[82], 'Violent showers', 'Mvua kubwa za ghafla'],
  [[95], 'Thunderstorms', 'Radi na mvua'],
  [[96, 99], 'Thunderstorms with hail', 'Radi na mvua ya mawe']
];
export function codeWords(code) {
  const c = Number(code);
  const hit = CODES.find(x => x[0].includes(c));
  return hit ? { en: hit[1], sw: hit[2] } : { en: '', sw: '' };
}
export const isStorm = code => Number(code) >= 95;

// The short sentence for a day, in English and Kiswahili, e.g.
//   "Heavy rain expected · 25 mm (90%) · 24°/14°"  /  "Mvua kubwa inatarajiwa · mm 25 (90%) · 24°/14°"
const HEAD = {
  heavy: ['Heavy rain expected', 'Mvua kubwa inatarajiwa'],
  wet: ['Rain expected', 'Mvua inatarajiwa'],
  light: ['Light rain possible', 'Mvua kidogo inawezekana'],
  dry: ['No rain expected', 'Hakuna mvua inayotarajiwa']
};
const r1 = v => Math.round(v * 10) / 10;
export function summarize(d) {
  let [en, sw] = HEAD[d.level] || HEAD.dry;
  if (isStorm(d.code) && d.level !== 'dry') { en = d.level === 'heavy' ? 'Heavy rain and thunderstorms expected' : 'Thunderstorms expected'; sw = d.level === 'heavy' ? 'Mvua kubwa na radi zinatarajiwa' : 'Radi na mvua zinatarajiwa'; }
  if (d.level === 'dry' && Number(d.code) <= 1) { en = 'Dry and sunny'; sw = 'Jua na hakuna mvua'; }
  const mm = d.rainMm >= 1 ? ' · ' + Math.round(d.rainMm) + ' mm' + (d.rainChance != null ? ' (' + d.rainChance + '%)' : '') : '';
  const mmSw = d.rainMm >= 1 ? ' · mm ' + Math.round(d.rainMm) + (d.rainChance != null ? ' (' + d.rainChance + '%)' : '') : '';
  const t = d.tMax != null && d.tMin != null ? ' · ' + Math.round(d.tMax) + '°/' + Math.round(d.tMin) + '°' : '';
  return { summary: en + mm + t, summarySw: sw + mmSw + t };
}

function forecastUrl(env, lat, lng) {
  const key = String(env.OPEN_METEO_API_KEY || '').trim();
  const base = key ? 'https://customer-api.open-meteo.com/v1/forecast' : 'https://api.open-meteo.com/v1/forecast';
  return base + '?latitude=' + lat + '&longitude=' + lng +
    '&daily=precipitation_sum,precipitation_probability_max,weather_code,temperature_2m_max,temperature_2m_min' +
    '&hourly=precipitation&timezone=Africa%2FNairobi&past_days=' + PAST_DAYS + '&forecast_days=' + WX_DAYS +
    (key ? '&apikey=' + encodeURIComponent(key) : '');
}

// Open-Meteo's answer → { days: { "YYYY-MM-DD": { r, p, c, tx, tn, m } } } (rain mm, chance %, code, max, min, morning rain mm)
export function compact(j) {
  const d = (j && j.daily) || {}, h = (j && j.hourly) || {}, days = {};
  (d.time || []).forEach((t, i) => {
    const n = (a, k) => (a && a[k] != null && isFinite(a[k]) ? Number(a[k]) : null);
    days[t] = { r: n(d.precipitation_sum, i), p: n(d.precipitation_probability_max, i), c: n(d.weather_code, i), tx: n(d.temperature_2m_max, i), tn: n(d.temperature_2m_min, i), m: 0 };
  });
  (h.time || []).forEach((t, i) => {
    const day = String(t).slice(0, 10), hr = Number(String(t).slice(11, 13)), v = Number(h.precipitation && h.precipitation[i]);
    if (days[day] && hr >= MORNING[0] && hr < MORNING[1] && isFinite(v)) days[day].m += v;
  });
  Object.values(days).forEach(x => { x.m = r1(x.m + 1e-9); });
  return { days };
}

/** The whole table for one place (cached). Returns { cell, days } or null when Open-Meteo can't be reached. */
export async function forecastTable(env, lat, lng, now) {
  const cell = cellOf(lat, lng), today = nairobiDay(now), key = 'wx:v1:' + cell + ':' + today;
  const hit = await getJson(env, key, null);
  if (hit && hit.days) return { cell, days: hit.days, cached: true };
  const [la, lo] = cell.split(',');
  let j = null;
  try {
    const r = await fetch(forecastUrl(env, la, lo), { headers: { Accept: 'application/json' } });
    if (!r.ok) { console.error('weather:', r.status); return null; }
    j = await r.json();
  } catch (e) { console.error('weather:', e && e.message); return null; }
  const t = compact(j);
  if (!Object.keys(t.days).length) return null;
  await putJson(env, key, { at: new Date(now || Date.now()).toISOString(), days: t.days }, { expirationTtl: WX_TTL });
  return { cell, days: t.days, cached: false };
}

/** One day from a table: { available, date, rainMm, rainChance, code, summary, summarySw, tMax, tMin, morningRainMm, level, prevRainMm, prev, roadLevel, soakMm } */
export function dayFrom(days, dateISO, today) {
  if (!isDay(dateISO)) return { available: false, date: dateISO, reason: 'bad-date' };
  const off = today ? daysBetween(today, dateISO) : 0;
  if (today && off >= WX_DAYS) return { available: false, date: dateISO, reason: 'too-far', availableFrom: addDays(dateISO, -(WX_DAYS - 1)) };
  if (today && off < -PAST_DAYS) return { available: false, date: dateISO, reason: 'past' };
  const d = days && days[dateISO];
  if (!d || d.r == null) return { available: false, date: dateISO, reason: today && off >= 0 ? 'too-far' : 'past' };
  const p1 = days[addDays(dateISO, -1)], p2 = days[addDays(dateISO, -2)];
  const prev = [p1 && p1.r != null ? r1(p1.r) : null, p2 && p2.r != null ? r1(p2.r) : null];
  const rainMm = r1(d.r), soak = r1(SOAK[0] * rainMm + SOAK[1] * (prev[0] || 0) + SOAK[2] * (prev[1] || 0));
  const out = {
    available: true, date: dateISO, rainMm, rainChance: d.p != null ? Math.round(d.p) : null, code: d.c,
    tMax: d.tx != null ? Math.round(d.tx) : null, tMin: d.tn != null ? Math.round(d.tn) : null,
    morningRainMm: r1(d.m || 0), level: levelOf(rainMm),
    // the 2 days before, newest first: [yesterday, the day before]
    prev, prevRainMm: r1((prev[0] || 0) + (prev[1] || 0)), soakMm: soak, roadLevel: worseLevel(levelOf(soak), levelOf(rainMm)),
    words: codeWords(d.c)
  };
  return Object.assign(out, summarize(out));
}

/** forecastFor(env, lat, lng, "YYYY-MM-DD") → the day (see dayFrom), or { available: false, reason }. */
export async function forecastFor(env, lat, lng, dateISO, now) {
  if (!isFinite(Number(lat)) || !isFinite(Number(lng))) return { available: false, date: dateISO, reason: 'place' };
  const today = nairobiDay(now);
  if (!isDay(dateISO)) return { available: false, date: dateISO, reason: 'bad-date' };
  const off = daysBetween(today, dateISO);
  if (off >= WX_DAYS) return { available: false, date: dateISO, reason: 'too-far', availableFrom: addDays(dateISO, -(WX_DAYS - 1)) };
  if (off < -PAST_DAYS) return { available: false, date: dateISO, reason: 'past' };
  const t = await forecastTable(env, lat, lng, now);
  if (!t) return { available: false, date: dateISO, reason: 'down' };
  return Object.assign(dayFrom(t.days, dateISO, today), { cell: t.cell });
}

// Kenya's rainy seasons: long rains March–May, short rains October–December. Used when there is no forecast yet.
export const rainySeason = dateISO => { const m = Number(String(dateISO).slice(5, 7)); return (m >= 3 && m <= 5) || m >= 10; };
