# Donor pages. 1) Office › Donors (web pin app, /api/admin mocked): an admin adds a donor, ticks projects with
# search and filters, gets the private link and copies it; edits the donor (group viewers). 2) The donor's private
# page /donor/<token> with /api/donor mocked (data built by the real lib/donor.js, qa/pin/donor_mock.mjs): hero
# and count-up, map with exact pins and street/satellite, pin card with Google Maps, project detail with photos,
# before/after slider, filters, share, impact report PDF, and the "link not working" page.
# Office › Export (choice cards, folded options) is covered here too.
# Screenshots at 390 (phone) and 1280 (desktop) to qa/pin/donor/. No page errors allowed.
import io, json, os, re, subprocess
from urllib.parse import urlparse, parse_qs
from playwright.sync_api import sync_playwright
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__)); ROOT = os.path.dirname(os.path.dirname(HERE))
BASE = os.environ.get('BASE', 'http://localhost:8817'); OUT = os.path.join(HERE, 'donor'); os.makedirs(OUT, exist_ok=True)
TOKEN = 'Q7mF2kLx9PzR4tYc8VbN1sWq6HjD3gUe5AoKi0ZlM_x'
problems = []
def check(c, w): print(('ok   ' if c else 'FAIL ') + w); (None if c else problems.append(w))

VIEW = json.loads(subprocess.check_output(['node', os.path.join(HERE, 'donor_mock.mjs')], cwd=ROOT, stderr=subprocess.DEVNULL))
def tile(bg, line):
    im = Image.new('RGB', (256, 256), bg); d = ImageDraw.Draw(im)
    for i in range(0, 256, 64): d.line([(i, 0), (i, 256)], fill=line, width=1); d.line([(0, i), (256, i)], fill=line, width=1)
    b = io.BytesIO(); im.save(b, 'PNG'); return b.getvalue()
OSM, SAT = tile((236, 232, 222), (214, 208, 196)), tile((74, 98, 64), (60, 82, 52))
PHOTO = {'900': 'w-field3.jpg', '901': 'w-child.jpg', '902': 'w-drill1.jpg', '600': 'w-drill2.jpg', '601': 'collect.jpg', '602': 'w-team.jpg', '603': 'w-elder.jpg', '700': 'washing.jpg'}
def jpg(name): return open(os.path.join(ROOT, 'img', name), 'rb').read()
seen_tiles = {'osm': 0, 'esri': 0}
def tiles(route, req):
    sat = 'arcgisonline' in req.url; seen_tiles['esri' if sat else 'osm'] += 1
    route.fulfill(body=SAT if sat else OSM, content_type='image/png')
def photo(route, req):
    pid = re.search(r'/api/photo/(\d+)', req.url).group(1); route.fulfill(body=jpg(PHOTO.get(pid, 'w-field3.jpg')), content_type='image/jpeg')
def donor_api(route, req):
    q = parse_qs(urlparse(req.url).query)
    if 'photo' in q:
        ok = q.get('t', [''])[0] == TOKEN
        return route.fulfill(body=jpg(PHOTO.get(q['photo'][0], 'collect.jpg')), content_type='image/jpeg') if ok else route.fulfill(status=404, body='Not found')
    if req.headers.get('x-donor-token') == TOKEN: return route.fulfill(json=VIEW, headers={'Cache-Control': 'private, no-store'})
    route.fulfill(status=404, json={'ok': False, 'reason': 'link', 'contact': 'info@rafikioutreach.org'})

# --- Office › Donors ---------------------------------------------------------------------------------------
P = [{'id': '101', 'title': 'Kapsaret Borehole', 'country': 'Kenya', 'ck': 'ke', 'area': 'Kapsaret, Eldoret', 'type': 'Borehole well', 'status': 'Completed', 'gps': [0.470123, 35.250456]},
     {'id': '102', 'title': 'Ndalat Primary School', 'country': 'Kenya', 'ck': 'ke', 'area': 'Ndalat, Nandi', 'type': 'School', 'status': 'Completed', 'gps': [0.2, 35.1]},
     {'id': '103', 'title': 'Turbo Spring Well', 'country': 'Kenya', 'ck': 'ke', 'area': 'Turbo', 'type': 'Borehole well', 'status': 'Completed', 'gps': [0.63, 35.05]},
     {'id': '104', 'title': 'Magara Water Tank', 'country': 'Tanzania', 'ck': 'tz', 'area': 'Magara, Manyara', 'type': 'Water collection', 'status': 'Under construction', 'gps': [-3.71, 35.83]},
     {'id': '105', 'title': 'Kipkaren Children’s Home', 'country': 'Kenya', 'ck': 'ke', 'area': 'Kipkaren', 'type': 'Orphanage', 'status': 'Completed', 'gps': [0.55, 35.2]},
     {'id': '106', 'title': 'Mzuzu Clinic', 'country': 'Malawi', 'ck': 'mw', 'area': 'Mzuzu', 'type': 'Health', 'status': 'Completed', 'gps': [-11.46, 34.02]}]
STATE = {'donors': [{'id': 'dA1', 'name': 'Anderson Family', 'email': 'anderson@example.org', 'org': '', 'projects': ['103'], 'showPhotos': True, 'photos': {}, 'digest': True, 'added': '2026-09-01T10:00:00Z', 'by': 'Reid',
                      'lastOpen': '2026-10-19T18:20:00Z', 'opens': 4, 'revoked': False, 'viewers': [{'vid': 'vA', 'name': 'Anderson Family', 'email': 'anderson@example.org', 'primary': True, 'lastOpen': '2026-10-19T18:20:00Z', 'opens': 4, 'revoked': None, 'active': True, 'unsub': None}]}]}
posts = []
def link(t): return 'https://www.rafikioutreach.org/donor/' + t
def admin(route, req):
    if req.method == 'GET':
        if 'view=donors' in req.url: return route.fulfill(json={'ok': True, 'donors': STATE['donors'], 'origin': 'https://www.rafikioutreach.org', 'mail': {'ready': False}, 'cron': True, 'secret': True})
        if 'view=team' in req.url: return route.fulfill(json={'ok': True, 'team': [], 'mail': {'ready': False}})
        return route.fulfill(json={'ok': True, 'items': []})
    b = json.loads(req.post_data or '{}'); posts.append(b)
    if b['action'] == 'donor-add':
        d = {'id': 'dG1', 'name': b['name'], 'email': b['email'], 'org': b['org'], 'projects': b['projects'], 'showPhotos': b['showPhotos'], 'photos': b['photos'], 'digest': b['digest'], 'added': '2026-10-21T09:00:00Z', 'by': 'Reid',
             'lastOpen': None, 'opens': 0, 'revoked': False, 'viewers': [{'vid': 'vG', 'name': b['name'], 'email': b['email'], 'primary': True, 'lastOpen': None, 'opens': 0, 'revoked': None, 'active': True, 'unsub': None}]}
        STATE['donors'].append(d); return route.fulfill(json={'ok': True, 'donor': d, 'link': link(TOKEN)})
    if b['action'] == 'donor-link': return route.fulfill(json={'ok': True, 'link': link(TOKEN if b['id'] == 'dG1' else 'AndersonLinkAndersonLinkAndersonLinkAnderso')})
    if b['action'] == 'donor-revoke' and not b.get('vid'):
        for x in STATE['donors']:
            if x['id'] == b['id']: x['revoked'] = True
        return route.fulfill(json={'ok': True})
    if b['action'] == 'donor-remove':
        STATE['donors'] = [x for x in STATE['donors'] if x['id'] != b['id']]; return route.fulfill(json={'ok': True})
    if b['action'] == 'donor-viewer-add':
        d = [x for x in STATE['donors'] if x['id'] == b['id']][0]
        d['viewers'].append({'vid': 'vJ', 'name': b['name'], 'email': b.get('email', ''), 'primary': False, 'lastOpen': None, 'opens': 0, 'revoked': None, 'active': True, 'unsub': None})
        return route.fulfill(json={'ok': True, 'donor': d, 'link': link('PastorJohnLinkPastorJohnLinkPastorJohnLink1')})
    route.fulfill(json={'ok': True})
def pin(route, req):
    if req.method == 'GET':
        if 'activity=' in req.url: return route.fulfill(json={'ok': True, 'activity': []})
        if 'team=' in req.url: return route.fulfill(json={'ok': True, 'team': []})
        return route.fulfill(json={'ok': True, 'who': 'Reid Anderson', 'admin': True, 'reviewOpen': 0, 'projects': P, 'repairs': {}, 'visits': {}, 'jobs': [], 'assigned': [], 'confirmed': {}, 'me': {'kind': 'app', 'nick': '', 'phone': '', 'home': 'ke'}})
    route.fulfill(json={'ok': True})

with sync_playwright() as p:
    br = p.chromium.launch()
    def page_for(w, h, mobile):
        ctx = br.new_context(viewport={'width': w, 'height': h}, device_scale_factor=2, is_mobile=mobile, has_touch=mobile, service_workers='block')
        ctx.grant_permissions(['clipboard-read', 'clipboard-write'], origin=BASE)
        pg = ctx.new_page(); errs = []; pg.on('pageerror', lambda e: errs.append(str(e)))
        pg.route(re.compile(r'tile\.openstreetmap\.org/'), tiles); pg.route(re.compile(r'arcgisonline\.com/'), tiles)
        pg.route('**/fonts.googleapis.com/**', lambda r, q: r.abort()); pg.route('**/google.com/s2/**', lambda r, q: r.abort())
        pg.route(re.compile(r'/api/photo/\d+'), photo); pg.route(re.compile(r'/api/donor(\?.*)?$'), donor_api)
        pg.route('**/api/pin-auth/me', lambda r, q: r.fulfill(json={'available': False})); pg.route(re.compile(r'/api/pin(\?.*)?$'), pin); pg.route(re.compile(r'/api/admin(\?.*)?$'), admin)
        return ctx, pg, errs

    # ---- Office › Donors, phone: numbers, filters, calm cards; a sheet per donor with every action ----
    def shot(pg, name, full=False): pg.wait_for_timeout(450); pg.screenshot(path=os.path.join(OUT, name), full_page=full)
    def sheet_open(pg): pg.wait_for_selector('.tsh.in'); pg.wait_for_timeout(650)
    def sheet_close(pg): pg.keyboard.press('Escape'); pg.wait_for_timeout(400)
    def folded(pg, k): return pg.evaluate("k => document.getElementById('dqmSec_' + k).getBoundingClientRect().height < 2 && document.getElementById('dqmSec_' + k).hasAttribute('inert')", k)
    def fits(pg, sel):   # nothing wider than its box, no word split into pieces (each text node on whole words)
        return pg.evaluate("""(sel) => { const out = []; document.querySelectorAll(sel).forEach(el => { if (el.scrollWidth > el.clientWidth + 1 && getComputedStyle(el).overflowX !== 'auto' && getComputedStyle(el).textOverflow !== 'ellipsis') out.push(el.className + ': ' + el.textContent.slice(0, 40)); }); return out; }""", sel)
    ctx, pg, errs = page_for(390, 844, True)
    pg.goto(BASE + '/pin'); pg.wait_for_timeout(700)
    for d in '9999': pg.click('.pk[data-k="%s"]' % d)
    pg.wait_for_timeout(1000); pg.evaluate("localStorage.setItem('rfPinOnb','1')"); pg.goto(BASE + '/pin#office'); pg.wait_for_timeout(1200)
    check(pg.locator('#oTabs [data-o=donors]').count() == 1, 'Office has a Donors section')
    pg.click('#oTabs [data-o=donors]'); pg.wait_for_timeout(900)
    card = pg.locator('[data-testid=dn_card][data-dn=dA1]')
    check(card.count() == 1 and 'Anderson Family' in card.inner_text() and 'Opened their page' in card.inner_text() and '1 project' in card.inner_text(), 'donor card: name, status pill, projects')
    check(card.locator('button, input, a').count() == 0, 'no buttons or forms inside the card')
    nums = pg.eval_on_selector_all('[data-testid=dn_head] .dqm-nums b', 'e => e.map(x => x.textContent)')
    check(nums == ['1', '1', '0'], 'head numbers: donors, opened, not opened %s' % nums)
    check(folded(pg, 'dnmail'), 'e-mail digest folded away')
    pg.click('[data-testid=dnmail_toggle]'); pg.wait_for_timeout(450)
    check('Off. Add RESEND_API_KEY and ALERT_FROM in Cloudflare' in pg.inner_text('[data-testid=dnmail]') and pg.get_attribute('[data-testid=dnmail_toggle]', 'aria-expanded') == 'true', 'digest row opens: status off and what to add')
    shot(pg, '01-office-donors-phone.png')
    check(not fits(pg, '#oBody .dqm-card, #oBody .dqm-nums > *, #oBody .dqm-row'), 'nothing overflows on the phone')
    pg.click('[data-testid=dn_filter_not]'); pg.wait_for_timeout(300)
    check(pg.locator('[data-testid=dn_card]').count() == 0 and 'Everyone has opened their page' in pg.inner_text('#oBody'), 'filter Not opened: friendly empty state')
    pg.click('[data-testid=dn_n_all]'); pg.wait_for_timeout(300)
    check(pg.locator('[data-testid=dn_card]').count() == 1 and pg.get_attribute('[data-testid=dn_filter_all]', 'aria-pressed') == 'true', 'the Donors number tile sets the filter back to All')
    # add a donor: the form in a sheet
    pg.click('[data-testid=dn_add]'); sheet_open(pg)
    check(pg.locator('.tsh [data-testid=dn_form]').count() == 1, 'Add a donor opens the form in a sheet')
    pg.fill('#dnName', 'Grace Community Church'); pg.fill('#dnEmail', 'office@gracechurch.example'); pg.fill('#dnOrg', 'Grace Community Church')
    pg.fill('#dnQ', 'kap'); pg.wait_for_timeout(200)
    check(pg.locator('#dnList .dnr-p').count() == 1, 'search narrows the projects')
    pg.check('#dnList input[value="101"]'); pg.fill('#dnQ', ''); pg.wait_for_timeout(150)
    pg.select_option('#dnCat', 'school'); pg.wait_for_timeout(150)
    check(pg.locator('#dnList .dnr-p').count() == 1 and 'Ndalat' in pg.inner_text('#dnList'), 'type filter: schools')
    pg.check('#dnList input[value="102"]'); pg.select_option('#dnCat', ''); pg.select_option('#dnCk', 'tz'); pg.wait_for_timeout(150)
    check(pg.locator('#dnList .dnr-p').count() == 1, 'country filter: Tanzania'); pg.check('#dnList input[value="104"]'); pg.select_option('#dnCk', '')
    pg.check('#dnList input[value="103"]'); pg.wait_for_timeout(150)
    check(pg.inner_text('#dnCount') == '4 ticked', 'four projects ticked')
    check(pg.locator('#dnSens [data-ph="102"]').count() == 1, 'school photos need a consent tick')
    shot(pg, '02-office-add-donor-sheet-phone.png')
    pg.locator('#dnSens').scroll_into_view_if_needed(); shot(pg, '02b-office-add-donor-consent-phone.png')
    pg.click('#dnSave'); pg.wait_for_timeout(1400)
    add = [x for x in posts if x['action'] == 'donor-add']
    check(add and sorted(add[0]['projects']) == ['101', '102', '103', '104'] and add[0]['showPhotos'] is True and add[0]['photos'] == {}, 'donor-add sent with projects and photo rule')
    check(pg.locator('.tsh [data-testid=dn_sheet]').count() == 1 and '/donor/' + TOKEN in pg.inner_text('.tsh .dqm-dnlink').replace('​', ''), 'the new donor’s sheet opens with the private link')
    check('Added ✓' in pg.inner_text('.tsh .dqm-acts .msg') and pg.locator('[data-testid=dn_card]').count() == 2, 'Added ✓, and the list has two donors')
    pg.click('.tsh .dqm-dnlink [data-copy]'); pg.wait_for_timeout(300)
    check(pg.evaluate('navigator.clipboard.readText()') == link(TOKEN), 'Copy puts the private link on the clipboard')
    wa = pg.get_attribute('.tsh .dqm-dnlink a[href^="https://wa.me"]', 'href'); check(wa and 'donor%2F' + TOKEN in wa, 'WhatsApp share carries the link')
    lines = pg.evaluate("""() => { const c = document.querySelector('.tsh .dnr-link code'); const tn = [...c.childNodes].filter(n => n.nodeType === 3).pop(); const r = document.createRange(); r.selectNodeContents(tn); return new Set([...r.getClientRects()].map(x => Math.round(x.top))).size; }""")
    check(lines == 1, 'the private token stays whole on one line (%d)' % lines)
    shot(pg, '03-office-link-made-sheet-phone.png')
    sheet_close(pg)
    # an existing donor's sheet: copy, share, rotate (asks), revoke (asks), viewers, edit
    pg.click('[data-testid=dn_card][data-dn=dA1]'); sheet_open(pg)
    t = pg.inner_text('.tsh [data-testid=dn_sheet]')
    check('Anderson Family' in t and 'anderson@example.org' in t and 'Last opened' in t and '4 times' in t, 'donor sheet: details')
    pg.click('.tsh [data-testid=dn_copy]'); pg.wait_for_timeout(400)
    check('AndersonLink' in pg.evaluate('navigator.clipboard.readText()') and 'Copied ✓' in pg.inner_text('.tsh [data-testid=dn_copy]'), 'Copy private link from the sheet')
    shot(pg, '04-office-donor-sheet-phone.png')
    n0 = len(posts); pg.click('.tsh [data-testid=dn_revoke]'); pg.wait_for_timeout(400)
    check(pg.locator('.tsh .tm-ask.bad').count() == 1 and len(posts) == n0, 'Revoke asks first and sends nothing yet')
    pg.locator('.tsh .tm-ask').scroll_into_view_if_needed(); shot(pg, '05-office-donor-revoke-ask-phone.png')
    pg.click('.tsh .tm-ask [data-k=no]'); pg.wait_for_timeout(200); check(pg.locator('.tsh .tm-ask').count() == 0, 'Cancel closes the question')
    pg.click('.tsh [data-testid=dn_share]'); pg.wait_for_timeout(500)
    check('AndersonLink' in pg.inner_text('.tsh .dqm-dnlink').replace('​', ''), 'Share link shows the link with WhatsApp and e-mail')
    pg.click('.tsh [data-testid=dnv_toggle]'); pg.wait_for_timeout(450)
    check('Who can open this page' in pg.inner_text('.tsh') and pg.locator('.tsh .dqm-v').count() == 1, 'viewers folded in the sheet, one person')
    sheet_close(pg)
    pg.click('[data-testid=dn_card][data-dn=dG1]'); sheet_open(pg)
    pg.fill('#dnVName', 'Pastor John'); pg.click('#dnVAdd'); pg.wait_for_timeout(600)
    check('PastorJohnLink' in pg.inner_text('#dnVLink').replace('​', '') and pg.locator('.tsh .dqm-v').count() == 2, 'group page: a second viewer with their own link')
    pg.locator('#dnVLink').scroll_into_view_if_needed(); shot(pg, '06-office-group-viewers-phone.png')
    pg.click('.tsh [data-testid=dn_edit]'); sheet_open(pg); pg.wait_for_timeout(300)
    check(pg.locator('.tsh [data-testid=dn_form]').count() == 1 and 'Grace Community Church' in pg.inner_text('.tsh .dqm-shd') and pg.locator('#dnList input:checked').count() == 4, 'Edit opens the form sheet with the ticked projects')
    pg.click('#dnCancel'); pg.wait_for_timeout(400)
    # revoke, then delete (both confirmed)
    pg.click('[data-testid=dn_card][data-dn=dA1]'); sheet_open(pg)
    pg.click('.tsh [data-testid=dn_revoke]'); pg.wait_for_timeout(300); pg.click('.tsh .tm-ask [data-k=yes]'); pg.wait_for_timeout(900)
    check(any(x['action'] == 'donor-revoke' and x['id'] == 'dA1' for x in posts) and 'Link revoked' in pg.inner_text('.tsh .dqm-shd') and pg.locator('.tsh [data-testid=dn_newlink]').count() == 1, 'revoked: the sheet now offers a new link')
    check('Link revoked' in pg.inner_text('[data-testid=dn_card][data-dn=dA1]'), 'the list refreshes')
    shot(pg, '07-office-donor-revoked-phone.png')
    sheet_close(pg)
    pg.click('[data-testid=dn_filter_revoked]'); pg.wait_for_timeout(300)
    check(pg.locator('[data-testid=dn_card]').count() == 1, 'filter Revoked')
    pg.click('[data-testid=dn_filter_all]'); pg.wait_for_timeout(300)
    pg.click('[data-testid=dn_card][data-dn=dG1]'); sheet_open(pg)
    pg.click('.tsh [data-testid=dn_delete]'); pg.wait_for_timeout(300)
    check('Delete Grace Community Church?' in pg.inner_text('.tsh .tm-ask'), 'Delete asks first')
    pg.click('.tsh .tm-ask [data-k=yes]'); pg.wait_for_timeout(1000)
    check(any(x['action'] == 'donor-remove' and x['id'] == 'dG1' for x in posts) and pg.locator('.tsh').count() == 0 and pg.locator('[data-testid=dn_card]').count() == 1, 'deleted: sheet closes, list refreshes')
    # Kiswahili, dark and sun
    pg.evaluate("localStorage.setItem('rfPinLang','sw')"); pg.reload(); pg.wait_for_timeout(1500)
    if pg.locator('#oTabs [data-o=donors][aria-pressed=true]').count() == 0: pg.click('#oTabs [data-o=donors]'); pg.wait_for_timeout(800)
    t = pg.inner_text('#oBody')
    check('Wafadhili' in t and 'Ongeza mfadhili' in t and 'Kiungo kimezimwa' in t, 'Donors in Kiswahili')
    check(not fits(pg, '#oBody .dqm-card, #oBody .dqm-nums > *, #oBody .dqm-row'), 'nothing overflows in Kiswahili')
    shot(pg, '08-office-donors-sw-phone.png')
    pg.click('[data-testid=dn_card][data-dn=dA1]'); sheet_open(pg); shot(pg, '09-office-donor-sheet-sw-phone.png'); sheet_close(pg)
    pg.click('[data-testid=dn_add]'); sheet_open(pg); shot(pg, '09b-office-add-donor-sw-phone.png'); sheet_close(pg)
    pg.evaluate("localStorage.setItem('rfPinLang','en')"); pg.reload(); pg.wait_for_timeout(1400)
    for th in ['dark', 'sun']:
        pg.evaluate("t => document.documentElement.setAttribute('data-theme', t)", th); pg.wait_for_timeout(200)
        shot(pg, '10-office-donors-%s-phone.png' % th)
        pg.click('[data-testid=dn_card][data-dn=dA1]'); sheet_open(pg); shot(pg, '11-office-donor-sheet-%s-phone.png' % th); sheet_close(pg)
    pg.evaluate("document.documentElement.removeAttribute('data-theme')")
    # Project › Thank-you for donors (field app): a photo goes to /api/pin-thanks
    thx = []
    pg.route('**/api/pin-thanks', lambda r, q: (thx.append(q.post_data_buffer), r.fulfill(json={'ok': True, 'id': '101', 'asset': '700'})))
    pg.goto(BASE + '/pin#p/101'); pg.wait_for_timeout(1200)
    check(pg.locator('#dnrThx .dnr-thx').count() == 1, 'project screen has Thank-you for donors')
    pg.click('#dnrThx summary'); pg.set_input_files('#dnThxFile', os.path.join(ROOT, 'img', 'washing.jpg')); pg.fill('#dnThxCap', 'Asante sana!')
    pg.locator('#dnrThx').scroll_into_view_if_needed(); pg.screenshot(path=os.path.join(OUT, '06-field-thank-you-phone.png'))
    pg.click('#dnThxGo'); pg.wait_for_timeout(800)
    check(thx and b'name="id"' in thx[0] and b'101' in thx[0] and b'Asante sana!' in thx[0] and 'Sent' in pg.inner_text('#dnThxMsg'), 'thank-you photo sent with project and caption')
    check(not errs, 'no page errors (office phone): %s' % errs); ctx.close()

    # ---- Office › Donors and Export, desktop ----
    ctx, pg, errs = page_for(1280, 900, False)
    pg.goto(BASE + '/pin'); pg.wait_for_timeout(700)
    for d in '9999': pg.click('.pk[data-k="%s"]' % d)
    pg.wait_for_timeout(1000); pg.evaluate("localStorage.setItem('rfPinOnb','1'); localStorage.setItem('rfPinOTab','donors')"); pg.goto(BASE + '/pin#office'); pg.reload(); pg.wait_for_timeout(1600)
    check(pg.locator('[data-testid=dn_head]').count() == 1 and pg.locator('#oBody .o-side .dqm-sets').count() == 1, 'desktop: donors open from the saved tab, settings in the side column')
    pg.click('[data-testid=dnhow_toggle]'); pg.wait_for_timeout(450)
    shot(pg, '12-office-donors-desktop.png')
    check(not fits(pg, '#oBody .dqm-card, #oBody .dqm-nums > *, #oBody .dqm-row'), 'nothing overflows on the desktop')
    pg.click('[data-testid=dn_card][data-dn=dA1]'); sheet_open(pg); shot(pg, '13-office-donor-sheet-desktop.png'); sheet_close(pg)
    pg.click('[data-testid=dn_add]'); sheet_open(pg); shot(pg, '14-office-add-donor-desktop.png'); sheet_close(pg)
    pg.evaluate("document.documentElement.setAttribute('data-theme','dark')"); shot(pg, '15-office-donors-dark-desktop.png'); pg.evaluate("document.documentElement.removeAttribute('data-theme')")
    # Export: a card per spreadsheet, the rest folded
    pg.click('#oTabs [data-o=export]'); pg.wait_for_timeout(900)
    check(pg.locator('#oBody .dqm-x').count() == 6 and pg.locator('[data-x=costs]').count() == 1 and pg.locator('[data-x=costsum]').count() == 1, 'export: six choice cards (with costs and the summary)')
    check(folded(pg, 'xrep') and folded(pg, 'xmail'), 'export: monthly report and e-mail summary folded')
    shot(pg, '20-export-desktop.png')
    with pg.expect_download() as dl: pg.click('[data-x=projects]')
    check(dl.value.suggested_filename.startswith('rafiki-projects-') and dl.value.suggested_filename.endswith('.csv'), 'export: projects CSV downloads')
    pg.click('[data-testid=xmail_toggle]'); pg.click('[data-testid=xrep_toggle]'); pg.wait_for_timeout(500)
    check('CRON_KEY' in pg.inner_text('[data-testid=xmail]') and pg.locator('#xRep').is_visible(), 'export: folds open')
    shot(pg, '21-export-open-desktop.png')
    for th in ['dark', 'sun']:
        pg.evaluate("t => document.documentElement.setAttribute('data-theme', t)", th); shot(pg, '22-export-%s-desktop.png' % th)
    pg.evaluate("document.documentElement.removeAttribute('data-theme')")
    check(not errs, 'no page errors (office desktop): %s' % errs); ctx.close()
    ctx, pg, errs = page_for(390, 844, True)
    pg.goto(BASE + '/pin'); pg.wait_for_timeout(700)
    for d in '9999': pg.click('.pk[data-k="%s"]' % d)
    pg.wait_for_timeout(1000); pg.evaluate("localStorage.setItem('rfPinOnb','1'); localStorage.setItem('rfPinOTab','export'); localStorage.setItem('rfPinLang','sw')"); pg.goto(BASE + '/pin#office'); pg.reload(); pg.wait_for_timeout(1600)
    t = pg.inner_text('#oBody')
    check('Pakua jedwali' in t and 'Ziara za maeneo' in t and 'Ripoti ya mwezi' in t, 'export in Kiswahili')
    check(not fits(pg, '#oBody .dqm-card, #oBody .dqm-row'), 'export: nothing overflows in Kiswahili on the phone')
    shot(pg, '23-export-sw-phone.png', True)
    check(not errs, 'no page errors (export phone): %s' % errs); ctx.close()

    # ---- donor page ----
    for (w, h, mobile, tag) in [(390, 844, True, 'phone'), (1280, 900, False, 'desktop')]:
        ctx, pg, errs = page_for(w, h, mobile)
        pg.goto(BASE + '/donor/' + TOKEN); pg.wait_for_selector('.hero h1'); pg.wait_for_timeout(1600)
        check(pg.inner_text('.hero h1') == 'Welcome, Grace', '%s: hero greets the donor' % tag)
        check('2,700 people' in pg.inner_text('.hero .say'), '%s: impact sentence' % tag)
        stats = pg.eval_on_selector_all('.stat b', 'e => e.map(x => x.textContent)')
        check(stats[:4] == ['2,700', '5.4', '50%', '10,560'], '%s: count-up ends on the totals %s' % (tag, stats))
        check(pg.get_attribute('meta[name=referrer]', 'content') == 'strict-origin' and pg.get_attribute('meta[name=robots]', 'content') == 'noindex, nofollow', '%s: noindex, no referrer leak' % tag)
        pg.screenshot(path=os.path.join(OUT, '10-donor-hero-%s.png' % tag))
        # map: exact pins
        pins = pg.locator('.leaflet-marker-icon').count()
        check(pins == 4, '%s: four pins on the map' % tag)
        check(seen_tiles['osm'] > 0 and 'OpenStreetMap' in pg.inner_text('.leaflet-control-attribution'), '%s: OSM tiles with attribution' % tag)
        pg.locator('#mapsec').scroll_into_view_if_needed(); pg.wait_for_timeout(300)
        pg.locator('.leaflet-marker-icon[title="Kapsaret Borehole"]').dispatch_event('click'); pg.wait_for_timeout(500)
        gm = pg.get_attribute('#pcard a[href*="google.com/maps"]', 'href')
        check(gm == 'https://www.google.com/maps/search/?api=1&query=0.470123,35.250456', '%s: Google Maps link has the exact point: %s' % (tag, gm))
        check(pg.get_attribute('#pcard a[href*="google.com/maps"]', 'rel') == 'noopener noreferrer', '%s: no referrer to Google' % tag)
        pg.screenshot(path=os.path.join(OUT, '11-donor-map-%s.png' % tag))
        pg.click('.mtog [data-l=sat]'); pg.wait_for_timeout(600)
        check(seen_tiles['esri'] > 0 and 'Esri' in pg.inner_text('.leaflet-control-attribution'), '%s: satellite (Esri) with attribution' % tag)
        pg.click('.mtog [data-l=street]')
        # feed + filters
        pg.locator('#hUp').scroll_into_view_if_needed()
        n_all = pg.locator('#feed .up').count()
        pg.click('.chip[data-f=repair]'); pg.wait_for_timeout(200)
        reps = pg.locator('#feed .up').count(); txt = pg.inner_text('#feed')
        check(reps == 2 and 'Repaired' in txt and 'KES 5,200' in txt and 'Repair under way' in txt, '%s: Repairs filter (%d items)' % (tag, reps))
        pg.click('.chip[data-f=story]'); pg.wait_for_timeout(200)
        check('My daughters used to walk' in pg.inner_text('#feed') and 'thank-you' in pg.inner_text('#feed').lower(), '%s: Stories filter with consented quotes and the thank-you' % tag)
        pg.click('.chip[data-f=visit]'); pg.wait_for_timeout(200)
        check(pg.locator('#feed .up').count() >= 5 and 'water tested safe' in pg.inner_text('#feed'), '%s: Visits filter' % tag)
        pg.click('.chip[data-f=all]'); pg.wait_for_timeout(200)
        check(pg.locator('#feed .up').count() == n_all and n_all >= 12, '%s: All (%d)' % (tag, n_all))
        pg.locator('#feed').scroll_into_view_if_needed(); pg.screenshot(path=os.path.join(OUT, '12-donor-updates-%s.png' % tag))
        # project detail
        pg.locator('.pj[data-open="101"]').click(); pg.wait_for_timeout(700)
        check(pg.locator('#ov.on').count() == 1 and pg.inner_text('#dTitle') == 'Kapsaret Borehole', '%s: project detail opens' % tag)
        d = pg.inner_text('#det')
        check('Working' in d and 'Last checked 14 Oct 2026' in d and 'Kapsaret, Eldoret, Kenya' in d and '1,450' in d and '0.470123, 35.250456' in d, '%s: badge, last checked, place, people, exact point' % tag)
        check(pg.locator('#det .gal img').count() == 4, '%s: gallery (board photo + 3 visit photos)' % tag)
        check('One year of water' in d, '%s: anniversary card' % tag)
        pg.screenshot(path=os.path.join(OUT, '13-donor-project-%s.png' % tag))
        pg.locator('#ba').scroll_into_view_if_needed(); pg.focus('#ba input'); pg.keyboard.press('ArrowRight'); pg.keyboard.press('ArrowRight'); pg.wait_for_timeout(100)
        check(pg.eval_on_selector('#ba', 'e => e.style.getPropertyValue("--x")') == '52%', '%s: before/after slider works with the keyboard' % tag)
        pg.screenshot(path=os.path.join(OUT, '14-donor-before-after-%s.png' % tag))
        pg.locator('#det .tl').scroll_into_view_if_needed(); pg.wait_for_timeout(200); pg.screenshot(path=os.path.join(OUT, '15-donor-visits-%s.png' % tag))
        # share: the public card link, never the private one
        pg.evaluate("navigator.share = undefined")
        pg.click('.dbar [data-share]'); pg.wait_for_timeout(300)
        clip = pg.evaluate('navigator.clipboard.readText()')
        check(clip.endswith('/p/101-kapsaret-borehole') and TOKEN not in clip, '%s: Share copies the public card link: %s' % (tag, clip))
        pg.keyboard.press('Escape'); pg.wait_for_timeout(400)
        check(pg.locator('#ov.hidden').count() == 1, '%s: Escape closes the project' % tag)
        # the school: photos hidden until approved
        pg.locator('.pj[data-open="102"]').click(); pg.wait_for_timeout(500)
        check(pg.locator('#det .gal img').count() == 1 and 'pupils' in pg.inner_text('#det').lower(), '%s: school shows board photo only (visit photos need consent) and pupils' % tag)
        pg.keyboard.press('Escape'); pg.wait_for_timeout(300)
        mail = pg.get_attribute('.band a[href^="mailto:"]', 'href')
        check(mail.startswith('mailto:info@rafikioutreach.org?subject=') and 'Grace%20Community%20Church' in mail, '%s: Ask the office (mailto with the donor name)' % tag)
        check(pg.get_attribute('.band a[href="/donate"]', 'href') == '/donate', '%s: Give again goes to /donate' % tag)
        pg.locator('.band').scroll_into_view_if_needed(); pg.screenshot(path=os.path.join(OUT, '16-donor-thanks-band-%s.png' % tag))
        # impact report PDF
        pg.evaluate("window.print = function () {}"); pg.evaluate("dnrReport()"); pg.wait_for_timeout(500)
        if tag == 'desktop':
            pg.emulate_media(media='print')
            pdf = os.path.join(OUT, 'impact-report.pdf'); pg.pdf(path=pdf, format='Letter', print_background=True)
            pg.screenshot(path=os.path.join(OUT, '17-impact-report-print.png'), full_page=True)
            check(os.path.getsize(pdf) > 20000, 'impact report PDF written (%d bytes)' % os.path.getsize(pdf))
            check('1817 N. Beck Street' in pg.inner_text('#report') and 'Impact report' in pg.inner_text('#report') and 'Kapsaret Borehole' in pg.inner_text('#report'), 'report on the US letterhead with the projects')
            pg.emulate_media(media='screen')
        check(not errs, '%s: no page errors: %s' % (tag, errs)); ctx.close()

    # bad link
    ctx, pg, errs = page_for(390, 844, True)
    pg.goto(BASE + '/donor/' + 'X' * 43); pg.wait_for_selector('.gone h1'); pg.wait_for_timeout(300)
    check('This link isn’t working' in pg.inner_text('.gone h1') and pg.locator('.gone a[href^="mailto:info@rafikioutreach.org"]').count() == 1, 'invalid link: friendly page with contact')
    pg.screenshot(path=os.path.join(OUT, '18-donor-bad-link-phone.png'))
    check(not errs, 'no page errors (bad link): %s' % errs); ctx.close()
    # public share card (functions/p/[slug].js, rendered with its own cardHtml)
    card = subprocess.check_output(['node', '--input-type=module', '-e', "import { cardHtml } from './functions/p/[slug].js'; const p = { id: '101', title: 'Kapsaret Borehole', country: 'Kenya', ck: 'ke', area: 'Kapsaret, Eldoret', type: 'Borehole well', people: 1200, year: 2025, status: 'Completed', photos: ['900'], gps: [0.470123, 35.250456], depth: 80 }; process.stdout.write(cardHtml(p, '" + BASE + "', { quote: 'My daughters used to walk two hours for water. Now they are in class before eight.', who: 'Mary Chebet' }, false));"], cwd=ROOT, stderr=subprocess.DEVNULL).decode()
    check('noindex' in card and 'Grace' not in card and TOKEN not in card, 'public card: noindex, no donor name, no private link')
    for (w, h, mobile, tag) in [(390, 844, True, 'phone'), (1280, 900, False, 'desktop')]:
        ctx, pg, errs = page_for(w, h, mobile)
        pg.route(re.compile(r'/p/101-kapsaret-borehole$'), lambda r, q: r.fulfill(body=card, content_type='text/html; charset=utf-8'))
        pg.goto(BASE + '/p/101-kapsaret-borehole'); pg.wait_for_timeout(600)
        check(pg.inner_text('h1') == 'Kapsaret Borehole', '%s: public card renders' % tag)
        pg.screenshot(path=os.path.join(OUT, '19-public-card-%s.png' % tag), full_page=True)
        check(not errs, '%s: no page errors (public card): %s' % (tag, errs)); ctx.close()
    br.close()

print('\n%d problem(s)' % len(problems)); raise SystemExit(1 if problems else 0)
