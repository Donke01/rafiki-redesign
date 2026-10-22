// Whole-country street maps: the /api/pin setting (countryMaps) and /api/cmap serving the files from R2 with ranges.
// Run: node --test tests/*.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { countryMaps } from '../functions/api/pin.js';
import { onRequest as cmap, parseRange } from '../functions/api/cmap/[[path]].js';

test('countryMaps: off until set; a public https address or the R2 binding', () => {
  assert.equal(countryMaps({}), null);
  assert.equal(countryMaps({ PIN_COUNTRY_MAPS: '' }), null);
  assert.equal(countryMaps({ PIN_COUNTRY_MAPS: 'http://maps.example.org/' }), null, 'https only');
  assert.equal(countryMaps({ PIN_COUNTRY_MAPS: 'not a url' }), null);
  assert.equal(countryMaps({ PIN_COUNTRY_MAPS: 'https://tile.openstreetmap.org/' }), null, 'never OSM, Esri or Google servers');
  assert.equal(countryMaps({ PIN_COUNTRY_MAPS: 'https://server.arcgisonline.com/x/' }), null);
  assert.equal(countryMaps({ PIN_COUNTRY_MAPS: 'https://maps.googleapis.com/x/' }), null);
  assert.deepEqual(countryMaps({ PIN_COUNTRY_MAPS: 'https://maps.example.org' }), { base: 'https://maps.example.org/' });
  assert.deepEqual(countryMaps({ PIN_COUNTRY_MAPS: ' https://pub-123.r2.dev/maps/ ' }), { base: 'https://pub-123.r2.dev/maps/' });
  assert.deepEqual(countryMaps({ COUNTRY_MAPS: fakeBucket({}) }), { base: '/api/cmap/' });
  // the variable wins over the binding
  assert.deepEqual(countryMaps({ PIN_COUNTRY_MAPS: 'https://maps.example.org/', COUNTRY_MAPS: fakeBucket({}) }), { base: 'https://maps.example.org/' });
});

test('parseRange', () => {
  assert.deepEqual(parseRange('bytes=0-99'), { offset: 0, length: 100 });
  assert.deepEqual(parseRange('bytes=100-'), { offset: 100 });
  assert.deepEqual(parseRange('bytes=-50'), { suffix: 50 });
  assert.equal(parseRange('bytes=9-3'), null);
  assert.equal(parseRange('bytes=0-1,5-9'), null);
  assert.equal(parseRange('items=0-1'), null);
});

// a stand-in for an R2 bucket binding
function fakeBucket(files) {
  const etag = k => 'e' + files[k].length;
  const meta = k => ({ key: k, size: files[k].length, httpEtag: '"' + etag(k) + '"', etag: etag(k) });
  return {
    async head(k) { return files[k] ? meta(k) : null; },
    async get(k, o = {}) {
      if (!files[k]) return null;
      const m = meta(k), data = files[k];
      if (o.onlyIf && o.onlyIf.etagMatches && o.onlyIf.etagMatches !== m.etag) return m;
      let a = 0, b = data.length;
      if (o.range) {
        if ('suffix' in o.range) a = Math.max(0, data.length - o.range.suffix);
        else { a = o.range.offset; if (a >= data.length) throw new Error('InvalidRange'); if (o.range.length !== undefined) b = Math.min(data.length, a + o.range.length); }
      }
      return Object.assign(m, { range: o.range, body: new Blob([data.slice(a, b)]).stream() });
    },
  };
}
const FILE = new Uint8Array(1000).map((_, i) => i % 251);
const env = { COUNTRY_MAPS: fakeBucket({ 'kenya-2026-10-20.pmtiles': FILE, 'countries.json': new TextEncoder().encode('{"countries":[]}') }) };
const call = (path, headers = {}, method = 'GET') => cmap({ request: new Request('https://x.test/api/cmap/' + path, { method, headers }), env, params: { path: path.split('/') } });

test('/api/cmap serves the list and country files, with ranges', async () => {
  let r = await call('countries.json');
  assert.equal(r.status, 200); assert.equal(r.headers.get('Cache-Control'), 'no-cache'); assert.deepEqual(await r.json(), { countries: [] });
  r = await call('kenya-2026-10-20.pmtiles');
  assert.equal(r.status, 200); assert.equal((await r.arrayBuffer()).byteLength, 1000); assert.equal(r.headers.get('Accept-Ranges'), 'bytes');
  r = await call('kenya-2026-10-20.pmtiles', { Range: 'bytes=10-19' });
  assert.equal(r.status, 206); assert.equal(r.headers.get('Content-Range'), 'bytes 10-19/1000');
  assert.deepEqual([...new Uint8Array(await r.arrayBuffer())], [...FILE.slice(10, 20)]);
  r = await call('kenya-2026-10-20.pmtiles', { Range: 'bytes=990-' });
  assert.equal(r.status, 206); assert.equal(r.headers.get('Content-Range'), 'bytes 990-999/1000'); assert.equal((await r.arrayBuffer()).byteLength, 10);
  r = await call('kenya-2026-10-20.pmtiles', { Range: 'bytes=-4' });
  assert.equal(r.status, 206); assert.equal(r.headers.get('Content-Range'), 'bytes 996-999/1000');
  r = await call('kenya-2026-10-20.pmtiles', { Range: 'bytes=990-5000' });
  assert.equal(r.status, 206); assert.equal(r.headers.get('Content-Range'), 'bytes 990-999/1000');
  r = await call('kenya-2026-10-20.pmtiles', { Range: 'bytes=2000-' });
  assert.equal(r.status, 416); assert.equal(r.headers.get('Content-Range'), 'bytes */1000');
  // a piece of a file that has since been replaced
  r = await call('kenya-2026-10-20.pmtiles', { Range: 'bytes=0-9', 'If-Match': '"old"' });
  assert.equal(r.status, 412);
  r = await call('kenya-2026-10-20.pmtiles', { Range: 'bytes=0-9', 'If-Match': '"e1000"' });
  assert.equal(r.status, 206);
  r = await call('kenya-2026-10-20.pmtiles', {}, 'HEAD');
  assert.equal(r.status, 200); assert.equal(r.headers.get('Content-Length'), '1000'); assert.equal(r.headers.get('ETag'), '"e1000"');
});

test('/api/cmap serves nothing else', async () => {
  assert.equal((await call('secrets.txt')).status, 404);
  assert.equal((await call('../pin.js')).status, 404);
  assert.equal((await call('uganda.pmtiles')).status, 404);
  assert.equal((await call('countries.json', {}, 'POST')).status, 405);
  const off = await cmap({ request: new Request('https://x.test/api/cmap/countries.json'), env: {}, params: { path: ['countries.json'] } });
  assert.equal(off.status, 404);
});
