// /api/vehicles: the vehicles trips can use (Office › Vehicles). Same sign-in as /api/pin.
//   GET  /api/vehicles                        → { vehicles: [{ …, model, real, use }], types, fuels, admin }
//   POST /api/vehicles { action: "add" | "update" | "remove", … }
//        admins: any vehicle; field officers: add their own motorbike, and change or remove only their own.
// Each vehicle: name, plate, type (saloon, suv, pickup, 4x4, van, minibus, motorbike), engine cc, fuel (petrol, diesel),
// optional L/100 km; the consumption model (lib/fuel-model.js) and its fuel logs give `use`, the figure trip costs start from.
import { checkPin } from './pin.js';
import { getJson, putJson, sameName } from '../../lib/pin-store.js';
import { TYPES, FUELS, modelFor, useFor } from '../../lib/fuel-model.js';
import { newId, vehicles, fuelLogs } from '../../lib/itin.js';

const HEAD = { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff', 'X-Robots-Tag': 'noindex' };
const json = (b, s) => new Response(JSON.stringify(b), { status: s || 200, headers: HEAD });
const bad = (reason, status, extra) => json(Object.assign({ ok: false, reason }, extra || {}), status || 400);
const clean = (s, n) => String(s == null ? '' : s).replace(/[<>]/g, '').replace(/\s+/g, ' ').trim().slice(0, n);
export const MAX_VEHICLES = 200;

export async function withUse(env, v) { const logs = await fuelLogs(env, v.vid), u = useFor(v, logs); return Object.assign({}, v, { model: u.model, real: u.real, use: { lPer100: u.lPer100, src: u.src, weight: u.weight } }); }

/** validate a vehicle from the body; cur: the one being changed */
export function vehicleFrom(b, cur) {
  const v = Object.assign({}, cur || {});
  if (b.name != null) v.name = clean(b.name, 60);
  if (b.plate != null) v.plate = clean(b.plate, 20).toUpperCase();
  if (!v.name && v.plate) v.name = v.plate;
  if (!v.name) return { err: 'bad-name' };
  if (b.type != null) { if (!TYPES.includes(b.type)) return { err: 'bad-type' }; v.type = b.type; }
  if (!v.type) return { err: 'bad-type' };
  if (b.cc != null) { const cc = Math.round(Number(b.cc)); if (!(cc >= 49 && cc <= 8000)) return { err: 'bad-cc' }; v.cc = cc; }
  if (!v.cc) return { err: 'bad-cc' };
  if (b.fuel != null) { if (!FUELS.includes(b.fuel)) return { err: 'bad-fuel' }; v.fuel = b.fuel; }
  if (!v.fuel) v.fuel = v.type === 'motorbike' ? 'petrol' : '';
  if (!v.fuel) return { err: 'bad-fuel' };
  if (b.lPer100 !== undefined) { if (b.lPer100 === null || b.lPer100 === '') v.lPer100 = null; else { const l = Number(b.lPer100); if (!(l >= 1 && l <= 60)) return { err: 'bad-consumption' }; v.lPer100 = Math.round(l * 10) / 10; } }
  if (b.seats != null) v.seats = Math.max(1, Math.min(60, Math.round(Number(b.seats) || 1)));
  return { v };
}

export async function onRequestGet({ request, env }) {
  const chk = await checkPin(request, env);
  if (chk.res) return chk.res;
  const list = await vehicles(env), out = [];
  for (const v of list) out.push(await withUse(env, v));
  return json({ ok: true, vehicles: out, types: TYPES, fuels: FUELS, admin: !!chk.admin });
}

export async function onRequestPost({ request, env }) {
  const origin = request.headers.get('Origin');
  if (origin && new URL(origin).host !== new URL(request.url).host) return bad('origin', 403);
  const chk = await checkPin(request, env);
  if (chk.res) return chk.res;
  if (!chk.who) return bad('named-only', 403);
  if (!env.FEED) return bad('no-storage', 503);
  let b; try { b = await request.json(); } catch (e) { return bad('bad-request'); }
  if (!b || typeof b !== 'object') return bad('bad-request');
  const list = await vehicles(env);
  const mine = v => v && v.owner && sameName(v.owner, chk.who);
  if (b.action === 'add') {
    if (!chk.admin && b.type !== 'motorbike') return bad('not-admin', 403);
    if (list.length >= MAX_VEHICLES) return bad('too-many');
    const r = vehicleFrom(b, null);
    if (r.err) return bad(r.err);
    const v = Object.assign(r.v, { vid: newId('vh'), owner: chk.admin && !b.mine ? null : chk.who, added: new Date().toISOString(), by: chk.who });
    list.push(v); await putJson(env, 'vehicles', list);
    return json({ ok: true, vehicle: await withUse(env, v) }, 201);
  }
  const i = list.findIndex(x => x.vid === b.vid);
  if (i < 0) return bad('not-found', 404);
  if (!chk.admin && !mine(list[i])) return bad('forbidden', 403);
  if (b.action === 'update') {
    if (!chk.admin && b.type && b.type !== 'motorbike') return bad('not-admin', 403);
    const r = vehicleFrom(b, list[i]);
    if (r.err) return bad(r.err);
    list[i] = Object.assign(r.v, { updated: new Date().toISOString() });
    await putJson(env, 'vehicles', list);
    return json({ ok: true, vehicle: await withUse(env, list[i]) });
  }
  if (b.action === 'remove') {
    // still on a planned or active trip? ask first
    const idx = await getJson(env, 'itinidx', []);
    const busy = [];
    for (const r of idx.filter(x => x.status === 'planned' || x.status === 'active')) { const rec = await getJson(env, 'itin:' + r.id, null); if (rec && rec.vehicleId === b.vid) busy.push({ id: r.id, title: r.title }); }
    if (busy.length && !b.force) return bad('in-use', 409, { trips: busy });
    list.splice(i, 1); await putJson(env, 'vehicles', list);
    return json({ ok: true, removed: b.vid });
  }
  return bad('action');
}

export function onRequest() { return json({ ok: false, reason: 'method' }, 405); }
