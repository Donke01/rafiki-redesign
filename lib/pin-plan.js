// Office › Plan a week: suggests site visits for one person in one country (or area), grouped into days.
//
// What needs a visit, most important first:
//   - open repairs (not working before needs repair; urgent ones first)
//   - sites not visited for DUE_DAYS (180) days, or never visited with the app
//   - water points whose last water test (E. coli, chlorine…) is over a year old
// Visits already assigned and not done yet are left out, and so are projects with no location.
// The office can tick several areas (none ticked: the whole country), and choose projects by hand (planChosen):
// those are always planned, and the rest of each day is filled with suggestions (or not, with "only these projects").
//
// Days are made by greedy clustering: each day starts at the most important site left, then takes the
// best nearby sites (importance divided by distance) within MAX_KM of the day's centre, up to `per` stops.
// Each day is then put in driving order (nearest next, then crossings untangled), the same way as the
// route planner in the app. Distances are straight lines; roads are longer.
// Pure functions (no storage), so they can be tested with `node --test`.

export const DUE_DAYS = 180;
export const MAX_KM = 60;
const WATER = /borehole|collection|water|well|spring|tank/i;

export function metres(a, b) {
  const R = 6371000, r = x => x * Math.PI / 180;
  const dLa = r(b[0] - a[0]), dLo = r(b[1] - a[1]);
  const h = Math.sin(dLa / 2) ** 2 + Math.cos(r(a[0])) * Math.cos(r(b[0])) * Math.sin(dLo / 2) ** 2;
  return 2 * R * Math.asin(Math.sqrt(h));
}
export const isWater = p => WATER.test(String((p && p.type) || ''));
// "Kapsaret, Eldoret" → "Kapsaret": the first part of the area, as the app's place search uses it
export const areaOf = p => String((p && p.area) || '').split(',')[0].trim();
// areas ticked in the planner ("Kapsaret", "Kaigat"): a project is in when its first area part is one of them (any case);
// none ticked means every area
export const areaFilter = areas => {
  const set = new Set((areas || []).map(a => String(a).trim().toLowerCase()).filter(Boolean));
  return p => !set.size || set.has(areaOf(p).toLowerCase());
};

/**
 * Sites that need a visit, scored.
 * projects: [{ id, title, ck, area, type, gps:[lat,lng] }]   visits: { id: { at, state } }   repairs: { id: { state, urgency } }
 * assigned: [{ id, done }] (open assignments are skipped)       tests: Set of ids whose water test is due
 */
export const hasGps = p => !!p && Array.isArray(p.gps) && p.gps.length === 2 && p.gps[0] != null && p.gps[1] != null && isFinite(p.gps[0]) && isFinite(p.gps[1]);
// why one site needs a visit, and how much: { id, t, area, gps, why: [...], score, age }
function scoreOne(p, { visits, repairs, tests, now }) {
  const t0 = now || Date.now(), id = String(p.id);
  const v = visits && visits[id], r = repairs && repairs[id];
  const age = v && v.at ? (t0 - Date.parse(v.at)) / 864e5 : Infinity;
  const why = []; let score = 0;
  if (r) {
    why.push('repair'); score += r.state === 'notworking' ? 100 : 70;
    if (r.urgency === 'urgent') { why.push('urgent'); score += 40; } else if (r.urgency === 'month') score += 15;
  }
  if (age === Infinity) { why.push('never'); score += 45; }
  else if (age >= DUE_DAYS) { why.push('overdue'); score += 40 + Math.min(30, (age - DUE_DAYS) / 12); }
  if (tests && tests.has(id)) { why.push('test'); score += 20; }
  return { id, t: p.title || '', area: p.area || '', gps: [p.gps[0], p.gps[1]], why, score: Math.round(score * 10) / 10, age: age === Infinity ? null : Math.round(age) };
}

export function candidates({ projects, visits, repairs, assigned, tests, now, ck, area, areas }) {
  const open = new Set((assigned || []).filter(a => !a.done).map(a => String(a.id)));
  const ar = String(area || '').trim().toLowerCase(), inAreas = areaFilter(areas);
  const out = [];
  for (const p of projects || []) {
    if (!hasGps(p)) continue;
    if (ck && p.ck !== ck) continue;
    if (ar && String(p.area || '').toLowerCase().indexOf(ar) < 0) continue;
    if (!inAreas(p)) continue;
    if (open.has(String(p.id))) continue;
    const c = scoreOne(p, { visits, repairs, tests, now });
    if (c.why.length) out.push(c);
  }
  return out.sort((a, b) => b.score - a.score || a.id.localeCompare(b.id));
}

/**
 * The projects the office picked by hand, in the order given, each with chosen: true and its reasons (if any).
 * They are always planned, even when nothing is due and even when a visit is already assigned. Only ones with a location.
 */
export function chosenStops(projects, ids, { visits, repairs, tests, now } = {}) {
  const by = new Map((projects || []).map(p => [String(p && p.id), p]));
  return (ids || []).map(id => by.get(String(id))).filter(hasGps).map(p => Object.assign(scoreOne(p, { visits, repairs, tests, now }), { chosen: true }));
}

const centre = day => [day.reduce((s, c) => s + c.gps[0], 0) / day.length, day.reduce((s, c) => s + c.gps[1], 0) / day.length];

/** Greedy clustering: `days` groups of up to `per` stops, each within maxKm of its centre. */
export function cluster(cands, { days = 5, per = 5, maxKm = MAX_KM } = {}) {
  const left = cands.slice().sort((a, b) => b.score - a.score), out = [];
  for (let d = 0; d < days && left.length; d++) {
    const day = [left.shift()];
    while (day.length < per) {
      const c = centre(day); let bi = -1, bv = -1;
      left.forEach((x, i) => { const km = metres(c, x.gps) / 1000; if (km > maxKm) return; const v = x.score / (1 + km / 10); if (v > bv) { bv = v; bi = i; } });
      if (bi < 0) break;
      day.push(left.splice(bi, 1)[0]);
    }
    out.push(day);
  }
  // one k-means style pass: a stop closer to another day's centre moves there if that day has room
  for (let pass = 0; pass < 2; pass++) {
    const cs = out.map(centre);
    out.forEach((day, di) => day.slice().forEach(x => {
      if (day.length < 2) return;
      let bj = di, bd = metres(cs[di], x.gps);
      out.forEach((o, j) => { if (j !== di && o.length < per) { const d = metres(cs[j], x.gps); if (d < bd * 0.7) { bd = d; bj = j; } } });
      if (bj !== di) { day.splice(day.indexOf(x), 1); out[bj].push(x); }
    }));
  }
  return { days: out, spare: left };
}

/** Nearest stop next from `start` (or the first stop), then 2-opt to untangle crossings. */
export function orderDay(stops, start) {
  const pts = stops.slice(), out = [];
  let cur = start || (pts[0] && pts[0].gps);
  while (pts.length) { let bi = 0, bd = Infinity; pts.forEach((p, i) => { const d = metres(cur, p.gps); if (d < bd) { bd = d; bi = i; } }); const nx = pts.splice(bi, 1)[0]; out.push(nx); cur = nx.gps; }
  const len = a => { let s = start && a.length ? metres(start, a[0].gps) : 0; for (let i = 1; i < a.length; i++) s += metres(a[i - 1].gps, a[i].gps); return s; };
  let best = out;
  for (let pass = 0, better = true; better && pass < 20; pass++) {
    better = false;
    for (let i = 0; i < best.length - 1; i++) for (let k = i + 1; k < best.length; k++) {
      const t = best.slice(0, i).concat(best.slice(i, k + 1).reverse(), best.slice(k + 1));
      if (len(t) + 1 < len(best)) { best = t; better = true; }
    }
  }
  return best;
}

/** Straight-line kilometres along the stops, in order. */
export function routeKm(stops, start) {
  let m = start && stops.length ? metres(start, stops[0].gps) : 0;
  for (let i = 1; i < stops.length; i++) m += metres(stops[i - 1].gps, stops[i].gps);
  return Math.round(m / 100) / 10;
}

/**
 * A week built around projects chosen by hand: every chosen one is planned (more days, then more stops a day,
 * when they don't fit), grouped by distance, and the room left each day is filled with the usual suggestions
 * near that day (none with only: true). Returns { days, spare, perDay, dayCount }.
 */
export function planChosen(chosen, cands, opts = {}) {
  let days = Math.max(1, Math.min(6, Math.round(Number(opts.days) || 5)));
  let per = Math.max(1, Math.min(8, Math.round(Number(opts.per) || 5)));
  const maxKm = opts.maxKm || MAX_KM, n = chosen.length;
  if (n > days * per) { days = Math.min(6, Math.ceil(n / per)); if (n > days * per) per = Math.ceil(n / days); }
  const mine = new Set(chosen.map(c => c.id));
  // the chosen ones first: seeded far apart, near ones together; any left over go to the nearest day with room
  const ch = chosen.map(c => Object.assign({}, c, { score: 1000 + (c.score || 0) }));
  const { days: groups, spare: over } = cluster(ch, { days, per, maxKm });
  over.forEach(x => {
    let bi = -1, bd = Infinity;
    groups.forEach((g, i) => { if (g.length < per) { const d = metres(centre(g), x.gps); if (d < bd) { bd = d; bi = i; } } });
    if (bi < 0) groups.push([x]); else groups[bi].push(x);
  });
  let left = opts.only ? [] : cands.filter(c => !mine.has(c.id)).sort((a, b) => b.score - a.score);
  // fill each chosen day with the best suggestions near it
  groups.forEach(day => {
    while (day.length < per && left.length) {
      const c = centre(day); let bi = -1, bv = -1;
      left.forEach((x, i) => { const km = metres(c, x.gps) / 1000; if (km > maxKm) return; const v = x.score / (1 + km / 10); if (v > bv) { bv = v; bi = i; } });
      if (bi < 0) break;
      day.push(left.splice(bi, 1)[0]);
    }
  });
  // days still free get the usual suggestions
  if (groups.length < days && left.length) { const more = cluster(left, { days: days - groups.length, per, maxKm }); more.days.forEach(d => groups.push(d)); left = more.spare; }
  const back = c => (c.chosen ? Object.assign({}, c, { score: c.score - 1000 }) : c);
  return {
    days: groups.map(g => { const stops = orderDay(g).map(back); return { stops, km: routeKm(stops) }; }),
    spare: left.slice(0, 40), perDay: per, dayCount: days
  };
}

/** The whole suggestion: days in order of importance (the most urgent day first), each in driving order. */
export function planWeek(cands, opts = {}) {
  const days = Math.max(1, Math.min(6, Math.round(Number(opts.days) || 5)));
  const per = Math.max(1, Math.min(8, Math.round(Number(opts.per) || 5)));
  const { days: groups, spare } = cluster(cands, { days, per, maxKm: opts.maxKm || MAX_KM });
  return {
    days: groups.map(g => { const stops = orderDay(g); return { stops, km: routeKm(stops) }; }),
    spare: spare.slice(0, 40)
  };
}
