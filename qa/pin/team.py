# Office › Team, redesigned from Figma (hero, search, chips, person cards, the person sheet). Was: Office › Repairs and Review: a head with numbers, filter chips with counts, calm cards that open a sheet
# holding every action (job step, person, due date, budget, actual cost + receipt, mark fixed, the second approval;
# approve / turn down review items), and the extras folded away. Checks the requests each action posts, the folded
# rows, Kiswahili, light / dark / sun, a 390px phone and a 1280px desktop, and no page errors.
# Screenshots to qa/pin/repairs/.  Run with the site on BASE (default :8851).
import json, os, re, datetime
from playwright.sync_api import sync_playwright
HERE = os.path.dirname(os.path.abspath(__file__)); BASE = os.environ.get('BASE', 'http://localhost:8851'); OUT = os.path.join(HERE, 'team'); os.makedirs(OUT, exist_ok=True)
for f in os.listdir(OUT):
    if f.endswith('.png'): os.remove(os.path.join(OUT, f))
now = datetime.datetime.now(datetime.timezone.utc).replace(tzinfo=None)
iso = lambda h: (now - datetime.timedelta(hours=h)).strftime('%Y-%m-%dT%H:%M:%SZ')
day = lambda d: (now + datetime.timedelta(days=d)).strftime('%Y-%m-%d')
P = [{'id': '104', 'title': 'Kapsaret Borehole', 'country': 'Kenya', 'ck': 'ke', 'area': 'Eldoret', 'type': 'Borehole well', 'status': 'Completed', 'gps': [0.47, 35.25]},
     {'id': '105', 'title': 'Magara Well', 'country': 'Tanzania', 'ck': 'tz', 'area': 'Babati', 'type': 'Borehole well', 'status': 'Completed', 'gps': [-4.2, 35.7]},
     {'id': '101', 'title': 'Chepkongi Dispensary', 'country': 'Kenya', 'ck': 'ke', 'area': 'Eldoret', 'type': 'Health', 'status': 'Completed', 'gps': [0.52, 35.28]},
     {'id': '107', 'title': 'Mzuzu Water Kiosk', 'country': 'Malawi', 'ck': 'mw', 'area': 'Mzuzu', 'type': 'Water collection', 'status': 'Completed', 'gps': [-11.45, 34.02]},
     {'id': '102', 'title': 'Nzoia Bridge Primary School – Rainwater Tanks and Gutters', 'country': 'Uganda', 'ck': 'ug', 'area': 'Tororo', 'type': 'School', 'status': 'Completed', 'gps': [0.69, 34.18]}]
REP = {'104': {'state': 'notworking', 'by': 'Bertin', 'at': iso(50), 't': 'Kapsaret Borehole', 'urgency': 'urgent', 'cost': 85000, 'parts': 'Rising main pipes, cylinder', 'problems': ['Handle broken', 'No water'], 'note': 'The handle came off on Monday', 'step': 'assigned', 'assignee': 'Meril Kiprop', 'due': day(-2), 'stepAt': {'assigned': iso(40)}},
       '105': {'state': 'repair', 'by': 'Amina', 'at': iso(90), 't': 'Magara Well', 'cost': 70000, 'step': 'assigned', 'assignee': 'Bertin Mwangi', 'approval': {'status': 'pending', 'rid': 'c1', 'by': 'Jessica', 'at': iso(5), 'est': 70000}},
       '101': {'state': 'repair', 'by': 'Grace', 'at': iso(30), 't': 'Chepkongi Dispensary', 'cost': 6000, 'step': 'assigned', 'assignee': 'Reid Anderson'},
       '107': {'state': 'repair', 'by': 'Grace', 'at': iso(300), 't': 'Mzuzu Water Kiosk', 'urgency': 'month', 'cost': 12000, 'budget': 15000, 'parts': 'Tap stands', 'step': 'parts', 'assignee': '', 'due': day(9)},
       '102': {'state': 'notworking', 'by': 'Okello', 'at': iso(8), 't': 'Nzoia Bridge Primary School – Rainwater Tanks and Gutters', 'urgency': 'urgent', 'cost': 64000, 'step': 'reported', 'approval': {'status': 'pending', 'rid': 'c2', 'by': 'Reid Anderson', 'at': iso(1), 'est': 64000}}}
def snap():
    return {'ok': True, 'who': 'Reid Anderson', 'admin': True, 'reviewOpen': 6, 'projects': P, 'repairs': json.loads(json.dumps(REP)), 'visits': {}, 'jobs': ['101'], 'assigned': [], 'confirmed': {},
            'me': {'kind': 'app', 'nick': '', 'phone': '', 'home': 'ke'}, 'approveAbove': 50000}
REVIEW = [{'rid': 'c1', 'kind': 'cost', 'id': '105', 't': 'Magara Well', 'by': 'Jessica', 'at': iso(5), 'status': 'open', 'data': {'est': 70000, 'step': 'parts', 'parts': 'New pump head', 'note': 'Quote from Babati Hardware'}},
          {'rid': 'c2', 'kind': 'cost', 'id': '102', 't': 'Nzoia Bridge Primary School – Rainwater Tanks and Gutters', 'by': 'Reid Anderson', 'at': iso(1), 'status': 'open', 'data': {'est': 64000, 'step': 'parts'}},
          {'rid': 'r1', 'kind': 'story', 'id': '101', 't': 'Chepkongi Dispensary', 'by': 'Meril Kiprop', 'at': iso(26), 'status': 'open', 'data': {'quote': 'Now the nurses can wash their hands between patients, and the children drink clean water at school.', 'who': 'Mary, nurse'}},
          {'rid': 'r2', 'kind': 'new', 'id': '109', 't': 'Kipsangui Spring', 'by': 'Bertin Mwangi', 'at': iso(70), 'status': 'open', 'data': {'type': 'Spring protection', 'village': 'Kipsangui', 'ck': 'ke', 'gps': '0.51234, 35.27011', 'note': 'Committee asked for a visit'}},
          {'rid': 'r3', 'kind': 'move', 'id': '104', 't': 'Kapsaret Borehole', 'by': 'Meril Kiprop', 'at': iso(100), 'status': 'open', 'data': {'from': '0.47000, 35.25000', 'to': '0.48120, 35.26310', 'dist': 1912, 'how': 'GPS on the phone, standing at the pump'}},
          {'rid': 'r4', 'kind': 'request', 'id': '107', 't': 'Mzuzu Water Kiosk', 'by': 'Grace Achieng', 'at': iso(150), 'status': 'open', 'data': {'from': '-11.45000, 34.02000', 'to': '-11.45310, 34.02240', 'dist': 430}},
          {'rid': 'd1', 'kind': 'new', 'id': '110', 't': 'Kaptebee Pigsty', 'by': 'Meril Kiprop', 'at': iso(400), 'status': 'approved', 'done': iso(20), 'doneBy': 'Jessica'},
          {'rid': 'd2', 'kind': 'move', 'id': '105', 't': 'Magara Well', 'by': 'Amina', 'at': iso(500), 'status': 'rejected', 'done': iso(40), 'doneBy': 'Reid Anderson', 'reason': 'Point was 2 km off'}]
TEAM = [{'name': 'Reid Anderson', 'admin': True, 'source': 'cloudflare', 'src': 'cloudflare', 'home': '', 'email': 'reid@rafikioutreach.org', 'phone': '+1 801 555 0101', 'lastSeen': iso(1), 'aka': []},
 {'name': 'Jessica Anderson', 'admin': True, 'source': 'app', 'src': 'app', 'home': '', 'email': 'jessica@rafikioutreach.org', 'phone': '', 'lastSeen': iso(30), 'aka': []},
 {'name': 'Meril Kiprop', 'source': 'app', 'src': 'app', 'home': 'ke', 'phone': '+254 712 345 678', 'role': 'Field officer', 'lastSeen': iso(2), 'aka': []},
 {'name': 'Bertin Mwangi', 'source': 'app', 'src': 'app', 'home': 'ke', 'phone': '+254 722 111 222', 'lastSeen': iso(26), 'aka': []},
 {'name': 'Grace Achieng', 'source': 'monday', 'src': 'monday', 'home': 'ug', 'phone': '', 'lastSeen': iso(200), 'aka': []},
 {'name': 'Amina Juma', 'source': 'app', 'src': 'app', 'home': 'tz', 'phone': '+255 754 000 111', 'lastSeen': None, 'aka': []},
 {'name': 'Okello Peter', 'source': 'app', 'src': 'app', 'home': 'ug', 'phone': '', 'lastSeen': iso(800), 'blocked': {'by': 'Reid Anderson', 'at': iso(100)}, 'aka': []},
 {'name': 'Chisomo Banda', 'source': 'app', 'src': 'app', 'home': 'mw', 'phone': '+265 99 123 4567', 'lastSeen': iso(5), 'aka': []}]
MODE = {'empty': False}
posts, photos, problems = [], [], []
def check(c, w): print(('ok   ' if c else 'FAIL ') + w); (None if c else problems.append(w))
def pin_api(route, req):
    if req.method == 'GET':
        u = req.url
        if 'activity=' in u: return route.fulfill(json={'ok': True, 'activity': []})
        if 'history=' in u: return route.fulfill(json={'ok': True, 'notes': []})
        if 'id=' in u: return route.fulfill(json={'ok': True, 'last': None, 'visits': [], 'costs': {'sum': 0, 'n': 0, 'last': []}})
        s = snap()
        if MODE['empty']: s['repairs'] = {}
        return route.fulfill(json=s)
    return route.fulfill(json={'ok': True})
def admin_api(route, req):
    u = req.url
    if req.method == 'GET':
        if 'view=review' in u: return route.fulfill(json={'ok': True, 'items': [] if MODE['empty'] else REVIEW})
        if 'view=team' in u: return route.fulfill(json={'ok': True, 'team': TEAM, 'mail': {'ready': True, 'to': ['reid@rafikioutreach.org', 'jessica@rafikioutreach.org']}, 'cron': True})
        return route.fulfill(json={'ok': True, 'items': []})
    b = json.loads(req.post_data or '{}'); posts.append(b)
    if b.get('action') == 'job' and b.get('id') == '104' and b.get('step') == 'parts':
        j = dict(REP['104']); j['approval'] = {'status': 'pending', 'rid': 'c3', 'by': 'Reid Anderson', 'at': iso(0), 'est': 85000}
        return route.fulfill(json={'ok': True, 'job': j, 'held': 'parts', 'asked': True, 'updateId': '8001'})
    if b.get('action') == 'job' and b.get('step') == 'fixed': return route.fulfill(json={'ok': True, 'job': None, 'held': '', 'asked': False, 'updateId': '8003'})
    if b.get('action') == 'job':
        j = dict(REP[b['id']]); j.update({'step': b.get('step') or j['step'], 'assignee': b.get('assignee', ''), 'due': b.get('due', '')})
        return route.fulfill(json={'ok': True, 'job': j, 'held': '', 'asked': False, 'updateId': '8002'})
    if b.get('action') == 'review' and b.get('rid') == 'c2': return route.fulfill(status=409, json={'ok': False, 'reason': 'own'})
    return route.fulfill(json={'ok': True})
def theme(pg, t): pg.evaluate("t => { localStorage.setItem('rfPinTheme', t); if (t) document.documentElement.setAttribute('data-theme', t); else document.documentElement.removeAttribute('data-theme'); }", t)
def login(ctx):
    pg = ctx.new_page(); errs = []; pg.on('pageerror', lambda e: errs.append(str(e)))
    pg.route('**/api/pin-auth/me', lambda r, q: r.fulfill(json={'available': False})); pg.route(re.compile(r'/api/pin(\?.*)?$'), pin_api)
    pg.route('**/api/pin-photo', lambda r, q: (photos.append(q.post_data_buffer[:4000]), r.fulfill(json={'ok': True}))[1])
    pg.route('**/api/admin**', admin_api)
    pg.route('**/tile.openstreetmap.org/**', lambda r, q: r.abort()); pg.route('**/arcgisonline.com/**', lambda r, q: r.abort()); pg.route('**/google.com/s2/**', lambda r, q: r.abort())
    pg.goto(BASE + '/pin'); pg.wait_for_timeout(800)
    for d in '9999': pg.click('.pk[data-k="%s"]' % d)
    pg.wait_for_timeout(1200); pg.evaluate("localStorage.setItem('rfPinOnb','1'); localStorage.setItem('rfPinOTab','overview')")
    return pg, errs
def office(pg, tab):
    pg.goto(BASE + '/pin#office'); pg.wait_for_timeout(900)
    pg.click('#oTabs [data-o=%s]' % tab); pg.wait_for_timeout(1300)
def sheet(pg): return pg.locator('.tsh .rr-sh')
with sync_playwright() as p:
    b = p.chromium.launch()
    for W, H, tag, th, sw in [(390, 844, 'phone', '', 0), (1280, 860, 'desktop', '', 0), (390, 844, 'dark', 'dark', 0), (390, 844, 'sun', 'sun', 0), (390, 844, 'sw', '', 1)]:
        ctx = b.new_context(viewport={'width': W, 'height': H}, device_scale_factor=2 if W < 500 else 1)
        pg, errs = login(ctx)
        theme(pg, th)
        if sw: pg.evaluate("localStorage.setItem('rfPinLang','sw')"); pg.reload(); pg.wait_for_timeout(1500)
        office(pg, 'team'); pg.wait_for_timeout(700)
        cards = pg.eval_on_selector_all('.tm-card', 'e => e.map(x => x.dataset.who)')
        check(len(cards) == 8, tag + ': 8 person cards')
        check(pg.inner_text('.tm-stat[data-tf=today] b').strip() == '3' and pg.inner_text('.tm-stat[data-tf=blk] b').strip() == '1' and pg.inner_text('.tm-stat[data-tf=inv] b').strip() == '1', tag + ': hero numbers')
        check(pg.locator('.tm-card[data-who="Okello Peter"] .tm-blk').count() == 1 and pg.locator('.tm-card[data-who="Okello Peter"] .tm-acts').count() == 0, tag + ': blocked card, no call bar')
        check(pg.get_attribute('.tm-card[data-who="Meril Kiprop"] .tm-cq.call', 'href') == 'tel:+254712345678' and 'wa.me/254712345678' in pg.get_attribute('.tm-card[data-who="Meril Kiprop"] .tm-cq.wa', 'href'), tag + ': Call and WhatsApp links')
        check(pg.locator('.tm-av.st-today').count() >= 3, tag + ': green ring on active today')
        check(pg.evaluate('document.documentElement.scrollWidth <= innerWidth'), tag + ': no sideways scroll')
        small = pg.eval_on_selector_all('.tm-row, #tmAdd, #tTest, .tm-chip, .tm-stat, .tm-cq', 'e => e.filter(x => x.offsetParent && x.getBoundingClientRect().height < 44).map(x => x.className)')
        check(not small, tag + ': touch targets 44px+ %s' % small)
        pg.screenshot(path=os.path.join(OUT, tag + '-1-list.png'), full_page=True)
        pg.click('.tm-chip[data-tf=blk]'); pg.wait_for_timeout(200)
        vis = pg.eval_on_selector_all('.tm-card', 'e => e.filter(x => !x.hidden).map(x => x.dataset.who)')
        check(vis == ['Okello Peter'] and pg.locator('.tm-sec.adm').is_hidden(), tag + ': Blocked chip %s' % vis)
        pg.click('.tm-stat[data-tf=today]'); pg.wait_for_timeout(200)
        vis = pg.eval_on_selector_all('.tm-card', 'e => e.filter(x => !x.hidden).map(x => x.dataset.who)')
        check(sorted(vis) == ['Chisomo Banda', 'Meril Kiprop', 'Reid Anderson'] and pg.get_attribute('.tm-chip[data-tf=today]', 'aria-pressed') == 'true', tag + ': today tile filters %s' % vis)
        pg.click('.tm-chip[data-tf=all]'); pg.fill('#tmQ', 'uganda'); pg.wait_for_timeout(200)
        vis = pg.eval_on_selector_all('.tm-card', 'e => e.filter(x => !x.hidden).map(x => x.dataset.who)')
        check(sorted(vis) == ['Grace Achieng', 'Okello Peter'], tag + ': search by country %s' % vis)
        pg.fill('#tmQ', '0101'); pg.wait_for_timeout(200)
        check(pg.eval_on_selector_all('.tm-card', 'e => e.filter(x => !x.hidden).map(x => x.dataset.who)') == ['Reid Anderson'], tag + ': search by phone')
        pg.fill('#tmQ', 'zzz'); pg.wait_for_timeout(200)
        check(pg.locator('.tm-none').is_visible(), tag + ': nobody matches')
        pg.fill('#tmQ', ''); pg.wait_for_timeout(200)
        if sw: check('Timu yako' in pg.inner_text('.tm-hero') and 'Ongeza mtu' in pg.inner_text('#tmAdd') and 'Hai leo' in pg.inner_text('.tm-chips'), tag + ': Kiswahili')
        pg.click('.tm-row[data-who="Meril Kiprop"]'); pg.wait_for_timeout(900)
        check(pg.locator('.tsh.in .ct-hd h3').inner_text() == 'Meril Kiprop', tag + ': sheet opens')
        check(pg.locator('.tsh .ct-qt').count() == 4 and pg.get_attribute('.tsh [data-testid=ct_call]', 'href') == 'tel:+254712345678', tag + ': quick tiles')
        cut = pg.eval_on_selector_all('.tsh .ct-qt b', 'e => e.filter(x => x.scrollWidth > x.clientWidth + 1).map(x => x.textContent)')
        check(not cut, tag + ': tile labels fit %s' % cut)
        pg.screenshot(path=os.path.join(OUT, tag + '-2-person.png'))
        pg.click('.tsh-x'); pg.wait_for_timeout(500)
        pg.click('.tm-row[data-who="Okello Peter"]'); pg.wait_for_timeout(900)
        check('Blocked' in pg.inner_text('.tsh .ct-hd') or 'Amezuiwa' in pg.inner_text('.tsh .ct-hd'), tag + ': blocked person sheet')
        if W > 500: pg.screenshot(path=os.path.join(OUT, tag + '-3-blocked.png'))
        pg.click('.tsh-x'); pg.wait_for_timeout(400)
        pg.click('#tmAdd'); pg.wait_for_timeout(700)
        check(pg.locator('.tsh [data-f=name]').count() == 1, tag + ': Add a person opens')
        check(not errs, tag + ': no page errors %s' % errs[:2])
        ctx.close()
    b.close()
print('PROBLEMS', problems); import sys; sys.exit(1 if problems else 0)
