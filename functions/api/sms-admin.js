// /api/sms-admin: the Office's "Community reports" section, for admins only.
//   GET  /api/sms-admin → { setup (which Cloudflare variables are set: names only), voice (voice notes
//                          typed out: on/off), urls (webhooks to paste), reports (latest SMS/WhatsApp
//                          messages, newest first), phones (committee phone → project list) }
//   POST /api/sms-admin { action: "phone-add", phone, id, name, cc }  register a committee phone for a project
//                       { action: "phone-remove", phone }
import { checkPin } from './pin.js';
import { readGps, tidyTitle, BOARD_ID } from '../../lib/monday.js';
import { phones, savePhones, reports, normPhone, smsSetup } from '../../lib/sms.js';
import { transcribeSetup } from '../../lib/transcribe.js';

function json(body, status) {
  return new Response(JSON.stringify(body), { status: status || 200, headers: { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff', 'X-Robots-Tag': 'noindex' } });
}
const clean = (s, n) => String(s || '').replace(/[<>]/g, '').replace(/\s+/g, ' ').trim().slice(0, n);

async function admin(request, env) {
  const chk = await checkPin(request, env);
  if (chk.res) return chk;
  if (!chk.admin) return { res: json({ ok: false, reason: 'not-admin' }, 403) };
  if (!env.FEED) return { res: json({ ok: false, reason: 'no-storage' }, 503) };
  return chk;
}

export async function onRequestGet({ request, env }) {
  const chk = await admin(request, env);
  if (chk.res) return chk.res;
  const origin = new URL(request.url).origin, v = transcribeSetup(env);
  const typed = ((await env.FEED.get('pinvoicetx', 'json')) || []);
  const month = new Date().toISOString().slice(0, 7);
  return json({
    ok: true,
    setup: smsSetup(env),
    voice: { on: v.on, provider: v.provider, model: v.model || '', missing: v.missing, note: v.note, thisMonth: typed.filter(x => String(x.at).slice(0, 7) === month && x.how !== 'failed').length },
    // the token itself is never sent back: the admin who set it pastes it in
    urls: { at: origin + '/api/sms?token=YOUR_SMS_WEBHOOK_TOKEN', twilio: origin + '/api/sms' },
    reports: (await reports(env)).slice().reverse().slice(0, 150),
    phones: (await phones(env)).slice().sort((a, b) => String(a.t).localeCompare(String(b.t)))
  });
}

export async function onRequestPost({ request, env }) {
  const origin = request.headers.get('Origin');
  if (origin && new URL(origin).host !== new URL(request.url).host) return json({ ok: false, reason: 'origin' }, 403);
  const chk = await admin(request, env);
  if (chk.res) return chk.res;
  let b;
  try { b = await request.json(); } catch (e) { return json({ ok: false, reason: 'bad-request' }, 400); }
  const by = chk.who || 'an admin';
  const phone = normPhone(b && b.phone, b && b.cc);
  if (!phone) return json({ ok: false, reason: 'phone' }, 400);
  const list = await phones(env);
  if (b.action === 'phone-remove') {
    await savePhones(env, list.filter(x => x.phone !== phone));
    return json({ ok: true, phones: list.filter(x => x.phone !== phone) });
  }
  if (b.action === 'phone-add') {
    const id = String(b.id || '').replace(/\D/g, '');
    if (!id) return json({ ok: false, reason: 'bad-request' }, 400);
    let cur;
    try { cur = await readGps(chk.token || env.MONDAY_API_TOKEN, id, env.MONDAY_API_URL); } catch (e) { return json({ ok: false, reason: 'down' }, 502); }
    if (!cur || (cur.board && cur.board !== String(env.MONDAY_BOARD_ID || BOARD_ID))) return json({ ok: false, reason: 'project' }, 404);
    const row = { phone, id, t: tidyTitle(cur.name).slice(0, 120), name: clean(b.name, 60), by, at: new Date().toISOString() };
    const next = list.filter(x => x.phone !== phone).concat([row]);
    if (next.length > 500) return json({ ok: false, reason: 'full' }, 409);
    await savePhones(env, next);
    return json({ ok: true, phone: row });
  }
  return json({ ok: false, reason: 'bad-request' }, 400);
}

export function onRequest() { return json({ ok: false, reason: 'method' }, 405); }
