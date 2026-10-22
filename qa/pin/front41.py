# The map, decluttered (mcl…): one pill for the active filter, the pinned bar steps aside, Nearest to you as a slim
# peek and, open, under every top control; a small Key button; the map fits the filter; a tap on empty map shows the
# map alone; one compact column on the right with Offline areas and Follow me under More. Nothing over the map
# overlaps (bounding boxes) and the map credit always shows. 360 / 390 / 1280 px, Kiswahili, dark, reduced motion.
import os, re, datetime
from playwright.sync_api import sync_playwright
HERE = os.path.dirname(os.path.abspath(__file__)); BASE = os.environ.get('BASE', 'http://localhost:8797'); OUT = os.path.join(HERE, 'mclean'); os.makedirs(OUT, exist_ok=True)
TILE = open(os.path.join(HERE, 'tile.png'), 'rb').read()
now = datetime.datetime.now(datetime.timezone.utc)
ago = lambda d: (now - datetime.timedelta(days=d)).strftime('%Y-%m-%dT%H:%M:%SZ')
def pj(i, t, area, ll, ty='Borehole well', ck='ke', c='Kenya'): return {'id': i, 'title': t, 'country': c, 'ck': ck, 'area': area, 'type': ty, 'status': 'Completed', 'gps': ll}
P = [pj('101', 'AIC Moiben Children’s Home Borehole and Water Tank', 'Moiben', [0.5152, 35.2702]), pj('102', 'Chepkongi Dispensary', 'Eldoret', [0.5443, 35.2998], 'Health'),
     pj('103', 'Turbo Spring', 'Turbo', [0.63, 35.05], 'Water collection'), pj('104', 'Nzoia Bridge Primary School', 'Bungoma', [0.58, 34.62], 'School'),
     pj('105', 'Tamiha Children’s Home', 'Kitale', [1.0, 35.0], 'Orphanage'), pj('106', 'Kaptebee Pigsty', 'Nandi', [0.2, 35.1], 'Farming'),
     pj('107', 'Moi’s Bridge Borehole', 'Eldoret', [0.87, 35.12]), pj('108', 'Mto wa Mbu Borehole', 'Arusha', [-3.37, 35.85], ck='tz', c='Tanzania'),
     pj('109', 'Kapsaret Primary School', 'Eldoret', [0.475, 35.255], 'School'), pj('110', 'Sosiot Well', 'Kericho', None),
     pj('111', 'Ziwa Borehole', 'Ziwa', [0.75, 35.3]), pj('112', 'Kipkaren Well', 'Kipkarren', [0.52, 35.05]), pj('113', 'Mengech Primary Borehole', 'Ziwa', [0.71, 35.22])]
REP = {'107': {'state': 'notworking', 'by': 'Bertin', 'at': ago(3), 'urgency': 'urgent'}, '102': {'state': 'repair', 'by': 'Grace', 'at': ago(9), 'urgency': 'month'}}
VIS = {'103': {'at': ago(20), 'by': 'Meril', 'state': 'working'}, '104': {'at': ago(400), 'by': 'Meril', 'state': 'working'}, '106': {'at': ago(10), 'by': 'Grace', 'state': 'working'},
       '109': {'at': ago(50), 'by': 'Reid', 'state': 'working'}, '111': {'at': ago(5), 'by': 'Reid', 'state': 'working'}}
problems = []
def check(c, w): print(('ok   ' if c else 'FAIL ') + w); (None if c else problems.append(w))
def api(route, req):
    if req.method == 'GET':
        if 'activity=' in req.url: return route.fulfill(json={'ok': True, 'activity': []})
        if '?id=' in req.url: return route.fulfill(json={'ok': True, 'last': None, 'canUndo': False, 'visits': []})
        if 'history=' in req.url: return route.fulfill(json={'ok': True, 'notes': []})
        return route.fulfill(json={'ok': True, 'who': 'Meril Kiprop', 'admin': False, 'reviewOpen': 0, 'projects': P, 'repairs': REP, 'visits': VIS, 'jobs': [], 'assigned': [], 'confirmed': {}, 'me': {'kind': 'app', 'home': 'ke'}})
    route.fulfill(json={'ok': True})

def context(br, w=390, h=844, **kw):
    mob = w < 700
    ctx = br.new_context(viewport={'width': w, 'height': h}, is_mobile=mob, has_touch=mob, service_workers='block', geolocation={'latitude': 0.515, 'longitude': 35.27, 'accuracy': 8}, permissions=['geolocation'], **kw)
    ctx.route(re.compile(r'https://(tile\.openstreetmap\.org|server\.arcgisonline\.com)/.*'), lambda r, q: r.fulfill(status=200, body=TILE, headers={'Content-Type': 'image/png', 'Access-Control-Allow-Origin': '*'}))
    # the Leaflet maps on the page, to move the home map in a test
    ctx.add_init_script("(() => { let _L; Object.defineProperty(window, 'L', { configurable: true, get() { return _L; }, set(v) { _L = v; try { v.Map.addInitHook(function () { (window.__maps = window.__maps || []).push(this); }); } catch (e) {} } }); })()")
    return ctx
def sign_in(pg, theme=None, lang=None):
    pg.route('**/api/pin-auth/me', lambda r, q: r.fulfill(json={'available': False})); pg.route(re.compile(r'/api/pin(\?.*)?$'), api)
    pg.route(re.compile(r'/api/place(\?.*)?$'), lambda r, q: r.fulfill(json={'ok': True, 'places': []}))
    pg.goto(BASE + '/pin'); pg.wait_for_timeout(700)
    for d in '9999': pg.click('.pk[data-k="%s"]' % d)
    pg.wait_for_timeout(1100); pg.evaluate("localStorage.setItem('rfPinOnb','1'); sessionStorage.removeItem('rfPinMapClean')")
    if theme: pg.evaluate("t => localStorage.setItem('rfPinTheme', t)", theme)
    if lang: pg.evaluate("l => localStorage.setItem('rfPinLang', l)", lang)
    pg.goto(BASE + '/pin#map'); pg.reload(); pg.wait_for_timeout(1900)
def shot(pg, name): pg.wait_for_timeout(250); pg.screenshot(path=os.path.join(OUT, name))
def vis(pg, sel): return pg.locator(sel).count() > 0 and pg.locator(sel).first.is_visible()

# the boxes of what is over the map (only those showing), by name
BOXES = """() => { const q = {search: '#mpaS', chips: '#mpaChips', pill: '#mclPill', pinned: '#vMap .ov.top > .pill', here: '#mHere > *', sbtn: '#mclSb',
    side: '#hMap .mcl-col', cmp: '#mpbCmp', fol: '#hMap .mcl-side > #mpbFol', tray: '#mclTray', key: '#mpaKey', keypop: '#mclKeyPop',
    sheet: '#nearSheet', card: '#mpaCard', layers: '.mpb-bot', credit: '#hMap .leaflet-control-attribution', glogo: '#hMap .glogo'};
  const out = {};
  for (const [k, s] of Object.entries(q)) { const e = document.querySelector(s); if (!e || !e.offsetParent && getComputedStyle(e).position !== 'fixed') continue;
    const r = e.getBoundingClientRect(); if (r.width < 1 || r.height < 1) continue; out[k] = [r.left, r.top, r.right, r.bottom]; }
  return out; }"""
def boxes(pg): return pg.evaluate(BOXES)
def meet(a, b): return a[0] < b[2] - 0.5 and b[0] < a[2] - 0.5 and a[1] < b[3] - 0.5 and b[1] < a[3] - 0.5
def overlaps(pg, pairs=None):
    b = boxes(pg); ks = sorted(b); bad = []
    for i, x in enumerate(ks):
        for y in ks[i + 1:]:
            if pairs and (x, y) not in pairs and (y, x) not in pairs: continue
            if {x, y} <= {'side', 'tray'} or {x, y} <= {'key', 'keypop'}: continue
            if meet(b[x], b[y]): bad.append((x, y))
    return bad, b
# the credit is drawn and nothing covers it: the point in the middle and both ends belong to it
CREDIT = """() => { const e = document.querySelector('#hMap .leaflet-control-attribution'); if (!e || !e.offsetParent) return false; const r = e.getBoundingClientRect();
  const m = document.querySelector('#hMap').getBoundingClientRect(); if (r.top < m.top || r.bottom > m.bottom || r.left < m.left - 1 || r.right > m.right + 1) return false;
  return [r.left + 6, (r.left + r.right) / 2, r.right - 6].every(x => { const h = document.elementFromPoint(x, (r.top + r.bottom) / 2); return h && e.contains(h); }); }"""
def credit_ok(pg): return pg.evaluate(CREDIT)
def targets(pg, sel):
    return pg.eval_on_selector_all(sel, "els => els.filter(e => e.offsetParent).map(e => { const r = e.getBoundingClientRect(); return [e.id || e.className, Math.round(r.width), Math.round(r.height)]; }).filter(x => x[1] < 44 || x[2] < 44)")
def broken_words(pg, sel):
    # text that wraps inside a word, or spills out of its box without an ellipsis
    return pg.eval_on_selector_all(sel, """els => els.filter(e => e.offsetParent).filter(e => { const s = getComputedStyle(e);
      if (e.scrollWidth > e.clientWidth + 1 && s.textOverflow !== 'ellipsis') return true; return /break-all|anywhere/.test(s.wordBreak + s.overflowWrap); }).map(e => e.textContent.trim().slice(0, 40))""")
# a spot on the map with nothing on it (no pin, no bubble, no control), for a plain tap
EMPTY = """() => { const m = document.querySelector('#hMap').getBoundingClientRect();
  for (let y = m.top + m.height * 0.45; y < m.bottom - 140; y += 23) for (let x = m.left + 40; x < m.right - 80; x += 29) {
    const h = document.elementFromPoint(x, y); if (!h || !h.closest('#hMap') || h.closest('.leaflet-marker-icon, .leaflet-control, .leaflet-interactive, .mcl-side')) continue;
    let ok = true; for (const dx of [-26, 0, 26]) for (const dy of [-26, 0, 26]) { const g = document.elementFromPoint(x + dx, y + dy); if (!g || !g.closest('#hMap') || g.closest('.leaflet-marker-icon, .leaflet-control, .leaflet-interactive')) ok = false; }
    if (ok) return [x, y]; } return null; }"""
def tap_empty(pg):
    xy = pg.evaluate(EMPTY); assert xy, 'no empty spot on the map'
    pg.mouse.click(xy[0], xy[1]); pg.wait_for_timeout(450); return xy
def pins_in_view(pg):
    # every pin and bubble the filter left is inside the free part of the map: under the top controls, left of the column, above the bottom
    return pg.evaluate("""() => { const m = document.querySelector('#hMap').getBoundingClientRect(); let top = m.top;
      document.querySelectorAll('#vMap .ov.top > *').forEach(e => { if (e.offsetParent) top = Math.max(top, e.getBoundingClientRect().bottom); });
      const side = document.querySelector('#hMap .mcl-col').getBoundingClientRect(), sh = document.querySelector('#nearSheet').getBoundingClientRect(), key = document.querySelector('#mpaKey').getBoundingClientRect();
      const bot = Math.min(sh.top || m.bottom, key.top || m.bottom);
      const els = [...document.querySelectorAll('#hMap .mpa-pin, #hMap .mpa-cl')]; if (!els.length) return [0, 0];
      const out = els.filter(e => { const r = e.getBoundingClientRect(), x = (r.left + r.right) / 2, y = (r.top + r.bottom) / 2; return !(y > top && y < bot && x > m.left && x < m.right && !(x > side.left - 4 && y < side.bottom)); });
      return [els.length, out.length]; }""")
def touch_hold(pg, sel, x, y, hold=750):
    pg.evaluate("""([s, x, y, hold]) => new Promise(ok => { const el = document.querySelector(s); const t = new Touch({ identifier: 1, target: el, clientX: x, clientY: y });
      el.dispatchEvent(new TouchEvent('touchstart', { touches: [t], changedTouches: [t], bubbles: true, cancelable: true }));
      setTimeout(() => { el.dispatchEvent(new TouchEvent('touchend', { touches: [], changedTouches: [t], bubbles: true, cancelable: true })); ok(); }, hold); })""", [sel, x, y, hold])

def layout_checks(pg, tag):
    bad, b = overlaps(pg)
    check(not bad, '%s: nothing over the map overlaps %s' % (tag, bad))
    check(credit_ok(pg), '%s: the map credit is drawn and nothing covers it' % tag)
    return b

with sync_playwright() as p:
    br = p.chromium.launch()
    # ===== 1. phone, English: the resting map ==================================================================
    ctx = context(br); pg = ctx.new_page(); errs = []; pg.on('pageerror', lambda e: errs.append(str(e)))
    sign_in(pg)
    check(vis(pg, '#mpaChips') and not vis(pg, '#mclPill') and vis(pg, '#vMap .ov.top > .pill'), 'no filter: the chip row and the pinned bar, no pill')
    ph = pg.locator('#vMap .ov.top > .pill').bounding_box()['height']
    check(ph <= 46 and pg.evaluate("document.querySelector('#mProg .row1').getClientRects().length && document.querySelector('#mProg .row1').getBoundingClientRect().height < 24"), 'the pinned bar is slim: one line and a thin meter (%d px)' % ph)
    check(pg.evaluate("getComputedStyle(document.querySelector('#mProg .meter')).height") == '4px', 'its meter is thin')
    check(vis(pg, '#mpaKey') and pg.inner_text('#mpaKey').strip() == 'Key' and not vis(pg, '#mclKeyPop'), 'the key is a small Key button, its colours folded away')
    check(vis(pg, '#mclMore') and not vis(pg, '#fldOb') and not vis(pg, '#mpbFol'), 'one column on the right: Offline areas and Follow me are under More')
    check(vis(pg, '#hMap .mcl-col .mstyle') and vis(pg, '#hMap .mcl-col .lc-me a'), 'Map style and Show where I am are always there')
    check(pg.locator('#hMap .mcl-col').count() == 1 and pg.locator('#hMap .leaflet-top.leaflet-right > *').count() == 1, 'the buttons on the right are one column')
    check(not targets(pg, '#hMap .mcl-col .mstyle, #hMap .mcl-col a, #mclMore, #mpaKey, #mpaChips button'), 'every button is at least 44 px %s' % targets(pg, '#hMap .mcl-col .mstyle, #hMap .mcl-col a, #mclMore, #mpaKey, #mpaChips button'))
    b = layout_checks(pg, 'resting')
    check(b['key'][0] < 60 and b['key'][3] <= b['sheet'][1] - 30, 'the Key button sits at the bottom left, above the credit and Nearest to you')
    check(b['credit'][3] <= b['sheet'][1] + 0.5, 'the credit sits above Nearest to you')
    shot(pg, '01-resting.png')

    # ===== 2. a filter clears the screen (the user's first picture) =============================================
    pg.evaluate("window.__maps.find(x => x.getContainer().id === 'hMap').setView([-3.3, 36.6], 9, { animate: false })"); pg.wait_for_timeout(400)
    pg.click('#mpaChips [data-mt=water]'); pg.wait_for_timeout(1500)
    pill = re.sub(r'\s+', ' ', pg.inner_text('#mclPill')).strip()
    check(vis(pg, '#mclPill') and not vis(pg, '#mpaChips') and re.match(r'^Water · \d+$', pill) is not None, 'Water: the row folds into one pill "%s"' % pill)
    check(pg.locator('#mclPill svg').count() >= 2 and vis(pg, '#mclPillX') and pg.get_attribute('#mclPillX', 'aria-label') == 'Clear filters', 'the pill has the drop and a × to clear')
    check(not vis(pg, '#vMap .ov.top > .pill'), 'the pinned bar steps aside while a filter is on')
    pk = re.sub(r'\s+', ' ', pg.inner_text('#mclPeek')).strip()
    check(vis(pg, '#mclPeek') and not vis(pg, '#nearList') and pk.startswith('Nearest: AIC Moiben') and re.search(r'· [\d.]+ (m|km)$', pk) is not None, 'Nearest to you is a slim handle: "%s"' % pk)
    check(pg.locator('#nearSheet').bounding_box()['height'] <= 54, 'the handle is slim (%d px)' % pg.locator('#nearSheet').bounding_box()['height'])
    check(pg.evaluate("(() => { const e = document.querySelector('#mclPeek .mcl-pk'); return e.scrollWidth > e.clientWidth && getComputedStyle(e).textOverflow === 'ellipsis'; })()"), 'a long name ends cleanly with …')
    n, out = pins_in_view(pg)
    check(n > 0 and out == 0, 'the map fits the matching pins, clear of what is over it (%d shown, %d outside)' % (n, out))
    check(not targets(pg, '#mclPillB, #mclPillX, #mclPeek'), 'pill and handle are 44 px to tap')
    layout_checks(pg, 'Water on')
    shot(pg, '02-water.png')
    # the pill opens the row; another chip folds it into "Water + Broken"; × clears and brings the row back
    pg.click('#mclPillB'); pg.wait_for_timeout(300)
    check(vis(pg, '#mpaChips') and not vis(pg, '#mclPill') and pg.get_attribute('#mpaChips [data-mt=water]', 'aria-pressed') == 'true', 'tap the pill: the whole row is back')
    check(not vis(pg, '#vMap .ov.top > .pill'), 'and the pinned bar stays aside')
    pg.click('#mpaChips [data-mf=broken]'); pg.wait_for_timeout(1300)
    pill = re.sub(r'\s+', ' ', pg.inner_text('#mclPill')).strip()
    check(vis(pg, '#mclPill') and re.match(r'^Water \+ Broken · \d+$', pill) is not None, 'two filters: "%s"' % pill)
    shot(pg, '03-two-filters.png')
    pg.click('#mclPillX'); pg.wait_for_timeout(500)
    check(vis(pg, '#mpaChips') and not vis(pg, '#mclPill') and pg.locator('#mpaChips [aria-pressed=true]').count() == 0 and pg.evaluate("localStorage.getItem('rfPinType')") == '' and vis(pg, '#vMap .ov.top > .pill'), '× clears the filters: the row and the pinned bar are back')
    check(vis(pg, '#nearHd') and not vis(pg, '#mclPeek'), 'Nearest to you rests as before')

    # ===== 3. Nearest to you open: under every top control, nothing shows through (the user's second picture) ===
    pg.click('#mpaChips [data-mt=water]'); pg.wait_for_timeout(1200)
    pg.click('#mclPeek'); pg.wait_for_timeout(700)
    check(vis(pg, '#nearList') and pg.get_attribute('#mclPeek', 'aria-expanded') == 'true', 'tap the handle: Nearest to you opens')
    check(not vis(pg, '#mpaKey') and not vis(pg, '#vMap .ov.top > .pill'), 'open, the key and the pinned bar step aside')
    b = layout_checks(pg, 'open with Water')
    check(b['sheet'][1] >= max(b['search'][3], b['pill'][3], b['side'][3]) + 20, 'the open sheet stops under the search, the pill and the column (top %d)' % b['sheet'][1])
    check(b['credit'][3] <= b['sheet'][1] + 0.5 and b['credit'][1] >= b['side'][3] - 0.5, 'the credit sits between them, in view')
    sc = pg.evaluate("(() => { const e = document.querySelector('#nearList'); return [e.scrollHeight > e.clientHeight + 4, getComputedStyle(e).overflowY]; })()")
    check(sc[0] and sc[1] == 'auto', 'the list scrolls inside the sheet %s' % sc)
    shot(pg, '04-nearest-open-water.png')
    pg.click('#nearHd'); pg.wait_for_timeout(400)
    check(vis(pg, '#mclPeek') and not vis(pg, '#nearList') and vis(pg, '#mpaKey'), 'and back to the handle; the key is back')
    # swipe the handle up
    hb = pg.locator('#mclPeek').bounding_box()
    pg.evaluate("""([x, y]) => { const el = document.querySelector('#mclPeek'); const t = (yy) => new Touch({ identifier: 2, target: el, clientX: x, clientY: yy });
      el.dispatchEvent(new TouchEvent('touchstart', { touches: [t(y)], changedTouches: [t(y)], bubbles: true, cancelable: true }));
      el.dispatchEvent(new TouchEvent('touchend', { touches: [], changedTouches: [t(y - 80)], bubbles: true, cancelable: true })); }""", [hb['x'] + 100, hb['y'] + 20]); pg.wait_for_timeout(500)
    check(vis(pg, '#nearList'), 'swiping the handle up opens it too')
    pg.click('#mclPillX'); pg.wait_for_timeout(500); pg.click('#nearHd') if vis(pg, '#nearList') else None; pg.wait_for_timeout(300)
    # without a filter, open from its header
    pg.click('#nearHd'); pg.wait_for_timeout(600)
    b = layout_checks(pg, 'open, no filter')
    check(b['sheet'][1] >= max(b['search'][3], b['chips'][3], b['side'][3]) + 20 and 'pinned' not in b and 'key' not in b, 'open with no filter: under the chips and the column; key and pinned bar aside')
    shot(pg, '05-nearest-open.png')
    pg.click('#nearHd'); pg.wait_for_timeout(400)

    # ===== 4. the Key button ===================================================================================
    pg.click('#mpaKey'); pg.wait_for_timeout(300)
    kt = pg.inner_text('#mclKeyPop')
    check(vis(pg, '#mclKeyPop') and all(k in kt for k in ['Working', 'Needs repair', 'Not working', 'No visit in 6 months']) and pg.get_attribute('#mpaKey', 'aria-expanded') == 'true', 'Key: the colours pop up')
    bad, b = overlaps(pg, [('keypop', 'search'), ('keypop', 'side'), ('keypop', 'chips'), ('keypop', 'credit'), ('keypop', 'sheet')])
    check(not bad, 'the key never covers the search, the column, the chips, the credit or the sheet %s' % bad)
    shot(pg, '06-key.png')
    pg.wait_for_timeout(5300)
    check(not vis(pg, '#mclKeyPop'), 'and closes by itself after a few seconds')
    pg.click('#mpaKey'); pg.wait_for_timeout(200)
    xy = pg.evaluate(EMPTY); pg.mouse.click(xy[0], xy[1]); pg.wait_for_timeout(500)
    check(not vis(pg, '#mclKeyPop') and 'mcl-clean' not in pg.get_attribute('#vMap', 'class'), 'or on a tap anywhere else (that tap does nothing more)')

    # ===== 5. More: Offline areas and Follow me ================================================================
    pg.click('#mclMore'); pg.wait_for_timeout(350)
    tt = pg.inner_text('#mclTray')
    check(vis(pg, '#mclTray') and vis(pg, '#fldOb') and vis(pg, '#mpbFol') and 'Offline areas' in tt and 'Follow me' in tt and pg.get_attribute('#mclMore', 'aria-expanded') == 'true', 'More opens a tray with labels: ' + tt.replace('\n', ' | '))
    check(pg.evaluate("getComputedStyle(document.querySelector('#mclTray')).animationName") == 'mclTray', 'the tray slides in')
    check(not [x for x in targets(pg, '#mclTray button') if x[2] < 48], 'tray rows are 48 px tall')
    bad, b = overlaps(pg, [('tray', k) for k in ['search', 'chips', 'pill', 'key', 'credit', 'glogo', 'pinned', 'here']])
    check(not bad, 'the tray covers none of the search, chips, pinned bar, key or credit %s' % bad)
    shot(pg, '07-more.png')
    xy = pg.evaluate(EMPTY); pg.mouse.click(xy[0], xy[1]); pg.wait_for_timeout(500)
    check(not vis(pg, '#mclTray') and 'mcl-clean' not in pg.get_attribute('#vMap', 'class'), 'a tap outside closes it')
    pg.click('#mclMore'); pg.wait_for_timeout(250); pg.click('#mpbFol'); pg.wait_for_timeout(1500)
    check(pg.get_attribute('#mpbFol', 'aria-pressed') == 'true' and vis(pg, '#hMap .mcl-side > #mpbFol') and not vis(pg, '#mclTray'), 'Follow me on: it shows in the column, the tray closed')
    layout_checks(pg, 'following')
    shot(pg, '08-following.png')
    pg.click('#mpbFol'); pg.wait_for_timeout(600)
    check(pg.get_attribute('#mpbFol', 'aria-pressed') == 'false' and not vis(pg, '#mpbFol') and pg.locator('#mclTray > #mpbFol').count() == 1, 'tap it to stop: back under More')
    pg.click('#mclMore'); pg.wait_for_timeout(250); pg.click('#fldOb'); pg.wait_for_timeout(700)
    check(pg.locator('.bsh').count() >= 1 and 'offline' in pg.inner_text('.bsh').lower(), 'Offline areas opens Save this area for offline')
    pg.click('.bsh-x'); pg.wait_for_function("() => !document.querySelector('.bsh')", timeout=5000); pg.wait_for_timeout(200)

    # ===== 6. a tap on empty map: the map alone ================================================================
    tap_empty(pg)
    cls = pg.get_attribute('#vMap', 'class')
    check('mcl-clean' in cls and pg.evaluate("sessionStorage.getItem('rfPinMapClean')") == '1', 'tap on empty map: map only (remembered for this session)')
    b = boxes(pg)
    check(set(b) <= {'sbtn', 'side', 'credit', 'glogo', 'cmp'} and 'sbtn' in b and 'side' in b, 'only a search button and the column are left: %s' % sorted(b))
    check(credit_ok(pg), 'the credit still shows')
    check(not targets(pg, '#mclSb'), 'the search button is 48 px')
    shot(pg, '09-map-only.png')
    pg.reload(); pg.wait_for_timeout(1900)
    check('mcl-clean' in pg.get_attribute('#vMap', 'class'), 'still map only after a reload (same session)')
    tap_empty(pg)
    check('mcl-clean' not in pg.get_attribute('#vMap', 'class') and vis(pg, '#mpaS') and vis(pg, '#nearSheet'), 'tap again: everything is back')
    tap_empty(pg); pg.click('#mclSb'); pg.wait_for_timeout(300)
    check('mcl-clean' not in pg.get_attribute('#vMap', 'class') and pg.evaluate("document.activeElement.id") == 'mpaQ', 'the search button brings it all back, ready to type')
    pg.keyboard.press('Escape'); pg.evaluate("document.activeElement.blur()")
    # a pin and a long press still open their cards; a tap then puts the card away (no map-only)
    pg.evaluate("window.__maps.find(x => x.getContainer().id === 'hMap').setView([0.63, 35.05], 13, { animate: false })"); pg.wait_for_timeout(600)
    pg.click('#hMap .mpa-pin[data-id="103"]'); pg.wait_for_timeout(600)
    check(vis(pg, '#mpaCard') and pg.inner_text('#mpaTitle') == 'Turbo Spring', 'a tap on a pin opens its card')
    layout_checks(pg, 'card')
    xy = pg.evaluate(EMPTY); pg.mouse.click(xy[0], xy[1]); pg.wait_for_timeout(600)
    check(not vis(pg, '#mpaCard') and 'mcl-clean' not in pg.get_attribute('#vMap', 'class'), 'a tap on the map puts the card away (and is not map only)')
    mb = pg.locator('#hMap').bounding_box(); touch_hold(pg, '#hMap', mb['x'] + 160, mb['y'] + 400); pg.wait_for_timeout(400)
    check(vis(pg, '#mpaCard') and 'This spot' in pg.inner_text('#mpaCard') and 'mcl-clean' not in pg.get_attribute('#vMap', 'class'), 'a long press still gives This spot')
    pg.click('#mpaCard [data-mpa=x]'); pg.wait_for_timeout(300)
    check(not errs, 'no page errors (phone) %s' % errs[:3])
    ctx.close()

    # ===== 7. Kiswahili at 360 px, dark at 390 px, desktop at 1280 px ===========================================
    for w, theme, lang in [(360, None, 'sw'), (390, 'dark', None), (390, 'sun', 'sw'), (1280, None, None)]:
        tag = '%d px%s%s' % (w, ' ' + theme if theme else '', ' Kiswahili' if lang else '')
        ctx = context(br, w, 860 if w > 700 else 780, color_scheme='dark' if theme == 'dark' else 'light'); pg = ctx.new_page(); errs = []; pg.on('pageerror', lambda e: errs.append(str(e)))
        sign_in(pg, theme=theme, lang=lang)
        check(pg.evaluate("document.scrollingElement.scrollWidth") <= w, '%s: no sideways page scroll' % tag)
        layout_checks(pg, tag + ' resting')
        f = 'sw' if lang else 'en'
        shot(pg, '10-%d-%s-%s-resting.png' % (w, theme or 'light', f))
        pg.click('#mpaChips [data-mt=home]'); pg.wait_for_timeout(1200)
        pill = re.sub(r'\s+', ' ', pg.inner_text('#mclPill')).strip()
        pk = re.sub(r'\s+', ' ', pg.inner_text('#mclPeek')).strip()
        if lang:
            check(pill.startswith('Makao ya watoto · ') and pk.startswith('Karibu zaidi:') and pg.inner_text('#mpaKey').strip() == 'Rangi', '%s: pill "%s", handle "%s", key' % (tag, pill, pk))
        check(not broken_words(pg, '#mclPill .mcl-pt, #mclPill .mcl-pn, #mclPeek b, #mclPeek .mcl-pk, #mclPeek i, #mpaKey span'), '%s: words whole, long ones end with … %s' % (tag, broken_words(pg, '#mclPill .mcl-pt, #mclPeek .mcl-pk')))
        n, out = pins_in_view(pg); check(n > 0 and out == 0, '%s: the map fits the filter (%d, %d outside)' % (tag, n, out))
        layout_checks(pg, tag + ' filter')
        shot(pg, '11-%d-%s-%s-filter.png' % (w, theme or 'light', f))
        pg.click('#mpaChips [data-mf=due]') if vis(pg, '#mpaChips') else (pg.click('#mclPillB'), pg.wait_for_timeout(250), pg.click('#mpaChips [data-mf=due]'))
        pg.wait_for_timeout(1200)
        pill = re.sub(r'\s+', ' ', pg.inner_text('#mclPill')).strip()
        bb = pg.locator('#mclPill').bounding_box()
        check(bb['x'] + bb['width'] <= pg.locator('#vMap .ov.top').bounding_box()['x'] + pg.locator('#vMap .ov.top').bounding_box()['width'] + 0.5 and vis(pg, '#mclPillX'), '%s: a long pill stays in its place, × in reach: "%s"' % (tag, pill))
        check(not broken_words(pg, '#mclPill .mcl-pt, #mclPill .mcl-pn'), '%s: two filters, words whole' % tag)
        pg.click('#mclPeek'); pg.wait_for_timeout(700)
        b = layout_checks(pg, tag + ' open')
        check(b['sheet'][1] >= max(b['search'][3], b['pill'][3], b['side'][3]) + 20, '%s: the open sheet stays under the top controls' % tag)
        shot(pg, '12-%d-%s-%s-open.png' % (w, theme or 'light', f))
        pg.click('#nearHd'); pg.wait_for_timeout(300)
        pg.click('#mclMore'); pg.wait_for_timeout(300)
        tt = pg.inner_text('#mclTray')
        if lang: check('Ramani bila mtandao' in tt and 'Nifuate' in tt, '%s: tray in Kiswahili: %s' % (tag, tt.replace('\n', ' | ')))
        check(not broken_words(pg, '#mclTray .mcl-lb') and pg.locator('#mclTray').bounding_box()['x'] >= 0, '%s: tray labels whole, on screen' % tag)
        bad, _ = overlaps(pg, [('tray', k) for k in ['search', 'chips', 'pill', 'key', 'credit', 'pinned', 'here']]); check(not bad, '%s: the tray covers nothing it shouldn’t %s' % (tag, bad))
        shot(pg, '13-%d-%s-%s-more.png' % (w, theme or 'light', f))
        pg.keyboard.press('Escape'); pg.wait_for_timeout(200)
        check(not vis(pg, '#mclTray'), '%s: Escape closes the tray' % tag)
        pg.click('#mpaKey'); pg.wait_for_timeout(300)
        if lang: check('Bila ziara miezi 6' in pg.inner_text('#mclKeyPop'), '%s: the key in Kiswahili' % tag)
        bad, _ = overlaps(pg, [('keypop', 'search'), ('keypop', 'side'), ('keypop', 'credit')]); check(not bad, '%s: the key covers nothing it shouldn’t %s' % (tag, bad))
        shot(pg, '14-%d-%s-%s-key.png' % (w, theme or 'light', f))
        tap_empty(pg); pg.wait_for_timeout(400)  # that tap only closed the key
        tap_empty(pg) if 'mcl-clean' not in pg.get_attribute('#vMap', 'class') else None
        check('mcl-clean' in pg.get_attribute('#vMap', 'class') and credit_ok(pg), '%s: map only, credit in view' % tag)
        shot(pg, '15-%d-%s-%s-map-only.png' % (w, theme or 'light', f))
        check(not errs, '%s: no page errors %s' % (tag, errs[:3]))
        ctx.close()

    # ===== 8. reduced motion: no slide for the tray or the key, the map jumps to the filter =====================
    ctx = context(br, reduced_motion='reduce'); pg = ctx.new_page()
    sign_in(pg)
    pg.click('#mclMore'); pg.wait_for_timeout(200)
    check(pg.evaluate("getComputedStyle(document.querySelector('#mclTray')).animationName") == 'none', 'reduced motion: the tray just appears')
    pg.keyboard.press('Escape'); pg.click('#mpaKey'); pg.wait_for_timeout(100)
    check(pg.evaluate("getComputedStyle(document.querySelector('#mclKeyPop')).animationName") == 'none', 'and so does the key')
    pg.keyboard.press('Escape')
    pg.evaluate("window.__maps.find(x => x.getContainer().id === 'hMap').setView([-3.3, 36.6], 9, { animate: false })"); pg.wait_for_timeout(300)
    pg.click('#mpaChips [data-mt=water]'); pg.wait_for_timeout(250)
    n, out = pins_in_view(pg); check(n > 0 and out == 0, 'reduced motion: the map is on the filter straight away (%d, %d outside)' % (n, out))
    ctx.close()
    br.close()
print('PROBLEMS', problems)
