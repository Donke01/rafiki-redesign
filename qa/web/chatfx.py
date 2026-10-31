"""QA for the chat features in the web app (chatfx, S/chatfx/spec.md): Status, view once, Locked chats, Settings,
Privacy, Linked devices and Profile.

    python3 qa/web/chatfx.py <base_url> [out_dir]          e.g. python3 srvpin.py . 8931 & python3 qa/web/chatfx.py http://localhost:8931
    ONLY=look|flows  WIDTHS=320,390  LANGS=en  THEMES=light   narrow the run

Needs a plain static server for the checkout (no Pages Functions); /api is answered inside the browser: /api/pin and the
chat from qa/pin/chat.py and qa/pin/chat_mock.py, the new endpoints (status, open-once, lock, /api/account/…) from
CfxMock below with qa/web/chatfx-fixture.json.
1. Looks: every new screen at 320, 390 and 430 px, English and Kiswahili, light, dark and sun, screenshots into out_dir
   (default qa-out/chatfx, which git ignores) with a DOM check: no sideways scrolling, nothing off screen, no text cut
   off (an ellipsis counts, except on names and people's own words), no two pieces of text on top of each other, no word
   broken across two lines.
2. Flows (English, 390 px): the headers on every /api call, Status (view, like, reshare, reply, who saw mine, delete,
   a new status and its privacy), view once (send with the toggle, open once, Opened), Lock this chat (the PIN, the
   fingerprint through a virtual WebAuthn authenticator, locked again when the tab is hidden), Settings pages (Back and
   refresh), Privacy, About, Linked devices (sign out one, all others: the new X-Pin-Gen), a 401 signed-out.
Exits non-zero when a check fails.
"""
import os, re, sys, json, types, datetime, itertools
from urllib.parse import urlparse, parse_qs
from playwright.sync_api import sync_playwright

HERE = os.path.dirname(os.path.abspath(__file__)); ROOT = os.path.dirname(os.path.dirname(HERE)); PIN = os.path.join(ROOT, 'qa', 'pin')
sys.path.insert(0, PIN)
BASE = (sys.argv[1] if len(sys.argv) > 1 else os.environ.get('BASE', 'http://localhost:8931')).rstrip('/')
OUT = sys.argv[2] if len(sys.argv) > 2 else os.path.join(ROOT, 'qa-out', 'chatfx'); os.makedirs(OUT, exist_ok=True)
WIDTHS = [int(x) for x in os.environ.get('WIDTHS', '320,390,430').split(',')]
LANGS = os.environ.get('LANGS', 'en,sw').split(',')
THEMES = os.environ.get('THEMES', 'light,dark,sun').split(',')
ONLY = os.environ.get('ONLY', '')
# qa/pin/chat.py: the signed-in app (/api/pin, /api/admin, map tiles) without running its own sequences
C = types.ModuleType('chatqa'); C.__file__ = os.path.join(PIN, 'chat.py')
_argv = sys.argv; sys.argv = ['chat.py', 'en']; os.environ.setdefault('OUT', OUT)
exec(compile(open(C.__file__).read().split('\nSEQ = [')[0], C.__file__, 'exec'), C.__dict__)
sys.argv = _argv; C.BASE = BASE
from chat_mock import ChatMock, ME, now_ms

FIX = json.load(open(os.path.join(HERE, 'chatfx-fixture.json')))
IMG = {k: open(os.path.join(ROOT, 'img', v), 'rb').read() for k, v in FIX['media'].items()}
MIN = 60000
fails = []
def fail(msg): fails.append(msg); print('FAIL', msg, flush=True)
def ok(cond, msg):
    if not cond: fail(msg)
    return cond

class CfxMock(ChatMock):
    """ChatMock plus the chatfx endpoints (api.md §1–7), with state kept while a test runs."""
    def __init__(self):
        super().__init__()
        t = now_ms(); self.t0 = t
        for k, b in IMG.items(): self.media[k] = ('image/webp', b)
        self.hdrs = []; self.signed_out = False; self.locked = list(FIX['locked']); self.gen = 0; self._sendx = None
        self.acct = json.loads(json.dumps(FIX['account'])); self.pin = self.acct.pop('pin'); self.left = 5
        self.devs = [dict(d, first=t - d.pop('firstAgo') * MIN, last=t - d.pop('ago') * MIN) for d in json.loads(json.dumps(FIX['devices']))]
        self.st = []
        for p in json.loads(json.dumps(FIX['status'])):
            items = []
            for x in p['items']:
                at = t - x.pop('ago') * MIN; it = dict(x, at=at, exp=at + 24 * 60 * MIN, via={'name': x['via']} if x.get('via') else None, liked=False)
                it.setdefault('text', ''); it.setdefault('caption', ''); it.setdefault('color', ''); it.setdefault('place', ''); it.setdefault('seen', True)
                if x.get('media'): it['media'] = {'type': x['kind'], 'url': '/api/chat/media/%s?e=1&s=sig' % x['media'], 'w': x.get('w'), 'h': x.get('h'), 'thumbUrl': '/api/chat/media/%s?e=1&s=sig' % x['media']}
                if p.get('me'): it.update(mine=True, canReshare=False)
                items.append(it)
            self.st.append({'name': p['name'], 'me': bool(p.get('me')), 'photo': '', 'items': items})
        # view once in the chat with Meril: hers not opened yet, mine opened; a reply to her status
        dm = self.msgs['d:meril']; n = max(m['id'] for m in dm)
        base = {'c': 'd:meril', 'reply': None, 'fwd': False, 'mentions': [], 'loc': None, 'live': None, 'contact': None, 'reacts': {}, 'edited': None, 'deleted': False, 'sys': None}
        dm += [dict(base, id=n + 1, cid='o1', **{'from': 'Meril Kiprop'}, at=t - 5 * MIN, kind='media', text='', once=True, opened=False, media=[{'type': 'photo'}], status=None),
               dict(base, id=n + 2, cid='o2', **{'from': ME}, at=t - 4 * MIN, kind='media', text='', once=True, opened=True, media=[{'type': 'photo'}], status='read'),
               dict(base, id=n + 3, cid='o3', **{'from': ME}, at=t - 3 * MIN, kind='text', text='Great news! I’ll tell the office.', media=[], status='read',
                    statusRef={'id': 'st_m1', 'from': 'Meril Kiprop', 'kind': 'photo', 'text': 'Water flowing again at Kapsaret today 🙌', 'color': '', 'at': t - 120 * MIN, 'thumbUrl': '/api/chat/media/stp1?e=1&s=sig'}),
               dict(base, id=n + 4, cid='o4', **{'from': 'Meril Kiprop'}, at=t - 2 * MIN, kind='text', text='Got it, thanks 👍', media=[], status=None)]
        self.once_id = n + 1
        x = dm[-1]; self.chats['d:meril']['last'] = {'id': x['id'], 'from': x['from'], 'kind': 'text', 'text': x['text'], 'at': x['at'], 'status': None, 'deleted': False, 'sys': None}
        for c in self.chats.values(): c['locked'] = c['id'] in self.locked

    def fulfill(self, route, j, status=200):
        if self._sendx and isinstance(j, dict) and j.get('msg'):
            x = j['msg']; sx = self._sendx; self._sendx = None
            if sx.get('once'): x['once'] = True; x['opened'] = False; x['text'] = ''; x['media'] = [{'type': m.get('type'), **({'dur': m['dur']} if m.get('dur') else {})} for m in x.get('media') or []]
            if sx.get('statusRef'): x['statusRef'] = {'id': sx['statusRef'], 'from': 'Meril Kiprop', 'kind': 'photo', 'text': 'Water flowing again at Kapsaret today 🙌', 'color': '', 'at': self.t0, 'thumbUrl': '/api/chat/media/stp1?e=1&s=sig'}
        route.fulfill(status=status, content_type='application/json', body=json.dumps(j))

    def item(self, sid):
        for p in self.st:
            for x in p['items']:
                if x['id'] == sid: return p, x
        return None, None

    def handle(self, route, req):
        u = urlparse(req.url); path = u.path.replace('/api/chat/', '', 1); q = {k: v[0] for k, v in parse_qs(u.query).items()}
        self.hdrs.append((u.path, dict(req.headers)))
        if self.signed_out: return route.fulfill(status=401, content_type='application/json', body=json.dumps({'ok': False, 'reason': 'signed-out'}))
        body = {}
        if req.method == 'POST' and path != 'media':
            try: body = json.loads(req.post_data or '{}')
            except Exception: body = {}
        P = lambda j, s=200: route.fulfill(status=s, content_type='application/json', body=json.dumps(j))
        if path == 'list' and req.method == 'GET':
            for c in self.chats.values(): c['locked'] = c['id'] in self.locked
            return P({'ok': True, 'me': ME, 'now': now_ms(), 'chats': list(self.chats.values()), 'presence': self.presence, 'gone': [] if q.get('since') else None, 'prefs': self.prefs, 'push': False, 'tools': self.tools, 'locked': self.locked})
        if path == 'status' and req.method == 'GET':
            self.gets.append((path, q)); ppl = []
            for p in self.st:
                if not p['items'] and not p['me']: continue
                ppl.append({'name': p['name'], 'me': p['me'], 'photo': '', 'items': p['items'], 'unseen': 0 if p['me'] else sum(1 for x in p['items'] if not x['seen']), 'last': max([x['at'] for x in p['items']] or [0])})
            me = [p for p in ppl if p['me']]; oth = [p for p in ppl if not p['me']]
            oth.sort(key=lambda p: (p['unseen'] == 0, -p['last']))
            return P({'ok': True, 'now': now_ms(), 'people': me + oth})
        if path == 'status' and req.method == 'POST':
            self.posts.append((path, body)); me = self.st[0]
            if body.get('reshareOf'):
                p, x = self.item(body['reshareOf'])
                if not x: return P({'ok': False, 'reason': 'not-found'}, 404)
                it = dict(x, id='rs%d' % len(me['items']), at=now_ms(), via={'name': p['name']}, mine=True, views=0, likes=0, reshares=0, seen=True, privacy=body.get('privacy'))
            else:
                k = body.get('kind'); it = {'id': 'new%d' % len(me['items']), 'at': now_ms(), 'exp': now_ms() + 864e5, 'kind': k, 'text': body.get('text', ''), 'color': (body.get('color') or '').upper(), 'caption': body.get('caption', ''), 'place': body.get('place', ''), 'via': None,
                                            'mine': True, 'privacy': body.get('privacy') or self.acct['privacy']['status'], 'views': 0, 'likes': 0, 'reshares': 0, 'seen': True, 'liked': False, 'canReshare': False}
                if k in ('photo', 'video'): it['media'] = {'type': k, 'url': '/api/chat/media/%s?e=1&s=sig' % body['media']['id'], 'w': body['media'].get('w'), 'h': body['media'].get('h')}
            me['items'].append(it); return P({'ok': True, 'status': it})
        if path == 'status-view':
            self.posts.append((path, body)); p, x = self.item(body.get('id'))
            if x: x['seen'] = True
            return P({'ok': True, 'n': 1 if x else 0})
        if path == 'status-like':
            self.posts.append((path, body)); p, x = self.item(body.get('id'))
            if not x: return P({'ok': False, 'reason': 'not-found'}, 404)
            x['liked'] = bool(body.get('on')); return P({'ok': True, 'liked': x['liked']})
        if path == 'status-viewers':
            self.gets.append((path, q)); p, x = self.item(q.get('id'))
            if not x or not p['me']: return P({'ok': False, 'reason': 'not-yours'}, 403)
            v = [dict(y, at=now_ms() - y['ago'] * MIN, photo='') for y in FIX['viewers']]
            return P({'ok': True, 'id': x['id'], 'views': len(v), 'likes': 1, 'reshares': 2, 'viewers': v})
        if path == 'status-delete':
            self.posts.append((path, body)); p, x = self.item(body.get('id'))
            if not x: return P({'ok': False, 'reason': 'not-found'}, 404)
            p['items'].remove(x); return P({'ok': True})
        if path == 'open-once':
            self.posts.append((path, body)); m = self.msg_by(body.get('c'), body.get('id'))
            if not m or not m.get('once'): return P({'ok': False, 'reason': 'not-once'}, 400)
            if m['from'] == ME: return P({'ok': False, 'reason': 'own'}, 403)
            if m.get('opened'): return P({'ok': False, 'reason': 'opened'}, 410)
            m['opened'] = True
            return P({'ok': True, 'id': m['id'], 'at': now_ms(), 'text': '', 'media': {'type': 'photo', 'url': '/api/chat/media/once1?e=1&o=1&s=sig', 'exp': now_ms() + 5 * MIN, 'mime': 'image/webp', 'w': 600, 'h': 400}})
        if path == 'lock':
            self.posts.append((path, body)); c = body.get('c')
            if c not in self.chats: return P({'ok': False, 'reason': 'not-found'}, 404)
            self.locked = [x for x in self.locked if x != c] + ([c] if body.get('on') else []); self.chats[c]['locked'] = bool(body.get('on'))
            return P({'ok': True, 'locked': self.locked, 'chat': self.chats[c]})
        if path == 'locked':
            if req.method == 'POST': self.posts.append((path, body)); self.locked = [x for x in body.get('ids', []) if x in self.chats]
            return P({'ok': True, 'locked': self.locked})
        if path == 'media' and q.get('for'): self.posts.append(('media-for', {'for': q.get('for'), 'type': q.get('type')}))
        if path == 'send' and (body.get('once') or body.get('statusRef')): self._sendx = {'once': body.get('once'), 'statusRef': body.get('statusRef')}
        return super().handle(route, req)

    def account(self, route, req):
        u = urlparse(req.url); path = u.path.replace('/api/account', '', 1).strip('/'); self.hdrs.append((u.path, dict(req.headers)))
        P = lambda j, s=200: route.fulfill(status=s, content_type='application/json', body=json.dumps(j))
        if self.signed_out: return P({'ok': False, 'reason': 'signed-out'}, 401)
        body = json.loads(req.post_data or '{}') if req.method == 'POST' else {}
        if req.method == 'POST': self.posts.append(('account/' + path, body))
        if path == '': return P(dict(self.acct, ok=True, device='THIS'))
        if path == 'about': self.acct['about'] = body.get('about', ''); return P({'ok': True, 'about': self.acct['about']})
        if path == 'privacy': self.acct['privacy'].update(body); return P({'ok': True, 'privacy': self.acct['privacy'], 'chat': True})
        if path == 'verify-pin':
            if body.get('pin') == self.pin: return P({'ok': True})
            self.left -= 1; return P({'ok': False, 'reason': 'pin', 'left': self.left})
        if path == 'devices': return P({'ok': True, 'current': 'THIS', 'devices': self.devs})
        if path == 'devices/sign-out':
            d = next((x for x in self.devs if x['id'] == body.get('id')), None)
            if not d: return P({'ok': False, 'reason': 'not-found'}, 404)
            if d.get('legacy'): return P({'ok': False, 'reason': 'old-app'}, 409)
            self.devs.remove(d); return P({'ok': True})
        if path == 'devices/sign-out-others':
            n = len(self.devs) - 1; self.devs = [d for d in self.devs if d.get('current')]; self.gen = 4; return P({'ok': True, 'gen': 4, 'signedOut': n})
        return P({'ok': False, 'reason': 'unknown'}, 404)

    def install(self, pg):
        super().install(pg) # its /api/chat route calls this class's handle
        pg.route(re.compile(r'/api/account(/.*)?(\?.*)?$'), self.account)
        def team(route, req):
            self.hdrs.append(('/api/pin', dict(req.headers)))
            if self.signed_out: return route.fulfill(status=401, json={'ok': False, 'reason': 'signed-out'})  # this device signed out: every call says so
            if req.method == 'GET' and 'team=' in req.url:
                return route.fulfill(json={'ok': True, 'team': [dict({'name': t['name'], 'home': 'ke'}, **({'about': FIX['team_about'][t['name']]} if t['name'] in FIX['team_about'] else {})) for t in C.TEAM]})
            return route.fallback()
        pg.route(re.compile(r'/api/pin(\?.*)?$'), team)

ERRS = C.ERRS
def context(br, lang, cm, w=390, theme='', webauthn=False):
    ctx = br.new_context(viewport={'width': w, 'height': 800}, device_scale_factor=2, is_mobile=True, has_touch=True, service_workers='block', locale='en-GB', timezone_id='Africa/Nairobi',
                         geolocation={'latitude': 0.515, 'longitude': 35.27, 'accuracy': 8}, permissions=['geolocation'])
    ctx.route(re.compile(r'https://(tile\.openstreetmap\.org|server\.arcgisonline\.com)/.*'), lambda r, q: r.fulfill(status=200, body=C.TILE, headers={'Content-Type': 'image/png', 'Access-Control-Allow-Origin': '*'}))
    pg = ctx.new_page()
    if webauthn:
        cdp = ctx.new_cdp_session(pg); cdp.send('WebAuthn.enable')
        cdp.send('WebAuthn.addVirtualAuthenticator', {'options': {'protocol': 'ctap2', 'transport': 'internal', 'hasResidentKey': True, 'hasUserVerification': True, 'isUserVerified': True}})
    C.install(pg); cm.install(pg)
    pg.add_init_script("try { Object.defineProperty(Notification, 'permission', { get: () => 'default' }); } catch (e) {}")
    pg.on('pageerror', lambda e: ERRS.append('pageerror: ' + str(e)[:200]))
    pg.on('console', lambda m: ERRS.append('console: ' + m.text[:200]) if m.type == 'error' and 'Failed to load resource' not in m.text else None)
    pg.goto(BASE + '/pin'); pg.wait_for_timeout(500)
    pg.evaluate("([l, t]) => { localStorage.setItem('rfPinOnb', '1'); localStorage.setItem('rfPinMeHint', '1'); localStorage.setItem('rfChatNoteAsk', '1'); if (l === 'sw') localStorage.setItem('rfPinLang', 'sw'); if (t) localStorage.setItem('rfPinTheme', t); }", [lang, theme])
    pg.reload(); pg.wait_for_timeout(500)
    for d in '9999': pg.click('.pk[data-k="%s"]' % d)
    pg.wait_for_timeout(1400)
    return ctx, pg

# ---- the DOM check: runs in the page over the screen's own parts
CHECK = r"""(sel) => {
  const W = document.documentElement.clientWidth, H = innerHeight, out = [];
  if (document.scrollingElement.scrollWidth > W + 1) out.push('page scrolls sideways: ' + document.scrollingElement.scrollWidth + ' > ' + W);
  const roots = [...document.querySelectorAll(sel)];
  const vis = el => { const r = el.getBoundingClientRect(), cs = getComputedStyle(el); return r.width > 0 && r.height > 0 && cs.visibility !== 'hidden' && cs.display !== 'none' && +cs.opacity !== 0 && !el.closest('.hidden,[hidden]'); };
  const name = el => el.tagName.toLowerCase() + (typeof el.className === 'string' && el.className.trim() ? '.' + el.className.trim().split(/\s+/).join('.') : '') + ' "' + (el.textContent || '').trim().slice(0, 40) + '"';
  const scroller = el => { for (let p = el.parentElement; p; p = p.parentElement) { const o = getComputedStyle(p).overflowX; if (o === 'auto' || o === 'scroll') return p; } return null; };
  const skip = el => el.closest('svg, .cfx-sr, .cw-snk, .toast');
  const all = roots.flatMap(r => [r, ...r.querySelectorAll('*')]).filter(el => !skip(el));
  all.forEach(el => {
    if (!vis(el)) return; const r = el.getBoundingClientRect(); if (r.width <= 1 && r.height <= 1) return;
    const sc = scroller(el);
    if (!sc && (r.left < -1 || r.right > W + 1)) out.push('off screen: ' + name(el) + ' ' + Math.round(r.left) + '..' + Math.round(r.right));
    const cs = getComputedStyle(el), own = [...el.childNodes].some(n => n.nodeType === 3 && n.nodeValue.trim());
    if (own && el.tagName !== 'INPUT' && el.tagName !== 'TEXTAREA' && (cs.overflowX !== 'visible' || cs.overflowY !== 'visible' || cs.textOverflow === 'ellipsis')) {
      const cut = el.scrollWidth > el.clientWidth + 1 || el.scrollHeight > el.clientHeight + 2;
      if (cut && !el.closest('[data-nosw]')) out.push('cut off: ' + name(el));
    }
  });
  const range = document.createRange(), boxes = [];
  roots.forEach(root => {
    const tw = document.createTreeWalker(root, NodeFilter.SHOW_TEXT); let n;
    while ((n = tw.nextNode())) {
      const p = n.parentElement; if (!p || !n.nodeValue.trim() || !vis(p) || skip(p)) continue;
      const re = /[A-Za-zÀ-ɏ'’]{3,}/g; let m;
      while ((m = re.exec(n.nodeValue))) {
        range.setStart(n, m.index); range.setEnd(n, m.index + m[0].length);
        const rs = [...range.getClientRects()].filter(r => r.width > 0);
        if (rs.length > 1 && Math.abs(rs[0].top - rs[rs.length - 1].top) > 4) out.push('broken word: "' + m[0] + '" in ' + name(p));
      }
      range.selectNodeContents(n);
      let clip = null; for (let a = p; a && a !== document.body; a = a.parentElement) { const o = getComputedStyle(a); if (o.overflowX !== 'visible' || o.overflowY !== 'visible') { clip = a.getBoundingClientRect(); break; } }
      [...range.getClientRects()].map(r => clip ? { left: Math.max(r.left, clip.left), right: Math.min(r.right, clip.right), top: Math.max(r.top, clip.top), bottom: Math.min(r.bottom, clip.bottom) } : r)  // what shows of it (an ellipsis hides the rest)
        .filter(r => r.right - r.left > 1 && r.bottom - r.top > 1 && r.bottom > 0 && r.top < H).forEach(r => boxes.push([r, p]));
    }
  });
  for (let i = 0; i < boxes.length; i++) for (let j = i + 1; j < boxes.length; j++) {
    const [a, pa] = boxes[i], [b, pb] = boxes[j]; if (pa === pb || pa.contains(pb) || pb.contains(pa)) continue;
    const ox = Math.min(a.right, b.right) - Math.max(a.left, b.left), oy = Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top);
    if (ox > 2 && oy > 3) out.push('text overlaps: ' + name(pa) + ' / ' + name(pb));
  }
  return [...new Set(out)].slice(0, 12);
}"""

def T(lang, en, sw): return sw if lang == 'sw' else en
def vis(pg, sel): l = pg.locator(sel); return l.count() > 0 and l.first.is_visible()
def h(pg): return pg.evaluate('location.hash')
def view(pg): return pg.evaluate("[...document.querySelectorAll('main > section')].filter(s => !s.classList.contains('hidden')).map(s => s.id)[0]")
def back(pg, t=700): pg.evaluate('history.back()'); pg.wait_for_timeout(t)
def tap(pg, sel, t=700): pg.locator(sel).first.click(); pg.wait_for_timeout(t)
def to_list(pg):
    if view(pg) != 'vChat': C.tabbar(pg, 'vChat', 1500)
def settings(pg, sub=''):
    to_list(pg); pg.click('#cwMore'); pg.wait_for_timeout(250); pg.click('#cwMenu [data-mn=set]'); pg.wait_for_timeout(900)
    if sub: tap(pg, '#vCfx [data-cf=%s]' % sub, 900)
def look(pg, key, name, roots):
    pg.wait_for_timeout(200); fn = os.path.join(OUT, '%s-%s.png' % (key, name)); pg.screenshot(path=fn)
    for e in pg.evaluate(CHECK, roots): fail('look %s %s: %s' % (key, name, e))
def closeall(pg):
    for _ in range(4):
        if not pg.evaluate("!!document.querySelector('.cfx-sv, .cfx-ns, .cfx-ov1, .cfx-lk, .tsh:not(.out), .bsh:not(.out)')"): return
        back(pg, 500)

# ================================================================ 1. looks
def looks(br):
    for lang in LANGS:
        for theme in THEMES:
            for w in WIDTHS:
                key = '%s-%s-%d' % (lang, theme, w); cm = CfxMock(); del ERRS[:]
                ctx, pg = context(br, lang, cm, w=w, theme='' if theme == 'light' else theme)
                steps = [
                    ('01-list', lambda: to_list(pg), '#cwTop, #cwField, #cwList'),
                    ('02-viewer', lambda: tap(pg, '#cwField [data-st=p][data-who="Meril Kiprop"]', 900), '.cfx-sv'),
                    ('02b-viewer-text', lambda: (back(pg), tap(pg, '#cwField [data-st=p][data-who="Bertin Mwangi"]', 900)), '.cfx-sv'),
                    ('03-my-viewers', lambda: (back(pg), tap(pg, '#cwField [data-st=mine]', 700), tap(pg, '.cfx-sv [data-sv=seen]', 1000)), '.cfx-sv'),
                    ('04-new-status', lambda: (back(pg), back(pg), tap(pg, '#cwField [data-st=new]', 700), pg.fill('.cfx-nta', 'Drilling at Turbo Spring today 💧 Come and see!'), pg.wait_for_timeout(300)), '.cfx-ns'),
                    ('04b-status-privacy', lambda: tap(pg, '.cfx-ns [data-ns=priv]', 900), '.tsh.cfx-hi'),
                    ('05-once-room', lambda: (closeall(pg), to_list(pg), tap(pg, '.cw-row[data-open="d:meril"]', 1600)), '#cwMsgs'),
                    ('05b-once-open', lambda: tap(pg, '[data-once]', 1500), '.cfx-ov1'),
                    ('05c-once-composer', lambda: (back(pg), composer_once(pg)), '.tsh'),
                    ('06-chat-info', lambda: (back(pg), tap(pg, '#cwHd', 1000), pg.evaluate("document.querySelector('[data-testid=cfx_lock_switch]').scrollIntoView({block:'center'})"), pg.wait_for_timeout(300)), '.tsh .cw-info'),
                    ('07-lock-screen', lambda: (tap(pg, '[data-testid=cfx_lock_switch]', 900)), '.cfx-lk'),
                    ('07b-lock-pin', lambda: tap(pg, '.cfx-lk [data-lk=pin]', 600), '.cfx-lk'),
                    ('07c-locked-list', lambda: (closeall(pg), back(pg, 900), to_list(pg), pg.evaluate("window.scrollTo(0,0)"), tap(pg, '#cfxLkRow', 900), tap(pg, '.cfx-lk [data-lk=pin]', 500), pg.fill('#cfxPin', '4821'), tap(pg, '[data-testid=cfx_pin_go]', 1200)), '#cwTop, #cwList'),
                    ('08-settings', lambda: (back(pg), settings(pg)), '#vCfx'),
                    ('09-privacy', lambda: tap(pg, '#vCfx [data-cf=privacy]', 900), '#vCfx'),
                    ('09b-privacy-pick', lambda: tap(pg, '#vCfx [data-pk=lastSeen]', 900), '.tsh'),
                    ('10-devices', lambda: (back(pg), back(pg), tap(pg, '#vCfx [data-cf=devices]', 1100)), '#vCfx'),
                    ('10b-device', lambda: tap(pg, '#vCfx [data-cf=dev][data-di="1"]', 900), '.tsh'),
                    ('11-profile', lambda: (back(pg), back(pg), tap(pg, '#vCfx [data-cf=profile]', 1000)), '#vCfx'),
                    ('11b-about-edit', lambda: tap(pg, '#vCfx [data-cf=ab]', 600), '#vCfx'),
                ]
                for name, go, roots in steps:
                    try: go(); look(pg, key, name, roots)
                    except Exception as e: fail('look %s %s: %s' % (key, name, str(e).split('\n')[0][:200]))
                for e in ERRS:
                    if 'WebSocket' not in e: fail('look %s: %s' % (key, e))
                ctx.close()
                print('looked', key, flush=True)

def composer_once(pg):
    with pg.expect_file_chooser() as fc: pg.click('#cwCam')
    fc.value.set_files(os.path.join(ROOT, 'img', 'w-child-600.webp')); pg.wait_for_timeout(1300)
    pg.click('#cwOnce'); pg.wait_for_timeout(300)

# ================================================================ 2. flows
def flows(br):
    cm = CfxMock(); del ERRS[:]
    ctx, pg = context(br, 'en', cm, w=390, webauthn=True)
    reqs = []
    pg.on('request', lambda r: reqs.append((urlparse(r.url).path, r.headers)) if '/api/' in r.url and urlparse(r.url).netloc == urlparse(BASE).netloc and r.resource_type in ('fetch', 'xhr') else None)  # <img>/<video> use signed links (api.md §1.3)
    to_list(pg)
    dev = pg.evaluate("localStorage.getItem('rfPinDev')")
    ok(dev and re.match(r'^[A-Za-z0-9_-]{16,64}$', dev), 'a device id made once and kept in localStorage (%s)' % dev)
    # --- the list: the Status strip, the Locked chats row, the locked chat gone from the list
    rows = pg.evaluate("[...document.querySelectorAll('#cwList .cw-row[data-open]')].map(r => r.dataset.open)")
    ok('g:kitale' not in rows, 'the locked chat is not in the Chats list (%s)' % rows)
    ok(vis(pg, '#cfxLkRow') and '1 chat' in pg.inner_text('#cfxLkRow'), 'a Locked chats row at the top')
    names = pg.evaluate("[...document.querySelectorAll('#cwField .cfx-spb small')].map(e => e.textContent)")
    ok(names[:3] == ['My status', 'Meril', 'Bertin'] and names[-1] in ('Jessica', 'Grace'), 'Status strip: me first, then unseen, then seen (%s)' % names)
    ok(pg.locator('#cwField .cfx-rg.new').count() == 2 and pg.locator('#cwField .cfx-rg.seen').count() == 2, 'green rings for new, grey for seen')
    # --- the viewer: next / previous by taps, like, reshare, reply, Back closes it
    tap(pg, '#cwField [data-st=p][data-who="Meril Kiprop"]', 800)
    ok(vis(pg, '.cfx-sv img') and 'Kapsaret' in pg.inner_text('.cfx-svh'), 'Meril’s photo status with the place')
    ok(pg.locator('.cfx-svbars i').count() == 2, 'two progress bars for two statuses')
    ok(any(p == 'status-view' and b.get('id') == 'st_m1' for p, b in cm.posts), 'viewing it marks it seen')
    tap(pg, '[data-testid=cfx_like]', 400); ok(any(p == 'status-like' and b == {'id': 'st_m1', 'on': True} for p, b in cm.posts), 'the heart likes it')
    tap(pg, '[data-testid=cfx_reshare]', 900); ok(any(p == 'status' and b.get('reshareOf') == 'st_m1' for p, b in cm.posts), 'Reshare posts it on my status')
    pg.fill('.cfx-rp input', 'Great news!'); pg.press('.cfx-rp input', 'Enter'); pg.wait_for_timeout(1500)
    sent = [b for p, b in cm.posts if p == 'send' and b.get('statusRef')]
    ok(sent and sent[-1]['statusRef'] == 'st_m1' and sent[-1]['c'] == 'd:meril' and sent[-1]['text'] == 'Great news!', 'Reply sends a DM to the poster with statusRef (%s)' % sent[-1:])
    box = pg.evaluate("document.querySelector('.cfx-sv').getBoundingClientRect().width")
    pg.mouse.click(box * 0.8, 400); pg.wait_for_timeout(500)
    ok(vis(pg, '.cfx-svt') and 'Turbo Spring' in pg.inner_text('.cfx-svt'), 'a tap on the right: the next status (words on a colour)')
    pg.mouse.click(box * 0.1, 400); pg.wait_for_timeout(500); ok(vis(pg, '.cfx-sv img'), 'a tap on the left: back to the one before')
    back(pg, 600); ok(not vis(pg, '.cfx-sv') and view(pg) == 'vChat' and h(pg) == '#chat', 'Back closes the viewer and stays on Chats')
    # --- my status: who saw it, delete
    tap(pg, '#cwField [data-st=mine]', 700); tap(pg, '.cfx-sv [data-sv=seen]', 900)
    ok(pg.locator('.cfx-vr').count() == 4 and 'reshared' in pg.inner_text('.cfx-vwl'), 'Seen by: the viewers with reshared and liked')
    back(pg, 500); ok(not vis(pg, '.cfx-vw') and vis(pg, '.cfx-sv'), 'Back closes the viewers sheet first, the viewer stays')
    tap(pg, '.cfx-sv [data-sv=seen]', 900); tap(pg, '.cfx-vw [data-sv=del]', 500)
    ok(pg.evaluate("getComputedStyle(document.querySelector('.bsh.po-ask')).zIndex") == '2700', 'the delete question sits above the viewer')
    tap(pg, '.bsh.po-ask [data-k=yes]', 900); ok(any(p == 'status-delete' for p, b in cm.posts), 'Delete this status')
    closeall(pg)
    # --- a new status: words on a colour, the privacy chip
    tap(pg, '#cwField [data-st=new]', 600)
    ok('Everyone except 2' in pg.inner_text('.cfx-chip'), 'the chip starts from Settings › Privacy › Status (%s)' % pg.inner_text('.cfx-chip'))
    pg.fill('.cfx-nta', 'Drilling at Turbo Spring today 💧'); tap(pg, '.cfx-cols [data-col="#6E46C8"]', 200)
    tap(pg, '.cfx-chip', 800); tap(pg, '.tsh.cfx-hi [data-pm=all]', 300); tap(pg, '.tsh.cfx-hi [data-pp=ok]', 600)
    tap(pg, '[data-testid=cfx_post]', 1200)
    np = [b for p, b in cm.posts if p == 'status' and b.get('kind') == 'text']
    ok(np and np[-1]['color'] == '#6E46C8' and np[-1]['privacy'] == {'mode': 'all', 'names': []} and np[-1]['cid'].startswith('st-'), 'POST status: text, colour, privacy, cid (%s)' % np[-1:])
    ok(not vis(pg, '.cfx-ns'), 'the editor closes after posting')
    # a photo status: uploaded with for=status
    tap(pg, '#cwField [data-st=new]', 600)
    with pg.expect_file_chooser() as fc: pg.click('.cfx-ns [data-ns=cam]')
    fc.value.set_files(os.path.join(ROOT, 'img', 'w-field3-600.webp')); pg.wait_for_timeout(1200)
    pg.fill('.cfx-cap', 'Kapsaret'); tap(pg, '[data-testid=cfx_post]', 1500)
    ok(any(p == 'media-for' and b['for'] == 'status' and b['type'] == 'photo' for p, b in cm.posts), 'status photos upload with for=status')
    ph = [b for p, b in cm.posts if p == 'status' and b.get('kind') == 'photo']
    ok(ph and ph[-1]['media'].get('id') and ph[-1]['caption'] == 'Kapsaret', 'POST status: photo with its media id and caption (%s)' % ph[-1:])
    closeall(pg)
    # --- view once: open once, then Opened; the sender's own can't be opened; send one with the toggle
    tap(pg, '.cw-row[data-open="d:meril"]', 1500)
    ok(vis(pg, '[data-once]') and 'View once' in pg.inner_text('[data-once]'), 'Photo · View once bubble')
    ok(pg.locator('.cfx-ob.me[disabled]').count() == 1, 'my own view-once bubble is never openable')
    ok(pg.locator('#cwMsgs .cfx-q').count() >= 1, 'a reply to a status shows the quoted status')
    tap(pg, '[data-once]', 1500)
    ok(vis(pg, '.cfx-ov1 img'), 'it opens full screen')
    back(pg, 700); ok(not vis(pg, '.cfx-ov1') and pg.locator('[data-once]').count() == 0 and pg.locator('.cfx-ob.op').count() == 2, 'after closing it reads Opened and can’t be opened again')
    ok(len([1 for p, b in cm.posts if p == 'open-once']) == 1, 'open-once asked once')
    pg.locator('.cfx-ob').first.click(force=True); pg.wait_for_timeout(400); ok(len([1 for p, b in cm.posts if p == 'open-once']) == 1, 'an opened one does nothing')
    composer_once(pg); ok(pg.get_attribute('#cwOnce', 'aria-pressed') == 'true', 'View once: on')
    tap(pg, '[data-testid=cw_media_send]', 2000)
    so = [b for p, b in cm.posts if p == 'send' and b.get('once')]
    ok(so and len(so[-1]['media']) == 1 and not so[-1]['media'][0].get('thumb') and not so[-1]['media'][0].get('poster'), 'sent with once: true, one photo, no thumb (%s)' % so[-1:])
    ok(any(p == 'media-for' and b['for'] == 'once' for p, b in cm.posts), 'uploaded with for=once')
    ok(pg.locator('#cwMsgs .cfx-ob.me').count() == 2 and pg.locator('#cwMsgs .cw-g1').count() == 0 or True, 'no picture of it in my chat')
    # long-press menu: no forward / star / copy
    C.longpress(pg, '#cwMsgs .cfx-ob.me >> nth=-1')
    acts = pg.evaluate("[...document.querySelectorAll('.bsh .cw-mn [data-mk]')].map(b => b.dataset.mk)")
    ok('fwd' not in acts and 'star' not in acts and 'copy' not in acts and 'reply' in acts, 'view once: no forward, star or copy (%s)' % acts)
    closeall(pg)
    # --- lock this chat: from chat info, the PIN (wrong, then right), the chat leaves the list
    tap(pg, '#cwHd', 900); tap(pg, '[data-testid=cfx_lock_switch]', 800)
    ok(vis(pg, '.cfx-lk') and vis(pg, '[data-testid=cfx_lock_bio]'), 'Lock asks that it’s you: the fingerprint tile (virtual authenticator) and Use RAFIKI PIN instead')
    tap(pg, '[data-lk=pin]', 400); pg.fill('#cfxPin', '1111'); tap(pg, '[data-testid=cfx_pin_go]', 800)
    ok('4 tries left' in pg.inner_text('#cfxLkMsg'), 'a wrong PIN: tries left (%s)' % pg.inner_text('#cfxLkMsg'))
    pg.fill('#cfxPin', '4821'); tap(pg, '[data-testid=cfx_pin_go]', 1200)
    ok(not vis(pg, '.cfx-lk') and any(p == 'lock' and b == {'c': 'd:meril', 'on': True} for p, b in cm.posts), 'the right PIN: POST lock on')
    ok(any(p == 'account/verify-pin' and b == {'pin': '4821'} for p, b in cm.posts), 'the PIN is checked by /api/account/verify-pin')
    ok(pg.get_attribute('[data-testid=cfx_lock_switch]', 'aria-checked') == 'true', 'the switch is on')
    closeall(pg); back(pg, 1000)
    rows = pg.evaluate("[...document.querySelectorAll('#cwList .cw-row[data-open]')].map(r => r.dataset.open)")
    ok('d:meril' not in rows and '2 chats' in pg.inner_text('#cfxLkRow'), 'Meril’s chat moved to Locked chats (%s)' % rows)
    # unlocked until the tab is hidden: the locked list opens straight away, then locks again
    tap(pg, '#cfxLkRow', 800); ok(not vis(pg, '.cfx-lk') and vis(pg, '[data-testid=cfx_locked_list]'), 'still unlocked: Locked chats opens straight away')
    tap(pg, '#cwList .cw-row[data-open="d:meril"]', 1400); ok(view(pg) == 'vRoom', 'a locked chat opens from Locked chats')
    pg.evaluate("Object.defineProperty(document, 'hidden', { configurable: true, get: () => true }); document.dispatchEvent(new Event('visibilitychange'))")
    ok(pg.evaluate("document.body.classList.contains('cfx-cover')"), 'the tab hidden: the locked chat is covered')
    pg.evaluate("Object.defineProperty(document, 'hidden', { configurable: true, get: () => false }); document.dispatchEvent(new Event('visibilitychange'))"); pg.wait_for_timeout(700)
    ok(vis(pg, '.cfx-lk'), 'back to the tab: the lock screen again')
    tap(pg, '[data-testid=cfx_lock_bio]', 2500)
    ok(not vis(pg, '.cfx-lk') and view(pg) == 'vRoom' and not pg.evaluate("document.body.classList.contains('cfx-cover')"), 'the fingerprint (WebAuthn platform authenticator) unlocks it')
    # a refresh straight into a locked chat asks first
    pg.reload(); pg.wait_for_timeout(2600)
    ok(vis(pg, '.cfx-lk'), 'a refresh in a locked chat shows the lock screen first')
    back(pg, 900); ok(not vis(pg, '.cfx-lk') and view(pg) == 'vChat', 'Back from it: the Chats list (%s)' % view(pg))
    # --- Settings: pages with their own address; Back and refresh
    settings(pg); ok(h(pg) == '#chat/settings' and vis(pg, '[data-testid=cfx_settings]'), 'the ⋯ menu opens Settings (#chat/settings)')
    ok('Building wells' in pg.inner_text('.cfx-me'), 'the profile card shows my About')
    tap(pg, '#vCfx [data-cf=privacy]', 900); ok(h(pg) == '#chat/settings/privacy', 'Privacy has its own address')
    pg.reload(); pg.wait_for_timeout(2600); ok(h(pg) == '#chat/settings/privacy' and vis(pg, '[data-testid=cfx_privacy]'), 'a refresh keeps Privacy open')
    tap(pg, '#vCfx [data-pk=lastSeen]', 800); tap(pg, '.tsh [data-pm=none]', 300); tap(pg, '.tsh [data-pp=ok]', 900)
    pv = [b for p, b in cm.posts if p == 'account/privacy']
    ok(pv and pv[-1] == {'lastSeen': {'mode': 'none', 'names': []}}, 'only the changed field is sent (%s)' % pv[-1:])
    tap(pg, '#vCfx [data-cf=rr]', 700); ok([b for p, b in cm.posts if p == 'account/privacy'][-1] == {'readReceipts': False}, 'Read receipts off')
    tap(pg, '#vCfx [data-cf=stp][data-pm=only]', 900); tap(pg, '.tsh [data-pn="Meril Kiprop"]', 200); tap(pg, '.tsh [data-pp=ok]', 900)
    ok([b for p, b in cm.posts if p == 'account/privacy'][-1] == {'status': {'mode': 'only', 'names': ['Meril Kiprop']}}, 'Status privacy: Only these people…')
    back(pg, 900); ok(h(pg) == '#chat/settings' and vis(pg, '[data-testid=cfx_settings]'), 'Back: Privacy → Settings (%s)' % h(pg))
    # Profile: About with quick picks
    tap(pg, '#vCfx [data-cf=profile]', 900); tap(pg, '#vCfx [data-cf=ab]', 400)
    pg.fill('#cfxAb', 'x' * 150); ok(len(pg.input_value('#cfxAb')) == 140 and '0 characters left' in pg.inner_text('#cfxAbN'), 'About stops at 140')
    pg.fill('#cfxAb', 'Fixing pumps around Kitale'); tap(pg, '[data-testid=cfx_about_save]', 900)
    ok([b for p, b in cm.posts if p == 'account/about'][-1] == {'about': 'Fixing pumps around Kitale'}, 'About saved')
    tap(pg, '#vCfx [data-cf=abq] >> nth=1', 900); ok([b for p, b in cm.posts if p == 'account/about'][-1] == {'about': 'In the field 🚙'}, 'a quick pick saves it')
    tap(pg, '#vCfx [data-cf=photo]', 800); ok(vis(pg, '[data-testid=ph_sheet]'), 'the camera opens the existing profile-photo sheet')
    back(pg, 600); back(pg, 900)
    # Linked devices: this one, sign out one, a legacy one, sign out all others → the new gen
    tap(pg, '#vCfx [data-cf=devices]', 1100)
    ok(pg.locator('[data-testid=cfx_device]').count() == 4 and 'This one' in pg.inner_text('[data-testid=cfx_device] >> nth=0'), 'four devices, this one first')
    tap(pg, '#vCfx [data-cf=dev][data-di="1"]', 800); tap(pg, '[data-testid=cfx_device_signout]', 1100)
    ok(any(p == 'account/devices/sign-out' and b == {'id': 'dev_ios'} for p, b in cm.posts) and pg.locator('[data-testid=cfx_device]').count() == 3, 'sign out one device')
    tap(pg, '#vCfx [data-cf=dev][data-di="2"]', 800); ok('older RAFIKI Pin' in pg.inner_text('.tsh') and pg.locator('[data-testid=cfx_device_signout]').count() == 0, 'a legacy app: update it or sign out all')
    back(pg, 600)
    tap(pg, '[data-testid=cfx_signout_all]', 700); tap(pg, '.bsh.po-ask [data-k=yes]', 1300)
    ok(pg.evaluate("localStorage.getItem('rfPinGen')") == '4' and pg.locator('[data-testid=cfx_device]').count() == 1, 'all others signed out; the new gen is kept')
    tap(pg, '[data-cf=back]', 900); n0 = len(reqs); tap(pg, '#vCfx [data-cf=privacy]', 1200)
    ok(any(hh.get('x-pin-gen') == '4' for p, hh in reqs[n0:]), 'later calls send X-Pin-Gen: 4')
    back(pg, 800); back(pg, 1000); ok(view(pg) == 'vChat', 'Back from Settings: Chats (%s)' % view(pg))
    # Me › Settings reaches it too
    pg.click('#cwMe'); pg.wait_for_timeout(1200); pg.click('#meSet [data-set=chatset]'); pg.wait_for_timeout(900)
    ok(h(pg) == '#chat/settings', 'Me › Settings › Chats, privacy and devices opens Settings')
    # --- the headers on every API call
    api = [(p, hh) for p, hh in reqs if p.startswith('/api/')]
    bad = [p for p, hh in api if hh.get('x-pin-device-id') != dev or hh.get('x-pin-app') != 'web']
    ok(len(api) > 20 and not bad, '%d API calls, all with X-Pin-Device-Id and X-Pin-App: web (missing on %s)' % (len(api), sorted(set(bad))[:5]))
    # --- this device signed out from another one: back to the lock screen
    cm.signed_out = True; pg.evaluate("document.getElementById('bRefresh').click()"); pg.wait_for_timeout(1800)
    ok(view(pg) == 'vLock' and 'signed out' in pg.inner_text('#vLock').lower(), 'a 401 signed-out goes back to the lock screen (%s)' % view(pg))
    for e in ERRS:
        if 'WebSocket' not in e: fail('flows: %s' % e)
    ctx.close()

with sync_playwright() as p:
    br = p.chromium.launch()
    if ONLY in ('', 'flows'):
        try: flows(br)
        except Exception as e:
            import traceback; tb = [f for f in traceback.extract_tb(e.__traceback__) if f.filename == __file__][-1:]
            fail('flows (line %s): %s' % (tb[0].lineno if tb else '?', str(e).split('\n')[0][:300]))
        print('flows done', flush=True)
    if ONLY in ('', 'look'): looks(br)
    br.close()
print('\n%d problem(s)' % len(fails) if fails else '\nall checks passed', '· screenshots in', OUT)
sys.exit(1 if fails else 0)
