// /api/admin: the Office tools in the pin app, for admins only (PIN_ADMINS, or made admin in the app).
//   GET  /api/admin?view=review   → the review queue (new sites, big location changes, change requests, story moments)
//   GET  /api/admin?view=team     → everyone who can sign in, their role, home country and last use
//   GET  /api/admin?view=assign   → visits assigned to field officers
//   GET  /api/admin?view=export   → every visit record kept by the app (for the spreadsheet export)
//   GET  /api/admin?view=costs    → repair spending (last 25 months) with this month / year / country / top 10, and the approval limit
//   GET  /api/admin?view=teammap  → teammates sharing their location (latest point only) and today's visits
//   GET  /api/admin?view=plan&ck=&days=&per=[&areas=Kapsaret,Kaigat][&ids=104,105][&only=1] → suggested visits for a week,
//        grouped into days. areas: the areas ticked (≤20, each ≤80 characters; none = the whole country); ids: projects
//        chosen by hand (digits, ≤60, in the feed), always planned; only=1: no other suggestions (lib/pin-here.js planView)
//   POST /api/admin { action: "review",  rid, decision: "approve"|"reject", reason }
//                   { action: "job",     id, step, assignee, due, budget, note, actual, what, receipt }  repair jobs
//                       (above the approval limit, "parts"/"fixed" wait for a second admin: answers { held, asked })
//                   { action: "settings", approveAbove }                            second approval above KES N
//                   { action: "assign",  ids: [], to, date, note } / { action: "unassign", aid }
//                   { action: "confirm", id, on }                                   lock a confirmed location
//                   { action: "team-add", name, home, admin, email } → { pin } (shown once)
//                   { action: "team-update", name, home, admin, email, phone, role } (fields left out stay; "" clears e-mail, phone, role)
//                   { action: "team-pin", name } → { pin } (a new one, shown once) / { action: "team-remove", name }
//                   { action: "team-rename", name, to }      old names are kept in "aka", so history still counts for them;
//                       open repair jobs and planned visits move to the new name
//                   { action: "team-set-pin", name, pin }    4–8 digits, not easy to guess ("weak-pin"), not anyone else's ("pin-taken")
//                   { action: "team-block", name, reason } / { action: "team-unblock", name }  blocked: PIN refused, every phone signed out
//                   { action: "team-signout", name }         ends their sessions on every phone; the PIN stays
//                   { action: "team-move", name } → { pin }  a Cloudflare PIN person gets an app PIN (their Cloudflare entry can then go)
//                   Team actions are for admins, except team-rename and team-update (name, e-mail, phone) on yourself.
//                   Refusals: exists, weak-pin, pin-taken, self, last-admin, not-in-app, not-cloudflare, bad-email
//                   { action: "test-mail" }
//   GET  /api/admin?view=donors   → donors with their projects and viewers (Office › Donors; see lib/donor.js)
//   POST /api/admin { action: "donor-add" | "donor-update" | "donor-link" | "donor-rotate" | "donor-revoke" | "donor-viewer-add" | "donor-viewer-remove" | "donor-remove"
//                    | "donor-viewer-update" (id, vid, name, email) | "donor-viewer-restore" (id, vid: the same link works again), id, vid, … }
//   POST /api/admin?cron=monthly with header X-Cron-Key = CRON_KEY → e-mails last month's summary to admins
//        (run on the 1st of each month by .github/workflows/monthly-report.yml), and the monthly e-mail to donors
import { checkPin } from './pin.js';
import { getFeed, dropFeed } from '../../lib/feed-cache.js';
import { parseGps, readGps, writeGps, createUpdate, BOARD_ID } from '../../lib/monday.js';
import { getJson, putJson, team, saveTeam, hashPin, newPin, freshPin, adminNames, sameName, sendMail, alertHtml, escHtml, recipients, addReview, guards, saveGuards, guardKey, guardFor, weakPin, pinInUse } from '../../lib/pin-store.js';
import { moveLink } from '../../lib/pin-auth.js';
import { kes, money, estOf, stepBlocked, canDecide, approveLimit, setApproveLimit, askApproval, costRow, addCost, costStats, snapPeople } from '../../lib/pin-office.js';
import { teamMap, planView, hereKey } from '../../lib/pin-here.js';
import { photoIndex, photoOf, movePhoto, dropPhoto } from '../../lib/team-photo.js';
import { donorAdminGet, donorAdminPost, runDigest } from '../../lib/donor.js';
import { chatSys, chatSync } from '../../lib/chat.js';
import { tripsMonth } from '../../lib/itin.js';

const STEPS = { reported: 'Reported', assigned: 'Assigned', parts: 'Parts ordered', fixed: 'Fixed' };
const CC = ['ke', 'tz', 'ug', 'mw'];

function json(body, status) {
  return new Response(JSON.stringify(body), { status: status || 200, headers: { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff', 'X-Robots-Tag': 'noindex' } });
}
const clean = (s, n) => String(s || '').replace(/[<>]/g, '').replace(/\s+/g, ' ').trim().slice(0, n);
const said = s => s ? (/[.!?]$/.test(s) ? s : s + '.') : '';
const day = s => /^\d{4}-\d{2}-\d{2}$/.test(String(s || '')) ? String(s) : '';

async function admin(request, env) {
  const chk = await checkPin(request, env);
  if (chk.res) return chk;
  if (!chk.admin) return { res: json({ ok: false, reason: 'not-admin' }, 403) };
  if (!env.FEED) return { res: json({ ok: false, reason: 'no-storage' }, 503) };
  return chk;
}
async function log(env, entry) {
  const cut = Date.now() - 400 * 864e5;
  const a = (await getJson(env, 'pinact', [])).filter(e => Date.parse(e.at) > cut);
  a.push(Object.assign({ at: new Date().toISOString() }, entry));
  await putJson(env, 'pinact', a.slice(-5000));
}
// a phone number as typed (+254 712 345 678, 0712…): digits, spaces and one leading +
function phone(v) { const s = String(v || '').replace(/[^\d+ ]/g, '').replace(/(?!^)\+/g, '').trim().slice(0, 20); return /\d{7,}/.test(s.replace(/\s/g, '')) ? s : ''; }
function people(env) {
  return String(env.PIN_USERS || '').split(/[,;\n]/).map(s => { const a = s.split(':').map(x => x.trim()); let home = ''; if (a.length > 2 && /^(ke|tz|ug|mw)$/i.test(a[a.length - 1])) home = a.pop().toLowerCase(); return a.length >= 2 ? { name: a.slice(0, -1).join(':'), home } : null; }).filter(Boolean);
}

export async function onRequestGet({ request, env }) {
  const chk = await admin(request, env);
  if (chk.res) return chk.res;
  const view = new URL(request.url).searchParams.get('view');
  if (view === 'review') return json({ ok: true, items: (await getJson(env, 'pinreview', [])).slice().reverse().map(x => (x.t ? Object.assign({}, x, { t: String(x.t).replace(/^[\s*#_-]+|[\s*#_]+$/g, '') }) : x)) });
  if (view === 'donors') return json(await donorAdminGet(env));
  if (view === 'assign') return json({ ok: true, items: await getJson(env, 'pinassign', []) });
  if (view === 'costs') {
    let feed = null; try { feed = await getFeed(env); } catch (e) {}
    const rows = await getJson(env, 'pincostidx', []);
    return json({ ok: true, rows, stats: costStats(rows, feed ? feed.projects : []), approveAbove: await approveLimit(env) });
  }
  if (view === 'teammap') { const tm = await teamMap(env), ph = await photoIndex(env); (tm.people || []).forEach(p => { p.photo = photoOf(ph, p.name); }); return json(Object.assign({ ok: true }, tm)); }
  if (view === 'plan') { let feed = null; try { feed = await getFeed(env); } catch (e) {} const r = await planView(env, new URL(request.url).searchParams, feed); return json(r.body, r.status); }
  if (view === 'team') {
    const t = await team(env), seenAt = await getJson(env, 'pinseen', {}), admins = adminNames(env), gd = await guards(env), cf = people(env);
    const list = t.map(p => ({ name: p.name, home: p.home || '', admin: !!p.admin || admins.includes(p.name.toLowerCase()), adminCf: admins.includes(p.name.toLowerCase()), email: p.email || '', phone: p.phone || '', role: p.role || '', aka: p.aka || [], source: 'app', added: p.added, lastSeen: seenAt[p.name] || null, alsoCloudflare: cf.some(x => sameName(x.name, p.name)) }));
    cf.forEach(p => { if (!list.some(x => sameName(x.name, p.name))) list.push({ name: p.name, home: p.home, admin: admins.includes(p.name.toLowerCase()), adminCf: admins.includes(p.name.toLowerCase()), email: '', phone: '', role: '', aka: [], source: 'cloudflare', lastSeen: seenAt[p.name] || null }); });
    // names someone used to have (renamed in the app) are not separate people
    const old = new Set(t.flatMap(p => (p.aka || []).map(a => a.toLowerCase())));
    Object.keys(seenAt).forEach(n => { if (!list.some(x => sameName(x.name, n)) && !old.has(n.toLowerCase())) list.push({ name: n, home: '', admin: admins.includes(n.toLowerCase()), adminCf: admins.includes(n.toLowerCase()), email: '', phone: '', role: '', aka: [], source: 'monday', lastSeen: seenAt[n] }); });
    const ph = await photoIndex(env);
    list.forEach(x => { const g = guardFor(gd, x.name); x.src = x.source; x.blocked = g && g.blocked ? g.blocked : null; x.out = (g && g.out) || null; x.photo = photoOf(ph, x.name); });
    return json({ ok: true, team: list, mail: { ready: !!(env.RESEND_API_KEY && env.ALERT_FROM), to: await recipients(env) }, cron: !!env.CRON_KEY });
  }
  if (view === 'export') {
    const out = []; let cursor;
    for (let i = 0; i < 20; i++) {
      const page = await env.FEED.list({ prefix: 'pinvisit:', cursor });
      for (const k of page.keys) { const a = await env.FEED.get(k.name, 'json'); (a || []).forEach(v => out.push(Object.assign({ id: k.name.slice(9) }, v))); }
      if (page.list_complete) break; cursor = page.cursor;
    }
    return json({ ok: true, visits: out });
  }
  return json({ ok: false, reason: 'bad-request' }, 400);
}

export async function onRequestPost({ request, env, waitUntil }) {
  const url = new URL(request.url);
  if (url.searchParams.get('cron') === 'monthly') return monthly(request, env);
  const origin = request.headers.get('Origin');
  if (origin && new URL(origin).host !== url.host) return json({ ok: false, reason: 'origin' }, 403);
  const chk = await checkPin(request, env);
  if (chk.res) return chk.res;
  let b;
  try { b = await request.json(); } catch (e) { return json({ ok: false, reason: chk.admin ? 'bad-request' : 'not-admin' }, chk.admin ? 400 : 403); }
  if (!b || typeof b !== 'object') return json({ ok: false, reason: 'bad-request' }, 400);
  // anyone may change their own name, e-mail and phone; everything else here is for admins
  const self = !chk.admin && !!chk.who && (b.action === 'team-rename' || b.action === 'team-update') && sameName(clean(b.name, 60), chk.who);
  if (!chk.admin && !self) return json({ ok: false, reason: 'not-admin' }, 403);
  if (!env.FEED) return json({ ok: false, reason: 'no-storage' }, 503);
  const by = chk.who || 'an admin', token = chk.token || env.MONDAY_API_TOKEN, api = env.MONDAY_API_URL, board = String(env.MONDAY_BOARD_ID || BOARD_ID);
  const id = String(b.id || '').replace(/\D/g, '');
  try {
    switch (b.action) {
      case 'review': {
        const all = await getJson(env, 'pinreview', []), it = all.find(x => x.rid === b.rid);
        if (!it || it.status !== 'open') return json({ ok: false, reason: 'gone' }, 404);
        const ok = b.decision === 'approve', reason = clean(b.reason, 300), d = it.data || {};
        let note = '';
        if (it.kind === 'cost') {
          // the second approval: never the person who asked
          if (!canDecide(it, chk.who)) return json({ ok: false, reason: 'own' }, 409);
          const r = await getJson(env, 'pinrepairs', {}), job = r[it.id];
          if (job && job.approval && job.approval.rid === it.rid) {
            job.approval = Object.assign({}, job.approval, { status: ok ? 'approved' : 'declined', dec: by, decAt: new Date().toISOString(), note: reason });
            await putJson(env, 'pinrepairs', r);
          }
          note = ok ? '✅ Repair cost of ' + kes(d.est) + ' approved by ' + by + ' (second approval; asked by ' + it.by + '). Parts can be ordered.' + (reason ? ' Note: ' + said(reason) : '')
            : '⛔ Repair cost of ' + kes(d.est) + ' declined by ' + by + (reason ? ': ' + said(reason) : '.') + ' Parts are not ordered yet.';
        }
        if (it.kind === 'new') note = ok ? '✅ New site reviewed and approved by ' + by + ' (RAFIKI pin app).' : '⛔ New site rejected by ' + by + (reason ? ': ' + said(reason) : '.') + ' Please archive it on the board if it should not be followed up.';
        if (it.kind === 'move') {
          if (ok) note = '✅ Location change to ' + d.to + ' approved by ' + by + '.';
          else { await writeGps(token, board, it.id, d.from || '', null, api); await dropFeed(env); note = '↩️ Location change rejected by ' + by + (reason ? ' (' + reason + ')' : '') + '. Back to ' + (d.from || 'no location') + '.'; }
        }
        if (it.kind === 'request') {
          if (ok) { await writeGps(token, board, it.id, d.to, null, api); await dropFeed(env); const c = await getJson(env, 'pinconfirmed', {}); c[it.id] = { by, at: new Date().toISOString(), value: d.to }; await putJson(env, 'pinconfirmed', c); note = '✅ Requested location ' + d.to + ' approved by ' + by + ' and confirmed.'; }
          else note = '⛔ Requested location ' + d.to + ' rejected by ' + by + (reason ? ': ' + said(reason) : '.') + ' The confirmed location stays ' + (d.from || 'as it was') + '.';
        }
        if (it.kind === 'story') {
          if (ok) { const st = await getJson(env, 'pinstories', []); st.push({ id: it.id, t: it.t, quote: d.quote, who: d.who, by: it.by, at: it.at, approvedBy: by }); await putJson(env, 'pinstories', st.slice(-500)); }
          note = ok ? '✨ Story moment approved for the website by ' + by + ': “' + d.quote + '”' + (d.who ? ' (' + d.who + ')' : '') + '.' : '⛔ Story moment not used, decided by ' + by + (reason ? ': ' + said(reason) : '.');
        }
        if (note) await createUpdate(token, it.id, note, api);
        it.status = ok ? 'approved' : 'rejected'; it.done = new Date().toISOString(); it.doneBy = by; it.reason = reason;
        await putJson(env, 'pinreview', all);
        await log(env, { by, a: ok ? 'approve' : 'reject', k: it.kind, id: it.id, t: it.t });
        return json({ ok: true, item: it });
      }
      case 'job': {
        if (!id) return json({ ok: false, reason: 'bad-request' }, 400);
        const r = await getJson(env, 'pinrepairs', {}), job = r[id];
        if (!job) return json({ ok: false, reason: 'gone' }, 404);
        let step = STEPS[b.step] ? b.step : job.step || 'reported';
        // fields not sent keep their current value
        const assignee = 'assignee' in b ? clean(b.assignee, 60) : job.assignee || '';
        const due = 'due' in b ? day(b.due) : job.due || '';
        const budget = 'budget' in b ? (b.budget !== '' && b.budget != null && Number(b.budget) >= 0 ? Math.round(Number(b.budget)) : null) : (job.budget == null ? null : job.budget);
        const ch = [], now0 = new Date().toISOString();
        // big repairs: parts ordered waits for a second admin to approve the cost (asked here, once)
        const blocked = stepBlocked(Object.assign({}, job, { budget }), step, await approveLimit(env));
        let held = '', asked = false;
        if (blocked) {
          held = step; step = job.step || 'reported';
          if (blocked !== 'pending') {
            job.budget = budget; const rid = askApproval(job, by, now0); asked = true;
            await addReview(env, { rid, kind: 'cost', id, t: job.t, by, data: { est: estOf(job), step: held, note: clean(b.note, 300), parts: job.parts || '' } });
            ch.push('second approval asked for ' + kes(estOf(job)));
            await log(env, { by, a: 'ask', k: 'cost', id, t: job.t });
          }
        }
        // what was actually spent at this step (not while the step is held back)
        const actual = held ? null : money(b.actual);
        if (actual) ch.push('spent ' + kes(actual) + (b.receipt ? ' (receipt attached)' : ''));
        if (step !== (job.step || 'reported')) ch.push('step: ' + STEPS[step]);
        if (assignee !== (job.assignee || '')) ch.push(assignee ? 'assigned to ' + assignee : 'unassigned');
        if (due !== (job.due || '')) ch.push(due ? 'due ' + due : 'no due date');
        if (budget !== (job.budget == null ? null : job.budget)) ch.push(budget == null ? 'no budget' : 'budget approved: KES ' + budget.toLocaleString('en-US'));
        const extra = clean(b.note, 600);
        if (!ch.length && !extra) return json({ ok: true, job, held, asked });
        const updateId = await createUpdate(token, id, '🔧 Repair job updated by ' + by + ' (RAFIKI pin app): ' + (ch.join('; ') || 'note added') + '.' + (extra ? ' Notes: ' + said(extra) : ''), api);
        if (actual) await addCost(env, id, costRow({ at: now0, rid: job.at, what: clean(b.what, 120) || STEPS[step], est: estOf(Object.assign({}, job, { budget })), actual, by, step, receipt: !!b.receipt }), { t: job.t });
        if (step === 'fixed') { delete r[id]; await log(env, { by, a: 'fixed', id, t: job.t }); }
        else {
          // when each step was reached, for the repair timeline in the app
          const at = Object.assign({}, job.stepAt), now = new Date().toISOString(), order = Object.keys(STEPS);
          if (step !== (job.step || 'reported')) order.slice(1, order.indexOf(step) + 1).forEach(k => { if (!at[k]) at[k] = now; });
          if (assignee && assignee !== (job.assignee || '')) at.assigned = at.assigned || now;
          Object.assign(job, { step, assignee, due, budget, stepAt: at }); r[id] = job; await log(env, { by, a: 'job', id, t: job.t, s: step });
        }
        await putJson(env, 'pinrepairs', r);
        // the project's chat thread
        const later = p => (waitUntil ? waitUntil(p) : p);
        if (assignee && ch.includes('assigned to ' + assignee)) later(chatSys(env, id, { type: 'assigned', to: assignee, by }, 'Repair assigned to ' + assignee + ' by ' + by + '.', { title: job.t, add: [assignee] }));
        if (ch.includes('step: ' + STEPS.fixed)) later(chatSys(env, id, { type: 'fixed', by }, 'Marked as fixed by ' + by + '.', { title: job.t }));
        else if (ch.some(x => x.indexOf('step: ') === 0)) later(chatSys(env, id, { type: 'step', step, by }, 'Repair step: ' + STEPS[step] + ' (by ' + by + ').', { title: job.t }));
        return json({ ok: true, job: step === 'fixed' ? null : job, updateId, held, asked });
      }
      case 'assign': {
        const ids = (Array.isArray(b.ids) ? b.ids : []).map(x => String(x).replace(/\D/g, '')).filter(Boolean).slice(0, 40);
        const to = clean(b.to, 60), date = day(b.date), note = clean(b.note, 300);
        if (!ids.length || !to || !date) return json({ ok: false, reason: 'bad-request' }, 400);
        const as = await getJson(env, 'pinassign', []), made = [];
        let feed = null; try { feed = await getFeed(env); } catch (e) {}
        for (const pid of ids) {
          const p = feed && (feed.projects || []).find(x => String(x.id) === pid);
          const a = { aid: crypto.randomUUID().slice(0, 10), id: pid, t: p ? p.title : '', to, date, note, by, at: new Date().toISOString() };
          as.push(a); made.push(a);
          await createUpdate(token, pid, '🗓 Site visit assigned to ' + to + ' for ' + date + ' by ' + by + (note ? '. Notes: ' + said(note) : '.'), api);
          const cs = chatSys(env, pid, { type: 'assigned', to, by }, 'Site visit assigned to ' + to + ' for ' + date + ' by ' + by + '.', { title: a.t, add: [to] });
          if (waitUntil) waitUntil(cs);
        }
        const cut = Date.now() - 120 * 864e5;
        await putJson(env, 'pinassign', as.filter(a => !a.done || Date.parse(a.done) > cut).slice(-600));
        await log(env, { by, a: 'assign', n: made.length, to, t: made.length + ' visit' + (made.length === 1 ? '' : 's') + ' for ' + to });
        return json({ ok: true, items: made });
      }
      case 'unassign': {
        const as = await getJson(env, 'pinassign', []), a = as.find(x => x.aid === b.aid);
        if (!a) return json({ ok: false, reason: 'gone' }, 404);
        await putJson(env, 'pinassign', as.filter(x => x.aid !== b.aid));
        return json({ ok: true });
      }
      case 'confirm': {
        if (!id) return json({ ok: false, reason: 'bad-request' }, 400);
        const c = await getJson(env, 'pinconfirmed', {}), cur = await readGps(token, id, api);
        if (!cur || (cur.board && cur.board !== board)) return json({ ok: false, reason: 'project' }, 404);
        if (b.on) {
          if (!parseGps(cur.text)) return json({ ok: false, reason: 'no-location' }, 409);
          c[id] = { by, at: new Date().toISOString(), value: cur.text };
          await createUpdate(token, id, '🔒 Location ' + cur.text + ' confirmed by ' + by + '. Changes from the field now need an admin’s approval.', api);
        } else {
          delete c[id];
          await createUpdate(token, id, '🔓 Location no longer marked as confirmed (' + by + ').', api);
        }
        await putJson(env, 'pinconfirmed', c);
        return json({ ok: true, confirmed: c[id] || null });
      }
      case 'team-add': case 'team-update': case 'team-pin': case 'team-remove': case 'team-rename': case 'team-set-pin':
      case 'team-block': case 'team-unblock': case 'team-signout': case 'team-move': {
        const r = await teamPost(env, b, chk, by);
        // the Team chats follow the team; blocked, removed or signed-out phones are cut off from chat at once
        if ((r.status || 200) < 300 && waitUntil) waitUntil(chatSync(env));
        return json(r.body, r.status || 200);
      }
      case 'settings': {
        const n = money(b.approveAbove, 1000000000);
        if (n == null) return json({ ok: false, reason: 'bad-request' }, 400);
        await setApproveLimit(env, n, by);
        await log(env, { by, a: 'settings', t: 'second approval above ' + kes(n) });
        return json({ ok: true, approveAbove: n });
      }
      case 'donor-add': case 'donor-update': case 'donor-link': case 'donor-rotate': case 'donor-revoke': case 'donor-viewer-add': case 'donor-viewer-remove': case 'donor-remove': case 'donor-viewer-update': case 'donor-viewer-restore': {
        const r = await donorAdminPost(env, b, by);
        return json(r.body, r.status || 200);
      }
      case 'test-mail': {
        const r = await sendMail(env, 'RAFIKI Pin: test alert', alertHtml('E-mail alerts are working', [{ label: 'Straight away', text: 'Urgent repairs, E. coli results and community reports', icon: 'check-green' }, { label: 'Every month', text: 'The field summary, on the 1st', icon: 'cal-blue' }, { label: 'Test sent by', text: by, icon: 'people-blue' }], new URL('/pin#office', request.url).toString(), '', { tone: 'green', icon: 'check-green', eyebrow: 'Test e-mail', intro: 'If you can read this, RAFIKI Pin can e-mail the admins.', cta: { label: 'Open RAFIKI Pin', href: new URL('/pin#office', request.url).toString() }, preheader: 'E-mail alerts from RAFIKI Pin are working.' }), 'Test alert from RAFIKI Pin. Sent by ' + by + '.');
        return json(r, r.ok ? 200 : 503);
      }
    }
    return json({ ok: false, reason: 'bad-request' }, 400);
  } catch (e) {
    console.error('admin ' + b.action + ':', e && e.message);
    return json({ ok: false, reason: 'down' }, 502);
  }
}

// ---- Office › Team: one person's contact sheet ---------------------------------------------------------------
const okMail = e => /^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(e);
const R = (status, body) => ({ status, body });
// everyone who can be an admin right now (app admins and PIN_ADMINS), minus the blocked
function activeAdmins(env, t, gd) {
  const names = new Set(adminNames(env).concat(t.filter(p => p.admin).map(p => p.name.toLowerCase())));
  return [...names].filter(n => !(guardFor(gd, n) || {}).blocked);
}
export async function teamPost(env, b, chk, by) {
  const t = await team(env), name = clean(b.name, 60), a = b.action, me = chk.who || '';
  if (!name) return R(400, { ok: false, reason: 'bad-request' });
  let p = t.find(x => sameName(x.name, name));
  const cf = people(env).find(x => sameName(x.name, name));
  const seenAt = await getJson(env, 'pinseen', {});
  const known = p || cf || Object.keys(seenAt).some(n => sameName(n, name)) || adminNames(env).includes(name.toLowerCase());
  const isSelf = sameName(name, me);
  const logT = async (text, who) => log(env, { by, a: 'team', t: text, who: who || (p && p.name) || name }); // "Morgan" + "blocked Grace"
  if (a === 'team-add') {
    if (p || cf) return R(409, { ok: false, reason: 'exists' });
    const email = clean(b.email, 120).toLowerCase();
    if (email && !okMail(email)) return R(400, { ok: false, reason: 'bad-email' });
    const { pin, h } = await freshPin(env, t);
    p = { name, home: CC.includes(b.home) ? b.home : '', admin: !!b.admin, email, phone: phone(b.phone), role: clean(b.role, 80), h, added: new Date().toISOString(), by };
    t.push(p); await saveTeam(env, t);
    await logT('added ' + name);
    return R(200, { ok: true, pin });
  }
  if (a === 'team-move') {
    if (p) return R(409, { ok: false, reason: 'exists' });
    if (!cf) return R(404, { ok: false, reason: 'not-cloudflare' });
    const { pin, h } = await freshPin(env, t);
    t.push({ name: cf.name, home: cf.home || '', admin: adminNames(env).includes(cf.name.toLowerCase()), email: '', phone: '', role: '', h, added: new Date().toISOString(), by, moved: true });
    await saveTeam(env, t);
    await logT('moved ' + cf.name + ' to an app PIN');
    return R(200, { ok: true, pin });
  }
  // blocking, unblocking and signing out work for everyone, whichever way they sign in
  if (a === 'team-block' || a === 'team-unblock' || a === 'team-signout') {
    if (!known) return R(404, { ok: false, reason: 'gone' });
    const who = (p || cf || {}).name || name, gd = await guards(env), k = guardKey(who), g = Object.assign({ gen: 0, out: null, blocked: null }, gd[k]);
    const now = new Date().toISOString();
    if (a === 'team-block') {
      if (isSelf) return R(409, { ok: false, reason: 'self' });
      const adm = activeAdmins(env, t, gd);
      if (adm.includes(k) && adm.length <= 1) return R(409, { ok: false, reason: 'last-admin' });
      g.blocked = { at: now, by, reason: clean(b.reason, 200) };
      g.gen = (Number(g.gen) || 0) + 1; g.out = now;           // every phone is signed out straight away
      gd[k] = g; await saveGuards(env, gd);
      await env.FEED.delete(hereKey(who));                        // off the team map
      await logT('blocked ' + who + (g.blocked.reason ? ' (' + g.blocked.reason + ')' : ''));
      return R(200, { ok: true, blocked: g.blocked });
    }
    if (a === 'team-unblock') {
      g.blocked = null; gd[k] = g; await saveGuards(env, gd);
      await logT('unblocked ' + who);
      return R(200, { ok: true });
    }
    g.gen = (Number(g.gen) || 0) + 1; g.out = now; gd[k] = g; await saveGuards(env, gd);
    await logT('signed ' + who + ' out of all phones');
    return R(200, { ok: true, gen: g.gen });
  }
  if (!p) return R(404, { ok: false, reason: cf ? 'cloudflare' : 'not-in-app' });
  if (a === 'team-remove') {
    if (isSelf) return R(409, { ok: false, reason: 'self' });
    const gd = await guards(env), adm = activeAdmins(env, t, gd);
    if (p.admin && !adminNames(env).includes(p.name.toLowerCase()) && adm.includes(guardKey(p.name)) && adm.length <= 1) return R(409, { ok: false, reason: 'last-admin' });
    await saveTeam(env, t.filter(x => x !== p));
    await dropPhoto(env, p.name);
    await logT('removed ' + p.name);
    return R(200, { ok: true });
  }
  if (a === 'team-pin') {
    const { pin, h } = await freshPin(env, t.filter(x => x !== p)); p.h = h; await saveTeam(env, t);
    await logT('new PIN for ' + p.name);
    return R(200, { ok: true, pin });
  }
  if (a === 'team-set-pin') {
    const pin = String(b.pin == null ? '' : b.pin).trim();
    if (weakPin(pin)) return R(400, { ok: false, reason: 'weak-pin' });
    if (await pinInUse(env, t, pin, p)) return R(409, { ok: false, reason: 'pin-taken' });
    p.h = await hashPin(env, pin); await saveTeam(env, t);
    await logT('set a PIN for ' + p.name);
    return R(200, { ok: true });
  }
  if (a === 'team-rename') {
    const to = clean(b.to, 60), from = p.name;
    if (!to) return R(400, { ok: false, reason: 'bad-request' });
    if (to === from) return R(200, { ok: true, name: to, aka: p.aka || [] });
    const mine = n => sameName(n, from) || (p.aka || []).some(x => sameName(x, n));
    const taken = t.some(x => x !== p && sameName(x.name, to)) || people(env).some(x => sameName(x.name, to)) ||
      Object.keys(seenAt).some(n => sameName(n, to) && !mine(n)) || t.some(x => x !== p && (x.aka || []).some(n => sameName(n, to)));
    if (taken) return R(409, { ok: false, reason: 'exists' });
    // the old name is kept, so history, the team feed, totals and repair jobs under it still count for them
    p.aka = [...new Set((p.aka || []).concat([from]))].filter(n => !sameName(n, to)).slice(-10);
    p.name = to; await saveTeam(env, t);
    const rep = await getJson(env, 'pinrepairs', {}); let nr = 0;
    Object.values(rep).forEach(j => { if (j && sameName(j.assignee, from)) { j.assignee = to; nr++; } });
    if (nr) await putJson(env, 'pinrepairs', rep);
    const as = await getJson(env, 'pinassign', []); let na = 0;
    as.forEach(x => { if (!x.done && sameName(x.to, from)) { x.to = to; na++; } });
    if (na) await putJson(env, 'pinassign', as);
    const move = async key => { const m = await getJson(env, key, {}), k = Object.keys(m).find(n => sameName(n, from)); if (k) { m[to] = m[k]; delete m[k]; await putJson(env, key, m); } };
    await move('pinseen'); await move('pinprofiles');
    const gd = await guards(env), gk = guardKey(from); if (gd[gk]) { gd[guardKey(to)] = gd[gk]; delete gd[gk]; await saveGuards(env, gd); }
    await moveLink(env, from, to);
    await movePhoto(env, from, to);
    await env.FEED.delete(hereKey(from));
    await logT('renamed ' + from + ' to ' + to);
    return R(200, { ok: true, name: to, aka: p.aka, repairs: nr, visits: na });
  }
  // team-update: only the fields sent change. Your own: name (team-rename), e-mail and phone; admins: everything.
  const admin = !!chk.admin;
  if ('email' in b) { const e = clean(b.email, 120).toLowerCase(); if (e && !okMail(e)) return R(400, { ok: false, reason: 'bad-email' }); }
  if (admin && 'admin' in b && !b.admin && p.admin) {
    if (isSelf && !adminNames(env).includes(name.toLowerCase())) return R(409, { ok: false, reason: 'self' });
    const adm = activeAdmins(env, t, await guards(env));
    if (!adminNames(env).includes(name.toLowerCase()) && adm.includes(guardKey(p.name)) && adm.length <= 1) return R(409, { ok: false, reason: 'last-admin' });
  }
  const was = { admin: !!p.admin, email: p.email || '', phone: p.phone || '' };
  if ('email' in b) p.email = clean(b.email, 120).toLowerCase();
  if ('phone' in b) p.phone = phone(b.phone);
  if (admin) {
    if ('home' in b) p.home = CC.includes(b.home) ? b.home : '';
    if ('admin' in b) p.admin = !!b.admin;
    if ('role' in b) p.role = clean(b.role, 80);
  }
  await saveTeam(env, t);
  const ch = [];
  if (was.admin !== !!p.admin) ch.push(p.admin ? 'made ' + p.name + ' an admin' : 'removed ' + p.name + '’s admin role');
  if (was.email !== (p.email || '')) ch.push(p.email ? 'changed ' + p.name + '’s e-mail' : 'removed ' + p.name + '’s e-mail');
  if (was.phone !== (p.phone || '')) ch.push(p.phone ? 'changed ' + p.name + '’s phone' : 'removed ' + p.name + '’s phone');
  if (ch.length) await logT('' + ch.join(', '));
  return R(200, { ok: true });
}

// Monthly summary e-mail (last calendar month), triggered by the scheduled GitHub workflow.
async function monthly(request, env) {
  const k = request.headers.get('X-Cron-Key') || '';
  if (!env.CRON_KEY || k.length < 16 || k !== env.CRON_KEY) return json({ ok: false, reason: 'pin' }, 401);
  const now = new Date(), from = Date.UTC(now.getUTCFullYear(), now.getUTCMonth() - 1, 1), to = Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), 1);
  const label = new Date(from).toLocaleDateString('en-GB', { month: 'long', year: 'numeric', timeZone: 'UTC' });
  const act = (await getJson(env, 'pinact', [])).filter(e => { const t = Date.parse(e.at); return t >= from && t < to; });
  const n = a => act.filter(e => e.a === a).length;
  const visits = act.filter(e => e.a === 'visit'), latest = {};
  visits.forEach(e => { if (!latest[e.id] || latest[e.id].at < e.at) latest[e.id] = e; });
  const ids = Object.keys(latest), working = ids.filter(i => latest[i].s === 'working').length;
  const rep = await getJson(env, 'pinrepairs', {}), open = Object.values(rep), urgent = open.filter(r => r.urgency === 'urgent').length;
  const cost = open.reduce((s, r) => s + (Number(r.cost) || 0), 0);
  const review = (await getJson(env, 'pinreview', [])).filter(x => x.status === 'open').length;
  const spent = (await getJson(env, 'pincostidx', [])).filter(e => { const t = Date.parse(e.at); return t >= from && t < to; }).reduce((s2, e) => s2 + (Number(e.actual) || 0), 0);
  // the people-served trend gets this month's total (also kept up to date as visits come in)
  try { const feed = await getFeed(env); await snapPeople(env, feed.projects || []); } catch (e) { console.error('people:', e && e.message); }
  // field trips finished last month (lib/itin.js)
  let trips = null; try { trips = await tripsMonth(env, from, to); } catch (e) { console.error('trips month:', e && e.message); }
  const lines = [
    visits.length + ' site visits to ' + ids.length + ' projects' + (ids.length ? ' (' + Math.round(working / ids.length * 100) + '% working)' : '') + '.',
    open.length + ' open repairs' + (urgent ? ', ' + urgent + ' urgent' : '') + (cost ? ', about KES ' + cost.toLocaleString('en-US') + ' estimated' : '') + '; ' + n('fixed') + ' fixed during the month.',
    n('pin') + ' locations pinned, ' + n('new') + ' new sites added.' + (spent ? ' ' + kes(spent) + ' spent on repairs.' : ''),
    review ? review + ' items waiting for review in the Office tab.' : 'Nothing waiting for review.'
  ];
  if (trips && trips.trips) lines.push(trips.text);
  // donors: what changed on their projects last month (skipped cleanly when e-mail isn't set up)
  let donorsMail;
  try { donorsMail = await runDigest(env, from, to, new URL(request.url).origin); } catch (e) { console.error('donor digest:', e && e.message); donorsMail = { ok: false, reason: 'down' }; }
  const r = await sendMail(env, 'RAFIKI Pin: ' + label + ' summary', alertHtml(label, lines.map((t, k) => ({ label: ['Visits', 'Repairs', 'Mapping', 'Waiting for review', 'Trips'][k], text: t, icon: ['cal-blue', 'wrench-amber', 'pin-blue', 'inbox-blue', 'cal-blue'][k] })), new URL('/pin#report', request.url).toString(), new URL(request.url).origin, { tone: 'blue', icon: 'cal-blue', eyebrow: 'Monthly field summary', intro: 'Here is what the field team did last month.', stats: [[visits.length, 'site visits'], [ids.length ? Math.round(working / ids.length * 100) + '%' : '–', 'working'], [open.length, 'open repairs'], [n('fixed'), 'fixed']], cta: { label: 'Open the full report', href: new URL('/pin#report', request.url).toString() }, preheader: visits.length + ' site visits, ' + open.length + ' open repairs in ' + label + '.' }), lines.join('\n'));
  return json(Object.assign({ month: label }, r, { donors: donorsMail }), r.ok ? 200 : 503);
}

export function onRequest() { return json({ ok: false, reason: 'method' }, 405); }
