# Me › Notifications: a full page from the bell (filters, Today / This week / Earlier, unread, mark all as read,
# the empty-state drop), in light, dark and sun, English and Kiswahili, and with reduced motion.
import json, os, re, datetime
from playwright.sync_api import sync_playwright
HERE = os.path.dirname(os.path.abspath(__file__)); BASE = 'http://localhost:8797'; OUT = os.path.join(HERE, 'notes'); os.makedirs(OUT, exist_ok=True)
now = datetime.datetime.now(datetime.timezone.utc).replace(tzinfo=None)
iso = lambda h: (now - datetime.timedelta(hours=h)).strftime('%Y-%m-%dT%H:%M:%SZ')
P = [{'id': '104', 'title': 'Kapsaret Borehole', 'country': 'Kenya', 'ck': 'ke', 'area': 'Eldoret', 'type': 'Borehole well', 'status': 'Completed', 'gps': [0.47, 35.25]},
     {'id': '105', 'title': 'Turbo Spring', 'country': 'Kenya', 'ck': 'ke', 'area': 'Turbo', 'type': 'Water collection', 'status': 'Completed', 'gps': [0.63, 35.05]},
     {'id': '106', 'title': 'Chepkongi Dispensary', 'country': 'Kenya', 'ck': 'ke', 'area': 'Eldoret', 'type': 'Health clinic', 'status': 'Completed', 'gps': [0.52, 35.28]},
     {'id': '107', 'title': 'Moiben Water Kiosk', 'country': 'Kenya', 'ck': 'ke', 'area': 'Moiben', 'type': 'Water collection', 'status': 'Completed', 'gps': [0.81, 35.38]}]
ACT = [{'by': 'Reid Anderson', 'a': 'assign', 'to': 'Meril Kiprop', 'n': 2, 't': '2 visits for Meril Kiprop', 'at': iso(0.5)},
       {'by': 'Bertin Mwangi', 'a': 'visit', 's': 'notworking', 'id': '104', 't': 'Kapsaret Borehole', 'at': iso(0.8)},
       {'by': 'Bertin Mwangi', 'a': 'new', 'id': '107', 't': 'Moiben Water Kiosk', 'at': iso(30)},
       {'by': 'Grace Achieng', 'a': 'fixed', 'id': '105', 't': 'Turbo Spring', 'at': iso(72)},
       {'by': 'Amina Wanjiru', 'a': 'visit', 's': 'repair', 'id': '106', 't': 'Chepkongi Dispensary', 'at': iso(240)},
       {'by': 'Meril Kiprop', 'a': 'pin', 'id': '104', 't': 'Kapsaret Borehole', 'at': iso(0.3)}]
problems = []
def check(c, w): print(('ok   ' if c else 'FAIL ') + w); (None if c else problems.append(w))
def api(route, req):
    if req.method == 'GET':
        if 'activity=' in req.url: return route.fulfill(json={'ok': True, 'activity': ACT})
        if 'team=' in req.url: return route.fulfill(json={'ok': True, 'team': []})
        return route.fulfill(json={'ok': True, 'who': 'Meril Kiprop', 'admin': True, 'reviewOpen': 2, 'projects': P, 'repairs': {'104': {'state': 'notworking', 'by': 'Bertin', 'at': iso(0.8), 't': 'Kapsaret Borehole', 'assignee': 'Meril Kiprop', 'stepAt': {'assigned': iso(0.6)}}},
                                   'visits': {}, 'jobs': ['104'], 'assigned': [], 'confirmed': {}, 'me': {'kind': 'app', 'nick': '', 'phone': '', 'home': 'ke'}})
    route.fulfill(json={'ok': True})
def theme(pg, t): pg.evaluate("t => { localStorage.setItem('rfPinTheme', t); if (t) document.documentElement.setAttribute('data-theme', t); else document.documentElement.removeAttribute('data-theme'); }", t)
def unread_again(pg): pg.evaluate("localStorage.removeItem('rfPinNoteSeen'); localStorage.removeItem('rfPinNoteRead')")
def openp(pg): pg.click('#meBell'); pg.wait_for_selector('.npg'); pg.wait_for_timeout(1300)
def rows(pg): return pg.locator('.npg .np-r').count()
def shut(pg): pg.keyboard.press('Escape'); pg.wait_for_timeout(450)

def login(ctx):
    pg = ctx.new_page(); errs = []; pg.on('pageerror', lambda e: errs.append(str(e)))
    pg.route('**/api/pin-auth/me', lambda r, q: r.fulfill(json={'available': False})); pg.route(re.compile(r'/api/pin(\?.*)?$'), api)
    pg.goto(BASE + '/pin'); pg.wait_for_timeout(800)
    for d in '9999': pg.click('.pk[data-k="%s"]' % d)
    pg.wait_for_timeout(1200); pg.evaluate("localStorage.setItem('rfPinOnb','1')"); pg.goto(BASE + '/pin#me'); pg.wait_for_timeout(1500)
    return pg, errs

with sync_playwright() as p:
    br = p.chromium.launch()
    ctx = br.new_context(viewport={'width': 390, 'height': 844}, is_mobile=True, has_touch=True, service_workers='block', device_scale_factor=2)
    pg, errs = login(ctx)
    # the bell: 7 unread (assign, repair today, new site, fixed, repair 10 days ago, job, review; not my own pin), ringing
    n = pg.inner_text('#meBellN'); check(n == '7', 'bell count 7: ' + n)
    check(pg.locator('#meBell.ring').count() == 1, 'bell rings while there is something unread')
    check(pg.locator('#meBellN.pop').count() == 1, 'red count popped in')
    pg.screenshot(path=os.path.join(OUT, '01-me-bell.png'), clip={'x': 0, 'y': 0, 'width': 390, 'height': 260})
    openp(pg)
    w = pg.locator('.npg')
    check(w.get_attribute('role') == 'dialog' and w.get_attribute('aria-modal') == 'true', 'full page is a modal dialog')
    box = w.bounding_box(); check(box['width'] >= 389 and box['height'] >= 843, 'it fills the screen: %s' % box)
    check(pg.evaluate("document.activeElement && document.activeElement.getAttribute('data-np')") == 'back', 'focus on Back')
    check(rows(pg) == 7 and pg.locator('.npg .np-r.new').count() == 7, 'seven rows, all unread')
    groups = pg.eval_on_selector_all('.npg .np-g', 'e => e.map(x => x.textContent)')
    check('This week' in groups and 'Earlier' in groups and (now.hour < 2 or 'Today' in groups), 'groups: %s' % groups)
    check(pg.inner_text('#npU') == '7 unread', 'subtitle says 7 unread')
    check(pg.inner_text('.np-f [data-f=unread] .c') == '7', 'Unread pill count')
    t = pg.inner_text('.npg .np-in')
    check('Bertin Mwangi' in t and 'Kapsaret Borehole' in t and 'Reported it’s not working' in t and 'min ago' in t, 'row: name, time · project, message')
    check('Reid Anderson' in t and 'Asked you to make 2 visits' in t, 'visits assigned to me')
    check('Office review' in t and '2 changes waiting for your review' in t, 'review row')
    check(pg.locator('.npg .np-av:not(.tile)').count() == 5 and pg.locator('.npg .np-av.tile').count() == 2, 'avatars for people, kind tiles for job and review')
    check(pg.locator('.npg .np-av .bd').count() == 5, 'type badge on each avatar')
    check(pg.locator('.npg .np-av:not(.tile)').first.inner_text().strip() == 'RA', 'initials in the avatar')
    check(pg.locator('.npg .np-d').count() == 7, 'blue dots')
    an = pg.eval_on_selector('.npg .np-d', 'e => getComputedStyle(e).animationName'); check(an == 'npDot', 'unread dot pulses: ' + an)
    an = pg.eval_on_selector('.npg .np-r', 'e => getComputedStyle(e).animationName'); check(an == 'npRow', 'rows rise in: ' + an)
    pg.screenshot(path=os.path.join(OUT, '02-page-light.png'))
    # filters
    pg.click('.np-f [data-f=me]'); pg.wait_for_timeout(900)
    t = pg.inner_text('.npg .np-in'); check(rows(pg) == 2 and 'Reid Anderson' in t and 'Repair job' in t, 'For me: visits assigned + repair job (%d)' % rows(pg))
    pg.screenshot(path=os.path.join(OUT, '03-for-me.png'))
    pg.click('.np-f [data-f=repairs]'); pg.wait_for_timeout(900)
    t = pg.inner_text('.npg .np-in'); check(rows(pg) == 3 and 'Grace Achieng' in t and 'Amina Wanjiru' in t and 'Reid' not in t, 'Repairs: reported + fixed (%d)' % rows(pg))
    pg.click('.np-f [data-f=all]'); pg.wait_for_timeout(600)
    # tap a row: opens the project, that one is read, Back comes to Me
    pg.click('.npg .np-r:has-text("Grace Achieng")'); pg.wait_for_timeout(900)
    check(pg.locator('.npg').count() == 0 and '#p/105' in pg.url, 'tap opens the project: ' + pg.url)
    check(pg.inner_text('#meBellN') == '6', 'one fewer unread')
    pg.go_back(); pg.wait_for_timeout(900); check(pg.url.endswith('#me') and pg.is_visible('#vMe'), 'Back from the project comes to Me')
    openp(pg)
    check(pg.locator('.npg .np-r:has-text("Grace Achieng").new').count() == 0 and pg.locator('.npg .np-r.new').count() == 6, 'the opened one is no longer unread')
    # menu › Mark all as read
    pg.click('[data-np=menu]'); pg.wait_for_timeout(350)
    check(pg.locator('.np-m [role=menuitem]').count() == 2, 'menu: mark all as read, settings')
    pg.screenshot(path=os.path.join(OUT, '04-menu.png'))
    pg.click('.np-m [data-np=all]'); pg.wait_for_timeout(700)
    check(pg.locator('.npg .np-r.new').count() == 0 and pg.locator('.npg .np-d').count() == 0, 'all read: no tint, no dots')
    check(pg.inner_text('#npU') == 'All read ✓', 'subtitle says all read')
    check(pg.locator('#meBellN.hidden').count() == 1 and pg.locator('#meBell.ring').count() == 0, 'bell quiet')
    pg.click('.np-f [data-f=unread]'); pg.wait_for_timeout(1500)
    check(pg.locator('.np-e[data-empty=unread]').count() == 1 and 'You’re all caught up' in pg.inner_text('.np-e'), 'empty state for Unread')
    check(pg.locator('.np-e .mascot').count() == 1 and pg.locator('.np-e .np-fc').count() == 3, 'drop with three little notes')
    an = pg.eval_on_selector('.np-fc.c2', 'e => getComputedStyle(e).animationName'); check(an == 'npFr', 'notes float in from the side: ' + an)
    pg.wait_for_timeout(900); pg.screenshot(path=os.path.join(OUT, '05-empty-light.png'))
    # Esc closes, focus back on the bell, the page's history step is gone
    shut(pg)
    check(pg.locator('.npg').count() == 0 and pg.evaluate("document.activeElement.id") == 'meBell', 'Esc closes, focus on the bell')
    check(pg.url.endswith('#me') and pg.is_visible('#vMe'), 'still on Me')
    # the phone's Back closes it too
    openp(pg); pg.go_back(); pg.wait_for_timeout(600)
    check(pg.locator('.npg').count() == 0 and pg.is_visible('#vMe'), 'Back closes the page and stays on Me')
    # settings link from the page
    openp(pg); pg.click('.np-in .np-set'); pg.wait_for_timeout(600)
    check(pg.locator('.npg').count() == 0 and pg.locator('.bsh [data-nk=fixed]').count() == 1, 'Choose what to be told about opens Settings › Notifications')
    pg.click('.bsh .bsh-x'); pg.wait_for_timeout(400)
    # dark
    theme(pg, 'dark'); unread_again(pg); pg.evaluate('1'); pg.wait_for_timeout(100)
    openp(pg); pg.screenshot(path=os.path.join(OUT, '06-page-dark.png'))
    pg.click('.np-f [data-f=me]'); pg.wait_for_timeout(300)
    pg.evaluate("localStorage.setItem('rfPinNoteKinds', JSON.stringify({assign:0, job:0}))"); pg.click('.np-f [data-f=all]'); pg.wait_for_timeout(200); pg.click('.np-f [data-f=me]'); pg.wait_for_timeout(1600)
    check(pg.locator('.np-e[data-empty=me]').count() == 1 and 'Nothing assigned to you right now' in pg.inner_text('.np-e'), 'empty For me')
    pg.screenshot(path=os.path.join(OUT, '07-empty-dark.png'))
    shut(pg); pg.evaluate("localStorage.removeItem('rfPinNoteKinds')")
    # sun
    theme(pg, 'sun'); openp(pg); pg.screenshot(path=os.path.join(OUT, '08-page-sun.png')); shut(pg)
    # Kiswahili
    theme(pg, ''); pg.click('#segLang button[data-v=sw]'); pg.wait_for_timeout(300); openp(pg)
    t = pg.inner_text('.npg'); check('Arifa' in t and 'Zote' in t and 'Hazijasomwa' in t and 'Kwangu' in t and 'Ameripoti kuwa haifanyi kazi' in t, 'Kiswahili')
    pg.screenshot(path=os.path.join(OUT, '09-page-sw.png')); shut(pg)
    pg.evaluate("document.querySelector('#segLang button[data-v=\"\"]').click()"); pg.wait_for_timeout(200)
    check(not errs, 'no page errors %s' % errs[:2])
    ctx.close()
    # reduced motion: no ringing, no rising, everything in place
    ctx = br.new_context(viewport={'width': 390, 'height': 844}, is_mobile=True, has_touch=True, service_workers='block', reduced_motion='reduce')
    pg, errs = login(ctx); openp(pg)
    check(pg.eval_on_selector('.npg .np-r', 'e => getComputedStyle(e).animationName') == 'none' and pg.eval_on_selector('#meBell svg', 'e => getComputedStyle(e).animationName') == 'none', 'reduced motion: no animation')
    check(pg.eval_on_selector('.npg .np-r', 'e => getComputedStyle(e).opacity') == '1', 'reduced motion: rows visible')
    pg.click('[data-np=menu]'); pg.click('.np-m [data-np=all]'); pg.click('.np-f [data-f=unread]'); pg.wait_for_timeout(300)
    check(pg.eval_on_selector('.np-fc.c1', 'e => getComputedStyle(e).opacity') == '1', 'reduced motion: the little notes sit still, visible')
    pg.screenshot(path=os.path.join(OUT, '10-empty-still.png'))
    check(not errs, 'no page errors (reduced motion) %s' % errs[:2])
    br.close()
print('PROBLEMS', problems)
