import json, os, re
from playwright.sync_api import sync_playwright

HERE = os.path.dirname(os.path.abspath(__file__))
BASE = 'http://localhost:8797'
PROJECTS = [
    {'id': '101', 'title': 'Chepkongi Dispensary', 'country': 'Kenya', 'ck': 'ke', 'area': 'Eldoret', 'type': 'health', 'status': 'Completed', 'gps': [0.5143, 35.2698], 'photo': '555'},
    {'id': '102', 'title': 'Kiptoo Well', 'country': 'Kenya', 'ck': 'ke', 'area': 'Nandi', 'type': 'borehole', 'status': 'Completed', 'gps': None, 'photo': None},
    {'id': '103', 'title': 'Arusha School', 'country': 'Tanzania', 'ck': 'tz', 'area': 'Arusha', 'type': 'school', 'status': 'Completed', 'gps': [-3.3869, 36.6830], 'photo': None},
    {'id': '104', 'title': 'Kapsaret Borehole', 'country': 'Kenya', 'ck': 'ke', 'area': 'Eldoret', 'type': 'borehole', 'status': 'Completed', 'gps': [0.47, 35.25], 'photo': None},
    {'id': '105', 'title': 'Turbo Spring', 'country': 'Kenya', 'ck': 'ke', 'area': 'Turbo', 'type': 'borehole', 'status': 'Completed', 'gps': [0.63, 35.05], 'photo': None},
]
posts, files, problems = [], [], []
state = {'offline': False, 'last': None, 'repairs': {'104': {'state': 'repair', 'by': 'Bertin', 'at': '2026-10-18T08:00:00Z', 'note': 'Handle loose', 't': 'Kapsaret Borehole'}}}
ACT = [{'by': 'Meril', 'a': 'pin', 'id': '101', 't': 'Chepkongi Dispensary', 'at': '2026-10-18T08:00:00Z'},
       {'by': 'Bertin', 'a': 'visit', 's': 'repair', 'id': '104', 't': 'Kapsaret Borehole', 'at': '2026-10-18T09:00:00Z'}]
PINS = {'1111': 'Meril Kiprop', '4321': ''}

def check(cond, what):
    print(('ok   ' if cond else 'FAIL ') + what)
    if not cond: problems.append(what)

def api(route, req):
    if state['offline']: return route.abort('internetdisconnected')
    who = PINS.get(req.headers.get('x-pin'))
    if who is None: return route.fulfill(status=401, json={'ok': False, 'reason': 'pin'})
    if req.method == 'GET':
        u = req.url
        if 'history=' in u:
            return route.fulfill(json={'ok': True, 'notes': [
                {'id': '1', 'text': '🩺 Site visit by Bertin (Rafiki pin app): ⚠️ Needs repair. Notes: Handle loose.', 'at': '2026-10-18T09:00:00Z', 'by': 'Morgan', 'files': [{'name': 'p.jpg', 'ext': 'jpg', 'url': BASE + '/img/pin/icon-192.png'}, {'name': 'v.webm', 'ext': 'webm', 'url': BASE + '/x.webm'}]}]})
        if 'activity=' in u: return route.fulfill(json={'ok': True, 'activity': ACT})
        if 'id=' in u: return route.fulfill(json={'ok': True, 'last': state['last'], 'canUndo': True})
        return route.fulfill(json={'ok': True, 'who': who, 'projects': PROJECTS, 'repairs': state['repairs']})
    b = json.loads(req.post_data); posts.append(b); a = b.get('action')
    if a == 'pin':
        state['last'] = {'value': '%.6f, %.6f' % (b['lat'], b['lng']), 'prev': '', 'by': who or b['by'], 'at': '2026-10-19T09:00:00Z'}
        return route.fulfill(json={'ok': True, 'updateId': '9001', 'gps': [b['lat'], b['lng']]})
    if a == 'undo':
        state['last'] = None; return route.fulfill(json={'ok': True, 'gps': None, 'value': '', 'updateId': '9002'})
    return route.fulfill(json={'ok': True, 'updateId': '9003', 'id': '555'})

def upload(route, req):
    if state['offline']: return route.abort('internetdisconnected')
    body = req.post_data_buffer or b''
    m = re.search(rb'filename="([^"]+)"', body)
    files.append((m.group(1).decode() if m else '?', len(body)))
    route.fulfill(json={'ok': True})

TILE = open(os.path.join(HERE, 'tile.png'), 'rb').read()

with sync_playwright() as p:
    br = p.chromium.launch(args=['--use-fake-device-for-media-stream', '--use-fake-ui-for-media-stream'])
    ctx = br.new_context(viewport={'width': 390, 'height': 844}, is_mobile=True, has_touch=True, service_workers='block',
                         geolocation={'latitude': 0.5150, 'longitude': 35.2700, 'accuracy': 8}, permissions=['geolocation', 'microphone'])
    pg = ctx.new_page()
    errs = []
    pg.on('pageerror', lambda e: errs.append(str(e)))
    pg.on('console', lambda m: errs.append(m.text) if m.type == 'error' and 'Failed to load resource' not in m.text and 'Content Security' not in m.text else None)
    pg.on('dialog', lambda d: d.accept())
    pg.route('**/api/pin-photo', upload)
    pg.route(re.compile(r'/api/pin(\?.*)?$'), api)
    pg.route(re.compile(r'/api/photo/'), lambda r, q: r.fulfill(status=200, content_type='image/png', body=open(os.path.join(HERE, 'big.jpg'), 'rb').read()))
    pg.route('**/tile.openstreetmap.org/**', lambda r, q: r.fulfill(status=200, content_type='image/png', body=TILE))
    pg.route('**/server.arcgisonline.com/**', lambda r, q: r.fulfill(status=200, content_type='image/png', body=TILE))

    pg.goto(BASE + '/pin')
    pg.fill('#iPin', '1111'); pg.click('#fLock button[type=submit]')
    pg.wait_for_selector('#vMap:not(.hidden)'); pg.wait_for_timeout(800)
    check(pg.evaluate('location.hash') == '#map', 'map has its address: ' + pg.evaluate('location.hash'))
    pg.click('#tabs [data-v=vList]'); pg.click('[data-f=all]')
    pg.locator('#list .row', has_text='Chepkongi').click(); pg.wait_for_timeout(600)
    check(pg.evaluate('location.hash') == '#p/101', 'project has its address')
    # live distance as we move
    first = pg.inner_text('#pDist')
    ctx.set_geolocation({'latitude': 0.5200, 'longitude': 35.2700, 'accuracy': 6}); pg.wait_for_timeout(1500)
    second = pg.inner_text('#pDist')
    ctx.set_geolocation({'latitude': 0.5250, 'longitude': 35.2700, 'accuracy': 6}); pg.wait_for_timeout(1500)
    third = pg.inner_text('#pDist')
    print('   ', first, '|', second, '|', third, '|', pg.inner_text('#pLive'))
    check(first != second != third and 'head south' in third, 'distance updates while moving')
    check('Live' in pg.inner_text('#pLive'), 'live indicator')
    pg.screenshot(path=os.path.join(HERE, 'm1-live.png'))
    pg.click('#tabVisit'); pg.wait_for_timeout(200)
    check(pg.evaluate('location.hash') == '#p/101/visit', 'tab in address (replaced)')
    # refresh keeps the place
    pg.reload(); pg.wait_for_timeout(1500)
    check(pg.is_visible('#paneVisit') and pg.inner_text('#pTitle') == 'Chepkongi Dispensary', 'refresh keeps you on the project and tab')
    # phone back button
    pg.go_back(); pg.wait_for_timeout(600)
    check(pg.is_visible('#vList'), 'phone Back returns to the list: ' + pg.evaluate('location.hash'))
    pg.go_back(); pg.wait_for_timeout(600)
    check(pg.is_visible('#vMap'), 'Back again returns to the map')
    pg.go_forward(); pg.wait_for_timeout(600)
    check(pg.is_visible('#vList'), 'Forward works')
    pg.locator('#list .row', has_text='Chepkongi').click(); pg.wait_for_timeout(400)
    pg.click('#hBack'); pg.wait_for_timeout(500)
    check(pg.is_visible('#vList') and pg.evaluate('location.hash') == '#list', 'in-app Back = phone Back')
    # route back
    pg.click('#bRouteMode'); pg.locator('#list .row', has_text='Turbo').click(); pg.click('#bRouteGo'); pg.wait_for_timeout(500)
    pg.locator('#routeStops [data-open]').first.click(); pg.wait_for_timeout(400); pg.go_back(); pg.wait_for_timeout(500)
    check(pg.is_visible('#vRoute'), 'Back from a route stop returns to the route')
    # refresh button
    pg.click('#bRefresh'); pg.wait_for_timeout(1200)
    check(pg.locator('.toast').count() == 1 and 'Up to date' in pg.inner_text('.toast'), 'refresh button')
    pg.screenshot(path=os.path.join(HERE, 'm2-refresh.png'))
    check(not errs, 'no page errors: ' + str(errs[:3]))
    br.close()
print('problems:', len(problems))
