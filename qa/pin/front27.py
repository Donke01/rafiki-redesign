# Me flows top to bottom (no "This phone" card); the phone's own settings are in Me › Settings › This phone:
# fingerprint switch in the row, Appearance (colours + text size, live preview), Language, Distances, My country;
# "Your name on monday.com" is in Account. Light and dark screenshots to qa/pin/meflow/.
import json, os, re
from playwright.sync_api import sync_playwright
HERE = os.path.dirname(os.path.abspath(__file__)); BASE = os.environ.get('BASE', 'http://localhost:8797'); OUT = os.path.join(HERE, 'meflow'); os.makedirs(OUT, exist_ok=True)
P = [{'id': '104', 'title': 'Kapsaret Borehole', 'country': 'Kenya', 'ck': 'ke', 'area': 'Eldoret', 'type': 'Borehole well', 'status': 'Completed', 'gps': [0.47, 35.25]}]
problems = []
def check(c, w): print(('ok   ' if c else 'FAIL ') + w); (None if c else problems.append(w))
def api(route, req):
    if req.method == 'GET':
        if 'activity=' in req.url: return route.fulfill(json={'ok': True, 'activity': []})
        if 'team=' in req.url: return route.fulfill(json={'ok': True, 'team': []})
        return route.fulfill(json={'ok': True, 'who': 'Meril Kiprop', 'admin': False, 'reviewOpen': 0, 'projects': P, 'repairs': {}, 'visits': {}, 'jobs': [], 'assigned': [], 'confirmed': {}, 'me': {'kind': 'app', 'nick': '', 'phone': '', 'home': 'ke'}})
    route.fulfill(json={'ok': True})
def sub(pg, k): return pg.inner_text('[data-set=%s] small' % k)
def shut(pg): pg.click('.bsh.kits .bsh-x'); pg.wait_for_timeout(450)
with sync_playwright() as p:
    br = p.chromium.launch(); ctx = br.new_context(viewport={'width': 390, 'height': 844}, device_scale_factor=2, is_mobile=True, has_touch=True, service_workers='block')
    pg = ctx.new_page(); errs = []; pg.on('pageerror', lambda e: errs.append(str(e)))
    cdp = ctx.new_cdp_session(pg); cdp.send('WebAuthn.enable')   # a phone with a fingerprint reader (as front20)
    cdp.send('WebAuthn.addVirtualAuthenticator', {'options': {'protocol': 'ctap2', 'transport': 'internal', 'hasUserVerification': True, 'isUserVerified': True, 'hasResidentKey': False, 'automaticPresenceSimulation': True}})
    pg.route('**/api/pin-auth/me', lambda r, q: r.fulfill(json={'available': False})); pg.route(re.compile(r'/api/pin(\?.*)?$'), api)
    pg.route('**/tile.openstreetmap.org/**', lambda r, q: r.abort()); pg.route('**/arcgisonline.com/**', lambda r, q: r.abort())
    pg.goto(BASE + '/pin'); pg.wait_for_timeout(800)
    for d in '9999': pg.click('.pk[data-k="%s"]' % d)
    pg.wait_for_timeout(1200); pg.evaluate("localStorage.setItem('rfPinOnb','1')"); pg.goto(BASE + '/pin#me'); pg.wait_for_timeout(1600)
    # Me reads top to bottom, no "This phone" card
    check(pg.locator('#mePhone').count() == 0 and pg.locator('#paneMe .card h2', has_text=re.compile('^This phone$')).count() == 0, 'no This phone card on Me')
    order = pg.evaluate("[...document.querySelectorAll('#paneMe > *')].filter(e => e.offsetParent !== null || e.id === 'meWait').map(e => e.id || e.className.split(' ').pop())")
    want = ['meHero', 'meWait', 'meWork', 'meMonth', 'card', 'stamps-card', 'meSet', 'meInstall']
    check([x for x in order if x in want] == [x for x in want if x in order] and order.index('meSet') > order.index('stamps-card') > order.index('meMonth'), 'Me order: %s' % order)
    check(pg.locator('#phoneKit').is_hidden(), 'the controls wait out of sight')
    # Settings › This phone
    t = pg.inner_text('#setPhone')
    check('THIS PHONE' in t.upper() and 'Appearance' in t and 'Language' in t and 'Distances' in t and 'My country' in t, 'This phone group')
    check(sub(pg, 'look') == 'Light · normal text' and sub(pg, 'lang') == 'English' and sub(pg, 'units') == 'Kilometres (km)', 'rows show the current values: %s / %s / %s' % (sub(pg, 'look'), sub(pg, 'lang'), sub(pg, 'units')))
    check(pg.locator('#setPhone #meBio:not(.hidden) #bioToggle').count() == 1, 'fingerprint switch right in the row')
    pg.click('#bioToggle'); pg.wait_for_timeout(800)
    check(pg.evaluate("!!localStorage.getItem('rfPinBio')") and pg.get_attribute('#bioToggle', 'aria-checked') == 'true', 'fingerprint unlock turns on')
    pg.click('#bioToggle'); pg.wait_for_timeout(400); check(not pg.evaluate("localStorage.getItem('rfPinBio')"), 'and off again')
    acc = pg.locator('#meSet .sgrp').first
    check(acc.locator('#meMonday:not(.hidden) #monRow').count() == 1 and acc.locator('#monOpen').count() == 1, 'monday.com row in Account, open to connect')
    pg.click('#monRow'); pg.wait_for_timeout(200); check(pg.locator('#meMonday.open').count() == 0, 'monday.com row folds')
    pg.click('#monRow'); pg.wait_for_timeout(200)
    pg.locator('#setPhone').scroll_into_view_if_needed(); pg.wait_for_timeout(300); pg.screenshot(path=os.path.join(OUT, '01-settings-this-phone.png'))
    # Appearance: colours and text size, with a preview
    pg.click('[data-set=look]'); pg.wait_for_timeout(500)
    check(pg.locator('.bsh.kits .lookpv').count() == 1 and pg.locator('.bsh.kits #segTheme').count() == 1 and pg.locator('.bsh.kits #segSize').count() == 1, 'Appearance sheet: preview, colours, text size')
    pg.screenshot(path=os.path.join(OUT, '02-appearance-light.png'))
    fs = pg.eval_on_selector('.lookpv p', 'e => parseFloat(getComputedStyle(e).fontSize)')
    pg.click('#segSize [data-v=xl]'); pg.click('#segTheme [data-v=dark]'); pg.wait_for_timeout(400)
    check(pg.evaluate("document.documentElement.dataset.theme") == 'dark' and pg.evaluate("localStorage.getItem('rfPinTheme')") == 'dark', 'Dark is on')
    check(pg.evaluate("localStorage.getItem('rfPinSize')") == 'xl' and pg.eval_on_selector('.lookpv p', 'e => parseFloat(getComputedStyle(e).fontSize)') > fs, 'Larger text, and the preview grows')
    pg.screenshot(path=os.path.join(OUT, '03-appearance-dark-larger.png'))
    pg.click('#segSize [data-v=""]'); pg.wait_for_timeout(200); shut(pg)
    check(pg.locator('#phoneKit #segTheme').count() == 1 and pg.locator('.bsh').count() == 0, 'controls go back when the sheet closes')
    check(sub(pg, 'look') == 'Dark · normal text', 'Appearance row says Dark: ' + sub(pg, 'look'))
    pg.locator('#setPhone').scroll_into_view_if_needed(); pg.wait_for_timeout(300); pg.screenshot(path=os.path.join(OUT, '04-settings-dark.png'))
    # Language
    pg.click('[data-set=lang]'); pg.wait_for_timeout(500); pg.screenshot(path=os.path.join(OUT, '05-language-dark.png'))
    pg.click('#segLang [data-v=sw]'); pg.wait_for_timeout(400)
    check(pg.evaluate("localStorage.getItem('rfPinLang')") == 'sw' and pg.inner_text('.bsh.kits h3') == 'Lugha' and 'Programu nzima' in pg.inner_text('.bsh.kits .intro'), 'Kiswahili, the sheet too')
    shut(pg); check(sub(pg, 'lang') == 'Kiswahili' and 'Mipangilio' in pg.inner_text('#meSet h2'), 'Language row: Kiswahili')
    pg.locator('#setPhone').scroll_into_view_if_needed(); pg.wait_for_timeout(300); pg.screenshot(path=os.path.join(OUT, '06-settings-sw-dark.png'))
    pg.click('[data-set=lang]'); pg.wait_for_timeout(400); pg.click('#segLang [data-v=""]'); pg.wait_for_timeout(300)
    check(pg.inner_text('.bsh.kits h3') == 'Language', 'back to English'); shut(pg)
    # Distances
    pg.click('[data-set=units]'); pg.wait_for_timeout(400); pg.click('#segUnits [data-v=mi]'); pg.wait_for_timeout(300)
    check(pg.evaluate("localStorage.getItem('rfPinUnits')") == 'mi', 'miles kept'); pg.screenshot(path=os.path.join(OUT, '07-distances-dark.png')); shut(pg)
    check(sub(pg, 'units') == 'Miles', 'Distances row: Miles')
    pg.click('[data-set=units]'); pg.wait_for_timeout(400); pg.click('#segUnits [data-v=""]'); pg.wait_for_timeout(200); shut(pg)
    check(sub(pg, 'units') == 'Kilometres (km)', 'back to km')
    # My country
    pg.click('[data-set=home]'); pg.wait_for_timeout(400); pg.select_option('#meHome', 'tz'); pg.wait_for_timeout(300)
    check(pg.evaluate("localStorage.getItem('rfPinHomeSet')") == 'tz' and 'Tanzania' in pg.inner_text('#meHomeTx'), 'My country: Tanzania'); shut(pg)
    check(sub(pg, 'home') == 'Tanzania', 'My country row: Tanzania')
    pg.click('[data-set=home]'); pg.wait_for_timeout(400); pg.select_option('#meHome', ''); pg.wait_for_timeout(200); shut(pg)
    # light again, a look at Me from the top
    pg.click('[data-set=look]'); pg.wait_for_timeout(400); pg.click('#segTheme [data-v=""]'); pg.wait_for_timeout(200); shut(pg)
    check(not pg.evaluate("document.documentElement.dataset.theme"), 'Light again')
    pg.evaluate('scrollTo(0, 0)'); pg.wait_for_timeout(300); pg.screenshot(path=os.path.join(OUT, '08-me-light.png'), full_page=True)
    pg.click('[data-set=lang]'); pg.wait_for_timeout(500); pg.screenshot(path=os.path.join(OUT, '09-language-light.png')); shut(pg)
    pg.click('[data-set=units]'); pg.wait_for_timeout(500); pg.screenshot(path=os.path.join(OUT, '10-distances-light.png')); shut(pg)
    pg.click('[data-set=look]'); pg.wait_for_timeout(400); pg.click('#segTheme [data-v=sun]'); pg.wait_for_timeout(300); pg.screenshot(path=os.path.join(OUT, '11-appearance-sun.png'))
    pg.click('#segTheme [data-v=""]'); pg.wait_for_timeout(200); shut(pg)
    check(not errs, 'no page errors %s' % errs[:2]); br.close()
print('PROBLEMS', problems)
