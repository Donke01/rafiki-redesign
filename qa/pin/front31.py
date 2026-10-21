# A project's photo timeline (same view, visit by visit), the donor-ready project summary, and "Water test due".
import io, json, os, re, datetime, subprocess
from playwright.sync_api import sync_playwright
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__)); BASE = os.environ.get('BASE', 'http://localhost:8812')
OUT = os.path.join(HERE, 'project'); os.makedirs(OUT, exist_ok=True)
now = datetime.datetime.now(datetime.timezone.utc)
iso = lambda days: (now - datetime.timedelta(days=days)).strftime('%Y-%m-%dT%H:%M:%SZ')
stamp = lambda days: (now - datetime.timedelta(days=days)).strftime('%Y-%m-%d-09-14')
P = [{'id': '104', 'title': 'Kapsaret Borehole', 'country': 'Kenya', 'ck': 'ke', 'area': 'Eldoret', 'type': 'Borehole well', 'status': 'Completed', 'year': 2019, 'gps': [0.47, 35.25], 'photo': 'main104', 'pump': 'Manual'},
     {'id': '105', 'title': 'Turbo Spring', 'country': 'Kenya', 'ck': 'ke', 'area': 'Turbo', 'type': 'Water collection', 'status': 'Completed', 'gps': [0.63, 35.05]},
     {'id': '106', 'title': 'Kiptoo Primary School', 'country': 'Kenya', 'ck': 'ke', 'area': 'Nandi', 'type': 'School', 'status': 'Completed', 'gps': [0.2, 35.1]},
     {'id': '107', 'title': 'Moiben Borehole', 'country': 'Kenya', 'ck': 'ke', 'area': 'Moiben', 'type': 'Borehole well', 'status': 'In Progress', 'gps': [0.8, 35.4]}]
VISITS = {'104': {'at': iso(40), 'by': 'Grace Achieng', 'state': 'repair'}, '105': {'at': iso(20), 'by': 'Meril Kiprop', 'state': 'working'}, '106': {'at': iso(10), 'by': 'Meril Kiprop', 'state': 'working'}}
TESTS = {'105': iso(30)}  # Turbo Spring was tested a month ago; Kapsaret never (in the app)
REPAIRS = {'104': {'state': 'repair', 'by': 'Grace Achieng', 'at': iso(40), 't': 'Kapsaret Borehole', 'urgency': 'month', 'problems': ['Handle loose'], 'parts': 'handle bush'}}
img = lambda n: BASE + '/qa-img/' + n + '.png'
NOTES = [
    {'id': '3', 'text': '🩺 Site visit by Grace Achieng (RAFIKI pin app): ⚠️ Needs repair. Urgency: This month.\nProblems found: Handle loose.\nReadings: people using it (estimate): 350.\nStory moment: “Now my girls get to school on time.” (Mary, mother of three). Consent to quote and photograph: given.',
     'at': iso(40), 'by': 'RAFIKI Pin', 'files': [
        {'name': 'site-photo-pump-or-tank-close-up-' + stamp(40) + '.jpg', 'ext': 'jpg', 'url': img('close-3')},
        {'name': 'site-photo-water-flowing-' + stamp(40) + '.jpg', 'ext': 'jpg', 'url': img('flow-3')},
        {'name': 'site-photo-the-site-with-people-' + stamp(40) + '.jpg', 'ext': 'jpg', 'url': img('site-3')},
        {'name': 'site-photo-any-damage-' + stamp(40) + '.jpg', 'ext': 'jpg', 'url': img('damage-3')},
        {'name': 'voice-note-' + stamp(40) + '.m4a', 'ext': 'm4a', 'url': BASE + '/qa-img/v.m4a'}]},
    {'id': '2', 'text': '🩺 Site visit by Bertin Mwangi (RAFIKI pin app): ✅ Working well.\nStory moment: “We used to walk two hours.” (Joseph). Consent to quote and photograph: not given.', 'at': iso(220), 'by': 'RAFIKI Pin', 'files': [
        {'name': 'site-photo-pump-or-tank-close-up-' + stamp(220) + '.jpg', 'ext': 'jpg', 'url': img('close-2')},
        {'name': 'site-photo-the-site-with-people-' + stamp(220) + '.jpg', 'ext': 'jpg', 'url': img('site-2')}]},
    {'id': '1', 'text': '📍 Location pinned with the RAFIKI pin app by Meril Kiprop: 0.47, 35.25 (GPS).', 'at': iso(400), 'by': 'RAFIKI Pin', 'files': [
        {'name': 'site-photo-' + stamp(400) + '.jpg', 'ext': 'jpg', 'url': img('pin-1')}]},
    {'id': '0', 'text': '🩺 Site visit by Meril Kiprop (RAFIKI pin app): ✅ Working well.', 'at': iso(420), 'by': 'RAFIKI Pin', 'files': [
        {'name': 'site-photo-pump-or-tank-close-up-' + stamp(420) + '.jpg', 'ext': 'jpg', 'url': img('close-1')}]},
]
COL = {'close': (10, 120, 194), 'flow': (28, 156, 90), 'site': (227, 154, 30), 'damage': (210, 60, 50), 'pin': (122, 79, 208), 'main104': (5, 79, 129)}
def png(name):
    base = name.split('-')[0]; im = Image.new('RGB', (640, 480), COL.get(base, (90, 96, 102))); d = ImageDraw.Draw(im)
    for i in range(0, 640, 40): d.line([(i, 480), (i + 200, 200)], fill=(255, 255, 255), width=3)
    d.rectangle([20, 20, 300, 80], fill=(255, 255, 255)); d.text((32, 40), name, fill=(0, 0, 0))
    b = io.BytesIO(); im.save(b, 'PNG'); return b.getvalue()
posts, problems = [], []
def check(c, w): print(('ok   ' if c else 'FAIL ') + w); (None if c else problems.append(w))
def api(route, req):
    u = req.url
    if req.method == 'GET':
        if 'activity=' in u: return route.fulfill(json={'ok': True, 'activity': []})
        if 'team=' in u: return route.fulfill(json={'ok': True, 'team': []})
        if 'history=104' in u: return route.fulfill(json={'ok': True, 'notes': NOTES})
        if 'history=' in u: return route.fulfill(json={'ok': True, 'notes': []})
        m = re.search(r'[?&]id=(\d+)', u)
        if m: return route.fulfill(json={'ok': True, 'last': None, 'canUndo': False, 'visits': [{'at': iso(40), 'by': 'Grace Achieng', 'state': 'repair', 'readings': {'users': 350, 'strokes': 30}}] if m.group(1) == '104' else []})
        return route.fulfill(json={'ok': True, 'who': 'Meril Kiprop', 'admin': True, 'reviewOpen': 0, 'projects': P, 'repairs': REPAIRS, 'visits': VISITS, 'tests': TESTS, 'jobs': [], 'assigned': [], 'confirmed': {}, 'me': {'kind': 'app', 'nick': '', 'phone': '', 'home': 'ke'}})
    posts.append(json.loads(req.post_data)); route.fulfill(json={'ok': True})
shot = lambda pg, n, **k: pg.screenshot(path=os.path.join(OUT, n), **k)
with sync_playwright() as p:
    br = p.chromium.launch(); ctx = br.new_context(viewport={'width': 390, 'height': 844}, is_mobile=True, has_touch=True, service_workers='block')
    pg = ctx.new_page(); errs = []; pg.on('pageerror', lambda e: errs.append(str(e)))
    pg.route('**/api/pin-auth/me', lambda r, q: r.fulfill(json={'available': False})); pg.route(re.compile(r'/api/pin(\?.*)?$'), api)
    pg.route('**/qa-img/**', lambda r, q: r.fulfill(body=png(q.url.rsplit('/', 1)[1].split('.')[0]), content_type='image/png'))
    pg.route('**/api/photo/**', lambda r, q: r.fulfill(body=png('main104'), content_type='image/png'))
    pg.route(re.compile(r'tile\.openstreetmap|google\.com/s2'), lambda r, q: r.fulfill(body=png('tile'), content_type='image/png'))
    pg.goto(BASE + '/pin'); pg.wait_for_timeout(800)
    for d in '9999': pg.click('.pk[data-k="%s"]' % d)
    pg.wait_for_timeout(1200); pg.evaluate("localStorage.setItem('rfPinOnb','1')")
    # Projects › Due a visit: Kapsaret (visited 40 days ago, never tested) is there with the tag; Turbo (tested) and the school aren't
    pg.goto(BASE + '/pin#list'); pg.wait_for_timeout(1000); pg.click('.chips .chip[data-f=due]'); pg.wait_for_timeout(900)
    rows = pg.inner_text('#list')
    check('Kapsaret Borehole' in rows and 'Water test due' in rows, 'due list has Kapsaret with "Water test due"')
    check('Turbo Spring' not in rows and 'Kiptoo Primary' not in rows, 'tested and non-water projects visited lately are not in the list')
    check(pg.locator('.row[data-id="107"]').count() == 1 and pg.locator('.row[data-id="107"] .prj-test').count() == 0, 'unfinished borehole (never visited) is due a visit but not a water test')
    check('1 due a water test' in pg.inner_text('#count'), 'count line says how many need a water test')
    shot(pg, '01-due-list.png')
    pg.click('.chips .chip[data-f=all]'); pg.wait_for_timeout(700)
    check(pg.locator('.row[data-id="104"] .prj-test').count() == 1 and pg.locator('.row[data-id="105"] .prj-test').count() == 0, 'tag on the project row in All')
    # the project: tag, buttons
    pg.goto(BASE + '/pin#p/104'); pg.wait_for_timeout(1400)
    check('Water test due' in pg.inner_text('#pState'), 'project screen shows "Water test due"')
    check(pg.locator('#prjSumGo').count() == 1 and pg.locator('#prjTlGo').count() == 1, 'project has Photo timeline and Share project summary')
    pg.locator('#prjBar').scroll_into_view_if_needed(); pg.wait_for_timeout(300); shot(pg, '02-project.png')
    # Photo timeline
    pg.click('#prjTlGo'); pg.wait_for_timeout(1500)
    heads = pg.locator('.prj-gh b').all_inner_texts()
    print('groups', heads)
    check(heads[:5] == ['Main photo', 'Pump or tank, close up', 'Water flowing', 'The site with people', 'Any damage'] and heads[-1] == 'Other photos', 'grouped by photo slot in the visit order, main photo first, other last')
    close = pg.locator('.prj-g').nth(1)
    caps = close.locator('.cap').all_inner_texts()
    check(len(caps) == 3 and 'Grace Achieng' in caps[0] and close.locator('.prj-ph').first.locator('.prj-new').count() == 1 and 'Bertin Mwangi' in caps[1] and 'Meril Kiprop' in caps[2], 'close-ups newest first with who took them: %s' % caps)
    check(pg.locator('.prj-th > span').inner_text().startswith('8 photos · 4 visits'), 'count: ' + pg.locator('.prj-th > span').inner_text())
    check(pg.locator('audio').count() >= 1 and pg.locator('.prj-ph img[src*="v.m4a"]').count() == 0, 'voice notes are not in the timeline')
    pg.locator('#prjTl').scroll_into_view_if_needed(); pg.evaluate('window.scrollBy(0,-70)'); pg.wait_for_timeout(500); shot(pg, '03-timeline.png')
    # swipe the strip
    pg.evaluate("document.querySelectorAll('.prj-strip')[1].scrollBy(200,0)"); pg.wait_for_timeout(400); shot(pg, '04-timeline-swiped.png')
    # full screen, left/right
    close.locator('.prj-ph').first.click(); pg.wait_for_timeout(500)
    check(pg.locator('.prj-lb').count() == 1 and pg.inner_text('.prj-c') == '1 / 3' and pg.locator('.prj-prev').is_disabled(), 'viewer opens on the newest')
    shot(pg, '05-viewer.png')
    pg.click('.prj-next'); pg.wait_for_timeout(400)
    check(pg.inner_text('.prj-c') == '2 / 3' and 'Bertin Mwangi' in pg.inner_text('.prj-lbt'), 'right goes to the older one')
    pg.keyboard.press('ArrowRight'); pg.wait_for_timeout(400); check(pg.inner_text('.prj-c') == '3 / 3' and pg.locator('.prj-next').is_disabled(), 'arrow key to the oldest')
    shot(pg, '06-viewer-oldest.png')
    pg.keyboard.press('Escape'); pg.wait_for_timeout(300); check(pg.locator('.prj-lb').count() == 0, 'Escape closes')
    # donor summary
    pg.click('#prjSumGo'); pg.wait_for_timeout(1800)
    t = pg.inner_text('#prjSum')
    print(t)
    check('Now my girls get to school on time.' in t and 'Mary, mother of three' in t, 'story quote with consent')
    check('We used to walk two hours' not in t, 'no quote without consent')
    check('350' in t and 'people use this water' in t, 'people served from the visit')
    check('Needs repair' in t and 'Handle loose' in t and 'Borehole well' in t and 'Eldoret, Kenya' in t, 'repair, type, location')
    check('Water last tested' not in t, 'no test date made up')
    srcs = pg.eval_on_selector_all('#prjSum .prj-ph3 img', 'l => l.map(i => i.getAttribute("src"))')
    check(len(srcs) == 3 and 'api/photo' in srcs[0] and not any('damage' in s for s in srcs), 'best 3 photos, main first, no damage photo: %s' % srcs)
    check(pg.locator('#prjSum .lh-top .lh-logo').count() == 1 and 'Kitale, Kenya' in t, 'on the letterhead (Kenya office)')
    shot(pg, '07-summary.png'); shot(pg, '08-summary-full.png', full_page=False)
    pg.evaluate("document.getElementById('prjSum').scrollTo(0, 9999)"); pg.wait_for_timeout(300); shot(pg, '09-summary-bottom.png')
    pg.emulate_media(media='print'); pg.wait_for_timeout(300)
    check(pg.evaluate("getComputedStyle(document.querySelector('.app') || document.querySelector('main')).display") == 'none' or pg.evaluate("getComputedStyle(document.querySelector('#vProj')).display") == 'none', 'only the summary prints')
    pdf = os.path.join(OUT, 'summary.pdf'); pg.pdf(path=pdf, format='A4', print_background=True)
    [os.remove(os.path.join(OUT, f)) for f in os.listdir(OUT) if f.startswith('10-summary-pdf')]
    subprocess.run(['pdftoppm', '-png', '-r', '60', pdf, os.path.join(OUT, '10-summary-pdf')], check=False)
    check(len([f for f in os.listdir(OUT) if f.startswith('10-summary-pdf')]) == 1, 'the summary prints on one page')
    pg.emulate_media(media='screen')
    pg.click('#prjSum [data-x]'); pg.wait_for_timeout(300); check(pg.locator('#prjSum').count() == 0, 'Back closes the summary')
    # dark and sun themes, Kiswahili
    pg.evaluate("document.documentElement.setAttribute('data-theme','dark')"); pg.wait_for_timeout(200)
    pg.locator('#prjTl').scroll_into_view_if_needed(); pg.evaluate('window.scrollBy(0,-70)'); pg.wait_for_timeout(300); shot(pg, '11-timeline-dark.png')
    pg.evaluate("document.documentElement.setAttribute('data-theme','sun')"); pg.wait_for_timeout(200); shot(pg, '12-timeline-sun.png')
    pg.evaluate("document.documentElement.removeAttribute('data-theme')")
    pg.evaluate("localStorage.setItem('rfPinLang','sw')"); pg.goto(BASE + '/pin#p/104/hist'); pg.reload(); pg.wait_for_timeout(1800)
    tt = pg.inner_text('#prjTl'); check('Picha kwa wakati' in tt and 'Pampu au tangi, kwa karibu' in tt, 'Kiswahili timeline')
    check('Kipimo cha maji kinahitajika' in pg.inner_text('#pState'), 'Kiswahili tag')
    shot(pg, '13-timeline-sw.png')
    pg.locator('#prjSumGo').evaluate('b => b.click()'); pg.wait_for_timeout(1500); check('Kuhusu mradi' in pg.inner_text('#prjSum'), 'Kiswahili summary'); shot(pg, '14-summary-sw.png'); pg.click('#prjSum [data-x]')
    pg.evaluate("localStorage.setItem('rfPinLang','en')"); pg.reload(); pg.wait_for_timeout(800)
    # Office overview count
    pg.goto(BASE + '/pin#office'); pg.wait_for_timeout(1500)
    ot = pg.inner_text('#oBody'); check(re.search(r'1\s*water tests due', ot) is not None, 'Office overview counts water tests due')
    shot(pg, '15-office.png')
    pg.set_viewport_size({'width': 1280, 'height': 900}); pg.goto(BASE + '/pin#p/104/hist'); pg.wait_for_timeout(1800); shot(pg, '16-desktop-timeline.png')
    pg.locator('#prjSumGo').evaluate('b => b.click()'); pg.wait_for_timeout(1500); shot(pg, '17-desktop-summary.png')
    check(not errs, 'no page errors %s' % errs[:2]); br.close()
print('PROBLEMS', problems)
