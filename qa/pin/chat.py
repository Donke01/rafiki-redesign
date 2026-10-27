# Chat in the web app (chatw): walks the chat list, one chat, sending (online and offline), long-press actions,
# media, voice, groups, the entry points (bottom bar, contact sheet "Message", project page "Chat") and Back,
# against a mocked /api/chat/* with a fake WebSocket (qa/pin/chat_mock.py). Prints PASS / FAIL per sequence.
#   BASE=http://localhost:8882 python3 qa/pin/chat.py [en|sw|both]        screenshots go to $OUT (default qa/pin/chat)
import os, re, sys, json, datetime
from playwright.sync_api import sync_playwright
HERE = os.path.dirname(os.path.abspath(__file__)); sys.path.insert(0, HERE)
from chat_mock import ChatMock, png
BASE = os.environ.get('BASE', 'http://localhost:8882')
OUT = os.environ.get('OUT', os.path.join(HERE, 'chat')); os.makedirs(OUT, exist_ok=True)
TILE = open(os.path.join(HERE, 'tile.png'), 'rb').read()
LANGS = {'en': ['en'], 'sw': ['sw'], 'both': ['en', 'sw']}[sys.argv[1] if len(sys.argv) > 1 else 'both']
ONLY = os.environ.get('ONLY', '')

now = datetime.datetime.now(datetime.timezone.utc)
iso = lambda h: (now - datetime.timedelta(hours=h)).strftime('%Y-%m-%dT%H:%M:%SZ')
def pj(i, t, area, ll, ty='Borehole well', ck='ke', c='Kenya'):
    d = {'id': i, 'title': t, 'country': c, 'ck': ck, 'area': area, 'type': ty, 'status': 'Completed'}
    if ll: d['gps'] = ll
    return d
P = [dict(pj('101', 'Kapsaret Borehole', 'Kapsaret, Eldoret', [0.47, 35.25]), photo='ph101'), pj('102', 'Chepkongi Dispensary', 'Eldoret', [0.5143, 35.2698], 'Health'),
     pj('103', 'Turbo Spring', 'Turbo', [0.63, 35.05], 'Water collection'), pj('104', 'Nzoia Bridge Primary School', 'Bungoma', [0.58, 34.62], 'School')]
REP = {'101': {'state': 'notworking', 'by': 'Bertin Mwangi', 'at': iso(30), 't': 'Kapsaret Borehole', 'urgency': 'urgent', 'step': 'reported'}}
TEAM = [{'name': 'Reid Anderson', 'admin': True, 'source': 'cloudflare'}, {'name': 'Meril Kiprop', 'source': 'app', 'home': 'ke'}, {'name': 'Grace Achieng', 'source': 'app', 'home': 'ke'},
        {'name': 'Bertin Mwangi', 'source': 'app', 'home': 'ke'}, {'name': 'Jessica Anderson', 'admin': True, 'source': 'app'}]
def snap():
    return {'ok': True, 'who': 'Reid Anderson', 'admin': True, 'reviewOpen': 0, 'projects': P, 'repairs': REP, 'visits': {}, 'tests': {}, 'jobs': [], 'assigned': [],
            'confirmed': {}, 'me': {'kind': 'app', 'nick': '', 'phone': '', 'home': 'ke'}, 'approveAbove': 50000}
def install(pg):
    def pin_api(route, req):
        u = req.url
        if req.method != 'GET': return route.fulfill(json={'ok': True, 'updateId': '9001'})
        if 'activity=' in u: return route.fulfill(json={'ok': True, 'activity': []})
        if 'team=' in u: return route.fulfill(json={'ok': True, 'team': [{'name': t['name'], 'home': 'ke'} for t in TEAM]})
        if 'history=' in u: return route.fulfill(json={'ok': True, 'notes': []})
        if re.search(r'[?&]id=\d+', u): return route.fulfill(json={'ok': True, 'last': None, 'canUndo': False, 'visits': [], 'costs': {'sum': 0, 'n': 0, 'last': []}})
        return route.fulfill(json=snap())
    def admin_api(route, req):
        u = req.url
        if req.method != 'GET': return route.fulfill(json={'ok': True})
        if 'view=team' in u: return route.fulfill(json={'ok': True, 'team': TEAM, 'mail': {'ready': True, 'to': []}, 'cron': True})
        return route.fulfill(json={'ok': True, 'items': [], 'rows': [], 'people': [], 'visits': [], 'donors': []})
    pg.route('**/api/pin-auth/me', lambda r, q: r.fulfill(json={'available': False}))
    pg.route(re.compile(r'/api/pin(\?.*)?$'), pin_api); pg.route(re.compile(r'/api/admin(\?.*)?$'), admin_api)
    pg.route(re.compile(r'/api/(place|sms-admin)(\?.*)?$'), lambda r, q: r.fulfill(json={'ok': True, 'places': []}))
    pg.route('**/api/photo/**', lambda r, q: r.fulfill(body=TILE, content_type='image/png'))
    pg.route(re.compile(r'https://(www\.google\.com/s2|fonts\.googleapis\.com)/.*'), lambda r, q: r.abort())

ERRS = []
def context(br, lang, cm, w=390, theme=''):
    ctx = br.new_context(viewport={'width': w, 'height': 800}, device_scale_factor=2 if w < 1000 else 1, is_mobile=w < 1000, has_touch=w < 1000, service_workers='block', locale='en-GB', timezone_id='Africa/Nairobi',
                         geolocation={'latitude': 0.515, 'longitude': 35.27, 'accuracy': 8}, permissions=['geolocation', 'microphone'])
    ctx.route(re.compile(r'https://(tile\.openstreetmap\.org|server\.arcgisonline\.com)/.*'), lambda r, q: r.fulfill(status=200, body=TILE, headers={'Content-Type': 'image/png', 'Access-Control-Allow-Origin': '*'}))
    pg = ctx.new_page(); install(pg); cm.install(pg)
    pg.on('pageerror', lambda e: ERRS.append('pageerror: ' + str(e)[:200]))
    pg.on('console', lambda m: ERRS.append('console: ' + m.text[:200]) if m.type == 'error' and 'Failed to load resource' not in m.text else None)
    pg.goto(BASE + '/pin'); pg.wait_for_timeout(600)
    pg.evaluate("([l, t]) => { localStorage.setItem('rfPinOnb', '1'); if (l === 'sw') localStorage.setItem('rfPinLang', 'sw'); if (t) localStorage.setItem('rfPinTheme', t); }", [lang, theme])
    pg.reload(); pg.wait_for_timeout(600)
    for d in '9999': pg.click('.pk[data-k="%s"]' % d)
    pg.wait_for_timeout(1500)
    return ctx, pg

def h(pg): return pg.evaluate('location.hash')
def view(pg): return pg.evaluate("['vMap','vList','vSearch','vRoute','vProj','vNew','vMe','vReport','vOffice','vChat','vRoom'].filter(v => !document.getElementById(v).classList.contains('hidden'))[0]")
def back(pg, t=800): pg.evaluate('history.back()'); pg.wait_for_timeout(t)
def tabbar(pg, v, t=1300): pg.click('#tabs button[data-v=%s]' % v); pg.wait_for_timeout(t)
def vis(pg, sel): loc = pg.locator(sel); return loc.count() > 0 and loc.first.is_visible()
def txt(pg, sel): return pg.locator(sel).first.inner_text() if pg.locator(sel).count() else ''
def shot(pg, name, lang): pg.wait_for_timeout(250); fn = os.path.join(OUT, '%s-%s.png' % (lang, name)); pg.screenshot(path=fn); return fn
def posts(cm, path): return [b for p, b in cm.posts if p == path]
def open_room(pg, cid, t=1300):
    if view(pg) != 'vChat': tabbar(pg, 'vChat', 1300)
    pg.click('.cw-row[data-open="%s"]' % cid); pg.wait_for_timeout(t)
def longpress(pg, sel):
    b = pg.locator(sel).first; b.scroll_into_view_if_needed(); box = b.bounding_box()
    pg.mouse.move(box['x'] + box['width'] / 2, box['y'] + box['height'] / 2); pg.mouse.down(); pg.wait_for_timeout(750); pg.mouse.up(); pg.wait_for_timeout(500)
def T(lang, en, sw): return sw if lang == 'sw' else en
# chat 2 list (cw2-list): search opens in place from the header, Settings / Starred / Refresh live in the ⋯ menu on a phone
def search(pg, t):
    if not vis(pg, '#cwQ'): pg.click('#cwSrch'); pg.wait_for_timeout(250)
    pg.fill('#cwQ', t)
def menu(pg, k, t=700): pg.click('#cwMore'); pg.wait_for_timeout(250); pg.click('#cwMenu [data-mn=%s]' % k); pg.wait_for_timeout(t)
def refresh(pg, t=1600):
    if vis(pg, '#bRefresh'): pg.click('#bRefresh')
    else: menu(pg, 'rf', 0)
    pg.wait_for_timeout(t)
# nothing wider than the screen, and no label cut inside the bottom bar
NOOVER = "(() => document.scrollingElement.scrollWidth <= innerWidth + 1)()"
BAR = """(() => { const out = []; document.querySelectorAll('#tabs nav button').forEach(b => { if (b.classList.contains('hidden')) return; const r = b.getBoundingClientRect();
  if (r.left < -1 || r.right > innerWidth + 1) out.push('off screen: ' + b.textContent.trim());
  const t = [...b.childNodes].filter(n => n.nodeType === 3).map(n => n.data.trim()).join(''); const rg = document.createRange(); rg.selectNodeContents(b);
  const words = t.split(/\\s+/).filter(Boolean); if (words.some(w => w.length > 1) && b.scrollWidth > b.clientWidth + 1) out.push('clipped: ' + t); });
  return out; })()"""
# a word split across two lines: any text node in these elements whose words land on different line boxes mid-word
BROKEN = """(sel => { const bad = []; document.querySelectorAll(sel).forEach(el => { if (!el.offsetParent) return; const w = document.createTreeWalker(el, NodeFilter.SHOW_TEXT); let n;
  while ((n = w.nextNode())) { const s = n.data; const re = /\\S+/g; let m; while ((m = re.exec(s))) { if (m[0].length < 2) continue; const r = document.createRange(); r.setStart(n, m.index); r.setEnd(n, m.index + m[0].length);
    const rects = [...r.getClientRects()].filter(x => x.width > 0.5); if (rects.length > 1 && Math.abs(rects[0].top - rects[rects.length - 1].top) > 4) bad.push(m[0]); } } }); return bad.slice(0, 6); })"""

RESULTS = []
def seq(name, lang, fn, br, cm=None, **kw):
    if ONLY and ONLY not in name: return
    cm = cm or ChatMock(); notes = []
    def check(c, what): (None if c else notes.append(what))
    del ERRS[:]
    ctx, pg = context(br, lang, cm, **kw)
    try: fn(pg, check, lang, cm)
    except Exception as e: notes.append('error: ' + str(e).split('\n')[0][:220])
    if notes:
        try: pg.screenshot(path=os.path.join(OUT, 'FAIL-%s-%s.png' % (lang, fn.__name__)))
        except Exception: pass
    errs = [e for e in ERRS if 'WebSocket' not in e]
    if errs: notes.append('console: %s' % errs[:3])
    ctx.close()
    RESULTS.append((lang, name, not notes, notes))
    print(('PASS ' if not notes else 'FAIL ') + '[%s] %s' % (lang, name) + ('' if not notes else ' :: ' + ' | '.join(notes)), flush=True)

def s_bar(pg, check, lang, cm):
    n = pg.locator('#tabs nav button:not(.hidden)').count()
    check(n == 6, 'admins see six items in the bar, Stories joined (%d)' % n)
    check(pg.locator('#tabs button[data-v=vChat]').count() == 1 and pg.locator('#tabs button[data-v=vMe]:not(.hidden)').count() == 0, 'Chat is in the bar and Me is not (admins: Me is the pill; field users get a Me tab, me-brief §5)')
    check(pg.evaluate(BAR) == [], 'every bar label fits at 360 px: %s' % pg.evaluate(BAR))
    check(pg.evaluate(BROKEN, '#tabs nav') == [], 'no word in the bar breaks: %s' % pg.evaluate(BROKEN, '#tabs nav'))
    pg.wait_for_timeout(600); check(txt(pg, '#chatDot') == '6', 'the Chat tab shows 6 unread, muted chats not counted (%s)' % txt(pg, '#chatDot'))
    shot(pg, 'bar-360', lang)
    pg.click('#hAv'); pg.wait_for_timeout(1200)
    check(h(pg) == '#me' and view(pg) == 'vMe', 'the avatar opens Me (%s)' % h(pg))
    check(pg.get_attribute('#hAv', 'aria-current') == 'page', 'the avatar is marked current on Me')
    back(pg, 1200); check(view(pg) == 'vMap', 'Back from Me goes to Map home (%s)' % view(pg))

def s_list(pg, check, lang, cm):
    tabbar(pg, 'vChat', 1500)
    check(h(pg) == '#chat' and view(pg) == 'vChat', 'the Chat tab opens the list (%s)' % h(pg))
    rows = pg.evaluate("[...document.querySelectorAll('#cwList .cw-row[data-open]')].map(r => r.dataset.open)")
    check(rows[:2] == ['t:news', 't:all'], 'Announcements first, then the pinned chat (%s)' % rows)
    check(rows[2:] == ['p:101', 'd:meril', 't:ke', 'g:kitale'], 'the rest newest first, archived left out (%s)' % rows)
    check(vis(pg, '.cw-row[data-open="p:101"] .cw-at'), 'the project thread shows the @ badge')
    check(txt(pg, '.cw-row[data-open="p:101"] .cw-un') == '3', 'unread count on the project thread')
    check(vis(pg, '.cw-row[data-open="d:meril"] .cw-on'), 'Meril is online: a green dot')
    check(T(lang, 'You:', 'Wewe:') in txt(pg, '.cw-row[data-open="t:ke"] .cw-pv'), 'my last message starts with “You:”')
    check(vis(pg, '.cw-row[data-open="t:ke"] .cw-tk.rd'), 'my last message shows blue ticks (read)')
    check(T(lang, 'Photo', 'Picha') in txt(pg, '.cw-row[data-open="p:101"] .cw-pv'), 'a photo preview says Photo')
    check(T(lang, 'Trip: Office planning trip', 'Safari: Office planning trip') in txt(pg, '.cw-row[data-open="g:kitale"] .cw-pv'), 'a trip card says “🧭 Trip: …”')
    check(pg.evaluate(NOOVER), 'nothing wider than the screen')
    check(pg.evaluate(BROKEN, '#vChat') == [], 'no word breaks in the list: %s' % pg.evaluate(BROKEN, '#vChat'))
    shot(pg, 'list', lang)
    # Archived: its own list; Back comes back to all chats, still on Chat
    pg.click('#cwArch'); pg.wait_for_timeout(600)
    check(pg.evaluate("[...document.querySelectorAll('#cwList .cw-row[data-open]')].map(r => r.dataset.open)") == ['d:grace'], 'Archived shows the archived chat')
    back(pg); check(h(pg) == '#chat' and vis(pg, '#cwArch') and not vis(pg, '#cwArchHd'), 'Back closes Archived, still on Chat (%s)' % h(pg))
    # search: titles here, message text from the server
    search(pg, 'cylinder'); pg.wait_for_timeout(900)
    check(pg.locator('#cwList .cw-hit').count() >= 2, 'search finds messages by their words (%d)' % pg.locator('#cwList .cw-hit').count())
    shot(pg, 'search', lang)
    pg.locator('#cwList .cw-hit[data-jump="7"]').click(); pg.wait_for_timeout(1800)
    check(view(pg) == 'vRoom' and h(pg) == '#chat/d:meril', 'a search hit opens its chat (%s)' % h(pg))
    check(pg.evaluate("document.getElementById('cwm-7').classList.contains('flash')"), 'and points at the message')
    back(pg, 1000); check(view(pg) == 'vChat' and pg.input_value('#cwQ') == 'cylinder', 'Back comes to the list with the search kept')
    back(pg); check(pg.input_value('#cwQ') == '' and view(pg) == 'vChat', 'Back then clears the search, still on Chat')
    # swipe a row left: Mute and Archive; Archive has Undo
    r = pg.locator('.cw-row[data-open="t:ke"]').bounding_box(); y = r['y'] + r['height'] / 2
    pg.mouse.move(r['x'] + r['width'] - 20, y); pg.mouse.down(); pg.mouse.move(r['x'] + r['width'] - 140, y, steps=8); pg.mouse.move(r['x'] + r['width'] - 200, y, steps=4); pg.mouse.up(); pg.wait_for_timeout(500)
    check(pg.evaluate("document.querySelector('.cw-rw[data-c=\"t:ke\"]').classList.contains('sw')"), 'a swipe left shows Mute and Archive')
    shot(pg, 'swipe', lang)
    pg.click('.cw-rw[data-c="t:ke"] [data-ra=arch]'); pg.wait_for_timeout(500)
    check(pg.locator('.cw-row[data-open="t:ke"]').count() == 0 and vis(pg, '.cw-snk'), 'Archive moves it away, with Undo')
    check(any(b.get('c') == 't:ke' and b.get('archived') is True for b in posts(cm, 'prefs')), 'the server hears about it')
    pg.click('.cw-snk button'); pg.wait_for_timeout(400); check(pg.locator('.cw-row[data-open="t:ke"]').count() == 1, 'Undo brings it back')
    # long-press a row: pin, mute, archive
    longpress(pg, '.cw-row[data-open="d:meril"]'); check(vis(pg, '.bsh [data-rm=pin]'), 'a long-press opens the row menu')
    pg.click('.bsh [data-rm=mute]'); pg.wait_for_timeout(600); pg.click('.bsh [data-mu="8h"]'); pg.wait_for_timeout(500)
    check(vis(pg, '.cw-row[data-open="d:meril"] .cw-bi'), 'muted for 8 hours: a muted mark')
    check(any(b.get('c') == 'd:meril' and isinstance(b.get('muted'), int) and b['muted'] > 0 for b in posts(cm, 'prefs')), 'the mute goes to the server with its end time')
    # starred messages
    menu(pg, 'star', 900); check(pg.locator('.tsh [data-sopen]').count() == 1, 'Starred messages lists the starred one')
    pg.click('.tsh [data-sopen]'); pg.wait_for_timeout(1800)
    check(h(pg) == '#chat/d:meril' and pg.evaluate("document.getElementById('cwm-4').classList.contains('flash')"), 'tapping it opens the chat at the message (%s)' % h(pg))

def s_room(pg, check, lang, cm):
    open_room(pg, 'd:meril', 1600)
    check(h(pg) == '#chat/d:meril' and view(pg) == 'vRoom', 'a row opens its chat (%s)' % h(pg))
    check(T(lang, 'online', 'mtandaoni') in txt(pg, '#cwHd'), 'the header says online (%s)' % txt(pg, '#cwHd'))
    check(not vis(pg, '#tabs') and vis(pg, '#hBack'), 'inside a chat: ← at top left, no bottom bar')
    check(any(b == {'c': 'd:meril', 'upTo': 10} for b in posts(cm, 'read')), 'opening it marks it read')
    days = pg.evaluate("[...document.querySelectorAll('#cwMsgs .cw-day')].map(d => d.textContent)")
    check(T(lang, 'Today', 'Leo') in days and T(lang, 'Yesterday', 'Jana') in days, 'date separators (%s)' % days)
    check(pg.locator('#cwm-2 .cw-tk.rd').count() == 1, 'my read message has blue ticks')
    check(pg.locator('#cwm-6 .cw-vo').count() == 1 and pg.locator('#cwm-5 .cw-loc').count() == 1 and pg.locator('#cwm-8 .cw-ct').count() == 1 and pg.locator('#cwm-9 .cw-doc').count() == 1,
          'a voice note, a location, a contact card and a document show')
    check(pg.locator('#cwm-3 .cw-g.n3 img').count() == 3, 'three photos show as a grid')
    check(pg.locator('#cwm-4 .cw-pc [data-visit="101"]').count() == 1 and pg.locator('#cwm-4 .cw-pc .cw-pci').count() == 1, 'a project link shows the project card: its photo and Report a visit')
    check(T(lang, 'edited', 'imehaririwa') in txt(pg, '#cwm-7'), 'an edited message says so')
    check(pg.evaluate(NOOVER), 'nothing wider than the screen')
    check(pg.evaluate(BROKEN, '#cwHd, #cwComp, .cw-day, .cw-mt, .cw-lt, .cw-la, .cw-ca, .cw-dn') == [], 'no word breaks: %s' % pg.evaluate(BROKEN, '#cwHd, #cwComp, .cw-day, .cw-mt, .cw-la, .cw-ca, .cw-dn'))
    shot(pg, 'room', lang)
    # a quote jumps to the message it quotes
    pg.evaluate("document.getElementById('cwMsgs').scrollTop = 1e6"); pg.wait_for_timeout(200)
    pg.click('#cwm-7 .cw-q'); pg.wait_for_timeout(700)
    check(pg.evaluate("document.getElementById('cwm-5').classList.contains('flash')"), 'tapping a quote jumps to the original')
    check(vis(pg, '#cwJump'), 'scrolled up: the jump-to-bottom button shows')
    # typing… and an incoming message while scrolled up
    cm.push({'t': 'typing', 'c': 'd:meril', 'who': 'Meril Kiprop', 'what': 'typing'}); pg.wait_for_timeout(400)
    check(T(lang, 'typing', 'anaandika') in txt(pg, '#cwHd'), 'typing… shows in the header (%s)' % txt(pg, '#cwHd'))
    shot(pg, 'typing', lang)
    cm.push({'t': 'msg', 'msg': {'id': 11, 'c': 'd:meril', 'from': 'Meril Kiprop', 'at': int(datetime.datetime.now(datetime.timezone.utc).timestamp() * 1000), 'kind': 'text', 'text': 'Nimefika!', 'media': []}}); pg.wait_for_timeout(600)
    check(pg.locator('#cwm-11').count() == 1 and txt(pg, '#cwJumpT') == T(lang, '1 new', '1 mpya'), 'a new message arrives; the chip says “1 new” (%s)' % txt(pg, '#cwJump'))
    pg.click('#cwJump'); pg.wait_for_timeout(800)
    # send: a clock first, then a tick; then the WebSocket says it was read
    pg.fill('#cwTx', 'On my way'); pg.wait_for_timeout(100)
    check(pg.get_attribute('#cwGo', 'aria-label') == T(lang, 'Send', 'Tuma'), 'with words in the box, the mic becomes Send')
    pg.click('#cwGo'); pg.wait_for_timeout(900)
    s = posts(cm, 'send'); check(len(s) == 1 and s[0]['text'] == 'On my way' and s[0].get('cid'), 'the message goes to the server with a client id')
    mid = pg.evaluate("(() => { const a = [...document.querySelectorAll('#cwMsgs .cw-m.me')]; return a[a.length - 1].dataset.id; })()")
    check(mid and pg.locator('#cwm-%s .cw-tk' % mid).count() == 1 and not pg.locator('#cwm-%s .cw-tk.rd' % mid).count(), 'sent: one grey tick')
    cm.push({'t': 'status', 'c': 'd:meril', 'upTo': int(mid), 'status': 'read'}); pg.wait_for_timeout(400)
    check(pg.locator('#cwm-%s .cw-tk.rd' % mid).count() == 1, 'read: two blue ticks')
    check(pg.evaluate("document.querySelectorAll('#cwMsgs .cw-m.pend').length") == 0, 'nothing left waiting')
    # Back: the list, the row updated; a message to another chat lifts the badge
    back(pg, 1000); check(view(pg) == 'vChat' and h(pg) == '#chat', 'Back from the chat comes to the list (%s)' % h(pg))
    check('On my way' in txt(pg, '.cw-row[data-open="d:meril"] .cw-pv'), 'the row shows my last message (%s)' % txt(pg, '.cw-row[data-open="d:meril"]'))
    n0 = int(txt(pg, '#chatDot') or 0)
    cm.push({'t': 'msg', 'msg': {'id': 6, 'c': 't:all', 'from': 'Grace Achieng', 'at': int(datetime.datetime.now(datetime.timezone.utc).timestamp() * 1000), 'kind': 'text', 'text': 'Tea at 4?', 'media': []}}); pg.wait_for_timeout(500)
    check(int(txt(pg, '#chatDot') or 0) == n0 + 1 and 'Tea at 4?' in txt(pg, '.cw-row[data-open="t:all"] .cw-pv'), 'a new message lifts the Chat badge and the row')
    check(any(b == {'c': 't:all', 'upTo': 6} for b in posts(cm, 'delivered')), 'it is marked delivered')
    back(pg, 1200); check(view(pg) == 'vMap', 'Back from Chat (opened from the bar) goes to Map home (%s)' % view(pg))

def s_actions(pg, check, lang, cm):
    open_room(pg, 'd:meril', 1500)
    longpress(pg, '#cwm-10 .cw-b'); check(vis(pg, '.bsh.cw-menu'), 'a long-press opens reactions and actions')
    shot(pg, 'menu', lang)
    back(pg); check(not vis(pg, '.bsh') and view(pg) == 'vRoom' and h(pg) == '#chat/d:meril', 'Back closes only the menu')
    longpress(pg, '#cwm-10 .cw-b'); pg.click('.bsh [data-react="👍"]'); pg.wait_for_timeout(600)
    check(pg.locator('#cwm-10 .cw-rc[data-rx="👍"].on').count() == 1, 'a reaction shows under the message')
    check(any(b.get('emoji') == '👍' and b.get('id') == 10 for b in posts(cm, 'react')), 'and goes to the server')
    pg.click('#cwm-10 .cw-rc[data-rx="❤️"]'); pg.wait_for_timeout(600); check(vis(pg, '.bsh .cw-pr'), 'tapping a reaction shows who reacted'); back(pg)
    # swipe right to reply
    b = pg.locator('#cwm-8 .cw-b').bounding_box(); y = b['y'] + 20
    pg.mouse.move(b['x'] + 30, y); pg.mouse.down(); pg.mouse.move(b['x'] + 110, y, steps=10); pg.mouse.up(); pg.wait_for_timeout(500)
    check(vis(pg, '.cw-rb') and 'Meril' in txt(pg, '.cw-rb'), 'a swipe right starts a reply')
    pg.fill('#cwTx', 'Thanks for the contact'); pg.click('#cwGo'); pg.wait_for_timeout(900)
    s = posts(cm, 'send'); check(s and s[-1].get('reply', {}).get('id') == 8, 'the reply quotes the message (%s)' % (s[-1].get('reply') if s else None))
    mid = s and pg.evaluate("(() => { const a = [...document.querySelectorAll('#cwMsgs .cw-m.me')]; return a[a.length - 1].dataset.id; })()")
    check(pg.locator('#cwm-%s .cw-q' % mid).count() == 1, 'the sent reply shows its quote')
    # edit my own message (within 15 minutes)
    longpress(pg, '#cwm-%s .cw-tx' % mid); pg.click('.bsh [data-mk=edit]'); pg.wait_for_timeout(500)
    check(vis(pg, '.cw-rb.ed') and pg.input_value('#cwTx') == 'Thanks for the contact', 'Edit puts the words back in the box')
    pg.fill('#cwTx', 'Thanks for Bertin’s contact'); pg.click('#cwGo'); pg.wait_for_timeout(800)
    check(any(e.get('text') == 'Thanks for Bertin’s contact' for e in posts(cm, 'edit')) and T(lang, 'edited', 'imehaririwa') in txt(pg, '#cwm-%s' % mid), 'the edit is saved and marked edited')
    # star and info
    longpress(pg, '#cwm-%s .cw-tx' % mid); pg.click('.bsh [data-mk=star]'); pg.wait_for_timeout(600)
    check(pg.locator('#cwm-%s .cw-st' % mid).count() == 1 and posts(cm, 'star'), 'Star marks it')
    longpress(pg, '#cwm-%s .cw-tx' % mid); pg.click('.bsh [data-mk=info]'); pg.wait_for_timeout(900)
    check('Meril Kiprop' in txt(pg, '.bsh .cw-inf'), 'Info lists who read it'); shot(pg, 'info', lang); back(pg)
    # delete for everyone
    longpress(pg, '#cwm-%s .cw-tx' % mid); pg.click('.bsh [data-mk=del]'); pg.wait_for_timeout(500); pg.click('.bsh [data-del=all]'); pg.wait_for_timeout(700)
    check(pg.locator('#cwm-%s .cw-dl' % mid).count() == 1 and any(d.get('forAll') for d in posts(cm, 'delete')), 'Delete for everyone leaves “This message was deleted”')
    # forward
    longpress(pg, '#cwm-10 .cw-b'); pg.click('.bsh [data-mk=fwd]'); pg.wait_for_timeout(600)
    pg.click('.tsh [data-fw="t:ke"]'); pg.click('#cwFwGo'); pg.wait_for_timeout(900)
    check(any(x.get('c') == 't:ke' and x.get('fwd') for x in posts(cm, 'send')), 'Forward sends it to the chosen chat')
    # turn into a job: Report a repair opens the visit, prefilled
    back(pg, 900); pg.click('.cw-row[data-open="p:101"]'); pg.wait_for_timeout(1400)
    longpress(pg, '#cwm-2 .cw-tx'); pg.click('.bsh [data-mk=repair]'); pg.wait_for_timeout(2200)
    check(h(pg) == '#p/101/visit', 'Report a repair opens the project’s visit (%s)' % h(pg))
    check(pg.evaluate("(() => { const d = JSON.parse(localStorage.getItem('rfPinDraft:101') || '{}'); return d.state === 'repair' && /handle came off/.test(d.note || ''); })()"), 'the visit draft has “Needs repair” and the words')
    back(pg, 900); check(vis(pg, '.bsh.po-ask'), 'Back from the prefilled visit asks first (the draft is kept)')
    pg.click('.bsh.po-ask [data-k=yes]'); pg.wait_for_timeout(1300); pg.evaluate("localStorage.removeItem('rfPinDraft:101')")
    check(h(pg) == '#chat/p:101', 'then Back returns to the chat (%s)' % h(pg))
    longpress(pg, '#cwm-2 .cw-tx'); pg.click('.bsh [data-mk=assign]'); pg.wait_for_timeout(1500)
    check(vis(pg, '[data-testid=ocv_assign_sheet]') and pg.input_value('#aTo') == 'Bertin Mwangi', 'Assign a visit opens the assign sheet with the project and the person (%s)' % (pg.input_value('#aTo') if vis(pg, '#aTo') else '-'))

def s_media(pg, check, lang, cm):
    open_room(pg, 'd:meril', 1500)
    pg.click('#cwAtt'); pg.wait_for_timeout(600)
    check(pg.locator('.bsh .cw-att button').count() == 6, 'attach: photos, camera, document, location, live location, contact')
    check(pg.evaluate(BROKEN, '.bsh .cw-att') == [], 'no word breaks in the attach sheet: %s' % pg.evaluate(BROKEN, '.bsh .cw-att'))
    shot(pg, 'attach', lang)
    pg.click('[data-att=loc]'); pg.wait_for_timeout(1500)
    s = posts(cm, 'send'); check(s and s[-1]['kind'] == 'location' and abs(s[-1]['loc']['lat'] - 0.515) < 1e-6, 'Location sends where I am')
    pg.click('#cwAtt'); pg.wait_for_timeout(500); pg.click('[data-att=live]'); pg.wait_for_timeout(600); pg.click('.bsh [data-live="15"]'); pg.wait_for_timeout(1500)
    s = posts(cm, 'send'); check(s and s[-1]['kind'] == 'live' and s[-1]['live']['until'] > 0, 'Live location for 15 minutes')
    mid = pg.evaluate("(() => { const a = [...document.querySelectorAll('#cwMsgs .cw-m.me')]; return a[a.length - 1].dataset.id; })()")
    check(pg.locator('#cwm-%s [data-livestop]' % mid).count() == 1, 'my live location has Stop sharing')
    pg.click('#cwm-%s [data-livestop]' % mid); pg.wait_for_timeout(600); check(any(x.get('id') == int(mid) for x in posts(cm, 'live-stop')), 'Stop sharing tells the server')
    pg.click('#cwAtt'); pg.wait_for_timeout(500); pg.click('[data-att=contact]'); pg.wait_for_timeout(900); pg.click('.tsh [data-ct="Grace Achieng"]'); pg.wait_for_timeout(900)
    s = posts(cm, 'send'); check(s and s[-1]['kind'] == 'contact' and s[-1]['contact'] == {'name': 'Grace Achieng'}, 'a teammate as a contact card (no phone number)')
    # a photo with a caption
    fn = os.path.join(OUT, 'pick.png'); open(fn, 'wb').write(png((10, 120, 194), 1200, 900))
    pg.click('#cwAtt'); pg.wait_for_timeout(500)
    with pg.expect_file_chooser() as fc: pg.click('[data-att=photo]')
    fc.value.set_files(fn); pg.wait_for_timeout(1500)
    check(vis(pg, '#cwCap'), 'the photo opens with a caption box'); shot(pg, 'photo-caption', lang)
    pg.fill('#cwCap', 'The new apron'); pg.click('#cwCapGo'); pg.wait_for_timeout(1500)
    up = [b for b in posts(cm, 'media') if b.get('type') == 'photo']; s = posts(cm, 'send')
    check(up and up[-1]['size'] <= 2 * 1048576 and s[-1]['kind'] == 'media' and s[-1]['text'] == 'The new apron' and s[-1]['media'][0]['id'].startswith('up'), 'the photo is shrunk, uploaded, then sent with its caption')
    # the viewer: every photo in the chat; Back closes it, still in the chat
    pg.locator('#cwm-3 [data-view]').first.click(); pg.wait_for_timeout(700)
    check(vis(pg, '.cw-lb') and '1 / ' in txt(pg, '.cw-lbn'), 'a photo opens full screen (%s)' % txt(pg, '.cw-lbn'))
    pg.click('.cw-lb [data-lb=n]'); pg.wait_for_timeout(300); check(txt(pg, '.cw-lbn').startswith('2 / '), 'the viewer moves to the next photo')
    shot(pg, 'viewer', lang)
    back(pg); check(not vis(pg, '.cw-lb') and view(pg) == 'vRoom', 'Back closes the viewer only')
    # the viewer: double-tap zooms in and out, swipe down closes
    pg.locator('#cwm-3 [data-view]').first.click(); pg.wait_for_timeout(700)
    bb = pg.locator('.cw-lbs img').bounding_box(); cx, cy = bb['x'] + bb['width'] / 2, bb['y'] + bb['height'] / 2
    pg.mouse.click(cx, cy); pg.wait_for_timeout(90); pg.mouse.click(cx, cy); pg.wait_for_timeout(400)
    check(pg.get_attribute('.cw-lb', 'data-zoom') == '1' and 'scale(2.5)' in pg.evaluate("document.querySelector('.cw-lbs img').style.transform"), 'a double-tap zooms in')
    pg.mouse.move(cx, cy); pg.mouse.down(); pg.mouse.move(cx - 60, cy - 40, steps=6); pg.mouse.up(); pg.wait_for_timeout(200)
    check('translate(' in pg.evaluate("document.querySelector('.cw-lbs img').style.transform"), 'zoomed in, a drag pans')
    pg.mouse.click(cx, cy); pg.wait_for_timeout(90); pg.mouse.click(cx, cy); pg.wait_for_timeout(400)
    check(pg.get_attribute('.cw-lb', 'data-zoom') == '', 'another double-tap zooms back out')
    pg.mouse.move(cx, cy); pg.mouse.down(); pg.mouse.move(cx, cy + 220, steps=10); pg.mouse.up(); pg.wait_for_timeout(500)
    check(not vis(pg, '.cw-lb') and view(pg) == 'vRoom', 'a swipe down closes the viewer')
    # voice: play at 1×, 1.5×, 2×
    pg.click('#cwm-6 .cw-vs'); pg.wait_for_timeout(200); check(txt(pg, '#cwm-6 .cw-vs') == '1.5×', 'speed goes to 1.5×')
    pg.click('#cwm-6 .cw-vs'); pg.wait_for_timeout(200); check(txt(pg, '#cwm-6 .cw-vs') == '2×', 'then 2×')
    pg.click('#cwm-6 .cw-vs'); pg.click('#cwm-6 .cw-vp'); pg.wait_for_timeout(1200)
    check(pg.evaluate("Object.values(window.document.querySelectorAll('#cwm-6 .cw-wv i.on')).length") > 0 or pg.get_attribute('#cwm-6 .cw-vp', 'aria-label') != T(lang, 'Play voice message', 'Cheza ujumbe wa sauti'), 'the voice note plays')
    # the contact card's Message opens a chat with them
    pg.click('#cwm-8 [data-ctmsg]'); pg.wait_for_timeout(1400)
    check(h(pg) == '#chat/d:bertin' and any(b.get('with') == 'Bertin Mwangi' for b in posts(cm, 'open')), 'the contact card’s Message opens a chat with them (%s)' % h(pg))

def s_voice(pg, check, lang, cm):
    open_room(pg, 'd:meril', 1500)
    check(pg.get_attribute('#cwGo', 'aria-label') == T(lang, 'Hold to record a voice message', 'Shikilia kurekodi ujumbe wa sauti'), 'an empty box shows the mic')
    b = pg.locator('#cwGo').bounding_box(); x, y = b['x'] + b['width'] / 2, b['y'] + b['height'] / 2
    pg.mouse.move(x, y); pg.mouse.down(); pg.wait_for_timeout(1800)
    check(vis(pg, '#cwRec') and T(lang, 'Slide to cancel', 'Telezesha kughairi') in txt(pg, '#cwRec'), 'holding the mic records, “Slide to cancel”')
    check(any('recording' in m for m in getattr(cm, 'wsin', [])), 'the chat sees “recording…”')
    shot(pg, 'recording', lang)
    pg.mouse.up(); pg.wait_for_timeout(900)
    check(vis(pg, '[data-testid=cw_voice_preview]') and not posts(cm, 'send'), 'letting go shows the preview first (nothing sent yet)')
    shot(pg, 'voice-preview', lang)
    pg.click('#cwVpGo'); pg.wait_for_timeout(1800)
    s = posts(cm, 'send'); up = [m for m in posts(cm, 'media') if m.get('type') == 'voice']
    check(up and s and s[-1]['kind'] == 'voice' and s[-1]['media'][0]['dur'] >= 1, 'Send in the preview sends the voice note (upload, then send with its length)')
    # slide left cancels
    pg.mouse.move(x, y); pg.mouse.down(); pg.wait_for_timeout(1300); pg.mouse.move(x - 160, y, steps=10); pg.wait_for_timeout(400); pg.mouse.up(); pg.wait_for_timeout(900)
    check(len(posts(cm, 'send')) == len(s), 'sliding left cancels it')
    # slide up locks it: hands-free, then Send
    pg.mouse.move(x, y); pg.mouse.down(); pg.wait_for_timeout(1300); pg.mouse.move(x, y - 120, steps=10); pg.wait_for_timeout(300); pg.mouse.up(); pg.wait_for_timeout(700)
    check(vis(pg, '#cwRecGo'), 'sliding up locks it hands-free'); shot(pg, 'recording-locked', lang)
    pg.click('#cwRecGo'); pg.wait_for_timeout(1800)
    check(len(posts(cm, 'send')) == len(s) + 1, 'Send sends the locked recording')
    # hands-free, then Stop: the preview; trim the end to half, and it goes as a shorter WAV
    pg.mouse.move(x, y); pg.mouse.down(); pg.wait_for_timeout(600); pg.mouse.move(x, y - 120, steps=10); pg.wait_for_timeout(200); pg.mouse.up(); pg.wait_for_timeout(2600)
    pg.click('#cwRecStop'); pg.wait_for_timeout(900)
    check(vis(pg, '[data-testid=cw_voice_preview]'), 'Stop when hands-free shows the preview')
    pg.click('#cwVpP'); pg.wait_for_timeout(600); check(pg.get_attribute('#cwVpP', 'aria-label') == T(lang, 'Pause', 'Simamisha'), 'the preview plays')
    tb = pg.locator('#cwVtr').bounding_box(); pg.mouse.click(tb['x'] + tb['width'] * 0.5, tb['y'] + tb['height'] / 2); pg.wait_for_timeout(300)
    check(pg.evaluate("parseFloat(document.querySelector('#cwVtr .cut').style.width)") > 40, 'dragging the handle trims the end')
    n0 = len(posts(cm, 'send')); pg.click('#cwVpGo'); pg.wait_for_timeout(2200)
    s2 = posts(cm, 'send'); up2 = [m for m in posts(cm, 'media') if m.get('type') == 'voice']
    check(len(s2) == n0 + 1 and up2[-1]['name'] == 'voice.wav' and s2[-1]['media'][0]['dur'] <= 2, 'the trimmed note is sent shorter, as WAV (%s, %ss)' % (up2[-1]['name'], s2[-1]['media'][0]['dur'] if s2 else '-'))
    # and Delete in the preview sends nothing
    pg.mouse.move(x, y); pg.mouse.down(); pg.wait_for_timeout(1300); pg.mouse.up(); pg.wait_for_timeout(800)
    pg.click('#cwVpX'); pg.wait_for_timeout(500)
    check(not vis(pg, '[data-testid=cw_voice_preview]') and len(posts(cm, 'send')) == n0 + 1, 'Delete in the preview throws it away')

def s_offline(pg, check, lang, cm):
    open_room(pg, 'd:meril', 1500)
    cm.down = True
    pg.fill('#cwTx', 'Sent with no signal'); pg.click('#cwGo'); pg.wait_for_timeout(900)
    check(pg.locator('#cwMsgs .cw-m.pend .cw-tk.q').count() == 1, 'with no signal the message waits with a clock')
    shot(pg, 'offline-clock', lang)
    pg.reload(); pg.wait_for_timeout(2500)
    check(h(pg) == '#chat/d:meril' and pg.locator('#cwMsgs .cw-m.pend .cw-tk.q').count() == 1, 'it survives the app being closed (%s)' % h(pg))
    cm.down = False; n = len(posts(cm, 'send'))
    pg.evaluate("dispatchEvent(new Event('online'))"); pg.wait_for_timeout(1500)
    s = posts(cm, 'send')
    check(pg.locator('#cwMsgs .cw-m.pend').count() == 0 and pg.locator('#cwMsgs .cw-m.me .cw-tx', has_text='Sent with no signal').count() == 1, 'back online it sends, once')
    check(len([x for x in s if x['text'] == 'Sent with no signal']) >= 1 and len(set(x['cid'] for x in s if x['text'] == 'Sent with no signal')) == 1, 'every try uses the same client id')

def s_group(pg, check, lang, cm):
    open_room(pg, 'g:kitale', 1500)
    pg.click('#cwHd'); pg.wait_for_timeout(800)
    check(vis(pg, '[data-testid=cw_info]') and pg.locator('.cw-info .cw-mem').count() == 3, 'chat info lists the members')
    check(pg.locator('.cw-info .cw-mem .tag').count() == 1, 'admins are marked')
    check(pg.evaluate(BROKEN, '.cw-info') == [], 'no word breaks in chat info: %s' % pg.evaluate(BROKEN, '.cw-info'))
    shot(pg, 'group-info', lang)
    pg.click('.cw-info [data-i=wall]'); pg.wait_for_timeout(600); pg.click('.bsh [data-wp=savanna]'); pg.wait_for_timeout(600); pg.click('.bsh [data-bub=green]'); pg.wait_for_timeout(900)
    check(pg.evaluate("document.getElementById('cwMsgs').classList.contains('cw-wp-savanna')") and any(b.get('wallpaper') == 'savanna' for b in posts(cm, 'prefs')), 'a RAFIKI wallpaper (savanna) is chosen for this chat')
    check(pg.get_attribute('#vRoom', 'data-bub') == 'green' and any(b.get('bubble') == 'green' for b in posts(cm, 'prefs')), 'and my bubble colour, saved per chat')
    shot(pg, 'wallpaper-sheet', lang); back(pg)
    pg.click('.cw-info [data-i=only]'); pg.wait_for_timeout(500)
    check(any(b.get('onlyAdmins') is True for b in posts(cm, 'group-update')), '“Only admins can send messages” goes to the server')
    pg.click('.cw-info [data-i=link]'); pg.wait_for_timeout(800)
    check('join/K1TALE7' in txt(pg, '#cwUrl'), 'the invite link shows (%s)' % txt(pg, '#cwUrl'))
    back(pg); check(not vis(pg, '#cwUrl') and vis(pg, '[data-testid=cw_info]'), 'Back closes the link, info stays')
    pg.locator('.cw-info [data-mem="Grace Achieng"]').click(); pg.wait_for_timeout(600)
    pg.click('.bsh [data-mm=admin]'); pg.wait_for_timeout(700)
    check(any(b.get('admin') == ['Grace Achieng'] for b in posts(cm, 'members')), 'an admin makes Grace an admin')
    back(pg); check(not vis(pg, '.tsh') and view(pg) == 'vRoom', 'Back closes chat info, still in the chat')
    # a new group
    back(pg, 900); pg.click('#cwNew'); pg.wait_for_timeout(900); pg.click('.tsh [data-nw=group]'); pg.wait_for_timeout(900)
    pg.click('.tsh [data-pk="Meril Kiprop"]'); pg.click('.tsh [data-pk="Bertin Mwangi"]'); pg.click('#cwPpGo'); pg.wait_for_timeout(700)
    pg.fill('#cwNg', 'Kesses visit'); pg.click('#cwNgo'); pg.wait_for_timeout(1600)
    g = posts(cm, 'group'); check(g and g[-1]['title'] == 'Kesses visit' and sorted(g[-1]['members']) == ['Bertin Mwangi', 'Meril Kiprop'], 'New group makes the group with its members')
    check(view(pg) == 'vRoom' and h(pg).startswith('#chat/g'), 'and opens it (%s)' % h(pg))
    # @mentions
    pg.fill('#cwTx', 'Hi @Mer'); pg.dispatch_event('#cwTx', 'input'); pg.wait_for_timeout(300)
    check(vis(pg, '#cwMen [data-men="Meril Kiprop"]'), '@ shows a list of names')
    pg.click('#cwMen [data-men="Meril Kiprop"]'); pg.click('#cwGo'); pg.wait_for_timeout(800)
    s = posts(cm, 'send'); check(s and s[-1].get('mentions') == ['Meril Kiprop'], 'the mention goes with the message')

def s_entry(pg, check, lang, cm):
    # the contact sheet's Message
    tabbar(pg, 'vOffice', 1800); pg.click('#oTabs [data-o=team]'); pg.wait_for_timeout(1500)
    pg.locator('.tm-row[data-who="Meril Kiprop"]').first.click(); pg.wait_for_timeout(1200)
    check(vis(pg, '[data-testid=ct_msg]'), 'the contact sheet has a Message button')
    check(pg.evaluate(BROKEN, '.ct-qk') == [], 'no word breaks in the contact buttons: %s' % pg.evaluate(BROKEN, '.ct-qk'))
    shot(pg, 'contact-sheet', lang)
    pg.click('[data-testid=ct_msg]'); pg.wait_for_timeout(1500)
    check(h(pg) == '#chat/d:meril' and view(pg) == 'vRoom', 'Message opens the chat with them (%s)' % h(pg))
    back(pg, 1200); check(view(pg) == 'vOffice', 'Back returns to the Office (%s)' % view(pg))
    # the project page's Chat
    pg.evaluate("location.hash = '#p/101'"); pg.wait_for_timeout(1500)
    check(vis(pg, '#pChat'), 'the project page has a Chat button')
    check(pg.evaluate(BROKEN, '#pLinks') == [], 'no word breaks on the project links: %s' % pg.evaluate(BROKEN, '#pLinks'))
    shot(pg, 'project-chat-button', lang)
    pg.click('#pChat'); pg.wait_for_timeout(1500)
    check(h(pg) == '#chat/p:101' and view(pg) == 'vRoom', 'Chat opens the project thread (%s)' % h(pg))
    check(pg.locator('#cwMsgs .cw-sys').count() >= 3, 'system messages (repair reported, assigned, community report) show in the thread')
    check(T(lang, 'This message was deleted', 'Ujumbe huu ulifutwa') in txt(pg, '#cwm-7') and T(lang, 'Forwarded', 'Imesambazwa') in txt(pg, '#cwm-8'), 'deleted and forwarded messages say so')
    shot(pg, 'project-thread', lang)
    back(pg, 1300); check(h(pg) == '#p/101' and view(pg) == 'vProj', 'Back returns to the project (%s)' % h(pg))
    # a project thread that doesn't exist yet is made on the server
    pg.evaluate("location.hash = '#p/103'"); pg.wait_for_timeout(1300); pg.click('#pChat'); pg.wait_for_timeout(1500)
    check(any(b == {'kind': 'project', 'project': '103'} for b in posts(cm, 'open')) and h(pg) == '#chat/p:103', 'a new project thread opens (%s)' % h(pg))

def s_nav(pg, check, lang, cm):
    tabbar(pg, 'vChat', 1400); pg.evaluate('scrollTo(0, 0)')
    refresh(pg)
    check(h(pg) == '#chat' and view(pg) == 'vChat', 'the refresh button (⋯ › Refresh on a phone) stays on Chat')
    check(pg.evaluate("[...document.querySelectorAll('.toast')].some(t => /Updated|Imesasishwa/.test(t.textContent))"), 'an “Updated” toast')
    open_room(pg, 'd:meril', 1300)
    n = len(ERRS); pg.reload(); pg.wait_for_timeout(2500)
    check(h(pg) == '#chat/d:meril' and view(pg) == 'vRoom' and pg.locator('#cwm-10').count() == 1, 'a reload on a chat comes back to it (%s)' % h(pg))
    check(len([e for e in ERRS[n:] if 'WebSocket' not in e]) == 0, 'no console errors after the reload (%s)' % ERRS[n:])
    back(pg, 1200); check(view(pg) == 'vChat', 'Back after the reload comes to the list (%s)' % view(pg))
    # start over: from another tab and back by the bar, the search is cleared
    search(pg, 'tea'); pg.wait_for_timeout(500); tabbar(pg, 'vList', 1200); tabbar(pg, 'vChat', 1300)
    check(pg.input_value('#cwQ') == '' and not vis(pg, '#cwQ') and h(pg) == '#chat', 'the bottom bar starts Chat over (search cleared and closed)')
    # the WebSocket drops: it comes back and catches up with only what's newer; closed with 4401 (blocked, removed, signed out) it stays shut
    open_room(pg, 'd:meril', 1300); t0 = len(posts(cm, 'ticket'))
    for w in cm.ws: w.close(code=1011, reason='restart')
    pg.wait_for_timeout(2600)
    check(len(posts(cm, 'ticket')) == t0 + 1, 'after a drop the socket comes back (%d tickets, was %d)' % (len(posts(cm, 'ticket')), t0))
    check(any(p == 'msgs' and q.get('after') == '10' for p, q in cm.gets), 'and catches up with msgs?after= (only newer messages)')
    t1 = len(posts(cm, 'ticket')); n_list = len([1 for p, q in cm.gets if p == 'list'])
    for w in cm.ws[-1:]: w.close(code=4401, reason='blocked')
    pg.wait_for_timeout(3500)
    check(len(posts(cm, 'ticket')) == t1 and len([1 for p, q in cm.gets if p == 'list']) > n_list, 'closed with 4401 it doesn’t reconnect, it asks the list again')
    back(pg, 900)
    # an older page loads when you scroll up
    open_room(pg, 't:ke', 1500); n0 = pg.locator('#cwMsgs .cw-m').count()
    pg.evaluate("document.getElementById('cwMsgs').scrollTop = 0"); pg.wait_for_timeout(1200)
    n1 = pg.locator('#cwMsgs .cw-m').count()
    check(n0 == 50 and n1 == 70 and any(p == 'msgs' and q.get('before') == '21' for p, q in cm.gets), 'scrolling up loads the older messages (%d → %d)' % (n0, n1))
    back(pg, 900)
    # the join link
    pg.evaluate("location.hash = '#chat/join/K1TALE7'"); pg.wait_for_timeout(1800)
    check(any(b.get('token') == 'K1TALE7' for b in posts(cm, 'join')) and h(pg) == '#chat/g:kitale', 'a join link joins the group and opens it (%s)' % h(pg))

def s_off(pg, check, lang, cm):
    tabbar(pg, 'vChat', 1500)
    check(vis(pg, '[data-testid=cw_off]'), 'no CHAT binding: “Chat isn’t switched on yet”')
    check(T(lang, 'The office can turn it on', 'Ofisi inaweza kuliwasha') in txt(pg, '[data-testid=cw_off]'), 'it says the office can turn it on')
    check(pg.locator('[data-testid=cw_off] details.po-tech').count() == 1 and 'rafiki-chat' in pg.eval_on_selector('[data-testid=cw_off] details.po-tech', 'd => d.textContent'), 'the technical names (what the server says is missing) sit in a fold')
    check(not vis(pg, '#chatDot'), 'no badge on the Chat tab')
    shot(pg, 'chat-off', lang)
    pg.evaluate("location.hash = '#p/101'"); pg.wait_for_timeout(1500); pg.click('#pChat'); pg.wait_for_timeout(900)
    check(view(pg) == 'vProj' and pg.evaluate("[...document.querySelectorAll('.toast')].some(t => /switched on|halijawashwa/.test(t.textContent))"), 'the project Chat button explains it, nothing breaks')
    tabbar(pg, 'vList', 1300); check(pg.locator('#list .row').count() > 0, 'Projects still works')

# ---- the polish round (chatpw) ------------------------------------------------------------------------------------
# two things on screen that cover each other: text, times, tags, buttons and faces inside the same row, bubble or sheet
OVER = """(sel => { const bad = []; const Q = 'b, small, time, .tag, .btn, .cw-un, .cw-at, .cw-k, .cw-tx, .cw-mt, .cw-sn, .cw-rc, .cw-seen .cw-av, .cw-q, .cw-trp span, .cw-pinb small, .cw-qr button, .cw-dots';
  document.querySelectorAll(sel).forEach(box => { if (!box.getClientRects().length) return; const k = [...box.querySelectorAll(Q)].filter(e => e.getClientRects().length && getComputedStyle(e).visibility !== 'hidden');
    for (let i = 0; i < k.length; i++) for (let j = i + 1; j < k.length; j++) { const a = k[i], b = k[j]; if (a.contains(b) || b.contains(a)) continue; const r = a.getBoundingClientRect(), s = b.getBoundingClientRect();
      const w = Math.min(r.right, s.right) - Math.max(r.left, s.left), h = Math.min(r.bottom, s.bottom) - Math.max(r.top, s.top); if (w > 3 && h > 3) bad.push((a.className || a.tagName) + ' × ' + (b.className || b.tagName) + ' “' + (a.textContent || '').trim().slice(0, 18) + '”'); } }); return bad.slice(0, 6); })"""
# a button whose words don't fit inside it (cut off at its edge)
CLIP = """(sel => [...document.querySelectorAll(sel)].filter(b => b.getClientRects().length && b.scrollWidth > b.clientWidth + 1 && getComputedStyle(b).overflowX !== 'auto').map(b => b.textContent.trim().slice(0, 24)).slice(0, 6))"""
ROOMSEL = '#cwMsgs .cw-m, #cwMsgs .cw-sys, #cwPinB, #cwComp, #cwHd, #cwRh'
ROOMTXT = '#cwHd, #cwRh, #cwComp, #cwPinB, .cw-day, .cw-mt, .cw-sys, .cw-la, .cw-ca, .cw-dn, .cw-vo, .cw-trip, .cw-pc, .cw-seen, .cw-jump, .cw-qr'
def itin(pg, cm):
    cm.itin = []
    def h(route, req):
        cm.itin.append(req.url)
        if 'card=1' in req.url: return route.fulfill(json={'ok': True, 'card': {'id': 'it_1', 'title': 'Kitale repairs run', 'from': '2026-10-27', 'to': '2026-10-29', 'stops': 5, 'people': ['Reid Anderson'], 'url': None, 'status': 'active', 'next': {'name': 'Kapsaret Borehole', 'date': '2026-10-27', 'at': '09:30'}, 'done': 2, 'total': 5}})
        return route.fulfill(status=404, json={'ok': False, 'reason': 'not-found'})
    pg.route(re.compile(r'/api/itin/'), h)

def s_look(pg, check, lang, cm):
    itin(pg, cm); tabbar(pg, 'vChat', 1500)
    check(vis(pg, '.cw-row[data-open="t:news"] .cw-av.an') and T(lang, 'Announcements', 'Matangazo') in txt(pg, '.cw-row[data-open="t:news"]'), 'Announcements sits at the top with its megaphone')
    check(pg.locator('.cw-row[data-open="t:ke"] .cw-av.fl svg').count() == 1, 'a country team shows its flag')
    check(pg.evaluate("getComputedStyle(document.querySelector('.cw-row[data-open=\"p:101\"] .cw-av')).backgroundImage").startswith('url('), 'a project chat shows the project photo')
    check(txt(pg, '.cw-row[data-open="g:kitale"] .cw-av') == 'KR', 'a custom group shows its initials')
    open_room(pg, 't:all', 1500)
    check(pg.locator('#cwMsgs .cw-m.emo.e2').count() == 1, 'two emoji on their own show large, with no bubble')
    check(pg.evaluate("getComputedStyle(document.querySelector('#cwMsgs .cw-m.emo .cw-b')).backgroundColor") in ('rgba(0, 0, 0, 0)', 'transparent'), 'the emoji have no bubble behind them')
    check(pg.evaluate("getComputedStyle(document.querySelector('#cwMsgs .cw-m.them.first:not(.emo) .cw-b'), '::before').content") == '""', 'the first bubble of a run has a tail')
    check(pg.evaluate("(() => { const m = [...document.querySelectorAll('#cwMsgs .cw-m.them:not(.first) .cw-b')][0]; return !m || parseFloat(getComputedStyle(m).borderTopLeftRadius) <= 6; })()"), 'a joined bubble has a small corner where it meets the one above')
    check(pg.locator('#cwMsgs .cw-trip').count() == 1 and T(lang, 'Kapsaret Borehole', 'Kapsaret Borehole') in txt(pg, '#cwMsgs .cw-trip') and pg.locator('#cwMsgs .cw-trip .cw-trb i').count() == 1, 'a trip card: title, dates, next stop and progress')
    check(pg.locator('#cwMsgs .cw-lp.big .cw-lpi').count() == 1, 'an ordinary link with a picture shows it large')
    check(pg.locator('#cwMsgs .cw-seen .cw-av').count() == 3, 'Seen by: three tiny faces under the last message')
    bad = pg.evaluate(OVER, ROOMSEL); check(bad == [], 'nothing covers anything in the chat: %s' % bad)
    bad = pg.evaluate(BROKEN, ROOMTXT); check(bad == [], 'no word breaks: %s' % bad)
    shot(pg, 'look-team', lang)
    pg.click('#cwMsgs [data-testid=cw_trip_open]'); pg.wait_for_timeout(900)
    check(any('/api/itin/it_1' in u for u in cm.itin), 'Open trip opens the trip the way the trips code does')
    back(pg, 700)
    # a trip the viewer can't open: the title only
    back(pg, 900); open_room(pg, 'g:kitale', 1500)
    check(T(lang, 'Only people on this trip can open it', 'Ni watu wa safari hii tu wanaoweza kuifungua') in txt(pg, '#cwMsgs .cw-trip') and pg.locator('#cwMsgs .cw-trip [data-trip]').count() == 0, 'a trip you can’t see: its title and a note, no Open button')
    check(pg.locator('#cwMsgs .cw-seen .cw-av').count() == 5 and '+2' in txt(pg, '#cwMsgs .cw-seen'), 'Seen by: up to 5 faces, then “+2”')
    shot(pg, 'look-group', lang)
    back(pg, 900); open_room(pg, 'p:101', 1500)
    sys = pg.evaluate("[...document.querySelectorAll('#cwMsgs .cw-sys:not(.cw-e2e)')].map(s => [s.className, s.querySelector('svg') ? 1 : 0, s.textContent])")
    check(len(sys) >= 3 and all(x[1] for x in sys), 'every system line has its icon')
    check(any(T(lang, 'Bertin reported a repair', 'Bertin ameripoti matengenezo') in x[2] and re.search(r'· \d\d:\d\d', x[2]) for x in sys), 'short words and the time: “Bertin reported a repair · 10:42” (%s)' % [x[2] for x in sys][:3])
    check(any(T(lang, 'Assigned to Meril', 'Amepewa Meril') in x[2] for x in sys), 'first names: “Assigned to Meril”')
    shot(pg, 'look-project', lang)
    # a new system line comes in with a short entrance
    cm.push({'t': 'msg', 'msg': {'id': 10, 'c': 'p:101', 'from': '', 'kind': 'sys', 'text': 'Meril fixed it', 'sys': {'type': 'fixed', 'by': 'Meril Kiprop', 'project': '101'}, 'at': int(datetime.datetime.now(datetime.timezone.utc).timestamp() * 1000), 'media': []}}); pg.wait_for_timeout(120)
    check(pg.locator('#cwMsgs .cw-sys.t-fixed.nw').count() == 1 and T(lang, 'Meril fixed the pump', 'Meril ametengeneza pampu') in txt(pg, '#cwMsgs .cw-sys.t-fixed'), 'a new “Meril fixed the pump” line animates in')
    # the project card: Report a visit opens the visit for that project
    back(pg, 900); open_room(pg, 'd:meril', 1500)
    pg.click('#cwm-4 [data-visit="101"]'); pg.wait_for_timeout(1500)
    check(h(pg) == '#p/101/visit', 'Report a visit on the project card opens its visit (%s)' % h(pg))

def s_feel(pg, check, lang, cm):
    open_room(pg, 'd:meril', 1500)
    check(pg.locator('#cwMsgs .cw-m.nw').count() == 0, 'opening a chat doesn’t animate what was already there')
    cm.push({'t': 'typing', 'c': 'd:meril', 'who': 'Meril Kiprop', 'what': 'typing'}); pg.wait_for_timeout(300)
    check(pg.locator('#cwHd .cw-dots i').count() == 3, 'typing shows three bouncing dots in the header')
    shot(pg, 'typing-dots', lang)
    cm.push({'t': 'msg', 'msg': {'id': 11, 'c': 'd:meril', 'from': 'Meril Kiprop', 'at': int(datetime.datetime.now(datetime.timezone.utc).timestamp() * 1000), 'kind': 'text', 'text': 'Nimefika!', 'media': []}}); pg.wait_for_timeout(80)
    check(pg.locator('#cwm-11.nw').count() == 1, 'a new message slides in')
    check(pg.evaluate("getComputedStyle(document.getElementById('cwm-11')).animationName") == 'cwIn' and 0.15 <= float(pg.evaluate("parseFloat(getComputedStyle(document.getElementById('cwm-11')).animationDuration)")) <= 0.35, 'its animation is 150–350 ms')
    cm.push({'t': 'react', 'c': 'd:meril', 'id': 11, 'reacts': {'👍': ['Meril Kiprop']}}); pg.wait_for_timeout(80)
    check(pg.locator('#cwm-11 .cw-rc.pop').count() == 1, 'a reaction pops')
    # the send button turns into the mic when the box is empty
    check(pg.evaluate("document.getElementById('cwGo').classList.contains('mic')"), 'an empty box: the mic')
    pg.fill('#cwTx', 'Karibu'); check(not pg.evaluate("document.getElementById('cwGo').classList.contains('mic')"), 'words in the box: the send arrow (it morphs)')
    pg.click('#cwGo'); pg.wait_for_timeout(900)
    mid = pg.evaluate("(() => { const a = [...document.querySelectorAll('#cwMsgs .cw-m.me')]; return a[a.length - 1].dataset.id; })()")
    cm.push({'t': 'status', 'c': 'd:meril', 'upTo': int(mid), 'status': 'read'}); pg.wait_for_timeout(80)
    check(pg.locator('#cwm-%s .cw-tk.rd.up' % mid).count() == 1, 'the ticks fade from grey to blue')
    # a reduced-motion phone: nothing moves
    pg.emulate_media(reduced_motion='reduce'); cm.push({'t': 'msg', 'msg': {'id': 13, 'c': 'd:meril', 'from': 'Meril Kiprop', 'at': int(datetime.datetime.now(datetime.timezone.utc).timestamp() * 1000), 'kind': 'text', 'text': 'Sawa', 'media': []}}); pg.wait_for_timeout(80)
    check(pg.evaluate("getComputedStyle(document.getElementById('cwm-13')).animationName") == 'none', 'with reduced motion the animations are off')
    pg.emulate_media(reduced_motion='no-preference')
    # the list row shows the typing dots too
    back(pg, 900); cm.push({'t': 'typing', 'c': 't:ke', 'who': 'Bertin Mwangi', 'what': 'typing'}); pg.wait_for_timeout(300)
    check(pg.locator('.cw-row[data-open="t:ke"] .cw-dots').count() == 1, 'and in the chat list row')
    # older messages: the “Loading older…” skeleton; then the scroll is remembered per chat
    cm.hold = True
    open_room(pg, 't:ke', 1500); pg.evaluate("document.getElementById('cwMsgs').scrollTop = 0"); pg.wait_for_timeout(400)
    check(vis(pg, '#cwMsgs .cw-skel'), 'scrolling up shows the “Loading older…” skeleton')
    shot(pg, 'older-skeleton', lang)
    cm.release(); pg.wait_for_timeout(1000)
    pg.evaluate("(() => { const e = document.getElementById('cwm-30'); const b = document.getElementById('cwMsgs'); b.scrollTop = e.getBoundingClientRect().top - b.getBoundingClientRect().top + b.scrollTop - 40; })()"); pg.wait_for_timeout(500)
    back(pg, 900); open_room(pg, 'd:meril', 1200); back(pg, 900); open_room(pg, 't:ke', 1800)
    y = pg.evaluate("(() => { const e = document.getElementById('cwm-30'), b = document.getElementById('cwMsgs'); return e ? Math.round(e.getBoundingClientRect().top - b.getBoundingClientRect().top) : null; })()")
    check(y is not None and abs(y - 40) < 30, 'coming back, the chat is where I left it (%s px from the top)' % y)
    # new messages while scrolled up: “↓ 2 new”
    for i, t in ((71, 'One'), (72, 'Two')):
        cm.push({'t': 'msg', 'msg': {'id': i, 'c': 't:ke', 'from': 'Meril Kiprop', 'at': int(datetime.datetime.now(datetime.timezone.utc).timestamp() * 1000), 'kind': 'text', 'text': t, 'media': []}}); pg.wait_for_timeout(200)
    check(vis(pg, '#cwJump.chip') and txt(pg, '#cwJumpT') == T(lang, '2 new', '2 mpya'), 'scrolled up, a chip says “↓ 2 new” (%s)' % txt(pg, '#cwJump'))
    pg.wait_for_timeout(500)
    ov = pg.evaluate("(() => { const a = document.getElementById('cwJump').getBoundingClientRect(), b = document.getElementById('cwComp').getBoundingClientRect(); return a.bottom > b.top + 1; })()")
    check(not ov, 'the chip floats clear of the box and the quick replies')
    shot(pg, 'new-chip', lang)
    # the unread line fades once it has scrolled past
    back(pg, 900); cm.chats['g:kitale']['unread'] = 1; refresh(pg, 1200)
    open_room(pg, 'g:kitale', 1500)
    check(vis(pg, '#cwUnl') and not pg.evaluate("document.getElementById('cwUnl').classList.contains('gone')"), 'the unread line is there on opening')

def s_composer(pg, check, lang, cm):
    open_room(pg, 'd:meril', 1500)
    # quick replies: chips above the box when it's empty; a tap sends one
    pg.click('#cwTx'); pg.wait_for_timeout(300)
    chips = pg.evaluate("[...document.querySelectorAll('#cwQr [data-qr]')].map(b => b.textContent)")
    check(chips[:2] == [T(lang, 'On my way', 'Niko njiani'), T(lang, 'Arrived at site', 'Nimefika eneo la kazi')] and len(chips) == 6, 'quick replies show above the box (%s)' % chips)
    bad = pg.evaluate(BROKEN, '#cwQr'); check(bad == [], 'no word breaks in the chips: %s' % bad)
    shot(pg, 'quick-replies', lang)
    pg.click('#cwQr [data-qr="%s"]' % T(lang, 'Pump fixed ✅', 'Pampu imetengenezwa ✅')); pg.wait_for_timeout(900)
    check(posts(cm, 'send') and posts(cm, 'send')[-1]['text'] == T(lang, 'Pump fixed ✅', 'Pampu imetengenezwa ✅'), 'a tap on a chip sends it')
    pg.fill('#cwTx', 'x'); pg.wait_for_timeout(100); check(not vis(pg, '#cwQr'), 'with words in the box the chips go')
    # the box grows to 6 lines, then scrolls; the expand button opens the big editor
    h1 = pg.evaluate("document.getElementById('cwTx').offsetHeight")
    pg.fill('#cwTx', '\n'.join('Line %d' % i for i in range(1, 10))); pg.wait_for_timeout(150)
    h6 = pg.evaluate("document.getElementById('cwTx').offsetHeight"); lh = pg.evaluate("parseFloat(getComputedStyle(document.getElementById('cwTx')).lineHeight)")
    check(h6 > h1 and abs(h6 - (6 * lh + 25)) < 8 and pg.evaluate("(() => { const t = document.getElementById('cwTx'); return t.scrollHeight > t.clientHeight; })()"), 'the box grows to 6 lines, then scrolls (%d → %d px)' % (h1, h6))
    check(vis(pg, '#cwEx'), 'and offers the expand button')
    bad = pg.evaluate(OVER, '#cwComp'); check(bad == [], 'nothing covers anything in the box: %s' % bad)
    shot(pg, 'box-6-lines', lang)
    pg.click('#cwEx'); pg.wait_for_timeout(500)
    check(vis(pg, '[data-testid=cw_full_editor]') and 'Line 9' in pg.input_value('.cw-fe textarea'), 'the full-screen editor opens with the words')
    pg.fill('.cw-fe textarea', 'Long message\nwith a second line'); shot(pg, 'full-editor', lang)
    back(pg); check(not vis(pg, '[data-testid=cw_full_editor]') and pg.input_value('#cwTx') == 'Long message\nwith a second line', 'Back closes it and the words are back in the box')
    # drafts: shown in the list as “Draft: …”
    pg.fill('#cwTx', 'Half a thought'); pg.wait_for_timeout(500); back(pg, 1000)
    check(T(lang, 'Draft:', 'Rasimu:') in txt(pg, '.cw-row[data-open="d:meril"] .cw-pv') and 'Half a thought' in txt(pg, '.cw-row[data-open="d:meril"] .cw-pv'), 'the list shows “Draft: …” (%s)' % txt(pg, '.cw-row[data-open="d:meril"] .cw-pv'))
    check(pg.evaluate("getComputedStyle(document.querySelector('.cw-row[data-open=\"d:meril\"] .cw-dr')).color") in ('rgb(28, 138, 80)', 'rgb(60, 192, 122)', 'rgb(0, 86, 42)'), 'in green')
    shot(pg, 'draft-row', lang)
    open_room(pg, 'd:meril', 1200); check(pg.input_value('#cwTx') == 'Half a thought', 'and it comes back in the box')
    # paste a screenshot into the box: the photo sheet with a caption
    pg.evaluate("""(async () => { const c = document.createElement('canvas'); c.width = 300; c.height = 200; const x = c.getContext('2d'); x.fillStyle = '#0A78C2'; x.fillRect(0, 0, 300, 200);
      const b = await new Promise(r => c.toBlob(r, 'image/png')); const dt = new DataTransfer(); dt.items.add(new File([b], 'shot.png', { type: 'image/png' }));
      document.getElementById('cwTx').dispatchEvent(new ClipboardEvent('paste', { clipboardData: dt, bubbles: true, cancelable: true })); })()"""); pg.wait_for_timeout(1500)
    check(vis(pg, '#cwCap'), 'a pasted screenshot opens the photo sheet'); back(pg)
    # dropped on the chat (a computer)
    pg.evaluate("""(async () => { const c = document.createElement('canvas'); c.width = 200; c.height = 200; const b = await new Promise(r => c.toBlob(r, 'image/png')); const dt = new DataTransfer(); dt.items.add(new File([b], 'drop.png', { type: 'image/png' }));
      const r = document.getElementById('vRoom'); r.dispatchEvent(new DragEvent('dragenter', { dataTransfer: dt, bubbles: true, cancelable: true })); r.dispatchEvent(new DragEvent('drop', { dataTransfer: dt, bubbles: true, cancelable: true })); })()"""); pg.wait_for_timeout(1500)
    check(vis(pg, '#cwCap'), 'a photo dropped on the chat opens the photo sheet'); back(pg)
    # double-tap a message: ❤️ by default; 👍 when chosen in Chat settings
    pg.locator('#cwm-1 .cw-tx').scroll_into_view_if_needed(); pg.wait_for_timeout(200); b = pg.locator('#cwm-1 .cw-tx').bounding_box(); pg.mouse.dblclick(b['x'] + 20, b['y'] + 8); pg.wait_for_timeout(700)
    check(any(r.get('id') == 1 and r.get('emoji') == '❤️' for r in posts(cm, 'react')), 'a double-tap reacts ❤️')
    back(pg, 900); menu(pg, 'set')
    check(vis(pg, '[data-testid=cw_settings_sheet]'), 'Chat settings opens')
    pg.click('[data-cs=dt][data-v="👍"]'); pg.wait_for_timeout(400)
    check(any(b.get('doubleTap') == '👍' and 'c' not in b for b in posts(cm, 'prefs')), 'the double-tap reaction is saved (synced prefs)')
    back(pg, 700); open_room(pg, 'd:meril', 1300)
    pg.locator('#cwm-2 .cw-tx').scroll_into_view_if_needed(); pg.wait_for_timeout(200); b = pg.locator('#cwm-2 .cw-tx').bounding_box(); pg.mouse.dblclick(b['x'] + 20, b['y'] + 8); pg.wait_for_timeout(700)
    check(any(r.get('id') == 2 and r.get('emoji') == '👍' for r in posts(cm, 'react')), 'then a double-tap reacts 👍')

def s_settings(pg, check, lang, cm):
    tabbar(pg, 'vChat', 1400); menu(pg, 'set')
    bad = pg.evaluate(BROKEN, '.tsh .cw-cs'); check(bad == [], 'no word breaks in Chat settings: %s' % bad)
    bad = pg.evaluate(OVER, '.tsh .cw-cs .row2, .tsh .cw-cs .seg, .tsh .cw-cs .cw-qe'); check(bad == [], 'nothing covers anything: %s' % bad)
    bad = pg.evaluate(CLIP, '.tsh .cw-cs button'); check(bad == [], 'no button cut off: %s' % bad)
    shot(pg, 'settings', lang)
    # quick replies: edit one, add one, at most 8
    pg.fill('[data-qe="0"]', 'Niko barabarani'); pg.dispatch_event('[data-qe="0"]', 'change'); pg.wait_for_timeout(300)
    q = [b for b in posts(cm, 'prefs') if 'quick' in b]
    check(q and q[-1]['quick'][0] == 'Niko barabarani' and len(q[-1]['quick']) == 6, 'editing a quick reply saves the list (%s)' % (q[-1] if q else None))
    for i in range(2): pg.click('[data-cs=qadd]'); pg.wait_for_timeout(150); pg.fill('[data-qe="%d"]' % (6 + i), 'Extra %d' % i); pg.dispatch_event('[data-qe="%d"]' % (6 + i), 'change'); pg.wait_for_timeout(250)
    q = [b for b in posts(cm, 'prefs') if 'quick' in b]
    check(len(q[-1]['quick']) == 8 and pg.locator('[data-cs=qadd]').count() == 0, 'up to 8 quick replies (%d)' % len(q[-1]['quick']))
    # Do-Not-Disturb hours, mentions break through
    pg.click('[data-cs=dnd]'); pg.wait_for_timeout(300)
    d = [b for b in posts(cm, 'prefs') if 'dnd' in b]
    check(d and d[-1]['dnd']['on'] is True and d[-1]['dnd']['from'] == '21:00' and d[-1]['dnd']['to'] == '06:30' and d[-1]['dnd']['tz'] == 180, 'Do-Not-Disturb 21:00–06:30 goes to the server with the time zone (%s)' % (d[-1] if d else None))
    pg.click('[data-cs=dndm]'); pg.wait_for_timeout(300)
    check([b for b in posts(cm, 'prefs') if 'dnd' in b][-1]['dnd']['mentions'] is True, '“@mentions still come through” is saved')
    # the data saver: Always; then a photo is a small preview with “Tap to load”
    pg.click('[data-cs=ds][data-v=always]'); pg.wait_for_timeout(300)
    check(pg.evaluate("localStorage.getItem('rfChatSaver')") == 'always', 'Data saver: Always')
    check(re.search(r'\d', txt(pg, '[data-testid=cw_data_used]') or ''), 'it shows the data used by chat this month (%s)' % txt(pg, '[data-testid=cw_data_used]'))
    shot(pg, 'settings-dnd', lang)
    back(pg, 700); open_room(pg, 'g:kitale', 1500)
    check(pg.locator('#cwMsgs [data-testid=cw_full]').count() == 1 and pg.locator('#cwMsgs img.lo[src*="img4t"]').count() == 1, 'with the data saver on a photo shows its small preview and “Tap to load”')
    shot(pg, 'data-saver', lang)
    pg.click('#cwMsgs [data-testid=cw_full]'); pg.wait_for_timeout(600)
    check(pg.locator('#cwMsgs [data-testid=cw_full]').count() == 0 and pg.locator('#cwMsgs img[src*="img4?"]').count() == 1, 'a tap loads the full photo')
    # the server's other phones change a setting: it arrives over the socket
    cm.push({'t': 'prefs', 'prefs': {'quick': ['Ndiyo', 'Hapana'], 'dnd': {'on': False, 'from': '21:00', 'to': '06:30', 'mentions': False, 'tz': 180}, 'doubleTap': '❤️'}}); pg.wait_for_timeout(300)
    pg.click('#cwTx'); pg.wait_for_timeout(300)
    check(pg.evaluate("[...document.querySelectorAll('#cwQr [data-qr]')].map(b => b.textContent)") == ['Ndiyo', 'Hapana'], 'quick replies follow a change from another phone')

def s_small(pg, check, lang, cm):
    # @mentions: highlighted; “Jump to @mention” when there are unseen ones
    open_room(pg, 'p:101', 1600)
    check(pg.locator('#cwm-4 .cw-men[data-me]').count() == 1, 'my @mention is highlighted')
    pg.evaluate("document.getElementById('cwMsgs').scrollTop = 1e6"); pg.wait_for_timeout(300)
    vis0 = vis(pg, '#cwJat')
    pg.evaluate("document.getElementById('cwMsgs').scrollTop = 0"); pg.wait_for_timeout(300)
    cm.push({'t': 'msg', 'msg': {'id': 20, 'c': 'p:101', 'from': 'Grace Achieng', 'at': int(datetime.datetime.now(datetime.timezone.utc).timestamp() * 1000), 'kind': 'text', 'text': '@Reid Anderson parts are here', 'mentions': ['Reid Anderson'], 'media': []}}); pg.wait_for_timeout(500)
    check(vis(pg, '#cwJat'), 'scrolled away, an @mention arrives: “Jump to @mention” shows')
    shot(pg, 'jump-mention', lang)
    pg.click('#cwJat'); pg.wait_for_timeout(900)
    check(pg.evaluate("document.getElementById('cwm-20').classList.contains('flash')") and not vis(pg, '#cwJat'), 'a tap jumps to it and the button goes')
    # the exact time: swipe a message left; hover shows it; long-press Info for anyone's message
    pg.locator('#cwm-2 .cw-b').scroll_into_view_if_needed(); pg.wait_for_timeout(200); b = pg.locator('#cwm-2 .cw-b').bounding_box(); y = b['y'] + 20
    pg.mouse.move(b['x'] + b['width'] - 20, y); pg.mouse.down(); pg.mouse.move(b['x'] + b['width'] - 90, y, steps=8); pg.wait_for_timeout(150)
    check(vis(pg, '#cwm-2 .cw-xt') and re.search(r'\d\d:\d\d', txt(pg, '#cwm-2 .cw-xt')), 'a swipe left shows its exact time (%s)' % txt(pg, '#cwm-2 .cw-xt'))
    shot(pg, 'swipe-time', lang)
    pg.mouse.up(); pg.wait_for_timeout(400)
    check(pg.get_attribute('#cwm-2 .cw-b', 'data-at'), 'hovering on a computer shows the same')
    longpress(pg, '#cwm-2 .cw-tx'); pg.click('.bsh [data-mk=info]'); pg.wait_for_timeout(600)
    check(vis(pg, '[data-testid=cw_exact]'), 'Info on someone else’s message shows when it was sent'); back(pg)
    # empty states: no search results, no starred, no archived, a new chat
    back(pg, 900); search(pg, 'zzqq'); pg.wait_for_timeout(1000)
    check(vis(pg, '[data-testid=cw_empty_search]'), 'no search results: a picture and “Clear the search”')
    shot(pg, 'empty-search', lang)
    pg.click('[data-testid=cw_empty_search] [data-es]'); pg.wait_for_timeout(400); check(pg.input_value('#cwQ') == '', 'its one action clears the search')
    cm.chats['d:grace']['archived'] = False; back(pg, 700); refresh(pg, 1400)
    cm.starred = []; menu(pg, 'star', 900)
    check(vis(pg, '[data-testid=cw_empty_star]'), 'no starred messages: a picture and one action'); shot(pg, 'empty-starred', lang); back(pg)
    pg.click('#cwNew'); pg.wait_for_timeout(800); pg.fill('#cwNq', 'Jessica'); pg.wait_for_timeout(200); pg.click('.tsh [data-dm="Jessica Anderson"]'); pg.wait_for_timeout(1500)
    check(vis(pg, '[data-testid=cw_empty_hello]') and T(lang, 'Say hello to Jessica', 'Msalimie Jessica') in txt(pg, '[data-testid=cw_empty_hello]'), 'a new chat: “Say hello to Jessica 👋”')
    shot(pg, 'empty-hello', lang)
    pg.click('[data-testid=cw_empty_hello] [data-es]'); pg.wait_for_timeout(900)
    check(posts(cm, 'send') and posts(cm, 'send')[-1]['text'] == '👋', 'its button waves')

def s_pins(pg, check, lang, cm):
    open_room(pg, 'd:meril', 1500)
    longpress(pg, '#cwm-1 .cw-tx'); pg.click('.bsh [data-mk=pin]'); pg.wait_for_timeout(800)
    check(any(b == {'c': 'd:meril', 'id': 1, 'on': True} for b in posts(cm, 'pin')), 'Pin goes to the server')
    check(vis(pg, '[data-testid=cw_pinbar]') and 'Habari Reid' in txt(pg, '[data-testid=cw_pinbar]'), 'the slim bar shows the pinned message')
    longpress(pg, '#cwm-9 .cw-doc'); pg.click('.bsh [data-mk=pin]'); pg.wait_for_timeout(800)
    check(pg.locator('#cwPinB .ln i').count() == 2, 'two pins: the bar shows which one')
    bad = pg.evaluate(OVER, '#cwPinB'); check(bad == [], 'nothing covers anything in the bar: %s' % bad)
    shot(pg, 'pins', lang)
    pg.evaluate("document.getElementById('cwMsgs').scrollTop = 1e6"); pg.click('#cwPinB'); pg.wait_for_timeout(700)
    check(pg.evaluate("document.getElementById('cwm-9').classList.contains('flash')"), 'a tap jumps to the newest pin')
    pg.click('#cwPinB'); pg.wait_for_timeout(700)
    check(pg.evaluate("document.getElementById('cwm-1').classList.contains('flash')"), 'the next tap cycles to the next pin')
    cm.push({'t': 'msg', 'msg': {'id': 30, 'c': 'd:meril', 'from': '', 'kind': 'sys', 'text': 'Reid pinned a message', 'sys': {'type': 'pinned', 'by': 'Reid Anderson', 'id': 9}, 'at': int(datetime.datetime.now(datetime.timezone.utc).timestamp() * 1000), 'media': []}}); pg.wait_for_timeout(400)
    check(T(lang, 'Reid pinned a message', 'Reid amebandika ujumbe') in txt(pg, '#cwMsgs .cw-sys.t-pinned'), 'the system line “Reid pinned a message”')
    longpress(pg, '#cwm-1 .cw-tx'); pg.click('.bsh [data-mk=pin]'); pg.wait_for_timeout(700)
    check(any(b == {'c': 'd:meril', 'id': 1, 'on': False} for b in posts(cm, 'pin')) and pg.locator('#cwPinB .ln i').count() == 1, 'Unpin takes it off the bar')
    # Announcements: only the office posts there
    back(pg, 900); open_room(pg, 't:news', 1400)
    check(vis(pg, '.cw-only') and not vis(pg, '#cwTx'), 'Announcements: only the office can post (no box for me)')
    check(vis(pg, '[data-testid=cw_empty_hello]'), 'an empty Announcements chat says what goes there')
    shot(pg, 'announcements', lang)

def s_trip(pg, check, lang, cm):
    itin(pg, cm)
    tabbar(pg, 'vChat', 1300)
    pg.evaluate("() => { window.shareToChat('it_1'); }"); pg.wait_for_timeout(1000)
    check(vis(pg, '[data-testid=cw_share_pick]'), 'Share to chat opens a chat picker')
    check(pg.locator('[data-testid=cw_share_pick] [data-shc="t:news"]').count() == 0, 'chats I can’t post in aren’t offered')
    bad = pg.evaluate(BROKEN, '[data-testid=cw_share_pick]'); check(bad == [], 'no word breaks in the picker: %s' % bad)
    shot(pg, 'trip-picker', lang)
    pg.click('[data-shc="t:ke"]', timeout=5000); pg.wait_for_timeout(1200)
    s = posts(cm, 'send'); check(s and s[-1]['kind'] == 'trip' and s[-1]['trip'] == {'id': 'it_1'}, 'it sends kind “trip” with the trip id (%s)' % (s[-1] if s else None))
    check(vis(pg, '[data-testid=cw_snack]'), 'a snackbar offers to open the chat')
    pg.click('[data-testid=cw_snack] button', timeout=5000); pg.wait_for_timeout(1500)
    check(h(pg) == '#chat/t:ke' and pg.locator('#cwMsgs .cw-trip').count() == 1, 'Open shows the trip card in the chat (%s)' % h(pg))
    check(T(lang, 'Trip: ', 'Safari: ') in txt(pg, '.cw-row[data-open="t:ke"] .cw-pv') if vis(pg, '.cw-row[data-open="t:ke"]') else True, '')
    shot(pg, 'trip-card', lang)
    # a card over the socket is asked for fresh
    cm.push({'t': 'msg', 'msg': {'id': 99, 'c': 't:ke', 'from': 'Meril Kiprop', 'kind': 'trip', 'trip': {'id': 'it_1', 'title': 'Old title'}, 'at': int(datetime.datetime.now(datetime.timezone.utc).timestamp() * 1000), 'media': []}}); pg.wait_for_timeout(900)
    check(any(p == 'trip' and q.get('id') == 'it_1' for p, q in cm.gets) and 'Kitale repairs run' in txt(pg, '#cwm-99 .cw-trip'), 'a trip card that came over the socket is refreshed')
    # the trips sheet's own button
    check(pg.evaluate("typeof window.rfChatShare") == 'function', 'the trips code finds rfChatShare')

def s_desktop(pg, check, lang, cm):
    for w in (1280, 1440):
        pg.set_viewport_size({'width': w, 'height': 860}); pg.goto(BASE + '/pin#chat'); pg.reload(); pg.wait_for_timeout(1800)
        check(pg.evaluate("document.body.classList.contains('cw2')") and vis(pg, '[data-testid=cw_pane_none]'), '%d: two panes, “Choose a chat” on the right' % w)
        lw = pg.evaluate("Math.round(document.getElementById('vChat').getBoundingClientRect().width)"); check(lw == 360, '%d: the list is 360 px wide (%s)' % (w, lw))
        shot(pg, 'desktop-%d-list' % w, lang)
        pg.click('.cw-row[data-open="d:meril"]'); pg.wait_for_timeout(1500)
        check(h(pg) == '#chat/d:meril' and vis(pg, '#cwList') and vis(pg, '#cwMsgs') and vis(pg, '#cwRh'), '%d: the chat opens on the right, the list stays (%s)' % (w, h(pg)))
        r = pg.evaluate("(() => { const a = document.getElementById('vChat').getBoundingClientRect(), b = document.getElementById('vRoom').getBoundingClientRect(); return [a.right, b.left, b.right <= innerWidth]; })()")
        check(abs(r[0] - r[1]) < 2 and r[2], '%d: side by side, nothing off screen (%s)' % (w, r))
        check(vis(pg, '#tabs'), '%d: the bottom bar stays' % w)
        bad = pg.evaluate(OVER, ROOMSEL + ', #cwList .cw-row'); check(bad == [], '%d: nothing covers anything: %s' % (w, bad))
        bad = pg.evaluate(BROKEN, ROOMTXT + ', #vChat'); check(bad == [], '%d: no word breaks: %s' % (w, bad))
        pg.mouse.move(2, 300); shot(pg, 'desktop-%d-room' % w, lang)
    # keyboard: Alt+↓ the next chat, Ctrl+K the search, arrows between messages, Esc back to the list
    ids = pg.evaluate("[...document.querySelectorAll('#cwList .cw-row[data-open]')].map(r => r.dataset.open)")
    pg.focus('#cwTx'); pg.keyboard.press('Alt+ArrowDown'); pg.wait_for_timeout(1300)
    nxt = ids[ids.index('d:meril') + 1]; check(h(pg) == '#chat/' + nxt, 'Alt+↓ opens the next chat (%s)' % h(pg))
    pg.keyboard.press('Alt+ArrowUp'); pg.wait_for_timeout(1300); check(h(pg) == '#chat/d:meril', 'Alt+↑ the one before (%s)' % h(pg))
    pg.keyboard.press('Control+k'); pg.wait_for_timeout(300); check(pg.evaluate("document.activeElement.id") == 'cwQ', 'Ctrl+K jumps to the search')
    pg.focus('#cwTx'); pg.keyboard.type('Line one'); pg.keyboard.press('Shift+Enter'); pg.keyboard.type('line two'); check(pg.input_value('#cwTx') == 'Line one\nline two', 'Shift+Enter makes a new line')
    n = len(posts(cm, 'send')); pg.keyboard.press('Enter'); pg.wait_for_timeout(900); check(len(posts(cm, 'send')) == n + 1 and posts(cm, 'send')[-1]['text'] == 'Line one\nline two', 'Enter sends')
    pg.focus('#cwTx'); pg.keyboard.press('ArrowUp'); pg.wait_for_timeout(100)
    check(pg.evaluate("document.activeElement.classList.contains('cw-m')"), '↑ from an empty box moves to the last message')
    lab = pg.evaluate("document.activeElement.getAttribute('aria-label')")
    check(lab and re.match(r'^(Meril Kiprop|You|Wewe), \d\d:\d\d, ', lab), 'a screen reader hears “Meril Kiprop, 10:42, …” (%s)' % lab)
    pg.keyboard.press('ArrowUp'); check(pg.evaluate("document.activeElement.classList.contains('cw-m')"), '↑ again: the one before')
    pg.keyboard.press('Enter'); pg.wait_for_timeout(500); check(vis(pg, '.bsh.cw-menu'), 'Enter opens its menu')
    pg.keyboard.press('Escape'); pg.wait_for_timeout(400); check(not vis(pg, '.bsh.cw-menu'), 'Esc closes the menu')
    pg.focus('#cwTx'); pg.keyboard.press('Escape'); pg.wait_for_timeout(1000)
    check(h(pg) == '#chat' and pg.evaluate("document.getElementById('vRoom').classList.contains('empty')"), 'Esc in the box closes the chat (%s)' % h(pg))
    # reload on a chat; Back
    pg.click('.cw-row[data-open="p:101"]'); pg.wait_for_timeout(1300); pg.reload(); pg.wait_for_timeout(2500)
    check(h(pg) == '#chat/p:101' and vis(pg, '#cwList') and pg.locator('#cwMsgs .cw-m').count() > 3, 'a reload keeps both panes on the chat (%s)' % h(pg))
    back(pg, 1200); check(h(pg) == '#chat' and view(pg) == 'vChat', 'Back goes to the list (%s)' % h(pg))
    # narrow again: one pane
    pg.set_viewport_size({'width': 390, 'height': 800}); pg.wait_for_timeout(400)
    check(not pg.evaluate("document.body.classList.contains('cw2')"), 'a narrow window falls back to one pane')

def s_a11y(pg, check, lang, cm):
    # Larger text and the sun theme: still fits, nothing covers anything
    for size in ('l', 'xl'):
        pg.evaluate("s => localStorage.setItem('rfPinSize', s)", size); pg.goto(BASE + '/pin#chat/d:meril'); pg.reload(); pg.wait_for_timeout(2000)
        check(pg.evaluate(NOOVER), '%s text: nothing wider than the screen' % size)
        bad = pg.evaluate(OVER, ROOMSEL); check(bad == [], '%s text: nothing covers anything: %s' % (size, bad))
        bad = pg.evaluate(BROKEN, ROOMTXT); check(bad == [], '%s text: no word breaks: %s' % (size, bad))
        shot(pg, 'text-%s' % size, lang)
    # 200 %: the browser zoomed in
    pg.evaluate("localStorage.removeItem('rfPinSize')"); pg.set_viewport_size({'width': 195, 'height': 400}); pg.goto(BASE + '/pin#chat/d:meril'); pg.reload(); pg.wait_for_timeout(2000)
    check(pg.evaluate(NOOVER), '200 %: nothing wider than the screen')
    bad = pg.evaluate(OVER, '#cwComp, #cwHd'); check(bad == [], '200 %%: the box and the header still fit: %s' % bad)
    pg.set_viewport_size({'width': 390, 'height': 800})
    labs = pg.evaluate("[...document.querySelectorAll('#cwMsgs .cw-m.me')].map(m => m.getAttribute('aria-label'))")
    check(labs and all(re.match('^' + T(lang, 'You', 'Wewe') + r', \d\d:\d\d, ', l) for l in labs) and any(l.endswith(T(lang, 'read', 'imesomwa')) for l in labs), 'my bubbles read “You, 10:42, …, read” (%s)' % labs[:1])

def s_shots(pg, check, lang, cm):
    # every theme at 360 and 390: the list, a chat, chat info
    for w in (360, 390):
        pg.set_viewport_size({'width': w, 'height': 800})
        for theme in ('', 'dark', 'sun'):
            pg.evaluate("t => { if (t) localStorage.setItem('rfPinTheme', t); else localStorage.removeItem('rfPinTheme'); }", theme); pg.goto(BASE + '/pin#chat'); pg.reload(); pg.wait_for_timeout(1800)
            check(pg.evaluate("document.documentElement.getAttribute('data-theme') || ''") == theme, '%d %s: the theme is on' % (w, theme or 'light'))
            tn = theme or 'light'
            check(pg.evaluate(NOOVER) and pg.evaluate(BAR) == [], '%d %s: the list fits' % (w, tn))
            bad = pg.evaluate(BROKEN, '#vChat, #tabs nav'); check(bad == [], '%d %s: no word breaks in the list: %s' % (w, tn, bad))
            shot(pg, 'list-%d-%s' % (w, tn), lang)
            pg.click('.cw-row[data-open="d:meril"]'); pg.wait_for_timeout(1500)
            check(pg.evaluate(NOOVER), '%d %s: a chat fits' % (w, tn))
            bad = pg.evaluate(BROKEN, '#cwHd, #cwComp, .cw-day, .cw-mt, .cw-la, .cw-ca, .cw-dn, .cw-vo'); check(bad == [], '%d %s: no word breaks in the chat: %s' % (w, tn, bad))
            shot(pg, 'room-%d-%s' % (w, tn), lang)
            if w == 390:
                back(pg, 900); pg.click('.cw-row[data-open="p:101"]'); pg.wait_for_timeout(1400); shot(pg, 'project-thread-%d-%s' % (w, tn), lang)
                pg.click('#cwHd'); pg.wait_for_timeout(900); shot(pg, 'info-%d-%s' % (w, tn), lang)
            back(pg, 900); pg.evaluate("document.querySelectorAll('.tsh').forEach(x => x.close && x.close())"); pg.wait_for_timeout(300)
            if view(pg) != 'vChat': back(pg, 900)
            pg.click('.cw-row[data-open="t:all"]'); pg.wait_for_timeout(1500)
            bad = pg.evaluate(BROKEN, ROOMTXT); check(bad == [], '%d %s: no word breaks in the team chat: %s' % (w, tn, bad))
            bad = pg.evaluate(OVER, ROOMSEL); check(bad == [], '%d %s: nothing covers anything in the team chat: %s' % (w, tn, bad))
            shot(pg, 'team-%d-%s' % (w, tn), lang)

def s_shots_desk(pg, check, lang, cm):
    # two panes at 1280 and 1440 in every theme
    for w in (1280, 1440):
        pg.set_viewport_size({'width': w, 'height': 860})
        for theme in ('', 'dark', 'sun'):
            tn = theme or 'light'
            pg.evaluate("t => { if (t) localStorage.setItem('rfPinTheme', t); else localStorage.removeItem('rfPinTheme'); }", theme); pg.goto(BASE + '/pin#chat/t:all'); pg.reload(); pg.wait_for_timeout(2200)
            check(pg.evaluate(NOOVER), '%d %s: fits' % (w, tn))
            bad = pg.evaluate(BROKEN, ROOMTXT + ', #vChat'); check(bad == [], '%d %s: no word breaks: %s' % (w, tn, bad))
            bad = pg.evaluate(OVER, ROOMSEL + ', #cwList .cw-row'); check(bad == [], '%d %s: nothing covers anything: %s' % (w, tn, bad))
            pg.mouse.move(2, 300); shot(pg, 'desk-%d-%s' % (w, tn), lang)

SEQ = [('Bottom bar: Chat instead of Me, Me on the avatar, everything fits at 360 px', s_bar, {'w': 360}),
       ('Chat list: pinned, unread, @, presence, Archived, search, swipe, row menu', s_list, {}),
       ('One chat: kinds of message, quote jump, typing, send with ticks, Back', s_room, {}),
       ('Long-press: react, reply by swipe, edit, star, info, delete, forward, turn into a job', s_actions, {}),
       ('Media: attach, location, live location, contact, photo with caption, viewer, voice playback', s_media, {}),
       ('Voice notes: hold to record, slide left to cancel, slide up to lock', s_voice, {}),
       ('Offline: the clock, survives a reload, sends once when back', s_offline, {}),
       ('Groups: info, only admins, invite link, admins, new group, @mentions', s_group, {}),
       ('Entry points: contact sheet Message, project page Chat', s_entry, {}),
       ('Refresh, reload, start over, join link', s_nav, {}),
       ('Chat not switched on: a clear note, nothing else breaks', s_off, {'off': True}),
       ('Look: tails, emoji, flags and photos, project and trip cards, system lines, Seen by', s_look, {}),
       ('Feel: slide in, pop, ticks, typing dots, older skeleton, scroll kept, “↓ new”, unread line', s_feel, {}),
       ('Composer: quick replies, 6 lines and the big editor, Enter, drafts, paste and drop, double-tap', s_composer, {}),
       ('Chat settings: quick replies (8), Do-Not-Disturb, data saver, synced prefs', s_settings, {}),
       ('Small things: @mention jump, exact time, Info, empty states', s_small, {}),
       ('Pinned messages and Announcements', s_pins, {}),
       ('Trip cards: Share to chat, the card, a fresh card', s_trip, {}),
       ('A computer: two panes at 1280 and 1440, keyboard, reload, Back', s_desktop, {'w': 1280}),
       ('Accessibility: larger text, 200 %, what a screen reader hears', s_a11y, {}),
       ('Screenshots: 360 and 390 px, light, dark and sun', s_shots, {}),
       ('Screenshots: two panes at 1280 and 1440 px, light, dark and sun', s_shots_desk, {'w': 1280})]
with sync_playwright() as p:
    br = p.chromium.launch(args=['--use-fake-ui-for-media-stream', '--use-fake-device-for-media-stream', '--autoplay-policy=no-user-gesture-required'])
    for lang in LANGS:
        for name, fn, kw in SEQ:
            kw = dict(kw); off = kw.pop('off', False)
            seq(name, lang, fn, br, ChatMock(off=off), **kw)
    br.close()
bad = [r for r in RESULTS if not r[2]]
print('\n%d of %d sequences pass' % (len(RESULTS) - len(bad), len(RESULTS)))
sys.exit(1 if bad else 0)
