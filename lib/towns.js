// Town centres used by the itinerary planner: to pick the fuel price of the nearest listed town (EPRA, EWURA…),
// to suggest a town to stay overnight, and to count stop-start town driving in the fuel model.
// [lat, lng], rounded to ~1 km. Only towns whose position is known well are here; a price list can name more towns
// (EPRA lists ~230), and those are still stored, but only towns in this table can be "the nearest".
// Names are written as the official price lists write them (EWURA's "Kilimanjaro (Moshi)" etc.), so they match.
import { metres } from './roads.js';

export const TOWNS = {
  ke: {
    Nairobi: [-1.286, 36.817], Mombasa: [-4.043, 39.668], Nakuru: [-0.303, 36.08], Eldoret: [0.514, 35.27], Kisumu: [-0.092, 34.768],
    Kitale: [1.015, 35.006], Kakamega: [0.283, 34.752], Bungoma: [0.563, 34.561], Webuye: [0.607, 34.77], Busia: [0.461, 34.111],
    Malaba: [0.636, 34.282], Mumias: [0.336, 34.488], Iten: [0.671, 35.508], Kapenguria: [1.239, 35.112], Kapsabet: [0.204, 35.105],
    Kericho: [-0.368, 35.283], Kisii: [-0.682, 34.767], Migori: [-1.063, 34.473], Homabay: [-0.527, 34.457], Siaya: [0.061, 34.288],
    Bondo: [-0.098, 34.27], Nyamira: [-0.563, 34.935], Bomet: [-0.782, 35.342], Narok: [-1.078, 35.871], Naivasha: [-0.717, 36.431],
    Gilgil: [-0.499, 36.32], Nyahururu: [0.038, 36.363], Nanyuki: [0.017, 37.073], Nyeri: [-0.42, 36.947], Thika: [-1.033, 37.069],
    Machakos: [-1.517, 37.263], Embu: [-0.531, 37.45], Meru: [0.047, 37.649], Isiolo: [0.354, 37.582], Garissa: [-0.453, 39.646],
    Lodwar: [3.119, 35.597], Kabarnet: [0.492, 35.743], 'Eldama Ravine': [0.051, 35.723], Molo: [-0.249, 35.732], Londiani: [-0.161, 35.596],
    'Burnt Forest': [0.217, 35.433], Turbo: [0.631, 35.05], "Moi's Bridge": [0.877, 35.122], Kiminini: [0.894, 34.917], Endebes: [1.072, 34.851],
    Kimilili: [0.787, 34.721], Malava: [0.446, 34.855], Moiben: [0.812, 35.382], 'Nandi Hills': [0.103, 35.183], Kitui: [-1.367, 38.01],
    Malindi: [-3.219, 40.117], Kilifi: [-3.63, 39.85], Voi: [-3.396, 38.556], Lamu: [-2.27, 40.902], Marsabit: [2.335, 37.99],
    Maralal: [1.097, 36.698], Kajiado: [-1.852, 36.776], Muranga: [-0.721, 37.152], Kerugoya: [-0.499, 37.28], Chuka: [-0.333, 37.646],
    Mwingi: [-0.934, 38.06], Wajir: [1.747, 40.057], Mandera: [3.937, 41.856], Moyale: [3.527, 39.056], Kakuma: [3.712, 34.857],
    Ahero: [-0.174, 34.919], Muhoroni: [-0.153, 35.198], Oyugis: [-0.508, 34.735], Rongo: [-0.758, 34.598], Isebania: [-1.233, 34.478],
    Sotik: [-0.68, 35.12], Keroka: [-0.776, 34.946], Kilgoris: [-1.004, 34.877], Olkalou: [-0.27, 36.38], Rumuruti: [0.272, 36.536]
  },
  tz: {
    'Dar es Salaam': [-6.792, 39.208], Arusha: [-3.387, 36.683], Dodoma: [-6.163, 35.752], Mwanza: [-2.516, 32.917], 'Kilimanjaro (Moshi)': [-3.335, 37.34],
    Tanga: [-5.069, 39.099], Morogoro: [-6.821, 37.661], Mbeya: [-8.909, 33.46], Iringa: [-7.77, 35.693], Tabora: [-5.016, 32.828],
    Kigoma: [-4.877, 29.627], 'Mara (Musoma)': [-1.5, 33.8], Singida: [-4.816, 34.744], Shinyanga: [-3.662, 33.421], 'Kagera (Bukoba)': [-1.332, 31.812],
    Geita: [-2.873, 32.233], 'Manyara (Babati)': [-4.212, 35.748], Lindi: [-9.997, 39.714], Mtwara: [-10.267, 40.183], 'Ruvuma (Songea)': [-10.683, 35.65],
    Njombe: [-9.333, 34.767], 'Rukwa (Sumbawanga)': [-7.967, 31.617], 'Katavi (Mpanda)': [-6.343, 31.069], 'Simiyu (Bariadi)': [-2.8, 33.983],
    'Pwani (Kibaha)': [-6.767, 38.917], Karatu: [-3.34, 35.67], 'Chalinze Junction': [-6.637, 38.353]
  },
  ug: {
    Kampala: [0.347, 32.582], Jinja: [0.424, 33.204], Mbale: [1.082, 34.175], Gulu: [2.774, 32.299], Mbarara: [-0.607, 30.654],
    Tororo: [0.693, 34.181], Soroti: [1.715, 33.611], Lira: [2.249, 32.9], 'Fort Portal': [0.671, 30.275], Masaka: [-0.333, 31.733],
    Busia: [0.467, 34.09], Hoima: [1.433, 31.35], Arua: [3.02, 30.911], Kapchorwa: [1.4, 34.45]
  },
  mw: {
    Lilongwe: [-13.963, 33.774], Blantyre: [-15.786, 35.006], Mzuzu: [-11.465, 34.02], Zomba: [-15.386, 35.318], Kasungu: [-13.033, 33.483], Mangochi: [-14.478, 35.264]
  }
};
export const CAPITAL = { ke: 'Nairobi', tz: 'Dar es Salaam', ug: 'Kampala', mw: 'Lilongwe' };

// "Kilimanjaro (Moshi)" → ["kilimanjaro moshi", "kilimanjaro", "moshi"]; "Moi&#039;s Bridge" → "mois bridge"
export const normTown = s => String(s || '').replace(/&#0?39;|&apos;|[’']/g, '').replace(/&amp;/g, '&').toLowerCase().replace(/[^a-z0-9()]+/g, ' ').trim();
export function townKeys(name) {
  const n = normTown(name), out = [n.replace(/[()]/g, '').replace(/\s+/g, ' ').trim()];
  const m = /^(.*?)\s*\(([^)]*)\)/.exec(n);
  if (m) { if (m[1].trim()) out.push(m[1].trim()); if (m[2].trim()) out.push(m[2].trim()); }
  return [...new Set(out.filter(Boolean))];
}
/** Position of a town name in a country, or null. */
export function townPoint(ck, name) {
  const t = TOWNS[ck] || {}, keys = townKeys(name);
  for (const [k, p] of Object.entries(t)) { const kk = townKeys(k); if (kk.some(x => keys.includes(x))) return p; }
  return null;
}
/** The nearest known town: { ck, town, lat, lng, km }. ck limits the search to one country. */
export function nearestTown(p, ck, filter) {
  let best = null;
  for (const [c, list] of Object.entries(TOWNS)) {
    if (ck && c !== ck) continue;
    for (const [town, q] of Object.entries(list)) {
      if (filter && !filter(c, town)) continue;
      const km = metres(p, q) / 1000;
      if (!best || km < best.km) best = { ck: c, town, lat: q[0], lng: q[1], km: Math.round(km * 10) / 10 };
    }
  }
  return best;
}
/** The country a point is in (nearest known town within 400 km), or ''. */
export function countryOf(p) { const t = nearestTown(p); return t && t.km < 400 ? t.ck : ''; }
