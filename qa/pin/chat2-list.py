# Chat 2, the chat LIST (cw2-list, design/chat-2026-10/list.png): the compact header (Me photo, search in place, ⋯ menu),
# the filter chips, "In the field now", the one-line alert prompt, dense rows, project pictures, Archived at the end and
# the new-chat button. Same mocks as qa/pin/chat.py (its helpers are loaded from it). Prints PASS / FAIL per sequence.
#   BASE=http://localhost:8891 python3 qa/pin/chat2-list.py [en|sw|both]     screenshots go to $OUT (default qa/pin/chat2-list)
import os, re, sys, json, datetime, types
from playwright.sync_api import sync_playwright
HERE = os.path.dirname(os.path.abspath(__file__)); sys.path.insert(0, HERE)
WHICH = sys.argv[1] if len(sys.argv) > 1 else 'both'
# chat.py's mocks and checks, without running its sequences
C = types.ModuleType('chatqa'); C.__file__ = os.path.join(HERE, 'chat.py')
_argv = sys.argv; sys.argv = ['chat.py', 'en']
exec(compile(open(C.__file__).read().split('\nSEQ = [')[0], C.__file__, 'exec'), C.__dict__)
sys.argv = _argv
from chat_mock import ChatMock
BASE = C.BASE
OUT = os.environ.get('OUT', os.path.join(HERE, 'chat2-list')); os.makedirs(OUT, exist_ok=True); C.OUT = OUT
LANGS = {'en': ['en'], 'sw': ['sw'], 'both': ['en', 'sw']}[WHICH]
ONLY = os.environ.get('ONLY', '')
h, view, back, tabbar, vis, txt, T, menu, search, BROKEN, NOOVER, OVER = C.h, C.view, C.back, C.tabbar, C.vis, C.txt, C.T, C.menu, C.search, C.BROKEN, C.NOOVER, C.OVER
ERRS = C.ERRS
now = datetime.datetime.now(datetime.timezone.utc)
iso = lambda mins: (now - datetime.timedelta(minutes=mins)).strftime('%Y-%m-%dT%H:%M:%SZ')
# Office › Team map for admins: Bertin shares his place next to Kapsaret Borehole, Jessica somewhere on the road
TEAMMAP = {'ok': True, 'people': [{'name': 'Bertin Mwangi', 'lat': 0.4702, 'lng': 35.2503, 'acc': 12, 'at': iso(8)}, {'name': 'Jessica Anderson', 'lat': 0.9, 'lng': 35.6, 'acc': 30, 'at': iso(20)}], 'visits': []}

def context(br, lang, cm, w=390, theme='', admin=True, tm=None, size=''):
    TM = {'n': 0}
    ctx = br.new_context(viewport={'width': w, 'height': 800}, device_scale_factor=2 if w < 1000 else 1, is_mobile=w < 1000, has_touch=w < 1000, service_workers='block', locale='en-GB', timezone_id='Africa/Nairobi',
                         geolocation={'latitude': 0.515, 'longitude': 35.27, 'accuracy': 8}, permissions=['geolocation'])
    ctx.route(re.compile(r'https://(tile\.openstreetmap\.org|server\.arcgisonline\.com)/.*'), lambda r, q: r.fulfill(status=200, body=C.TILE, headers={'Content-Type': 'image/png', 'Access-Control-Allow-Origin': '*'}))
    pg = ctx.new_page(); C.install(pg); cm.install(pg)
    pg.add_init_script("try { Object.defineProperty(Notification, 'permission', { get: () => 'default' }); } catch (e) {}")   # headless says 'denied'; a new phone says 'default'
    if not admin:
        def snap_field(route, req):
            u = req.url
            if req.method != 'GET': return route.fulfill(json={'ok': True})
            if 'team=' in u: return route.fulfill(json={'ok': True, 'team': [{'name': t['name'], 'home': 'ke'} for t in C.TEAM]})
            if 'activity=' in u: return route.fulfill(json={'ok': True, 'activity': []})
            if re.search(r'[?&](id|history)=', u): return route.fulfill(json={'ok': True, 'notes': [], 'last': None, 'visits': [], 'costs': {'sum': 0, 'n': 0, 'last': []}})
            j = C.snap(); j['who'] = 'Reid Anderson'; j['admin'] = False; return route.fulfill(json=j)
        pg.route(re.compile(r'/api/pin(\?.*)?$'), snap_field)
    def admin_api(route, req):
        if 'view=teammap' in req.url: TM['n'] += 1; return route.fulfill(json=tm if tm is not None else TEAMMAP)
        if 'view=team' in req.url: return route.fulfill(json={'ok': True, 'team': C.TEAM, 'mail': {'ready': True, 'to': []}, 'cron': True})
        return route.fulfill(json={'ok': True, 'items': [], 'rows': [], 'people': [], 'visits': [], 'donors': []})
    pg.route(re.compile(r'/api/admin(\?.*)?$'), admin_api)
    pg.on('pageerror', lambda e: ERRS.append('pageerror: ' + str(e)[:200]))
    pg.on('console', lambda m: ERRS.append('console: ' + m.text[:200]) if m.type == 'error' and 'Failed to load resource' not in m.text else None)
    pg.goto(BASE + '/pin'); pg.wait_for_timeout(600)
    pg.evaluate("([l, t, s]) => { localStorage.setItem('rfPinOnb', '1'); localStorage.setItem('rfPinMeHint', '1'); if (l === 'sw') localStorage.setItem('rfPinLang', 'sw'); if (t) localStorage.setItem('rfPinTheme', t); if (s) localStorage.setItem('rfPinSize', s); }", [lang, theme, size])
    pg.reload(); pg.wait_for_timeout(600)
    for d in '9999': pg.click('.pk[data-k="%s"]' % d)
    pg.wait_for_timeout(1500)
    pg.tm = TM
    return ctx, pg

def shot(pg, name, lang): pg.wait_for_timeout(250); fn = os.path.join(OUT, '%s-%s.png' % (lang, name)); pg.screenshot(path=fn); return fn
def rows(pg): return pg.evaluate("[...document.querySelectorAll('#cwList .cw-row[data-open]')].map(r => r.dataset.open)")
def chip(pg, k, t=500): pg.click('#cwChips [data-chip=%s]' % k); pg.wait_for_timeout(t)
def chip_on(pg): return pg.evaluate("(document.querySelector('#cwChips [aria-pressed=true]') || {}).dataset?.chip || ''")
# anything of the list sticking out of the screen (the chip row and the field row scroll sideways inside themselves)
OFF = """(() => { const bad = []; document.querySelectorAll('#cwTop *, #cwField *, #cwNote *, #cwList *, #cwNew').forEach(e => { if (!e.offsetParent || e.closest('.cw2-chips, .cw2-ppl, .cw-ra, .cw2-pop.hidden')) return;
  const r = e.getBoundingClientRect(); if (r.width && (r.left < -1 || r.right > innerWidth + 1)) bad.push((e.id || e.className || e.tagName) + ' ' + Math.round(r.left) + '..' + Math.round(r.right)); }); return bad.slice(0, 6); })()"""
# words that do not fit their box (cut off, not an intended "…"): chips, title, menu, alert, field labels
CUT = """(sel => { const bad = []; document.querySelectorAll(sel).forEach(e => { if (!e.offsetParent) return; const cs = getComputedStyle(e);
  if (cs.textOverflow === 'ellipsis' && cs.webkitLineClamp === 'none') return; if (e.scrollWidth > e.clientWidth + 1 || (cs.webkitLineClamp === 'none' && e.scrollHeight > e.clientHeight + 2 && cs.overflowY === 'hidden')) bad.push(e.textContent.trim().slice(0, 30)); }); return bad.slice(0, 6); })"""
CUTSEL = '#cwChips button, #h-chat, .cw2-pop button, .cw2-pop button span, .cw2-nt span, .cw2-nt button, .cw2-fd h2, .cw2-arch span, .cw-empty h2, .cw-es h3, .cw-es .btn'
# lines of a list row lying on top of each other, or on the picture
ROWLAP = """(() => { const bad = []; document.querySelectorAll('#cwList .cw-row').forEach(r => { const a = r.querySelector('.cw-av'), parts = [...r.querySelectorAll('.cw-r1 b, .cw-r1 time, .cw-pv, .cw-typ, .cw-bs > *')].filter(e => e.offsetParent);
  const R = e => e.getBoundingClientRect(), lap = (x, y) => x.left < y.right - 1 && y.left < x.right - 1 && x.top < y.bottom - 1 && y.top < x.bottom - 1;
  for (let i = 0; i < parts.length; i++) { if (a && lap(R(a), R(parts[i]))) bad.push(r.dataset.open + ': picture × ' + parts[i].className); for (let j = i + 1; j < parts.length; j++) if (!parts[i].contains(parts[j]) && !parts[j].contains(parts[i]) && lap(R(parts[i]), R(parts[j]))) bad.push(r.dataset.open + ': ' + parts[i].className + ' × ' + parts[j].className); } }); return bad.slice(0, 6); })()"""
FIELDLAP = """(() => { const bad = []; document.querySelectorAll('.cw2-p').forEach(p => { const r = p.getBoundingClientRect(); [...p.querySelectorAll('small, em')].forEach(e => { const q = e.getBoundingClientRect(); if (q.left < r.left - 1 || q.right > r.right + 1 || e.scrollHeight > e.clientHeight + 1 || e.scrollWidth > e.clientWidth + 1) bad.push(e.textContent); }); }); return bad; })()"""
# at the end of the list the new-chat button covers no row
FABLAP = """(() => { scrollTo(0, document.scrollingElement.scrollHeight); const f = document.getElementById('cwNew').getBoundingClientRect(); const bad = [];
  document.querySelectorAll('#cwList .cw-rt, #cwList .cw2-arch, #cwList .cw-av').forEach(e => { const r = e.getBoundingClientRect(); if (r.left < f.right && f.left < r.right && r.top < f.bottom && f.top < r.bottom) bad.push(e.className); }); return bad; })()"""

RESULTS = []
def seq(name, lang, fn, br, cm=None, **kw):
    if ONLY and ONLY not in name: return
    cm = cm or ChatMock(); notes = []
    def check(c, what): (None if c else notes.append(what))
    del ERRS[:]
    pre = kw.pop('pre', None)
    if pre: pre(cm)
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

def s_look(pg, check, lang, cm):
    tabbar(pg, 'vChat', 1600)
    check(not vis(pg, 'header.top') and vis(pg, '#cwMe') and vis(pg, '#cwSrch') and vis(pg, '#cwMore'), 'phone: the RAFIKI bar steps aside; Me photo, search and ⋯ in the header')
    check(txt(pg, '#h-chat') == T(lang, 'Chats', 'Gumzo'), 'the title says Chats (%s)' % txt(pg, '#h-chat'))
    check(pg.evaluate("getComputedStyle(document.getElementById('h-chat')).fontSize") == pg.evaluate("getComputedStyle(document.documentElement).getPropertyValue('--cw-fs-title').trim() === '1.5rem' ? '24px' : ''"), 'the title uses --cw-fs-title')
    sizes = pg.evaluate("[...new Set([...document.querySelectorAll('#cwTop *, #cwField *, #cwNote *, #cwList *')].filter(e => e.offsetParent && [...e.childNodes].some(n => n.nodeType === 3 && n.data.trim())).map(e => getComputedStyle(e).fontSize))]")
    allowed = pg.evaluate("['title','name','msg','meta','small'].map(k => { const d = document.createElement('i'); d.style.fontSize = 'var(--cw-fs-' + k + ')'; document.body.appendChild(d); const v = getComputedStyle(d).fontSize; d.remove(); return v; })")
    extra = [s for s in sizes if s not in allowed]
    check(not extra, 'every text size in the list is one of the five chat sizes (%s not in %s)' % (extra, allowed))
    check(rows(pg)[:2] == ['t:news', 't:all'], 'Announcements first, then the pinned chat (%s)' % rows(pg))
    check(vis(pg, '.cw-row[data-open="t:news"] .cw-bi') and pg.locator('#cwList .cw-sec').count() == 0, 'Announcements is a plain row with a small pin, no section titles')
    check(pg.evaluate("Math.round(document.querySelector('.cw-row[data-open=\"d:meril\"]').getBoundingClientRect().height)") >= 74 and pg.evaluate("Math.round(document.querySelector('.cw-row[data-open=\"d:meril\"] .cw-av').getBoundingClientRect().width)") == 52, 'rows are 74 px with a 52 px picture')
    check(pg.evaluate("getComputedStyle(document.querySelector('.cw-row[data-open=\"d:meril\"] .cw-rt')).borderBottomStyle") == 'solid', 'thin dividers under the words')
    check(pg.evaluate("getComputedStyle(document.querySelector('.cw-row[data-open=\"p:101\"] .cw-r1 b')).fontWeight") == '800', 'unread: a bold name')
    check(vis(pg, '.cw-row[data-open="p:101"] .cw-at') and vis(pg, '.cw-row[data-open="p:101"] .cw-pdr'), 'a mention pill, and the project photo carries the blue water drop')
    check(pg.locator('.cw-row[data-open="p:102"] .cw-av.tile svg').count() == 1, 'a project without a photo: its kind of project on a coloured tile')
    check(pg.locator('#cwList .cw-k').count() == 0, 'no kind tags (Team, Project) in the rows')
    check(pg.evaluate("document.querySelector('#cwList > :last-child').id") == 'cwArch', 'Archived is a small row at the end')
    check(vis(pg, '[data-testid=cw_alerts]') and pg.evaluate("Math.round(document.querySelector('[data-testid=cw_alerts]').getBoundingClientRect().height)") <= 48, 'the alert prompt is one slim line')
    check(pg.evaluate(FABLAP) == [], 'at the end of the list the new-chat button covers no row (%s)' % pg.evaluate(FABLAP))
    pg.evaluate('scrollTo(0, 0)')
    pg.click('#cwNtX'); pg.wait_for_timeout(400); check(not vis(pg, '[data-testid=cw_alerts]'), '× puts the alert prompt away')
    pg.click('#cwNew'); pg.wait_for_timeout(900); check(vis(pg, '.tsh [data-nw=group]'), 'the green button opens the new-chat sheet')
    back(pg, 800); check(not vis(pg, '.tsh') and view(pg) == 'vChat', 'Back closes it, still on Chat')

def s_chips(pg, check, lang, cm):
    tabbar(pg, 'vChat', 1600)
    labels = pg.evaluate("[...document.querySelectorAll('#cwChips button')].map(b => b.textContent)")
    check(labels == [T(lang, 'All', 'Zote'), T(lang, 'Unread 4', 'Hazijasomwa 4'), T(lang, 'Projects', 'Miradi'), T(lang, 'Groups', 'Vikundi'), T(lang, 'Teams', 'Timu'), T(lang, 'People', 'Watu')], 'All · Unread (4) · Projects · Groups · Teams · People (%s)' % labels)
    for k, want in [('unread', ['t:all', 'p:101', 'd:meril', 'g:kitale']), ('project', ['p:101', 'p:102']), ('group', ['g:kitale']), ('team', ['t:all', 't:ke']), ('dm', ['d:meril']), ('all', None)]:
        chip(pg, k)
        if want: check(rows(pg) == want, '%s shows %s (%s)' % (k, want, rows(pg)))
        check(chip_on(pg) == k, '%s is marked chosen' % k)
    check(len(rows(pg)) == 7 and vis(pg, '#cwArch'), 'All: everything again, Archived at the end')
    # Back after changing chips is not a step; a reload keeps the chip; the bar keeps it too
    chip(pg, 'group'); chip(pg, 'project'); back(pg, 1200)
    check(view(pg) == 'vMap', 'Back after changing chips leaves Chat (no step per chip) (%s)' % view(pg))
    tabbar(pg, 'vChat', 1400); check(chip_on(pg) == 'project', 'the chosen chip is remembered for the visit (%s)' % chip_on(pg))
    pg.reload(); pg.wait_for_timeout(2400); check(h(pg) == '#chat' and chip_on(pg) == 'project' and rows(pg) == ['p:101', 'p:102'], 'a reload keeps the chip (%s, %s)' % (chip_on(pg), rows(pg)))
    shot(pg, 'chip-projects', lang)
    # Unread with nothing unread: a picture and "Show all chats"
    chip(pg, 'unread'); menu(pg, 'read', 900)
    check(vis(pg, '[data-testid=cw_empty_chip]') and T(lang, 'Unread', 'Hazijasomwa') == txt(pg, '#cwChips [data-chip=unread]'), '⋯ › Mark all as read: Unread is empty, its count goes')
    check(len(cm.posts) and sum(1 for p, b in cm.posts if p == 'read') >= 4, 'the server hears every read')
    shot(pg, 'chip-empty', lang)
    pg.click('[data-testid=cw_empty_chip] [data-es]'); pg.wait_for_timeout(500); check(chip_on(pg) == 'all' and len(rows(pg)) == 7, 'its one action shows all chats')

def s_field(pg, check, lang, cm):
    tabbar(pg, 'vChat', 1800)
    ppl = pg.evaluate("[...document.querySelectorAll('.cw2-p')].map(p => [p.dataset.dmw, p.dataset.k, p.querySelector('em').textContent])")
    check([p[0] for p in ppl] == ['Bertin Mwangi', 'Jessica Anderson', 'Meril Kiprop', 'Grace Achieng'], 'admins: sharing first, then online, then on lately (%s)' % ppl)
    check(ppl and ppl[0][1] == 'site' and ppl[0][2] == T(lang, 'At Kapsaret Borehole', 'Yuko Kapsaret Borehole'), 'Bertin shares his place next to a project: “At Kapsaret Borehole” with the pin (%s)' % (ppl[:1],))
    check(len(ppl) > 1 and ppl[1][2] == T(lang, 'On the road', 'Njiani'), 'Jessica shares hers away from projects: “On the road” (%s)' % (ppl[1:2],))
    check(len(ppl) > 3 and ppl[2][1] == 'on' and ppl[2][2] == T(lang, 'Online', 'Mtandaoni') and ppl[3][1] == '', 'Meril online, Grace on lately')
    check(pg.locator('.cw2-p[data-k=site] .cw2-tag').count() == 2 and pg.evaluate("getComputedStyle(document.querySelector('.cw2-rg.site')).backgroundImage").startswith('conic-gradient'), 'sharing: the green gradient ring and the pin badge')
    check(pg.evaluate(FIELDLAP) == [], 'every label fits under its picture (%s)' % pg.evaluate(FIELDLAP))
    check(pg.evaluate(BROKEN, '.cw2-fd') == [], 'no word breaks in the field row: %s' % pg.evaluate(BROKEN, '.cw2-fd'))
    shot(pg, 'field-admin', lang)
    pg.click('.cw2-p[data-dmw="Bertin Mwangi"]'); pg.wait_for_timeout(1500)
    check(view(pg) == 'vRoom' and h(pg) == '#chat/d:bertin' and any(b == {'kind': 'dm', 'with': 'Bertin Mwangi'} for b in C.posts(cm, 'open')), 'a tap opens the chat with them (%s)' % h(pg))
    back(pg, 1200); check(view(pg) == 'vChat' and h(pg) == '#chat', 'Back comes to the list (%s)' % h(pg))

def s_field_user(pg, check, lang, cm):
    tabbar(pg, 'vChat', 1800)
    ppl = pg.evaluate("[...document.querySelectorAll('.cw2-p')].map(p => [p.dataset.dmw, p.dataset.k, p.querySelector('em').textContent])")
    check(pg.tm['n'] == 0, 'field users: the team map is never asked for (%d)' % pg.tm['n'])
    check([p[0] for p in ppl] == ['Meril Kiprop', 'Grace Achieng'] and not any(p[1] == 'site' for p in ppl), 'field users see only Online and last seen, never where anyone is (%s)' % ppl)
    check(not any(T(lang, 'At ', 'Yuko ') in p[2] or p[2] == T(lang, 'On the road', 'Njiani') for p in ppl), 'no “At …” or “On the road” for field users')
    shot(pg, 'field-user', lang)

def s_field_none(pg, check, lang, cm):
    tabbar(pg, 'vChat', 1800)
    check(pg.locator('[data-testid=cw_field]').count() == 0, 'nobody online or on lately: no field row')

def s_back(pg, check, lang, cm):
    tabbar(pg, 'vChat', 1600)
    # search in place
    pg.click('#cwSrch'); pg.wait_for_timeout(400)
    check(vis(pg, '#cwQ') and pg.evaluate("document.activeElement.id") == 'cwQ' and not vis(pg, '#h-chat') and not vis(pg, '#cwChips'), 'the search icon opens the box in place of the title, ready to type')
    shot(pg, 'search-open', lang)
    back(pg, 800); check(view(pg) == 'vChat' and h(pg) == '#chat' and not vis(pg, '#cwQ') and vis(pg, '#h-chat'), 'Back closes the search, still on Chat')
    search(pg, 'kit'); pg.wait_for_timeout(700)
    check(rows(pg) == ['g:kitale'], 'typing finds the chat (%s)' % rows(pg))
    pg.click('#cwQb'); pg.wait_for_timeout(600); check(not vis(pg, '#cwQ') and len(rows(pg)) == 7, '← closes it and shows every chat')
    back(pg, 1200); check(view(pg) == 'vMap', 'and leaves no extra step: Back goes on to Map (%s)' % view(pg))
    tabbar(pg, 'vChat', 1400)
    # the ⋯ menu
    pg.click('#cwMore'); pg.wait_for_timeout(300)
    items = pg.evaluate("[...document.querySelectorAll('#cwMenu [data-mn]')].map(b => b.dataset.mn)")
    check(items == ['set', 'star', 'arch', 'group', 'read', 'bell', 'rf'], 'the menu: Settings, Starred, Archived, New group, Mark all read, Notifications, Refresh (%s)' % items)
    check(pg.evaluate(CUT, '.cw2-pop button span') == [], 'every menu item fits: %s' % pg.evaluate(CUT, '.cw2-pop button span'))
    shot(pg, 'menu', lang)
    back(pg, 700); check(not vis(pg, '#cwMenu') and view(pg) == 'vChat', 'Back closes the menu, still on Chat')
    pg.click('#cwMore'); pg.wait_for_timeout(300); pg.mouse.click(30, 600); pg.wait_for_timeout(500)
    check(not vis(pg, '#cwMenu') and view(pg) == 'vChat', 'a tap outside closes it')
    pg.click('#cwMore'); pg.wait_for_timeout(300); pg.keyboard.press('Escape'); pg.wait_for_timeout(400); check(not vis(pg, '#cwMenu'), 'Esc closes it')
    for k, sel in [('set', '[data-testid=cw_settings_sheet]'), ('star', '.tsh #cwSl'), ('group', '.tsh, .bsh'), ('bell', '.bsh, .tsh, .npg')]:
        menu(pg, k, 900); check(vis(pg, sel), '⋯ › %s opens' % k)
        back(pg, 900); check(not vis(pg, '.tsh, .bsh, .npg') and view(pg) == 'vChat' and h(pg) == '#chat', '… and Back closes it, still on Chat (%s)' % k)
    menu(pg, 'arch', 700); check(vis(pg, '#cwArchHd') and rows(pg) == ['d:grace'] and not vis(pg, '#cwChips'), '⋯ › Archived shows the archived chat')
    back(pg, 800); check(not vis(pg, '#cwArchHd') and len(rows(pg)) == 7, 'Back comes back to all chats')
    menu(pg, 'rf', 1600); check(view(pg) == 'vChat' and pg.evaluate("[...document.querySelectorAll('.toast')].some(t => /Updated|Imesasishwa/.test(t.textContent))"), '⋯ › Refresh updates in place')
    back(pg, 1200); check(view(pg) == 'vMap', 'after all that, Back leaves Chat: no steps left over (%s)' % view(pg))
    tabbar(pg, 'vChat', 1400); pg.click('#cwMe'); pg.wait_for_timeout(1200)
    check(view(pg) == 'vMe' and h(pg) == '#me', 'the Me photo opens Me (%s)' % h(pg))

def s_refresh(pg, check, lang, cm):
    tabbar(pg, 'vChat', 1600); chip(pg, 'team')
    n = len(ERRS); pg.reload(); pg.wait_for_timeout(2600)
    check(h(pg) == '#chat' and view(pg) == 'vChat' and rows(pg) == ['t:all', 't:ke'] and vis(pg, '[data-testid=cw_field]'), 'a reload comes back to the chat list, same chip, field row (%s %s)' % (h(pg), rows(pg)))
    check(len([e for e in ERRS[n:] if 'WebSocket' not in e]) == 0, 'no console errors after the reload')
    chip(pg, 'all'); pg.evaluate('scrollTo(0, 200)'); pg.wait_for_timeout(300)
    pg.evaluate("""() => { window.__churn = 0; window.__blink = []; const mo = new MutationObserver(ms => ms.forEach(m => { if (m.type === 'childList' && m.target.closest && m.target.closest('#cwList, #cwField, #cwChips, #cwNote') && m.removedNodes.length && [...m.removedNodes].some(x => x.nodeType === 1 && !x.classList.contains('avph'))) window.__churn++;
      if (m.type === 'attributes' && m.target.tagName === 'SECTION' && /view-(in|fwd|back)/.test(m.target.className)) window.__blink.push(m.target.id); }));
      mo.observe(document.querySelector('main'), { childList: true, subtree: true, attributes: true, attributeFilter: ['class'] }); }""")
    pg.click('#tabs button[data-v=vChat]'); pg.wait_for_timeout(60)
    anim = pg.evaluate("[...document.querySelectorAll('main > section')].filter(s => !s.classList.contains('hidden')).flatMap(s => s.getAnimations()).filter(a => a.playState === 'running').length")
    pg.wait_for_timeout(1200)
    got = pg.evaluate('[window.__blink, window.__churn, scrollY]')
    check(view(pg) == 'vChat' and not got[0] and not anim, 're-tapping Chat: no fade or slide (%s, %d running)' % (got[0], anim))
    check(got[1] == 0, 're-tapping Chat: no row is drawn again, nothing blinks (%d)' % got[1])
    check(got[2] == 0, 're-tapping Chat: back at the top (%d)' % got[2])

def s_screens(pg, check, lang, cm):
    for w in (320, 360, 390):
        for theme in ('', 'dark', 'sun'):
            tn = theme or 'light'
            pg.set_viewport_size({'width': w, 'height': 800})
            pg.evaluate("t => { if (t) localStorage.setItem('rfPinTheme', t); else localStorage.removeItem('rfPinTheme'); localStorage.removeItem('rfChatNoteAsk'); }", theme); pg.goto(BASE + '/pin#chat'); pg.reload(); pg.wait_for_timeout(2400)
            check(view(pg) == 'vChat', '%d %s: on the list' % (w, tn))
            check(pg.evaluate(NOOVER), '%d %s: nothing wider than the screen' % (w, tn))
            bad = pg.evaluate(OFF); check(bad == [], '%d %s: nothing outside the screen: %s' % (w, tn, bad))
            bad = pg.evaluate(BROKEN, '#vChat'); check(bad == [], '%d %s: no word breaks: %s' % (w, tn, bad))
            bad = pg.evaluate(CUT, CUTSEL); check(bad == [], '%d %s: no words cut off: %s' % (w, tn, bad))
            bad = pg.evaluate(ROWLAP); check(bad == [], '%d %s: nothing in a row covers anything: %s' % (w, tn, bad))
            bad = pg.evaluate(FIELDLAP); check(bad == [], '%d %s: field labels fit: %s' % (w, tn, bad))
            bad = pg.evaluate(FABLAP); check(bad == [], '%d %s: the green button covers no row at the end: %s' % (w, tn, bad))
            pg.evaluate('scrollTo(0, 0)'); shot(pg, 'list-%d-%s' % (w, tn), lang)
            if w == 320:
                pg.click('#cwMore'); pg.wait_for_timeout(300)
                bad = pg.evaluate(OFF); check(bad == [], '%d %s: the menu fits on the screen: %s' % (w, tn, bad))
                bad = pg.evaluate(CUT, '.cw2-pop button span'); check(bad == [], '%d %s: menu items fit: %s' % (w, tn, bad))
                shot(pg, 'menu-%d-%s' % (w, tn), lang); pg.keyboard.press('Escape'); pg.wait_for_timeout(200)
    # larger text: the same five sizes grow evenly, still fits
    for size in ('l', 'xl'):
        pg.set_viewport_size({'width': 360, 'height': 800})
        pg.evaluate("s => { localStorage.removeItem('rfPinTheme'); localStorage.setItem('rfPinSize', s); }", size); pg.goto(BASE + '/pin#chat'); pg.reload(); pg.wait_for_timeout(2400)
        check(pg.evaluate(NOOVER), '%s text: nothing wider than the screen' % size)
        bad = pg.evaluate(CUT, CUTSEL); check(bad == [], '%s text: no words cut off: %s' % (size, bad))
        bad = pg.evaluate(ROWLAP); check(bad == [], '%s text: nothing in a row covers anything: %s' % (size, bad))
        bad = pg.evaluate(BROKEN, '#vChat'); check(bad == [], '%s text: no word breaks: %s' % (size, bad))
        shot(pg, 'text-%s' % size, lang)
    pg.evaluate("localStorage.removeItem('rfPinSize')")

def s_desktop(pg, check, lang, cm):
    for w in (1280, 1440):
        for theme in ('', 'dark', 'sun'):
            tn = theme or 'light'
            pg.set_viewport_size({'width': w, 'height': 860})
            pg.evaluate("t => { if (t) localStorage.setItem('rfPinTheme', t); else localStorage.removeItem('rfPinTheme'); }", theme); pg.goto(BASE + '/pin#chat'); pg.reload(); pg.wait_for_timeout(2400)
            check(vis(pg, 'header.top') and not vis(pg, '#cwMe') and vis(pg, '#cwChips') and vis(pg, '#cwNew'), '%d %s: two panes; the app bar stays, the list has the new header' % (w, tn))
            g = pg.evaluate("(() => { const p = document.getElementById('vChat').getBoundingClientRect(), f = document.getElementById('cwNew').getBoundingClientRect(), t = document.getElementById('cwTop').getBoundingClientRect(); return [p.left, p.right, p.bottom, f.left, f.right, f.bottom, t.top, p.top]; })()")
            check(g[0] <= g[3] and g[4] <= g[1] and g[5] <= g[2], '%d %s: the green button sits inside the list pane (%s)' % (w, tn, g))
            check(abs(g[6] - g[7]) <= 1, '%d %s: the header starts at the top of the pane (%s)' % (w, tn, g))
            check(pg.evaluate(NOOVER), '%d %s: fits' % (w, tn))
            bad = pg.evaluate(CUT, CUTSEL); check(bad == [], '%d %s: no words cut off: %s' % (w, tn, bad))
            bad = pg.evaluate(ROWLAP); check(bad == [], '%d %s: nothing in a row covers anything: %s' % (w, tn, bad))
            pg.mouse.move(2, 300); shot(pg, 'desk-%d-%s' % (w, tn), lang)
            if not theme:
                pg.click('.cw-row[data-open="d:meril"]'); pg.wait_for_timeout(1500)
                check(view(pg) == 'vRoom' and vis(pg, '#cwList') and vis(pg, '#cwNew') and vis(pg, '.cw-row.cur[data-open="d:meril"]'), '%d: a chat opens on the right, the list stays with its button' % w)
                pg.evaluate("document.querySelector('#vChat').scrollTop = 400"); pg.wait_for_timeout(200)
                gt = pg.evaluate("(() => [document.getElementById('cwTop').getBoundingClientRect().top, document.getElementById('vChat').getBoundingClientRect().top])()")
                check(abs(gt[0] - gt[1]) <= 1, '%d: scrolled, the header stays at the top of the pane (%s)' % (w, gt))
                shot(pg, 'desk-%d-room' % w, lang)
                pg.keyboard.press('Control+k'); pg.wait_for_timeout(300); check(pg.evaluate("document.activeElement.id") == 'cwQ', '%d: Ctrl+K opens the search' % w)
                back(pg, 900); check(not vis(pg, '#cwQ'), '%d: Back closes it' % w)

def p102(cm):
    cm.chats['p:102'] = {'id': 'p:102', 'kind': 'project', 'title': 'Chepkongi Dispensary', 'photo': '', 'project': '102', 'members': ['Reid Anderson', 'Meril Kiprop'], 'admins': ['Reid Anderson'], 'unread': 0, 'mention': False, 'pinned': False, 'muted': 0, 'archived': False, 'onlyAdmins': False,
                         'last': None, 'wallpaper': '', 'description': '', 'bubble': '', 'pins': [], 'seen': [], 'mentionId': 0, 'country': ''}
    cm.msgs['p:102'] = []
def nobody(cm): p102(cm); cm.presence = {}

SEQ = [('Look: header, rows, dividers, project pictures, Announcements, Archived, alert line, new-chat button', s_look, {'pre': p102}),
       ('Chips: counts, filters, no Back step, kept on reload, Unread empty', s_chips, {'pre': p102}),
       ('In the field now (admin): sharing, at a project, on the road, online, tap to chat', s_field, {'pre': p102}),
       ('In the field now (field user): Online and last seen only', s_field_user, {'pre': p102, 'admin': False}),
       ('In the field now: hidden when nobody is around', s_field_none, {'pre': nobody, 'tm': {'ok': True, 'people': [], 'visits': []}}),
       ('Back: search in place, the ⋯ menu and each of its items, the Me photo', s_back, {'pre': p102}),
       ('Refresh keeps the list; re-tapping Chat does not blink', s_refresh, {'pre': p102}),
       ('Screenshots: 320 / 360 / 390, light / dark / sun, larger text', s_screens, {'pre': p102}),
       ('A computer: two panes at 1280 and 1440, light / dark / sun', s_desktop, {'pre': p102, 'w': 1280})]
with sync_playwright() as p:
    br = p.chromium.launch()
    for lang in LANGS:
        for name, fn, kw in SEQ:
            seq(name, lang, fn, br, ChatMock(), **dict(kw))
    br.close()
bad = [r for r in RESULTS if not r[2]]
print('\n%d of %d sequences pass' % (len(RESULTS) - len(bad), len(RESULTS)))
sys.exit(1 if bad else 0)
