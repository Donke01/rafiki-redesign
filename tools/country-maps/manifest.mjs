// Writes countries.json, the list the apps read: for each country the newest <country>-<date>.pmtiles in the folder,
// with its size, checksum and area. Usage: node manifest.mjs out
import { readdirSync, statSync, openSync, readSync, closeSync, writeFileSync, createReadStream } from 'node:fs';
import { createHash } from 'node:crypto';
import { join } from 'node:path';

const dir = process.argv[2] || 'out';
const NAMES = { kenya: ['ke', 'Kenya'], uganda: ['ug', 'Uganda'], tanzania: ['tz', 'Tanzania'], malawi: ['mw', 'Malawi'], test: ['xx', 'Test area'] };

function header(file) {
  const b = Buffer.alloc(127), fd = openSync(file, 'r'); readSync(fd, b, 0, 127, 0); closeSync(fd);
  if (b.toString('latin1', 0, 7) !== 'PMTiles' || b[7] !== 3) throw new Error(file + ' is not a PMTiles v3 file');
  const e7 = o => b.readInt32LE(o) / 1e7;
  return { minzoom: b[100], maxzoom: b[101], bounds: [e7(102), e7(106), e7(110), e7(114)], center: [e7(123), e7(119)] };
}
const sha256 = file => new Promise((ok, no) => { const h = createHash('sha256'); createReadStream(file).on('data', d => h.update(d)).on('end', () => ok(h.digest('hex'))).on('error', no); });

const newest = {};
for (const f of readdirSync(dir)) {
  const m = /^([a-z]+)-(\d{4}-\d{2}-\d{2})\.pmtiles$/.exec(f);
  if (!m || !NAMES[m[1]]) continue;
  if (!newest[m[1]] || newest[m[1]].date < m[2]) newest[m[1]] = { file: f, date: m[2] };
}
const countries = [];
for (const c of ['kenya', 'uganda', 'tanzania', 'malawi', 'test']) {
  const n = newest[c]; if (!n) continue;
  const p = join(dir, n.file), h = header(p);
  countries.push({ code: NAMES[c][0], name: NAMES[c][1], file: n.file, date: n.date, bytes: statSync(p).size, sha256: await sha256(p), bounds: h.bounds, center: h.center, minzoom: h.minzoom, maxzoom: h.maxzoom });
}
const list = { format: 1, attribution: '© OpenStreetMap contributors', license: 'ODbL 1.0', countries };
writeFileSync(join(dir, 'countries.json'), JSON.stringify(list, null, 1) + '\n');
for (const c of countries) console.log(c.name.padEnd(10), (c.bytes / 1048576).toFixed(1).padStart(7), 'MB ', c.file);
