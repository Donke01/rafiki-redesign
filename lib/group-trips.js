// Group trips: volunteer and donor expeditions (Office › Trips › New group trip, admins only). Separate from field trips.
// A trip has its details, the group (participants), leaders, day-by-day items (flights, transfers, lodging, project days,
// school visits, safari, free time, meals), costs per person and payments, a packing list and "What to expect" text,
// emergency contacts, and a private read-only page for the group (/pin-trip?t=…, /api/trip-share?t=…).
//
// Privacy: diet and medical notes, e-mails, phones and payments are for admins only and never on the shared page.
// Passport numbers are never stored: there is no field for them, and anything that looks like one is refused.
// KV: group:<id>, groupidx, itinshare:<sha256(token)> (shared with field trips).
import { getJson, putJson, escHtml, mailHtml } from './pin-store.js';
import { fxRates, convert } from './fx-rates.js';
import { newId, newToken, sha } from './itin.js';
import { CONTACT } from './donor.js';

export const KINDS = ['flight', 'transfer', 'lodging', 'project', 'school', 'safari', 'free', 'meal', 'other'];
export const ROLES = ['volunteer', 'donor', 'leader'];
export const CURRENCIES = ['USD', 'KES', 'GBP', 'EUR', 'CAD', 'AUD', 'TZS', 'UGX', 'MWK'];
export const LIMITS = { participants: 60, items: 300, costs: 60, payments: 600, attachments: 6, emergency: 8, text: 4000 };
const clean = (s, n) => String(s == null ? '' : s).replace(/[<>]/g, '').replace(/[ \t]+/g, ' ').trim().slice(0, n);
const text = (s, n) => String(s == null ? '' : s).replace(/[<>]/g, '').replace(/\r\n?/g, '\n').replace(/[ \t]+/g, ' ').replace(/\n{3,}/g, '\n\n').trim().slice(0, n);
const isDay = s => /^\d{4}-\d{2}-\d{2}$/.test(String(s || '')) && !isNaN(Date.parse(s + 'T00:00:00Z'));
const isHm = s => /^([01]?\d|2[0-3]):[0-5]\d$/.test(String(s || ''));
const okEmail = s => /^[^\s@<>]{1,64}@[^\s@<>]{1,190}\.[A-Za-z]{2,}$/.test(String(s || ''));
const phone = v => { const s = String(v || '').replace(/[^\d+ ]/g, '').replace(/(?!^)\+/g, '').trim().slice(0, 20); return /\d{7,}/.test(s.replace(/\s/g, '')) ? s : ''; };
const COUNTRY = { ke: 'Kenya', tz: 'Tanzania', ug: 'Uganda', mw: 'Malawi' };

// ---- passport numbers: never stored ---------------------------------------------------------------------
// a key like passportNumber / passport_no / passportId, or "passport" followed by something number-like in a note
const PASSPORT_KEY = /passport.?(no|num|number|nr|id|#)|^ppn?o?$/i;
const PASSPORT_VALUE = /passport\s*(no\.?|number|nr\.?|#|:)?\s*[:#]?\s*[A-Z0-9]{6,12}\b/i;
export function hasPassportNumber(o, depth) {
  if (!o || typeof o !== 'object' || (depth || 0) > 4) return false;
  for (const [k, v] of Object.entries(o)) {
    if (PASSPORT_KEY.test(k)) return true;
    if (typeof v === 'string' && PASSPORT_VALUE.test(v) && /\d/.test(v)) return true;
    if (v && typeof v === 'object' && hasPassportNumber(v, (depth || 0) + 1)) return true;
  }
  return false;
}

// ---- defaults ------------------------------------------------------------------------------------------
export function defaultPacking() {
  return [
    'Passport (valid for 6 months after you travel) and your visa or eTA',
    'Yellow fever certificate, if your travel clinic says you need one',
    'Malaria tablets, as your travel clinic advises',
    'Insect repellent, and long sleeves for the evenings',
    'Sunscreen, a hat and sunglasses',
    'Clothes you don’t mind getting muddy for drilling days, and closed shoes or boots',
    'A warm layer: mornings and evenings in the highlands are cool',
    'A light rain jacket',
    'Torch or head torch',
    'Phone charger, power bank and a plug adapter (type G)',
    'Refillable water bottle',
    'Any medicines you take, in their original packaging'
  ].map(x => '• ' + x).join('\n');
}
export function defaultExpect(ck) {
  const c = COUNTRY[ck] || 'East Africa', slug = { ke: 'kenya', tz: 'tanzania', ug: 'uganda', mw: 'malawi' }[ck] || 'kenya';
  return [
    'Health: see a travel clinic 6–8 weeks before you go. Ask about vaccinations (yellow fever, typhoid, hepatitis A) and malaria tablets for ' + c + '.',
    'Malaria advice: https://wwwnc.cdc.gov/travel/destinations/traveler/none/' + slug,
    'Drilling days: long, hot and dusty. Wear old clothes and closed shoes, drink plenty of water and use sunscreen.',
    'School and community visits: dress modestly (knees and shoulders covered). Always ask before you take a photo of a person, and never photograph children without the school’s permission.',
    'Safari: stay inside the vehicle unless the guide says otherwise, wear neutral colours, and bring binoculars. Early starts are worth it.',
    'Money: carry some cash in small notes; M-Pesa and cards work in towns but not everywhere.',
    'Your leaders are with you all the time. If anything worries you, tell them straight away.'
  ].join('\n\n');
}

// ---- storage ----------------------------------------------------------------------------------------------
export const loadGroup = (env, id) => (/^gt_[A-Za-z0-9]{6,20}$/.test(String(id || '')) ? getJson(env, 'group:' + id, null) : Promise.resolve(null));
const row = t => ({ id: t.id, name: t.name, from: t.from, to: t.to, country: t.country, participants: t.participants.length, public: !!t.public, donors: t.donors || [], updated: t.updated });
export async function saveGroup(env, t) {
  await putJson(env, 'group:' + t.id, t);
  const idx = (await getJson(env, 'groupidx', [])).filter(x => x.id !== t.id); idx.unshift(row(t));
  await putJson(env, 'groupidx', idx.slice(0, 500));
}
export async function removeGroup(env, t) {
  if (env.FEED) { await env.FEED.delete('group:' + t.id); if (t.share && t.share.h) await env.FEED.delete('itinshare:' + t.share.h); }
  await putJson(env, 'groupidx', (await getJson(env, 'groupidx', [])).filter(x => x.id !== t.id));
}

// ---- validation ------------------------------------------------------------------------------------------
export function participantFrom(p, cur) {
  if (!p || typeof p !== 'object') return { err: 'bad-request' };
  if (hasPassportNumber(p)) return { err: 'passport-number' };
  const o = Object.assign({}, cur || {});
  if (p.name != null) o.name = clean(p.name, 80);
  if (!o.name) return { err: 'bad-name' };
  if (p.email != null) { const e = clean(p.email, 254).toLowerCase(); if (e && !okEmail(e)) return { err: 'bad-email' }; o.email = e; }
  if (p.phone != null) o.phone = phone(p.phone);
  if (p.role != null) o.role = ROLES.includes(p.role) ? p.role : 'volunteer';
  if (!o.role) o.role = 'volunteer';
  if (p.diet != null) o.diet = clean(p.diet, 200);
  if (p.medical != null) o.medical = text(p.medical, 500);
  if (p.passportReminder != null) o.passportReminder = !!p.passportReminder;
  if (p.passportExpiry !== undefined) { const v = p.passportExpiry; if (v === null || v === '') o.passportExpiry = null; else if (/^\d{4}-\d{2}(-\d{2})?$/.test(String(v))) o.passportExpiry = String(v); else return { err: 'bad-date', field: 'passportExpiry' }; }
  if (p.donorId !== undefined) o.donorId = p.donorId ? clean(p.donorId, 40) : null;
  return { p: o };
}
export function itemFrom(it, cur, trip) {
  if (!it || typeof it !== 'object') return { err: 'bad-item' };
  if (hasPassportNumber(it)) return { err: 'passport-number' };
  const o = Object.assign({}, cur || {});
  if (it.date != null) { if (!isDay(it.date)) return { err: 'bad-date' }; o.date = it.date; }
  if (!o.date) return { err: 'bad-date' };
  if (it.kind != null) o.kind = KINDS.includes(it.kind) ? it.kind : 'other';
  if (!o.kind) o.kind = 'other';
  for (const k of ['start', 'end']) if (it[k] !== undefined) { if (it[k] === null || it[k] === '') o[k] = null; else if (isHm(it[k])) o[k] = it[k]; else return { err: 'bad-time', field: k }; }
  if (it.title != null) o.title = clean(it.title, 120);
  if (!o.title) return { err: 'bad-item', field: 'title' };
  if (it.notes != null) o.notes = text(it.notes, 1500);
  if (it.location !== undefined) {
    const l = it.location;
    if (!l) o.location = null;
    else { const lat = Number(l.lat), lng = Number(l.lng); o.location = { name: clean(l.name, 120), address: clean(l.address, 200), lat: isFinite(lat) && Math.abs(lat) <= 90 && l.lat != null ? Math.round(lat * 1e5) / 1e5 : null, lng: isFinite(lng) && Math.abs(lng) <= 180 && l.lng != null ? Math.round(lng * 1e5) / 1e5 : null }; }
  }
  if (it.attachments !== undefined) o.attachments = (Array.isArray(it.attachments) ? it.attachments : []).slice(0, LIMITS.attachments).map(a => ({ name: clean(a && a.name, 80), photo: /^[A-Za-z0-9_-]{1,64}$/.test(String(a && a.photo || '')) ? a.photo : null, url: /^https:\/\/[^\s<>"]{3,400}$/.test(String(a && a.url || '')) ? a.url : null })).filter(a => a.name && (a.photo || a.url));
  if (it.flight !== undefined) o.flight = it.flight ? { airline: clean(it.flight.airline, 60), number: clean(it.flight.number, 12).toUpperCase(), from: clean(it.flight.from, 40), to: clean(it.flight.to, 40), depart: isHm(it.flight.depart) ? it.flight.depart : null, arrive: isHm(it.flight.arrive) ? it.flight.arrive : null, checkIn: isHm(it.flight.checkIn) ? it.flight.checkIn : null } : null;
  if (it.lodging !== undefined) o.lodging = it.lodging ? { name: clean(it.lodging.name, 120), address: clean(it.lodging.address, 200), checkIn: clean(it.lodging.checkIn, 40), checkOut: clean(it.lodging.checkOut, 40), contact: clean(it.lodging.contact, 80) } : null;
  if (it.projectId !== undefined) o.projectId = it.projectId && /^\d{1,15}$/.test(String(it.projectId)) ? String(it.projectId) : null;
  if (it.remind !== undefined) o.remind = it.remind ? { before: ['24h', '3h', '1h'].includes(it.remind.before) ? it.remind.before : null, sentAt: (cur && cur.remind && cur.remind.sentAt) || null } : null;
  if (trip && (o.date < trip.from || o.date > trip.to)) return { err: 'bad-date', why: 'outside-trip' };
  return { it: o };
}
export function tripFrom(b, cur) {
  if (hasPassportNumber(b)) return { err: 'passport-number' };
  const t = Object.assign({}, cur || {});
  if (b.name != null) t.name = clean(b.name, 100);
  if (!t.name) return { err: 'bad-name' };
  for (const k of ['from', 'to']) if (b[k] != null) { if (!isDay(b[k])) return { err: 'bad-date', field: k }; t[k] = b[k]; }
  if (!t.from || !t.to || t.to < t.from || (Date.parse(t.to) - Date.parse(t.from)) / 864e5 > 60) return { err: 'bad-date' };
  if (b.country != null) t.country = ['ke', 'tz', 'ug', 'mw'].includes(b.country) ? b.country : 'ke';
  if (!t.country) t.country = 'ke';
  if (b.destination != null) t.destination = clean(b.destination, 120);
  if (b.summary != null) t.summary = text(b.summary, 400);
  if (b.public != null) t.public = !!b.public;
  if (b.leaders != null) t.leaders = (Array.isArray(b.leaders) ? b.leaders : []).map(x => clean(x, 60)).filter(Boolean).slice(0, 6);
  if (b.contact != null) { const c = clean(b.contact, 254); if (c && !okEmail(c)) return { err: 'bad-email', field: 'contact' }; t.contact = c; }
  if (b.donors != null) t.donors = (Array.isArray(b.donors) ? b.donors : []).map(x => clean(x, 40)).filter(Boolean).slice(0, 40);
  if (b.packing != null) t.packing = text(b.packing, LIMITS.text);
  if (b.expect != null) t.expect = text(b.expect, LIMITS.text);
  if (b.emergency != null) t.emergency = (Array.isArray(b.emergency) ? b.emergency : []).slice(0, LIMITS.emergency).map(e => ({ name: clean(e && e.name, 80), phone: phone(e && e.phone), role: clean(e && e.role, 60) })).filter(e => e.name && e.phone);
  if (t.packing == null) t.packing = defaultPacking();
  if (t.expect == null) t.expect = defaultExpect(t.country);
  for (const k of ['participants', 'items', 'costs', 'payments', 'leaders', 'emergency', 'donors']) if (!Array.isArray(t[k])) t[k] = [];
  if (t.contact == null) t.contact = CONTACT;
  return { t };
}
export function costFrom(c) {
  const amount = Number(c && c.amount);
  if (!(amount >= 0 && amount <= 1e9)) return { err: 'bad-amount' };
  if (!CURRENCIES.includes(c.currency)) return { err: 'bad-currency' };
  return { c: { label: clean(c.label, 80) || 'Cost', amount: Math.round(amount * 100) / 100, currency: c.currency, per: c.per === 'group' ? 'group' : 'person' } };
}
export function paymentFrom(y, trip) {
  const amount = Number(y && y.amount);
  if (!trip.participants.some(p => p.pid === y.pid)) return { err: 'participant' };
  if (!(amount > 0 && amount <= 1e9)) return { err: 'bad-amount' };
  if (!CURRENCIES.includes(y.currency)) return { err: 'bad-currency' };
  return { y: { pid: y.pid, amount: Math.round(amount * 100) / 100, currency: y.currency, at: isDay(y.at) ? y.at : new Date().toISOString().slice(0, 10), note: clean(y.note, 120) } };
}

// ---- totals (USD and KES) --------------------------------------------------------------------------------
export function totals(t, fx) {
  const n = Math.max(1, t.participants.filter(p => p.role !== 'leader').length || t.participants.length);
  const add = (acc, cur, v) => { acc[cur] = Math.round(((acc[cur] || 0) + v) * 100) / 100; };
  const both = list => {
    // one amount per currency → also in USD and KES when rates are known
    const out = { byCurrency: {} };
    list.forEach(([cur, v]) => add(out.byCurrency, cur, v));
    if (fx) { let usd = 0; for (const [cur, v] of Object.entries(out.byCurrency)) { const u = convert(v, cur, 'USD', fx); if (u == null) { usd = null; break; } usd += u; } if (usd != null) { out.USD = Math.round(usd); out.KES = Math.round(convert(usd, 'USD', 'KES', fx)); } }
    return out;
  };
  const per = both(t.costs.map(c => [c.currency, c.per === 'group' ? c.amount / n : c.amount]));
  const group = both(t.costs.map(c => [c.currency, c.per === 'group' ? c.amount : c.amount * n]));
  const paid = {}, due = {};
  t.participants.forEach(p => {
    const mine = both(t.payments.filter(y => y.pid === p.pid).map(y => [y.currency, y.amount]));
    paid[p.pid] = mine;
    if (p.role !== 'leader' && per.USD != null && mine.USD != null) due[p.pid] = { USD: Math.max(0, per.USD - mine.USD), KES: Math.max(0, per.KES - (mine.KES || 0)) };
    else if (p.role !== 'leader' && per.USD != null && !t.payments.some(y => y.pid === p.pid)) due[p.pid] = { USD: per.USD, KES: per.KES };
  });
  return { participants: n, perPerson: per, group, paid, due, fx: fx ? { at: fx.at, USDKES: Math.round(convert(1, 'USD', 'KES', fx) * 100) / 100 } : null };
}
export async function withTotals(env, t) { const fx = await fxRates(env).catch(() => null); return Object.assign({}, t, { totals: totals(t, fx), share: t.share ? { url: t.share.url, at: t.share.at, by: t.share.by } : null }); }

// ---- the group's page (public, English) ------------------------------------------------------------------
export function publicGroup(t, site) {
  const days = {};
  // by day and time; untimed items last in their day
  t.items.slice().sort((a, b) => (a.date + (a.start || '99:99')).localeCompare(b.date + (b.start || '99:99'))).forEach(i => {
    (days[i.date] = days[i.date] || []).push({ start: i.start || null, end: i.end || null, kind: i.kind, title: i.title, notes: i.notes || '',
      location: i.location ? { name: i.location.name, address: i.location.address, lat: i.location.lat, lng: i.location.lng } : null,
      flight: i.flight ? { airline: i.flight.airline, number: i.flight.number, from: i.flight.from, to: i.flight.to, depart: i.flight.depart, arrive: i.flight.arrive, checkIn: i.flight.checkIn || null } : null,
      lodging: i.lodging ? { name: i.lodging.name, address: i.lodging.address, checkIn: i.lodging.checkIn, checkOut: i.lodging.checkOut } : null,
      projectUrl: i.projectId ? (site || '') + '/project/' + i.projectId : null,
      attachments: (i.attachments || []).filter(a => a.url).map(a => ({ name: a.name, url: a.url })) });
  });
  return {
    name: t.name, from: t.from, to: t.to, country: t.country, destination: t.destination || '', summary: t.summary || '',
    leaders: (t.leaders || []).slice(), contact: t.contact || CONTACT,
    days: Object.keys(days).sort().map(date => ({ date, items: days[date] })),
    packing: t.packing || '', expect: t.expect || '', emergency: (t.emergency || []).map(e => ({ name: e.name, phone: e.phone, role: e.role })),
    map: t.items.filter(i => i.location && i.location.lat != null).map(i => ({ name: i.location.name || i.title, lat: i.location.lat, lng: i.location.lng, kind: i.kind })),
    updated: t.updated
  };
}
export async function publicList(env) {
  const idx = (await getJson(env, 'groupidx', [])).filter(x => x.public && x.to >= new Date().toISOString().slice(0, 10));
  const out = [];
  for (const r of idx.slice(0, 20)) { const t = await loadGroup(env, r.id); if (t && t.public) out.push({ name: t.name, from: t.from, to: t.to, country: t.country, summary: t.summary || '', contact: t.contact || CONTACT }); }
  return out.sort((a, b) => (a.from < b.from ? -1 : 1));
}
export async function forDonor(env, donorId, site) {
  const idx = (await getJson(env, 'groupidx', [])).filter(x => (x.donors || []).includes(donorId));
  const out = [];
  for (const r of idx.slice(0, 10)) { const t = await loadGroup(env, r.id); if (t) out.push(Object.assign(publicGroup(t, site), { url: t.share ? t.share.url : null })); }
  return out;
}

// ---- share links -------------------------------------------------------------------------------------------
export async function shareGroup(env, t, by, site, rotate) {
  if (t.share && t.share.h && !rotate) return { url: t.share.url, token: t.share.t };
  if (t.share && t.share.h && env.FEED) await env.FEED.delete('itinshare:' + t.share.h);
  const tok = newToken(), h = await sha(tok);
  await putJson(env, 'itinshare:' + h, { kind: 'group', id: t.id });
  t.share = { t: tok, h, url: new URL('/pin-trip?t=' + tok, site).toString(), at: new Date().toISOString(), by };
  return { url: t.share.url, token: tok };
}

// ---- reminders ----------------------------------------------------------------------------------------------
const D = iso => { const d = new Date(iso + 'T00:00:00Z'); return ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'][d.getUTCDay()] + ' ' + d.getUTCDate() + ' ' + ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'][d.getUTCMonth()]; };
/** The reminder e-mail for one item: { subject, html, text } (personalised with {first name} per person when sent) */
export function reminderMail(t, it, site, firstName) {
  const when = D(it.date) + (it.start ? ', ' + it.start : '');
  const lines = [];
  if (it.flight) lines.push(['Flight', [it.flight.airline, it.flight.number].filter(Boolean).join(' ') + (it.flight.from || it.flight.to ? ' · ' + [it.flight.from, it.flight.to].filter(Boolean).join(' → ') : '') + (it.flight.depart ? ' · departs ' + it.flight.depart : '') + (it.flight.checkIn ? ' · check in by ' + it.flight.checkIn : '')]);
  if (it.location && (it.location.name || it.location.address)) lines.push(['Where', [it.location.name, it.location.address].filter(Boolean).join(', ')]);
  if (it.lodging && it.lodging.name) lines.push(['Stay', it.lodging.name + (it.lodging.checkIn ? ' · check in ' + it.lodging.checkIn : '')]);
  if (it.notes) lines.push(['Notes', it.notes]);
  const subject = 'Reminder: ' + it.title + ' — ' + when;
  const hello = 'Hi ' + (firstName || 'there') + ', a reminder for ' + t.name + ':';
  const link = t.share ? t.share.url : '';
  const html = mailHtml({ site, tone: 'blue', icon: 'cal-blue', product: 'RAFIKI Outreach', eyebrow: t.name, title: it.title + ' — ' + when,
    bodyHtml: '<p style="margin:0 0 16px;font-size:16px;line-height:26px;color:#1F2A33">' + escHtml(hello) + '</p>',
    rows: lines.map(([label, v]) => ({ label, text: v, icon: 'cal-blue' })), cta: link ? { label: 'Open the trip page', href: link } : null,
    note: 'Questions? Reply to this e-mail or call your trip leader.', footer: 'You get this because you are on a RAFIKI Outreach&nbsp;trip.', preheader: subject });
  const txt = hello + '\n\n' + it.title + ' — ' + when + '\n' + lines.map(([l, v]) => l + ': ' + v).join('\n') + (link ? '\n\nTrip page: ' + link : '');
  return { subject, html, text: txt };
}
