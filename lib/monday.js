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
  driller: 'color_mkrc44xb',
  gps: 'text_mks3437k' // "GPS Coordinates", written as "lat, lng" (the pin app writes it too)
};
// The story columns (Stories tab in the pin app, lib/story.js). Only an Approved or Published story reaches the website.
export const STORY_COL = {
  before: 'file_mm8aa295', progress: 'file_mm8as38b', after: 'file_mm8avqb5',
  q1: 'long_text_mm8a22e7', q2: 'long_text_mm8a2mhf', q3: 'text_mm8a4vr', q4: 'long_text_mm8aghp4', q5: 'long_text_mm8adpct', q6: 'long_text_mm8awqp3',
  quote: 'long_text_mm8aaxsk', quoteBy: 'text_mm8a7p9', credit: 'text_mm8abhj7', text: 'long_text_mm8aj2j2',
  consent: 'color_mm8axnqx', status: 'color_mm8ag46z'
};
export const STORY_LONG = { q1: 1, q2: 1, q4: 1, q5: 1, q6: 1, quote: 1, text: 1 };

// GPS text from the board → [lat, lng], or null when missing or clearly wrong.
// Only points inside East Africa (Kenya, Tanzania, Uganda, Malawi and around) are accepted.
export function parseGps(t) {
  const m = /(-?\d{1,2}(?:\.\d+)?)\s*[,; ]\s*(-?\d{1,3}(?:\.\d+)?)/.exec(String(t || ''));
  if (!m) return null;
  const lat = Number(m[1]), lng = Number(m[2]);
  if (!(lat >= -18 && lat <= 6 && lng >= 28 && lng <= 42)) return null;
  return [Math.round(lat * 1e6) / 1e6, Math.round(lng * 1e6) / 1e6];
}
export const GPS_COLUMN = COL.gps;
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
// "Beyond the well": every project that is not water, grouped for the website.
const CAT = { 'Borehole well': 'water', 'Water collection': 'water', 'Health': 'health', 'School': 'school', 'Orphanage': 'children',
  'Farming': 'livelihoods', 'Training': 'livelihoods', 'Nutrition': 'livelihoods', 'Community': 'community', 'Other': 'community' };
export const CATS = ['health', 'school', 'children', 'livelihoods', 'community'];

const PUMP = { 'Hand Pump': 'Manual', 'Electric Pump': 'Electric', 'Solar Pump': 'Solar' };

const COUNTRY = { KE: 'ke', TZ: 'tz', UG: 'ug', MW: 'mw' };
const COUNTRY_NAME = { ke: 'Kenya', tz: 'Tanzania', ug: 'Uganda', mw: 'Malawi' };

// Browsers can show these; HEIC photos, videos and Google Drive links are skipped.
const PHOTO_EXT = /\.(jpe?g|jfif|png|webp|gif)$/i;
const MAX_PHOTOS = 6;
// Projects whose board photos are kept off the website until better ones arrive (the board is unchanged).
const HIDE_PHOTOS = { '10084384191': 'Bishop Muge Memorial' };

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

// Pin app: read one project's current GPS text, then write a new point and leave a note on the item.
export async function readGps(token, itemId, apiUrl) {
  const d = await gql(token, 'query ($ids: [ID!]) { items(ids: $ids) { id name board { id } column_values(ids: ["' + COL.gps + '"]) { text } } }', { ids: [String(itemId)] }, apiUrl);
  const it = d.items && d.items[0];
  if (!it) return null;
  return { id: String(it.id), name: it.name, board: it.board && String(it.board.id), text: (it.column_values && it.column_values[0] && it.column_values[0].text) || '' };
}
export async function writeGps(token, boardId, itemId, value, note, apiUrl) {
  await gql(token, 'mutation ($b: ID!, $i: ID!, $c: String!, $v: String) { change_simple_column_value(board_id: $b, item_id: $i, column_id: $c, value: $v) { id } }',
    { b: String(boardId), i: String(itemId), c: COL.gps, v: value }, apiUrl);
  return note ? createUpdate(token, itemId, note, apiUrl) : null;
}
// Leaves a note on an item; returns the note id (or null if it could not be added).
export async function createUpdate(token, itemId, body, apiUrl) {
  try {
    const d = await gql(token, 'mutation ($i: ID!, $t: String!) { create_update(item_id: $i, body: $t) { id } }', { i: String(itemId), t: body }, apiUrl);
    return d && d.create_update ? String(d.create_update.id) : null;
  } catch (e) { console.error('note:', e.message); return null; }
}
// Sign-in with monday.com: who a token belongs to, and whether it can see the projects board.
export async function whoAmI(token, boardId, apiUrl) {
  // only fields monday.com has kept across API versions; the photo is asked for on its own (below),
  // so a renamed photo field can never turn a good token away
  const d = await gql(token, 'query ($b: [ID!]) { me { id name account { id slug } } boards(ids: $b) { id } }', { b: [String(boardId || BOARD_ID)] }, apiUrl);
  const me = d && d.me;
  if (!me) return null;
  const photo = await myPhoto(token, apiUrl);
  return { id: String(me.id), name: me.name || '', photo, account: me.account ? String(me.account.id) : '', slug: me.account ? me.account.slug || '' : '', board: !!(d.boards && d.boards.length) };
}
// The person's small profile photo, or '' (newer API: photo_url { thumb_small }; older: photo_thumb_small).
async function myPhoto(token, apiUrl) {
  const a = await gql(token, '{ me { photo_url { thumb_small } } }', {}, apiUrl).catch(() => null);
  if (a && a.me && a.me.photo_url) return a.me.photo_url.thumb_small || '';
  const b = await gql(token, '{ me { photo_thumb_small } }', {}, apiUrl).catch(() => null);
  return (b && b.me && b.me.photo_thumb_small) || '';
}
// The person's country from their monday.com profile (ke/tz/ug/mw), or '' if not one of ours.
// Asked separately so a missing field can never stop someone signing in.
export async function homeCountry(token, apiUrl) {
  const d = await gql(token, '{ me { country_code time_zone_identifier } }', {}, apiUrl);
  const me = (d && d.me) || {};
  const cc = String(me.country_code || '').toLowerCase();
  if (/^(ke|tz|ug|mw)$/.test(cc)) return cc;
  const tz = String(me.time_zone_identifier || '');
  return { 'Africa/Nairobi': 'ke', 'Africa/Dar_es_Salaam': 'tz', 'Africa/Kampala': 'ug', 'Africa/Blantyre': 'mw' }[tz] || '';
}
// The latest notes on a project (newest first), with their files, for the pin app's history.
export async function readUpdates(token, itemId, apiUrl) {
  const d = await gql(token, 'query ($ids: [ID!]) { items(ids: $ids) { updates(limit: 15) { id text_body created_at creator { name } assets { id name file_extension public_url } } } }', { ids: [String(itemId)] }, apiUrl);
  const it = d.items && d.items[0];
  return ((it && it.updates) || []).map(u => ({
    id: String(u.id), text: u.text_body || '', at: u.created_at, by: (u.creator && u.creator.name) || '',
    files: (u.assets || []).map(a => ({ name: a.name || '', ext: String(a.file_extension || '').replace(/^\./, '').toLowerCase(), url: a.public_url || '' })).filter(a => a.url)
  }));
}
// New site from the field: goes onto the board as "Due Diligence" (so it stays off the website).
export async function createSite(token, boardId, s, apiUrl) {
  const cv = {};
  cv[COL.country] = { countryCode: s.cc, countryName: s.countryName };
  if (s.village) cv[COL.village] = s.village;
  if (s.typeLabel) cv[COL.type] = { label: s.typeLabel };
  cv[COL.status] = { label: s.status || 'Due Diligence' }; // or 'Under Construction' / 'Planned and/or Permitted' (lib/construction.js)
  if (s.gps) cv[COL.gps] = s.gps;
  const d = await gql(token, 'mutation ($b: ID!, $n: String!, $v: JSON) { create_item(board_id: $b, group_id: "topics", item_name: $n, column_values: $v) { id } }',
    { b: String(boardId), n: s.name, v: JSON.stringify(cv) }, apiUrl);
  return d && d.create_item ? String(d.create_item.id) : null;
}
// Attaches a photo to a note (not to the public photo column).
export async function addFileToUpdate(token, updateId, blob, filename, apiUrl) {
  const base = (apiUrl || API_URL).replace(/\/v2\/?$/, '/v2');
  const form = new FormData();
  form.append('query', 'mutation ($file: File!) { add_file_to_update(update_id: ' + Number(updateId) + ', file: $file) { id } }');
  form.append('variables[file]', blob, filename);
  const res = await fetch(base + '/file', { method: 'POST', headers: { Authorization: token }, body: form });
  if (!res.ok) throw new Error('monday.com file upload answered ' + res.status);
  const body = await res.json();
  if (body.errors && body.errors.length) throw new Error('monday.com: ' + (body.errors[0].message || 'upload failed'));
  const id = body.data && body.data.add_file_to_update && body.data.add_file_to_update.id;
  return id ? String(id) : true; // the asset id (construction stage galleries keep it)
}
// Story columns: read them all (text and file lists) for one item
export async function readStoryCols(token, itemId, apiUrl) {
  const ids = Object.values(STORY_COL).concat([COL.type, COL.village, COL.location, COL.people, COL.completed, COL.status]);
  const d = await gql(token, 'query ($ids: [ID!], $c: [String!]) { items(ids: $ids) { id name board { id } column_values(ids: $c) { id text value } } }', { ids: [String(itemId)], c: ids }, apiUrl);
  const it = d.items && d.items[0];
  if (!it) return null;
  const v = {}; for (const cv of it.column_values || []) v[cv.id] = cv;
  const text = id => (v[id] && v[id].text) || '';
  const files = id => ((parse(v[id] && v[id].value) || {}).files || []).filter(f => f && f.assetId).map(f => ({ id: String(f.assetId), name: f.name || '' }));
  return { id: String(it.id), name: it.name, board: it.board && String(it.board.id), text, files,
    facts: { type: TYPE[text(COL.type)] || '', area: tidyArea(area(text(COL.village), text(COL.location))), people: num(text(COL.people)), done: (/^(\d{4}-\d{2}-\d{2})/.exec(text(COL.completed)) || [])[1] || '', status: STATUS[text(COL.status)] || '' } };
}
// several columns at once; values as monday wants them ({text} for long text, a string for text, {label} for status)
export async function writeCols(token, boardId, itemId, values, apiUrl) {
  await gql(token, 'mutation ($b: ID!, $i: ID!, $v: JSON!) { change_multiple_column_values(board_id: $b, item_id: $i, column_values: $v) { id } }',
    { b: String(boardId), i: String(itemId), v: JSON.stringify(values) }, apiUrl);
}
// a file into a file column (the story photo rows); returns the asset id
export async function addFileToColumn(token, itemId, columnId, blob, filename, apiUrl) {
  const base = (apiUrl || API_URL).replace(/\/v2\/?$/, '/v2');
  const form = new FormData();
  form.append('query', 'mutation ($file: File!) { add_file_to_column(item_id: ' + Number(itemId) + ', column_id: "' + String(columnId).replace(/[^a-z0-9_]/gi, '') + '", file: $file) { id } }');
  form.append('variables[file]', blob, filename);
  const res = await fetch(base + '/file', { method: 'POST', headers: { Authorization: token }, body: form });
  if (!res.ok) throw new Error('monday.com file upload answered ' + res.status);
  const body = await res.json();
  if (body.errors && body.errors.length) throw new Error('monday.com: ' + (body.errors[0].message || 'upload failed'));
  const id = body.data && body.data.add_file_to_column && body.data.add_file_to_column.id;
  return id ? String(id) : '';
}
// The Status column (e.g. "Under Construction", "Completed/Operational"); labels must already exist on the board.
export async function setStatus(token, boardId, itemId, label, apiUrl) {
  await gql(token, 'mutation ($b: ID!, $i: ID!, $c: String!, $v: JSON!) { change_column_value(board_id: $b, item_id: $i, column_id: $c, value: $v) { id } }',
    { b: String(boardId), i: String(itemId), c: COL.status, v: JSON.stringify({ label }) }, apiUrl);
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
  // Swahili joining words stay lower case: "Kanisa La PAG" -> "Kanisa la PAG", "Shule Ya Msingi" -> "Shule ya Msingi"
  s = s.replace(/\b(Kanisa|Shule|Mtaa|Kambi|Kwa) (La|Ya|Wa|Kwa) /g, (m, a, b) => a + ' ' + b.toLowerCase() + ' ');
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
      cat: CAT[type] || 'community',
      pump: PUMP[text(COL.pump)] || '',
      people: num(text(COL.people)),
      year: year(text(COL.completed), text(COL.funding)),
      status,
      photos: HIDE_PHOTOS[String(it.id)] ? [] : photos(v[COL.photos])
    };
    // well depth (metres) and flow rate (litres per hour), when the board has them
    if (num(text(COL.depth))) p.depth = num(text(COL.depth));
    if (num(text(COL.flow))) p.flow = num(text(COL.flow));
    // the day it was finished (YYYY-MM-DD), for donors' "one year of water" cards
    const done = /^(\d{4}-\d{2}-\d{2})/.exec(text(COL.completed));
    if (done && status === 'Completed') p.done = done[1];
    // the project's GPS point, used by the maps (filled in from the board or the pin app)
    const gps = parseGps(text(COL.gps));
    if (gps) p.gps = gps;
    // the project's story, once an admin approved it (the pin app's Stories tab): words, the quote and the after photos
    if (/^(Approved|Published)$/.test(text(STORY_COL.status)) && text(STORY_COL.text).trim()) {
      p.story = { text: text(STORY_COL.text).trim().slice(0, 4000), quote: text(STORY_COL.quote).trim().slice(0, 600), quoteBy: text(STORY_COL.quoteBy).trim().slice(0, 80), credit: text(STORY_COL.credit).trim().slice(0, 80), after: photos(v[STORY_COL.after]) };
    }
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
  // beyond the well: counts by group, for the whole site and per country (people are not added: they would double count)
  const bblank = () => { const o = { completed: 0, underConstruction: 0, upcoming: 0, cats: {} }; for (const c of CATS) o.cats[c] = { completed: 0, underConstruction: 0, upcoming: 0 }; return o; };
  const beyond = bblank(); for (const k of Object.keys(countries)) countries[k].beyond = bblank();
  for (const p of projects) {
    if (p.water) continue;
    const key = p.status === 'Completed' ? 'completed' : p.status === 'Under construction' ? 'underConstruction' : 'upcoming';
    for (const b of [beyond, countries[p.ck].beyond]) { b[key]++; b.cats[p.cat][key]++; }
  }
  total.beyond = beyond;
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
  for (const p of (feed && feed.projects) || []) for (const id of (p.photos || []).concat((p.story && p.story.after) || [])) ids.add(String(id));
  return ids;
}
