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
  funding: 'text_mkwcqg8j'
};

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
  const data = await gql(token, 'query ($ids: [ID!]!) { assets(ids: $ids) { id public_url } }', { ids: [String(assetId)] }, apiUrl);
  const a = data.assets && data.assets[0];
  return a && a.public_url;
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

function year(completed, funding) {
  const m = /(\d{4})/.exec(completed || '') || /(\d{4})/.exec(funding || '');
  return m ? Number(m[1]) : null;
}

export function buildFeed(columns, items) {
  const show = columns.find(c => c.type === 'checkbox' && SHOW_TITLE.test((c.title || '').trim()));
  const projects = [];
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
    const p = {
      id: String(it.id),
      title: (it.name || '').trim(),
      country: COUNTRY_NAME[ck],
      ck,
      area: area(text(COL.village), text(COL.location)),
      type,
      water: !!WATER[type],
      pump: PUMP[text(COL.pump)] || '',
      people: num(text(COL.people)),
      year: year(text(COL.completed), text(COL.funding)),
      status,
      photos: photos(v[COL.photos])
    };
    // the amount still to raise is shown only while a project is looking for funding
    if (status === 'Upcoming' && num(text(COL.cost))) p.needed = num(text(COL.cost));
    projects.push(p);
  }
  projects.sort((a, b) => (b.year || 0) - (a.year || 0) || a.title.localeCompare(b.title));

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

  return { source: 'monday.com', updated: new Date().toISOString(), filteredBy: show ? show.title : null, summary: { total, countries }, projects };
}

export function photoIds(feed) {
  const ids = new Set();
  for (const p of (feed && feed.projects) || []) for (const id of p.photos || []) ids.add(String(id));
  return ids;
}
