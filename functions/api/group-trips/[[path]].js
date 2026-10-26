// /api/group-trips: volunteer and donor group trips (Office › Trips), admins only. Logic and privacy rules: lib/group-trips.js.
//   GET  /api/group-trips                  → { items }
//   GET  /api/group-trips/<id>             → { trip } (everything, with totals in USD and KES)
//   POST /api/group-trips                  create → { trip }
//   POST /api/group-trips/<id> { action, v, … }
//        update · participant-add | participant-update | participant-remove · item-add | item-update | item-remove
//        cost-add | cost-remove · payment-add | payment-remove · share (rotate) | unshare · remind (iid, send) · delete
import { checkPin } from '../pin.js';
import { sendAll, mailReady, firstName, readLog, addLog, usage, overCap } from '../../../lib/mail.js';
import { loadGroup, saveGroup, removeGroup, tripFrom, participantFrom, itemFrom, costFrom, paymentFrom, withTotals, shareGroup, reminderMail, LIMITS } from '../../../lib/group-trips.js';
import { getJson } from '../../../lib/pin-store.js';
import { newId } from '../../../lib/itin.js';

const HEAD = { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff', 'X-Robots-Tag': 'noindex' };
const json = (b, s) => new Response(JSON.stringify(b), { status: s || 200, headers: HEAD });
const bad = (reason, status, extra) => json(Object.assign({ ok: false, reason }, extra || {}), status || 400);

async function admin(request, env) {
  const chk = await checkPin(request, env);
  if (chk.res) return chk;
  if (!chk.admin) return { res: bad('not-admin', 403) };
  if (!env.FEED) return { res: bad('no-storage', 503) };
  return chk;
}

export async function onRequestGet({ request, env, params }) {
  const chk = await admin(request, env);
  if (chk.res) return chk.res;
  const path = (params && params.path) || [];
  if (!path.length) return json({ ok: true, items: await getJson(env, 'groupidx', []) });
  const t = await loadGroup(env, path[0]);
  if (!t) return bad('not-found', 404);
  return json({ ok: true, trip: await withTotals(env, t) });
}

export async function onRequestPost({ request, env, params }) {
  const origin = request.headers.get('Origin');
  if (origin && new URL(origin).host !== new URL(request.url).host) return bad('origin', 403);
  const chk = await admin(request, env);
  if (chk.res) return chk.res;
  let b; try { b = await request.json(); } catch (e) { return bad('bad-request'); }
  if (!b || typeof b !== 'object') return bad('bad-request');
  const path = (params && params.path) || [], site = new URL(request.url).origin;
  try {
    if (!path.length) {
      const r = tripFrom(b, null);
      if (r.err) return bad(r.err);
      const now = new Date().toISOString();
      const t = Object.assign(r.t, { id: newId('gt'), v: 1, created: now, updated: now, by: chk.who, share: null });
      await saveGroup(env, t);
      return json({ ok: true, trip: await withTotals(env, t) }, 201);
    }
    const t = await loadGroup(env, path[0]);
    if (!t) return bad('not-found', 404);
    if (b.v != null && Number(b.v) !== t.v) return json({ ok: false, reason: 'conflict', current: await withTotals(env, t) }, 409);
    const done = async () => { t.v++; t.updated = new Date().toISOString(); t.by = chk.who; await saveGroup(env, t); return json({ ok: true, trip: await withTotals(env, t) }); };
    const nextId = (list, key, p) => p + (list.reduce((m, x) => Math.max(m, Number(String(x[key]).slice(p.length)) || 0), 0) + 1);
    switch (b.action) {
      case 'update': { const r = tripFrom(b, t); if (r.err) return bad(r.err, 400, { field: r.field }); Object.assign(t, r.t); if (t.items.some(i => i.date < t.from || i.date > t.to)) return bad('bad-date', 400, { why: 'items-outside' }); return done(); }
      case 'participant-add': {
        if (t.participants.length >= LIMITS.participants) return bad('too-many');
        const r = participantFrom(b.participant, null); if (r.err) return bad(r.err, 400, { field: r.field });
        t.participants.push(Object.assign(r.p, { pid: nextId(t.participants, 'pid', 'p') })); return done();
      }
      case 'participant-update': {
        const i = t.participants.findIndex(p => p.pid === b.pid); if (i < 0) return bad('not-found', 404);
        const r = participantFrom(b.participant, t.participants[i]); if (r.err) return bad(r.err, 400, { field: r.field });
        t.participants[i] = r.p; return done();
      }
      case 'participant-remove': { const n = t.participants.length; t.participants = t.participants.filter(p => p.pid !== b.pid); if (n === t.participants.length) return bad('not-found', 404); t.payments = t.payments.filter(y => y.pid !== b.pid); return done(); }
      case 'item-add': {
        if (t.items.length >= LIMITS.items) return bad('too-many');
        const r = itemFrom(b.item, null, t); if (r.err) return bad(r.err, 400, { field: r.field, why: r.why });
        t.items.push(Object.assign(r.it, { iid: nextId(t.items, 'iid', 'i') })); return done();
      }
      case 'item-update': {
        const i = t.items.findIndex(x => x.iid === b.iid); if (i < 0) return bad('not-found', 404);
        const r = itemFrom(b.item, t.items[i], t); if (r.err) return bad(r.err, 400, { field: r.field, why: r.why });
        t.items[i] = r.it; return done();
      }
      case 'item-remove': { const n = t.items.length; t.items = t.items.filter(x => x.iid !== b.iid); if (n === t.items.length) return bad('not-found', 404); return done(); }
      case 'cost-add': { if (t.costs.length >= LIMITS.costs) return bad('too-many'); const r = costFrom(b.cost || {}); if (r.err) return bad(r.err); t.costs.push(Object.assign(r.c, { cid: nextId(t.costs, 'cid', 'c') })); return done(); }
      case 'cost-remove': { t.costs = t.costs.filter(c => c.cid !== b.cid); return done(); }
      case 'payment-add': { if (t.payments.length >= LIMITS.payments) return bad('too-many'); const r = paymentFrom(b.payment || {}, t); if (r.err) return bad(r.err); t.payments.push(Object.assign(r.y, { payid: nextId(t.payments, 'payid', 'y') })); return done(); }
      case 'payment-remove': { t.payments = t.payments.filter(y => y.payid !== b.payid); return done(); }
      case 'share': { const r = await shareGroup(env, t, chk.who, site, !!b.rotate); t.v++; t.updated = new Date().toISOString(); await saveGroup(env, t); return json({ ok: true, url: r.url, token: r.token, v: t.v }); }
      case 'unshare': { if (t.share && t.share.h) await env.FEED.delete('itinshare:' + t.share.h); t.share = null; return done(); }
      case 'remind': {
        const it = t.items.find(x => x.iid === b.iid); if (!it) return bad('not-found', 404);
        const to = t.participants.filter(p => p.email), missing = t.participants.length - to.length;
        const sample = reminderMail(t, it, site, to[0] ? firstName(to[0].name) : '');
        if (!b.send) return json({ ok: true, preview: sample, to: to.length, missing, ready: mailReady(env) });
        if (!mailReady(env)) return bad('no-mail', 503);
        if (!to.length) return bad('no-recipients');
        const use = usage(await readLog(env)), cap = overCap(env, to.length, use);
        if (cap) return bad(cap.reason, 429, cap);
        const res = await sendAll(env, to, p => reminderMail(t, it, site, firstName(p.name)), { replyTo: t.contact || undefined });
        const sent = res.filter(x => x.ok).length;
        await addLog(env, { id: crypto.randomUUID().slice(0, 12), at: new Date().toISOString(), by: chk.who, audience: 'group:' + t.id, label: 'Trip reminder: ' + t.name, kind: 'group', subject: sample.subject, link: false,
          n: to.length, sent, failed: res.length - sent, skipped: missing, skippedBy: { unsub: 0, revoked: 0, noEmail: missing, dup: 0 }, rcpt: res.map(x => ({ name: x.p.name, s: x.ok ? 'sent' : 'failed', e: x.ok ? undefined : String(x.error || '').slice(0, 120) })) });
        it.remind = Object.assign({}, it.remind || {}, { sentAt: new Date().toISOString() });
        await done();
        return json({ ok: true, sent, failed: res.filter(x => !x.ok).map(x => ({ name: x.p.name, error: x.error })) });
      }
      case 'delete': await removeGroup(env, t); return json({ ok: true, deleted: t.id });
      default: return bad('action');
    }
  } catch (e) { console.error('group-trips:', e && e.stack || e); return bad('down', 502); }
}

export function onRequest() { return json({ ok: false, reason: 'method' }, 405); }
