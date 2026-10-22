# Live team map (privacy first), Share my location in Settings, and Office › Plan a week.
# Needs the app served on PORT (default 8814): PORT=8814 node server.js
import json, os, re, datetime
from playwright.sync_api import sync_playwright
HERE = os.path.dirname(os.path.abspath(__file__)); BASE = 'http://localhost:' + os.environ.get('PORT', '8814'); OUT = os.path.join(HERE, 'teammap'); os.makedirs(OUT, exist_ok=True)
# a Wednesday morning in Nairobi: inside the default work hours (Mon–Sat 07:00–18:00)
NOW = datetime.datetime(2026, 10, 21, 7, 30, tzinfo=datetime.timezone.utc)   # 10:30 in Nairobi
iso = lambda m: (NOW - datetime.timedelta(minutes=m)).strftime('%Y-%m-%dT%H:%M:%SZ')
def proj(i, t, area, gps, typ='Borehole well'): return {'id': str(i), 'title': t, 'country': 'Kenya', 'ck': 'ke', 'area': area, 'type': typ, 'status': 'Completed', 'gps': gps}
P = [proj(101, 'Kapsaret Borehole', 'Kapsaret, Eldoret', [0.47, 35.25]), proj(102, 'Chepkongi Dispensary', 'Kapsaret, Eldoret', [0.514, 35.27]), proj(103, 'Turbo Spring', 'Turbo', [0.63, 35.05], 'Water collection'),
     proj(104, 'Sigowet Church Well', 'Sigowet', [-0.27, 35.15]), proj(105, 'Kitale Primary Tank', 'Kitale', [1.015, 35.0]), proj(106, 'Kiminini Borehole', 'Kitale', [0.9, 34.92]),
     proj(107, 'Bungoma Bridge School', 'Bungoma', [0.563, 34.56], 'School'), proj(108, 'Webuye Well', 'Bungoma', [0.61, 34.77]), proj(109, 'Moi’s Bridge Kiosk', 'Eldoret', [0.87, 35.12]),
     proj(110, 'Ziwa Borehole', 'Eldoret', [0.75, 35.3]), proj(111, 'Lessos Dispensary', 'Lessos', None, 'Health'),
     # for Areas and projects: Kapsaret (8, one with no location), Kaigat (3), Ndalat (2), and long area names
     proj(121, 'Kapsaret Primary School', 'Kapsaret, Eldoret', [0.48, 35.26], 'School'), proj(122, 'Simat Water Kiosk', 'Kapsaret, Eldoret', [0.49, 35.24], 'Water collection'),
     proj(123, 'Kapsaret Girls Secondary', 'Kapsaret, Eldoret', [0.46, 35.27], 'School'), proj(124, 'Lemook Spring', 'Kapsaret, Eldoret', [0.45, 35.23], 'Water collection'),
     proj(125, 'Kapsaret Market Tank', 'Kapsaret, Eldoret', [0.475, 35.255]), proj(127, 'Kapsaret Health Centre', 'Kapsaret, Eldoret', None, 'Health'),
     proj(131, 'Kaigat Borehole', 'Kaigat, Nandi', [0.31, 35.2]), proj(132, 'Kaigat Primary School', 'Kaigat, Nandi', [0.32, 35.21], 'School'), proj(133, 'Kaigat Dispensary', 'Kaigat, Nandi', [0.3, 35.19], 'Health'),
     proj(141, 'Ndalat Well', 'Ndalat', [0.2, 35.1]), proj(142, 'Ndalat Church Tank', 'Ndalat', [0.21, 35.11]),
     proj(151, 'Elgon Estate Primary Borehole', 'Elgon Estate Primary', [0.9, 34.7]), proj(152, 'Kapsabaot Comprehensive School Rainwater Harvesting Tank', 'Kapsabaot Comprehensive School', [0.95, 35.08], 'School')]
REP = {'104': {'state': 'notworking', 'by': 'Bertin', 'at': iso(3000), 'urgency': 'urgent', 't': 'Sigowet Church Well'}, '107': {'state': 'repair', 'by': 'Grace', 'at': iso(9000), 't': 'Bungoma Bridge School'}}
VIS = {'101': {'at': iso(400 * 1440), 'by': 'Meril', 'state': 'working'}, '102': {'at': iso(20 * 1440), 'by': 'Meril', 'state': 'working'}}
TEAM = [{'name': 'Meril Kiprop', 'home': 'ke', 'admin': False, 'source': 'app', 'phone': '+254 712 345 678'}, {'name': 'Bertin Mwangi', 'home': 'ke', 'admin': False, 'source': 'app'}, {'name': 'Grace Achieng', 'home': 'ke', 'admin': False, 'source': 'app'}, {'name': 'Reid Anderson', 'admin': True, 'source': 'cloudflare'}]
TM = {'ok': True, 'people': [{'name': 'Bertin Mwangi', 'lat': 0.52, 'lng': 35.28, 'acc': 12, 'at': iso(12), 'phone': '0722 111 222', 'home': 'ke'},
                             {'name': 'Grace Achieng', 'lat': 0.58, 'lng': 34.6, 'acc': 30, 'at': iso(95), 'phone': '', 'home': 'ke'},
                             {'name': 'Meril Kiprop', 'lat': 1.0, 'lng': 35.02, 'acc': 8, 'at': iso(3), 'phone': '+254 712 345 678', 'home': 'ke'}],
      'visits': [{'id': '102', 'by': 'Bertin Mwangi', 'at': iso(70), 's': 'working', 't': 'Chepkongi Dispensary'}, {'id': '101', 'by': 'Bertin Mwangi', 'at': iso(150), 's': 'repair', 't': 'Kapsaret Borehole'},
                 {'id': '107', 'by': 'Grace Achieng', 'at': iso(120), 's': 'notworking', 't': 'Bungoma Bridge School'}]}
def stop(i, why, age=None, chosen=False): p = next(x for x in P if x['id'] == str(i)); return dict({'id': p['id'], 't': p['title'], 'area': p['area'], 'gps': p['gps'], 'why': why, 'score': 50, 'age': age}, **({'chosen': True} if chosen else {}))
PLAN = {'ok': True, 'total': 9, 'days': [{'stops': [stop(104, ['repair', 'urgent'], 3), stop(101, ['overdue'], 400)], 'km': 82.1},
                                          {'stops': [stop(107, ['repair']), stop(108, ['never'])], 'km': 24.0},
                                          {'stops': [stop(105, ['never', 'test']), stop(106, ['never'])], 'km': 15.2}],
        'spare': [stop(109, ['never']), stop(110, ['never']), stop(103, ['never'])]}
# projects chosen by hand (ids=…) in Kapsaret: only these, or with suggestions filling the days; one has no location
PLAN_ONLY = {'ok': True, 'total': 2, 'chosen': 2, 'only': True, 'days': [{'stops': [stop(101, ['overdue'], 400, True), stop(102, [], 20, True)], 'km': 5.4}], 'spare': [], 'nogps': [{'id': '127', 't': 'Kapsaret Health Centre'}]}
PLAN_MIX = {'ok': True, 'total': 9, 'chosen': 2, 'only': False, 'days': [{'stops': [stop(101, ['overdue'], 400, True), stop(124, ['never'])], 'km': 3.1}, {'stops': [stop(102, [], 20, True), stop(131, ['never'])], 'km': 22.6}], 'spare': [stop(132, ['never'])], 'nogps': [{'id': '127', 't': 'Kapsaret Health Centre'}]}
posts, adm, problems = [], [], []
def check(c, w): print(('ok   ' if c else 'FAIL ') + w); (None if c else problems.append(w))
state = {'admin': True, 'who': 'Meril Kiprop'}
def api(route, req):
    if req.method == 'GET':
        if 'activity=' in req.url: return route.fulfill(json={'ok': True, 'activity': []})
        if 'team=' in req.url: return route.fulfill(json={'ok': True, 'team': []})
        return route.fulfill(json={'ok': True, 'who': state['who'], 'admin': state['admin'], 'reviewOpen': 0, 'projects': P, 'repairs': REP, 'visits': VIS, 'jobs': [], 'assigned': [], 'confirmed': {}, 'me': {'kind': 'app', 'nick': '', 'phone': '', 'home': 'ke'}})
    b = json.loads(req.post_data); posts.append(b); route.fulfill(json={'ok': True})
def admin(route, req):
    adm.append(req.method + ' ' + req.url + ' ' + (req.post_data or ''))
    if req.method == 'GET':
        if 'view=teammap' in req.url: return route.fulfill(json=TM)
        if 'view=plan' in req.url: return route.fulfill(json=(PLAN_ONLY if 'only=1' in req.url else PLAN_MIX) if 'ids=' in req.url else PLAN)
        if 'view=team' in req.url: return route.fulfill(json={'ok': True, 'team': TEAM, 'mail': {'ready': False}, 'cron': False})
        if 'view=assign' in req.url: return route.fulfill(json={'ok': True, 'items': []})
        return route.fulfill(json={'ok': True, 'items': []})
    b = json.loads(req.post_data)
    if b.get('action') == 'assign': return route.fulfill(json={'ok': True, 'items': [{'aid': 'a' + i, 'id': i} for i in b['ids']]})
    route.fulfill(json={'ok': True})
def shot(pg, name, full=False): pg.wait_for_timeout(350); pg.screenshot(path=os.path.join(OUT, name), full_page=full)
with sync_playwright() as p:
    br = p.chromium.launch()
    ctx = br.new_context(viewport={'width': 390, 'height': 844}, is_mobile=True, has_touch=True, service_workers='block', timezone_id='Africa/Nairobi', locale='en-GB',
                         permissions=['geolocation'], geolocation={'latitude': 0.5205, 'longitude': 35.2699, 'accuracy': 15})
    ctx.route(re.compile(r'tile\.openstreetmap\.org|arcgisonline|google\.com/s2'), lambda r, q: r.fulfill(status=204, body=''))
    pg = ctx.new_page(); errs = []; pg.on('pageerror', lambda e: errs.append(str(e)))
    pg.clock.install(time=NOW)
    pg.route('**/api/pin-auth/me', lambda r, q: r.fulfill(json={'available': False})); pg.route(re.compile(r'/api/pin(\?.*)?$'), api); pg.route(re.compile(r'/api/admin(\?.*)?$'), admin)
    pg.goto(BASE + '/pin'); pg.wait_for_timeout(800)
    for d in '9999': pg.click('.pk[data-k="%s"]' % d)
    pg.wait_for_timeout(1200); pg.evaluate("localStorage.setItem('rfPinOnb','1')"); pg.goto(BASE + '/pin#me'); pg.wait_for_timeout(1500)

    # --- 1. sharing is off by default; turning it on sends one point and shows the pill ---------------
    check(pg.locator('#ofaShare').count() == 0, 'no sharing pill before turning it on')
    row = pg.locator('[data-set=share]'); row.scroll_into_view_if_needed()
    check('Share my location with the office' in row.inner_text() and 'Off' in row.inner_text(), 'Settings › Security and alerts shows Share my location: Off')
    pg.evaluate("document.querySelector('[data-set=share]').scrollIntoView({block:'center'})"); shot(pg, '01-settings-share-off.png')
    row.click(); pg.wait_for_timeout(500)
    check(pg.locator('#ofaTgl[aria-checked=false]').count() == 1, 'switch starts off')
    check(pg.locator('#ofaDays [aria-pressed=true]').count() == 6 and pg.input_value('#ofaFrom') == '07:00' and pg.input_value('#ofaTo2') == '18:00', 'default work hours Mon–Sat 07:00–18:00')
    check(not any(x.get('action') == 'here' for x in posts), 'nothing sent while off')
    pg.click('#ofaTgl'); pg.wait_for_timeout(1200)
    here = [x for x in posts if x.get('action') == 'here' and not x.get('off')]
    check(len(here) == 1 and abs(here[0]['lat'] - 0.5205) < 1e-6 and here[0]['lng'] > 35, 'turning on sends the current position once: %s' % here[:1])
    check('Sharing now' in pg.inner_text('#ofaShMsg'), 'status says sharing now')
    shot(pg, '02-share-sheet-on.png')
    pg.click('.bsh .bsh-x'); pg.wait_for_timeout(400)
    check(pg.locator('#ofaShare').is_visible() and 'Sharing location with the office' in pg.inner_text('#ofaShare'), 'pill visible while sharing')
    check('On · Mon–Sat · 07:00–18:00' in pg.inner_text('[data-set=share]'), 'settings row shows the hours: ' + pg.inner_text('[data-set=share]'))
    pg.evaluate("window.scrollTo(0,0)"); shot(pg, '03-pill-me.png')
    # at most every 10 minutes
    pg.clock.run_for(5 * 60 * 1000); pg.wait_for_timeout(300)
    check(len([x for x in posts if x.get('action') == 'here' and not x.get('off')]) == 1, 'no second point within 10 minutes')
    pg.clock.run_for(6 * 60 * 1000); pg.wait_for_timeout(800)
    check(len([x for x in posts if x.get('action') == 'here' and not x.get('off')]) == 2, 'a second point after 10 minutes')
    pg.goto(BASE + '/pin#list'); pg.wait_for_timeout(1200); shot(pg, '04-pill-projects.png')
    check(pg.locator('#ofaShare').is_visible(), 'pill shows on every screen')

    # --- 2. outside work hours: nothing shared, no pill ---------------------------------------------
    pg.goto(BASE + '/pin#me'); pg.wait_for_timeout(1200); pg.click('[data-set=share]'); pg.wait_for_timeout(400)
    pg.fill('#ofaFrom', '13:00'); pg.dispatch_event('#ofaFrom', 'change'); pg.wait_for_timeout(300)
    check('Outside your work hours' in pg.inner_text('#ofaShMsg') and pg.locator('#ofaShare').count() == 0, 'outside the hours: not sharing, no pill')
    n0 = len(posts); pg.clock.run_for(11 * 60 * 1000); pg.wait_for_timeout(500)
    check(not any(x.get('action') == 'here' for x in posts[n0:]), 'nothing sent outside work hours')
    pg.fill('#ofaFrom', '07:00'); pg.dispatch_event('#ofaFrom', 'change'); pg.wait_for_timeout(300); pg.click('.bsh .bsh-x'); pg.wait_for_timeout(400)

    # --- 3. Stop from the pill deletes the point on the server --------------------------------------
    pg.click('#ofaShare [data-ofa=stop]'); pg.wait_for_timeout(600)
    check(any(x.get('action') == 'here' and x.get('off') for x in posts), 'Stop tells the server to forget the point')
    check(pg.locator('#ofaShare').count() == 0 and 'Off' in pg.inner_text('[data-set=share]'), 'Stop turns sharing off')

    # --- 4. Office › Team map (admins): the map first, people as a compact list, a sheet per person ----------
    def sheet_open(pg): pg.wait_for_selector('.tsh.in'); pg.wait_for_timeout(700)
    def sheet_close(pg): pg.keyboard.press('Escape'); pg.wait_for_timeout(450)
    def folded(pg, k): return pg.evaluate("k => document.getElementById('dqmSec_' + k).getBoundingClientRect().height < 2", k)
    def overflow(pg, sel): return pg.evaluate("(sel) => [...document.querySelectorAll(sel)].filter(el => el.scrollWidth > el.clientWidth + 1 && getComputedStyle(el).textOverflow !== 'ellipsis').map(el => el.className)", sel)
    pg.goto(BASE + '/pin#office'); pg.wait_for_timeout(1200); pg.click('.otab[data-o=teammap]'); pg.wait_for_timeout(1500)
    check(any('view=teammap' in a for a in adm), 'team map loads from the admin view')
    check(pg.locator('.otab[data-o=teammap]').count() == 1 and pg.locator('.otab[data-o=plan]').count() == 1, 'Team map and Plan a week tabs in the Office')
    check(pg.locator('.ofa-mk').count() == 3, 'three teammates on the map as avatars: %d' % pg.locator('.ofa-mk').count())
    check(pg.locator('.ofa-mk.old').count() == 1, 'someone last seen over an hour ago is faded')
    nums = pg.eval_on_selector_all('#ofaTmNums b', 'e => e.map(x => x.textContent)')
    check(nums == ['3', '3', '1'], 'head numbers: sharing, visits today, not working %s' % nums)
    mb = pg.eval_on_selector('#ofaTmMap', 'e => e.getBoundingClientRect().top'); lb = pg.eval_on_selector('#ofaTmList', 'e => e.getBoundingClientRect().top')
    check(mb < lb, 'the map comes first, the people under it')
    t = pg.inner_text('#ofaTmList')
    check(re.search(r'Last seen \d+ min ago', t) and 'Bertin Mwangi' in t and '2 visits today' in t and 'Not seen for over an hour' in t, 'compact list: last seen, visits today, faded note')
    check(pg.locator('#ofaTmList a, #ofaTmList button button').count() == 0, 'no buttons inside the person cards')
    check(pg.locator('#ofaTmMap path.leaflet-interactive').count() == 3, 'today’s visits as small pins')
    check(folded(pg, 'tmpriv'), 'who shows here: folded')
    check(not overflow(pg, '#oBody .dqm-card, #oBody .dqm-nums > *, #oBody .dqm-row'), 'nothing overflows on the phone')
    shot(pg, '05-teammap.png')
    pg.click('[data-testid=tmpriv_toggle]'); pg.wait_for_timeout(450)
    check('Field officers never see this map' in pg.inner_text('[data-testid=tmpriv]'), 'privacy text opens with a tap')
    pg.locator('[data-testid=tmpriv]').scroll_into_view_if_needed(); shot(pg, '05b-teammap-list.png')
    pg.click('#ofaTmList [data-ofa-p="Bertin Mwangi"]'); sheet_open(pg)
    t = pg.inner_text('.tsh [data-testid=tm_sheet]')
    check('Chepkongi Dispensary' in t and 'Kapsaret Borehole' in t and 'Visits today · 2' in t.replace('VISITS TODAY', 'Visits today'), 'person sheet: today’s visits')
    check(pg.locator('.tsh a[href="tel:0722111222"]').count() == 1 and pg.locator('.tsh a[href="https://wa.me/254722111222"]').count() == 1, 'call and WhatsApp from the team phone')
    shot(pg, '06-teammap-person-sheet.png')
    pg.click('.tsh [data-testid=tm_onmap]'); pg.wait_for_timeout(1200)
    check(pg.locator('.tsh').count() == 0 and pg.locator('.leaflet-popup').count() == 1 and 'Bertin Mwangi' in pg.inner_text('.leaflet-popup'), 'Show on the map: the sheet closes and the person opens on the map')
    shot(pg, '07-teammap-popup.png')
    pg.click('.leaflet-popup [data-ofa-p]'); sheet_open(pg)
    check('Bertin Mwangi' in pg.inner_text('.tsh h3'), 'the map popup opens the same sheet'); sheet_close(pg)
    pg.click('#ofaTmList [data-ofa-p="Grace Achieng"]'); sheet_open(pg)
    check('No phone number' in pg.inner_text('.tsh') and pg.locator('.tsh a[href^="tel:"]').count() == 0, 'no phone: no call buttons'); sheet_close(pg)
    pg.click('#ofaTmList [data-ofa-p="Bertin Mwangi"]'); sheet_open(pg)
    n0 = pg.evaluate('location.hash'); pg.click('.tsh [data-a=open][data-id="102"]'); pg.wait_for_timeout(1200)
    check(pg.evaluate('location.hash').startswith('#p/102'), 'a visit in the sheet opens its project: ' + pg.evaluate('location.hash'))
    pg.goto(BASE + '/pin#office'); pg.wait_for_timeout(1200); pg.click('.otab[data-o=teammap]'); pg.wait_for_timeout(1500)
    for th in ['dark', 'sun']:
        pg.evaluate("t => document.documentElement.setAttribute('data-theme', t)", th); pg.click('#ofaTmR'); pg.wait_for_timeout(900); pg.evaluate("window.scrollTo(0,0)"); shot(pg, '08-teammap-%s.png' % th)
        pg.click('#ofaTmList [data-ofa-p="Meril Kiprop"]'); sheet_open(pg); shot(pg, '09-teammap-sheet-%s.png' % th); sheet_close(pg)
    pg.evaluate("document.documentElement.removeAttribute('data-theme')")

    # --- 5. Office › Plan a week: the choices fold after the first run; a stop opens a sheet ---------------
    pg.click('.otab[data-o=plan]'); pg.wait_for_timeout(900)
    check(not folded(pg, 'planf') and folded(pg, 'planhow'), 'before the first run the choices are open, how it chooses is folded')
    pg.select_option('#ofaTo', 'Meril Kiprop'); pg.select_option('#ofaDaysN', '3'); pg.select_option('#ofaPer', '4'); pg.fill('#ofaStart', '2026-10-24')   # a Saturday: the next day is Monday
    pg.dispatch_event('#ofaPer', 'change')
    check(pg.inner_text('#ofaAreaV') == 'All of Kenya' and pg.inner_text('#ofaPickSum') == 'All of Kenya · suggestions from every area', 'no area ticked: all of Kenya')
    check(pg.inner_text('#dqmPlanSum') == 'Meril Kiprop · Kenya · all areas · 3 days · 4 a day', 'the choices in one line: ' + pg.inner_text('#dqmPlanSum'))
    shot(pg, '10-plan-form.png')
    pg.click('#ofaGo'); pg.wait_for_timeout(1400)
    q = [a for a in adm if 'view=plan' in a][-1]
    check('ck=ke' in q and 'days=3' in q and 'per=4' in q, 'asks the server for 3 days of up to 4 stops')
    check(folded(pg, 'planf'), 'after the first run the choices fold away')
    check(pg.locator('.ofa-day').count() == 3, 'three day cards')
    days = pg.locator('.ofa-day .oh b').all_inner_texts()
    check('Sat 24 Oct' in days[0] and 'Mon 26 Oct' in days[1] and 'Tue 27 Oct' in days[2], 'days skip Sunday: %s' % days)
    t = pg.inner_text('#ofaRes')
    check('Urgent' in t and 'Not working' in t and 'Not visited for 13 months' in t and 'Water test due' in t, 'why each site is suggested')
    check(pg.locator('#ofaPMap .ofa-num').count() == 6 and pg.locator('#ofaPMap path.leaflet-interactive').count() == 3, 'numbered stops and a route line per day on the map')
    check(pg.locator('.dqm-stop button, .dqm-stop .ofa-x').count() == 0, 'no buttons inside the stops')
    check(not overflow(pg, '#oBody .dqm-stop, #oBody .dqm-row, #oBody .ofa-day .oh'), 'nothing overflows in the plan')
    pg.wait_for_timeout(600); shot(pg, '11-plan-result.png')
    shot(pg, '12-plan-full.png', True)
    pg.click('[data-ofa-day="1"]'); pg.wait_for_timeout(500); check(pg.locator('#ofaPMap .ofa-num.dim').count() == 4, 'one day picked: the others dim')
    # a stop's sheet: take it off the day, or open the project
    pg.click('[data-ofa-st="0:101"]'); sheet_open(pg)
    t = pg.inner_text('.tsh [data-testid=plan_sheet]')
    check('Kapsaret Borehole' in t and 'Stop 2 of 2' in t and 'Not visited for 13 months' in t and 'From the last stop' in t, 'stop sheet: why, which day, distance')
    shot(pg, '13-plan-stop-sheet.png')
    pg.click('.tsh [data-testid=plan_rm]'); pg.wait_for_timeout(700)
    check(pg.locator('.tsh').count() == 0 and pg.locator('.ofa-day').nth(0).locator('.dqm-stop').count() == 1, 'stop taken off day 1, the sheet closes')
    pg.select_option('[data-ofa-add="1"]', '109'); pg.wait_for_timeout(400)
    check('Moi’s Bridge Kiosk' in pg.locator('.ofa-day').nth(1).inner_text(), 'stop added to day 2')
    check('3 stops' in pg.locator('.ofa-day').nth(1).inner_text(), 'day 2 now has 3 stops (km re-worked out)')
    pg.click('[data-ofa-st="2:105"]'); sheet_open(pg); pg.click('.tsh [data-testid=plan_open]'); pg.wait_for_timeout(1200)
    check(pg.evaluate('location.hash').startswith('#p/105'), 'Open project from the stop sheet')
    pg.go_back(); pg.wait_for_timeout(1200)
    if pg.locator('.otab[data-o=plan][aria-pressed=true]').count() == 0: pg.click('.otab[data-o=plan]'); pg.wait_for_timeout(900)
    check(pg.locator('.ofa-day').count() == 3 and folded(pg, 'planf'), 'back in the Office: the plan is kept, choices folded')
    pg.click('[data-testid=planf_toggle]'); pg.wait_for_timeout(450); check(not folded(pg, 'planf'), 'the choices open again with a tap'); pg.click('[data-testid=planf_toggle]'); pg.wait_for_timeout(400)
    pg.fill('#ofaNote', 'Take the water test kit')
    btn = pg.inner_text('#ofaAssign'); check('Assign 6 visits to Meril Kiprop' in btn, 'assign button: ' + btn)
    pg.click('#ofaAssign'); pg.wait_for_timeout(1200)
    asg = [json.loads(a.split(' ', 2)[2]) for a in adm if a.startswith('POST') and '"assign"' in a]
    check(len(asg) == 3 and [a['date'] for a in asg] == ['2026-10-24', '2026-10-26', '2026-10-27'] and all(a['to'] == 'Meril Kiprop' for a in asg), 'one assign per day with its date: %s' % [(a['date'], a['ids']) for a in asg])
    check(asg and asg[0]['ids'] == ['104'] and set(asg[1]['ids']) == {'107', '108', '109'} and 'Take the water test kit · Week plan, day 2 of 3' == asg[1]['note'], 'the edited stops are sent, with the note')
    check('Assigned ✓ 6 visits' in pg.inner_text('#ofaAMsg') and pg.locator('#ofaAssign[disabled]').count() == 1, 'assigned once, button off')
    pg.evaluate("document.querySelector('#ofaAssign').scrollIntoView({block:'center'})"); shot(pg, '13b-plan-assigned.png')
    for th in ['dark', 'sun']:
        pg.evaluate("t => document.documentElement.setAttribute('data-theme', t)", th); pg.evaluate("document.querySelector('#ofaRes').scrollIntoView()"); shot(pg, '14-plan-%s.png' % th)
    pg.evaluate("document.documentElement.removeAttribute('data-theme')")

    # --- 5a. Plan a week › Areas and projects: several areas, projects ticked by hand, only these or with suggestions ---
    def midword(pg, sel): return pg.evaluate("""(sel) => { const bad = []; document.querySelectorAll(sel).forEach(el => { const w = document.createTreeWalker(el, NodeFilter.SHOW_TEXT); let n; while ((n = w.nextNode())) { const re = /\\S+/g; let m; while ((m = re.exec(n.data))) { const r = document.createRange(); r.setStart(n, m.index); r.setEnd(n, m.index + m[0].length); const tops = new Set([...r.getClientRects()].filter(x => x.width > 0).map(x => Math.round(x.top))); if (tops.size > 1) bad.push(m[0]); } } }); return bad; }""", sel)
    def narrow(pg, sel, mn): return pg.evaluate("([sel, mn]) => [...document.querySelectorAll(sel)].filter(el => el.getClientRects().length && el.getBoundingClientRect().width < mn).map(el => el.textContent.trim().slice(0, 40) + ' ' + Math.round(el.getBoundingClientRect().width))", [sel, mn])
    def last_plan(): return [a for a in adm if 'view=plan' in a][-1].split('?', 1)[1].split(' ')[0]
    pg.click('[data-testid=planf_toggle]'); pg.wait_for_timeout(450)
    check(pg.locator('#ofaOnly').is_hidden(), 'nothing chosen: the Only switch is hidden')
    pg.click('[data-testid=plan_area]'); sheet_open(pg)
    check(pg.locator('.tsh [data-testid=plan_pick_sheet]').count() == 1, 'the Area field opens Areas and projects')
    rows = pg.locator('#ofaArList label').all_inner_texts()
    check(pg.locator('#ofaArList input[type=checkbox]').count() >= 10 and pg.locator('#ofaArList input[type=radio]').count() == 0, 'areas are checkboxes')
    check(rows[0].split() == ['Kapsaret', '8'] and rows[1].split() == ['Kaigat', '3'] and any(r.startswith('Kapsabaot Comprehensive School') and r.endswith('1') for r in rows), 'areas keep their counts, biggest first: %s' % rows[:3])
    check(pg.inner_text('#ofaArN') == 'All of Kenya' and 'None ticked' in pg.inner_text('#ofaArHint'), 'none ticked means all of Kenya')
    check(pg.inner_text('#ofaPrL') == 'Projects in Kenya' and pg.locator('#ofaPkList .dqm-pg').count() >= 10, 'no area ticked: all of Kenya’s projects, by area')
    pg.fill('#ofaPkQ', 'ziwa'); pg.wait_for_timeout(200)
    check(pg.locator('#ofaPkList label.dnr-p').count() == 1, 'search narrows the projects'); pg.fill('#ofaPkQ', ''); pg.wait_for_timeout(150)
    pg.check('#ofaArList input[value="Kapsaret"]'); pg.check('#ofaArList input[value="Kaigat"]'); pg.wait_for_timeout(200)
    heads = pg.locator('#ofaPkList .dqm-pgt b').all_inner_texts()
    check(heads == ['Kapsaret', 'Kaigat'] and pg.locator('#ofaPkList label.dnr-p').count() == 11, 'projects of the ticked areas, grouped by area: %s' % heads)
    check(pg.inner_text('#ofaArN') == '2 ticked' and pg.inner_text('#ofaPrL') == 'Projects in these areas', 'two areas ticked')
    for i in ['101', '102', '127']: pg.check('#ofaPkList input[value="%s"]' % i)
    pg.wait_for_timeout(150)
    check(pg.inner_text('#ofaPkN') == '3 chosen' and '3 of 8 chosen' in pg.locator('.dqm-pg').nth(0).inner_text(), 'three ticked: the count and the area heading')
    check('No location' in pg.locator('#ofaPkList label:has(input[value="127"])').inner_text(), 'a project with no location says so')
    kg = pg.locator('.dqm-pg').nth(1)
    kg.locator('[data-pa=all]').click(); pg.wait_for_timeout(150)
    check(pg.inner_text('#ofaPkN') == '6 chosen' and kg.locator('input:checked').count() == 3, 'Select all in Kaigat')
    pg.locator('.dqm-pg').nth(1).locator('[data-pa=none]').click(); pg.wait_for_timeout(150)
    check(pg.inner_text('#ofaPkN') == '3 chosen' and pg.locator('.dqm-pg').nth(1).locator('input:checked').count() == 0, 'Clear in Kaigat')
    pg.click('#ofaPkOnly'); pg.wait_for_timeout(150)
    check(pg.locator('#ofaPkList label.dnr-p').count() == 3, 'ticked only'); pg.click('#ofaPkOnly'); pg.wait_for_timeout(150)
    check(not overflow(pg, '.tsh .dnr-p, .tsh .dqm-pgh, .tsh .dqm-pks > *') and not midword(pg, '.tsh .dqm-an, .tsh .dqm-pgt b, .tsh label.dnr-p b'), 'picker: nothing overflows or breaks mid-word')
    pg.locator('#ofaArList label:has(input[value="Kapsabaot Comprehensive School"])').scroll_into_view_if_needed()
    check(not narrow(pg, '.tsh .dqm-an, .tsh .dqm-pgt, .tsh label.dnr-p:not(.dqm-ac) > span', 150), 'long names are never squeezed: %s' % narrow(pg, '.tsh .dqm-an, .tsh .dqm-pgt, .tsh label.dnr-p:not(.dqm-ac) > span', 150))
    shot(pg, '21-plan-areas.png')
    pg.evaluate("document.querySelector('#ofaPrs').scrollIntoView()"); shot(pg, '21b-plan-choose-projects.png')
    pg.click('.tsh [data-testid=plan_pick_done]'); pg.wait_for_timeout(450)
    check(pg.inner_text('#ofaAreaV') == 'Kapsaret, Kaigat', 'the Area field: ' + pg.inner_text('#ofaAreaV'))
    check(pg.inner_text('#ofaPickSum') == 'Kapsaret: 3 of 8 projects · Kaigat: all 3', 'the summary under the form: ' + pg.inner_text('#ofaPickSum'))
    check(pg.locator('#ofaOnly').is_visible() and pg.get_attribute('#ofaOnly', 'aria-checked') == 'false', 'the Only switch appears, off')
    pg.click('#ofaOnly'); pg.wait_for_timeout(200)
    check(pg.get_attribute('#ofaOnly', 'aria-checked') == 'true' and 'only the 3 chosen' in pg.inner_text('#dqmPlanSum'), 'Only these projects: on, in the folded summary: ' + pg.inner_text('#dqmPlanSum'))
    pg.locator('#ofaPick').scroll_into_view_if_needed(); pg.evaluate("window.scrollBy(0, -260)"); shot(pg, '22-plan-form-chosen.png')
    # tapping the summary opens the picker at the projects
    pg.click('[data-testid=plan_pick]'); sheet_open(pg)
    check(pg.evaluate("(() => { const r = document.querySelector('#ofaPrs').getBoundingClientRect(); return r.top < innerHeight * 0.6 && r.top > 0; })()"), 'the summary opens the picker at the projects')
    sheet_close(pg)
    pg.click('#ofaGo'); pg.wait_for_timeout(1400)
    q = last_plan()
    check('areas=Kapsaret,Kaigat' in q and 'ids=101,102,127' in q and 'only=1' in q and 'area=' not in q.replace('areas=', ''), 'asks for the ticked areas and the chosen projects only: ' + q)
    check(pg.locator('.ofa-day').count() == 1 and pg.locator('.ofa-day .tag.dqm-ch').count() == 2, 'only the chosen projects, each with a Chosen tag')
    check('No location, so not placed' in pg.inner_text('[data-testid=plan_nogps]') and 'Kapsaret Health Centre' in pg.inner_text('[data-testid=plan_nogps]'), 'a chosen project with no location is listed, not dropped')
    check(not overflow(pg, '#oBody .dqm-stop, #oBody .dqm-row, #oBody .dqm-nogps'), 'chosen plan: nothing overflows')
    pg.evaluate("document.querySelector('[data-testid=plan_res]').scrollIntoView()"); shot(pg, '23-plan-only-chosen.png')
    pg.click('[data-testid=planf_toggle]'); pg.wait_for_timeout(400); pg.click('#ofaOnly'); pg.click('#ofaGo'); pg.wait_for_timeout(1400)
    q = last_plan()
    check('ids=101,102,127' in q and 'only=1' not in q, 'with suggestions: no only=1')
    tags = pg.locator('.ofa-day .dqm-stop').evaluate_all("e => e.map(x => !!x.querySelector('.tag.dqm-ch'))")
    check(pg.locator('.ofa-day').count() == 2 and tags.count(True) == 2 and tags.count(False) == 2, 'chosen projects stand out from suggestions: %s' % tags)
    pg.click('[data-ofa-st="0:101"]'); sheet_open(pg)
    check('Chosen' in pg.inner_text('.tsh .ofa-why'), 'the stop sheet says Chosen'); shot(pg, '24-plan-chosen-stop-sheet.png'); sheet_close(pg)
    pg.evaluate("document.querySelector('[data-testid=plan_res]').scrollIntoView()"); shot(pg, '25-plan-chosen-with-suggestions.png')
    for th in ['dark', 'sun']:
        pg.evaluate("t => document.documentElement.setAttribute('data-theme', t)", th); pg.evaluate("document.querySelectorAll('.ofa-day')[0].scrollIntoView()"); shot(pg, '26-plan-chosen-%s.png' % th)
        pg.click('[data-testid=planf_toggle]'); pg.wait_for_timeout(400); pg.locator('#ofaPick').scroll_into_view_if_needed(); pg.evaluate("window.scrollBy(0, -260)"); shot(pg, '26b-plan-form-%s.png' % th)
        pg.click('[data-testid=plan_area]'); sheet_open(pg); shot(pg, '27-plan-areas-%s.png' % th)
        pg.evaluate("document.querySelector('#ofaPrs').scrollIntoView()"); shot(pg, '27b-plan-projects-%s.png' % th); sheet_close(pg); pg.click('[data-testid=planf_toggle]'); pg.wait_for_timeout(400)
    pg.evaluate("document.documentElement.removeAttribute('data-theme')")
    # unticking an area lets go of its projects; the first area ticked brings the areas of projects already chosen
    pg.click('[data-testid=planf_toggle]'); pg.wait_for_timeout(400); pg.click('[data-testid=plan_area]'); sheet_open(pg)
    pg.uncheck('#ofaArList input[value="Kapsaret"]'); pg.wait_for_timeout(150)
    check(pg.inner_text('#ofaPkN') == '0 chosen' and pg.locator('#ofaPkList .dqm-pgt b').all_inner_texts() == ['Kaigat'], 'unticking Kapsaret lets go of its projects')
    pg.uncheck('#ofaArList input[value="Kaigat"]'); pg.wait_for_timeout(150)
    pg.check('#ofaPkList input[value="152"]'); pg.check('#ofaArList input[value="Ndalat"]'); pg.wait_for_timeout(150)
    check(pg.locator('#ofaArList input:checked').evaluate_all('e => e.map(x => x.value)') == ['Kapsabaot Comprehensive School', 'Ndalat'] or sorted(pg.locator('#ofaArList input:checked').evaluate_all('e => e.map(x => x.value)')) == ['Kapsabaot Comprehensive School', 'Ndalat'], 'the chosen project’s area is ticked too, so it isn’t hidden')
    pg.click('.tsh [data-testid=plan_pick_done]'); pg.wait_for_timeout(400)
    check(pg.inner_text('#ofaPickSum') == 'Ndalat: all 2 · Kapsabaot Comprehensive School: 1 of 1 project', 'summary: ' + pg.inner_text('#ofaPickSum'))
    check(not overflow(pg, '#oBody .dqm-row, #oBody .dqm-sel') and not midword(pg, '#ofaPickSum, #ofaAreaV'), 'long area names wrap cleanly in the form')
    pg.click('#ofaGo'); pg.wait_for_timeout(1200)
    check('areas=Ndalat,Kapsabaot%20Comprehensive%20School' in last_plan() and 'ids=152' in last_plan(), 'area names are sent encoded: ' + last_plan())
    # a new country starts again
    pg.click('[data-testid=planf_toggle]'); pg.wait_for_timeout(400)
    pg.select_option('#ofaCk', 'tz'); pg.wait_for_timeout(200)
    check(pg.inner_text('#ofaAreaV') == 'All of Tanzania' and pg.locator('#ofaOnly').is_hidden(), 'another country: areas and projects cleared')
    pg.select_option('#ofaCk', 'ke'); pg.wait_for_timeout(200); pg.click('[data-testid=planf_toggle]'); pg.wait_for_timeout(400)

    # --- 5b. desktop (1280) ------------------------------------------------------------------------------
    pg.set_viewport_size({'width': 1280, 'height': 900}); pg.goto(BASE + '/pin#office'); pg.wait_for_timeout(1200)
    pg.click('.otab[data-o=teammap]'); pg.wait_for_timeout(1500); pg.evaluate("window.scrollTo(0,0)"); shot(pg, '16-teammap-desktop.png')
    pg.click('#ofaTmList [data-ofa-p="Bertin Mwangi"]'); sheet_open(pg); shot(pg, '17-teammap-sheet-desktop.png'); sheet_close(pg)
    pg.click('.otab[data-o=plan]'); pg.wait_for_timeout(1200); shot(pg, '18-plan-desktop.png', True)
    check(not overflow(pg, '#oBody .dqm-stop, #oBody .dqm-row, #oBody .dqm-card'), 'nothing overflows on the desktop')
    pg.click('[data-testid=planf_toggle]'); pg.wait_for_timeout(450); pg.click('[data-testid=plan_area]'); sheet_open(pg)
    pg.check('#ofaArList input[value="Kapsaret"]'); pg.check('#ofaArList input[value="Kapsabaot Comprehensive School"]'); pg.check('#ofaPkList input[value="121"]'); pg.wait_for_timeout(200)
    check(not overflow(pg, '.tsh .dnr-p, .tsh .dqm-pgh') and not midword(pg, '.tsh .dqm-an, .tsh .dqm-pgt b, .tsh label.dnr-p b'), 'picker on the desktop: nothing overflows or breaks mid-word')
    pg.evaluate("document.querySelector('#ofaPrs').scrollIntoView()"); shot(pg, '18b-plan-picker-desktop.png'); pg.click('.tsh [data-testid=plan_pick_done]'); pg.wait_for_timeout(400)
    pg.locator('#ofaPick').scroll_into_view_if_needed(); shot(pg, '18c-plan-form-desktop.png')
    pg.set_viewport_size({'width': 390, 'height': 844})

    # --- 6. Kiswahili ----------------------------------------------------------------------------------
    pg.evaluate("localStorage.setItem('rfPinLang','sw')"); pg.goto(BASE + '/pin#me'); pg.reload(); pg.wait_for_timeout(1500)
    pg.click('[data-set=share]'); pg.wait_for_timeout(500)
    check('Shiriki mahali nilipo' in pg.inner_text('.bsh') and 'Jtt' in pg.inner_text('#ofaDays'), 'Kiswahili sheet')
    shot(pg, '15-share-sw.png'); pg.click('.bsh .bsh-x'); pg.wait_for_timeout(300)
    pg.goto(BASE + '/pin#office'); pg.wait_for_timeout(1500)
    check('Ramani ya timu' in pg.inner_text('#oTabs') and 'Panga wiki' in pg.inner_text('#oTabs'), 'Kiswahili Office tabs')
    pg.click('.otab[data-o=teammap]'); pg.wait_for_timeout(1500)
    t = pg.inner_text('#oBody'); check('Wanashiriki sasa' in t and 'Nani anaonekana hapa' in t and 'Alionekana' in t, 'team map in Kiswahili')
    check(not overflow(pg, '#oBody .dqm-card, #oBody .dqm-nums > *, #oBody .dqm-row'), 'team map: nothing overflows in Kiswahili')
    shot(pg, '19-teammap-sw.png'); pg.click('#ofaTmList [data-ofa-p="Bertin Mwangi"]'); sheet_open(pg); shot(pg, '19b-teammap-sheet-sw.png'); sheet_close(pg)
    pg.click('.otab[data-o=plan]'); pg.wait_for_timeout(1200)
    shot(pg, '20a-plan-form-sw.png')
    pg.select_option('#ofaTo', 'Meril Kiprop'); pg.click('#ofaGo'); pg.wait_for_timeout(1400)
    t = pg.inner_text('#oBody'); check('Machaguo' in t and 'Siku 1' in t and 'Jinsi inavyochagua' in t, 'plan a week in Kiswahili')
    check(not overflow(pg, '#oBody .dqm-stop, #oBody .dqm-row, #oBody .ofa-day .oh'), 'plan: nothing overflows in Kiswahili')
    shot(pg, '20-plan-sw.png', True)
    pg.click('[data-ofa-st="1:107"]'); sheet_open(pg); shot(pg, '20b-plan-stop-sheet-sw.png'); sheet_close(pg)
    pg.click('[data-testid=planf_toggle]'); pg.wait_for_timeout(400)
    check(pg.inner_text('#ofaAreaV') == 'Yote ya Kenya' and 'mapendekezo kutoka maeneo yote' in pg.inner_text('#ofaPickSum'), 'Kiswahili: hakuna eneo')
    pg.click('[data-testid=plan_area]'); sheet_open(pg)
    pg.check('#ofaArList input[value="Kapsaret"]'); pg.check('#ofaArList input[value="Kapsabaot Comprehensive School"]'); pg.check('#ofaPkList input[value="101"]'); pg.wait_for_timeout(150)
    t = pg.inner_text('.tsh')
    check('Maeneo na miradi' in t and 'Chagua zote' in t and 'Futa' in t and pg.inner_text('#ofaPkN') == '1 zimechaguliwa' and pg.inner_text('#ofaArN') == '2 zimechaguliwa', 'areas and projects in Kiswahili')
    check(not overflow(pg, '.tsh .dnr-p, .tsh .dqm-pgh, .tsh .dqm-pks > *') and not midword(pg, '.tsh .dqm-an, .tsh .dqm-pgt b, .tsh label.dnr-p b, .tsh .dqm-pga'), 'Kiswahili picker: nothing overflows or breaks mid-word')
    check(not narrow(pg, '.tsh .dqm-an, .tsh .dqm-pgt, .tsh label.dnr-p:not(.dqm-ac) > span', 150), 'Kiswahili: long names are never squeezed')
    shot(pg, '28-plan-areas-sw.png'); pg.evaluate("document.querySelector('#ofaPrs').scrollIntoView()"); shot(pg, '28b-plan-projects-sw.png')
    pg.click('.tsh [data-testid=plan_pick_done]'); pg.wait_for_timeout(400)
    check(pg.inner_text('#ofaPickSum') == 'Kapsaret: miradi 1 kati ya 8 · Kapsabaot Comprehensive School: mradi wake 1', 'summary in Kiswahili: ' + pg.inner_text('#ofaPickSum'))
    check('Miradi hii tu' in pg.inner_text('#ofaOnly') and '1 zimechaguliwa' in pg.inner_text('#dqmPlanSum'), 'the Only switch and folded summary in Kiswahili')
    check(not overflow(pg, '#oBody .dqm-row, #oBody .dqm-sel, #oBody .tm-sw') and not midword(pg, '#ofaPickSum, #ofaAreaV, #ofaOnly'), 'Kiswahili form: nothing overflows or breaks mid-word')
    pg.locator('#ofaPick').scroll_into_view_if_needed(); pg.evaluate("window.scrollBy(0, -260)"); shot(pg, '29-plan-form-chosen-sw.png')
    pg.evaluate("localStorage.setItem('rfPinLang','en')")

    # --- 7. a field officer never sees the team map ----------------------------------------------------
    state['admin'] = False; n0 = len(adm)
    pg.reload(); pg.wait_for_timeout(2000)
    check(pg.locator('#tabOffice.hidden').count() == 1 and not any('teammap' in a for a in adm[n0:]), 'no Office tab and no team map request for field users')
    check(not errs, 'no page errors %s' % errs[:2]); br.close()
print('PROBLEMS', problems)
