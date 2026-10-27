# Me is easy to find, profile photos and centred content (mew): the labelled Me pill at top left, the one-time hint,
# profile photos (set, change, remove; shown on every avatar; initials when there is none or it fails), and every
# main screen in a centred column on wide screens. Mocked APIs (incl. /api/team-photo and /api/chat/*). PASS / FAIL.
#   BASE=http://localhost:8907 OUT=/tmp/me python3 qa/pin/me.py [all|pill|hint|photo|centre]
#   centre widths: 768 1024 1280 1440; the pill: 320 360 390 in EN and SW, light / dark / sun
import os, re, sys, json, datetime, io
from playwright.sync_api import sync_playwright
HERE = os.path.dirname(os.path.abspath(__file__)); sys.path.insert(0, HERE)
from chat_mock import ChatMock, png
BASE = os.environ.get('BASE', 'http://localhost:8907')
OUT = os.environ.get('OUT', os.path.join(HERE, 'me')); os.makedirs(OUT, exist_ok=True)
TILE = open(os.path.join(HERE, 'tile.png'), 'rb').read()
WHAT = sys.argv[1] if len(sys.argv) > 1 else 'all'
now = datetime.datetime.now(datetime.timezone.utc)
iso = lambda h: (now - datetime.timedelta(hours=h)).strftime('%Y-%m-%dT%H:%M:%SZ')
day = lambda d: (now + datetime.timedelta(days=d)).strftime('%Y-%m-%d')
def pj(i, t, area, ll, ty='Borehole well', ck='ke', c='Kenya'):
    d = {'id': i, 'title': t, 'country': c, 'ck': ck, 'area': area, 'type': ty, 'status': 'Completed'}
    if ll: d['gps'] = ll
    return d
P = [dict(pj('101', 'Kapsaret Borehole', 'Kapsaret, Eldoret', [0.47, 35.25]), photo='ph101'), pj('102', 'Chepkongi Dispensary', 'Eldoret', [0.5143, 35.2698], 'Health'),
     pj('103', 'Turbo Spring', 'Turbo', [0.63, 35.05], 'Water collection'), pj('104', 'Nzoia Bridge Primary School', 'Bungoma', [0.58, 34.62], 'School'),
     pj('105', 'Tamiha Children’s Home', 'Kitale', [1.0, 35.0], 'Orphanage'), pj('106', 'Kaptebee Pigsty', 'Nandi', [0.2, 35.1], 'Farming')]
REP = {'101': {'state': 'notworking', 'by': 'Bertin Mwangi', 'at': iso(30), 't': 'Kapsaret Borehole', 'urgency': 'urgent', 'step': 'assigned', 'assignee': 'Meril Kiprop'},
       '104': {'state': 'repair', 'by': 'Grace Achieng', 'at': iso(80), 't': 'Nzoia Bridge Primary School', 'step': 'reported'}}
ME = 'Reid Anderson'
# profile photos the server knows (lower-case name → version); PHOTO_BYTES: what GET /api/team-photo answers
PH = {'meril kiprop': 'v1', 'grace achieng': 'v1', 'jessica anderson': 'v1'}
NAMES = {'meril kiprop': 'Meril Kiprop', 'grace achieng': 'Grace Achieng', 'jessica anderson': 'Jessica Anderson', 'reid anderson': 'Reid Anderson', 'bertin mwangi': 'Bertin Mwangi'}
COL = {'meril kiprop': (28, 156, 90), 'grace achieng': (194, 84, 40), 'jessica anderson': (122, 79, 208), 'reid anderson': (10, 120, 194), 'bertin mwangi': (200, 150, 20)}
def face(rgb, n=160):
    # a stand-in portrait: a head and shoulders on a soft background
    from PIL import Image, ImageDraw
    im = Image.new('RGB', (n, n), tuple(min(255, c + 120) for c in rgb)); d = ImageDraw.Draw(im)
    d.ellipse([n * .3, n * .16, n * .7, n * .6], fill=(120, 84, 60)); d.ellipse([n * .12, n * .62, n * .88, n * 1.3], fill=rgb)
    b = io.BytesIO(); im.save(b, 'PNG'); return b.getvalue()
BROKEN = set()   # names whose photo fails to load (initials must come back)
def url(k): return '/api/team-photo?name=%s&v=%s' % (NAMES[k].replace(' ', '%20'), PH[k]) if k in PH else ''
def photos(): return {NAMES[k]: url(k) for k in PH}
TEAM = [{'name': 'Reid Anderson', 'admin': True, 'source': 'cloudflare', 'home': 'ke', 'phone': '+1 801 555 0101', 'lastSeen': iso(1)},
        {'name': 'Meril Kiprop', 'source': 'app', 'home': 'ke', 'phone': '+254 712 345 678', 'role': 'Field officer', 'lastSeen': iso(2)},
        {'name': 'Grace Achieng', 'source': 'app', 'home': 'ug', 'lastSeen': iso(30)},
        {'name': 'Bertin Mwangi', 'source': 'app', 'home': 'ke', 'phone': '+254 722 111 222', 'lastSeen': iso(26)},
        {'name': 'Jessica Anderson', 'admin': True, 'source': 'app', 'lastSeen': iso(50)}]
def team(): return [dict(t, photo=url(t['name'].lower())) for t in TEAM]
ACT = [{'by': 'Meril Kiprop', 'a': 'visit', 's': 'notworking', 'id': '101', 't': 'Kapsaret Borehole', 'at': iso(3)},
       {'by': 'Grace Achieng', 'a': 'pin', 'id': '103', 't': 'Turbo Spring', 'at': iso(26)}, {'by': ME, 'a': 'visit', 's': 'working', 'id': '102', 't': 'Chepkongi Dispensary', 'at': iso(40)}]
def snap(admin=True, who=ME):
    return {'ok': True, 'who': who, 'admin': admin, 'reviewOpen': 0, 'projects': P, 'repairs': REP, 'visits': {}, 'tests': {}, 'jobs': ['101'] if who == ME else [],
            'assigned': [{'id': '103', 't': 'Turbo Spring', 'to': who, 'by': 'Jessica Anderson', 'at': iso(5), 'date': day(2)}], 'confirmed': {},
            'me': {'kind': 'app', 'nick': '', 'phone': '', 'home': 'ke', 'photo': url(who.lower())}, 'photos': photos(), 'approveAbove': 50000,
            'stages': {'101': {'s': CS['stage'], 'at': DAYS(6), 'by': 'Meril Kiprop', 'pct': 45, 'due': day(-3), 'parent': None, 't': 'Kapsaret Borehole', 'n': 3, 'behind': CS['stage'] != 'handed'}, '105': {'s': 'planned', 'at': DAYS(2), 'by': ME, 'pct': None, 'due': None, 'parent': '101', 't': 'Tamiha Dormitory', 'n': 0, 'behind': False}},
            'stories': {k: {'st': v['status'], 'n': len([a for a in v['answers'].values() if a]), 'b': len(v['photos']['before']), 'i': 0, 'a': len(v['photos']['after']), 'c': v['consent'], 'at': DAYS(1)} for k, v in STORY.items()}}
POSTS = []
# construction (§8) and stories (§9): project 101 is being built (Walls, behind schedule, linked to 104), 102 has an approved story,
# 103 a draft ready; what the app sends is kept in SENT
DAYS = lambda d: (now - datetime.timedelta(days=d)).strftime('%Y-%m-%dT%H:%M:%SZ')
def stage_rec():
    return {'id': '101', 'stage': CS['stage'], 'at': DAYS(6), 'by': 'Meril Kiprop', 'pct': 45, 'due': day(-3), 'behind': CS['stage'] != 'handed', 'parent': None, 'ptitle': '',
            'hist': [{'stage': 'planned', 'at': DAYS(60), 'by': 'Meril Kiprop', 'note': '', 'update': '1', 'photos': []}, {'stage': 'foundation', 'at': DAYS(40), 'by': 'Meril Kiprop', 'note': 'Trenches', 'update': '2', 'photos': ['9001', '9002']},
                     {'stage': 'walls', 'at': DAYS(6), 'by': 'Bertin Mwangi', 'note': '', 'update': '3', 'photos': ['9003']}] + ([{'stage': CS['stage'], 'at': DAYS(0), 'by': ME, 'note': '', 'update': '4', 'photos': []}] if CS['stage'] not in ('walls',) else [])}
CS = {'stage': 'walls'}
STORY = {}
def story_reset():
    STORY.clear(); STORY.update({'102': {'id': '102', 'status': 'approved', 'consent': 'received', 'answers': {'q1': 'Patients waited outside in the rain.', 'q2': 'No clean water for the clinic.', 'q3': '1,200 patients a month', 'q4': 'The community dug the trench.', 'q5': 'Nurses wash their hands between patients.', 'q6': 'The first baby born with clean water at hand.'},
        'quote': 'Now we can wash our hands between patients.', 'quoteBy': 'Mary, nurse', 'credit': 'Meril Kiprop', 'text': 'Before the tank, patients at Chepkongi waited outside.\n\nToday nurses wash their hands between every patient.',
        'photos': {'before': [{'id': '8001', 'name': 'b.jpg', 'children': False, 'by': 'Meril Kiprop', 'at': DAYS(90)}], 'progress': [], 'after': [{'id': '8002', 'name': 'a.jpg', 'children': True, 'by': 'Meril Kiprop', 'at': DAYS(3)}]}},
      '103': {'id': '103', 'status': 'draft', 'consent': 'not-yet', 'answers': {'q1': 'The spring was open to animals.', 'q2': '', 'q3': '', 'q4': '', 'q5': '', 'q6': ''}, 'quote': '', 'quoteBy': '', 'credit': '', 'text': 'Turbo Spring was open to animals.',
        'photos': {'before': [], 'progress': [], 'after': []}}})
story_reset()
PROMPTS = {'q1': {'en': 'What was life like here before the project?', 'sw': 'Maisha yalikuwaje hapa kabla ya mradi?'}, 'q2': {'en': 'What was the main problem people faced?', 'sw': 'Tatizo kubwa ambalo watu walikabili lilikuwa nini?'},
           'q3': {'en': 'Who does this project serve? (who, and how many)', 'sw': 'Mradi huu unawahudumia nani? (nani, na wangapi)'}, 'q4': {'en': 'What part did the community play?', 'sw': 'Jamii ilichangia nini?'},
           'q5': {'en': 'What has changed since?', 'sw': 'Nini kimebadilika tangu hapo?'}, 'q6': {'en': 'Describe one moment that shows the change.', 'sw': 'Eleza tukio moja linaloonyesha mabadiliko.'},
           'quote': {'en': 'A quote from someone it serves', 'sw': 'Nukuu kutoka kwa mtu anayehudumiwa'}, 'quoteBy': {'en': 'Who said it (name and role)', 'sw': 'Aliyesema (jina na nafasi yake)'}, 'credit': {'en': 'Photo credit', 'sw': 'Picha na'}}
def story_of(pid):
    x = STORY.get(pid) or {'id': pid, 'status': 'not-started', 'consent': 'not-yet', 'answers': {q: '' for q in ['q1', 'q2', 'q3', 'q4', 'q5', 'q6']}, 'quote': '', 'quoteBy': '', 'credit': '', 'text': '', 'photos': {'before': [], 'progress': [], 'after': []}}
    STORY[pid] = x; y = dict(x); y['prompts'] = PROMPTS; y['missing'] = []; y['canApprove'] = True; return y
SENT = []
def install(pg, admin=True):
    def pin_api(route, req):
        u = req.url
        if req.method != 'GET':
            b = json.loads(req.post_data or '{}'); SENT.append(b)
            if b.get('action') == 'stage': CS['stage'] = b['stage']; return route.fulfill(json={'ok': True, 'id': b['id'], 'updateId': '9001', 'stage': stage_rec()})
            if b.get('action') == 'stage-meta': return route.fulfill(json={'ok': True, 'id': b['id'], 'stage': stage_rec()})
            if b.get('action') == 'new': return route.fulfill(json={'ok': True, 'id': '401', 'updateId': '9002', 'stage': dict(stage_rec(), id='401', stage='planned') if b.get('build') else None})
            return route.fulfill(json={'ok': True, 'updateId': '9001'})
        if 'activity=' in u: return route.fulfill(json={'ok': True, 'activity': ACT + [{'by': 'Meril Kiprop', 'a': 'stage', 's': 'walls', 'id': '101', 't': 'Kapsaret Borehole', 'at': DAYS(6)}]})
        if 'team=' in u: return route.fulfill(json={'ok': True, 'team': team()})
        if 'history=' in u: return route.fulfill(json={'ok': True, 'notes': []})
        m = re.search(r'[?&]id=(\d+)', u)
        if m:
            pid = m.group(1)
            st = stage_rec() if pid == '101' else None
            lk = {'parent': None, 'children': [{'id': '105', 't': 'Tamiha Dormitory', 's': 'planned'}]} if pid == '101' else {'parent': {'id': '101', 't': 'Kapsaret Borehole', 's': CS['stage']}, 'children': []} if pid == '105' else {'parent': None, 'children': []}
            return route.fulfill(json={'ok': True, 'last': None, 'canUndo': False, 'visits': [], 'costs': {'sum': 0, 'n': 0, 'last': []}, 'stage': st, 'linked': lk})
        return route.fulfill(json=snap(admin))
    def admin_api(route, req):
        u = req.url
        if req.method != 'GET': return route.fulfill(json={'ok': True})
        if 'view=team' in u and 'teammap' not in u: return route.fulfill(json={'ok': True, 'team': team(), 'mail': {'ready': True, 'to': []}, 'cron': True})
        if 'view=teammap' in u: return route.fulfill(json={'ok': True, 'people': [{'name': 'Meril Kiprop', 'lat': 0.52, 'lng': 35.27, 'acc': 8, 'at': iso(0.2), 'photo': url('meril kiprop')}, {'name': 'Bertin Mwangi', 'lat': 0.6, 'lng': 35.1, 'acc': 20, 'at': iso(0.5), 'photo': ''}], 'visits': [], 'since': iso(10)})
        if 'view=costs' in u: return route.fulfill(json={'ok': True, 'rows': [], 'stats': {'month': 0, 'year': 0, 'sum12': 0, 'repairs': 0, 'avg': 0, 'byCountry': {}, 'top': []}, 'approveAbove': 50000})
        return route.fulfill(json={'ok': True, 'items': [], 'rows': [], 'people': [], 'visits': [], 'donors': []})
    def photo_api(route, req):
        from urllib.parse import urlparse, parse_qs
        q = parse_qs(urlparse(req.url).query)
        if req.method == 'GET':
            if 'X-Pin' not in {k.title(): v for k, v in req.headers.items()} and 'x-pin' not in req.headers: return route.fulfill(status=401, json={'ok': False, 'reason': 'signin'})
            k = (q.get('name') or [''])[0].lower()
            if k in BROKEN: return route.fulfill(status=500, body='x')
            if k not in PH: return route.fulfill(status=404, json={'ok': False, 'reason': 'not-found'})
            return route.fulfill(body=face(COL.get(k, (90, 90, 90))), content_type='image/png', headers={'ETag': '"%s"' % PH[k], 'Cache-Control': 'private, max-age=31536000, immutable'})
        k = ((q.get('name') or [ME])[0]).lower()
        ct = req.headers.get('content-type', '')
        body = req.post_data_buffer or b''
        POSTS.append({'method': req.method, 'name': k, 'type': ct, 'size': len(body), 'head': body[:3]})
        if req.method == 'DELETE' or (ct.startswith('application/json') and b'remove' in body):
            PH.pop(k, None); return route.fulfill(json={'ok': True, 'name': NAMES.get(k, k), 'photo': ''})
        PH[k] = 'v%d' % (int(PH.get(k, 'v0')[1:]) + 1); NAMES.setdefault(k, k.title())
        return route.fulfill(json={'ok': True, 'name': NAMES[k], 'photo': url(k)})
    pg.route('**/api/pin-auth/me', lambda r, q: r.fulfill(json={'available': False}))
    pg.route(re.compile(r'/api/pin(\?.*)?$'), pin_api); pg.route(re.compile(r'/api/admin(\?.*)?$'), admin_api)
    pg.route(re.compile(r'/api/team-photo(\?.*)?$'), photo_api)
    def pin_photo(route, req):
        if req.method == 'GET': return route.fulfill(body=face((200, 150, 20)), content_type='image/png')
        SENT.append({'photo': True, 'body': (req.post_data_buffer or b'')[:600]}); return route.fulfill(json={'ok': True, 'asset': '9100'})
    pg.route(re.compile(r'/api/pin-photo(\?.*)?$'), pin_photo)
    def story_api(route, req):
        from urllib.parse import urlparse, parse_qs
        q = parse_qs(urlparse(req.url).query)
        if req.method == 'GET':
            if 'asset' in q: return route.fulfill(body=face((122, 79, 208)), content_type='image/png')
            return route.fulfill(json={'ok': True, 'story': story_of(q['id'][0])})
        ct = req.headers.get('content-type', '')
        if ct.startswith('multipart'):
            raw = req.post_data_buffer or b''; SENT.append({'story-multipart': True, 'body': raw[:800]})
            pid = re.search(rb'name="id"\r\n\r\n(\d+)', raw).group(1).decode(); row = re.search(rb'name="row"\r\n\r\n(\w+)', raw)
            x = STORY.setdefault(pid, story_of(pid))
            if row: x['photos'][row.group(1).decode()].append({'id': '8100', 'name': 'n.jpg', 'children': b'name="children"\r\n\r\ntrue' in raw, 'by': ME, 'at': DAYS(0)})
            return route.fulfill(json={'ok': True, 'story': story_of(pid), 'typed': False, 'text': ''})
        b = json.loads(req.post_data or '{}'); SENT.append(dict(b, story=True)); pid = b['id']; x = story_of(pid); x = STORY[pid]
        a = b.get('action')
        if a == 'answers':
            for k in ['q1', 'q2', 'q3', 'q4', 'q5', 'q6']:
                if k in b: x['answers'][k] = b[k]
            for k in ['quote', 'quoteBy', 'credit']:
                if k in b: x[k] = b[k]
            if x['status'] == 'not-started': x['status'] = 'collecting'
        elif a == 'consent': x['consent'] = b['value']
        elif a == 'draft': x['text'] = 'A draft from the answers.'; x['status'] = 'draft'
        elif a == 'approve':
            if not admin: return route.fulfill(status=403, json={'ok': False, 'reason': 'admin-only'})
            x['status'] = 'approved'
        elif a == 'publish': x['status'] = 'published'
        elif a == 'collecting': x['status'] = 'collecting'
        return route.fulfill(json={'ok': True, 'story': story_of(pid), 'text': x['text'], 'how': 'template'})
    pg.route(re.compile(r'/api/story(\?.*)?$'), story_api)
    pg.route(re.compile(r'/api/(place|sms-admin)(\?.*)?$'), lambda r, q: r.fulfill(json={'ok': True, 'places': []}))
    pg.route('**/api/photo/**', lambda r, q: r.fulfill(body=TILE, content_type='image/png'))
    pg.route(re.compile(r'https://(www\.google\.com/s2|fonts\.googleapis\.com)/.*'), lambda r, q: r.abort())

ERRS = []
def context(br, lang='en', w=390, theme='', hint=False, admin=True, h=None, cm=None, reduce=False):
    mob = w < 1000
    ctx = br.new_context(viewport={'width': w, 'height': h or (844 if mob else 900)}, device_scale_factor=2 if w <= 430 else 1, is_mobile=mob, has_touch=mob, service_workers='block', locale='en-GB', timezone_id='Africa/Nairobi',
                         geolocation={'latitude': 0.515, 'longitude': 35.27, 'accuracy': 8}, permissions=['geolocation'], reduced_motion='reduce' if reduce else 'no-preference')
    ctx.route(re.compile(r'https://(tile\.openstreetmap\.org|server\.arcgisonline\.com)/.*'), lambda r, q: r.fulfill(status=200, body=TILE, headers={'Content-Type': 'image/png', 'Access-Control-Allow-Origin': '*'}))
    pg = ctx.new_page(); install(pg, admin); (cm or ChatMock()).install(pg)
    pg.on('pageerror', lambda e: ERRS.append('pageerror: ' + str(e)[:200]))
    pg.on('console', lambda m: ERRS.append('console: ' + m.text[:200]) if m.type == 'error' and 'Failed to load resource' not in m.text else None)
    pg.goto(BASE + '/pin'); pg.wait_for_timeout(500)
    pg.evaluate("([l, t, hint]) => { localStorage.setItem('rfPinOnb', '1'); if (l === 'sw') localStorage.setItem('rfPinLang', 'sw'); if (t) localStorage.setItem('rfPinTheme', t); if (!hint) localStorage.setItem('rfPinMeHint', '1'); }", [lang, theme, hint])
    pg.reload(); pg.wait_for_timeout(500)
    for d in '9999': pg.click('.pk[data-k="%s"]' % d)
    pg.wait_for_timeout(1600)
    return ctx, pg

def h(pg): return pg.evaluate('location.hash')
def view(pg): return pg.evaluate("['vMap','vList','vSearch','vRoute','vProj','vNew','vMe','vReport','vOffice','vChat','vRoom'].filter(v => !document.getElementById(v).classList.contains('hidden'))[0]")
def back(pg, t=800): pg.evaluate('history.back()'); pg.wait_for_timeout(t)
def vis(pg, sel): loc = pg.locator(sel); return loc.count() > 0 and loc.first.is_visible()
def shot(pg, name, full=False): pg.wait_for_timeout(250); fn = os.path.join(OUT, name + '.png'); pg.screenshot(path=fn, full_page=full); return fn
RESULTS = []
def seq(name, fn, *a):
    notes = []
    def check(c, what): (None if c else notes.append(what))
    try: fn(check, *a)
    except Exception as e: notes.append('error: ' + str(e).split('\n')[0][:220])
    RESULTS.append((name, not notes, notes))
    print(('PASS ' if not notes else 'FAIL ') + name + ('' if not notes else ' :: ' + ' | '.join(notes)), flush=True)

# --- centring ------------------------------------------------------------------------------------------------
# every main screen, how to get there, and whether it is full-bleed (the map) or a two-pane (chat at ≥ 1000)
SCREENS = [('map', '#map'), ('list', '#list'), ('new', '#new'), ('me', '#me'), ('team', '#me/team'), ('office', '#office'), ('office-team', '#office/team'),
           ('office-repairs', '#office/repairs'), ('chat', '#chat'), ('room', '#chat/d:meril'), ('project', '#p/101'), ('report', '#report')]
# the main column: where the visible screen's content really is (its children, not the section box)
GEOM = """() => { const v = ['vMap','vList','vSearch','vRoute','vProj','vNew','vMe','vReport','vOffice','vChat','vRoom'].find(x => !document.getElementById(x).classList.contains('hidden'));
  const sec = document.getElementById(v), W = document.documentElement.clientWidth;
  let l = 1e9, r = -1e9;
  for (const el of sec.querySelectorAll('*')) { const cs = getComputedStyle(el); if (cs.position === 'fixed' || cs.display === 'none' || cs.visibility === 'hidden') continue;
    const b = el.getBoundingClientRect(); if (b.width < 2 || b.height < 2 || b.bottom < 0 || b.top > innerHeight) continue; if (el.closest('.leaflet-container') && v !== 'vMap') continue;
    let clip = false; for (let a = el.parentElement; a && a !== sec; a = a.parentElement) { const ox = getComputedStyle(a).overflowX; if (ox !== 'visible') { clip = true; break; } } if (clip) continue; // inside a sideways scroller (chips, quick replies)
    if (b.left < l) l = b.left; if (b.right > r) r = b.right; }
  const over = document.documentElement.scrollWidth > W + 1;
  return { v, W, l: Math.round(l), r: Math.round(r), left: Math.round(l), right: Math.round(W - r), over }; }"""
def go(pg, hsh, t=1400):
    pg.evaluate('h => { location.hash = h; }', hsh); pg.wait_for_timeout(t)
def centre(check, widths, langs, themes, tag, judge=True, shots=True):
    with sync_playwright() as p:
        br = p.chromium.launch()
        for w in widths:
            for lang in langs:
                for th in themes:
                    ctx, pg = context(br, lang, w, th, h=900)
                    for name, hsh in SCREENS:
                        go(pg, hsh)
                        g = pg.evaluate(GEOM)
                        fn = '%s-%s-%d-%s%s' % (tag, name, w, lang, ('-' + th) if th else '')
                        if shots: shot(pg, fn)
                        if not judge: continue
                        check(not g['over'], '%s: no sideways scroll (%s)' % (fn, g))
                        if name in ('map',) or (name in ('chat', 'room') and w >= 1000): continue
                        check(abs(g['left'] - g['right']) <= 24, '%s: content centred (left %d, right %d)' % (fn, g['left'], g['right']))
                    ctx.close()
        br.close()

# --- the pill -------------------------------------------------------------------------------------------------
HEAD = """() => { const r = s => { const e = document.querySelector(s); if (!e || !e.offsetParent) return null; const b = e.getBoundingClientRect(); return [b.left, b.top, b.right, b.bottom]; };
  const wm = document.querySelector('.top .ht img:not([style*="none"])'); return { pill: r('#hAv'), ht: r('.top .ht'), rf: r('#bRefresh'), bell: r('#meBell'), word: !!(document.querySelector('#hAv .hav-t') || {}).offsetParent,
  person: !!(document.querySelector('#hAv .hav-p') || {}).getBoundingClientRect().width, text: (document.querySelector('#hAv .hav-t') || {}).textContent, ring: document.getElementById('hAv').getAttribute('aria-current') }; }"""
def lap(a, b): return a and b and min(a[2], b[2]) - max(a[0], b[0]) > 0 and min(a[3], b[3]) - max(a[1], b[1]) > 0
def s_pill(check):
    with sync_playwright() as p:
        br = p.chromium.launch()
        for w in (320, 360, 390):
            for lang in ('en', 'sw'):
                for th in ('', 'dark', 'sun'):
                    for admin in (True, False):
                        if th and not admin: continue
                        ctx, pg = context(br, lang, w, th, admin=admin); t = '%d-%s%s%s' % (w, lang, '-' + th if th else '', '' if admin else '-field')
                        g = pg.evaluate(HEAD)
                        check(g['pill'] and g['pill'][3] - g['pill'][1] >= 44, '[%s] the pill is at least 44 px tall (%s)' % (t, g['pill']))
                        check(not lap(g['pill'], g['ht']) and not lap(g['pill'], g['rf']) and not lap(g['pill'], g['bell']), '[%s] the pill clears the wordmark, refresh and bell (%s)' % (t, g))
                        check(g['ht'] and g['pill'][2] <= g['ht'][0] - 4, '[%s] a gap before the wordmark' % t)
                        check(g['person'], '[%s] the person icon shows' % t)
                        if w >= 360: check(g['word'] and g['text'] == ('Mimi' if lang == 'sw' else 'Me'), '[%s] the word shows (%s)' % (t, g['text']))
                        tabs = pg.evaluate("[...document.querySelectorAll('#tabs button')].filter(b => b.offsetParent).map(b => [b.textContent.trim(), b.scrollWidth <= b.clientWidth + 1, b.getClientRects().length])")
                        check(len(tabs) == 5 and all(x[1] for x in tabs), '[%s] five tabs, nothing cut (%s)' % (t, tabs))
                        if not admin: check(any(x[0].startswith('Mimi' if lang == 'sw' else 'Me') for x in tabs), '[%s] a field user has the Me tab' % t)
                        pg.click('#hAv'); pg.wait_for_timeout(1200)
                        g2 = pg.evaluate(HEAD); check(view(pg) == 'vMe' and g2['ring'] == 'page', '[%s] the pill opens Me and shows it is open' % t)
                        if not admin: check(pg.get_attribute('#tabs button[data-v=vMe]', 'aria-current') == 'page', '[%s] the Me tab is lit too' % t)
                        if w == 360 or (w == 390 and th == ''): pg.evaluate('scrollTo(0,0)'); shot(pg, 'pill-%s' % t)
                        # a scrolled page title stays clear of the pill
                        pg.evaluate("scrollTo(0, 600)"); pg.wait_for_timeout(500)
                        tt = pg.evaluate("(() => { const t = document.getElementById('hTitle'), b = t.getBoundingClientRect(), a = document.getElementById('hAv').getBoundingClientRect(); return [getComputedStyle(t).opacity, b.left, a.right]; })()")
                        check(float(tt[0]) < 0.5 or tt[1] >= tt[2] + 4, '[%s] the scrolled title clears the pill (%s)' % (t, tt))
                        # Back replaces the pill on deeper screens
                        go(pg, '#p/101'); check(not vis(pg, '#hAv') and vis(pg, '#hBack'), '[%s] Back replaces the pill on a project' % t)
                        ctx.close()
        br.close()

# --- the hint -------------------------------------------------------------------------------------------------
def s_hint(check):
    with sync_playwright() as p:
        br = p.chromium.launch()
        for lang in ('en', 'sw'):
            for th in ('', 'dark', 'sun'):
                ctx, pg = context(br, lang, 390, th, hint=True); t = lang + ('-' + th if th else '')
                pg.wait_for_timeout(900)
                check(vis(pg, '.mehint'), '[%s] the hint shows the first time (admin)' % t)
                tx = pg.inner_text('.mehint p') if vis(pg, '.mehint') else ''
                check(tx == ('Mimi iko hapa sasa: kazi yako, mipangilio na timu' if lang == 'sw' else 'Me is here now: your work, settings and team'), '[%s] its words (%s)' % (t, tx))
                b = pg.evaluate("(() => { const h = document.querySelector('.mehint').getBoundingClientRect(), a = document.getElementById('hAv').getBoundingClientRect(), tb = document.getElementById('tabs').getBoundingClientRect(), ar = document.querySelector('.mehint-ar').getBoundingClientRect();"
                                " return { under: h.top >= a.bottom, clearBar: h.bottom < tb.top, arrow: ar.left + ar.width / 2 > a.left && ar.left + ar.width / 2 < a.right, inside: h.left >= 0 && h.right <= innerWidth, lines: Math.round(document.querySelector('.mehint p').getBoundingClientRect().height) }; })()")
                check(b['under'] and b['clearBar'] and b['arrow'] and b['inside'], '[%s] under the pill, pointing at it, clear of the bottom bar (%s)' % (t, b))
                shot(pg, 'hint-%s' % t)
                if th == '':
                    # Back closes it first, still on Map
                    back(pg); check(not vis(pg, '.mehint') and view(pg) == 'vMap' and h(pg) == '#map', '[%s] Back closes the hint, still on Map' % t)
                    pg.reload(); pg.wait_for_timeout(2200); check(not vis(pg, '.mehint'), '[%s] never shown again' % t)
                ctx.close()
        # the first tap anywhere closes it and still reaches the map
        ctx, pg = context(br, 'en', 390, hint=True); pg.wait_for_timeout(900)
        x, y = pg.evaluate("""(() => { const r = document.getElementById('hMap').getBoundingClientRect(); for (let fy = .5; fy < .8; fy += .05) for (let fx = .15; fx < .7; fx += .06) { const x = r.left + r.width * fx, y = r.top + r.height * fy, e = document.elementFromPoint(x, y); if (e && (e.classList.contains('leaflet-tile') || e.id === 'hMap')) return [x, y]; } return [r.left + 40, r.top + r.height * .6]; })()""")
        pg.mouse.click(x, y); pg.wait_for_timeout(1000)
        check(not vis(pg, '.mehint') and not pg.evaluate("document.getElementById('vMap').classList.contains('mcl-clean')"), 'the first map tap closes the hint and still reveals the map tiles')
        check(h(pg) == '#map' and view(pg) == 'vMap', 'still on Map after that tap (%s)' % h(pg))
        ctx.close()
        ctx, pg = context(br, 'en', 390, hint=True); pg.wait_for_timeout(900); pg.click('.mehint-ok'); pg.wait_for_timeout(500)
        check(not vis(pg, '.mehint') and h(pg) == '#map', '“Got it” closes it')
        ctx.close()
        ctx, pg = context(br, 'en', 390, hint=True, admin=False); pg.wait_for_timeout(1200); check(not vis(pg, '.mehint'), 'field users (Me is in their bar) get no hint'); ctx.close()
        ctx, pg = context(br, 'en', 390, hint=True, reduce=True); pg.wait_for_timeout(900)
        check(vis(pg, '.mehint') and pg.evaluate("getComputedStyle(document.querySelector('.mehint')).transitionDuration.split(',').every(x => parseFloat(x) === 0)"), 'reduced motion: the hint has no animation')
        ctx.close(); br.close()

# --- photos ---------------------------------------------------------------------------------------------------
def has_img(pg, sel): return pg.evaluate("s => [...document.querySelectorAll(s)].some(e => { const i = e.querySelector(':scope > img.avph'); return i && i.complete && i.naturalWidth > 0; })", sel)
def make_png(fn, w=900, h=600):
    from PIL import Image, ImageDraw
    im = Image.new('RGB', (w, h), (150, 200, 230)); d = ImageDraw.Draw(im)
    d.rectangle([0, h * .7, w, h], fill=(90, 150, 80)); d.ellipse([w * .4, h * .2, w * .6, h * .5], fill=(120, 84, 60)); d.ellipse([w * .3, h * .48, w * .7, h * 1.1], fill=(10, 120, 194))
    im.save(fn)
def s_photo(check):
    from PIL import Image
    BROKEN.add('grace achieng')
    pic = os.path.join(OUT, '_pick.png'); make_png(pic)
    with sync_playwright() as p:
        br = p.chromium.launch()
        for lang, th in (('en', ''), ('sw', 'dark'), ('en', 'sun')):
            t = lang + ('-' + th if th else '')
            ctx, pg = context(br, lang, 390, th)
            check(not has_img(pg, '#hAv .hav-a'), '[%s] no photo yet: initials in the pill' % t)
            go(pg, '#me/team', 1800)
            check(has_img(pg, '.mates [data-ph="Meril Kiprop"]'), '[%s] Me › Team: Meril’s photo' % t)
            check(not has_img(pg, '.mates [data-ph="Grace Achieng"]') and pg.locator('.mates [data-ph="Grace Achieng"]').inner_text().strip() == 'GA', '[%s] a photo that fails to load: initials' % t)
            check(not has_img(pg, '.mates [data-ph="Bertin Mwangi"]'), '[%s] no photo: initials' % t)
            shot(pg, 'photo-team-%s' % t)
            go(pg, '#chat', 1800); check(has_img(pg, '#vChat [data-ph="Meril Kiprop"]'), '[%s] chat list: Meril’s photo on the DM' % t); shot(pg, 'photo-chat-%s' % t)
            go(pg, '#chat/t:all', 1800); check(has_img(pg, '#vRoom .cw-sav[data-ph="Jessica Anderson"]'), '[%s] group bubbles: the sender’s face' % t)
            go(pg, '#office/team', 1800); check(has_img(pg, '#vOffice .tm-av[data-ph="Jessica Anderson"]'), '[%s] Office › Team cards' % t)
            # your own photo: Me → tap the avatar → gallery → crop → save
            go(pg, '#me', 1500); pg.click('#meAv'); pg.wait_for_timeout(700)
            check(vis(pg, '[data-testid=ph_sheet]') and vis(pg, '[data-testid=ph_cam]') and vis(pg, '[data-testid=ph_pick]') and not vis(pg, '[data-testid=ph_rm]'), '[%s] the photo sheet: camera, gallery (nothing to remove yet)' % t)
            shot(pg, 'photo-sheet-%s' % t)
            pg.set_input_files('.phs input[data-k=fpick]', pic); pg.wait_for_timeout(900)
            check(vis(pg, '[data-testid=ph_crop]'), '[%s] the crop step' % t)
            pg.fill('[data-testid=ph_zoom]', '1.6'); pg.dispatch_event('[data-testid=ph_zoom]', 'input')
            cb = pg.locator('.phc-c').bounding_box(); pg.mouse.move(cb['x'] + 100, cb['y'] + 100); pg.mouse.down(); pg.mouse.move(cb['x'] + 60, cb['y'] + 80, steps=4); pg.mouse.up()
            shot(pg, 'photo-crop-%s' % t)
            n0 = len(POSTS); pg.click('[data-testid=ph_use]'); pg.wait_for_timeout(1500)
            last = POSTS[-1] if len(POSTS) > n0 else {}
            check(last.get('type', '').startswith('image/jpeg') and 0 < last.get('size', 0) <= 200 * 1024 and last.get('head') == b'\xff\xd8\xff', '[%s] a JPEG of 200 KB or less is sent (%s)' % (t, {k: last.get(k) for k in ('type', 'size')}))
            if last.get('size'):
                pass
            check(not vis(pg, '[data-testid=ph_sheet]') and has_img(pg, '#hAv .hav-a') and has_img(pg, '#meAv'), '[%s] the new photo shows in the pill and on Me straight away' % t)
            shot(pg, 'photo-me-%s' % t)
            # remove it again
            pg.click('#meAv'); pg.wait_for_timeout(700); check(vis(pg, '[data-testid=ph_rm]'), '[%s] now it can be removed' % t)
            pg.click('[data-testid=ph_rm]'); pg.wait_for_timeout(600); pg.click('.bsh.po-ask [data-k=yes]'); pg.wait_for_timeout(1200)
            check(POSTS[-1]['method'] == 'DELETE' and not has_img(pg, '#hAv .hav-a'), '[%s] removed: initials again' % t)
            # an admin sets someone else's photo from their contact sheet
            go(pg, "#office/team", 1500); pg.locator(".tm-card[data-who=\"Bertin Mwangi\"]").first.click(); pg.wait_for_timeout(1000)
            check(vis(pg, '[data-testid=ct_photo]'), '[%s] admin: “Add photo” on Bertin’s contact sheet' % t)
            shot(pg, 'photo-contact-%s' % t)
            pg.click('[data-testid=ct_photo]'); pg.wait_for_timeout(700); pg.set_input_files('.phs input[data-k=fpick]', pic); pg.wait_for_timeout(900); pg.click('[data-testid=ph_use]'); pg.wait_for_timeout(1500)
            check(POSTS[-1]['name'] == 'bertin mwangi' and has_img(pg, '.tsh [data-ph="Bertin Mwangi"]'), '[%s] admin: Bertin’s photo saved (?name=) and shown' % t)
            PH.pop('bertin mwangi', None); PH.pop('reid anderson', None)
            ctx.close()
        # a field user: only their own
        ctx, pg = context(br, 'en', 390, admin=False)
        go(pg, '#me/team', 1800); pg.locator('.mates [data-mate="Grace Achieng"]').first.click(); pg.wait_for_timeout(1000)
        check(not vis(pg, '[data-testid=ct_photo]'), 'field user: no photo button on someone else’s sheet')
        ctx.close()
        # offline: the last photo still shows when the new one can't be loaded
        ctx, pg = context(br, 'en', 390); go(pg, '#me/team', 1800); ok1 = has_img(pg, '.mates [data-ph="Meril Kiprop"]')
        pg.route(re.compile(r'/api/team-photo(\?.*)?$'), lambda r, q: r.abort()); PH['meril kiprop'] = 'v7'
        pg.click('#bRefresh'); pg.wait_for_timeout(1800); go(pg, '#map', 800); go(pg, '#me/team', 1800)
        check(ok1 and has_img(pg, '.mates [data-ph="Meril Kiprop"]'), 'no signal: the last photo kept on the phone still shows')
        PH['meril kiprop'] = 'v1'; ctx.close()
        br.close()
    BROKEN.discard('grace achieng')
    try: os.remove(pic)
    except Exception: pass


# --- construction (§8) ----------------------------------------------------------------------------------------
def wrapcheck(pg, sel):
    # no word split across lines and nothing cut off inside sel
    return pg.evaluate("""s => { const bad = []; document.querySelectorAll(s).forEach(el => { if (!el.offsetParent) return; if (el.scrollWidth > el.clientWidth + 1 && getComputedStyle(el).overflowX !== 'auto') bad.push('cut:' + el.className + ':' + el.textContent.slice(0, 30));
      const w = document.createTreeWalker(el, NodeFilter.SHOW_TEXT); let t; while ((t = w.nextNode())) { const words = t.data.split(/(\\s+)/); let off = 0; for (const wd of words) { if (wd.trim().length > 1) { const r = document.createRange(); r.setStart(t, off); r.setEnd(t, off + wd.length); if (r.getClientRects().length > 1) bad.push('split:' + wd); } off += wd.length; } } }); return bad.slice(0, 6); }""", sel)
def s_build(check):
    pic = os.path.join(OUT, '_site.png'); make_png(pic, 800, 600)
    with sync_playwright() as p:
        br = p.chromium.launch()
        for lang, th in (('en', ''), ('sw', ''), ('en', 'dark'), ('sw', 'sun')):
            t = lang + ('-' + th if th else ''); CS['stage'] = 'walls'; del SENT[:]
            ctx, pg = context(br, lang, 390, th)
            check(pg.locator('#hMap [data-testid=pin_build]').count() >= 1, '[%s] the map pin carries a hard-hat badge' % t)
            go(pg, '#p/101', 1800)
            check(vis(pg, '[data-testid=cn_card]'), '[%s] the Construction card shows' % t)
            check(pg.locator('.cn-st li.now').inner_text().startswith('Kuta' if lang == 'sw' else 'Walls'), '[%s] the stepper is on Walls' % t)
            check(vis(pg, '[data-testid=cn_behind]'), '[%s] Behind schedule' % t)
            pg.wait_for_timeout(600); check(pg.locator('.cn-th img.in').count() == 3, '[%s] the stage galleries load (%d)' % (t, pg.locator('.cn-th img.in').count()))
            check(pg.locator('.cn-link').count() == 1 and 'Tamiha Dormitory' in pg.inner_text('.cn-link'), '[%s] “Also here” names the linked project' % t)
            bad = wrapcheck(pg, '.cn'); check(not bad, '[%s] no word split or cut in the card (%s)' % (t, bad))
            pg.locator('[data-testid=cn_card]').scroll_into_view_if_needed(); shot(pg, 'build-card-%s' % t)
            if lang == 'en' and th == '':
                pg.locator('.cn-th').first.click(); pg.wait_for_timeout(700); check(vis(pg, '.prj-lb'), '[%s] a photo opens full size' % t); pg.keyboard.press('Escape'); pg.wait_for_timeout(300)
                pg.click('[data-testid=cn_next]'); pg.wait_for_timeout(600)
                check(vis(pg, '[data-testid=cn_form]'), '[%s] Move to Roof asks for photos and a note' % t)
                pg.click('[data-testid=cn_save]'); pg.wait_for_timeout(300); check('photo' in pg.inner_text('.cn-f .msg'), '[%s] a photo is required' % t)
                pg.set_input_files('.cn-f input[data-k=fpick]', pic); pg.wait_for_timeout(800); pg.fill('#cnNote', 'Trusses up'); pg.fill('#cnPct', '60')
                shot(pg, 'build-move-%s' % t)
                pg.click('[data-testid=cn_save]'); pg.wait_for_timeout(1800)
                st = [x for x in SENT if x.get('action') == 'stage']; ph = [x for x in SENT if x.get('photo')]
                check(st and st[-1]['stage'] == 'roof' and st[-1]['note'] == 'Trusses up' and st[-1]['pct'] == 60, '[%s] the stage change is sent (%s)' % (t, st[-1:] ))
                check(ph and b'name="stage"' in ph[-1]['body'] and b'roof' in ph[-1]['body'] and b'Construction: Roof' in ph[-1]['body'], '[%s] the photo goes with stage=roof, project and the label' % t)
                check(pg.locator('.cn-st li.now').inner_text().startswith('Roof'), '[%s] the card moves on to Roof' % t)
                # admin hand-over from Finishing, with a confirm
                CS['stage'] = 'finishing'; go(pg, '#list', 900); go(pg, '#p/101', 1800)
                check(vis(pg, '[data-testid=cn_hand]'), '[%s] admins can hand over from Finishing' % t)
                pg.click('[data-testid=cn_hand]'); pg.wait_for_timeout(500); pg.set_input_files('.cn-f input[data-k=fpick]', pic); pg.wait_for_timeout(700); pg.click('[data-testid=cn_save]'); pg.wait_for_timeout(600)
                check(vis(pg, '.bsh.po-ask'), '[%s] handing over asks first' % t); pg.click('.bsh.po-ask [data-k=yes]'); pg.wait_for_timeout(1500)
                check([x for x in SENT if x.get('action') == 'stage'][-1]['stage'] == 'handed', '[%s] handed over is sent' % t)
                CS['stage'] = 'walls'
                # the list filter, the Office count, the report
                go(pg, '#list', 1200); pg.click('#lChips [data-f=build]'); pg.wait_for_timeout(800)
                check(pg.locator('#list [data-testid=row_build]').count() >= 1, '[%s] Projects › Under construction lists it with a stage tag' % t); shot(pg, 'build-list-%s' % t)
                go(pg, '#office', 1800); check(vis(pg, '[data-testid=ocv_t_build]'), '[%s] the Office overview counts it' % t)
                go(pg, '#report', 2200); check('Construction' in pg.inner_text('#repBody'), '[%s] the monthly report has a Construction section' % t)
                pg.wait_for_timeout(800); check(pg.locator('.rep-cnph img.in').count() >= 1, '[%s] with this month’s photos' % t)
                go(pg, '#me/team', 1800); fs = pg.locator('#actFeed [data-testid=feed_stage]')
                check(fs.count() >= 1 and ('Walls' if lang == 'en' else 'Kuta') in fs.first.inner_text(), '[%s] the team feed shows the stage change' % t)
                if fs.count(): fs.first.scroll_into_view_if_needed(); shot(pg, 'build-feed-%s' % t)
                # New site: what's happening here + part of
                go(pg, '#new', 1600); pg.fill('#nName', 'Kapsaret Dormitory'); pg.click('#nBuild [data-v=building]'); pg.fill('#nPartQ', 'Kapsa'); pg.wait_for_timeout(400)
                pg.locator('#nPartL [data-id="101"]').click(); pg.wait_for_timeout(300)
                check(vis(pg, '#nPartOn') and 'Kapsaret Borehole' in pg.inner_text('#nPartOn'), '[%s] Part of: picked' % t)
                pg.click('#nGps'); pg.wait_for_timeout(1800); pg.locator('#nBuild').scroll_into_view_if_needed(); shot(pg, 'build-new-%s' % t)
                pg.click('#bNewSave'); pg.wait_for_timeout(600)
                if vis(pg, '.bsh [data-ask=y]'): pg.click('.bsh [data-ask=y]')
                pg.wait_for_timeout(1500)
                nw = [x for x in SENT if x.get('action') == 'new']
                check(nw and nw[-1].get('build') == 'building' and nw[-1].get('parent') == '101', '[%s] New site sends build and parent (%s)' % (t, nw[-1:]))
            ctx.close()
        # the New site choice at 360 in Kiswahili: whole words only
        ctx, pg = context(br, 'sw', 360); go(pg, '#new', 1600); pg.locator('#nBuild').scroll_into_view_if_needed()
        bad = wrapcheck(pg, '#nBuild button'); check(not bad, '[360-sw] New site choices: no word split (%s)' % bad); shot(pg, 'build-new-360-sw'); ctx.close()
        br.close()
    try: os.remove(pic)
    except Exception: pass

# --- stories (§9) ------------------------------------------------------------------------------------------------
def s_story(check):
    pic = os.path.join(OUT, '_st.png'); make_png(pic, 800, 600)
    with sync_playwright() as p:
        br = p.chromium.launch()
        for lang, th in (('en', ''), ('sw', ''), ('en', 'dark'), ('sw', 'sun')):
            t = lang + ('-' + th if th else ''); story_reset(); del SENT[:]
            ctx, pg = context(br, lang, 390, th)
            go(pg, '#p/103/story', 2000)
            check(vis(pg, '[data-testid=sty_status]') and pg.locator('[data-testid=sty_now]').inner_text().startswith('Rasimu' if lang == 'sw' else 'Draft'), '[%s] the Stories tab: Draft ready' % t)
            check(pg.get_attribute('#tabStory', 'aria-selected') == 'true', '[%s] its own tab (#p/103/story)' % t)
            tabs = pg.evaluate("[...document.querySelectorAll('.ptabs [role=tab]')].map(b => [b.textContent.trim(), b.scrollWidth <= b.clientWidth + 1])")
            check(len(tabs) == 4 and all(x[1] for x in tabs), '[%s] four project tabs, nothing cut (%s)' % (t, tabs))
            bad = wrapcheck(pg, '#paneStory .card'); check(not bad, '[%s] no word split or cut (%s)' % (t, bad))
            shot(pg, 'story-top-%s' % t)
            if lang == 'en' and th == '':
                pg.fill('#styA', 'The spring was open to cattle.'); pg.click('[data-testid=sty_qsave]'); pg.wait_for_timeout(700)
                a = [x for x in SENT if x.get('action') == 'answers']
                check(a and a[-1].get('q1') == 'The spring was open to cattle.' and 'Question 2 of 6' in pg.inner_text('.sty-q'), '[%s] an answer is saved, on to question 2' % t)
                pg.click('[data-testid=sty_add_after]'); pg.set_input_files('#styFile', pic); pg.wait_for_timeout(900)
                check(vis(pg, '.sty-sh'), '[%s] a photo asks about children' % t); pg.click('.sty-sh [data-k=yes]'); pg.wait_for_timeout(1200)
                mp = [x for x in SENT if x.get('story-multipart')]
                check(mp and b'name="row"\r\n\r\nafter' in mp[-1]['body'] and b'name="children"\r\n\r\ntrue' in mp[-1]['body'], '[%s] the After photo is sent with children=true' % t)
                check(pg.locator('.sty-row[data-row=after] .cn-th').count() == 1, '[%s] it shows in the After row' % t)
                pg.click('[data-sty=consent][data-v=received]'); pg.wait_for_timeout(600); check([x for x in SENT if x.get('action') == 'consent'][-1]['value'] == 'received', '[%s] consent' % t)
                pg.click('[data-testid=sty_draft]'); pg.wait_for_timeout(800); check([x for x in SENT if x.get('action') == 'draft'], '[%s] Draft the story' % t)
                pg.click('[data-testid=sty_approve]'); pg.wait_for_timeout(800)
                check([x for x in SENT if x.get('action') == 'approve'] and pg.locator('[data-testid=sty_now]').inner_text().startswith('Approved'), '[%s] an admin approves' % t)
                pg.locator('[data-testid=sty_status]').scroll_into_view_if_needed(); shot(pg, 'story-approved-%s' % t)
                go(pg, '#list', 1200); pg.click('#lChips [data-f=sok]'); pg.wait_for_timeout(800)
                check(pg.locator('#list [data-testid=row_story]').count() >= 1, '[%s] Projects › Story approved, with the badge' % t); shot(pg, 'story-list-%s' % t)
                go(pg, '#report', 2200); check('stories approved' in pg.inner_text('#repBody'), '[%s] the monthly report counts stories' % t)
            ctx.close()
        # field users collect and draft, never approve
        story_reset(); ctx, pg = context(br, 'en', 390, admin=False); go(pg, '#p/103/story', 2000)
        check(not vis(pg, '[data-testid=sty_approve]') and vis(pg, '[data-testid=sty_draft]'), 'field user: Draft, no Approve'); ctx.close()
        br.close()
    try: os.remove(pic)
    except Exception: pass

# --- donor mode ------------------------------------------------------------------------------------------------------
def s_donor(check):
    import subprocess
    view = subprocess.run(['node', os.path.join(HERE, 'me_donor.mjs')], capture_output=True, text=True).stdout
    with sync_playwright() as p:
        br = p.chromium.launch()
        for lang in ('en', 'sw'):
            ctx = br.new_context(viewport={'width': 390, 'height': 844}, device_scale_factor=2, is_mobile=True, has_touch=True, service_workers='block')
            pg = ctx.new_page(); pg.on('pageerror', lambda e: ERRS.append('donor pageerror: ' + str(e)[:200]))
            pg.route(re.compile(r'/api/donor(\?.*)?$'), lambda r, q: r.fulfill(body=face((10, 120, 194)), content_type='image/png') if 'photo=' in q.url else r.fulfill(body=view, content_type='application/json'))
            pg.route('**/api/photo/**', lambda r, q: r.fulfill(body=TILE, content_type='image/png'))
            pg.route(re.compile(r'https://.*'), lambda r, q: r.abort())
            pg.goto(BASE + '/pin-donor.html'); pg.evaluate("l => { localStorage.setItem('rfPinDonor', 'tok'); if (l === 'sw') localStorage.setItem('rfPinLang', 'sw'); }", lang)
            pg.goto(BASE + '/pin-donor.html#p/301'); pg.reload(); pg.wait_for_timeout(2200)
            check(vis(pg, '[data-testid=donor_build]'), '[%s] donors see the stage tracker' % lang)
            check(pg.locator('[data-testid=donor_build] .strip .ph').count() == 3, '[%s] with the stage photos' % lang)
            pg.locator('[data-testid=donor_build]').scroll_into_view_if_needed(); shot(pg, 'donor-build-%s' % lang)
            pg.goto(BASE + '/pin-donor.html#p/101'); pg.reload(); pg.wait_for_timeout(2000)
            check(vis(pg, '[data-testid=donor_story]') and pg.locator('[data-testid=donor_story] .ph').count() == 2, '[%s] and the approved story with its after photos' % lang)
            pg.locator('[data-testid=donor_story]').scroll_into_view_if_needed(); shot(pg, 'donor-story-%s' % lang)
            ctx.close()
        br.close()

if __name__ == '__main__':
    if WHAT == 'before':
        seq('centring before (screenshots only)', lambda check: centre(check, [768, 1024, 1280, 1440], ['en'], [''], 'before', judge=False))
    if WHAT in ('all', 'pill'): seq('The Me pill: 44 px, clear of the wordmark / refresh / bell at 320, 360, 390, EN/SW, light/dark/sun, both roles', s_pill)
    if WHAT in ('all', 'hint'): seq('The one-time hint: admins, under the pill, Back / tap / Got it close it, the map still gets the tap, never again', s_hint)
    if WHAT in ('all', 'photo'): seq('Profile photos: shown everywhere, initials as fallback, set / crop / remove, admins for anyone, offline', s_photo)
    if WHAT in ('all', 'build'): seq('Construction: card, stepper, gallery, move a stage with photos, hand over, filter, map badge, Office, report, New site', s_build)
    if WHAT in ('all', 'story'): seq('Stories tab: status, answers, photos with children, consent, draft, approve; filter, badge, report; field users', s_story)
    if WHAT in ('all', 'donor'): seq('Donor mode: stage tracker and approved story, read-only', s_donor)
    if WHAT in ('all', 'centre'):
        seq('Centred on wide screens: 768, 1024, 1280, 1440 (EN, light; screenshots)', lambda check: centre(check, [768, 1024, 1280, 1440], ['en'], [''], 'after'))
        seq('Centred on wide screens: dark and sun, Kiswahili (checks only)', lambda check: centre(check, [768, 1024, 1280, 1440], ['sw'], ['dark', 'sun'], 'x', shots=False))
    fails = [r for r in RESULTS if not r[1]]
    print('\n%d/%d passed' % (len(RESULTS) - len(fails), len(RESULTS)))
    errs = sorted(set(ERRS))
    print('page errors: %d' % len(errs)); [print('  ' + e) for e in errs[:20]]
    sys.exit(1 if fails or errs else 0)
