# Office › Community: SMS / WhatsApp reports from water committees (/api/sms-admin, mocked here).
# Latest messages with their result, set-up status (which Cloudflare variables are set, webhook URLs to paste),
# the committee phone list (add, add from a message, remove), and the voice-note typing status.
# Light, dark, sun, Kiswahili and a wide screen; screenshots to qa/pin/sms/.
import json, os, re
from playwright.sync_api import sync_playwright
HERE = os.path.dirname(os.path.abspath(__file__)); BASE = os.environ.get('BASE', 'http://localhost:8813'); OUT = os.path.join(HERE, 'sms'); os.makedirs(OUT, exist_ok=True)
P = [{'id': '104', 'title': 'Kapsaret Borehole', 'country': 'Kenya', 'ck': 'ke', 'area': 'Eldoret', 'type': 'Borehole well', 'status': 'Completed', 'gps': [0.47, 35.25]},
     {'id': '105', 'title': 'Turbo Spring', 'country': 'Tanzania', 'ck': 'tz', 'area': 'Arusha', 'type': 'Water collection', 'status': 'Completed', 'gps': [-3.37, 36.68]}]
problems = []
def check(c, w): print(('ok   ' if c else 'FAIL ') + w); (None if c else problems.append(w))
REPORTS = [
    {'sid': 'a1', 'at': '2026-10-21T07:58:00Z', 'ch': 'sms', 'from': '+254712345678', 'text': 'IMEHARIBIKA 104 pampu', 'kind': 'broken', 'lang': 'sw', 'id': '104', 't': 'Kapsaret Borehole', 'result': 'reported-broken', 'reg': False, 'reply': 'RAFIKI: Asante. Ripoti ya Kapsaret Borehole (haifanyi kazi) imepokelewa. Timu yetu itafuatilia. Ref A1B2C'},
    {'sid': 'a2', 'at': '2026-10-20T15:10:00Z', 'ch': 'whatsapp', 'from': '+255712000111', 'text': 'FIXED', 'kind': 'fixed', 'lang': 'en', 'id': '105', 't': 'Turbo Spring', 'result': 'fixed', 'reg': True, 'reply': 'RAFIKI: Thank you! Turbo Spring is marked as working again.'},
    {'sid': 'a3', 'at': '2026-10-20T09:00:00Z', 'ch': 'sms', 'from': '+254700000001', 'text': 'win a prize now', 'kind': 'unknown', 'lang': 'en', 'id': '', 't': '', 'result': 'ignored-unregistered', 'reg': False, 'reply': ''},
    {'sid': 'a4', 'at': '2026-10-19T12:00:00Z', 'ch': 'sms', 'from': '+255712000111', 'text': 'tap is leaking', 'kind': 'repair', 'lang': 'en', 'id': '105', 't': 'Turbo Spring', 'result': 'reported-repair', 'reg': True, 'reply': 'RAFIKI: Thank you. Report received: Turbo Spring needs repair. Our team will follow up.'},
]
def state(setup_on):
    return {'ok': True,
        'setup': {'storage': True, 'monday': True,
                  'at': {'on': setup_on, 'replies': setup_on, 'tokenShort': False, 'vars': {'SMS_WEBHOOK_TOKEN': setup_on, 'AT_USERNAME': setup_on, 'AT_API_KEY': setup_on, 'AT_SENDER_ID': False, 'AT_KEYWORD': False}},
                  'twilio': {'on': False, 'vars': {'TWILIO_AUTH_TOKEN': False}}},
        'voice': {'on': setup_on, 'provider': 'openai', 'model': 'whisper-1', 'missing': [] if setup_on else ['OPENAI_API_KEY'], 'note': '', 'thisMonth': 7 if setup_on else 0},
        'urls': {'at': BASE + '/api/sms?token=YOUR_SMS_WEBHOOK_TOKEN', 'twilio': BASE + '/api/sms'},
        'reports': REPORTS if setup_on else [], 'phones': PHONES[:]}
PHONES = [{'phone': '+255712000111', 'id': '105', 't': 'Turbo Spring', 'name': 'Turbo water committee', 'by': 'Reid', 'at': '2026-10-01T00:00:00Z'}]
posts = []; ON = {'v': True}
def sms_admin(route, req):
    if req.method == 'GET': return route.fulfill(json=state(ON['v']))
    b = json.loads(req.post_data or '{}'); posts.append(b)
    if b.get('action') == 'phone-add':
        if b.get('id') == '999': return route.fulfill(status=404, json={'ok': False, 'reason': 'project'})
        ph = re.sub(r'[^\d+]', '', b['phone']); ph = '+254' + ph[1:] if ph.startswith('0') else ph
        row = {'phone': ph, 'id': b['id'], 't': {'104': 'Kapsaret Borehole', '105': 'Turbo Spring'}.get(b['id'], 'Project'), 'name': b.get('name', ''), 'by': 'Reid', 'at': '2026-10-21T08:00:00Z'}
        PHONES[:] = [p for p in PHONES if p['phone'] != ph] + [row]
        return route.fulfill(json={'ok': True, 'phone': row})
    if b.get('action') == 'phone-remove':
        PHONES[:] = [p for p in PHONES if p['phone'] != b['phone']]
        return route.fulfill(json={'ok': True, 'phones': PHONES})
    route.fulfill(json={'ok': False, 'reason': 'bad-request'}, status=400)
def api(route, req):
    if req.method == 'GET':
        if 'activity=' in req.url: return route.fulfill(json={'ok': True, 'activity': [{'at': '2026-10-21T07:58:00Z', 'by': 'SMS from +254•••678', 'a': 'visit', 's': 'notworking', 'id': '104', 't': 'Kapsaret Borehole', 'src': 'sms'}]})
        if 'team=' in req.url: return route.fulfill(json={'ok': True, 'team': []})
        if 'history=' in req.url: return route.fulfill(json={'ok': True, 'notes': [{'id': '777', 'at': '2026-10-21T07:00:00Z', 'by': 'Meril Kiprop', 'files': [], 'text': '🩺 Site visit by Meril (RAFIKI pin app): ❌ Not working. Notes: Handle broken.\n\n🎙️ Voice note (typed out): Pampu imeharibika tangu jana, kamati inaomba fundi aje haraka. (Kiswahili, typed out automatically, may have small mistakes)'}]})
        return route.fulfill(json={'ok': True, 'who': 'Reid Anderson', 'admin': True, 'reviewOpen': 0, 'projects': P, 'repairs': {'104': {'state': 'notworking', 'by': 'SMS from +254•••678', 'at': '2026-10-21T07:58:00Z', 't': 'Kapsaret Borehole', 'src': 'sms'}}, 'visits': {}, 'jobs': [], 'assigned': [], 'confirmed': {}, 'me': {'kind': 'app', 'nick': '', 'phone': '', 'home': 'ke'}})
    route.fulfill(json={'ok': True})
def office(pg):
    pg.evaluate('scrollTo(0, 0)'); pg.wait_for_timeout(200)
    pg.evaluate("document.getElementById('tabOffice').click()"); pg.wait_for_timeout(700)   # the tab bar hides while scrolled
    pg.click('#oTabs [data-o=sms]'); pg.wait_for_timeout(900)
with sync_playwright() as p:
    br = p.chromium.launch(); ctx = br.new_context(viewport={'width': 390, 'height': 844}, device_scale_factor=2, is_mobile=True, has_touch=True, service_workers='block')
    ctx.grant_permissions(['clipboard-read', 'clipboard-write'], origin=BASE)
    pg = ctx.new_page(); errs = []; pg.on('pageerror', lambda e: errs.append(str(e)))
    pg.route('**/api/pin-auth/me', lambda r, q: r.fulfill(json={'available': False})); pg.route(re.compile(r'/api/pin(\?.*)?$'), api); pg.route(re.compile(r'/api/admin(\?.*)?$'), lambda r, q: r.fulfill(json={'ok': True, 'team': [], 'items': []}))
    pg.route(re.compile(r'/api/sms-admin$'), sms_admin)
    pg.route('**/tile.openstreetmap.org/**', lambda r, q: r.abort()); pg.route('**/arcgisonline.com/**', lambda r, q: r.abort()); pg.route('**/google.com/s2/**', lambda r, q: r.abort())
    pg.goto(BASE + '/pin'); pg.wait_for_timeout(800)
    for d in '9999': pg.click('.pk[data-k="%s"]' % d)
    pg.wait_for_timeout(1200); pg.evaluate("localStorage.setItem('rfPinOnb','1')"); pg.goto(BASE + '/pin'); pg.wait_for_timeout(1500)
    office(pg)
    tabs = pg.eval_on_selector_all('#oTabs .otab span', 'e => e.map(x => x.textContent)')
    check('Community' in tabs, 'Community tile in the Office: %s' % tabs)
    check(pg.get_attribute('#oTabs [data-o=sms]', 'aria-pressed') == 'true', 'Community tile selected')
    body = pg.inner_text('#oBody')
    check('Community reports' in body and 'Latest messages' in body, 'heading and the message list')
    check(pg.locator('.vx-msg').count() == 4, '4 messages listed')
    first = pg.inner_text('.vx-msg >> nth=0')
    check('Kapsaret Borehole' in first and 'Not working' in first and 'IMEHARIBIKA 104 pampu' in first and '+254712345678' in first, 'a report: project, result, text, number')
    check('Ref A1B2C' in first, 'the answer sent back is shown')
    check(pg.locator('.vx-msg.vx-bad').count() == 1 and pg.locator('.vx-msg.vx-off').count() == 1 and pg.locator('.vx-msg.vx-ok').count() == 1, 'results coloured: broken / ignored / working')
    check('Ignored: phone not on the list' in pg.inner_text('.vx-msg >> nth=2'), 'ignored message says why')
    check('WhatsApp' in pg.inner_text('.vx-msg >> nth=1') and 'Turbo water committee' in pg.inner_text('.vx-msg >> nth=1'), 'WhatsApp, with the committee name')
    nums = pg.eval_on_selector_all('.vx-nums b', 'e => e.map(x => x.textContent)')
    check(nums == ['3', '4', '1'], 'numbers at the top: %s' % nums)
    # set-up
    st = pg.inner_text('.vx-setup')
    check('SMS (Africa’s Talking)' in st and 'Answers by SMS' in st and 'WhatsApp / SMS (Twilio)' in st, 'set-up rows')
    check(pg.locator('.vx-setup .vx-st.on').count() == 2, 'AT on, replies on, Twilio off')
    check(pg.locator('.vx-vars code.set', has_text='SMS_WEBHOOK_TOKEN').count() == 1 and pg.locator('.vx-vars code:not(.set)', has_text='TWILIO_AUTH_TOKEN').count() == 1, 'variable names: set and missing')
    check('/api/sms?token=YOUR_SMS_WEBHOOK_TOKEN' in st and BASE + '/api/sms' in st, 'webhook URLs to paste')
    pg.click('.vx-setup [data-vxcopy] >> nth=0'); pg.wait_for_timeout(200)
    check(pg.evaluate('navigator.clipboard.readText()').endswith('/api/sms?token=YOUR_SMS_WEBHOOK_TOKEN'), 'Copy puts the URL on the clipboard')
    check('Copied' in pg.inner_text('.vx-setup [data-vxcopy] >> nth=0'), 'Copy says Copied')
    pg.screenshot(path=os.path.join(OUT, '01-community-light.png'), full_page=True)
    # voice notes typed out
    check('Voice notes typed out' in pg.inner_text('.vx-voice') and '7 this month' in pg.inner_text('.vx-voice'), 'voice-note status: on, 7 this month')
    # phones: add from a message
    check(pg.locator('.vx-ph li').count() == 1, 'one committee phone')
    pg.click('.vx-msg >> nth=0 >> [data-vxreg]'); pg.wait_for_timeout(300)
    check(pg.input_value('#vxPhone') == '+254712345678' and pg.input_value('#vxProj') == '104', 'Add this phone fills the form')
    pg.fill('#vxName', 'Kapsaret committee, Mary'); pg.click('#vxAdd'); pg.wait_for_timeout(500)
    check(posts[-1] == {'action': 'phone-add', 'phone': '+254712345678', 'id': '104', 'name': 'Kapsaret committee, Mary', 'cc': 'ke'}, 'phone-add sent: %s' % posts[-1:])
    check(pg.locator('.vx-ph li').count() == 2 and 'Added' in pg.inner_text('#vxMsg'), 'listed, Added ✓')
    check(pg.locator('.vx-msg >> nth=0 >> [data-vxreg]').count() == 0, 'no "add" button once the phone is on the list')
    pg.fill('#vxPhone', '0712 999 888'); pg.fill('#vxProj', '999'); pg.click('#vxAdd'); pg.wait_for_timeout(400)
    check('No project with that number' in pg.inner_text('#vxMsg'), 'unknown project is refused')
    pg.locator('.vx-phones').scroll_into_view_if_needed(); pg.wait_for_timeout(200); pg.screenshot(path=os.path.join(OUT, '02-committee-phones.png'))
    pg.once('dialog', lambda d: d.accept())
    pg.click('[data-vxdel="+255712000111"]'); pg.wait_for_timeout(500)
    check(posts[-1] == {'action': 'phone-remove', 'phone': '+255712000111'} and pg.locator('.vx-ph li').count() == 1, 'phone removed')
    # open the project from a message
    pg.click('.vx-msg >> nth=0 >> [data-vxopen]'); pg.wait_for_timeout(700)
    check(pg.evaluate("location.hash").startswith('#p/104') or 'Kapsaret' in pg.inner_text('body'), 'project opens from the message')
    pg.goto(BASE + '/pin#p/104/hist'); pg.wait_for_timeout(1500)
    hl = pg.inner_text('#hList') if pg.locator('#hList').count() else ''
    check('Voice note (typed out): Pampu imeharibika' in hl, 'History shows the typed-out voice note')
    pg.locator('#hList').scroll_into_view_if_needed(); pg.screenshot(path=os.path.join(OUT, '03-history-voice-typed.png'))
    # dark, sun, Kiswahili
    pg.goto(BASE + '/pin'); pg.wait_for_timeout(1200)
    pg.evaluate("localStorage.setItem('rfPinTheme','dark')"); pg.reload(); pg.wait_for_timeout(1200); office(pg)
    check(pg.evaluate("document.documentElement.dataset.theme") == 'dark' and pg.locator('.vx-msg').count() == 4, 'dark')
    pg.screenshot(path=os.path.join(OUT, '04-community-dark.png'), full_page=True)
    pg.evaluate("localStorage.setItem('rfPinTheme','sun')"); pg.reload(); pg.wait_for_timeout(1200); office(pg)
    pg.screenshot(path=os.path.join(OUT, '05-community-sun.png'))
    pg.evaluate("localStorage.setItem('rfPinTheme',''); localStorage.setItem('rfPinLang','sw')"); pg.reload(); pg.wait_for_timeout(1200); office(pg)
    body = pg.inner_text('#oBody')
    check('Ripoti za jamii' in body and 'Ujumbe wa karibuni' in body and 'Haifanyi kazi' in body and 'Simu za kamati' in body, 'Kiswahili')
    check(pg.inner_text('#oTabs [data-o=sms] span') == 'Jamii', 'tile in Kiswahili')
    pg.screenshot(path=os.path.join(OUT, '06-community-sw.png'), full_page=True)
    pg.evaluate("localStorage.setItem('rfPinLang','')")
    # nothing set up yet
    ON['v'] = False; pg.reload(); pg.wait_for_timeout(1200); office(pg)
    check(pg.locator('.vx-setup .vx-st.on').count() == 0 and pg.locator('.vx-vars code.set').count() == 0, 'all off when no variables are set')
    check('OPENAI_API_KEY' in pg.inner_text('.vx-voice') and 'Off' in pg.inner_text('.vx-voice'), 'voice: Off, says to add OPENAI_API_KEY')
    check(pg.locator('.vx-list').count() == 0 and 'No messages yet' in pg.inner_text('#oBody'), 'empty list message')
    pg.screenshot(path=os.path.join(OUT, '07-not-set-up.png'), full_page=True)
    ON['v'] = True
    # wide screen
    pg.set_viewport_size({'width': 1280, 'height': 900}); pg.reload(); pg.wait_for_timeout(1400); office(pg)
    sw = pg.evaluate('document.documentElement.scrollWidth <= innerWidth + 1')
    check(sw, 'no sideways scroll on a wide screen')
    pg.screenshot(path=os.path.join(OUT, '08-community-wide.png'))
    pg.set_viewport_size({'width': 390, 'height': 844}); pg.wait_for_timeout(300)
    check(pg.evaluate('document.documentElement.scrollWidth <= innerWidth + 1'), 'no sideways scroll on a phone')
    check(not errs, 'no page errors %s' % errs[:2]); br.close()
print('PROBLEMS', problems)
