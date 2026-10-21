# Kiswahili across the whole app: switch in Me, every screen changes; switch back restores English.
import json, os, re
from playwright.sync_api import sync_playwright
HERE = os.path.dirname(os.path.abspath(__file__)); BASE = 'http://localhost:8797'
P = [{'id': '101', 'title': 'Chepkongi Dispensary', 'country': 'Kenya', 'ck': 'ke', 'area': 'Eldoret', 'type': 'health', 'status': 'Completed', 'gps': [0.5143, 35.2698], 'photo': None},
     {'id': '102', 'title': 'Kiptoo Well', 'country': 'Kenya', 'ck': 'ke', 'area': 'Nandi', 'type': 'borehole', 'status': 'Completed', 'gps': None, 'photo': None},
     {'id': '104', 'title': 'Kapsaret Borehole', 'country': 'Kenya', 'ck': 'ke', 'area': 'Eldoret', 'type': 'borehole', 'status': 'Completed', 'gps': [0.47, 35.25], 'photo': None, 'pump': 'manual'}]
problems = []
def check(c, w):
    print(('ok   ' if c else 'FAIL ') + w)
    if not c: problems.append(w)
def api(route, req):
    u = req.url
    if req.method == 'GET':
        if 'activity=' in u: return route.fulfill(json={'ok': True, 'activity': [{'by': 'Meril', 'a': 'visit', 's': 'repair', 'id': '104', 't': 'Kapsaret Borehole', 'at': '2026-10-18T09:00:00Z'}]})
        if 'history=' in u: return route.fulfill(json={'ok': True, 'notes': []})
        if 'id=' in u: return route.fulfill(json={'ok': True, 'last': None, 'visits': []})
        return route.fulfill(json={'ok': True, 'who': 'Reid Anderson', 'admin': True, 'reviewOpen': 1, 'projects': P, 'repairs': {'104': {'state': 'repair', 'by': 'Bertin', 'at': '2026-10-18T08:00:00Z', 'note': 'Handle loose', 'urgency': 'urgent'}}, 'visits': {}, 'jobs': [], 'assigned': [], 'confirmed': {}})
    return route.fulfill(json={'ok': True, 'updateId': '9'})
ENG = ['Projects', 'Map', 'New site', 'Needs a location', 'Nearby', 'Repairs', 'Due a visit', 'Pin location', 'Site visit', 'History', 'Save this location', 'Use my location',
       'Overview', 'Visits', 'Review', 'Team', 'Export', 'Lock the app', 'Language', 'Distances', 'Add a new site', 'Site name', 'Reported as needing repair', 'Mark fixed', 'Plan a route', 'Search by project name or place']
def english_left(pg):
    txt = pg.evaluate("""() => { const out = []; const w = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT); let t;
      while ((t = w.nextNode())) { const p = t.parentElement; if (!p || p.closest('script,style,.hidden,.leaflet-control-attribution')) continue; const r = p.getBoundingClientRect(); if (!r.width) continue; out.push(t.data.trim()); }
      document.querySelectorAll('[placeholder]').forEach(e => { if (e.getBoundingClientRect().width) out.push(e.getAttribute('placeholder')); });
      return out; }""")
    return sorted(set(x for x in txt if x in ENG))
with sync_playwright() as p:
    br = p.chromium.launch(); ctx = br.new_context(viewport={'width': 390, 'height': 844}, service_workers='block', geolocation={'latitude': 0.5150, 'longitude': 35.27, 'accuracy': 8}, permissions=['geolocation'])
    pg = ctx.new_page(); errs = []; pg.on('pageerror', lambda e: errs.append(str(e)))
    pg.route('**/api/pin-auth/me', lambda r, q: r.fulfill(json={'available': False})); pg.route(re.compile(r'/api/pin(\?.*)?$'), api)
    pg.route('**/api/admin**', lambda r, q: r.fulfill(json={'ok': True, 'items': [], 'team': [], 'mail': {}, 'cron': False}))
    pg.route('**/tile.openstreetmap.org/**', lambda r, q: r.abort()); pg.route('**/arcgisonline.com/**', lambda r, q: r.abort())
    pg.goto(BASE + '/pin'); pg.wait_for_timeout(500)
    for d in '9999': pg.click('.pk[data-k="%s"]' % d)
    pg.wait_for_selector('#vLock.hidden', state='attached'); pg.wait_for_timeout(600)
    pg.click('#tabs [data-v=vMe]'); pg.click('[data-set=lang]'); pg.wait_for_timeout(400); pg.click('#segLang [data-v=sw]'); pg.click('.bsh.kits .bsh-x'); pg.wait_for_timeout(500)   # Me › Settings › Language
    check('Lugha' in pg.inner_text('#vMe') and 'Mimi na timu' in pg.inner_text('#vMe'), 'Me page in Kiswahili')
    pg.screenshot(path=os.path.join(HERE, 'sw-me.png'))
    shots = {}
    for name, sel in [('list', '#tabs [data-v=vList]'), ('new', '#tabs [data-v=vNew]'), ('office', '#tabOffice'), ('map', '#tabs [data-v=vMap]')]:
        pg.click(sel); pg.wait_for_timeout(900)
        left = english_left(pg); check(not left, name + ' has no English left: ' + str(left))
        pg.screenshot(path=os.path.join(HERE, 'sw-' + name + '.png'))
    check('Miradi' in pg.inner_text('#tabs') and 'Ramani' in pg.inner_text('#tabs'), 'tab bar in Kiswahili')
    pg.click('#tabs [data-v=vList]'); pg.wait_for_timeout(500); pg.click('.chip[data-f=all]'); pg.wait_for_timeout(300)
    pg.click('#list .row[data-id="104"]'); pg.wait_for_timeout(900)
    left = english_left(pg); check(not left, 'project page has no English left: ' + str(left))
    pg.screenshot(path=os.path.join(HERE, 'sw-project.png'), full_page=True)
    # reload keeps Kiswahili
    pg.reload(); pg.wait_for_timeout(1200)
    check('Miradi' in pg.inner_text('#tabs'), 'Kiswahili stays after a refresh')
    # back to English
    pg.click('#tabs [data-v=vMe]'); pg.click('[data-set=lang]'); pg.wait_for_timeout(400); pg.click('#segLang [data-v=""]'); pg.click('.bsh.kits .bsh-x'); pg.wait_for_timeout(500)   # Me › Settings › Language
    pg.click('#tabs [data-v=vList]'); pg.wait_for_timeout(500)
    check('Projects' in pg.inner_text('#vList') and 'Map' in pg.inner_text('#tabs'), 'English comes back')
    check(not errs, 'no page errors: ' + str(errs))
    print('problems:', len(problems))
