# Me › Your stamps: badges for real work (locked with progress, earned), a new one celebrating once, less motion, Kiswahili, dark.
import json, os, re, datetime
from playwright.sync_api import sync_playwright
HERE = os.path.dirname(os.path.abspath(__file__)); BASE = 'http://localhost:' + os.environ.get('PORT', '8816'); OUT = os.path.join(HERE, 'badges'); os.makedirs(OUT, exist_ok=True)
TZ = datetime.timezone(datetime.timedelta(hours=3))  # Africa/Nairobi
today = datetime.datetime.now(TZ).date()
days = [d for d in (today - datetime.timedelta(days=i) for i in range(5)) if d.month == today.month] or [today]
streak = len(days) == 5
at = lambda d, h: datetime.datetime(d.year, d.month, d.day, h, 15, tzinfo=TZ).astimezone(datetime.timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ')
def pj(i, t, c, ck, gps=True): return dict({'id': i, 'title': t, 'country': c, 'ck': ck, 'area': 'Eldoret', 'type': 'Borehole well', 'status': 'Completed'}, **({'gps': [0.51, 35.27]} if gps else {}))
P = [pj('101', 'Chepkongi Dispensary', 'Kenya', 'ke'), pj('104', 'Kapsaret Borehole', 'Kenya', 'ke'), pj('105', 'Turbo Spring', 'Kenya', 'ke'),
     pj('201', 'Arusha School Tank', 'Tanzania', 'tz'), pj('301', 'Gulu Borehole', 'Uganda', 'ug'), pj('401', 'Zomba Well', 'Malawi', 'mw', False)]
ACT = []
for i, pid in enumerate(['101', '104', '105', '201', '301', '101', '104']):
    e = {'by': 'Meril Kiprop', 'a': 'visit', 's': 'working', 'id': pid, 't': 'x', 'at': at(days[i % len(days)], 9 + i)}
    if i < 2: e['wq'] = 1
    if 2 <= i <= 4: e['st'] = 1
    ACT.append(e)
for i in range(10): ACT.append({'by': 'Meril Kiprop', 'a': 'pin', 'id': ['101', '104', '105', '201'][i % 4], 't': 'x', 'at': at(days[i % len(days)], 8)})
ACT += [{'by': 'Meril Kiprop', 'a': 'fixed', 'id': '104', 't': 'Kapsaret Borehole', 'at': at(days[0], 14)}, {'by': 'Grace Achieng', 'a': 'visit', 's': 'working', 'id': '401', 't': 'Zomba Well', 'at': at(days[0], 15)}]
EARNED = ['pin1', 'visit1', 'fixed1', 'pin10', 'story3'] + (['streak5'] if streak else [])
problems = []
def check(c, w): print(('ok   ' if c else 'FAIL ') + w); (None if c else problems.append(w))
def api(route, req):
    if req.method == 'GET':
        if 'activity=' in req.url: return route.fulfill(json={'ok': True, 'activity': ACT})
        if 'team=' in req.url: return route.fulfill(json={'ok': True, 'team': []})
        return route.fulfill(json={'ok': True, 'who': 'Meril Kiprop', 'admin': False, 'reviewOpen': 0, 'projects': P, 'repairs': {}, 'visits': {}, 'jobs': [], 'assigned': [], 'confirmed': {}, 'me': {'kind': 'app', 'nick': '', 'phone': '', 'home': 'ke'}})
    route.fulfill(json={'ok': True})

def session(br, seen=None, motion='no-preference', lang='', theme=''):
    ctx = br.new_context(viewport={'width': 390, 'height': 844}, is_mobile=True, has_touch=True, service_workers='block', reduced_motion=motion, timezone_id='Africa/Nairobi')
    pg = ctx.new_page(); errs = []; pg.on('pageerror', lambda e: errs.append(str(e)))
    pg.route('**/api/pin-auth/me', lambda r, q: r.fulfill(json={'available': False})); pg.route(re.compile(r'/api/pin(\?.*)?$'), api)
    pg.goto(BASE + '/pin'); pg.wait_for_timeout(700)
    for d in '9999': pg.click('.pk[data-k="%s"]' % d)
    pg.wait_for_timeout(1000)
    pg.evaluate("""([seen, lang, theme]) => { localStorage.setItem('rfPinOnb', '1'); if (seen) localStorage.setItem('rfPinBadges:meril kiprop', JSON.stringify(seen)); else localStorage.removeItem('rfPinBadges:meril kiprop');
        if (lang) localStorage.setItem('rfPinLang', lang); if (theme) localStorage.setItem('rfPinTheme', theme); }""", [seen, lang, theme])
    pg.goto(BASE + '/pin#list'); pg.reload(); pg.wait_for_timeout(1200)
    return ctx, pg, errs

def badge(pg, k): return pg.inner_text('#meStamps [data-badge=%s] span' % k).strip()

with sync_playwright() as p:
    br = p.chromium.launch()
    # 1. the first look: everything earned so far shows, nothing celebrates, and it's remembered
    ctx, pg, errs = session(br)
    pg.goto(BASE + '/pin#me'); pg.wait_for_timeout(1800)
    got = pg.eval_on_selector_all('#meStamps .stp.got', 'els => els.map(e => e.dataset.badge)')
    check(sorted(got) == sorted(EARNED), 'earned: %s' % got)
    check(pg.locator('#meStamps .stp').count() == 12, '12 stamps in the album')
    check(badge(pg, 'month10') == '7/10 visits', '10 visits this month shows 7/10: ' + badge(pg, 'month10'))
    check(badge(pg, 'countries4') == '3/4 countries', 'all 4 countries shows 3/4: ' + badge(pg, 'countries4'))
    check(badge(pg, 'water3') == '2/3 tests' and badge(pg, 'pin100') == '10/100 pins', 'water tester and 100 pins progress')
    check(badge(pg, 'new1') == 'Add a new site' and badge(pg, 'story3') == 'Earned', 'how to earn / earned')
    check(pg.locator('#meStamps [data-badge=month10] .pol-bar').count() == 1 and pg.locator('#meStamps [data-badge=new1] .pol-bar').count() == 0, 'progress bars only on partly done ones')
    check(('%d of 12' % len(EARNED)) in pg.inner_text('#polStampsSum'), 'album total')
    check(pg.locator('.pol-toast').count() == 0 and pg.locator('.stp.pol-new').count() == 0, 'first look: no celebration')
    seen = json.loads(pg.evaluate("localStorage.getItem('rfPinBadges:meril kiprop')") or '[]')
    check(sorted(seen) == sorted(EARNED), 'remembered what was already earned')
    grey = pg.eval_on_selector('#meStamps [data-badge=month10] .mascot', 'e => getComputedStyle(e).filter')
    check('grayscale' in grey, 'locked stamps are grey: ' + grey)
    pg.locator('.stamps-card').scroll_into_view_if_needed(); pg.wait_for_timeout(1400)
    pg.locator('.stamps-card').screenshot(path=os.path.join(OUT, '01-stamps.png'))
    pg.screenshot(path=os.path.join(OUT, '02-me-stamps-page.png'))
    # the second time Me opens: still nothing new
    pg.goto(BASE + '/pin#list'); pg.wait_for_timeout(500); pg.goto(BASE + '/pin#me'); pg.wait_for_timeout(1500)
    check(pg.locator('.pol-toast').count() == 0, 'no celebration for the same stamps again')
    check(not errs, 'no page errors %s' % errs[:2]); ctx.close()

    # 2. new stamps: they land with confetti and a note at the top, once
    ctx, pg, errs = session(br, seen=['pin1', 'visit1'])
    pg.locator('#tabs [data-v=vMe]').click() if pg.locator('#tabs [data-v=vMe]').count() else pg.goto(BASE + '/pin#me')
    pg.wait_for_selector('.pol-toast', timeout=5000)
    fresh = [k for k in EARNED if k not in ('pin1', 'visit1')]
    check(pg.locator('.stp.pol-new').count() == len(fresh), 'new stamps marked: %d' % pg.locator('.stp.pol-new').count())
    check(pg.locator('.stp.pol-new .pol-cf i').count() == 22 * len(fresh), 'confetti burst on each new stamp')
    tt = pg.inner_text('.pol-toast')
    check(('%d new stamps earned!' % len(fresh)) in tt and 'Story collector' in tt and 'Mapper' in tt, 'note at the top: ' + tt.replace('\n', ' '))
    check(pg.get_attribute('.pol-toast', 'role') == 'status', 'the note is read out by screen readers')
    pg.locator('.stamps-card').scroll_into_view_if_needed(); pg.wait_for_timeout(250)
    pg.screenshot(path=os.path.join(OUT, '03-celebrate-burst.png'))
    pg.wait_for_timeout(450); pg.screenshot(path=os.path.join(OUT, '04-celebrate-settling.png'))
    pg.wait_for_timeout(300); pg.locator('.stamps-card').screenshot(path=os.path.join(OUT, '05-celebrate-card.png'))
    pg.wait_for_timeout(5200)
    check(pg.locator('.pol-toast').count() == 0, 'the note goes away by itself')
    check(pg.locator('.pol-cf').count() == 0, 'confetti cleared')
    pg.reload(); pg.wait_for_timeout(600); pg.goto(BASE + '/pin#me'); pg.wait_for_timeout(1800)
    check(pg.locator('.pol-toast').count() == 0, 'celebrates only once')
    check(not errs, 'no page errors %s' % errs[:2]); ctx.close()

    # 3. tapping the note takes you to the stamps
    ctx, pg, errs = session(br, seen=[k for k in EARNED if k != 'fixed1'])
    pg.goto(BASE + '/pin#me'); pg.wait_for_selector('.pol-toast', timeout=5000)
    check('New stamp earned!' in pg.inner_text('.pol-toast') and 'Mark a repair fixed' in pg.inner_text('.pol-toast'), 'one new stamp: its name and what it was for')
    pg.screenshot(path=os.path.join(OUT, '06-one-new.png'))
    pg.click('.pol-toast'); pg.wait_for_timeout(900)
    box = pg.locator('.stamps-card').bounding_box()
    check(pg.locator('.pol-toast').count() == 0 and box['y'] < 400, 'tapping the note scrolls to the stamps')
    check(not errs, 'no page errors %s' % errs[:2]); ctx.close()

    # 4. less motion: the note still says so, without confetti or movement
    ctx, pg, errs = session(br, seen=['pin1', 'visit1'], motion='reduce')
    pg.goto(BASE + '/pin#me'); pg.wait_for_selector('.pol-toast', timeout=5000)
    check(pg.locator('.pol-cf').count() == 0, 'no confetti with less motion')
    anim = pg.eval_on_selector('.stp.pol-new .stin', 'e => getComputedStyle(e).animationName')
    check(anim == 'none', 'new stamp does not move with less motion: ' + anim)
    pg.locator('.stamps-card').scroll_into_view_if_needed(); pg.wait_for_timeout(200)
    pg.screenshot(path=os.path.join(OUT, '07-less-motion.png'))
    check(not errs, 'no page errors %s' % errs[:2]); ctx.close()

    # 5. Kiswahili, dark
    ctx, pg, errs = session(br, seen=['pin1'], lang='sw', theme='dark')
    pg.goto(BASE + '/pin#me'); pg.wait_for_selector('.pol-toast', timeout=5000)
    check(badge(pg, 'month10') == 'ziara 7/10', 'Kiswahili progress: ' + badge(pg, 'month10'))
    check('Umepata stempu' in pg.inner_text('.pol-toast'), 'Kiswahili note: ' + pg.inner_text('.pol-toast').replace('\n', ' '))
    pg.wait_for_timeout(5800)
    pg.locator('.stamps-card').scroll_into_view_if_needed(); pg.wait_for_timeout(1400)
    pg.locator('.stamps-card').screenshot(path=os.path.join(OUT, '08-stamps-sw-dark.png'))
    check(not errs, 'no page errors %s' % errs[:2]); ctx.close()

    # 6. sun (bright light)
    ctx, pg, errs = session(br, theme='sun')
    pg.goto(BASE + '/pin#me'); pg.wait_for_timeout(1500)
    pg.locator('.stamps-card').scroll_into_view_if_needed(); pg.wait_for_timeout(1400)
    pg.locator('.stamps-card').screenshot(path=os.path.join(OUT, '09-stamps-sun.png'))
    check(not errs, 'no page errors %s' % errs[:2]); ctx.close()
    br.close()
print('PROBLEMS', problems)
