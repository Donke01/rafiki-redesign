// rafiki-chat worker: the chat2 room round (select + delete many, Clear chat / Delete chat, a push for the people added).
// Run: cd workers/chat && npm test
import { SELF, env, runInDurableObject } from 'cloudflare:test';
import { describe, it, expect } from 'vitest';

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
const group = async (by, members, title) => (await post(by, 'group', { title: title || 'Pump crew', members })).id;
const TOK = n => 'tok-' + n + '-' + 'y'.repeat(40);
async function socket(n) {
  const t = await post(n, 'ticket', {});
  const r = await SELF.fetch('https://chat/ws?t=' + encodeURIComponent(t.t), { headers: { Upgrade: 'websocket' } });
  const ws = r.webSocket, got = [];
  ws.accept();
  ws.addEventListener('message', e => got.push(JSON.parse(e.data)));
  return { ws, got, wait: async (pred, ms) => { const end = Date.now() + (ms || 2000); while (Date.now() < end) { const m = got.find(pred); if (m) return m; await new Promise(r => setTimeout(r, 10)); } return null; } };
}

describe('select + delete many (delete-many)', () => {
  it('deletes my own messages for everyone, and refuses someone else’s unless I’m the group’s admin', async () => {
    const c = await group('Grace', ['Bertin', 'Otieno'], 'Select crew');
    const a = (await send('Bertin', c, 'one')).msg.id, b2 = (await send('Bertin', c, 'two')).msg.id, g = (await send('Grace', c, 'three')).msg.id;
    // Otieno is not an admin here: not his messages, so nothing goes
    const no = await post('Otieno', 'delete-many', { c, ids: [a, b2], forAll: true });
    expect(no.status).toBe(403); expect(no.reason).toBe('not-yours');
    expect((await msgs('Grace', c)).filter(m => m.deleted).length).toBe(0);
    // Bertin: his own two
    const ws = await socket('Otieno');
    expect((await post('Bertin', 'delete-many', { c, ids: [a, b2], forAll: true })).n).toBe(2);
    expect(await ws.wait(m => m.t === 'delete' && m.id === b2 && m.forAll)).toBeTruthy();
    // Grace made the group (its admin): Bertin's already-gone ones count 0, her own one goes
    expect((await post('Grace', 'delete-many', { c, ids: [a, g], forAll: true })).n).toBe(1);
    const list = await msgs('Otieno', c);
    expect(list.filter(m => m.deleted).map(m => m.id).sort()).toEqual([a, b2, g].sort());
    expect((await row('Otieno', c)).last.deleted).toBe(true);
    ws.ws.close();
  });
  it('mixes are all-or-nothing; an Office admin may delete for everyone in a group; DMs only my own', async () => {
    const c = await group('Grace', ['Bertin', 'Jessica'], 'Mixed crew');
    const x = (await send('Bertin', c, 'x')).msg.id, y = (await send('Grace', c, 'y')).msg.id;
    expect((await post('Bertin', 'delete-many', { c, ids: [x, y], forAll: true })).reason).toBe('not-yours');
    expect((await msgs('Grace', c)).some(m => m.deleted)).toBe(false);
    // Jessica is an Office admin (X-Chat-Admin)
    expect((await post('Jessica', 'delete-many', { c, ids: [x, y], forAll: true })).n).toBe(2);
    const d = (await post('Grace', 'open', { kind: 'dm', with: 'Faith' })).id;
    const f = (await send('Faith', d, 'hi')).msg.id;
    expect((await post('Grace', 'delete-many', { c: d, ids: [f], forAll: true })).reason).toBe('not-yours');
  });
  it('for me: gone from my view only, and my unread count drops', async () => {
    const c = await group('Grace', ['Bertin'], 'Hide crew');
    const ids = [];
    for (const t of ['a', 'b', 'c']) ids.push((await send('Bertin', c, t)).msg.id);
    const u0 = (await row('Grace', c)).unread;
    expect(u0).toBeGreaterThanOrEqual(3);
    expect((await post('Grace', 'delete-many', { c, ids: ids.slice(0, 2), forAll: false })).n).toBe(2);
    expect((await msgs('Grace', c)).map(m => m.text).filter(t => /^[abc]$/.test(t))).toEqual(['c']);
    expect((await msgs('Bertin', c)).filter(m => /^[abc]$/.test(m.text)).length).toBe(3);
    expect((await row('Grace', c)).unread).toBe(u0 - 2);
  });
  it('checks what it is given', async () => {
    const c = await group('Grace', ['Bertin'], 'Check crew');
    const m = (await send('Grace', c, 'ok')).msg.id;
    expect((await post('Grace', 'delete-many', { c, ids: [] })).reason).toBe('bad-request');
    expect((await post('Grace', 'delete-many', { c, ids: Array.from({ length: 101 }, (_, i) => i + 1) })).reason).toBe('bad-request');
    expect((await post('Grace', 'delete-many', { c, ids: [m, 9999] })).reason).toBe('not-found');
    // the group's "created" line is a system line: not deletable
    expect((await post('Grace', 'delete-many', { c, ids: [1] })).reason).toBe('bad-request');
    expect((await post('Faith', 'delete-many', { c, ids: [m] })).reason).toBe('not-member');
  });
});

describe('Clear chat (for me) and Delete chat', () => {
  it('clears my view for good: reloads, search and the list row stay clear; others keep everything', async () => {
    const c = await group('Grace', ['Bertin'], 'Clear crew');
    await send('Bertin', c, 'borehole quote 18500');
    await send('Grace', c, 'approved');
    const r = await post('Grace', 'clear', { c });
    expect(r.ok).toBe(true); expect(r.cleared).toBeGreaterThan(0);
    expect(await msgs('Grace', c)).toEqual([]);
    expect((await get('Grace', 'msgs?c=' + encodeURIComponent(c) + '&after=0')).msgs).toEqual([]);
    const rw = await row('Grace', c);
    expect(rw.last).toBe(null); expect(rw.unread).toBe(0); expect(rw.cleared).toBe(r.cleared);
    expect((await get('Grace', 'search?q=' + encodeURIComponent('borehole quote'))).hits.filter(h => h.c === c)).toEqual([]);
    expect((await msgs('Bertin', c)).filter(m => m.kind === 'text').length).toBe(2);
    expect((await get('Bertin', 'search?q=' + encodeURIComponent('borehole quote'))).hits.some(h => h.c === c)).toBe(true);
    // new messages after the clear come through as usual
    await send('Bertin', c, 'one more');
    expect((await msgs('Grace', c)).map(m => m.text)).toEqual(['one more']);
    expect((await row('Grace', c)).last.text).toBe('one more');
  });
  it('a pinned message from before the clear leaves my pin bar', async () => {
    const c = await group('Grace', ['Bertin'], 'Pin clear crew');
    const m = (await send('Grace', c, 'pin me')).msg.id;
    await post('Grace', 'pin', { c, id: m, on: true });
    expect((await row('Bertin', c)).pins.length).toBe(1);
    await post('Grace', 'clear', { c });
    expect((await row('Grace', c)).pins).toEqual([]);
    expect((await row('Bertin', c)).pins.length).toBe(1);
  });
  it('Delete chat (a DM): cleared and archived, and tells my other phones', async () => {
    const d = (await post('Otieno', 'open', { kind: 'dm', with: 'Faith' })).id;
    await send('Faith', d, 'habari');
    const ws = await socket('Otieno');
    const r = await post('Otieno', 'clear', { c: d, archive: true });
    expect(r.chat.archived).toBe(true); expect(r.chat.last).toBe(null);
    expect(await ws.wait(m => m.t === 'chat' && m.chat.id === d && m.chat.archived)).toBeTruthy();
    expect(await msgs('Otieno', d)).toEqual([]);
    ws.ws.close();
  });
  it('only members can clear', async () => {
    const c = await group('Grace', ['Bertin'], 'Member clear crew');
    expect((await post('Faith', 'clear', { c })).reason).toBe('not-member');
  });
});

describe('adding people to a group', () => {
  it('posts “<name> added <names>” and pushes it only to the people added, saying who added them', async () => {
    const c = await group('Grace', ['Bertin'], 'Add crew');
    for (const n of ['Bertin', 'Otieno', 'Faith']) await post(n, 'device', { token: TOK(n), platform: 'android' });
    const r = await post('Grace', 'members', { c, add: ['Otieno', 'Faith'] });
    expect(r.ok).toBe(true);
    const line = (await msgs('Otieno', c)).find(m => m.kind === 'sys' && m.sys.type === 'added');
    expect(line.sys).toMatchObject({ by: 'Grace', who: ['Otieno', 'Faith'] });
    await runInDurableObject(dir(), inst => {
      const ch = inst.chat(c), plan = inst.pushPlan(ch, Object.assign({}, line, { fk: '' }), []);
      expect(plan.map(p => p.k).sort()).toEqual(['faith', 'otieno']);
      expect(plan[0].data).toMatchObject({ c, title: 'Add crew', sys: 'added', body: 'Grace added you to Add crew' });
    });
  });
});
