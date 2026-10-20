# Lock screen: monday.com sign-in with your own token, then the app opens under your monday.com name.
import re, os
from playwright.sync_api import sync_playwright
BASE = 'http://localhost:8797'; OUT = '/tmp/claude-0/-home-claude/a284d8b3-3cbb-5c42-a9ef-c5363bc71e30/scratchpad/web19'; os.makedirs(OUT, exist_ok=True)
P = [{'id': '101', 'title': 'Chepkongi Dispensary', 'country': 'Kenya', 'ck': 'ke', 'area': 'Eldoret', 'type': 'health', 'status': 'Completed', 'gps': [0.5143, 35.2698], 'photo': None}]
TOK = 'eyJhbGciOiJIUzI1NiJ9.' + 'b' * 120 + '.sig'
problems = []; st = {'signed': False}
def check(c, w):
    print(('ok   ' if c else 'FAIL ') + w)
    if not c: problems.append(w)
def me(route, req): return route.fulfill(json={'ok': True, 'available': True, 'user': {'name': 'Meril Adoyo', 'photo': ''} if st['signed'] else None})
def tok(route, req):
    b = req.post_data_json
    if b.get('token') == TOK: st['signed'] = True; return route.fulfill(json={'ok': True, 'name': 'Meril Adoyo', 'sid': 'x'})
    return route.fulfill(status=400, json={'ok': False, 'reason': 'token-bad'})
def api(route, req):
    if 'activity=' in req.url: return route.fulfill(json={'ok': True, 'activity': []})
    if 'team=' in req.url: return route.fulfill(json={'ok': True, 'team': []})
    return route.fulfill(json={'ok': True, 'who': 'Meril Adoyo', 'monday': True, 'admin': False, 'reviewOpen': 0, 'projects': P, 'repairs': {}, 'visits': {}, 'jobs': [], 'assigned': [], 'confirmed': {}})
with sync_playwright() as p:
    br = p.chromium.launch(); ctx = br.new_context(viewport={'width': 390, 'height': 844}, device_scale_factor=2, service_workers='block', permissions=['clipboard-read', 'clipboard-write'])
    pg = ctx.new_page(); errs = []; pg.on('pageerror', lambda e: errs.append(str(e)))
    pg.route('**/api/pin-auth/me', me); pg.route('**/api/pin-auth/token', tok); pg.route(re.compile(r'/api/pin(\?.*)?$'), api)
    pg.route('**/tile.openstreetmap.org/**', lambda r, q: r.abort()); pg.route('**/arcgisonline.com/**', lambda r, q: r.abort())
    pg.goto(BASE + '/pin'); pg.wait_for_timeout(1500)
    check(pg.locator('#mondayBox:not(.off)').count() == 1, 'monday.com sign-in shows on the lock screen')
    pg.click('#bMonday'); pg.wait_for_timeout(500)
    check(pg.locator('.msi.in').count() == 1 and pg.get_attribute('#msOpen', 'href').endswith('/apps/manage/tokens'), 'opens the token sign-in sheet'); pg.screenshot(path=OUT + '/1-sheet.png')
    pg.evaluate('t => navigator.clipboard.writeText(t)', 'z' * 60); pg.click('#msPaste'); pg.wait_for_timeout(500)
    check('didn’t accept' in pg.inner_text('#msMsg'), 'a refused token says so')
    pg.evaluate('t => navigator.clipboard.writeText(t)', TOK); pg.click('#msPaste'); pg.wait_for_timeout(2500)
    check(pg.locator('#vLock:not(.hidden)').count() == 0, 'signed in: the lock screen is gone')
    pg.goto(BASE + '/pin#me'); pg.wait_for_timeout(1500)
    check('Meril Adoyo' in pg.inner_text('#meName') and 'monday.com' in pg.inner_text('#mePin'), 'Me shows the monday.com name and sign-in: ' + pg.inner_text('#mePin'))
    check(pg.locator('#meMonday.hidden').count() == 1, 'no "connect" card when already signed in with monday.com')
    check(not errs, 'no errors ' + str(errs[:1])); br.close()
print('PROBLEMS', problems)
