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
     proj(110, 'Ziwa Borehole', 'Eldoret', [0.75, 35.3])]
REP = {'104': {'state': 'notworking', 'by': 'Bertin', 'at': iso(3000), 'urgency': 'urgent', 't': 'Sigowet Church Well'}, '107': {'state': 'repair', 'by': 'Grace', 'at': iso(9000), 't': 'Bungoma Bridge School'}}
VIS = {'101': {'at': iso(400 * 1440), 'by': 'Meril', 'state': 'working'}, '102': {'at': iso(20 * 1440), 'by': 'Meril', 'state': 'working'}}
TEAM = [{'name': 'Meril Kiprop', 'home': 'ke', 'admin': False, 'source': 'app', 'phone': '+254 712 345 678'}, {'name': 'Bertin Mwangi', 'home': 'ke', 'admin': False, 'source': 'app'}, {'name': 'Grace Achieng', 'home': 'ke', 'admin': False, 'source': 'app'}, {'name': 'Reid Anderson', 'admin': True, 'source': 'cloudflare'}]
TM = {'ok': True, 'people': [{'name': 'Bertin Mwangi', 'lat': 0.52, 'lng': 35.28, 'acc': 12, 'at': iso(12), 'phone': '0722 111 222', 'home': 'ke'},
                             {'name': 'Grace Achieng', 'lat': 0.58, 'lng': 34.6, 'acc': 30, 'at': iso(95), 'phone': '', 'home': 'ke'},
                             {'name': 'Meril Kiprop', 'lat': 1.0, 'lng': 35.02, 'acc': 8, 'at': iso(3), 'phone': '+254 712 345 678', 'home': 'ke'}],
      'visits': [{'id': '102', 'by': 'Bertin Mwangi', 'at': iso(70), 's': 'working', 't': 'Chepkongi Dispensary'}, {'id': '101', 'by': 'Bertin Mwangi', 'at': iso(150), 's': 'repair', 't': 'Kapsaret Borehole'},
                 {'id': '107', 'by': 'Grace Achieng', 'at': iso(120), 's': 'notworking', 't': 'Bungoma Bridge School'}]}
def stop(i, why, age=None): p = next(x for x in P if x['id'] == str(i)); return {'id': p['id'], 't': p['title'], 'area': p['area'], 'gps': p['gps'], 'why': why, 'score': 50, 'age': age}
PLAN = {'ok': True, 'total': 9, 'days': [{'stops': [stop(104, ['repair', 'urgent'], 3), stop(101, ['overdue'], 400)], 'km': 82.1},
                                          {'stops': [stop(107, ['repair']), stop(108, ['never'])], 'km': 24.0},
                                          {'stops': [stop(105, ['never', 'test']), stop(106, ['never'])], 'km': 15.2}],
        'spare': [stop(109, ['never']), stop(110, ['never']), stop(103, ['never'])]}
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
        if 'view=plan' in req.url: return route.fulfill(json=PLAN)
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
    check('Kitale (2)' in pg.inner_text('#ofaArea'), 'areas with how many sites')
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

    # --- 5b. desktop (1280) ------------------------------------------------------------------------------
    pg.set_viewport_size({'width': 1280, 'height': 900}); pg.goto(BASE + '/pin#office'); pg.wait_for_timeout(1200)
    pg.click('.otab[data-o=teammap]'); pg.wait_for_timeout(1500); pg.evaluate("window.scrollTo(0,0)"); shot(pg, '16-teammap-desktop.png')
    pg.click('#ofaTmList [data-ofa-p="Bertin Mwangi"]'); sheet_open(pg); shot(pg, '17-teammap-sheet-desktop.png'); sheet_close(pg)
    pg.click('.otab[data-o=plan]'); pg.wait_for_timeout(1200); shot(pg, '18-plan-desktop.png', True)
    check(not overflow(pg, '#oBody .dqm-stop, #oBody .dqm-row, #oBody .dqm-card'), 'nothing overflows on the desktop')
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
    pg.evaluate("localStorage.setItem('rfPinLang','en')")

    # --- 7. a field officer never sees the team map ----------------------------------------------------
    state['admin'] = False; n0 = len(adm)
    pg.reload(); pg.wait_for_timeout(2000)
    check(pg.locator('#tabOffice.hidden').count() == 1 and not any('teammap' in a for a in adm[n0:]), 'no Office tab and no team map request for field users')
    check(not errs, 'no page errors %s' % errs[:2]); br.close()
print('PROBLEMS', problems)
