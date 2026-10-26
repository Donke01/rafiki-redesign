// One chat's messages (a SQLite-backed Durable Object per chat, named by the chat id).
// Only the Directory calls it (RPC); the Directory checks who may do what first.
import { DurableObject } from 'cloudflare:workers';

const J = v => (v == null ? null : JSON.stringify(v));
const P = s => { if (s == null || s === '') return null; try { return JSON.parse(s); } catch (e) { return null; } };
export const EDIT_MS = 15 * 60 * 1000;

export class Conversation extends DurableObject {
  constructor(ctx, env) {
    super(ctx, env);
    this.sql = ctx.storage.sql;
    this.sql.exec(`CREATE TABLE IF NOT EXISTS msgs (id INTEGER PRIMARY KEY, cid TEXT, fk TEXT, frm TEXT, at INTEGER, kind TEXT, text TEXT, media TEXT,
      reply TEXT, fwd INTEGER, mentions TEXT, loc TEXT, live TEXT, contact TEXT, edited INTEGER, deleted INTEGER DEFAULT 0, sys TEXT)`);
    this.sql.exec('CREATE UNIQUE INDEX IF NOT EXISTS msgs_cid ON msgs (fk, cid)');
    this.sql.exec('CREATE TABLE IF NOT EXISTS reacts (id INTEGER, k TEXT, name TEXT, emoji TEXT, at INTEGER, PRIMARY KEY (id, k))');
    this.sql.exec('CREATE TABLE IF NOT EXISTS hidden (k TEXT, id INTEGER, PRIMARY KEY (k, id))');
    this.sql.exec('CREATE TABLE IF NOT EXISTS receipts (k TEXT, name TEXT, kind TEXT, up INTEGER, at INTEGER)');
  }

  row(r) {
    if (!r) return null;
    const reacts = {};
    for (const x of this.sql.exec('SELECT name, emoji FROM reacts WHERE id = ? ORDER BY at', r.id)) (reacts[x.emoji] = reacts[x.emoji] || []).push(x.name);
    return {
      id: r.id, cid: r.cid || null, from: r.frm, fk: r.fk, at: r.at, kind: r.kind, text: r.text || '', media: P(r.media), reply: P(r.reply), fwd: !!r.fwd,
      mentions: P(r.mentions) || [], loc: P(r.loc), live: P(r.live), contact: P(r.contact), reacts, edited: r.edited || null, deleted: !!r.deleted, sys: P(r.sys)
    };
  }
  one(id) { return this.row([...this.sql.exec('SELECT * FROM msgs WHERE id = ?', Number(id))][0]); }
  seq() { return Number([...this.sql.exec('SELECT MAX(id) AS m FROM msgs')][0].m) || 0; }

  // m: { fk, from, cid, kind, text, media, reply (id), fwd, mentions, loc, live, contact, sys, at }
  async send(m) {
    if (m.cid) {
      const dup = [...this.sql.exec('SELECT * FROM msgs WHERE fk = ? AND cid = ?', m.fk, m.cid)][0];
      if (dup) return { msg: this.row(dup), dup: true };
    }
    let reply = null;
    if (m.reply) {
      const o = this.one(m.reply);
      if (o) reply = { id: o.id, from: o.from, kind: o.kind, text: o.deleted ? '' : String(o.text || '').slice(0, 200) };
    }
    const id = this.seq() + 1;
    this.sql.exec('INSERT INTO msgs (id, cid, fk, frm, at, kind, text, media, reply, fwd, mentions, loc, live, contact, sys) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)',
      id, m.cid || null, m.fk || '', m.from || '', m.at, m.kind, m.text || '', J(m.media), J(reply), m.fwd ? 1 : 0, J(m.mentions && m.mentions.length ? m.mentions : null), J(m.loc), J(m.live), J(m.contact), J(m.sys));
    return { msg: this.one(id), dup: false };
  }

  // newest page before `before` (or after `after`), oldest first; hidden-for-me messages left out
  async page(k, before, after, limit) {
    limit = Math.min(Math.max(Number(limit) || 50, 1), 100);
    let rows;
    if (after != null && after !== '') rows = [...this.sql.exec('SELECT * FROM msgs WHERE id > ? AND id NOT IN (SELECT id FROM hidden WHERE k = ?) ORDER BY id ASC LIMIT ?', Number(after), k, limit + 1)];
    else rows = [...this.sql.exec('SELECT * FROM msgs WHERE id < ? AND id NOT IN (SELECT id FROM hidden WHERE k = ?) ORDER BY id DESC LIMIT ?', Number(before) || 9e15, k, limit + 1)].reverse();
    const more = rows.length > limit;
    if (more) rows = after != null && after !== '' ? rows.slice(0, limit) : rows.slice(1);
    return { msgs: rows.map(r => this.row(r)), more };
  }
  async get(id) { return this.one(id); }
  async byCid(fk, cid) { return this.row([...this.sql.exec('SELECT * FROM msgs WHERE fk = ? AND cid = ?', fk, cid)][0]); }
  async byIds(ids) { return ids.map(id => this.one(id)).filter(Boolean); }

  async edit(fk, id, text, now) {
    const m = this.one(id);
    if (!m) return { reason: 'not-found' };
    if (m.fk !== fk || m.sys) return { reason: 'not-yours' };
    if (m.deleted || now - m.at > EDIT_MS) return { reason: 'edit-window' };
    if (!['text', 'media', 'doc'].includes(m.kind) || (m.kind === 'text' && !text)) return { reason: 'bad-request' };
    this.sql.exec('UPDATE msgs SET text = ?, edited = ? WHERE id = ?', text, now, Number(id));
    return { msg: this.one(id) };
  }
  // delete for everyone: the content goes, the row stays (marked deleted)
  async remove(id) {
    if (!this.one(id)) return { reason: 'not-found' };
    this.sql.exec("UPDATE msgs SET text = '', media = NULL, loc = NULL, live = NULL, contact = NULL, mentions = NULL, reply = NULL, deleted = 1 WHERE id = ?", Number(id));
    this.sql.exec('DELETE FROM reacts WHERE id = ?', Number(id));
    return { msg: this.one(id) };
  }
  async hide(k, id) { this.sql.exec('INSERT OR IGNORE INTO hidden (k, id) VALUES (?, ?)', k, Number(id)); return true; }

  async react(k, name, id, emoji, now) {
    const m = this.one(id);
    if (!m || m.deleted) return { reason: 'not-found' };
    if (emoji) this.sql.exec('INSERT OR REPLACE INTO reacts (id, k, name, emoji, at) VALUES (?,?,?,?,?)', Number(id), k, name, emoji, now);
    else this.sql.exec('DELETE FROM reacts WHERE id = ? AND k = ?', Number(id), k);
    return { msg: this.one(id) };
  }

  async receipt(k, name, kind, up, at) { this.sql.exec('INSERT INTO receipts (k, name, kind, up, at) VALUES (?,?,?,?,?)', k, name, kind, Number(up), at); return true; }
  // who read / got message `id`, and when (the first receipt that covered it); the sender left out
  async info(id, fk) {
    const q = kind => [...this.sql.exec('SELECT k, name, MIN(at) AS at FROM receipts WHERE kind = ? AND up >= ? AND k != ? GROUP BY k ORDER BY at', kind, Number(id), fk)].map(r => ({ name: r.name, at: r.at }));
    const read = q('read'), dl = q('delivered');
    // read implies delivered
    read.forEach(r => { if (!dl.some(d => d.name === r.name)) dl.push(r); });
    return { read, delivered: dl.sort((a, b) => a.at - b.at) };
  }
  // messages from others after `up` (for the unread count)
  async unreadAfter(k, up) { return Number([...this.sql.exec('SELECT COUNT(*) AS n FROM msgs WHERE id > ? AND fk != ? AND deleted = 0 AND id NOT IN (SELECT id FROM hidden WHERE k = ?)', Number(up) || 0, k, k)][0].n) || 0; }

  async live(fk, id, lat, lng, now, stop) {
    const m = this.one(id);
    if (!m || m.kind !== 'live' || m.deleted) return { reason: 'not-found' };
    if (m.fk !== fk) return { reason: 'not-yours' };
    const lv = Object.assign({}, m.live);
    if (!stop && now > lv.until) return { reason: 'ended' };
    if (stop) lv.until = Math.min(lv.until, now);
    else { lv.lat = lat; lv.lng = lng; lv.at = now; }
    this.sql.exec('UPDATE msgs SET live = ? WHERE id = ?', J(lv), Number(id));
    return { msg: this.one(id) };
  }
}
