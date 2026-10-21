# Me: notifications bell, Settings (personal details, change PIN, help), centred headings.
import json, os, re, datetime
from playwright.sync_api import sync_playwright
HERE = os.path.dirname(os.path.abspath(__file__)); BASE = 'http://localhost:8797'; OUT = os.path.join(HERE, 'settings'); os.makedirs(OUT, exist_ok=True)
now = datetime.datetime.utcnow()
iso = lambda h: (now - datetime.timedelta(hours=h)).strftime('%Y-%m-%dT%H:%M:%SZ')
P = [{'id': '104', 'title': 'Kapsaret Borehole', 'country': 'Kenya', 'ck': 'ke', 'area': 'Eldoret', 'type': 'Borehole well', 'status': 'Completed', 'gps': [0.47, 35.25]},
     {'id': '105', 'title': 'Turbo Spring', 'country': 'Kenya', 'ck': 'ke', 'area': 'Turbo', 'type': 'Water collection', 'status': 'Completed', 'gps': [0.63, 35.05]}]
ACT = [{'by': 'Bertin Mwangi', 'a': 'visit', 's': 'notworking', 'id': '104', 't': 'Kapsaret Borehole', 'at': iso(2)}, {'by': 'Grace Achieng', 'a': 'fixed', 'id': '105', 't': 'Turbo Spring', 'at': iso(5)},
       {'by': 'Reid Anderson', 'a': 'assign', 'to': 'Meril Kiprop', 'n': 2, 't': '2 visits for Meril Kiprop', 'at': iso(1)}, {'by': 'Meril Kiprop', 'a': 'pin', 'id': '104', 't': 'Kapsaret Borehole', 'at': iso(3)}]
posts, problems = [], []
def check(c, w): print(('ok   ' if c else 'FAIL ') + w); (None if c else problems.append(w))
me = {'kind': 'app', 'nick': '', 'phone': '', 'home': 'ke'}
def api(route, req):
    if req.method == 'GET':
        if 'activity=' in req.url: return route.fulfill(json={'ok': True, 'activity': ACT})
        if 'team=' in req.url: return route.fulfill(json={'ok': True, 'team': []})
        return route.fulfill(json={'ok': True, 'who': 'Meril Kiprop', 'admin': True, 'reviewOpen': 2, 'projects': P, 'repairs': {'104': {'state': 'notworking', 'by': 'Bertin', 'at': iso(2), 't': 'Kapsaret Borehole', 'assignee': 'Meril Kiprop', 'stepAt': {'assigned': iso(1)}}}, 'visits': {}, 'jobs': ['104'], 'assigned': [], 'confirmed': {}, 'me': me})
    b = json.loads(req.post_data); posts.append(b)
    if b['action'] == 'profile': return route.fulfill(json={'ok': True, 'me': {'kind': 'app', 'nick': b['nick'], 'phone': b['phone'], 'home': b['home']}})
    if b['action'] == 'pin-change': return route.fulfill(json={'ok': True} if b['current'] == '9999' and b['pin'] == '4826' else {'ok': False, 'reason': 'pin-current'})
    route.fulfill(json={'ok': True})
with sync_playwright() as p:
    br = p.chromium.launch(); ctx = br.new_context(viewport={'width': 390, 'height': 844}, is_mobile=True, has_touch=True, service_workers='block')
    pg = ctx.new_page(); errs = []; pg.on('pageerror', lambda e: errs.append(str(e)))
    pg.route('**/api/pin-auth/me', lambda r, q: r.fulfill(json={'available': False})); pg.route(re.compile(r'/api/pin(\?.*)?$'), api)
    pg.goto(BASE + '/pin'); pg.wait_for_timeout(800)
    for d in '9999': pg.click('.pk[data-k="%s"]' % d)
    pg.wait_for_timeout(1200); pg.evaluate("localStorage.setItem('rfPinOnb','1')"); pg.goto(BASE + '/pin#list'); pg.wait_for_timeout(1200)
    pg.screenshot(path=os.path.join(OUT, '01-home-centred.png'))
    pg.goto(BASE + '/pin#me'); pg.wait_for_timeout(1500)
    n = pg.inner_text('#meBellN'); check(n == '5', 'bell shows 5 new (assign, repair, fixed, job, review; not my own pin): ' + n)
    pg.screenshot(path=os.path.join(OUT, '02-me-top.png'))
    pg.click('#meBell'); pg.wait_for_timeout(1200)
    t = pg.inner_text('.npg'); check('Reid Anderson' in t and 'Asked you to make 2 visits' in t and 'not working' in t and 'Grace Achieng' in t and 'Turbo Spring' in t and 'waiting for your review' in t and 'Meril Kiprop' not in t, 'notification list (full page)')
    pg.screenshot(path=os.path.join(OUT, '03-bell.png'))
    pg.click('.npg [data-np=menu]'); pg.click('.np-m [data-np=all]'); pg.wait_for_timeout(300)
    pg.click('.npg [data-np=back]'); pg.wait_for_timeout(500)
    check(pg.locator('#meBellN.hidden').count() == 1 and pg.locator('.npg').count() == 0, 'read after Mark all as read')
    pg.locator('#meSet').scroll_into_view_if_needed(); pg.wait_for_timeout(300)
    pg.screenshot(path=os.path.join(OUT, '04-settings.png'))
    pg.click('[data-set=profile]'); pg.wait_for_timeout(500)
    pg.fill('#pfNick', 'Mery'); pg.fill('#pfPhone', '0712 345 678'); pg.screenshot(path=os.path.join(OUT, '05-profile.png'))
    pg.click('#pfSave'); pg.wait_for_timeout(800)
    check(any(x.get('action') == 'profile' and x['phone'] == '0712 345 678' and x['nick'] == 'Mery' for x in posts), 'profile saved')
    check('Mery · 0712 345 678' in pg.inner_text('#meSet'), 'settings row shows nickname and phone')
    pg.click('[data-set=pin]'); pg.wait_for_timeout(500)
    pg.fill('#cpOld', '9999'); pg.fill('#cpNew', '4826'); pg.fill('#cpNew2', '4826'); pg.screenshot(path=os.path.join(OUT, '06-pin.png')); pg.click('#cpSave'); pg.wait_for_timeout(800)
    check(pg.evaluate("localStorage.getItem('rfPin')") == '4826', 'new PIN kept on this phone')
    pg.click('[data-set=help]'); pg.wait_for_timeout(500); pg.locator('.faq summary').first.click(); pg.wait_for_timeout(300)
    pg.screenshot(path=os.path.join(OUT, '07-help.png')); pg.click('.bsh .bsh-x'); pg.wait_for_timeout(400)
    pg.click('[data-set=notify]'); pg.wait_for_timeout(500); pg.screenshot(path=os.path.join(OUT, '08-notify.png'))
    pg.click('[data-nk=fixed]'); pg.wait_for_timeout(200); pg.click('.bsh .bsh-x'); pg.wait_for_timeout(300)
    pg.click('#meBell'); pg.wait_for_timeout(800); check('Turbo Spring' not in pg.inner_text('.npg'), 'turning off Repairs fixed hides them'); pg.click('.npg [data-np=back]'); pg.wait_for_timeout(500)
    pg.click('[data-set=about]'); pg.wait_for_timeout(500); pg.screenshot(path=os.path.join(OUT, '09-about.png'))
    check(not errs, 'no page errors %s' % errs[:2]); br.close()
print('PROBLEMS', problems)
