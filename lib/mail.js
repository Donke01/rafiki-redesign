// Office › Send an e-mail: an admin writes one message and it goes to donors, to the admins, or to team members,
// as one e-mail per person (never a CC or BCC list, so nobody sees anyone else's address).
//
// Audiences:  donors:all            every donor viewer with an e-mail who hasn't unsubscribed and whose link isn't revoked
//             donors:project:<id>   the same, for donors linked to that project
//             donors:pick           chosen viewers ("<donor id>.<viewer id>", or "<donor id>" for all of a donor's viewers)
//             admins                admins with an e-mail (the team list, PIN_ADMINS names with an e-mail, ALERT_EMAILS)
//             team                  everyone on the team with an e-mail
//             people:pick           chosen team members (keys from the overview)
//             pick                  "Choose people": chosen team members (people: [keys]) and chosen donor viewers (donors:
//                                   ["<donor id>.<viewer id>" or "<donor id>"]) together, one e-mail per address. Each person
//                                   gets the e-mail of their own kind: donors keep their unsubscribe link and are skipped if
//                                   unsubscribed or revoked.
// {name} and {first} in the subject or message are replaced per person. The message is plain text: paragraphs,
// and lines starting with "- " as bullets. Everything a person typed is escaped before it goes into the e-mail.
// Donor e-mails carry the one-click unsubscribe (List-Unsubscribe headers and a footer link); their private page link
// is only added when the admin asks for it, and then each donor gets their own.
//
// Sending goes through Resend's batch endpoint (one request carries up to 100 separate e-mails), in small batches with
// pauses. Resend's free plan allows 100 e-mails a day and 3,000 a month (and 10 API requests a second), so a send is
// capped (MAIL_CAP, default 80) and checked against what was already sent today and this month (MAIL_DAY_CAP, default
// 100; MAIL_MONTH_CAP, default 3000). Every send is logged in KV (pinmaillog): who, when, the audience, the subject,
// the counts and each recipient's status (names only, no addresses).
import { getJson, putJson, team, adminNames, sameName, escHtml, mailHtml } from './pin-store.js';
import { donors, linkKey, linkOrigin, unsubSig, currentLink, CONTACT } from './donor.js';

export const LOG_KEY = 'pinmaillog';
const LOG_KEEP = 200, LOG_SHOW = 50;
export const BATCH = 20;          // e-mails per Resend batch request
export const PAUSE = 600;         // ms between batches (well under 10 requests a second)
const MAX_SUBJECT = 150, MAX_BODY = 6000;
const okEmail = s => /^[^@\s,;<>"]+@[^@\s,;<>"]+\.[^@\s,;<>"]+$/.test(String(s || ''));
const int = (v, lo, hi, d) => { const n = Math.floor(Number(v)); return Number.isFinite(n) && n >= lo ? Math.min(n, hi) : d; };

/** The limits for one send, today and this month (Cloudflare variables MAIL_CAP, MAIL_DAY_CAP, MAIL_MONTH_CAP). */
export function caps(env) {
  return { per: int(env.MAIL_CAP, 1, 2000, 80), day: int(env.MAIL_DAY_CAP, 1, 100000, 100), month: int(env.MAIL_MONTH_CAP, 1, 1000000, 3000) };
}
export const mailReady = env => !!(env.RESEND_API_KEY && env.ALERT_FROM);
export const firstName = n => String(n || '').trim().split(/\s+/)[0] || '';

// ---- who it goes to -----------------------------------------------------------------------------------------
function cfPeople(env) {
  return String(env.PIN_USERS || '').split(/[,;\n]/).map(s => { const a = s.split(':').map(x => x.trim()); if (a.length > 2 && /^(ke|tz|ug|mw)$/i.test(a[a.length - 1])) a.pop(); return a.length >= 2 ? a.slice(0, -1).join(':') : ''; }).filter(Boolean);
}
const nameFromEmail = e => String(e).split('@')[0].split(/[._-]+/).filter(Boolean).map(w => w.charAt(0).toUpperCase() + w.slice(1)).join(' ') || e;
/** Everyone on the team: { key, name, email, admin, role }. Keys are stable: "t:<name>" or "a:<n>" for ALERT_EMAILS. */
export async function teamPeople(env) {
  const t = await team(env), admins = adminNames(env), out = [];
  const add = p => { if (!out.some(x => sameName(x.name, p.name) && (!p.email || !x.email || x.email === p.email))) out.push(p); };
  t.forEach(p => add({ key: 't:' + p.name.toLowerCase(), name: p.name, email: okEmail(p.email) ? String(p.email).trim().toLowerCase() : '', admin: !!p.admin || admins.includes(p.name.toLowerCase()) }));
  cfPeople(env).forEach(n => add({ key: 't:' + n.toLowerCase(), name: n, email: '', admin: admins.includes(n.toLowerCase()) }));
  admins.forEach(n => { if (!out.some(x => x.name.toLowerCase() === n)) add({ key: 't:' + n, name: n.replace(/\b\w/g, c => c.toUpperCase()), email: '', admin: true }); });
  String(env.ALERT_EMAILS || '').split(/[,;\s]+/).map(s => s.trim().toLowerCase()).filter(okEmail).forEach((e, i) => {
    if (out.some(x => x.email === e)) return;
    add({ key: 'a:' + i, name: nameFromEmail(e), email: e, admin: true, alerts: true });
  });
  return out.map(p => Object.assign(p, { role: p.admin ? 'admin' : 'field' }));
}
/** Donor viewers: { key, name, email, donor, viewer, why } where why is '' (can be e-mailed), 'unsub', 'revoked' or 'no-email'. */
export function donorPeople(list) {
  const out = [];
  for (const d of list || []) for (const v of d.viewers || []) {
    const email = okEmail(v.email) ? String(v.email).trim().toLowerCase() : '';
    const why = !email ? 'no-email' : v.unsub ? 'unsub' : (v.revoked || !v.h) ? 'revoked' : '';
    out.push({ key: d.id + '.' + v.vid, name: v.name || d.name, email, donor: d, viewer: v, why, projects: d.projects || [] });
  }
  return out;
}
/** The audience, resolved: { kind: 'donor'|'team'|'mixed', list: [person], skipped: { unsub, revoked, 'no-email', dup } }. null when the audience is unknown.
 *  For 'pick', picks is { people: [team keys], donors: [viewer keys or donor ids] } (or one array of both) and each person carries their own kind. */
export async function resolve(env, audience, picks) {
  const a = String(audience || '');
  const keys = v => (Array.isArray(v) ? v : []).map(String).slice(0, 2000);
  const both = picks && !Array.isArray(picks) && typeof picks === 'object' ? picks : null;
  const pick = new Set(both ? [] : keys(picks));
  let kind, all;
  if (a === 'pick') {
    const pp = new Set(both ? keys(both.people) : [...pick]), pd = new Set(both ? keys(both.donors) : [...pick]);
    kind = 'mixed';
    // team first: a teammate who is also a donor gets the team e-mail once
    all = (await teamPeople(env)).filter(p => pp.has(p.key)).map(p => Object.assign({ why: p.email ? '' : 'no-email' }, p, { kind: 'team' }))
      .concat(donorPeople(await donors(env)).filter(p => pd.has(p.key) || pd.has(p.donor.id)).map(p => Object.assign(p, { kind: 'donor' })));
  } else if (a === 'donors:all' || a === 'donors:pick' || /^donors:project:\d{1,20}$/.test(a)) {
    kind = 'donor'; all = donorPeople(await donors(env));
    if (a === 'donors:pick') all = all.filter(p => pick.has(p.key) || pick.has(p.donor.id));
    else if (a !== 'donors:all') { const pid = a.slice(15); all = all.filter(p => (p.projects || []).map(String).includes(pid)); }
  } else if (a === 'admins' || a === 'team' || a === 'people:pick') {
    kind = 'team'; all = (await teamPeople(env)).map(p => Object.assign({ why: p.email ? '' : 'no-email' }, p));
    if (a === 'admins') all = all.filter(p => p.admin);
    if (a === 'people:pick') all = all.filter(p => pick.has(p.key));
  } else return null;
  const skipped = { unsub: 0, revoked: 0, 'no-email': 0, dup: 0 }, seen = new Set(), list = [];
  for (const p of all) {
    if (p.why) { skipped[p.why]++; continue; }
    if (seen.has(p.email)) { skipped.dup++; continue; }   // one e-mail per address, even if they're on two donor records
    seen.add(p.email); list.push(p);
  }
  return { kind, list, skipped };
}

// ---- the message ----------------------------------------------------------------------------------------------
/** {name} and {first} for one person (before escaping, so names are escaped with everything else). */
export function personalise(s, name) {
  const n = String(name || '').trim();
  return String(s == null ? '' : s).replace(/\{\s*first\s*\}/gi, firstName(n) || 'friend').replace(/\{\s*name\s*\}/gi, n || 'friend');
}
/** Plain text → escaped HTML: blank lines make paragraphs, lines starting with "- " make a bulleted list. */
export function bodyHtml(text) {
  const P = 'margin:0 0 16px;font-size:16px;line-height:26px;color:#1F2A33';
  return String(text || '').replace(/\r\n?/g, '\n').split(/\n\s*\n/).map(b => b.replace(/^\n+|\s+$/g, '')).filter(Boolean).map(b => {
    const lines = b.split('\n'), out = []; let ul = [], para = [];
    const flushP = () => { if (para.length) out.push('<p style="' + P + '">' + para.map(escHtml).join('<br>') + '</p>'); para = []; };
    const flushU = () => { if (ul.length) out.push('<ul style="margin:0 0 16px;padding:0 0 0 22px;font-size:16px;line-height:26px;color:#1F2A33">' + ul.map(x => '<li style="margin:0 0 6px">' + escHtml(x) + '</li>').join('') + '</ul>'); ul = []; };
    lines.forEach(l => { const m = /^\s*[-•]\s+(.*)$/.exec(l); if (m) { flushP(); ul.push(m[1]); } else { flushU(); para.push(l.trim()); } });
    flushP(); flushU();
    return out.join('');
  }).join('');
}
function bodyText(text) { return String(text || '').replace(/\r\n?/g, '\n').replace(/^\s*•\s+/gm, '- ').trim(); }
/** A link someone typed: only http(s), no spaces or quotes. */
export function safeUrl(u) {
  const s = String(u || '').trim();
  if (!s || s.length > 500 || /[\s"<>]/.test(s)) return '';
  try { const x = new URL(s); return x.protocol === 'https:' || x.protocol === 'http:' ? x.toString() : ''; } catch (e) { return ''; }
}
/** The admin's draft, cleaned: { subject, body, button: {label, href}|null, photo: {kind, id}|null, includeLink }. */
export function cleanDraft(b) {
  const one = (s, n) => String(s || '').replace(/[\r\n\t]+/g, ' ').replace(/\s+/g, ' ').trim().slice(0, n);
  const btn = b && b.button && safeUrl(b.button.href) && one(b.button.label, 40) ? { label: one(b.button.label, 40), href: safeUrl(b.button.href) } : null;
  const ph = b && b.photo;
  const photo = ph && ph.kind === 'project' && /^\d{1,20}$/.test(String(ph.id)) ? { kind: 'project', id: String(ph.id) }
    : ph && ph.kind === 'upload' && /^[A-Za-z0-9]{16,40}$/.test(String(ph.id)) ? { kind: 'upload', id: String(ph.id) } : null;
  return { subject: one(b && b.subject, MAX_SUBJECT), body: String((b && b.body) || '').replace(/\r\n?/g, '\n').slice(0, MAX_BODY).trim(), button: btn, photo, includeLink: !!(b && b.includeLink) };
}
export function photoUrl(site, photo) {
  if (!photo) return '';
  return photo.kind === 'project' ? site + '/api/photo/' + photo.id + '?w=960' : site + '/api/mail?img=' + photo.id;
}
/**
 * One person's e-mail: { subject, html, text, headers }.
 * o: { site, kind, person: {name}, draft, by, link (their private page, donors only), unsub (their unsubscribe link, donors only) }
 */
export function renderMail(o) {
  const d = o.draft, name = o.person && o.person.name || '', donor = o.kind === 'donor', site = String(o.site || '').replace(/\/+$/, '');
  const subject = personalise(d.subject, name), body = personalise(d.body, name);
  const sign = (o.by ? o.by + ', ' : '') + 'RAFIKI Outreach';
  const pic = photoUrl(site, d.photo);
  let html = (pic ? '<img src="' + escHtml(pic) + '" width="536" alt="" style="display:block;width:100%;max-width:536px;height:auto;border:0;border-radius:14px;margin:0 0 20px">' : '') +
    bodyHtml(body) + '<p style="margin:0;font-size:16px;line-height:26px;color:#1F2A33">' + escHtml(sign) + '</p>';
  let cta = d.button, note = '';
  if (donor && o.link) {
    if (!cta) cta = { label: 'See your projects, map and photos', href: o.link };
    else note = 'Your private project page: <a href="' + escHtml(o.link) + '" style="color:#0A78C2;white-space:nowrap">open it here</a>. Please don’t forward it.';
  }
  const footer = donor
    ? 'You get this because you support RAFIKI Outreach&nbsp;projects.' + (o.unsub ? ' <a href="' + escHtml(o.unsub) + '" style="color:#5A6066;white-space:nowrap">Unsubscribe</a>' : '')
    : 'You get this because you are on the RAFIKI Pin&nbsp;team.';
  html = mailHtml({
    site, tone: donor ? 'coral' : 'blue', icon: donor ? 'heart-coral' : 'bell-blue', product: donor ? 'RAFIKI Outreach' : 'RAFIKI Pin',
    eyebrow: donor ? 'News from RAFIKI Outreach' : 'Message from ' + (o.by || 'the office'), title: subject, bodyHtml: html,
    cta, note: note || (donor ? 'Questions? Just reply to this e-mail.' : ''), footer, preheader: body.replace(/\s+/g, ' ').slice(0, 110)
  });
  const text = body + '\n\n' + sign + (cta ? '\n\n' + cta.label + ': ' + cta.href : '') + (note && o.link ? '\n\nYour private project page: ' + o.link : '') + (donor && o.unsub ? '\n\nUnsubscribe: ' + o.unsub : '');
  const headers = donor && o.unsub ? { 'List-Unsubscribe': '<' + o.unsub + '>, <mailto:' + CONTACT + '?subject=unsubscribe>', 'List-Unsubscribe-Post': 'List-Unsubscribe=One-Click' } : undefined;
  return { subject, html, text: bodyText(text), headers };
}
/** Each donor's own links (private page only when asked for). In previews and tests: harmless sample links. */
export async function donorLinks(env, site, p, includeLink, sample) {
  if (sample) return { link: includeLink ? linkOrigin(env) + '/donor/(their-private-link)' : '', unsub: site + '/api/donor?unsub=(their-own-link)' };
  const key = await linkKey(env);
  const unsub = site + '/api/donor?unsub=' + p.donor.id + '.' + p.viewer.vid + '.' + await unsubSig(key, p.donor.id, p.viewer.vid);
  let link = '';
  if (includeLink) { const t = await currentLink(env, key, p.donor, p.viewer); if (t) link = linkOrigin(env) + '/donor/' + t; }
  return { link, unsub };
}

// ---- sending --------------------------------------------------------------------------------------------------
const sleep = ms => new Promise(r => setTimeout(r, ms));
/** One Resend batch (each item is its own e-mail to one address). Returns one { ok, id, error } per item. */
export async function resendBatch(env, items, opts) {
  const o = opts || {};
  for (let tries = 0; ; tries++) {
    let r;
    try {
      r = await fetch('https://api.resend.com/emails/batch', { method: 'POST', headers: { Authorization: 'Bearer ' + env.RESEND_API_KEY, 'Content-Type': 'application/json', 'x-batch-validation': 'permissive' }, body: JSON.stringify(items) });
    } catch (e) { return items.map(() => ({ ok: false, error: 'network' })); }
    if (r.status === 429 && tries < 2) { const w = Math.min(5, Number(r.headers.get('retry-after')) || 1); await (o.sleep || sleep)(w * 1000); continue; }
    const raw = await r.text(); let j = {}; try { j = JSON.parse(raw); } catch (e) {}
    if (!r.ok) { const msg = String(j.message || raw || r.status).slice(0, 200); console.error('mail batch:', r.status, msg); return items.map(() => ({ ok: false, error: msg, status: r.status })); }
    const bad = {}; (j.errors || []).forEach(x => { bad[x.index] = String(x.message || 'rejected').slice(0, 200); });
    const data = j.data || [];
    let k = 0;
    return items.map((_, i) => bad[i] !== undefined ? { ok: false, error: bad[i] } : { ok: true, id: (data[k++] || {}).id || '' });
  }
}
/** Sends one e-mail per person, in batches with pauses. made(person) → { subject, html, text, headers }. */
export async function sendAll(env, people, made, opts) {
  const o = opts || {}, size = o.batch || BATCH, from = env.ALERT_FROM, out = [];
  for (let i = 0; i < people.length; i += size) {
    if (i) await (o.sleep || sleep)(o.pause == null ? PAUSE : o.pause);
    const chunk = people.slice(i, i + size), items = [];
    for (const p of chunk) {
      const m = await made(p);
      const it = { from, to: [p.email], subject: m.subject, html: m.html, text: m.text, reply_to: o.replyTo || CONTACT };
      if (m.headers) it.headers = m.headers;
      items.push(it);
    }
    const res = await resendBatch(env, items, o);
    chunk.forEach((p, k) => out.push({ p, ok: res[k].ok, error: res[k].error || '' }));
  }
  return out;
}

// ---- the log ----------------------------------------------------------------------------------------------------
export async function readLog(env) { return getJson(env, LOG_KEY, []); }
export async function addLog(env, entry) {
  const a = await readLog(env);
  a.push(entry);
  await putJson(env, LOG_KEY, a.slice(-LOG_KEEP));
}
/** Sent today (UTC, as Resend counts) and this month, from the log. */
export function usage(log, now) {
  const n = new Date(now || Date.now()), day = n.toISOString().slice(0, 10), mon = day.slice(0, 7);
  let today = 0, month = 0;
  (log || []).forEach(e => { const at = String(e.at || ''); if (at.slice(0, 7) === mon) { month += Number(e.sent) || 0; if (at.slice(0, 10) === day) today += Number(e.sent) || 0; } });
  return { today, month };
}
export const lastLog = log => (log || []).slice(-LOG_SHOW).reverse();
/** Is a send of n e-mails within the limits? → null, or { reason, cap, left }. */
export function overCap(env, n, use) {
  const c = caps(env);
  if (n > c.per) return { reason: 'cap', cap: c.per, left: c.per };
  if (use.today + n > c.day) return { reason: 'day-cap', cap: c.day, left: Math.max(0, c.day - use.today) };
  if (use.month + n > c.month) return { reason: 'month-cap', cap: c.month, left: Math.max(0, c.month - use.month) };
  return null;
}
export function audienceLabel(a, extra) {
  const s = String(a || '');
  if (s === 'donors:all') return 'All donors';
  if (s === 'donors:pick') return 'Chosen donors';
  if (s.indexOf('donors:project:') === 0) return 'Donors of ' + (extra || 'project ' + s.slice(15));
  return { admins: 'Admins', team: 'The whole team', 'people:pick': 'Chosen team members', pick: 'Chosen people', invite: 'App invite' }[s] || s;
}

// ---- the app invite (Office › Team › a person › Send app invite) ----------------------------------------------
// A ready-made e-mail to one team member: what the app is, the Office tab for admins, how to open it on an iPhone,
// an Android phone or a computer, and a button to the app. It NEVER carries a PIN: the admin sends that separately.
export const INVITE_URL = 'https://pin.rafikioutreach.org/pin';
export const INVITE_SUBJECT = 'You’re invited to RAFIKI Pin';
/** The invite as a draft for renderMail: { subject, body, button, photo, includeLink }. person: { name, admin }. */
export function inviteDraft(person, by) {
  const from = String(by || '').trim() || 'The RAFIKI office';
  const body = [
    'Hi {first},',
    from + ' has added you to RAFIKI Pin, the field app the RAFIKI Outreach team uses to look after our water projects. With it you can:\n' +
      '- see every project on a map, and the ones closest to you\n- report a visit, with photos, in a few taps\n- report a repair, and see what the team did today',
    person && person.admin
      ? 'You’re an admin, so you also get the Office tab: repairs, reviews, the team, visit plans and reports.'
      : 'Admins also get an Office tab, for repairs, reviews, the team and reports.',
    'How to open it:\n- iPhone: open the link in Safari, tap Share, then “Add to Home Screen”.\n- Android phone or a computer: just open the link.',
    'Sign in with your PIN, which I’m sending you separately.'
  ].join('\n\n');
  return { subject: INVITE_SUBJECT, body, button: { label: 'Open RAFIKI Pin', href: INVITE_URL }, photo: null, includeLink: false };
}
/** The same invite as plain text, for WhatsApp or SMS ("Share invite"). */
export function inviteText(person, by) {
  const d = inviteDraft(person, by);
  return personalise(d.subject + '\n\n' + d.body + '\n\nOpen RAFIKI Pin: ' + INVITE_URL + '\n\n' + (String(by || '').trim() || 'RAFIKI Outreach'), person && person.name);
}
