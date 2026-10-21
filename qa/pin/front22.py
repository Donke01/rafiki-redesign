# Site visits by type of project: schools, clinics, children's homes, farms, building sites and water.
import json, os, re
from playwright.sync_api import sync_playwright

HERE = os.path.dirname(os.path.abspath(__file__))
BASE = 'http://localhost:8797'
OUT = os.path.join(HERE, 'types'); os.makedirs(OUT, exist_ok=True)
P = [
    {'id': '201', 'title': 'Enyuata Primary School Kinder Classroom', 'country': 'Kenya', 'ck': 'ke', 'area': 'Narok', 'type': 'School', 'status': 'Completed', 'gps': [-1.08, 35.86]},
    {'id': '202', 'title': 'Nzoia Bridge Primary School - 6 Classrooms', 'country': 'Kenya', 'ck': 'ke', 'area': 'Bungoma', 'type': 'School', 'status': 'Under construction', 'gps': [0.56, 34.6]},
    {'id': '203', 'title': 'Kaptebee Pigsty', 'country': 'Kenya', 'ck': 'ke', 'area': 'Nandi', 'type': 'Farming', 'status': 'Completed', 'gps': [0.2, 35.1],},
    {'id': '204', 'title': 'Chepkongi Dispensary - Beds, Medical Equipment', 'country': 'Kenya', 'ck': 'ke', 'area': 'Eldoret', 'type': 'Health', 'status': 'Completed', 'gps': [0.51, 35.27]},
    {'id': '205', 'title': 'Tamiha Orphanage', 'country': 'Kenya', 'ck': 'ke', 'area': 'Kitale', 'type': 'Orphanage', 'status': 'Completed', 'gps': [1.0, 35.0]},
    {'id': '206', 'title': 'Kapsaret Borehole', 'country': 'Kenya', 'ck': 'ke', 'area': 'Eldoret', 'type': 'Borehole well', 'status': 'Completed', 'gps': [0.47, 35.25], 'pump': 'Manual'},
]
posts, problems = [], []
PREV = {'203': [{'at': '2026-09-20T09:00:00Z', 'by': 'Meril', 'state': 'working', 'numbers': {'animals': 14}}]}

def check(cond, what):
    print(('ok   ' if cond else 'FAIL ') + what)
    if not cond: problems.append(what)

def api(route, req):
    u = req.url
    if req.method == 'GET':
        if 'activity=' in u: return route.fulfill(json={'ok': True, 'activity': []})
        m = re.search(r'[?&]id=(\d+)', u)
        if m: return route.fulfill(json={'ok': True, 'last': None, 'canUndo': False, 'visits': PREV.get(m.group(1), [])})
        if 'history=' in u: return route.fulfill(json={'ok': True, 'notes': []})
        return route.fulfill(json={'ok': True, 'who': 'Meril Kiprop', 'admin': False, 'reviewOpen': 0, 'projects': P, 'repairs': {}, 'visits': {}, 'jobs': [], 'assigned': [], 'confirmed': {}})
    posts.append(json.loads(req.post_data)); return route.fulfill(json={'ok': True, 'updateId': '9003'})

with sync_playwright() as p:
    br = p.chromium.launch()
    ctx = br.new_context(viewport={'width': 390, 'height': 844}, is_mobile=True, has_touch=True, service_workers='block',
                         geolocation={'latitude': 0.2, 'longitude': 35.1, 'accuracy': 8}, permissions=['geolocation'])
    pg = ctx.new_page(); errs = []
    pg.on('pageerror', lambda e: errs.append(str(e)))
    pg.route('**/api/pin-auth/me', lambda r, q: r.fulfill(json={'available': False}))
    pg.route('**/api/pin-photo', lambda r, q: r.fulfill(json={'ok': True}))
    pg.route(re.compile(r'/api/pin(\?.*)?$'), api)
    pg.route('**/*.openstreetmap.org/**', lambda r, q: r.fulfill(status=200, content_type='image/png', body=open(os.path.join(HERE, 'tile.png'), 'rb').read()))
    pg.goto(BASE + '/pin'); pg.wait_for_timeout(800)
    for d in '9999': pg.click('.pk[data-k="%s"]' % d)
    pg.wait_for_timeout(1200); pg.evaluate("localStorage.setItem('rfPinOnb','1')"); pg.goto(BASE + '/pin#list'); pg.wait_for_timeout(1500)

    check('Which project' in pg.inner_text('body'), 'home asks "Which project today?"')
    pg.click('.chips .chip[data-f=all]'); pg.wait_for_timeout(500)
    chips = pg.locator('#typeChips .tchip').all_inner_texts()
    check(any(c.startswith('Schools') for c in chips) and any(c.startswith('Farming') for c in chips) and any(c.startswith('Water') for c in chips), 'type filters: ' + ' | '.join(chips))
    pg.locator('#typeChips').scroll_into_view_if_needed(); pg.screenshot(path=os.path.join(OUT, '01-type-filters.png'))
    pg.click('#typeChips [data-t=school]'); pg.wait_for_timeout(500)
    check(pg.locator('#list .row').count() == 2, 'Schools filter shows the 2 schools')
    pg.click('#typeChips [data-t=""]'); pg.wait_for_timeout(400)

    def visit(pid):
        pg.evaluate("id => { location.hash = 'list'; }", pid); pg.wait_for_timeout(300)
        pg.click('#list .row[data-id="%s"]' % pid); pg.wait_for_timeout(900)
        pg.click('#tabVisit'); pg.wait_for_timeout(700)
        pg.click('[data-act=next]'); pg.wait_for_timeout(400)  # skip check-in

    # farm: checklist, numbers compared with the last visit, carer, photos
    visit('203')
    pg.click('[data-state=working]'); pg.wait_for_timeout(500)
    t = pg.inner_text('#vw')
    check('Pen or coop' in t and 'Feed and water for the animals' in t and 'Pump' not in t, 'farm checklist')
    pg.screenshot(path=os.path.join(OUT, '02-farm-checklist.png'), full_page=False)
    pg.click('[data-act=next]'); pg.wait_for_timeout(500)
    t = pg.inner_text('#vw')
    check('Numbers' in t and 'Animals now' in t and 'Water tests' not in t, 'farm numbers, no water tests')
    pg.fill('[data-p="n.animals"]', '18'); pg.wait_for_timeout(200)
    check('14 → 18' in pg.inner_text('#vw'), 'animals compared with the last visit')
    pg.fill('[data-p="n.born"]', '6'); pg.fill('[data-p="n.income"]', '12000')
    pg.screenshot(path=os.path.join(OUT, '03-farm-numbers.png'))
    pg.click('[data-act=next]'); pg.wait_for_timeout(500)
    t = pg.inner_text('#vw')
    check('The animals' in t and 'The pen or coop' in t and 'Water flowing' not in t and 'test strip' not in t, 'farm photos')
    pg.screenshot(path=os.path.join(OUT, '04-farm-photos.png'))
    pg.click('[data-act=next]'); pg.wait_for_timeout(500)
    t = pg.inner_text('#vw')
    check('Who looks after it' in t and 'Farmer' in t and 'Water committee' not in t, 'farm: who looks after it')
    pg.fill('[data-p="c.chair"]', 'Julius'); pg.fill('[data-p="c.phone"]', '0712000000')
    pg.screenshot(path=os.path.join(OUT, '05-farm-carer.png'))
    pg.click('[data-act=next]'); pg.wait_for_timeout(500)
    pg.click('[data-act=send]'); pg.wait_for_timeout(1500)
    b = [x for x in posts if x.get('action') == 'visit'][-1]
    check(b.get('kind') == 'farm' and b['numbers'] == {'animals': 18, 'born': 6, 'income': 12000} and b['carer']['name'] == 'Julius', 'farm visit sent: %s %s %s' % (b.get('kind'), b.get('numbers'), b.get('carer')))

    # building site: stage, construction checklist and states
    visit('202')
    t = pg.inner_text('#vw')
    check('How is the building going' in t and 'On track' in t and 'Work stopped' in t, 'construction states')
    pg.click('[data-state=repair]'); pg.wait_for_timeout(500)
    t = pg.inner_text('#vw')
    check('Stage the building has reached' in t and 'Materials on site' in t and 'On schedule' in t, 'construction checklist with stage')
    pg.screenshot(path=os.path.join(OUT, '06a-stage.png')); pg.click('[data-seg=stage][data-v=roof]'); pg.wait_for_timeout(300)
    pg.click('[data-ck=schedule][data-v=bad]'); pg.wait_for_timeout(300)
    pg.screenshot(path=os.path.join(OUT, '06-build-checklist.png'))
    pg.click('[data-act=next]'); pg.wait_for_timeout(400)
    check('Workers on site today' in pg.inner_text('#vw'), 'construction numbers')
    pg.fill('[data-p="n.workers"]', '9')
    pg.click('[data-act=next]'); pg.wait_for_timeout(400)
    check('The whole building now' in pg.inner_text('#vw'), 'construction photos')
    pg.screenshot(path=os.path.join(OUT, '07-build-photos.png'))
    pg.click('[data-act=next]'); pg.wait_for_timeout(400)
    check('Fundi or site foreman' in pg.inner_text('#vw'), 'construction: fundi')
    pg.click('[data-act=next]'); pg.wait_for_timeout(400)
    check('Roof' in pg.inner_text('#vw'), 'review shows the stage')
    pg.screenshot(path=os.path.join(OUT, '08-build-review.png'))
    pg.click('[data-act=send]'); pg.wait_for_timeout(1500)
    b = [x for x in posts if x.get('action') == 'visit'][-1]
    check(b.get('kind') == 'build' and b.get('stage') == 'roof' and 'On schedule' in b['problems'] and b['numbers'] == {'workers': 9}, 'construction visit sent')

    # school, clinic, children's home: their own checklists
    for pid, want, shot in [('201', 'Desks and furniture', '09-school'), ('204', 'Medicines and supplies', '10-clinic'), ('205', 'Beds, mattresses and bedding', '11-home')]:
        visit(pid); pg.click('[data-state=working]'); pg.wait_for_timeout(500)
        t = pg.inner_text('#vw'); check(want in t and 'Pump' not in t, shot + ' checklist')
        pg.screenshot(path=os.path.join(OUT, shot + '-checklist.png'))
        if pid == '205':
            pg.click('[data-act=next]'); pg.wait_for_timeout(300); pg.click('[data-act=next]'); pg.wait_for_timeout(400)
            check('no close-up faces' in pg.inner_text('#vw'), "children's home photo reminder")
            pg.screenshot(path=os.path.join(OUT, '12-home-photos.png'))

    # water is unchanged
    visit('206'); pg.click('[data-state=working]'); pg.wait_for_timeout(500)
    check('Pump handle and pivot pins' in pg.inner_text('#vw'), 'hand pump checklist unchanged')
    pg.click('[data-act=next]'); pg.wait_for_timeout(400)
    check('Pump strokes to fill 20 L' in pg.inner_text('#vw'), 'water readings unchanged')
    pg.click('[data-act=next]'); pg.wait_for_timeout(300); pg.click('[data-act=next]'); pg.wait_for_timeout(400)
    check('Water committee' in pg.inner_text('#vw'), 'water committee unchanged')

    check(not errs, 'no page errors: %s' % errs[:3])
    br.close()
print('PROBLEMS:', problems)
