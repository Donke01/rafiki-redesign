# Fingerprint / Touch ID lock through WebAuthn (virtual platform authenticator).
import re, os
from playwright.sync_api import sync_playwright
BASE = 'http://localhost:8797'; OUT = '/tmp/claude-0/-home-claude/a284d8b3-3cbb-5c42-a9ef-c5363bc71e30/scratchpad/web20'; os.makedirs(OUT, exist_ok=True)
P = [{'id': '101', 'title': 'Chepkongi Dispensary', 'country': 'Kenya', 'ck': 'ke', 'area': 'Eldoret', 'type': 'health', 'status': 'Completed', 'gps': [0.5143, 35.2698], 'photo': None}]
problems = []
def check(c, w):
    print(('ok   ' if c else 'FAIL ') + w)
    if not c: problems.append(w)
def api(route, req):
    if 'activity=' in req.url: return route.fulfill(json={'ok': True, 'activity': []})
    if 'team=' in req.url: return route.fulfill(json={'ok': True, 'team': []})
    return route.fulfill(json={'ok': True, 'who': 'Meril Kiprop', 'admin': False, 'reviewOpen': 0, 'projects': P, 'repairs': {}, 'visits': {}, 'jobs': [], 'assigned': [], 'confirmed': {}})
with sync_playwright() as p:
    br = p.chromium.launch(); ctx = br.new_context(viewport={'width': 390, 'height': 844}, device_scale_factor=2, service_workers='block')
    pg = ctx.new_page(); errs = []; pg.on('pageerror', lambda e: errs.append(str(e)))
    cdp = ctx.new_cdp_session(pg); cdp.send('WebAuthn.enable')
    au = cdp.send('WebAuthn.addVirtualAuthenticator', {'options': {'protocol': 'ctap2', 'transport': 'internal', 'hasUserVerification': True, 'isUserVerified': True, 'hasResidentKey': False, 'automaticPresenceSimulation': True}})['authenticatorId']
    pg.route('**/api/pin-auth/me', lambda r, q: r.fulfill(json={'available': False})); pg.route(re.compile(r'/api/pin(\?.*)?$'), api)
    pg.route('**/tile.openstreetmap.org/**', lambda r, q: r.abort()); pg.route('**/arcgisonline.com/**', lambda r, q: r.abort())
    pg.goto(BASE + '/pin'); pg.wait_for_timeout(800)
    for d in '1111': pg.click('.pk[data-k="%s"]' % d)
    pg.wait_for_timeout(1200); pg.evaluate("localStorage.setItem('rfPinOnb','1')"); pg.goto(BASE + '/pin#me'); pg.wait_for_timeout(1500)
    check(pg.locator('#meBio:not(.hidden) #bioToggle').count() == 1, 'Me offers fingerprint unlock'); pg.click('#bioToggle'); pg.wait_for_timeout(800)
    check(pg.evaluate("!!localStorage.getItem('rfPinBio')") and pg.get_attribute('#bioToggle', 'aria-checked') == 'true', 'turned on with the device check')
    pg.screenshot(path=OUT + '/1-me.png')
    pg.click('#lockBtn'); pg.wait_for_timeout(400)
    check(pg.locator('#bioLock').count() == 1 and pg.evaluate("!!localStorage.getItem('rfPin')"), 'Lock the app locks but keeps the PIN'); pg.screenshot(path=OUT + '/2-locked.png')
    pg.click('#blGo'); pg.wait_for_timeout(800); check(pg.locator('#bioLock').count() == 0, 'unlocks with the fingerprint')
    pg.reload(); pg.wait_for_timeout(1500); check(pg.locator('#bioLock').count() == 1, 'opening the app asks first')
    cdp.send('WebAuthn.setUserVerified', {'authenticatorId': au, 'isUserVerified': False})
    pg.click('#blGo'); pg.wait_for_timeout(1200); check(pg.locator('#bioLock').count() == 1 and 'Not unlocked' in pg.inner_text('#blMsg'), 'a failed check stays locked')
    pg.screenshot(path=OUT + '/3-failed.png')
    pg.click('#blPinBtn'); pg.wait_for_timeout(200); check(pg.locator('.bl-pad .bk').count() == 12, 'PIN keypad with a fingerprint key'); pg.screenshot(path=OUT + '/4-pin.png')
    for k in '1112': pg.click('.bk[data-b="%s"]' % k)
    pg.wait_for_timeout(200); check('isn’t right' in pg.inner_text('#blMsg'), 'a wrong PIN says so')
    for k in '1111': pg.click('.bk[data-b="%s"]' % k)
    pg.wait_for_timeout(400); check(pg.locator('#bioLock').count() == 0, 'the PIN unlocks too')
    pg.evaluate("document.dispatchEvent(new Event('visibilitychange'))")
    pg.click('#lockBtn'); pg.wait_for_timeout(300); pg.click('#blOut'); pg.wait_for_timeout(600)
    check(not pg.evaluate("localStorage.getItem('rfPin')") and not pg.evaluate("localStorage.getItem('rfPinBio')"), 'Sign out forgets the PIN and the fingerprint lock')
    check(not errs, 'no errors ' + str(errs[:1])); br.close()
print('PROBLEMS', problems)
