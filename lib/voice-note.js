// Voice notes typed out: after /api/pin-photo has put a voice note on a visit's monday.com note, this
// runs in the background (waitUntil), sends the audio to speech to text (lib/transcribe.js) and adds
//   "🎙️ Voice note (typed out): …"
// to that same monday.com note (edit_update), so it shows in the app's History and monday.com search
// finds it. If the note can't be edited (older API, or someone else's note) a follow-up note goes on the
// project instead, and if even that fails, a reply under the note.
// Limits: voice notes are at most 2 minutes (the apps stop recording at 2:00); files over MAX_BYTES
// are skipped. 20 per connection per hour, TRANSCRIBE_DAILY_MAX (default 200) per day for everyone.
// A copy of each text is kept in KV (pinvoicetx, the latest 300) for the Office.
import { transcribe, transcribeSetup } from './transcribe.js';
import { API_URL } from './monday.js';

export const MAX_BYTES = 4 * 1024 * 1024; // 2 min of AAC at 128 kbit/s is about 1.9 MB; browser Opus is far less
export const MAX_SECS = 130;
const PER_HOUR = 20;
const LANG = { sw: 'Kiswahili', en: 'English' };
const esc = s => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');

async function gql(token, query, variables, apiUrl) {
  const r = await fetch(apiUrl || API_URL, { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: token }, body: JSON.stringify({ query, variables }) });
  if (!r.ok) throw new Error('monday.com answered ' + r.status);
  const b = await r.json();
  if (b.errors && b.errors.length) throw new Error('monday.com: ' + (b.errors[0].message || 'failed'));
  return b.data;
}
async function count(env, key) { return env.FEED ? Number(await env.FEED.get(key)) || 0 : 0; }
async function bump(env, key, ttl) { if (env.FEED) await env.FEED.put(key, String((await count(env, key)) + 1), { expirationTtl: ttl }); }

// The line added to the note
export function typedLine(text, lang) {
  return '🎙️ Voice note (typed out): ' + esc(text) + ' (' + (LANG[lang] ? LANG[lang] + ', ' : '') + 'typed out automatically, may have small mistakes)';
}

// opts: { token, update, bytes, mime, ip, by, apiUrl, secs }. Never throws; returns what happened.
export async function typeOutVoiceNote(env, o) {
  const cfg = transcribeSetup(env);
  if (!cfg.on) return { done: false, why: 'off' };
  const size = o.bytes ? (o.bytes.byteLength || o.bytes.length || 0) : 0;
  if (!size) return { done: false, why: 'empty' };
  if (size > MAX_BYTES || (o.secs && o.secs > MAX_SECS)) return { done: false, why: 'too-long' };
  const day = new Date().toISOString().slice(0, 10), dayKey = 'rl:vxday:' + day, ipKey = 'rl:vx:' + (o.ip || 'unknown');
  const max = Math.max(0, parseInt(env.TRANSCRIBE_DAILY_MAX, 10) || 200);
  if (await count(env, ipKey) >= PER_HOUR) return { done: false, why: 'rate' };
  if (await count(env, dayKey) >= max) return { done: false, why: 'daily-limit' };
  await bump(env, ipKey, 3600); await bump(env, dayKey, 2 * 86400);
  let res;
  try { res = await transcribe(env, o.bytes, o.mime); } catch (e) { console.error('voice note typing:', e && e.message); return { done: false, why: (e && e.code) || 'down' }; }
  if (res.secs && res.secs > MAX_SECS + 30) return { done: false, why: 'too-long' };
  const text = String(res.text || '').slice(0, 4000);
  if (!text) return { done: false, why: 'no-speech' };
  const line = typedLine(text, res.lang), api = o.apiUrl || env.MONDAY_API_URL, token = o.token || env.MONDAY_API_TOKEN;
  let how = '', item = '';
  // 1. add it to the visit's own note
  try {
    const d = await gql(token, 'query ($ids: [ID!]) { updates(ids: $ids) { id body item_id } }', { ids: [String(o.update)] }, api);
    const u = d && d.updates && d.updates[0];
    if (u) {
      item = String(u.item_id || '');
      if (String(u.body || '').indexOf(esc(text).slice(0, 60)) >= 0) how = 'already';
      else { await gql(token, 'mutation ($i: ID!, $b: String!) { edit_update(id: $i, body: $b) { id } }', { i: String(o.update), b: String(u.body || '') + '<br><br>' + line }, api); how = 'edit'; }
    }
  } catch (e) { console.error('voice note edit:', e && e.message); }
  // 2. a follow-up note on the project; 3. a reply under the note
  if (!how && item) {
    try { const d = await gql(token, 'mutation ($i: ID!, $t: String!) { create_update(item_id: $i, body: $t) { id } }', { i: item, t: line + (o.by ? ' (from the visit note by ' + esc(o.by) + ')' : '') }, api); if (d && d.create_update) how = 'note'; } catch (e) { console.error('voice note follow-up:', e && e.message); }
  }
  if (!how) {
    try { const d = await gql(token, 'mutation ($p: ID!, $t: String!) { create_update(parent_id: $p, body: $t) { id } }', { p: String(o.update), t: line }, api); if (d && d.create_update) how = 'reply'; } catch (e) { console.error('voice note reply:', e && e.message); }
  }
  if (env.FEED) {
    try {
      const raw = await env.FEED.get('pinvoicetx', 'json'), a = Array.isArray(raw) ? raw : [];
      a.push({ at: new Date().toISOString(), update: String(o.update), item, by: o.by || '', lang: res.lang || '', text, how: how || 'failed' });
      await env.FEED.put('pinvoicetx', JSON.stringify(a.slice(-300)));
    } catch (e) {}
  }
  return { done: !!how, how: how || 'failed', text, lang: res.lang || '' };
}
