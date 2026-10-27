# Chat 2, one chat (design/chat-2026-10/room.png, delete.png): the header (search, ⋯), the project strip, bubbles, quick
# replies, Add to project photos (+ the story), Use in a story, Translate · Tafsiri, Show text, Seen by from the ticks,
# select + delete, Clear / Delete chat, Media, files and links, Jump to a date, sending states, photo sizes, Add people,
# and the sheets' titles. Every new sheet and mode: Back closes it first, a reload comes back to the chat. Screenshots at
# 320 / 360 / 390 px in light, dark and sun, English and Kiswahili, and two panes at 1280 / 1440. Prints PASS / FAIL.
#   BASE=http://localhost:8892 OUT=/tmp/x python3 qa/pin/chat2-room.py [en|sw|both]     (ONLY=<part of a name> runs some)
import os, re, sys, json, datetime
from playwright.sync_api import sync_playwright
HERE = os.path.dirname(os.path.abspath(__file__)); sys.path.insert(0, HERE)
from chat_mock import ChatMock, png, ME
BASE = os.environ.get('BASE', 'http://localhost:8892')
OUT = os.environ.get('OUT', os.path.join(HERE, 'chat2-room')); os.makedirs(OUT, exist_ok=True)
TILE = open(os.path.join(HERE, 'tile.png'), 'rb').read()
LANGS = {'en': ['en'], 'sw': ['sw'], 'both': ['en', 'sw']}[sys.argv[1] if len(sys.argv) > 1 else 'both']
ONLY = os.environ.get('ONLY', '')

now = datetime.datetime.now(datetime.timezone.utc)
iso = lambda h: (now - datetime.timedelta(hours=h)).strftime('%Y-%m-%dT%H:%M:%SZ')
def pj(i, t, area, ll, ty='Borehole well', ck='ke', c='Kenya'):
    d = {'id': i, 'title': t, 'country': c, 'ck': ck, 'area': area, 'type': ty, 'status': 'Completed'}
    if ll: d['gps'] = ll
    return d
P = [dict(pj('101', 'Kapsaret Borehole', 'Eldoret', [0.47, 35.25]), photo='ph101'), pj('102', 'Chepkongi Dispensary', 'Eldoret', [0.5143, 35.2698], 'Health'),
     pj('103', 'Turbo Spring', 'Turbo', [0.63, 35.05], 'Water collection'), pj('104', 'Nzoia Bridge Primary School', 'Bungoma', [0.58, 34.62], 'School')]
VISITS = {'101': {'at': (now - datetime.timedelta(days=1)).strftime('%Y-%m-%dT%H:%M:%S.000Z'), 'by': 'Meril Kiprop', 'state': 'working'}}
TEAM = [{'name': 'Reid Anderson', 'home': 'ke'}, {'name': 'Meril Kiprop', 'home': 'ke'}, {'name': 'Grace Achieng', 'home': 'ke'}, {'name': 'Bertin Mwangi', 'home': 'tz'},
        {'name': 'Jessica Anderson', 'home': 'ug'}, {'name': 'Otieno Ouma', 'home': 'ke'}, {'name': 'Faith Nakato', 'home': 'ug'}, {'name': 'Neema Mushi', 'home': 'tz'}, {'name': 'Chikondi Banda', 'home': 'mw'}]
def snap(admin=True):
    return {'ok': True, 'who': ME, 'admin': admin, 'reviewOpen': 0, 'projects': P, 'repairs': {}, 'visits': VISITS, 'tests': {}, 'jobs': [], 'assigned': [],
            'confirmed': {}, 'me': {'kind': 'app', 'nick': '', 'phone': '', 'home': 'ke'}, 'approveAbove': 50000}

class World:
    def __init__(self, admin=True): self.admin = admin; self.pin_posts = []; self.photo_posts = []; self.story_posts = []
def install(pg, W):
    def pin_api(route, req):
        u = req.url
        if req.method != 'GET':
            b = json.loads(req.post_data or '{}'); W.pin_posts.append(b)
            return route.fulfill(json={'ok': True, 'id': b.get('id'), 'updateId': '9001'})
        if 'activity=' in u: return route.fulfill(json={'ok': True, 'activity': []})
        if 'team=' in u: return route.fulfill(json={'ok': True, 'team': TEAM})
        if 'history=' in u: return route.fulfill(json={'ok': True, 'notes': []})
        if re.search(r'[?&]id=\d+', u): return route.fulfill(json={'ok': True, 'last': None, 'canUndo': False, 'visits': [], 'costs': {'sum': 0, 'n': 0, 'last': []}})
        return route.fulfill(json=snap(W.admin))
    def admin_api(route, req):
        if req.method != 'GET': return route.fulfill(json={'ok': True})
        if 'view=team' in req.url: return route.fulfill(json={'ok': True, 'team': [{'name': t['name'], 'home': t['home'], 'source': 'app'} for t in TEAM], 'mail': {'ready': True, 'to': []}, 'cron': True})
        return route.fulfill(json={'ok': True, 'items': [], 'rows': [], 'people': [], 'visits': [], 'donors': []})
    def photo_api(route, req):
        if req.method == 'POST': W.photo_posts.append(len(req.post_data_buffer or b'')); return route.fulfill(json={'ok': True, 'id': 'asset1'})
        return route.fulfill(body=TILE, content_type='image/png')
    STORY = {'id': '101', 'status': 'collecting', 'consent': 'not-yet', 'answers': {q: '' for q in ['q1', 'q2', 'q3', 'q4', 'q5', 'q6']}, 'quote': '', 'quoteBy': '', 'credit': '', 'text': '',
             'photos': {'before': [], 'progress': [], 'after': []}, 'missing': [], 'canApprove': False, 'prompts': {}}
    def story_api(route, req):
        if req.method == 'POST':
            W.story_posts.append(req.headers.get('content-type', '')[:30])
            return route.fulfill(json={'ok': True, 'story': STORY})
        if 'asset=' in req.url: return route.fulfill(body=TILE, content_type='image/png')
        return route.fulfill(json={'ok': True, 'story': STORY})
    pg.route('**/api/pin-auth/me', lambda r, q: r.fulfill(json={'available': False}))
    pg.route(re.compile(r'/api/pin(\?.*)?$'), pin_api); pg.route(re.compile(r'/api/admin(\?.*)?$'), admin_api)
    pg.route(re.compile(r'/api/pin-photo(\?.*)?$'), photo_api); pg.route(re.compile(r'/api/story(\?.*)?$'), story_api)
    pg.route(re.compile(r'/api/(place|sms-admin|cmap|road-reports)(\?.*)?$'), lambda r, q: r.fulfill(json={'ok': True, 'places': [], 'reports': []}))
    pg.route('**/api/photo/**', lambda r, q: r.fulfill(body=TILE, content_type='image/png'))
    pg.route(re.compile(r'https://(www\.google\.com/s2|fonts\.googleapis\.com|translate\.google\.com)/.*'), lambda r, q: r.abort())

# the project chat as in room.png: a quote yesterday, my answer, a photo with a caption, a message in Kiswahili, a voice note
def seed(cm):
    t = lambda mins: int((now - datetime.timedelta(minutes=mins)).timestamp() * 1000)
    D = 24 * 60
    def m(i, frm, mins, kind='text', text='', **kw):
        d = {'id': i, 'c': 'p:101', 'cid': 'srvp%d' % i, 'from': frm, 'at': t(mins), 'kind': kind, 'text': text, 'media': [], 'reply': None, 'fwd': False, 'mentions': [], 'loc': None, 'live': None,
             'contact': None, 'reacts': {}, 'edited': None, 'deleted': False, 'sys': None, 'status': 'read' if frm == ME else None}
        d.update(kw); return d
    WAVE = [20, 35, 60, 80, 55, 30, 45, 70, 90, 65, 40, 25, 50, 75, 85, 60, 35, 20, 40, 65, 80, 55, 30, 45, 70, 50, 30, 20, 35, 55, 40, 25]
    cm.media['wash'] = ('image/png', png((196, 140, 60), 600, 340))
    cm.msgs['p:101'] = [
        m(1, '', D + 120, 'sys', 'Bertin reported a repair', sys={'type': 'repair', 'project': '101', 'by': 'Bertin Mwangi'}),
        m(2, 'Bertin Mwangi', D + 60, text='Quote from Eldoret Hardware for the cylinder: KES 18,500. Can I order?'),
        m(3, ME, D + 52, text='Yes, go ahead. I’ll approve it in the app.'),
        m(4, 'Meril Kiprop', 14, 'media', 'Water flowing again 🙌 The committee chair is here.', media=[{'id': 'wash', 'type': 'photo', 'w': 600, 'h': 340, 'url': '/api/chat/media/wash?e=1&s=sig'}]),
        m(5, 'Meril Kiprop', 13, text='Mwenyekiti anasema watoto sasa wanakunywa maji safi kila siku.', reacts={'🙏': ['Grace Achieng', 'Bertin Mwangi']}),
        m(6, 'Meril Kiprop', 12, 'voice', media=[{'id': 'vo1', 'type': 'voice', 'dur': 12, 'wave': WAVE, 'url': '/api/chat/media/vo1?e=1&s=sig'}]),
    ]
    x = cm.msgs['p:101'][-1]
    cm.chats['p:101'].update(unread=0, mention=False, mentionId=0, last={'id': x['id'], 'from': x['from'], 'kind': x['kind'], 'text': '', 'at': x['at'], 'status': None, 'deleted': False, 'sys': None},
                             members=['Reid Anderson', 'Meril Kiprop', 'Bertin Mwangi', 'Grace Achieng', 'Jessica Anderson'], admins=[ME])
    # a new group with only me in it
    cm.chats['g:solo'] = dict(cm.chats['g:kitale'], id='g:solo', title='Kesses visit', members=[ME], admins=[ME], unread=0, muted=0, description='', seen=[], last=None)
    cm.msgs['g:solo'] = [{'id': 1, 'c': 'g:solo', 'cid': 's1', 'from': '', 'at': t(30), 'kind': 'sys', 'text': 'Reid made the group', 'media': [], 'reply': None, 'fwd': False, 'mentions': [], 'loc': None, 'live': None,
                          'contact': None, 'reacts': {}, 'edited': None, 'deleted': False, 'sys': {'type': 'created', 'by': ME}}]

ERRS = []
def context(br, lang, cm, W, w=390, theme='', size=''):
    ctx = br.new_context(viewport={'width': w, 'height': 844 if w < 1000 else 860}, device_scale_factor=2 if w < 1000 else 1, is_mobile=w < 1000, has_touch=w < 1000, service_workers='block', locale='en-GB', timezone_id='Africa/Nairobi',
                         geolocation={'latitude': 0.515, 'longitude': 35.27, 'accuracy': 8}, permissions=['geolocation', 'microphone'])
    ctx.route(re.compile(r'https://(tile\.openstreetmap\.org|server\.arcgisonline\.com)/.*'), lambda r, q: r.fulfill(status=200, body=TILE, headers={'Content-Type': 'image/png', 'Access-Control-Allow-Origin': '*'}))
    pg = ctx.new_page(); install(pg, W); cm.install(pg)
    pg.on('pageerror', lambda e: ERRS.append('pageerror: ' + str(e)[:200]))
    pg.on('console', lambda m: ERRS.append('console: ' + m.text[:200]) if m.type == 'error' and 'Failed to load resource' not in m.text else None)
    pg.goto(BASE + '/pin'); pg.wait_for_timeout(500)
    pg.evaluate("([l, t, z]) => { localStorage.setItem('rfPinOnb', '1'); if (l === 'sw') localStorage.setItem('rfPinLang', 'sw'); if (t) localStorage.setItem('rfPinTheme', t); if (z) localStorage.setItem('rfPinSize', z); }", [lang, theme, size])
    pg.reload(); pg.wait_for_timeout(500)
    for d in '9999': pg.click('.pk[data-k="%s"]' % d)
    pg.wait_for_timeout(1300)
    return ctx, pg

def h(pg): return pg.evaluate('location.hash')
def view(pg): return pg.evaluate("['vMap','vList','vSearch','vRoute','vProj','vNew','vMe','vReport','vOffice','vChat','vRoom','vStories'].filter(v => !document.getElementById(v).classList.contains('hidden'))[0]")
def back(pg, t=800): pg.evaluate('history.back()'); pg.wait_for_timeout(t)
def vis(pg, sel): loc = pg.locator(sel); return loc.count() > 0 and loc.first.is_visible()
def txt(pg, sel): return pg.locator(sel).first.inner_text() if pg.locator(sel).count() else ''
def shot(pg, name, lang): pg.wait_for_timeout(300); fn = os.path.join(OUT, '%s-%s.png' % (lang, name)); pg.screenshot(path=fn); return fn
def posts(cm, path): return [b for p, b in cm.posts if p == path]
def T(lang, en, sw): return sw if lang == 'sw' else en
def room(pg, cid, t=1400):
    pg.click('#tabs button[data-v=vChat]'); pg.wait_for_timeout(1200)
    pg.click('.cw-row[data-open="%s"]' % cid); pg.wait_for_timeout(t)
def longpress(pg, sel):
    b = pg.locator(sel).first; b.scroll_into_view_if_needed(); box = b.bounding_box()
    pg.mouse.move(box['x'] + box['width'] / 2, box['y'] + box['height'] / 2); pg.mouse.down(); pg.wait_for_timeout(750); pg.mouse.up(); pg.wait_for_timeout(500)
def tap(pg, sel, t=500): pg.locator(sel).first.click(); pg.wait_for_timeout(t)
def reload(pg, t=2200): pg.reload(); pg.wait_for_timeout(600); [pg.click('.pk[data-k="%s"]' % d) for d in '9999'] if vis(pg, '.pk[data-k="9"]') else None; pg.wait_for_timeout(t)

NOOVER = "(() => document.scrollingElement.scrollWidth <= innerWidth + 1)()"
# anything inside these that sticks out of the screen sideways
OFF = """(sel => { const bad = []; document.querySelectorAll(sel).forEach(el => { if (!el.getClientRects().length) return; const r = el.getBoundingClientRect(); if (r.width && (r.left < -1 || r.right > innerWidth + 1)) bad.push((el.className || el.tagName) + ' ' + Math.round(r.left) + '…' + Math.round(r.right)); }); return bad.slice(0, 6); })"""
# a word split across two lines
BROKEN = """(sel => { const bad = []; document.querySelectorAll(sel).forEach(el => { if (!el.getClientRects().length) return; const w = document.createTreeWalker(el, NodeFilter.SHOW_TEXT); let n;
  while ((n = w.nextNode())) { const s = n.data; const re = /\\S+/g; let m; while ((m = re.exec(s))) { if (m[0].length < 2) continue; const r = document.createRange(); r.setStart(n, m.index); r.setEnd(n, m.index + m[0].length);
    const rects = [...r.getClientRects()].filter(x => x.width > 0.5); if (rects.length > 1 && Math.abs(rects[0].top - rects[rects.length - 1].top) > 4) bad.push(m[0]); } } }); return bad.slice(0, 6); })"""
# text wider than its own box (cut off), for elements that should never ellipsize
CLIP = """(sel => [...document.querySelectorAll(sel)].filter(b => b.getClientRects().length && b.scrollWidth > b.clientWidth + 1 && getComputedStyle(b).overflowX !== 'auto' && getComputedStyle(b).textOverflow !== 'ellipsis').map(b => (b.className || b.tagName) + ': ' + b.textContent.trim().slice(0, 30)).slice(0, 6))"""
# two pieces of text on top of each other
OVER = """(sel => { const bad = []; const Q = 'b, small, time, .tag, .btn, .cw-tx, .cw-mt, .cw-sn, .cw-rc, .cw-tlk, .cw-tlx, .cw-addp, .cw-snt, .cw-pst, .cw-psx, .cw-pso, .cw-qr button, .cw-hdb, .cw-fn, input, .cw-vd, .cw-wv, .cw-vs, .cw-vp';
  document.querySelectorAll(sel).forEach(box => { if (!box.getClientRects().length) return; const k = [...box.querySelectorAll(Q)].filter(e => e.getClientRects().length && getComputedStyle(e).visibility !== 'hidden' && !e.closest('.cw-foot'));
    for (let i = 0; i < k.length; i++) for (let j = i + 1; j < k.length; j++) { const a = k[i], b = k[j]; if (a.contains(b) || b.contains(a)) continue; const r = a.getBoundingClientRect(), s = b.getBoundingClientRect();
      const w = Math.min(r.right, s.right) - Math.max(r.left, s.left), hh = Math.min(r.bottom, s.bottom) - Math.max(r.top, s.top); if (w > 3 && hh > 3) bad.push((a.className || a.tagName) + ' × ' + (b.className || b.tagName) + ' “' + (a.textContent || '').trim().slice(0, 18) + '”'); } }); return bad.slice(0, 6); })"""
ROOM = '#cwHd, #cwHda, #cwRh, #cwPs, #cwFind, #cwSel, #cwMsgs .cw-m, #cwMsgs .cw-day, #cwMsgs .cw-sys, #cwComp'
SHEET = '.tsh-p, .bsh-p'

RESULTS = []
def seq(name, lang, fn, br, admin=True, **kw):
    if ONLY and ONLY not in name: return
    cm = ChatMock(); seed(cm); W = World(admin); notes = []
    def check(c, what): (None if c else notes.append(what))
    del ERRS[:]
    ctx, pg = context(br, lang, cm, W, **kw)
    try: fn(pg, check, lang, cm, W)
    except Exception as e: notes.append('error: ' + str(e).split('\n')[0][:220])
    if notes:
        try: pg.screenshot(path=os.path.join(OUT, 'FAIL-%s-%s.png' % (lang, fn.__name__)))
        except Exception: pass
    errs = [e for e in ERRS if 'WebSocket' not in e]
    if errs: notes.append('console: %s' % errs[:3])
    ctx.close()
    RESULTS.append((lang, name, not notes, notes))
    print(('PASS ' if not notes else 'FAIL ') + '[%s] %s' % (lang, name) + ('' if not notes else ' :: ' + ' | '.join(notes)), flush=True)
def tidy(pg, check, where, sel=ROOM):
    check(pg.evaluate(NOOVER), '%s: nothing wider than the screen' % where)
    b = pg.evaluate(OFF, sel); check(b == [], '%s: nothing off the screen: %s' % (where, b))
    b = pg.evaluate(BROKEN, sel); check(b == [], '%s: no word breaks: %s' % (where, b))
    b = pg.evaluate(OVER, sel); check(b == [], '%s: nothing on top of anything: %s' % (where, b))

# ---- B1 B2 B3 B4: the room as in room.png ----
def s_look(pg, check, lang, cm, W):
    room(pg, 'p:101')
    check(h(pg) == '#chat/p:101' and view(pg) == 'vRoom', 'the project chat opens (%s)' % h(pg))
    check(vis(pg, '#cwHs') and vis(pg, '#cwHm') and vis(pg, '#hBack'), 'header: back, search and ⋯')
    check(pg.locator('.top [aria-label*="all" i], .top [data-hd=call]').count() == 0, 'no call button')
    check(T(lang, 'Kapsaret Borehole', 'Kapsaret Borehole') in txt(pg, '#cwHd') and 'Meril' in txt(pg, '#cwHd .cw-hdt small'), 'name and the members under it (%s)' % txt(pg, '#cwHd'))
    cm.push({'t': 'typing', 'c': 'p:101', 'who': 'Meril Kiprop', 'what': 'typing'}); pg.wait_for_timeout(400)
    check(T(lang, 'Meril is typing', 'Meril anaandika') in txt(pg, '#cwHd') and pg.evaluate("getComputedStyle(document.querySelector('#cwHd small')).color") in ('rgb(28, 138, 80)', 'rgb(60, 192, 122)', 'rgb(0, 86, 42)'), 'typing… in green in the header (%s)' % txt(pg, '#cwHd'))
    st = txt(pg, '#cwPs')
    check(vis(pg, '#cwPs') and T(lang, 'Working', 'Inafanya kazi') in st and T(lang, 'Visited', 'Ziara') in st and T(lang, 'Open project', 'Fungua mradi') in st, 'the project strip: status, last visit, Open project (%s)' % st)
    fs = pg.evaluate("['.cw-tx','.cw-sn','.cw-mt','.cw-day span','.cw-hdt b'].map(s => { const e = document.querySelector('#vRoom ' + s + ', #cwHd ' + s); return e ? parseFloat(getComputedStyle(e).fontSize) : 0; })")
    check(abs(fs[0] - 15.52) < 0.3 and abs(fs[1] - 12.48) < 0.3 and abs(fs[2] - 11.52) < 0.3 and abs(fs[3] - 12.48) < 0.3, 'text from the chat scale: message .97rem, name .78rem, time .72rem, day .78rem (%s)' % fs)
    w = pg.evaluate("[...document.querySelectorAll('#cwMsgs .cw-b')].map(b => b.getBoundingClientRect().width / document.getElementById('cwMsgs').clientWidth)")
    check(max(w) <= 0.81, 'bubbles at most 80%% of the width (%s)' % round(max(w), 3))
    check(pg.evaluate("getComputedStyle(document.querySelector('#cwMsgs .cw-m.them.first .cw-b')).borderTopLeftRadius") == '4px', 'the first bubble of a run has the square-ish corner')
    check(pg.locator('#cwm-3 .cw-tk.rd').count() == 1, 'my read message: blue ticks')
    check(vis(pg, '#cwm-4 [data-testid=cw_add_photos]'), 'under the photo: Add to project photos')
    check(vis(pg, '#cwm-5 [data-testid=cw_translate]') == (lang == 'en'), 'Translate · Tafsiri under the Kiswahili message (only when the app is in English)')
    check(vis(pg, '#cwm-6 [data-testid=cw_voice_text]'), 'Show text under the voice note')
    chips = pg.evaluate("[...document.querySelectorAll('#cwQr [data-qr]')].map(b => b.getBoundingClientRect().height)")
    check(chips and max(chips) <= 34, 'quick replies are small chips (%s)' % chips[:2])
    tidy(pg, check, 'the room')
    shot(pg, 'room', lang)
    pg.fill('#cwTx', 'On my'); pg.wait_for_timeout(150)
    check(not vis(pg, '#cwQr'), 'typing hides the quick replies')
    pg.fill('#cwTx', ''); pg.wait_for_timeout(150); check(vis(pg, '#cwQr'), 'an empty box shows them again')
    # tapping the header still opens chat info; the strip opens the project
    tap(pg, '#cwHd', 800); check(vis(pg, '[data-testid=cw_info]'), 'the header opens chat info'); back(pg)
    tap(pg, '#cwPs', 1200); check(h(pg) == '#p/101', 'the strip opens the project (%s)' % h(pg)); back(pg, 1200)
    check(h(pg) == '#chat/p:101', 'Back comes back to the chat (%s)' % h(pg))

# ---- B10: select + delete (delete.png) ----
def s_select(pg, check, lang, cm, W):
    room(pg, 'p:101')
    longpress(pg, '#cwm-2 .cw-tx'); tap(pg, '.bsh [data-mk=select]', 600)
    check(vis(pg, '[data-testid=cw_select_bar]') and txt(pg, '[data-testid=cw_sel_n]') == T(lang, '1 selected', '1 imechaguliwa'), 'Select: the dark bar says 1 selected (%s)' % txt(pg, '[data-testid=cw_sel_n]'))
    tap(pg, '#cwm-3 .cw-b', 400)
    check(txt(pg, '[data-testid=cw_sel_n]') == T(lang, '2 selected', '2 zimechaguliwa') and pg.locator('#cwMsgs .cw-m.sel').count() == 2, 'a tap adds another (%s)' % txt(pg, '[data-testid=cw_sel_n]'))
    sel = pg.evaluate("(() => { const a = document.getElementById('cwSel').getBoundingClientRect(), t = document.querySelector('header.top').getBoundingClientRect(); return [Math.round(a.top), Math.round(a.height), Math.round(t.height)]; })()")
    check(sel[0] == 0 and abs(sel[1] - sel[2]) <= 1, 'the bar sits exactly over the header (%s)' % sel)
    tidy(pg, check, 'selecting', '#cwSel')
    tap(pg, '#cwm-4 .cw-b', 400); tap(pg, '#cwm-4 .cw-b', 400)
    check(txt(pg, '[data-testid=cw_sel_n]') == T(lang, '2 selected', '2 zimechaguliwa'), 'a second tap takes it away again')
    shot(pg, 'select', lang)
    tap(pg, '[data-testid=cw_sel_del]', 600)
    check(vis(pg, '[data-testid=cw_del_sheet]') and T(lang, 'Delete 2 messages?', 'Futa jumbe 2?') in txt(pg, '.bsh.cw-delsh .bsh-h'), 'Delete opens the sheet: Delete 2 messages?')
    note = txt(pg, '.cw-dsn')
    check(T(lang, 'One of them is Bertin’s', 'Mmoja wao ni wa Bertin') in note, 'it says the admin power is used (%s)' % note)
    check(vis(pg, '[data-testid=cw_del_all]') and vis(pg, '[data-testid=cw_del_me]') and T(lang, 'Gone for all 5 people', 'watu wote 5') in txt(pg, '[data-testid=cw_del_all]'), 'for everyone (all 5 people) and for me')
    tidy(pg, check, 'the delete sheet', SHEET)
    b = pg.evaluate(CLIP, '.cw-dop, .cw-dcan'); check(b == [], 'nothing cut off in the sheet: %s' % b)
    shot(pg, 'delete-sheet', lang)
    back(pg); check(not vis(pg, '.bsh') and vis(pg, '[data-testid=cw_select_bar]'), 'Back closes the sheet first, still selecting')
    back(pg); check(not vis(pg, '[data-testid=cw_select_bar]') and pg.locator('#cwMsgs .cw-m.sel').count() == 0 and view(pg) == 'vRoom', 'Back again leaves selection mode, still in the chat')
    # again, and delete for everyone: one call for both
    longpress(pg, '#cwm-2 .cw-tx'); tap(pg, '.bsh [data-mk=select]', 500); tap(pg, '#cwm-3 .cw-b', 300); tap(pg, '[data-testid=cw_sel_del]', 500); tap(pg, '[data-testid=cw_del_all]', 900)
    d = posts(cm, 'delete-many'); check(d and sorted(d[-1]['ids']) == [2, 3] and d[-1]['forAll'] is True, 'delete-many goes to the server once (%s)' % (d[-1] if d else None))
    check(pg.locator('#cwm-2 .cw-dl').count() == 1 and pg.locator('#cwm-3 .cw-dl').count() == 1 and not vis(pg, '[data-testid=cw_select_bar]'), 'both say “This message was deleted”, and selection ends')
    # a field user selecting someone else's message: only "Delete for me"
    longpress(pg, '#cwm-5 .cw-tx'); tap(pg, '.bsh [data-mk=del]', 600)
    check(vis(pg, '[data-testid=cw_del_me]') and vis(pg, '[data-testid=cw_del_all]'), 'one message: the same sheet (an admin sees both)')
    back(pg)
    # forward and star from the bar
    longpress(pg, '#cwm-5 .cw-tx'); tap(pg, '.bsh [data-mk=select]', 500); tap(pg, '[data-testid=cw_sel_star]', 600)
    check(any(b.get('id') == 5 and b.get('on') for b in posts(cm, 'star')) and not vis(pg, '[data-testid=cw_select_bar]'), 'Star from the bar stars it')
    longpress(pg, '#cwm-5 .cw-tx'); tap(pg, '.bsh [data-mk=select]', 500); tap(pg, '#cwm-4 .cw-b', 300); tap(pg, '[data-testid=cw_sel_fwd]', 700)
    pg.click('.tsh [data-fw="t:ke"]'); pg.click('#cwFwGo'); pg.wait_for_timeout(1000)
    f = [x for x in posts(cm, 'send') if x.get('c') == 't:ke' and x.get('fwd')]
    check(len(f) == 2 and f[0]['kind'] == 'media' and f[1]['kind'] == 'text', 'Forward sends both, oldest first (%s)' % [x['kind'] for x in f])
    check(not vis(pg, '[data-testid=cw_select_bar]') and view(pg) == 'vRoom' and h(pg) == '#chat/p:101', 'and selection ends, still in the chat (%s)' % h(pg))
    # refresh while selecting: back in the chat, not selecting
    longpress(pg, '#cwm-5 .cw-tx'); tap(pg, '.bsh [data-mk=select]', 500); reload(pg)
    check(h(pg) == '#chat/p:101' and view(pg) == 'vRoom' and not vis(pg, '[data-testid=cw_select_bar]'), 'a reload while selecting comes back to the chat (%s)' % h(pg))

def s_select_field(pg, check, lang, cm, W):
    cm.chats['p:101']['admins'] = []
    room(pg, 'p:101')
    longpress(pg, '#cwm-2 .cw-tx'); tap(pg, '.bsh [data-mk=select]', 500); tap(pg, '#cwm-3 .cw-b', 300); tap(pg, '[data-testid=cw_sel_del]', 600)
    check(vis(pg, '[data-testid=cw_del_me]') and not vis(pg, '[data-testid=cw_del_all]'), 'not an admin, one is someone else’s: only Delete for me')
    tap(pg, '[data-testid=cw_del_me]', 900)
    d = posts(cm, 'delete-many'); check(d and d[-1]['forAll'] is False and pg.locator('#cwm-2').count() == 0, 'Delete for me hides them here')

# ---- B11 B12 B13: the ⋯ menu, Clear chat, Media files and links, Jump to a date; search in the chat ----
def s_menu(pg, check, lang, cm, W):
    room(pg, 'p:101')
    tap(pg, '#cwHm', 700); check(vis(pg, '[data-testid=cw_room_menu_sheet]'), '⋯ opens the menu')
    tidy(pg, check, 'the ⋯ menu', SHEET); shot(pg, 'menu', lang)
    back(pg); check(not vis(pg, '.bsh') and view(pg) == 'vRoom', 'Back closes it')
    # media, files and links
    tap(pg, '#cwHm', 600); tap(pg, '[data-testid=cw_rm_mfl]', 900)
    check(vis(pg, '[data-testid=cw_mfl]') and pg.locator('.cw-mfg .cw-mfi').count() >= 1, 'Media, files and links: the photo shows')
    tidy(pg, check, 'media', SHEET); shot(pg, 'media', lang)
    tap(pg, '[data-testid=cw_mfl_links]', 300)
    check(pg.locator('.cw-mfb .cw-mfe').count() == 1, 'Links: an empty note when there are none')
    tap(pg, '[data-testid=cw_mfl_media]', 300); tap(pg, '.cw-mfi', 900)
    check(vis(pg, '.cw-lb'), 'a tap opens the photo viewer')
    back(pg); check(not vis(pg, '.cw-lb') and vis(pg, '[data-testid=cw_mfl]'), 'Back closes the viewer, the page stays')
    back(pg); check(not vis(pg, '.tsh') and view(pg) == 'vRoom', 'Back closes the page, still in the chat')
    # jump to a date
    room_ke(pg)
    tap(pg, '#cwHm', 600); tap(pg, '[data-testid=cw_rm_date]', 700)
    check(vis(pg, '[data-testid=cw_jump_date]'), 'Jump to a date asks for the day'); tidy(pg, check, 'the date sheet', SHEET); shot(pg, 'jump-date', lang)
    day = pg.evaluate("(() => { const d = new Date(Date.now() - 6 * 864e5); return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0'); })()")
    pg.fill('#cwJd', day); tap(pg, '#cwJdGo', 3500)
    first = pg.evaluate("(() => { const b = document.getElementById('cwMsgs'), r = b.getBoundingClientRect(); const el = [...b.querySelectorAll('.cw-day, .cw-m')].find(e => e.getBoundingClientRect().bottom > r.top + 4); return el ? el.textContent.slice(0, 40) : ''; })()")
    check(any(posts_get(cm, 'before')), 'older pages load to reach that day')
    check(first != '', 'the chat scrolls to that day (%s)' % first)
    # search in this chat
    tap(pg, '#cwHs', 500); check(vis(pg, '[data-testid=cw_find]') and pg.evaluate("document.activeElement.id") == 'cwFq', 'the search bar opens, ready to type')
    pg.fill('#cwFq', 'note 6'); pg.wait_for_timeout(1500)
    n = txt(pg, '[data-testid=cw_find_n]'); check(re.match(r'^1/\d+$', n or ''), 'it counts the hits (%s)' % n)
    check(pg.locator('#cwMsgs mark.cw-fm').count() >= 1, 'the words are marked')
    tidy(pg, check, 'searching', '#cwFind'); shot(pg, 'search', lang)
    tap(pg, '[data-testid=cw_find_up]', 900)
    check(txt(pg, '[data-testid=cw_find_n]').startswith('2/'), '↑ goes to the next older one (%s)' % txt(pg, '[data-testid=cw_find_n]'))
    back(pg); check(not vis(pg, '[data-testid=cw_find]') and pg.locator('#cwMsgs mark.cw-fm').count() == 0 and view(pg) == 'vRoom', 'Back closes the search, still in the chat')
def posts_get(cm, k): return [q for p, q in cm.gets if p == 'msgs' and q.get(k)]
def room_ke(pg):
    back(pg, 900) if view(pg) == 'vRoom' else None
    pg.click('.cw-row[data-open="t:ke"]'); pg.wait_for_timeout(1400)

def s_clear(pg, check, lang, cm, W):
    room(pg, 'p:101')
    tap(pg, '#cwHm', 600); tap(pg, '[data-testid=cw_rm_clear]', 700)
    check(vis(pg, '.bsh.po-ask'), 'Clear chat asks first'); shot(pg, 'clear-ask', lang)
    tap(pg, '.bsh.po-ask [data-k=yes]', 1000)
    check(posts(cm, 'clear') and posts(cm, 'clear')[-1] == {'c': 'p:101'}, 'clear goes to the server')
    check(pg.locator('#cwMsgs .cw-m').count() == 0, 'the messages are gone from my view')
    reload(pg); check(h(pg) == '#chat/p:101' and pg.locator('#cwMsgs .cw-m').count() == 0, 'and they don’t come back after a reload')
    # a DM: Delete chat = cleared and archived, back to the list
    back(pg, 900); pg.click('.cw-row[data-open="d:meril"]'); pg.wait_for_timeout(1300)
    tap(pg, '#cwHm', 600); check(vis(pg, '[data-testid=cw_rm_delchat]'), 'a DM has Delete chat')
    tap(pg, '[data-testid=cw_rm_delchat]', 700); tap(pg, '.bsh.po-ask [data-k=yes]', 1600)
    check(posts(cm, 'clear')[-1] == {'c': 'd:meril', 'archive': True}, 'Delete chat clears and archives')
    check(view(pg) == 'vChat' and pg.locator('.cw-row[data-open="d:meril"]').count() == 0, 'and goes back to the list, the chat in Archived (%s)' % view(pg))

# ---- B5 B6 B7 B8 B9: photos into the project and the story, quote into the story, translate, voice text, seen by ----
def s_project(pg, check, lang, cm, W):
    room(pg, 'p:101')
    tap(pg, '#cwm-4 [data-testid=cw_add_photos]', 2500)
    check(any(b.get('action') == 'chat-photo' and b.get('id') == '101' and b.get('from') == 'Meril Kiprop' for b in W.pin_posts), 'a note on the project, naming the chat and Meril (%s)' % W.pin_posts[-1:])
    check(len(W.photo_posts) == 1 and W.photo_posts[0] > 1000, 'the photo goes to that note')
    check(vis(pg, '#cwm-4 [data-testid=cw_added]'), '“Added to project” on the bubble')
    check(vis(pg, '[data-testid=cw_story_ask]'), 'then: also add to the story? (Before / During / After)')
    tidy(pg, check, 'the story question', SHEET); shot(pg, 'story-ask', lang)
    tap(pg, '[data-testid=cw_story_ask] [data-row=after]', 1500)
    check(len(W.story_posts) == 1 and 'multipart' in W.story_posts[0], 'the photo goes to the story’s After row')
    reload(pg); check(vis(pg, '#cwm-4 [data-testid=cw_added]'), 'still “Added to project” after a reload')
    # translate
    if lang == 'en':
        tap(pg, '#cwm-5 [data-testid=cw_translate]', 900)
        check(vis(pg, '#cwm-5 [data-testid=cw_translation]') and 'children' in txt(pg, '#cwm-5 [data-testid=cw_translation]') and cm.tr, 'the translation shows under the original')
        check(pg.evaluate("getComputedStyle(document.querySelector('#cwm-5 .cw-tlx')).fontStyle") == 'italic', 'in muted italics')
        tidy(pg, check, 'a translation'); shot(pg, 'translated', lang)
        tap(pg, '#cwm-5 [data-trorig]', 400); check(not vis(pg, '#cwm-5 [data-testid=cw_translation]'), 'Show original hides it again')
    longpress(pg, '#cwm-2 .cw-tx'); check(vis(pg, '.bsh [data-mk=tr]'), 'Translate · Tafsiri is in the long-press menu for any message'); back(pg)
    # voice note text
    tap(pg, '#cwm-6 [data-testid=cw_voice_text]', 900)
    check('silinda' in txt(pg, '#cwm-6 [data-testid=cw_voice_words]') and posts(cm, 'transcribe')[-1] == {'c': 'p:101', 'id': 6}, 'Show text types out the voice note under it')
    tidy(pg, check, 'voice text'); shot(pg, 'voice-text', lang)
    # Seen by: tap the ticks
    tap(pg, '#cwm-3 .cw-mt', 900)
    check(vis(pg, '.bsh .cw-inf') and 'Meril Kiprop' in txt(pg, '.bsh .cw-inf'), 'tapping ✓✓ opens Message info: read by, delivered to')
    tidy(pg, check, 'message info', SHEET); shot(pg, 'seen-by', lang); back(pg)
    # use in a story: the quote is filled, nothing saved
    longpress(pg, '#cwm-5 .cw-tx'); tap(pg, '.bsh [data-mk=story]', 2600)
    check(h(pg) == '#p/101/story', 'Use in a story opens the project’s story (%s)' % h(pg))
    check(pg.input_value('#styQ') == 'Mwenyekiti anasema watoto sasa wanakunywa maji safi kila siku.' and pg.input_value('#styQB') == 'Meril Kiprop', 'the quote and who said it are filled in')
    check(not any('answers' in str(x) for x in W.story_posts), 'nothing is saved until Save quote')
    shot(pg, 'story-quote', lang)
    back(pg, 1200); check(h(pg) == '#chat/p:101', 'Back returns to the chat (%s)' % h(pg))
def s_tools_off(pg, check, lang, cm, W):
    room(pg, 'p:101')
    check(not vis(pg, '#cwm-6 [data-testid=cw_voice_text]'), 'typing out off: no Show text')
    if lang == 'en':
        check(pg.get_attribute('#cwm-5 [data-testid=cw_translate]', 'href', timeout=2000).startswith('https://translate.google.com/?sl=auto&tl=en&text=Mwenyekiti'), 'no translation key: the link opens Google Translate')

# ---- B14 B15: sending states and photo sizes ----
def s_send(pg, check, lang, cm, W):
    room(pg, 'p:101')
    cm.down = True; pg.context.set_offline(True); pg.wait_for_timeout(300)
    pg.fill('#cwTx', 'Nimefika'); pg.click('#cwGo'); pg.wait_for_timeout(900)
    check(vis(pg, '#cwMsgs .cw-m.pend [data-testid=cw_waiting]') and T(lang, 'Waiting for signal', 'Inasubiri mtandao') in txt(pg, '#cwMsgs .cw-m.pend'), 'offline: “Waiting for signal…” with Retry')
    tidy(pg, check, 'waiting'); shot(pg, 'waiting', lang)
    pg.context.set_offline(False); pg.wait_for_timeout(9000)  # signal again but the server is still out of reach: after a few seconds it says so again
    check(vis(pg, '#cwMsgs .cw-m.pend [data-testid=cw_waiting]'), 'still not through after 8 s: “Waiting for signal…” again')
    cm.down = False; tap(pg, '#cwMsgs .cw-m.pend [data-retry]', 1500)
    check(pg.locator('#cwMsgs .cw-m.pend').count() == 0 and any(b.get('text') == 'Nimefika' for b in posts(cm, 'send')), 'Retry sends it')
    # refused by the server: Not sent · Tap to retry
    cm.refuse = True
    pg.fill('#cwTx', 'Hii haitafika'); pg.wait_for_timeout(150); pg.click('#cwGo'); pg.wait_for_timeout(1200)
    check(vis(pg, '[data-testid=cw_not_sent]') and T(lang, 'Not sent · Tap to retry', 'Haijatumwa · Gusa kujaribu tena') in txt(pg, '[data-testid=cw_not_sent]'), '“Not sent · Tap to retry”')
    shot(pg, 'not-sent', lang)
    cm.refuse = False
    tap(pg, '#cwMsgs .cw-m.failed .cw-b', 1500)
    check(pg.locator('#cwMsgs .cw-m.failed').count() == 0 and any(b.get('text') == 'Hii haitafika' for b in posts(cm, 'send')), 'a tap retries it')
    # photo sizes: Wi-Fi 1600, mobile data 1280, data saver 960 (a 3000 × 2000 photo pasted into the box and sent)
    sizes = []
    for mode, kind in (('off', 'wifi'), ('off', 'cellular'), ('always', 'wifi')):
        pg.evaluate("""([mode, kind]) => { localStorage.setItem('rfChatSaver', mode); Object.defineProperty(navigator, 'connection', { value: { type: kind, effectiveType: '4g', saveData: false }, configurable: true }); }""", [mode, kind])
        pg.evaluate("""(async () => { const c = document.createElement('canvas'); c.width = 3000; c.height = 2000; const x = c.getContext('2d'); x.fillStyle = '#0A78C2'; x.fillRect(0, 0, 3000, 2000);
          const b = await new Promise(r => c.toBlob(r, 'image/png')); const dt = new DataTransfer(); dt.items.add(new File([b], 'big.png', { type: 'image/png' }));
          document.getElementById('cwTx').dispatchEvent(new ClipboardEvent('paste', { clipboardData: dt, bubbles: true, cancelable: true })); })()"""); pg.wait_for_timeout(2500)
        tap(pg, '#cwCapGo', 2500)
        md = [b['media'][0] for b in posts(cm, 'send') if b.get('kind') == 'media' and b.get('media')]
        sizes.append(md[-1].get('w') if md else None)
    pg.evaluate("localStorage.removeItem('rfChatSaver')")
    check(sizes == [1600, 1280, 960], 'photos: 1600 px on Wi-Fi, 1280 on mobile data, 960 with the data saver (%s)' % sizes)

# ---- B16: add people ----
def s_add(pg, check, lang, cm, W):
    room(pg, 'g:kitale')
    tap(pg, '#cwHm', 600); tap(pg, '[data-testid=cw_rm_add]', 1000)
    check(vis(pg, '[data-testid=cw_add_people]'), '⋯ › Add people opens the picker')
    check(pg.locator('.cw-aph').count() >= 3 and T(lang, 'Kenya', 'Kenya') in txt(pg, '.cw-apl'), 'grouped by country with flags')
    check(pg.locator('.cw-apr.in').count() == 2 and T(lang, 'Already in the group', 'Tayari yuko kwenye kikundi') in txt(pg, '.cw-apr.in'), 'people already in it are greyed: “Already in the group”')
    check(vis(pg, '[data-testid=cw_ap_invite]') and vis(pg, '[data-testid=cw_ap_link]'), 'an admin: Invite a new person and Share invite link')
    tap(pg, '[data-testid=cw_ap_all_ug]', 300)
    check(pg.locator('.cw-apchip').count() == 2 and T(lang, 'Add 2 people', 'Ongeza watu 2') in txt(pg, '#cwApGo'), '“Add all Uganda team” picks both; chips at the top; Add 2 people (%s)' % txt(pg, '#cwApGo'))
    pg.fill('#cwApq', 'Neema'); pg.wait_for_timeout(200); tap(pg, '.cw-apr[data-app="Neema Mushi"]', 300); pg.fill('#cwApq', ''); pg.wait_for_timeout(200)
    tidy(pg, check, 'add people', SHEET)
    b = pg.evaluate(CLIP, '.cw-apall, .cw-apchip, #cwApGo, .cw-aph b'); check(b == [], 'nothing cut off: %s' % b)
    shot(pg, 'add-people', lang)
    tap(pg, '#cwApGo', 900)
    m = posts(cm, 'members'); check(m and sorted(m[-1]['add']) == ['Faith Nakato', 'Jessica Anderson', 'Neema Mushi'], 'members add goes to the server (%s)' % (m[-1] if m else None))
    check(not vis(pg, '[data-testid=cw_add_people]') and view(pg) == 'vRoom', 'the picker closes, back in the chat')
    # Back closes the picker first
    tap(pg, '#cwHm', 600); tap(pg, '[data-testid=cw_rm_add]', 900); back(pg)
    check(not vis(pg, '[data-testid=cw_add_people]') and view(pg) == 'vRoom' and h(pg) == '#chat/g:kitale', 'Back closes the picker')
    # a group with only me: the Add people card in the chat
    back(pg, 900); pg.click('.cw-row[data-open="g:solo"]'); pg.wait_for_timeout(1300)
    check(vis(pg, '[data-testid=cw_add_card]'), 'a new group with only me: “Add people” in the chat'); shot(pg, 'add-card', lang)
    tap(pg, '[data-testid=cw_add_card] [data-es]', 900); check(vis(pg, '[data-testid=cw_add_people]'), 'its button opens the picker'); back(pg)
    # someone added me to a group: a note with Open
    cm.push({'t': 'chat', 'chat': dict(cm.chats['g:kitale'], id='g:new', title='Turbo works', admins=['Grace Achieng'], members=['Grace Achieng', ME], last=None)}); pg.wait_for_timeout(500)
    check(T(lang, 'You were added to Turbo works', 'Umeongezwa kwenye Turbo works') in txt(pg, '[data-testid=cw_snack]'), 'being added: “You were added to Turbo works” with Open (%s)' % txt(pg, '[data-testid=cw_snack]'))
def s_add_field(pg, check, lang, cm, W):
    cm.chats['g:kitale']['admins'] = ['Grace Achieng']
    room(pg, 'g:kitale')
    tap(pg, '#cwHd', 800); tap(pg, '[data-testid=cw_info_add]', 1000)
    check(vis(pg, '.cw-apn') and not vis(pg, '[data-testid=cw_ap_invite]') and vis(pg, '[data-testid=cw_ap_link]'), 'not an admin: a note, no Invite a new person, Share invite link stays')
    tap(pg, '.cw-apr[data-app="Otieno Ouma"]', 300)
    check(T(lang, 'Ask an admin to add 1 person', 'Mwombe msimamizi aongeze mtu 1') in txt(pg, '#cwApGo'), 'the button asks an admin (%s)' % txt(pg, '#cwApGo'))
    tidy(pg, check, 'add people (field)', SHEET); shot(pg, 'add-people-field', lang)
    tap(pg, '#cwApGo', 1200)
    s = posts(cm, 'send'); check(s and s[-1].get('mentions') == ['Grace Achieng'] and 'Otieno Ouma' in s[-1]['text'], 'it sends a message to the group’s admins (%s)' % (s[-1]['text'] if s else None))

# ---- B17 and the look in every theme, size and width ----
def s_shots(pg, check, lang, cm, W):
    for theme in ('', 'dark', 'sun'):
        pg.evaluate("t => { if (t) document.documentElement.setAttribute('data-theme', t); else document.documentElement.removeAttribute('data-theme'); }", theme)
        for w in (320, 360, 390):
            pg.set_viewport_size({'width': w, 'height': 844}); pg.wait_for_timeout(250)
            if view(pg) != 'vRoom': room(pg, 'p:101')
            pg.evaluate("document.getElementById('cwMsgs').scrollTop = 1e6"); pg.wait_for_timeout(200)
            tidy(pg, check, 'room %s %d' % (theme or 'light', w))
            b = pg.evaluate(CLIP, '.cw-pst, .cw-pso, .cw-addp, .cw-tlk, .cw-qr button'); check(b == [], 'room %s %d: nothing cut off: %s' % (theme or 'light', w, b))
            shot(pg, 'room-%d-%s' % (w, theme or 'light'), lang)
        tap(pg, '#cwHm', 600); tidy(pg, check, 'menu %s' % (theme or 'light'), SHEET); shot(pg, 'menu-320-%s' % (theme or 'light'), lang); back(pg)
        longpress(pg, '#cwm-2 .cw-tx'); tap(pg, '.bsh [data-mk=select]', 500); tap(pg, '#cwm-3 .cw-b', 300); tap(pg, '[data-testid=cw_sel_del]', 600)
        tidy(pg, check, 'delete %s' % (theme or 'light'), SHEET); shot(pg, 'delete-320-%s' % (theme or 'light'), lang); back(pg); back(pg)
    # chat info title and rows match the sheet scale
    tap(pg, '#cwHd', 800)
    fs = pg.evaluate("[parseFloat(getComputedStyle(document.querySelector('.cw-ihd h3')).fontSize), Math.round(document.querySelector('.cw-info .ct-r').getBoundingClientRect().height)]")
    check(abs(fs[0] - 20) < 0.5 and fs[1] >= 56, 'chat info: title 1.25rem like Office sheets, rows 56 px (%s)' % fs)
    shot(pg, 'info-320-sun', lang); back(pg)
def s_size(pg, check, lang, cm, W):
    room(pg, 'p:101')
    for z, px in (('l', 18), ('xl', 20)):
        pg.evaluate("z => document.documentElement.setAttribute('data-size', z)", z); pg.wait_for_timeout(300)
        fs = pg.evaluate("['.cw-tx','.cw-sn','.cw-mt'].map(s => parseFloat(getComputedStyle(document.querySelector('#cwMsgs ' + s)).fontSize))")
        check(abs(fs[0] - .97 * px) < .3 and abs(fs[1] - .78 * px) < .3 and abs(fs[2] - .72 * px) < .3, 'Text size %s: words, names and times grow together (%s)' % (z.upper(), fs))
        tidy(pg, check, 'text size %s' % z); shot(pg, 'text-%s' % z, lang)
def s_desktop(pg, check, lang, cm, W):
    for w in (1280, 1440):
        pg.set_viewport_size({'width': w, 'height': 860}); pg.wait_for_timeout(300)
        if view(pg) != 'vRoom': room(pg, 'p:101')
        check(vis(pg, '#cwRh [data-hd=search]') and vis(pg, '#cwRh [data-hd=menu]') and vis(pg, '#cwPs'), '%d: the room header has search and ⋯, the strip shows' % w)
        mw = pg.evaluate("Math.max(...[...document.querySelectorAll('#cwMsgs .cw-b')].map(b => b.getBoundingClientRect().width))")
        check(mw <= 561, '%d: bubbles at most 560 px (%s)' % (w, mw))
        tidy(pg, check, 'desktop %d' % w); shot(pg, 'desk-%d' % w, lang)
    longpress(pg, '#cwm-2 .cw-tx'); tap(pg, '.bsh [data-mk=select]', 500)
    r = pg.evaluate("(() => { const a = document.getElementById('cwSel').getBoundingClientRect(), b = document.getElementById('cwRh').getBoundingClientRect(); return [Math.round(a.top - b.top), Math.round(a.bottom - b.bottom)]; })()")
    check(r[0] <= 0 and r[0] >= -3 and r[1] >= 0 and r[1] <= 3, 'a computer: the selection bar covers the room header (%s)' % r)
    shot(pg, 'desk-select', lang); back(pg)
    tap(pg, '#cwRh [data-hd=search]', 500); check(vis(pg, '[data-testid=cw_find]'), 'search from the room header'); back(pg)
    check(not vis(pg, '[data-testid=cw_find]') and view(pg) == 'vRoom', 'Back closes it')

SEQ = [('The room: header, project strip, bubbles, quick replies (room.png)', s_look, {}),
       ('Select + delete: the bar, the sheet (delete.png), Back, forward, star, reload', s_select, {}),
       ('Select + delete as a field user: only Delete for me', s_select_field, {'admin': False}),
       ('⋯ menu, media/files/links, jump to a date, search in the chat', s_menu, {}),
       ('Clear chat and Delete chat', s_clear, {}),
       ('Add to project photos (+ story), translate, voice text, seen by, use in a story', s_project, {}),
       ('Tools off: Google Translate link, no Show text', s_tools_off, {}),
       ('Sending: waiting for signal, not sent, retry; photo sizes', s_send, {}),
       ('Add people (an admin), the add card, being added', s_add, {}),
       ('Add people (not an admin): ask an admin', s_add_field, {'admin': False}),
       ('Screenshots: 320/360/390 × light/dark/sun; sheet titles', s_shots, {}),
       ('Text size L and XL', s_size, {}),
       ('A computer: 1280 and 1440', s_desktop, {'w': 1280})]
with sync_playwright() as p:
    br = p.chromium.launch(args=['--use-fake-ui-for-media-stream', '--use-fake-device-for-media-stream'])
    for lang in LANGS:
        for name, fn, kw in SEQ:
            kw = dict(kw); adm = kw.pop('admin', True)
            if fn is s_tools_off:
                def fn2(pg, check, lang, cm, W):
                    cm.tools.update(translate=False, transcribe=False); pg.reload(); pg.wait_for_timeout(500); [pg.click('.pk[data-k="%s"]' % d) for d in '9999'] if vis(pg, '.pk[data-k="9"]') else None; pg.wait_for_timeout(1500); s_tools_off(pg, check, lang, cm, W)
                fn2.__name__ = 's_tools_off'; seq(name, lang, fn2, br, adm, **kw)
            else: seq(name, lang, fn, br, adm, **kw)
    br.close()
bad = [r for r in RESULTS if not r[2]]
print('\n%d of %d sequences pass' % (len(RESULTS) - len(bad), len(RESULTS)))
sys.exit(1 if bad else 0)
