# 2026 look: glass, home greeting, the RAFIKI drop, repair tracker, green saved buttons.
import os, re, sys
from playwright.sync_api import sync_playwright
HERE = os.path.dirname(os.path.abspath(__file__)); BASE = 'http://localhost:8797'
P = [{'id': '101', 'title': 'Chepkongi Dispensary', 'country': 'Kenya', 'ck': 'ke', 'area': 'Eldoret', 'type': 'health', 'status': 'Completed', 'gps': [0.5143, 35.2698], 'photo': None},
     {'id': '102', 'title': 'Kiptoo Well', 'country': 'Kenya', 'ck': 'ke', 'area': 'Nandi', 'type': 'borehole', 'status': 'Completed', 'gps': None, 'photo': None},
     {'id': '104', 'title': 'Kapsaret Borehole', 'country': 'Kenya', 'ck': 'ke', 'area': 'Eldoret', 'type': 'borehole', 'status': 'Completed', 'gps': [0.5101, 35.2742], 'photo': None, 'pump': 'manual'},
     {'id': '105', 'title': 'Turbo Spring', 'country': 'Kenya', 'ck': 'ke', 'area': 'Turbo', 'type': 'collection', 'status': 'Completed', 'gps': [0.63, 35.05], 'photo': None}]
REP = {'104': {'state': 'notworking', 'by': 'Bertin', 'at': '2026-10-18T08:00:00Z', 'note': 'Handle loose', 'urgency': 'urgent', 'step': 'parts', 'assignee': 'Meril Kiprop', 'due': '2026-10-24', 'parts': 'pump handle, 2 pivot pins', 'cost': 4500,
               'stepAt': {'assigned': '2026-10-18T10:20:00Z', 'parts': '2026-10-19T07:45:00Z'}}}
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
        return route.fulfill(json={'ok': True, 'who': 'Meril Kiprop', 'admin': True, 'reviewOpen': 0, 'projects': P, 'repairs': REP, 'visits': {}, 'jobs': ['104'], 'assigned': [], 'confirmed': {}})
    return route.fulfill(json={'ok': True, 'updateId': '9'})
theme = sys.argv[1] if len(sys.argv) > 1 else ''
with sync_playwright() as p:
    br = p.chromium.launch(); ctx = br.new_context(viewport={'width': 390, 'height': 844}, device_scale_factor=2, service_workers='block', geolocation={'latitude': 0.5150, 'longitude': 35.27, 'accuracy': 8}, permissions=['geolocation'])
    if theme: ctx.add_init_script("localStorage.setItem('rfPinTheme','%s')" % theme)
    pg = ctx.new_page(); errs = []; pg.on('pageerror', lambda e: errs.append(str(e)))
    pg.route('**/api/pin-auth/me', lambda r, q: r.fulfill(json={'available': False})); pg.route(re.compile(r'/api/pin(\?.*)?$'), api)
    pg.route('**/api/admin**', lambda r, q: r.fulfill(json={'ok': True, 'items': [], 'team': [], 'mail': {}, 'cron': False}))
    pg.route('**/arcgisonline.com/**', lambda r, q: r.abort())
    pg.goto(BASE + '/pin'); pg.wait_for_timeout(900)
    pf = 'g' + (theme or 'light') + '-'
    check(pg.locator('#vLock .mascot').count() == 1, 'drop on the sign-in screen')
    pg.screenshot(path=os.path.join(HERE, pf + '1-lock.png'))
    for d in '9999': pg.click('.pk[data-k="%s"]' % d)
    pg.wait_for_selector('#vLock.hidden', state='attached'); pg.wait_for_timeout(900)
    pg.click('#tabs [data-v=vList]'); pg.wait_for_timeout(900)
    t = pg.inner_text('#homeTop')
    check('Habari, Meril' in t and 'today?' in t and 'Pin a location' in t, 'home greeting and quick actions: ' + t.replace('\n', ' | '))
    check('Eldoret' in t, 'where you are, from the nearest project')
    pg.screenshot(path=os.path.join(HERE, pf + '2-home.png'))
    pg.click('#homeTop [data-q=repairs]'); pg.wait_for_timeout(500)
    check(pg.locator('.chip[data-f=repairs][aria-pressed=true]').count() == 1, 'quick action sets the Repairs filter')
    pg.click('#list .row[data-id="104"]'); pg.wait_for_timeout(1800)
    tr = pg.inner_text('#pRepair')
    check('Meril Kiprop' in tr and 'Parts ordered' in tr and '· Live' in tr, 'repair tracker: ' + tr.replace('\n', ' | ')[:200])
    check(pg.locator('#pRepair li.done').count() == 2 and pg.locator('#pRepair li.now').count() == 1, 'two steps done, parts ordered is now')
    print(pg.evaluate("[...document.querySelectorAll('.smap img')].map(i=>[i.naturalWidth,i.style.left,i.style.top,i.getBoundingClientRect().x|0,i.getBoundingClientRect().y|0])"))
    pg.locator('#pRepair').screenshot(path=os.path.join(HERE, pf + '3-tracker.png'))
    pg.screenshot(path=os.path.join(HERE, pf + '3-project.png'))
    pg.click('#bFixed'); pg.click('#bFixed'); pg.wait_for_timeout(800)
    check(pg.locator('#pRepair li.done').count() == 4, 'after Mark fixed every step is ticked')
    pg.click('#tabs [data-v=vList]'); pg.wait_for_timeout(400); pg.click('.chip[data-f=repairs]'); pg.wait_for_timeout(400)
    check(pg.locator('#list .empty .mascot').count() == 1, 'empty list shows the drop')
    pg.screenshot(path=os.path.join(HERE, pf + '4-empty.png'))
    pg.click('#tabs [data-v=vMe]'); pg.wait_for_timeout(600); pg.screenshot(path=os.path.join(HERE, pf + '5-me.png'))
    pg.click('#tabs [data-v=vList]'); pg.click('.chip[data-f=all]'); pg.click('#list .row[data-id="102"]'); pg.wait_for_timeout(900)
    pg.evaluate("document.querySelector('#bSave') && (savedBtn = null)")
    check(not errs, 'no page errors: ' + str(errs))
    print('problems:', len(problems))
