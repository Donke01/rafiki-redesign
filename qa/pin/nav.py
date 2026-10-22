# Back, refresh and "start over" (navw): walks the owner's sequences against the app with mocked APIs and prints
# PASS / FAIL for each one. Back here is the browser's Back (history.back), the same stack the app's ← and Android use.
#   BASE=http://localhost:8876 python3 qa/pin/nav.py [en|sw|both]      screenshots go to $OUT (default qa/pin/nav)
import os, re, sys, datetime
from playwright.sync_api import sync_playwright
HERE = os.path.dirname(os.path.abspath(__file__)); BASE = os.environ.get('BASE', 'http://localhost:8876')
OUT = os.environ.get('OUT', os.path.join(HERE, 'nav')); os.makedirs(OUT, exist_ok=True)
TILE = open(os.path.join(HERE, 'tile.png'), 'rb').read()
LANGS = {'en': ['en'], 'sw': ['sw'], 'both': ['en', 'sw']}[sys.argv[1] if len(sys.argv) > 1 else 'both']

now = datetime.datetime.now(datetime.timezone.utc)
iso = lambda h: (now - datetime.timedelta(hours=h)).strftime('%Y-%m-%dT%H:%M:%SZ')
day = lambda d: (now + datetime.timedelta(days=d)).strftime('%Y-%m-%d')
def pj(i, t, area, ll, ty='Borehole well', ck='ke', c='Kenya'):
    d = {'id': i, 'title': t, 'country': c, 'ck': ck, 'area': area, 'type': ty, 'status': 'Completed'}
    if ll: d['gps'] = ll
    return d
P = [pj('101', 'Kapsaret Borehole', 'Kapsaret, Eldoret', [0.47, 35.25]), pj('102', 'Chepkongi Dispensary', 'Eldoret', [0.5143, 35.2698], 'Health'),
     pj('103', 'Turbo Spring', 'Turbo', [0.63, 35.05], 'Water collection'), pj('104', 'Nzoia Bridge Primary School', 'Bungoma', [0.58, 34.62], 'School'),
     pj('105', 'Tamiha Children’s Home', 'Kitale', [1.0, 35.0], 'Orphanage'), pj('106', 'Kaptebee Pigsty', 'Nandi', [0.2, 35.1], 'Farming'),
     pj('107', 'Moiben Health Centre', 'Moiben', [0.8, 35.4], 'Health'), pj('108', 'Ziwa Borehole', 'Ziwa', [0.75, 35.3]),
     pj('109', 'Kapsabaot School Tank', 'Kapsabaot', [0.95, 35.08], 'School'), pj('110', 'Sosiot Well', 'Kericho', None),
     pj('111', 'Kipkaren Well', 'Kipkarren', [0.52, 35.05]), pj('112', 'Mengech Primary Borehole', 'Ziwa', [0.71, 35.22]),
     pj('113', 'Kiptoo Primary School', 'Nandi', [0.25, 35.12], 'School'), pj('114', 'Lessos Dispensary', 'Lessos', None, 'Health'),
     pj('115', 'Moi’s Bridge Borehole', 'Eldoret', [0.87, 35.12]), pj('116', 'Kesses Spring', 'Kesses', [0.3, 35.33], 'Water collection'),
     pj('117', 'Burnt Forest Well', 'Burnt Forest', [0.21, 35.43]), pj('118', 'Mto wa Mbu Borehole', 'Arusha', [-3.37, 35.85], ck='tz', c='Tanzania')]
REP = {i: {'state': 'notworking' if n % 2 else 'repair', 'by': 'Bertin Mwangi', 'at': iso(30 + n), 't': next(p['title'] for p in P if p['id'] == i), 'urgency': 'urgent', 'step': 'reported'}
       for n, i in enumerate(['101', '102', '103', '104', '105', '106', '107', '108', '109', '111', '112', '115', '116'])}
VIS = {'103': {'at': iso(24 * 20), 'by': 'Meril Kiprop', 'state': 'working'}, '113': {'at': iso(24 * 50), 'by': 'Reid Anderson', 'state': 'working'}}
TEAM = [{'name': 'Reid Anderson', 'admin': True, 'source': 'cloudflare'}, {'name': 'Meril Kiprop', 'source': 'app', 'home': 'ke'}]
ACT = [{'by': 'Meril Kiprop', 'a': 'visit', 's': 'notworking', 'id': '101', 't': 'Kapsaret Borehole', 'at': iso(3)},
       {'by': 'Grace Achieng', 'a': 'pin', 'id': '103', 't': 'Turbo Spring', 'at': iso(26)}]
def snap():
    return {'ok': True, 'who': 'Reid Anderson', 'admin': True, 'reviewOpen': 0, 'projects': P, 'repairs': REP, 'visits': VIS, 'tests': {}, 'jobs': [], 'assigned': [],
            'confirmed': {}, 'me': {'kind': 'app', 'nick': '', 'phone': '', 'home': 'ke'}, 'approveAbove': 50000}
def install(pg):
    def pin_api(route, req):
        u = req.url
        if req.method != 'GET': return route.fulfill(json={'ok': True, 'updateId': '9001'})
        if 'activity=' in u: return route.fulfill(json={'ok': True, 'activity': ACT})
        if 'team=' in u: return route.fulfill(json={'ok': True, 'team': [{'name': t['name'], 'home': 'ke'} for t in TEAM]})
        if 'history=' in u: return route.fulfill(json={'ok': True, 'notes': []})
        if re.search(r'[?&]id=\d+', u): return route.fulfill(json={'ok': True, 'last': None, 'canUndo': False, 'visits': [], 'costs': {'sum': 0, 'n': 0, 'last': []}})
        return route.fulfill(json=snap())
    def admin_api(route, req):
        u = req.url
        if req.method != 'GET': return route.fulfill(json={'ok': True})
        if 'view=team' in u: return route.fulfill(json={'ok': True, 'team': TEAM, 'mail': {'ready': True, 'to': []}, 'cron': True})
        if 'view=costs' in u: return route.fulfill(json={'ok': True, 'rows': [], 'stats': {'month': 0, 'year': 0, 'sum12': 0, 'repairs': 0, 'avg': 0, 'byCountry': {}, 'top': []}, 'approveAbove': 50000})
        return route.fulfill(json={'ok': True, 'items': [], 'rows': [], 'people': [], 'visits': [], 'donors': []})
    pg.route('**/api/pin-auth/me', lambda r, q: r.fulfill(json={'available': False}))
    pg.route(re.compile(r'/api/pin(\?.*)?$'), pin_api); pg.route(re.compile(r'/api/admin(\?.*)?$'), admin_api)
    pg.route(re.compile(r'/api/(place|sms-admin)(\?.*)?$'), lambda r, q: r.fulfill(json={'ok': True, 'places': []}))
    pg.route('**/api/photo/**', lambda r, q: r.fulfill(body=TILE, content_type='image/png'))
    pg.route(re.compile(r'https://(www\.google\.com/s2|fonts\.googleapis\.com)/.*'), lambda r, q: r.abort())

ERRS = []
def context(br, lang):
    ctx = br.new_context(viewport={'width': 390, 'height': 844}, is_mobile=True, has_touch=True, service_workers='block', locale='en-GB', timezone_id='Africa/Nairobi',
                         geolocation={'latitude': 0.515, 'longitude': 35.27, 'accuracy': 8}, permissions=['geolocation'])
    ctx.route(re.compile(r'https://(tile\.openstreetmap\.org|server\.arcgisonline\.com)/.*'), lambda r, q: r.fulfill(status=200, body=TILE, headers={'Content-Type': 'image/png', 'Access-Control-Allow-Origin': '*'}))
    # every Leaflet map on the page, so a test can read and move the home map
    ctx.add_init_script("(() => { let _L; Object.defineProperty(window, 'L', { configurable: true, get() { return _L; }, set(v) { _L = v; try { v.Map.addInitHook(function () { (window.__maps = window.__maps || []).push(this); }); } catch (e) {} } }); })()")
    pg = ctx.new_page(); install(pg)
    pg.on('pageerror', lambda e: ERRS.append('pageerror: ' + str(e)[:200]))
    pg.on('console', lambda m: ERRS.append('console: ' + m.text[:200]) if m.type == 'error' and 'Failed to load resource' not in m.text else None)
    pg.goto(BASE + '/pin'); pg.wait_for_timeout(700)
    pg.evaluate("l => { localStorage.setItem('rfPinOnb', '1'); if (l === 'sw') localStorage.setItem('rfPinLang', 'sw'); }", lang)
    pg.reload(); pg.wait_for_timeout(700)
    for d in '9999': pg.click('.pk[data-k="%s"]' % d)
    pg.wait_for_timeout(1500)
    return ctx, pg

def h(pg): return pg.evaluate('location.hash')
def view(pg): return pg.evaluate("['vMap','vList','vSearch','vRoute','vProj','vNew','vMe','vReport','vOffice'].filter(v => !document.getElementById(v).classList.contains('hidden'))[0]")
def back(pg, t=800): pg.evaluate('history.back()'); pg.wait_for_timeout(t)
def tabbar(pg, v, t=1300): pg.click('#tabs button[data-v=%s]' % v); pg.wait_for_timeout(t)
def y(pg): return pg.evaluate('Math.round(scrollY)')
def vis(pg, sel): loc = pg.locator(sel); return loc.count() > 0 and loc.first.is_visible()
def shot(pg, name, lang): pg.wait_for_timeout(300); fn = os.path.join(OUT, '%s-%s.png' % (lang, name)); pg.screenshot(path=fn); return fn
# a point on the home map with nothing on it (no pin, bubble or control), for a tap on empty map
def empty_spot(pg):
    return pg.evaluate("""(() => { const r = document.getElementById('hMap').getBoundingClientRect();
      for (let fy = .45; fy < .8; fy += .05) for (let fx = .1; fx < .7; fx += .06) { const x = r.left + r.width * fx, y = r.top + r.height * fy, e = document.elementFromPoint(x, y);
        if (e && (e.id === 'hMap' || e.classList.contains('leaflet-tile') || e.classList.contains('leaflet-container')) && ![...document.querySelectorAll('#hMap .leaflet-marker-icon')].some(m => { const b = m.getBoundingClientRect(); return x > b.left - 24 && x < b.right + 24 && y > b.top - 24 && y < b.bottom + 24; })) return [x, y]; }
      return [r.left + 30, r.top + r.height * .6]; })()""")
HMAP = "(() => { const m = (window.__maps || []).filter(x => x.getContainer().id === 'hMap')[0]; const c = m.getCenter(); return [+c.lat.toFixed(4), +c.lng.toFixed(4), m.getZoom()]; })()"

RESULTS = []
def seq(name, lang, fn, pg):
    notes = []
    def check(c, what): (None if c else notes.append(what))
    try: fn(pg, check, lang)
    except Exception as e: notes.append('error: ' + str(e).split('\n')[0][:200])
    RESULTS.append((lang, name, not notes, notes))
    print(('PASS ' if not notes else 'FAIL ') + '[%s] %s' % (lang, name) + ('' if not notes else ' :: ' + ' | '.join(notes)), flush=True)

def s_office(pg, check, lang):
    tabbar(pg, 'vOffice', 1800)
    check(h(pg) == '#office', 'Office opens on Overview (%s)' % h(pg))
    pg.click('#oTabs [data-o=repairs]'); pg.wait_for_timeout(1200)
    check(h(pg) == '#office/repairs', 'a tile opens its tab (%s)' % h(pg)); shot(pg, 'office-repairs', lang)
    pg.click('#oTabs [data-o=team]'); pg.wait_for_timeout(1000)
    back(pg, 1200)
    check(view(pg) == 'vOffice' and h(pg) == '#office', 'Back lands on the Office grid, not Map home (%s, %s)' % (view(pg), h(pg)))
    check(pg.locator('#oTabs [data-o=overview][aria-pressed=true]').count() == 1, 'Overview is the chosen tile again')
    shot(pg, 'office-back-grid', lang)
    back(pg, 1200); check(view(pg) == 'vMap', 'Back from the Office grid goes to Map home (%s)' % view(pg))

def s_project(pg, check, lang):
    tabbar(pg, 'vList', 1500)
    pg.evaluate('scrollTo(0, 520)'); pg.wait_for_timeout(400); y0 = y(pg)
    rid = pg.evaluate("(() => { const r = [...document.querySelectorAll('#list .row[data-id]')].find(e => { const b = e.getBoundingClientRect(); return b.top > 160 && b.bottom < innerHeight - 120; }); return r && r.dataset.id; })()")
    pg.click('#list .row[data-id="%s"]' % rid); pg.wait_for_timeout(1500)
    check(h(pg) == '#p/%s' % rid, 'the project opens on Pin location (%s)' % h(pg))
    pg.click('#tabHist'); pg.wait_for_timeout(1000); check(h(pg) == '#p/%s/hist' % rid, 'History has its own address (%s)' % h(pg))
    pg.click('#tabVisit'); pg.wait_for_timeout(800); pg.click('#tabHist'); pg.wait_for_timeout(800)
    back(pg); check(h(pg) == '#p/%s' % rid and pg.get_attribute('#tabPin', 'aria-selected') == 'true', 'Back from History lands on Pin location (%s)' % h(pg))
    shot(pg, 'project-back-pin', lang)
    back(pg, 1200); check(view(pg) == 'vList' and h(pg) == '#list', 'Back again goes to Projects (%s)' % h(pg))
    check(abs(y(pg) - y0) <= 4, 'Projects is scrolled where it was (%d, was %d)' % (y(pg), y0))
    # the app's own ← uses the same steps
    pg.click('#list .row[data-id="%s"]' % rid); pg.wait_for_timeout(1300); pg.click('#tabHist'); pg.wait_for_timeout(800)
    pg.click('#hBack'); pg.wait_for_timeout(800); check(h(pg) == '#p/%s' % rid, 'the app’s ← from History lands on Pin location (%s)' % h(pg))
    pg.click('#hBack'); pg.wait_for_timeout(1200); check(h(pg) == '#list', 'the app’s ← again goes to Projects (%s)' % h(pg))

def s_map(pg, check, lang):
    tabbar(pg, 'vMap', 2200); first = pg.evaluate(HMAP)
    box = pg.locator('#hMap').bounding_box(); cx, cy = box['x'] + box['width'] / 2, box['y'] + box['height'] * 0.55
    pg.mouse.move(cx, cy); pg.mouse.down(); pg.mouse.move(cx - 120, cy + 90, steps=8); pg.mouse.up(); pg.wait_for_timeout(300)
    pg.evaluate("(() => { const m = window.__maps.filter(x => x.getContainer().id === 'hMap')[0]; m.setZoom(m.getZoom() + 2); })()"); pg.wait_for_timeout(800)
    moved = pg.evaluate(HMAP); check(moved != first, 'the map moved (%s → %s)' % (first, moved))
    tabbar(pg, 'vList', 1200); tabbar(pg, 'vMap', 2200)
    back_to = pg.evaluate(HMAP)
    check(abs(back_to[0] - first[0]) < 0.02 and abs(back_to[1] - first[1]) < 0.02 and back_to[2] == first[2], 'the bottom bar brings Map back to its first view (%s, first %s)' % (back_to, first))
    shot(pg, 'map-default', lang)
    # calm by default (map3w): a tap on empty map brings the filters in, Back puts them away (one step), still on Map
    check(vis(pg, '#mclSb') and not vis(pg, '#mpaChips'), 'the map starts calm: a round search button, no chips')
    ex, ey = empty_spot(pg); pg.mouse.click(ex, ey); pg.wait_for_timeout(900)
    check(vis(pg, '#mpaChips') and vis(pg, '#mpaS'), 'a tap on empty map brings the filters in')
    back(pg); check(vis(pg, '#mclSb') and not vis(pg, '#mpaChips') and view(pg) == 'vMap' and h(pg) == '#map', 'Back puts the filters away, still on Map (%s, %s)' % (view(pg), h(pg)))
    if vis(pg, '#mclSb'): pg.click('#mclSb'); pg.wait_for_timeout(300)
    pg.fill('#mpaQ', 'Kaps'); pg.wait_for_timeout(600); pg.click('#mpaSug [data-k=p]'); pg.wait_for_timeout(1300)
    check(vis(pg, '#mpaCard'), 'a map card opens'); shot(pg, 'map-card', lang)
    back(pg); check(not vis(pg, '#mpaCard') and view(pg) == 'vMap' and h(pg) == '#map', 'Back closes the card only (%s, %s)' % (view(pg), h(pg)))
    if vis(pg, '#mclPeek'):
        pg.click('#mclPeek'); pg.wait_for_timeout(600); check(pg.get_attribute('#nearHd', 'aria-expanded') == 'true', 'the Nearest list opens')
        back(pg); check(pg.get_attribute('#nearHd', 'aria-expanded') == 'false' and view(pg) == 'vMap', 'Back puts the Nearest list away, still on Map')

def s_me(pg, check, lang):
    tabbar(pg, 'vMe', 1800)
    pg.locator('#meStampsMore').scroll_into_view_if_needed(); pg.click('#meStampsMore'); pg.wait_for_timeout(500)
    check(pg.get_attribute('#meStampsMore', 'aria-expanded') == 'true', 'the stamps expand'); shot(pg, 'me-stamps-open', lang)
    back(pg); check(pg.get_attribute('#meStampsMore', 'aria-expanded') == 'false' and h(pg) == '#me', 'Back collapses the stamps, still on Me (%s)' % h(pg))
    shot(pg, 'me-stamps-closed', lang)
    pg.locator('#meRep').scroll_into_view_if_needed(); pg.click('#meRep'); pg.wait_for_timeout(1200); check(h(pg) == '#report', 'Monthly report opens (%s)' % h(pg))
    back(pg, 1200); check(h(pg) == '#me' and view(pg) == 'vMe', 'Back from Monthly report comes to Me (%s)' % h(pg))
    pg.evaluate('scrollTo(0,0)'); pg.locator('#meSw button[data-p=team]').click(); pg.wait_for_timeout(900); check(h(pg) == '#me/team', 'Team has its own address (%s)' % h(pg))
    back(pg); check(h(pg) == '#me' and pg.get_attribute('#meSw button[data-p=me]', 'aria-selected') == 'true', 'Back from Team comes to Me (%s)' % h(pg))

def s_visit(pg, check, lang):
    pg.evaluate("localStorage.removeItem('rfPinDraft:113')")
    tabbar(pg, 'vList', 1300); pg.evaluate("location.hash = '#p/113'"); pg.wait_for_timeout(1500)
    pg.click('#tabVisit'); pg.wait_for_timeout(1200)
    step = lambda: pg.evaluate("(() => { const m = /(\\d+)/.exec((document.querySelector('#vw .wiz-now') || {}).textContent || ''); return m ? +m[1] : 0; })()")
    for _ in range(3):
        if pg.locator('#vw [data-state]').count(): pg.click('#vw [data-state=working]'); pg.wait_for_timeout(700); continue
        pg.click('#vw [data-act=next]'); pg.wait_for_timeout(700)
    check(step() == 4, 'the visit is on step 4 (%d)' % step()); shot(pg, 'visit-step4', lang)
    back(pg); check(step() == 3 and h(pg) == '#p/113/visit', 'Back goes to step 3 (%d, %s)' % (step(), h(pg)))
    back(pg); back(pg); check(step() == 1, 'Back again reaches step 1 (%d)' % step())
    back(pg); check(vis(pg, '.bsh.po-ask'), 'Back on step 1 asks before leaving'); shot(pg, 'visit-leave-ask', lang)
    pg.click('.bsh.po-ask [data-k=no]'); pg.wait_for_timeout(700)
    check(h(pg) == '#p/113/visit' and step() == 1 and not vis(pg, '.bsh.po-ask'), 'Cancel stays on the visit (%s)' % h(pg))
    back(pg); pg.click('.bsh.po-ask [data-k=yes]'); pg.wait_for_timeout(1300)
    check(h(pg) == '#p/113' and view(pg) == 'vProj', 'Leave goes to the project (%s)' % h(pg))
    check(pg.evaluate("!!localStorage.getItem('rfPinDraft:113')"), 'the visit draft is kept')
    pg.reload(); pg.wait_for_timeout(2000); pg.click('#tabVisit'); pg.wait_for_timeout(1200)
    check(step() == 1 and pg.locator('#vw [data-act=restart]').count() > 0, 'after a reload the visit opens again with its draft')
    pg.evaluate("localStorage.removeItem('rfPinDraft:113')")

def s_sheet(pg, check, lang):
    tabbar(pg, 'vList', 1300); pg.click('#bCountry'); pg.wait_for_timeout(700); check(vis(pg, '.bsh'), 'the Filter sheet opens')
    back(pg); check(not vis(pg, '.bsh') and view(pg) == 'vList' and h(pg) == '#list', 'Back closes only the sheet (%s)' % h(pg))
    tabbar(pg, 'vMe', 1500); pg.click('#meBell'); pg.wait_for_timeout(800); check(vis(pg, '.npg'), 'Notifications open')
    back(pg); check(not vis(pg, '.npg') and h(pg) == '#me', 'Back closes only Notifications (%s)' % h(pg))
    pg.locator('#meSet [data-set=help]').scroll_into_view_if_needed(); pg.click('#meSet [data-set=help]'); pg.wait_for_timeout(800)
    pg.locator('.bsh .faq summary').first.click(); pg.wait_for_timeout(400); check(pg.evaluate("document.querySelector('.bsh .faq details').open"), 'a fold in the sheet opens')
    shot(pg, 'sheet-fold-open', lang)
    back(pg); check(vis(pg, '.bsh') and not pg.evaluate("document.querySelector('.bsh .faq details').open"), 'Back closes the fold first, the sheet stays')
    back(pg); check(not vis(pg, '.bsh') and h(pg) == '#me', 'Back then closes the sheet, still on Me (%s)' % h(pg))
    pg.click('#meSet [data-set=help]'); pg.wait_for_timeout(800); pg.locator('.bsh .bsh-x').first.click(); pg.wait_for_timeout(700)
    back(pg, 1200); check(view(pg) == 'vMap', 'a sheet closed with × leaves no extra step: Back goes on to Map home (%s)' % view(pg))

def s_reload(pg, check, lang):
    for hh, v, sel in [('#p/101/hist', 'vProj', '#tabHist[aria-selected=true]'), ('#office/repairs', 'vOffice', '#oTabs [data-o=repairs][aria-pressed=true]'), ('#me', 'vMe', '#meSw button[data-p=me][aria-selected=true]'), ('#me/team', 'vMe', '#meSw button[data-p=team][aria-selected=true]')]:
        n = len(ERRS); pg.evaluate("h => { location.hash = h; }", hh); pg.wait_for_timeout(900); pg.evaluate('scrollTo(0, 300)'); pg.reload(); pg.wait_for_timeout(2200)
        check(h(pg) == hh and view(pg) == v and pg.locator(sel).count() == 1, 'reload on %s comes back there (%s, %s)' % (hh, h(pg), view(pg)))
        check(y(pg) == 0 and not vis(pg, '.bsh, .tsh, .npg'), 'reload on %s: at the top, nothing open' % hh)
        check(len(ERRS) == n, 'reload on %s: no console errors (%s)' % (hh, ERRS[n:]))
        if hh == '#office/repairs': shot(pg, 'reload-office-repairs', lang)
    back(pg, 1200); check(h(pg) == '#me' and view(pg) == 'vMe', 'Back after a reload still walks the earlier steps (%s)' % h(pg))

def s_refresh(pg, check, lang):
    for v in ['vMap', 'vList', 'vNew', 'vMe', 'vOffice']:
        tabbar(pg, v, 1600); h0 = h(pg)
        if v != 'vMap': pg.evaluate('scrollTo(0, 260)'); pg.wait_for_timeout(300)
        y0 = y(pg); pg.click('#bRefresh'); pg.wait_for_timeout(1600)
        check(h(pg) == h0 and view(pg) == v, '%s: refresh stays on the screen (%s)' % (v, h(pg)))
        check(abs(y(pg) - y0) <= 4, '%s: refresh keeps the scroll (%d, was %d)' % (v, y(pg), y0))
        check(pg.evaluate("[...document.querySelectorAll('.toast')].some(t => /Updated|Imesasishwa/.test(t.textContent))"), '%s: an “Updated” toast' % v)
        if v == 'vMe': shot(pg, 'refresh-me', lang)

def s_filter(pg, check, lang):
    tabbar(pg, 'vList', 1400); pg.click('#lChips [data-f=repairs]'); pg.wait_for_timeout(800); pg.evaluate('scrollTo(0, 360)'); pg.wait_for_timeout(300)
    check(pg.locator('#lChips [data-f=repairs][aria-pressed=true]').count() == 1 and y(pg) > 100, 'Projects on Repairs, scrolled down (%d)' % y(pg))
    tabbar(pg, 'vMe', 1200); tabbar(pg, 'vList', 1300)
    check(pg.locator('#lChips [data-f=all][aria-pressed=true]').count() == 1 and y(pg) == 0, 'the bottom bar brings Projects back on All, at the top (%d)' % y(pg))
    shot(pg, 'projects-start-over', lang)
    pg.click('#lChips [data-f=repairs]'); pg.wait_for_timeout(800); pg.evaluate('scrollTo(0, 360)'); pg.wait_for_timeout(300); y0 = y(pg)
    rid = pg.evaluate("(() => { const r = [...document.querySelectorAll('#list .row[data-id]')].find(e => { const b = e.getBoundingClientRect(); return b.top > 160 && b.bottom < innerHeight - 120; }); return r && r.dataset.id; })()")
    pg.click('#list .row[data-id="%s"]' % rid); pg.wait_for_timeout(1300); back(pg, 1200)
    check(pg.locator('#lChips [data-f=repairs][aria-pressed=true]').count() == 1 and abs(y(pg) - y0) <= 4, 'Back keeps Repairs and the scroll (%d, was %d)' % (y(pg), y0))
    shot(pg, 'projects-back-kept', lang)

def s_home(pg, check, lang):
    # a link straight to a project: Back comes to Map home, not out of the app
    pg.goto(BASE + '/pin#p/101'); pg.wait_for_timeout(2000); check(h(pg) == '#p/101' and view(pg) == 'vProj', 'a link to a project opens it (%s)' % h(pg))
    back(pg, 1500); check(h(pg) == '#map' and view(pg) == 'vMap', 'Back from it comes to Map home (%s)' % h(pg))
    # the bottom bar from a deeper screen: Back from there comes to Map home
    tabbar(pg, 'vList', 1300); pg.click('#list .row[data-id="102"]'); pg.wait_for_timeout(1300); pg.click('#tabHist'); pg.wait_for_timeout(800)
    tabbar(pg, 'vMe', 1500); check(h(pg) == '#me', 'the bottom bar opens Me (%s)' % h(pg))
    back(pg, 1500); check(h(pg) == '#map' and view(pg) == 'vMap', 'Back from a bottom-bar screen comes to Map home (%s)' % h(pg))
    # a notification opens its project; Back comes to Me
    tabbar(pg, 'vMe', 1500); pg.click('#meBell'); pg.wait_for_timeout(800); pg.locator('.npg .np-r[data-id]').first.click(); pg.wait_for_timeout(1300)
    check(view(pg) == 'vProj' and not vis(pg, '.npg'), 'a notification opens its project (%s)' % h(pg))
    back(pg, 1300); check(h(pg) == '#me' and not vis(pg, '.npg'), 'Back from it comes to Me (%s)' % h(pg))
    # Office KPI → Projects → Back → Office
    tabbar(pg, 'vOffice', 1800); k = pg.locator('#oBody [data-ocvgo^="list-"]'); check(k.count() > 0, 'the Office overview has numbers that open Projects')
    k.first.scroll_into_view_if_needed(); k.first.click(); pg.wait_for_timeout(1200); check(view(pg) == 'vList', 'an Office number opens Projects (%s)' % view(pg))
    back(pg, 1300); check(view(pg) == 'vOffice' and h(pg) == '#office', 'Back comes to the Office (%s)' % h(pg))
    k = pg.locator('#oBody [data-ocvgo^="o-"]'); check(k.count() > 0, 'the Office overview has cards that open a section')
    k.first.scroll_into_view_if_needed(); k.first.click(); pg.wait_for_timeout(1200); check(h(pg).startswith('#office/'), 'a card opens its section (%s)' % h(pg))
    back(pg, 1300); check(h(pg) == '#office', 'Back comes to Overview (%s)' % h(pg))

def s_new(pg, check, lang):
    tabbar(pg, 'vNew', 1800); pg.fill('#nName', 'Kipkaren Spring'); pg.fill('#nVillage', 'Kipkaren'); pg.click('#nGps'); pg.wait_for_timeout(1800)
    pg.evaluate('scrollTo(0, 200)'); pg.reload(); pg.wait_for_timeout(2200)
    check(h(pg) == '#new' and y(pg) == 0, 'reload on New site comes back there, at the top (%s, %d)' % (h(pg), y(pg)))
    check(pg.input_value('#nName') == 'Kipkaren Spring' and pg.input_value('#nVillage') == 'Kipkaren', 'the half-done form is still there')
    shot(pg, 'new-site-after-reload', lang)
    tabbar(pg, 'vMap', 1500); tabbar(pg, 'vNew', 1500); check(pg.input_value('#nName') == 'Kipkaren Spring', 'the bottom bar starts New site over but keeps the draft')

SEQ = [('Office: a tile, then Back → Office grid', s_office), ('Project: History, Back → Pin location, Back → Projects where you were', s_project),
       ('Map: moved, away and back by the bottom bar → first view; card, Back → card only', s_map), ('Me: stamps, Back → collapsed; Monthly report, Back; Team, Back', s_me),
       ('Visit: step 4, Back → step 3; step 1, Back asks and keeps the draft', s_visit), ('A sheet open, Back closes only the sheet', s_sheet),
       ('Reload on #p/101/hist, #office/repairs, #me', s_reload), ('Refresh button on each top-level screen stays put', s_refresh),
       ('Projects: Repairs + scrolled; bottom bar → All, top; Back → kept', s_filter),
       ('Links, the bottom bar and notifications: Back comes home or to where you were', s_home), ('New site: the form survives a reload; the bottom bar keeps it', s_new)]
with sync_playwright() as p:
    br = p.chromium.launch()
    for lang in LANGS:
        for name, fn in SEQ:
            ctx, pg = context(br, lang); del ERRS[:]
            seq(name, lang, fn, pg)
            ctx.close()
    br.close()
bad = [r for r in RESULTS if not r[2]]
print('\n%d of %d sequences pass' % (len(RESULTS) - len(bad), len(RESULTS)))
sys.exit(1 if bad else 0)
