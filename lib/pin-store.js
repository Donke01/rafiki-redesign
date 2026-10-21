// Shared storage for the RAFIKI pin app (Workers KV, binding FEED): the team list and admin roles,
// the review queue, repair jobs, visit assignments, confirmed locations, and e-mail alerts.
//
// People can sign in three ways: monday.com, a PIN set in the app by an admin (stored here, hashed),
// or a PIN in the Cloudflare secret PIN_USERS. Admins are listed in the Cloudflare secret PIN_ADMINS
// ("Reid, Jessica, Morgan"), or made admin in the app by another admin.
//
// E-mail (optional): RESEND_API_KEY, ALERT_FROM ("RAFIKI Pin <alerts@rafikioutreach.org>") and
// ALERT_EMAILS ("reid@…, jessica@…"). Admins added in the app with an e-mail address get alerts too.

export async function getJson(env, key, dflt) { if (!env.FEED) return dflt; return (await env.FEED.get(key, 'json')) || dflt; }
export async function putJson(env, key, val, opts) { if (env.FEED) await env.FEED.put(key, JSON.stringify(val), opts); }

const hex = buf => [...new Uint8Array(buf)].map(b => b.toString(16).padStart(2, '0')).join('');
async function salt(env) {
  let s = await env.FEED.get('pinsalt');
  if (!s) { s = hex(crypto.getRandomValues(new Uint8Array(16))); await env.FEED.put('pinsalt', s); }
  return s;
}
export async function hashPin(env, pin) { return hex(await crypto.subtle.digest('SHA-256', new TextEncoder().encode((await salt(env)) + ':' + String(pin).trim()))); }
export function newPin() { const a = crypto.getRandomValues(new Uint32Array(1))[0] % 9000; return String(1000 + a); }
// a 4-digit PIN nobody else on the team already has
export async function freshPin(env, t) {
  const taken = new Set(String(env.PIN_USERS || '').split(/[,;\n]/).map(x => x.split(':').map(y => y.trim())).filter(a => a.length >= 2).map(a => /^(ke|tz|ug|mw)$/i.test(a[a.length - 1]) && a.length > 2 ? a[a.length - 2] : a[a.length - 1]));
  if (env.PIN_CODE) taken.add(String(env.PIN_CODE).trim());
  for (let i = 0; i < 300; i++) {
    const pin = newPin(); if (taken.has(pin)) continue;
    const h = await hashPin(env, pin); if (t.some(p => p.h === h)) continue;
    return { pin, h };
  }
  throw new Error('no free PIN');
}

export const team = env => getJson(env, 'pinteam', []);
export const saveTeam = (env, t) => putJson(env, 'pinteam', t);
export function adminNames(env) { return String(env.PIN_ADMINS || '').split(/[,;\n]/).map(s => s.trim().toLowerCase()).filter(Boolean); }
export function isAdminName(env, t, name) {
  const n = String(name || '').trim().toLowerCase();
  if (!n) return false;
  if (adminNames(env).includes(n)) return true;
  return t.some(p => p.name.toLowerCase() === n && p.admin);
}
export function sameName(a, b) { return String(a || '').trim().toLowerCase() === String(b || '').trim().toLowerCase(); }

// last time each person used the app (written at most every 10 minutes per person)
export async function seen(env, name) {
  if (!env.FEED || !name) return;
  const m = await getJson(env, 'pinseen', {}), prev = Date.parse(m[name] || 0) || 0;
  if (Date.now() - prev < 600000) return;
  m[name] = new Date().toISOString(); await putJson(env, 'pinseen', m);
}

// review queue: new sites, big location changes, location change requests on confirmed points, story moments
export async function addReview(env, item) {
  if (!env.FEED) return;
  const cut = Date.now() - 90 * 864e5;
  const a = (await getJson(env, 'pinreview', [])).filter(x => x.status === 'open' || Date.parse(x.done || x.at) > cut);
  a.push(Object.assign({ rid: crypto.randomUUID().slice(0, 12), at: new Date().toISOString(), status: 'open' }, item));
  await putJson(env, 'pinreview', a.slice(-400));
}

// e-mail through Resend (https://resend.com); silently skipped until the keys are set
export async function recipients(env) {
  const t = await team(env);
  const list = String(env.ALERT_EMAILS || '').split(/[,;\s]+/).concat(t.filter(p => p.admin && p.email).map(p => p.email));
  return [...new Set(list.map(s => s.trim().toLowerCase()).filter(s => /^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(s)))];
}
export async function sendMail(env, subject, html, text, to) {
  if (!env.RESEND_API_KEY || !env.ALERT_FROM) return { ok: false, reason: 'no-mail' };
  const list = to || await recipients(env);
  if (!list.length) return { ok: false, reason: 'no-recipients' };
  const r = await fetch('https://api.resend.com/emails', {
    method: 'POST', headers: { Authorization: 'Bearer ' + env.RESEND_API_KEY, 'Content-Type': 'application/json' },
    body: JSON.stringify({ from: env.ALERT_FROM, to: list, subject, html, text })
  });
  if (!r.ok) { console.error('mail:', r.status, (await r.text()).slice(0, 200)); return { ok: false, reason: 'down' }; }
  return { ok: true, sent: list.length };
}
export const escHtml = s => String(s == null ? '' : s).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
// The RAFIKI letterhead for report e-mails (logo, US head office address, navy rule). `site` is the site origin the logo is served from.
// Admins are in several countries, so the e-mail uses the US address, as on the printed letterhead.
export function letterheadHtml(site) {
  return '<table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="border-bottom:2px solid #1A365D;margin:0 0 18px;font-family:Helvetica,Arial,sans-serif"><tr>' +
    '<td valign="bottom" style="padding:0 0 10px"><img src="' + escHtml(site) + '/img/letterhead/rafiki-logo.jpg" width="170" height="57" alt="RAFIKI Outreach" style="display:block;border:0"></td>' +
    '<td valign="bottom" align="right" style="padding:0 0 4px;font-size:11px;line-height:15px;color:#000000">Rafiki Outreach<br>1817 N. Beck Street<br>Salt Lake City, UT 84116, U.S.A.<br>Phone: +1 801 547 7847<br>Email: Info@rafikioutreach.org</td></tr></table>';
}
export function alertHtml(title, lines, link, site) {
  return '<div style="font-family:Arial,sans-serif;max-width:560px;color:#141618">' + (site ? letterheadHtml(site) : '') + '<h2 style="color:#04263F;margin:0 0 10px">' + escHtml(title) + '</h2>' +
    lines.map(l => '<p style="margin:0 0 8px;line-height:1.5">' + escHtml(l) + '</p>').join('') +
    (link ? '<p style="margin:16px 0"><a href="' + escHtml(link) + '" style="background:#0A78C2;color:#fff;padding:10px 18px;border-radius:999px;text-decoration:none;font-weight:bold">Open in RAFIKI Pin</a></p>' : '') +
    '<p style="color:#5A6066;font-size:12px;margin-top:18px">Sent by the RAFIKI Pin app to RAFIKI admins.</p>' +
    (site ? '<p style="border-top:1px solid #1A365D;padding-top:6px;color:#808080;font-size:11px;margin-top:18px">501(c)(3) Nonprofit Organization&nbsp; |&nbsp; Providing clean water and community development in East Africa</p>' : '') + '</div>';
}
