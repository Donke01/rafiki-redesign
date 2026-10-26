// Fuel use for trip costs: litres per 100 km from the vehicle (type, engine size, fuel), adjusted per leg for the road
// surface and its wetness, the load and stop-start town driving; blended with the vehicle's real figure from its fuel logs.
//
// Every number is in CONSUMPTION below, so it can be tuned in one place:
//
//   L/100 km = base(type, cc band) × fuel × surface × load × town
//
//   base     petrol, flat tarmac, driver only, steady speed — typical figures for East African fleets
//            (Toyota/Nissan/Isuzu/Mitsubishi), by engine size:
//              type        bands (up to cc → L/100 km)
//              motorbike   125 → 2.4 · 150 → 2.7 · 200 → 3.1 · 250 → 3.5 · larger → 4.8
//              saloon      1300 → 6.0 · 1600 → 7.0 · 2000 → 8.0 · 2500 → 9.5 · larger → 11.0
//              suv         2000 → 9.0 · 2500 → 10.5 · 3000 → 12.0 · larger → 14.0
//              pickup      2000 → 9.0 · 2500 → 10.0 · 3000 → 11.5 · larger → 13.0
//              4x4         2500 → 11.0 · 3000 → 12.5 · 4000 → 14.0 · larger → 16.0   (Land Cruiser class)
//              van         2000 → 9.5 · 2500 → 10.5 · 3000 → 12.0 · larger → 13.0
//              minibus     2500 → 11.0 · 3000 → 12.5 · larger → 14.0
//   fuel     petrol 1.00 · diesel 0.85 (a diesel engine of the same size uses about 15% fewer litres)
//   surface  by the leg's tarmac / murram split (lib/roads.js) and its road wetness (lib/weather.js roadLevel):
//              tarmac  dry 1.00 · light 1.00 · wet 1.03 · heavy 1.05
//              murram  dry 1.25 · light 1.32 · wet 1.42 · heavy 1.60
//              unknown surface counts halfway between the two
//   load     +2% per passenger beyond the driver (motorbike: +8% for a pillion) · +1.5% per 100 kg of cargo
//   town     +15% on the km driven within TOWN_KM (4 km) of a town centre (start/end in a town, stops in towns)
//
// Real figures: a vehicle's fuel logs with odometer readings (full tank to full tank) give its real L/100 km. Logs are
// mostly from mixed trips, so the real figure is divided by MIXED (1.12, the model's factor for a typical mix of tarmac,
// murram and towns) to compare with the flat base. From 3 logs it is blended in: weight n / (n + 3), at most 0.8.
// An admin-entered L/100 km replaces the model's base (and is blended with logs the same way).

export const CONSUMPTION = {
  base: {
    motorbike: [[125, 2.4], [150, 2.7], [200, 3.1], [250, 3.5], [Infinity, 4.8]],
    saloon: [[1300, 6.0], [1600, 7.0], [2000, 8.0], [2500, 9.5], [Infinity, 11.0]],
    suv: [[2000, 9.0], [2500, 10.5], [3000, 12.0], [Infinity, 14.0]],
    pickup: [[2000, 9.0], [2500, 10.0], [3000, 11.5], [Infinity, 13.0]],
    '4x4': [[2500, 11.0], [3000, 12.5], [4000, 14.0], [Infinity, 16.0]],
    van: [[2000, 9.5], [2500, 10.5], [3000, 12.0], [Infinity, 13.0]],
    minibus: [[2500, 11.0], [3000, 12.5], [Infinity, 14.0]]
  },
  fuel: { petrol: 1.0, diesel: 0.85 },
  surface: { paved: { dry: 1.0, light: 1.0, wet: 1.03, heavy: 1.05 }, unpaved: { dry: 1.25, light: 1.32, wet: 1.42, heavy: 1.6 } },
  load: { perPassenger: 0.02, pillion: 0.08, per100kg: 0.015 },
  town: { factor: 1.15, km: 4 },
  mixed: 1.12,
  blend: { min: 3, maxWeight: 0.8 }
};
export const TYPES = Object.keys(CONSUMPTION.base);
export const FUELS = Object.keys(CONSUMPTION.fuel);
export const TOWN_KM = CONSUMPTION.town.km;
const r1 = v => Math.round(v * 10) / 10, r2 = v => Math.round(v * 100) / 100;

/** The model's flat-road figure for a vehicle: { lPer100, base, fuelFactor, band } (null for an unknown type). */
export function modelFor(v) {
  const bands = CONSUMPTION.base[v && v.type];
  if (!bands) return null;
  const cc = Number(v.cc) || 0;
  let lo = 0, hit = bands[bands.length - 1];
  for (const b of bands) { if (cc <= b[0]) { hit = b; break; } lo = b[0]; }
  const fuelFactor = v.type === 'motorbike' ? 1 : (CONSUMPTION.fuel[v.fuel] || 1);
  const band = hit[0] === Infinity ? 'over ' + lo + ' cc' : (lo ? lo + 1 : 0) + '–' + hit[0] + ' cc';
  return { lPer100: r2(hit[1] * fuelFactor), base: hit[1], fuelFactor, band };
}

/**
 * The real figure from fuel logs: full-tank logs sorted by odometer; litres bought after the first full tank,
 * over the km between the first and last full tank. → { lPer100, logs, km } or null (needs at least 2 full tanks
 * and 50 km). Logs that only topped up (full: false) count towards the litres between two full tanks.
 */
export function realFromLogs(logs) {
  const L = (logs || []).filter(l => Number(l.odometer) > 0 && Number(l.litres) > 0).sort((a, b) => a.odometer - b.odometer || (a.at < b.at ? -1 : 1));
  const fulls = L.filter(l => l.full !== false);
  if (fulls.length < 2) return null;
  const first = fulls[0], last = fulls[fulls.length - 1];
  const km = last.odometer - first.odometer;
  if (km < 50) return null;
  const litres = L.filter(l => l.odometer > first.odometer && l.odometer <= last.odometer).reduce((s, l) => s + Number(l.litres), 0);
  const lp = litres / km * 100;
  if (!(lp > 0.5 && lp < 60)) return null;
  return { lPer100: r2(lp), logs: L.length, km: Math.round(km) };
}

/** What trip costs start from: { lPer100, src: "blend"|"entered"|"model"|"logs", weight, model, real } */
export function useFor(v, logs) {
  const model = modelFor(v);
  const entered = Number(v && v.lPer100) > 0 ? Number(v.lPer100) : null;
  const real = realFromLogs(logs);
  let base = entered != null ? entered : model ? model.lPer100 : null;
  let src = entered != null ? 'entered' : 'model', weight = 0;
  const n = (logs || []).length;
  if (real && n >= CONSUMPTION.blend.min) {
    weight = r2(Math.min(CONSUMPTION.blend.maxWeight, n / (n + 3)));
    const flat = real.lPer100 / CONSUMPTION.mixed;
    src = base == null ? 'logs' : 'blend';
    base = base == null ? flat : (1 - weight) * base + weight * flat;
  }
  return { lPer100: base == null ? null : r2(base), src, weight, model, real };
}

/** The load factor for a trip: passengers (people in the vehicle besides the driver) and cargo kg. */
export function loadFactor(v, passengers, cargoKg) {
  const L = CONSUMPTION.load, p = Math.max(0, Math.min(30, Number(passengers) || 0)), kg = Math.max(0, Math.min(5000, Number(cargoKg) || 0));
  if (v && v.type === 'motorbike') return 1 + (p > 0 ? L.pillion : 0) + kg / 100 * L.per100kg;
  return 1 + p * L.perPassenger + kg / 100 * L.per100kg;
}

/**
 * Litres for one leg. leg: { km, surface: { pavedKm, unpavedKm, unknownKm, likely }, roadLevel, townKm }
 * base: flat L/100 km for the vehicle (useFor(...).lPer100); load: loadFactor(...)
 * → { litres, lPer100, factors: { surface, town, load } }
 */
export function legLitres(leg, base, load) {
  const km = Math.max(0, Number(leg && leg.km) || 0);
  if (!km || !(base > 0)) return { litres: 0, lPer100: base || 0, factors: { surface: 1, town: 1, load: load || 1 } };
  const lv = CONSUMPTION.surface.paved[leg.roadLevel] ? leg.roadLevel : 'dry';
  const s = leg.surface || {}, lk = s.likely || {};
  let pav = (Number(s.pavedKm) || 0) + (Number(lk.pavedKm) || 0), unp = (Number(s.unpavedKm) || 0) + (Number(lk.unpavedKm) || 0);
  let unk = Math.max(0, (Number(s.unknownKm) || 0) - (Number(lk.pavedKm) || 0) - (Number(lk.unpavedKm) || 0));
  const tot = pav + unp + unk;
  if (tot <= 0) { pav = km; unp = 0; unk = 0; } else { const k = km / tot; pav *= k; unp *= k; unk *= k; }
  const P = CONSUMPTION.surface.paved[lv], U = CONSUMPTION.surface.unpaved[lv];
  const surface = (pav * P + unp * U + unk * (P + U) / 2) / km;
  const townKm = Math.min(km, Math.max(0, Number(leg.townKm) || 0));
  const town = 1 + (CONSUMPTION.town.factor - 1) * townKm / km;
  const l100 = base * surface * town * (load || 1);
  return { litres: r2(km * l100 / 100), lPer100: r2(l100), factors: { surface: r2(surface), town: r2(town), load: r2(load || 1) } };
}

/** "Hilux KDA 123X · 2400 cc diesel pickup · model 8.5 L/100 km (4 logs blended) · murram and wet roads included" */
export function basisWords(v, use) {
  if (!v) return { en: 'No vehicle chosen', sw: 'Hakuna gari lililochaguliwa' };
  const FUEL_SW = { petrol: 'petroli', diesel: 'dizeli' }, TYPE_SW = { motorbike: 'pikipiki', saloon: 'saloon', suv: 'SUV', pickup: 'pickup', '4x4': '4x4', van: 'van', minibus: 'matatu' };
  const head = (v.name || v.plate || 'Vehicle') + ' · ' + (v.cc ? v.cc + ' cc ' : '');
  const src = use.src === 'entered' ? { en: 'entered', sw: 'iliyowekwa' } : use.src === 'blend' ? { en: 'model and ' + (use.real ? use.real.logs : 0) + ' fuel logs', sw: 'kadirio na kumbukumbu ' + (use.real ? use.real.logs : 0) + ' za mafuta' } : use.src === 'logs' ? { en: 'fuel logs', sw: 'kumbukumbu za mafuta' } : { en: 'model', sw: 'kadirio' };
  const l = use.lPer100 == null ? '–' : String(r1(use.lPer100));
  return {
    en: head + (v.type === 'motorbike' ? '' : v.fuel + ' ') + v.type + ' · ' + l + ' L/100 km (' + src.en + '), adjusted for murram, rain, load and towns',
    sw: (v.name || v.plate || 'Gari') + ' · ' + (v.cc ? 'cc ' + v.cc + ' ' : '') + (TYPE_SW[v.type] || v.type) + (v.type === 'motorbike' ? '' : ' ' + (FUEL_SW[v.fuel] || v.fuel)) + ' · L ' + l + ' kwa km 100 (' + src.sw + '), pamoja na murram, mvua, mzigo na miji'
  };
}
