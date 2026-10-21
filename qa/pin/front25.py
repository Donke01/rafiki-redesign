# Office › Team: a compact list grouped by role (crown on admins), a person sheet that springs up, add a person.
import json, os, re, sys, datetime
from playwright.sync_api import sync_playwright
HERE = os.path.dirname(os.path.abspath(__file__)); BASE = os.environ.get('BASE', 'http://localhost:8799'); OUT = os.path.join(HERE, 'team'); os.makedirs(OUT, exist_ok=True)
now = datetime.datetime.now(datetime.timezone.utc)
iso = lambda h: (now - datetime.timedelta(hours=h)).strftime('%Y-%m-%dT%H:%M:%SZ')
P = [{'id': '104', 'title': 'Kapsaret Borehole', 'country': 'Kenya', 'ck': 'ke', 'area': 'Eldoret', 'type': 'Borehole well', 'status': 'Completed', 'gps': [0.47, 35.25]}]
def team():
    return [{'name': 'Meril Kiprop', 'home': 'ke', 'admin': True, 'email': 'meril@rafikioutreach.org', 'phone': '+254 712 000 111', 'source': 'app', 'lastSeen': iso(1)},
            {'name': 'Reid Anderson', 'home': '', 'admin': True, 'email': '', 'source': 'cloudflare', 'lastSeen': iso(30)},
            {'name': 'Bertin Mwangi', 'home': 'ke', 'admin': False, 'email': '', 'phone': '', 'source': 'app', 'lastSeen': iso(3)},
            {'name': 'Grace Achieng', 'home': 'ug', 'admin': False, 'email': 'grace@example.org', 'phone': '+256 700 123 456', 'source': 'app', 'lastSeen': None},
            {'name': 'Jessica Anderson', 'home': '', 'admin': False, 'email': '', 'source': 'monday', 'lastSeen': iso(80)},
            {'name': 'Tumaini Mrisho', 'home': 'tz', 'admin': False, 'email': '', 'phone': '', 'source': 'app', 'lastSeen': iso(200)}]
problems = []
def check(c, w): print(('ok   ' if c else 'FAIL ') + w); (None if c else problems.append(w))

def run(theme, width, full):
    posts = []
    def api(route, req):
        if req.method == 'GET':
            if 'activity=' in req.url: return route.fulfill(json={'ok': True, 'activity': []})
            if 'team=' in req.url: return route.fulfill(json={'ok': True, 'team': []})
            return route.fulfill(json={'ok': True, 'who': 'Meril Kiprop', 'admin': True, 'reviewOpen': 0, 'projects': P, 'repairs': {}, 'visits': {}, 'jobs': [], 'assigned': [], 'confirmed': {}, 'me': {'kind': 'app', 'home': 'ke'}})
        route.fulfill(json={'ok': True})
    def admin(route, req):
        if req.method == 'GET':
            if 'view=team' in req.url: return route.fulfill(json={'ok': True, 'team': team(), 'mail': {'ready': True, 'to': ['meril@rafikioutreach.org']}, 'cron': True})
            return route.fulfill(json={'ok': True, 'items': []})
        b = json.loads(req.post_data); posts.append(b)
        if b['action'] == 'team-pin': return route.fulfill(json={'ok': True, 'pin': '5307'})
        if b['action'] == 'team-add': return route.fulfill(json={'ok': True, 'pin': '4821'})
        route.fulfill(json={'ok': True})
    tag = '%s-%d' % (theme, width)
    with sync_playwright() as p:
        br = p.chromium.launch(); mobile = width < 700
        ctx = br.new_context(viewport={'width': width, 'height': 844 if mobile else 860}, device_scale_factor=2, is_mobile=mobile, has_touch=mobile, service_workers='block')
        ctx.add_init_script("localStorage.setItem('rfPinTheme','%s');localStorage.setItem('rfPinOnb','1');localStorage.setItem('rfPinOTab','team')" % ('' if theme == 'light' else theme))
        pg = ctx.new_page(); errs = []; pg.on('pageerror', lambda e: errs.append(str(e)))
        pg.route('**/api/pin-auth/me', lambda r, q: r.fulfill(json={'available': False})); pg.route(re.compile(r'/api/pin(\?.*)?$'), api)
        pg.route('**/api/admin**', admin); pg.route('**/arcgisonline.com/**', lambda r, q: r.abort())
        pg.goto(BASE + '/pin'); pg.wait_for_timeout(800)
        for d in '9999': pg.click('.pk[data-k="%s"]' % d)
        pg.wait_for_selector('#vLock.hidden', state='attached'); pg.wait_for_timeout(700)
        pg.click('#tabOffice'); pg.wait_for_timeout(400)
        if pg.locator('[data-o=team][aria-pressed=true]').count() == 0: pg.click('[data-o=team]')
        pg.wait_for_selector('.tm-row'); pg.wait_for_timeout(900)
        adm = pg.eval_on_selector_all('.tm-list[data-sec=adm] .tm-row', 'e => e.map(x => x.dataset.who)')
        fld = pg.eval_on_selector_all('.tm-list[data-sec=fld] .tm-row', 'e => e.map(x => x.dataset.who)')
        check(adm == ['Meril Kiprop', 'Reid Anderson'] and fld == ['Bertin Mwangi', 'Grace Achieng', 'Jessica Anderson', 'Tumaini Mrisho'], '%s: grouped by role %s %s' % (tag, adm, fld))
        check(pg.inner_text('.tm-sec.adm .n') == '2' and pg.inner_text('.tm-sec.fld .n') == '4', tag + ': section counts')
        check(pg.locator('.tm-list[data-sec=adm] .tm-row .tm-av .cr svg').count() == 2 and pg.locator('.tm-list[data-sec=fld] .tm-av .cr').count() == 0, tag + ': crown (vector) only on admins')
        check(pg.locator('.tm-sec.adm svg path[fill=currentColor]').count() == 1, tag + ': crown in the Admins header')
        rt = pg.inner_text('.tm-row[data-who="Reid Anderson"]')
        check('PIN in Cloudflare' in rt and 'Admin' in rt, tag + ': tags on a row: ' + rt.replace('\n', ' | '))
        check('KE' in pg.inner_text('.tm-row[data-who="Meril Kiprop"]') and 'Last active' in pg.inner_text('.tm-row[data-who="Meril Kiprop"]'), tag + ': country code and last active')
        hmax = max(pg.eval_on_selector_all('.tm-row', 'e => e.map(x => x.getBoundingClientRect().height)'))
        check(hmax < 120, tag + ': rows are small cards (%dpx tall)' % hmax)
        check(pg.evaluate('document.documentElement.scrollWidth <= innerWidth'), tag + ': no sideways scroll')
        pg.screenshot(path=os.path.join(OUT, tag + '-1-list.png'))
        # open a person
        pg.click('.tm-row[data-who="Grace Achieng"]'); pg.wait_for_timeout(700)
        check(pg.locator('.tsh.in .tm-hd h3').inner_text() == 'Grace Achieng', tag + ': person sheet opens')
        box = pg.locator('.tsh-p').bounding_box()
        if mobile: check(abs(box['y'] + box['height'] - 844) < 2, tag + ': bottom sheet sits on the bottom edge')
        else: check(abs((box['x'] + box['width'] / 2) - width / 2) < 2 and box['y'] > 20, tag + ': centred card on a wide screen')
        pg.screenshot(path=os.path.join(OUT, tag + '-2-person.png'))
        if full:
            pg.fill('.tsh [data-f=phone]', '+256 777 888 999'); pg.select_option('.tsh [data-f=home]', 'ke')
            pg.click('.tsh .tm-sw'); pg.wait_for_timeout(400)
            check(pg.get_attribute('.tsh .tm-sw', 'aria-checked') == 'true', tag + ': admin toggle switches on')
            pg.screenshot(path=os.path.join(OUT, tag + '-3-edit.png'))
            pg.click('.tsh [data-a=save]'); pg.wait_for_timeout(400)
            up = [x for x in posts if x['action'] == 'team-update']
            check(up and up[-1] == {'action': 'team-update', 'name': 'Grace Achieng', 'home': 'ke', 'email': 'grace@example.org', 'phone': '+256 777 888 999', 'admin': True}, tag + ': Save posts %s' % (up[-1] if up else None))
            check('Saved' in pg.inner_text('.tsh .tm-go .msg'), tag + ': saved message')
            pg.wait_for_timeout(1100)
            check(pg.locator('.tsh').count() == 0, tag + ': sheet closes after saving')
            check('Grace Achieng' in pg.eval_on_selector_all('.tm-list[data-sec=adm] .tm-row', 'e => e.map(x => x.dataset.who)'), tag + ': Grace moved to Admins')
            # new PIN
            pg.click('.tm-row[data-who="Bertin Mwangi"]'); pg.wait_for_timeout(600)
            pg.click('.tsh [data-a=pin]'); pg.wait_for_timeout(400)
            check('Make a new PIN for Bertin Mwangi' in pg.inner_text('.tsh .tm-ask') and not [x for x in posts if x['action'] == 'team-pin'], tag + ': New PIN asks first')
            pg.click('.tsh .tm-ask [data-k=yes]'); pg.wait_for_timeout(600)
            check(pg.inner_text('.tsh .pinbox b') == '5307' and {'action': 'team-pin', 'name': 'Bertin Mwangi'} in posts, tag + ': new PIN shown once')
            pg.screenshot(path=os.path.join(OUT, tag + '-4-newpin.png'))
            # remove: asks, cancel, ask again, confirm
            pg.click('.tsh [data-a=rm]'); pg.wait_for_timeout(400)
            check('Remove Bertin Mwangi' in pg.inner_text('.tsh .tm-ask.bad') and not [x for x in posts if x['action'] == 'team-remove'], tag + ': Remove asks to confirm inside the sheet')
            pg.screenshot(path=os.path.join(OUT, tag + '-5-remove.png'))
            pg.click('.tsh .tm-ask [data-k=no]'); pg.wait_for_timeout(200)
            check(pg.locator('.tsh .tm-ask').count() == 0 and not [x for x in posts if x['action'] == 'team-remove'], tag + ': Cancel keeps them')
            pg.click('.tsh [data-a=rm]'); pg.wait_for_timeout(300); pg.click('.tsh .tm-ask [data-k=yes]'); pg.wait_for_timeout(1200)
            check({'action': 'team-remove', 'name': 'Bertin Mwangi'} in posts and pg.locator('.tsh').count() == 0, tag + ': removed and sheet closed')
            # Cloudflare person: short note, only the re-add action
            pg.click('.tm-row[data-who="Reid Anderson"]'); pg.wait_for_timeout(600)
            t = pg.inner_text('.tsh')
            check('Set in Cloudflare — change it there, or re-add here with an app PIN' in t and pg.locator('.tsh [data-a=save], .tsh [data-a=pin], .tsh [data-a=rm]').count() == 0, tag + ': Cloudflare note, no edit actions')
            pg.screenshot(path=os.path.join(OUT, tag + '-6-cloudflare.png'))
            pg.keyboard.press('Escape'); pg.wait_for_timeout(400)
            check(pg.locator('.tsh').count() == 0, tag + ': Esc closes the sheet')
        else:
            pg.click('.tsh .tsh-x'); pg.wait_for_timeout(400)
        # add a person
        pg.click('#tmAdd'); pg.wait_for_timeout(600)
        pg.fill('.tsh [data-f=name]', 'Jessica Anderson'); pg.select_option('.tsh [data-f=home]', 'ke'); pg.fill('.tsh [data-f=phone]', '0712 345 678')
        pg.click('.tsh .tm-sw'); pg.wait_for_timeout(400)
        check(pg.inner_text('.tsh .tm-hd .tm-av') == 'JA' and pg.locator('.tsh .tm-hd .tm-av .cr').count() == 1, tag + ': avatar follows the name and wears the crown')
        pg.screenshot(path=os.path.join(OUT, tag + '-7-add.png'))
        pg.click('.tsh [data-a=add]'); pg.wait_for_timeout(700)
        ad = [x for x in posts if x['action'] == 'team-add']
        check(ad and ad[-1] == {'action': 'team-add', 'name': 'Jessica Anderson', 'home': 'ke', 'email': '', 'phone': '0712 345 678', 'admin': True}, tag + ': add posts %s' % (ad[-1] if ad else None))
        check(pg.inner_text('.tsh .pinbox b') == '4821', tag + ': new person PIN shown in the sheet')
        pg.screenshot(path=os.path.join(OUT, tag + '-8-added.png'))
        pg.click('.tsh [data-a=done]'); pg.wait_for_timeout(400)
        check(pg.locator('.tsh').count() == 0, tag + ': Done closes')
        if full:
            pg.emulate_media(reduced_motion='reduce'); pg.click('.tm-row[data-who="Meril Kiprop"]'); pg.wait_for_timeout(80)
            check(pg.evaluate("getComputedStyle(document.querySelector('.tsh-p')).transitionDuration") in ('0s',), tag + ': reduced motion: no slide')
        check(not errs, tag + ': no page errors %s' % errs[:2]); br.close()

for theme, width, full in [('light', 390, True), ('dark', 390, False), ('light', 1280, True), ('dark', 1280, False), ('sun', 390, False)]:
    run(theme, width, full)
print('PROBLEMS', problems)
sys.exit(1 if problems else 0)
