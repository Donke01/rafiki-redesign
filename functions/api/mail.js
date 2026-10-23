// /api/mail: Office › Send an e-mail (admins only; see lib/mail.js for who it goes to, the limits and the log).
//   GET  /api/mail?view=overview   → whether e-mail is set up, the limits, sent today / this month, donors and team members
//                                     who can be reached (names only, never addresses), and the last 50 sends
//   GET  /api/mail?img=<id>        → a photo uploaded for an e-mail (public, so mail apps can show it)
//   POST /api/mail { action: "preview", audience, picks, subject, body, button: {label, href}, photo: {kind, id}, includeLink }
//                    → { count, names, skipped, html } (the e-mail as the first person gets it, with sample links)
//                  { action: "test", … }   → the same e-mail, sent only to the admin's own address
//                  { action: "send", …, expect } → one e-mail per person (expect = the count the admin confirmed)
//                  { action: "photo", type, data (base64) } → { id } a photo for the e-mail (≤ 1.5 MB)
//                  audience "pick" (Choose people): people: [team keys], donors: [viewer keys]; each person gets their own kind of e-mail
//                  { action: "invite", to, preview } → the ready-made app invite to ONE team member (to = their name or key).
//                    preview: true → { subject, html, text } (text is the same invite for WhatsApp/SMS); otherwise it is sent.
//                    The invite never carries a PIN.
import { checkPin } from './pin.js';
import { team, sameName } from '../../lib/pin-store.js';
import { getFeed } from '../../lib/feed-cache.js';
import { CONTACT } from '../../lib/donor.js';
import { resolve, teamPeople, donorPeople, cleanDraft, renderMail, donorLinks, sendAll, readLog, addLog, usage, lastLog, overCap, caps, mailReady, audienceLabel, inviteDraft, inviteText } from '../../lib/mail.js';
import { donors } from '../../lib/donor.js';

const LIMIT = { send: 6, test: 12, photo: 20, invite: 20 };   // per admin per hour
const MAX_IMG = 1536 * 1024;
const HEAD = { 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff', 'X-Robots-Tag': 'noindex' };
function json(body, status) { return new Response(JSON.stringify(body), { status: status || 200, headers: Object.assign({ 'Content-Type': 'application/json; charset=utf-8' }, HEAD) }); }

async function admin(request, env) {
  const chk = await checkPin(request, env);
  if (chk.res) return chk;
  if (!chk.admin) return { res: json({ ok: false, reason: 'not-admin' }, 403) };
  if (!env.FEED) return { res: json({ ok: false, reason: 'no-storage' }, 503) };
  return chk;
}
// at most n of this kind per admin per hour
async function limited(env, kind, who, n) {
  const k = 'rl:mail:' + kind + ':' + String(who || 'admin').toLowerCase().replace(/[^a-z0-9]+/g, '-').slice(0, 40) + ':' + Math.floor(Date.now() / 3600e3);
  const c = Number(await env.FEED.get(k)) || 0;
  if (c >= n) return true;
  await env.FEED.put(k, String(c + 1), { expirationTtl: 3700 });
  return false;
}
// the sending admin's own address (from the team list), for Reply-To and the test e-mail
async function ownEmail(env, who) {
  const p = (await team(env)).find(x => sameName(x.name, who));
  return p && /^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(String(p.email || '')) ? String(p.email).trim().toLowerCase() : '';
}
const site = request => new URL(request.url).origin;

export async function onRequestGet({ request, env }) {
  const q = new URL(request.url).searchParams;
  if (q.get('img')) return image(env, q.get('img'));
  const chk = await admin(request, env);
  if (chk.res) return chk.res;
  if (q.get('view') !== 'overview') return json({ ok: false, reason: 'bad-request' }, 400);
  const dl = await donors(env), dp = donorPeople(dl), people = await teamPeople(env);
  const dAll = await resolve(env, 'donors:all'), tAll = await resolve(env, 'team'), aAll = await resolve(env, 'admins');
  const log = await readLog(env);
  return json({
    ok: true, ready: mailReady(env), caps: caps(env), use: usage(log), me: { email: !!(await ownEmail(env, chk.who)) },
    donors: {
      reachable: dAll.list.length, unsub: dAll.skipped.unsub, revoked: dAll.skipped.revoked, noEmail: dAll.skipped['no-email'],
      // names and whether each can be e-mailed; never the addresses
      list: dl.map(d => ({ id: d.id, name: d.name, org: d.org || '', projects: d.projects || [], viewers: dp.filter(p => p.donor === d).map(p => ({ key: p.key, name: p.name, why: p.why })) }))
    },
    team: { reachable: tAll.list.length, admins: aAll.list.length, noEmail: tAll.skipped['no-email'], people: people.map(p => ({ key: p.key, name: p.name, role: p.role, email: !!p.email })) },
    log: lastLog(log)
  });
}

async function image(env, id) {
  const bad = new Response('Not found', { status: 404, headers: HEAD });
  if (!/^[A-Za-z0-9]{16,40}$/.test(id) || !env.FEED) return bad;
  const v = await env.FEED.get('mailimg:' + id, 'json');
  if (!v || !v.d) return bad;
  const bin = atob(v.d), bytes = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
  return new Response(bytes, { headers: { 'Content-Type': v.t, 'Cache-Control': 'public, max-age=31536000, immutable', 'X-Content-Type-Options': 'nosniff' } });
}
function sniff(b) {
  if (b[0] === 0xFF && b[1] === 0xD8 && b[2] === 0xFF) return 'image/jpeg';
  if (b[0] === 0x89 && b[1] === 0x50 && b[2] === 0x4E && b[3] === 0x47) return 'image/png';
  if (b[0] === 0x52 && b[1] === 0x49 && b[2] === 0x46 && b[3] === 0x46 && b[8] === 0x57 && b[9] === 0x45 && b[10] === 0x42 && b[11] === 0x50) return 'image/webp';
  return '';
}

export async function onRequestPost({ request, env }) {
  const url = new URL(request.url), origin = request.headers.get('Origin');
  if (origin && new URL(origin).host !== url.host) return json({ ok: false, reason: 'origin' }, 403);
  const chk = await admin(request, env);
  if (chk.res) return chk.res;
  let b;
  try { b = await request.json(); } catch (e) { return json({ ok: false, reason: 'bad-request' }, 400); }
  const by = chk.who || 'An admin';
  try {
    if (b.action === 'photo') {
      const data = String(b.data || '').replace(/^data:[^,]*,/, '');
      if (!data || data.length > MAX_IMG * 1.37 + 8) return json({ ok: false, reason: 'too-large' }, 413);
      let bin; try { bin = atob(data); } catch (e) { return json({ ok: false, reason: 'bad-request' }, 400); }
      const head = new Uint8Array(12); for (let i = 0; i < 12 && i < bin.length; i++) head[i] = bin.charCodeAt(i);
      const type = sniff(head);
      if (!type) return json({ ok: false, reason: 'type' }, 415);
      if (bin.length > MAX_IMG) return json({ ok: false, reason: 'too-large' }, 413);
      if (await limited(env, 'photo', by, LIMIT.photo)) return json({ ok: false, reason: 'rate' }, 429);
      const id = [...crypto.getRandomValues(new Uint8Array(12))].map(x => 'abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789'[x % 56]).join('') + Date.now().toString(36);
      await env.FEED.put('mailimg:' + id, JSON.stringify({ t: type, d: btoa(bin), by, at: new Date().toISOString() }), { expirationTtl: 60 * 60 * 24 * 400 });
      return json({ ok: true, id, url: site(request) + '/api/mail?img=' + id });
    }
    if (b.action === 'invite') return await invite(request, env, chk, b);
    if (!['preview', 'test', 'send'].includes(b.action)) return json({ ok: false, reason: 'bad-request' }, 400);
    const draft = cleanDraft(b), aud = String(b.audience || '');
    const r = await resolve(env, aud, aud === 'pick' ? { people: b.people, donors: b.donors } : b.picks);
    if (!r) return json({ ok: false, reason: 'bad-request' }, 400);
    if (r.kind !== 'donor') draft.includeLink = false;   // the private link only goes in an e-mail to donors alone
    const skipped = { unsub: r.skipped.unsub, revoked: r.skipped.revoked, noEmail: r.skipped['no-email'], dup: r.skipped.dup };
    const S = site(request), mine = await ownEmail(env, by);
    // the e-mail as one person gets it; previews and tests use sample links, so nobody's private link or unsubscribe goes astray
    const made = async (p, sample) => { const k = p.kind || r.kind; return renderMail(Object.assign({ site: S, kind: k, person: p, draft, by: chk.who || '' }, k === 'donor' && (sample || p.donor) ? await donorLinks(env, S, p, draft.includeLink, sample) : {})); };
    const sampleP = r.list[0] || { name: chk.who || 'Friend' };
    if (b.action === 'preview') {
      const m = await made(sampleP, true);
      return json({ ok: true, kind: r.kind, count: r.list.length, names: r.list.slice(0, 40).map(p => p.name), skipped, sample: sampleP.name, subject: m.subject, html: m.html, caps: caps(env), use: usage(await readLog(env)) });
    }
    if (!draft.subject || !draft.body) return json({ ok: false, reason: 'empty' }, 400);
    if (!mailReady(env)) return json({ ok: false, reason: 'no-mail' }, 503);
    if (b.action === 'test') {
      if (!mine) return json({ ok: false, reason: 'no-own-email' }, 409);
      if (await limited(env, 'test', by, LIMIT.test)) return json({ ok: false, reason: 'rate' }, 429);
      const m = await made(sampleP, true);
      const res = await sendAll(env, [{ email: mine, name: by }], async () => Object.assign({}, m, { subject: '[Test] ' + m.subject }), { replyTo: mine });
      return res[0].ok ? json({ ok: true, to: mine }) : json({ ok: false, reason: 'down', detail: res[0].error }, 502);
    }
    // send
    if (!r.list.length) return json({ ok: false, reason: 'nobody', skipped }, 409);
    if (Number(b.expect) !== r.list.length) return json({ ok: false, reason: 'changed', count: r.list.length }, 409);
    const log = await readLog(env), use = usage(log), over = overCap(env, r.list.length, use);
    if (over) return json(Object.assign({ ok: false, count: r.list.length }, over), 409);
    if (await limited(env, 'send', by, LIMIT.send)) return json({ ok: false, reason: 'rate' }, 429);
    const res = await sendAll(env, r.list, p => made(p, false), { replyTo: mine || CONTACT });
    const sent = res.filter(x => x.ok).length, failed = res.length - sent, skip = skipped.unsub + skipped.revoked + skipped.noEmail + skipped.dup;
    let label = '';
    if (aud.indexOf('donors:project:') === 0) { try { const f = await getFeed(env); const pr = (f.projects || []).find(x => String(x.id) === aud.slice(15)); label = pr ? pr.title : ''; } catch (e) {} }
    const entry = { id: crypto.randomUUID().slice(0, 12), at: new Date().toISOString(), by, audience: aud, label: audienceLabel(aud, label), kind: r.kind, subject: draft.subject.slice(0, 150), link: draft.includeLink,
      n: r.list.length, sent, failed, skipped: skip, skippedBy: skipped,
      rcpt: res.map(x => ({ name: x.p.name, s: x.ok ? 'sent' : 'failed', e: x.ok ? undefined : String(x.error || '').slice(0, 120) })) };
    await addLog(env, entry);
    if (!sent) return json({ ok: false, reason: 'down', detail: (res[0] && res[0].error) || '', sent, failed, skipped: skip, id: entry.id }, 502);
    return json({ ok: true, sent, failed, skipped: skip, id: entry.id });
  } catch (e) {
    console.error('mail ' + b.action + ':', e && e.message);
    return json({ ok: false, reason: 'down' }, 502);
  }
}

// one team member, found by key ("t:<name>") or by name
async function invite(request, env, chk, b) {
  const to = String(b.to || '').trim().slice(0, 80), by = chk.who || 'An admin';
  if (!to) return json({ ok: false, reason: 'bad-request' }, 400);
  const p = (await teamPeople(env)).find(x => x.key === to || sameName(x.name, to));
  if (!p) return json({ ok: false, reason: 'not-found' }, 404);
  const m = renderMail({ site: site(request), kind: 'team', person: p, draft: inviteDraft(p, chk.who), by: chk.who || '' });
  if (b.preview) return json({ ok: true, to: p.name, email: !!p.email, subject: m.subject, html: m.html, text: inviteText(p, chk.who) });
  if (!p.email) return json({ ok: false, reason: 'no-email' }, 409);
  if (!mailReady(env)) return json({ ok: false, reason: 'no-mail' }, 503);
  const log = await readLog(env), over = overCap(env, 1, usage(log));
  if (over) return json(Object.assign({ ok: false, count: 1 }, over), 409);
  if (await limited(env, 'invite', by, LIMIT.invite)) return json({ ok: false, reason: 'rate' }, 429);
  const mine = await ownEmail(env, by);
  const res = await sendAll(env, [p], async () => m, { replyTo: mine || CONTACT });
  const ok = res[0].ok;
  await addLog(env, { id: crypto.randomUUID().slice(0, 12), at: new Date().toISOString(), by, audience: 'invite', label: 'App invite to ' + p.name, kind: 'team', subject: m.subject, link: false,
    n: 1, sent: ok ? 1 : 0, failed: ok ? 0 : 1, skipped: 0, skippedBy: { unsub: 0, revoked: 0, noEmail: 0, dup: 0 }, rcpt: [{ name: p.name, s: ok ? 'sent' : 'failed', e: ok ? undefined : String(res[0].error || '').slice(0, 120) }] });
  return ok ? json({ ok: true, sent: 1, to: p.name }) : json({ ok: false, reason: 'down', detail: res[0].error }, 502);
}

export function onRequest() { return json({ ok: false, reason: 'method' }, 405); }
