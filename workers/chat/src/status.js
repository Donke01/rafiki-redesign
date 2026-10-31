// Status (chatfx): like WhatsApp Status. Text on a colour, or a photo / short video (≤ 30 s) with a caption, for
// 24 hours, seen by the whole team or a chosen audience. Kept in the Directory's SQLite (statuses, status_views);
// these functions become Directory methods (Object.assign at the end of directory.js), so `this` is the Directory.
//
// The media are uploaded with the normal POST /media and `for=status`; index.js asks statusMediaOk() before serving
// such a file, so it stops working the moment its status ends or is deleted (the file itself is deleted soon after).
import { json, fail, rand, keyOf, clean, mediaUrl } from './util.js';
import { aud, allowed } from './privacy.js';

export const STATUS_MS = 24 * 3600e3;
export const COLORS = ['#12A37A', '#0A78C2', '#6E46C8', '#E8862A', '#06375A'];
const MAX_TEXT = 700, MAX_LIVE = 30, VIDEO_MAX = 30;
const P = s => { if (s == null || s === '') return null; try { return JSON.parse(s); } catch (e) { return null; } };
const isMediaId = s => /^[A-Za-z0-9_-]{16,40}$/.test(String(s || ''));
const num = v => (v === '' || v == null || !isFinite(Number(v)) ? null : Number(v));
const like = id => '%"' + String(id).replace(/[\\%_]/g, c => '\\' + c) + '"%';

function rowOf(r) {
  if (!r) return null;
  return { id: r.id, k: r.k, at: r.at, exp: r.exp, kind: r.kind, text: r.text || '', color: r.color || '', media: P(r.media), caption: r.caption || '',
    place: r.place || '', priv: P(r.priv) || { mode: 'all', names: [] }, via: P(r.via), cid: r.cid || '' };
}
const refs = st => (st && st.media ? [st.media.id, st.media.thumb, st.media.poster].filter(isMediaId) : []);

export const statusMethods = {
  statusById(id, now) { return rowOf(this.one('SELECT * FROM statuses WHERE id = ? AND exp > ?', String(id || ''), now || Date.now())); },
  // may `viewer` (a key) see this status?
  canSeeStatus(st, viewer) {
    if (!st || !viewer) return false;
    if (st.k === viewer) return true;
    const u = this.user(viewer);
    if (!u || u.blocked || u.removed) return false;
    return allowed(st.priv, st.k, viewer);
  },
  liveStatuses(now) {
    return this.q('SELECT s.* FROM statuses s JOIN users u ON u.k = s.k WHERE s.exp > ? AND u.blocked = 0 AND u.removed = 0 ORDER BY s.at', now).map(rowOf);
  },
  // send `payload` to every open socket whose person may see `st` (`mine`: also to the poster's own devices)
  statusTell(st, payload, mine) {
    const can = new Map(), s = JSON.stringify(payload);
    for (const ws of this.ctx.getWebSockets()) {
      const a = ws.deserializeAttachment() || {};
      if (!a.k || (a.k === st.k && !mine)) continue;
      if (!can.has(a.k)) can.set(a.k, this.canSeeStatus(st, a.k));
      if (can.get(a.k)) { try { ws.send(s); } catch (e) {} }
    }
  },
  // a status as `viewer` sees it
  async statusItem(st, viewer, key) {
    const it = { id: st.id, at: st.at, exp: st.exp, kind: st.kind, text: st.text, color: st.color, caption: st.caption, place: st.place, via: st.via ? { name: st.via.name } : null };
    if (st.media) {
      const m = st.media;
      it.media = { type: m.type, url: await mediaUrl(key, m.id) };
      for (const f of ['w', 'h', 'dur']) if (m[f] != null) it.media[f] = m[f];
      if (m.thumb) it.media.thumbUrl = await mediaUrl(key, m.thumb);
      if (m.poster) it.media.posterUrl = await mediaUrl(key, m.poster);
    }
    if (st.k === viewer) {
      const c = this.one('SELECT COUNT(*) AS n, SUM(liked) AS l, SUM(reshared) AS r FROM status_views WHERE s = ?', st.id);
      Object.assign(it, { mine: true, privacy: st.priv, views: Number(c.n) || 0, likes: Number(c.l) || 0, reshares: Number(c.r) || 0, seen: true, liked: false, canReshare: false });
    } else {
      const v = this.one('SELECT liked FROM status_views WHERE s = ? AND k = ?', st.id, viewer);
      Object.assign(it, { seen: !!v, liked: !!(v && v.liked), canReshare: st.priv.mode === 'all' });
    }
    return it;
  },

  // GET status: every status I may see, grouped per person: me first (always, even with none), then people with
  // something new (newest first), then those I've seen all of.
  async statusList(me) {
    const now = Date.now(), key = await this.key();
    await this.pruneStatuses(now);
    const by = new Map();
    for (const st of this.liveStatuses(now)) {
      if (!this.canSeeStatus(st, me.k)) continue;
      if (!by.has(st.k)) by.set(st.k, []);
      by.get(st.k).push(await this.statusItem(st, me.k, key));
    }
    const person = (k, items) => {
      const u = this.user(k) || { k, name: k };
      return { name: u.name, me: k === me.k, photo: this.photoFor(u, me.k), items, unseen: k === me.k ? 0 : items.filter(i => !i.seen).length, last: items.length ? items[items.length - 1].at : 0 };
    };
    const mine = person(me.k, by.get(me.k) || []);
    by.delete(me.k);
    const others = [...by.entries()].map(([k, items]) => person(k, items)).sort((a, b) => (b.unseen > 0) - (a.unseen > 0) || b.last - a.last);
    return json({ ok: true, now, people: [mine].concat(others) });
  },

  // POST status: a new status, or a reshare ({ reshareOf })
  async statusPost(me, b) {
    const now = Date.now(), cid = b.cid == null ? '' : clean(b.cid, 64);
    if (cid) {
      const had = rowOf(this.one('SELECT * FROM statuses WHERE k = ? AND cid = ? AND exp > ?', me.k, cid, now));
      if (had) return json({ ok: true, status: await this.statusItem(had, me.k, await this.key()) });
    }
    let priv = this.privOf(me.k).status;
    if (b.privacy != null) { priv = aud(b.privacy, 'status'); if (!priv) return fail('bad-request'); }
    const place = clean(b.place, 60);
    let st;
    if (b.reshareOf != null) {
      const o = this.statusById(b.reshareOf, now);
      if (!o || !this.canSeeStatus(o, me.k)) return fail('not-found', 404);
      if (o.k === me.k) return fail('own', 409);
      if (o.priv.mode !== 'all') return fail('private', 403);
      const ou = this.user(o.k) || { name: o.k };
      st = { kind: o.kind, text: o.text, color: o.color, media: o.media, caption: o.caption, place: place || o.place, via: o.via || { id: o.id, name: ou.name } };
      st.from = o;
    } else {
      const kind = String(b.kind || '');
      if (kind === 'text') {
        const text = clean(b.text, MAX_TEXT + 1);
        if (!text) return fail('bad-request');
        if (text.length > MAX_TEXT) return fail('too-long', 413);
        const color = String(b.color || COLORS[0]).toUpperCase();
        if (!COLORS.includes(color)) return fail('bad-request');
        st = { kind, text, color, media: null, caption: '' };
      } else if (kind === 'photo' || kind === 'video') {
        const m = b.media && typeof b.media === 'object' ? b.media : null;
        if (!m || !isMediaId(m.id) || (m.thumb != null && !isMediaId(m.thumb)) || (m.poster != null && !isMediaId(m.poster))) return fail('bad-request');
        const media = { id: m.id, type: kind };
        for (const f of ['w', 'h', 'dur']) if (num(m[f]) != null && num(m[f]) >= 0) media[f] = Math.round(num(m[f]) * 10) / 10;
        if (kind === 'video' && media.dur > VIDEO_MAX) return fail('too-long', 413, { max: VIDEO_MAX });
        if (m.thumb) media.thumb = m.thumb;
        if (m.poster) media.poster = m.poster;
        // every file must be mine and uploaded for a status (so it ends with the status)
        for (const id of refs({ media })) {
          const o = this.env.CHAT_MEDIA ? await this.env.CHAT_MEDIA.head('m/' + id) : null, md = (o && o.customMetadata) || {};
          if (!o || md.by !== me.k || md.for !== 'status') return fail('media');
          if (id === media.id && md.type !== kind) return fail('media');
        }
        const caption = clean(b.caption, MAX_TEXT + 1);
        if (caption.length > MAX_TEXT) return fail('too-long', 413);
        st = { kind, text: '', color: '', media, caption };
      } else return fail('bad-request');
      st.place = place;
      st.via = null;
    }
    if (this.limited(me.k, 'status')) return fail('rate', 429);
    if (Number(this.one('SELECT COUNT(*) AS n FROM statuses WHERE k = ? AND exp > ?', me.k, now).n) >= MAX_LIVE) return fail('too-many', 409, { max: MAX_LIVE });
    const id = rand(12), exp = now + STATUS_MS;
    this.sql.exec('INSERT INTO statuses (id, k, at, exp, kind, text, color, media, caption, place, priv, via, cid) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)',
      id, me.k, now, exp, st.kind, st.text, st.color, st.media ? JSON.stringify(st.media) : null, st.caption, st.place || '', JSON.stringify(priv), st.via ? JSON.stringify(st.via) : null, cid || null);
    if (st.from) {
      // the original's poster sees "reshared" in their viewers list
      this.sql.exec('INSERT INTO status_views (s, k, at, liked, reshared) VALUES (?,?,?,0,1) ON CONFLICT (s, k) DO UPDATE SET reshared = 1', st.from.id, me.k, now);
      this.statusToPoster(st.from, me);
    }
    await this.schedule(exp);
    const row = this.statusById(id, now);
    this.statusTell(row, { t: 'status', who: me.name, id, at: now }, true);
    return json({ ok: true, status: await this.statusItem(row, me.k, await this.key()) });
  },
  statusToPoster(st, me) {
    const v = this.one('SELECT at, liked, reshared FROM status_views WHERE s = ? AND k = ?', st.id, me.k);
    if (v) this.sendTo(st.k, { t: 'status-view', id: st.id, who: me.name, at: v.at, liked: !!v.liked, reshared: !!v.reshared });
  },
  // POST status-view { id } or { ids: [...] }: I saw it (my own and ones I may not see are skipped)
  async statusView(me, b) {
    const ids = (Array.isArray(b.ids) ? b.ids : [b.id]).filter(x => x != null).map(String).slice(0, 100);
    if (!ids.length) return fail('bad-request');
    const now = Date.now();
    let n = 0;
    for (const id of ids) {
      const st = this.statusById(id, now);
      if (!st || st.k === me.k || !this.canSeeStatus(st, me.k)) continue;
      if (this.one('SELECT 1 AS x FROM status_views WHERE s = ? AND k = ?', id, me.k)) continue;
      this.sql.exec('INSERT INTO status_views (s, k, at) VALUES (?,?,?)', id, me.k, now);
      this.statusToPoster(st, me);
      n++;
    }
    return json({ ok: true, n });
  },
  // POST status-like { id, on }
  async statusLike(me, b) {
    const now = Date.now(), st = this.statusById(b.id, now);
    if (!st || !this.canSeeStatus(st, me.k)) return fail('not-found', 404);
    if (st.k === me.k) return fail('own', 409);
    const on = b.on === false ? 0 : 1;
    this.sql.exec('INSERT INTO status_views (s, k, at, liked) VALUES (?,?,?,?) ON CONFLICT (s, k) DO UPDATE SET liked = ?', st.id, me.k, now, on, on);
    this.statusToPoster(st, me);
    return json({ ok: true, liked: !!on });
  },
  // GET status-viewers?id=: only the poster
  async statusViewers(me, id) {
    const st = this.statusById(id, Date.now());
    if (!st) return fail('not-found', 404);
    if (st.k !== me.k) return fail('not-yours', 403);
    const viewers = this.q('SELECT v.k, v.at, v.liked, v.reshared, u.name, u.photo FROM status_views v LEFT JOIN users u ON u.k = v.k WHERE v.s = ? ORDER BY v.at DESC', st.id)
      .map(r => ({ name: r.name || r.k, at: r.at, liked: !!r.liked, reshared: !!r.reshared, photo: this.photoFor({ k: r.k, name: r.name || r.k, photo: r.photo }, me.k) }));
    return json({ ok: true, id: st.id, views: viewers.length, likes: viewers.filter(v => v.liked).length, reshares: viewers.filter(v => v.reshared).length, viewers });
  },
  // POST status-delete { id }: only the poster
  async statusDelete(me, b) {
    const now = Date.now(), st = this.statusById(b.id, now);
    if (!st) return fail('not-found', 404);
    if (st.k !== me.k) return fail('not-yours', 403);
    this.statusTell(st, { t: 'status-gone', who: me.name, id: st.id }, true);
    await this.dropStatuses([st], now);
    return json({ ok: true });
  },
  // statuses gone (deleted or ended): their views go, and their files once no living status uses them
  async dropStatuses(list, now) {
    const ids = new Set();
    for (const st of list) {
      this.sql.exec('DELETE FROM statuses WHERE id = ?', st.id);
      this.sql.exec('DELETE FROM status_views WHERE s = ?', st.id);
      refs(st).forEach(x => ids.add(x));
    }
    const dead = [...ids].filter(id => !this.one("SELECT 1 AS x FROM statuses WHERE exp > ? AND media LIKE ? ESCAPE '\\'", now, like(id)));
    if (dead.length) await this.purgeLater(dead, 0);
  },
  async pruneStatuses(now) {
    const old = this.q('SELECT * FROM statuses WHERE exp <= ?', now).map(rowOf);
    if (old.length) await this.dropStatuses(old, now);
    return old.length;
  },
  // RPC from index.js before serving a file uploaded for a status: is a living status using it (and, for a
  // signed-in request, may that person see one)?
  async statusMediaOk(id, viewerName) {
    if (!isMediaId(id)) return false;
    const now = Date.now(), list = this.q("SELECT * FROM statuses WHERE exp > ? AND media LIKE ? ESCAPE '\\'", now, like(id)).map(rowOf);
    if (!list.length) return false;
    if (!viewerName) return true;
    const v = keyOf(viewerName);
    return list.some(st => this.canSeeStatus(st, v));
  },
  // a reply to a status, quoted in the message (send { c, statusRef: id }); only in the 1:1 chat with its poster
  statusQuote(me, ch, id) {
    const st = this.statusById(id, Date.now());
    if (!st || !this.canSeeStatus(st, me.k) || st.k === me.k) return { res: fail('not-found', 404) };
    if (ch.kind !== 'dm' || !this.member(ch.id, st.k)) return { res: fail('not-poster') };
    const u = this.user(st.k) || { name: st.k }, m = st.media || {};
    const quote = { id: st.id, from: u.name, kind: st.kind, text: String(st.text || st.caption || '').slice(0, 120), color: st.color || '', at: st.at };
    const thumb = st.kind === 'photo' ? m.thumb || m.id : st.kind === 'video' ? m.poster || m.thumb : '';
    if (thumb) quote.thumb = thumb;
    return { quote };
  }
};
