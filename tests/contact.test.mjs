// node --test tests/contact.test.mjs — Office › Team › a person's contact sheet (functions/api/admin.js teamPost):
// rename with history kept, set a PIN (weak / taken), block and unblock, sign out of all phones, move a Cloudflare
// person to an app PIN, the self and last-admin protections, non-admins, and the donor viewer update / restore.
import test from 'node:test';
import assert from 'node:assert/strict';
import { weakPin } from '../lib/pin-store.js';
import { tokenSession } from '../lib/pin-auth.js';
import { donorAdminPost, findByToken } from '../lib/donor.js';

function kv(init = {}) {
  const m = new Map(Object.entries(init).map(([k, v]) => [k, JSON.stringify(v)]));
  return {
    m, get: async (k, t) => { const v = m.get(k); return v == null ? null : t === 'json' ? JSON.parse(v) : v; },
    put: async (k, v) => { m.set(k, String(v)); }, delete: async k => { m.delete(k); },
    list: async ({ prefix }) => ({ keys: [...m.keys()].filter(k => k.startsWith(prefix)).map(name => ({ name })), list_complete: true })
  };
}
// monday.com: who a token belongs to (for the monday.com session test); everything else answers empty
globalThis.fetch = async (url, o) => {
  const b = JSON.parse((o && o.body) || '{}');
  if (/me \{ id name/.test(b.query || '')) return new Response(JSON.stringify({ data: { me: { id: '77', name: 'Bertin', photo_thumb_small: '', account: { id: '1', slug: 'rafiki' } }, boards: [{ id: '9412605754' }] } }));
  return new Response(JSON.stringify({ data: {} }));
};
const FEEDV = { updated: '2026-10-20T00:00:00Z', projects: [{ id: '101', title: 'Kapsaret Borehole', country: 'Kenya', ck: 'ke', status: 'Completed', photos: [] }] };
function env(extra) {
  const store = kv({ pinacct: { id: '1', slug: 'rafiki' } });
  store.m.set('feed:v9', JSON.stringify({ at: Date.now(), feed: FEEDV }));
  return Object.assign({ FEED: store, MONDAY_API_TOKEN: 't', PIN_USERS: 'Morgan:4821, Meril:7364:ke', PIN_ADMINS: 'Morgan' }, extra || {});
}
const admin = await import('../functions/api/admin.js');
const pin = await import('../functions/api/pin.js');
let ip = 1;
function req(path, h, body) {
  return new Request('https://pin.example' + path, { method: body ? 'POST' : 'GET', headers: Object.assign({ 'CF-Connecting-IP': '10.0.0.' + (ip++ % 250) }, h, body ? { 'Content-Type': 'application/json', Origin: 'https://pin.example' } : {}), body: body ? JSON.stringify(body) : undefined });
}
const run = async (E, mod, path, h, body) => { const r = await (body ? mod.onRequestPost : mod.onRequestGet)({ request: req(path, h, body), env: E, waitUntil: p => p }); return Object.assign(await r.json(), { status: r.status }); };
const as = (E, p) => ({ adm: b => run(E, admin, '/api/admin', { 'X-Pin': p }, b), get: (q, h) => run(E, pin, '/api/pin' + (q || ''), Object.assign({ 'X-Pin': p }, h || {})), view: q => run(E, admin, '/api/admin?view=' + q, { 'X-Pin': p }) });
const teamOf = async E => (await as(E, '4821').view('team')).team;

test('weak PINs: repeated digits, runs, repeated blocks, wrong length', () => {
  for (const p of ['0000', '1111', '1234', '4321', '6789', '9876', '1212', '123123', '12341234', '123', '123456789', '12a4', '']) assert.equal(weakPin(p), true, p);
  for (const p of ['4821', '7364', '19730', '58204196', '2580']) assert.equal(weakPin(p), false, p);
});

test('set a PIN: weak and taken are refused, a good one signs them in', async () => {
  const E = env(), M = as(E, '4821');
  const add = await M.adm({ action: 'team-add', name: 'Grace Wanjiru', home: 'ke', email: 'grace@example.org', role: 'Field officer, Kitale' });
  assert.equal(add.ok, true); assert.match(add.pin, /^\d{4}$/);
  assert.equal((await M.adm({ action: 'team-set-pin', name: 'Grace Wanjiru', pin: '1234' })).reason, 'weak-pin');
  assert.equal((await M.adm({ action: 'team-set-pin', name: 'Grace Wanjiru', pin: '5555' })).reason, 'weak-pin');
  const taken = await M.adm({ action: 'team-set-pin', name: 'Grace Wanjiru', pin: '7364' }); // Meril's Cloudflare PIN
  assert.equal(taken.status, 409); assert.equal(taken.reason, 'pin-taken');
  const ok = await M.adm({ action: 'team-set-pin', name: 'Grace Wanjiru', pin: '58204' });
  assert.equal(ok.ok, true); assert.equal(ok.pin, undefined, 'a PIN typed by the admin is not sent back');
  const g = await as(E, '58204').get();
  assert.equal(g.ok, true); assert.equal(g.who, 'Grace Wanjiru');
  assert.equal((await as(E, add.pin).get()).status, 401, 'the old PIN stops working');
  // a second person can't take Grace's PIN either
  await M.adm({ action: 'team-add', name: 'Brian' });
  assert.equal((await M.adm({ action: 'team-set-pin', name: 'Brian', pin: '58204' })).reason, 'pin-taken');
  const t = await teamOf(E), gr = t.find(p => p.name === 'Grace Wanjiru');
  assert.equal(gr.role, 'Field officer, Kitale'); assert.equal(gr.src, 'app'); assert.equal(gr.blocked, null); assert.deepEqual(gr.aka, []);
  assert.equal(t.find(p => p.name === 'Meril').src, 'cloudflare');
  assert.ok(!JSON.stringify(t).includes('58204'));
  const act = await E.FEED.get('pinact', 'json');
  assert.ok(act.some(e => e.by === 'Morgan' && e.a === 'team' && e.t === 'set a PIN for Grace Wanjiru'));
});

test('block: sign-in refused with "blocked", every request refused, history kept; unblock restores the same PIN', async () => {
  const E = env(), M = as(E, '4821');
  await M.adm({ action: 'team-add', name: 'Grace' });
  await M.adm({ action: 'team-set-pin', name: 'Grace', pin: '58204' });
  const G = as(E, '58204'), s1 = await G.get('', { 'X-Pin-New': '1' });
  assert.equal(s1.ok, true); assert.equal(s1.gen, 0);
  await run(E, pin, '/api/pin', { 'X-Pin': '58204' }, { action: 'here', lat: 0.5, lng: 35.2, acc: 10 });
  assert.ok(E.FEED.m.has('pinhere:grace'));
  const b = await M.adm({ action: 'team-block', name: 'Grace', reason: 'Lost her phone' });
  assert.equal(b.ok, true); assert.equal(b.blocked.by, 'Morgan'); assert.equal(b.blocked.reason, 'Lost her phone');
  assert.ok(!E.FEED.m.has('pinhere:grace'), 'off the team map');
  // her phone (old session number) and a fresh sign-in are both refused with "blocked"
  let r = await G.get('', { 'X-Pin-Gen': '0' }); assert.equal(r.status, 401); assert.equal(r.reason, 'blocked');
  r = await G.get('', { 'X-Pin-New': '1' }); assert.equal(r.status, 401); assert.equal(r.reason, 'blocked');
  r = await run(E, pin, '/api/pin', { 'X-Pin': '58204' }, { action: 'profile', phone: '0712' }); assert.equal(r.reason, 'blocked');
  // she stays on the list, marked blocked, and isn't counted as a wrong PIN
  const t = await teamOf(E), gr = t.find(p => p.name === 'Grace');
  assert.equal(gr.blocked.reason, 'Lost her phone'); assert.equal(gr.blocked.by, 'Morgan'); assert.ok(gr.blocked.at);
  assert.equal(((await run(E, pin, '/api/pin?team=1', { 'X-Pin': '4821' })).team.find(p => p.name === 'Grace') || {}).blocked, true);
  assert.equal(E.FEED.m.get('rl:pinfail:10.0.0.' + ((ip - 1) % 250)), undefined);
  // unblock: the same PIN works again (a fresh sign-in gets the new session number)
  assert.equal((await M.adm({ action: 'team-unblock', name: 'Grace' })).ok, true);
  r = await G.get('', { 'X-Pin-New': '1' }); assert.equal(r.ok, true); assert.equal(r.gen, 1);
  assert.equal((await G.get('', { 'X-Pin-Gen': '1' })).ok, true);
  const act = await E.FEED.get('pinact', 'json');
  assert.ok(act.some(e => e.by === 'Morgan' && e.t === 'blocked Grace (Lost her phone)'));
  assert.ok(act.some(e => e.by === 'Morgan' && e.t === 'unblocked Grace'));
  // Cloudflare people can be blocked too
  assert.equal((await M.adm({ action: 'team-block', name: 'Meril' })).ok, true);
  assert.equal((await as(E, '7364').get()).reason, 'blocked');
  assert.equal((await M.adm({ action: 'team-block', name: 'Nobody Here' })).status, 404);
});

test('sign out of all phones: old session number refused with "signed-out", the PIN keeps working', async () => {
  const E = env(), M = as(E, '4821');
  await M.adm({ action: 'team-add', name: 'Grace' });
  await M.adm({ action: 'team-set-pin', name: 'Grace', pin: '58204' });
  const G = as(E, '58204');
  assert.equal((await G.get('', { 'X-Pin-Gen': '0' })).ok, true);
  const so = await M.adm({ action: 'team-signout', name: 'Grace' });
  assert.equal(so.ok, true); assert.equal(so.gen, 1);
  let r = await G.get('', { 'X-Pin-Gen': '0' }); assert.equal(r.status, 401); assert.equal(r.reason, 'signed-out');
  r = await run(E, admin, '/api/admin?view=team', { 'X-Pin': '58204', 'X-Pin-Gen': '0' }); assert.equal(r.reason, 'signed-out');
  // the PIN typed again on the lock screen: a new session with the new number
  r = await G.get('', { 'X-Pin-New': '1' }); assert.equal(r.ok, true); assert.equal(r.gen, 1);
  assert.equal((await G.get('', { 'X-Pin-Gen': '1' })).ok, true);
  assert.ok((await E.FEED.get('pinact', 'json')).some(e => e.t === 'signed Grace out of all phones'));
});

test('monday.com sessions: blocked and signed out are refused (401) and the cookie session is ended', async () => {
  const E = env({ MONDAY_CLIENT_ID: 'c', MONDAY_CLIENT_SECRET: 's'.repeat(32) }), M = as(E, '4821');
  const s1 = await tokenSession(E, 'x'.repeat(48));
  assert.ok(s1.sid); assert.equal(s1.name, 'Bertin');
  const B = h => run(E, pin, '/api/pin', Object.assign({ Cookie: 'rfpin=' + s1.sid }, h || {}));
  assert.equal((await B()).who, 'Bertin');
  await new Promise(r => setTimeout(r, 5));
  assert.equal((await M.adm({ action: 'team-signout', name: 'Bertin' })).ok, true);
  let r = await B(); assert.equal(r.status, 401); assert.equal(r.reason, 'signed-out');
  r = await B(); assert.equal(r.reason, 'signin', 'the session itself is gone');
  // signing in again after the sign-out works; blocking then refuses it
  await new Promise(r2 => setTimeout(r2, 5));
  const s2 = await tokenSession(E, 'y'.repeat(48));
  assert.equal((await run(E, pin, '/api/pin', { Cookie: 'rfpin=' + s2.sid })).who, 'Bertin');
  assert.equal((await M.adm({ action: 'team-block', name: 'Bertin' })).ok, true);
  r = await run(E, pin, '/api/pin', { Cookie: 'rfpin=' + s2.sid }); assert.equal(r.status, 401); assert.equal(r.reason, 'blocked');
});

test('rename: old name kept as aka; repair jobs, planned visits, last seen and profile follow; names in use refused', async () => {
  const E = env(), M = as(E, '4821');
  await M.adm({ action: 'team-add', name: 'Grace' });
  await M.adm({ action: 'team-set-pin', name: 'Grace', pin: '58204' });
  await E.FEED.put('pinrepairs', JSON.stringify({ '101': { t: 'Kapsaret Borehole', assignee: 'Grace', step: 'assigned', at: '2026-10-01T00:00:00Z' } }));
  await E.FEED.put('pinassign', JSON.stringify([{ aid: 'a1', id: '101', to: 'Grace', date: '2026-10-25' }, { aid: 'a2', id: '101', to: 'Grace', date: '2026-09-01', done: '2026-09-01T10:00:00Z' }]));
  await E.FEED.put('pinact', JSON.stringify([{ at: new Date().toISOString(), by: 'Grace', a: 'visit', id: '101', s: 'working' }]));
  await E.FEED.put('pinseen', JSON.stringify({ Grace: '2026-10-22T12:32:00Z' }));
  await E.FEED.put('pinprofiles', JSON.stringify({ Grace: { phone: '+254 712 000 111', home: 'ke' } }));
  assert.equal((await M.adm({ action: 'team-rename', name: 'Grace', to: 'Meril' })).reason, 'exists', 'a Cloudflare name');
  assert.equal((await M.adm({ action: 'team-rename', name: 'Grace', to: 'morgan' })).reason, 'exists');
  const r = await M.adm({ action: 'team-rename', name: 'Grace', to: 'Grace Wanjiru' });
  assert.equal(r.ok, true); assert.deepEqual(r.aka, ['Grace']); assert.equal(r.repairs, 1); assert.equal(r.visits, 1);
  assert.equal((await E.FEED.get('pinrepairs', 'json'))['101'].assignee, 'Grace Wanjiru');
  const asg = await E.FEED.get('pinassign', 'json');
  assert.equal(asg[0].to, 'Grace Wanjiru'); assert.equal(asg[1].to, 'Grace', 'done visits stay as they were');
  assert.equal((await E.FEED.get('pinact', 'json'))[0].by, 'Grace', 'history is never rewritten');
  const t = await teamOf(E), gw = t.find(p => p.name === 'Grace Wanjiru');
  assert.deepEqual(gw.aka, ['Grace']); assert.equal(gw.lastSeen, '2026-10-22T12:32:00Z');
  assert.ok(!t.some(p => p.name === 'Grace'), 'the old name is not a separate person');
  // her PIN now signs in under the new name, with her jobs; the field team list carries the old name
  const g = await as(E, '58204').get();
  assert.equal(g.who, 'Grace Wanjiru'); assert.deepEqual(g.jobs, ['101']); assert.equal(g.assigned.length, 1); assert.equal(g.me.phone, '+254 712 000 111');
  const ft = (await as(E, '58204').get('?team=1')).team.find(p => p.name === 'Grace Wanjiru');
  assert.deepEqual(ft.aka, ['Grace']);
  // and nobody else can take the old name while it's hers
  await M.adm({ action: 'team-add', name: 'Brian' });
  assert.equal((await M.adm({ action: 'team-rename', name: 'Brian', to: 'grace' })).reason, 'exists');
  // she can go back to her old name
  assert.equal((await M.adm({ action: 'team-rename', name: 'Grace Wanjiru', to: 'Grace' })).ok, true);
  assert.deepEqual((await teamOf(E)).find(p => p.name === 'Grace').aka, ['Grace Wanjiru']);
  assert.ok((await E.FEED.get('pinact', 'json')).some(e => e.by === 'Morgan' && e.t === 'renamed Grace to Grace Wanjiru'));
});

test('update: role, clear e-mail and phone, bad e-mail; only the fields sent change', async () => {
  const E = env(), M = as(E, '4821');
  await M.adm({ action: 'team-add', name: 'Grace', home: 'ke', email: 'grace@example.org', phone: '+254 712 000 111', admin: true });
  assert.equal((await M.adm({ action: 'team-update', name: 'Grace', email: 'not an email' })).reason, 'bad-email');
  assert.equal((await M.adm({ action: 'team-update', name: 'Grace', role: 'Field officer, Kitale', email: '' })).ok, true);
  let gr = (await teamOf(E)).find(p => p.name === 'Grace');
  assert.equal(gr.role, 'Field officer, Kitale'); assert.equal(gr.email, ''); assert.equal(gr.phone, '+254 712 000 111'); assert.equal(gr.home, 'ke'); assert.equal(gr.admin, true);
  assert.equal((await M.adm({ action: 'team-update', name: 'Grace', phone: '' })).ok, true);
  gr = (await teamOf(E)).find(p => p.name === 'Grace');
  assert.equal(gr.phone, ''); assert.equal(gr.admin, true);
  const act = await E.FEED.get('pinact', 'json');
  assert.ok(act.some(e => e.t === 'removed Grace’s e-mail')); assert.ok(act.some(e => e.t === 'removed Grace’s phone'));
});

test('self and last-admin protections', async () => {
  const E = env({ PIN_ADMINS: '' }), M0 = as(E, '4821');
  // nobody is an admin through Cloudflare here, so Morgan can't use the Office at all
  assert.equal((await M0.adm({ action: 'team-add', name: 'Ann' })).status, 403);
  E.PIN_ADMINS = 'Morgan';
  await M0.adm({ action: 'team-add', name: 'Ann', admin: true });
  await M0.adm({ action: 'team-set-pin', name: 'Ann', pin: '58204' });
  E.PIN_ADMINS = '';
  const A = as(E, '58204');   // Ann is now the only admin
  assert.equal((await A.adm({ action: 'team-block', name: 'Ann' })).reason, 'self');
  assert.equal((await A.adm({ action: 'team-remove', name: 'Ann' })).reason, 'self');
  assert.equal((await A.adm({ action: 'team-update', name: 'Ann', admin: false })).reason, 'self');
  // a second admin can't block, remove or demote the last one either
  await A.adm({ action: 'team-add', name: 'Bea' });
  await A.adm({ action: 'team-set-pin', name: 'Bea', pin: '19730' });
  await A.adm({ action: 'team-update', name: 'Bea', admin: true });
  const Bea = as(E, '19730');
  assert.equal((await Bea.adm({ action: 'team-block', name: 'Ann' })).ok, true, 'two admins: allowed');
  let r = await Bea.adm({ action: 'team-update', name: 'Bea', admin: false });
  assert.equal(r.reason, 'self');
  // Bea takes Ann's admin role away: Bea is now the last admin. Whoever still acts as an admin (an earlier
  // session, a monday.com admin) can't block, remove or demote her.
  assert.equal((await Bea.adm({ action: 'team-unblock', name: 'Ann' })).ok, true);
  assert.equal((await Bea.adm({ action: 'team-update', name: 'Ann', admin: false })).ok, true);
  assert.equal((await A.adm({ action: 'team-block', name: 'Bea' })).status, 403, 'Ann is no longer an admin');
  const ops = { admin: true, who: 'Ops' };
  r = await admin.teamPost(E, { action: 'team-block', name: 'Bea' }, ops, 'Ops');
  assert.equal(r.status, 409); assert.equal(r.body.reason, 'last-admin');
  assert.equal((await admin.teamPost(E, { action: 'team-update', name: 'Bea', admin: false }, ops, 'Ops')).body.reason, 'last-admin');
  assert.equal((await admin.teamPost(E, { action: 'team-remove', name: 'Bea' }, ops, 'Ops')).body.reason, 'last-admin');
  assert.equal((await admin.teamPost(E, { action: 'team-signout', name: 'Bea' }, ops, 'Ops')).body.ok, true, 'signing out is fine');
});

test('non-admins: refused, except their own name, e-mail and phone', async () => {
  const E = env(), M = as(E, '4821');
  await M.adm({ action: 'team-add', name: 'Grace', home: 'ke' });
  await M.adm({ action: 'team-set-pin', name: 'Grace', pin: '58204' });
  await M.adm({ action: 'team-add', name: 'Brian' });
  const G = as(E, '58204');
  for (const b of [{ action: 'team-block', name: 'Brian' }, { action: 'team-signout', name: 'Brian' }, { action: 'team-set-pin', name: 'Brian', pin: '19730' }, { action: 'team-pin', name: 'Grace' },
    { action: 'team-rename', name: 'Brian', to: 'B' }, { action: 'team-update', name: 'Brian', phone: '0712 345 678' }, { action: 'team-remove', name: 'Brian' }, { action: 'team-move', name: 'Meril' }, { action: 'team-add', name: 'X' }]) {
    const r = await G.adm(b); assert.equal(r.status, 403, b.action); assert.equal(r.reason, 'not-admin');
  }
  assert.equal((await run(E, admin, '/api/admin?view=team', { 'X-Pin': '58204' })).status, 403);
  // her own: e-mail and phone (admin, home and role are ignored), and her name
  assert.equal((await G.adm({ action: 'team-update', name: 'Grace', email: 'grace@example.org', phone: '+254 712 000 111', admin: true, home: 'tz', role: 'Boss' })).ok, true);
  let gr = (await teamOf(E)).find(p => p.name === 'Grace');
  assert.equal(gr.email, 'grace@example.org'); assert.equal(gr.admin, false); assert.equal(gr.home, 'ke'); assert.equal(gr.role, '');
  assert.equal((await G.adm({ action: 'team-rename', name: 'Grace', to: 'Grace Wanjiru' })).ok, true);
  assert.equal((await as(E, '58204').get()).who, 'Grace Wanjiru');
  gr = (await teamOf(E)).find(p => p.name === 'Grace Wanjiru'); assert.deepEqual(gr.aka, ['Grace']);
});

test('move a Cloudflare person to an app PIN', async () => {
  const E = env(), M = as(E, '4821');
  assert.equal((await M.adm({ action: 'team-move', name: 'Nobody' })).reason, 'not-cloudflare');
  assert.equal((await M.adm({ action: 'team-set-pin', name: 'Meril', pin: '58204' })).reason, 'cloudflare');
  const r = await M.adm({ action: 'team-move', name: 'meril' });
  assert.equal(r.ok, true); assert.match(r.pin, /^\d{4}$/); assert.notEqual(r.pin, '7364');
  const t = await teamOf(E), me = t.find(p => p.name === 'Meril');
  assert.equal(me.src, 'app'); assert.equal(me.home, 'ke'); assert.equal(me.alsoCloudflare, true);
  assert.equal((await as(E, r.pin).get()).who, 'Meril');
  assert.equal((await M.adm({ action: 'team-move', name: 'Meril' })).reason, 'exists');
  // now the app can manage her
  assert.equal((await M.adm({ action: 'team-set-pin', name: 'Meril', pin: '58204' })).ok, true);
});

test('donors: rename a viewer, change their e-mail, block and restore the same link', async () => {
  const E = env();
  const add = await donorAdminPost(E, { action: 'donor-add', name: 'Grace Church', email: 'grace@example.org', projects: ['101'] }, 'Morgan');
  const id = add.body.donor.id, vid = add.body.donor.viewers[0].vid, tok = add.body.link.split('/donor/')[1];
  let r = await donorAdminPost(E, { action: 'donor-viewer-update', id, vid, name: 'Grace Community Church', email: 'office@gracechurch.org' }, 'Morgan');
  assert.equal(r.body.ok, true); assert.equal(r.body.donor.name, 'Grace Community Church'); assert.equal(r.body.donor.email, 'office@gracechurch.org');
  assert.equal((await donorAdminPost(E, { action: 'donor-viewer-update', id, vid, email: 'nope' }, 'M')).body.reason, 'bad-email');
  assert.equal((await donorAdminPost(E, { action: 'donor-viewer-update', id, vid: 'zzz', name: 'X' }, 'M')).status, 404);
  await donorAdminPost(E, { action: 'donor-revoke', id, vid }, 'Morgan');
  assert.equal(await findByToken(E, tok), null);
  r = await donorAdminPost(E, { action: 'donor-viewer-restore', id, vid }, 'Morgan');
  assert.equal(r.body.ok, true); assert.equal(r.body.link.split('/donor/')[1], tok, 'the same link works again');
  assert.ok(await findByToken(E, tok)); assert.ok(!r.body.donor.revoked);
});
