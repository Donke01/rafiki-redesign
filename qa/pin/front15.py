# Me & team: waiting list, my work, my month, team today (call / WhatsApp), team totals, team feed.
import os, re, sys
from datetime import datetime, timedelta, timezone, date
from playwright.sync_api import sync_playwright
BASE = 'http://localhost:8797'
OUT = sys.argv[1] if len(sys.argv) > 1 else '/tmp/claude-0/-home-claude/a284d8b3-3cbb-5c42-a9ef-c5363bc71e30/scratchpad/web15'; os.makedirs(OUT, exist_ok=True)
def ago(h): return (datetime.now(timezone.utc) - timedelta(hours=h)).strftime('%Y-%m-%dT%H:%M:%S.000Z')
P = [{'id': i, 'title': t, 'country': 'Kenya', 'ck': 'ke', 'area': 'Eldoret', 'type': 'borehole', 'status': 'Completed', 'gps': [0.51, 35.27], 'photo': None}
     for i, t in [('101', 'Chepkongi Dispensary'), ('104', 'Kapsaret Borehole'), ('105', 'Turbo Spring')]]
today = date.today().isoformat()
SNAP = {'ok': True, 'who': 'Meril Kiprop', 'admin': True, 'reviewOpen': 0, 'projects': P,
        'repairs': {'104': {'state': 'repair', 'by': 'Bertin', 'at': ago(30), 't': 'Kapsaret Borehole', 'step': 'assigned', 'assignee': 'Meril Kiprop', 'due': (date.today() - timedelta(days=2)).isoformat()}},
        'visits': {}, 'jobs': ['104'], 'assigned': [{'aid': 'a1', 'id': '105', 't': 'Turbo Spring', 'date': today, 'by': 'Grace', 'note': 'Check the tank'}], 'confirmed': {}}
def a(by, k, id, t, h, s=None):
    e = {'by': by, 'a': k, 'id': id, 't': t, 'at': ago(h)}
    if s: e['s'] = s
    return e
ACT = [a('Meril Kiprop', 'pin', '101', 'Chepkongi Dispensary', 50), a('Meril Kiprop', 'visit', '101', 'Chepkongi Dispensary', 26, 'working'), a('Bertin Mwangi', 'visit', '104', 'Kapsaret Borehole', 30, 'repair'),
       a('Grace Achieng', 'fixed', '105', 'Turbo Spring', 3), a('Meril Kiprop', 'visit', '104', 'Kapsaret Borehole', 1, 'repair'), a('Grace Achieng', 'pin', '104', 'Kapsaret Borehole', 2)] + \
      [a('Grace Achieng', 'visit', '101', 'Chepkongi Dispensary', 100 + i) for i in range(6)]
TEAM = [{'name': 'Bertin Mwangi', 'home': 'tz', 'phone': '0754 123 456', 'lastSeen': ago(29)}, {'name': 'Grace Achieng', 'home': 'ke', 'phone': '+254 712 000 111', 'lastSeen': ago(2)},
        {'name': 'Meril Kiprop', 'home': 'ke', 'phone': '', 'lastSeen': ago(0)}]
problems = []
def check(c, w):
    print(('ok   ' if c else 'FAIL ') + w)
    if not c: problems.append(w)
post_ok = [False]
def api(route, req):
    u = req.url
    if req.method == 'GET':
        if 'activity=' in u: return route.fulfill(json={'ok': True, 'activity': ACT})
        if 'team=' in u: return route.fulfill(json={'ok': True, 'team': TEAM})
        if 'history=' in u: return route.fulfill(json={'ok': True, 'notes': []})
        if 'id=' in u: return route.fulfill(json={'ok': True, 'last': None, 'visits': []})
        return route.fulfill(json=SNAP)
    if not post_ok[0]: return route.fulfill(status=503, json={'ok': False, 'error': 'busy'})
    return route.fulfill(json={'ok': True, 'updateId': '9'})
with sync_playwright() as p:
    br = p.chromium.launch(); ctx = br.new_context(viewport={'width': 390, 'height': 844}, device_scale_factor=2, has_touch=True, is_mobile=True, service_workers='block')
    pg = ctx.new_page(); errs = []; pg.on('pageerror', lambda e: errs.append(str(e)))
    pg.route('**/api/pin-auth/me', lambda r, q: r.fulfill(json={'available': False})); pg.route(re.compile(r'/api/pin(\?.*)?$'), api)
    pg.route('**/api/admin**', lambda r, q: r.fulfill(json={'ok': True, 'items': [], 'team': [], 'mail': {}, 'cron': False}))
    pg.route('**/arcgisonline.com/**', lambda r, q: r.abort()); pg.route('**/tile.openstreetmap.org/**', lambda r, q: r.abort())
    pg.goto(BASE + '/pin'); pg.wait_for_timeout(800)
    for d in '9999': pg.tap('.pk[data-k="%s"]' % d)
    pg.wait_for_timeout(1200); pg.evaluate("localStorage.setItem('rfPinOnb','1')")
    # one change waiting on the phone
    pg.evaluate("""() => new Promise(ok => { const r = indexedDB.open('rafiki-pin', 2); r.onsuccess = () => { const t = r.result.transaction('out', 'readwrite');
        t.objectStore('out').add({ body: { action: 'visit', id: '101', state: 'working', by: 'Meril Kiprop' }, files: [], at: Date.now() - 120000 }); t.oncomplete = ok; }; })""")
    pg.goto(BASE + '/pin#me'); pg.wait_for_timeout(2500)
    check(pg.locator('#vMe:not(.hidden)').count() == 1, 'Me opens')
    check('1 change waiting to send' in pg.inner_text('#meWait') and pg.locator('#meWait .wl li').count() == 1, 'waiting list shows the queued visit')
    check('Chepkongi' in pg.inner_text('#meWait') and 'Working well' in pg.inner_text('#meWait'), 'waiting row names the project and result')
    pg.screenshot(path=OUT + '/1-me-top.png')
    w = pg.inner_text('#meWork'); check('My work' in w and 'Overdue' in w and 'Today' in w, 'my work: today visit and overdue job')
    m = pg.inner_text('#mmBody'); check(re.search(r'2\s*Visits', m) is not None or date.today().day <= 2, 'my month counts my visits')
    check('3 days in a row' in pg.inner_text('#meStreak'), 'streak of three days (profile card)'); check(pg.inner_text('#meStats').split()[:1] != ['–'], 'month numbers in the profile card'); check(pg.locator('.wk > div').count() == 6, 'six week bars')
    pg.locator('#meMonth').scroll_into_view_if_needed(); pg.wait_for_timeout(800); pg.screenshot(path=OUT + '/2-my-month.png')
    pg.tap('#meSw [data-p=team]'); pg.wait_for_timeout(500); check(pg.locator('#paneTeam:not(.hidden)').count() == 1 and pg.locator('#paneMe.hidden').count() == 1, 'Team tab shows the team')
    t = pg.inner_text('#ttBody'); check('Grace Achieng' in t and 'Bertin Mwangi' in t and 'Meril' not in t, 'team today lists the others, not me')
    check(t.index('Grace') < t.index('Bertin'), 'most recent first')
    hrefs = pg.eval_on_selector_all('#ttBody a.ca', 'els => els.map(e => e.getAttribute("href"))')
    check('tel:0754123456' in hrefs and 'https://wa.me/255754123456' in hrefs and 'https://wa.me/254712000111' in hrefs, 'call and WhatsApp links with country codes')
    pg.locator('#teamToday').scroll_into_view_if_needed(); pg.wait_for_timeout(500); pg.screenshot(path=OUT + '/3-team-today.png')
    tot = pg.inner_text('#actTable'); names = re.findall(r'(Bertin Mwangi|Grace Achieng|Meril Kiprop)', tot)
    check(names == sorted(names) and len(names) == 3, 'totals in name order'); check('Together' in tot, 'together total')
    pg.tap('#segPeriod button[data-v=w]'); pg.wait_for_timeout(300); check('Together' in pg.inner_text('#actTable'), 'week toggle')
    pg.tap('#segPeriod button[data-v=m]'); pg.wait_for_timeout(300)
    pg.locator('#actTable').scroll_into_view_if_needed(); pg.wait_for_timeout(500); pg.screenshot(path=OUT + '/4-team-totals.png')
    check(pg.locator('#actFeed li').count() == 8, 'feed shows 8'); first = pg.inner_text('#actFeed li >> nth=0'); check('Meril' in first and 'Kapsaret' in first, 'newest first')
    pg.tap('#feedMore'); pg.wait_for_timeout(300); check(pg.locator('#actFeed li').count() == 12, 'show more')
    pg.locator('#actFeed').scroll_into_view_if_needed(); pg.wait_for_timeout(500); pg.screenshot(path=OUT + '/5-team-feed.png')
    pg.tap('#actFeed li.go >> nth=0'); pg.wait_for_timeout(700); check(pg.locator('#vProj:not(.hidden)').count() == 1, 'feed row opens the project')
    pg.goto(BASE + '/pin#me'); pg.wait_for_timeout(1500); pg.tap('#meSw [data-p=me]'); pg.wait_for_timeout(300)
    post_ok[0] = True; pg.tap('#waitSend'); pg.wait_for_timeout(1500); check(pg.inner_text('#meWait').strip() == '', 'send now empties the list')
    # Kiswahili
    pg.tap('[data-set=lang]'); pg.wait_for_timeout(400); pg.tap('#segLang button[data-v=sw]'); pg.wait_for_timeout(300);   # Me › Settings › Language
    pg.goto(BASE + '/pin#me'); pg.wait_for_timeout(2500)
    s = pg.text_content('#vMe'); check('Mwezi wangu' in s and 'Timu leo' in s and 'Pamoja' in s and 'Habari za timu' in s, 'Kiswahili'); pg.screenshot(path=OUT + '/6-sw.png', full_page=True)
    check(not errs, 'no page errors ' + str(errs[:2]))
    br.close()
print('PROBLEMS', problems)
