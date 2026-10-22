# Office › Overview, Costs and Visits, made calm and interactive (ocv): a dashboard of tappable tiles (each opens its list,
# tab or a sheet), costs with the big numbers and a 12-month chart first, rows that open a sheet with the project's cost
# history and the approval limit folded away, and assigned visits as cards with filters (person, overdue, done) that open
# a sheet to reassign, cancel or open the project; the assign form lives in its own sheet.
# Light, dark and sun; Kiswahili; 390 and 1280 wide; screenshots to qa/pin/ocv/.  Run with the site on BASE (default :8852).
import json, os, re, datetime, sys
from playwright.sync_api import sync_playwright
HERE = os.path.dirname(os.path.abspath(__file__)); BASE = os.environ.get('BASE', 'http://localhost:8852'); OUT = os.path.join(HERE, 'ocv'); os.makedirs(OUT, exist_ok=True)
LOOK = os.environ.get('LOOK')
now = datetime.datetime.now(datetime.timezone.utc).replace(tzinfo=None)
iso = lambda h: (now - datetime.timedelta(hours=h)).strftime('%Y-%m-%dT%H:%M:%SZ')
day = lambda d: (now + datetime.timedelta(days=d)).strftime('%Y-%m-%d')
P = [{'id': '104', 'title': 'Kapsaret Borehole', 'country': 'Kenya', 'ck': 'ke', 'area': 'Eldoret', 'type': 'Borehole well', 'status': 'Completed', 'gps': [0.47, 35.25]},
     {'id': '105', 'title': 'Magara Well', 'country': 'Tanzania', 'ck': 'tz', 'area': 'Babati', 'type': 'Borehole well', 'status': 'Completed', 'gps': [-4.2, 35.7]},
     {'id': '101', 'title': 'Chepkongi Dispensary', 'country': 'Kenya', 'ck': 'ke', 'area': 'Eldoret', 'type': 'Health', 'status': 'Completed', 'gps': [0.52, 35.28]},
     {'id': '107', 'title': 'Mzuzu Water Kiosk', 'country': 'Malawi', 'ck': 'mw', 'area': 'Mzuzu', 'type': 'Water collection', 'status': 'Completed', 'gps': [-11.45, 34.02]},
     {'id': '108', 'title': 'Nzoia Bridge Primary School - 6 Classrooms and a Water Tank', 'country': 'Kenya', 'ck': 'ke', 'area': 'Bungoma', 'type': 'School', 'status': 'Completed', 'gps': [0.56, 34.6]},
     {'id': '109', 'title': 'Kiptoo Well', 'country': 'Kenya', 'ck': 'ke', 'area': 'Nandi', 'type': 'Borehole well', 'status': 'Completed'},
     {'id': '110', 'title': 'Busia Spring', 'country': 'Uganda', 'ck': 'ug', 'area': 'Busia', 'type': 'Spring', 'status': 'Completed', 'gps': [0.46, 34.09]},
     {'id': '111', 'title': 'Turbo Spring', 'country': 'Kenya', 'ck': 'ke', 'area': 'Turbo', 'type': 'Spring', 'status': 'Completed', 'gps': [0.63, 35.05]}]
months = [(now.replace(day=1) - datetime.timedelta(days=30 * i)).strftime('%Y-%m') for i in range(11, -1, -1)]
PEOPLE = {'total': 48250, 'counted': 61, 'measured': 23, 'byCountry': {'ke': 26400, 'tz': 12850, 'ug': 3100, 'mw': 5900},
          'byType': {'water': 39800, 'school': 5200, 'health': 2400, 'children': 850},
          'trend': [{'m': m, 'n': (None if i < 2 else 41000 + i * 650)} for i, m in enumerate(months[:-1])] + [{'m': months[-1], 'n': 48250}]}
REP = {'104': {'state': 'notworking', 'by': 'Bertin', 'at': iso(50), 't': 'Kapsaret Borehole', 'urgency': 'urgent', 'cost': 85000, 'parts': 'Rising main pipes', 'step': 'assigned', 'assignee': 'Meril Kiprop'},
       '105': {'state': 'repair', 'by': 'Amina', 'at': iso(90), 't': 'Magara Well', 'cost': 7000, 'step': 'assigned', 'assignee': 'Bertin Mwangi'}}
VIS = {'104': {'at': iso(24 * 40), 'state': 'notworking', 'by': 'Meril'}, '101': {'at': iso(24 * 300), 'state': 'working', 'by': 'Grace'}, '105': {'at': iso(24 * 20), 'state': 'repair', 'by': 'Amina'}, '110': {'at': iso(24 * 10), 'state': 'working', 'by': 'Grace'}}
def snap():
    return {'ok': True, 'who': 'Reid Anderson', 'admin': True, 'reviewOpen': 2, 'projects': P, 'repairs': json.loads(json.dumps(REP)), 'visits': VIS, 'jobs': [], 'assigned': [], 'confirmed': {},
            'me': {'kind': 'app', 'nick': '', 'phone': '', 'home': 'ke'}, 'approveAbove': 50000, 'people': PEOPLE}
ROWS = [{'id': '104', 'rid': iso(400), 'at': iso(20), 'what': 'Pump cylinder', 'est': 30000, 'actual': 28500, 'by': 'Meril Kiprop', 'step': 'parts', 'receipt': True, 't': 'Kapsaret Borehole'},
        {'id': '104', 'rid': iso(400), 'at': iso(10), 'what': 'Fitting', 'est': 30000, 'actual': 3500, 'by': 'Meril Kiprop', 'step': 'fixed', 't': 'Kapsaret Borehole'},
        {'id': '104', 'rid': iso(24 * 200), 'at': iso(24 * 190), 'what': 'Handle bearings', 'est': 6000, 'actual': 5200, 'by': 'Bertin Mwangi', 'step': 'fixed', 't': 'Kapsaret Borehole'},
        {'id': '105', 'rid': iso(2000), 'at': iso(1900), 'what': 'Handle and pins', 'est': 9000, 'actual': 8200, 'by': 'Bertin Mwangi', 'step': 'fixed', 't': 'Magara Well'},
        {'id': '107', 'rid': iso(3000), 'at': iso(2900), 'what': 'Tap stands', 'est': 12000, 'actual': 11800, 'by': 'Grace Achieng', 'step': 'fixed', 't': 'Mzuzu Water Kiosk'},
        {'id': '108', 'rid': iso(24 * 120), 'at': iso(24 * 110), 'what': 'Gutters and tank tap', 'est': 15000, 'actual': 14600, 'by': 'Grace Achieng', 'step': 'fixed', 't': 'Nzoia Bridge Primary School - 6 Classrooms and a Water Tank'}]
STATS = {'month': 32000, 'year': 72000, 'sum12': 77200, 'repairs': 5, 'avg': 15440, 'byCountry': {'ke': 51800, 'tz': 8200, 'mw': 11800}, 'monthKey': months[-1], 'yearKey': now.year,
         'top': [{'id': '104', 't': 'Kapsaret Borehole', 'ck': 'ke', 'sum': 37200, 'n': 2}, {'id': '108', 't': 'Nzoia Bridge Primary School - 6 Classrooms and a Water Tank', 'ck': 'ke', 'sum': 14600, 'n': 1},
                 {'id': '107', 't': 'Mzuzu Water Kiosk', 'ck': 'mw', 'sum': 11800, 'n': 1}, {'id': '105', 't': 'Magara Well', 'ck': 'tz', 'sum': 8200, 'n': 1}]}
TEAM = [{'name': 'Reid Anderson', 'admin': True, 'source': 'cloudflare'}, {'name': 'Meril Kiprop', 'source': 'app', 'home': 'ke'}, {'name': 'Bertin Mwangi', 'source': 'app', 'home': 'ke'}, {'name': 'Grace Achieng', 'source': 'app', 'home': 'tz'}]
ASSIGN = [{'aid': 'a1', 'id': '104', 't': 'Kapsaret Borehole', 'to': 'Meril Kiprop', 'date': day(-3), 'note': 'Check the pump and meet the committee', 'by': 'Reid Anderson', 'at': iso(24 * 6)},
          {'aid': 'a2', 'id': '108', 't': 'Nzoia Bridge Primary School - 6 Classrooms and a Water Tank', 'to': 'Bertin Mwangi', 'date': day(0), 'note': '', 'by': 'Reid Anderson', 'at': iso(30)},
          {'aid': 'a3', 'id': '105', 't': 'Magara Well', 'to': 'Grace Achieng', 'date': day(4), 'note': 'Water test', 'by': 'Jessica', 'at': iso(12)},
          {'aid': 'a4', 'id': '111', 't': 'Turbo Spring', 'to': 'Meril Kiprop', 'date': day(9), 'note': '', 'by': 'Reid Anderson', 'at': iso(5)},
          {'aid': 'a5', 'id': '110', 't': 'Busia Spring', 'to': 'Grace Achieng', 'date': day(-12), 'note': '', 'by': 'Reid Anderson', 'at': iso(24 * 15), 'done': iso(24 * 10)},
          {'aid': 'a6', 'id': '101', 't': 'Chepkongi Dispensary', 'to': 'Meril Kiprop', 'date': day(-20), 'note': '', 'by': 'Reid Anderson', 'at': iso(24 * 25), 'done': iso(24 * 19)}]
ACT = [{'by': 'Meril Kiprop', 'a': 'visit', 's': 'notworking', 'id': '104', 't': 'Kapsaret Borehole', 'at': iso(3)}, {'by': 'Grace Achieng', 'a': 'visit', 's': 'working', 'id': '110', 't': 'Busia Spring', 'at': iso(26)},
       {'by': 'Jessica', 'a': 'approve', 'id': '105', 't': 'Magara Well', 'at': iso(30)}, {'by': 'Reid Anderson', 'a': 'assign', 't': '2 visits for Meril Kiprop', 'at': iso(40)},
       {'by': 'Bertin Mwangi', 'a': 'pin', 'id': '108', 't': 'Nzoia Bridge Primary School - 6 Classrooms and a Water Tank', 'at': iso(50)}, {'by': 'Meril Kiprop', 'a': 'fixed', 'id': '101', 't': 'Chepkongi Dispensary', 'at': iso(70)},
       {'by': 'Grace Achieng', 'a': 'new', 'id': '112', 't': 'Lilongwe Borehole', 'at': iso(90)}]
posts = []
problems = []
state = {'assign': json.loads(json.dumps(ASSIGN)), 'n': 0}
def check(c, w): print(('ok   ' if c else 'FAIL ') + w); (None if c else problems.append(w))
def pin_api(route, req):
    if req.method == 'GET':
        u = req.url
        if 'activity=' in u: return route.fulfill(json={'ok': True, 'activity': list(reversed(ACT))})
        if 'team=' in u: return route.fulfill(json={'ok': True, 'team': []})
        if 'history=' in u: return route.fulfill(json={'ok': True, 'notes': []})
        if 'id=' in u: return route.fulfill(json={'ok': True, 'last': None, 'visits': [], 'costs': {'sum': 0, 'n': 0, 'last': []}})
        return route.fulfill(json=snap())
    b = json.loads(req.post_data or '{}'); posts.append(b)
    return route.fulfill(json={'ok': True})
def admin_api(route, req):
    u = req.url
    if req.method == 'GET':
        if 'view=costs' in u: return route.fulfill(json={'ok': True, 'rows': ROWS, 'stats': STATS, 'approveAbove': 50000})
        if 'view=team' in u: return route.fulfill(json={'ok': True, 'team': TEAM, 'mail': {}, 'cron': False})
        if 'view=assign' in u: return route.fulfill(json={'ok': True, 'items': state['assign']})
        if 'view=review' in u: return route.fulfill(json={'ok': True, 'items': []})
        return route.fulfill(json={'ok': True, 'items': []})
    b = json.loads(req.post_data or '{}'); posts.append(b)
    if b.get('action') == 'settings': return route.fulfill(json={'ok': True, 'approveAbove': int(str(b['approveAbove']).replace(',', ''))})
    if b.get('action') == 'assign':
        made = []
        for pid in b['ids']:
            state['n'] += 1; p = next((x for x in P if x['id'] == pid), {})
            a = {'aid': 'n%d' % state['n'], 'id': pid, 't': p.get('title', ''), 'to': b['to'], 'date': b['date'], 'note': b.get('note', ''), 'by': 'Reid Anderson', 'at': iso(0)}
            state['assign'].append(a); made.append(a)
        return route.fulfill(json={'ok': True, 'items': made})
    if b.get('action') == 'unassign':
        state['assign'] = [a for a in state['assign'] if a['aid'] != b['aid']]
        return route.fulfill(json={'ok': True})
    return route.fulfill(json={'ok': True})
def theme(pg, t): pg.evaluate("t => { localStorage.setItem('rfPinTheme', t); if (t) document.documentElement.setAttribute('data-theme', t); else document.documentElement.removeAttribute('data-theme'); }", t)
def login(ctx):
    pg = ctx.new_page(); errs = []; pg.on('pageerror', lambda e: errs.append(str(e)))
    pg.route('**/api/pin-auth/me', lambda r, q: r.fulfill(json={'available': False})); pg.route(re.compile(r'/api/pin(\?.*)?$'), pin_api)
    pg.route('**/api/admin**', admin_api)
    pg.route('**/tile.openstreetmap.org/**', lambda r, q: r.abort()); pg.route('**/arcgisonline.com/**', lambda r, q: r.abort()); pg.route('**/google.com/s2/**', lambda r, q: r.abort())
    pg.goto(BASE + '/pin'); pg.wait_for_timeout(800)
    for d in '9999': pg.click('.pk[data-k="%s"]' % d)
    pg.wait_for_timeout(1200); pg.evaluate("localStorage.setItem('rfPinOnb','1'); localStorage.setItem('rfPinOTab','overview')")
    return pg, errs
def office(pg, tab):
    pg.goto(BASE + '/pin#office'); pg.wait_for_timeout(900)
    pg.click('#oTabs [data-o=%s]' % tab); pg.wait_for_timeout(1400)
def body_shot(pg, name):
    # the Office body only, from its top: the tabs grid above it is someone else's
    pg.evaluate("document.querySelector('#oBody').scrollIntoView()"); pg.evaluate('scrollBy(0,-70)'); pg.wait_for_timeout(1300)
    pg.screenshot(path=os.path.join(OUT, name + '.png'))
def full(pg, name):
    pg.wait_for_timeout(1300); pg.locator('#oBody').screenshot(path=os.path.join(OUT, name + '.png'))
def sheet(pg): return pg.locator('.tsh:not(.out)')
def sheet_shot(pg, name): pg.wait_for_timeout(700); pg.screenshot(path=os.path.join(OUT, name + '.png'))
def close_sheet(pg):
    pg.locator('.tsh:not(.out) .tsh-x').click(); pg.wait_for_timeout(450)
def no_overflow(pg, sel, what):
    bad = pg.evaluate("""s => [...document.querySelectorAll(s)].filter(e => e.offsetParent && e.scrollWidth > e.clientWidth + 1).map(e => e.className + ': ' + e.textContent.trim().slice(0, 40))""", sel)
    check(not bad, what + ' %s' % bad[:3])

if LOOK:
    with sync_playwright() as p:
        br = p.chromium.launch()
        for w, h in ((390, 844), (1280, 900)):
            ctx = br.new_context(viewport={'width': w, 'height': h}, device_scale_factor=2 if w < 500 else 1, is_mobile=w < 500, has_touch=w < 500, service_workers='block')
            pg, errs = login(ctx)
            for t in LOOK.split(','):
                office(pg, t); full(pg, 'look-%s-%d' % (t, w))
            print(errs)
        br.close()
    sys.exit(0)

def page_ok(pg, what):
    w = pg.evaluate('[document.documentElement.scrollWidth, innerWidth]')
    check(w[0] <= w[1], what + ': no sideways scroll %s' % w)

with sync_playwright() as p:
    br = p.chromium.launch()
    ctx = br.new_context(viewport={'width': 390, 'height': 844}, device_scale_factor=2, is_mobile=True, has_touch=True, service_workers='block', accept_downloads=True)
    pg, errs = login(ctx)
    # ---------------- Overview
    office(pg, 'overview')
    check(pg.locator('[data-testid=ocv_overview_head]').count() == 1, 'overview: a compact head')
    check(pg.locator('.ocv-tile').count() == 6, 'overview: six tiles (repairs, review, due, tests, visits, people)')
    check(pg.locator('.tank').count() == 0 and pg.locator('.ofb-people').count() == 0, 'overview: no big tank, people served folded into a tile')
    check('7 of 8 projects on the map' in pg.inner_text('[data-testid=ocv_mapped]') and '1 still needs a location' in pg.inner_text('[data-testid=ocv_mapped]'), 'on-the-map strip')
    t = pg.inner_text('[data-testid=ocv_t_repairs]'); check('2' in t and '1 urgent' in t and 'KES 92,000' in t, 'repairs tile: count, urgent, estimate: ' + t.replace('\n', ' '))
    pg.wait_for_timeout(800)
    check(pg.inner_text('#oVm') == '2', 'visits this month counted from the activity: ' + pg.inner_text('#oVm'))
    check(pg.locator('.ocv-feed li').count() == 5 and pg.locator('[data-ocvgo=feedmore]').count() == 1, 'latest from the field: 5, then Show more')
    pg.click('[data-ocvgo=feedmore]'); pg.wait_for_timeout(200)
    check(pg.locator('.ocv-feed li').count() == 7, 'show more: all 7')
    page_ok(pg, 'overview 390')
    pg.evaluate('scrollTo(0,0)'); body_shot(pg, '01-overview-light')
    pg.locator('[data-testid=ocv_c_ke]').scroll_into_view_if_needed(); pg.wait_for_timeout(500); pg.screenshot(path=os.path.join(OUT, '01b-overview-countries.png'))
    # tiles open their tab or list
    pg.click('[data-testid=ocv_t_repairs]'); pg.wait_for_timeout(700)
    check(pg.get_attribute('#oTabs [data-o=repairs]', 'aria-pressed') == 'true', 'repairs tile opens Repairs')
    pg.click('#oTabs [data-o=overview]'); pg.wait_for_timeout(700)
    pg.click('[data-testid=ocv_t_review]'); pg.wait_for_timeout(700)
    check(pg.get_attribute('#oTabs [data-o=review]', 'aria-pressed') == 'true', 'review tile opens Review')
    pg.click('#oTabs [data-o=overview]'); pg.wait_for_timeout(700)
    pg.click('[data-testid=ocv_t_visits]'); pg.wait_for_timeout(900)
    check(pg.get_attribute('#oTabs [data-o=visits]', 'aria-pressed') == 'true', 'visits tile opens Visits')
    pg.click('#oTabs [data-o=overview]'); pg.wait_for_timeout(700)
    pg.click('[data-testid=ocv_t_due]'); pg.wait_for_timeout(700)
    check(pg.is_visible('#vList') and pg.get_attribute('.chips .chip[data-f=due]', 'aria-pressed') == 'true', 'due tile opens Projects › Due a visit')
    office(pg, 'overview'); pg.click('[data-testid=ocv_mapped]'); pg.wait_for_timeout(700)
    check(pg.is_visible('#vList') and pg.get_attribute('.chips .chip[data-f=missing]', 'aria-pressed') == 'true', 'on-the-map strip opens Needs a location')
    office(pg, 'overview')
    pg.click('[data-testid=ocv_t_people]'); pg.wait_for_timeout(1500)
    check(sheet(pg).locator('.ofb-people').count() == 1 and pg.inner_text('#ofbPeople') == '48,250', 'people tile: a sheet with people served, counted up')
    t = sheet(pg).inner_text(); check('Kenya' in t and '26,400' in t and 'Water points' in t and '+5,950 in 12 months' in t, 'people sheet: split by country and type, 12-month change')
    sheet_shot(pg, '02-people-sheet'); close_sheet(pg)
    pg.click('[data-testid=ocv_c_ke]'); pg.wait_for_timeout(700)
    t = sheet(pg).inner_text(); check('Kenya' in t and '5 projects' in t and '4 of 5' in t, 'country sheet: Kenya, 5 projects, 4 of 5 on the map')
    sheet_shot(pg, '03-country-sheet')
    pg.click('[data-testid=ocv_country_all]'); pg.wait_for_timeout(800)
    check(pg.is_visible('#vList') and pg.input_value('#iCountry') == 'ke' and pg.get_attribute('.chips .chip[data-f=all]', 'aria-pressed') == 'true', 'Show these projects: Projects, Kenya, All')
    pg.evaluate("localStorage.removeItem('rfPinCountry')")
    # ---------------- Costs
    office(pg, 'costs')
    t = pg.inner_text('[data-testid=ocv_costs_head]')
    check('KES32,000' in t.replace(' ', '').replace('\n', '') and '72,000' in t and '77,200' in t and '15,440' in t and '5 repairs' in t, 'costs head: month, year, 12 months, average')
    check(pg.locator('.ocv-bar').count() == 12 and pg.get_attribute('.ocv-bar:last-child', 'aria-pressed') == 'true', 'chart: 12 months, this month chosen')
    check('KES 32,000' in pg.inner_text('.ocv-ch-sel') and '2 entries' in pg.inner_text('.ocv-ch-sel'), 'chart caption: this month: ' + pg.inner_text('.ocv-ch-sel'))
    jul = pg.locator('.ocv-bar').nth(8); jul.click(); pg.wait_for_timeout(200)
    check(jul.get_attribute('aria-pressed') == 'true' and ('KES' in pg.inner_text('.ocv-ch-sel')) and pg.inner_text('.ocv-ch-sel') != '', 'tap a bar: that month shown: ' + pg.inner_text('.ocv-ch-sel'))
    check(pg.locator('[data-testid=ocv_cost]').count() == 6, 'latest spending: 6 cards')
    check(pg.locator('.ocv-rc-ic').count() == 1, 'receipt mark on the one with a receipt')
    lim = pg.locator('[data-testid=ocv_limit_toggle]')
    check(lim.get_attribute('aria-expanded') == 'false' and pg.locator('#ocvSec_limit[inert]').count() == 1 and 'Above KES 50,000' in lim.inner_text(), 'second approval folded away, summary shows the limit')
    page_ok(pg, 'costs 390')
    pg.evaluate('scrollTo(0,0)'); body_shot(pg, '04-costs-light')
    pg.locator('[data-testid=ocv_cost]').first.scroll_into_view_if_needed(); pg.wait_for_timeout(400); pg.screenshot(path=os.path.join(OUT, '04b-costs-list.png'))
    pg.locator('[data-testid=ocv_cost]').nth(1).click(); pg.wait_for_timeout(900)
    t = sheet(pg).inner_text()
    check('Kapsaret Borehole' in t and 'KES 37,200' in t and sheet(pg).locator('.ocv-hist li').count() == 3 and 'Pump cylinder' in t, 'cost sheet: total for 12 months and the 3 entries')
    check(sheet(pg).locator('[data-testid=ocv_cost_open]').count() == 1, 'cost sheet: open project')
    sheet_shot(pg, '05-cost-sheet')
    pg.click('[data-testid=ocv_cost_open]'); pg.wait_for_timeout(1200)
    check(pg.is_visible('#vProj'), 'open project from the cost sheet')
    office(pg, 'costs')
    pg.click('[data-testid=ocv_cf_top]'); pg.wait_for_timeout(500)
    check(pg.locator('[data-testid=ocv_top]').count() == 4 and 'Kapsaret Borehole' in pg.inner_text('[data-testid=ocv_top] >> nth=0'), 'most spent: 4, most expensive first')
    pg.locator('[data-testid=ocv_top]').nth(1).click(); pg.wait_for_timeout(700)
    check('KES 14,600' in sheet(pg).inner_text(), 'top card opens its sheet'); close_sheet(pg)
    lim.click(); pg.wait_for_timeout(500)
    check(lim.get_attribute('aria-expanded') == 'true' and pg.is_visible('#ofbLim') and pg.input_value('#ofbLim') == '50000', 'tap: the setting opens')
    pg.locator('.ocv-sec.open').scroll_into_view_if_needed(); pg.wait_for_timeout(300); pg.screenshot(path=os.path.join(OUT, '06-costs-setting-open.png'))
    pg.fill('#ofbLim', '75000'); pg.click('#ofbLimGo'); pg.wait_for_timeout(600)
    check(any(b.get('action') == 'settings' and str(b.get('approveAbove')) == '75000' for b in posts) and 'KES 75,000' in pg.inner_text('#ofbLimMsg') and 'Above KES 75,000' in lim.inner_text(), 'setting saved, summary updated')
    pg.fill('#ofbLim', '50000'); pg.click('#ofbLimGo'); pg.wait_for_timeout(500)
    pg.click('[data-testid=ocv_csv_toggle]'); pg.wait_for_timeout(400)
    with pg.expect_download() as dl: pg.click('#ocvSec_csv [data-x=costs]')
    data = open(dl.value.path(), encoding='utf-8-sig').read()
    check(data.startswith('Date,Project ID,Project,Country') and data.count('\n') == 6, 'costs CSV from the folded Download')
    # ---------------- Visits
    office(pg, 'visits'); pg.wait_for_timeout(400)
    check(pg.locator('[data-testid=ocv_visit]').count() == 4, 'planned: 4 cards')
    t = pg.inner_text('[data-testid=ocv_visit] >> nth=0'); check('Kapsaret Borehole' in t and 'Overdue' in t and '3 days late' in t and 'Meril Kiprop' in t, 'overdue first: ' + t.replace('\n', ' | '))
    check(pg.locator('#aTo').count() == 0 and pg.locator('[data-testid=ocv_assign]').count() == 1, 'assign form moved into a sheet')
    page_ok(pg, 'visits 390')
    pg.evaluate('scrollTo(0,0)'); body_shot(pg, '07-visits-light')
    pg.click('[data-testid=ocv_vf_late]'); pg.wait_for_timeout(300)
    check(pg.locator('[data-testid=ocv_visit]').count() == 1, 'overdue filter: 1')
    pg.click('[data-testid=ocv_vf_done]'); pg.wait_for_timeout(300)
    check(pg.locator('[data-testid=ocv_visit]').count() == 2 and 'Done' in pg.inner_text('[data-testid=ocv_visit] >> nth=0'), 'done filter: 2')
    pg.screenshot(path=os.path.join(OUT, '08-visits-done.png'))
    pg.click('[data-testid=ocv_vf_open]'); pg.wait_for_timeout(300)
    pg.locator('[data-testid=ocv_vp][data-who="Meril Kiprop"]').click(); pg.wait_for_timeout(300)
    check(pg.locator('[data-testid=ocv_visit]').count() == 2 and all('Meril Kiprop' in x for x in pg.locator('[data-testid=ocv_visit]').all_inner_texts()), 'person filter: Meril’s 2')
    pg.click('[data-testid=ocv_vf_today]'); pg.wait_for_timeout(300)
    check(pg.locator('[data-testid=ocv_visit]').count() == 0 and 'Nothing here for Meril Kiprop' in pg.inner_text('#oBody'), 'empty state for the filter')
    pg.click('[data-testid=ocv_vp_all]'); pg.click('[data-testid=ocv_vf_open]'); pg.wait_for_timeout(300)
    # tap a visit: reassign
    pg.locator('[data-ocvv=a1]').click(); pg.wait_for_timeout(800)
    t = sheet(pg).inner_text(); check('Kapsaret Borehole' in t and 'Check the pump' in t and 'Reid Anderson' in t and sheet(pg).locator('[data-testid=ocv_v_open]').count() == 1, 'visit sheet: details and Open project')
    sheet_shot(pg, '09-visit-sheet')
    pg.click('[data-testid=ocv_v_re]'); pg.wait_for_timeout(400)
    sheet(pg).locator('[data-f=to]').select_option('Bertin Mwangi'); sheet(pg).locator('[data-f=date]').fill(day(1))
    sheet_shot(pg, '09b-visit-reassign')
    pg.click('[data-testid=ocv_v_re_go]'); pg.wait_for_timeout(1500)
    a = [b for b in posts if b.get('action') == 'assign'][-1]; u = [b for b in posts if b.get('action') == 'unassign']
    check(a == {'action': 'assign', 'ids': ['104'], 'to': 'Bertin Mwangi', 'date': day(1), 'note': 'Check the pump and meet the committee'} and u and u[-1] == {'action': 'unassign', 'aid': 'a1'}, 'reassign: assigned to Bertin, old one removed %s %s' % (a, u[-1:]))
    check(sheet(pg).count() == 0 and pg.locator('[data-ocvv=a1]').count() == 0 and 'Bertin Mwangi' in pg.inner_text('[data-testid=ocv_visit] >> nth=0'), 'sheet closed, list refreshed')
    # cancel, with a confirmation
    pg.locator('[data-ocvv=a3]').click(); pg.wait_for_timeout(700)
    n0 = len(posts); pg.click('[data-testid=ocv_v_rm]'); pg.wait_for_timeout(400)
    check(len(posts) == n0 and sheet(pg).locator('.tm-ask').count() == 1, 'cancel asks first')
    sheet_shot(pg, '10-visit-cancel-ask')
    sheet(pg).locator('.tm-ask [data-k=yes]').click(); pg.wait_for_timeout(1300)
    check(posts[-1] == {'action': 'unassign', 'aid': 'a3'} and pg.locator('[data-ocvv=a3]').count() == 0, 'cancelled and gone from the list')
    # assign a visit, in its sheet
    pg.click('[data-testid=ocv_assign]'); pg.wait_for_timeout(800)
    check(sheet(pg).locator('#aTo').count() == 1 and sheet(pg).locator('#aList .row').count() >= 2, 'assign sheet: person, date, projects')
    pg.select_option('#aTo', 'Grace Achieng'); pg.select_option('#aF', 'all'); pg.select_option('#aC', '')
    pg.locator('#aList [data-pick="107"]').click(); pg.locator('#aList [data-pick="110"]').click(); pg.fill('#aNote', 'Water test and meet the committee')
    check(pg.inner_text('#aGo') == 'Assign 2 visits' and pg.is_enabled('#aGo'), 'Assign 2 visits')
    sheet_shot(pg, '11-assign-sheet')
    pg.click('#aGo'); pg.wait_for_timeout(1800)
    a = [b for b in posts if b.get('action') == 'assign'][-1]
    check(sorted(a['ids']) == ['107', '110'] and a['to'] == 'Grace Achieng' and a['note'] == 'Water test and meet the committee', 'assign posted %s' % a)
    check(sheet(pg).count() == 0 and pg.locator('[data-testid=ocv_visit]').count() == 5, 'sheet closed, 5 planned now')
    # ---------------- dark and sun
    for th in ('dark', 'sun'):
        theme(pg, th)
        office(pg, 'overview'); pg.evaluate('scrollTo(0,0)'); body_shot(pg, '12-overview-' + th)
        office(pg, 'costs'); pg.click('[data-testid=ocv_cf_latest]'); pg.wait_for_timeout(300); pg.evaluate('scrollTo(0,0)'); body_shot(pg, '13-costs-' + th)
        pg.locator('[data-testid=ocv_cost]').nth(1).click(); sheet_shot(pg, '13b-cost-sheet-' + th); close_sheet(pg)
        office(pg, 'visits'); pg.evaluate('scrollTo(0,0)'); body_shot(pg, '14-visits-' + th)
        pg.locator('[data-testid=ocv_visit]').first.click(); sheet_shot(pg, '14b-visit-sheet-' + th); close_sheet(pg)
    theme(pg, '')
    # ---------------- Kiswahili
    pg.evaluate("localStorage.setItem('rfPinLang', 'sw')"); pg.reload(); pg.wait_for_timeout(1500)
    office(pg, 'overview'); t = pg.inner_text('#oBody')
    check('Muhtasari' in t and 'matengenezo yaliyo wazi' in t and 'kwenye ramani' in t and 'Kwa nchi' in t, 'overview in Kiswahili')
    no_overflow(pg, '.ocv-tile, .ocv-n, .ocv-crow, .ocv-map', 'overview sw: nothing spills'); page_ok(pg, 'overview sw')
    pg.evaluate('scrollTo(0,0)'); body_shot(pg, '15-overview-sw')
    pg.click('[data-testid=ocv_c_tz]'); sheet_shot(pg, '15b-country-sheet-sw'); close_sheet(pg)
    office(pg, 'costs'); t = pg.inner_text('#oBody')
    check('Gharama za ukarabati' in t and 'Matumizi kila mwezi' in t and 'Idhini ya pili' in t and 'Kwa nchi, mwaka huu' in t, 'costs in Kiswahili')
    no_overflow(pg, '.ocv-n, .ocv-card, .ocv-row, .ocv-bar small', 'costs sw: nothing spills'); page_ok(pg, 'costs sw')
    pg.evaluate('scrollTo(0,0)'); body_shot(pg, '16-costs-sw')
    pg.click('[data-testid=ocv_limit_toggle]'); pg.wait_for_timeout(400); pg.locator('.ocv-sec.open').scroll_into_view_if_needed(); pg.screenshot(path=os.path.join(OUT, '16b-costs-setting-sw.png'))
    office(pg, 'visits'); t = pg.inner_text('#oBody')
    check('Gawa ziara' in t and 'Zimepangwa' in t and 'Zimechelewa' in t, 'visits in Kiswahili')
    no_overflow(pg, '.ocv-n, .ocv-card, .chip', 'visits sw: nothing spills'); page_ok(pg, 'visits sw')
    pg.evaluate('scrollTo(0,0)'); body_shot(pg, '17-visits-sw')
    pg.locator('[data-testid=ocv_visit]').first.click(); pg.click('[data-testid=ocv_v_re]'); sheet_shot(pg, '17b-visit-sheet-sw'); close_sheet(pg)
    pg.click('[data-testid=ocv_assign]'); sheet_shot(pg, '17c-assign-sheet-sw'); close_sheet(pg)
    pg.evaluate("localStorage.removeItem('rfPinLang')")
    check(not errs, 'no page errors (390) %s' % errs[:3])
    # ---------------- desktop
    ctx2 = br.new_context(viewport={'width': 1280, 'height': 900}, service_workers='block')
    pg2, errs2 = login(ctx2)
    for t in ('overview', 'costs', 'visits'):
        office(pg2, t); pg2.evaluate('scrollTo(0,0)'); body_shot(pg2, '18-desktop-' + t); page_ok(pg2, t + ' 1280')
    pg2.locator('[data-testid=ocv_visit]').first.click(); sheet_shot(pg2, '19-desktop-visit-sheet'); close_sheet(pg2)
    pg2.click('[data-testid=ocv_assign]'); sheet_shot(pg2, '19b-desktop-assign-sheet'); close_sheet(pg2)
    office(pg2, 'costs'); pg2.locator('[data-testid=ocv_cost]').first.click(); sheet_shot(pg2, '19c-desktop-cost-sheet'); close_sheet(pg2)
    office(pg2, 'overview'); pg2.click('[data-testid=ocv_t_people]'); pg2.wait_for_timeout(900); sheet_shot(pg2, '19d-desktop-people-sheet')
    check(not errs2, 'no page errors (1280) %s' % errs2[:3])
    br.close()
print('PROBLEMS', problems)
