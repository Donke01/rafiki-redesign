# Computer that can't find itself: set where you are (front21). Location that fails (computer without a position, or blocked) says why and offers Try again.
import re, sys
from playwright.sync_api import sync_playwright
BASE = 'http://localhost:8797'
OUT = '/tmp/claude-0/-home-claude/a284d8b3-3cbb-5c42-a9ef-c5363bc71e30/scratchpad/web21'
import os; os.makedirs(OUT, exist_ok=True)
P = [{'id': '101', 'title': 'Chepkongi Dispensary', 'country': 'Kenya', 'ck': 'ke', 'area': 'Eldoret', 'type': 'health', 'status': 'Completed', 'gps': [0.5143, 35.2698], 'photo': None}]
P.append({'id': '104', 'title': 'Kapsaret Borehole', 'country': 'Kenya', 'ck': 'ke', 'area': 'Kapsaret', 'type': 'borehole', 'status': 'Completed', 'gps': [0.47, 35.25], 'photo': None})
P.append({'id': '105', 'title': 'Turbo Spring', 'country': 'Kenya', 'ck': 'ke', 'area': 'Turbo', 'type': 'borehole', 'status': 'Completed', 'gps': [0.63, 35.05], 'photo': None})
problems = []
def check(c, w):
    print(('ok   ' if c else 'FAIL ') + w)
    if not c: problems.append(w)
def api(route, req):
    if 'where=' in req.url: return route.fulfill(json={'ok': True, 'lat': 0.52, 'lon': 35.27, 'city': 'Eldoret', 'country': 'KE'})
    if 'activity=' in req.url: return route.fulfill(json={'ok': True, 'activity': []})
    return route.fulfill(json={'ok': True, 'who': 'Meril Kiprop', 'admin': False, 'reviewOpen': 0, 'projects': P, 'repairs': {}, 'visits': {}, 'jobs': [], 'assigned': [], 'confirmed': {}})
# fake geolocation: watch times out, then the easy one-shot also times out until "fixed" is set
FAKE = """(() => { let n = 0; window.__geoFixed = false; const g = {
  watchPosition(ok, no) { const id = ++n; setTimeout(() => window.__geoFixed ? ok({coords:{latitude:0.515,longitude:35.27,accuracy:40}}) : no({code:3, message:'Timeout expired'}), 200); return id; },
  clearWatch() {}, getCurrentPosition(ok, no) { setTimeout(() => window.__geoFixed ? ok({coords:{latitude:0.515,longitude:35.27,accuracy:40}}) : no({code:3, message:'Timeout expired'}), 200); } };
  Object.defineProperty(navigator, 'geolocation', { get: () => g }); })();"""
with sync_playwright() as p:
    br = p.chromium.launch()
    ctx = br.new_context(viewport={'width': 1280, 'height': 860}, service_workers='block', user_agent='Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0 Safari/537.36')
    ctx.add_init_script(FAKE)
    pg = ctx.new_page(); errs = []; pg.on('pageerror', lambda e: errs.append(str(e)))
    pg.route('**/api/pin-auth/me', lambda r, q: r.fulfill(json={'available': False})); pg.route(re.compile(r'/api/pin(\?.*)?$'), api)
    pg.route('**/api/place**', lambda r, q: r.fulfill(json={'ok': True, 'places': [{'name': 'Turbo', 'where': 'Uasin Gishu, Kenya', 'lat': 0.63, 'lng': 35.05}]}))
    pg.route('**/tile.openstreetmap.org/**', lambda r, q: r.abort()); pg.route('**/arcgisonline.com/**', lambda r, q: r.abort())
    pg.goto(BASE + '/pin'); pg.wait_for_timeout(800)
    for d in '9999': pg.click('.pk[data-k="%s"]' % d)
    pg.wait_for_timeout(1200); pg.evaluate("localStorage.setItem('rfPinOnb','1')"); pg.goto(BASE + '/pin#map'); pg.wait_for_timeout(2000)
    t = pg.inner_text('#nearList')
    check('Roughly' in t and pg.locator('#nearList .row').count() == 3, 'rough list straight away: ' + t[:90])
    pg.click('#spotSet'); pg.wait_for_timeout(500)
    check(pg.locator('.spot .prow').count() == 3, 'sheet lists projects'); pg.screenshot(path=OUT + '/1-sheet.png')
    pg.fill('#spQ', 'turbo'); pg.press('#spQ', 'Enter'); pg.wait_for_timeout(600)
    check('PLACES' in pg.inner_text('.spot').upper() and pg.locator('.spot .prow').count() == 2, 'project match + place result')
    pg.locator('.spot .prow').nth(1).click(); pg.wait_for_timeout(600)
    t = pg.inner_text('#nearList')
    check('From Turbo, the place you set' in t and pg.inner_text('#nearList .row >> nth=0').startswith('Turbo Spring'), 'nearest measured from the set place: ' + t[:90]); pg.screenshot(path=OUT + '/2-set.png')
    pg.reload(); pg.wait_for_timeout(2000)
    check('From Turbo' in pg.inner_text('#nearList'), 'remembered after reload')
    check(pg.locator('#mHere .here').count() == 0 if pg.locator('#mHere').count() else True, 'set place never gives an I-am-here')
    pg.goto(BASE + '/pin#list'); pg.wait_for_timeout(800); pg.click('[data-f=near]'); pg.wait_for_timeout(800)
    check('Closest to Turbo' in pg.inner_text('#count') and pg.locator('#list .row').count() == 3, 'Projects › Nearby uses it: ' + pg.inner_text('#count')); pg.screenshot(path=OUT + '/3-list.png')
    pg.goto(BASE + '/pin#map'); pg.wait_for_timeout(1500)
    pg.click('#spotSet'); pg.wait_for_timeout(400); pg.click('#spClear'); pg.wait_for_timeout(500)
    check('Roughly' in pg.inner_text('#nearList'), 'forget goes back to the rough location')
    pg.evaluate('window.__geoFixed = true'); pg.click('#geoRetry'); pg.wait_for_timeout(800)
    check('Roughly' not in pg.inner_text('#nearList') and '≈' not in pg.inner_text('#nearList'), 'a real position wins')
    check(not errs, 'no errors ' + str(errs[:1])); ctx.close(); br.close()
print('PROBLEMS', problems)
