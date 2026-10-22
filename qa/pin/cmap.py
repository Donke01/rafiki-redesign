# Whole-country street maps (feat-cmap): Offline maps › Whole country maps (list, sizes, free space, Wi-Fi only,
# download in pieces with progress, Stop, resume after a dropped connection, update, delete), the "Offline street
# map" style, the map switching to the country file with no signal (projects and the OSM credit on top), English and
# Kiswahili, light, dark and sun themes, and the feature staying hidden while the office hasn't set it up.
#
# The country file is the SYNTHETIC test area made by tools/country-maps/test/make-test-data.py and built with
# Planetiler (CMAP_FILE, default tools/country-maps/test/test-area.pmtiles), served here as "Kenya".
# Run: BASE=http://localhost:8855 python3 qa/pin/cmap.py
import json, os, re
from playwright.sync_api import sync_playwright
HERE = os.path.dirname(os.path.abspath(__file__)); BASE = os.environ.get('BASE', 'http://localhost:8855'); OUT = os.path.join(HERE, 'cmap'); os.makedirs(OUT, exist_ok=True)
FILE = os.environ.get('CMAP_FILE', os.path.join(HERE, '..', '..', 'tools', 'country-maps', 'test', 'test-area.pmtiles'))
DATA = open(FILE, 'rb').read()
KE = 'kenya-2026-10-20.pmtiles'
LIST = {'format': 1, 'attribution': '© OpenStreetMap contributors', 'countries': [
    {'code': 'ke', 'name': 'Kenya', 'file': KE, 'date': '2026-10-20', 'bytes': len(DATA), 'bounds': [33.9, -4.8, 41.9, 5.0], 'maxzoom': 14},
    {'code': 'ug', 'name': 'Uganda', 'file': 'uganda-2026-10-20.pmtiles', 'date': '2026-10-20', 'bytes': 104857600, 'bounds': [29.5, -1.5, 35.1, 4.3], 'maxzoom': 14},
    {'code': 'tz', 'name': 'Tanzania', 'file': 'tanzania-2026-10-20.pmtiles', 'date': '2026-10-20', 'bytes': 209715200, 'bounds': [29.3, -11.8, 40.5, -0.9], 'maxzoom': 14},
    {'code': 'mw', 'name': 'Malawi', 'file': 'malawi-2026-10-20.pmtiles', 'date': '2026-10-20', 'bytes': 62914560, 'bounds': [32.6, -17.2, 35.95, -9.3], 'maxzoom': 14}]}
P = [{'id': '104', 'title': 'Kapsaret Borehole', 'country': 'Kenya', 'ck': 'ke', 'area': 'Eldoret', 'type': 'Borehole well', 'status': 'Completed', 'gps': [0.47, 35.25]},
     {'id': '105', 'title': 'Turbo Spring', 'country': 'Kenya', 'ck': 'ke', 'area': 'Turbo', 'type': 'Water collection', 'status': 'Completed', 'gps': [0.63, 35.05]},
     {'id': '106', 'title': 'Chepkongi Dispensary Well', 'country': 'Kenya', 'ck': 'ke', 'area': 'Eldoret', 'type': 'Borehole well', 'status': 'Completed', 'gps': [0.5143, 35.2698]},
     {'id': '107', 'title': 'Moiben Water Kiosk', 'country': 'Kenya', 'ck': 'ke', 'area': 'Eldoret', 'type': 'Borehole well', 'status': 'Completed', 'gps': [0.56, 35.33]}]
problems = []
def check(c, w): print(('ok   ' if c else 'FAIL ') + w); (None if c else problems.append(w))
CFG = {'cmaps': {'base': '/api/cmap/'}}
def api(route, req):
    if req.method == 'GET':
        if 'activity=' in req.url: return route.fulfill(json={'ok': True, 'activity': []})
        if 'team=' in req.url: return route.fulfill(json={'ok': True, 'team': []})
        if '?id=' in req.url: return route.fulfill(json={'ok': True, 'last': None, 'canUndo': True, 'visits': []})
        return route.fulfill(json={'ok': True, 'who': 'Meril Kiprop', 'admin': False, 'reviewOpen': 0, 'projects': P, 'repairs': {}, 'visits': {}, 'jobs': [], 'assigned': [], 'confirmed': {}, 'countryMaps': CFG['cmaps'], 'me': {'kind': 'app', 'nick': '', 'phone': '', 'home': 'ke'}})
    route.fulfill(json={'ok': True})
# the R2 files, with range requests; DROP lists pieces whose first try breaks off (a dropped connection)
SEEN, DROP, SLOW = [], set(), {'ms': 0}
def files(route, req):
    name = req.url.split('/api/cmap/')[1].split('?')[0]
    if name == 'countries.json': return route.fulfill(json=LIST, headers={'Cache-Control': 'no-cache'})
    if name != KE: return route.fulfill(status=404, body='Not found')
    rg = req.headers.get('range', ''); m = re.match(r'bytes=(\d+)-(\d+)$', rg)
    if not m: return route.fulfill(status=200, body=DATA, headers={'ETag': '"v1"', 'Accept-Ranges': 'bytes'})
    a, b = int(m.group(1)), min(int(m.group(2)), len(DATA) - 1); SEEN.append(a)
    if a in DROP: DROP.discard(a); return route.abort('connectionreset')
    if SLOW['ms']: import time; time.sleep(SLOW['ms'] / 1000)
    route.fulfill(status=206, body=DATA[a:b + 1], headers={'Content-Range': 'bytes %d-%d/%d' % (a, b, len(DATA)), 'ETag': '"v1"', 'Accept-Ranges': 'bytes', 'Content-Type': 'application/vnd.pmtiles'})
PNG = bytes.fromhex('89504e470d0a1a0a0000000d49484452000000010000000108060000001f15c4890000000d49444154789c6360f8cfc00000030101009a2ecb6a0000000049454e44ae426082')
INIT = """(() => { let _L; Object.defineProperty(window, 'L', { configurable: true, get() { return _L; }, set(v) { _L = v; try { v.Map.addInitHook(function () { (window.__maps = window.__maps || []).push(this); }); } catch (e) {} } });
  if (window.__cell) Object.defineProperty(navigator, 'connection', { configurable: true, get() { return window.__conn || (window.__conn = Object.assign(new EventTarget(), { type: 'cellular' })); } }); })();"""

def sign_in(pg, lang=None, chunk=131072):
    pg.route('**/api/pin-auth/me', lambda r, q: r.fulfill(json={'available': False})); pg.route(re.compile(r'/api/pin(\?.*)?$'), api)
    pg.route(re.compile(r'/api/cmap/'), files)
    pg.goto(BASE + '/pin'); pg.wait_for_timeout(800)
    pg.evaluate("(c) => { localStorage.setItem('rfPinCmChunk', String(c)); localStorage.setItem('rfPinOnb', '1'); }", chunk)
    if lang: pg.evaluate("(l) => localStorage.setItem('rfPinLang', l)", lang)
    pg.reload(); pg.wait_for_timeout(800)
    if pg.locator('.pk[data-k="9"]').count():
        for d in '9999': pg.click('.pk[data-k="%s"]' % d)
        pg.wait_for_timeout(1200)

def open_list(pg):
    pg.goto(BASE + '/pin#map'); pg.wait_for_timeout(1300)
    pg.click('#fldOb'); pg.wait_for_timeout(500); pg.click('#fldList'); pg.wait_for_timeout(1200)

def ctx_new(br, **kw):
    c = br.new_context(viewport={'width': kw.pop('w', 390), 'height': 844}, is_mobile=True, has_touch=True, service_workers='block', **kw)
    c.route(re.compile(r'https://(tile\.openstreetmap\.org|server\.arcgisonline\.com)/.*'), lambda r, q: r.fulfill(status=200, body=PNG, headers={'Content-Type': 'image/png', 'Access-Control-Allow-Origin': '*'}))
    return c

def map_px(pg, lat, lng):
    return pg.evaluate("([a, b]) => { const m = window.__maps.find(x => x.getContainer().id === 'hMap'); const p = m.latLngToContainerPoint([a, b]); const r = m.getContainer().getBoundingClientRect(); return [r.left + p.x, r.top + p.y]; }", [lat, lng])

def shot_el(pg, sel, name):
    # the section redraws while downloading, so take the page with the element's box
    b = pg.evaluate("(s) => { const e = document.querySelector(s); e.scrollIntoView({ block: 'center' }); const r = e.getBoundingClientRect(); return { x: r.x, y: r.y, width: r.width, height: r.height }; }", sel)
    pg.screenshot(path=os.path.join(OUT, name), clip={'x': max(0, b['x'] - 8), 'y': max(0, b['y'] - 8), 'width': b['width'] + 16, 'height': b['height'] + 16})

def pixel(pg, x, y):
    return pg.evaluate("""([x, y]) => { for (const c of document.querySelectorAll('.cm-layer canvas')) { const r = c.getBoundingClientRect(); if (x >= r.left && x < r.right && y >= r.top && y < r.bottom) { const k = c.width / r.width, d = c.getContext('2d').getImageData(Math.floor((x - r.left) * k), Math.floor((y - r.top) * k), 1, 1).data; return [d[0], d[1], d[2], d[3]]; } } return null; }""", [x, y])

with sync_playwright() as p:
    br = p.chromium.launch()
    # --- 1. the list: four countries with sizes, free space, Wi-Fi only switch, the browser warning ------------
    ctx = ctx_new(br); ctx.add_init_script(INIT); pg = ctx.new_page(); errs = []; pg.on('pageerror', lambda e: errs.append(str(e)))
    sign_in(pg); open_list(pg)
    box = pg.locator('#cmBox')
    check(box.is_visible() and 'Whole country maps' in box.inner_text(), 'Offline maps has a Whole country maps section')
    t = box.inner_text()
    check(all(n in t for n in ['Kenya', 'Uganda', 'Tanzania', 'Malawi']), 'it lists the four countries')
    check('100 MB' in t and '200 MB' in t and '60 MB' in t, 'with their sizes: ' + t.replace('\n', ' | ')[:300])
    check('Free space for maps on this device' in t, 'it shows the free space')
    check(not pg.locator('#cmMobile').is_checked() and 'Use mobile data too' in t, 'Wi-Fi only by default, with a switch for mobile data')
    check('Android app keeps it safely' in t, 'the browser size warning is shown')
    check('OpenStreetMap contributors' in t, 'the OpenStreetMap credit is shown')
    box.scroll_into_view_if_needed(); pg.screenshot(path=os.path.join(OUT, '01-country-list.png'))

    # --- 2. download Kenya: progress with Stop, a dropped connection, carry on by itself, then Resume after Stop ---
    SLOW['ms'] = 450; DROP.add(3 * 131072)
    pg.click('[data-cm-get=ke]'); pg.wait_for_timeout(600)
    t = box.inner_text()
    check('Downloading' in t and re.search(r'of [\d.]+ MB', t) is not None, 'progress shows while downloading: ' + re.sub(r'\s+', ' ', t)[:160])
    check(pg.locator('[data-cm-stop=ke]').count() == 1, 'with a Stop button')
    pg.wait_for_function("() => /Connection lost/.test(document.getElementById('cmBox').innerText) || /On this device/.test(document.getElementById('cmBox').innerText)", timeout=15000)
    t = box.inner_text()
    check('Connection lost' in t, 'a dropped connection is shown, not an error')
    shot_el(pg, '.cm-c[data-code=ke]', '03-connection-lost.png')
    pg.wait_for_function("() => document.querySelector('.cm-c[data-code=ke] [data-cm-stop]') && /Downloading/.test(document.getElementById('cmBox').innerText)", timeout=15000)
    for _ in range(40):
        if SEEN.count(3 * 131072) >= 2: break
        pg.wait_for_timeout(250)
    check(SEEN.count(3 * 131072) >= 2, 'the broken piece is asked again by itself')
    first = SEEN[:]
    check(first.count(0) == 1 and first.count(131072) == 1 and first.count(2 * 131072) == 1, 'pieces already saved are not downloaded again')
    pg.click('[data-cm-stop=ke]'); pg.wait_for_timeout(500)
    t = box.inner_text()
    check('Resume' in t and 'Stopped part way' in t and 'nothing is downloaded twice' in t, 'Stop keeps what is saved and offers Resume')
    shot_el(pg, '.cm-c[data-code=ke]', '04-stopped.png')
    n0 = len(SEEN); SLOW['ms'] = 900
    pg.click('[data-cm-get=ke]'); pg.wait_for_timeout(300)
    check('Downloading' in box.inner_text(), 'Resume shows the download going again')
    shot_el(pg, '.cm-c[data-code=ke]', '02-downloading.png'); SLOW['ms'] = 0
    pg.wait_for_function("() => /On this device/.test(document.getElementById('cmBox').innerText)", timeout=20000)
    asked = SEEN[n0:]
    check(0 not in asked and 131072 not in asked, 'Resume only fetches the missing pieces: %s' % asked)
    check(len(set(SEEN)) == -(-len(DATA) // 131072), 'every piece was fetched')
    have = pg.evaluate("JSON.parse(localStorage.getItem('rfPinCm')).ke")
    check(have and have['done'] and have['file'] == KE, 'Kenya is marked as on this device')
    stored = pg.evaluate("async () => { const c = await caches.open('rafiki-pin-cmap-kenya-2026-10-20'); const ks = await c.keys(); let n = 0; for (const k of ks) n += (await (await c.match(k)).arrayBuffer()).byteLength; return [ks.length, n]; }")
    check(stored[1] == len(DATA), 'the whole file is in the browser storage: %s of %d bytes' % (stored, len(DATA)))
    check(pg.locator('[data-cm-del=ke]').count() == 1 and pg.locator('[data-cm-get=ke]').count() == 0, 'Kenya now offers Delete (no Download)')
    pg.locator('#cmBox').scroll_into_view_if_needed(); pg.screenshot(path=os.path.join(OUT, '05-downloaded.png'))
    pg.click('.bsh-x'); pg.wait_for_timeout(500)

    # --- 3. the map: "Offline street map" style, drawn from the file, projects on top, OSM credit ------------------
    pg.wait_for_function("() => !document.querySelector('.bsh.fld-sh')", timeout=5000)
    pg.click('#vMap .mstyle:not(.fld-ob)'); pg.wait_for_timeout(500)
    if not pg.locator('.bsh-o[data-k=cmap]').count(): pg.screenshot(path=os.path.join(OUT, 'debug-style.png'))
    check(pg.locator('.bsh-o[data-k=cmap]').count() == 1, 'Map style offers Offline street map')
    pg.screenshot(path=os.path.join(OUT, '06-map-style.png'))
    pg.click('.bsh-o[data-k=cmap]'); pg.wait_for_timeout(600)
    pg.evaluate("() => window.__maps.find(x => x.getContainer().id === 'hMap').setView([0.5143, 35.2698], 13, { animate: false })"); pg.wait_for_timeout(2500)
    check(pg.locator('.cm-layer canvas').count() > 0, 'the country map is on the map')
    attr = pg.inner_text('#hMap .leaflet-control-attribution')
    check(attr.count('OpenStreetMap contributors') == 1, 'the map credits OpenStreetMap contributors (once): ' + attr)
    ab = pg.locator('#hMap .leaflet-control-attribution').bounding_box(); sheet = pg.locator('#vMap .ov.bot').bounding_box()
    check(ab['y'] + ab['height'] <= sheet['y'] + 1, 'the credit sits above the sheet, in view')
    x, y = map_px(pg, 0.5143, 35.2698)
    check(pg.locator('#hMap .leaflet-overlay-pane path.leaflet-interactive').count() >= 4, 'project pins are drawn on top of the country map')
    bg = pixel(pg, 8, 300)
    check(bg is not None and bg[3] == 255, 'map tiles are drawn: %s' % bg)
    pg.screenshot(path=os.path.join(OUT, '07-offline-street-map-z13.png'))
    pg.evaluate("() => window.__maps.find(x => x.getContainer().id === 'hMap').setView([0.5143, 35.2698], 16, { animate: false })"); pg.wait_for_timeout(2500)
    pg.screenshot(path=os.path.join(OUT, '08-offline-street-map-z16-overzoom.png'))
    pg.evaluate("() => window.__maps.find(x => x.getContainer().id === 'hMap').setView([0.45, 35.25], 10, { animate: false })"); pg.wait_for_timeout(2500)
    pg.screenshot(path=os.path.join(OUT, '09-offline-street-map-z10.png'))
    pg.evaluate("() => window.__maps.find(x => x.getContainer().id === 'hMap').setView([0.55, 35.3], 7, { animate: false })"); pg.wait_for_timeout(2500)
    pg.screenshot(path=os.path.join(OUT, '10-offline-street-map-z7.png'))

    # --- 4. back to the street map; with no signal the country map comes on by itself ---------------------------------
    pg.click('#vMap .mstyle:not(.fld-ob)'); pg.wait_for_timeout(500); pg.click('.bsh-o[data-k=street]'); pg.wait_for_timeout(600)
    check(pg.locator('.cm-layer').count() == 0, 'Street map takes the country map off while there is signal')
    ctx.set_offline(True); pg.wait_for_timeout(400)
    pg.evaluate("() => window.__maps.find(x => x.getContainer().id === 'hMap').setView([0.50, 35.27], 14, { animate: false })"); pg.wait_for_timeout(2500)
    check(pg.locator('.cm-layer canvas').count() > 0, 'with no signal the map switches to the country file by itself')
    check(pg.locator('#hMap .leaflet-overlay-pane path.leaflet-interactive').count() >= 4, 'projects still show on it')
    pg.screenshot(path=os.path.join(OUT, '11-no-signal-auto.png'))
    pg.click('#vMap .mstyle:not(.fld-ob)'); pg.wait_for_timeout(400); pg.click('.bsh-o[data-k=sat]'); pg.wait_for_timeout(600)
    check(pg.locator('.cm-layer').count() == 0, 'picking Satellite with no signal shows the satellite photos kept on the device')
    ctx.set_offline(False); pg.wait_for_timeout(600)
    check(pg.locator('.cm-layer').count() == 0, 'signal back: the chosen style stays')
    ctx.set_offline(True); pg.wait_for_timeout(600)
    check(pg.locator('.cm-layer').count() == 1, 'signal gone again: country map again')
    ctx.set_offline(False); pg.wait_for_timeout(600)

    # --- 5. themes and Kiswahili ---------------------------------------------------------------------------------
    open_list(pg)
    for th in ['dark', 'sun']:
        pg.evaluate("(t) => document.documentElement.setAttribute('data-theme', t)", th); pg.wait_for_timeout(250)
        pg.locator('#cmBox').scroll_into_view_if_needed(); pg.screenshot(path=os.path.join(OUT, '12-list-%s.png' % th))
    pg.evaluate("document.documentElement.removeAttribute('data-theme')")
    # words are never broken in the middle
    broken = pg.evaluate("""() => { const out = []; document.querySelectorAll('#cmBox b, #cmBox span, #cmBox em, #cmBox p, #cmBox .btn').forEach(e => { const cs = getComputedStyle(e); if (cs.wordBreak === 'break-all' || cs.overflowWrap === 'anywhere' || cs.hyphens === 'auto') out.push(e.textContent.slice(0, 30)); }); return out; }""")
    check(not broken, 'no word-breaking styles in the section: %s' % broken)

    # --- 6. update when a newer file is listed, then delete ------------------------------------------------------
    LIST['countries'][0]['file'] = 'kenya-2026-11-01.pmtiles'
    pg.click('.bsh-x'); pg.wait_for_timeout(400); open_list(pg)
    t = box.inner_text()
    check('A newer map is ready' in t and pg.locator('[data-cm-get=ke]').inner_text().strip().endswith('Update'), 'a newer file offers Update')
    LIST['countries'][0]['file'] = KE
    pg.click('[data-cm-del=ke]'); pg.wait_for_timeout(200); check('Delete?' in pg.inner_text('[data-cm-del=ke]'), 'Delete asks to be sure')
    pg.click('[data-cm-del=ke]'); pg.wait_for_timeout(800)
    left = pg.evaluate("async () => (await caches.keys()).filter(k => k.startsWith('rafiki-pin-cmap-'))")
    check(left == [] and pg.evaluate("JSON.parse(localStorage.getItem('rfPinCm')).ke") is None, 'Delete removes the file from the browser: %s' % left)
    check(pg.locator('[data-cm-get=ke]').count() == 1, 'and offers Download again')
    check(not errs, 'no page errors: %s' % errs[:3])
    ctx.close()

    # --- 7. Wi-Fi only on a phone that says it is on mobile data ----------------------------------------------------
    ctx = ctx_new(br); ctx.add_init_script("window.__cell = true;"); ctx.add_init_script(INIT); pg = ctx.new_page(); errs = []; pg.on('pageerror', lambda e: errs.append(str(e)))
    sign_in(pg); open_list(pg); n0 = len(SEEN)
    pg.click('[data-cm-get=ke]'); pg.wait_for_timeout(700)
    check('Waiting for Wi-Fi' in pg.inner_text('#cmBox') and len(SEEN) == n0, 'on mobile data nothing downloads by default')
    pg.locator('#cmBox').scroll_into_view_if_needed(); pg.screenshot(path=os.path.join(OUT, '13-wifi-only.png'))
    pg.check('#cmMobile'); pg.wait_for_timeout(300); pg.click('[data-cm-get=ke]')
    pg.wait_for_function("() => /On this device/.test(document.getElementById('cmBox').innerText)", timeout=20000)
    check(len(SEEN) > n0, 'with “Use mobile data too” it downloads')
    check(pg.evaluate("localStorage.getItem('rfPinCmMobile')") == '1', 'the switch is remembered')
    check(not errs, 'no page errors: %s' % errs[:3])
    ctx.close()

    # --- 8. Kiswahili, narrow phone ---------------------------------------------------------------------------------
    ctx = ctx_new(br, w=360); ctx.add_init_script(INIT); pg = ctx.new_page(); errs = []; pg.on('pageerror', lambda e: errs.append(str(e)))
    sign_in(pg, 'sw'); open_list(pg)
    t = pg.inner_text('#cmBox')
    check('Ramani za nchi nzima' in t and 'Tumia data ya simu pia' in t and 'Pakua' in t, 'Kiswahili: ' + t.replace('\n', ' | ')[:200])
    over = pg.evaluate("() => [...document.querySelectorAll('#cmBox *')].filter(e => e.getBoundingClientRect().right > innerWidth + 1).map(e => e.className || e.tagName).slice(0, 5)")
    check(not over, 'nothing runs off a 360 px screen: %s' % over)
    pg.locator('#cmBox').scroll_into_view_if_needed(); pg.screenshot(path=os.path.join(OUT, '14-list-sw-360.png'))
    pg.click('[data-cm-get=ke]'); pg.wait_for_function("() => /Kwenye kifaa hiki/.test(document.getElementById('cmBox').innerText)", timeout=20000)
    pg.click('.bsh-x'); pg.wait_for_timeout(400); pg.click('#vMap .mstyle:not(.fld-ob)'); pg.wait_for_timeout(500)
    check('Ramani ya barabara bila mtandao' in pg.inner_text('.bsh'), 'the map style is in Kiswahili too')
    pg.screenshot(path=os.path.join(OUT, '15-map-style-sw.png'))
    check(not errs, 'no page errors: %s' % errs[:3])
    ctx.close()

    # --- 9. not set up: nothing shows ------------------------------------------------------------------------------
    CFG['cmaps'] = None
    ctx = ctx_new(br); ctx.add_init_script(INIT); pg = ctx.new_page(); errs = []; pg.on('pageerror', lambda e: errs.append(str(e)))
    sign_in(pg); open_list(pg)
    check(not pg.locator('#cmBox').is_visible(), 'while PIN_COUNTRY_MAPS is unset the section is hidden')
    pg.click('.bsh-x'); pg.wait_for_timeout(300); pg.click('#vMap .mstyle:not(.fld-ob)'); pg.wait_for_timeout(400)
    check(pg.locator('.bsh-o[data-k=cmap]').count() == 0, 'and Map style has no offline street map')
    ctx.set_offline(True); pg.wait_for_timeout(400); check(pg.locator('.cm-layer').count() == 0, 'and nothing changes with no signal')
    check(not errs, 'no page errors: %s' % errs[:3])
    ctx.close(); br.close()

print('\n%d problem(s)' % len(problems)); [print(' -', x) for x in problems]
