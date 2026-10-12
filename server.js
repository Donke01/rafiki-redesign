// Tiny static server for hosts like Railway (Vercel ignores this file and serves the folder directly).
// The design runtime (support.js) builds each page's logic with `new Function`, so the
// Content-Security-Policy must allow 'unsafe-eval'; some host templates block it by default.
const http = require('http');
const fs = require('fs');
const path = require('path');

const ROOT = __dirname;
const PORT = process.env.PORT || 3000;
const TYPES = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8', '.json': 'application/json', '.png': 'image/png', '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.svg': 'image/svg+xml', '.webp': 'image/webp', '.ico': 'image/x-icon' };
const CSP = "default-src 'self'; script-src 'self' 'unsafe-inline' 'unsafe-eval' https://cdn.jsdelivr.net; style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; font-src 'self' https://fonts.gstatic.com data:; img-src 'self' data: https:; connect-src 'self' https:";

function resolve(urlPath) {
  const clean = decodeURIComponent(urlPath.split('?')[0]);
  const safe = path.normalize(clean).replace(/^(\.\.[/\\])+/, '');
  let file = path.join(ROOT, safe);
  if (!file.startsWith(ROOT)) return null;
  if (fs.existsSync(file) && fs.statSync(file).isDirectory()) file = path.join(file, 'index.html');
  if (!fs.existsSync(file) && fs.existsSync(file + '.html')) file += '.html';
  return fs.existsSync(file) ? file : null;
}

http.createServer((req, res) => {
  const REDIRECTS = { '/about-1': '/communities', '/about-1.html': '/communities' };
  const target = REDIRECTS[req.url.split('?')[0]];
  if (target) { res.writeHead(301, { Location: target }); return res.end(); }
  const file = resolve(req.url);
  const headers = { 'Content-Security-Policy': CSP, 'X-Content-Type-Options': 'nosniff' };
  if (!file || path.basename(file) === 'server.js') {
    res.writeHead(404, { ...headers, 'Content-Type': 'text/plain' });
    return res.end('Not found');
  }
  headers['Content-Type'] = TYPES[path.extname(file).toLowerCase()] || 'application/octet-stream';
  headers['Cache-Control'] = file.endsWith('.html') ? 'no-cache' : 'public, max-age=3600';
  res.writeHead(200, headers);
  fs.createReadStream(file).pipe(res);
}).listen(PORT, () => console.log('Rafiki redesign preview on port ' + PORT));
