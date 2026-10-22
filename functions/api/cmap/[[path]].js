// /api/cmap/<file>: the whole-country map files, from the Cloudflare R2 bucket bound to this Pages project as
// COUNTRY_MAPS (see countryMaps() in functions/api/pin.js and tools/country-maps/README.md).
// Only countries.json (the list) and *.pmtiles files (one per country) are served. Range requests
// ("Range: bytes=a-b") answer 206 with just those bytes, so the apps download a country in pieces and can carry on
// after a dropped connection; "If-Match" makes sure every piece comes from the same version of the file.
// The map data is OpenStreetMap's (© OpenStreetMap contributors, ODbL) and public, so no PIN is needed.
const NAME = /^(countries\.json|[a-z0-9][a-z0-9._-]{0,80}\.pmtiles)$/;

export function parseRange(h) {
  const m = /^bytes=(\d*)-(\d*)$/.exec(String(h || '').trim());
  if (!m || (m[1] === '' && m[2] === '')) return null;
  if (m[1] === '') return { suffix: Number(m[2]) };
  const offset = Number(m[1]);
  if (m[2] === '') return { offset };
  const end = Number(m[2]);
  return end >= offset ? { offset, length: end - offset + 1 } : null;
}

const plain = (status, text, extra) => new Response(text, { status, headers: Object.assign({ 'Content-Type': 'text/plain; charset=utf-8', 'Cache-Control': 'no-store' }, extra || {}) });

export async function onRequest({ request, env, params }) {
  if (request.method !== 'GET' && request.method !== 'HEAD') return plain(405, 'Method not allowed', { Allow: 'GET, HEAD' });
  const bucket = env.COUNTRY_MAPS;
  const key = [].concat(params.path || []).join('/');
  if (!bucket || !NAME.test(key)) return plain(404, 'Not found');
  const list = key === 'countries.json';
  const headers = new Headers({
    'Accept-Ranges': 'bytes',
    'Content-Type': list ? 'application/json' : 'application/vnd.pmtiles',
    // a country file never changes under the same name (a new build gets a new name); the list is checked each time
    'Cache-Control': list ? 'no-cache' : 'public, max-age=86400',
  });
  if (request.method === 'HEAD') {
    const o = await bucket.head(key);
    if (!o) return plain(404, 'Not found');
    headers.set('Content-Length', String(o.size)); headers.set('ETag', o.httpEtag);
    return new Response(null, { status: 200, headers });
  }
  const want = request.headers.get('Range');
  const range = want ? parseRange(want) : null;
  const ifMatch = request.headers.get('If-Match');
  if (want && !range) return plain(416, 'Bad range');
  let o;
  try {
    o = await bucket.get(key, Object.assign({}, range ? { range } : {}, ifMatch ? { onlyIf: { etagMatches: ifMatch.replace(/^W\//, '').replace(/"/g, '') } } : {}));
  } catch (e) {
    // asking past the end of the file
    const h = await bucket.head(key).catch(() => null);
    if (!h) return plain(404, 'Not found');
    return plain(416, 'Range not satisfiable', { 'Content-Range': 'bytes */' + h.size });
  }
  if (!o) return plain(404, 'Not found');
  headers.set('ETag', o.httpEtag);
  // If-Match failed: the file was replaced since the download started
  if (!('body' in o) || !o.body) return new Response(null, { status: 412, headers });
  if (range) {
    const size = o.size, r = o.range || range;
    const offset = 'suffix' in r && r.offset === undefined ? Math.max(0, size - r.suffix) : r.offset;
    const length = r.length !== undefined ? r.length : size - offset;
    if (offset >= size) return plain(416, 'Range not satisfiable', { 'Content-Range': 'bytes */' + size });
    const len = Math.min(length, size - offset);
    headers.set('Content-Range', 'bytes ' + offset + '-' + (offset + len - 1) + '/' + size);
    headers.set('Content-Length', String(len));
    return new Response(o.body, { status: 206, headers });
  }
  headers.set('Content-Length', String(o.size));
  return new Response(o.body, { status: 200, headers });
}
