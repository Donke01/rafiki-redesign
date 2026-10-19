// /api/admin: the Office tools in the pin app, for admins only (PIN_ADMINS, or made admin in the app).
//   GET  /api/admin?view=review   → the review queue (new sites, big location changes, change requests, story moments)
//   GET  /api/admin?view=team     → everyone who can sign in, their role, home country and last use
//   GET  /api/admin?view=assign   → visits assigned to field officers
//   GET  /api/admin?view=export   → every visit record kept by the app (for the spreadsheet export)
//   POST /api/admin { action: "review",  rid, decision: "approve"|"reject", reason }
//                   { action: "job",     id, step, assignee, due, budget, note }      repair jobs
//                   { action: "assign",  ids: [], to, date, note } / { action: "unassign", aid }
//                   { action: "confirm", id, on }                                   lock a confirmed location
//                   { action: "team-add", name, home, admin, email } → { pin } (shown once)
//                   { action: "team-update", name, home, admin, email } / "team-pin" (new PIN) / "team-remove"
//                   { action: "test-mail" }
//   POST /api/admin?cron=monthly with header X-Cron-Key = CRON_KEY → e-mails last month's summary to admins
//        (run on the 1st of each month by .github/workflows/monthly-report.yml)
import { checkPin } from './pin.js';
import { getFeed, dropFeed } from '../../lib/feed-cache.js';
import { parseGps, readGps, writeGps, createUpdate, BOARD_ID } from '../../lib/monday.js';
import { getJson, putJson, team, saveTeam, hashPin, newPin, adminNames, sameName, sendMail, alertHtml, escHtml, recipients } from '../../lib/pin-store.js';

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
function people(env) {
  return String(env.PIN_USERS || '').split(/[,;\n]/).map(s => { const a = s.split(':').map(x => x.trim()); let home = ''; if (a.length > 2 && /^(ke|tz|ug|mw)$/i.test(a[a.length - 1])) home = a.pop().toLowerCase(); return a.length >= 2 ? { name: a.slice(0, -1).join(':'), home } : null; }).filter(Boolean);
}

export async function onRequestGet({ request, env }) {
  const chk = await admin(request, env);
  if (chk.res) return chk.res;
  const view = new URL(request.url).searchParams.get('view');
  if (view === 'review') return json({ ok: true, items: (await getJson(env, 'pinreview', [])).slice().reverse().map(x => (x.t ? Object.assign({}, x, { t: String(x.t).replace(/^[\s*#_-]+|[\s*#_]+$/g, '') }) : x)) });
  if (view === 'assign') return json({ ok: true, items: await getJson(env, 'pinassign', []) });
  if (view === 'team') {
    const t = await team(env), seenAt = await getJson(env, 'pinseen', {}), admins = adminNames(env);
    const list = t.map(p => ({ name: p.name, home: p.home || '', admin: !!p.admin || admins.includes(p.name.toLowerCase()), email: p.email || '', source: 'app', added: p.added, lastSeen: seenAt[p.name] || null }));
    people(env).forEach(p => { if (!list.some(x => sameName(x.name, p.name))) list.push({ name: p.name, home: p.home, admin: admins.includes(p.name.toLowerCase()), email: '', source: 'cloudflare', lastSeen: seenAt[p.name] || null }); });
    Object.keys(seenAt).forEach(n => { if (!list.some(x => sameName(x.name, n))) list.push({ name: n, home: '', admin: admins.includes(n.toLowerCase()), email: '', source: 'monday', lastSeen: seenAt[n] }); });
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
  const chk = await admin(request, env);
  if (chk.res) return chk.res;
  let b;
  try { b = await request.json(); } catch (e) { return json({ ok: false, reason: 'bad-request' }, 400); }
  const by = chk.who || 'an admin', token = chk.token || env.MONDAY_API_TOKEN, api = env.MONDAY_API_URL, board = String(env.MONDAY_BOARD_ID || BOARD_ID);
  const id = String(b.id || '').replace(/\D/g, '');
  try {
    switch (b.action) {
      case 'review': {
        const all = await getJson(env, 'pinreview', []), it = all.find(x => x.rid === b.rid);
        if (!it || it.status !== 'open') return json({ ok: false, reason: 'gone' }, 404);
        const ok = b.decision === 'approve', reason = clean(b.reason, 300), d = it.data || {};
        let note = '';
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
        const step = STEPS[b.step] ? b.step : job.step || 'reported';
        // fields not sent keep their current value
        const assignee = 'assignee' in b ? clean(b.assignee, 60) : job.assignee || '';
        const due = 'due' in b ? day(b.due) : job.due || '';
        const budget = 'budget' in b ? (b.budget !== '' && b.budget != null && Number(b.budget) >= 0 ? Math.round(Number(b.budget)) : null) : (job.budget == null ? null : job.budget);
        const ch = [];
        if (step !== (job.step || 'reported')) ch.push('step: ' + STEPS[step]);
        if (assignee !== (job.assignee || '')) ch.push(assignee ? 'assigned to ' + assignee : 'unassigned');
        if (due !== (job.due || '')) ch.push(due ? 'due ' + due : 'no due date');
        if (budget !== (job.budget == null ? null : job.budget)) ch.push(budget == null ? 'no budget' : 'budget approved: KES ' + budget.toLocaleString('en-US'));
        const extra = clean(b.note, 600);
        if (!ch.length && !extra) return json({ ok: true, job });
        await createUpdate(token, id, '🔧 Repair job updated by ' + by + ' (RAFIKI pin app): ' + (ch.join('; ') || 'note added') + '.' + (extra ? ' Notes: ' + said(extra) : ''), api);
        if (step === 'fixed') { delete r[id]; await log(env, { by, a: 'fixed', id, t: job.t }); }
        else { Object.assign(job, { step, assignee, due, budget }); r[id] = job; await log(env, { by, a: 'job', id, t: job.t, s: step }); }
        await putJson(env, 'pinrepairs', r);
        return json({ ok: true, job: step === 'fixed' ? null : job });
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
      case 'team-add': case 'team-update': case 'team-pin': case 'team-remove': {
        const t = await team(env), name = clean(b.name, 60);
        if (!name) return json({ ok: false, reason: 'bad-request' }, 400);
        let p = t.find(x => sameName(x.name, name));
        if (b.action === 'team-add') {
          if (p) return json({ ok: false, reason: 'exists' }, 409);
          const pin = newPin();
          p = { name, home: CC.includes(b.home) ? b.home : '', admin: !!b.admin, email: clean(b.email, 120), h: await hashPin(env, pin), added: new Date().toISOString(), by };
          t.push(p); await saveTeam(env, t);
          await log(env, { by, a: 'team', t: 'added ' + name });
          return json({ ok: true, pin });
        }
        if (!p) return json({ ok: false, reason: 'not-in-app' }, 404);
        if (b.action === 'team-remove') { await saveTeam(env, t.filter(x => x !== p)); await log(env, { by, a: 'team', t: 'removed ' + name }); return json({ ok: true }); }
        if (b.action === 'team-pin') { const pin = newPin(); p.h = await hashPin(env, pin); await saveTeam(env, t); await log(env, { by, a: 'team', t: 'new PIN for ' + name }); return json({ ok: true, pin }); }
        if (sameName(name, by) && p.admin && !b.admin && !adminNames(env).includes(name.toLowerCase())) return json({ ok: false, reason: 'self' }, 409);
        p.home = CC.includes(b.home) ? b.home : ''; p.admin = !!b.admin; p.email = clean(b.email, 120);
        await saveTeam(env, t);
        return json({ ok: true });
      }
      case 'test-mail': {
        const r = await sendMail(env, 'RAFIKI Pin: test alert', alertHtml('Test alert from RAFIKI Pin', ['If you can read this, e-mail alerts are working.', 'Sent by ' + by + '.'], new URL('/pin#me', request.url).toString()), 'Test alert from RAFIKI Pin. Sent by ' + by + '.');
        return json(r, r.ok ? 200 : 503);
      }
    }
    return json({ ok: false, reason: 'bad-request' }, 400);
  } catch (e) {
    console.error('admin ' + b.action + ':', e && e.message);
    return json({ ok: false, reason: 'down' }, 502);
  }
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
  const lines = [
    visits.length + ' site visits to ' + ids.length + ' projects' + (ids.length ? ' (' + Math.round(working / ids.length * 100) + '% working)' : '') + '.',
    open.length + ' open repairs' + (urgent ? ', ' + urgent + ' urgent' : '') + (cost ? ', about KES ' + cost.toLocaleString('en-US') + ' estimated' : '') + '; ' + n('fixed') + ' fixed during the month.',
    n('pin') + ' locations pinned, ' + n('new') + ' new sites added.',
    review ? review + ' items waiting for review in the Office tab.' : 'Nothing waiting for review.'
  ];
  const r = await sendMail(env, 'RAFIKI Pin: ' + label + ' summary', alertHtml('Field summary: ' + label, lines, new URL('/pin#report', request.url).toString()), lines.join('\n'));
  return json(Object.assign({ month: label }, r), r.ok ? 200 : 503);
}

export function onRequest() { return json({ ok: false, reason: 'method' }, 405); }
