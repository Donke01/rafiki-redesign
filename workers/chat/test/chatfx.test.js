// rafiki-chat worker: chatfx (Status, view once, locked chats, privacy, linked-device sign-out).
// Run: cd workers/chat && npm test
import { SELF, env, runInDurableObject } from 'cloudflare:test';
import { describe, it, expect } from 'vitest';
import { readPrivacy, allowed, aud } from '../src/privacy.js';
import { onceUrl } from '../src/util.js';

const b64u = s => btoa(unescape(encodeURIComponent(s))).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
const ROSTER = [
  { n: 'Amani', h: 'ke', a: 0, g: 0, b: 0, p: 'pa1' }, { n: 'Baraka', h: 'ke', a: 0, g: 0, b: 0 }, { n: 'Chiku', h: 'tz', a: 0, g: 0, b: 0 },
  { n: 'Dalia', h: 'ug', a: 0, g: 0, b: 0 }, { n: 'Ezra', h: 'ke', a: 1, g: 0, b: 0 }
];
const who = n => ROSTER.find(x => x.n === n);
const hd = (n, extra) => Object.assign({ 'X-Chat-User': encodeURIComponent(n), 'X-Chat-Admin': who(n).a ? '1' : '0', 'X-Chat-Home': who(n).h, 'X-Chat-Gen': '0', 'X-Chat-Roster': b64u(JSON.stringify(ROSTER)) }, extra || {});
async function call(n, path, body, extra) {
  const r = await SELF.fetch('https://chat/' + path, body === undefined ? { headers: hd(n, extra) } : { method: 'POST', headers: hd(n, Object.assign({ 'Content-Type': 'application/json' }, extra || {})), body: JSON.stringify(body) });
  const j = await r.json().catch(() => ({}));
  return Object.assign({ status: r.status }, j);
}
const get = (n, path, extra) => call(n, path, undefined, extra);
const post = (n, path, body, extra) => call(n, path, body, extra);
const internal = (path, body) => SELF.fetch('https://chat/' + path, { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-Chat-Roster': b64u(JSON.stringify(ROSTER)) }, body: JSON.stringify(body) }).then(r => r.json());
const dm = async (a, b) => (await post(a, 'open', { kind: 'dm', with: b })).id;
const send = (n, c, text, more) => post(n, 'send', Object.assign({ c, kind: 'text', text, cid: 'c' + Math.random().toString(36).slice(2) }, more || {}));
const msgs = async (n, c) => (await get(n, 'msgs?c=' + encodeURIComponent(c))).msgs;
const row = async (n, c) => (await get(n, 'list')).chats.find(x => x.id === c);
const dir = () => env.DIR.get(env.DIR.idFromName('directory'));
const jpeg = n => { const b = new Uint8Array(n); b.set([0xFF, 0xD8, 0xFF, 0xE0]); return b; };
const upload = async (n, q) => (await SELF.fetch('https://chat/media?' + q, { method: 'POST', headers: hd(n), body: jpeg(3000) })).json();
const fetchMedia = (url, headers) => SELF.fetch('https://chat' + url.replace('/api/chat', ''), headers ? { headers } : {});
async function socket(n, dev) {
  const t = await post(n, 'ticket', {}, dev ? { 'X-Chat-Dev': dev } : {});
  const r = await SELF.fetch('https://chat/ws?t=' + encodeURIComponent(t.t), { headers: { Upgrade: 'websocket' } });
  const ws = r.webSocket, got = [];
  let closed = null;
  ws.accept();
  ws.addEventListener('message', e => got.push(JSON.parse(e.data)));
  ws.addEventListener('close', e => { closed = e.code; });
  const wait = async (pred, ms) => { const end = Date.now() + (ms || 1500); while (Date.now() < end) { const m = got.find(pred); if (m) return m; await new Promise(r => setTimeout(r, 10)); } return null; };
  return { ws, got, wait, closed: () => closed, waitClose: async () => { const end = Date.now() + 1500; while (closed == null && Date.now() < end) await new Promise(r => setTimeout(r, 10)); return closed; } };
}
const setPriv = (n, priv) => internal('people', { list: [{ n, priv }] });

describe('privacy rules', () => {
  it('reads settings safely and decides who may see what', () => {
    expect(readPrivacy(null)).toMatchObject({ lastSeen: { mode: 'all' }, readReceipts: true, groupAdd: 'all' });
    expect(readPrivacy({ status: { mode: 'none' } }).status.mode).toBe('all'); // status has no "Nobody"
    expect(aud({ mode: 'only', names: ['Amani', 'amani ', 'Baraka'] })).toEqual({ mode: 'only', names: ['Amani', 'Baraka'] });
    expect(allowed({ mode: 'except', names: ['Baraka'] }, 'amani', 'baraka')).toBe(false);
    expect(allowed({ mode: 'only', names: ['Baraka'] }, 'amani', 'Baraka')).toBe(true);
    expect(allowed({ mode: 'none', names: [] }, 'amani', 'amani')).toBe(true);
    expect(allowed({ mode: 'none', names: [] }, 'amani', 'baraka')).toBe(false);
  });
});

describe('internal routes', () => {
  it('refuses /people, /kick, /sys, /sync and /key with a signed-in person', async () => {
    for (const p of ['people', 'kick', 'sys', 'sync', 'key']) expect((await post('Amani', p, {})).status).toBe(404);
  });
});

describe('status', () => {
  it('text status: seen by the team, unseen first, viewed, liked, viewers list for the poster only, deleted', async () => {
    await get('Ezra', 'list');
    const sb = await socket('Baraka');
    const p = await post('Amani', 'status', { kind: 'text', text: 'Drilling at Turbo Spring today 💧', color: '#0a78c2', place: 'Turbo', cid: 's1' });
    expect(p.ok).toBe(true);
    expect(p.status).toMatchObject({ kind: 'text', color: '#0A78C2', mine: true, views: 0, privacy: { mode: 'all', names: [] }, place: 'Turbo' });
    expect((await post('Amani', 'status', { kind: 'text', text: 'Drilling at Turbo Spring today 💧', cid: 's1' })).status.id).toBe(p.status.id); // same cid: no copy
    expect(await sb.wait(m => m.t === 'status' && m.who === 'Amani' && m.id === p.status.id)).toBeTruthy();
    const l = await get('Baraka', 'status');
    expect(l.people[0]).toMatchObject({ name: 'Baraka', me: true, items: [] });
    const a = l.people.find(x => x.name === 'Amani');
    expect(a).toMatchObject({ unseen: 1, photo: '/api/team-photo?name=Amani&v=pa1' });
    expect(a.items[0]).toMatchObject({ id: p.status.id, seen: false, liked: false, canReshare: true, text: 'Drilling at Turbo Spring today 💧' });
    expect(a.items[0].privacy).toBeUndefined();
    const sa = await socket('Amani');
    expect((await post('Baraka', 'status-view', { id: p.status.id })).n).toBe(1);
    expect(await sa.wait(m => m.t === 'status-view' && m.who === 'Baraka' && !m.liked)).toBeTruthy();
    expect((await post('Baraka', 'status-like', { id: p.status.id, on: true })).liked).toBe(true);
    expect(await sa.wait(m => m.t === 'status-view' && m.who === 'Baraka' && m.liked)).toBeTruthy();
    expect((await get('Baraka', 'status')).people.find(x => x.name === 'Amani')).toMatchObject({ unseen: 0 });
    const v = await get('Amani', 'status-viewers?id=' + p.status.id);
    expect(v).toMatchObject({ views: 1, likes: 1, reshares: 0 });
    expect(v.viewers[0]).toMatchObject({ name: 'Baraka', liked: true, reshared: false });
    expect((await get('Baraka', 'status-viewers?id=' + p.status.id)).reason).toBe('not-yours');
    expect((await post('Baraka', 'status-delete', { id: p.status.id })).reason).toBe('not-yours');
    expect((await post('Amani', 'status-delete', { id: p.status.id })).ok).toBe(true);
    expect(await sb.wait(m => m.t === 'status-gone' && m.id === p.status.id)).toBeTruthy();
    expect((await get('Baraka', 'status')).people.find(x => x.name === 'Amani')).toBeUndefined();
    sa.ws.close(); sb.ws.close();
  });
  it('privacy: everyone except / only these people, and the default from settings', async () => {
    const ex = await post('Chiku', 'status', { kind: 'text', text: 'Not for Dalia', privacy: { mode: 'except', names: ['Dalia'] } });
    const only = await post('Chiku', 'status', { kind: 'text', text: 'Only Amani', privacy: { mode: 'only', names: ['amani'] } });
    const ids = n => get(n, 'status').then(j => (j.people.find(x => x.name === 'Chiku') || { items: [] }).items.map(i => i.id));
    expect(await ids('Dalia')).toEqual([]);
    expect(await ids('Baraka')).toEqual([ex.status.id]);
    expect((await ids('Amani')).sort()).toEqual([ex.status.id, only.status.id].sort());
    expect((await post('Dalia', 'status-view', { id: ex.status.id })).n).toBe(0);
    expect((await post('Dalia', 'status-like', { id: ex.status.id })).status).toBe(404);
    expect((await post('Chiku', 'status', { kind: 'text', text: 'x', privacy: { mode: 'none' } })).reason).toBe('bad-request');
    await setPriv('Chiku', { status: { mode: 'only', names: ['Baraka'] } });
    const d = await post('Chiku', 'status', { kind: 'text', text: 'Default audience' });
    expect(d.status.privacy).toEqual({ mode: 'only', names: ['Baraka'] });
    expect(await ids('Amani')).not.toContain(d.status.id);
    await setPriv('Chiku', {});
    for (const s of [ex, only, d]) await post('Chiku', 'status-delete', { id: s.status.id });
  });
  it('reshare: only Whole-team statuses, marked via, and shown to the original poster', async () => {
    const pub = await post('Dalia', 'status', { kind: 'text', text: 'Water flowing again', color: '#E8862A' });
    const priv = await post('Dalia', 'status', { kind: 'text', text: 'Small group', privacy: { mode: 'except', names: ['Chiku'] } });
    expect((await post('Amani', 'status', { reshareOf: priv.status.id })).reason).toBe('private');
    expect((await post('Dalia', 'status', { reshareOf: pub.status.id })).reason).toBe('own');
    const r = await post('Amani', 'status', { reshareOf: pub.status.id });
    expect(r.status).toMatchObject({ kind: 'text', text: 'Water flowing again', color: '#E8862A', via: { name: 'Dalia' } });
    expect((await get('Dalia', 'status-viewers?id=' + pub.status.id)).viewers[0]).toMatchObject({ name: 'Amani', reshared: true });
    expect((await get('Baraka', 'status')).people.find(x => x.name === 'Amani').items[0].via).toEqual({ name: 'Dalia' });
    for (const [n, s] of [['Dalia', pub], ['Dalia', priv], ['Amani', r]]) await post(n, 'status-delete', { id: s.status.id });
  });
  it('photo status: only files uploaded for a status, served while it lives, gone after it ends', async () => {
    const plain = await upload('Baraka', 'type=photo');
    expect((await post('Baraka', 'status', { kind: 'photo', media: { id: plain.id } })).reason).toBe('media');
    const up = await upload('Baraka', 'type=photo&for=status');
    expect(up).toMatchObject({ ok: true, for: 'status' });
    expect((await post('Amani', 'status', { kind: 'photo', media: { id: up.id } })).reason).toBe('media'); // not Amani's file
    expect((await SELF.fetch('https://chat/media?type=video&for=status&dur=31', { method: 'POST', headers: hd('Baraka'), body: jpeg(10) })).status).toBe(413);
    const s = await post('Baraka', 'status', { kind: 'photo', media: { id: up.id, w: 800, h: 600 }, caption: 'Committee chair came to see it' });
    expect(s.status.media).toMatchObject({ type: 'photo', w: 800, h: 600 });
    const url = (await get('Amani', 'status')).people.find(x => x.name === 'Baraka').items[0].media.url;
    expect((await fetchMedia(url)).status).toBe(200);
    expect((await SELF.fetch('https://chat/media/' + up.id, { headers: hd('Amani') })).status).toBe(200);
    // a reply in the 1:1 chat quotes it (and only there)
    const c = await dm('Amani', 'Baraka');
    const rep = await send('Amani', c, 'Great news!', { statusRef: s.status.id });
    expect(rep.msg.statusRef).toMatchObject({ id: s.status.id, from: 'Baraka', kind: 'photo', text: 'Committee chair came to see it' });
    expect(rep.msg.statusRef.thumbUrl).toContain(up.id);
    expect(rep.msg.status).toBe('sent');
    expect((await send('Amani', await dm('Amani', 'Chiku'), 'x', { statusRef: s.status.id })).reason).toBe('not-poster');
    // it ends (24 h later): the file stops being served and is deleted
    await runInDurableObject(dir(), async inst => { inst.sql.exec('UPDATE statuses SET exp = ? WHERE id = ?', Date.now() - 1, s.status.id); await inst.alarm(); });
    expect((await fetchMedia(url)).status).toBe(404);
    expect(await env.CHAT_MEDIA.head('m/' + up.id)).toBe(null);
    expect((await get('Amani', 'status')).people.find(x => x.name === 'Baraka')).toBeUndefined();
  });
});

describe('view once', () => {
  it('no file, link or caption in lists; opened once per receiver; the sender is told; then refused', async () => {
    const c = await dm('Chiku', 'Dalia');
    const up = await upload('Chiku', 'type=photo&for=once');
    expect(up.url).toBe('');
    expect((await post('Dalia', 'send', { c, kind: 'media', once: true, media: [{ id: up.id, type: 'photo' }] })).reason).toBe('media'); // not hers
    expect((await post('Chiku', 'send', { c, kind: 'media', once: true, media: [{ id: up.id, type: 'photo', thumb: up.id }] })).reason).toBe('bad-request');
    const s = await post('Chiku', 'send', { c, kind: 'media', once: true, text: 'secret caption', media: [{ id: up.id, type: 'photo', w: 10, h: 10 }] });
    expect(s.msg).toMatchObject({ once: true, opened: false, text: '', media: [{ type: 'photo' }] });
    const seen = (await msgs('Dalia', c)).pop();
    expect(seen).toMatchObject({ once: true, opened: false, text: '', media: [{ type: 'photo' }] });
    expect(JSON.stringify(seen)).not.toContain(up.id);
    expect((await row('Dalia', c)).last).toMatchObject({ kind: 'media', text: '', media: 'photo', once: true });
    expect((await get('Dalia', 'search?q=secret')).hits).toEqual([]);
    // the file can't be fetched with a day link or as a signed-in person
    expect((await SELF.fetch('https://chat/media/' + up.id, { headers: hd('Dalia') })).status).toBe(404);
    expect((await post('Dalia', 'star', { c, id: s.msg.id, on: true })).reason).toBe('once');
    expect((await post('Chiku', 'edit', { c, id: s.msg.id, text: 'x' })).reason).toBe('once');
    expect((await post('Chiku', 'open-once', { c, id: s.msg.id })).reason).toBe('own');
    const sc = await socket('Chiku');
    const o = await post('Dalia', 'open-once', { c, id: s.msg.id });
    expect(o).toMatchObject({ ok: true, text: 'secret caption', media: { type: 'photo', w: 10, h: 10, mime: 'image/jpeg' } });
    expect(o.media.url).toMatch(/&o=1&s=/);
    expect((await fetchMedia(o.media.url)).status).toBe(200);
    expect(await sc.wait(m => m.t === 'opened' && m.id === s.msg.id && m.who === 'Dalia')).toBeTruthy();
    expect((await post('Dalia', 'open-once', { c, id: s.msg.id })).reason).toBe('opened');
    expect((await msgs('Dalia', c)).pop().opened).toBe(true);
    expect((await msgs('Chiku', c)).pop().opened).toBe(true);
    // everyone opened it: the file is deleted a little later
    await runInDurableObject(dir(), async inst => { inst.sql.exec('UPDATE purge SET at = 0'); await inst.alarm(); });
    expect(await env.CHAT_MEDIA.head('m/' + up.id)).toBe(null);
    sc.ws.close();
  });
  it('a view-once link is short-lived and opens only view-once files; push text is just "📷 Photo"', async () => {
    const plain = await upload('Chiku', 'type=photo');
    const key = await runInDurableObject(dir(), inst => inst.key());
    const forged = await onceUrl(key, plain.id);
    expect((await fetchMedia(forged.url)).status).toBe(404);
    const c = await dm('Chiku', 'Amani'), up = await upload('Chiku', 'type=video&dur=5&for=once');
    const s = await post('Chiku', 'send', { c, kind: 'media', once: true, text: 'hidden', media: [{ id: up.id, type: 'video', dur: 5 }] });
    await runInDurableObject(dir(), inst => {
      const p = inst.pushPlan(inst.chat(c), Object.assign({}, s.msg, { fk: 'chiku', text: 'hidden', once: true, media: [{ id: up.id, type: 'video' }] }), []);
      // no device registered: plan the data line directly
      expect(p).toEqual([]);
    });
    await post('Amani', 'device', { token: 'tok-amani-' + 'y'.repeat(40), platform: 'android' });
    await runInDurableObject(dir(), inst => {
      const p = inst.pushPlan(inst.chat(c), Object.assign({}, s.msg, { fk: 'chiku', text: 'hidden', once: true, media: [{ id: up.id, type: 'video' }] }), []);
      expect(p[0].data).toMatchObject({ text: '', body: '🎥 Video', media: 'video' });
    });
  });
});

describe('locked chats', () => {
  it('per person, in the list rows, synced to my devices, and pushes say only "New message"', async () => {
    const c = await dm('Amani', 'Dalia');
    await send('Dalia', c, 'Private thing');
    const s = await socket('Amani');
    const l = await post('Amani', 'lock', { c, on: true });
    expect(l.locked).toContain(c);
    expect(l.chat.locked).toBe(true);
    expect(await s.wait(m => m.t === 'locked' && m.locked.includes(c))).toBeTruthy();
    expect((await row('Amani', c)).locked).toBe(true);
    expect((await row('Dalia', c)).locked).toBe(false);
    expect((await get('Amani', 'locked')).locked).toContain(c);
    await post('Amani', 'device', { token: 'tok-amani-' + 'z'.repeat(40), platform: 'android' });
    s.ws.close();
    const m = await send('Dalia', c, 'Are you there?');
    await runInDurableObject(dir(), inst => {
      const p = inst.pushPlan(inst.chat(c), Object.assign({}, m.msg, { fk: 'dalia' }), []).filter(x => x.k === 'amani');
      expect(p.length).toBeGreaterThan(0);
      expect(p[0].data).toMatchObject({ locked: '1', title: 'RAFIKI', from: '', text: '', body: 'New message' });
    });
    expect((await post('Amani', 'locked', { ids: [] })).locked).toEqual([]);
    expect((await row('Amani', c)).locked).toBe(false);
    expect((await post('Amani', 'locked', { ids: 'x' })).reason).toBe('bad-request');
  });
});

describe('privacy enforcement', () => {
  it('last seen both ways, profile photo, and group adds', async () => {
    await get('Baraka', 'list');
    const sb = await socket('Baraka');
    await setPriv('Amani', { lastSeen: { mode: 'except', names: ['Baraka'] }, photo: { mode: 'none' } });
    const pb = (await get('Baraka', 'list')).presence;
    expect(pb.Amani).toEqual({ online: false, seen: null, hidden: true });
    // symmetric: Amani doesn't see Baraka's either
    expect((await get('Amani', 'list')).presence.Baraka).toEqual({ online: false, seen: null, hidden: true });
    expect((await get('Chiku', 'list')).presence.Amani.hidden).toBeUndefined();
    expect((await get('Chiku', 'list')).presence.Amani.photo).toBeUndefined();
    expect((await get('Amani', 'list')).presence.Amani.photo).toBe('/api/team-photo?name=Amani&v=pa1');
    // a socket opening doesn't leak "online" to Baraka
    const sa = await socket('Amani');
    const ev = await sb.wait(m => m.t === 'presence' && m.who === 'Amani' && m.online === true, 400);
    expect(ev).toBe(null);
    sa.ws.close(); sb.ws.close();
    await setPriv('Amani', {});
    expect((await get('Baraka', 'list')).presence.Amani.hidden).toBeUndefined();
    // who can add me to groups: admins only
    await setPriv('Dalia', { groupAdd: 'admins' });
    const g = await post('Baraka', 'group', { title: 'Borehole crew', members: ['Dalia', 'Chiku'] });
    expect(g.refused).toEqual(['Dalia']);
    expect((await row('Baraka', g.id)).members.sort()).toEqual(['Baraka', 'Chiku']);
    expect((await post('Baraka', 'members', { c: g.id, add: ['Dalia'] })).refused).toEqual(['Dalia']);
    const g2 = await post('Ezra', 'group', { title: 'Office crew', members: ['Dalia'] });
    expect(g2.refused).toBeUndefined();
    await setPriv('Dalia', {});
  });
  it('read receipts off: my reads are not shown, and I see no blue ticks in 1:1 chats', async () => {
    const c = await dm('Chiku', 'Baraka');
    await setPriv('Baraka', { readReceipts: false });
    const sc = await socket('Chiku');
    const m = await send('Chiku', c, 'Did you see this?');
    await post('Baraka', 'read', { c, upTo: m.msg.id });
    expect(await sc.wait(x => x.t === 'seen' && x.c === c, 400)).toBe(null);
    expect((await msgs('Chiku', c)).pop().status).toBe('delivered');
    expect((await row('Baraka', c)).unread).toBe(0);
    // Chiku reads Baraka's message: Baraka still sees only delivered, and no 'seen' row
    const m2 = await send('Baraka', c, 'Yes');
    await post('Chiku', 'read', { c, upTo: m2.msg.id });
    expect((await msgs('Baraka', c)).pop().status).not.toBe('read');
    expect((await row('Baraka', c)).seen).toEqual([]);
    expect((await get('Baraka', 'info?c=' + encodeURIComponent(c) + '&id=' + m2.msg.id)).read).toEqual([]);
    // back on: reads count again
    await setPriv('Baraka', {});
    const m3 = await send('Chiku', c, 'And now?');
    await post('Baraka', 'read', { c, upTo: m3.msg.id });
    expect(await sc.wait(x => x.t === 'seen' && x.c === c && x.upTo === m3.msg.id)).toBeTruthy();
    expect((await msgs('Chiku', c)).pop().status).toBe('read');
    sc.ws.close();
  });
});

describe('linked devices', () => {
  it('signing out one device closes its sockets and forgets its push tokens; "all others" keeps this one', async () => {
    const A = 'devAAAAAAAAAAAAAAAA', B = 'devBBBBBBBBBBBBBBBB';
    const sa = await socket('Ezra', A), sb = await socket('Ezra', B);
    await post('Ezra', 'device', { token: 'tok-ezra-a-' + 'x'.repeat(40), platform: 'android' }, { 'X-Chat-Dev': A });
    await post('Ezra', 'device', { token: 'tok-ezra-b-' + 'x'.repeat(40), platform: 'web' }, { 'X-Chat-Dev': B });
    expect((await internal('kick', { name: 'Ezra', dev: B })).closed).toBe(1);
    expect(await sb.waitClose()).toBe(4401);
    expect(sa.closed()).toBe(null);
    const toks = () => runInDurableObject(dir(), inst => inst.q("SELECT token, dev, gen FROM devices WHERE k = 'ezra'"));
    expect((await toks()).map(t => t.dev)).toEqual([A]);
    // all others: keep A (with the new session number 3), close the rest
    const sc = await socket('Ezra', 'devCCCCCCCCCCCCCCCC');
    expect((await internal('kick', { name: 'Ezra', keep: A, gen: 3 })).closed).toBe(1);
    expect(await sc.waitClose()).toBe(4401);
    expect((await toks())[0].gen).toBe(3);
    // the roster then says gen 3: device A's socket is not cut off by it
    ROSTER.find(x => x.n === 'Ezra').g = 3;
    await get('Ezra', 'list');
    await new Promise(r => setTimeout(r, 100));
    expect(sa.closed()).toBe(null);
    ROSTER.find(x => x.n === 'Ezra').g = 0;
    sa.ws.close();
  });
});
