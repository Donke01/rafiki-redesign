// Privacy settings and the About line per person (chatfx), the Pages side. The settings live in KV pinprofiles next to
// the person's other profile fields ({ …, about, priv }); each change is also pushed to the chat worker (POST /people),
// whose copy (workers/chat/src/privacy.js) follows the same rules and enforces them on presence, statuses, read
// receipts and group adds. Here they decide profile photos and About in /api/pin, /api/team-photo and /api/account.
//
//   { lastSeen: Aud, photo: Aud, about: Aud, status: Aud (no 'none'), readReceipts: bool, groupAdd: 'all'|'admins' }
//   Aud = { mode: 'all'|'except'|'only'|'none', names: [display names] }
import { sameName } from './pin-store.js';

export const AUD_FIELDS = ['lastSeen', 'photo', 'about', 'status'];
const MODES = ['all', 'except', 'only', 'none'];
const MAX_NAMES = 200;
export const ABOUT_MAX = 140;
const lc = s => String(s || '').trim().toLowerCase();

export const DEF_PRIVACY = () => ({
  lastSeen: { mode: 'all', names: [] }, photo: { mode: 'all', names: [] }, about: { mode: 'all', names: [] },
  status: { mode: 'all', names: [] }, readReceipts: true, groupAdd: 'all'
});

export function aud(v, field) {
  if (!v || typeof v !== 'object' || !MODES.includes(v.mode)) return null;
  if (field === 'status' && v.mode === 'none') return null;
  const seen = new Set(), names = [];
  for (const n of Array.isArray(v.names) ? v.names : []) {
    const s = String(n == null ? '' : n).replace(/[\u0000-\u001F\u007F<>]/g, '').trim().slice(0, 80);
    if (s && !seen.has(lc(s))) { seen.add(lc(s)); names.push(s); }
    if (names.length >= MAX_NAMES) break;
  }
  return { mode: v.mode, names: v.mode === 'except' || v.mode === 'only' ? names : [] };
}
export function readPrivacy(p) {
  const out = DEF_PRIVACY();
  if (!p || typeof p !== 'object') return out;
  for (const f of AUD_FIELDS) { const a = aud(p[f], f); if (a) out[f] = a; }
  if (typeof p.readReceipts === 'boolean') out.readReceipts = p.readReceipts;
  if (p.groupAdd === 'admins' || p.groupAdd === 'all') out.groupAdd = p.groupAdd;
  return out;
}
// a change on top of the current settings; null when anything in it is invalid (nothing is half-saved)
export function mergePrivacy(cur, change) {
  if (!change || typeof change !== 'object' || Array.isArray(change)) return null;
  const out = readPrivacy(cur);
  for (const [k, v] of Object.entries(change)) {
    if (AUD_FIELDS.includes(k)) { const a = aud(v, k); if (!a) return null; out[k] = a; }
    else if (k === 'readReceipts') { if (typeof v !== 'boolean') return null; out.readReceipts = v; }
    else if (k === 'groupAdd') { if (v !== 'all' && v !== 'admins') return null; out.groupAdd = v; }
    else return null;
  }
  return out;
}
export function allowed(a, owner, viewer) {
  const o = lc(owner), v = lc(viewer);
  if (v && o === v) return true;
  if (!a || a.mode === 'all') return true;
  if (a.mode === 'none' || !v) return false;
  const inList = (a.names || []).some(n => lc(n) === v);
  return a.mode === 'except' ? !inList : inList;
}
// someone's stored profile from the pinprofiles map (or {})
export function profileOf(prof, name) { const k = Object.keys(prof || {}).find(n => sameName(n, name)); return (k && prof[k]) || {}; }
// may `viewer` (a name; '' = the shared team PIN) see `owner`'s `field` ('photo' | 'about' | 'lastSeen')?
export function sees(prof, owner, field, viewer) { return allowed(readPrivacy(profileOf(prof, owner).priv)[field], owner, viewer); }
// the About line: one line, at most 140 characters
export function cleanAbout(s) { return String(s == null ? '' : s).replace(/[\u0000-\u001F\u007F<>]/g, ' ').replace(/\s+/g, ' ').trim(); }
