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
// One look for every RAFIKI e-mail, from the Figma file "RAFIKI Pin – E-mails": a header with the logo and a
// coloured stripe, a coloured icon, eyebrow and heading, optional number tiles, the details as labelled rows
// in a bordered box, a full-width button, and a navy footer with the office address.
// E-mail apps don't run CSS files or show SVG, so it is tables with inline styles and PNG icons served from
// the site (/img/email). Tones: blue, red, amber, green, coral.
const TONE = { blue: ['#0A78C2', '#E3F0FA'], red: ['#C62828', '#FDE8E6'], amber: ['#B45F06', '#FFF1DC'], green: ['#18804F', '#E2F5EA'], coral: ['#D23C32', '#FDE9E6'] };
const FONT = "font-family:Montserrat,'Segoe UI',Helvetica,Arial,sans-serif";
// "Problems found: pump handle" → label "Problems found", value "Pump handle" (a short prefix only; otherwise no label)
function splitRow(r) {
  if (typeof r !== 'string') return r.label ? r : Object.assign({}, r, splitRow(String(r.text || '')));
  const t = r.replace(/^[^\w“"(+]+/u, '').replace(/ \(RAFIKI pin app\)/i, '').trim();
  const m = /^([^:]{2,40}?):\s+(.+)$/s.exec(t);
  if (!m || /^https?$/i.test(m[1])) return { text: t };
  const v = m[2].trim();
  return { label: m[1].trim(), text: v.charAt(0).toUpperCase() + v.slice(1) };
}
// o: { site, tone, icon (badge, e.g. 'alert-red'), product (header pill), eyebrow, title, intro,
//      stats: [[number, label]], rows: [text or { label, text, icon }], rowIcon (default small icon, e.g. 'drop-blue'),
//      cta: { label, href }, note (small text under the button, HTML allowed), footer (why they get it, HTML allowed), preheader }
export function mailHtml(o) {
  const site = String(o.site || 'https://rafiki-redesign.pages.dev').replace(/\/+$/, ''), img = n => site + '/img/email/' + n + '.png';
  const [fg, bg] = TONE[o.tone] || TONE.blue;
  const rows = (o.rows || []).filter(Boolean).map(splitRow);
  const stats = (o.stats || []).filter(x => x && x[0] !== undefined && x[0] !== null && x[0] !== '');
  const statCells = stats.map(([n, l], k) => (k ? '<td width="8" style="width:8px;font-size:0">&nbsp;</td>' : '') +
    '<td width="' + Math.floor(100 / stats.length) + '%" valign="top" align="center" bgcolor="#F3F6F9" style="background:#F3F6F9;border-radius:14px;padding:16px 6px;' + FONT + '">' +
    '<div style="font-size:26px;line-height:30px;font-weight:800;color:#04263F">' + escHtml(n) + '</div><div style="font-size:12px;line-height:16px;font-weight:500;color:#5A6066;margin-top:4px">' + escHtml(l) + '</div></td>').join('');
  const rowHtml = rows.map((r, k) => (k ? '<tr><td colspan="2" style="padding:0 20px"><div style="height:1px;line-height:1px;font-size:0;background:#EEF2F5">&nbsp;</div></td></tr>' : '') +
    '<tr><td width="34" valign="top" style="width:34px;padding:16px 0 16px 20px"><img src="' + img((r.icon || o.rowIcon || 'drop-blue') + '-sm') + '" width="20" height="20" alt="" style="display:block;border:0;margin-top:' + (r.label ? '0' : '1') + 'px"></td>' +
    '<td valign="top" style="padding:16px 20px 16px 0;' + FONT + '">' +
    (r.label ? '<div style="font-size:11px;line-height:14px;font-weight:700;letter-spacing:.06em;text-transform:uppercase;color:#6B7680;margin:0 0 3px">' + escHtml(r.label) + '</div>' : '') +
    '<div style="font-size:15px;line-height:22px;color:#1F2A33">' + escHtml(r.text) + '</div></td></tr>').join('');
  const btn = o.cta && o.cta.href ? '<table role="presentation" width="100%" cellpadding="0" cellspacing="0"><tr><td align="center" bgcolor="' + fg + '" style="border-radius:999px;background:' + fg + '">' +
    '<a href="' + escHtml(o.cta.href) + '" style="display:block;padding:16px 24px;' + FONT + ';font-size:16px;line-height:20px;font-weight:700;color:#FFFFFF;text-decoration:none;border-radius:999px">' + escHtml(o.cta.label || 'Open in RAFIKI Pin') + '&nbsp;&nbsp;&rarr;</a></td></tr></table>' : '';
  const W = '<table role="presentation" width="100%" cellpadding="0" cellspacing="0">';
  return '<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="color-scheme" content="light"><meta name="supported-color-schemes" content="light">' +
    '<title>' + escHtml(o.title) + '</title><link href="https://fonts.googleapis.com/css2?family=Montserrat:wght@400;500;600;700;800&display=swap" rel="stylesheet"><style>@media (max-width:480px){.px{padding-left:20px!important;padding-right:20px!important}.outer{padding:16px 8px!important}.h1{font-size:23px!important;line-height:29px!important}}</style></head>' +
    '<body style="margin:0;padding:0;background:#EEF2F6;-webkit-text-size-adjust:100%">' +
    (o.preheader ? '<div style="display:none;max-height:0;overflow:hidden;opacity:0;color:#EEF2F6">' + escHtml(o.preheader) + '</div>' : '') +
    '<table role="presentation" width="100%" cellpadding="0" cellspacing="0" bgcolor="#EEF2F6" style="background:#EEF2F6"><tr><td align="center" class="outer" style="padding:32px 16px">' +
    '<table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="max-width:600px;width:100%">' +
    // card: stripe, logo bar
    '<tr><td bgcolor="#FFFFFF" style="background:#FFFFFF;border-radius:20px">' + W +
    '<tr><td height="6" bgcolor="' + fg + '" style="height:6px;line-height:6px;font-size:0;background:' + fg + ';border-radius:20px 20px 0 0">&nbsp;</td></tr>' +
    '<tr><td class="px" style="padding:22px 32px;border-bottom:1px solid #E8EDF2">' + W + '<tr>' +
    '<td valign="middle" align="left"><img src="' + site + '/img/logo-dark-bold.png" width="108" height="37" alt="RAFIKI Outreach" style="display:block;border:0;width:108px;height:37px"></td>' +
    '<td valign="middle" align="right"><span style="display:inline-block;background:' + bg + ';color:' + fg + ';border-radius:999px;padding:6px 12px;' + FONT + ';font-size:11px;line-height:14px;font-weight:700;letter-spacing:.08em;text-transform:uppercase">' + escHtml(o.product || 'RAFIKI Pin') + '</span></td>' +
    '</tr></table></td></tr>' +
    // icon, eyebrow, heading, intro
    '<tr><td class="px" style="padding:32px 32px 4px">' +
    '<img src="' + img(o.icon || 'bell-blue') + '" width="52" height="52" alt="" style="display:block;border:0;width:52px;height:52px">' +
    (o.eyebrow ? '<div style="' + FONT + ';font-size:12px;line-height:16px;font-weight:800;letter-spacing:.08em;text-transform:uppercase;color:' + fg + ';margin-top:14px">' + escHtml(o.eyebrow) + '</div>' : '') +
    '<div class="h1" style="' + FONT + ';font-size:26px;line-height:32px;font-weight:700;color:#04263F;margin-top:6px">' + escHtml(o.title) + '</div>' +
    (o.intro ? '<div style="' + FONT + ';font-size:16px;line-height:26px;color:#3B4650;margin-top:14px">' + escHtml(o.intro) + '</div>' : '') + '</td></tr>' +
    // numbers
    (statCells ? '<tr><td class="px" style="padding:20px 32px 0">' + W + '<tr>' + statCells + '</tr></table></td></tr>' : '') +
    // details
    (rowHtml ? '<tr><td class="px" style="padding:24px 32px 0"><table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="border:1px solid #E3E9EE;border-radius:16px;border-collapse:separate">' + rowHtml + '</table></td></tr>' : '') +
    // button and note
    '<tr><td class="px" style="padding:28px 32px 32px">' + btn +
    (o.note ? '<div style="' + FONT + ';font-size:13px;line-height:20px;color:#5A6066;text-align:center;margin-top:14px">' + o.note + '</div>' : '') + '</td></tr>' +
    '</table></td></tr>' +
    // why, then the footer
    '<tr><td align="center" style="padding:20px 16px 12px;' + FONT + ';font-size:12px;line-height:18px;font-weight:500;color:#6B7680">' + (o.footer || 'You get this because you are a RAFIKI Pin admin.') + '</td></tr>' +
    '<tr><td align="center" bgcolor="#04263F" style="background:#04263F;border-radius:20px;padding:28px 32px">' +
    '<img src="' + site + '/img/logo-light-bold.png" width="88" height="30" alt="RAFIKI Outreach" style="display:block;border:0;width:88px;height:30px;margin:0 auto">' +
    '<div style="' + FONT + ';font-size:13px;line-height:20px;color:#C9D6E2;margin-top:10px">1817 N. Beck Street, Salt Lake City, UT 84116, U.S.A.</div>' +
    '<div style="' + FONT + ';font-size:13px;line-height:20px;font-weight:600;color:#8FD3FF;margin-top:10px"><a href="tel:+18015477847" style="color:#8FD3FF;text-decoration:none">+1 801 547 7847</a>&nbsp;&nbsp;·&nbsp;&nbsp;<a href="mailto:info@rafikioutreach.org" style="color:#8FD3FF;text-decoration:none">info@rafikioutreach.org</a></div>' +
    '<div style="width:80px;height:1px;line-height:1px;font-size:0;background:#1E4466;margin:10px auto 0">&nbsp;</div>' +
    '<div style="' + FONT + ';font-size:11px;line-height:17px;font-weight:500;color:#8DA2B5;margin-top:10px">501(c)(3) Nonprofit Organization · Clean water and community development in East Africa</div></td></tr>' +
    '</table></td></tr></table></body></html>';
}
// The admin alerts (urgent visits, community reports, the test e-mail, the monthly summary). `site` defaults to the link's site.
export function alertHtml(title, lines, link, site, opts) {
  let origin = site; if (!origin && link) { try { origin = new URL(link).origin; } catch (e) {} }
  const o = Object.assign({ site: origin, title, rows: lines, cta: link ? { label: 'Open in RAFIKI Pin', href: link } : null, preheader: (lines || [])[0] || '' }, opts || {});
  return mailHtml(o);
}
