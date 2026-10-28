# Projects by donor (dnr-tags): the board's "Donors" tags in the web pin app, /api/pin and /api/admin mocked.
# Office › Donors › Add a donor › From monday.com: the picker (counts, order, search, Back closes it), one tag ticks all
# its projects under "Funded by …", Select none / Select all / untick one, a second tag (union, name "A & B"), saving
# (mondayDonors, mondayKnown), the list chip, "N new projects tagged … add them?" on a linked donor's sheet, refresh
# staying on Office › Donors, Projects › Filter › Donor, "Funded by" on the project screen, and none of it for the
# field team. At 320, 360, 390 and 1280 px, dark, sun and Kiswahili; no sideways scroll, nothing cut off or overlapping.
#   BASE=http://localhost:8895 python3 qa/pin/donor-tags.py        screenshots go to qa/pin/donor-tags/
import json, os, re, sys
from playwright.sync_api import sync_playwright
HERE = os.path.dirname(os.path.abspath(__file__)); BASE = os.environ.get('BASE', 'http://localhost:8895')
OUT = os.path.join(HERE, 'donor-tags'); os.makedirs(OUT, exist_ok=True)
for f in os.listdir(OUT):
    if f.endswith('.png'): os.remove(os.path.join(OUT, f))
TILE = open(os.path.join(HERE, 'tile.png'), 'rb').read()
# made-up donors (the real names stay on monday.com)
A, B, C, L = 'Grace Valley Church', 'Mwangi Family', 'Kito Foundation', 'The Wilson-Hartley Charitable Foundation of Greater Salt Lake'
def pr(i, title, area, ck, typ, donors, gps=True):
    c = {'ke': 'Kenya', 'tz': 'Tanzania', 'ug': 'Uganda', 'mw': 'Malawi'}[ck]
    p = {'id': str(i), 'title': title, 'country': c, 'ck': ck, 'area': area, 'type': typ, 'status': 'Completed', 'gps': [0.5 + i / 1000, 35.2] if gps else None}
    if donors: p['donors'] = donors
    return p
P = [pr(201, 'Kapsaret Borehole', 'Kapsaret', 'ke', 'Borehole well', [A]),
     pr(202, 'Ndalat Primary School Rainwater Tanks and Gutters', 'Ndalat', 'ke', 'School', [A, B]),
     pr(203, 'Turbo Spring Well', 'Turbo', 'ke', 'Borehole well', [A]),
     pr(204, 'Magara Water Tank', 'Magara, Manyara', 'tz', 'Water collection', [A]),
     pr(205, 'Kipkaren Children’s Home', 'Kipkaren', 'ke', 'Orphanage', [B]),
     pr(206, 'Mzuzu Clinic', 'Mzuzu', 'mw', 'Health', [C]),
     pr(207, 'Sigowet Borehole', 'Sigowet', 'ke', 'Borehole well', [C]),
     pr(208, 'Chepkongi Dispensary', 'Chepkongi', 'ke', 'Health', [C, L]),
     pr(209, 'Kisese Well', 'Kisese', 'tz', 'Borehole well', [L]),
     pr(210, 'Tororo Farm Training', 'Tororo', 'ug', 'Farming', None),
     pr(211, 'Babati Well', 'Babati', 'tz', 'Borehole well', ['Bybee Family', 'Ricks Family', 'Langston Family', 'Texas Steakhouse Group', 'Falling Creek Dental', 'Martin Family', 'Hope Lutheran Church', 'Jill Cloward'], False)]
COUNT = {A: 4, B: 2, C: 3, L: 2}
def viewer(vid, name): return {'vid': vid, 'name': name, 'email': '', 'primary': True, 'lastOpen': None, 'opens': 0, 'revoked': None, 'active': True, 'unsub': None, 'code': 'K7QM4XR9TB'}
def fresh_state():
    # Kito Foundation's page was made from its tag when it had one project; two more were tagged since (207, 208)
    return {'donors': [{'id': 'dK1', 'name': C, 'email': 'kito@example.org', 'org': '', 'projects': ['206'], 'showPhotos': True, 'photos': {}, 'digest': True, 'mondayDonors': [C], 'mondayKnown': ['206'],
                        'added': '2026-09-01T10:00:00Z', 'by': 'Reid', 'lastOpen': '2026-10-19T18:20:00Z', 'opens': 2, 'revoked': False, 'viewers': [viewer('vK', C)]},
                       {'id': 'dP1', 'name': 'Anderson Family', 'email': 'anderson@example.org', 'org': '', 'projects': ['210'], 'showPhotos': True, 'photos': {}, 'digest': True,
                        'added': '2026-09-01T10:00:00Z', 'by': 'Reid', 'lastOpen': None, 'opens': 0, 'revoked': False, 'viewers': [viewer('vP', 'Anderson Family')]}]}
STATE = fresh_state(); MODE = {'field': False}
posts, problems = [], []
def check(c, w): print(('ok   ' if c else 'FAIL ') + w); (None if c else problems.append(w))
def snap():
    if MODE['field']:
        return {'ok': True, 'who': 'Meril', 'admin': False, 'projects': [{k: v for k, v in p.items() if k != 'donors'} for p in P], 'repairs': {}, 'visits': {}, 'jobs': [], 'assigned': [], 'confirmed': {}, 'me': {'kind': 'app', 'nick': '', 'phone': '', 'home': 'ke'}}
    return {'ok': True, 'who': 'Reid Anderson', 'admin': True, 'reviewOpen': 0, 'projects': P, 'repairs': {}, 'visits': {}, 'jobs': [], 'assigned': [], 'confirmed': {}, 'me': {'kind': 'app', 'nick': '', 'phone': '', 'home': 'ke'}, 'approveAbove': 50000}
def pin_api(route, req):
    if req.method == 'GET':
        u = req.url
        if 'activity=' in u: return route.fulfill(json={'ok': True, 'activity': []})
        if 'history=' in u: return route.fulfill(json={'ok': True, 'notes': []})
        if 'team=' in u: return route.fulfill(json={'ok': True, 'team': []})
        if 'id=' in u: return route.fulfill(json={'ok': True, 'last': None, 'visits': [], 'costs': {'sum': 0, 'n': 0, 'last': []}})
        return route.fulfill(json=snap())
    return route.fulfill(json={'ok': True})
def admin_api(route, req):
    if req.method == 'GET':
        if 'view=donors' in req.url: return route.fulfill(json={'ok': True, 'donors': STATE['donors'], 'origin': 'https://www.rafikioutreach.org', 'mail': {'ready': False}, 'cron': True, 'secret': True})
        return route.fulfill(json={'ok': True, 'items': [], 'team': []})
    b = json.loads(req.post_data or '{}'); posts.append(b)
    if b.get('action') == 'donor-add':
        d = {'id': 'dN1', 'name': b['name'], 'email': b.get('email', ''), 'org': b.get('org', ''), 'projects': b['projects'], 'showPhotos': True, 'photos': {}, 'digest': True, 'mondayDonors': b.get('mondayDonors', []), 'mondayKnown': b.get('mondayKnown', []),
             'added': '2026-10-28T09:00:00Z', 'by': 'Reid', 'lastOpen': None, 'opens': 0, 'revoked': False, 'viewers': [viewer('vN', b['name'])]}
        STATE['donors'].append(d); return route.fulfill(json={'ok': True, 'donor': d, 'link': 'https://www.rafikioutreach.org/donor/Q7mF2kLx9PzR4tYc8VbN1sWq6HjD3gUe5AoKi0ZlM_x', 'code': 'K7QM4-XR9TB'})
    if b.get('action') == 'donor-update':
        d = [x for x in STATE['donors'] if x['id'] == b['id']][0]
        for k in ('projects', 'mondayKnown', 'mondayDonors', 'name'):
            if k in b: d[k] = b[k]
        return route.fulfill(json={'ok': True, 'donor': d})
    return route.fulfill(json={'ok': True})
def theme(pg, t): pg.evaluate("t => { localStorage.setItem('rfPinTheme', t); if (t) document.documentElement.setAttribute('data-theme', t); else document.documentElement.removeAttribute('data-theme'); }", t)
def login(ctx):
    pg = ctx.new_page(); errs = []; pg.on('pageerror', lambda e: errs.append(str(e)))
    pg.route('**/api/pin-auth/me', lambda r, q: r.fulfill(json={'available': False})); pg.route(re.compile(r'/api/pin(\?.*)?$'), pin_api)
    pg.route('**/api/admin**', admin_api)
    pg.route(re.compile(r'tile\.openstreetmap|arcgisonline|google\.com/s2|basemaps|tile\.'), lambda r, q: r.fulfill(body=TILE, content_type='image/png'))
    pg.goto(BASE + '/pin'); pg.wait_for_timeout(800)
    for d in '9999': pg.click('.pk[data-k="%s"]' % d)
    pg.wait_for_timeout(1200); pg.evaluate("localStorage.setItem('rfPinOnb','1'); localStorage.setItem('rfPinOTab','overview')")
    return pg, errs
def office(pg):
    pg.goto(BASE + '/pin#office'); pg.wait_for_timeout(900)
    pg.click('#oTabs [data-o=donors]'); pg.wait_for_timeout(1300)
def sideways(pg): return pg.evaluate('document.documentElement.scrollWidth <= innerWidth')
# text cut off (an element narrower than its words), or two things on top of each other, inside what's open
CUT = """sel => [...document.querySelectorAll(sel)].filter(x => x.offsetParent && getComputedStyle(x).overflow !== 'visible' ? x.scrollWidth > x.clientWidth + 1 : false).map(x => x.className + ': ' + x.textContent.trim().slice(0, 40))"""
OUTSIDE = """sel => { const bad = []; for (const x of document.querySelectorAll(sel)) { if (!x.offsetParent) continue; const r = x.getBoundingClientRect(), p = x.parentElement.getBoundingClientRect();
  if (r.right > p.right + 1 || r.left < p.left - 1) bad.push(x.className + ': ' + x.textContent.trim().slice(0, 40)); } return bad; }"""
OVERLAP = """sel => { const bad = []; for (const h of document.querySelectorAll(sel)) { const a = h.children[0], b = h.children[1]; if (!a || !b || !h.offsetParent) continue;
  const r = a.getBoundingClientRect(), s = b.getBoundingClientRect(); if (r.right > s.left + 1 && r.bottom > s.top + 1 && s.bottom > r.top + 1 && s.right > r.left + 1) bad.push(a.textContent.trim().slice(0, 40)); } return bad; }"""
def tidy(pg, tag, what):
    cut = pg.evaluate(CUT, '.tsh .dnt-o b, .tsh .dnt-gt, .tsh .dnt-chip, .tsh .dnt-b, .tsh .dnt-up b, .tsh .btn, .dnt-tag, .dnt-fund, .lfilt .bsh-o span, .tsh .dnr-p b')
    out = pg.evaluate(OUTSIDE, '.tsh .dnt-b, .tsh .dnt-chip, .tsh .dnt-go, .tsh .dnt-ub .btn, .tsh .dnt-pf .btn, .dnt-tag, .tsh .dnt-o')
    ov = pg.evaluate(OVERLAP, '.tsh .dnt-gh, .tsh .dnt-gr, .tsh .dnt-ga, .tsh .dnt-sh, .tsh .dnt-ub')
    check(not cut and not out and not ov and sideways(pg), '%s: %s: nothing cut off, outside or overlapping %s %s %s' % (tag, what, cut[:3], out[:3], ov[:3]))
def ticked(pg): return sorted(pg.eval_on_selector_all('#dnList input:checked', 'e => e.map(x => x.value)'))
def shot(pg, tag, name, full=False): pg.screenshot(path=os.path.join(OUT, '%s-%s.png' % (tag, name)), full_page=full)

RUNS = [(390, 844, 'phone', '', 0), (320, 640, 'p320', '', 0), (360, 740, 'p360', '', 0), (360, 740, 'p360sw', '', 1), (1280, 860, 'desktop', '', 0), (390, 844, 'dark', 'dark', 0), (390, 844, 'sun', 'sun', 0)]
if len(sys.argv) > 1: RUNS = [r for r in RUNS if r[2] in sys.argv[1:]]
with sync_playwright() as p:
    b = p.chromium.launch()
    for W, H, tag, th, sw in RUNS:
        STATE.clear(); STATE.update(fresh_state()); del posts[:]
        ctx = b.new_context(viewport={'width': W, 'height': H}, device_scale_factor=2 if W < 500 else 1)
        pg, errs = login(ctx)
        theme(pg, th)
        if sw: pg.evaluate("localStorage.setItem('rfPinLang','sw')"); pg.reload(); pg.wait_for_timeout(1500)
        office(pg)
        # the list: the linked donor carries its tag and how many are new
        chip = pg.locator('[data-dn=dK1] [data-testid=dnt_tag]')
        check(chip.count() == 1 and C in chip.inner_text() and ('2 new' in chip.inner_text() or '2 mpya' in chip.inner_text()), tag + ': list chip with the tag and "2 new"')
        check(pg.locator('[data-dn=dP1] [data-testid=dnt_tag]').count() == 0, tag + ': no chip on a donor made by hand')
        tidy(pg, tag, 'list'); pg.locator('[data-dn=dK1]').scroll_into_view_if_needed(); shot(pg, tag, '1-list')
        # Add a donor: From monday.com comes first
        pg.click('[data-testid=dn_add]'); pg.wait_for_timeout(700)
        check(pg.evaluate("document.querySelector('#dnrForm').firstElementChild.id") == 'dntSrc', tag + ': From monday.com is the first field')
        shot(pg, tag, '2-form-empty')
        pg.click('[data-testid=dnt_pick]'); pg.wait_for_timeout(600)
        names = pg.eval_on_selector_all('.tsh [data-testid=dnt_picker] .dnt-o b', 'e => e.map(x => x.textContent)')
        names = [x.replace('\u2060', '') for x in names]  # names on screen carry a no-break mark after hyphens
        check(names[:4] == [A, C, B, L] and len(names) == 12, tag + ': picker sorted by projects, then name %s' % names)
        counts = pg.eval_on_selector_all('.tsh [data-testid=dnt_picker] .dnt-o small', 'e => e.map(x => x.textContent)')
        check(counts[0] in ('4 projects', 'miradi 4') and counts[2] in ('2 projects', 'miradi 2'), tag + ': project counts %s' % counts)
        check(pg.locator('[data-testid=dnt_use]').is_disabled(), tag + ': Use waits for a tick')
        tidy(pg, tag, 'picker'); shot(pg, tag, '3-picker')
        pg.fill('#dntQ', 'famil'); pg.wait_for_timeout(200)
        check(pg.eval_on_selector_all('.tsh [data-testid=dnt_picker] .dnt-o b', 'e => e.map(x => x.textContent)') == [B, 'Bybee Family', 'Langston Family', 'Martin Family', 'Ricks Family'], tag + ': search narrows the donors')
        pg.fill('#dntQ', 'zzz'); pg.wait_for_timeout(200)
        check(pg.locator('.tsh [data-testid=dnt_picker] .dqm-note').is_visible(), tag + ': nobody matches')
        # Back closes the picker, not the form
        pg.go_back(); pg.wait_for_timeout(700)
        check(pg.locator('.tsh [data-testid=dnt_picker]').count() == 0 and pg.locator('.tsh [data-testid=dn_form]').count() == 1, tag + ': Back closes the picker, the form stays')
        # one tag: the name and all its projects
        pg.click('[data-testid=dnt_pick]'); pg.wait_for_timeout(600)
        pg.click('.tsh [data-testid=dnt_opt]:has-text("%s")' % A); pg.wait_for_timeout(100)
        check(('Use this donor' in pg.inner_text('[data-testid=dnt_use]') or 'Tumia mfadhili huyu' in pg.inner_text('[data-testid=dnt_use]')), tag + ': Use this donor')
        pg.click('[data-testid=dnt_use]'); pg.wait_for_timeout(700)
        check(pg.input_value('#dnName') == A, tag + ': name filled in')
        check(ticked(pg) == ['201', '202', '203', '204'], tag + ': all their projects ticked %s' % ticked(pg))
        g = pg.locator('[data-testid=dnt_group]')
        check(g.count() == 1 and A in g.first.locator('.dnt-gt').inner_text() and g.first.locator('input').count() == 4, tag + ': grouped "Funded by %s"' % A)
        check(re.search(r'4 (of|kati ya) 4', g.first.locator('.dnt-gn').inner_text()) is not None, tag + ': 4 of 4 ticked')
        other = pg.locator('#dnList .dnt-other')
        check(other.count() == 1 and pg.locator('#dnList > label.dnr-p:has-text("Mzuzu Clinic") .dnt-by').count() == 1, tag + ': other projects below, marked with their donors')
        tidy(pg, tag, 'form with a tag'); shot(pg, tag, '4-form-one-tag')
        pg.click('[data-testid=dnt_none]'); pg.wait_for_timeout(200)
        check(ticked(pg) == [], tag + ': Select none')
        pg.click('[data-testid=dnt_all]'); pg.wait_for_timeout(200)
        check(ticked(pg) == ['201', '202', '203', '204'], tag + ': Select all')
        pg.locator('[data-testid=dnt_group] label.dnr-p:has-text("Turbo Spring Well") input').click(); pg.wait_for_timeout(200)
        check(ticked(pg) == ['201', '202', '204'] and re.search(r'3 (of|kati ya) 4', g.first.locator('.dnt-gn').inner_text()) is not None, tag + ': untick one, 3 of 4')
        # by hand too
        pg.locator('#dnList > label.dnr-p:has-text("Tororo Farm Training") input').click(); pg.wait_for_timeout(200)
        # a second tag for the same page: the union
        pg.click('[data-testid=dnt_pick]'); pg.wait_for_timeout(600)
        check(pg.locator('.tsh [data-testid=dnt_opt]:has-text("%s") input' % A).is_checked(), tag + ': the chosen tag stays ticked')
        pg.click('.tsh [data-testid=dnt_opt]:has-text("%s")' % B); pg.wait_for_timeout(100)
        check('2' in pg.inner_text('[data-testid=dnt_use]') and ('5 projects' in pg.inner_text('[data-testid=dnt_use]') or 'miradi 5' in pg.inner_text('[data-testid=dnt_use]')), tag + ': Use these 2 donors · 5 projects')
        pg.click('[data-testid=dnt_use]'); pg.wait_for_timeout(700)
        check(pg.input_value('#dnName') == A + ' & ' + B, tag + ': name "A & B"')
        check(ticked(pg) == ['201', '202', '204', '205', '210'], tag + ': union, the unticked one stays off, the hand-picked one stays %s' % ticked(pg))
        check(pg.locator('[data-testid=dnt_group]').count() == 2 and pg.locator('[data-testid=dnt_chip]').count() == 2, tag + ': two groups and two chips')
        tidy(pg, tag, 'form with two tags')
        pg.locator('.tsh-c').first.evaluate('e => e.scrollTop = 0'); shot(pg, tag, '5-form-two-tags')
        pg.evaluate("document.querySelector('#dnList').scrollIntoView({block: 'center'})"); pg.wait_for_timeout(200); shot(pg, tag, '5b-form-list')
        # Save
        pg.click('#dnSave'); pg.wait_for_timeout(1500)
        add = [x for x in posts if x.get('action') == 'donor-add']
        check(len(add) == 1 and add[0]['mondayDonors'] == [A, B] and sorted(add[0]['projects']) == ['201', '202', '204', '205', '210'] and sorted(add[0]['mondayKnown']) == ['201', '202', '203', '204', '205'],
              tag + ': donor-add sends the tags, the ticked projects and every tagged one as known %s' % (add[0] if add else None))
        check(pg.locator('.tsh [data-testid=dn_sheet]').count() == 1 and pg.locator('.tsh [data-testid=dnt_linked]').count() == 1, tag + ': the new donor\'s sheet says it is linked and up to date')
        for _ in range(3):
            if pg.locator('.tsh:not(.out)').count(): pg.keyboard.press('Escape'); pg.wait_for_timeout(400)
        check(pg.locator('[data-dn=dN1] [data-testid=dnt_tag]').count() == 1, tag + ': the new donor has its chip in the list')
        # Kito Foundation: two projects tagged since
        pg.click('[data-dn=dK1]'); pg.wait_for_timeout(800)
        up = pg.locator('.tsh [data-testid=dnt_update]')
        check(up.count() == 1 and re.search(r'2 new projects tagged for Kito Foundation, add them\?|Miradi 2 mipya ina lebo ya Kito Foundation', up.inner_text()) is not None, tag + ': "2 new projects tagged for Kito Foundation, add them?"')
        tidy(pg, tag, 'donor sheet offer'); shot(pg, tag, '6-sheet-offer')
        pg.click('[data-testid=dnt_up_add]'); pg.wait_for_timeout(900)
        u = [x for x in posts if x.get('action') == 'donor-update']
        check(len(u) == 1 and sorted(u[-1]['projects']) == ['206', '207', '208'] and sorted(u[-1]['mondayKnown']) == ['206', '207', '208'], tag + ': Add them sends the new projects %s' % (u[-1] if u else None))
        check(pg.locator('.tsh [data-testid=dnt_update]').count() == 0 and pg.locator('.tsh [data-testid=dnt_linked]').count() == 1, tag + ': then up to date')
        shot(pg, tag, '7-sheet-linked')
        pg.keyboard.press('Escape'); pg.wait_for_timeout(500)
        # refresh stays on Office › Donors
        pg.reload(); pg.wait_for_timeout(2200)
        check('#office/donors' in pg.url and pg.locator('[data-testid=dn_card]').count() == 3, tag + ': refresh keeps Office › Donors %s' % pg.url)
        # Projects › Filter › Donor
        pg.goto(BASE + '/pin#list'); pg.wait_for_timeout(1200)
        pg.click('#bCountry'); pg.wait_for_timeout(600)
        check(pg.locator('.lfilt [data-testid=lf_donor]').count() == 1, tag + ': Filter has Donor')
        tidy(pg, tag, 'filter'); shot(pg, tag, '8-filter')
        pg.click('.lfilt [data-testid=lf_donor]'); pg.wait_for_timeout(700)
        check(pg.locator('.tsh [data-testid=dnt_picker]').count() == 1, tag + ': the donor picker opens over Filter')
        pg.go_back(); pg.wait_for_timeout(700)
        check(pg.locator('.tsh [data-testid=dnt_picker]').count() == 0 and pg.locator('.bsh .lfilt').count() == 1, tag + ': Back closes the picker, Filter stays')
        pg.click('.lfilt [data-testid=lf_donor]'); pg.wait_for_timeout(700)
        pg.click('.tsh [data-dnn="%s"]' % C); pg.wait_for_timeout(700)
        check(C in pg.inner_text('.lfilt [data-testid=lf_donor]') and pg.inner_text('.lfilt [data-testid=lf_donor] .lfn').strip() == '3', tag + ': Filter shows the donor and 3')
        shot(pg, tag, '9-filter-donor')
        pg.click('.lfilt [data-lx]'); pg.wait_for_timeout(600)
        pg.evaluate("document.querySelector('#iCountry').value = ''; document.querySelector('#iCountry').dispatchEvent(new Event('change'))"); pg.wait_for_timeout(500)
        rows = pg.eval_on_selector_all('#list .row', 'e => e.map(x => x.dataset.id).sort()')
        check(rows == ['206', '207', '208'], tag + ': the list shows their projects %s' % rows)
        check(C in pg.inner_text('#dntLChip') and pg.locator('#dntLChip').is_visible(), tag + ': "Funded by" under the Filter pill')
        tidy(pg, tag, 'list filtered'); shot(pg, tag, '10-list-donor')
        pg.click('[data-testid=l_donor]'); pg.wait_for_timeout(500)
        check(pg.locator('#list .row').count() == len(P) and pg.locator('#dntLChip').is_hidden(), tag + ': tapping it shows every project again')
        pg.click('#bCountry'); pg.wait_for_timeout(500); pg.click('.lfilt [data-testid=lf_donor]'); pg.wait_for_timeout(600); pg.click('.tsh [data-dnn="%s"]' % C); pg.wait_for_timeout(600); pg.click('.lfilt [data-lx]'); pg.wait_for_timeout(600)
        # project screen: Funded by
        pg.click('#list .row[data-id="208"]'); pg.wait_for_timeout(1200)
        fund = pg.locator('#pDonors')
        ft = fund.inner_text().replace('\u2060', '')
        check(fund.is_visible() and C in ft and L in ft and ('Funded by' in ft or 'Imefadhiliwa na' in ft), tag + ': project screen: Funded by both donors')
        tidy(pg, tag, 'project'); shot(pg, tag, '11-project')
        # Edit: the tags come back, Kito Foundation's projects grouped
        if tag == 'phone':
            office(pg); pg.click('[data-dn=dK1]'); pg.wait_for_timeout(800); pg.click('.tsh [data-testid=dn_edit]'); pg.wait_for_timeout(900)
            check(pg.locator('[data-testid=dnt_chip]').count() == 1 and C in pg.inner_text('[data-testid=dnt_chip]') and pg.locator('[data-testid=dnt_group] input:checked').count() == 3, tag + ': Edit keeps the tag and its 3 ticked projects')
            pg.click('[data-testid=dnt_chip] button'); pg.wait_for_timeout(300)
            check(pg.locator('[data-testid=dnt_chip]').count() == 0 and ticked(pg) == [], tag + ': taking the tag off unticks its projects')
            pg.click('#dnSave'); pg.wait_for_timeout(300)
            if pg.locator('.tsh .tm-ask, .po-ask, [role=alertdialog]').count() == 0: pg.wait_for_timeout(400)
            shot(pg, tag, '12-edit-untagged')
        check(not errs, tag + ': no page errors %s' % errs[:2])
        ctx.close()
    # the field team: nothing about donors
    MODE['field'] = True
    ctx = b.new_context(viewport={'width': 390, 'height': 844}, device_scale_factor=2)
    pg, errs = login(ctx)
    pg.goto(BASE + '/pin#list'); pg.wait_for_timeout(1200); pg.click('#bCountry'); pg.wait_for_timeout(600)
    check(pg.locator('.lfilt [data-testid=lf_donor]').count() == 0, 'field: no Donor in Filter')
    pg.click('.lfilt [data-lx]'); pg.wait_for_timeout(500)
    pg.evaluate("document.querySelector('#iCountry').value = ''; document.querySelector('#iCountry').dispatchEvent(new Event('change'))"); pg.wait_for_timeout(500)
    pg.click('#list .row[data-id="208"]'); pg.wait_for_timeout(1000)
    check(pg.locator('#pDonors').is_hidden() and C not in pg.inner_text('body'), 'field: no Funded by on the project screen')
    check(not errs, 'field: no page errors %s' % errs[:2])
    ctx.close()
    b.close()
print('PROBLEMS', problems); sys.exit(1 if problems else 0)
