// GET /api/trip/week: the driest day for a list of projects (Office › Plan a week, assigning a visit).
//   ?ids=104,105,106[&start=2026-10-26][&days=5][&groups=104,105|106]
//   ids:    projects (digits, at most 60, each with a location in the feed)
//   start:  the first day (default today, Nairobi); days: how many (1–16, default 5)
//   groups: the planned days, as id lists separated by "|": each gets its own driest day
// Weather only (no routes), so it costs no OpenRouteService calls. Same sign-in as /api/pin. See lib/trip.js weekAdvice.
import { checkPin } from '../pin.js';
import { json, projectsById } from '../trip.js';
import { weekAdvice } from '../../../lib/trip.js';

const strip = s => String(s || '').replace(/^[\s*#_-]+|[\s*#_]+$/g, '');

export async function onRequestGet({ request, env, waitUntil }) {
  const chk = await checkPin(request, env);
  if (chk.res) return chk.res;
  const q = new URL(request.url).searchParams;
  const ids = String(q.get('ids') || '').split(',').map(s => s.trim()).filter(Boolean);
  if (!ids.length || ids.length > 60 || !ids.every(x => /^\d{1,15}$/.test(x))) return json({ ok: false, reason: 'bad-request' }, 400);
  const start = q.get('start') || '';
  if (start && !/^\d{4}-\d{2}-\d{2}$/.test(start)) return json({ ok: false, reason: 'date' }, 400);
  const groupsRaw = String(q.get('groups') || '').trim();
  const groups = groupsRaw ? groupsRaw.split('|').map(g => g.split(',').map(s => s.trim()).filter(Boolean)) : [];
  if (groups.length > 7 || groups.some(g => !g.every(x => /^\d{1,15}$/.test(x)))) return json({ ok: false, reason: 'bad-request' }, 400);
  try {
    const byId = await projectsById(env, waitUntil, true);
    const projects = [], nogps = [];
    [...new Set(ids)].forEach(id => {
      const p = byId.get(id);
      if (p && Array.isArray(p.gps) && p.gps[0] != null && isFinite(p.gps[0]) && isFinite(p.gps[1])) projects.push({ id, name: strip(p.title), lat: Number(p.gps[0]), lng: Number(p.gps[1]) });
      else nogps.push(id);
    });
    if (!projects.length) return json({ ok: false, reason: 'stops', nogps }, 400);
    const r = await weekAdvice(env, { projects, start, days: q.get('days'), groups });
    if (r.ok) r.nogps = nogps;
    return json(r, r.ok ? 200 : 400);
  } catch (e) { console.error('trip week:', e && e.message); return json({ ok: false, reason: 'down' }, 502); }
}

export function onRequest() { return json({ ok: false, reason: 'method' }, 405); }
