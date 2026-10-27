// Profile photos for the RAFIKI Pin team (Me › tap the avatar, or a contact sheet). The contract both apps
// build against: /api/team-photo in functions/api/team-photo.js.
// Kept in Workers KV FEED, like trip receipts: photos are small (the phones send ~512 px JPEGs, ≤ 200 KB).
//   teamphoto:<lower-case name> → { t: type, d: base64, v: version, by, at }
//   teamphotos                   → { <lower-case name>: { n: Name, v: version } }   one read for every list
import { getJson, putJson, sameName } from './pin-store.js';

export const PHOTO_MAX = 300 * 1024;
export const PHOTO_TYPES = { 'image/jpeg': 1, 'image/png': 1, 'image/webp': 1 };
export const photoKey = name => String(name || '').trim().toLowerCase();
export const photoUrl = (name, v) => (v ? '/api/team-photo?name=' + encodeURIComponent(String(name || '').trim()) + '&v=' + encodeURIComponent(v) : '');

export const photoIndex = env => getJson(env, 'teamphotos', {});
// the URL for one name from an index already read ('' when there is none)
export function photoOf(idx, name) {
  const x = idx && idx[photoKey(name)];
  return x && x.v ? photoUrl(x.n || name, x.v) : '';
}
// { Name: url } for everyone who has a photo
export function photoMap(idx) {
  const out = {};
  Object.keys(idx || {}).forEach(k => { const x = idx[k]; if (x && x.v) out[x.n || k] = photoUrl(x.n || k, x.v); });
  return out;
}
// a new version: base-36 time, always different from the one before
function nextV(old) {
  let v = Date.now().toString(36);
  if (v === old) v += '1';
  return v;
}

export async function readPhoto(env, name) {
  if (!env.FEED) return null;
  return env.FEED.get('teamphoto:' + photoKey(name), 'json');
}
export async function savePhoto(env, name, type, bytes, by) {
  const idx = await photoIndex(env), k = photoKey(name), v = nextV(idx[k] && idx[k].v);
  let bin = ''; for (let i = 0; i < bytes.length; i += 0x8000) bin += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
  await env.FEED.put('teamphoto:' + k, JSON.stringify({ t: type, d: btoa(bin), v, by: by || '', at: new Date().toISOString() }));
  idx[k] = { n: String(name).trim(), v };
  await putJson(env, 'teamphotos', idx);
  return photoUrl(name, v);
}
export async function dropPhoto(env, name) {
  if (!env.FEED) return false;
  const idx = await photoIndex(env), k = photoKey(name), had = !!idx[k];
  await env.FEED.delete('teamphoto:' + k);
  if (had) { delete idx[k]; await putJson(env, 'teamphotos', idx); }
  return had;
}
// Office › Team › rename: the photo goes with the person
export async function movePhoto(env, from, to) {
  if (!env.FEED || sameName(from, to) && String(from).trim() === String(to).trim()) return;
  const ph = await readPhoto(env, from);
  if (!ph) return;
  const idx = await photoIndex(env);
  if (photoKey(from) !== photoKey(to)) await env.FEED.delete('teamphoto:' + photoKey(from));
  delete idx[photoKey(from)];
  await env.FEED.put('teamphoto:' + photoKey(to), JSON.stringify(ph));
  idx[photoKey(to)] = { n: String(to).trim(), v: ph.v };
  await putJson(env, 'teamphotos', idx);
}
