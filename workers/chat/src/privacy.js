// Privacy settings per person (chatfx), the worker's copy. The Pages side (lib/privacy.js, same rules) keeps the
// source in KV pinprofiles and pushes each change here (internal POST /people), so the worker can enforce them on
// presence, profile photos, statuses, read receipts and group adds.
//
//   { lastSeen: Aud, photo: Aud, about: Aud, status: Aud (no 'none'), readReceipts: bool, groupAdd: 'all'|'admins' }
//   Aud = { mode: 'all'|'except'|'only'|'none', names: [display names] }
export const AUD_FIELDS = ['lastSeen', 'photo', 'about', 'status'];
const MODES = ['all', 'except', 'only', 'none'];
const MAX_NAMES = 200;
const lc = s => String(s || '').trim().toLowerCase();

export const DEF_PRIVACY = () => ({
  lastSeen: { mode: 'all', names: [] }, photo: { mode: 'all', names: [] }, about: { mode: 'all', names: [] },
  status: { mode: 'all', names: [] }, readReceipts: true, groupAdd: 'all'
});

// an audience from anything; null when it isn't valid
export function aud(v, field) {
  if (!v || typeof v !== 'object' || !MODES.includes(v.mode)) return null;
  if (field === 'status' && v.mode === 'none') return null;
  const seen = new Set(), names = [];
  for (const n of Array.isArray(v.names) ? v.names : []) {
    const s = String(n == null ? '' : n).replace(/[\u0000-\u001F\u007F]/g, '').trim().slice(0, 80);
    if (s && !seen.has(lc(s))) { seen.add(lc(s)); names.push(s); }
    if (names.length >= MAX_NAMES) break;
  }
  return { mode: v.mode, names: v.mode === 'except' || v.mode === 'only' ? names : [] };
}

// a full, clean settings object from a stored or sent one (unknown or broken parts fall back to the defaults)
export function readPrivacy(raw) {
  let p = raw;
  if (typeof raw === 'string') { try { p = JSON.parse(raw); } catch (e) { p = null; } }
  const out = DEF_PRIVACY();
  if (!p || typeof p !== 'object') return out;
  for (const f of AUD_FIELDS) { const a = aud(p[f], f); if (a) out[f] = a; }
  if (typeof p.readReceipts === 'boolean') out.readReceipts = p.readReceipts;
  if (p.groupAdd === 'admins' || p.groupAdd === 'all') out.groupAdd = p.groupAdd;
  return out;
}

// may `viewer` (a name or key; '' = not a named person) see what `owner` shares with audience `a`?
export function allowed(a, owner, viewer) {
  const o = lc(owner), v = lc(viewer);
  if (v && o === v) return true;
  if (!a || a.mode === 'all') return true;
  if (a.mode === 'none' || !v) return false;
  const inList = (a.names || []).some(n => lc(n) === v);
  return a.mode === 'except' ? !inList : inList;
}
