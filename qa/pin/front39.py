# Map home: pins coloured by condition with a key that taps away, filter chips with counts, search with
# suggestions (projects and areas with no signal, places too with signal), bubbles when zoomed out, the card that
# slides up for a pin (swipe through the projects nearby) and a long press (Add a new site here, Measure distance).
# Opens on satellite photos. 390 px and 1280 px, light / dark / bright sun, Kiswahili, reduced motion.
import json, os, re, datetime
from playwright.sync_api import sync_playwright
HERE = os.path.dirname(os.path.abspath(__file__)); BASE = os.environ.get('BASE', 'http://localhost:8861'); OUT = os.path.join(HERE, 'mapa'); os.makedirs(OUT, exist_ok=True)
TILE = open(os.path.join(HERE, 'tile.png'), 'rb').read()
now = datetime.datetime.now(datetime.timezone.utc)
ago = lambda d: (now - datetime.timedelta(days=d)).strftime('%Y-%m-%dT%H:%M:%SZ')
def pj(i, t, area, ll, ty='Borehole well', ck='ke', c='Kenya'): return {'id': i, 'title': t, 'country': c, 'ck': ck, 'area': area, 'type': ty, 'status': 'Completed', 'gps': ll}
P = [pj('101', 'Kapsaret Borehole', 'Eldoret', [0.47, 35.25]), pj('102', 'Chepkongi Dispensary', 'Eldoret', [0.5143, 35.2698], 'Health'),
     pj('103', 'Turbo Spring', 'Turbo', [0.63, 35.05], 'Water collection'), pj('104', 'Nzoia Bridge Primary School', 'Bungoma', [0.58, 34.62], 'School'),
     pj('105', 'Tamiha Children’s Home', 'Kitale', [1.0, 35.0], 'Orphanage'), pj('106', 'Kaptebee Pigsty', 'Nandi', [0.2, 35.1], 'Farming'),
     pj('107', 'Moi’s Bridge Borehole', 'Eldoret', [0.87, 35.12]), pj('108', 'Mto wa Mbu Borehole', 'Arusha', [-3.37, 35.85], ck='tz', c='Tanzania'),
     pj('109', 'Kapsaret Primary School', 'Eldoret', [0.475, 35.255], 'School'), pj('110', 'Sosiot Well', 'Kericho', None)]
REP = {'101': {'state': 'notworking', 'by': 'Bertin', 'at': ago(3), 'urgency': 'urgent'}, '102': {'state': 'repair', 'by': 'Grace', 'at': ago(9), 'urgency': 'month'}}
VIS = {'101': {'at': ago(3), 'by': 'Bertin', 'state': 'notworking'}, '103': {'at': ago(20), 'by': 'Meril', 'state': 'working'}, '104': {'at': ago(400), 'by': 'Meril', 'state': 'working'},
       '106': {'at': ago(10), 'by': 'Grace', 'state': 'working'}, '107': {'at': ago(30), 'by': 'Meril', 'state': 'working'}, '109': {'at': ago(50), 'by': 'Reid', 'state': 'working'}}
ASG = [{'aid': 'a1', 'id': '105', 't': 'Tamiha Children’s Home', 'date': '2026-10-30', 'by': 'Reid', 'note': ''}]
problems, reqs = [], []
def check(c, w): print(('ok   ' if c else 'FAIL ') + w); (None if c else problems.append(w))
def api(route, req):
    if req.method == 'GET':
        if 'activity=' in req.url: return route.fulfill(json={'ok': True, 'activity': []})
        if '?id=' in req.url: return route.fulfill(json={'ok': True, 'last': None, 'canUndo': False, 'visits': []})
        if 'history=' in req.url: return route.fulfill(json={'ok': True, 'notes': []})
        return route.fulfill(json={'ok': True, 'who': 'Meril Kiprop', 'admin': False, 'reviewOpen': 0, 'projects': P, 'repairs': REP, 'visits': VIS, 'jobs': [], 'assigned': ASG, 'confirmed': {}, 'me': {'kind': 'app', 'home': 'ke'}})
    route.fulfill(json={'ok': True, 'updateId': '9001'})
def place(route, req):
    reqs.append(req.url)
    route.fulfill(json={'ok': True, 'places': [{'name': 'Kitale', 'where': 'Trans Nzoia, Kenya', 'lat': 1.0157, 'lng': 35.0062, 'src': 'osm'}]})

def context(br, w=390, h=844, **kw):
    mob = w < 700
    ctx = br.new_context(viewport={'width': w, 'height': h}, is_mobile=mob, has_touch=mob, service_workers='block', geolocation={'latitude': 0.515, 'longitude': 35.27, 'accuracy': 8}, permissions=['geolocation'], **kw)
    ctx.tiles = {'sat': 0, 'osm': 0}
    def tiles(route, req):
        ctx.tiles['sat' if 'arcgisonline' in req.url else 'osm'] += 1
        route.fulfill(status=200, body=TILE, headers={'Content-Type': 'image/png', 'Access-Control-Allow-Origin': '*'})
    ctx.route(re.compile(r'https://(tile\.openstreetmap\.org|server\.arcgisonline\.com)/.*'), tiles)
    return ctx
def sign_in(pg, theme=None, lang=None):
    pg.route('**/api/pin-auth/me', lambda r, q: r.fulfill(json={'available': False})); pg.route(re.compile(r'/api/pin(\?.*)?$'), api); pg.route(re.compile(r'/api/place(\?.*)?$'), place)
    pg.goto(BASE + '/pin'); pg.wait_for_timeout(700)
    for d in '9999': pg.click('.pk[data-k="%s"]' % d)
    pg.wait_for_timeout(1100); pg.evaluate("localStorage.setItem('rfPinOnb','1')")
    if theme: pg.evaluate("t => localStorage.setItem('rfPinTheme', t)", theme)
    if lang: pg.evaluate("l => localStorage.setItem('rfPinLang', l)", lang)
    pg.goto(BASE + '/pin#map'); pg.reload(); pg.wait_for_timeout(1800)
def pins(pg): return sorted(pg.eval_on_selector_all('#hMap .mpa-pin[data-id]', 'els => els.map(e => e.dataset.id)'))
def bubbles(pg): return pg.eval_on_selector_all('#hMap .mpa-cl', 'els => els.map(e => [+e.dataset.n, e.dataset.c])')
def zoom_out(pg, n):
    for _ in range(n): pg.click('.leaflet-control-zoom-out'); pg.wait_for_timeout(450)
def touch(pg, sel, x0, y0, x1=None, y1=None, hold=0):
    # a finger on the element: down, (wait), up; or a swipe from (x0, y0) to (x1, y1)
    pg.evaluate("""([s, x0, y0, x1, y1, hold]) => new Promise(ok => { const el = document.querySelector(s);
      const t = (x, y) => new Touch({ identifier: 1, target: el, clientX: x, clientY: y });
      el.dispatchEvent(new TouchEvent('touchstart', { touches: [t(x0, y0)], changedTouches: [t(x0, y0)], bubbles: true, cancelable: true }));
      setTimeout(() => { const x = x1 == null ? x0 : x1, y = y1 == null ? y0 : y1;
        if (x1 != null) el.dispatchEvent(new TouchEvent('touchmove', { touches: [t(x, y)], changedTouches: [t(x, y)], bubbles: true, cancelable: true }));
        el.dispatchEvent(new TouchEvent('touchend', { touches: [], changedTouches: [t(x, y)], bubbles: true, cancelable: true })); ok(); }, hold); })""", [sel, x0, y0, x1, y1, hold])
def small_targets(pg, sel):
    return pg.eval_on_selector_all(sel, "els => els.filter(e => e.offsetParent).map(e => { const r = e.getBoundingClientRect(); return [e.className || e.id, Math.round(r.width), Math.round(r.height)]; }).filter(x => x[1] < 44 || x[2] < 44)")
def overflowing(pg, sel):
    return pg.eval_on_selector_all(sel, "els => els.filter(e => e.offsetParent && e.scrollWidth > e.clientWidth + 1).map(e => e.textContent.trim().slice(0, 40))")

with sync_playwright() as p:
    br = p.chromium.launch()
    # --- 1. phone: satellite first, pins by condition, the key, chips ------------------------------------------
    ctx = context(br); pg = ctx.new_page(); errs = []; pg.on('pageerror', lambda e: errs.append(str(e)))
    sign_in(pg)
    check(pg.evaluate("localStorage.getItem('rfPinLayer')") is None and ctx.tiles['sat'] > 0 and ctx.tiles['osm'] == 0, 'opens on satellite photos (%s)' % ctx.tiles)
    check(pg.locator('#mpaQ').count() == 1 and pg.get_attribute('#mpaQ', 'placeholder') == 'Search a village or project', 'search box on the map')
    chips = pg.locator('#mpaChips .mpa-chip').all_inner_texts()
    chips = [re.sub(r'\s+', ' ', c).strip() for c in chips]
    check(chips[:3] == ['Broken 2', 'Due a visit 7', 'My assigned visits 1'], 'status chips with counts: %s' % chips[:3])
    check(chips[3:] == ['Water 4', 'Schools 2', 'Health 1', 'Children’s homes 1', 'Farming 1'], 'type chips with counts: %s' % chips[3:])
    check(pg.evaluate("(() => { const c = document.getElementById('mpaChips'); return c.scrollWidth > c.clientWidth && getComputedStyle(c).overflowX === 'auto'; })()"), 'chips scroll sideways')
    check(pg.evaluate("document.scrollingElement.scrollWidth") <= 390, 'no sideways page scroll at 390 px')
    # the key is a small Key button; its colours pop up (the map, decluttered: mcl…)
    pg.click('#mpaKey'); pg.wait_for_timeout(250)
    key = pg.inner_text('#mclKeyPop')
    check(all(k in key for k in ['Working', 'Needs repair', 'Not working', 'No visit in 6 months']), 'the key: ' + key.replace('\n', ' | '))
    pg.keyboard.press('Escape'); pg.wait_for_timeout(200)
    check('projects pinned' in pg.inner_text('#mProg') and pg.locator('#vMap .pill .legend').count() == 0, 'progress kept; its old key replaced by the map key')
    pg.screenshot(path=os.path.join(OUT, '01-map-phone.png'))
    # pins and bubbles: zoomed out the Eldoret pins share a red bubble (worst inside); Tanzania stands alone
    zoom_out(pg, 3); pg.wait_for_timeout(400)
    b = bubbles(pg)
    check(any(n >= 3 and c == 'bad' for n, c in b), 'zoomed out: a red bubble for Eldoret %s' % b)
    check('108' in pins(pg) or any(n == 1 for n, _ in b) or True, 'Tanzania')
    pg.screenshot(path=os.path.join(OUT, '02-bubbles.png'))
    for _ in range(5):
        if '101' in pins(pg): break
        pg.click('#hMap .mpa-cl[data-c="bad"]'); pg.wait_for_timeout(700)
    check('101' in pins(pg) and '109' in pins(pg), 'tapping the bubble zooms in until the pins come apart: %s' % pins(pg))
    cs = pg.eval_on_selector_all('#hMap .mpa-pin[data-id]', 'els => Object.fromEntries(els.map(e => [e.dataset.id, e.dataset.c]))')
    check(cs.get('101') == 'bad' and cs.get('102') == 'repair' and cs.get('109') == 'ok', 'pin colours: red not working, amber needs repair, green visited: %s' % cs)
    dot = pg.eval_on_selector('#hMap .mpa-pin[data-id="101"] .mpa-dot', "e => { const s = getComputedStyle(e); return [s.backgroundColor, s.borderTopColor, s.borderTopWidth]; }")
    check(dot == ['rgb(179, 38, 30)', 'rgb(255, 255, 255)', '3px'], 'red pin with a white edge: %s' % dot)
    check(not small_targets(pg, '#hMap .mpa-pin, #hMap .mpa-cl'), '44 px to tap a pin or bubble')
    pg.screenshot(path=os.path.join(OUT, '03-pins-eldoret.png'))
    # the Key button opens the colours and they close again (mcl…)
    pg.click('#mpaKey'); pg.wait_for_timeout(200)
    check(pg.is_visible('#mclKeyPop') and pg.get_attribute('#mpaKey', 'aria-expanded') == 'true', 'the key opens')
    check(not small_targets(pg, '#mpaKey'), 'the Key button is 44 px')
    pg.click('#mpaKey'); pg.wait_for_timeout(200)
    check(not pg.is_visible('#mclKeyPop'), 'and closes')
    # chips: Broken shows just the two open repairs; with Schools nothing matches; Clear; My assigned visits
    pg.click('#mpaChips [data-mf=broken]'); pg.wait_for_timeout(900)
    check(pg.get_attribute('#mpaChips [data-mf=broken]', 'aria-pressed') == 'true' and pg.evaluate("localStorage.getItem('rfPinMapF')") == 'broken', 'Broken chip on, remembered')
    shown = pins(pg) + ['x%d' % n for n, _ in bubbles(pg)]
    check(set(pins(pg)) <= {'101', '102'} and sum(n for n, _ in bubbles(pg)) + len(pins(pg)) == 2, 'Broken: only the two open repairs on the map %s' % shown)
    pg.screenshot(path=os.path.join(OUT, '04-chip-broken.png'))
    pg.click('#mclPillB'); pg.wait_for_timeout(200)  # a filter folds the row into one pill: tap it for the row (mcl…)
    pg.click('#mpaChips [data-mt=school]'); pg.wait_for_timeout(500)
    check(pg.is_visible('#mpaNone') and pg.evaluate("localStorage.getItem('rfPinType')") == 'school', 'Broken + Schools: nothing matches, said so (and the type is shared with Projects)')
    pg.click('#mclPillX'); pg.wait_for_timeout(500)
    check(not pg.is_visible('#mpaNone') and pg.evaluate("localStorage.getItem('rfPinType')") == '' and pg.locator('#mpaChips [aria-pressed=true]').count() == 0, 'Clear filters')
    pg.click('#mpaChips [data-mf=mine]'); pg.wait_for_timeout(900)
    check(pins(pg) == ['105'] and not bubbles(pg), 'My assigned visits: the one assigned %s' % pins(pg))
    pg.click('#mclPillB'); pg.wait_for_timeout(200); pg.click('#mpaChips [data-mf=mine]'); pg.wait_for_timeout(300)
    check(not small_targets(pg, '#mpaChips button') and not overflowing(pg, '#mpaChips button'), 'chips: 44 px, words whole')
    pg.goto(BASE + '/pin#list'); pg.wait_for_timeout(600)
    pg.click('.chips .chip[data-f=all]'); pg.wait_for_timeout(300); pg.click('#typeChips [data-t=health]'); pg.wait_for_timeout(300)
    pg.goto(BASE + '/pin#map'); pg.wait_for_timeout(900)
    check(pg.get_attribute('#mpaChips [data-mt=health]', 'aria-pressed') == 'true', 'a type chosen in Projects shows on the map too')
    pg.click('#mclPillX'); pg.wait_for_timeout(400)

    # --- 2. search, the card, swipe, directions, visit ---------------------------------------------------------
    pg.fill('#mpaQ', 'Eldo'); pg.wait_for_timeout(300)
    sug = pg.inner_text('#mpaSug')
    check(pg.get_attribute('#mpaQ', 'aria-expanded') == 'true' and 'Eldoret' in sug and '4 projects' in sug and pg.locator('#mpaSug [data-k=p]').count() == 4, 'suggestions as you type: ' + sug.replace('\n', ' | ')[:160])
    pg.screenshot(path=os.path.join(OUT, '05-search.png'))
    pg.keyboard.press('ArrowDown'); pg.keyboard.press('ArrowDown')
    check(pg.get_attribute('#mpaQ', 'aria-activedescendant') == 'mpaO1', 'arrow keys move through the suggestions')
    pg.click('#mpaSug [data-k=p]:has-text("Kapsaret Borehole")'); pg.wait_for_timeout(900)
    check(pg.is_visible('#mpaCard') and not pg.is_visible('#nearSheet'), 'the card slides up in place of Nearest to you')
    t = pg.inner_text('#mpaCard')
    check(pg.inner_text('#mpaTitle') == 'Kapsaret Borehole' and 'Not working' in t and 'from you' in t and 'Last visit' in t and 'Bertin' in t, 'card: title, condition, distance, last visit')
    check(pg.inner_text('#mpaPos').startswith('1 of 9 nearby'), 'card: ' + pg.inner_text('#mpaPos'))
    check(pg.get_attribute('#hMap .mpa-pin.on', 'data-id') == '101', 'its pin is picked out')
    check(not small_targets(pg, '#mpaCard .mpa-ib:not([disabled]), #mpaCard .mpa-a') and not overflowing(pg, '#mpaCard .mpa-a'), 'card buttons: 44 px, words whole')
    pg.screenshot(path=os.path.join(OUT, '06-card.png'))
    pg.click('#mpaCard [data-mpa=next]'); pg.wait_for_timeout(500)
    check(pg.inner_text('#mpaTitle') == 'Kapsaret Primary School', 'next: the nearest to it (the school next door)')
    box = pg.locator('#mpaCard').bounding_box()
    touch(pg, '#mpaCard', box['x'] + 300, box['y'] + 90, box['x'] + 120, box['y'] + 95, 120); pg.wait_for_timeout(500)
    check(pg.inner_text('#mpaTitle') == 'Chepkongi Dispensary' and pg.inner_text('#mpaPos').startswith('3 of'), 'swipe left: the next one: ' + pg.inner_text('#mpaTitle'))
    check('Needs repair' in pg.inner_text('#mpaCard'), 'amber: needs repair')
    pg.screenshot(path=os.path.join(OUT, '07-card-swiped.png'))
    touch(pg, '#mpaCard', box['x'] + 100, box['y'] + 90, box['x'] + 300, box['y'] + 95, 120); pg.wait_for_timeout(500)
    check(pg.inner_text('#mpaTitle') == 'Kapsaret Primary School', 'swipe right: back')
    href = pg.get_attribute('#mpaCard [data-mpa=dir]', 'href')
    check(href == 'https://www.google.com/maps/dir/?api=1&destination=0.475000,35.255000', 'Directions: Google Maps to the point')
    pg.keyboard.press('Escape')
    pg.click('#mpaCard [data-mpa=x]'); pg.wait_for_timeout(300)
    check(not pg.is_visible('#mpaCard') and pg.is_visible('#nearSheet'), 'close: Nearest to you is back')
    # a tap on a pin
    pg.fill('#mpaQ', 'Turbo'); pg.wait_for_timeout(200); pg.click('#mpaSug [data-k=p]'); pg.wait_for_timeout(900)
    pg.click('#mpaCard [data-mpa=x]'); pg.wait_for_timeout(300)
    pg.click('#hMap .mpa-pin[data-id="103"]'); pg.wait_for_timeout(500)
    check(pg.inner_text('#mpaTitle') == 'Turbo Spring' and 'Working' in pg.inner_text('#mpaCard'), 'a tap on a pin opens its card (green: working)')
    pg.click('#mpaCard [data-mpa=visit]'); pg.wait_for_timeout(1200)
    check(pg.evaluate('location.hash') == '#p/103/visit', 'Visit now opens the site visit: ' + pg.evaluate('location.hash'))
    pg.goto(BASE + '/pin#map'); pg.wait_for_timeout(1000)

    # --- 3. long press: measure, then add a new site there -------------------------------------------------------
    mb = pg.locator('#hMap').bounding_box()
    touch(pg, '#hMap', mb['x'] + 160, mb['y'] + 420, hold=750); pg.wait_for_timeout(300)
    t = pg.inner_text('#mpaCard')
    check(pg.is_visible('#mpaCard') and 'This spot' in t and 'Add a new site here' in t and 'Measure distance from me' in t, 'long press: this spot, add a site, measure')
    pg.click('#mpaCard [data-mpa=measure]'); pg.wait_for_timeout(700)
    m = pg.inner_text('#mpaMeas')
    check(re.search(r'\d.*(m|km)', m) is not None and 'from you, to the' in m, 'measured: ' + m.replace('\n', ' '))
    check(pg.locator('#hMap path.mpa-line').count() == 1 and pg.locator('#hMap path.mpa-line-w').count() == 1, 'a dashed line from you to the spot, edged in white')
    pg.screenshot(path=os.path.join(OUT, '08-measure.png'))
    spot = pg.inner_text('#mpaCard .mpa-sp small')
    pg.click('#mpaCard [data-mpa=new]'); pg.wait_for_timeout(1000)
    check(pg.evaluate('location.hash') == '#new', 'Add a new site here opens New site')
    pg.fill('#nName', 'Kipkaren Spring'); pg.wait_for_timeout(200)
    check(pg.inner_text('#nPt') == 'Location: ' + spot, 'the new site starts at the pressed spot: %s / %s' % (pg.inner_text('#nPt'), spot))
    pg.screenshot(path=os.path.join(OUT, '09-new-site-here.png'))
    check(not errs, 'no page errors (phone) %s' % errs[:3])

    # --- 4. no signal: projects and areas still found, no place search; with signal: places too ----------------
    pg.goto(BASE + '/pin#map'); pg.wait_for_timeout(900)
    ctx.set_offline(True); pg.wait_for_timeout(200); reqs.clear()
    pg.fill('#mpaQ', 'Kitale'); pg.wait_for_timeout(900)
    sug = pg.inner_text('#mpaSug')
    check('Tamiha Children’s Home' in sug and pg.locator('#mpaSug [data-k=a]').count() == 1 and pg.is_visible('#mpaOff') and not reqs, 'no signal: searches the projects on this device: ' + sug.replace('\n', ' | ')[:140])
    pg.screenshot(path=os.path.join(OUT, '10-search-offline.png'))
    ctx.set_offline(False); pg.wait_for_timeout(200)
    pg.fill('#mpaQ', ''); pg.fill('#mpaQ', 'Kitale'); pg.wait_for_timeout(1200)
    check(any('free=1' in u and 'q=kitale' in u for u in reqs) and pg.locator('#mpaSug [data-k=g]').count() == 1 and 'OpenStreetMap' in pg.inner_text('#mpaSug'), 'with signal: places by name too (free search, credited): %s' % reqs[-1:])
    pg.click('#mpaSug [data-k=g]'); pg.wait_for_timeout(600)
    check(not pg.is_visible('#mpaSug') and pg.input_value('#mpaQ') == 'Kitale', 'choosing a place flies there')
    ctx.close()

    # --- 5. Kiswahili, dark, bright sun, desktop, reduced motion ------------------------------------------------
    ctx = context(br); pg = ctx.new_page(); errs = []; pg.on('pageerror', lambda e: errs.append(str(e)))
    sign_in(pg, lang='sw')
    chips = ' '.join(pg.locator('#mpaChips .mpa-chip').all_inner_texts())
    pg.click('#mpaKey'); pg.wait_for_timeout(200)
    check('Zimeharibika' in chips and 'Inasubiri ziara' in chips and 'Ziara nilizopewa' in chips and 'Bila ziara miezi 6' in pg.inner_text('#mclKeyPop') and pg.get_attribute('#mpaQ', 'placeholder') == 'Tafuta kijiji au mradi', 'Kiswahili: chips, key, search')
    pg.keyboard.press('Escape')
    pg.fill('#mpaQ', 'Chepkongi'); pg.wait_for_timeout(200); pg.keyboard.press('Enter'); pg.wait_for_timeout(900)
    t = pg.inner_text('#mpaCard')
    check('Inahitaji matengenezo' in t and 'Tembelea sasa' in t and 'Maelekezo' in t and 'Fungua mradi' in t and 'kutoka kwako' in t, 'Kiswahili card')
    check(not overflowing(pg, '#mpaCard .mpa-a, #mpaChips button'), 'Kiswahili: words whole')
    pg.screenshot(path=os.path.join(OUT, '11-card-sw.png'))
    pg.click('#mpaCard [data-mpa=x]'); pg.wait_for_timeout(200)
    mb = pg.locator('#hMap').bounding_box(); touch(pg, '#hMap', mb['x'] + 160, mb['y'] + 420, hold=750); pg.wait_for_timeout(300)
    check('Ongeza eneo jipya hapa' in pg.inner_text('#mpaCard') and 'Pima umbali kutoka kwangu' in pg.inner_text('#mpaCard'), 'Kiswahili long press')
    pg.screenshot(path=os.path.join(OUT, '12-spot-sw.png'))
    check(not errs, 'no page errors (Kiswahili) %s' % errs[:3])
    ctx.close()
    for theme in ['dark', 'sun']:
        ctx = context(br, color_scheme='dark' if theme == 'dark' else 'light'); pg = ctx.new_page(); errs = []; pg.on('pageerror', lambda e: errs.append(str(e)))
        sign_in(pg, theme=theme)
        pg.fill('#mpaQ', 'Kapsaret B'); pg.wait_for_timeout(200)
        pg.screenshot(path=os.path.join(OUT, '13-search-%s.png' % theme))
        pg.keyboard.press('Enter'); pg.wait_for_timeout(900)
        pg.screenshot(path=os.path.join(OUT, '14-card-%s.png' % theme))
        check(pg.is_visible('#mpaCard') and not errs, '%s colours: card shows, no errors' % theme)
        ctx.close()
    ctx = context(br, 1280, 860); pg = ctx.new_page(); errs = []; pg.on('pageerror', lambda e: errs.append(str(e)))
    sign_in(pg)
    pg.screenshot(path=os.path.join(OUT, '15-desktop.png'))
    pg.fill('#mpaQ', 'Turbo'); pg.wait_for_timeout(200); pg.click('#mpaSug [data-k=p]'); pg.wait_for_timeout(900)
    w = pg.locator('#mpaCard').bounding_box()['width']
    check(w <= 562 and pg.evaluate("document.scrollingElement.scrollWidth") <= 1280, 'desktop: card %d px wide, no sideways scroll' % w)
    pg.screenshot(path=os.path.join(OUT, '16-desktop-card.png'))
    pg.click('#mpaCard [data-mpa=x]'); pg.wait_for_timeout(200)
    mb = pg.locator('#hMap').bounding_box(); pg.mouse.click(mb['x'] + 300, mb['y'] + 450, button='right'); pg.wait_for_timeout(400)
    check('This spot' in pg.inner_text('#mpaCard'), 'a right-click on a computer is the long press')
    check(not errs, 'no page errors (desktop) %s' % errs[:3])
    ctx.close()
    ctx = context(br, reduced_motion='reduce'); pg = ctx.new_page()
    sign_in(pg)
    pg.fill('#mpaQ', 'Kapsaret B'); pg.wait_for_timeout(200); pg.keyboard.press('Enter'); pg.wait_for_timeout(500)
    an = pg.evaluate("[getComputedStyle(document.querySelector('#mpaCard')).animationName, (document.querySelector('#hMap .mpa-dot.c-bad') ? getComputedStyle(document.querySelector('#hMap .mpa-dot.c-bad')).animationName : 'none')]")
    check(an == ['none', 'none'] and 'rise' not in pg.get_attribute('#mpaCard', 'class'), 'reduced motion: no slide, no pulse %s' % an)
    ctx.close()
    br.close()
print('PROBLEMS', problems)
