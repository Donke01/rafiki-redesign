# Home map layers: today's route (ticks off on check-in), follow me with heading and compass, heat (repairs /
# people served), the time slider with play, live team dots (admins), and motion that respects reduced motion.
# Needs the app served on PORT (default 8862): PORT=8862 node server.js
import json, os, re, datetime
from playwright.sync_api import sync_playwright
HERE = os.path.dirname(os.path.abspath(__file__)); BASE = 'http://localhost:' + os.environ.get('PORT', '8862'); OUT = os.path.join(HERE, 'maplayers'); os.makedirs(OUT, exist_ok=True)
TILE = open(os.path.join(HERE, 'tile.png'), 'rb').read()
NOW = datetime.datetime(2026, 10, 22, 7, 30, tzinfo=datetime.timezone.utc)   # Thursday 10:30 in Nairobi
TODAY = '2026-10-22'
iso = lambda m: (NOW - datetime.timedelta(minutes=m)).strftime('%Y-%m-%dT%H:%M:%SZ')
def proj(i, t, area, gps, year, people, typ='Borehole well', status='Completed', ck='ke', country='Kenya'):
    return {'id': str(i), 'title': t, 'country': country, 'ck': ck, 'area': area, 'type': typ, 'status': status, 'year': year, 'people': people, 'gps': gps}
P = [proj(101, 'Kapsaret Borehole', 'Kapsaret, Eldoret', [0.47, 35.25], 2014, 1200), proj(102, 'Chepkongi Dispensary', 'Kapsaret, Eldoret', [0.514, 35.27], 2016, 3400, 'Health'),
     proj(103, 'Turbo Spring', 'Turbo', [0.63, 35.05], 2015, 600, 'Water collection'), proj(104, 'Sigowet Church Well', 'Sigowet', [-0.27, 35.15], 2018, 900),
     proj(105, 'Kitale Primary Tank', 'Kitale', [1.015, 35.0], 2019, 1500, 'School'), proj(106, 'Kiminini Borehole', 'Kitale', [0.9, 34.92], 2020, 2200),
     proj(107, 'Bungoma Bridge School', 'Bungoma', [0.563, 34.56], 2021, 800, 'School'), proj(108, 'Webuye Well', 'Bungoma', [0.61, 34.77], 2022, 1100),
     proj(109, 'Moi’s Bridge Kiosk', 'Eldoret', [0.87, 35.12], 2023, 700), proj(110, 'Ziwa Borehole', 'Eldoret', [0.75, 35.3], 2024, 1900),
     proj(111, 'Iten Water Tank', 'Iten', [0.67, 35.51], 2025, 1300, 'Water collection'), proj(112, 'Kapcherop Well', 'Kapcherop', [1.07, 35.33], None, None, status='Upcoming'),
     proj(113, 'Mbale Borehole', 'Mbale', [1.07, 34.18], 2017, 2600, ck='ug', country='Uganda'), proj(114, 'Musoma Well', 'Musoma', [-1.5, 33.8], 2019, 1800, ck='tz', country='Tanzania'),
     proj(115, 'Nandi Hills Spring', 'Nandi Hills', [0.1, 35.18], 2013, 500, 'Water collection')]
REP = {'104': {'state': 'notworking', 'by': 'Bertin', 'at': iso(3000), 'urgency': 'urgent', 't': 'Sigowet Church Well'}, '107': {'state': 'repair', 'by': 'Grace', 'at': iso(9000), 't': 'Bungoma Bridge School'},
       '103': {'state': 'repair', 'by': 'Grace', 'at': iso(5000), 'urgency': 'urgent', 't': 'Turbo Spring'}, '102': {'state': 'repair', 'by': 'Meril', 'at': iso(800), 't': 'Chepkongi Dispensary'}}
VIS0 = {'101': {'at': iso(400 * 1440), 'by': 'Meril', 'state': 'working'}}
ASG = [{'aid': 'a1', 'id': '102', 't': 'Chepkongi Dispensary', 'to': 'Meril Kiprop', 'date': TODAY, 'note': '', 'by': 'Reid Anderson'},
       {'aid': 'a2', 'id': '110', 't': 'Ziwa Borehole', 'to': 'Meril Kiprop', 'date': TODAY, 'note': '', 'by': 'Reid Anderson'},
       {'aid': 'a3', 'id': '109', 't': 'Moi’s Bridge Kiosk', 'to': 'Meril Kiprop', 'date': TODAY, 'note': '', 'by': 'Reid Anderson'},
       {'aid': 'a4', 'id': '105', 't': 'Kitale Primary Tank', 'to': 'Meril Kiprop', 'date': '2026-10-24', 'note': '', 'by': 'Reid Anderson'}]
TM = {'ok': True, 'people': [{'name': 'Bertin Mwangi', 'lat': 0.52, 'lng': 35.28, 'acc': 12, 'at': iso(12), 'phone': '0722 111 222', 'home': 'ke'},
                             {'name': 'Grace Achieng', 'lat': 0.58, 'lng': 34.6, 'acc': 30, 'at': iso(95), 'phone': '', 'home': 'ke'},
                             {'name': 'Meril Kiprop', 'lat': 1.0, 'lng': 35.02, 'acc': 8, 'at': iso(3), 'phone': '+254 712 345 678', 'home': 'ke'}], 'visits': []}
problems, adm = [], []
def check(c, w): print(('ok   ' if c else 'FAIL ') + w); (None if c else problems.append(w))
def shot(pg, name, full=False): pg.wait_for_timeout(450); pg.screenshot(path=os.path.join(OUT, name), full_page=full)

def run(br, admin, vp=(390, 844), reduce=False, name='Meril Kiprop'):
    st = {'assigned': json.loads(json.dumps(ASG)), 'visits': json.loads(json.dumps(VIS0))}
    ctx = br.new_context(viewport={'width': vp[0], 'height': vp[1]}, is_mobile=vp[0] < 600, has_touch=vp[0] < 600, service_workers='block', timezone_id='Africa/Nairobi', locale='en-GB',
                         permissions=['geolocation'], geolocation={'latitude': 0.5205, 'longitude': 35.2699, 'accuracy': 15}, reduced_motion='reduce' if reduce else 'no-preference')
    ctx.route(re.compile(r'tile\.openstreetmap\.org|arcgisonline'), lambda r, q: r.fulfill(status=200, content_type='image/png', body=TILE))
    ctx.route(re.compile(r'google\.com/s2'), lambda r, q: r.fulfill(status=204, body=''))
    pg = ctx.new_page(); errs = []; pg.on('pageerror', lambda e: errs.append(str(e)))
    # counts the dots that get the drop-in animation
    pg.add_init_script("window.__drops=0;new MutationObserver(function(ms){ms.forEach(function(m){var c=m.target.getAttribute&&m.target.getAttribute('class')||'';if(/mpb-drop/.test(c)&&!/mpb-drop/.test(m.oldValue||''))window.__drops++})}).observe(document,{subtree:true,attributes:true,attributeOldValue:true,attributeFilter:['class']})")
    pg.clock.install(time=NOW)
    def api(route, req):
        if req.method == 'GET':
            if 'activity=' in req.url: return route.fulfill(json={'ok': True, 'activity': []})
            if 'team=' in req.url: return route.fulfill(json={'ok': True, 'team': []})
            return route.fulfill(json={'ok': True, 'who': name, 'admin': admin, 'reviewOpen': 0, 'projects': P, 'repairs': REP, 'visits': st['visits'], 'jobs': [],
                                       'assigned': [a for a in st['assigned'] if a['to'] == name], 'confirmed': {}, 'me': {'kind': 'app', 'nick': '', 'phone': '', 'home': 'ke'}})
        route.fulfill(json={'ok': True})
    def adminr(route, req):
        adm.append(req.method + ' ' + req.url)
        if not admin: return route.fulfill(status=403, json={'ok': False, 'reason': 'not-admin'})
        if 'view=teammap' in req.url: return route.fulfill(json=TM)
        if 'view=team' in req.url: return route.fulfill(json={'ok': True, 'team': [], 'mail': {'ready': False}, 'cron': False})
        route.fulfill(json={'ok': True, 'items': []})
    pg.route('**/api/pin-auth/me', lambda r, q: r.fulfill(json={'available': False})); pg.route(re.compile(r'/api/pin(\?.*)?$'), api); pg.route(re.compile(r'/api/admin(\?.*)?$'), adminr)
    pg.goto(BASE + '/pin'); pg.wait_for_timeout(800)
    for d in '9999': pg.click('.pk[data-k="%s"]' % d)
    pg.wait_for_timeout(1200); pg.evaluate("localStorage.setItem('rfPinOnb','1')")
    return ctx, pg, st, errs

def sheet(pg): pg.click('#vMap .mstyle'); pg.wait_for_timeout(450)
# Follow me: in the column while it is on, in the tray under More while it is off (mcl…)
def fol(pg):
    if not pg.locator('#mpbFol').is_visible(): pg.click('#mclMore'); pg.wait_for_timeout(300)
    pg.click('#mpbFol')
def close_sheet(pg): pg.keyboard.press('Escape'); pg.wait_for_timeout(350)
def no_hscroll(pg, w): return pg.evaluate('document.documentElement.scrollWidth') <= w
# the map tab's pins (mpa…): single pins plus the projects in the number bubbles
def pins(pg): return pg.evaluate("document.querySelectorAll('#hMap .mpa-pin').length + Array.prototype.reduce.call(document.querySelectorAll('#hMap .mpa-cl'), function (s, e) { return s + Number(e.getAttribute('data-n') || 0); }, 0)")

with sync_playwright() as p:
    br = p.chromium.launch()

    # ===== 1. a field officer: today's route, ticks, follow me ===========================================
    ctx, pg, st, errs = run(br, admin=False)
    pg.goto(BASE + '/pin#map'); pg.reload(); pg.wait_for_timeout(2000)
    drops = pg.evaluate('window.__drops')
    check(drops > 5, 'pins drop in when the map first shows (%d animating)' % drops)
    check(pg.locator('#hMap .mpb-drop').count() == 0, 'the drop finishes and leaves nothing behind')
    check(pg.locator('.mpb-n').count() == 3, 'today’s 3 visits are numbered stops on the map (%d)' % pg.locator('.mpb-n').count())
    check(pg.locator('.mpb-n.next').count() == 1 and pg.locator('.mpb-n.ok').count() == 0, 'the next stop stands out, nothing ticked yet')
    order_ = pg.evaluate("JSON.parse(localStorage.getItem('rfPinMpbDay')).ids")
    check(order_ == ['102', '110', '109'], 'driving order from where you are (nearest first): %s' % order_)
    check(pg.locator('#hMap path.mpb-line').count() >= 3, 'the route is drawn as a line')
    check(pg.locator('.mpb-pl').count() == 2 and pg.locator('.mpb-pl.bad').count() == 1, 'a soft pulse on the urgent / not working sites only (%d)' % pg.locator('.mpb-pl').count())
    pg.click('#mclMore'); pg.wait_for_timeout(300)
    check(pg.locator('#mpbFol').is_visible() and pg.locator('#mpbCmp').is_hidden(), 'follow-me button (under More); the compass only shows when the map is turned')
    b = pg.locator('#mpbFol').bounding_box(); check(b and b['width'] >= 44 and b['height'] >= 44, 'follow button is 44 px')
    pg.keyboard.press('Escape'); pg.wait_for_timeout(200)
    shot(pg, '01-route-field.png')
    sheet(pg)
    check(pg.locator('.mpb-sh [data-mpb=route][aria-checked=true]').count() == 1, 'layers sheet: Today’s route is on')
    check('3 visits for you today · 0 done' in pg.inner_text('.mpb-sh'), 'it says how many and how many done')
    check(pg.locator('[data-mpb=team], [data-mpb=years], [data-mpb-heat]').count() == 0, 'field users don’t see the office layers')
    shot(pg, '02-sheet-field.png')
    pg.click('[data-mpb=route]'); pg.wait_for_timeout(300)
    check(pg.locator('.mpb-n').count() == 0 and pg.locator('[data-mpb=route][aria-checked=false]').count() == 1, 'switch the route off: gone, the sheet stays open')
    pg.click('[data-mpb=route]'); pg.wait_for_timeout(1200)
    check(pg.locator('.mpb-n').count() == 3, 'and back on')
    close_sheet(pg)
    # check in at the first stop: the server drops the done assignment, the visit is today
    st['visits']['102'] = {'at': iso(5), 'by': 'Meril Kiprop', 'state': 'working'}; st['assigned'] = [a for a in st['assigned'] if a['id'] != '102']
    pg.click('#bRefresh'); pg.wait_for_timeout(1500)
    check(pg.locator('.mpb-n').count() == 3 and pg.locator('.mpb-n.ok').count() == 1, 'checked in: the stop ticks off and stays on the route')
    check(pg.evaluate("JSON.parse(localStorage.getItem('rfPinMpbDay')).ids") == ['102', '110', '109'], 'the order stays the same through the day')
    pg.locator('.mpb-n.next').click(); pg.wait_for_timeout(500)
    t = pg.inner_text('.leaflet-popup')
    check('stop 2 of 3' in t.lower() and 'Ziwa Borehole' in t and 'Report the visit' in t and 'Directions' in t, 'next stop popup: ' + t.replace('\n', ' | '))
    shot(pg, '03-route-ticked-popup.png')
    pg.evaluate("document.querySelector('.leaflet-popup-close-button').click()"); pg.wait_for_timeout(300)
    # follow me
    fol(pg); pg.wait_for_timeout(1600)
    check(pg.locator('#mpbFol[aria-pressed=true]').count() == 1, 'follow me is on')
    check(pg.locator('#mpbNext').is_visible() and 'next stop · 2 of 3' in pg.inner_text('#mpbNext').lower() and 'Ziwa Borehole' in pg.inner_text('#mpbNext'), 'the next stop and how far: ' + pg.inner_text('#mpbNext').replace('\n', ' | '))
    check(re.search(r'≈ [\d.]+ km', pg.inner_text('#mpbNext')) is not None, 'with a distance')
    check(pg.locator('.mpb-me').count() == 1 and pg.locator('#nearSheet').is_hidden(), 'your dot; the next-stop card takes the place of Nearest to you')
    c = pg.evaluate("(function(){var m=document.querySelector('#hMap').getBoundingClientRect(),e=document.querySelector('.mpb-me').getBoundingClientRect();return [Math.round(e.x+e.width/2-m.x-m.width/2),Math.round(e.y+e.height/2-m.y-m.height/2)]})()")
    check(abs(c[0]) < 4 and abs(c[1]) < 4, 'you are in the middle of the map: %s' % c)
    shot(pg, '04-follow.png')
    # the phone's compass says we face east-north-east: the map turns so that is up
    pg.evaluate("window.dispatchEvent(new DeviceOrientationEvent('ondeviceorientationabsolute' in window ? 'deviceorientationabsolute' : 'deviceorientation', {alpha: 300, beta: 0, gamma: 0, absolute: true}))"); pg.wait_for_timeout(900)
    tf = pg.evaluate("(document.querySelector('.mpb-rot')||{style:{}}).style.transform")
    check(tf == 'rotate(-60deg)', 'heading-up: the map turns by the heading (%s)' % tf)
    check(pg.locator('#mpbCmp').is_visible(), 'the compass shows while the map is turned')
    un = pg.evaluate("getComputedStyle(document.querySelector('.mpb-n .mpb-up')).rotate")
    check(un == '60deg', 'numbers on the map stay upright (%s)' % un)
    check(pg.evaluate("document.querySelectorAll('#hMap .leaflet-tile').length") > 6, 'tiles fill the turned corners')
    shot(pg, '05-follow-turned.png')
    pg.click('#mpbCmp'); pg.wait_for_timeout(800)
    check(pg.evaluate("document.querySelector('.mpb-rot').style.transform") in ('', 'rotate(0deg)', 'none') and pg.locator('#mpbCmp').is_hidden(), 'the compass turns the map back to north and hides')
    pg.evaluate("window.dispatchEvent(new DeviceOrientationEvent('ondeviceorientationabsolute' in window ? 'deviceorientationabsolute' : 'deviceorientation', {alpha: 200, absolute: true}))"); pg.wait_for_timeout(500)
    check(pg.evaluate("document.querySelector('.mpb-rot').style.transform") in ('', 'none'), 'and it stays north-up while following')
    check(pg.locator('#mpbFol[aria-pressed=true]').count() == 1, 'still following')
    # dragging the map stops following
    bb = pg.locator('#hMap').bounding_box(); x0, y0 = bb['x'] + bb['width'] / 2 - 60, bb['y'] + bb['height'] / 2 + 40
    pg.mouse.move(x0, y0); pg.mouse.down(); pg.mouse.move(x0 + 40, y0 + 30, steps=4); pg.mouse.move(x0 + 90, y0 + 60, steps=4); pg.mouse.up(); pg.wait_for_timeout(500)
    check(pg.locator('#mpbFol[aria-pressed=false]').count() == 1 and pg.locator('#mpbNext').is_hidden() and pg.locator('.mpb-me').count() == 0, 'dragging the map stops following')
    check(pg.locator('#nearSheet').is_visible(), 'Nearest to you is back')
    # Kiswahili
    pg.evaluate("localStorage.setItem('rfPinLang','sw')"); pg.reload(); pg.wait_for_timeout(1800)
    sheet(pg)
    t = pg.inner_text('.mpb-sh')
    check('Njia ya leo' in t and 'kwenye ramani' in t.lower() and 'zimekamilika' in t, 'the sheet in Kiswahili: ' + t.replace('\n', ' | '))
    shot(pg, '06-sheet-sw.png')
    close_sheet(pg)
    fol(pg); pg.wait_for_timeout(1500)
    check('kituo kijacho' in pg.inner_text('#mpbNext').lower(), 'next stop in Kiswahili: ' + pg.inner_text('#mpbNext').replace('\n', ' | '))
    shot(pg, '07-follow-sw.png')
    pg.click('#mpbNx'); pg.wait_for_timeout(300)
    check(pg.locator('#mpbFol[aria-pressed=false]').count() == 1, '× stops following')
    # with the map tab's pins (mpa…): a long press (here a right-click) on the turned map marks the spot under the
    # finger, and following stops for its card
    fol(pg); pg.wait_for_timeout(1200)
    pg.evaluate("window.dispatchEvent(new DeviceOrientationEvent('ondeviceorientationabsolute' in window ? 'deviceorientationabsolute' : 'deviceorientation', {alpha: 300, absolute: true}))"); pg.wait_for_timeout(900)
    check(pg.evaluate("document.querySelector('.mpb-rot').style.transform") == 'rotate(-60deg)' and pg.locator('#mpaCard').is_hidden(), 'turned while following; no pin card meanwhile')
    hb = pg.locator('#hMap').bounding_box(); mx, my = hb['x'] + hb['width'] / 2, hb['y'] + hb['height'] / 2
    pg.mouse.click(mx + 60, my - 90, button='right'); pg.wait_for_timeout(1000)
    sp = pg.locator('.mpa-spot').bounding_box(); got = (sp['x'] + sp['width'] / 2 - mx, sp['y'] + sp['height'] / 2 - my) if sp else (0, 0)
    # turned by -60°: the spot is the point that was under the finger, now seen north-up
    check(abs(got[0] - 107.9) < 5 and abs(got[1] - 7.0) < 5, 'the spot is where the finger was on the turned map (%.0f, %.0f)' % got)
    check(pg.locator('#mpbFol[aria-pressed=false]').count() == 1 and pg.locator('#mpaCard').is_visible() and pg.evaluate("document.querySelector('.mpb-rot').style.transform") in ('', 'none'), 'following stops for the spot’s card, north-up again')
    pg.click('#mpaCard [data-mpa=x]'); pg.wait_for_timeout(300)
    check(no_hscroll(pg, 390), 'no sideways scroll at 390 px')
    check(not errs, 'no page errors (field): %s' % errs[:3])
    # the app never asks for the team map for a field user (the server refuses it too: tests/map-layers.test.mjs)
    check(not any('teammap' in a for a in adm), 'a field user’s app never asks for the team map')
    ctx.close()

    # ===== 2. an admin: heat, years, team ===============================================================
    adm.clear()
    ctx, pg, st, errs = run(br, admin=True, name='Reid Anderson')
    pg.goto(BASE + '/pin#map'); pg.wait_for_timeout(2200)
    check(pg.locator('.mpb-n').count() == 0, 'no route when nothing is assigned to you today')
    sheet(pg)
    check(pg.locator('[data-mpb-heat]').count() == 3 and pg.locator('[data-mpb=years]').count() == 1 and pg.locator('[data-mpb=team][aria-checked=false]').count() == 1, 'office layers in the sheet; team is off by default')
    check('No visits for you today' in pg.inner_text('.mpb-sh'), 'route row says no visits today')
    seg = pg.locator('[data-mpb-heat]').all(); check(all(s.bounding_box()['height'] >= 44 for s in seg), 'heat buttons are 44 px tall')
    shot(pg, '10-sheet-admin.png')
    pg.click('[data-mpb-heat=repairs]'); pg.wait_for_timeout(500)
    close_sheet(pg)
    painted = pg.evaluate("(function(){var c=document.querySelector('.mpb-heat');if(!c||c.style.display==='none')return 0;var d=c.getContext('2d').getImageData(0,0,c.width,c.height).data,n=0;for(var i=3;i<d.length;i+=16)if(d[i]>0)n++;return n})()")
    check(painted > 500, 'repairs heat is painted (%d px)' % painted)
    check(pg.locator('#mpbLeg').is_visible() and 'Open repairs' in pg.inner_text('#mpbLeg') and '4 open repairs' in pg.inner_text('#mpbLeg'), 'legend: ' + pg.inner_text('#mpbLeg').replace('\n', ' | '))
    shot(pg, '11-heat-repairs.png')
    sheet(pg); pg.click('[data-mpb-heat=people]'); pg.wait_for_timeout(300); close_sheet(pg)
    check('People served' in pg.inner_text('#mpbLeg') and '20,500 people served at 14 sites' in pg.inner_text('#mpbLeg'), 'people heat legend: ' + pg.inner_text('#mpbLeg').replace('\n', ' | '))
    shot(pg, '12-heat-people.png')
    pg.click('#mpbLx'); pg.wait_for_timeout(300)
    check(pg.locator('#mpbLeg').is_hidden() and pg.evaluate("document.querySelector('.mpb-heat').style.display") == 'none', '× hides the heat map')
    # the time slider
    sheet(pg); pg.click('[data-mpb=years]'); pg.wait_for_timeout(400); close_sheet(pg); pg.wait_for_timeout(1500)
    done = [x for x in P if x['status'] == 'Completed' and x['year']]
    check(pg.locator('.mpb-y').count() == len(done), 'years: all %d completed projects (%d)' % (len(done), pg.locator('.mpb-y').count()))
    check(pins(pg) == 0, 'the usual pins and bubbles step aside (%d)' % pins(pg))
    check(pg.locator('#mpaChips').is_hidden() and pg.locator('#mpaKey').is_hidden() and pg.locator('#nearSheet').is_hidden(), 'and so do their filters, their key and Nearest to you')
    check(pg.locator('.mpb-pl').count() == 0, 'no repair pulses on the history view')
    check('By 2025' in pg.inner_text('#mpbTime') and '14 projects completed' in pg.inner_text('#mpbTime'), 'slider: ' + pg.inner_text('#mpbTime').replace('\n', ' | '))
    shot(pg, '13-years-all.png')
    pg.evaluate("var r=document.getElementById('mpbRange');r.value=2017;r.dispatchEvent(new Event('input'))"); pg.wait_for_timeout(600)
    n17 = len([x for x in done if x['year'] <= 2017])
    check(pg.locator('.mpb-y').count() == n17 and 'By 2017' in pg.inner_text('#mpbTime'), 'slide to 2017: %d projects' % pg.locator('.mpb-y').count())
    shot(pg, '14-years-2017.png')
    pg.click('#mpbPlay'); pg.wait_for_timeout(200)
    check(pg.locator('#mpbPlay[aria-pressed=true]').count() == 1, 'play')
    pg.clock.run_for(1350); pg.wait_for_timeout(400)
    check('By 2018' in pg.inner_text('#mpbTime') and pg.locator('.mpb-y').count() == n17 + 1, 'it steps to the next year (%s)' % pg.inner_text('#mpbYr'))
    check(pg.locator('.mpb-y.new').count() >= 1, 'the year’s new projects drop in, highlighted')
    shot(pg, '15-years-playing.png')
    pg.clock.run_for(1300 * 8); pg.wait_for_timeout(500)
    check('By 2025' in pg.inner_text('#mpbTime') and pg.locator('#mpbPlay[aria-pressed=false]').count() == 1, 'it stops at the last year')
    pg.click('#mpbTx'); pg.wait_for_timeout(600)
    check(pg.locator('.mpb-y').count() == 0 and pg.locator('#mpbTime').is_hidden() and pins(pg) == len(P), '× closes the slider; the usual pins return (%d)' % pins(pg))
    # live team
    sheet(pg); pg.click('[data-mpb=team]'); pg.wait_for_timeout(700); close_sheet(pg); pg.wait_for_timeout(1200)
    check(sum('view=teammap' in a for a in adm) == 1, 'team: asks the server once')
    check(pg.locator('.mpb-tm').count() == 3 and pg.locator('.mpb-tm.old').count() == 1, 'three initials markers, one faded (last seen over an hour ago)')
    lab = pg.locator('.mpb-tm em').all_inner_texts(); check(any(re.match(r'1[23] min ago$', x) for x in lab) and any(re.match(r'[34] min ago$', x) for x in lab), 'last seen labels: %s' % lab)
    shot(pg, '16-team.png')
    pg.locator('.mpb-tm[title="Bertin Mwangi"]').click(); pg.wait_for_timeout(500)
    check(pg.locator('.leaflet-popup a[href="tel:0722111222"]').count() == 1 and pg.locator('.leaflet-popup a[href="https://wa.me/254722111222"]').count() == 1, 'tap: Call and WhatsApp')
    shot(pg, '17-team-popup.png')
    pg.clock.run_for(181000); pg.wait_for_timeout(600)
    check(sum('view=teammap' in a for a in adm) == 2, 'refreshes every 3 minutes while it shows')
    # themes, with heat + team + route on together
    pg.evaluate("document.querySelector('.leaflet-popup-close-button') && document.querySelector('.leaflet-popup-close-button').click()")
    sheet(pg); pg.click('[data-mpb-heat=repairs]'); pg.wait_for_timeout(200)
    pg.evaluate("document.documentElement.setAttribute('data-theme','dark')"); pg.wait_for_timeout(200); shot(pg, '18-sheet-dark.png'); close_sheet(pg)
    shot(pg, '19-map-dark.png')
    pg.evaluate("document.documentElement.setAttribute('data-theme','sun')"); pg.wait_for_timeout(200); shot(pg, '20-map-sun.png')
    sheet(pg); shot(pg, '21-sheet-sun.png'); close_sheet(pg)
    pg.evaluate("document.documentElement.removeAttribute('data-theme')")
    # offline: the team needs a signal
    sheet(pg); pg.click('[data-mpb=team]'); pg.wait_for_timeout(200); ctx.set_offline(True); pg.click('[data-mpb=team]'); pg.wait_for_timeout(500)
    check('No signal' in pg.inner_text('body'), 'offline: says the team needs a connection')
    close_sheet(pg); ctx.set_offline(False)
    # the office switches in Kiswahili: nothing spills out of its button
    pg.evaluate("localStorage.setItem('rfPinLang','sw')"); pg.reload(); pg.wait_for_timeout(1800); sheet(pg)
    spill = pg.evaluate("Array.prototype.filter.call(document.querySelectorAll('.mpb-sh button, .mpb-sh b, .mpb-sh small'),function(e){return e.scrollWidth>e.clientWidth+1}).map(function(e){return e.textContent})")
    check('Ramani ya joto' in pg.inner_text('.mpb-sh') and not spill, 'office switches in Kiswahili fit (%s)' % spill)
    shot(pg, '22-sheet-admin-sw.png'); close_sheet(pg)
    pg.evaluate("localStorage.setItem('rfPinLang','en')"); pg.reload(); pg.wait_for_timeout(1500)
    check(no_hscroll(pg, 390), 'no sideways scroll at 390 px (admin)')
    check(not errs, 'no page errors (admin): %s' % errs[:3])
    ctx.close()

    # ===== 3. a computer at 1280 px =======================================================================
    ctx, pg, st, errs = run(br, admin=True, vp=(1280, 800), name='Reid Anderson')
    pg.goto(BASE + '/pin#map'); pg.wait_for_timeout(2200)
    sheet(pg); pg.click('[data-mpb-heat=people]'); pg.click('[data-mpb=years]'); pg.wait_for_timeout(300); close_sheet(pg); pg.wait_for_timeout(1500)
    pg.evaluate("var r=document.getElementById('mpbRange');r.value=2020;r.dispatchEvent(new Event('input'))"); pg.wait_for_timeout(800)
    shot(pg, '30-desktop-years-heat.png')
    sheet(pg); shot(pg, '31-desktop-sheet.png'); close_sheet(pg)
    check(no_hscroll(pg, 1280), 'no sideways scroll at 1280 px')
    check(not errs, 'no page errors (desktop): %s' % errs[:3])
    ctx.close()

    # ===== 4. reduced motion =============================================================================
    ctx, pg, st, errs = run(br, admin=False, reduce=True)
    pg.goto(BASE + '/pin#map'); pg.reload(); pg.wait_for_timeout(1800)
    check(pg.evaluate('window.__drops') == 0, 'reduced motion: pins don’t drop')
    an = pg.evaluate("getComputedStyle(document.querySelector('.mpb-pl i')).animationName")
    check(an == 'none', 'reduced motion: no pulse (%s)' % an)
    fol(pg); pg.wait_for_timeout(500)
    pg.evaluate("window.dispatchEvent(new DeviceOrientationEvent('ondeviceorientationabsolute' in window ? 'deviceorientationabsolute' : 'deviceorientation', {alpha: 300, absolute: true}))"); pg.wait_for_timeout(200)
    tr_ = pg.evaluate("getComputedStyle(document.querySelector('.mpb-rot')).transitionDuration")
    check(tr_ in ('0s', '0s, 0s'), 'reduced motion: the map turns without sliding (%s)' % tr_)
    shot(pg, '40-reduced-follow.png')
    check(not errs, 'no page errors (reduced motion): %s' % errs[:3])
    ctx.close()
    br.close()

print('\n%d problem(s)' % len(problems))
for x in problems: print(' -', x)
