# Field tools: Scan QR (Home), check-in by QR (#p=<id> and a photo of the sticker), the visit's qr flag,
# Office › QR codes (A4 sticker sheet, print / PDF), and Map › Save this area for offline (saved areas, settings,
# the service worker keeping viewed tiles of a saved area).
import json, os, re, datetime, base64
from playwright.sync_api import sync_playwright
HERE = os.path.dirname(os.path.abspath(__file__)); BASE = os.environ.get('BASE', 'http://localhost:8811'); OUT = os.path.join(HERE, 'field'); os.makedirs(OUT, exist_ok=True)
now = datetime.datetime.now(datetime.timezone.utc)
iso = lambda h: (now - datetime.timedelta(hours=h)).strftime('%Y-%m-%dT%H:%M:%SZ')
P = [{'id': '104', 'title': 'Kapsaret Borehole', 'country': 'Kenya', 'ck': 'ke', 'area': 'Eldoret', 'type': 'Borehole well', 'status': 'Completed', 'gps': [0.47, 35.25]},
     {'id': '105', 'title': 'Turbo Spring', 'country': 'Kenya', 'ck': 'ke', 'area': 'Turbo', 'type': 'Water collection', 'status': 'Completed', 'gps': [0.63, 35.05]},
     {'id': '106', 'title': 'Chepkongi Dispensary Well', 'country': 'Kenya', 'ck': 'ke', 'area': 'Eldoret', 'type': 'Borehole well', 'status': 'Completed', 'gps': [0.5143, 35.2698]},
     {'id': '311', 'title': 'Nzoia Bridge Primary School and Community Water Kiosk', 'country': 'Kenya', 'ck': 'ke', 'area': 'Bungoma', 'type': 'School', 'status': 'Completed', 'gps': [0.58, 34.62]},
     {'id': '402', 'title': 'Mto wa Mbu Borehole', 'country': 'Tanzania', 'ck': 'tz', 'area': 'Arusha', 'type': 'Borehole well', 'status': 'Completed', 'gps': [-3.37, 35.85]}]
posts, problems = [], []
def check(c, w): print(('ok   ' if c else 'FAIL ') + w); (None if c else problems.append(w))
ADMIN = {'v': False}
def api(route, req):
    if req.method == 'GET':
        if 'activity=' in req.url: return route.fulfill(json={'ok': True, 'activity': []})
        if 'team=' in req.url: return route.fulfill(json={'ok': True, 'team': []})
        if '?id=' in req.url: return route.fulfill(json={'ok': True, 'last': None, 'canUndo': True, 'visits': []})
        return route.fulfill(json={'ok': True, 'who': 'Meril Kiprop', 'admin': ADMIN['v'], 'reviewOpen': 0, 'projects': P, 'repairs': {}, 'visits': {}, 'jobs': [], 'assigned': [], 'confirmed': {}, 'me': {'kind': 'app', 'nick': '', 'phone': '', 'home': 'ke'}})
    b = json.loads(req.post_data); posts.append(b)
    route.fulfill(json={'ok': True, 'id': b.get('id'), 'updateId': '9001'})
PNG = base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==')
def tiles(route, req): route.fulfill(status=200, body=PNG, headers={'Content-Type': 'image/png', 'Access-Control-Allow-Origin': '*'})

def sign_in(pg):
    pg.route('**/api/pin-auth/me', lambda r, q: r.fulfill(json={'available': False})); pg.route(re.compile(r'/api/pin(\?.*)?$'), api)
    pg.goto(BASE + '/pin'); pg.wait_for_timeout(800)
    for d in '9999': pg.click('.pk[data-k="%s"]' % d)
    pg.wait_for_timeout(1200); pg.evaluate("localStorage.setItem('rfPinOnb','1')")

def qr_png(pg, text):
    # a photo of a sticker: the code drawn big on white, with some margin
    return base64.b64decode(pg.evaluate("""async (t) => {
      await new Promise((ok, no) => { if (window.qrcode) return ok(); const s = document.createElement('script'); s.src = '/vendor/qr/qrcode.min.js'; s.onload = ok; s.onerror = no; document.head.appendChild(s); });
      const q = qrcode(0, 'M'); q.addData(t); q.make(); const n = q.getModuleCount(), k = 12, m = 60, c = document.createElement('canvas'); c.width = c.height = n * k + 2 * m;
      const x = c.getContext('2d'); x.fillStyle = '#f4f1ea'; x.fillRect(0, 0, c.width, c.height); x.fillStyle = '#111';
      for (let r = 0; r < n; r++) for (let cc = 0; cc < n; cc++) if (q.isDark(r, cc)) x.fillRect(m + cc * k, m + r * k, k, k);
      return c.toDataURL('image/png').split(',')[1]; }""", text))

with sync_playwright() as p:
    br = p.chromium.launch()
    # --- 1. Scan QR from Home, check in by a photo of the sticker, the visit says qr: true ---------------
    ctx = br.new_context(viewport={'width': 390, 'height': 844}, is_mobile=True, has_touch=True, service_workers='block')
    ctx.route(re.compile(r'https://(tile\.openstreetmap\.org|server\.arcgisonline\.com)/.*'), tiles)
    pg = ctx.new_page(); errs = []; pg.on('pageerror', lambda e: errs.append(str(e)))
    sign_in(pg); pg.goto(BASE + '/pin#list'); pg.wait_for_timeout(1300)
    check(pg.locator('#homeTop .quick button').count() == 5 and 'Scan QR' in pg.inner_text('#homeTop .quick'), 'Home has five quick actions with Scan QR')
    pg.screenshot(path=os.path.join(OUT, '01-home-scan-qr.png'))
    pg.click('#homeTop [data-q=scan]'); pg.wait_for_timeout(600)
    check(pg.locator('#fldScan').count() == 1, 'Scan QR opens the scanner')
    check('Take a photo of the code' in pg.inner_text('#fldScan'), 'photo fallback offered')
    pg.screenshot(path=os.path.join(OUT, '02-scan.png'))
    pg.set_input_files('#fldPh', files=[{'name': 'wrong.png', 'mimeType': 'image/png', 'buffer': qr_png(pg, 'https://example.com/hello')}]); pg.wait_for_timeout(1500)
    check('isn’t a RAFIKI project sticker' in pg.inner_text('#fldSt'), 'a code that is not ours is refused: ' + pg.inner_text('#fldSt'))
    pg.set_input_files('#fldPh', files=[{'name': 'sticker.png', 'mimeType': 'image/png', 'buffer': qr_png(pg, 'https://pin.rafikioutreach.org/pin#p=104')}]); pg.wait_for_timeout(2200)
    check(pg.locator('#fldScan').count() == 0, 'scanner closes after a good code')
    check(pg.evaluate('location.hash') == '#p/104/visit', 'opens the site visit: ' + pg.evaluate('location.hash'))
    t = pg.inner_text('#vProj')
    check('Checked in by scanning the site’s QR code' in t and 'Arrived' in t, 'visit shows checked in by QR, arrived filled in')
    pg.screenshot(path=os.path.join(OUT, '03-visit-checked-in-by-qr.png'))
    pg.click('[data-act=next]'); pg.wait_for_timeout(300); pg.click('[data-state=working]'); pg.wait_for_timeout(200); pg.click('[data-go="6"]'); pg.wait_for_timeout(300)
    pg.click('[data-act=send]'); pg.wait_for_timeout(1500)
    v = [x for x in posts if x.get('action') == 'visit']
    check(len(v) == 1 and v[0].get('qr') is True and v[0]['id'] == '104' and v[0].get('arrive'), 'visit sent with qr: true and arrival')
    check(pg.evaluate("JSON.parse(localStorage.getItem('rfPinQr')||'{}')['104']") is None, 'qr mark cleared after sending')
    # typed number: opens the visit, not marked as QR
    pg.goto(BASE + '/pin#list'); pg.wait_for_timeout(900)
    pg.click('#homeTop [data-q=scan]'); pg.wait_for_timeout(400)
    pg.fill('#fldId', '999'); pg.click('#fldGo'); pg.wait_for_timeout(300)
    check('Project 999 isn’t in your list' in pg.inner_text('#fldSt'), 'unknown project number')
    pg.fill('#fldId', '311'); pg.click('#fldGo'); pg.wait_for_timeout(1200)
    check(pg.evaluate('location.hash') == '#p/311/visit' and 'Checked in by scanning' not in pg.inner_text('#vProj'), 'typed number opens the visit without the QR mark')
    # a sticker scanned with the phone's own camera: /pin#p=<id>
    pg.goto(BASE + '/pin#p=105'); pg.wait_for_timeout(2200)
    check(pg.evaluate('location.hash') == '#p/105/visit', 'the #p= address opens that visit: ' + pg.evaluate('location.hash'))
    check('Turbo Spring' in pg.inner_text('#pTitle') and 'Checked in by scanning the site’s QR code' in pg.inner_text('#vProj'), 'phone camera link: checked in by QR')
    pg.goto(BASE + '/pin#p=777'); pg.wait_for_timeout(1500)
    check(pg.evaluate('location.hash') == '#map', 'unknown project in a link goes to the map')
    # Kiswahili
    pg.evaluate("localStorage.setItem('rfPinLang','sw')"); pg.goto(BASE + '/pin#list'); pg.reload(); pg.wait_for_timeout(1300)
    pg.click('#homeTop [data-q=scan]'); pg.wait_for_timeout(500)
    check('Changanua QR' in pg.inner_text('#homeTop .quick') and 'Piga picha ya msimbo' in pg.inner_text('#fldScan'), 'scanner in Kiswahili')
    pg.screenshot(path=os.path.join(OUT, '04-scan-sw.png'))
    pg.keyboard.press('Escape'); pg.wait_for_timeout(200)
    pg.evaluate("localStorage.removeItem('rfPinLang')"); pg.goto(BASE + '/pin#list'); pg.reload(); pg.wait_for_timeout(1000)

    # --- 2. Map › Save this area for offline -----------------------------------------------------------
    pg.goto(BASE + '/pin#map'); pg.wait_for_timeout(1500)
    check(pg.locator('#fldOb').count() == 1, 'map has the Save for offline button')
    pg.click('#fldOb'); pg.wait_for_timeout(600)
    est = pg.inner_text('#fldEst')
    check('map tiles' in est and 'Zoom' in est, 'size estimate shown: ' + est.replace('\n', ' | '))
    pg.screenshot(path=os.path.join(OUT, '05-offline-sheet.png'))
    pg.click('#fldWhich [data-fa=me]'); pg.wait_for_timeout(400)
    est2 = pg.inner_text('#fldEst')
    check('Allow location' in est2 or '50 × 50 km' in est2, 'around me: needs location or shows 50 km box')
    pg.click('#fldWhich [data-fa=screen]'); pg.wait_for_timeout(300)
    pg.fill('#fldName', 'Eldoret trip')
    big = 'too big' in pg.inner_text('#fldEst')
    if big:
        pg.keyboard.press('Escape'); pg.click('.fld-sh .bsh-x'); pg.wait_for_timeout(300)
    check(not big, 'visible area is a sensible size')
    pg.click('#fldSave'); pg.wait_for_timeout(800)
    areas = pg.evaluate("JSON.parse(localStorage.getItem('rfPinFldAreas')||'[]')")
    check(len(areas) == 1 and areas[0]['name'] == 'Eldoret trip' and areas[0]['maxZ'] >= areas[0]['minZ'], 'area saved: %s' % areas)
    check(pg.locator('#fldBar').count() == 1 and 'Keeping “Eldoret trip” for offline' in pg.inner_text('#fldBar'), 'filling bar shows')
    cfg = pg.evaluate("caches.open('rafiki-pin-fld').then(c => c.match('/__fld/cfg')).then(r => r && r.json())")
    check(cfg and len(cfg['areas']) == 1 and cfg['keep'] == 30, 'service worker config written')
    # what the service worker does as tiles are viewed (blocked in this context): put a few in the area's cache
    a = areas[0]
    pg.evaluate("""(a) => { const tx = (l, z) => Math.floor((l + 180) / 360 * 2 ** z), ty = (l, z) => { const r = l * Math.PI / 180; return Math.floor((1 - Math.log(Math.tan(r) + 1 / Math.cos(r)) / Math.PI) / 2 * 2 ** z); };
      return caches.open('rafiki-pin-area-' + a.id).then(c => { const ps = []; for (let z = a.minZ; z <= a.minZ + 2; z++) for (let x = tx(a.w, z); x <= tx(a.e, z); x++) for (let y = ty(a.n, z); y <= ty(a.s, z); y++) ps.push(c.put('https://tile.openstreetmap.org/' + z + '/' + x + '/' + y + '.png', new Response('x'))); return Promise.all(ps); }); }""", a)
    pg.wait_for_timeout(3000)
    pct = pg.inner_text('#fldBp')
    check(re.search(r'Street map [1-9]\d*%', pct) is not None, 'coverage grows as tiles are kept: ' + pct)
    pg.screenshot(path=os.path.join(OUT, '06-offline-filling.png'))
    pg.click('#fldDone'); pg.wait_for_timeout(500)
    check(pg.locator('#fldBar').count() == 0, 'Done closes the bar')
    pg.click('#fldOb'); pg.wait_for_timeout(500); pg.click('#fldList'); pg.wait_for_timeout(1200)
    t = pg.inner_text('.fld-sh')
    check('Eldoret trip' in t and 'of the street map kept' in t and 'Storage limit for maps' in t and 'Why the app doesn’t download whole areas' in t, 'saved areas list')
    pg.screenshot(path=os.path.join(OUT, '07-offline-list.png'))
    pg.evaluate("document.documentElement.setAttribute('data-theme','sun')"); pg.wait_for_timeout(200)
    pg.screenshot(path=os.path.join(OUT, '07b-offline-list-sun.png')); pg.evaluate("document.documentElement.removeAttribute('data-theme')")
    pg.click('#fldLimit [data-v="500"]'); pg.wait_for_timeout(400); pg.click('#fldKeep [data-v="90"]'); pg.wait_for_timeout(400)
    st = pg.evaluate("JSON.parse(localStorage.getItem('rfPinFldSet'))")
    cfg = pg.evaluate("caches.open('rafiki-pin-fld').then(c => c.match('/__fld/cfg')).then(r => r && r.json())")
    check(st == {'limit': 500, 'keep': 90} and cfg['max'] == 25600 and cfg['keep'] == 90, 'settings saved for the service worker: %s %s' % (st, cfg and cfg['max']))
    pg.click('.fld-sh [data-del]'); pg.wait_for_timeout(200)
    check('Delete?' in pg.inner_text('.fld-sh [data-del]'), 'delete asks once more')
    pg.click('.fld-sh [data-del]'); pg.wait_for_timeout(600)
    check(pg.evaluate("JSON.parse(localStorage.getItem('rfPinFldAreas'))") == [] and not pg.evaluate("caches.has('rafiki-pin-area-%s')" % a['id']), 'area and its tiles deleted')
    pg.click('.fld-sh .bsh-x'); pg.wait_for_timeout(300)
    check(not errs, 'no page errors (part 1) %s' % errs[:3])
    ctx.close()

    # --- 3. Office › QR codes: sticker sheet, print to PDF; dark colours -------------------------------
    ADMIN['v'] = True
    ctx = br.new_context(viewport={'width': 1280, 'height': 900}, service_workers='block', color_scheme='dark')
    ctx.route(re.compile(r'https://(tile\.openstreetmap\.org|server\.arcgisonline\.com)/.*'), tiles)
    pg = ctx.new_page(); errs = []; pg.on('pageerror', lambda e: errs.append(str(e)))
    sign_in(pg); pg.evaluate("localStorage.setItem('rfPinTheme','dark')"); pg.goto(BASE + '/pin#office'); pg.reload(); pg.wait_for_timeout(1500)
    pg.click('.otab[data-o=qr]'); pg.wait_for_timeout(1500)
    t = pg.inner_text('#oBody')
    ck = pg.get_attribute('.fq-ck [aria-pressed=true]', 'data-fqck')
    check('QR check-in stickers' in t and (('4 of 4 chosen' in t) if ck == 'ke' else ('5 of 5 chosen' in t)), 'Office QR codes tab, starts on the home country (%s)' % ck)
    check(pg.locator('#oBody .fq-s svg').count() == 1, 'sticker preview with a QR code')
    pg.screenshot(path=os.path.join(OUT, '08-office-qr-dark.png'))
    check(pg.locator('#oBody [data-testid=qr_head] .dqm-nums > *').count() == 2, 'head with two numbers: chosen, A4 pages')
    check(pg.evaluate("document.getElementById('dqmSec_qrhow').getBoundingClientRect().height < 2"), 'how it works: folded')
    pg.click('[data-testid=qrhow_toggle]'); pg.wait_for_timeout(450)
    check('monday.com notes the visit was checked in by QR' in pg.inner_text('[data-testid=qrhow]'), 'how it works opens with a tap')
    pg.screenshot(path=os.path.join(OUT, '08b-office-qr-how-dark.png'))
    pg.click('[data-fqck=""]'); pg.wait_for_timeout(400)
    check('5 of 5 chosen' in pg.inner_text('#oBody'), 'all countries')
    pg.click('[data-fq=none]'); pg.wait_for_timeout(300); check('0 of 5 chosen' in pg.inner_text('#oBody') and pg.locator('#fqPrint[disabled]').count() == 1, 'none chosen: print off')
    pg.click('[data-fq=all]'); pg.wait_for_timeout(300)
    pg.locator('[data-fqid="402"]').uncheck(); pg.wait_for_timeout(300)
    check('4 of 5 chosen' in pg.inner_text('#oBody') and '4 stickers on 1 A4 page' in pg.inner_text('#oBody'), 'untick one')
    # the sticker read back by a QR reader
    url = pg.evaluate("""() => new Promise(ok => { const s = document.createElement('script'); s.src = '/vendor/qr/jsQR.min.js'; s.onload = () => {
        const svg = document.querySelector('#oBody .fq-s svg'), img = new Image(), c = document.createElement('canvas'); c.width = c.height = 400;
        img.onload = () => { const x = c.getContext('2d'); x.fillStyle = '#fff'; x.fillRect(0, 0, 400, 400); x.drawImage(img, 40, 40, 320, 320); const d = x.getImageData(0, 0, 400, 400); const r = jsQR(d.data, 400, 400); ok(r && r.data); };
        img.src = 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(new XMLSerializer().serializeToString(svg)); }; document.head.appendChild(s); })""")
    check(url == 'https://pin.rafikioutreach.org/pin#p=106', 'sticker QR reads back as the project address: %s' % url)
    pg.evaluate("() => { window.print = () => { window.__printed = (window.__printed || 0) + 1 } }")
    pg.click('#fqPrint'); pg.wait_for_timeout(600)
    pr = (pg.evaluate('window.__printed'), pg.locator('#fldPrint .fq-page').count(), pg.locator('#fldPrint .fq-s').count())
    check(pr == (1, 1, 4), 'print sheet: 1 A4 page with 4 stickers %s' % (pr,))
    pg.emulate_media(media='print', color_scheme='light'); pg.wait_for_timeout(300)
    pg.set_viewport_size({'width': 794, 'height': 1123}); pg.wait_for_timeout(300)
    pg.screenshot(path=os.path.join(OUT, '09-sticker-sheet-print.png'), full_page=True)
    pg.pdf(path=os.path.join(OUT, 'stickers.pdf'), format='A4', print_background=True, prefer_css_page_size=True)
    pg.emulate_media(media='screen', color_scheme='dark'); pg.set_viewport_size({'width': 1280, 'height': 900}); pg.evaluate("document.body.classList.remove('fld-printing')")
    # Kiswahili
    pg.evaluate("localStorage.setItem('rfPinLang','sw')"); pg.goto(BASE + '/pin#office'); pg.reload(); pg.wait_for_timeout(1500); pg.click('.otab[data-o=qr]'); pg.wait_for_timeout(1200)
    t = pg.inner_text('#oBody')
    check('Vibandiko vya QR vya kuingia' in t and 'Chapisha au hifadhi kama PDF' in t and 'kwenye kurasa' in t and 'Misimbo ya QR' in pg.inner_text('#oTabs'), 'Office QR codes in Kiswahili')
    pg.screenshot(path=os.path.join(OUT, '10-office-qr-sw.png'))
    ov = lambda: pg.evaluate("[...document.querySelectorAll('#oBody .dqm-nums > *, #oBody .fq-r, #oBody .fq-lh, #oBody .dqm-row')].filter(el => el.scrollWidth > el.clientWidth + 1).map(el => el.className)")
    check(not ov(), 'QR codes: nothing overflows (desktop, Kiswahili) %s' % ov())
    pg.set_viewport_size({'width': 390, 'height': 844}); pg.evaluate("document.documentElement.setAttribute('data-theme','sun')"); pg.wait_for_timeout(500)
    check(not ov(), 'QR codes: nothing overflows (phone, Kiswahili) %s' % ov())
    pg.screenshot(path=os.path.join(OUT, '10b-office-qr-sw-sun-phone.png'), full_page=True)
    pg.evaluate("localStorage.setItem('rfPinLang','en'); document.documentElement.removeAttribute('data-theme')"); pg.evaluate("localStorage.removeItem('rfPinTheme')"); pg.goto(BASE + '/pin#office'); pg.reload(); pg.wait_for_timeout(1500); pg.click('.otab[data-o=qr]'); pg.wait_for_timeout(1200)
    pg.click('[data-fqck=tz]'); pg.wait_for_timeout(400)
    check('1 of 1 chosen' in pg.inner_text('#oBody') and 'Mto wa Mbu Borehole' in pg.inner_text('.fq-s'), 'phone: country chip, preview follows')
    pg.screenshot(path=os.path.join(OUT, '10c-office-qr-phone.png'), full_page=True)
    pg.click('[data-fqck=""]'); pg.wait_for_timeout(400); pg.click('[data-fq=none]'); pg.wait_for_timeout(300)
    check('Choose at least one project' in pg.inner_text('#oBody'), 'phone: nothing chosen, a friendly note instead of the preview')
    pg.screenshot(path=os.path.join(OUT, '10d-office-qr-none-phone.png'))
    check(not errs, 'no page errors (part 2) %s' % errs[:3])
    ctx.close()

    # --- 4. the service worker keeps the tiles you look at inside a saved area ---------------------------
    ADMIN['v'] = False
    ctx = br.new_context(viewport={'width': 390, 'height': 844}, is_mobile=True, has_touch=True, service_workers='allow')
    ctx.route(re.compile(r'https://(tile\.openstreetmap\.org|server\.arcgisonline\.com)/.*'), tiles)
    pg = ctx.new_page(); errs = []; pg.on('pageerror', lambda e: errs.append(str(e)))
    sign_in(pg); pg.wait_for_timeout(1500)
    # maps open on satellite photos; this checks the street map is kept, so choose it (as a person would, once)
    pg.evaluate("localStorage.setItem('rfPinLayer','street')")
    pg.goto(BASE + '/pin#map'); pg.reload(); pg.wait_for_timeout(2500)
    ok = pg.evaluate("navigator.serviceWorker.ready.then(() => !!navigator.serviceWorker.controller)")
    if not ok: pg.reload(); pg.wait_for_timeout(2500)
    pg.click('#fldOb'); pg.wait_for_timeout(600); pg.fill('#fldName', 'Kitale'); pg.click('#fldSave'); pg.wait_for_timeout(2500)
    a = pg.evaluate("JSON.parse(localStorage.getItem('rfPinFldAreas'))")[0]
    # look over it: zoom in a step, then pan a little
    pg.evaluate("() => { const el = document.querySelector('#hMap'); el.dispatchEvent(new WheelEvent('wheel', { deltaY: -120, clientX: 195, clientY: 400, bubbles: true })); }"); pg.wait_for_timeout(2500)
    n = pg.evaluate("caches.open('rafiki-pin-area-%s').then(c => c.keys()).then(k => k.length)" % a['id'])
    weeks = pg.evaluate("caches.keys().then(ks => ks.filter(k => k.indexOf('rafiki-pin-') === 0))")
    sw_on = pg.evaluate("!!navigator.serviceWorker.controller")
    print('   service worker controlling: %s, caches: %s' % (sw_on, weeks))
    if sw_on:
        check(n > 0, 'viewed tiles inside the saved area are kept in its own cache: %d' % n)
        pg.wait_for_timeout(2600)
        check(re.search(r'Street map [1-9]\d*%', pg.inner_text('#fldBp')) is not None, 'coverage from the service worker: ' + pg.inner_text('#fldBp'))
        pg.screenshot(path=os.path.join(OUT, '11-offline-sw-kept.png'))
    else:
        print('     (service worker routing not available in this browser: skipped)')
    check(not errs, 'no page errors (part 3) %s' % errs[:3])
    br.close()
print('PROBLEMS', problems)
