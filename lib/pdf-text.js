// A small PDF text extractor for official price notices (EWURA, MERA), small enough for a Cloudflare Worker.
// It is not a general PDF reader: it finds the pages, inflates their content streams (FlateDecode only, the usual
// case), decodes text with each font's ToUnicode map (or WinAnsi), and places every piece of text on the page.
// textLines() then groups the pieces into lines (same baseline), left to right, with a space where there is a gap:
// enough to read a table of towns and prices row by row.
//   const pages = await pdfText(bytes)   → [{ items: [{ x, y, s, size }] }]
//   textLines(page)                     → ["Dar es Salaam 4,057 4,086 4,067", …]
// Works with object streams (PDF 1.5+), inherited page resources, Type0/Identity-H fonts with ToUnicode,
// and simple fonts with /Widths. Encrypted PDFs and other filters are refused (throws 'pdf-…').

const latin1 = bytes => { let s = ''; for (let i = 0; i < bytes.length; i += 0x8000) s += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000)); return s; };
const toBytes = s => { const b = new Uint8Array(s.length); for (let i = 0; i < s.length; i++) b[i] = s.charCodeAt(i) & 255; return b; };

async function inflate(bytes) {
  // zlib streams (FlateDecode). Some writers leave junk after the end, so read what comes out and stop on error.
  const ds = new DecompressionStream('deflate');
  const w = ds.writable.getWriter(), r = ds.readable.getReader(), chunks = [];
  w.write(bytes).catch(() => {}); w.close().catch(() => {});
  let n = 0;
  try { for (;;) { const { value, done } = await r.read(); if (done) break; chunks.push(value); n += value.length; } } catch (e) { if (!n) throw new Error('pdf-inflate'); }
  const out = new Uint8Array(n); let o = 0; for (const c of chunks) { out.set(c, o); o += c.length; }
  return out;
}

// ---- a tiny tokenizer for PDF objects and content streams ------------------------------------------------
const WS = /[\0\t\n\f\r ]/, DELIM = /[()<>[\]{}/%]/;
function lexer(s) {
  let i = 0;
  const skip = () => { for (;;) { while (i < s.length && WS.test(s[i])) i++; if (s[i] === '%') { while (i < s.length && s[i] !== '\n' && s[i] !== '\r') i++; } else break; } };
  function str() {               // ( … ) with escapes and nested parentheses → a byte string
    let depth = 1, out = ''; i++;
    while (i < s.length && depth) {
      const c = s[i++];
      if (c === '\\') {
        const d = s[i++];
        if (d === 'n') out += '\n'; else if (d === 'r') out += '\r'; else if (d === 't') out += '\t'; else if (d === 'b') out += '\b'; else if (d === 'f') out += '\f';
        else if (d >= '0' && d <= '7') { let o = d; while (o.length < 3 && s[i] >= '0' && s[i] <= '7') o += s[i++]; out += String.fromCharCode(parseInt(o, 8) & 255); }
        else if (d === '\r') { if (s[i] === '\n') i++; } else if (d === '\n') {} else out += d;
      } else if (c === '(') { depth++; out += c; } else if (c === ')') { if (--depth) out += c; } else out += c;
    }
    return { t: 'str', v: out };
  }
  function next() {
    skip();
    if (i >= s.length) return null;
    const c = s[i];
    if (c === '(') return str();
    if (c === '<' && s[i + 1] === '<') { i += 2; return { t: '<<' }; }
    if (c === '>' && s[i + 1] === '>') { i += 2; return { t: '>>' }; }
    if (c === '<') { const j = s.indexOf('>', i); const h = s.slice(i + 1, j).replace(/[^0-9a-fA-F]/g, ''); i = j + 1; let v = ''; const hh = h.length % 2 ? h + '0' : h; for (let k = 0; k < hh.length; k += 2) v += String.fromCharCode(parseInt(hh.substr(k, 2), 16)); return { t: 'str', v, hex: true }; }
    if (c === '[' || c === ']' || c === '{' || c === '}') { i++; return { t: c }; }
    if (c === '/') { let j = i + 1; while (j < s.length && !WS.test(s[j]) && !DELIM.test(s[j])) j++; const v = s.slice(i + 1, j).replace(/#([0-9a-fA-F]{2})/g, (_, h) => String.fromCharCode(parseInt(h, 16))); i = j; return { t: 'name', v }; }
    let j = i; while (j < s.length && !WS.test(s[j]) && !DELIM.test(s[j])) j++;
    if (j === i) { i++; return next(); }
    const w = s.slice(i, j); i = j;
    if (/^[+-]?(\d+\.?\d*|\.\d+)$/.test(w)) return { t: 'num', v: Number(w) };
    return { t: 'op', v: w };
  }
  return { next, get pos() { return i; }, set pos(v) { i = v; } };
}
// one PDF value from the lexer (dicts → objects, arrays, refs as { ref: n })
function value(lx, tok) {
  const t = tok || lx.next();
  if (!t) return null;
  if (t.t === '<<') { const d = {}; for (;;) { const k = lx.next(); if (!k || k.t === '>>') break; if (k.t !== 'name') continue; d[k.v] = value(lx); } return d; }
  if (t.t === '[') { const a = []; for (;;) { const k = lx.next(); if (!k || k.t === ']') break; a.push(value(lx, k)); } return refs(a); }
  if (t.t === 'num') {
    // "12 0 R" is a reference
    const save = lx.pos, a = lx.next(), b = a && a.t === 'num' ? lx.next() : null;
    if (a && a.t === 'num' && b && b.t === 'op' && b.v === 'R') return { ref: t.v };
    lx.pos = save; return t.v;
  }
  if (t.t === 'name') return { name: t.v };
  if (t.t === 'str') return t.v;
  if (t.t === 'op') return t.v === 'true' ? true : t.v === 'false' ? false : t.v === 'null' ? null : { op: t.v };
  return null;
}
const refs = a => a; // arrays keep { ref } items as they are

// ---- the document ------------------------------------------------------------------------------------------
export async function openPdf(bytes) {
  const s = latin1(bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes));
  if (!/^%PDF-/.test(s.slice(0, 1024).trimStart())) throw new Error('pdf-not-pdf');
  const objs = new Map(), raw = new Map();
  const re = /(\d+)\s+(\d+)\s+obj\b/g; let m;
  while ((m = re.exec(s))) raw.set(Number(m[1]), m.index + m[0].length);
  const streamOf = (start, dict) => {
    let k = s.indexOf('stream', start); k += 6; if (s[k] === '\r') k++; if (s[k] === '\n') k++;
    let len = typeof dict.Length === 'number' ? dict.Length : -1;
    if (len < 0 || s.slice(k + len, k + len + 30).indexOf('endstream') < 0) len = s.indexOf('endstream', k) - k;
    return toBytes(s.slice(k, k + len));
  };
  const parse = n => {
    if (objs.has(n)) return objs.get(n);
    const at = raw.get(n);
    if (at == null) return null;
    const lx = lexer(s); lx.pos = at;
    const v = value(lx);
    const save = lx.pos, t = lx.next();
    let o = v;
    if (t && t.t === 'op' && t.v === 'stream' && v && typeof v === 'object') o = { dict: v, raw: streamOf(save, v) };
    lx.pos = save;
    objs.set(n, o); return o;
  };
  const get = x => (x && typeof x === 'object' && 'ref' in x ? parse(x.ref) : x);
  // trailer /Encrypt → refuse
  if (/\/Encrypt\s+\d+\s+\d+\s+R/.test(s.slice(-4096)) || /\/Encrypt\s*<</.test(s.slice(-4096))) throw new Error('pdf-encrypted');
  async function data(o) {
    if (!o || !o.raw) return new Uint8Array(0);
    const f = get(o.dict.Filter), fs = Array.isArray(f) ? f.map(get) : f ? [f] : [];
    let b = o.raw;
    for (const x of fs) { if (x.name === 'FlateDecode' || x.name === 'Fl') b = await inflate(b); else throw new Error('pdf-filter-' + x.name); }
    return b;
  }
  // objects inside object streams (PDF 1.5+)
  for (const n of [...raw.keys()]) {
    const o = parse(n);
    if (o && o.dict && get(o.dict.Type) && get(o.dict.Type).name === 'ObjStm') {
      const txt = latin1(await data(o)), first = get(o.dict.First), cnt = get(o.dict.N);
      const lx = lexer(txt), pairs = [];
      for (let k = 0; k < cnt; k++) { const a = lx.next(), b = lx.next(); if (!a || !b) break; pairs.push([a.v, b.v]); }
      for (const [num, off] of pairs) { if (objs.has(num) && raw.has(num)) continue; const l2 = lexer(txt); l2.pos = first + off; objs.set(num, value(l2)); }
    }
  }
  return { get, parse, data, objs, raw };
}

// ---- fonts: ToUnicode maps and widths ----------------------------------------------------------------------
function parseCMap(txt) {
  const map = new Map(); let bytes = 1;
  const cs = /begincodespacerange([\s\S]*?)endcodespacerange/.exec(txt);
  if (cs) { const h = /<([0-9a-fA-F]+)>/.exec(cs[1]); if (h) bytes = h[1].length / 2; }
  const hexStr = h => { let o = ''; for (let k = 0; k + 4 <= h.length; k += 4) o += String.fromCharCode(parseInt(h.substr(k, 4), 16)); if (h.length % 4 === 2) o += String.fromCharCode(parseInt(h.slice(-2), 16)); return o; };
  for (const b of txt.match(/beginbfchar[\s\S]*?endbfchar/g) || []) {
    const re = /<([0-9a-fA-F]+)>\s*<([0-9a-fA-F]*)>/g; let m;
    while ((m = re.exec(b))) map.set(parseInt(m[1], 16), hexStr(m[2]));
  }
  for (const b of txt.match(/beginbfrange[\s\S]*?endbfrange/g) || []) {
    const re = /<([0-9a-fA-F]+)>\s*<([0-9a-fA-F]+)>\s*(<([0-9a-fA-F]*)>|\[([^\]]*)\])/g; let m;
    while ((m = re.exec(b))) {
      const lo = parseInt(m[1], 16), hi = parseInt(m[2], 16);
      if (hi - lo > 5000) continue;
      if (m[4] != null) { const base = hexStr(m[4]); for (let c = lo; c <= hi; c++) map.set(c, base.slice(0, -1) + String.fromCharCode(base.charCodeAt(base.length - 1) + (c - lo))); }
      else { const list = (m[5].match(/<([0-9a-fA-F]*)>/g) || []).map(x => hexStr(x.slice(1, -1))); for (let c = lo; c <= hi && c - lo < list.length; c++) map.set(c, list[c - lo]); }
    }
  }
  return { map, bytes };
}
async function loadFont(doc, ref) {
  const f = doc.get(ref) || {};
  const sub = (doc.get(f.Subtype) || {}).name || '';
  const font = { type0: sub === 'Type0', map: null, bytes: sub === 'Type0' ? 2 : 1, widths: null, first: 0, dw: sub === 'Type0' ? 1000 : 500, cid: null };
  const tu = doc.get(f.ToUnicode);
  if (tu && tu.raw) { try { const c = parseCMap(latin1(await doc.data(tu))); font.map = c.map; if (!font.type0) font.bytes = c.bytes; } catch (e) {} }
  if (font.type0) {
    const d = doc.get((doc.get(f.DescendantFonts) || [])[0]) || {};
    if (typeof doc.get(d.DW) === 'number') font.dw = doc.get(d.DW);
    const w = doc.get(d.W);
    if (Array.isArray(w)) {
      font.cid = new Map();
      for (let k = 0; k < w.length;) {
        const a = doc.get(w[k]), b = doc.get(w[k + 1]);
        if (Array.isArray(b)) { b.forEach((x, j) => font.cid.set(a + j, doc.get(x))); k += 2; }
        else { const v = doc.get(w[k + 2]); for (let c = a; c <= b && c - a < 5000; c++) font.cid.set(c, v); k += 3; }
      }
    }
  } else {
    const ws = doc.get(f.Widths);
    if (Array.isArray(ws)) { font.widths = ws.map(x => doc.get(x)); font.first = doc.get(f.FirstChar) || 0; }
  }
  return font;
}
function decode(font, str) {
  const out = []; const n = font.bytes;
  for (let k = 0; k < str.length; k += n) {
    const code = n === 2 ? ((str.charCodeAt(k) << 8) | (str.charCodeAt(k + 1) || 0)) : str.charCodeAt(k);
    const ch = font.map && font.map.has(code) ? font.map.get(code) : font.type0 ? '' : String.fromCharCode(code === 0x92 ? 0x2019 : code === 0xA0 ? 0x20 : code);
    const w = font.type0 ? (font.cid && font.cid.has(code) ? font.cid.get(code) : font.dw) : font.widths && code - font.first >= 0 && code - font.first < font.widths.length ? font.widths[code - font.first] : font.dw;
    out.push({ ch, w: (Number(w) || 0) / 1000, space: n === 1 && code === 32 });
  }
  return out;
}

// ---- content streams ---------------------------------------------------------------------------------------
const mul = (a, b) => [a[0] * b[0] + a[1] * b[2], a[0] * b[1] + a[1] * b[3], a[2] * b[0] + a[3] * b[2], a[2] * b[1] + a[3] * b[3], a[4] * b[0] + a[5] * b[2] + b[4], a[4] * b[1] + a[5] * b[3] + b[5]];
async function runContent(doc, txt, res, out, ctm0, depth) {
  const fonts = new Map(), fontDict = doc.get((doc.get(res) || {}).Font) || {}, xobj = doc.get((doc.get(res) || {}).XObject) || {};
  const fontOf = async name => { if (!fonts.has(name)) fonts.set(name, fontDict[name] ? await loadFont(doc, fontDict[name]) : { map: null, bytes: 1, widths: null, dw: 500 }); return fonts.get(name); };
  const lx = lexer(txt), stack = [];
  let gs = { ctm: ctm0.slice(), font: null, size: 12, tc: 0, tw: 0, th: 1, tl: 0, rise: 0 };
  let tm = [1, 0, 0, 1, 0, 0], tlm = [1, 0, 0, 1, 0, 0], ops = [];
  const show = (glyphs, adjust) => {
    // glyphs: [{ ch, w, space }] with text-space adjustments (TJ numbers) in between
    let text = '', startX = null, startY = null, size = 0;
    const flush = () => { if (text.trim()) out.push({ x: startX, y: startY, s: text, size }); text = ''; startX = null; };
    for (const g of glyphs) {
      if (typeof g === 'number') { const tx = -g / 1000 * gs.size * gs.th; tm = mul([1, 0, 0, 1, tx, 0], tm); if (-g > 180) flush(); continue; }
      const trm = mul(mul([gs.size * gs.th, 0, 0, gs.size, 0, gs.rise], tm), gs.ctm);
      if (startX == null) { startX = trm[4]; startY = trm[5]; size = Math.abs(trm[3]) || Math.abs(trm[1]); }
      text += g.ch;
      const tx = (g.w * gs.size + gs.tc + (g.space ? gs.tw : 0)) * gs.th;
      tm = mul([1, 0, 0, 1, tx, 0], tm);
    }
    flush();
    // where this run ends (for joining runs on the same line)
    if (out.length) { const e = mul(mul([gs.size * gs.th, 0, 0, gs.size, 0, gs.rise], tm), gs.ctm); out[out.length - 1].x2 = e[4]; }
  };
  for (let t = lx.next(); t; t = lx.next()) {
    if (t.t !== 'op') { if (t.t === '[') { const a = []; for (let k = lx.next(); k && k.t !== ']'; k = lx.next()) if (k.t === 'num' || k.t === 'str') a.push(k.t === 'num' ? k.v : k); ops.push({ arr: a }); } else if (t.t === '<<') { value(lx, t); } else ops.push(t); continue; }
    const op = t.v, n = i => (ops[ops.length - i] || {}).v;
    switch (op) {
      case 'q': stack.push(Object.assign({}, gs, { ctm: gs.ctm.slice() })); break;
      case 'Q': if (stack.length) gs = stack.pop(); break;
      case 'cm': gs.ctm = mul([n(6), n(5), n(4), n(3), n(2), n(1)], gs.ctm); break;
      case 'BT': tm = [1, 0, 0, 1, 0, 0]; tlm = tm.slice(); break;
      case 'Tf': gs.font = await fontOf((ops[ops.length - 2] || {}).v); gs.size = n(1); break;
      case 'Tc': gs.tc = n(1); break;
      case 'Tw': gs.tw = n(1); break;
      case 'Tz': gs.th = n(1) / 100; break;
      case 'TL': gs.tl = n(1); break;
      case 'Ts': gs.rise = n(1); break;
      case 'Td': tlm = mul([1, 0, 0, 1, n(2), n(1)], tlm); tm = tlm.slice(); break;
      case 'TD': gs.tl = -n(1); tlm = mul([1, 0, 0, 1, n(2), n(1)], tlm); tm = tlm.slice(); break;
      case 'Tm': tlm = [n(6), n(5), n(4), n(3), n(2), n(1)]; tm = tlm.slice(); break;
      case 'T*': tlm = mul([1, 0, 0, 1, 0, -gs.tl], tlm); tm = tlm.slice(); break;
      case 'Tj': case "'": case '"': {
        if (op !== 'Tj') { tlm = mul([1, 0, 0, 1, 0, -gs.tl], tlm); tm = tlm.slice(); }
        const st = ops[ops.length - 1];
        if (st && st.t === 'str' && gs.font) show(decode(gs.font, st.v));
        break;
      }
      case 'TJ': {
        const a = (ops[ops.length - 1] || {}).arr || [];
        if (gs.font) { const g = []; a.forEach(x => (typeof x === 'number' ? g.push(x) : g.push(...decode(gs.font, x.v)))); show(g); }
        break;
      }
      case 'Do': {
        const nm = (ops[ops.length - 1] || {}).v, xo = doc.get(xobj[nm]);
        if (depth < 3 && xo && xo.dict && (doc.get(xo.dict.Subtype) || {}).name === 'Form') {
          const mtx = doc.get(xo.dict.Matrix) || [1, 0, 0, 1, 0, 0];
          await runContent(doc, latin1(await doc.data(xo)), xo.dict.Resources || res, out, mul(mtx.map(doc.get), gs.ctm), depth + 1);
        }
        break;
      }
      case 'BI': { // inline image: skip to EI
        const rest = txt.indexOf('EI', lx.pos); lx.pos = rest < 0 ? txt.length : rest + 2; break;
      }
    }
    ops = [];
  }
}

function pageList(doc) {
  const pages = [];
  for (const [num] of doc.raw) {
    const o = doc.parse(num);
    if (o && typeof o === 'object' && !o.raw && doc.get(o.Type) && doc.get(o.Type).name === 'Page') pages.push({ num, o });
  }
  for (const [num, o] of doc.objs) if (!doc.raw.has(num) && o && typeof o === 'object' && doc.get(o.Type) && doc.get(o.Type).name === 'Page') pages.push({ num, o });
  // document order: follow the page tree from the catalog when there is one
  const cat = [...doc.objs.values()].concat([...doc.raw.keys()].map(n => doc.parse(n))).find(x => x && typeof x === 'object' && !x.raw && doc.get(x.Type) && doc.get(x.Type).name === 'Catalog');
  if (cat) {
    const order = [], walk = (ref, d) => { const node = doc.get(ref); if (!node || d > 20) return; const ty = (doc.get(node.Type) || {}).name; if (ty === 'Page') order.push(ref.ref); else (doc.get(node.Kids) || []).forEach(k => walk(k, d + 1)); };
    walk(cat.Pages, 0);
    if (order.length) return order.map(n => pages.find(p => p.num === n)).filter(Boolean);
  }
  return pages.sort((a, b) => a.num - b.num);
}
const inherited = (doc, page, key) => { let p = page; for (let d = 0; p && d < 20; d++) { if (p[key] != null) return p[key]; p = doc.get(p.Parent); } return null; };

/** Text pieces per page: [{ items: [{ x, y, s, size, x2 }] }] in PDF points (origin bottom-left). */
export async function pdfText(bytes, opts = {}) {
  const doc = await openPdf(bytes);
  const out = [];
  for (const { o } of pageList(doc).slice(0, opts.maxPages || 40)) {
    const res = inherited(doc, o, 'Resources'), cs = doc.get(o.Contents), parts = Array.isArray(cs) ? cs.map(doc.get) : [cs];
    let txt = ''; for (const c of parts) if (c && c.raw) txt += latin1(await doc.data(c)) + '\n';
    const items = [];
    await runContent(doc, txt, res, items, [1, 0, 0, 1, 0, 0], 0);
    out.push({ items });
  }
  return out;
}

/** Lines of text on a page, top to bottom: pieces on the same baseline (within 40% of the font size) joined left to right. */
export function textLines(page) {
  const items = (page.items || []).filter(x => isFinite(x.x) && isFinite(x.y)).slice().sort((a, b) => b.y - a.y || a.x - b.x);
  const lines = [];
  for (const it of items) {
    const tol = Math.max(1.5, (it.size || 10) * 0.4);
    let L = lines.find(l => Math.abs(l.y - it.y) <= tol);
    if (!L) { L = { y: it.y, parts: [] }; lines.push(L); }
    L.parts.push(it);
  }
  return lines.sort((a, b) => b.y - a.y).map(l => {
    const ps = l.parts.sort((a, b) => a.x - b.x);
    let s = '', end = null;
    for (const p of ps) {
      const gap = end == null ? 0 : p.x - end, sz = p.size || 10;
      if (s && gap > sz * 0.22 && !/\s$/.test(s)) s += ' ';
      s += p.s; end = p.x2 != null ? p.x2 : p.x + p.s.length * sz * 0.5;
    }
    return s.replace(/\s+/g, ' ').trim();
  }).filter(Boolean);
}
