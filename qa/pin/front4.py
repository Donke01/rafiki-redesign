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
    if state.get('signed') and 'rfpin=' in (req.headers.get('cookie') or ''): who = 'Morgan Mfo'
    elif state.get('signed') is not None and who is None: return route.fulfill(status=401, json={'ok': False, 'reason': 'signin'})
    if who is None: return route.fulfill(status=401, json={'ok': False, 'reason': 'pin'})
    if req.method == 'GET':
        u = req.url
        if 'history=' in u:
            return route.fulfill(json={'ok': True, 'notes': [
                {'id': '1', 'text': '🩺 Site visit by Bertin (Rafiki pin app): ⚠️ Needs repair. Notes: Handle loose.', 'at': '2026-10-18T09:00:00Z', 'by': 'Morgan', 'files': [{'name': 'p.jpg', 'ext': 'jpg', 'url': BASE + '/img/pin/icon-192.png'}, {'name': 'v.webm', 'ext': 'webm', 'url': BASE + '/x.webm'}]}]})
        if 'activity=' in u: return route.fulfill(json={'ok': True, 'activity': ACT})
        if 'id=' in u: return route.fulfill(json={'ok': True, 'last': state['last'], 'canUndo': True})
        return route.fulfill(json={'ok': True, 'who': who, 'monday': who == 'Morgan Mfo', 'projects': PROJECTS, 'repairs': state['repairs']})
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

    state['signed'] = False
    def me(route, req):
        u = {'name': 'Morgan Mfo', 'photo': BASE + '/img/pin/icon-192.png'} if state['signed'] and 'rfpin=' in (req.headers.get('cookie') or '') else None
        route.fulfill(json={'ok': True, 'available': True, 'user': u})
    def start(route, req):
        state['signed'] = True
        ret = req.url.split('return=')[1] if 'return=' in req.url else ''
        import urllib.parse
        route.fulfill(status=302, headers={'Location': BASE + '/pin?signin=ok' + urllib.parse.unquote(ret), 'Set-Cookie': 'rfpin=abcdefghijklmnopqrstuvwxyz0123; Path=/; HttpOnly; SameSite=Lax'})
    def logout(route, req):
        state['signed'] = False
        route.fulfill(json={'ok': True}, headers={'Set-Cookie': 'rfpin=x; Path=/; Max-Age=0'})
    pg.route('**/api/pin-auth/me', me); pg.route('**/api/pin-auth/start**', start); pg.route('**/api/pin-auth/logout', logout)
    pg.goto(BASE + '/pin#p/101/visit'); pg.wait_for_timeout(800)
    check(pg.is_visible('#bMonday') and pg.is_visible('.pp-pad'), 'PIN pad with the monday.com key')
    pg.screenshot(path=os.path.join(HERE, 'o1-lock.png'))
    pg.click('#bMonday'); pg.wait_for_timeout(500); pg.click('.msi a.lnk'); pg.wait_for_timeout(1500)
    check(pg.inner_text('#who') == 'Morgan Mfo', 'signed in as the monday.com person: ' + pg.inner_text('#who'))
    check(pg.is_visible('#paneVisit') and pg.evaluate('location.search') == '', 'came back to the same screen, address tidied: ' + pg.evaluate('location.href'))
    pg.wait_for_timeout(500); pg.click('[data-act=next]'); pg.click('[data-state=working]'); pg.click('[data-go="6"]'); pg.click('[data-act=send]'); pg.wait_for_timeout(1000)
    check('Visit report sent' in pg.inner_text('#vw'), 'save works with the monday.com sign-in')
    pg.reload(); pg.wait_for_timeout(1500)
    check(pg.inner_text('#who') == 'Morgan Mfo' and pg.is_visible('#vProj'), 'stays signed in after refresh')
    pg.click('#tabs [data-v=vMe]'); pg.wait_for_timeout(400)
    check('monday.com' in pg.inner_text('#mePin') and pg.inner_text('#lockBtn') == 'Sign out', 'Me shows monday.com + Sign out')
    pg.screenshot(path=os.path.join(HERE, 'o2-me.png'))
    pg.click('#lockBtn'); pg.wait_for_timeout(600)
    check(pg.is_visible('#vLock') and not state['signed'], 'sign out')
    # personal PIN still works as a fallback
    pg.fill('#iPin', '1111'); pg.click('#fLock button[type=submit]'); pg.wait_for_timeout(1200)
    check(pg.inner_text('#who') == 'Meril Kiprop', 'PIN fallback still works')
    # error coming back from monday.com
    pg.click('#tabs [data-v=vMe]'); pg.click('#lockBtn'); pg.goto(BASE + '/pin?signin=board'); pg.wait_for_timeout(800)
    check('projects board' in pg.inner_text('#mSign'), 'explains a sign-in problem: ' + pg.inner_text('#mSign'))
    check(not errs, 'no page errors: ' + str(errs[:3]))
    br.close()
print('problems:', len(problems))
