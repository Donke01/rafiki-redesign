// rafiki-chat worker: profile photos. The Pages side puts each person's photo version in the roster (p); the
// worker hands out the same /api/team-photo URL in presence, and tells open chats when a photo changes.
// Run: cd workers/chat && npm test
import { SELF } from 'cloudflare:test';
import { describe, it, expect } from 'vitest';
import { photoUrl } from '../src/directory.js';

const b64u = s => btoa(unescape(encodeURIComponent(s))).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
let ROSTER = [{ n: 'Grace', h: 'ke', a: 0, g: 0, b: 0, p: 'mabc12' }, { n: 'Meril', h: 'ke', a: 1, g: 0, b: 0 }, { n: 'Mary Ann', h: 'ke', a: 0, g: 0, b: 0, p: 'BAD/../x' }];
const hd = n => ({ 'X-Chat-User': encodeURIComponent(n), 'X-Chat-Admin': '0', 'X-Chat-Home': 'ke', 'X-Chat-Gen': '0', 'X-Chat-Roster': b64u(JSON.stringify(ROSTER)) });
const list = async n => (await SELF.fetch('https://chat/list', { headers: hd(n) })).json();

describe('profile photos', () => {
  it('builds the same URL as the Pages side, and only from a clean version', () => {
    expect(photoUrl('Mary Ann', 'mabc12')).toBe('/api/team-photo?name=Mary%20Ann&v=mabc12');
    expect(photoUrl('Grace', '')).toBe('');
    expect(photoUrl('Grace', 'x"><')).toBe('');
  });
  it('presence carries the photo URL for people who have one', async () => {
    const j = await list('Meril');
    expect(j.presence.Grace.photo).toBe('/api/team-photo?name=Grace&v=mabc12');
    expect(j.presence.Meril.photo).toBeUndefined();
    expect(j.presence['Mary Ann'].photo).toBeUndefined();
  });
  it('a new photo in the roster tells open chats straight away', async () => {
    const t = await (await SELF.fetch('https://chat/ticket', { method: 'POST', headers: Object.assign(hd('Meril'), { 'Content-Type': 'application/json' }), body: '{}' })).json();
    const r = await SELF.fetch('https://chat/ws?t=' + encodeURIComponent(t.t), { headers: { Upgrade: 'websocket' } });
    const ws = r.webSocket, got = [];
    ws.accept();
    ws.addEventListener('message', e => got.push(JSON.parse(e.data)));
    ROSTER = ROSTER.map(x => (x.n === 'Grace' ? Object.assign({}, x, { p: 'mabc99' }) : x));
    await SELF.fetch('https://chat/sync', { method: 'POST', headers: { 'X-Chat-Roster': b64u(JSON.stringify(ROSTER)) }, body: '{}' });
    const end = Date.now() + 2000;
    let m = null;
    while (Date.now() < end && !(m = got.find(x => x.t === 'presence' && x.who === 'Grace'))) await new Promise(res => setTimeout(res, 10));
    expect(m && m.photo).toBe('/api/team-photo?name=Grace&v=mabc99');
    expect((await list('Meril')).presence.Grace.photo).toBe('/api/team-photo?name=Grace&v=mabc99');
    // removed: presence has no photo again
    ROSTER = ROSTER.map(x => (x.n === 'Grace' ? Object.assign({}, x, { p: undefined }) : x));
    expect((await list('Meril')).presence.Grace.photo).toBeUndefined();
    ws.close(1000, 'bye');
  });
});
