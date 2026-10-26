// /api/fuel: official fuel prices (lib/fuel-prices.js) and fuel logs (litres bought on trips). Same sign-in as /api/pin.
//   GET  /api/fuel?prices=1[&ck=ke]                      → the lists per country, with source, dates, office prices and fetch status
//   GET  /api/fuel?price=1&lat=&lng=&fuel=diesel          → the price for that place (nearest listed town); or &ck=ke&town=Eldoret
//   GET  /api/fuel?logs=<vid>  /  ?itin=<id>              → fuel logs of a vehicle / of a trip
//   POST /api/fuel { action: "override", ck, town, petrol, diesel, until? }   admins: an office price ("*" = whole country)
//        { action: "override-remove", ck, town }  ·  { action: "refresh", ck? }  admins: fetch the official lists now
//        { action: "log", vid, itinId?, litres, kesPerL | totalKes (or perL | total with currency TZS/UGX/MWK), odometer?, full, town?, lat?, lng?, receipt?, note?, at? }
//        { action: "log-remove", vid, lid }
//   POST /api/fuel?cron=prices with header X-Cron-Key = CRON_KEY → the scheduled fetch (.github/workflows/fuel-prices.yml)
import { checkPin } from './pin.js';
import { getJson, putJson, sameName } from '../../lib/pin-store.js';
import { pricesView, priceFor, refreshAll, setOverride, removeOverride, COUNTRIES } from '../../lib/fuel-prices.js';
import { realFromLogs } from '../../lib/fuel-model.js';
import { fxRates, convert } from '../../lib/fx-rates.js';
import { newId, vehicles, fuelLogs, load, canEdit, canSee } from '../../lib/itin.js';

const HEAD = { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff', 'X-Robots-Tag': 'noindex' };
const json = (b, s) => new Response(JSON.stringify(b), { status: s || 200, headers: HEAD });
const bad = (reason, status, extra) => json(Object.assign({ ok: false, reason }, extra || {}), status || 400);
const clean = (s, n) => String(s == null ? '' : s).replace(/[<>]/g, '').replace(/\s+/g, ' ').trim().slice(0, n);
export const MAX_LOGS = 300;

export async function onRequestGet({ request, env }) {
  const chk = await checkPin(request, env);
  if (chk.res) return chk.res;
  const q = new URL(request.url).searchParams;
  try {
    if (q.get('prices')) { const ck = COUNTRIES.includes(q.get('ck')) ? q.get('ck') : null; return json({ ok: true, countries: await pricesView(env, ck), admin: !!chk.admin }); }
    if (q.get('price')) {
      const r = await priceFor(env, { lat: q.get('lat') != null ? Number(q.get('lat')) : null, lng: Number(q.get('lng')), ck: q.get('ck') || undefined, town: q.get('town') || undefined, fuel: q.get('fuel') || 'petrol' });
      return json({ ok: true, price: r.price, reason: r.reason });
    }
    if (q.get('logs')) { const logs = await fuelLogs(env, String(q.get('logs')).slice(0, 30)); return json({ ok: true, logs: logs.slice().reverse(), real: realFromLogs(logs) }); }
    if (q.get('itin')) {
      const rec = await load(env, q.get('itin'));
      if (!rec) return bad('not-found', 404);
      if (!canSee(chk, rec)) return bad('forbidden', 403);
      const out = []; for (const v of await vehicles(env)) for (const l of await fuelLogs(env, v.vid)) if (l.itinId === rec.id) out.push(l);
      return json({ ok: true, logs: out.sort((a, b) => (a.at < b.at ? 1 : -1)) });
    }
    return bad('bad-request');
  } catch (e) { console.error('fuel get:', e && e.stack || e); return bad('down', 502); }
}

async function cron(request, env) {
  const k = request.headers.get('X-Cron-Key') || '';
  if (!env.CRON_KEY || k.length < 16 || k !== env.CRON_KEY) return bad('pin', 401);
  if (!env.FEED) return bad('no-storage', 503);
  const results = await refreshAll(env);
  return json({ ok: true, results });
}

export async function onRequestPost({ request, env }) {
  const url = new URL(request.url);
  if (url.searchParams.get('cron') === 'prices') return cron(request, env);
  const origin = request.headers.get('Origin');
  if (origin && new URL(origin).host !== url.host) return bad('origin', 403);
  const chk = await checkPin(request, env);
  if (chk.res) return chk.res;
  if (!chk.who) return bad('named-only', 403);
  if (!env.FEED) return bad('no-storage', 503);
  let b; try { b = await request.json(); } catch (e) { return bad('bad-request'); }
  if (!b || typeof b !== 'object') return bad('bad-request');
  try {
    switch (b.action) {
      case 'override': { if (!chk.admin) return bad('not-admin', 403); const r = await setOverride(env, b, chk.who); return json(r, r.ok ? 200 : 400); }
      case 'override-remove': { if (!chk.admin) return bad('not-admin', 403); const r = await removeOverride(env, b); return json(r, r.ok ? 200 : 404); }
      case 'refresh': {
        if (!chk.admin) return bad('not-admin', 403);
        const rk = 'rl:fuelrefresh', n = Number(await env.FEED.get(rk)) || 0;
        if (n >= 20) return bad('rate', 429);
        await env.FEED.put(rk, String(n + 1), { expirationTtl: 3600 });
        return json({ ok: true, results: await refreshAll(env, { only: COUNTRIES.includes(b.ck) ? b.ck : null }) });
      }
      case 'log': {
        const v = (await vehicles(env)).find(x => x.vid === b.vid);
        if (!v) return bad('no-vehicle');
        // a receipt photo from /api/pin-photo (kind=receipt) must exist and belong to this trip
        if (b.receipt) { const ph = /^rc_[A-Za-z0-9]{6,20}$/.test(String(b.receipt)) ? await env.FEED.get('itinphoto:' + b.receipt, 'json') : null; if (!ph || (b.itinId && ph.itin !== b.itinId)) return bad('receipt'); }
        if (b.itinId) { const rec = await load(env, b.itinId); if (!rec) return bad('not-found', 404); if (!canEdit(chk, rec)) return bad('forbidden', 403); }
        const litres = Number(b.litres);
        if (!(litres >= 0.5 && litres <= 400)) return bad('bad-litres');
        // paid in KES, or in TZS / UGX / MWK (then converted with the day's rate; the amount paid is kept as well)
        const cur = ['KES', 'TZS', 'UGX', 'MWK'].includes(b.currency) ? b.currency : 'KES';
        let per = b.perL != null && b.perL !== '' ? Number(b.perL) : b.kesPerL != null && b.kesPerL !== '' ? Number(b.kesPerL) : null;
        let total = b.total != null && b.total !== '' ? Number(b.total) : b.totalKes != null && b.totalKes !== '' ? Number(b.totalKes) : null;
        if (per == null && total != null) per = total / litres;
        if (total == null && per != null) total = per * litres;
        const range = { KES: [20, 2000], TZS: [500, 30000], UGX: [1000, 40000], MWK: [300, 60000] }[cur];
        if (!(per >= range[0] && per <= range[1]) || !(total > 0)) return bad('bad-price', 400, { currency: cur, range });
        let paid = null;
        if (cur !== 'KES') {
          const fx = await fxRates(env), k = convert(1, cur, 'KES', fx);
          if (!k) return bad('no-fx');
          paid = { currency: cur, perL: Math.round(per * 100) / 100, total: Math.round(total), rate: Math.round(k * 1e5) / 1e5, at: fx.at };
          per *= k; total *= k;
        }
        const logs = await fuelLogs(env, v.vid);
        const odo = b.odometer != null && b.odometer !== '' ? Math.round(Number(b.odometer)) : null;
        if (odo != null && (!(odo >= 0 && odo < 5e6) || logs.some(l => l.odometer != null && l.odometer > odo && (!b.at || l.at <= b.at)))) return bad('bad-odometer');
        const at = b.at && !isNaN(Date.parse(b.at)) && Date.parse(b.at) <= Date.now() + 864e5 ? new Date(b.at).toISOString() : new Date().toISOString();
        const log = { lid: newId('fl'), vid: v.vid, itinId: b.itinId || null, at, by: chk.who, litres: Math.round(litres * 100) / 100, kesPerL: Math.round(per * 100) / 100, totalKes: Math.round(total),
          odometer: odo, full: b.full !== false, town: clean(b.town, 60), lat: isFinite(Number(b.lat)) && b.lat != null ? Math.round(Number(b.lat) * 1e5) / 1e5 : null, lng: isFinite(Number(b.lng)) && b.lng != null ? Math.round(Number(b.lng) * 1e5) / 1e5 : null,
          receipt: /^[A-Za-z0-9_-]{1,64}$/.test(String(b.receipt || '')) ? b.receipt : null, note: clean(b.note, 200), paid };
        logs.push(log);
        await putJson(env, 'fuellog:' + v.vid, logs.sort((x, y) => (x.at < y.at ? -1 : 1)).slice(-MAX_LOGS));
        return json({ ok: true, log, real: realFromLogs(logs) }, 201);
      }
      case 'log-remove': {
        const logs = await fuelLogs(env, String(b.vid || '')), i = logs.findIndex(l => l.lid === b.lid);
        if (i < 0) return bad('not-found', 404);
        if (!chk.admin && !sameName(logs[i].by, chk.who)) return bad('forbidden', 403);
        logs.splice(i, 1); await putJson(env, 'fuellog:' + b.vid, logs);
        return json({ ok: true, real: realFromLogs(logs) });
      }
      default: return bad('action');
    }
  } catch (e) { console.error('fuel post:', e && e.stack || e); return bad('down', 502); }
}

export function onRequest() { return json({ ok: false, reason: 'method' }, 405); }
