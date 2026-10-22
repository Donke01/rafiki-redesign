# Office › Community: SMS / WhatsApp reports from water committees (/api/sms-admin, mocked here).
# Head card with 7-day numbers, filter chips with counts, a card per message (tap: a sheet with what happened and
# Open project / Call / WhatsApp / Copy / Add as committee phone), and the set-up, committee phones and voice-note
# rows folded away until tapped. Every result kind, the real "Broken 104" from a WhatsApp id, light / dark / sun,
# Kiswahili, a 390px phone and a 1280px desktop; screenshots to qa/pin/sms/.
import json, os, re
from datetime import datetime, timedelta, timezone
from playwright.sync_api import sync_playwright
HERE = os.path.dirname(os.path.abspath(__file__)); BASE = os.environ.get('BASE', 'http://localhost:8813'); OUT = os.path.join(HERE, 'sms'); os.makedirs(OUT, exist_ok=True)
for f in os.listdir(OUT):
    if f.endswith('.png') and not f.startswith('android-'): os.remove(os.path.join(OUT, f))   # the Android QA keeps its own here
P = [{'id': '104', 'title': 'Kapsaret Borehole', 'country': 'Kenya', 'ck': 'ke', 'area': 'Eldoret', 'type': 'Borehole well', 'status': 'Completed', 'gps': [0.47, 35.25]},
     {'id': '105', 'title': 'Turbo Spring', 'country': 'Tanzania', 'ck': 'tz', 'area': 'Arusha', 'type': 'Water collection', 'status': 'Completed', 'gps': [-3.37, 36.68]}]
problems = []
def check(c, w): print(('ok   ' if c else 'FAIL ') + w); (None if c else problems.append(w))
NOW = datetime.now(timezone.utc)
def ago(h): return (NOW - timedelta(hours=h)).strftime('%Y-%m-%dT%H:%M:%SZ')
def rep(sid, h, ch, frm, text, result, id='', t='', reg=False, reply=''):
    return {'sid': sid, 'at': ago(h), 'ch': ch, 'from': frm, 'text': text, 'kind': '', 'lang': 'en', 'id': id, 't': t, 'result': result, 'reg': reg, 'reply': reply}
WA_ID = 'wa:KE.1461161065410436'
# newest first; every result kind. Last 7 days: a1..a9 (9), not working: a1 + a4 (2), ignored: a2, a5, a8 (3)
REPORTS = [
    rep('a1', 2, 'sms', '+254712345678', 'IMEHARIBIKA 104 pampu', 'reported-broken', '104', 'Kapsaret Borehole', False, 'RAFIKI: Asante. Ripoti ya Kapsaret Borehole (haifanyi kazi) imepokelewa. Timu yetu itafuatilia. Ref A1B2C'),
    rep('a2', 5, 'whatsapp', WA_ID, 'Broken 104', 'ignored-no-project', '104', ''),
    rep('a3', 20, 'whatsapp', '+255712000111', 'FIXED', 'fixed', '105', 'Turbo Spring', True, 'RAFIKI: Thank you! Turbo Spring is marked as working again.'),
    rep('a4', 30, 'sms', '+255712000111', 'tap is leaking', 'reported-repair', '105', 'Turbo Spring', True, 'RAFIKI: Thank you. Report received: Turbo Spring needs repair. Our team will follow up.'),
    rep('a5', 50, 'sms', '+254700000001', 'win a prize now', 'ignored-unregistered'),
    rep('a6', 75, 'sms', '+254711222333', 'BROKEN 104 handle', 'already-open', '104', 'Kapsaret Borehole', False, 'RAFIKI: Thank you. A repair for Kapsaret Borehole is already open.'),
    rep('a7', 98, 'sms', '+254722000444', 'HELP', 'help', reply='RAFIKI: Text BROKEN and the project number, e.g. BROKEN 104.'),
    rep('a8', 120, 'sms', '+254700000001', 'win win win', 'rate-limited'),
    rep('a9', 150, 'whatsapp', '+254733111222', 'imerekebishwa 104', 'fixed-unconfirmed', '104', 'Kapsaret Borehole', False, 'RAFIKI: Asante. Tutahakikisha.'),
    rep('a10', 200, 'sms', '+255712000111', 'WORKING 105', 'noted-working', '105', 'Turbo Spring', True, 'RAFIKI: Thank you, noted.'),
    rep('a11', 220, 'sms', '+254722000444', 'hello?', 'unknown', reply='RAFIKI: Text BROKEN and the project number, e.g. BROKEN 104.'),
    rep('a12', 240, 'whatsapp', '+254733111222', 'BROKEN 99999', 'no-project', '99999', '', False, 'RAFIKI: There is no project 99999.'),
    rep('a13', 290, 'sms', '+254700000001', 'win', 'daily-limit'),
    rep('a14', 340, 'sms', 'ABCDEF', 'BROKEN', 'bad-number'),
    rep('a15', 480, 'sms', '+254712345678', 'REPAIR 104', 'error', '104', 'Kapsaret Borehole'),
]
PHONES = [{'phone': '+255712000111', 'id': '105', 't': 'Turbo Spring', 'name': 'Turbo water committee', 'by': 'Reid', 'at': '2026-10-01T00:00:00Z'}]
MODE = {'v': 'full'}   # full · none (nothing set up) · one (a single report) · many (45 reports)
def reports():
    m = MODE['v']
    if m == 'none': return []
    if m == 'one': return REPORTS[:1]
    if m == 'many': return [dict(REPORTS[i % 15], sid='m%d' % i) for i in range(45)]
    return REPORTS
def state():
    on = MODE['v'] != 'none'
    return {'ok': True,
        'setup': {'storage': True, 'monday': True,
                  'at': {'on': on, 'replies': on, 'tokenShort': False, 'vars': {'SMS_WEBHOOK_TOKEN': on, 'AT_USERNAME': on, 'AT_API_KEY': on, 'AT_SENDER_ID': False, 'AT_KEYWORD': False}},
                  'twilio': {'on': False, 'vars': {'TWILIO_AUTH_TOKEN': False}}},
        'voice': {'on': on, 'provider': 'openai', 'model': 'whisper-1', 'missing': [] if on else ['OPENAI_API_KEY'], 'note': '', 'thisMonth': 7 if on else 0},
        'urls': {'at': BASE + '/api/sms?token=YOUR_SMS_WEBHOOK_TOKEN', 'twilio': BASE + '/api/sms'},
        'reports': reports(), 'phones': PHONES[:]}
posts = []; gets = [0]
def sms_admin(route, req):
    if req.method == 'GET': gets[0] += 1; return route.fulfill(json=state())
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
        if 'activity=' in req.url: return route.fulfill(json={'ok': True, 'activity': [{'at': ago(2), 'by': 'SMS from +254•••678', 'a': 'visit', 's': 'notworking', 'id': '104', 't': 'Kapsaret Borehole', 'src': 'sms'}]})
        if 'team=' in req.url: return route.fulfill(json={'ok': True, 'team': []})
        if 'history=' in req.url: return route.fulfill(json={'ok': True, 'notes': [{'id': '777', 'at': ago(3), 'by': 'Meril Kiprop', 'files': [], 'text': '🩺 Site visit by Meril (RAFIKI pin app): ❌ Not working. Notes: Handle broken.\n\n🎙️ Voice note (typed out): Pampu imeharibika tangu jana, kamati inaomba fundi aje haraka. (Kiswahili, typed out automatically, may have small mistakes)'}]})
        return route.fulfill(json={'ok': True, 'who': 'Reid Anderson', 'admin': True, 'reviewOpen': 0, 'projects': P, 'repairs': {'104': {'state': 'notworking', 'by': 'SMS from +254•••678', 'at': ago(2), 't': 'Kapsaret Borehole', 'src': 'sms'}}, 'visits': {}, 'jobs': [], 'assigned': [], 'confirmed': {}, 'me': {'kind': 'app', 'nick': '', 'phone': '', 'home': 'ke'}})
    route.fulfill(json={'ok': True})
def office(pg):
    pg.evaluate('scrollTo(0, 0)'); pg.wait_for_timeout(200)
    pg.evaluate("document.getElementById('tabOffice').click()"); pg.wait_for_timeout(700)   # the tab bar hides while scrolled
    pg.click('#oTabs [data-o=sms]'); pg.wait_for_timeout(900)
T = lambda k: '[data-testid="%s"]' % k
def card(pg, n): return pg.locator(T('sms_msg')).nth(n)
def sheet_open(pg, n):
    card(pg, n).scroll_into_view_if_needed(); card(pg, n).click(); pg.wait_for_timeout(600)
def sheet_close(pg):
    pg.keyboard.press('Escape'); pg.wait_for_timeout(400)
def shot(pg, name, full=False): pg.screenshot(path=os.path.join(OUT, name), full_page=full)
def drop_h(pg, k): return pg.evaluate("document.getElementById('vxSec_%s').getBoundingClientRect().height" % k)
def theme(pg, t, lang=''):
    pg.evaluate("localStorage.setItem('rfPinTheme','%s'); localStorage.setItem('rfPinLang','%s')" % (t, lang)); pg.reload(); pg.wait_for_timeout(1200); office(pg)
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
    # head card
    hd = pg.inner_text(T('sms_head'))
    check('Community reports' in hd and 'Water committees text or WhatsApp when a water point breaks.' in hd, 'head card: title and one line')
    nums = pg.eval_on_selector_all('.vx-nums b', 'e => e.map(x => x.textContent)')
    labs = pg.eval_on_selector_all('.vx-nums small', 'e => e.map(x => x.textContent)')
    check(nums == ['9', '2', '3'] and labs == ['Last 7 days', 'Not working', 'Ignored'], 'numbers: last 7 days / not working / ignored = %s %s' % (nums, labs))
    # filter chips and counts
    chips = {k: pg.inner_text(T('sms_filter_' + k)).replace('\n', ' ') for k in ['all', 'broken', 'working', 'other', 'ignored']}
    want = {'all': 'All 15', 'broken': 'Broken 3', 'working': 'Working 3', 'other': 'Other 5', 'ignored': 'Ignored 4'}
    check(all(re.sub(r'\s+', ' ', chips[k]).strip() == want[k] for k in want), 'chips with counts: %s' % chips)
    check(pg.get_attribute(T('sms_filter_all'), 'aria-pressed') == 'true', '"All" selected at first')
    check(pg.locator(T('sms_msg')).count() == 15, '15 message cards')
    for k, n in [('broken', 3), ('working', 3), ('other', 5), ('ignored', 4)]:
        pg.click(T('sms_filter_' + k)); pg.wait_for_timeout(250)
        check(pg.locator(T('sms_msg')).count() == n and pg.get_attribute(T('sms_filter_' + k), 'aria-pressed') == 'true', 'filter %s: %d cards' % (k, n))
    check(pg.locator('.vx-card.vx-off').count() == 4, 'ignored messages are dimmed')
    check(pg.evaluate("(()=>{const c=document.querySelector('.vx-chips').getBoundingClientRect(),p=document.querySelector('[data-testid=sms_filter_ignored]').getBoundingClientRect();return p.left>=c.left&&p.right<=c.right})()"), 'the chosen chip scrolls into sight')
    op = pg.evaluate("getComputedStyle(document.querySelector('.vx-card.vx-off > .vx-top')).opacity")
    check(op == '0.7', 'ignored at 70%% emphasis (%s)' % op)
    shot(pg, '10-filter-ignored.png')
    pg.click(T('sms_filter_all')); pg.wait_for_timeout(250)
    # cards
    c0 = pg.inner_text(T('sms_msg') + ' >> nth=0')
    check('Kapsaret Borehole' in c0 and 'Not working' in c0 and 'IMEHARIBIKA 104 pampu' in c0, 'card: project, status, message')
    check('+254 712 ••• 678' in c0 and '+254712345678' not in c0 and 'SMS' in c0, 'card: number shown as +254 712 ••• 678, channel')
    check('↩ Replied' in c0 and 'Ref A1B2C' not in c0, 'card: "↩ Replied", not the reply itself')
    c1 = pg.inner_text(T('sms_msg') + ' >> nth=1')
    check('WhatsApp user ••• 436' in c1 and '1461161065410436' not in c1, 'WhatsApp id shown as "WhatsApp user ••• 436"')
    check('Project 104' in c1 and 'Broken 104' in c1 and 'Ignored: no such project' in c1, 'the real case: "Broken 104", Project 104, ignored')
    check(pg.locator(T('sms_msg') + ' >> nth=1 >> .vx-av.wa').count() == 1 and pg.locator(T('sms_msg') + ' >> nth=0 >> .vx-av.sms').count() == 1, 'WhatsApp and SMS avatars')
    check('Turbo water committee' in pg.inner_text(T('sms_msg') + ' >> nth=2'), 'committee name in the footer')
    check('No project number' in pg.inner_text(T('sms_msg') + ' >> nth=4'), 'no project: "No project number"')
    old = pg.inner_text(T('sms_msg') + ' >> nth=7 >> .vx-when').strip()
    check(re.match(r'^\d{1,2} [A-Z][a-z]{2}$', old) is not None, 'older message dated like "17 Oct": %r' % old)
    check(pg.locator(T('sms_msg') + ' >> nth=0 >> .vx-when svg').count() == 1, 'chevron on the card')
    # settings rows start collapsed
    for k in ['setup', 'phones', 'voice']:
        check(pg.get_attribute(T('sms_%s_toggle' % k), 'aria-expanded') == 'false' and drop_h(pg, k) < 1, '%s starts collapsed' % k)
    st = pg.inner_text(T('sms_setup_toggle')).replace('\n', ' ')
    check('Set-up' in st and 'WhatsApp off' in st and 'SMS on' in st, 'Set-up summary: %s' % st)
    check('1 phone' in pg.inner_text(T('sms_phones_toggle')) and 'On' in pg.inner_text(T('sms_voice_toggle')), 'phones and voice summaries')
    shot(pg, '01-list-light.png'); shot(pg, '01b-list-light-full.png', True)
    # expand
    pg.locator(T('sms_setup_toggle')).scroll_into_view_if_needed(); pg.click(T('sms_setup_toggle')); pg.wait_for_timeout(450)
    check(pg.get_attribute(T('sms_setup_toggle'), 'aria-expanded') == 'true' and drop_h(pg, 'setup') > 150, 'Set-up opens on tap (%d px)' % drop_h(pg, 'setup'))
    stx = pg.inner_text(T('sms_setup'))
    check('SMS (Africa’s Talking)' in stx and 'Answers by SMS' in stx and 'WhatsApp / SMS (Twilio)' in stx and pg.locator('.vx-setup .vx-st.on').count() == 2, 'set-up rows: AT on, replies on, Twilio off')
    check(pg.locator('.vx-vars code.set', has_text='SMS_WEBHOOK_TOKEN').count() == 1 and pg.locator('.vx-vars code:not(.set)', has_text='TWILIO_AUTH_TOKEN').count() == 1, 'variable names: set and missing')
    check('/api/sms?token=YOUR_SMS_WEBHOOK_TOKEN' in stx and BASE + '/api/sms' in stx, 'webhook URLs to paste')
    pg.click('.vx-setup [data-vxcopy] >> nth=0'); pg.wait_for_timeout(200)
    check(pg.evaluate('navigator.clipboard.readText()').endswith('/api/sms?token=YOUR_SMS_WEBHOOK_TOKEN') and 'Copied' in pg.inner_text('.vx-setup [data-vxcopy] >> nth=0'), 'Copy puts the URL on the clipboard')
    pg.click(T('sms_phones_toggle')); pg.wait_for_timeout(450)
    check(pg.get_attribute(T('sms_phones_toggle'), 'aria-expanded') == 'true' and drop_h(pg, 'phones') > 100 and pg.locator('.vx-ph li').count() == 1, 'Committee phones opens on tap, 1 phone')
    pg.click(T('sms_voice_toggle')); pg.wait_for_timeout(450)
    check(pg.get_attribute(T('sms_voice_toggle'), 'aria-expanded') == 'true' and '7 this month' in pg.inner_text(T('sms_voice')), 'Voice opens on tap: 7 this month')
    pg.locator('.vx-sets').scroll_into_view_if_needed(); pg.wait_for_timeout(200); shot(pg, '03-settings-open-light.png')
    pg.locator(T('sms_setup_toggle')).screenshot(path=os.path.join(OUT, '03b-setup-row.png'))
    pg.click(T('sms_voice_toggle')); pg.wait_for_timeout(450)
    check(pg.get_attribute(T('sms_voice_toggle'), 'aria-expanded') == 'false' and drop_h(pg, 'voice') < 1, 'Voice folds away again')
    # phones form (inside Committee phones)
    pg.fill('#vxPhone', '0712 999 888'); pg.fill('#vxProj', '999'); pg.click('#vxAdd'); pg.wait_for_timeout(400)
    check('No project with that number' in pg.inner_text('#vxMsg'), 'unknown project is refused')
    pg.fill('#vxPhone', '0712 999 888'); pg.fill('#vxProj', '105'); pg.fill('#vxName', 'Mary'); pg.click('#vxAdd'); pg.wait_for_timeout(500)
    check(posts[-1] == {'action': 'phone-add', 'phone': '0712 999 888', 'id': '105', 'name': 'Mary', 'cc': 'tz'} and pg.locator('.vx-ph li').count() == 2 and 'Added' in pg.inner_text('#vxMsg'), 'phone added from the form, stays open')
    pg.once('dialog', lambda d: d.accept())
    pg.click('[data-vxdel="+254712999888"]'); pg.wait_for_timeout(500)
    check(posts[-1] == {'action': 'phone-remove', 'phone': '+254712999888'} and pg.locator('.vx-ph li').count() == 1, 'phone removed')
    # the sheet: a report from an unlisted phone
    pg.evaluate('scrollTo(0, 0)'); sheet_open(pg, 0)
    sh = pg.locator(T('sms_sheet'))
    check(sh.count() == 1 and sh.is_visible(), 'tapping a card opens the sheet')
    stx = sh.inner_text()
    check('Not working' in stx and 'Kapsaret Borehole' in stx and 'IMEHARIBIKA 104 pampu' in stx, 'sheet: status, project, their message')
    check('RAFIKI REPLIED' in stx.upper() and 'Ref A1B2C' in stx, 'sheet: RAFIKI’s reply in full')
    check('A repair was opened and the admins were e-mailed.' in stx, 'sheet: what happened')
    check('+254 712 345 678' in stx and 'Received' in stx and 'Channel' in stx, 'sheet: facts')
    check(all(sh.locator(T(k)).count() == 1 for k in ['sms_open', 'sms_call', 'sms_wa', 'sms_add']), 'actions: Open project, Call, WhatsApp, Add as committee phone')
    check(sh.locator(T('sms_call')).get_attribute('href') == 'tel:+254712345678' and sh.locator(T('sms_wa')).get_attribute('href') == 'https://wa.me/254712345678', 'Call is tel:, WhatsApp is wa.me')
    check(min(b['height'] for b in [sh.locator('.vx-acts .btn').nth(i).bounding_box() for i in range(sh.locator('.vx-acts .btn').count())]) >= 48, 'action buttons 48px or taller')
    shot(pg, '02-sheet-light.png')
    pg.locator('.tsh-c').evaluate('e => e.scrollTo(0, e.scrollHeight)'); pg.wait_for_timeout(200); shot(pg, '02b-sheet-light-actions.png')
    sh.locator('[data-a=copy]').click(); pg.wait_for_timeout(200)
    check(pg.evaluate('navigator.clipboard.readText()') == '+254712345678' and 'Copied' in sh.locator('[data-a=copy]').inner_text(), 'Copy number')
    g0 = gets[0]; sh.locator(T('sms_add')).click(); pg.wait_for_timeout(700)
    check(posts[-1] == {'action': 'phone-add', 'phone': '+254712345678', 'id': '104', 'name': '', 'cc': 'ke'}, 'Add posts phone-add: %s' % posts[-1:])
    check('Added ✓' in sh.locator(T('sms_add')).inner_text() and gets[0] > g0, 'shows "Added ✓" and refreshes')
    shot(pg, '02c-sheet-added.png')
    sheet_close(pg)
    check(pg.locator(T('sms_sheet')).count() == 0, 'Esc closes the sheet')
    check('2 phones' in pg.inner_text(T('sms_phones_toggle')), 'committee phones now 2')
    sheet_open(pg, 0); check(pg.locator(T('sms_add')).count() == 0, 'no "Add" once the phone is listed'); sheet_close(pg)
    # the WhatsApp id: nothing to call
    sheet_open(pg, 1); sh = pg.locator(T('sms_sheet')); stx = sh.inner_text()
    check('WhatsApp user ••• 436' in stx and 'Broken 104' in stx and 'Project 104' in stx, 'WhatsApp id sheet: who, message, Project 104')
    check(all(sh.locator(T(k)).count() == 0 for k in ['sms_call', 'sms_wa', 'sms_add', 'sms_open']) and sh.locator('[data-a=copy]').count() == 0, 'WhatsApp id: no Call / WhatsApp / Copy / Add / Open')
    check('There is no project with that number and the phone isn’t registered, so nothing was done.' in stx, 'ignored-no-project explained')
    shot(pg, '04-sheet-wa-id.png'); sheet_close(pg)
    # listed phone: no Add
    sheet_open(pg, 2); sh = pg.locator(T('sms_sheet'))
    check(sh.locator(T('sms_open')).count() == 1 and sh.locator(T('sms_call')).count() == 1 and sh.locator(T('sms_add')).count() == 0 and 'The open repair was closed.' in sh.inner_text(), 'listed phone: Open / Call, no Add; fixed explained')
    sheet_close(pg)
    # every result explained
    whys = {3: 'A repair was opened.', 5: 'A repair was already open, so a note was added.', 6: 'They were sent the instructions.', 7: 'Too many messages from this phone today', 8: 'the repair stays open until the team checks', 9: 'Noted on monday.com.', 10: 'The message wasn’t understood', 11: 'There is no project with that number; they were told.', 12: 'Too many messages', 13: 'couldn’t be read', 14: 'monday.com didn’t answer; try again later.', 4: 'No project number, and the phone isn’t on the committee list'}
    bad = []
    for n, s in sorted(whys.items()):
        sheet_open(pg, n)
        if s not in pg.inner_text(T('sms_sheet')): bad.append(n)
        sheet_close(pg)
    check(not bad, 'every result kind explained in the sheet %s' % bad)
    # open the project from the sheet
    pg.evaluate('scrollTo(0, 0)'); sheet_open(pg, 0); pg.click(T('sms_open')); pg.wait_for_timeout(900)
    check(pg.evaluate("location.hash").startswith('#p/104'), 'Open project opens it')
    pg.goto(BASE + '/pin#p/104/hist'); pg.wait_for_timeout(1500)
    hl = pg.inner_text('#hList') if pg.locator('#hList').count() else ''
    check('Voice note (typed out): Pampu imeharibika' in hl, 'History shows the typed-out voice note')
    # empty filter, show more
    MODE['v'] = 'one'; pg.goto(BASE + '/pin'); pg.wait_for_timeout(1200); office(pg)
    pg.click(T('sms_filter_working')); pg.wait_for_timeout(250)
    check(pg.locator(T('sms_msg')).count() == 0 and 'No messages here. Try another filter.' in pg.inner_text('#oBody'), 'empty filter: friendly message')
    shot(pg, '11-empty-filter.png'); pg.click(T('sms_filter_all')); pg.wait_for_timeout(200)
    MODE['v'] = 'many'; pg.reload(); pg.wait_for_timeout(1200); office(pg)
    check(pg.locator(T('sms_msg')).count() == 40 and 'Show 5 more' in pg.inner_text('.vx-more'), '40 shown, "Show 5 more"')
    pg.click('.vx-more'); pg.wait_for_timeout(300)
    check(pg.locator(T('sms_msg')).count() == 45 and pg.locator('.vx-more').count() == 0, 'Show more shows the rest')
    MODE['v'] = 'full'
    # dark, sun
    theme(pg, 'dark')
    check(pg.evaluate("document.documentElement.dataset.theme") == 'dark' and pg.locator(T('sms_msg')).count() == 15, 'dark')
    shot(pg, '05-list-dark.png')
    sheet_open(pg, 0); shot(pg, '06-sheet-dark.png'); sheet_close(pg)
    pg.click(T('sms_setup_toggle')); pg.wait_for_timeout(450); pg.locator('.vx-sets').scroll_into_view_if_needed(); shot(pg, '07-settings-dark.png')
    theme(pg, 'sun'); shot(pg, '08-list-sun.png')
    sheet_open(pg, 0); shot(pg, '08b-sheet-sun.png'); sheet_close(pg)
    # Kiswahili
    theme(pg, '', 'sw')
    body = pg.inner_text('#oBody')
    check('Ripoti za jamii' in body and 'Siku 7 zilizopita' in body and 'Haifanyi kazi' in body and 'Mipangilio' in body and 'Simu za kamati' in body, 'Kiswahili: head and settings')
    sc = {k: pg.inner_text(T('sms_filter_' + k)) for k in ['all', 'broken', 'working', 'other', 'ignored']}
    check('Zote' in sc['all'] and 'Zimeharibika' in sc['broken'] and 'Zinafanya kazi' in sc['working'] and 'Nyingine' in sc['other'] and 'Zimepuuzwa' in sc['ignored'], 'Kiswahili chips: %s' % sc)
    check('Mtumiaji wa WhatsApp ••• 436' in pg.inner_text(T('sms_msg') + ' >> nth=1') and 'Mradi 104' in pg.inner_text(T('sms_msg') + ' >> nth=1') and '↩ Imejibiwa' in pg.inner_text(T('sms_msg') + ' >> nth=0'), 'Kiswahili cards')
    check(pg.inner_text('#oTabs [data-o=sms] span') == 'Jamii', 'tile in Kiswahili')
    shot(pg, '09-list-sw.png'); pg.locator('.vx-sets').screenshot(path=os.path.join(OUT, '09d-settings-rows-sw.png'))
    sheet_open(pg, 0); stx = pg.inner_text(T('sms_sheet'))
    check('UJUMBE WAO' in stx.upper() and 'Fungua mradi' in stx and 'Piga simu' in stx and 'Ukarabati ulifunguliwa' in stx and 'Ilipokelewa' in stx, 'Kiswahili sheet')
    shot(pg, '09b-sheet-sw.png'); sheet_close(pg)
    pg.click(T('sms_setup_toggle')); pg.click(T('sms_phones_toggle')); pg.wait_for_timeout(450); pg.locator('.vx-sets').scroll_into_view_if_needed(); shot(pg, '09c-settings-sw.png')
    pg.evaluate("localStorage.setItem('rfPinLang','')")
    # nothing set up yet
    MODE['v'] = 'none'; pg.reload(); pg.wait_for_timeout(1200); office(pg)
    pg.click(T('sms_setup_toggle')); pg.click(T('sms_voice_toggle')); pg.wait_for_timeout(450)
    check(pg.locator('.vx-setup .vx-st.on').count() == 0 and pg.locator('.vx-vars code.set').count() == 0, 'all off when no variables are set')
    check('OPENAI_API_KEY' in pg.inner_text(T('sms_voice')) and 'Off' in pg.inner_text(T('sms_voice_toggle')), 'voice: Off, says to add OPENAI_API_KEY')
    check(pg.locator(T('sms_msg')).count() == 0 and pg.locator('.vx-chips').count() == 0 and 'No messages yet' in pg.inner_text('#oBody'), 'empty list message, no chips')
    shot(pg, '12-not-set-up.png', True)
    MODE['v'] = 'full'
    # reduced motion
    pg.emulate_media(reduced_motion='reduce'); pg.reload(); pg.wait_for_timeout(1200); office(pg)
    check(pg.evaluate("getComputedStyle(document.querySelector('.vx-drop')).transitionDuration") == '0s', 'reduced motion: no fold animation')
    pg.emulate_media(reduced_motion='no-preference')
    check(pg.evaluate('document.documentElement.scrollWidth <= innerWidth + 1'), 'no sideways scroll on a phone')
    # desktop
    pg.set_viewport_size({'width': 1280, 'height': 900}); pg.reload(); pg.wait_for_timeout(1400); office(pg)
    check(pg.evaluate('document.documentElement.scrollWidth <= innerWidth + 1'), 'no sideways scroll on a wide screen')
    shot(pg, '13-desktop-list.png')
    pg.click(T('sms_setup_toggle')); pg.click(T('sms_phones_toggle')); pg.wait_for_timeout(450); shot(pg, '14-desktop-settings.png')
    sheet_open(pg, 0); shot(pg, '15-desktop-sheet.png'); sheet_close(pg)
    theme(pg, 'dark'); shot(pg, '16-desktop-dark.png')
    pg.evaluate("localStorage.setItem('rfPinTheme','')")
    check(not errs, 'no page errors %s' % errs[:2]); br.close()
print('PROBLEMS', problems)
