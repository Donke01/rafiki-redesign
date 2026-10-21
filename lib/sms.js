// Community reporting by SMS / WhatsApp: a water committee texts "BROKEN 104" or "IMEHARIBIKA 104 pampu"
// and it becomes a repair report in the RAFIKI Pin app, the same as a "Not working" visit report:
// an open repair, a line in the activity log (by "SMS from +254•••678", so the team's bell shows it),
// a note on the project in monday.com and an e-mail to the admins (when e-mail is set up).
// The webhook is functions/api/sms.js; the Office list and the phone list are functions/api/sms-admin.js.
//
// Messages understood (English or Kiswahili, any case, the project number anywhere):
//   BROKEN / NOT WORKING / DRY / IMEHARIBIKA / HAIFANYI (KAZI) / IMEKUFA / IMEKAUKA …   → not working
//   REPAIR / LEAK / SLOW / MATENGENEZO / INAVUJA / SHIDA / TATIZO …                      → needs repair
//   FIXED / WORKING / REPAIRED / IMEREKEBISHWA / IMETENGENEZWA / INAFANYA (KAZI) …       → working again
//   HELP / MSAADA                                    → how to use it (with a project number: needs repair)
// The project is the number in the message (the monday.com item id shown in the app), or, for a
// committee phone the office has registered, their project.
// Abuse: a number not on the office's list is ignored unless it names a real project; 6 messages an hour
// and 20 a day per number, 500 a day in all; "working again" only closes a repair when it comes from a
// number registered for that project (otherwise the team is asked to check). Everything is logged.
import { readGps, createUpdate, tidyTitle, BOARD_ID } from './monday.js';
import { sendMail, alertHtml } from './pin-store.js';

export const PER_HOUR = 6, PER_DAY = 20, ALL_PER_DAY = 500;
const CC = { ke: '254', tz: '255', ug: '256', mw: '265' };

// ---- phone numbers -----------------------------------------------------------------------------
// "+254 712 345 678", "254712345678", "0712345678" (Kenya unless cc says otherwise), "whatsapp:+2547…" → "+254712345678"
export function normPhone(v, cc) {
  let s = String(v || '').replace(/^whatsapp:/i, '').replace(/[^\d+]/g, '');
  if (s.indexOf('00') === 0) s = '+' + s.slice(2);
  if (s[0] !== '+') {
    if (/^0\d{9}$/.test(s)) s = '+' + (CC[cc] || CC.ke) + s.slice(1);
    else if (/^(254|255|256|265)\d{9}$/.test(s)) s = '+' + s;
    else if (/^[1-9]\d{9,13}$/.test(s)) s = '+' + s;
    else return '';
  }
  s = '+' + s.slice(1).replace(/\+/g, '');
  return /^\+\d{9,15}$/.test(s) ? s : '';
}
// WhatsApp can hide a person's number and send a user ID instead ("whatsapp:KE.1461161065410436"); keep it as
// "wa:KE.1461161065410436" so the report still goes through (it can't match a registered committee phone).
export function waId(msg) {
  const m = /^whatsapp:([A-Z]{2})\.(\d{6,30})$/i.exec(String((msg && msg.from) || '').trim());
  return m && (msg.ch || 'whatsapp') === 'whatsapp' ? 'wa:' + m[1].toUpperCase() + '.' + m[2] : '';
}
// "+254712345678" → "+254•••678" (country code and the last 3 digits)
export function maskPhone(p) {
  const s = normPhone(p) || String(p || '');
  const m = /^\+(254|255|256|265|1|44|\d{3})(\d*)(\d{3})$/.exec(s);
  return m ? '+' + m[1] + '•••' + m[3] : '•••' + s.slice(-3);
}

// ---- reading a message -------------------------------------------------------------------------
const WORDS = [
  // longest phrases first; [words, kind, language]
  [['NOT', 'WORKING'], 'broken', 'en'], [['HAIFANYI', 'KAZI'], 'broken', 'sw'], [['HAITOI', 'MAJI'], 'broken', 'sw'], [['NO', 'WATER'], 'broken', 'en'],
  [['HAKUNA', 'MAJI'], 'broken', 'sw'], [['INAFANYA', 'KAZI'], 'fixed', 'sw'], [['WORKING', 'AGAIN'], 'fixed', 'en'],
  [['BROKEN'], 'broken', 'en'], [['BROKE'], 'broken', 'en'], [['BREAKDOWN'], 'broken', 'en'], [['DRY'], 'broken', 'en'], [['DEAD'], 'broken', 'en'],
  [['IMEHARIBIKA'], 'broken', 'sw'], [['IMEHARIBIKIWA'], 'broken', 'sw'], [['HARIBIKA'], 'broken', 'sw'], [['HAIFANYI'], 'broken', 'sw'], [['HAITOI'], 'broken', 'sw'],
  [['IMEKUFA'], 'broken', 'sw'], [['IMEKAUKA'], 'broken', 'sw'], [['IMEVUNJIKA'], 'broken', 'sw'], [['MBOVU'], 'broken', 'sw'],
  [['REPAIR'], 'repair', 'en'], [['LEAK'], 'repair', 'en'], [['LEAKING'], 'repair', 'en'], [['SLOW'], 'repair', 'en'], [['PROBLEM'], 'repair', 'en'],
  [['MATENGENEZO'], 'repair', 'sw'], [['UKARABATI'], 'repair', 'sw'], [['INAVUJA'], 'repair', 'sw'], [['SHIDA'], 'repair', 'sw'], [['TATIZO'], 'repair', 'sw'],
  [['FIXED'], 'fixed', 'en'], [['REPAIRED'], 'fixed', 'en'], [['WORKING'], 'fixed', 'en'], [['WORKS'], 'fixed', 'en'],
  [['IMEREKEBISHWA'], 'fixed', 'sw'], [['IMETENGENEZWA'], 'fixed', 'sw'], [['INAFANYA'], 'fixed', 'sw'], [['IMEPONA'], 'fixed', 'sw'],
  [['HELP'], 'help', 'en'], [['MSAADA'], 'help', 'sw'], [['SAIDIA'], 'help', 'sw'], [['TUSAIDIE'], 'help', 'sw']
];

// → { kind: 'broken'|'repair'|'fixed'|'help'|'unknown', id: '' or digits, note, lang: 'en'|'sw' }
export function parseSms(text, keyword) {
  const raw = String(text || '').replace(/\s+/g, ' ').trim().slice(0, 480);
  let toks = raw.toUpperCase().replace(/[^A-Z0-9#\s]/g, ' ').split(/\s+/).filter(Boolean);
  // a shared shortcode puts the office's keyword first ("RAFIKI BROKEN 104")
  const skip = ['RAFIKI', String(keyword || '').toUpperCase()].filter(Boolean);
  while (toks.length && skip.includes(toks[0])) toks = toks.slice(1);
  let kind = 'unknown', lang = '', at = -1, len = 0;
  for (const [w, k, l] of WORDS) {
    for (let i = 0; i + w.length <= toks.length; i++) {
      if (w.every((x, j) => toks[i + j] === x) && (at < 0 || i < at)) { kind = k; lang = l; at = i; len = w.length; break; }
    }
    if (at === 0) break;
  }
  // the project number: the first 2–12 digit number that isn't a phone number
  let id = '';
  for (const t of toks) {
    const d = t.replace(/^#/, '');
    if (!/^\d{2,12}$/.test(d)) continue;
    if (/^0\d{9}$/.test(d) || /^(254|255|256|265)\d{9}$/.test(d)) continue;
    id = d; break;
  }
  if (!lang) lang = /\b(maji|kisima|pampu|bomba|kamati|tafadhali|asante|habari)\b/i.test(raw) ? 'sw' : 'en';
  const used = new Set(at >= 0 ? toks.slice(at, at + len) : []);
  const note = raw.split(' ').filter(w => { const u = w.toUpperCase().replace(/[^A-Z0-9]/g, ''); return u && !used.has(u) && u !== id && !skip.includes(u); }).join(' ').slice(0, 300);
  return { kind, id, note, lang };
}

// ---- checking who sent it ----------------------------------------------------------------------
export function sameText(a, b) {
  const x = new TextEncoder().encode(String(a)), y = new TextEncoder().encode(String(b));
  let diff = x.length ^ y.length;
  for (let i = 0; i < Math.max(x.length, y.length); i++) diff |= (x[i] || 0) ^ (y[i] || 0);
  return diff === 0;
}
// Twilio: base64(HMAC-SHA1(auth token, full URL + each POST field name and value, sorted by name))
export async function twilioSignature(authToken, url, params) {
  const data = url + Object.keys(params).sort().map(k => k + params[k]).join('');
  const key = await crypto.subtle.importKey('raw', new TextEncoder().encode(authToken), { name: 'HMAC', hash: 'SHA-1' }, false, ['sign']);
  const sig = new Uint8Array(await crypto.subtle.sign('HMAC', key, new TextEncoder().encode(data)));
  let s = ''; sig.forEach(b => { s += String.fromCharCode(b); });
  return btoa(s);
}
export async function verifyTwilio(authToken, url, params, given) {
  if (!authToken || !given) return false;
  return sameText(await twilioSignature(authToken, url, params), given);
}
// Africa's Talking: the secret token in the callback URL (?token=…), at least 16 characters
export function verifyToken(secret, given) {
  return !!secret && String(secret).length >= 16 && sameText(String(secret), String(given || ''));
}

// ---- answers ----------------------------------------------------------------------------------
const short = (s, n) => { s = String(s || ''); return s.length > n ? s.slice(0, n - 1) + '…' : s; };
export function replyText(kind, lang, o) {
  const sw = lang === 'sw', t = short(o && o.t, 40), ref = o && o.ref ? ' Ref ' + o.ref : '';
  switch (kind) {
    case 'broken': return sw ? 'RAFIKI: Asante. Ripoti ya ' + t + ' (haifanyi kazi) imepokelewa. Timu yetu itafuatilia.' + ref : 'RAFIKI: Thank you. Report received: ' + t + ' is not working. Our team will follow up.' + ref;
    case 'repair': return sw ? 'RAFIKI: Asante. Ripoti ya ' + t + ' (inahitaji ukarabati) imepokelewa. Timu yetu itafuatilia.' + ref : 'RAFIKI: Thank you. Report received: ' + t + ' needs repair. Our team will follow up.' + ref;
    case 'already': return sw ? 'RAFIKI: Asante. Tayari tunajua ' + t + ' ina tatizo; timu inashughulikia.' + ref : 'RAFIKI: Thank you. We already know about ' + t + ' and the team is on it.' + ref;
    case 'fixed': return sw ? 'RAFIKI: Asante! Tumeweka ' + t + ' kuwa inafanya kazi tena.' + ref : 'RAFIKI: Thank you! ' + t + ' is marked as working again.' + ref;
    case 'fixed-check': return sw ? 'RAFIKI: Asante! Timu yetu itathibitisha kuwa ' + t + ' inafanya kazi.' + ref : 'RAFIKI: Thank you! Our team will check that ' + t + ' is working.' + ref;
    case 'no-project': return sw ? 'RAFIKI: Hatukupata mradi namba ' + short(o && o.id, 14) + '. Tuma mf. IMEHARIBIKA <namba ya mradi>.' : 'RAFIKI: We could not find project ' + short(o && o.id, 14) + '. Send e.g. BROKEN <project number>.';
    default: return sw ? 'RAFIKI maji: tuma IMEHARIBIKA <namba ya mradi> kama haifanyi kazi, MATENGENEZO <namba> kama inahitaji ukarabati, IMEREKEBISHWA <namba> ikifanya kazi tena.'
      : 'RAFIKI water: send BROKEN <project number> if it is not working, REPAIR <number> if it needs repair, FIXED <number> when it works again.';
  }
}
// The answer in the format each provider expects
export function twiml(text) {
  const x = s => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  return '<?xml version="1.0" encoding="UTF-8"?><Response>' + (text ? '<Message>' + x(text) + '</Message>' : '') + '</Response>';
}
// Africa's Talking doesn't take the answer from the callback's response: it's sent with their SMS API
// when AT_USERNAME and AT_API_KEY are set (AT_SENDER_ID optional: your shortcode or sender name).
export async function sendAfricasTalking(env, to, text) {
  if (!env.AT_USERNAME || !env.AT_API_KEY || !text) return { ok: false, reason: 'no-at' };
  const host = env.AT_USERNAME === 'sandbox' ? 'https://api.sandbox.africastalking.com' : 'https://api.africastalking.com';
  const body = new URLSearchParams({ username: env.AT_USERNAME, to, message: text });
  if (env.AT_SENDER_ID) body.set('from', env.AT_SENDER_ID);
  const r = await fetch(host + '/version1/messaging', { method: 'POST', headers: { apiKey: env.AT_API_KEY, Accept: 'application/json', 'Content-Type': 'application/x-www-form-urlencoded' }, body: body.toString() });
  if (!r.ok) { console.error('sms reply:', r.status, (await r.text()).slice(0, 160)); return { ok: false, reason: 'down' }; }
  return { ok: true };
}

// ---- storage ----------------------------------------------------------------------------------
async function getJson(env, key, d) { if (!env.FEED) return d; const v = await env.FEED.get(key, 'json'); return v == null ? d : v; }
async function putJson(env, key, v, o) { if (env.FEED) await env.FEED.put(key, JSON.stringify(v), o); }
async function count(env, key) { return env.FEED ? Number(await env.FEED.get(key)) || 0 : 0; }
async function bump(env, key, ttl) { if (env.FEED) await env.FEED.put(key, String((await count(env, key)) + 1), { expirationTtl: ttl }); }
export const phones = env => getJson(env, 'pinsmsphones', []);
export const savePhones = (env, a) => putJson(env, 'pinsmsphones', a.slice(0, 500));
export const reports = env => getJson(env, 'pinsms', []);
async function remember(env, entry) {
  const a = await reports(env);
  a.push(entry);
  await putJson(env, 'pinsms', a.slice(-300));
}
async function logAct(env, entry) {
  const cut = Date.now() - 400 * 864e5;
  const a = (await getJson(env, 'pinact', [])).filter(e => Date.parse(e.at) > cut);
  a.push(Object.assign({ at: new Date().toISOString() }, entry));
  await putJson(env, 'pinact', a.slice(-5000));
}

// ---- one message ------------------------------------------------------------------------------
// msg: { ch: 'sms'|'whatsapp', from, text, mid (provider's message id), to }
// deps (for tests): { readGps, createUpdate, sendMail, site }
// → { reply: text or '', result, entry }
export async function handleMessage(env, msg, deps) {
  const D = Object.assign({ readGps, createUpdate, sendMail }, deps || {});
  const now = new Date().toISOString(), day = now.slice(0, 10);
  const from = normPhone(msg.from) || waId(msg), text = String(msg.text || '').replace(/\s+/g, ' ').trim().slice(0, 480);
  const entry = { sid: (crypto.randomUUID ? crypto.randomUUID() : String(Math.random()).slice(2)).slice(0, 10), at: now, ch: msg.ch || 'sms', from: from || String(msg.from || '').slice(0, 24), text, kind: 'unknown', id: '', t: '', result: '' };
  const done = async (result, reply) => { entry.result = result; entry.reply = reply || ''; await remember(env, entry); return { reply: reply || '', result, entry }; };
  if (!from) return done('bad-number');
  // the same message delivered twice (providers retry)
  if (msg.mid && env.FEED) {
    const k = 'smsseen:' + String(msg.mid).slice(0, 80);
    if (await env.FEED.get(k)) return { reply: '', result: 'duplicate', entry };
    await env.FEED.put(k, '1', { expirationTtl: 86400 });
  }
  const hk = 'rl:sms:' + from, dk = 'rl:smsd:' + from + ':' + day, ak = 'rl:smsall:' + day;
  if (await count(env, hk) >= PER_HOUR || await count(env, dk) >= PER_DAY) return done('rate-limited');
  if (await count(env, ak) >= ALL_PER_DAY) return done('daily-limit');
  await bump(env, hk, 3600); await bump(env, dk, 2 * 86400); await bump(env, ak, 2 * 86400);

  const p = parseSms(text, env.AT_KEYWORD), list = await phones(env), reg = list.find(x => x.phone === from);
  Object.assign(entry, { kind: p.kind, lang: p.lang, reg: !!reg });
  if (reg && reg.name) entry.name = reg.name;
  const token = env.MONDAY_API_TOKEN, api = env.MONDAY_API_URL, board = String(env.MONDAY_BOARD_ID || BOARD_ID);
  // which project
  let pid = p.id || (reg ? reg.id : ''), cur = null;
  entry.id = pid || '';
  if (pid) {
    // monday.com not answering: logged, nothing changed (the office can follow it up from the list)
    try { cur = await D.readGps(token, pid, api); } catch (e) { console.error('sms project:', e && e.message); return done('error'); }
    if (cur && cur.board && cur.board !== board) cur = null;
  }
  if (!reg && !cur) return done(pid ? 'ignored-no-project' : 'ignored-unregistered');
  if (!cur) return done('no-project', replyText('no-project', p.lang, { id: pid }));
  const title = tidyTitle(cur.name).slice(0, 120);
  entry.t = title;
  if (p.kind === 'unknown' || (p.kind === 'help' && !p.id)) return done(p.kind === 'help' ? 'help' : 'unknown', replyText('help', p.lang));

  const who = 'SMS from ' + maskPhone(from), via = entry.ch === 'whatsapp' ? 'WhatsApp' : 'SMS';
  const head = '📱 Community report by ' + via + ' from ' + from + (reg && reg.name ? ' (' + reg.name + ')' : reg ? ' (registered committee phone)' : '') + ': ';
  const quote = '<br>Message: “' + text.replace(/</g, '&lt;') + '”';
  const repairs = await getJson(env, 'pinrepairs', {}), open = repairs[pid];
  const ref = entry.sid.slice(0, 5).toUpperCase();
  if (p.kind === 'fixed') {
    if (!open) { await D.createUpdate(token, pid, head + '✅ Says it is working.' + quote, api); return done('noted-working', replyText('fixed-check', p.lang, { t: title, ref })); }
    if (reg && reg.id === pid) {
      await D.createUpdate(token, pid, head + '✅ Working again. The open repair is closed in the RAFIKI Pin app; a field visit can confirm it.' + quote, api);
      delete repairs[pid]; await putJson(env, 'pinrepairs', repairs);
      await logAct(env, { by: who, a: 'fixed', id: pid, t: title, src: 'sms' });
      return done('fixed', replyText('fixed', p.lang, { t: title, ref }));
    }
    await D.createUpdate(token, pid, head + '✅ Says it is working again. This number is not registered for this project, so the repair stays open until the team confirms it.' + quote, api);
    return done('fixed-unconfirmed', replyText('fixed-check', p.lang, { t: title, ref }));
  }
  const state = p.kind === 'broken' ? 'notworking' : 'repair';
  if (open) {
    await D.createUpdate(token, pid, head + (state === 'notworking' ? '❌ Not working' : '⚠️ Needs repair') + ' (a repair is already open in the RAFIKI Pin app).' + quote, api);
    return done('already-open', replyText('already', p.lang, { t: title, ref }));
  }
  const note = head + (state === 'notworking' ? '❌ Not working.' : '⚠️ Needs repair.') + quote + '<br>Reported through the RAFIKI Pin SMS line. Please call them to confirm, then plan a visit.';
  await D.createUpdate(token, pid, note, api);
  repairs[pid] = { state, by: who, at: now, note: (p.note || text).slice(0, 300), t: title, urgency: '', problems: [], parts: '', cost: null, src: 'sms' };
  await putJson(env, 'pinrepairs', repairs);
  await logAct(env, { by: who, a: 'visit', s: state, id: pid, t: title, src: 'sms' });
  if (state === 'notworking') {
    const lines = [title + ' was reported NOT WORKING by ' + via + ' from ' + from + (reg && reg.name ? ' (' + reg.name + ')' : '') + '.', 'Message: “' + text + '”'];
    const link = (deps && deps.site ? deps.site : '') + '/pin#p/' + pid + '/hist';
    try { await D.sendMail(env, '⚠️ ' + title + ': community reports it is not working', alertHtml(title + ': not working (community ' + via + ')', lines, link), lines.join('\n') + '\n' + link); } catch (e) { console.error('sms alert:', e && e.message); }
  }
  return done(state === 'notworking' ? 'reported-broken' : 'reported-repair', replyText(p.kind === 'broken' ? 'broken' : 'repair', p.lang, { t: title, ref }));
}

// What is set in Cloudflare (names only, never values), for the Office
export function smsSetup(env) {
  const v = k => !!(env[k] && String(env[k]).trim());
  return {
    storage: !!env.FEED, monday: v('MONDAY_API_TOKEN'),
    at: { on: v('SMS_WEBHOOK_TOKEN') && String(env.SMS_WEBHOOK_TOKEN).length >= 16, replies: v('AT_USERNAME') && v('AT_API_KEY'),
      vars: { SMS_WEBHOOK_TOKEN: v('SMS_WEBHOOK_TOKEN'), AT_USERNAME: v('AT_USERNAME'), AT_API_KEY: v('AT_API_KEY'), AT_SENDER_ID: v('AT_SENDER_ID'), AT_KEYWORD: v('AT_KEYWORD') },
      tokenShort: v('SMS_WEBHOOK_TOKEN') && String(env.SMS_WEBHOOK_TOKEN).length < 16 },
    twilio: { on: v('TWILIO_AUTH_TOKEN'), vars: { TWILIO_AUTH_TOKEN: v('TWILIO_AUTH_TOKEN') } }
  };
}
