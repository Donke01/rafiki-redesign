// Exchange rates for server-side totals (fuel prices in TZS/UGX/MWK shown in KES, group-trip totals in USD and KES).
// The same free source as /api/fx (open.er-api.com, no key), kept in KV for a day. When it can't be reached the last
// rates are used (with their date); when there have never been any, null, and callers show the amount in its own
// currency only. No made-up rates here: an estimate in money must say where its numbers came from.
import { getJson, putJson } from './pin-store.js';

export const FX_SOURCE = 'https://open.er-api.com/v6/latest/USD';
export const FX_KEY = 'fx:usd';
const DAY = 864e5;

export async function fxRates(env, now) {
  const t = now || Date.now();
  const cur = await getJson(env, FX_KEY, null);
  if (cur && cur.rates && t - (cur.got || 0) < DAY) return cur;
  try {
    const r = await fetch(FX_SOURCE, { headers: { Accept: 'application/json' } });
    if (r.ok) {
      const j = await r.json();
      if (j && j.result === 'success' && j.rates && Number(j.rates.KES) > 0) {
        const rates = {}; for (const c of ['USD', 'KES', 'UGX', 'TZS', 'MWK', 'GBP', 'EUR', 'CAD', 'AUD']) { const v = Number(j.rates[c]); if (v > 0) rates[c] = v; }
        const at = j.time_last_update_unix ? new Date(j.time_last_update_unix * 1000).toISOString().slice(0, 10) : new Date(t).toISOString().slice(0, 10);
        const out = { rates, at, got: t };
        await putJson(env, FX_KEY, out);
        return out;
      }
    }
  } catch (e) {}
  return cur && cur.rates ? cur : null;
}
/** amount in `from` → `to` with rates (per USD), or null. */
export function convert(amount, from, to, fx) {
  if (from === to) return amount;
  const r = fx && fx.rates, a = r && r[from], b = r && r[to];
  return a > 0 && b > 0 ? amount / a * b : null;
}
