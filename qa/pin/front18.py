# Google maps: Map Tiles API session, Google tiles, logo + copyright, satellite, Google place results with attribution.
import re, os, base64
from playwright.sync_api import sync_playwright
BASE = 'http://localhost:8797'; OUT = '/tmp/claude-0/-home-claude/a284d8b3-3cbb-5c42-a9ef-c5363bc71e30/scratchpad/web18'; os.makedirs(OUT, exist_ok=True)
P = [{'id': '101', 'title': 'Chepkongi Dispensary', 'country': 'Kenya', 'ck': 'ke', 'area': 'Eldoret', 'type': 'health', 'status': 'Completed', 'gps': [0.5143, 35.2698], 'photo': None},
     {'id': '102', 'title': 'Kiptoo Well', 'country': 'Kenya', 'ck': 'ke', 'area': 'Nandi', 'type': 'borehole', 'status': 'Completed', 'gps': None, 'photo': None}]
PNG = base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFBQIAX8jx0gAAAABJRU5ErkJggg==')
problems = []; seen = {'sess': [], 'tiles': set(), 'vp': 0}
def check(c, w):
    print(('ok   ' if c else 'FAIL ') + w)
    if not c: problems.append(w)
def api(route, req):
    if 'activity=' in req.url: return route.fulfill(json={'ok': True, 'activity': []})
    if 'team=' in req.url: return route.fulfill(json={'ok': True, 'team': []})
    if 'history=' in req.url: return route.fulfill(json={'ok': True, 'notes': []})
    if 'id=' in req.url: return route.fulfill(json={'ok': True, 'last': None, 'visits': []})
    return route.fulfill(json={'ok': True, 'who': 'Meril Kiprop', 'gmaps': {'web': 'WEBKEY', 'android': 'AKEY'}, 'admin': False, 'reviewOpen': 0, 'projects': P, 'repairs': {}, 'visits': {}, 'jobs': [], 'assigned': [], 'confirmed': {}})
def gt(route, req):
    u = req.url
    if 'createSession' in u:
        b = req.post_data_json; seen['sess'].append(b['mapType'])
        return route.fulfill(json={'session': 'S-' + b['mapType'], 'expiry': '9999999999', 'tileWidth': 256, 'tileHeight': 256, 'imageFormat': 'png'})
    if '/2dtiles/' in u: seen['tiles'].add(re.search(r'session=([^&]+)', u).group(1)); return route.fulfill(body=PNG, content_type='image/png')
    if '/viewport' in u: seen['vp'] += 1; return route.fulfill(json={'copyright': 'Map data ©2026 Google', 'maxZoomRects': []})
    return route.fulfill(status=404)
with sync_playwright() as p:
    br = p.chromium.launch(); ctx = br.new_context(viewport={'width': 390, 'height': 844}, device_scale_factor=2, service_workers='block')
    pg = ctx.new_page(); errs = []; pg.on('pageerror', lambda e: errs.append(str(e)))
    osm = []; pg.route('**/tile.openstreetmap.org/**', lambda r, q: (osm.append(1), r.fulfill(body=PNG, content_type='image/png')))
    pg.route('**/arcgisonline.com/**', lambda r, q: r.fulfill(body=PNG, content_type='image/png'))
    pg.route('**/api/pin-auth/me', lambda r, q: r.fulfill(json={'available': False})); pg.route(re.compile(r'/api/pin(\?.*)?$'), api)
    pg.route('**/api/place**', lambda r, q: r.fulfill(json={'ok': True, 'places': [{'name': 'Kiptoo Primary School', 'where': 'Nandi, Kenya', 'type': 'School', 'lat': 0.2, 'lng': 35.1, 'src': 'google', 'similar': False}]}))
    pg.route('**/tile.googleapis.com/**', gt)
    pg.goto(BASE + '/pin'); pg.wait_for_timeout(800)
    for d in '9999': pg.click('.pk[data-k="%s"]' % d)
    pg.wait_for_timeout(1200); pg.evaluate("localStorage.setItem('rfPinOnb','1')"); pg.goto(BASE + '/pin#map'); pg.wait_for_timeout(3000)
    check(sorted(set(seen['sess'])) == ['roadmap', 'satellite'], 'creates roadmap and satellite sessions: ' + str(seen['sess']))
    check('S-roadmap' in seen['tiles'], 'map draws Google street tiles')
    check(pg.locator('#vMap .glogo img').count() == 1 and pg.get_attribute('#vMap .glogo img', 'src').endswith('google-logo.png'), 'Google logo on the map')
    check('Google' in pg.inner_text('#vMap .leaflet-control-attribution'), 'Google copyright shown: ' + pg.inner_text('#vMap .leaflet-control-attribution'))
    check(seen['vp'] >= 1, 'asks Google which copyright to show'); pg.screenshot(path=OUT + '/1-map.png')
    pg.click('#vMap .mstyle'); pg.wait_for_timeout(400); pg.click('.bsh-o[data-k=sat]'); pg.wait_for_timeout(1200)
    check('S-satellite' in seen['tiles'] and pg.get_attribute('#vMap .glogo img', 'src').endswith('google-logo-white.png'), 'satellite uses Google imagery and the white logo'); pg.screenshot(path=OUT + '/2-sat.png')
    n = len(seen['sess']); pg.reload(); pg.wait_for_timeout(2500); check(len(seen['sess']) == n, 'sessions are reused after a reload')
    # place search on the pin screen
    pg.goto(BASE + '/pin#p/102'); pg.wait_for_timeout(2500)
    osm.clear()
    pg.locator('#vProj .findbtn').first.click(); pg.wait_for_timeout(300)
    pg.fill('#pMapQ', 'Kiptoo school'); pg.locator('#pFind [data-f=go]').click(); pg.wait_for_timeout(800)
    r = pg.inner_text('#pMapR'); check('Kiptoo Primary School' in r and pg.locator('#pMapR .pby img[alt=Google]').count() == 1, 'Google place results with Powered by Google')
    pg.locator('#pMapR .prow').first.click(); pg.wait_for_timeout(500); check('from Google' in pg.inner_text('#vProj'), 'pin note says the place came from Google')
    pg.screenshot(path=OUT + '/3-search.png')
    check(len(osm) == 0, 'no OpenStreetMap tiles loaded once Google is on: %d' % len(osm))
    check(not errs, 'no errors ' + str(errs[:1])); br.close()
print('PROBLEMS', problems)
