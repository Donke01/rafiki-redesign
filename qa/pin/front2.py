import json, os, re
from playwright.sync_api import sync_playwright

HERE = os.path.dirname(os.path.abspath(__file__))
BASE = 'http://localhost:8797'
PROJECTS = [
    {'id': '101', 'title': 'Chepkongi Dispensary', 'country': 'Kenya', 'ck': 'ke', 'area': 'Eldoret', 'type': 'health', 'status': 'Completed', 'gps': [0.5143, 35.2698], 'photo': '555'},
    {'id': '102', 'title': 'Kiptoo Well', 'country': 'Kenya', 'ck': 'ke', 'area': 'Nandi', 'type': 'borehole', 'status': 'Completed', 'gps': None, 'photo': None},
    {'id': '103', 'title': 'Arusha School', 'country': 'Tanzania', 'ck': 'tz', 'area': 'Arusha', 'type': 'school', 'status': 'Completed', 'gps': [-3.3869, 36.6830], 'photo': None},
    {'id': '104', 'title': 'Kapsaret Borehole', 'country': 'Kenya', 'ck': 'ke', 'area': 'Eldoret', 'type': 'borehole', 'status': 'Completed', 'gps': [0.47, 35.25], 'photo': None},
    {'id': '105', 'title': 'Turbo Spring', 'country': 'Kenya', 'ck': 'ke', 'area': 'Turbo', 'type': 'borehole', 'status': 'Completed', 'gps': [0.63, 35.05], 'photo': None},
]
posts, files, problems = [], [], []
state = {'offline': False, 'last': None, 'repairs': {'104': {'state': 'repair', 'by': 'Bertin', 'at': '2026-10-18T08:00:00Z', 'note': 'Handle loose', 't': 'Kapsaret Borehole'}}}
ACT = [{'by': 'Meril', 'a': 'pin', 'id': '101', 't': 'Chepkongi Dispensary', 'at': '2026-10-18T08:00:00Z'},
       {'by': 'Bertin', 'a': 'visit', 's': 'repair', 'id': '104', 't': 'Kapsaret Borehole', 'at': '2026-10-18T09:00:00Z'}]
PINS = {'1111': 'Meril Kiprop', '4321': ''}

def check(cond, what):
    print(('ok   ' if cond else 'FAIL ') + what)
    if not cond: problems.append(what)

def api(route, req):
    if state['offline']: return route.abort('internetdisconnected')
    who = PINS.get(req.headers.get('x-pin'))
    if who is None: return route.fulfill(status=401, json={'ok': False, 'reason': 'pin'})
    if req.method == 'GET':
        u = req.url
        if 'history=' in u:
            return route.fulfill(json={'ok': True, 'notes': [
                {'id': '1', 'text': '🩺 Site visit by Bertin (Rafiki pin app): ⚠️ Needs repair. Notes: Handle loose.', 'at': '2026-10-18T09:00:00Z', 'by': 'Morgan', 'files': [{'name': 'p.jpg', 'ext': 'jpg', 'url': BASE + '/img/pin/icon-192.png'}, {'name': 'v.webm', 'ext': 'webm', 'url': BASE + '/x.webm'}]}]})
        if 'activity=' in u: return route.fulfill(json={'ok': True, 'activity': ACT})
        if 'id=' in u: return route.fulfill(json={'ok': True, 'last': state['last'], 'canUndo': True, 'visits': [{'at': '2026-03-12T08:00:00Z', 'by': 'Bertin', 'state': 'working', 'readings': {'flow': 12, 'strokes': None}}]})
        return route.fulfill(json={'ok': True, 'who': who, 'projects': PROJECTS, 'repairs': state['repairs']})
    b = json.loads(req.post_data); posts.append(b); a = b.get('action')
    if a == 'pin':
        state['last'] = {'value': '%.6f, %.6f' % (b['lat'], b['lng']), 'prev': '', 'by': who or b['by'], 'at': '2026-10-19T09:00:00Z'}
        return route.fulfill(json={'ok': True, 'updateId': '9001', 'gps': [b['lat'], b['lng']]})
    if a == 'undo':
        state['last'] = None; return route.fulfill(json={'ok': True, 'gps': None, 'value': '', 'updateId': '9002'})
    return route.fulfill(json={'ok': True, 'updateId': '9003', 'id': '555'})

def upload(route, req):
    if state['offline']: return route.abort('internetdisconnected')
    body = req.post_data_buffer or b''
    m = re.search(rb'filename="([^"]+)"', body)
    lb = re.search(rb'name="label"\r\n\r\n([^\r]+)', body)
    files.append((m.group(1).decode() if m else '?', len(body), lb.group(1).decode() if lb else ''))
    route.fulfill(json={'ok': True})

TILE = open(os.path.join(HERE, 'tile.png'), 'rb').read()

with sync_playwright() as p:
    br = p.chromium.launch(args=['--use-fake-device-for-media-stream', '--use-fake-ui-for-media-stream'])
    ctx = br.new_context(viewport={'width': 390, 'height': 844}, is_mobile=True, has_touch=True, service_workers='block',
                         geolocation={'latitude': 0.5150, 'longitude': 35.2700, 'accuracy': 8}, permissions=['geolocation', 'microphone'])
    pg = ctx.new_page()
    errs = []
    pg.on('pageerror', lambda e: errs.append(str(e)))
    pg.on('console', lambda m: errs.append(m.text) if m.type == 'error' and 'Failed to load resource' not in m.text and 'Content Security' not in m.text else None)
    pg.on('dialog', lambda d: d.accept())
    pg.route('**/api/pin-photo', upload)
    pg.route(re.compile(r'/api/pin(\?.*)?$'), api)
    pg.route(re.compile(r'/api/photo/'), lambda r, q: r.fulfill(status=200, content_type='image/png', body=open(os.path.join(HERE, 'big.jpg'), 'rb').read()))
    pg.route('**/tile.openstreetmap.org/**', lambda r, q: r.fulfill(status=200, content_type='image/png', body=TILE))
    pg.route('**/server.arcgisonline.com/**', lambda r, q: r.fulfill(status=200, content_type='image/png', body=TILE))

    pg.goto(BASE + '/pin')
    check(not pg.is_visible('#tabs'), 'no tab bar when locked')
    # shared team PIN without a name -> asks for name
    pg.fill('#iPin', '4321'); pg.click('#fLock button[type=submit]'); pg.wait_for_timeout(600)
    check('add your name' in pg.inner_text('#mLock'), 'team PIN asks for a name: ' + pg.inner_text('#mLock'))
    # personal PIN -> name from server
    pg.fill('#iPin', '1111'); pg.click('#fLock button[type=submit]')
    pg.wait_for_selector('#vMap:not(.hidden)')
    pg.wait_for_timeout(1500)
    check(pg.inner_text('#who') == 'Meril Kiprop', 'personal PIN names the person: ' + pg.inner_text('#who'))
    check(pg.is_visible('#tabs'), 'tab bar shows')
    check('3 of 5' in pg.inner_text('#mProg') or '4 of 5' in pg.inner_text('#mProg'), 'progress on map: ' + pg.inner_text('#mProg'))
    check(pg.locator('#hMap path.leaflet-interactive').count() == 4, 'map dots for pinned projects: %d' % pg.locator('#hMap path.leaflet-interactive').count())
    check('Chepkongi' in pg.inner_text('#mHere'), "I'm-here banner: " + pg.inner_text('#mHere')[:80])
    pg.click('#nearHd'); pg.wait_for_timeout(200)
    check(pg.locator('#nearList .row').count() == 4, 'nearest on the map sheet (up to 5; 4 pinned here)')
    pg.screenshot(path=os.path.join(HERE, 'n1c-near.png'))
    pg.click('#nearHd')
    pg.screenshot(path=os.path.join(HERE, 'n1-map.png'))
    # popup
    pg.locator('#hMap path.leaflet-interactive[fill="#D98200"]').click(force=True); pg.wait_for_timeout(400)
    check(pg.locator('.leaflet-popup .pop').count() == 1, 'tap a dot shows a popup')
    pg.screenshot(path=os.path.join(HERE, 'n1b-popup.png'))
    pg.locator('.leaflet-popup [data-open]').click(); pg.wait_for_timeout(500)
    check(pg.is_visible('#vProj'), 'popup opens the project')
    pg.click('#hBack'); pg.wait_for_timeout(300)
    check(pg.is_visible('#vMap'), 'back returns to the map')

    # "I'm here" -> report a visit
    pg.locator('#mHere [data-here=visit]').click(); pg.wait_for_timeout(500)
    check(pg.is_visible('#paneVisit') and pg.inner_text('#pTitle') == 'Chepkongi Dispensary', 'banner opens the visit form')
    check(pg.is_visible('#pHero'), 'project photo shown')
    W = lambda: pg.wait_for_timeout(400)
    check(pg.locator('.wiz-steps button').count() == 7, 'seven visit steps')
    pg.click('[data-act=arrive]'); pg.wait_for_timeout(800)
    check('Arrived' in pg.inner_text('#vw') and '81 m' in pg.inner_text('#vw'), 'check-in records time and distance: ' + pg.inner_text('.wiz')[:80])
    pg.screenshot(path=os.path.join(HERE, 'v1-arrive.png'))
    pg.click('[data-act=next]'); W()
    pg.click('[data-state=working]'); W()
    check(pg.locator('.cl-row').count() == 6, 'clinic checklist (building): %d' % pg.locator('.cl-row').count())
    pg.click('[data-ck=handwash][data-v=ok]'); pg.click('[data-ck=latrines][data-v=bad]'); W()
    check(pg.locator('.repairbox').count() == 1, 'a problem turns it into needs-repair with repair details')
    pg.click('[data-seg=urgency][data-v=urgent]'); pg.fill('[data-p=parts]', 'door hinges'); pg.fill('[data-p=cost]', '2500')
    pg.screenshot(path=os.path.join(HERE, 'v2-check.png'), full_page=True)
    # draft survives a reload
    pg.wait_for_timeout(700); pg.reload(); pg.wait_for_timeout(1800)
    check(pg.is_visible('#paneVisit') and pg.locator('.cl-row.bad').count() == 1 and pg.input_value('[data-p=parts]') == 'door hinges', 'draft kept after reload')
    pg.click('[data-act=next]'); W()
    pg.fill('[data-p="r.flow"]', '9'); W()
    check('worse' in pg.inner_text('#cmp_flow'), 'compared with last visit: ' + pg.inner_text('#cmp_flow'))
    pg.click('[data-seg="r.clarity"][data-v=cloudy]'); W()
    pg.locator('.wiz details.more summary').click(); pg.click('[data-seg="q.ecoli"][data-v=pos]'); pg.fill('[data-p="q.fluoride"]', '2.1')
    pg.screenshot(path=os.path.join(HERE, 'v3-read.png'), full_page=True)
    pg.click('[data-act=next]'); W()
    pg.set_input_files('#sl_flow', os.path.join(HERE, 'big.jpg')); pg.wait_for_timeout(700)
    pg.set_input_files('#sl_damage', os.path.join(HERE, 'big.jpg')); pg.wait_for_timeout(700)
    check(pg.locator('.slot.has').count() == 2, 'guided photos taken')
    pg.locator('[data-act=rec]').click(); pg.wait_for_timeout(1500); pg.locator('[data-act=recstop]').click(); pg.wait_for_timeout(600)
    check(pg.locator('#wVoice audio').count() == 1, 'voice note recorded')
    pg.screenshot(path=os.path.join(HERE, 'v4-photos.png'), full_page=True)
    pg.click('[data-act=next]'); W()
    pg.click('[data-seg="c.active"][data-v=yes]'); pg.fill('[data-p="c.chair"]', 'Mary Chebet'); pg.fill('[data-p="c.fund"]', '3000')
    pg.fill('[data-p="story.quote"]', 'Now the nurses can wash their hands'); pg.check('[data-p="story.consent"]')
    pg.screenshot(path=os.path.join(HERE, 'v5-people.png'), full_page=True)
    pg.click('[data-act=next]'); W()
    pg.fill('[data-p=note]', 'Handle loose')
    pg.screenshot(path=os.path.join(HERE, 'v6-review.png'), full_page=True)
    pg.click('[data-act=send]'); pg.wait_for_timeout(2000)
    b = posts[-1]
    print('    BODY', {k: b[k] for k in ['state', 'urgency', 'problems', 'checked', 'parts', 'cost', 'readings', 'quality', 'committee', 'story']})
    check(b['action'] == 'visit' and b['state'] == 'repair' and b['urgency'] == 'urgent' and b['problems'] == ['Latrines clean and usable'] and b['cost'] == 2500, 'visit details sent')
    check(b['readings'].get('flow') == 9 and b['quality'].get('ecoli') == 'pos' and b['committee'].get('chair') == 'Mary Chebet' and b['story'].get('consent') is True and b.get('arrive'), 'readings, tests, committee, story, check-in sent')
    check(len(files) == 3 and files[-1][0].startswith('voice.'), 'two photos + voice uploaded: ' + str(files))
    check(pg.locator('.done-card').count() == 1, 'done screen')
    pg.screenshot(path=os.path.join(HERE, 'v7-done.png'))
    check(pg.locator('[data-act=share]').count() == 1, 'share button')
    check(pg.locator('#pRepair .trk').count() == 1, 'repair tracker now on the project')

    # history
    pg.click('#tabHist'); pg.wait_for_timeout(600)
    check(pg.locator('#hList .note').count() == 1 and pg.locator('#hList audio').count() == 1 and pg.locator('#hList img').count() == 1, 'history with photo + audio')
    pg.screenshot(path=os.path.join(HERE, 'n3-history.png'), full_page=True)

    # mark fixed
    pg.click('#bFixed'); pg.fill('#fixNote', 'New handle'); pg.click('#bFixed'); pg.wait_for_timeout(800)
    check(posts[-1]['action'] == 'fixed' and posts[-1]['note'] == 'New handle', 'mark fixed sent')
    check('Fixed ✓' in pg.inner_text('#pRepair') and pg.locator('#pRepair li.done').count() == 4, 'fixed shown')

    # pin with photos, undo
    pg.click('#tabPin'); pg.wait_for_timeout(300)
    pg.locator('#pMap').click(position={'x': 120, 'y': 90}); pg.wait_for_timeout(200)
    pg.set_input_files('#pPf', os.path.join(HERE, 'big.jpg')); pg.wait_for_timeout(600)
    pg.click('#bSave'); pg.click('#bYes'); pg.wait_for_timeout(1500)
    check('Saved to monday.com' in pg.inner_text('#mSave'), 'pin saved')
    check(pg.locator('#pLinks a:has-text("Apple Maps")').count() == 1, 'Apple Maps still there')
    pg.click('#bUndo'); pg.wait_for_timeout(700)
    check(posts[-1]['action'] == 'undo', 'undo')

    # list: progress, repairs chip, route planner
    pg.click('#tabs [data-v=vList]'); pg.wait_for_timeout(400)
    check(pg.locator('#cProg > div').count() == 2, 'per-country progress')
    pg.click('[data-f=repairs]'); pg.wait_for_timeout(200)
    check(pg.locator('#list .row').count() == 1 and 'Kapsaret' in pg.inner_text('#list'), 'repairs chip lists open repairs')
    pg.screenshot(path=os.path.join(HERE, 'n4-repairs.png'), full_page=True)
    check(pg.input_value('#iCountry') == 'ke' and pg.inner_text('#count').startswith('Kenya'), 'list starts with the home country: ' + pg.inner_text('#count'))
    pg.click('[data-f=due]'); pg.wait_for_timeout(200)
    check(pg.locator('#list .row').count() >= 1 and ('No visit yet' in pg.inner_text('#list') or 'mo ago' in pg.inner_text('#list')), 'due-a-visit list')
    pg.screenshot(path=os.path.join(HERE, 'n4b-due.png'))
    pg.select_option('#iCountry', '')
    pg.click('[data-f=all]'); pg.click('#bRouteMode'); pg.wait_for_timeout(200)
    for t in ['Turbo Spring', 'Arusha School', 'Kapsaret Borehole']:
        pg.locator('#list .row', has_text=t).click()
    check('3 stops' in pg.inner_text('#routeN'), 'route bar counts stops')
    pg.screenshot(path=os.path.join(HERE, 'n5-pick.png'))
    pg.click('#bRouteGo'); pg.wait_for_timeout(600)
    stops = pg.locator('#routeStops .t').all_inner_texts()
    check(sorted(stops) == ['Arusha School', 'Kapsaret Borehole', 'Turbo Spring'] and stops[-1] == 'Arusha School', 'route in a sensible order: ' + str(stops))
    href = pg.get_attribute('#routeLegs a', 'href') or ''
    check('waypoints=' in href and 'destination=-3.386900,36.683000' in href, 'Google Maps route link')
    pg.screenshot(path=os.path.join(HERE, 'n6-route.png'), full_page=True)
    pg.locator('#routeStops [data-open]').first.click(); pg.wait_for_timeout(300); pg.click('#hBack'); pg.wait_for_timeout(300)
    check(pg.is_visible('#vRoute'), 'back from a stop returns to the route')

    # new site with photo + voice
    pg.click('#tabs [data-v=vNew]'); pg.wait_for_timeout(400)
    pg.fill('#nName', 'Kiptoo Primary'); pg.click('#nPaste'); pg.fill('#nPasteIn', '0.2, 35.1'); pg.click('#nPasteGo')
    pg.click('#tabs [data-v=vMe]'); pg.wait_for_timeout(300); pg.click('#tabs [data-v=vNew]'); pg.wait_for_timeout(300)
    check(pg.input_value('#nName') == 'Kiptoo Primary', 'new-site form kept when switching tabs')
    pg.click('#bNewSave'); pg.wait_for_timeout(1000)
    check(posts[-1]['action'] == 'new' and 'Due Diligence' in pg.inner_text('#mNew'), 'new site')

    # monthly report
    pg.click('#tabs [data-v=vMe]'); pg.wait_for_timeout(400)
    pg.click('#repOpen'); pg.wait_for_timeout(800)
    check(pg.is_visible('#vReport') and pg.locator('.kpi').count() == 8 and 'Field report' in pg.inner_text('#repBody'), 'monthly report')
    pg.screenshot(path=os.path.join(HERE, 'r1-report.png'), full_page=True)
    check(not pg.evaluate('document.documentElement.scrollWidth > innerWidth'), 'report fits the phone width')
    pg.emulate_media(media='print'); pg.pdf(path=os.path.join(HERE, 'r1-report.pdf'), format='A4', print_background=True); pg.emulate_media(media='screen')
    pg.pdf(path=os.path.join(HERE, 'r1-report.pdf')) if False else None
    pg.click('#hBack'); pg.wait_for_timeout(300)
    # Kiswahili
    pg.locator('#segLang [data-v=sw]').click()
    pg.click('#tabs [data-v=vList]'); pg.locator('#list .row').first.click(); pg.wait_for_timeout(400); pg.click('#tabVisit'); pg.wait_for_timeout(400)
    check('Nimefika' in pg.inner_text('#vw'), 'visit form in Kiswahili')
    pg.screenshot(path=os.path.join(HERE, 'v8-sw.png'))
    pg.click('#tabs [data-v=vMe]'); pg.locator('#segLang [data-v=""]').click()
    # me: activity, theme, text size
    pg.click('#tabs [data-v=vMe]'); pg.wait_for_timeout(600)
    check(pg.locator('#actTable tbody tr').count() >= 1 or 'Nothing yet' in pg.inner_text('#actTable'), 'activity table')
    check('Your own PIN' in pg.inner_text('#mePin'), 'Me shows own PIN')
    pg.locator('#segTheme [data-v=dark]').click(); pg.locator('#segSize [data-v=l]').click(); pg.wait_for_timeout(200)
    check(pg.evaluate("document.documentElement.getAttribute('data-theme')") == 'dark', 'dark mode')
    check(pg.evaluate("getComputedStyle(document.documentElement).fontSize") == '18px', 'large text')
    pg.screenshot(path=os.path.join(HERE, 'n7-me-dark.png'), full_page=True)
    pg.click('#tabs [data-v=vMap]'); pg.wait_for_timeout(800)
    pg.screenshot(path=os.path.join(HERE, 'n8-map-dark.png'))
    pg.locator('#segTheme [data-v=sun]').count()
    pg.click('#tabs [data-v=vMe]'); pg.locator('#segTheme [data-v=sun]').click(); pg.locator('#segSize [data-v=""]').click()
    pg.click('#tabs [data-v=vList]'); pg.wait_for_timeout(300)
    pg.screenshot(path=os.path.join(HERE, 'n9-list-sun.png'))
    pg.click('#tabs [data-v=vMe]'); pg.locator('#segTheme [data-v=""]').click()

    # offline queue still works
    state['offline'] = True; ctx.set_offline(True)
    pg.click('#tabs [data-v=vList]'); pg.click('[data-f=all]'); pg.locator('#list .row', has_text='Turbo Spring').click() if pg.get_attribute('#bRouteMode', 'aria-pressed') == 'false' else None
    pg.wait_for_timeout(300); pg.click('#tabVisit'); pg.wait_for_timeout(300); pg.click('[data-act=next]'); pg.click('[data-state=working]'); pg.click('[data-go="6"]'); pg.click('[data-act=send]'); pg.wait_for_timeout(1200)
    check('Saved on this phone' in pg.inner_text('#vw'), 'offline queued')
    check(pg.is_visible('#tabDot'), 'tab bar shows waiting count')
    n = len(posts); state['offline'] = False; ctx.set_offline(False); pg.wait_for_timeout(2000)
    check(len(posts) == n + 1, 'sent when back online')
    check(not errs, 'no page errors: ' + str(errs[:3]))
    br.close()
print('problems:', len(problems))
