// The chat directory: ONE SQLite-backed Durable Object for the whole team. It knows who is in which chat,
// each person's list (unread, @mention, pinned, muted, archived, wallpaper), read and delivered receipts,
// presence, and holds every open WebSocket (hibernation API), so it fans out new messages, typing and
// presence. Messages themselves live in one Conversation object per chat (conversation.js).
//
// Every request comes from the Pages Functions (functions/api/chat), which already checked the PIN or
// monday.com session, the blocked and signed-out guards, and refused donors. They pass the person in
// X-Chat-User (URI-encoded name), X-Chat-Admin (1 = Office admin), X-Chat-Home (ke/tz/ug/mw), X-Chat-Gen
// (session number) and the team roster in X-Chat-Roster (base64url JSON [{n, h, a, g, b}]).
import { DurableObject } from 'cloudflare:workers';
import { json, fail, rand, keyOf, clean, sha, mediaUrl, makeTicket, readTicket } from './util.js';
import { EDIT_MS } from './conversation.js';

const COUNTRIES = { ke: 'Kenya', tz: 'Tanzania', ug: 'Uganda', mw: 'Malawi' };
const KINDS = ['text', 'media', 'voice', 'location', 'live', 'contact', 'doc'];
const EMOJI = /^(?:\p{Extended_Pictographic}|\p{Emoji_Presentation})(?:️|‍|\p{Extended_Pictographic}|\p{Emoji_Modifier}|\p{Emoji_Presentation})*$/u;
const MAX_TEXT = 4000;
const LIVE_MAX = 8 * 3600e3 + 60e3;
const LIMITS = { send: [60, 60e3], media: [120, 3600e3], group: [20, 3600e3], preview: [120, 3600e3] };
const P = s => { if (s == null || s === '') return null; try { return JSON.parse(s); } catch (e) { return null; } };
const num = v => (v === '' || v == null || !isFinite(Number(v)) ? null : Number(v));
const isMediaId = s => /^[A-Za-z0-9_-]{16,40}$/.test(String(s || ''));

export class Directory extends DurableObject {
  constructor(ctx, env) {
    super(ctx, env);
    this.sql = ctx.storage.sql;
    this.rl = new Map();
    const x = s => this.sql.exec(s);
    x('CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v TEXT)');
    x('CREATE TABLE IF NOT EXISTS users (k TEXT PRIMARY KEY, name TEXT, home TEXT, admin INTEGER DEFAULT 0, gen INTEGER DEFAULT 0, blocked INTEGER DEFAULT 0, removed INTEGER DEFAULT 0, roster INTEGER DEFAULT 0, seen INTEGER)');
    x(`CREATE TABLE IF NOT EXISTS chats (id TEXT PRIMARY KEY, kind TEXT, title TEXT, photo TEXT, project TEXT, description TEXT, only_admins INTEGER DEFAULT 0,
      link TEXT, created INTEGER, by TEXT, last TEXT, last_at INTEGER DEFAULT 0, seq INTEGER DEFAULT 0, read_all INTEGER DEFAULT 0, deliv_all INTEGER DEFAULT 0)`);
    x(`CREATE TABLE IF NOT EXISTS members (c TEXT, k TEXT, admin INTEGER DEFAULT 0, joined INTEGER, unread INTEGER DEFAULT 0, mention INTEGER DEFAULT 0,
      pinned INTEGER DEFAULT 0, muted INTEGER DEFAULT 0, archived INTEGER DEFAULT 0, wallpaper TEXT DEFAULT '', read_up INTEGER DEFAULT 0, deliv_up INTEGER DEFAULT 0, upd INTEGER, PRIMARY KEY (c, k))`);
    x('CREATE INDEX IF NOT EXISTS members_k ON members (k)');
    x('CREATE TABLE IF NOT EXISTS gone (c TEXT, k TEXT, at INTEGER, PRIMARY KEY (c, k))');
    x('CREATE TABLE IF NOT EXISTS texts (c TEXT, id INTEGER, frm TEXT, at INTEGER, text TEXT, PRIMARY KEY (c, id))');
    x('CREATE TABLE IF NOT EXISTS stars (k TEXT, c TEXT, id INTEGER, at INTEGER, PRIMARY KEY (k, c, id))');
  }

  // ---- small reads ----------------------------------------------------------------------------
  q(s, ...a) { return [...this.sql.exec(s, ...a)]; }
  one(s, ...a) { return this.q(s, ...a)[0] || null; }
  meta(k, v) { if (v === undefined) { const r = this.one('SELECT v FROM meta WHERE k = ?', k); return r ? r.v : null; } this.sql.exec('INSERT OR REPLACE INTO meta (k, v) VALUES (?, ?)', k, v); }
  // the key that signs media links and WebSocket tickets: CHAT_SECRET if set, otherwise made once and kept here
  async key() {
    if (this.env.CHAT_SECRET) return String(this.env.CHAT_SECRET);
    let k = this.meta('key');
    if (!k) { k = rand(32); this.meta('key', k); }
    return k;
  }
  user(k) { return this.one('SELECT * FROM users WHERE k = ?', k); }
  nameOf(k) { const u = this.user(k); return u ? u.name : k; }
  chat(c) { return this.one('SELECT * FROM chats WHERE id = ?', c); }
  member(c, k) { return this.one('SELECT * FROM members WHERE c = ? AND k = ?', c, k); }
  memberKeys(c) { return this.q('SELECT k FROM members WHERE c = ? ORDER BY joined, k', c).map(r => r.k); }
  conv(c) { return this.env.CONV.get(this.env.CONV.idFromName(c)); }
  isAdmin(c, me) { const m = this.member(c, me.k); return !!(me.admin || (m && m.admin)); }
  limited(k, what) {
    const [n, win] = LIMITS[what], now = Date.now(), key = what + ':' + k, h = this.rl.get(key);
    if (!h || now - h.t > win) { this.rl.set(key, { t: now, n: 1 }); return false; }
    return ++h.n > n;
  }
  sockets(k) { try { return this.ctx.getWebSockets(k); } catch (e) { return []; } }
  sendTo(k, payload) { const s = JSON.stringify(payload); for (const ws of this.sockets(k)) { try { ws.send(s); } catch (e) {} } }
  fan(c, payload, except) { for (const k of this.memberKeys(c)) if (k !== except) this.sendTo(k, payload); }

  // ---- people and the team roster ---------------------------------------------------------------
  me(request) {
    const h = request.headers;
    let name = ''; try { name = decodeURIComponent(h.get('X-Chat-User') || ''); } catch (e) {}
    name = clean(name, 80);
    if (!name) return null;
    return { k: keyOf(name), name, admin: h.get('X-Chat-Admin') === '1', home: /^(ke|tz|ug|mw)$/.test(h.get('X-Chat-Home') || '') ? h.get('X-Chat-Home') : '', gen: Number(h.get('X-Chat-Gen')) || 0 };
  }
  async roster(request) {
    const raw = request.headers.get('X-Chat-Roster');
    if (!raw) return;
    const hash = await sha(raw);
    if (this.meta('roster') === hash) return;
    let list; try { list = JSON.parse(new TextDecoder().decode(Uint8Array.from(atob(raw.replace(/-/g, '+').replace(/_/g, '/') + '==='.slice((raw.length + 3) % 4)), c => c.charCodeAt(0)))); } catch (e) { return; }
    if (!Array.isArray(list)) return;
    this.meta('roster', hash);
    this.syncRoster(list);
  }
  // list: [{ n: name, h: home, a: admin, g: session number, b: blocked }]
  syncRoster(list) {
    const now = Date.now(), seen = new Set(), kick = [];
    for (const p of list.slice(0, 2000)) {
      const name = clean(p && p.n, 80), k = keyOf(name);
      if (!k || seen.has(k)) continue;
      seen.add(k);
      const u = this.user(k), home = /^(ke|tz|ug|mw)$/.test(p.h || '') ? p.h : (u ? u.home : ''), gen = Number(p.g) || 0, blocked = p.b ? 1 : 0;
      if (u) this.sql.exec('UPDATE users SET name = ?, home = ?, admin = ?, gen = ?, blocked = ?, removed = 0, roster = 1 WHERE k = ?', u.name || name, home, p.a ? 1 : 0, gen, blocked, k);
      else this.sql.exec('INSERT INTO users (k, name, home, admin, gen, blocked, roster) VALUES (?,?,?,?,?,?,1)', k, name, home, p.a ? 1 : 0, gen, blocked);
      if (blocked) kick.push(k);
      for (const ws of this.sockets(k)) { const a = ws.deserializeAttachment() || {}; if (gen > (Number(a.gen) || 0)) kick.push(k); }
    }
    // taken off the team list: out of the Team chats and their phones disconnected
    for (const u of this.q('SELECT k FROM users WHERE roster = 1')) if (!seen.has(u.k)) { this.sql.exec('UPDATE users SET roster = 0, removed = 1 WHERE k = ?', u.k); kick.push(u.k); }
    for (const k of new Set(kick)) for (const ws of this.sockets(k)) { try { ws.close(4401, 'signed-out'); } catch (e) {} }
    this.syncTeams(now);
  }
  ensureUser(me) {
    const u = this.user(me.k), now = Date.now();
    if (!u) this.sql.exec('INSERT INTO users (k, name, home, admin, gen, seen) VALUES (?,?,?,?,?,?)', me.k, me.name, me.home, me.admin ? 1 : 0, me.gen, now);
    else this.sql.exec('UPDATE users SET name = ?, home = CASE WHEN ? != \'\' THEN ? ELSE home END, admin = ?, removed = 0, blocked = 0, seen = ? WHERE k = ?', me.name, me.home, me.home, me.admin ? 1 : 0, now, me.k);
    if (!u || u.removed || u.blocked || (me.home && u.home !== me.home) || !!u.admin !== me.admin) this.syncTeams(now);
  }
  // Team chats: "All RAFIKI" and one per country, members kept in step with the team; Office admins are their admins
  syncTeams(now) {
    const people = this.q('SELECT * FROM users WHERE blocked = 0 AND removed = 0');
    const want = { 't:all': people };
    for (const cc of Object.keys(COUNTRIES)) want['t:' + cc] = people.filter(u => u.home === cc);
    for (const [c, list] of Object.entries(want)) {
      let ch = this.chat(c);
      if (!ch && !list.length) continue;
      if (!ch) {
        this.sql.exec("INSERT INTO chats (id, kind, title, photo, project, description, created, by, last_at) VALUES (?, 'team', ?, '', '', '', ?, '', ?)", c, c === 't:all' ? 'All RAFIKI' : COUNTRIES[c.slice(2)], now, now);
        ch = this.chat(c);
      }
      const keys = new Set(list.map(u => u.k));
      for (const u of list) {
        const m = this.member(c, u.k);
        if (!m) this.addMember(ch, u.k, u.admin ? 1 : 0, now);
        else if (!!m.admin !== !!u.admin) this.sql.exec('UPDATE members SET admin = ?, upd = ? WHERE c = ? AND k = ?', u.admin ? 1 : 0, now, c, u.k);
      }
      for (const k of this.memberKeys(c)) if (!keys.has(k)) this.dropMember(c, k, now);
    }
  }
  addMember(ch, k, admin, now) {
    this.sql.exec('INSERT OR IGNORE INTO members (c, k, admin, joined, read_up, deliv_up, upd) VALUES (?,?,?,?,?,?,?)', ch.id, k, admin ? 1 : 0, now, ch.seq || 0, ch.seq || 0, now);
    this.sql.exec('DELETE FROM gone WHERE c = ? AND k = ?', ch.id, k);
    this.sql.exec('UPDATE members SET upd = ? WHERE c = ?', now, ch.id);
  }
  dropMember(c, k, now) {
    this.sql.exec('DELETE FROM members WHERE c = ? AND k = ?', c, k);
    this.sql.exec('INSERT OR REPLACE INTO gone (c, k, at) VALUES (?,?,?)', c, k, now);
    this.sql.exec('UPDATE members SET upd = ? WHERE c = ?', now, c);
    this.sendTo(k, { t: 'chat', chat: { id: c, gone: true } });
  }

  // ---- what clients see -------------------------------------------------------------------------
  async row(ch, k, key) {
    const m = this.member(ch.id, k), now = Date.now();
    const mem = this.q('SELECT m.k, m.admin, u.name FROM members m LEFT JOIN users u ON u.k = m.k WHERE m.c = ? ORDER BY m.joined, m.k', ch.id);
    let title = ch.title;
    if (ch.kind === 'dm') { const o = mem.find(x => x.k !== k); title = o ? o.name || o.k : ch.title; }
    return {
      id: ch.id, kind: ch.kind, title, photo: isMediaId(ch.photo) ? await mediaUrl(key, ch.photo, now) : '', project: ch.project || '', description: ch.description || '',
      members: mem.map(x => x.name || x.k), admins: mem.filter(x => x.admin).map(x => x.name || x.k), last: P(ch.last),
      unread: m ? m.unread : 0, mention: !!(m && m.mention), pinned: !!(m && m.pinned),
      muted: m ? (m.muted === -1 ? 'always' : m.muted > now ? m.muted : 0) : 0, archived: !!(m && m.archived), onlyAdmins: !!ch.only_admins, wallpaper: (m && m.wallpaper) || ''
    };
  }
  async pushRows(c, only) {
    const ch = this.chat(c), key = await this.key();
    if (!ch) return;
    for (const k of only ? [only] : this.memberKeys(c)) if (this.sockets(k).length) this.sendTo(k, { t: 'chat', chat: await this.row(ch, k, key) });
  }
  async deco(msg, ch, key) {
    if (!msg) return null;
    const out = Object.assign({}, msg, { c: ch.id });
    delete out.fk;
    const n = this.q('SELECT COUNT(*) AS n FROM members WHERE c = ?', ch.id)[0].n;
    out.status = n < 2 ? 'sent' : msg.id <= ch.read_all ? 'read' : msg.id <= ch.deliv_all ? 'delivered' : 'sent';
    if (Array.isArray(out.media)) out.media = await Promise.all(out.media.map(async x => Object.assign({}, x, { url: await mediaUrl(key, x.id) })));
    return out;
  }
  presence() {
    const p = {};
    for (const u of this.q('SELECT k, name, seen FROM users WHERE blocked = 0 AND removed = 0')) p[u.name] = { online: this.sockets(u.k).length > 0, seen: u.seen || null };
    return p;
  }

  // ---- the router ---------------------------------------------------------------------------------
  async fetch(request) {
    const url = new URL(request.url), path = url.pathname.replace(/^\/+/, ''), q = url.searchParams;
    try {
      if (path === 'sys' && request.method === 'POST') return await this.sys(await request.json());
      if (path === 'sync' && request.method === 'POST') { await this.roster(request); return json({ ok: true }); }
      if (path === 'ws') return await this.ws(request, q.get('t'));
      const me = this.me(request);
      if (!me) return fail('no-name', 403);
      await this.roster(request);
      const u = this.user(me.k);
      if (u && u.blocked) return fail('blocked', 401);
      this.ensureUser(me);
      if (request.method === 'GET') {
        switch (path) {
          case 'list': return await this.list(me, num(q.get('since')));
          case 'msgs': return await this.msgs(me, q.get('c'), q.get('before'), q.get('after'), q.get('limit'));
          case 'info': return await this.info(me, q.get('c'), q.get('id'));
          case 'starred': return await this.starred(me);
          case 'search': return this.search(me, q.get('q'));
          case 'key': return json({ ok: true, key: await this.key() });
        }
        return fail('not-found', 404);
      }
      if (request.method !== 'POST') return fail('method', 405);
      let b = {};
      try { b = await request.json(); } catch (e) { return fail('bad-request'); }
      if (!b || typeof b !== 'object') return fail('bad-request');
      switch (path) {
        case 'ticket': { const key = await this.key(); return json(Object.assign({ ok: true }, await makeTicket(key, me))); }
        case 'allow': return this.limited(me.k, LIMITS[b.what] ? b.what : 'media') ? fail('rate', 429) : json({ ok: true });
        case 'open': return await this.open(me, b);
        case 'group': return await this.group(me, b);
        case 'group-update': return await this.groupUpdate(me, b);
        case 'members': return await this.members(me, b);
        case 'link': return await this.link(me, b);
        case 'join': return await this.join(me, b);
        case 'leave': return await this.leave(me, b);
        case 'send': return await this.send(me, b);
        case 'edit': return await this.edit(me, b);
        case 'delete': return await this.del(me, b);
        case 'react': return await this.react(me, b);
        case 'star': return this.star(me, b);
        case 'read': return await this.receipt(me, b, 'read');
        case 'delivered': return await this.receipt(me, b, 'delivered');
        case 'live': return await this.live(me, b, false);
        case 'live-stop': return await this.live(me, b, true);
        case 'prefs': return await this.prefs(me, b);
      }
      return fail('not-found', 404);
    } catch (e) {
      console.error('chat ' + path + ':', e && e.stack || e);
      return fail('down', 500);
    }
  }

  // a chat this person is in (or the reason they can't use it)
  inChat(me, c) {
    const ch = c && this.chat(String(c));
    if (!ch) return { res: fail('not-found', 404) };
    if (!this.member(ch.id, me.k)) return { res: fail('not-member', 403) };
    return { ch };
  }

  // ---- list, messages ----------------------------------------------------------------------------
  async list(me, since) {
    const key = await this.key(), now = Date.now();
    const rows = this.q('SELECT c.* FROM chats c JOIN members m ON m.c = c.id WHERE m.k = ?' + (since ? ' AND m.upd > ?' : '') + ' ORDER BY c.last_at DESC', ...(since ? [me.k, since] : [me.k]));
    const chats = [];
    for (const ch of rows) if (!(ch.kind === 'dm' && !ch.seq && ch.by !== me.k)) chats.push(await this.row(ch, me.k, key));
    const out = { ok: true, me: me.name, now, chats, presence: this.presence() };
    if (since) out.gone = this.q('SELECT c FROM gone WHERE k = ? AND at > ?', me.k, since).map(r => r.c);
    return json(out);
  }
  async msgs(me, c, before, after, limit) {
    const { ch, res } = this.inChat(me, c);
    if (res) return res;
    const key = await this.key(), r = await this.conv(ch.id).page(me.k, num(before), num(after), num(limit));
    return json({ ok: true, msgs: await Promise.all(r.msgs.map(m => this.deco(m, ch, key))), more: r.more });
  }

  // ---- opening chats ----------------------------------------------------------------------------
  async open(me, b) {
    const now = Date.now();
    if (b.kind === 'dm') {
      const w = this.user(keyOf(b.with));
      if (!w || w.k === me.k || w.blocked || w.removed) return fail('not-found', 404);
      const id = 'd:' + (await sha([me.k, w.k].sort().join('\n'))).slice(0, 24);
      let ch = this.chat(id);
      if (!ch) {
        this.sql.exec("INSERT INTO chats (id, kind, title, photo, project, description, created, by, last_at) VALUES (?, 'dm', '', '', '', '', ?, ?, ?)", id, now, me.k, now);
        ch = this.chat(id); this.addMember(ch, me.k, 0, now); this.addMember(ch, w.k, 0, now);
      }
      return json({ ok: true, id });
    }
    if (b.kind === 'project') {
      const p = String(b.project || '').replace(/\D/g, '').slice(0, 20);
      if (!p) return fail('bad-request');
      const ch = this.projectChat(p, b.ptitle, now);
      if (!this.member(ch.id, me.k)) { this.addMember(ch, me.k, 0, now); await this.pushRows(ch.id, me.k); }
      return json({ ok: true, id: ch.id });
    }
    return fail('bad-request');
  }
  projectChat(p, title, now) {
    const id = 'p:' + p;
    let ch = this.chat(id);
    const t = clean(title, 120);
    if (!ch) {
      this.sql.exec("INSERT INTO chats (id, kind, title, photo, project, description, created, by, last_at) VALUES (?, 'project', ?, '', ?, '', ?, '', ?)", id, t || 'Project ' + p, p, now, now);
      ch = this.chat(id);
    } else if (t && t !== ch.title) { this.sql.exec('UPDATE chats SET title = ? WHERE id = ?', t, id); ch = this.chat(id); }
    return ch;
  }

  // ---- groups -------------------------------------------------------------------------------------
  names(list, max) { return (Array.isArray(list) ? list : []).slice(0, max || 256).map(n => this.user(keyOf(n))).filter(u => u && !u.blocked && !u.removed); }
  async group(me, b) {
    const title = clean(b.title, 60);
    if (!title) return fail('bad-request');
    if (this.limited(me.k, 'group')) return fail('rate', 429);
    const now = Date.now(), id = 'g:' + rand(12);
    this.sql.exec("INSERT INTO chats (id, kind, title, photo, project, description, created, by, last_at) VALUES (?, 'group', ?, '', '', ?, ?, ?, ?)", id, title, clean(b.description, 500), now, me.k, now);
    const ch = this.chat(id);
    this.addMember(ch, me.k, 1, now);
    for (const u of this.names(b.members)) this.addMember(ch, u.k, 0, now);
    await this.post(this.chat(id), { fk: '', from: '', kind: 'sys', text: me.name + ' created the group “' + title + '”', sys: { type: 'created', by: me.name } });
    return json({ ok: true, id });
  }
  groupish(ch) { return ch.kind === 'group' || ch.kind === 'team' || ch.kind === 'project'; }
  async groupUpdate(me, b) {
    const { ch, res } = this.inChat(me, b.c);
    if (res) return res;
    if (!this.groupish(ch)) return fail('bad-request');
    if (!this.isAdmin(ch.id, me)) return fail('not-admin', 403);
    const now = Date.now(), set = [];
    if ('title' in b && ch.kind === 'group') { const t = clean(b.title, 60); if (!t) return fail('bad-request'); set.push(['title', t]); }
    if ('description' in b) set.push(['description', clean(b.description, 500)]);
    if ('photo' in b) { if (b.photo && !isMediaId(b.photo)) return fail('bad-request'); set.push(['photo', b.photo || '']); }
    if ('onlyAdmins' in b) set.push(['only_admins', b.onlyAdmins ? 1 : 0]);
    for (const [col, v] of set) this.sql.exec('UPDATE chats SET ' + col + ' = ? WHERE id = ?', v, ch.id);
    this.sql.exec('UPDATE members SET upd = ? WHERE c = ?', now, ch.id);
    await this.pushRows(ch.id);
    return json({ ok: true, chat: await this.row(this.chat(ch.id), me.k, await this.key()) });
  }
  async members(me, b) {
    const { ch, res } = this.inChat(me, b.c);
    if (res) return res;
    if (ch.kind === 'team') return fail('team', 409);
    if (ch.kind === 'dm') return fail('bad-request');
    if (!this.isAdmin(ch.id, me)) return fail('not-admin', 403);
    const now = Date.now(), added = [], removed = [];
    for (const u of this.names(b.add)) if (!this.member(ch.id, u.k)) { this.addMember(this.chat(ch.id), u.k, 0, now); added.push(u.name); }
    for (const n of (Array.isArray(b.remove) ? b.remove : []).slice(0, 256)) { const k = keyOf(n); if (k !== me.k && this.member(ch.id, k)) { this.dropMember(ch.id, k, now); removed.push(this.nameOf(k)); } }
    for (const n of (Array.isArray(b.admin) ? b.admin : []).slice(0, 256)) this.sql.exec('UPDATE members SET admin = 1, upd = ? WHERE c = ? AND k = ?', now, ch.id, keyOf(n));
    for (const n of (Array.isArray(b.unadmin) ? b.unadmin : []).slice(0, 256)) if (keyOf(n) !== me.k || me.admin) this.sql.exec('UPDATE members SET admin = 0, upd = ? WHERE c = ? AND k = ?', now, ch.id, keyOf(n));
    if (added.length) await this.post(this.chat(ch.id), { fk: '', from: '', kind: 'sys', text: me.name + ' added ' + added.join(', '), sys: { type: 'added', by: me.name, who: added } });
    if (removed.length) await this.post(this.chat(ch.id), { fk: '', from: '', kind: 'sys', text: me.name + ' removed ' + removed.join(', '), sys: { type: 'removed', by: me.name, who: removed } });
    this.sql.exec('UPDATE members SET upd = ? WHERE c = ?', now, ch.id);
    await this.recalc(this.chat(ch.id));
    await this.pushRows(ch.id);
    return json({ ok: true, chat: await this.row(this.chat(ch.id), me.k, await this.key()) });
  }
  async link(me, b) {
    const { ch, res } = this.inChat(me, b.c);
    if (res) return res;
    if (ch.kind !== 'group') return fail('bad-request');
    let token = ch.link;
    if (b.reset || !token) {
      if (!this.isAdmin(ch.id, me)) return fail('not-admin', 403);
      token = rand(16);
      this.sql.exec('UPDATE chats SET link = ? WHERE id = ?', token, ch.id);
    }
    return json({ ok: true, token, url: '/pin#chat/join/' + token });
  }
  async join(me, b) {
    const token = String(b.token || '');
    const ch = /^[A-Za-z0-9_-]{16,40}$/.test(token) && this.one('SELECT * FROM chats WHERE link = ? AND kind = ?', token, 'group');
    if (!ch) return fail('not-found', 404);
    if (!this.member(ch.id, me.k)) {
      this.addMember(ch, me.k, 0, Date.now());
      await this.post(this.chat(ch.id), { fk: '', from: '', kind: 'sys', text: me.name + ' joined using the group link', sys: { type: 'joined', by: me.name } });
      await this.pushRows(ch.id);
    }
    return json({ ok: true, id: ch.id });
  }
  async leave(me, b) {
    const { ch, res } = this.inChat(me, b.c);
    if (res) return res;
    if (ch.kind === 'team') return fail('team', 409);
    if (ch.kind === 'dm') return fail('bad-request');
    const now = Date.now(), wasAdmin = this.member(ch.id, me.k).admin;
    this.dropMember(ch.id, me.k, now);
    // the last admin leaving hands over to the longest-standing member
    if (ch.kind === 'group' && wasAdmin && !this.one('SELECT 1 AS x FROM members WHERE c = ? AND admin = 1', ch.id)) {
      const next = this.one('SELECT k FROM members WHERE c = ? ORDER BY joined, k LIMIT 1', ch.id);
      if (next) this.sql.exec('UPDATE members SET admin = 1 WHERE c = ? AND k = ?', ch.id, next.k);
    }
    if (ch.kind === 'group') await this.post(this.chat(ch.id), { fk: '', from: '', kind: 'sys', text: me.name + ' left', sys: { type: 'left', by: me.name } });
    await this.recalc(this.chat(ch.id));
    await this.pushRows(ch.id);
    return json({ ok: true });
  }

  // ---- sending ------------------------------------------------------------------------------------
  // a message in, with its effects on everyone's list, then fanned out
  async post(ch, m, mentionKeys) {
    const now = Date.now();
    m.at = now;
    const r = await this.conv(ch.id).send(m);
    if (r.dup) return r;
    const msg = r.msg;
    const last = { id: msg.id, from: msg.from, kind: msg.kind, text: String(msg.text || '').slice(0, 160), at: msg.at };
    if (m.sys) last.sys = m.sys.type;
    this.sql.exec('UPDATE chats SET seq = ?, last = ?, last_at = ? WHERE id = ?', msg.id, JSON.stringify(last), now, ch.id);
    if (msg.text && msg.kind !== 'sys') this.sql.exec('INSERT OR REPLACE INTO texts (c, id, frm, at, text) VALUES (?,?,?,?,?)', ch.id, msg.id, msg.from, now, msg.text);
    this.sql.exec('UPDATE members SET unread = unread + 1, upd = ? WHERE c = ? AND k != ?', now, ch.id, m.fk || '');
    for (const k of mentionKeys || []) this.sql.exec('UPDATE members SET mention = CASE WHEN mention > 0 THEN mention ELSE ? END WHERE c = ? AND k = ?', msg.id, ch.id, k);
    if (m.fk) this.sql.exec('UPDATE members SET read_up = ?, deliv_up = ?, unread = 0, mention = 0, upd = ? WHERE c = ? AND k = ?', msg.id, msg.id, now, ch.id, m.fk);
    const fresh = this.chat(ch.id), key = await this.key();
    this.fan(ch.id, { t: 'msg', msg: await this.deco(msg, fresh, key) });
    await this.pushRows(ch.id);
    return r;
  }
  async send(me, b) {
    const { ch, res } = this.inChat(me, b.c);
    if (res) return res;
    if (ch.only_admins && !this.isAdmin(ch.id, me)) return fail('only-admins', 403);
    const kind = String(b.kind || 'text');
    if (!KINDS.includes(kind)) return fail('bad-request');
    const cid = b.cid == null ? '' : clean(b.cid, 64);
    // the same client id again: the message already sent (no rate limit, no second copy)
    if (cid) {
      const had = await this.conv(ch.id).byCid(me.k, cid);
      if (had) return json({ ok: true, msg: await this.deco(had, ch, await this.key()) });
    }
    if (this.limited(me.k, 'send')) return fail('rate', 429);
    const text = clean(b.text, MAX_TEXT + 1);
    if (text.length > MAX_TEXT) return fail('too-long', 413);
    const m = { fk: me.k, from: me.name, cid, kind, text, fwd: !!b.fwd };
    const media = Array.isArray(b.media) ? b.media.slice(0, 10) : [];
    const items = [];
    for (const x of media) {
      if (!x || !isMediaId(x.id)) return fail('bad-request');
      const it = { id: x.id, type: ['photo', 'video', 'voice', 'doc'].includes(x.type) ? x.type : 'doc' };
      for (const f of ['w', 'h', 'dur', 'size']) if (num(x[f]) != null && num(x[f]) >= 0) it[f] = Math.round(num(x[f]) * (f === 'dur' ? 10 : 1)) / (f === 'dur' ? 10 : 1);
      if (Array.isArray(x.wave)) it.wave = x.wave.slice(0, 128).map(v => Math.max(0, Math.min(100, Math.round(Number(v) || 0))));
      if (x.name) it.name = clean(x.name, 120);
      if (x.poster && isMediaId(x.poster)) it.poster = x.poster;
      items.push(it);
    }
    if (kind === 'text' && !text) return fail('bad-request');
    if (kind === 'media' && (!items.length || items.some(x => x.type !== 'photo' && x.type !== 'video'))) return fail('bad-request');
    if (kind === 'voice' && (items.length !== 1 || items[0].type !== 'voice')) return fail('bad-request');
    if (kind === 'voice' && items[0].dur > 300) return fail('too-long', 413);
    if (kind === 'media' && items.some(x => x.type === 'video' && x.dur > 60)) return fail('too-long', 413);
    if (kind === 'doc' && (items.length !== 1 || items[0].type !== 'doc')) return fail('bad-request');
    if (items.length) m.media = items;
    if (kind === 'location' || kind === 'live') {
      const lat = num(b.loc && b.loc.lat), lng = num(b.loc && b.loc.lng);
      if (lat == null || lng == null || Math.abs(lat) > 90 || Math.abs(lng) > 180) return fail('bad-request');
      m.loc = { lat, lng };
      if (b.loc.name) m.loc.name = clean(b.loc.name, 120);
      if (kind === 'live') {
        const until = num(b.live && b.live.until), now = Date.now();
        if (!until || until <= now || until > now + LIVE_MAX) return fail('bad-request');
        m.live = { until, lat, lng, at: now };
      }
    }
    if (kind === 'contact') {
      const u = b.contact && this.user(keyOf(b.contact.name));
      if (!u || u.blocked || u.removed) return fail('bad-request');
      m.contact = { name: u.name };
    }
    if (b.reply != null && num(b.reply) != null) m.reply = num(b.reply);
    const mem = new Set(this.memberKeys(ch.id));
    const mk = (Array.isArray(b.mentions) ? b.mentions : []).slice(0, 50).map(keyOf).filter(k => mem.has(k) && k !== me.k);
    m.mentions = [...new Set(mk)].map(k => this.nameOf(k));
    const r = await this.post(ch, m, mk);
    return json({ ok: true, msg: await this.deco(r.msg, this.chat(ch.id), await this.key()) });
  }
  // a system line in a project thread (from the Pages Functions: repairs, visits, community reports)
  async sys(b) {
    const p = String(b.project || '').replace(/\D/g, '').slice(0, 20), type = clean(b.sys && b.sys.type, 20);
    if (!p || !type) return fail('bad-request');
    const now = Date.now(), ch = this.projectChat(p, b.title, now);
    for (const u of this.names(b.add, 10)) if (!this.member(ch.id, u.k)) this.addMember(this.chat(ch.id), u.k, 0, now);
    const s = { type, project: p };
    for (const f of ['repair', 'step', 'by', 'to', 'state']) if (b.sys[f] != null && b.sys[f] !== '') s[f] = clean(b.sys[f], 120);
    const r = await this.post(this.chat(ch.id), { fk: '', from: '', kind: 'sys', text: clean(b.text, 600), sys: s });
    return json({ ok: true, id: ch.id, msg: r.msg.id });
  }

  // ---- changing messages --------------------------------------------------------------------------
  async afterChange(ch, msg, payload) {
    const last = P(ch.last);
    if (last && last.id === msg.id) {
      const l = Object.assign({}, last, { text: String(msg.text || '').slice(0, 160), deleted: msg.deleted || undefined, edited: msg.edited || undefined });
      this.sql.exec('UPDATE chats SET last = ? WHERE id = ?', JSON.stringify(l), ch.id);
      this.sql.exec('UPDATE members SET upd = ? WHERE c = ?', Date.now(), ch.id);
      await this.pushRows(ch.id);
    }
    this.fan(ch.id, payload);
  }
  async edit(me, b) {
    const { ch, res } = this.inChat(me, b.c);
    if (res) return res;
    const text = clean(b.text, MAX_TEXT + 1);
    if (text.length > MAX_TEXT) return fail('too-long', 413);
    const r = await this.conv(ch.id).edit(me.k, num(b.id), text, Date.now());
    if (r.reason) return fail(r.reason, { 'not-found': 404, 'not-yours': 403, 'edit-window': 409 }[r.reason] || 400);
    if (r.msg.text) this.sql.exec('INSERT OR REPLACE INTO texts (c, id, frm, at, text) VALUES (?,?,?,?,?)', ch.id, r.msg.id, r.msg.from, r.msg.at, r.msg.text);
    else this.sql.exec('DELETE FROM texts WHERE c = ? AND id = ?', ch.id, r.msg.id);
    await this.afterChange(ch, r.msg, { t: 'edit', c: ch.id, id: r.msg.id, text: r.msg.text, edited: r.msg.edited });
    return json({ ok: true, msg: await this.deco(r.msg, this.chat(ch.id), await this.key()) });
  }
  async del(me, b) {
    const { ch, res } = this.inChat(me, b.c);
    if (res) return res;
    const id = num(b.id), conv = this.conv(ch.id), m = id != null && await conv.get(id);
    if (!m) return fail('not-found', 404);
    if (!b.forAll) {
      await conv.hide(me.k, id);
      const mm = this.member(ch.id, me.k);
      if (id > mm.read_up && m.fk !== me.k) this.sql.exec('UPDATE members SET unread = MAX(unread - 1, 0), upd = ? WHERE c = ? AND k = ?', Date.now(), ch.id, me.k);
      this.sendTo(me.k, { t: 'delete', c: ch.id, id, forAll: false });
      return json({ ok: true });
    }
    // for everyone: your own message, or an admin in a group, team or project chat
    const own = m.fk === me.k, admin = this.groupish(ch) && this.isAdmin(ch.id, me);
    if (!own && !admin) return fail('not-yours', 403);
    if (m.deleted) return json({ ok: true });
    const r = await conv.remove(id);
    this.sql.exec('DELETE FROM texts WHERE c = ? AND id = ?', ch.id, id);
    await this.afterChange(ch, r.msg, { t: 'delete', c: ch.id, id, forAll: true, by: own ? undefined : me.name });
    return json({ ok: true });
  }
  async react(me, b) {
    const { ch, res } = this.inChat(me, b.c);
    if (res) return res;
    const e = b.emoji == null || b.emoji === '' ? null : String(b.emoji);
    if (e && (e.length > 16 || !EMOJI.test(e))) return fail('bad-request');
    const r = await this.conv(ch.id).react(me.k, me.name, num(b.id), e, Date.now());
    if (r.reason) return fail(r.reason, 404);
    this.fan(ch.id, { t: 'react', c: ch.id, id: r.msg.id, reacts: r.msg.reacts, who: me.name, emoji: e });
    return json({ ok: true, reacts: r.msg.reacts });
  }
  star(me, b) {
    const { ch, res } = this.inChat(me, b.c);
    if (res) return res;
    const id = num(b.id);
    if (id == null) return fail('bad-request');
    if (b.on) this.sql.exec('INSERT OR REPLACE INTO stars (k, c, id, at) VALUES (?,?,?,?)', me.k, ch.id, id, Date.now());
    else this.sql.exec('DELETE FROM stars WHERE k = ? AND c = ? AND id = ?', me.k, ch.id, id);
    return json({ ok: true });
  }
  async starred(me) {
    const rows = this.q('SELECT s.c, s.id FROM stars s JOIN members m ON m.c = s.c AND m.k = s.k WHERE s.k = ? ORDER BY s.at DESC LIMIT 500', me.k), by = {};
    rows.forEach(r => (by[r.c] = by[r.c] || []).push(r.id));
    const key = await this.key(), out = [];
    for (const [c, ids] of Object.entries(by)) { const ch = this.chat(c); for (const m of await this.conv(c).byIds(ids)) out.push(Object.assign(await this.deco(m, ch, key), { chat: ch.kind === 'dm' ? '' : ch.title })); }
    const order = new Map(rows.map((r, i) => [r.c + '#' + r.id, i]));
    out.sort((a, b) => order.get(a.c + '#' + a.id) - order.get(b.c + '#' + b.id));
    return json({ ok: true, msgs: out });
  }

  // ---- read and delivered -----------------------------------------------------------------------
  async receipt(me, b, kind) {
    const { ch, res } = this.inChat(me, b.c);
    if (res) return res;
    const up = Math.min(Math.max(num(b.upTo) || 0, 0), ch.seq), m = this.member(ch.id, me.k), now = Date.now(), conv = this.conv(ch.id);
    if (kind === 'read' && up > m.read_up) {
      const unread = await conv.unreadAfter(me.k, up);
      this.sql.exec('UPDATE members SET read_up = ?, deliv_up = MAX(deliv_up, ?), unread = ?, mention = CASE WHEN mention > ? THEN mention ELSE 0 END, upd = ? WHERE c = ? AND k = ?', up, up, unread, up, now, ch.id, me.k);
      await conv.receipt(me.k, me.name, 'read', up, now);
      if (up > m.deliv_up) await conv.receipt(me.k, me.name, 'delivered', up, now);
      await this.pushRows(ch.id, me.k);
    } else if (kind === 'delivered' && up > m.deliv_up) {
      this.sql.exec('UPDATE members SET deliv_up = ? WHERE c = ? AND k = ?', up, ch.id, me.k);
      await conv.receipt(me.k, me.name, 'delivered', up, now);
    }
    await this.recalc(this.chat(ch.id));
    return json({ ok: true });
  }
  // blue ticks when everyone has read; grey double ticks when everyone has the message
  async recalc(ch) {
    if (!ch) return;
    const r = this.one('SELECT MIN(read_up) AS r, MIN(deliv_up) AS d, COUNT(*) AS n FROM members WHERE c = ?', ch.id);
    if (!r || r.n < 2) return;
    const ra = Math.max(Number(r.r) || 0, ch.read_all), da = Math.max(Number(r.d) || 0, ra, ch.deliv_all);
    if (ra === ch.read_all && da === ch.deliv_all) return;
    this.sql.exec('UPDATE chats SET read_all = ?, deliv_all = ? WHERE id = ?', ra, da, ch.id);
    if (da > ch.deliv_all) this.fan(ch.id, { t: 'status', c: ch.id, upTo: da, status: 'delivered' });
    if (ra > ch.read_all) this.fan(ch.id, { t: 'status', c: ch.id, upTo: ra, status: 'read' });
  }
  async info(me, c, id) {
    const { ch, res } = this.inChat(me, c);
    if (res) return res;
    const conv = this.conv(ch.id), m = num(id) != null && await conv.get(num(id));
    if (!m) return fail('not-found', 404);
    if (m.fk !== me.k) return fail('not-yours', 403);
    return json(Object.assign({ ok: true }, await conv.info(m.id, me.k)));
  }

  // ---- live location, settings, search --------------------------------------------------------------
  async live(me, b, stop) {
    const { ch, res } = this.inChat(me, b.c);
    if (res) return res;
    const lat = num(b.lat), lng = num(b.lng);
    if (!stop && (lat == null || lng == null || Math.abs(lat) > 90 || Math.abs(lng) > 180)) return fail('bad-request');
    const r = await this.conv(ch.id).live(me.k, num(b.id), lat, lng, Date.now(), stop);
    if (r.reason) return fail(r.reason, r.reason === 'not-yours' ? 403 : r.reason === 'ended' ? 409 : 404);
    const lv = r.msg.live;
    this.fan(ch.id, { t: 'live', c: ch.id, id: r.msg.id, lat: lv.lat, lng: lv.lng, at: lv.at, until: lv.until });
    return json({ ok: true, live: lv });
  }
  async prefs(me, b) {
    const { ch, res } = this.inChat(me, b.c);
    if (res) return res;
    const set = [];
    if ('pinned' in b) set.push(['pinned', b.pinned ? 1 : 0]);
    if ('archived' in b) set.push(['archived', b.archived ? 1 : 0]);
    if ('wallpaper' in b) set.push(['wallpaper', clean(b.wallpaper, 40)]);
    if ('muted' in b) set.push(['muted', b.muted === 'always' ? -1 : Math.max(0, Math.round(num(b.muted) || 0))]);
    for (const [col, v] of set) this.sql.exec('UPDATE members SET ' + col + ' = ? WHERE c = ? AND k = ?', v, ch.id, me.k);
    this.sql.exec('UPDATE members SET upd = ? WHERE c = ? AND k = ?', Date.now(), ch.id, me.k);
    const row = await this.row(this.chat(ch.id), me.k, await this.key());
    this.sendTo(me.k, { t: 'chat', chat: row });
    return json({ ok: true, chat: row });
  }
  search(me, qs) {
    const q = clean(qs, 100);
    if (q.length < 2) return json({ ok: true, hits: [] });
    const like = '%' + q.replace(/[\\%_]/g, c => '\\' + c) + '%';
    const rows = this.q("SELECT t.c, t.id, t.frm, t.at, t.text FROM texts t JOIN members m ON m.c = t.c AND m.k = ? WHERE t.text LIKE ? ESCAPE '\\' ORDER BY t.at DESC LIMIT 50", me.k, like);
    const lo = q.toLowerCase();
    return json({
      ok: true, hits: rows.map(r => {
        const i = r.text.toLowerCase().indexOf(lo), s = Math.max(0, i - 30);
        return { c: r.c, id: r.id, from: r.frm, at: r.at, snippet: (s ? '…' : '') + r.text.slice(s, s + 100) + (r.text.length > s + 100 ? '…' : '') };
      })
    });
  }

  // ---- WebSockets (hibernation API) -----------------------------------------------------------------
  async ws(request, t) {
    if (request.headers.get('Upgrade') !== 'websocket') return fail('bad-request', 426);
    const v = await readTicket(await this.key(), t);
    if (!v) return fail('ticket', 401);
    const k = keyOf(v.n), u = this.user(k);
    if (u && (u.blocked || u.removed || (Number(u.gen) || 0) > (Number(v.g) || 0))) return fail('signed-out', 401);
    this.ensureUser({ k, name: v.n, home: v.h || '', admin: !!v.a, gen: v.g || 0 });
    const pair = new WebSocketPair(), [client, server] = Object.values(pair);
    const first = this.sockets(k).length === 0;
    this.ctx.acceptWebSocket(server, [k]);
    server.serializeAttachment({ k, name: v.n, gen: Number(v.g) || 0 });
    server.send(JSON.stringify({ t: 'hello', me: v.n, now: Date.now() }));
    if (first) this.everyone({ t: 'presence', who: v.n, online: true, seen: Date.now() }, k);
    return new Response(null, { status: 101, webSocket: client });
  }
  everyone(payload, except) { const s = JSON.stringify(payload); for (const ws of this.ctx.getWebSockets()) { const a = ws.deserializeAttachment() || {}; if (a.k !== except) { try { ws.send(s); } catch (e) {} } } }
  async webSocketMessage(ws, data) {
    const a = ws.deserializeAttachment() || {};
    let m; try { m = JSON.parse(typeof data === 'string' ? data : new TextDecoder().decode(data)); } catch (e) { return; }
    if (!m || typeof m !== 'object') return;
    if (m.t === 'ping') { ws.send(JSON.stringify({ t: 'pong', now: Date.now() })); return; }
    if (m.t === 'typing') {
      const c = String(m.c || ''), u = this.user(a.k);
      if (!u || u.blocked || !this.member(c, a.k)) return;
      const what = m.what === 'typing' || m.what === 'recording' ? m.what : null;
      this.fan(c, { t: 'typing', c, who: a.name, what }, a.k);
    }
  }
  async gone(ws) {
    const a = ws.deserializeAttachment() || {};
    if (!a.k) return;
    const left = this.sockets(a.k).filter(x => x !== ws && x.readyState === 1);
    if (left.length) return;
    const now = Date.now();
    this.sql.exec('UPDATE users SET seen = ? WHERE k = ?', now, a.k);
    this.everyone({ t: 'presence', who: a.name, online: false, seen: now }, a.k);
  }
  async webSocketClose(ws, code) { try { ws.close(code === 1005 || code === 1006 ? 1000 : code, 'bye'); } catch (e) {} await this.gone(ws); }
  async webSocketError(ws) { await this.gone(ws); }
}
export { EDIT_MS };
