# App feel: app header, first-run screens with the RAFIKI drops.
import os, re, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from playwright.sync_api import sync_playwright
HERE = os.path.dirname(os.path.abspath(__file__)); BASE = 'http://localhost:8797'
P = [{'id': '101', 'title': 'Chepkongi Dispensary', 'country': 'Kenya', 'ck': 'ke', 'area': 'Eldoret', 'type': 'health', 'status': 'Completed', 'gps': [0.5143, 35.2698], 'photo': None},
     {'id': '102', 'title': 'Kiptoo Well', 'country': 'Kenya', 'ck': 'ke', 'area': 'Nandi', 'type': 'borehole', 'status': 'Completed', 'gps': None, 'photo': None},
     {'id': '104', 'title': 'Kapsaret Borehole', 'country': 'Kenya', 'ck': 'ke', 'area': 'Eldoret', 'type': 'borehole', 'status': 'Completed', 'gps': [0.5101, 35.2742], 'photo': None},
     {'id': '105', 'title': 'Turbo Spring', 'country': 'Kenya', 'ck': 'ke', 'area': 'Turbo', 'type': 'collection', 'status': 'Completed', 'gps': [0.63, 35.05], 'photo': None}]
problems = []
def check(c, w):
    print(('ok   ' if c else 'FAIL ') + w)
    if not c: problems.append(w)
def api(route, req):
    u = req.url
    if req.method == 'GET':
        if 'activity=' in u: return route.fulfill(json={'ok': True, 'activity': [{'by': 'Meril Kiprop', 'a': 'pin', 'id': '101', 't': 'Chepkongi Dispensary', 'at': '2026-10-19T08:00:00Z'}, {'by': 'Meril Kiprop', 'a': 'visit', 's': 'working', 'id': '101', 't': 'Chepkongi Dispensary', 'at': '2026-10-19T09:00:00Z'}]})
        if 'history=' in u: return route.fulfill(json={'ok': True, 'notes': []})
        if 'id=' in u: return route.fulfill(json={'ok': True, 'last': None, 'visits': []})
        return route.fulfill(json={'ok': True, 'who': 'Meril Kiprop', 'admin': True, 'reviewOpen': 0, 'projects': P, 'repairs': {}, 'visits': {}, 'jobs': [], 'assigned': [], 'confirmed': {}})
    return route.fulfill(json={'ok': True, 'updateId': '9'})
OUT = '/tmp/claude-0/-home-claude/a284d8b3-3cbb-5c42-a9ef-c5363bc71e30/scratchpad/onb'
os.makedirs(OUT, exist_ok=True)
with sync_playwright() as p:
    br = p.chromium.launch(); ctx = br.new_context(viewport={'width': 390, 'height': 844}, device_scale_factor=2, service_workers='block', geolocation={'latitude': 0.5150, 'longitude': 35.27, 'accuracy': 8}, permissions=['geolocation'])
    pg = ctx.new_page(); errs = []; pg.on('pageerror', lambda e: errs.append(str(e)))
    pg.route('**/api/pin-auth/me', lambda r, q: r.fulfill(json={'available': False})); pg.route(re.compile(r'/api/pin(\?.*)?$'), api)
    pg.route('**/api/admin**', lambda r, q: r.fulfill(json={'ok': True, 'items': [], 'team': [], 'mail': {}, 'cron': False}))
    pg.route('**/arcgisonline.com/**', lambda r, q: r.abort())
    pg.goto(BASE + '/pin?onb=1'); pg.wait_for_timeout(900)
    check(pg.locator('#onb:not(.hidden) [data-o=signin]').count() == 1, 'welcome shows before signing in'); pg.screenshot(path=OUT + '/0.png')
    pg.click('#onb [data-o=signin]'); pg.wait_for_timeout(400)
    for d in '9999': pg.click('.pk[data-k="%s"]' % d)
    pg.wait_for_selector('#onb:not(.hidden)'); pg.wait_for_timeout(900)
    check('Every water point' in pg.inner_text('#onb'), 'first screen'); pg.screenshot(path=OUT + '/1.png')
    pg.click('#onb [data-o=next]'); pg.wait_for_timeout(500)
    check(pg.locator('#onb .go1[disabled]').count() == 1, 'continue waits for a choice')
    pg.click('#onb [data-r=visit]'); pg.wait_for_timeout(450); pg.screenshot(path=OUT + '/2.png')
    pg.click('#onb .go1'); pg.wait_for_timeout(500); pg.screenshot(path=OUT + '/3.png')
    pg.click('#onb .lc[data-l=sw]'); pg.wait_for_timeout(900)
    check('Kiswahili' in pg.inner_text('#onbLn'), 'swiping to Kiswahili')
    pg.click('#onb [data-o=lang]'); pg.wait_for_timeout(600)
    t = pg.inner_text('#onb'); check('Unafanya kazi wapi?' in t, 'rest of the screens in Kiswahili: ' + t[:60].replace('\n', ' '))
    pg.click('#onb [data-c=ke]'); pg.wait_for_timeout(400); pg.screenshot(path=OUT + '/4.png')
    pg.click('#onb .go1'); pg.wait_for_timeout(500); pg.screenshot(path=OUT + '/5.png')
    pg.click('#onb [data-o=loc]'); pg.wait_for_timeout(600); pg.screenshot(path=OUT + '/6.png')
    pg.click('#onb [data-o=done]'); pg.wait_for_timeout(900)
    check(pg.locator('#onb.hidden').count() == 1, 'done closes the first-run screens')
    check(pg.evaluate("localStorage.getItem('rfPinFilter')") == 'near' and pg.evaluate("localStorage.getItem('rfPinHomeSet')") == 'ke', 'choices kept')
    pg.screenshot(path=OUT + '/7.png')
    pg.reload(); pg.wait_for_timeout(1200)
    check(pg.locator('#onb.hidden').count() == 1 or 'karibu' in pg.inner_text('#onb'), 'only a short welcome after that'); pg.screenshot(path=OUT + '/splash.png'); pg.wait_for_timeout(2200); check(pg.locator('#onb.hidden').count() == 1, 'welcome goes away by itself')
    pg.click('#tabs [data-v=vList]'); pg.click('.chip[data-f=all]'); pg.click('#list .row[data-id="101"]'); pg.wait_for_timeout(900)
    check(pg.locator('#hBack').is_visible() and not pg.locator('#hAv').is_visible(), 'back in the header on a project')
    pg.screenshot(path=OUT + '/8.png')
    pg.click('#hBack'); pg.wait_for_timeout(600)
    check(pg.locator('#vList:not(.hidden)').count() == 1, 'header back goes back')
    pg.click('#hAv'); pg.wait_for_timeout(1200)
    check(pg.locator('#meStamps .stp.got').count() == 2, 'two stamps earned (first drop, first visit)')
    pg.locator('.stamps-card').screenshot(path=OUT + '/9.png')
    pg.click('#lockBtn'); pg.wait_for_timeout(500)
    check(pg.locator('#onb.hidden').count() == 1, 'welcome only once per opening')
    check(not errs, 'no page errors: ' + str(errs))
    print('problems:', len(problems))
