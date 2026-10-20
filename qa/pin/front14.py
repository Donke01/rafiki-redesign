# Phone-app feel: slide transitions, search screen, bottom sheets, title in the bar, swipeable tabs, drag-up map list.
import os, re, sys
from playwright.sync_api import sync_playwright
BASE = 'http://localhost:8797'
OUT = '/tmp/claude-0/-home-claude/a284d8b3-3cbb-5c42-a9ef-c5363bc71e30/scratchpad/web14'; os.makedirs(OUT, exist_ok=True)
P = [{'id': '101', 'title': 'Chepkongi Dispensary', 'country': 'Kenya', 'ck': 'ke', 'area': 'Eldoret', 'type': 'health', 'status': 'Completed', 'gps': [0.5143, 35.2698], 'photo': None},
     {'id': '102', 'title': 'Kiptoo Well', 'country': 'Kenya', 'ck': 'ke', 'area': 'Nandi', 'type': 'borehole', 'status': 'Completed', 'gps': None, 'photo': None},
     {'id': '104', 'title': 'Kapsaret Borehole', 'country': 'Kenya', 'ck': 'ke', 'area': 'Eldoret', 'type': 'borehole', 'status': 'Completed', 'gps': [0.5101, 35.2742], 'photo': None},
     {'id': '105', 'title': 'Turbo Spring', 'country': 'Kenya', 'ck': 'ke', 'area': 'Turbo', 'type': 'collection', 'status': 'Completed', 'gps': [0.63, 35.05], 'photo': None}] + \
    [{'id': str(200 + i), 'title': 'Village Well %d' % i, 'country': 'Uganda', 'ck': 'ug', 'area': 'Mbale', 'type': 'borehole', 'status': 'Completed', 'gps': [1.08 + i / 100, 34.17], 'photo': None} for i in range(12)]
problems = []
def check(c, w):
    print(('ok   ' if c else 'FAIL ') + w)
    if not c: problems.append(w)
def api(route, req):
    u = req.url
    if req.method == 'GET':
        if 'activity=' in u: return route.fulfill(json={'ok': True, 'activity': []})
        if 'history=' in u: return route.fulfill(json={'ok': True, 'notes': []})
        if 'id=' in u: return route.fulfill(json={'ok': True, 'last': None, 'visits': []})
        return route.fulfill(json={'ok': True, 'who': 'Meril Kiprop', 'admin': True, 'reviewOpen': 0, 'projects': P, 'repairs': {}, 'visits': {}, 'jobs': [], 'assigned': [], 'confirmed': {}})
    return route.fulfill(json={'ok': True, 'updateId': '9'})
with sync_playwright() as p:
    br = p.chromium.launch(); ctx = br.new_context(viewport={'width': 390, 'height': 844}, device_scale_factor=2, has_touch=True, is_mobile=True, service_workers='block', geolocation={'latitude': 0.5150, 'longitude': 35.27, 'accuracy': 8}, permissions=['geolocation'])
    pg = ctx.new_page(); errs = []; pg.on('pageerror', lambda e: errs.append(str(e)))
    pg.route('**/api/pin-auth/me', lambda r, q: r.fulfill(json={'available': False})); pg.route(re.compile(r'/api/pin(\?.*)?$'), api)
    pg.route('**/api/admin**', lambda r, q: r.fulfill(json={'ok': True, 'items': [], 'team': [], 'mail': {}, 'cron': False}))
    pg.route('**/arcgisonline.com/**', lambda r, q: r.abort()); pg.route('**/tile.openstreetmap.org/**', lambda r, q: r.abort())
    pg.goto(BASE + '/pin'); pg.wait_for_timeout(800)
    for d in '9999': pg.tap('.pk[data-k="%s"]' % d)
    pg.wait_for_timeout(1200); pg.evaluate("localStorage.setItem('rfPinOnb','1')"); pg.goto(BASE + '/pin#list'); pg.wait_for_timeout(3000)
    # search screen
    pg.tap('#homeTop .sbox'); pg.wait_for_timeout(500)
    check(pg.locator('#vSearch:not(.hidden)').count() == 1 and pg.evaluate("document.activeElement.id") == 'sQ', 'search opens its own screen with the keyboard')
    check(pg.locator('#vSearch.view-fwd').count() == 1, 'search slides in from the right')
    check(pg.locator('#sList .row').count() >= 3, 'closest projects before typing')
    pg.fill('#sQ', 'kaps'); pg.wait_for_timeout(200)
    check(pg.locator('#sList .row').count() == 1 and 'Kapsaret' in pg.inner_text('#sList'), 'results as you type'); pg.screenshot(path=OUT + '/1-search.png')
    pg.tap('#sList .row'); pg.wait_for_timeout(700)
    check(pg.locator('#vProj:not(.hidden)').count() == 1, 'result opens the project')
    # swipe tabs
    box = pg.locator('#pSub').bounding_box()
    def swipe(x0, x1, y):
        pg.evaluate("""([x0,x1,y]) => { const el = document.elementFromPoint(x0, y); const t = (x) => new Touch({identifier: 1, target: el, clientX: x, clientY: y});
            el.dispatchEvent(new TouchEvent('touchstart', {bubbles: true, touches: [t(x0)], changedTouches: [t(x0)]}));
            el.dispatchEvent(new TouchEvent('touchend', {bubbles: true, touches: [], changedTouches: [t(x1)]})); }""", [x0, x1, y])
    swipe(330, 120, box['y'] + 5); pg.wait_for_timeout(400)
    check(pg.get_attribute('#tabVisit', 'aria-selected') == 'true', 'swiping left moves to Site visit')
    swipe(120, 330, box['y'] + 5); pg.wait_for_timeout(400)
    check(pg.get_attribute('#tabPin', 'aria-selected') == 'true', 'swiping right goes back')
    pg.evaluate('scrollTo(0, 600)'); pg.wait_for_timeout(300)
    check(pg.locator('body.titled').count() == 1 and 'Kapsaret' in pg.inner_text('#hTitle'), 'project name moves into the top bar'); pg.screenshot(path=OUT + '/2-project-titled.png')
    pg.tap('#hBack'); pg.wait_for_timeout(500)
    check(pg.locator('#vSearch:not(.hidden)').count() == 1, 'back returns to the search')
    pg.tap('#hBack'); pg.wait_for_timeout(500)
    check(pg.locator('#vList:not(.hidden)').count() == 1, 'back again returns to the list')
    # country sheet and sticky chips
    pg.tap('#bCountry'); pg.wait_for_timeout(400)
    check(pg.locator('.bsh.in').count() == 1, 'country opens a bottom sheet'); pg.screenshot(path=OUT + '/3-country-sheet.png')
    pg.tap('.bsh-o[data-k=ug]'); pg.wait_for_timeout(500); pg.tap('.chip[data-f=all]'); pg.wait_for_timeout(500)
    check(pg.input_value('#iCountry') == 'ug' and 'Uganda' in pg.inner_text('#bCountryTx'), 'picking a country filters the list')
    pg.evaluate("document.getElementById('list').scrollIntoView()"); pg.evaluate('scrollBy(0, 300)'); pg.wait_for_timeout(300)
    tb = pg.locator('#vList .tools').bounding_box(); hb = pg.locator('header.top').bounding_box()
    check(abs(tb['y'] - (hb['y'] + hb['height'])) < 4, 'filter chips stay under the top bar while scrolling')
    check(pg.locator('body.titled').count() == 1 and pg.inner_text('#hTitle') == 'Projects', '"Projects" in the top bar'); pg.screenshot(path=OUT + '/4-list-scrolled.png')
    # map: drag-up list and style sheet
    pg.tap('#tabs [data-v=vMap]'); pg.wait_for_timeout(1500)
    check(pg.locator('#nearList .row:visible').count() == 1, 'map list rests on the closest project')
    pg.tap('#nearHd'); pg.wait_for_timeout(400)
    check(pg.locator('#nearList .row:visible').count() == 5, 'opens to the five closest'); pg.screenshot(path=OUT + '/5-map-sheet.png')
    pg.tap('.mstyle'); pg.wait_for_timeout(400)
    check(pg.locator('.bsh.in .bsh-o[data-k=sat]').count() == 1, 'map style sheet'); pg.screenshot(path=OUT + '/6-map-style.png')
    pg.tap('.bsh-o[data-k=sat]'); pg.wait_for_timeout(400)
    check(pg.evaluate("localStorage.getItem('rfPinLayer')") == 'sat', 'satellite chosen')
    check(not errs, 'no page errors: ' + '; '.join(errs[:3]))
    br.close()
print('PROBLEMS:', problems if problems else 'none')
