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
  if (!r.ok) {
    // Resend says why (domain not verified, testing-only sender…): pass its words on so the Office can show them
    const body = (await r.text()).slice(0, 400); console.error('mail:', r.status, body.slice(0, 200));
    let detail = ''; try { detail = String(JSON.parse(body).message || ''); } catch (e) {}
    return { ok: false, reason: 'down', status: r.status, detail: detail.slice(0, 240) };
  }
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
// ---- e-mail design ----------------------------------------------------------------------------------
// One look for every RAFIKI e-mail: logo header, a coloured icon and heading, the message as a card of
// rows with small icons, optional number tiles, a big button and a navy footer with the office address.
// E-mail apps don't run CSS files or show SVG, so it is tables with inline styles and PNG icons served
// from the site (/img/email). Tones: blue, red, amber, green, coral.
const TONE = { blue: ['#0A78C2', '#E3F0FA'], red: ['#C62828', '#FDE8E6'], amber: ['#B45F06', '#FFF1DC'], green: ['#18804F', '#E2F5EA'], coral: ['#D23C32', '#FDE9E6'] };
const FONT = "font-family:'Montserrat',Helvetica,Arial,sans-serif";
// o: { site, tone, icon (badge, e.g. 'alert-red'), eyebrow, title, intro, stats: [[number, label]], rows: [text or { text, icon }],
//      rowIcon (default small icon, e.g. 'drop-blue'), cta: { label, href }, note (small text under the button, HTML allowed),
//      footer (why they get it, HTML allowed), preheader }
export function mailHtml(o) {
  const site = String(o.site || 'https://rafiki-redesign.pages.dev').replace(/\/+$/, ''), img = n => site + '/img/email/' + n + '.png';
  const [fg, bg] = TONE[o.tone] || TONE.blue;
  const rows = (o.rows || []).filter(Boolean).map(r => typeof r === 'string' ? { text: r } : r);
  const stats = (o.stats || []).filter(x => x && x[0] !== undefined && x[0] !== null && x[0] !== '');
  const statCells = stats.map(([n, l]) => '<td width="' + Math.floor(100 / stats.length) + '%" valign="top" style="padding:0 4px"><table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background:#F4F8FB;border-radius:14px"><tr><td align="center" valign="top" height="76" style="height:76px;padding:14px 6px;' + FONT + '">' +
    '<div style="font-size:26px;line-height:30px;font-weight:800;color:#04263F">' + escHtml(n) + '</div><div style="font-size:12px;line-height:16px;color:#5A6066;margin-top:4px">' + escHtml(l) + '</div></td></tr></table></td>').join('');
  const rowHtml = rows.map((r, k) => '<tr><td width="34" valign="top" style="padding:' + (k ? '12px' : '2px') + ' 0 0"><img src="' + img((r.icon || o.rowIcon || 'drop-blue') + '-sm') + '" width="22" height="22" alt="" style="display:block;border:0"></td>' +
    '<td valign="top" style="padding:' + (k ? '12px' : '2px') + ' 0 0;' + FONT + ';font-size:15px;line-height:22px;color:#1F2A33">' + escHtml(r.text) + '</td></tr>').join('');
  const btn = o.cta && o.cta.href ? '<table role="presentation" cellpadding="0" cellspacing="0" style="margin:24px 0 4px"><tr><td align="center" bgcolor="' + fg + '" style="border-radius:999px;background:' + fg + '">' +
    '<a href="' + escHtml(o.cta.href) + '" style="display:inline-block;padding:14px 28px;' + FONT + ';font-size:15px;font-weight:700;color:#FFFFFF;text-decoration:none;border-radius:999px">' + escHtml(o.cta.label || 'Open in RAFIKI Pin') + ' &rarr;</a></td></tr></table>' : '';
  return '<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="color-scheme" content="light"><meta name="supported-color-schemes" content="light">' +
    '<title>' + escHtml(o.title) + '</title></head><body style="margin:0;padding:0;background:#EAF0F5">' +
    (o.preheader ? '<div style="display:none;max-height:0;overflow:hidden;opacity:0;color:#EAF0F5">' + escHtml(o.preheader) + '</div>' : '') +
    '<table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background:#EAF0F5"><tr><td align="center" style="padding:24px 12px">' +
    '<table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="max-width:600px">' +
    // header: logo on white, brand stripe
    '<tr><td style="background:#FFFFFF;border-radius:20px 20px 0 0;border-top:5px solid #0A78C2;padding:22px 28px 16px" align="left"><table role="presentation" width="100%" cellpadding="0" cellspacing="0"><tr>' +
    '<td valign="middle"><img src="' + site + '/img/letterhead/rafiki-logo.jpg" width="150" height="50" alt="RAFIKI Outreach" style="display:block;border:0"></td>' +
    '<td valign="middle" align="right" style="' + FONT + ';font-size:12px;font-weight:700;letter-spacing:.08em;color:#0A78C2;text-transform:uppercase">' + escHtml(o.brand || 'RAFIKI Pin') + '</td></tr></table></td></tr>' +
    // hero
    '<tr><td style="background:#FFFFFF;padding:8px 28px 4px"><table role="presentation" width="100%" cellpadding="0" cellspacing="0"><tr>' +
    '<td width="68" valign="top"><img src="' + img(o.icon || 'bell-blue') + '" width="56" height="56" alt="" style="display:block;border:0;border-radius:16px"></td>' +
    '<td valign="middle" style="' + FONT + '">' + (o.eyebrow ? '<div style="font-size:12px;line-height:16px;font-weight:800;letter-spacing:.08em;text-transform:uppercase;color:' + fg + '">' + escHtml(o.eyebrow) + '</div>' : '') +
    '<div style="font-size:22px;line-height:28px;font-weight:800;color:#04263F;margin-top:4px">' + escHtml(o.title) + '</div></td></tr></table>' +
    (o.intro ? '<p style="' + FONT + ';font-size:15px;line-height:23px;color:#3B4650;margin:16px 0 0">' + escHtml(o.intro) + '</p>' : '') + '</td></tr>' +
    // numbers
    (statCells ? '<tr><td style="background:#FFFFFF;padding:18px 24px 0"><table role="presentation" width="100%" cellpadding="0" cellspacing="0"><tr>' + statCells + '</tr></table></td></tr>' : '') +
    // the message as a card
    (rowHtml ? '<tr><td style="background:#FFFFFF;padding:18px 28px 0"><table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background:' + bg + ';border-radius:16px"><tr><td style="padding:16px 18px 18px">' +
      '<table role="presentation" width="100%" cellpadding="0" cellspacing="0">' + rowHtml + '</table></td></tr></table></td></tr>' : '') +
    // button
    '<tr><td style="background:#FFFFFF;padding:4px 28px 26px;border-radius:0 0 20px 20px">' + btn +
    (o.note ? '<p style="' + FONT + ';font-size:13px;line-height:19px;color:#5A6066;margin:14px 0 0">' + o.note + '</p>' : '') + '</td></tr>' +
    // footer
    '<tr><td style="padding:22px 28px 8px" align="center"><p style="' + FONT + ';font-size:12px;line-height:18px;color:#5A6066;margin:0">' + (o.footer || 'Sent by the RAFIKI Pin app to RAFIKI admins.') + '</p></td></tr>' +
    '<tr><td align="center" style="padding:10px 28px 0"><table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background:#04263F;border-radius:16px"><tr><td align="center" style="padding:18px 20px;' + FONT + ';font-size:12px;line-height:19px;color:#C9D6E2">' +
    '<b style="color:#FFFFFF;font-size:13px">RAFIKI Outreach</b><br>1817 N. Beck Street, Salt Lake City, UT 84116, U.S.A.<br>' +
    '<a href="tel:+18015477847" style="color:#C9D6E2;text-decoration:none">+1 801 547 7847</a> &nbsp;·&nbsp; <a href="mailto:info@rafikioutreach.org" style="color:#8FD3FF;text-decoration:none">info@rafikioutreach.org</a><br>' +
    '<span style="color:#8DA2B5;font-size:11px">501(c)(3) Nonprofit Organization · Providing clean water and community development in East Africa</span></td></tr></table></td></tr>' +
    '</table></td></tr></table></body></html>';
}
// The admin alerts (urgent visits, community reports, the test e-mail, the monthly summary). `site` defaults to the link's site.
export function alertHtml(title, lines, link, site, opts) {
  let origin = site; if (!origin && link) { try { origin = new URL(link).origin; } catch (e) {} }
  const o = Object.assign({ site: origin, title, rows: lines, cta: link ? { label: 'Open in RAFIKI Pin', href: link } : null, preheader: (lines || [])[0] || '' }, opts || {});
  return mailHtml(o);
}
