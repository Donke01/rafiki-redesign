// rafiki-chat worker tests (vitest + @cloudflare/vitest-pool-workers: real Durable Objects, SQLite, R2 and WebSockets in workerd).
// Run: cd workers/chat && npm test
import { SELF, env, runInDurableObject } from 'cloudflare:test';
import { describe, it, expect } from 'vitest';

const b64u = s => btoa(unescape(encodeURIComponent(s))).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
const TEAM = [
  { n: 'Grace', h: 'ke', a: 0, g: 0, b: 0 }, { n: 'Meril', h: 'ke', a: 1, g: 0, b: 0 }, { n: 'Bertin', h: 'tz', a: 0, g: 0, b: 0 },
  { n: 'Jessica', h: '', a: 1, g: 0, b: 0 }, { n: 'Otieno', h: 'ke', a: 0, g: 0, b: 0 }, { n: 'Faith', h: 'ug', a: 0, g: 0, b: 0 }
];
let ROSTER = TEAM.map(x => Object.assign({}, x));
const who = n => ROSTER.find(x => x.n === n) || { n, h: '', a: 0, g: 0 };
const hd = (n, extra) => Object.assign({ 'X-Chat-User': encodeURIComponent(n), 'X-Chat-Admin': who(n).a ? '1' : '0', 'X-Chat-Home': who(n).h || '', 'X-Chat-Gen': String(who(n).g || 0), 'X-Chat-Roster': b64u(JSON.stringify(ROSTER)) }, extra || {});
async function call(n, path, body, extra) {
  const r = await SELF.fetch('https://chat/' + path, body === undefined ? { headers: hd(n, extra) } : { method: 'POST', headers: hd(n, Object.assign({ 'Content-Type': 'application/json' }, extra)), body: JSON.stringify(body) });
  const j = await r.json().catch(() => ({}));
  return Object.assign({ status: r.status }, j);
}
const get = (n, path) => call(n, path);
const post = (n, path, body) => call(n, path, body);
const send = (n, c, text, more) => post(n, 'send', Object.assign({ c, kind: 'text', text, cid: 'c' + Math.random().toString(36).slice(2) }, more || {}));
const row = async (n, c) => (await get(n, 'list')).chats.find(x => x.id === c);
const dirStub = () => env.DIR.get(env.DIR.idFromName('directory'));

// a WebSocket for a person, with the messages it receives
async function socket(n) {
  const t = await post(n, 'ticket', {});
  const r = await SELF.fetch('https://chat/ws?t=' + encodeURIComponent(t.t), { headers: { Upgrade: 'websocket' } });
  expect(r.status).toBe(101);
  const ws = r.webSocket, got = [];
  let closed = null;
  ws.accept();
  ws.addEventListener('message', e => got.push(JSON.parse(e.data)));
  ws.addEventListener('close', e => { closed = e.code; });
  return { ws, got, closed: () => closed, wait: async (pred, ms) => { const end = Date.now() + (ms || 2000); while (Date.now() < end) { const m = got.find(pred); if (m) return m; await new Promise(r => setTimeout(r, 10)); } return null; } };
}
const until = async (f, ms) => { const end = Date.now() + (ms || 2000); while (Date.now() < end) { if (await f()) return true; await new Promise(r => setTimeout(r, 10)); } return false; };

describe('team chats and the list', () => {
  it('makes All RAFIKI and one chat per country, members from the team list, Office admins as admins', async () => {
    const j = await get('Grace', 'list');
    expect(j.ok).toBe(true);
    expect(j.me).toBe('Grace');
    const all = j.chats.find(c => c.id === 't:all'), ke = j.chats.find(c => c.id === 't:ke');
    expect(all.title).toBe('All RAFIKI');
    expect(all.kind).toBe('team');
    expect(all.members.sort()).toEqual(TEAM.map(x => x.n).sort());
    expect(all.admins.sort()).toEqual(['Jessica', 'Meril']);
    expect(ke.members.sort()).toEqual(['Grace', 'Meril', 'Otieno']);
    expect(j.chats.some(c => c.id === 't:tz')).toBe(false);
    expect(typeof j.now).toBe('number');
    expect(j.presence.Bertin).toEqual({ online: false, seen: null });
  });
  it('requires a name', async () => {
    const r = await SELF.fetch('https://chat/list');
    expect(r.status).toBe(403);
    expect((await r.json()).reason).toBe('no-name');
  });
  it('team chats cannot be left or have members changed', async () => {
    expect((await post('Grace', 'leave', { c: 't:all' })).reason).toBe('team');
    expect((await post('Meril', 'members', { c: 't:ke', add: ['Bertin'] })).reason).toBe('team');
  });
});

describe('direct messages: send, list, unread, receipts', () => {
  let c;
  it('opens the same DM from either side', async () => {
    const a = await post('Grace', 'open', { kind: 'dm', with: 'Bertin' }), b = await post('Bertin', 'open', { kind: 'dm', with: 'grace' });
    expect(a.ok).toBe(true);
    expect(a.id).toBe(b.id);
    c = a.id;
    expect((await post('Grace', 'open', { kind: 'dm', with: 'Nobody' })).status).toBe(404);
    expect((await post('Grace', 'open', { kind: 'dm', with: 'Grace' })).status).toBe(404);
  });
  it('sends; the list shows the last message and unread count; read and delivered receipts move the ticks', async () => {
    const s = await send('Grace', c, 'Habari Bertin');
    expect(s.ok).toBe(true);
    expect(s.msg).toMatchObject({ c, from: 'Grace', kind: 'text', text: 'Habari Bertin', status: 'sent', deleted: false, edited: null, reacts: {} });
    expect(typeof s.msg.id).toBe('number');
    const s2 = await send('Grace', c, 'Are you at Kapsaret?');
    const rb = await row('Bertin', c);
    expect(rb.title).toBe('Grace');
    expect(rb.unread).toBe(2);
    expect(rb.last).toMatchObject({ id: s2.msg.id, from: 'Grace', kind: 'text', text: 'Are you at Kapsaret?' });
    expect((await row('Grace', c)).unread).toBe(0);
    await post('Bertin', 'delivered', { c, upTo: s2.msg.id });
    let m = await get('Grace', 'msgs?c=' + encodeURIComponent(c));
    expect(m.msgs.map(x => x.status)).toEqual(['delivered', 'delivered']);
    await post('Bertin', 'read', { c, upTo: s.msg.id });
    expect((await row('Bertin', c)).unread).toBe(1);
    m = await get('Grace', 'msgs?c=' + encodeURIComponent(c));
    expect(m.msgs.map(x => x.status)).toEqual(['read', 'delivered']);
    await post('Bertin', 'read', { c, upTo: s2.msg.id });
    expect((await row('Bertin', c)).unread).toBe(0);
    const info = await get('Grace', 'info?c=' + encodeURIComponent(c) + '&id=' + s.msg.id);
    expect(info.read.map(x => x.name)).toEqual(['Bertin']);
    expect(info.delivered.map(x => x.name)).toEqual(['Bertin']);
    expect((await get('Bertin', 'info?c=' + encodeURIComponent(c) + '&id=' + s.msg.id)).reason).toBe('not-yours');
  });
  it('list?since gives only changed chats', async () => {
    const t = (await get('Grace', 'list')).now;
    await new Promise(r => setTimeout(r, 5));
    await send('Bertin', c, 'Yes, here now');
    const j = await get('Grace', 'list?since=' + t);
    expect(j.chats.map(x => x.id)).toEqual([c]);
    expect(j.gone).toEqual([]);
  });
  it('re-sending the same cid returns the same message', async () => {
    const a = await post('Grace', 'send', { c, cid: 'offline-1', kind: 'text', text: 'Sent from the bus' });
    const b = await post('Grace', 'send', { c, cid: 'offline-1', kind: 'text', text: 'Sent from the bus' });
    expect(b.msg.id).toBe(a.msg.id);
    const m = await get('Grace', 'msgs?c=' + encodeURIComponent(c));
    expect(m.msgs.filter(x => x.cid === 'offline-1').length).toBe(1);
  });
  it('pages older messages with before, newer with after', async () => {
    for (let i = 0; i < 6; i++) await send('Grace', c, 'n' + i);
    const p1 = await get('Grace', 'msgs?c=' + encodeURIComponent(c) + '&limit=4');
    expect(p1.msgs.length).toBe(4);
    expect(p1.more).toBe(true);
    expect(p1.msgs.map(x => x.text)).toEqual(['n2', 'n3', 'n4', 'n5']);
    const p2 = await get('Grace', 'msgs?c=' + encodeURIComponent(c) + '&limit=4&before=' + p1.msgs[0].id);
    expect(p2.msgs[p2.msgs.length - 1].text).toBe('n1');
    const p3 = await get('Grace', 'msgs?c=' + encodeURIComponent(c) + '&after=' + p1.msgs[1].id);
    expect(p3.msgs.map(x => x.text)).toEqual(['n4', 'n5']);
  });
  it('outsiders cannot read or send', async () => {
    expect((await get('Otieno', 'msgs?c=' + encodeURIComponent(c))).reason).toBe('not-member');
    expect((await send('Otieno', c, 'hi')).reason).toBe('not-member');
  });
});

describe('edit, delete, reactions, star, search', () => {
  let c;
  it('setup', async () => { c = (await post('Grace', 'open', { kind: 'dm', with: 'Otieno' })).id; });
  it('edits own messages within 15 minutes only', async () => {
    const s = await send('Grace', c, 'Pump hadle broken');
    const e = await post('Grace', 'edit', { c, id: s.msg.id, text: 'Pump handle broken' });
    expect(e.ok).toBe(true);
    expect(e.msg.text).toBe('Pump handle broken');
    expect(typeof e.msg.edited).toBe('number');
    expect((await post('Otieno', 'edit', { c, id: s.msg.id, text: 'x' })).reason).toBe('not-yours');
    await runInDurableObject(env.CONV.get(env.CONV.idFromName(c)), (inst, state) => { state.storage.sql.exec('UPDATE msgs SET at = at - 16 * 60000 WHERE id = ?', s.msg.id); });
    const late = await post('Grace', 'edit', { c, id: s.msg.id, text: 'too late' });
    expect(late.status).toBe(409);
    expect(late.reason).toBe('edit-window');
  });
  it('deletes for everyone (own only in a DM) and for me', async () => {
    const s = await send('Grace', c, 'wrong chat');
    expect((await post('Otieno', 'delete', { c, id: s.msg.id, forAll: true })).reason).toBe('not-yours');
    expect((await post('Grace', 'delete', { c, id: s.msg.id, forAll: true })).ok).toBe(true);
    const m = (await get('Otieno', 'msgs?c=' + encodeURIComponent(c))).msgs.find(x => x.id === s.msg.id);
    expect(m.deleted).toBe(true);
    expect(m.text).toBe('');
    expect((await row('Otieno', c)).last.deleted).toBe(true);
    const s2 = await send('Otieno', c, 'only hide this for Grace');
    expect((await post('Grace', 'delete', { c, id: s2.msg.id, forAll: false })).ok).toBe(true);
    expect((await get('Grace', 'msgs?c=' + encodeURIComponent(c))).msgs.some(x => x.id === s2.msg.id)).toBe(false);
    expect((await get('Otieno', 'msgs?c=' + encodeURIComponent(c))).msgs.some(x => x.id === s2.msg.id)).toBe(true);
  });
  it('reacts, changes and removes a reaction', async () => {
    const s = await send('Grace', c, 'Fixed!');
    expect((await post('Otieno', 'react', { c, id: s.msg.id, emoji: '👍' })).reacts).toEqual({ '👍': ['Otieno'] });
    expect((await post('Grace', 'react', { c, id: s.msg.id, emoji: '🙏' })).reacts).toEqual({ '👍': ['Otieno'], '🙏': ['Grace'] });
    expect((await post('Otieno', 'react', { c, id: s.msg.id, emoji: '❤️' })).reacts).toEqual({ '🙏': ['Grace'], '❤️': ['Otieno'] });
    expect((await post('Otieno', 'react', { c, id: s.msg.id, emoji: null })).reacts).toEqual({ '🙏': ['Grace'] });
    expect((await post('Otieno', 'react', { c, id: s.msg.id, emoji: 'lol' })).reason).toBe('bad-request');
  });
  it('stars and lists starred; searches message text in my chats only', async () => {
    const s = await send('Otieno', c, 'The borehole at Chepkongi needs a new cylinder');
    expect((await post('Grace', 'star', { c, id: s.msg.id, on: true })).ok).toBe(true);
    const st = await get('Grace', 'starred');
    expect(st.msgs.map(x => x.text)).toEqual(['The borehole at Chepkongi needs a new cylinder']);
    await post('Grace', 'star', { c, id: s.msg.id, on: false });
    expect((await get('Grace', 'starred')).msgs).toEqual([]);
    const h = await get('Grace', 'search?q=chepkongi');
    expect(h.hits.length).toBe(1);
    expect(h.hits[0]).toMatchObject({ c, id: s.msg.id, from: 'Otieno' });
    expect(h.hits[0].snippet).toContain('Chepkongi');
    expect((await get('Bertin', 'search?q=chepkongi')).hits).toEqual([]);
  });
  it('mentions set the @ flag until read', async () => {
    await send('Grace', 't:ke', 'Morning team');
    const s = await send('Grace', 't:ke', '@Otieno can you check?', { mentions: ['Otieno'] });
    expect(s.msg.mentions).toEqual(['Otieno']);
    let r = await row('Otieno', 't:ke');
    expect(r.mention).toBe(true);
    expect((await row('Meril', 't:ke')).mention).toBe(false);
    await post('Otieno', 'read', { c: 't:ke', upTo: s.msg.id });
    r = await row('Otieno', 't:ke');
    expect(r.mention).toBe(false);
    expect(r.unread).toBe(0);
  });
  it('chat settings: pinned, muted, archived, wallpaper', async () => {
    const p = await post('Grace', 'prefs', { c, pinned: true, muted: 'always', archived: true, wallpaper: 'savanna' });
    expect(p.chat).toMatchObject({ pinned: true, muted: 'always', archived: true, wallpaper: 'savanna' });
    const until = Date.now() + 8 * 3600e3;
    expect((await post('Grace', 'prefs', { c, muted: until })).chat.muted).toBe(until);
    expect((await post('Grace', 'prefs', { c, muted: 0, archived: false })).chat).toMatchObject({ muted: 0, archived: false, pinned: true });
    expect((await row('Otieno', c)).pinned).toBe(false);
  });
  it('location, live location (updates, stop), contact card', async () => {
    expect((await post('Grace', 'send', { c, kind: 'location', loc: { lat: 0.5143, lng: 35.2698 } })).msg.loc).toEqual({ lat: 0.5143, lng: 35.2698 });
    expect((await post('Grace', 'send', { c, kind: 'location', loc: { lat: 91, lng: 0 } })).reason).toBe('bad-request');
    const lv = await post('Grace', 'send', { c, kind: 'live', loc: { lat: 0.5, lng: 35.2 }, live: { until: Date.now() + 15 * 60e3 } });
    expect(lv.msg.live.until).toBeGreaterThan(Date.now());
    expect((await post('Grace', 'send', { c, kind: 'live', loc: { lat: 0.5, lng: 35.2 }, live: { until: Date.now() + 9 * 3600e3 } })).reason).toBe('bad-request');
    const u = await post('Grace', 'live', { c, id: lv.msg.id, lat: 0.51, lng: 35.21 });
    expect(u.live).toMatchObject({ lat: 0.51, lng: 35.21 });
    expect((await post('Otieno', 'live', { c, id: lv.msg.id, lat: 1, lng: 1 })).reason).toBe('not-yours');
    const st = await post('Grace', 'live-stop', { c, id: lv.msg.id });
    expect(st.live.until).toBeLessThanOrEqual(Date.now());
    expect((await post('Grace', 'live', { c, id: lv.msg.id, lat: 0.52, lng: 35.22 })).reason).toBe('ended');
    expect((await post('Grace', 'send', { c, kind: 'contact', contact: { name: 'meril' } })).msg.contact).toEqual({ name: 'Meril' });
    expect((await post('Grace', 'send', { c, kind: 'contact', contact: { name: 'A Stranger' } })).reason).toBe('bad-request');
  });
  it('replies quote the original', async () => {
    const o = await send('Otieno', c, 'Which part?');
    const r = await send('Grace', c, 'The cylinder', { reply: o.msg.id });
    expect(r.msg.reply).toEqual({ id: o.msg.id, from: 'Otieno', kind: 'text', text: 'Which part?' });
  });
});

describe('groups', () => {
  let g, link;
  it('creates a group; the creator is admin', async () => {
    const r = await post('Grace', 'group', { title: 'Kapsaret repair crew', members: ['Otieno', 'Bertin', 'Unknown Person'], description: 'Pump repair, Oct' });
    expect(r.ok).toBe(true);
    g = r.id;
    const row1 = await row('Otieno', g);
    expect(row1).toMatchObject({ kind: 'group', title: 'Kapsaret repair crew', description: 'Pump repair, Oct', admins: ['Grace'] });
    expect(row1.members.sort()).toEqual(['Bertin', 'Grace', 'Otieno']);
    expect(row1.last.kind).toBe('sys');
  });
  it('only admins change the group and its members', async () => {
    expect((await post('Otieno', 'group-update', { c: g, title: 'Mine now' })).reason).toBe('not-admin');
    expect((await post('Otieno', 'members', { c: g, add: ['Faith'] })).reason).toBe('not-admin');
    expect((await post('Grace', 'group-update', { c: g, title: 'Kapsaret crew' })).chat.title).toBe('Kapsaret crew');
    const m = await post('Grace', 'members', { c: g, add: ['Faith'], admin: ['Otieno'], remove: ['Bertin'] });
    expect(m.chat.members.sort()).toEqual(['Faith', 'Grace', 'Otieno']);
    expect(m.chat.admins.sort()).toEqual(['Grace', 'Otieno']);
    expect((await get('Bertin', 'msgs?c=' + encodeURIComponent(g))).reason).toBe('not-member');
    const since = (await get('Bertin', 'list')).now - 5000;
    expect((await get('Bertin', 'list?since=' + since)).gone).toContain(g);
  });
  it('only admins can send when "Only admins can send messages" is on', async () => {
    await post('Grace', 'group-update', { c: g, onlyAdmins: true });
    expect((await send('Faith', g, 'hello?')).reason).toBe('only-admins');
    expect((await send('Otieno', g, 'Admins only for now')).ok).toBe(true);
    // Office admins count as admins everywhere
    await post('Grace', 'members', { c: g, add: ['Jessica'] });
    expect((await send('Jessica', g, 'Office here')).ok).toBe(true);
    await post('Grace', 'group-update', { c: g, onlyAdmins: false });
    expect((await send('Faith', g, 'hello!')).ok).toBe(true);
  });
  it('admins (and Office admins) can delete anyone\'s message for everyone in a group', async () => {
    const s = await send('Faith', g, 'oops');
    expect((await post('Grace', 'delete', { c: g, id: s.msg.id, forAll: true })).ok).toBe(true);
    const s2 = await send('Faith', g, 'oops again');
    expect((await post('Jessica', 'delete', { c: g, id: s2.msg.id, forAll: true })).ok).toBe(true);
    const s3 = await send('Otieno', g, 'mine');
    expect((await post('Faith', 'delete', { c: g, id: s3.msg.id, forAll: true })).reason).toBe('not-yours');
  });
  it('join by link; admins reset it', async () => {
    const l = await post('Faith', 'link', { c: g });
    expect(l.status).toBe(403); // no link yet and Faith isn't an admin
    const a = await post('Grace', 'link', { c: g });
    expect(a.url).toBe('/pin#chat/join/' + a.token);
    link = a.token;
    expect((await post('Faith', 'link', { c: g })).token).toBe(link);
    const j = await post('Bertin', 'join', { token: link });
    expect(j.id).toBe(g);
    expect((await row('Bertin', g)).members).toContain('Bertin');
    expect((await post('Faith', 'link', { c: g, reset: true })).reason).toBe('not-admin');
    const r = await post('Grace', 'link', { c: g, reset: true });
    expect(r.token).not.toBe(link);
    expect((await post('Meril', 'join', { token: link })).status).toBe(404);
    expect((await post('Meril', 'join', { token: r.token })).ok).toBe(true);
  });
  it('leaving hands admin on when the last admin goes', async () => {
    const r = await post('Grace', 'group', { title: 'Two of us', members: ['Faith'] });
    expect((await post('Grace', 'leave', { c: r.id })).ok).toBe(true);
    expect((await row('Faith', r.id)).admins).toEqual(['Faith']);
  });
});

describe('project threads and system messages', () => {
  it('opens a project thread and posts system lines into it', async () => {
    const o = await post('Grace', 'open', { kind: 'project', project: '101', ptitle: 'Kapsaret Borehole' });
    expect(o.id).toBe('p:101');
    const r = await SELF.fetch('https://chat/sys', { method: 'POST', body: JSON.stringify({ project: '101', title: 'Kapsaret Borehole', sys: { type: 'assigned', to: 'Otieno', by: 'Meril' }, text: 'Repair assigned to Otieno by Meril.', add: ['Otieno'] }) });
    expect((await r.json()).ok).toBe(true);
    const ro = await row('Otieno', 'p:101');
    expect(ro).toMatchObject({ kind: 'project', project: '101', title: 'Kapsaret Borehole', unread: 1 });
    const m = (await get('Grace', 'msgs?c=p%3A101')).msgs;
    expect(m[m.length - 1]).toMatchObject({ kind: 'sys', from: '', text: 'Repair assigned to Otieno by Meril.', sys: { type: 'assigned', project: '101', to: 'Otieno', by: 'Meril' } });
    // a new project thread from a system line alone
    await SELF.fetch('https://chat/sys', { method: 'POST', body: JSON.stringify({ project: '202', title: 'Ndalat School', sys: { type: 'sms', state: 'notworking' }, text: 'Community report by SMS: not working.' }) });
    const p = await post('Bertin', 'open', { kind: 'project', project: '202' });
    expect((await get('Bertin', 'msgs?c=' + encodeURIComponent(p.id))).msgs[0].sys.type).toBe('sms');
  });
});

describe('media', () => {
  const jpeg = n => { const b = new Uint8Array(n); b.set([0xFF, 0xD8, 0xFF, 0xE0]); return b; };
  const up = (n, body, q, extra) => SELF.fetch('https://chat/media?' + q, { method: 'POST', headers: hd(n, extra), body });
  it('stores a photo, serves it by signed link and with headers, with Range support', async () => {
    const r = await up('Grace', jpeg(5000), 'type=photo&name=pump.jpg');
    const j = await r.json();
    expect(j.ok).toBe(true);
    expect(j.mime).toBe('image/jpeg');
    expect(j.url).toMatch(/^\/api\/chat\/media\/[A-Za-z0-9_-]+\?e=\d+&s=/);
    const signed = await SELF.fetch('https://chat' + j.url.replace('/api/chat', ''));
    expect(signed.status).toBe(200);
    expect(signed.headers.get('Content-Type')).toBe('image/jpeg');
    expect((await signed.arrayBuffer()).byteLength).toBe(5000);
    const bad = await SELF.fetch('https://chat/media/' + j.id + '?e=' + (Date.now() + 1e6) + '&s=forged');
    expect(bad.status).toBe(404);
    const ranged = await SELF.fetch('https://chat/media/' + j.id, { headers: hd('Bertin', { Range: 'bytes=0-99' }) });
    expect(ranged.status).toBe(206);
    expect(ranged.headers.get('Content-Range')).toBe('bytes 0-99/5000');
    expect((await ranged.arrayBuffer()).byteLength).toBe(100);
    // in a message, each media item gets its own signed url
    const c = (await post('Grace', 'open', { kind: 'dm', with: 'Faith' })).id;
    const s = await post('Grace', 'send', { c, kind: 'media', text: 'The new pump', media: [{ id: j.id, type: 'photo', w: 1200, h: 900 }] });
    expect(s.msg.media[0]).toMatchObject({ id: j.id, type: 'photo', w: 1200, h: 900 });
    expect(s.msg.media[0].url).toContain(j.id);
  });
  it('multipart uploads work too', async () => {
    const fd = new FormData();
    fd.append('type', 'doc');
    fd.append('file', new File([new TextEncoder().encode('%PDF-1.4 test')], 'report.pdf', { type: 'application/pdf' }));
    const h = hd('Grace'); delete h['Content-Type'];
    const r = await SELF.fetch('https://chat/media', { method: 'POST', headers: h, body: fd });
    const j = await r.json();
    expect(j.ok).toBe(true);
    expect(j.mime).toBe('application/pdf');
    const d = await SELF.fetch('https://chat/media/' + j.id, { headers: hd('Grace') });
    expect(d.headers.get('Content-Disposition')).toContain('report.pdf');
  });
  it('enforces the size caps, lengths and file types', async () => {
    const big = await up('Grace', jpeg(2 * 1024 * 1024 + 1), 'type=photo');
    expect(big.status).toBe(413);
    expect((await big.json()).reason).toBe('too-large');
    const notPhoto = await up('Grace', new TextEncoder().encode('<svg onload=alert(1)>'), 'type=photo');
    expect(notPhoto.status).toBe(415);
    const vid = new Uint8Array(1000); vid.set(new TextEncoder().encode('\0\0\0\x18ftypmp42'));
    expect((await up('Grace', vid, 'type=video&dur=61')).status).toBe(413);
    expect((await up('Grace', vid, 'type=video&dur=30')).status).toBe(200);
    const ogg = new Uint8Array(100); ogg.set(new TextEncoder().encode('OggS'));
    expect((await (await up('Grace', ogg, 'type=voice&dur=301')).json()).reason).toBe('too-long');
    const voice = await (await up('Grace', ogg, 'type=voice&dur=12')).json();
    expect(voice.mime).toBe('audio/ogg');
    expect((await up('Grace', new Uint8Array(10 * 1024 * 1024 + 1), 'type=doc&name=x.pdf')).status).toBe(413);
    expect((await up('Grace', jpeg(10), 'type=exe')).status).toBe(400);
    // a voice note of more than 5 minutes is refused at send too
    const c = (await post('Grace', 'open', { kind: 'dm', with: 'Faith' })).id;
    expect((await post('Grace', 'send', { c, kind: 'voice', media: [{ id: voice.id, type: 'voice', dur: 400 }] })).reason).toBe('too-long');
    const ok = await post('Grace', 'send', { c, kind: 'voice', media: [{ id: voice.id, type: 'voice', dur: 12, wave: [1, 50, 99, 120] }] });
    expect(ok.msg.media[0].wave).toEqual([1, 50, 99, 100]);
  });
});

describe('WebSockets', () => {
  it('fans out messages, typing, receipts and presence to the chat members', async () => {
    const c = (await post('Meril', 'open', { kind: 'dm', with: 'Otieno' })).id;
    const a = await socket('Meril'), b = await socket('Otieno'), x = await socket('Faith');
    expect(await a.wait(m => m.t === 'hello')).toMatchObject({ me: 'Meril' });
    expect(await a.wait(m => m.t === 'presence' && m.who === 'Otieno' && m.online)).toBeTruthy();
    expect((await get('Meril', 'list')).presence.Otieno.online).toBe(true);
    b.ws.send(JSON.stringify({ t: 'typing', c, what: 'recording' }));
    expect(await a.wait(m => m.t === 'typing')).toEqual({ t: 'typing', c, who: 'Otieno', what: 'recording' });
    const s = await send('Meril', c, 'Live test');
    const got = await b.wait(m => m.t === 'msg' && m.msg.id === s.msg.id);
    expect(got.msg.text).toBe('Live test');
    expect(await b.wait(m => m.t === 'chat' && m.chat.id === c && m.chat.unread === 1)).toBeTruthy();
    await post('Otieno', 'read', { c, upTo: s.msg.id });
    expect(await a.wait(m => m.t === 'status' && m.c === c && m.status === 'read' && m.upTo === s.msg.id)).toBeTruthy();
    await post('Otieno', 'react', { c, id: s.msg.id, emoji: '😂' });
    expect(await a.wait(m => m.t === 'react')).toMatchObject({ c, id: s.msg.id, reacts: { '😂': ['Otieno'] } });
    await post('Meril', 'edit', { c, id: s.msg.id, text: 'Live test!' });
    expect(await b.wait(m => m.t === 'edit')).toMatchObject({ c, id: s.msg.id, text: 'Live test!' });
    // Faith isn't in this chat and hears nothing of it
    await new Promise(r => setTimeout(r, 50));
    expect(x.got.some(m => m.c === c || (m.msg && m.msg.c === c))).toBe(false);
    a.ws.send(JSON.stringify({ t: 'ping' }));
    expect(await a.wait(m => m.t === 'pong')).toBeTruthy();
    b.ws.close(1000);
    expect(await a.wait(m => m.t === 'presence' && m.who === 'Otieno' && m.online === false)).toBeTruthy();
    a.ws.close(1000); x.ws.close(1000);
  });
  it('refuses a forged or stale ticket', async () => {
    const t = await post('Grace', 'ticket', {});
    const forged = t.t.split('.')[0] + '.AAAA';
    const r = await SELF.fetch('https://chat/ws?t=' + forged, { headers: { Upgrade: 'websocket' } });
    expect(r.status).toBe(401);
  });
});

describe('blocked and removed teammates', () => {
  it('a blocked teammate is refused and their open phone is disconnected at once', async () => {
    const f = await socket('Faith');
    ROSTER = TEAM.map(x => Object.assign({}, x, x.n === 'Faith' ? { b: 1 } : {}));
    // anyone's next request carries the new team list
    const r = await get('Grace', 'list');
    expect(r.chats.find(c => c.id === 't:all').members).not.toContain('Faith');
    expect(await until(() => f.closed() === 4401)).toBe(true);
    expect((await get('Faith', 'list')).status).toBe(401);
    ROSTER = TEAM.map(x => Object.assign({}, x));
    await get('Grace', 'list');
  });
  it('someone taken off the team list leaves the Team chats and is disconnected; /sync does it without a user request', async () => {
    const o = await socket('Otieno');
    ROSTER = TEAM.filter(x => x.n !== 'Otieno').map(x => Object.assign({}, x));
    const res = await SELF.fetch('https://chat/sync', { method: 'POST', headers: { 'X-Chat-Roster': b64u(JSON.stringify(ROSTER)) }, body: '{}' });
    expect((await res.json()).ok).toBe(true);
    expect(await until(() => o.closed() === 4401)).toBe(true);
    expect((await row('Grace', 't:ke')).members).not.toContain('Otieno');
    ROSTER = TEAM.map(x => Object.assign({}, x));
    await get('Grace', 'list');
  });
  it('"sign out of all phones" (a new session number) disconnects old sockets and refuses old tickets', async () => {
    const t = await post('Bertin', 'ticket', {});
    const b = await socket('Bertin');
    ROSTER = TEAM.map(x => Object.assign({}, x, x.n === 'Bertin' ? { g: 1 } : {}));
    await get('Grace', 'list');
    expect(await until(() => b.closed() === 4401)).toBe(true);
    const r = await SELF.fetch('https://chat/ws?t=' + encodeURIComponent(t.t), { headers: { Upgrade: 'websocket' } });
    expect(r.status).toBe(401);
    ROSTER = TEAM.map(x => Object.assign({}, x));
  });
});

describe('signing', () => {
  it('keeps one signing key in the directory', async () => {
    const k1 = await dirStub().key(), k2 = await dirStub().key();
    expect(k1).toBe(k2);
    expect(k1.length).toBeGreaterThan(30);
  });
});
