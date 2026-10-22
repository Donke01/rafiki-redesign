# Office › Repairs and Review, redesigned: a head with numbers, filter chips with counts, calm cards that open a sheet
# holding every action (job step, person, due date, budget, actual cost + receipt, mark fixed, the second approval;
# approve / turn down review items), and the extras folded away. Checks the requests each action posts, the folded
# rows, Kiswahili, light / dark / sun, a 390px phone and a 1280px desktop, and no page errors.
# Screenshots to qa/pin/repairs/.  Run with the site on BASE (default :8851).
import json, os, re, datetime
from playwright.sync_api import sync_playwright
HERE = os.path.dirname(os.path.abspath(__file__)); BASE = os.environ.get('BASE', 'http://localhost:8851'); OUT = os.path.join(HERE, 'repairs'); os.makedirs(OUT, exist_ok=True)
for f in os.listdir(OUT):
    if f.endswith('.png') and not f.startswith('android-'): os.remove(os.path.join(OUT, f))
now = datetime.datetime.now(datetime.timezone.utc).replace(tzinfo=None)
iso = lambda h: (now - datetime.timedelta(hours=h)).strftime('%Y-%m-%dT%H:%M:%SZ')
day = lambda d: (now + datetime.timedelta(days=d)).strftime('%Y-%m-%d')
P = [{'id': '104', 'title': 'Kapsaret Borehole', 'country': 'Kenya', 'ck': 'ke', 'area': 'Eldoret', 'type': 'Borehole well', 'status': 'Completed', 'gps': [0.47, 35.25]},
     {'id': '105', 'title': 'Magara Well', 'country': 'Tanzania', 'ck': 'tz', 'area': 'Babati', 'type': 'Borehole well', 'status': 'Completed', 'gps': [-4.2, 35.7]},
     {'id': '101', 'title': 'Chepkongi Dispensary', 'country': 'Kenya', 'ck': 'ke', 'area': 'Eldoret', 'type': 'Health', 'status': 'Completed', 'gps': [0.52, 35.28]},
     {'id': '107', 'title': 'Mzuzu Water Kiosk', 'country': 'Malawi', 'ck': 'mw', 'area': 'Mzuzu', 'type': 'Water collection', 'status': 'Completed', 'gps': [-11.45, 34.02]},
     {'id': '102', 'title': 'Nzoia Bridge Primary School – Rainwater Tanks and Gutters', 'country': 'Uganda', 'ck': 'ug', 'area': 'Tororo', 'type': 'School', 'status': 'Completed', 'gps': [0.69, 34.18]}]
REP = {'104': {'state': 'notworking', 'by': 'Bertin', 'at': iso(50), 't': 'Kapsaret Borehole', 'urgency': 'urgent', 'cost': 85000, 'parts': 'Rising main pipes, cylinder', 'problems': ['Handle broken', 'No water'], 'note': 'The handle came off on Monday', 'step': 'assigned', 'assignee': 'Meril Kiprop', 'due': day(-2), 'stepAt': {'assigned': iso(40)}},
       '105': {'state': 'repair', 'by': 'Amina', 'at': iso(90), 't': 'Magara Well', 'cost': 70000, 'step': 'assigned', 'assignee': 'Bertin Mwangi', 'approval': {'status': 'pending', 'rid': 'c1', 'by': 'Jessica', 'at': iso(5), 'est': 70000}},
       '101': {'state': 'repair', 'by': 'Grace', 'at': iso(30), 't': 'Chepkongi Dispensary', 'cost': 6000, 'step': 'assigned', 'assignee': 'Reid Anderson'},
       '107': {'state': 'repair', 'by': 'Grace', 'at': iso(300), 't': 'Mzuzu Water Kiosk', 'urgency': 'month', 'cost': 12000, 'budget': 15000, 'parts': 'Tap stands', 'step': 'parts', 'assignee': '', 'due': day(9)},
       '102': {'state': 'notworking', 'by': 'Okello', 'at': iso(8), 't': 'Nzoia Bridge Primary School – Rainwater Tanks and Gutters', 'urgency': 'urgent', 'cost': 64000, 'step': 'reported', 'approval': {'status': 'pending', 'rid': 'c2', 'by': 'Reid Anderson', 'at': iso(1), 'est': 64000}}}
def snap():
    return {'ok': True, 'who': 'Reid Anderson', 'admin': True, 'reviewOpen': 6, 'projects': P, 'repairs': json.loads(json.dumps(REP)), 'visits': {}, 'jobs': ['101'], 'assigned': [], 'confirmed': {},
            'me': {'kind': 'app', 'nick': '', 'phone': '', 'home': 'ke'}, 'approveAbove': 50000}
REVIEW = [{'rid': 'c1', 'kind': 'cost', 'id': '105', 't': 'Magara Well', 'by': 'Jessica', 'at': iso(5), 'status': 'open', 'data': {'est': 70000, 'step': 'parts', 'parts': 'New pump head', 'note': 'Quote from Babati Hardware'}},
          {'rid': 'c2', 'kind': 'cost', 'id': '102', 't': 'Nzoia Bridge Primary School – Rainwater Tanks and Gutters', 'by': 'Reid Anderson', 'at': iso(1), 'status': 'open', 'data': {'est': 64000, 'step': 'parts'}},
          {'rid': 'r1', 'kind': 'story', 'id': '101', 't': 'Chepkongi Dispensary', 'by': 'Meril Kiprop', 'at': iso(26), 'status': 'open', 'data': {'quote': 'Now the nurses can wash their hands between patients, and the children drink clean water at school.', 'who': 'Mary, nurse'}},
          {'rid': 'r2', 'kind': 'new', 'id': '109', 't': 'Kipsangui Spring', 'by': 'Bertin Mwangi', 'at': iso(70), 'status': 'open', 'data': {'type': 'Spring protection', 'village': 'Kipsangui', 'ck': 'ke', 'gps': '0.51234, 35.27011', 'note': 'Committee asked for a visit'}},
          {'rid': 'r3', 'kind': 'move', 'id': '104', 't': 'Kapsaret Borehole', 'by': 'Meril Kiprop', 'at': iso(100), 'status': 'open', 'data': {'from': '0.47000, 35.25000', 'to': '0.48120, 35.26310', 'dist': 1912, 'how': 'GPS on the phone, standing at the pump'}},
          {'rid': 'r4', 'kind': 'request', 'id': '107', 't': 'Mzuzu Water Kiosk', 'by': 'Grace Achieng', 'at': iso(150), 'status': 'open', 'data': {'from': '-11.45000, 34.02000', 'to': '-11.45310, 34.02240', 'dist': 430}},
          {'rid': 'd1', 'kind': 'new', 'id': '110', 't': 'Kaptebee Pigsty', 'by': 'Meril Kiprop', 'at': iso(400), 'status': 'approved', 'done': iso(20), 'doneBy': 'Jessica'},
          {'rid': 'd2', 'kind': 'move', 'id': '105', 't': 'Magara Well', 'by': 'Amina', 'at': iso(500), 'status': 'rejected', 'done': iso(40), 'doneBy': 'Reid Anderson', 'reason': 'Point was 2 km off'}]
TEAM = [{'name': 'Reid Anderson', 'admin': True, 'source': 'cloudflare'}, {'name': 'Meril Kiprop', 'source': 'app'}, {'name': 'Bertin Mwangi', 'source': 'app'}]
MODE = {'empty': False}
posts, photos, problems = [], [], []
def check(c, w): print(('ok   ' if c else 'FAIL ') + w); (None if c else problems.append(w))
def pin_api(route, req):
    if req.method == 'GET':
        u = req.url
        if 'activity=' in u: return route.fulfill(json={'ok': True, 'activity': []})
        if 'history=' in u: return route.fulfill(json={'ok': True, 'notes': []})
        if 'id=' in u: return route.fulfill(json={'ok': True, 'last': None, 'visits': [], 'costs': {'sum': 0, 'n': 0, 'last': []}})
        s = snap()
        if MODE['empty']: s['repairs'] = {}
        return route.fulfill(json=s)
    return route.fulfill(json={'ok': True})
def admin_api(route, req):
    u = req.url
    if req.method == 'GET':
        if 'view=review' in u: return route.fulfill(json={'ok': True, 'items': [] if MODE['empty'] else REVIEW})
        if 'view=team' in u: return route.fulfill(json={'ok': True, 'team': TEAM, 'mail': {}, 'cron': False})
        return route.fulfill(json={'ok': True, 'items': []})
    b = json.loads(req.post_data or '{}'); posts.append(b)
    if b.get('action') == 'job' and b.get('id') == '104' and b.get('step') == 'parts':
        j = dict(REP['104']); j['approval'] = {'status': 'pending', 'rid': 'c3', 'by': 'Reid Anderson', 'at': iso(0), 'est': 85000}
        return route.fulfill(json={'ok': True, 'job': j, 'held': 'parts', 'asked': True, 'updateId': '8001'})
    if b.get('action') == 'job' and b.get('step') == 'fixed': return route.fulfill(json={'ok': True, 'job': None, 'held': '', 'asked': False, 'updateId': '8003'})
    if b.get('action') == 'job':
        j = dict(REP[b['id']]); j.update({'step': b.get('step') or j['step'], 'assignee': b.get('assignee', ''), 'due': b.get('due', '')})
        return route.fulfill(json={'ok': True, 'job': j, 'held': '', 'asked': False, 'updateId': '8002'})
    if b.get('action') == 'review' and b.get('rid') == 'c2': return route.fulfill(status=409, json={'ok': False, 'reason': 'own'})
    return route.fulfill(json={'ok': True})
def theme(pg, t): pg.evaluate("t => { localStorage.setItem('rfPinTheme', t); if (t) document.documentElement.setAttribute('data-theme', t); else document.documentElement.removeAttribute('data-theme'); }", t)
def login(ctx):
    pg = ctx.new_page(); errs = []; pg.on('pageerror', lambda e: errs.append(str(e)))
    pg.route('**/api/pin-auth/me', lambda r, q: r.fulfill(json={'available': False})); pg.route(re.compile(r'/api/pin(\?.*)?$'), pin_api)
    pg.route('**/api/pin-photo', lambda r, q: (photos.append(q.post_data_buffer[:4000]), r.fulfill(json={'ok': True}))[1])
    pg.route('**/api/admin**', admin_api)
    pg.route('**/tile.openstreetmap.org/**', lambda r, q: r.abort()); pg.route('**/arcgisonline.com/**', lambda r, q: r.abort()); pg.route('**/google.com/s2/**', lambda r, q: r.abort())
    pg.goto(BASE + '/pin'); pg.wait_for_timeout(800)
    for d in '9999': pg.click('.pk[data-k="%s"]' % d)
    pg.wait_for_timeout(1200); pg.evaluate("localStorage.setItem('rfPinOnb','1'); localStorage.setItem('rfPinOTab','overview')")
    return pg, errs
def office(pg, tab):
    pg.goto(BASE + '/pin#office'); pg.wait_for_timeout(900)
    pg.click('#oTabs [data-o=%s]' % tab); pg.wait_for_timeout(1300)
def sheet(pg): return pg.locator('.tsh .rr-sh')
def close(pg): pg.click('.tsh-x'); pg.wait_for_timeout(400)
def chips(pg): return {c.get_attribute('data-testid'): int(c.locator('.n').inner_text()) for c in pg.locator('.rr-chips .chip').all()}
def card_titles(pg, sel): return [c.locator('.rr-tt').inner_text() for c in pg.locator(sel).all()]
def after(n): return posts[n:]
# folded = the box that holds it has no height (it opens with an animation)
def shut(pg, sel): return pg.evaluate("s => { const e = document.querySelector(s); const b = e && e.closest('.rr-in'); return !!b && b.getBoundingClientRect().height < 2; }", sel)
# no word is split across lines, and nothing spills out sideways
def tidy(pg, root, what):
    bad = pg.evaluate("""r => { const out = []; const el = document.querySelector(r); if (!el) return ['missing ' + r];
      const W = document.documentElement.clientWidth;
      el.querySelectorAll('*').forEach(e => { const b = e.getBoundingClientRect(); if (e.closest('.rr-chips') || e.querySelector('.rr-chips')) return;
        if (b.width && (b.right > W + 1 || e.scrollWidth > e.clientWidth + 2 && getComputedStyle(e).overflowX === 'visible' && e.clientWidth)) out.push(e.className + ' ' + (e.textContent || '').slice(0, 30)); });
      const tw = document.createTreeWalker(el, NodeFilter.SHOW_TEXT); let t;
      while ((t = tw.nextNode())) { const s = t.data; const re = /\\S+/g; let m; while ((m = re.exec(s))) { if (m[0].length < 3) continue; const rg = document.createRange(); rg.setStart(t, m.index); rg.setEnd(t, m.index + m[0].length);
        const rs = [...rg.getClientRects()].filter(x => x.width > 0); if (rs.length > 1 && Math.abs(rs[0].top - rs[rs.length - 1].top) > 4 && !t.parentElement.closest('.rr-d.q')) out.push('split: ' + m[0]); } }
      if (document.scrollingElement.scrollWidth > W + 1) out.push('the page scrolls sideways');
      return out.slice(0, 6); }""", root)
    check(not bad, what + ' tidy (no split words, no spill) %s' % bad)

RECEIPT = os.path.join(HERE, '..', '..', 'img', 'letterhead', 'rafiki-logo.jpg')
if not os.path.exists(RECEIPT): RECEIPT = next(os.path.join(dp, f) for dp, _, fs in os.walk(os.path.join(HERE, '..', '..', 'img')) for f in fs if f.endswith('.jpg'))

with sync_playwright() as p:
    br = p.chromium.launch()
    ctx = br.new_context(viewport={'width': 390, 'height': 844}, device_scale_factor=2, is_mobile=True, has_touch=True, service_workers='block')
    pg, errs = login(ctx)
    # ---------------- Repairs: the list
    office(pg, 'repairs')
    check(pg.locator('[data-testid=rr_head]').count() == 1 and 'Repair jobs' in pg.inner_text('[data-testid=rr_head]'), 'repairs head')
    nums = {k: pg.inner_text('[data-testid=rr_n_%s] b' % k) for k in ('all', 'urgent', 'approval')}
    check(nums == {'all': '5', 'urgent': '2', 'approval': '3'}, 'number tiles: open 5, urgent 2, need approval 3: %s' % nums)
    cs = chips(pg)
    check(cs == {'rrf_all': 5, 'rrf_urgent': 2, 'rrf_notworking': 2, 'rrf_unassigned': 2, 'rrf_approval': 3, 'rrf_parts': 1}, 'filter chips with counts %s' % cs)
    t = card_titles(pg, '[data-testid=rr_job]')
    check(t[:2] == ['Kapsaret Borehole', 'Nzoia Bridge Primary School – Rainwater Tanks and Gutters'] and t[2] == 'Mzuzu Water Kiosk', 'most urgent first, oldest first within: %s' % t)
    c104 = pg.locator('[data-rrjob="104"]'); tx = c104.inner_text()
    check('Urgent · Not working' in tx and 'Assigned · Meril Kiprop' in tx and 'KES 85,000' in tx and 'needs approval' in tx, 'card: pill, step and person, estimate and approval: ' + tx.replace('\n', ' | '))
    check(c104.locator('.late').count() == 1, 'overdue due date in red')
    check(pg.locator('[data-rrjob] button, [data-rrjob] input, [data-rrjob] select').count() == 0, 'no buttons or forms inside the cards')
    check(pg.locator('[data-rrjob="107"]').inner_text().count('not assigned') == 1, 'not assigned shows on the card')
    tidy(pg, '#oBody', 'repairs list')
    pg.evaluate('scrollTo(0,0)'); pg.wait_for_timeout(600); pg.screenshot(path=os.path.join(OUT, '01-repairs-light.png'))
    pg.screenshot(path=os.path.join(OUT, '01b-repairs-light-full.png'), full_page=True)
    # filters
    pg.click('[data-testid=rrf_urgent]'); pg.wait_for_timeout(500)
    check(card_titles(pg, '[data-testid=rr_job]') == ['Kapsaret Borehole', 'Nzoia Bridge Primary School – Rainwater Tanks and Gutters'], 'urgent filter')
    check(pg.get_attribute('[data-testid=rr_n_urgent]', 'aria-pressed') == 'true', 'the urgent tile lights up with the chip')
    pg.click('[data-testid=rrf_approval]'); pg.wait_for_timeout(400)
    check(sorted(card_titles(pg, '[data-testid=rr_job]')) == sorted(['Kapsaret Borehole', 'Nzoia Bridge Primary School – Rainwater Tanks and Gutters', 'Magara Well']), 'needs approval filter')
    pg.click('[data-testid=rrf_parts]'); pg.wait_for_timeout(400)
    check(card_titles(pg, '[data-testid=rr_job]') == ['Mzuzu Water Kiosk'], 'parts ordered filter')
    pg.click('[data-testid=rrf_unassigned]'); pg.wait_for_timeout(400)
    check(sorted(card_titles(pg, '[data-testid=rr_job]')) == ['Mzuzu Water Kiosk', 'Nzoia Bridge Primary School – Rainwater Tanks and Gutters'], 'not assigned filter')
    pg.click('[data-testid=rr_n_urgent]'); pg.wait_for_timeout(400)
    check(pg.get_attribute('[data-testid=rrf_urgent]', 'aria-pressed') == 'true' and len(card_titles(pg, '[data-testid=rr_job]')) == 2, 'tapping a tile sets the filter')
    pg.click('[data-testid=rrf_notworking]'); pg.wait_for_timeout(400); pg.screenshot(path=os.path.join(OUT, '02-repairs-filter-notworking.png'))
    pg.click('[data-testid=rrf_all]'); pg.wait_for_timeout(400)
    # folded: how it works
    check(pg.get_attribute('[data-testid=rr_how_toggle]', 'aria-expanded') == 'false' and shut(pg, '[data-testid=rr_how]'), 'how it works folded away')
    pg.click('[data-testid=rr_how_toggle]'); pg.wait_for_timeout(500)
    check(not shut(pg, '[data-testid=rr_how]') and 'KES 50,000' in pg.inner_text('[data-testid=rr_how]'), 'how it works opens')
    pg.locator('.rr-sets').scroll_into_view_if_needed(); pg.wait_for_timeout(300); pg.locator('.rr-sets').screenshot(path=os.path.join(OUT, '03-repairs-how-open.png'))
    pg.click('[data-testid=rr_how_toggle]'); pg.wait_for_timeout(400)

    # ---------------- Repairs: a small job's sheet: step, person, note, actual cost + receipt
    pg.evaluate('scrollTo(0,0)'); pg.click('[data-rrjob="101"]'); pg.wait_for_timeout(700)
    sh = sheet(pg)
    check(sh.count() == 1 and 'Chepkongi Dispensary' in sh.inner_text(), 'tap a card: the job sheet')
    check(sh.locator('[data-step=assigned]').get_attribute('aria-checked') == 'true', 'the sheet shows the step')
    check(sh.locator('[data-f=assignee]').input_value() == 'Reid Anderson', 'the person on the job is chosen')
    check(shut(pg, '.tsh [data-f=budget]'), 'budget, cost and receipt folded away')
    pg.wait_for_timeout(300); pg.screenshot(path=os.path.join(OUT, '04-job-sheet-light.png'))
    sh.locator('[data-step=parts]').click(); sh.locator('[data-f=note]').fill('Tap washers from Eldoret')
    sh.locator('[data-testid=rr_money_toggle]').click(); pg.wait_for_timeout(450)
    check(not shut(pg, '.tsh [data-f=budget]'), 'the money row opens')
    sh.locator('[data-f=actual]').fill('5400'); sh.locator('input[data-rc]').set_input_files(RECEIPT); pg.wait_for_timeout(700)
    check('Receipt ready' in sh.inner_text(), 'receipt picked, shown')
    sh.locator('[data-testid=rr_money]').scroll_into_view_if_needed(); pg.wait_for_timeout(200); pg.screenshot(path=os.path.join(OUT, '05-job-sheet-money-open.png'))
    n0, ph0 = len(posts), len(photos); sh.locator('[data-testid=rr_save]').click(); pg.wait_for_timeout(1200)
    jb = [b for b in after(n0) if b.get('action') == 'job']
    check(jb == [{'action': 'job', 'id': '101', 'step': 'parts', 'assignee': 'Reid Anderson', 'due': '', 'budget': '', 'note': 'Tap washers from Eldoret', 'actual': '5400', 'receipt': True, 'what': 'Tap washers from Eldoret'}], 'save posts the job with step, cost and receipt: %s' % jb)
    check(len(photos) == ph0 + 1 and b'8002' in photos[-1], 'receipt photo sent to the monday.com note')
    check('Saved ✓' in sh.locator('[data-testid=rr_msg]').inner_text(), 'saved message in the sheet')
    check(pg.locator('[data-rrjob="101"]').inner_text().count('Parts ordered') == 1, 'the list behind updates')
    close(pg)
    # assign + due date on another job
    pg.click('[data-rrjob="107"]'); pg.wait_for_timeout(600); sh = sheet(pg)
    sh.locator('[data-f=assignee]').select_option('Bertin Mwangi'); sh.locator('[data-f=due]').fill(day(5))
    n0 = len(posts); sh.locator('[data-testid=rr_save]').click(); pg.wait_for_timeout(900)
    jb = [b for b in after(n0) if b.get('action') == 'job'][-1]
    check(jb.get('assignee') == 'Bertin Mwangi' and jb.get('due') == day(5) and jb.get('step') == 'parts' and jb.get('budget') == '15000', 'assign with a due date: %s' % jb)
    close(pg)
    # ---------------- a big repair: parts ordered is held for a second approval
    pg.click('[data-rrjob="104"]'); pg.wait_for_timeout(600); sh = sheet(pg)
    check('Needs a second approval before parts are ordered' in sh.inner_text(), 'big repair: needs a second approval')
    check('Handle broken, No water' in sh.inner_text() and 'The handle came off on Monday' in sh.inner_text(), 'problems and their note in the sheet')
    sh.locator('[data-step=parts]').click(); n0 = len(posts); sh.locator('[data-testid=rr_save]').click(); pg.wait_for_timeout(900)
    check('Asked ✓' in sh.locator('[data-testid=rr_msg]').inner_text() and sh.locator('[data-step=assigned]').get_attribute('aria-checked') == 'true', 'parts held, asked, step back to Assigned')
    check('Waiting for a second approval · asked by Reid Anderson' in sh.inner_text(), 'the sheet now shows the pending approval')
    pg.wait_for_timeout(300); pg.screenshot(path=os.path.join(OUT, '06-job-held-for-approval.png'))
    close(pg)
    # ---------------- approve someone else's cost from the job sheet
    pg.click('[data-rrjob="105"]'); pg.wait_for_timeout(600); sh = sheet(pg)
    check(sh.locator('[data-testid=rr_cost_dec]').count() == 1 and 'Waiting for a second approval · asked by Jessica' in sh.inner_text(), 'pending cost: approve or decline here')
    pg.screenshot(path=os.path.join(OUT, '07-job-approve-cost.png'))
    sh.locator('[data-f=dnote]').fill('Checked the quote'); n0 = len(posts); sh.locator('[data-testid=rr_cost_approve]').click(); pg.wait_for_timeout(900)
    rv = [b for b in after(n0) if b.get('action') == 'review']
    check(rv == [{'action': 'review', 'rid': 'c1', 'decision': 'approve', 'reason': 'Checked the quote'}], 'approve the cost posts the review: %s' % rv)
    check('Cost approved ✓' in sh.inner_text() and 'Cost approved by Reid Anderson' in sh.inner_text() and sh.locator('[data-testid=rr_cost_dec]').count() == 0, 'approved: the sheet says so')
    close(pg)
    # your own request: no approve buttons
    pg.click('[data-rrjob="102"]'); pg.wait_for_timeout(600); sh = sheet(pg)
    check(sh.locator('[data-testid=rr_cost_dec]').count() == 0 and 'another admin has to approve it' in sh.inner_text(), 'own request: no approve, a note why')
    tidy(pg, '.tsh .rr-sh', 'job sheet (long title)')
    pg.screenshot(path=os.path.join(OUT, '08-job-own-request.png'))
    close(pg)
    # ---------------- mark fixed: asks first, then closes the job
    pg.click('[data-rrjob="107"]'); pg.wait_for_timeout(600); sh = sheet(pg)
    n0 = len(posts); sh.locator('[data-testid=rr_fixed]').click(); pg.wait_for_timeout(400)
    check(sh.locator('.tm-ask').count() == 1 and 'Mark Mzuzu Water Kiosk as fixed?' in sh.inner_text() and not [b for b in after(n0) if b.get('action') == 'job'], 'mark fixed asks first')
    pg.screenshot(path=os.path.join(OUT, '09-job-mark-fixed-ask.png'))
    sh.locator('.tm-ask [data-k=no]').click(); pg.wait_for_timeout(200)
    check(sh.locator('.tm-ask').count() == 0 and not after(n0), 'cancel: nothing sent')
    sh.locator('[data-step=fixed]').click(); sh.locator('[data-testid=rr_save]').click(); pg.wait_for_timeout(300)
    check(sh.locator('.tm-ask').count() == 1, 'saving with "Fixed" chosen asks too')
    sh.locator('.tm-ask [data-k=yes]').click(); pg.wait_for_timeout(500)
    jb = [b for b in after(n0) if b.get('action') == 'job']
    check(len(jb) == 1 and jb[0]['step'] == 'fixed' and jb[0]['id'] == '107', 'fixed posted: %s' % jb)
    check('Marked fixed ✓' in sh.inner_text(), 'marked fixed message')
    pg.wait_for_timeout(1400)
    check(pg.locator('.tsh').count() == 0 and pg.locator('[data-rrjob="107"]').count() == 0, 'the sheet closes and the job leaves the list')
    # open project from the sheet
    pg.click('[data-rrjob="101"]'); pg.wait_for_timeout(500); sheet(pg).locator('[data-testid=rr_open]').click(); pg.wait_for_timeout(900)
    check('#p/101' in pg.url or pg.evaluate("document.querySelector('#vProj') && !document.querySelector('#vProj').classList.contains('hidden')"), 'open project from the sheet')

    # ---------------- Review
    office(pg, 'review')
    check('Waiting for review' in pg.inner_text('[data-testid=rr_head]'), 'review head')
    nums = {k: pg.inner_text('[data-testid=rr_n_%s] b' % k) for k in ('all', 'cost', 'age')}
    check(nums['all'] == '6' and nums['cost'] == '2' and nums['age'] == '6 d', 'review tiles: 6 waiting, 2 costs, oldest 6 d: %s' % nums)
    cs = chips(pg)
    check(cs == {'rrkf_all': 6, 'rrkf_cost': 2, 'rrkf_new': 1, 'rrkf_loc': 2, 'rrkf_story': 1}, 'review chips by kind %s' % cs)
    check(pg.locator('[data-rrrev] button, [data-rrrev] input').count() == 0, 'no buttons or forms inside review cards')
    check('You asked: another admin approves' in pg.inner_text('[data-rrrev=c2]'), 'own cost marked on its card')
    tidy(pg, '#oBody', 'review list')
    pg.evaluate('scrollTo(0,0)'); pg.wait_for_timeout(500); pg.screenshot(path=os.path.join(OUT, '10-review-light.png'))
    pg.screenshot(path=os.path.join(OUT, '10b-review-light-full.png'), full_page=True)
    pg.click('[data-testid=rrkf_loc]'); pg.wait_for_timeout(400)
    check(sorted(card_titles(pg, '[data-testid=rr_rev]')) == ['Kapsaret Borehole', 'Mzuzu Water Kiosk'], 'locations filter (moved + confirmed)')
    pg.click('[data-testid=rrkf_story]'); pg.wait_for_timeout(400)
    check(card_titles(pg, '[data-testid=rr_rev]') == ['Chepkongi Dispensary'], 'stories filter')
    pg.click('[data-testid=rr_n_cost]'); pg.wait_for_timeout(400)
    check(len(card_titles(pg, '[data-testid=rr_rev]')) == 2 and pg.get_attribute('[data-testid=rrkf_cost]', 'aria-pressed') == 'true', 'cost tile sets the filter')
    pg.click('[data-testid=rrkf_all]'); pg.wait_for_timeout(300)
    # recently decided: folded
    check(shut(pg, '[data-testid=rr_done]') and '2 lately' in pg.inner_text('[data-testid=rr_done_toggle]'), 'recently decided folded, with a summary')
    pg.click('[data-testid=rr_done_toggle]'); pg.wait_for_timeout(500)
    check(pg.locator('[data-testid=rr_done] li').count() == 2 and 'Point was 2 km off' in pg.inner_text('[data-testid=rr_done]'), 'recently decided opens')
    pg.locator('.rr-sets').scroll_into_view_if_needed(); pg.wait_for_timeout(200); pg.locator('.rr-sets').screenshot(path=os.path.join(OUT, '11-review-decided-open.png'))
    pg.click('[data-testid=rr_done_toggle]'); pg.wait_for_timeout(300)
    # a cost: approve with a note
    pg.evaluate('scrollTo(0,0)'); pg.click('[data-rrrev=c1]'); pg.wait_for_timeout(700); sh = pg.locator('.tsh [data-testid=rr_rev_sheet]')
    t = sh.inner_text()
    check('Second approval: repair cost' in t and 'KES 70,000' in t and 'Quote from Babati Hardware' in t and 'New pump head' in t, 'cost sheet')
    check(sh.locator('[data-testid=rr_reason]').is_visible() and sh.locator('[data-testid=rr_approve]').is_enabled(), 'note box open; can approve someone else’s')
    pg.wait_for_timeout(200); pg.screenshot(path=os.path.join(OUT, '12-review-cost-sheet.png'))
    sh.locator('[data-testid=rr_reason]').fill('Checked the quote'); n0 = len(posts); sh.locator('[data-testid=rr_approve]').click(); pg.wait_for_timeout(400)
    rv = [b for b in after(n0) if b.get('action') == 'review']
    check(rv == [{'action': 'review', 'rid': 'c1', 'decision': 'approve', 'reason': 'Checked the quote'}], 'approve with a note: %s' % rv)
    check('Approved ✓' in sh.inner_text(), 'approved message')
    pg.wait_for_timeout(1200); check(pg.locator('.tsh').count() == 0, 'the sheet closes after deciding')
    # your own cost: buttons off
    pg.click('[data-rrrev=c2]'); pg.wait_for_timeout(600); sh = pg.locator('.tsh [data-testid=rr_rev_sheet]')
    check(sh.locator('[data-testid=rr_approve]').is_disabled() and sh.locator('[data-testid=rr_reject]').is_disabled() and 'another admin approves it' in sh.inner_text(), 'own request: approve and decline are off')
    tidy(pg, '.tsh .rr-sh', 'own cost sheet')
    close(pg)
    # a story: turning it down asks for a reason first
    pg.click('[data-rrrev=r1]'); pg.wait_for_timeout(600); sh = pg.locator('.tsh [data-testid=rr_rev_sheet]')
    check('Now the nurses can wash their hands' in sh.inner_text() and 'Mary, nurse' in sh.inner_text(), 'story sheet with the quote')
    check(shut(pg, '.tsh [data-testid=rr_reason]'), 'reason hidden at first')
    pg.screenshot(path=os.path.join(OUT, '13-review-story-sheet.png'))
    n0 = len(posts); sh.locator('[data-testid=rr_reject]').click(); pg.wait_for_timeout(450)
    check(not shut(pg, '.tsh [data-testid=rr_reason]') and not after(n0) and 'Confirm: Don’t use' in sh.locator('[data-testid=rr_reject]').inner_text(), 'reject asks first, with a reason box')
    sh.locator('[data-testid=rr_reason]').fill('Not their words'); pg.screenshot(path=os.path.join(OUT, '14-review-story-reject-ask.png'))
    sh.locator('[data-testid=rr_reject]').click(); pg.wait_for_timeout(400)
    rv = [b for b in after(n0) if b.get('action') == 'review']
    check(rv == [{'action': 'review', 'rid': 'r1', 'decision': 'reject', 'reason': 'Not their words'}], 'reject with a reason: %s' % rv)
    pg.wait_for_timeout(1200)
    # a moved location and a new site: details with map links
    pg.click('[data-rrrev=r3]'); pg.wait_for_timeout(600); sh = pg.locator('.tsh [data-testid=rr_rev_sheet]')
    check(sh.locator('a[href*="google.com/maps?q=0.48120,35.26310"]').count() == 1 and '1,912 m' in sh.inner_text() and 'Keep the new location' in sh.inner_text(), 'moved location: from, to, distance, map link')
    pg.screenshot(path=os.path.join(OUT, '15-review-move-sheet.png'))
    n0 = len(posts); sh.locator('[data-testid=rr_approve]').click(); pg.wait_for_timeout(400)
    check([b for b in after(n0) if b.get('action') == 'review'] == [{'action': 'review', 'rid': 'r3', 'decision': 'approve', 'reason': ''}], 'keep the new location posts approve')
    pg.wait_for_timeout(1200)
    pg.click('[data-rrrev=r2]'); pg.wait_for_timeout(600); sh = pg.locator('.tsh [data-testid=rr_rev_sheet]')
    check('Spring protection' in sh.inner_text() and 'Kipsangui' in sh.inner_text() and 'Committee asked for a visit' in sh.inner_text(), 'new site sheet')
    close(pg)
    check(not errs, 'no page errors (light) %s' % errs[:3])

    # ---------------- dark and sun
    theme(pg, 'dark'); office(pg, 'repairs'); pg.evaluate('scrollTo(0,0)'); pg.wait_for_timeout(700)
    pg.screenshot(path=os.path.join(OUT, '20-repairs-dark.png'))
    pg.click('[data-rrjob="104"]'); pg.wait_for_timeout(800); pg.screenshot(path=os.path.join(OUT, '21-job-sheet-dark.png')); close(pg)
    pg.click('#oTabs [data-o=review]'); pg.wait_for_timeout(1200); pg.evaluate('scrollTo(0,0)'); pg.wait_for_timeout(500); pg.screenshot(path=os.path.join(OUT, '22-review-dark.png'))
    pg.click('[data-rrrev=c1]'); pg.wait_for_timeout(800); pg.screenshot(path=os.path.join(OUT, '23-review-sheet-dark.png')); close(pg)
    theme(pg, 'sun'); office(pg, 'repairs'); pg.evaluate('scrollTo(0,0)'); pg.wait_for_timeout(700)
    pg.screenshot(path=os.path.join(OUT, '24-repairs-sun.png'))
    pg.click('[data-rrjob="105"]'); pg.wait_for_timeout(800); pg.screenshot(path=os.path.join(OUT, '25-job-sheet-sun.png')); close(pg)
    pg.click('#oTabs [data-o=review]'); pg.wait_for_timeout(1200); pg.evaluate('scrollTo(0,0)'); pg.wait_for_timeout(400); pg.screenshot(path=os.path.join(OUT, '26-review-sun.png'))
    theme(pg, '')
    # ---------------- Kiswahili
    pg.evaluate("localStorage.setItem('rfPinLang', 'sw')"); pg.reload(); pg.wait_for_timeout(1400); office(pg, 'repairs'); pg.evaluate('scrollTo(0,0)'); pg.wait_for_timeout(500)
    t = pg.inner_text('#oBody')
    check('Kazi za ukarabati' in t and 'Zinahitaji idhini' in t and 'Hazijagawiwa' in t and 'Haraka · Haifanyi kazi' in t, 'repairs in Kiswahili')
    tidy(pg, '#oBody', 'repairs list (sw)')
    pg.screenshot(path=os.path.join(OUT, '30-repairs-sw.png'))
    pg.click('[data-rrjob="104"]'); pg.wait_for_timeout(700); sh = sheet(pg)
    check('hatua ya kazi' in sh.inner_text().lower() and 'Weka imerekebishwa' in sh.inner_text() and 'Bajeti, gharama na risiti' in sh.inner_text(), 'job sheet in Kiswahili')
    tidy(pg, '.tsh .rr-sh', 'job sheet (sw)')
    pg.screenshot(path=os.path.join(OUT, '31-job-sheet-sw.png'))
    sh.locator('[data-testid=rr_money_toggle]').click(); pg.wait_for_timeout(450); sh.locator('[data-testid=rr_money]').scroll_into_view_if_needed(); pg.screenshot(path=os.path.join(OUT, '31b-job-sheet-money-sw.png')); close(pg)
    pg.click('#oTabs [data-o=review]'); pg.wait_for_timeout(1200); pg.evaluate('scrollTo(0,0)'); pg.wait_for_timeout(300)
    t = pg.inner_text('#oBody'); check('Zinasubiri kuidhinishwa' in t and 'Gharama za ukarabati' in t and 'Hadithi' in t, 'review in Kiswahili')
    tidy(pg, '#oBody', 'review list (sw)')
    pg.screenshot(path=os.path.join(OUT, '32-review-sw.png'))
    pg.click('[data-rrrev=r4]'); pg.wait_for_timeout(700); tidy(pg, '.tsh .rr-sh', 'review sheet (sw)'); pg.screenshot(path=os.path.join(OUT, '33-review-sheet-sw.png')); close(pg)
    pg.evaluate("localStorage.removeItem('rfPinLang')")
    # ---------------- empty: nothing open
    MODE['empty'] = True; pg.reload(); pg.wait_for_timeout(1400); office(pg, 'repairs')
    check('No open repairs.' in pg.inner_text('#oBody') and pg.locator('.rr-chips').count() == 0, 'repairs: empty state, no chips')
    pg.click('#oTabs [data-o=review]'); pg.wait_for_timeout(1200)
    check('Nothing waiting for review.' in pg.inner_text('#oBody'), 'review: empty state')
    pg.screenshot(path=os.path.join(OUT, '34-review-empty.png'))
    MODE['empty'] = False
    check(not errs, 'no page errors (themes, sw) %s' % errs[:3])

    # ---------------- 1280px desktop
    pg.set_viewport_size({'width': 1280, 'height': 900}); pg.reload(); pg.wait_for_timeout(1400); office(pg, 'repairs'); pg.evaluate('scrollTo(0,0)'); pg.wait_for_timeout(600)
    side = pg.locator('.rr .o-side').bounding_box(); main = pg.locator('.rr-list').bounding_box()
    check(side and main and side['x'] > main['x'] + main['width'] - 2, 'desktop: list on the left, folded rows on the right')
    tidy(pg, '#oBody', 'repairs desktop')
    pg.screenshot(path=os.path.join(OUT, '40-desktop-repairs.png'))
    pg.click('[data-rrjob="104"]'); pg.wait_for_timeout(800); pg.screenshot(path=os.path.join(OUT, '41-desktop-job-sheet.png')); close(pg)
    pg.click('#oTabs [data-o=review]'); pg.wait_for_timeout(1200); pg.evaluate('scrollTo(0,0)'); pg.wait_for_timeout(400)
    pg.screenshot(path=os.path.join(OUT, '42-desktop-review.png'))
    pg.click('[data-rrrev=c1]'); pg.wait_for_timeout(800); pg.screenshot(path=os.path.join(OUT, '43-desktop-review-sheet.png')); close(pg)
    theme(pg, 'dark'); pg.reload(); pg.wait_for_timeout(1400); office(pg, 'review'); pg.evaluate('scrollTo(0,0)'); pg.wait_for_timeout(500); pg.screenshot(path=os.path.join(OUT, '44-desktop-review-dark.png')); theme(pg, '')
    check(not errs, 'no page errors (desktop) %s' % errs[:3])
    br.close()
print('PROBLEMS', problems)
