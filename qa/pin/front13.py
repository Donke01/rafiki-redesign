# Polished design: screenshots of welcome, home, map, project+repair, me (and checks for the new pieces).
import os, re, sys
from playwright.sync_api import sync_playwright
BASE = 'http://localhost:8797'
OUT = sys.argv[1] if len(sys.argv) > 1 else '/tmp/claude-0/-home-claude/a284d8b3-3cbb-5c42-a9ef-c5363bc71e30/scratchpad/web13'
CHECK = len(sys.argv) > 2
os.makedirs(OUT, exist_ok=True)
P = [{'id': '101', 'title': 'Chepkongi Dispensary', 'country': 'Kenya', 'ck': 'ke', 'area': 'Eldoret', 'type': 'health', 'status': 'Completed', 'gps': [0.5143, 35.2698], 'photo': None},
     {'id': '102', 'title': 'Kiptoo Well', 'country': 'Kenya', 'ck': 'ke', 'area': 'Nandi', 'type': 'borehole', 'status': 'Completed', 'gps': None, 'photo': None},
     {'id': '104', 'title': 'Kapsaret Borehole', 'country': 'Kenya', 'ck': 'ke', 'area': 'Eldoret', 'type': 'borehole', 'status': 'Completed', 'gps': [0.5101, 35.2742], 'photo': None},
     {'id': '105', 'title': 'Turbo Spring', 'country': 'Kenya', 'ck': 'ke', 'area': 'Turbo', 'type': 'collection', 'status': 'Completed', 'gps': [0.63, 35.05], 'photo': None}]
REP = {'104': {'state': 'notworking', 'by': 'Bertin', 'at': '2026-10-18T08:00:00Z', 'note': 'Handle loose', 'urgency': 'urgent', 't': 'Kapsaret Borehole', 'cost': 4500, 'step': 'parts', 'assignee': 'Meril Kiprop', 'due': '2026-10-24', 'stepAt': {'assigned': '2026-10-18T10:20:00Z', 'parts': '2026-10-19T07:45:00Z'}}}
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
        return route.fulfill(json={'ok': True, 'who': 'Meril Kiprop', 'admin': True, 'reviewOpen': 0, 'projects': P, 'repairs': REP, 'visits': {}, 'jobs': [], 'assigned': [], 'confirmed': {}})
    return route.fulfill(json={'ok': True, 'updateId': '9'})
with sync_playwright() as p:
    br = p.chromium.launch(); ctx = br.new_context(viewport={'width': 390, 'height': 844}, device_scale_factor=2, service_workers='block', geolocation={'latitude': 0.5150, 'longitude': 35.27, 'accuracy': 8}, permissions=['geolocation'])
    pg = ctx.new_page(); errs = []; pg.on('pageerror', lambda e: errs.append(str(e)))
    pg.route('**/api/pin-auth/me', lambda r, q: r.fulfill(json={'available': False})); pg.route(re.compile(r'/api/pin(\?.*)?$'), api)
    pg.route('**/api/admin**', lambda r, q: r.fulfill(json={'ok': True, 'items': [], 'team': [], 'mail': {}, 'cron': False}))
    pg.route('**/arcgisonline.com/**', lambda r, q: r.abort()); pg.route('**/tile.openstreetmap.org/**', lambda r, q: r.abort())
    pg.goto(BASE + '/pin?onb=1'); pg.wait_for_timeout(1000); pg.screenshot(path=OUT + '/1-welcome.png')
    if CHECK: check(pg.locator('#onb .lsw').count() == 1, 'language switch on the welcome')
    pg.click('#onb [data-o=signin]'); pg.wait_for_timeout(400)
    for d in '9999': pg.click('.pk[data-k="%s"]' % d)
    pg.wait_for_timeout(900)
    pg.evaluate("localStorage.setItem('rfPinOnb','1')")
    pg.goto(BASE + '/pin'); pg.wait_for_timeout(3200)
    pg.click('#tabs [data-v=vList]'); pg.wait_for_timeout(500); pg.click('.chip[data-f=all]'); pg.wait_for_timeout(500)
    pg.evaluate('scrollTo(0,0)'); pg.wait_for_timeout(300); pg.screenshot(path=OUT + '/2-home.png')
    pg.evaluate("document.getElementById('list').scrollIntoView()"); pg.evaluate('scrollBy(0,-90)'); pg.wait_for_timeout(300); pg.screenshot(path=OUT + '/2b-list.png')
    if CHECK:
        check(pg.locator('#homeTop .sbox').count() == 1, 'search box on home')
        check(pg.locator('#list .row .chev').count() >= 4, 'rows have a chevron')
        check(pg.locator('#list .row .tx .tag').count() >= 4, 'tag sits under the name')
    pg.click('#tabs [data-v=vMap]'); pg.wait_for_timeout(1500); pg.screenshot(path=OUT + '/3-map.png')
    if CHECK: check(pg.locator('#mHere .here.slim').count() == 1, 'slim you-are-here strip')
    pg.click('#tabs [data-v=vList]'); pg.wait_for_timeout(400); pg.click('#list .row[data-id="104"]'); pg.wait_for_timeout(1200)
    pg.evaluate('scrollTo(0,0)'); pg.screenshot(path=OUT + '/4-project.png')
    pg.evaluate("document.querySelector('.trk') && document.querySelector('.trk').scrollIntoView()"); pg.wait_for_timeout(300); pg.screenshot(path=OUT + '/4b-repair.png')
    if CHECK:
        check(pg.locator('.phead .tag').count() >= 1, 'status tag in the project header')
        check(pg.locator('.trk .rnote').count() == 1, 'report note box')
    pg.click('#hBack'); pg.wait_for_timeout(500); pg.click('#tabs [data-v=vMe]'); pg.wait_for_timeout(1200); pg.evaluate('scrollTo(0,0)'); pg.screenshot(path=OUT + '/5-me.png')
    check(not errs, 'no page errors: ' + '; '.join(errs[:3]))
    br.close()
print('PROBLEMS:', problems if problems else 'none')
