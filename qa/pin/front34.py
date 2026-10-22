# Office money and reach: repair costs (Office › Costs, actual cost + receipt on the job card and the tracker,
# the per-project line, CSV export), people served on the overview (count-up, country and type split, 12-month
# sparkline) and the two-step approval for big repairs (held step, Review › Approve/Decline, own request blocked).
# Light, dark and sun; Kiswahili; screenshots to qa/pin/costs/.  Run with the site on BASE (default :8815).
import json, os, re, datetime
from playwright.sync_api import sync_playwright
HERE = os.path.dirname(os.path.abspath(__file__)); BASE = os.environ.get('BASE', 'http://localhost:8815'); OUT = os.path.join(HERE, 'costs'); os.makedirs(OUT, exist_ok=True)
now = datetime.datetime.now(datetime.timezone.utc).replace(tzinfo=None)
iso = lambda h: (now - datetime.timedelta(hours=h)).strftime('%Y-%m-%dT%H:%M:%SZ')
P = [{'id': '104', 'title': 'Kapsaret Borehole', 'country': 'Kenya', 'ck': 'ke', 'area': 'Eldoret', 'type': 'Borehole well', 'status': 'Completed', 'gps': [0.47, 35.25]},
     {'id': '105', 'title': 'Magara Well', 'country': 'Tanzania', 'ck': 'tz', 'area': 'Babati', 'type': 'Borehole well', 'status': 'Completed', 'gps': [-4.2, 35.7]},
     {'id': '101', 'title': 'Chepkongi Dispensary', 'country': 'Kenya', 'ck': 'ke', 'area': 'Eldoret', 'type': 'Health', 'status': 'Completed', 'gps': [0.52, 35.28]},
     {'id': '107', 'title': 'Mzuzu Water Kiosk', 'country': 'Malawi', 'ck': 'mw', 'area': 'Mzuzu', 'type': 'Water collection', 'status': 'Completed', 'gps': [-11.45, 34.02]}]
months = [(now.replace(day=1) - datetime.timedelta(days=30 * i)).strftime('%Y-%m') for i in range(11, -1, -1)]
PEOPLE = {'total': 48250, 'counted': 61, 'measured': 23, 'byCountry': {'ke': 26400, 'tz': 12850, 'ug': 3100, 'mw': 5900},
          'byType': {'water': 39800, 'school': 5200, 'health': 2400, 'children': 850},
          'trend': [{'m': m, 'n': (None if i < 2 else 41000 + i * 650)} for i, m in enumerate(months[:-1])] + [{'m': months[-1], 'n': 48250}]}
REP = {'104': {'state': 'notworking', 'by': 'Bertin', 'at': iso(50), 't': 'Kapsaret Borehole', 'urgency': 'urgent', 'cost': 85000, 'parts': 'Rising main pipes, cylinder', 'step': 'assigned', 'assignee': 'Meril Kiprop', 'stepAt': {'assigned': iso(40)}},
       '105': {'state': 'repair', 'by': 'Amina', 'at': iso(90), 't': 'Magara Well', 'cost': 70000, 'step': 'assigned', 'assignee': 'Bertin Mwangi', 'approval': {'status': 'pending', 'rid': 'c1', 'by': 'Jessica', 'at': iso(5), 'est': 70000}},
       '101': {'state': 'repair', 'by': 'Grace', 'at': iso(30), 't': 'Chepkongi Dispensary', 'cost': 6000, 'step': 'assigned', 'assignee': 'Reid Anderson'}}
def snap(admin=True, who='Reid Anderson'):
    return {'ok': True, 'who': who, 'admin': admin, 'reviewOpen': 2, 'projects': P, 'repairs': json.loads(json.dumps(REP)), 'visits': {}, 'jobs': ['101'] if who == 'Reid Anderson' else ['104'], 'assigned': [], 'confirmed': {},
            'me': {'kind': 'app', 'nick': '', 'phone': '', 'home': 'ke'}, 'approveAbove': 50000, **({'people': PEOPLE} if admin else {})}
ROWS = [{'id': '104', 'rid': iso(400), 'at': iso(20), 'what': 'Pump cylinder', 'est': 30000, 'actual': 28500, 'by': 'Meril Kiprop', 'step': 'parts', 'receipt': True, 't': 'Kapsaret Borehole'},
        {'id': '104', 'rid': iso(400), 'at': iso(10), 'what': 'Fitting', 'est': 30000, 'actual': 3500, 'by': 'Meril Kiprop', 'step': 'fixed', 't': 'Kapsaret Borehole'},
        {'id': '105', 'rid': iso(2000), 'at': iso(1900), 'what': 'Handle and pins', 'est': 9000, 'actual': 8200, 'by': 'Bertin Mwangi', 'step': 'fixed', 't': 'Magara Well'},
        {'id': '107', 'rid': iso(3000), 'at': iso(2900), 'what': 'Tap stands', 'est': 12000, 'actual': 11800, 'by': 'Grace Achieng', 'step': 'fixed', 't': 'Mzuzu Water Kiosk'}]
STATS = {'month': 32000, 'year': 52000, 'sum12': 52000, 'repairs': 3, 'avg': 17333, 'byCountry': {'ke': 32000, 'tz': 8200, 'mw': 11800}, 'monthKey': months[-1], 'yearKey': now.year,
         'top': [{'id': '104', 't': 'Kapsaret Borehole', 'ck': 'ke', 'sum': 32000, 'n': 1}, {'id': '107', 't': 'Mzuzu Water Kiosk', 'ck': 'mw', 'sum': 11800, 'n': 1}, {'id': '105', 't': 'Magara Well', 'ck': 'tz', 'sum': 8200, 'n': 1}]}
REVIEW = [{'rid': 'c1', 'kind': 'cost', 'id': '105', 't': 'Magara Well', 'by': 'Jessica', 'at': iso(5), 'status': 'open', 'data': {'est': 70000, 'step': 'parts', 'parts': 'New pump head', 'note': 'Quote from Babati Hardware'}},
          {'rid': 'c2', 'kind': 'cost', 'id': '104', 't': 'Kapsaret Borehole', 'by': 'Reid Anderson', 'at': iso(1), 'status': 'open', 'data': {'est': 85000, 'step': 'parts', 'parts': 'Rising main pipes, cylinder'}}]
posts, photos = [], []
problems = []
def check(c, w): print(('ok   ' if c else 'FAIL ') + w); (None if c else problems.append(w))
def pin_api(admin, who):
    def h(route, req):
        if req.method == 'GET':
            u = req.url
            if 'activity=' in u: return route.fulfill(json={'ok': True, 'activity': [{'by': 'Jessica', 'a': 'approve', 'k': 'cost', 'id': '105', 't': 'Magara Well', 'at': iso(0.2)}, {'by': 'Reid Anderson', 'a': 'ask', 'k': 'cost', 'id': '104', 't': 'Kapsaret Borehole', 'at': iso(0.4)}]})
            if 'team=' in u: return route.fulfill(json={'ok': True, 'team': []})
            if 'history=' in u: return route.fulfill(json={'ok': True, 'notes': []})
            if 'id=104' in u: return route.fulfill(json={'ok': True, 'last': None, 'visits': [], 'costs': {'sum': 36500, 'n': 3, 'last': [ROWS[1]]}})
            if 'id=' in u: return route.fulfill(json={'ok': True, 'last': None, 'visits': [], 'costs': {'sum': 0, 'n': 0, 'last': []}})
            return route.fulfill(json=snap(admin, who))
        b = json.loads(req.post_data or '{}'); posts.append(b)
        if b.get('action') == 'parts' and b.get('id') == '104':
            j = dict(REP['104']); j['approval'] = {'status': 'pending', 'rid': 'c9', 'by': who, 'at': iso(0), 'est': 85000}
            return route.fulfill(json={'ok': True, 'id': '104', 'held': 'parts', 'asked': True, 'job': j})
        if b.get('action') == 'parts':
            j = dict(REP[b['id']]); j['step'] = 'parts'; j['stepAt'] = {'parts': iso(0)}
            return route.fulfill(json={'ok': True, 'id': b['id'], 'updateId': '7001', 'job': j})
        return route.fulfill(json={'ok': True, 'id': b.get('id'), 'updateId': '7002'})
    return h
def admin_api(route, req):
    u = req.url
    if req.method == 'GET':
        if 'view=costs' in u: return route.fulfill(json={'ok': True, 'rows': ROWS, 'stats': STATS, 'approveAbove': 50000})
        if 'view=review' in u: return route.fulfill(json={'ok': True, 'items': REVIEW})
        if 'view=team' in u: return route.fulfill(json={'ok': True, 'team': [{'name': 'Reid Anderson', 'admin': True, 'source': 'cloudflare'}, {'name': 'Meril Kiprop', 'source': 'app'}], 'mail': {}, 'cron': False})
        return route.fulfill(json={'ok': True, 'items': []})
    b = json.loads(req.post_data or '{}'); posts.append(b)
    if b.get('action') == 'job' and b.get('id') == '104' and b.get('step') == 'parts':
        j = dict(REP['104']); j['approval'] = {'status': 'pending', 'rid': 'c3', 'by': 'Reid Anderson', 'at': iso(0), 'est': 85000}
        return route.fulfill(json={'ok': True, 'job': j, 'held': 'parts', 'asked': True, 'updateId': '8001'})
    if b.get('action') == 'job':
        j = dict(REP[b['id']]); j['step'] = b.get('step') or j['step']
        return route.fulfill(json={'ok': True, 'job': j, 'held': '', 'asked': False, 'updateId': '8002'})
    if b.get('action') == 'settings': return route.fulfill(json={'ok': True, 'approveAbove': int(str(b['approveAbove']).replace(',', ''))})
    if b.get('action') == 'review' and b.get('rid') == 'c2': return route.fulfill(status=409, json={'ok': False, 'reason': 'own'})
    return route.fulfill(json={'ok': True})
def theme(pg, t): pg.evaluate("t => { localStorage.setItem('rfPinTheme', t); if (t) document.documentElement.setAttribute('data-theme', t); else document.documentElement.removeAttribute('data-theme'); }", t)
def login(ctx, admin=True, who='Reid Anderson'):
    pg = ctx.new_page(); errs = []; pg.on('pageerror', lambda e: errs.append(str(e)))
    pg.route('**/api/pin-auth/me', lambda r, q: r.fulfill(json={'available': False})); pg.route(re.compile(r'/api/pin(\?.*)?$'), pin_api(admin, who))
    pg.route('**/api/pin-photo', lambda r, q: (photos.append(q.post_data_buffer[:4000]), r.fulfill(json={'ok': True}))[1])
    pg.route('**/api/admin**', admin_api)
    pg.route('**/tile.openstreetmap.org/**', lambda r, q: r.abort()); pg.route('**/arcgisonline.com/**', lambda r, q: r.abort()); pg.route('**/google.com/s2/**', lambda r, q: r.abort())
    pg.goto(BASE + '/pin'); pg.wait_for_timeout(800)
    for d in '9999': pg.click('.pk[data-k="%s"]' % d)
    pg.wait_for_timeout(1200); pg.evaluate("localStorage.setItem('rfPinOnb','1'); localStorage.setItem('rfPinOTab','overview')")
    return pg, errs
def office(pg, tab):
    pg.goto(BASE + '/pin#office'); pg.wait_for_timeout(900)
    pg.click('#oTabs [data-o=%s]' % tab); pg.wait_for_timeout(1400)
RECEIPT = os.path.join(os.path.dirname(HERE), '..', 'img', 'letterhead', 'rafiki-logo.jpg')
if not os.path.exists(RECEIPT): RECEIPT = next(os.path.join(dp, f) for dp, _, fs in os.walk(os.path.join(HERE, '..', '..', 'img')) for f in fs if f.endswith('.jpg'))

with sync_playwright() as p:
    br = p.chromium.launch()
    ctx = br.new_context(viewport={'width': 390, 'height': 844}, device_scale_factor=2, is_mobile=True, has_touch=True, service_workers='block', accept_downloads=True)
    pg, errs = login(ctx)
    # --- people served on the overview
    office(pg, 'overview')
    check(pg.locator('#oTabs [data-o=costs]').count() == 1, 'Office has a Costs tab')
    # people served: a tile on the overview (Office › Overview, ocv), the details in a sheet
    check(pg.locator('[data-testid=ocv_t_people]').count() == 1 and '48,250' in pg.inner_text('[data-testid=ocv_t_people]'), 'people served tile on the overview')
    pg.click('[data-testid=ocv_t_people]'); pg.wait_for_timeout(1500)
    check(pg.locator('.tsh .ofb-people').count() == 1, 'people served card in its sheet')
    check(pg.inner_text('#ofbPeople') == '48,250', 'people served counts up to 48,250: ' + pg.inner_text('#ofbPeople'))
    t = pg.inner_text('.ofb-people')
    check('Kenya' in t and '26,400' in t and 'Malawi' in t and '5,900' in t, 'split by country')
    check('Water points' in t and '39,800' in t and 'Schools' in t, 'split by type')
    check('+5,950 in 12 months' in t, '12-month change: ' + re.sub(r'\s+', ' ', t)[:200])
    check(pg.locator('.ofb-spark .ln').count() == 1 and pg.locator('.ofb-spark .dt').count() == 1, 'sparkline drawn')
    check(pg.eval_on_selector('.ofb-people .ofb-bar em', 'e => parseFloat(e.style.width)') == 100.0, 'country bars grow')
    pg.wait_for_timeout(800); pg.locator('.tsh .ofb-people').screenshot(path=os.path.join(OUT, '01-people-served-light.png'))
    pg.click('.tsh .tsh-x'); pg.wait_for_timeout(400)
    # --- Costs
    pg.click('#oTabs [data-o=costs]'); pg.wait_for_timeout(1600)
    t = pg.inner_text('#oBody')
    check('KES 32,000' in t and 'KES 52,000' in t and 'KES 17,333' in t and 'average per repair' in t and '3 repairs' in t, 'tiles: month, year, average')
    check(pg.locator('[data-testid=ocv_cost]').count() == 4 and pg.locator('.ocv-rc-ic').count() == 1, 'latest spending with receipt mark')
    check('Tanzania' in t and 'KES 8,200' in t, 'by country this year')
    pg.evaluate('scrollTo(0,0)'); pg.wait_for_timeout(300); pg.screenshot(path=os.path.join(OUT, '02-costs-light.png'), full_page=True)
    pg.click('[data-testid=ocv_cf_top]'); pg.wait_for_timeout(400)
    check(pg.locator('[data-testid=ocv_top]').count() == 3 and 'Kapsaret Borehole' in pg.inner_text('[data-testid=ocv_top] >> nth=0'), 'top water points, most expensive first')
    pg.click('[data-testid=ocv_cf_latest]'); pg.wait_for_timeout(300)
    pg.click('[data-testid=ocv_limit_toggle]'); pg.wait_for_timeout(400)
    check(pg.input_value('#ofbLim') == '50000', 'second approval setting (folded away) shows 50,000')
    pg.fill('#ofbLim', '75000'); pg.click('#ofbLimGo'); pg.wait_for_timeout(600)
    check(any(b.get('action') == 'settings' and str(b.get('approveAbove')) == '75000' for b in posts) and 'KES 75,000' in pg.inner_text('#ofbLimMsg'), 'setting saved: ' + pg.inner_text('#ofbLimMsg'))
    pg.fill('#ofbLim', '50000'); pg.click('#ofbLimGo'); pg.wait_for_timeout(500)
    pg.click('[data-testid=ocv_csv_toggle]'); pg.wait_for_timeout(400)
    with pg.expect_download() as dl: pg.click('#ocvSec_csv [data-x=costs]')
    data = open(dl.value.path(), encoding='utf-8-sig').read()
    check(data.startswith('Date,Project ID,Project,Country') and 'Kapsaret Borehole,Kenya' in data and ',28500,' in data and data.count('\n') == 4, 'costs CSV from the Costs view')
    # --- Repairs: the second approval on the job card
    pg.click('#oTabs [data-o=repairs]'); pg.wait_for_timeout(1200)
    big = pg.locator('.ojob[data-id="104"]'); pend = pg.locator('.ojob[data-id="105"]'); small = pg.locator('.ojob[data-id="101"]')
    check('Needs a second approval before parts are ordered' in big.inner_text() and 'KES 85,000 is above KES 50,000' in big.inner_text(), 'big repair: needs a second approval')
    check('Waiting for a second approval · asked by Jessica' in pend.inner_text(), 'pending: waiting, asked by Jessica')
    check(small.locator('.ofb-appr').count() == 0, 'small repair: no approval needed')
    big.locator('[data-step=parts]').click(); big.locator('[data-save]').click(); pg.wait_for_timeout(900)
    check('Asked ✓' in big.locator('.msg').inner_text() and big.locator('[data-step=assigned]').get_attribute('aria-checked') == 'true', 'parts held, asked, step back to Assigned')
    check('Waiting for a second approval · asked by Reid Anderson' in big.inner_text(), 'card now shows the pending approval')
    big.scroll_into_view_if_needed(); pg.wait_for_timeout(300); big.screenshot(path=os.path.join(OUT, '03-job-held-for-approval.png'))
    small.locator('[data-step=parts]').click(); small.locator('[data-f=actual]').fill('5400'); small.locator('[data-f=note]').fill('Tap washers from Eldoret')
    small.locator('input[data-rc]').set_input_files(RECEIPT); pg.wait_for_timeout(700)
    check('Receipt ready' in small.inner_text(), 'receipt picked, shown')
    n0 = len(photos); small.locator('[data-save]').click(); pg.wait_for_timeout(1200)
    jb = [b for b in posts if b.get('action') == 'job' and b.get('id') == '101'][-1]
    check(jb.get('actual') == '5400' and jb.get('receipt') is True and jb.get('what') == 'Tap washers from Eldoret' and jb.get('step') == 'parts', 'job saved with actual cost and receipt: %s' % jb)
    check(len(photos) == n0 + 1 and b'8002' in photos[-1], 'receipt photo sent to the monday.com note')
    small.screenshot(path=os.path.join(OUT, '04-job-actual-cost.png'))
    # --- Review: approve someone else's cost; your own is blocked
    pg.click('#oTabs [data-o=review]'); pg.wait_for_timeout(1300)
    c1 = pg.locator('.orev[data-rid=c1]'); c2 = pg.locator('.orev[data-rid=c2]')
    check('Second approval: repair cost' in c1.inner_text() and 'KES 70,000' in c1.inner_text() and 'Quote from Babati Hardware' in c1.inner_text(), 'cost review card')
    check(c1.locator('[data-reason]').is_visible() and c1.locator('[data-dec=approve]').is_enabled(), 'note box open; can approve someone else’s')
    check(c2.locator('[data-dec=approve]').is_disabled() and 'another admin approves it' in c2.inner_text(), 'own request: approve is off')
    pg.evaluate('scrollTo(0,0)'); pg.wait_for_timeout(200); pg.screenshot(path=os.path.join(OUT, '05-review-cost-light.png'), full_page=True)
    c1.locator('[data-reason] input').fill('Checked the quote'); c1.locator('[data-dec=approve]').click(); pg.wait_for_timeout(700)
    rv = [b for b in posts if b.get('action') == 'review'][-1]
    check(rv == {'action': 'review', 'rid': 'c1', 'decision': 'approve', 'reason': 'Checked the quote'}, 'approve with a note: %s' % rv)
    # --- Export
    pg.click('#oTabs [data-o=export]'); pg.wait_for_timeout(900)
    check(pg.locator('[data-x=costs]').count() == 1 and pg.locator('[data-x=costsum]').count() == 1, 'export: costs and summary buttons')
    with pg.expect_download() as dl: pg.click('[data-x=costsum]')
    data = open(dl.value.path(), encoding='utf-8-sig').read()
    check('Top 10 water points' in data and 'People served,Total,48250' in data and 'Average per repair' in data, 'summary CSV: costs and people served')
    pg.screenshot(path=os.path.join(OUT, '06-export.png'))
    # --- the project page: tracker and cost line
    pg.goto(BASE + '/pin#p/104'); pg.wait_for_timeout(1800)
    check('Repairs: KES 36,500 in the last 12 months (3 repairs)' in pg.inner_text('#ofbPc'), 'project cost line: ' + pg.inner_text('#ofbPc'))
    check('Needs a second approval' in pg.inner_text('#pRepair') and pg.inner_text('#ofbParts').endswith('Parts ordered: ask for approval'), 'tracker: approval needed, admin can ask')
    pg.click('#ofbParts'); pg.wait_for_timeout(1200)
    check(any(b.get('action') == 'parts' and b.get('id') == '104' for b in posts), 'parts posted from the tracker')
    check('Waiting for a second approval · asked by Reid Anderson' in pg.inner_text('#pRepair') and 'Sent for a second approval' in pg.inner_text('#pRepair'), 'tracker shows the pending approval')
    check(pg.locator('#ofbParts').count() == 0, 'no parts button while it waits')
    pg.locator('#pRepair .trk').scroll_into_view_if_needed(); pg.wait_for_timeout(500); pg.evaluate("document.querySelector('.tabs') && (document.querySelector('.tabs').style.visibility='hidden')"); pg.locator('#pRepair .trk').screenshot(path=os.path.join(OUT, '07-tracker-waiting-light.png')); pg.locator('#ofbPc').screenshot(path=os.path.join(OUT, '07b-project-cost-line.png')); pg.evaluate("document.querySelector('.tabs') && (document.querySelector('.tabs').style.visibility='')")
    # the small repair on Reid's own job: parts with cost; then fixed with cost and receipt
    pg.goto(BASE + '/pin#p/101'); pg.wait_for_timeout(1600)
    check('Repairs: nothing spent in the last 12 months' in pg.inner_text('#ofbPc'), 'no spend line')
    pg.click('#ofbParts'); pg.wait_for_timeout(200); pg.fill('#ofbPartsCost', '2,000'.replace(',', '')); pg.fill('#ofbPartsWhat', 'Washers'); pg.click('#ofbParts'); pg.wait_for_timeout(1200)
    pb = [b for b in posts if b.get('action') == 'parts' and b.get('id') == '101'][-1]
    check(pb.get('actual') == '2000' and pb.get('what') == 'Washers', 'parts with cost: %s' % pb)
    check(pg.locator('#ofbParts').count() == 0 and 'Parts ordered' in pg.inner_text('#pRepair'), 'step moved on, parts button gone')
    pg.click('#bFixed'); pg.wait_for_timeout(200); pg.fill('#fixNote', 'Replaced the tap'); pg.fill('#ofbFixCost', '800'); pg.set_input_files('input[data-rc="fix101"]', RECEIPT); pg.wait_for_timeout(600)
    pg.locator('#fixBox').scroll_into_view_if_needed(); pg.screenshot(path=os.path.join(OUT, '08-fixed-with-cost.png'))
    n0 = len(photos); pg.click('#bFixed'); pg.wait_for_timeout(1500)
    fb = [b for b in posts if b.get('action') == 'fixed'][-1]
    check(fb.get('actual') == '800' and fb.get('receipt') is True and fb.get('what') == 'Replaced the tap', 'fixed with actual cost + receipt: %s' % fb)
    check(len(photos) == n0 + 1, 'receipt sent with the fixed note')
    # --- dark and sun
    theme(pg, 'dark'); office(pg, 'overview'); pg.wait_for_timeout(600)
    pg.click('[data-testid=ocv_t_people]'); pg.wait_for_timeout(1500); pg.locator('.tsh .tsh-p').screenshot(path=os.path.join(OUT, '09-people-served-dark.png')); pg.click('.tsh .tsh-x'); pg.wait_for_timeout(400)
    pg.click('#oTabs [data-o=costs]'); pg.wait_for_timeout(1600); pg.evaluate('scrollTo(0,0)'); pg.screenshot(path=os.path.join(OUT, '10-costs-dark.png'), full_page=True)
    pg.click('#oTabs [data-o=repairs]'); pg.wait_for_timeout(900); pg.locator('.ojob[data-id="105"]').screenshot(path=os.path.join(OUT, '11-job-pending-dark.png'))
    theme(pg, 'sun'); pg.goto(BASE + '/pin#p/105'); pg.wait_for_timeout(1600); pg.locator('#pRepair').scroll_into_view_if_needed(); pg.wait_for_timeout(400)
    pg.screenshot(path=os.path.join(OUT, '12-tracker-pending-sun.png'))
    theme(pg, '')
    # --- Kiswahili
    pg.evaluate("localStorage.setItem('rfPinLang', 'sw')"); pg.reload(); pg.wait_for_timeout(1500); office(pg, 'costs'); pg.wait_for_timeout(500)
    t = pg.inner_text('#oBody'); check('Idhini ya pili' in t and 'wastani kwa kila ukarabati' in t and 'Kwa nchi, mwaka huu' in t, 'Costs in Kiswahili')
    pg.screenshot(path=os.path.join(OUT, '13-costs-sw.png'), full_page=True)
    pg.click('#oTabs [data-o=overview]'); pg.wait_for_timeout(900); pg.click('[data-testid=ocv_t_people]'); pg.wait_for_timeout(900); check('Watu wanaohudumiwa' in pg.inner_text('.tsh .ofb-people'), 'people served in Kiswahili'); pg.click('.tsh .tsh-x'); pg.wait_for_timeout(400)
    pg.evaluate("localStorage.removeItem('rfPinLang')")
    check(not errs, 'no page errors (admin) %s' % errs[:3])
    # --- a field officer (not an admin) on the job: no people card data, parts from the tracker asks for approval
    ctx2 = br.new_context(viewport={'width': 390, 'height': 844}, device_scale_factor=2, is_mobile=True, has_touch=True, service_workers='block')
    pg2, errs2 = login(ctx2, admin=False, who='Meril Kiprop')
    pg2.goto(BASE + '/pin#p/104'); pg2.wait_for_timeout(1600)
    check(pg2.locator('#ofbParts').count() == 1, 'the person on the job sees Parts ordered')
    pg2.goto(BASE + '/pin#p/105'); pg2.wait_for_timeout(1400)
    check(pg2.locator('#ofbParts').count() == 0 and 'Waiting for a second approval · asked by Jessica' in pg2.inner_text('#pRepair'), 'someone else’s job: status only')
    pg2.locator('#pRepair').scroll_into_view_if_needed(); pg2.wait_for_timeout(400); pg2.screenshot(path=os.path.join(OUT, '14-field-tracker-pending.png'))
    check(not errs2, 'no page errors (field) %s' % errs2[:3])
    br.close()
print('PROBLEMS', problems)
