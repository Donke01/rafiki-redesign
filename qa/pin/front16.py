# Location that fails (computer without a position, or blocked) says why and offers Try again.
import re, sys
from playwright.sync_api import sync_playwright
BASE = 'http://localhost:8797'
OUT = '/tmp/claude-0/-home-claude/a284d8b3-3cbb-5c42-a9ef-c5363bc71e30/scratchpad/web16'
import os; os.makedirs(OUT, exist_ok=True)
P = [{'id': '101', 'title': 'Chepkongi Dispensary', 'country': 'Kenya', 'ck': 'ke', 'area': 'Eldoret', 'type': 'health', 'status': 'Completed', 'gps': [0.5143, 35.2698], 'photo': None}]
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
    for mode in ['timeout', 'blocked']:
        ctx = br.new_context(viewport={'width': 1280, 'height': 860}, service_workers='block')
        if mode == 'timeout': ctx.add_init_script(FAKE)
        pg = ctx.new_page(); errs = []; pg.on('pageerror', lambda e: errs.append(str(e)))
        pg.route('**/api/pin-auth/me', lambda r, q: r.fulfill(json={'available': False})); pg.route(re.compile(r'/api/pin(\?.*)?$'), api)
        pg.route('**/tile.openstreetmap.org/**', lambda r, q: r.abort()); pg.route('**/arcgisonline.com/**', lambda r, q: r.abort())
        pg.goto(BASE + '/pin'); pg.wait_for_timeout(800)
        for d in '9999': pg.click('.pk[data-k="%s"]' % d)
        pg.wait_for_timeout(1200); pg.evaluate("localStorage.setItem('rfPinOnb','1')"); pg.goto(BASE + '/pin#map'); pg.wait_for_timeout(2000)
        t = pg.inner_text('#nearList')
        if mode == 'timeout':
            check('couldn’t work out exactly where it is' in t and pg.locator('#spotSet').count() == 1, 'computer: says so and offers Set where I am: ' + t[:80])
            check(pg.locator('#geoRetry').count() == 1, 'Try again button')
            check('Roughly' in t and 'Eldoret' in t and pg.locator('#nearList .row').count() == 1 and '≈' in pg.inner_text('#nearList .row'), 'rough nearest list from the internet connection')
            check(pg.locator('#mHere .here').count() == 0 if pg.locator('#mHere').count() else True, 'no "I am here" from a rough position'); pg.screenshot(path=OUT + '/1-mac.png')
            pg.evaluate('window.__geoFixed = true'); pg.click('#geoRetry'); pg.wait_for_timeout(800)
            check(pg.locator('#nearList .row').count() == 1 and 'Roughly' not in pg.inner_text('#nearList'), 'Try again finds the nearest project exactly')
        else:
            check('blocked' in t, 'blocked: says so: ' + t[:80]); pg.screenshot(path=OUT + '/2-blocked.png')
        check(not errs, 'no errors ' + str(errs[:1])); ctx.close()
    br.close()
print('PROBLEMS', problems)
