// Reads the RAFIKI Outreach "Projects" board on monday.com and turns it into the
// public project feed the website uses. Only the fields listed in PUBLIC below
// ever leave this file: costs (except for projects still looking for funding),
// invoices, people, drilling companies, co-sponsors and internal notes stay in monday.com.

export const API_URL = 'https://api.monday.com/v2';
export const BOARD_ID = '9412605754';

// Column ids on the Projects board (monday.com → board → column menu → "Column information").
const COL = {
  country: 'country_mkswymy5',
  status: 'single_selectxrldna6',
  type: 'color_mkrccgae',
  village: 'text_mks3h8ek',
  location: 'location_mksxemjp',
  people: 'numeric_mks3m7q8',
  completed: 'datenk2ra6oh',
  photos: 'file_mkrcpk9c',
  pump: 'color_mkrcrpbw',
  cost: 'number0zb8ppzw',
  funding: 'text_mkwcqg8j',
  depth: 'numeric_mkrc9d7f', // "Drill Depth? (Meters)"
  flow: 'numeric_mks391fs', // "Flow Rate", litres per hour
  driller: 'color_mkrc44xb'
};
// Wells this deep or shallower count as shallow (hand-drilled, e.g. The Village Drill); deeper ones as deep boreholes (drilling rig).
const SHALLOW_MAX_M = 60;

// Board status → website status. Anything not listed (due diligence, failed
// attempts, needs attention, blank) is left off the website.
const STATUS = {
  'Completed/Operational': 'Completed',
  'Under Construction': 'Under construction',
  'Planned and/or Permitted': 'Upcoming',
  'Approved - Ready to Fund': 'Upcoming',
  'Recommended to Fund - Awaiting Approval': 'Upcoming'
};

const TYPE = {
  'Water (Drilling)': 'Borehole well',
  'Water (Collection)': 'Water collection',
  'Educational': 'School',
  'Medical': 'Health',
  'Vocational Training': 'Training',
  'Community': 'Community',
  'Agricultural': 'Farming',
  'Orphanage': 'Orphanage',
  'Nutrition': 'Nutrition',
  'Other': 'Other'
};
const WATER = { 'Borehole well': true, 'Water collection': true };

const PUMP = { 'Hand Pump': 'Manual', 'Electric Pump': 'Electric', 'Solar Pump': 'Solar' };

const COUNTRY = { KE: 'ke', TZ: 'tz', UG: 'ug', MW: 'mw' };
const COUNTRY_NAME = { ke: 'Kenya', tz: 'Tanzania', ug: 'Uganda', mw: 'Malawi' };

// Browsers can show these; HEIC photos, videos and Google Drive links are skipped.
const PHOTO_EXT = /\.(jpe?g|jfif|png|webp|gif)$/i;
const MAX_PHOTOS = 6;

// An optional checkbox column titled "Show on website" switches projects on one by one.
const SHOW_TITLE = /^show on (the )?website$/i;

const ITEM_FIELDS = 'id name column_values { id text value }';

async function gql(token, query, variables, apiUrl) {
  const res = await fetch(apiUrl || API_URL, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: token },
    body: JSON.stringify({ query, variables })
  });
  if (!res.ok) throw new Error('monday.com answered ' + res.status);
  const body = await res.json();
  if (body.errors && body.errors.length) throw new Error('monday.com: ' + (body.errors[0].message || 'query failed'));
  return body.data;
}

export async function fetchBoard(token, boardId, apiUrl) {
  const first = await gql(token,
    'query ($ids: [ID!]) { boards(ids: $ids) { columns { id title type } items_page(limit: 500) { cursor items { ' + ITEM_FIELDS + ' } } } }',
    { ids: [String(boardId)] }, apiUrl);
  const board = first.boards && first.boards[0];
  if (!board) throw new Error('board ' + boardId + ' not found, or the API token cannot see it');
  const items = board.items_page.items.slice();
  let cursor = board.items_page.cursor;
  for (let page = 0; cursor && page < 20; page++) {
    const next = await gql(token,
      'query ($c: String!) { next_items_page(cursor: $c, limit: 500) { cursor items { ' + ITEM_FIELDS + ' } } }',
      { c: cursor }, apiUrl);
    items.push(...next.next_items_page.items);
    cursor = next.next_items_page.cursor;
  }
  return { columns: board.columns || [], items };
}

export async function assetUrl(token, assetId, apiUrl) {
  const a = await assetInfo(token, assetId, apiUrl);
  return a && a.public_url;
}

// One file's links and size. public_url is a signed copy of the original that works
// for an hour without a login; url_thumbnail is monday.com's small preview, which sits
// behind a monday.com login (protected_static) and may not open with the API token.
export async function assetInfo(token, assetId, apiUrl) {
  const data = await gql(token,
    'query ($ids: [ID!]!) { assets(ids: $ids) { id public_url url_thumbnail file_size original_geometry } }',
    { ids: [String(assetId)] }, apiUrl);
  const a = data.assets && data.assets[0];
  if (!a || !a.public_url) return null;
  return { public_url: a.public_url, url_thumbnail: a.url_thumbnail || '', file_size: Number(a.file_size) || 0, geometry: a.original_geometry || '' };
}

function parse(v) { try { return v ? JSON.parse(v) : null; } catch (e) { return null; } }
function num(t) { const n = Number(String(t || '').replace(/[^0-9.]/g, '')); return isFinite(n) && n > 0 ? Math.round(n) : 0; }

function photos(cv) {
  if (!cv) return [];
  const out = [];
  const files = (parse(cv.value) || {}).files || [];
  for (const f of files) {
    if (f && f.assetId && PHOTO_EXT.test(f.name || '')) out.push(String(f.assetId));
  }
  // older API versions only give the file links in text
  if (!files.length && cv.text) {
    for (const url of cv.text.split(/,\s*/)) {
      const m = /\/resources\/(\d+)\/([^?#]+)$/.exec(url);
      if (m && PHOTO_EXT.test(decodeURIComponent(m[2]))) out.push(m[1]);
    }
  }
  return out.slice(0, MAX_PHOTOS);
}

function area(village, location) {
  // "Magara, Manyara, Tanzania" → "Magara, Manyara"; GPS pairs and country names are dropped
  const clean = s => (s || '').split(',').map(x => x.trim())
    .filter(x => x && !/^-?\d+(\.\d+)?$/.test(x) && !/^(kenya|tanzania|uganda|malawi|tz|ke)$/i.test(x));
  const out = clean(location).length ? clean(location) : clean(village);
  return out.slice(0, 2).join(', ');
}

// ---- tidying names for the public site (the board itself is left as it is) ----
// Board entries that are Rafiki's own buildings, not projects.
const INTERNAL = /\b(office|store ?house|warehouse)\b/i;
// Short names that stay in capitals when an ALL-CAPS name is re-cased.
const ACRONYM = new Set(['ACK', 'AIC', 'ECDE', 'PAG', 'TAG', 'KKKT', 'ADC', 'SDA', 'PCEA', 'AIPCA', 'ELCT', 'ROAM', 'II', 'III', 'IV']);
const SMALL = new Set(['of', 'and', 'the', 'la', 'ya', 'wa', 'kwa', 'na', 'de']);

function recase(s) {
  // ALL-CAPS or all-lowercase text becomes Title Case; mixed case is kept as typed
  const letters = s.replace(/[^A-Za-z]/g, '');
  if (letters.length < 3) return s;
  const upper = letters.replace(/[^A-Z]/g, '').length;
  if (upper / letters.length < 0.8 && upper > 0) return s;
  return s.replace(/[A-Za-z][A-Za-z'’]*/g, (w, i) => {
    if ((ACRONYM.has(w.toUpperCase()) && upper > 0) || w.length === 1) return w.toUpperCase();
    const lw = w.toLowerCase();
    if (i > 0 && SMALL.has(lw)) return lw;
    return lw.charAt(0).toUpperCase() + lw.slice(1);
  });
}

// Spelling slips on the board, corrected for the public site only.
const SPELL = { pripary: 'Primary', dispenserary: 'Dispensary', mokoiywet: 'Mokoiyet', ngarenero: 'Ngarenaro', kifungulo: 'Kifungilo', beds: 'Beds' };
// Plain English words typed in lower case inside otherwise capitalised names ("Chepkongi primary school").
const CAP = new Set(['primary', 'secondary', 'school', 'village', 'well', 'dispensary', 'plumbing', 'electrical', 'washrooms', 'sosiani', 'tower', 'paint', 'furniture', 'desks']);
function spell(s) {
  return s.replace(/[A-Za-z][A-Za-z'’]*/g, w => {
    const lw = w.toLowerCase();
    if (SPELL[lw]) return SPELL[lw];
    return CAP.has(w) ? w.charAt(0).toUpperCase() + w.slice(1) : w;
  });
}

export function tidyTitle(s) {
  const t = spell(recase(String(s || '').replace(/^[\s*#_-]+|[\s*#_]+$/g, '').replace(/\s+/g, ' ')));
  return t.charAt(0).toUpperCase() + t.slice(1);
}

export function tidyArea(s) {
  // "kaigat, eldoret" → "Kaigat, Eldoret"; "lamanda, kwa Sanare" → "Lamanda, kwa Sanare"
  return String(s || '').split(',').map(x => spell(recase(x.trim()))).filter(Boolean)
    .map(x => x.charAt(0).toUpperCase() + x.slice(1)).join(', ');
}

// Several entries with the same name get told apart by their place, then by number.
export function dedupeTitles(projects) {
  const key = p => p.title.toLowerCase();
  const groups = new Map();
  for (const p of projects) { if (!groups.has(key(p))) groups.set(key(p), []); groups.get(key(p)).push(p); }
  for (const list of groups.values()) {
    if (list.length < 2) continue;
    for (const p of list) {
      const have = new Set(p.title.toLowerCase().split(/[^a-z0-9']+/).filter(Boolean));
      const extra = (p.area || '').split(',').map(x => x.trim())
        .find(x => x && x.toLowerCase().split(/[^a-z0-9']+/).filter(Boolean).some(w => !have.has(w)));
      if (extra) p.title = p.title + ' · ' + extra;
    }
    const seen = new Map();
    for (const p of list) seen.set(key(p), (seen.get(key(p)) || 0) + 1);
    const n = new Map();
    for (const p of list) {
      if (seen.get(key(p)) < 2) continue;
      const k = key(p); n.set(k, (n.get(k) || 0) + 1);
      p.title = p.title + ' #' + n.get(k);
    }
  }
  return projects;
}

function year(completed, funding) {
  const m = /(\d{4})/.exec(completed || '') || /(\d{4})/.exec(funding || '');
  return m ? Number(m[1]) : null;
}

export function buildFeed(columns, items) {
  const show = columns.find(c => c.type === 'checkbox' && SHOW_TITLE.test((c.title || '').trim()));
  const projects = [];
  const costRows = [];
  for (const it of items) {
    const v = {};
    for (const cv of it.column_values || []) v[cv.id] = cv;
    const text = id => (v[id] && v[id].text) || '';
    if (show) {
      const chk = parse(v[show.id] && v[show.id].value);
      if (!(chk && (chk.checked === true || chk.checked === 'true'))) continue;
    }
    const status = STATUS[text(COL.status)];
    if (!status) continue;
    const cval = parse(v[COL.country] && v[COL.country].value) || {};
    const ck = COUNTRY[(cval.countryCode || '').toUpperCase()] ||
      Object.keys(COUNTRY_NAME).find(k => COUNTRY_NAME[k].toLowerCase() === text(COL.country).toLowerCase());
    if (!ck) continue;
    const type = TYPE[text(COL.type)] || 'Other';
    const title = tidyTitle(it.name);
    if (!title || (type === 'Other' && INTERNAL.test(title))) continue;
    const p = {
      id: String(it.id),
      title,
      country: COUNTRY_NAME[ck],
      ck,
      area: tidyArea(area(text(COL.village), text(COL.location))),
      type,
      water: !!WATER[type],
      pump: PUMP[text(COL.pump)] || '',
      people: num(text(COL.people)),
      year: year(text(COL.completed), text(COL.funding)),
      status,
      photos: photos(v[COL.photos])
    };
    // well depth (metres) and flow rate (litres per hour), when the board has them
    if (num(text(COL.depth))) p.depth = num(text(COL.depth));
    if (num(text(COL.flow))) p.flow = num(text(COL.flow));
    // the amount still to raise is shown only while a project is looking for funding
    if (status === 'Upcoming' && num(text(COL.cost))) p.needed = num(text(COL.cost));
    projects.push(p);
    // only totals and medians of costs leave this file, never a single project's cost
    if (status === 'Completed' && type === 'Borehole well' && num(text(COL.cost)) && p.people) {
      const depth = num(text(COL.depth));
      const shallow = depth ? depth <= SHALLOW_MAX_M : /rafiki/i.test(text(COL.driller));
      costRows.push({ cost: num(text(COL.cost)), people: p.people, depth, shallow });
    }
  }
  dedupeTitles(projects);
  projects.sort((a, b) => (b.year || 0) - (a.year || 0) || a.title.localeCompare(b.title, 'en', { numeric: true }));

  const blank = () => ({ wells: 0, wellsUnderConstruction: 0, projects: 0, underConstruction: 0, upcoming: 0, people: 0 });
  const countries = { ke: blank(), tz: blank(), ug: blank(), mw: blank() };
  const total = blank();
  for (const p of projects) {
    for (const s of [countries[p.ck], total]) {
      if (p.status === 'Completed') {
        s.projects++;
        if (p.type === 'Borehole well') s.wells++;
        if (p.water) s.people += p.people;
      } else if (p.status === 'Under construction') {
        s.underConstruction++;
        if (p.type === 'Borehole well') s.wellsUnderConstruction++;
      } else s.upcoming++;
    }
  }
  const wellPeople = projects.filter(p => p.status === 'Completed' && p.type === 'Borehole well' && p.people);
  total.peoplePerWell = wellPeople.length ? Math.round(wellPeople.reduce((a, p) => a + p.people, 0) / wellPeople.length) : 0;

  return { source: 'monday.com', updated: new Date().toISOString(), filteredBy: show ? show.title : null, summary: { total, countries, cost: costSummary(costRows) }, projects };
}

function median(a) { const b = a.slice().sort((x, y) => x - y); const m = b.length >> 1; return b.length ? (b.length % 2 ? b[m] : (b[m - 1] + b[m]) / 2) : 0; }
const round = (n, to) => Math.round(n / to) * to;

// Average cost per person across completed wells, and the typical shallow and deep well.
export function costSummary(rows) {
  if (!rows.length) return null;
  const cost = rows.reduce((a, r) => a + r.cost, 0), people = rows.reduce((a, r) => a + r.people, 0);
  const kind = list => list.length ? {
    wells: list.length,
    cost: round(median(list.map(r => r.cost)), 100),
    people: round(median(list.map(r => r.people)), 50),
    depth: Math.round(median(list.filter(r => r.depth).map(r => r.depth)) || 0)
  } : null;
  return {
    wells: rows.length,
    people,
    perPerson: Math.round(cost / people * 100) / 100,
    shallow: kind(rows.filter(r => r.shallow)),
    deep: kind(rows.filter(r => !r.shallow))
  };
}

export function photoIds(feed) {
  const ids = new Set();
  for (const p of (feed && feed.projects) || []) for (const id of p.photos || []) ids.add(String(id));
  return ids;
}
