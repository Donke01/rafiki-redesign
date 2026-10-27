// rafiki-chat worker: the chat polish round (Announcements, pins, per-user settings, "seen by", trip cards,
// photo thumbnails, push devices and FCM). Run: cd workers/chat && npm test
import { SELF, env, runInDurableObject } from 'cloudflare:test';
import { describe, it, expect } from 'vitest';
import { inDnd, makeJwt, message, account } from '../src/fcm.js';
import { tripCard } from '../src/directory.js';

const b64u = s => btoa(unescape(encodeURIComponent(s))).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
const ROSTER = [
  { n: 'Grace', h: 'ke', a: 0, g: 0, b: 0 }, { n: 'Meril', h: 'ke', a: 1, g: 0, b: 0 }, { n: 'Bertin', h: 'tz', a: 0, g: 0, b: 0 },
  { n: 'Jessica', h: '', a: 1, g: 0, b: 0 }, { n: 'Otieno', h: 'ke', a: 0, g: 0, b: 0 }, { n: 'Faith', h: 'ug', a: 0, g: 0, b: 0 }
];
const who = n => ROSTER.find(x => x.n === n) || { n, h: '', a: 0, g: 0 };
const hd = (n, extra) => Object.assign({ 'X-Chat-User': encodeURIComponent(n), 'X-Chat-Admin': who(n).a ? '1' : '0', 'X-Chat-Home': who(n).h || '', 'X-Chat-Gen': String(who(n).g || 0), 'X-Chat-Roster': b64u(JSON.stringify(ROSTER)) }, extra || {});
async function call(n, path, body) {
  const r = await SELF.fetch('https://chat/' + path, body === undefined ? { headers: hd(n) } : { method: 'POST', headers: hd(n, { 'Content-Type': 'application/json' }), body: JSON.stringify(body) });
  const j = await r.json().catch(() => ({}));
  return Object.assign({ status: r.status }, j);
}
const get = (n, path) => call(n, path);
const post = (n, path, body) => call(n, path, body);
const send = (n, c, text, more) => post(n, 'send', Object.assign({ c, kind: 'text', text, cid: 'c' + Math.random().toString(36).slice(2) }, more || {}));
const row = async (n, c) => (await get(n, 'list')).chats.find(x => x.id === c);
const msgs = async (n, c) => (await get(n, 'msgs?c=' + encodeURIComponent(c))).msgs;
const dir = () => env.DIR.get(env.DIR.idFromName('directory'));
async function socket(n) {
  const t = await post(n, 'ticket', {});
  const r = await SELF.fetch('https://chat/ws?t=' + encodeURIComponent(t.t), { headers: { Upgrade: 'websocket' } });
  const ws = r.webSocket, got = [];
  ws.accept();
  ws.addEventListener('message', e => got.push(JSON.parse(e.data)));
  return { ws, got, wait: async (pred, ms) => { const end = Date.now() + (ms || 2000); while (Date.now() < end) { const m = got.find(pred); if (m) return m; await new Promise(r => setTimeout(r, 10)); } return null; } };
}
const group = async (by, members, title) => (await post(by, 'group', { title: title || 'Pump crew', members })).id;
const TOK = n => 'tok-' + n + '-' + 'x'.repeat(40);

describe('Announcements', () => {
  it('everyone is in it, it is marked, and only admins can post', async () => {
    const r = await row('Grace', 't:news');
    expect(r).toMatchObject({ kind: 'team', title: 'Announcements', announce: true, onlyAdmins: true, country: '' });
    expect(r.members.sort()).toEqual(ROSTER.map(x => x.n).sort());
    expect(r.admins.sort()).toEqual(['Jessica', 'Meril']);
    expect((await send('Grace', 't:news', 'hello')).reason).toBe('only-admins');
    expect((await send('Meril', 't:news', 'Staff meeting Friday 9:00')).ok).toBe(true);
    expect((await post('Grace', 'leave', { c: 't:news' })).reason).toBe('team');
    await post('Meril', 'group-update', { c: 't:news', onlyAdmins: false });
    expect((await row('Grace', 't:news')).onlyAdmins).toBe(true);
    expect((await row('Grace', 't:ke')).country).toBe('ke');
    expect((await row('Grace', 't:all')).announce).toBeUndefined();
  });
});

describe('per-person settings (prefs without c)', () => {
  it('has defaults, keeps quick replies (max 8), DND and the double-tap emoji, and sends them to my other devices', async () => {
    const d = await get('Faith', 'prefs');
    expect(d.prefs).toEqual({ quick: null, dnd: { on: false, from: '21:00', to: '06:30', mentions: false, tz: 180 }, doubleTap: '❤️' });
    const s = await socket('Faith');
    const p = await post('Faith', 'prefs', { quick: ['On my way', 'Nimefika'], dnd: { on: true, from: '22:00', mentions: true }, doubleTap: '👍' });
    expect(p.prefs).toEqual({ quick: ['On my way', 'Nimefika'], dnd: { on: true, from: '22:00', to: '06:30', mentions: true, tz: 180 }, doubleTap: '👍' });
    expect((await s.wait(m => m.t === 'prefs')).prefs.doubleTap).toBe('👍');
    expect((await get('Faith', 'list')).prefs.quick).toEqual(['On my way', 'Nimefika']);
    expect((await post('Faith', 'prefs', { quick: Array(9).fill('x') })).reason).toBe('bad-request');
    expect((await post('Faith', 'prefs', { quick: ['y'.repeat(61)] })).reason).toBe('bad-request');
    expect((await post('Faith', 'prefs', { dnd: { from: '25:00' } })).reason).toBe('bad-request');
    expect((await post('Faith', 'prefs', { doubleTap: 'lol' })).reason).toBe('bad-request');
    expect((await post('Faith', 'prefs', { quick: null })).prefs.quick).toBe(null);
    s.ws.close();
  });
  it('bubble colour per chat, per person', async () => {
    const c = (await post('Faith', 'open', { kind: 'dm', with: 'Bertin' })).id;
    await send('Faith', c, 'Habari');
    expect((await post('Faith', 'prefs', { c, bubble: 'teal', wallpaper: 'night' })).chat).toMatchObject({ bubble: 'teal', wallpaper: 'night' });
    expect((await post('Faith', 'prefs', { c, bubble: 'Not A Token!' })).reason).toBe('bad-request');
    expect((await row('Bertin', c)).bubble).toBe('');
  });
  it('Do-Not-Disturb hours, across midnight and in local time', () => {
    const at = (h, m) => Date.UTC(2026, 9, 27, h, m) - 180 * 60000; // h:m in Nairobi
    const dnd = { on: true, from: '21:00', to: '06:30', tz: 180 };
    expect(inDnd(dnd, at(22, 0))).toBe(true);
    expect(inDnd(dnd, at(3, 0))).toBe(true);
    expect(inDnd(dnd, at(6, 30))).toBe(false);
    expect(inDnd(dnd, at(12, 0))).toBe(false);
    expect(inDnd({ on: true, from: '13:00', to: '14:00', tz: 180 }, at(13, 30))).toBe(true);
    expect(inDnd(Object.assign({}, dnd, { on: false }), at(22, 0))).toBe(false);
  });
});

describe('pinned messages', () => {
  it('group admins pin (up to 3, the oldest goes), with a system line; others cannot', async () => {
    const c = await group('Grace', ['Otieno', 'Bertin'], 'Pins');
    const ids = [];
    for (const t of ['one', 'two', 'three', 'four']) ids.push((await send('Otieno', c, t)).msg.id);
    expect((await post('Otieno', 'pin', { c, id: ids[0], on: true })).reason).toBe('not-admin');
    const p1 = await post('Grace', 'pin', { c, id: ids[0], on: true });
    expect(p1.ok).toBe(true);
    expect(p1.pins).toMatchObject([{ id: ids[0], from: 'Otieno', text: 'one', by: 'Grace' }]);
    const m = await msgs('Bertin', c);
    expect(m[m.length - 1]).toMatchObject({ kind: 'sys', text: 'Grace pinned a message', sys: { type: 'pinned', by: 'Grace', id: ids[0] } });
    await post('Grace', 'pin', { c, id: ids[1], on: true });
    await post('Grace', 'pin', { c, id: ids[2], on: true });
    const p4 = await post('Grace', 'pin', { c, id: ids[3], on: true });
    expect(p4.pins.map(x => x.id)).toEqual([ids[3], ids[2], ids[1]]);
    expect(p4.replaced).toBe(ids[0]);
    expect((await row('Bertin', c)).pins.map(x => x.id)).toEqual([ids[3], ids[2], ids[1]]);
    expect((await post('Grace', 'pin', { c, id: m[m.length - 1].id, on: true })).reason).toBe('bad-request');
    // unpin, edit and delete keep the bar right
    expect((await post('Grace', 'pin', { c, id: ids[2], on: false })).pins.map(x => x.id)).toEqual([ids[3], ids[1]]);
    await post('Otieno', 'edit', { c, id: ids[3], text: 'four (edited)' });
    expect((await row('Grace', c)).pins[0].text).toBe('four (edited)');
    await post('Otieno', 'delete', { c, id: ids[3], forAll: true });
    expect((await row('Grace', c)).pins.map(x => x.id)).toEqual([ids[1]]);
  });
  it('anyone can pin in a DM', async () => {
    const c = (await post('Otieno', 'open', { kind: 'dm', with: 'Faith' })).id;
    const s = await send('Faith', c, 'Gate code 4411');
    expect((await post('Otieno', 'pin', { c, id: s.msg.id, on: true })).pins.length).toBe(1);
  });
});

describe('"seen by" and @mention ids', () => {
  it('lists who has read the last message, most recent first, and tells the others live', async () => {
    const c = await group('Meril', ['Grace', 'Otieno', 'Bertin'], 'Seen');
    const s = await send('Meril', c, 'Pump fixed ✅');
    expect((await row('Meril', c)).seen).toEqual([]);
    const ws = await socket('Meril');
    await post('Grace', 'read', { c, upTo: s.msg.id });
    expect(await ws.wait(m => m.t === 'seen' && m.c === c && m.who === 'Grace')).toMatchObject({ upTo: s.msg.id });
    await new Promise(r => setTimeout(r, 3));
    await post('Otieno', 'read', { c, upTo: s.msg.id });
    expect((await row('Meril', c)).seen).toEqual(['Otieno', 'Grace']);
    expect((await row('Grace', c)).seen).toEqual(['Otieno']);
    await send('Bertin', c, 'Great');
    expect((await row('Meril', c)).seen).toEqual([]);
    ws.ws.close();
  });
  it('mentionId is my oldest unseen mention', async () => {
    const c = await group('Meril', ['Grace', 'Otieno'], 'Mentions');
    const a = await send('Meril', c, '@Grace look', { mentions: ['Grace'] });
    await send('Meril', c, '@Grace again', { mentions: ['Grace'] });
    expect((await row('Grace', c)).mentionId).toBe(a.msg.id);
    expect((await row('Otieno', c)).mentionId).toBe(0);
  });
});

describe('trip cards and photo thumbnails', () => {
  const card = { id: 'it_abc12345', title: 'Kapsaret loop', from: '2026-10-27', to: '2026-10-28', status: 'active', next: { name: 'Chepkongi', date: '2026-10-27', at: '10:30' }, done: 2, total: 5 };
  it('sends kind trip with the card; the list shows the title', async () => {
    const c = await group('Grace', ['Otieno'], 'Trips');
    const s = await post('Grace', 'send', { c, cid: 'trip-1', kind: 'trip', trip: card });
    expect(s.ok).toBe(true);
    expect(s.msg).toMatchObject({ kind: 'trip', trip: Object.assign({}, card, { access: true }) });
    expect((await row('Otieno', c)).last).toMatchObject({ kind: 'trip', text: 'Kapsaret loop' });
    expect((await post('Grace', 'send', { c, kind: 'trip', trip: { id: 'nope' } })).reason).toBe('bad-request');
    expect((await msgs('Otieno', c)).find(m => m.id === s.msg.id).trip.next).toEqual({ name: 'Chepkongi', date: '2026-10-27', at: '10:30' });
    // text messages carry no trip field
    expect('trip' in (await send('Grace', c, 'plain')).msg).toBe(false);
  });
  it('cleans the card', () => {
    expect(tripCard({ id: 'it_abc12345', title: '', from: 'x', status: 'weird', done: 9, total: 3, next: { name: 'A', at: '99:00' } }))
      .toEqual({ id: 'it_abc12345', title: 'Trip', from: '', to: '', status: 'planned', next: { name: 'A', date: '', at: null }, done: 3, total: 3, access: true });
  });
  it('keeps a photo thumbnail and signs its link', async () => {
    const c = await group('Grace', ['Otieno'], 'Thumbs');
    const s = await post('Grace', 'send', { c, kind: 'media', media: [{ id: 'A'.repeat(24), type: 'photo', w: 1600, h: 1200, size: 900000, thumb: 'B'.repeat(24) }] });
    expect(s.msg.media[0].thumb).toBe('B'.repeat(24));
    expect(s.msg.media[0].thumbUrl).toMatch(/^\/api\/chat\/media\/BBBB+\?e=\d+&s=/);
  });
});

describe('push devices and FCM', () => {
  it('registers and removes a device token; list says whether push is on', async () => {
    expect((await post('Bertin', 'device', { token: 'short', platform: 'android' })).reason).toBe('bad-request');
    expect((await post('Bertin', 'device', { token: TOK('b'), platform: 'ios' })).reason).toBe('bad-request');
    expect((await post('Bertin', 'device', { token: TOK('b'), platform: 'android' })).ok).toBe(true);
    expect((await get('Bertin', 'list')).push).toBe(false);
    await runInDurableObject(dir(), inst => { expect(inst.q('SELECT k FROM devices WHERE token = ?', TOK('b'))[0].k).toBe('bertin'); });
    expect((await post('Bertin', 'device', { token: TOK('b'), remove: true })).ok).toBe(true);
    await runInDurableObject(dir(), inst => { expect(inst.q('SELECT k FROM devices WHERE token = ?', TOK('b')).length).toBe(0); });
  });
  it('plans pushes for offline members only, respecting mute, DND, mentions and sign-outs', async () => {
    const c = await group('Jessica', ['Grace', 'Otieno', 'Faith', 'Bertin'], 'Push crew');
    for (const n of ['Grace', 'Otieno', 'Faith', 'Bertin']) await post(n, 'device', { token: TOK(n), platform: 'android' });
    await post('Otieno', 'prefs', { c, muted: 'always' });
    await post('Faith', 'prefs', { dnd: { on: true, from: '00:00', to: '23:59', mentions: false } });
    const online = await socket('Bertin');
    const s = await send('Jessica', c, 'Truck leaves at 7', { mentions: ['Otieno'] });
    const s2 = await send('Jessica', c, 'No mentions here');
    await runInDurableObject(dir(), inst => {
      const ch = inst.chat(c), m1 = Object.assign({}, s.msg, { fk: 'jessica' }), m2 = Object.assign({}, s2.msg, { fk: 'jessica' });
      // Otieno is muted but mentioned; Faith is in DND; Bertin is online; Jessica sent it
      expect(inst.pushPlan(ch, m1, ['otieno']).map(p => p.k).sort()).toEqual(['grace', 'otieno']);
      const p = inst.pushPlan(ch, m2, []);
      expect(p.map(p => p.k)).toEqual(['grace']);
      expect(p[0].data).toMatchObject({ t: 'msg', c, id: s2.msg.id, from: 'Jessica', title: 'Push crew', kind: 'text', text: 'No mentions here', mention: '0', body: 'Jessica: No mentions here' });
      expect(p[0].data.unread).toBeGreaterThan(0);
      // group housekeeping lines never push; project ones do
      expect(inst.pushPlan(ch, { id: 99, fk: '', from: '', kind: 'sys', text: 'x', sys: { type: 'added' } }, [])).toEqual([]);
      expect(inst.pushPlan(ch, { id: 99, fk: '', from: '', kind: 'sys', text: 'Repair reported', sys: { type: 'repair' } }, []).map(p => p.k)).toEqual(['grace']);
      // signed out of every phone after registering: that token is skipped
      inst.sql.exec("UPDATE users SET gen = 5 WHERE k = 'grace'");
      expect(inst.pushPlan(ch, m2, []).map(p => p.k)).toEqual([]);
      inst.sql.exec("UPDATE users SET gen = 0 WHERE k = 'grace'");
    });
    // Faith lets mentions through DND
    await post('Faith', 'prefs', { dnd: { mentions: true } });
    await runInDurableObject(dir(), inst => { expect(inst.pushPlan(inst.chat(c), Object.assign({}, s.msg, { fk: 'jessica' }), ['faith']).map(p => p.k).sort()).toEqual(['faith', 'grace']); });
    online.ws.close();
  });
  it('sends through the FCM HTTP v1 API with a service-account token, and forgets dead tokens', async () => {
    const kp = await crypto.subtle.generateKey({ name: 'RSASSA-PKCS1-v1_5', modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: 'SHA-256' }, true, ['sign', 'verify']);
    const der = new Uint8Array(await crypto.subtle.exportKey('pkcs8', kp.privateKey));
    const pem = '-----BEGIN PRIVATE KEY-----\n' + btoa(String.fromCharCode(...der)).replace(/.{64}/g, '$&\n') + '\n-----END PRIVATE KEY-----\n';
    const sa = { type: 'service_account', project_id: 'rafiki-test', client_email: 'push@rafiki-test.iam.gserviceaccount.com', private_key: pem, token_uri: 'https://oauth2.googleapis.com/token' };
    const fenv = { FCM_PROJECT_ID: 'rafiki-test', FCM_SERVICE_ACCOUNT: JSON.stringify(sa) };
    expect(account(fenv).client_email).toBe(sa.client_email);
    expect(account({ FCM_SERVICE_ACCOUNT: btoa(JSON.stringify(sa)) }).client_email).toBe(sa.client_email);
    // the JWT verifies with the public key
    const jwt = await makeJwt(sa, Date.UTC(2026, 9, 27));
    const [h, p, sig] = jwt.split('.'), un = s => Uint8Array.from(atob(s.replace(/-/g, '+').replace(/_/g, '/') + '==='.slice((s.length + 3) % 4)), c => c.charCodeAt(0));
    expect(JSON.parse(new TextDecoder().decode(un(p)))).toMatchObject({ iss: sa.client_email, scope: 'https://www.googleapis.com/auth/firebase.messaging', aud: 'https://oauth2.googleapis.com/token' });
    expect(await crypto.subtle.verify('RSASSA-PKCS1-v1_5', kp.publicKey, un(sig), new TextEncoder().encode(h + '.' + p))).toBe(true);

    const c = await group('Meril', ['Grace', 'Otieno'], 'FCM');
    await post('Grace', 'device', { token: TOK('g2'), platform: 'android' });
    await post('Otieno', 'device', { token: TOK('dead'), platform: 'web' });
    const s = await send('Meril', c, 'Pump fixed ✅');
    const calls = [];
    await runInDurableObject(dir(), async inst => {
      Object.defineProperty(inst, 'env', { value: Object.assign({}, inst.env, fenv), configurable: true });
      inst.fcmFetch = async (url, init) => {
        calls.push({ url, init });
        if (url === 'https://oauth2.googleapis.com/token') return Response.json({ access_token: 'ya29.test', expires_in: 3600 });
        const b = JSON.parse(init.body);
        if (b.message.token === TOK('dead')) return Response.json({ error: { code: 404, status: 'NOT_FOUND', details: [{ errorCode: 'UNREGISTERED' }] } }, { status: 404 });
        return Response.json({ name: 'projects/rafiki-test/messages/1' });
      };
      const n = await inst.push(inst.chat(c), Object.assign({}, s.msg, { fk: 'meril' }), []);
      expect(n).toBeGreaterThanOrEqual(2);
      expect(inst.q('SELECT token FROM devices WHERE token = ?', TOK('dead')).length).toBe(0);
      expect(inst.q('SELECT token FROM devices WHERE token = ?', TOK('g2')).length).toBe(1);
    });
    const tokenCall = calls.find(x => x.url.includes('oauth2'));
    expect(tokenCall.init.body).toMatch(/^grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer&assertion=ey/);
    const sends = calls.filter(x => x.url === 'https://fcm.googleapis.com/v1/projects/rafiki-test/messages:send');
    expect(sends.every(x => x.init.headers.Authorization === 'Bearer ya29.test')).toBe(true);
    const android = sends.map(x => JSON.parse(x.init.body).message).find(m => m.token === TOK('g2'));
    expect(android.android).toEqual({ priority: 'high', collapse_key: c, ttl: '86400s' });
    expect(android.data).toMatchObject({ c, from: 'Meril', text: 'Pump fixed ✅', id: String(s.msg.id) });
    expect(Object.values(android.data).every(v => typeof v === 'string')).toBe(true);
    expect(android.notification).toBeUndefined();
  });
  it('web tokens get a notification block that opens the chat', () => {
    const m = message({ token: 't', platform: 'web' }, { c: 'g:1', title: 'Crew', body: 'Meril: hi' });
    expect(m.webpush.notification).toMatchObject({ title: 'Crew', body: 'Meril: hi', tag: 'g:1' });
    expect(m.webpush.fcm_options.link).toBe('/pin#chat/g%3A1');
  });
});
