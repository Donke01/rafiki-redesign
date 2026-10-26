// GET /pin-trip?t=<token>: the private, read-only trip page for a share link (a field itinerary or a group trip).
// Server-rendered, mobile-first and print-friendly ("Save as PDF" from the browser). It checks for changes every minute
// (/api/trip-share with ETag) and reloads when the Office changes the plan. "Add to home screen": &manifest=1 is the
// web app manifest for this one link. No PINs, phone numbers of the field team, costs or private notes appear here.
// Field trips can be shown in Kiswahili with &lang=sw; group trips are in English.
import { lookupShare, etagOf } from './api/trip-share.js';
import { publicItin } from '../lib/itin.js';
import { publicGroup } from '../lib/group-trips.js';

const esc = s => String(s == null ? '' : s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const HEAD = { 'Cache-Control': 'private, no-store', 'X-Robots-Tag': 'noindex, nofollow', 'Referrer-Policy': 'strict-origin', 'X-Content-Type-Options': 'nosniff' };
const MON = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'], MON_SW = ['Jan', 'Feb', 'Mac', 'Apr', 'Mei', 'Jun', 'Jul', 'Ago', 'Sep', 'Okt', 'Nov', 'Des'];
const DAY = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'], DAY_SW = ['Jpl', 'Jtt', 'Jnn', 'Jtn', 'Alh', 'Iju', 'Jmo'];
const dayLabel = (iso, sw) => { const d = new Date(iso + 'T00:00:00Z'); return (sw ? DAY_SW : DAY)[d.getUTCDay()] + ' ' + d.getUTCDate() + ' ' + (sw ? MON_SW : MON)[d.getUTCMonth()]; };
const span = (a, b, sw) => a === b ? dayLabel(a, sw) : dayLabel(a, sw) + ' – ' + dayLabel(b, sw);
const W = {
  en: { day: 'Day', leave: 'Leave', drive: 'Drive', lunch: 'Lunch', wait: 'Wait', back: 'Back at', night: 'Overnight in', stay: 'Stay', km: 'km', min: 'min', people: 'Team', updated: 'Updated', print: 'Print or save as PDF', add: 'Add to home screen', addIos: 'Tap Share, then “Add to Home Screen”.', addOther: 'Open your browser menu and choose “Add to Home screen”.', gone: 'This trip link has expired or was turned off.', map: 'Open in Google Maps' },
  sw: { day: 'Siku', leave: 'Kuondoka', drive: 'Safari', lunch: 'Chakula cha mchana', wait: 'Kusubiri', back: 'Kurudi', night: 'Kulala', stay: 'Malazi', km: 'km', min: 'dak', people: 'Timu', updated: 'Imesasishwa', print: 'Chapisha au hifadhi kama PDF', add: 'Weka kwenye skrini ya mwanzo', addIos: 'Gusa Shiriki, kisha “Add to Home Screen”.', addOther: 'Fungua menyu ya kivinjari na uchague “Add to Home screen”.', gone: 'Kiungo hiki cha safari kimeisha muda au kimezimwa.', map: 'Fungua kwenye Google Maps' }
};
const nbsp = '&nbsp;';
const js = v => JSON.stringify(v).replace(/</g, '\\u003c');
const gmaps = (lat, lng) => 'https://www.google.com/maps/search/?api=1&query=' + lat + ',' + lng;

function page(title, body, opts = {}) {
  const map = opts.points && opts.points.length;
  return '<!doctype html><html lang="' + (opts.lang || 'en') + '"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><meta name="robots" content="noindex, nofollow">' +
    '<title>' + esc(title) + ' — RAFIKI Outreach</title><meta name="theme-color" content="#04263F">' +
    (opts.manifest ? '<link rel="manifest" href="' + esc(opts.manifest) + '"><link rel="apple-touch-icon" href="/apple-touch-icon.png"><meta name="apple-mobile-web-app-capable" content="yes">' : '') +
    (map ? '<link rel="stylesheet" href="/vendor/leaflet/leaflet.css">' : '') +
    '<style>:root{--ink:#141618;--muted:#5A6066;--line:#E3E8ED;--bg:#FAF8F3;--card:#fff;--brand:#04263F;--blue:#0A78C2}' +
    '@media (prefers-color-scheme:dark){:root{--ink:#EEF1F4;--muted:#A7B0B8;--line:#2A333B;--bg:#0F1418;--card:#171E24;--brand:#9CC9EE;--blue:#6DB6EE}}' +
    '*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--ink);font:16px/1.55 Montserrat,system-ui,-apple-system,"Segoe UI",sans-serif;-webkit-text-size-adjust:100%}' +
    'main{max-width:760px;margin:0 auto;padding:20px 16px 48px}h1{font-size:24px;line-height:1.25;margin:4px 0 4px;color:var(--brand);overflow-wrap:break-word;hyphens:none}h2{font-size:18px;margin:28px 0 10px;color:var(--brand)}' +
    '.sub{color:var(--muted);margin:0 0 4px}.card{background:var(--card);border:1px solid var(--line);border-radius:14px;padding:14px 16px;margin:10px 0}' +
    '.row{display:flex;gap:12px;align-items:baseline;padding:6px 0;border-top:1px solid var(--line)}.row:first-child{border-top:0}.t{flex:0 0 auto;min-width:6.2em;font-variant-numeric:tabular-nums;font-weight:700;white-space:nowrap}' +
    '.w{flex:1 1 auto;min-width:0;overflow-wrap:break-word;word-break:normal;hyphens:none}.muted{color:var(--muted)}.chip{display:inline-block;white-space:nowrap;font-size:13px;color:var(--muted);margin-right:10px}' +
    'a{color:var(--blue);font-weight:600}pre{white-space:pre-wrap;font:inherit;margin:0}#map{height:300px;border-radius:14px;border:1px solid var(--line);margin:10px 0}' +
    '.bar{display:flex;flex-wrap:wrap;gap:8px;margin:14px 0}.btn{font:inherit;font-weight:700;border:1px solid var(--line);background:var(--card);color:var(--ink);border-radius:999px;padding:9px 16px;min-height:44px;cursor:pointer;white-space:nowrap}' +
    '#add{display:none}.hint{font-size:14px;color:var(--muted)}img.logo{height:34px}@media print{.bar,#add,.hint{display:none}#map{display:none}body{background:#fff;color:#000}.card{break-inside:avoid}}</style></head>' +
    '<body><main><img class="logo" src="/img/logo-dark-bold.png" alt="RAFIKI Outreach">' + body + '</main>' +
    (map ? '<script src="/vendor/leaflet/leaflet.js"></script><script>(function(){var p=' + js(opts.points) + ',l=' + js(opts.lines || []) + ';if(!window.L)return;var m=L.map("map",{scrollWheelZoom:false});' +
      'L.tileLayer("https://tile.openstreetmap.org/{z}/{x}/{y}.png",{maxZoom:19,attribution:"&copy; OpenStreetMap contributors"}).addTo(m);var b=[];' +
      'l.forEach(function(x){L.polyline(x,{color:"#0A78C2",weight:4,opacity:.75}).addTo(m)});p.forEach(function(x){b.push([x.lat,x.lng]);L.circleMarker([x.lat,x.lng],{radius:7,color:"#04263F",fillColor:"#FF6B4A",fillOpacity:1,weight:2}).addTo(m).bindPopup(x.name.replace(/[<>&]/g,""))});' +
      'if(b.length>1)m.fitBounds(b,{padding:[24,24]});else m.setView(b[0],12)})()</script>' : '') +
    '<script>(function(){var t=' + js(opts.token || '') + ',tag=' + js(opts.etag || '') + ';' +
    // live updates: reload when the trip changes
    'if(t)setInterval(function(){if(document.hidden)return;fetch("/api/trip-share?t="+encodeURIComponent(t),{headers:{"If-None-Match":tag}}).then(function(r){if(r.status===200)location.reload()}).catch(function(){})},60000);' +
    // add to home screen
    'var a=document.getElementById("add"),h=document.getElementById("addhint"),d=null;if(!a)return;var standalone=matchMedia("(display-mode: standalone)").matches||navigator.standalone;if(standalone)return;a.style.display="inline-block";' +
    'addEventListener("beforeinstallprompt",function(e){e.preventDefault();d=e});a.onclick=function(){if(d){d.prompt();d=null;return}h.textContent=/iphone|ipad|ipod/i.test(navigator.userAgent)?h.dataset.ios:h.dataset.other;h.hidden=false};' +
    'var p=document.getElementById("print");if(p)p.onclick=function(){print()}})()</script></body></html>';
}

function itinBody(t, sw, w) {
  let h = '<h1>' + esc(t.title) + '</h1><p class="sub">' + esc(span(t.from, t.to, sw)) + '</p>' + (t.people.length ? '<p class="sub">' + w.people + ': ' + esc(t.people.join(', ')) + '</p>' : '');
  h += '<div class="bar"><button class="btn" id="add">' + w.add + '</button><button class="btn" id="print">' + w.print + '</button></div><p class="hint" id="addhint" hidden data-ios="' + esc(w.addIos) + '" data-other="' + esc(w.addOther) + '"></p>';
  if (t.stops.length) h += '<div id="map"></div>';
  t.days.forEach((d, i) => {
    h += '<h2>' + w.day + ' ' + (i + 1) + ' · ' + esc(dayLabel(d.date, sw)) + '</h2><div class="card">';
    d.timeline.forEach(x => {
      if (x.kind === 'leave') h += '<div class="row"><span class="t">' + esc(x.at) + '</span><span class="w">' + w.leave + (x.place ? ' · ' + esc(x.place) : '') + '</span></div>';
      else if (x.kind === 'drive') h += '<div class="row"><span class="t muted">' + esc(x.dep) + '</span><span class="w muted">' + w.drive + ' <span class="chip">' + Math.round(x.km) + nbsp + w.km + '</span><span class="chip">' + x.min + nbsp + w.min + '</span>' + (x.chips && x.chips.surface ? '<span class="chip">' + esc(sw ? x.chips.surface.sw : x.chips.surface.en) + '</span>' : '') + '</span></div>';
      else if (x.kind === 'stop') h += '<div class="row"><span class="t">' + esc(x.arrive) + '–' + esc(x.leave) + '</span><span class="w"><b>' + esc(x.name) + '</b></span></div>';
      else if (x.kind === 'lunch') h += '<div class="row"><span class="t muted">' + esc(x.from) + '</span><span class="w muted">' + w.lunch + '</span></div>';
      else if (x.kind === 'wait') h += '<div class="row"><span class="t muted">' + esc(x.from) + '</span><span class="w muted">' + w.wait + '</span></div>';
      else if (x.kind === 'overnight') h += '<div class="row"><span class="t">' + esc(x.at) + '</span><span class="w">' + w.night + ' ' + esc(x.place || (d.overnight && d.overnight.town) || '') + (d.overnight && d.overnight.lodging ? ' · ' + w.stay + ': ' + esc(d.overnight.lodging.name) : '') + '</span></div>';
      else if (x.kind === 'return') h += '<div class="row"><span class="t">' + esc(x.at) + '</span><span class="w">' + w.back + ' ' + esc(x.place) + '</span></div>';
    });
    h += '</div>';
  });
  return h;
}
const KIND = { flight: 'Flight', transfer: 'Transfer', lodging: 'Stay', project: 'Project day', school: 'School visit', safari: 'Safari', free: 'Free time', meal: 'Meal', other: '' };
function groupBody(t) {
  let h = '<h1>' + esc(t.name) + '</h1><p class="sub">' + esc(span(t.from, t.to, false)) + (t.destination ? ' · ' + esc(t.destination) : '') + '</p>' + (t.summary ? '<p>' + esc(t.summary) + '</p>' : '');
  h += '<div class="bar"><button class="btn" id="add">Add to home screen</button><button class="btn" id="print">Print or save as PDF</button></div><p class="hint" id="addhint" hidden data-ios="' + esc(W.en.addIos) + '" data-other="' + esc(W.en.addOther) + '"></p>';
  if (t.map.length) h += '<div id="map"></div>';
  t.days.forEach(d => {
    h += '<h2>' + esc(dayLabel(d.date, false)) + '</h2><div class="card">';
    d.items.forEach(i => {
      const time = i.start ? esc(i.start) + (i.end ? '–' + esc(i.end) : '') : '';
      let more = '';
      if (i.flight) more += '<br><span class="muted">' + esc([i.flight.airline, i.flight.number].filter(Boolean).join(' ')) + (i.flight.from || i.flight.to ? ' · ' + esc([i.flight.from, i.flight.to].filter(Boolean).join(' → ')) : '') + (i.flight.depart ? ' · departs ' + esc(i.flight.depart) : '') + (i.flight.arrive ? ' · arrives ' + esc(i.flight.arrive) : '') + (i.flight.checkIn ? ' · check in by ' + esc(i.flight.checkIn) : '') + '</span>';
      if (i.lodging && i.lodging.name) more += '<br><span class="muted">' + esc(i.lodging.name) + (i.lodging.address ? ', ' + esc(i.lodging.address) : '') + (i.lodging.checkIn ? ' · check in ' + esc(i.lodging.checkIn) : '') + (i.lodging.checkOut ? ' · check out ' + esc(i.lodging.checkOut) : '') + '</span>';
      if (i.location && (i.location.name || i.location.address)) more += '<br><span class="muted">' + esc([i.location.name, i.location.address].filter(Boolean).join(', ')) + '</span>' + (i.location.lat != null ? ' <a href="' + esc(gmaps(i.location.lat, i.location.lng)) + '" rel="noopener" target="_blank">Map</a>' : '');
      if (i.notes) more += '<br>' + esc(i.notes).replace(/\n/g, '<br>');
      if (i.projectUrl) more += '<br><a href="' + esc(i.projectUrl) + '">About this project</a>';
      i.attachments.forEach(a => { more += '<br><a href="' + esc(a.url) + '" rel="noopener" target="_blank">' + esc(a.name) + '</a>'; });
      h += '<div class="row"><span class="t">' + (time || '&nbsp;') + '</span><span class="w"><b>' + esc(i.title) + '</b>' + (KIND[i.kind] ? ' <span class="chip">' + KIND[i.kind] + '</span>' : '') + more + '</span></div>';
    });
    h += '</div>';
  });
  if (t.packing) h += '<h2>Packing list</h2><div class="card"><pre>' + esc(t.packing) + '</pre></div>';
  if (t.expect) h += '<h2>What to expect</h2><div class="card"><pre>' + esc(t.expect).replace(/(https:\/\/[^\s<]+)/g, '<a href="$1" rel="noopener" target="_blank">$1</a>') + '</pre></div>';
  if (t.emergency.length) h += '<h2>Emergency contacts</h2><div class="card">' + t.emergency.map(e => '<div class="row"><span class="w"><b>' + esc(e.name) + '</b>' + (e.role ? ' · ' + esc(e.role) : '') + '</span><a href="tel:' + esc(e.phone.replace(/\s/g, '')) + '" style="white-space:nowrap">' + esc(e.phone) + '</a></div>').join('') + '</div>';
  h += '<p class="hint">Questions: <a href="mailto:' + esc(t.contact) + '">' + esc(t.contact) + '</a></p>';
  return h;
}

export async function onRequestGet({ request, env }) {
  const url = new URL(request.url), token = url.searchParams.get('t') || '', sw = url.searchParams.get('lang') === 'sw';
  const r = await lookupShare(request, env, token);
  if (r.res) {
    const w = W[sw ? 'sw' : 'en'];
    return new Response(page('Trip', '<h1>' + esc(w.gone) + '</h1>'), { status: r.res.status === 429 ? 429 : 404, headers: Object.assign({ 'Content-Type': 'text/html; charset=utf-8' }, HEAD) });
  }
  const isGroup = r.kind === 'group', site = url.origin;
  const t = isGroup ? publicGroup(r.rec, site) : publicItin(r.rec);
  const title = isGroup ? t.name : t.title;
  if (url.searchParams.get('manifest')) {
    const m = { name: title, short_name: title.slice(0, 24), start_url: '/pin-trip?t=' + token + (sw ? '&lang=sw' : ''), scope: '/pin-trip', display: 'standalone', background_color: '#FAF8F3', theme_color: '#04263F',
      icons: [{ src: '/apple-touch-icon.png', sizes: '180x180', type: 'image/png' }] };
    return new Response(JSON.stringify(m), { headers: Object.assign({ 'Content-Type': 'application/manifest+json; charset=utf-8' }, HEAD) });
  }
  const w = W[sw && !isGroup ? 'sw' : 'en'];
  const body = isGroup ? groupBody(t) : itinBody(t, sw, w);
  const points = isGroup ? t.map : t.stops;
  return new Response(page(title, body, { lang: sw && !isGroup ? 'sw' : 'en', points, lines: isGroup ? [] : t.lines, token, etag: etagOf(r.kind, r.rec), manifest: '/pin-trip?t=' + token + (sw ? '&lang=sw' : '') + '&manifest=1' }), { headers: Object.assign({ 'Content-Type': 'text/html; charset=utf-8' }, HEAD) });
}
