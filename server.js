// Tiny static server for hosts like Railway (Vercel ignores this file and serves the folder directly).
// The design runtime (support.js) builds each page's logic with `new Function`, so the
// Content-Security-Policy must allow 'unsafe-eval'; some host templates block it by default.
const http = require('http');
const https = require('https');
const fs = require('fs');
const path = require('path');

const ROOT = __dirname;
const PORT = process.env.PORT || 3000;
const TYPES = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8', '.json': 'application/json', '.png': 'image/png', '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.svg': 'image/svg+xml', '.webp': 'image/webp', '.ico': 'image/x-icon', '.xml': 'application/xml; charset=utf-8', '.txt': 'text/plain; charset=utf-8' };
const HIDDEN = new Set(['server.js', 'package.json', 'package-lock.json', 'vercel.json', 'readme.md', '404', '404.html']);
const CSP = "default-src 'self'; script-src 'self' 'unsafe-inline' 'unsafe-eval' https://cdn.jsdelivr.net https://challenges.cloudflare.com; frame-src https://challenges.cloudflare.com; style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; font-src 'self' https://fonts.gstatic.com data:; img-src 'self' data: https:; connect-src 'self' https:";

function resolve(urlPath) {
  let clean;
  try { clean = decodeURIComponent(urlPath.split('?')[0]); } catch (e) { return null; }
  if (HIDDEN.has(path.basename(clean).toLowerCase()) || clean.startsWith('/.')) return null;
  const safe = path.normalize(clean).replace(/^(\.\.[/\\])+/, '');
  let file = path.join(ROOT, safe);
  if (!file.startsWith(ROOT)) return null;
  if (fs.existsSync(file) && fs.statSync(file).isDirectory()) file = path.join(file, 'index.html');
  if (!fs.existsSync(file) && fs.existsSync(file + '.html')) file += '.html';
  return fs.existsSync(file) ? file : null;
}

// This host has no data functions of its own, so /api/* is forwarded to the Cloudflare site.
const API_ORIGIN = new URL(process.env.API_ORIGIN || 'https://rafiki-redesign.pages.dev');
function proxyApi(req, res) {
  const fwd = {};
  ['accept', 'content-type', 'content-length', 'user-agent', 'accept-language'].forEach(h => { if (req.headers[h]) fwd[h] = req.headers[h]; });
  const ip = (req.headers['x-forwarded-for'] || req.socket.remoteAddress || '').split(',')[0].trim();
  if (ip) fwd['x-forwarded-for'] = ip;
  const up = https.request({ hostname: API_ORIGIN.hostname, port: 443, path: req.url, method: req.method, headers: { ...fwd, host: API_ORIGIN.hostname }, timeout: 25000 }, r => {
    const h = {};
    ['content-type', 'cache-control', 'etag', 'last-modified', 'content-length'].forEach(k => { if (r.headers[k]) h[k] = r.headers[k]; });
    res.writeHead(r.statusCode || 502, h);
    r.pipe(res);
  });
  up.on('timeout', () => up.destroy(new Error('timeout')));
  up.on('error', () => { if (!res.headersSent) { res.writeHead(502, { 'Content-Type': 'application/json' }); } res.end('{"error":"unavailable"}'); });
  req.pipe(up);
}

http.createServer((req, res) => {
  const REDIRECTS = { '/about-1': '/communities', '/about-1.html': '/communities', '/meet': '/about', '/meet.html': '/about',
    // old Wix URLs (keep in step with _redirects and vercel.json)
    '/home': '/', '/blog': '/updates', '/rally': '/event-list', '/book-online': '/trips', '/submit-your-application': '/contact',
    '/artificial-intelligence-researcher': '/donate', '/deep-learning-engineer': '/contact', '/donate-1': '/donate' };
  const [p0, q0] = req.url.split('?');
  // trailing slash: /about/ -> /about (same as Vercel's trailingSlash:false)
  if (p0.length > 1 && p0.endsWith('/')) { const t = p0.replace(/\/+$/, '') || '/'; res.writeHead(301, { Location: (REDIRECTS[t] || t) + (q0 ? '?' + q0 : '') }); return res.end(); }
  // share links /project/<id>: on Cloudflare functions/project/[id].js adds link-preview tags; here just go to the project
  const pj = /^\/project\/(\d{1,20})$/.exec(p0);
  if (pj) { res.writeHead(302, { Location: '/stories?project=' + pj[1], 'Cache-Control': 'no-store' }); return res.end(); }
  // live data (/api/projects, photos, contact form, newsletter) runs on Cloudflare: pass it through
  if (p0.startsWith('/api/')) return proxyApi(req, res);
  const target = REDIRECTS[p0];
  if (target) { res.writeHead(301, { Location: target }); return res.end(); }
  const file = resolve(req.url);
  const headers = { 'Content-Security-Policy': CSP, 'X-Content-Type-Options': 'nosniff' };
  if (!file) {
    const nf = path.join(ROOT, '404.html');
    if (fs.existsSync(nf)) { res.writeHead(404, { ...headers, 'Content-Type': 'text/html; charset=utf-8' }); return fs.createReadStream(nf).pipe(res); }
    res.writeHead(404, { ...headers, 'Content-Type': 'text/plain' });
    return res.end('Not found');
  }
  headers['Content-Type'] = TYPES[path.extname(file).toLowerCase()] || 'application/octet-stream';
  headers['Cache-Control'] = file.endsWith('.html') ? 'no-cache' : 'public, max-age=3600';
  res.writeHead(200, headers);
  fs.createReadStream(file).pipe(res);
}).listen(PORT, () => console.log('Rafiki redesign preview on port ' + PORT));
