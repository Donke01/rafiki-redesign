# A mocked /api/chat/* for the web app (chatw): one small world of chats and messages that answers the client
# contract in the chat brief, keeps state while a test runs, and drives a fake WebSocket.
#   from chat_mock import ChatMock;  m = ChatMock(); m.install(page)      m.push({...}) sends a server event
import json, re, io, math, struct, datetime, itertools
from urllib.parse import urlparse, parse_qs

ME = 'Reid Anderson'
# chat ids as the server makes them (t: team, d: direct, g: group, p: project); message ids are numbers, increasing within a chat
CID = {'all': 't:all', 'ke': 't:ke', 'dm-meril': 'd:meril', 'p101': 'p:101', 'g-kitale': 'g:kitale', 'dm-grace': 'd:grace'}
SYS_EN = {'repair': 'Bertin reported a repair', 'assigned': 'Assigned to Meril Kiprop', 'sms': 'A community report arrived (SMS)', 'created': 'Reid made the group'}
def ms(iso): return int(datetime.datetime.strptime(iso, '%Y-%m-%dT%H:%M:%S.000Z').replace(tzinfo=datetime.timezone.utc).timestamp() * 1000)
def now_ms(): return int(datetime.datetime.now(datetime.timezone.utc).timestamp() * 1000)
TEAM = ['Reid Anderson', 'Meril Kiprop', 'Grace Achieng', 'Bertin Mwangi', 'Jessica Anderson']

def png(rgb, w=480, h=360):
    try:
        from PIL import Image, ImageDraw
        im = Image.new('RGB', (w, h), rgb); d = ImageDraw.Draw(im)
        for i in range(0, w, 40): d.line([(i, h), (i + 160, h // 3)], fill=(255, 255, 255), width=3)
        d.ellipse([w // 2 - 50, h // 2 - 50, w // 2 + 50, h // 2 + 50], fill=(255, 255, 255))
        b = io.BytesIO(); im.save(b, 'PNG'); return b.getvalue()
    except Exception:
        return bytes.fromhex('89504e470d0a1a0a0000000d4948445200000001000000010806000000'
                             '1f15c4890000000d49444154789c6360f8cf000000030101005d2b8f0a0000000049454e44ae426082')

def wav(sec=2.0, rate=8000):
    n = int(sec * rate); data = b''.join(struct.pack('<h', int(9000 * math.sin(i * 2 * math.pi * 440 / rate) * (1 - i / n))) for i in range(n))
    return b'RIFF' + struct.pack('<I', 36 + len(data)) + b'WAVEfmt ' + struct.pack('<IHHIIHH', 16, 1, 1, rate, rate * 2, 2, 16) + b'data' + struct.pack('<I', len(data)) + data

class ChatMock:
    def __init__(self, off=False):
        self.off = off; self.down = False; self.posts = []; self.gets = []; self.ws = []; self.ids = itertools.count(500)
        now = datetime.datetime.now(datetime.timezone.utc)
        self.now = now
        at = lambda mins: (now - datetime.timedelta(minutes=mins)).strftime('%Y-%m-%dT%H:%M:%S.000Z')
        self.at = at
        WAVE = [20, 35, 60, 80, 55, 30, 45, 70, 90, 65, 40, 25, 50, 75, 85, 60, 35, 20, 40, 65, 80, 55, 30, 45, 70, 50, 30, 20, 35, 55, 40, 25]
        def m(c, i, frm, mins, kind='text', text=None, **kw):
            d = {'id': i, 'c': c, 'cid': 'srv' + i, 'from': frm, 'at': at(mins), 'kind': kind, 'text': text, 'media': [], 'reply': None, 'fwd': False, 'mentions': [], 'loc': None, 'live': None,
                 'contact': None, 'reacts': {}, 'edited': None, 'deleted': False, 'sys': None, 'status': 'read' if frm == ME else None}
            d.update(kw); return d
        D = 24 * 60
        self.msgs = {
            'dm-meril': [
                m('dm-meril', 'm1', 'Meril Kiprop', 3 * D + 40, text='Habari Reid! I reached Kapsaret Borehole this morning.'),
                m('dm-meril', 'm2', ME, 3 * D + 35, text='Great. How does the pump look?'),
                m('dm-meril', 'm3', 'Meril Kiprop', D + 90, 'media', 'The handle and the apron', media=[{'id': 'img1', 'type': 'photo', 'w': 480, 'h': 360}, {'id': 'img2', 'type': 'photo', 'w': 480, 'h': 360}, {'id': 'img3', 'type': 'photo', 'w': 480, 'h': 360}]),
                m('dm-meril', 'm4', ME, D + 80, text='Thanks. Here’s the project: https://pin.rafikioutreach.org/pin#p/101', reacts={'👍': ['Meril Kiprop']}),
                m('dm-meril', 'm5', 'Meril Kiprop', D + 70, 'location', loc={'lat': 0.47012, 'lng': 35.25031}),
                m('dm-meril', 'm6', 'Meril Kiprop', 50, 'voice', media=[{'id': 'vo1', 'type': 'voice', 'dur': 12, 'wave': WAVE}]),
                m('dm-meril', 'm7', ME, 42, text='Got it, I’ll order the cylinder today.', reply={'id': 'm5', 'from': 'Meril Kiprop', 'kind': 'location', 'text': ''}, edited=at(41)),
                m('dm-meril', 'm8', 'Meril Kiprop', 30, 'contact', contact={'name': 'Bertin Mwangi'}),
                m('dm-meril', 'm9', 'Meril Kiprop', 12, 'doc', media=[{'id': 'doc1', 'type': 'doc', 'name': 'Kapsaret-quote.pdf', 'size': 182000}]),
                m('dm-meril', 'm10', 'Meril Kiprop', 6, text='Asante! I’ll be at the site at 9. Bertin knows the committee chair.', reacts={'🙏': ['Reid Anderson'], '❤️': ['Meril Kiprop']}),
            ],
            'p101': [
                m('p101', 'p1', 'RAFIKI Pin', 2 * D, sys={'type': 'repair', 'project': '101', 'by': 'Bertin Mwangi'}),
                m('p101', 'p2', 'Bertin Mwangi', 2 * D - 5, text='The handle came off on Monday. Community says no water since.'),
                m('p101', 'p3', 'RAFIKI Pin', 2 * D - 30, sys={'type': 'assigned', 'project': '101', 'to': 'Meril Kiprop', 'by': ME}),
                m('p101', 'p4', 'Grace Achieng', 300, text='@Reid Anderson can we approve the parts today?', mentions=['Reid Anderson']),
                m('p101', 'p5', 'RAFIKI Pin', 200, sys={'type': 'sms', 'project': '101'}),
                m('p101', 'p6', 'Meril Kiprop', 25, 'live', loc={'lat': 0.4699, 'lng': 35.2511}, live={'until': int((now + datetime.timedelta(minutes=35)).timestamp() * 1000), 'lat': 0.4701, 'lng': 35.2509, 'at': at(2)}),
                m('p101', 'p7', 'Grace Achieng', 20, text='Old message', deleted=True),
                m('p101', 'p8', 'Bertin Mwangi', 15, 'text', 'Forwarding the quote from Eldoret Hardware', fwd=True),
                m('p101', 'p9', 'Meril Kiprop', 4, 'media', media=[{'id': 'vid1', 'type': 'video', 'w': 640, 'h': 360, 'dur': 18, 'poster': 'img4'}]),
            ],
            'g-kitale': [
                m('g-kitale', 'g1', 'RAFIKI Pin', 5 * D, sys={'type': 'created', 'by': ME}),
                m('g-kitale', 'g2', 'Grace Achieng', 3 * D, 'media', media=[{'id': 'img4', 'type': 'photo', 'w': 360, 'h': 480}]),
            ],
            'all': [m('all', 'a1', 'Grace Achieng', 8, text='Morning team! Pump at Turbo Spring fixed ✓'), m('all', 'a2', 'Jessica Anderson', 3, text='Brilliant work 🙌')],
            'ke': [m('ke', 'k%d' % i, [ME, 'Meril Kiprop', 'Bertin Mwangi'][i % 3], 70 + (70 - i) * 9, text='Kenya team note %d' % i) for i in range(1, 70)] + [m('ke', 'k70', ME, 70, text='I’ll bring the cylinder tomorrow')],
            'dm-grace': [m('dm-grace', 'd1', 'Grace Achieng', 6 * D, text='Asante!')],
        }
        def last(c):
            x = self.msgs[c][-1]; return {'id': x['id'], 'from': x['from'], 'kind': x['kind'], 'text': x['text'] or '', 'at': x['at'], 'status': x.get('status'), 'deleted': x['deleted'], 'sys': x['sys']['type'] if x['sys'] else None}
        self.chats = {
            'all': {'id': 'all', 'kind': 'team', 'title': 'All RAFIKI', 'photo': '', 'project': None, 'members': TEAM, 'admins': [ME, 'Jessica Anderson'], 'unread': 2, 'mention': False, 'pinned': True, 'muted': 0, 'archived': False, 'onlyAdmins': False},
            'p101': {'id': 'p101', 'kind': 'project', 'title': 'Kapsaret Borehole', 'photo': '', 'project': '101', 'members': TEAM, 'admins': [ME], 'unread': 3, 'mention': True, 'pinned': False, 'muted': 0, 'archived': False, 'onlyAdmins': False},
            'dm-meril': {'id': 'dm-meril', 'kind': 'dm', 'title': 'Meril Kiprop', 'photo': '', 'project': None, 'members': [ME, 'Meril Kiprop'], 'admins': [], 'unread': 1, 'mention': False, 'pinned': False, 'muted': 0, 'archived': False, 'onlyAdmins': False},
            'ke': {'id': 'ke', 'kind': 'team', 'title': 'Kenya team', 'photo': '', 'project': None, 'members': [ME, 'Meril Kiprop', 'Bertin Mwangi'], 'admins': [ME], 'unread': 0, 'mention': False, 'pinned': False, 'muted': 0, 'archived': False, 'onlyAdmins': False},
            'g-kitale': {'id': 'g-kitale', 'kind': 'group', 'title': 'Kitale repairs', 'description': 'Parts, quotes and trips for the Kitale side.', 'photo': '', 'project': None, 'members': [ME, 'Grace Achieng', 'Bertin Mwangi'], 'admins': [ME], 'unread': 4, 'mention': False, 'pinned': False, 'muted': 'always', 'archived': False, 'onlyAdmins': False},
            'dm-grace': {'id': 'dm-grace', 'kind': 'dm', 'title': 'Grace Achieng', 'photo': '', 'project': None, 'members': [ME, 'Grace Achieng'], 'admins': [], 'unread': 0, 'mention': False, 'pinned': False, 'muted': 0, 'archived': True, 'onlyAdmins': False},
        }
        # to the contract's types: number ids, epoch-ms times, kind 'sys', signed media urls, prefixed chat ids
        self.MID = {}
        for sym, a in self.msgs.items():
            for i, x in enumerate(a): self.MID[x['id']] = i + 1
        for sym, a in self.msgs.items():
            for x in a:
                x['id'] = self.MID[x['id']]; x['c'] = CID[sym]; x['at'] = ms(x['at'])
                if x['edited']: x['edited'] = ms(x['edited'])
                if x['reply']: x['reply']['id'] = self.MID[x['reply']['id']]
                if x['live'] and isinstance(x['live'].get('at'), str): x['live']['at'] = ms(x['live']['at'])
                if x['sys']: x['kind'] = 'sys'; x['from'] = ''; x['text'] = SYS_EN.get(x['sys']['type'], '')
                for md in x['media']:
                    md['url'] = '/api/chat/media/%s?e=1&s=sig' % md['id']
                    if md.get('poster'): md['posterUrl'] = '/api/chat/media/%s?e=1&s=sig' % md['poster']
        self.msgs = {CID[k]: v for k, v in self.msgs.items()}
        self.chats = {CID[k]: dict(v, id=CID[k]) for k, v in self.chats.items()}
        for k in self.chats: self.chats[k]['last'] = last(k); self.chats[k].setdefault('wallpaper', ''); self.chats[k].setdefault('description', '')
        self.presence = {'Meril Kiprop': {'online': True, 'seen': ms(at(0))}, 'Grace Achieng': {'online': False, 'seen': ms(at(95))}, 'Bertin Mwangi': {'online': False, 'seen': ms(at(60 * 26))}}
        self.media = {'img1': ('image/png', png((10, 120, 194))), 'img2': ('image/png', png((28, 156, 90))), 'img3': ('image/png', png((227, 154, 30))), 'img4': ('image/png', png((122, 79, 208), 360, 480)),
                      'vo1': ('audio/wav', wav()), 'doc1': ('application/pdf', b'%PDF-1.4\n%mock\n'), 'vid1': ('image/png', png((90, 96, 102)))}

    # ---- the fake server
    def msg_by(self, c, i): return next((x for x in self.msgs.get(c, []) if x['id'] == i), None)
    def fulfill(self, route, j, status=200): route.fulfill(status=status, content_type='application/json', body=json.dumps(j))
    def handle(self, route, req):
        u = urlparse(req.url); path = u.path.replace('/api/chat/', '', 1); q = {k: v[0] for k, v in parse_qs(u.query).items()}
        if req.method == 'GET': self.gets.append((path, q))
        if self.off: return self.fulfill(route, {'ok': False, 'reason': 'chat-off', 'need': ['CHAT service binding → rafiki-chat']})
        if self.down: return self.fulfill(route, {'ok': False, 'reason': 'down'}, 503)
        body = {}
        if req.method == 'POST' and path != 'media':
            try: body = json.loads(req.post_data or '{}')
            except Exception: body = {}
            self.posts.append((path, body))
        if path.startswith('media/'):
            mid = path.split('/', 1)[1]; t, b = self.media.get(mid, ('image/png', png((60, 70, 80))))
            return route.fulfill(status=200, content_type=t, body=b)
        if path == 'media':
            if q.get('type') not in ('photo', 'video', 'voice', 'doc'): return self.fulfill(route, {'ok': False, 'reason': 'bad-request'}, 400)
            mid = 'up%d' % next(self.ids); self.media[mid] = (req.headers.get('content-type', 'application/octet-stream'), req.post_data_buffer or b'')
            self.posts.append(('media', {'type': q.get('type'), 'name': q.get('name'), 'size': len(req.post_data_buffer or b''), 'dur': q.get('dur')}))
            return self.fulfill(route, {'ok': True, 'id': mid, 'url': '/api/chat/media/%s?e=1&s=sig' % mid, 'type': q.get('type'), 'size': len(req.post_data_buffer or b''), 'name': q.get('name')})
        if path == 'list':
            return self.fulfill(route, {'ok': True, 'me': ME, 'now': now_ms(), 'chats': list(self.chats.values()), 'presence': self.presence, 'gone': [] if q.get('since') else None})
        if path == 'msgs':
            a = self.msgs.get(q.get('c'), []); lim = int(q.get('limit', 50))
            if q.get('before'): a = [x for x in a if x['id'] < int(q['before'])]
            if q.get('after'): a = [x for x in a if x['id'] > int(q['after'])]; return self.fulfill(route, {'ok': True, 'msgs': a[:lim], 'more': len(a) > lim})
            return self.fulfill(route, {'ok': True, 'msgs': a[-lim:], 'more': len(a) > lim})
        if path == 'send':
            c = body['c']; old = next((x for x in self.msgs.get(c, []) if x.get('cid') == body.get('cid')), None)
            if old: return self.fulfill(route, {'ok': True, 'msg': old})
            x = {'id': max([m['id'] for m in self.msgs.get(c, [])] or [0]) + 1, 'c': c, 'cid': body.get('cid'), 'from': ME, 'at': now_ms(), 'kind': body.get('kind'),
                 'text': body.get('text'), 'media': body.get('media') or [], 'reply': body.get('reply'), 'fwd': bool(body.get('fwd')), 'mentions': body.get('mentions') or [], 'loc': body.get('loc'),
                 'live': body.get('live'), 'contact': body.get('contact'), 'reacts': {}, 'edited': None, 'deleted': False, 'sys': None, 'status': 'sent'}
            if x['kind'] == 'live' and x['loc']: x['live'] = dict(x['live'] or {}, lat=x['loc']['lat'], lng=x['loc']['lng'], at=x['at'])
            for md in x['media']: md['url'] = '/api/chat/media/%s?e=1&s=sig' % md['id']
            self.msgs.setdefault(c, []).append(x); self.chats[c]['last'] = {'id': x['id'], 'from': ME, 'kind': x['kind'], 'text': x['text'] or '', 'at': x['at'], 'status': 'sent'}
            return self.fulfill(route, {'ok': True, 'msg': x})
        if path == 'open':
            if body.get('kind') == 'dm':
                w = body.get('with'); k = next((c for c in self.chats.values() if c['kind'] == 'dm' and w in c['members']), None)
                if not k:
                    cid = 'd:' + w.split()[0].lower(); k = self.chats[cid] = {'id': cid, 'kind': 'dm', 'title': w, 'photo': '', 'project': None, 'members': [ME, w], 'admins': [], 'unread': 0, 'mention': False, 'pinned': False, 'muted': 0, 'archived': False, 'onlyAdmins': False, 'last': None}
                    self.msgs[cid] = []
                return self.fulfill(route, {'ok': True, 'id': k['id']})
            pid = str(body.get('project')); k = next((c for c in self.chats.values() if c['kind'] == 'project' and c['project'] == pid), None)
            if not k:
                cid = 'p:' + pid; k = self.chats[cid] = {'id': cid, 'kind': 'project', 'title': 'Project ' + pid, 'photo': '', 'project': pid, 'members': TEAM, 'admins': [ME], 'unread': 0, 'mention': False, 'pinned': False, 'muted': 0, 'archived': False, 'onlyAdmins': False, 'last': None}
                self.msgs[cid] = []
            return self.fulfill(route, {'ok': True, 'id': k['id']})
        if path == 'edit':
            x = self.msg_by(body['c'], body['id']); x['text'] = body['text']; x['edited'] = now_ms(); return self.fulfill(route, {'ok': True, 'msg': x})
        if path == 'delete':
            x = self.msg_by(body['c'], body['id'])
            if body.get('forAll'): x.update(deleted=True, text='', media=[])
            else: self.msgs[body['c']].remove(x)
            return self.fulfill(route, {'ok': True})
        if path == 'react':
            x = self.msg_by(body['c'], body['id']); rx = x['reacts']
            for k in list(rx): rx[k] = [n for n in rx[k] if n != ME]
            if body.get('emoji'): rx.setdefault(body['emoji'], []).append(ME)
            return self.fulfill(route, {'ok': True, 'reacts': rx})
        if path == 'info':
            x = self.msg_by(q.get('c'), int(q.get('id', 0)))
            if not x or x['from'] != ME: return self.fulfill(route, {'ok': False, 'reason': 'not-yours'}, 403)
            return self.fulfill(route, {'ok': True, 'read': [{'name': 'Meril Kiprop', 'at': ms(self.at(3))}], 'delivered': [{'name': 'Meril Kiprop', 'at': ms(self.at(5))}, {'name': 'Grace Achieng', 'at': ms(self.at(4))}]})
        if path == 'link':
            tok = 'NEWTOKEN' if body.get('reset') else 'K1TALE7'; return self.fulfill(route, {'ok': True, 'url': 'https://pin.rafikioutreach.org/pin#chat/join/' + tok, 'token': tok})
        if path == 'search':
            ql = q.get('q', '').lower(); hits = [{'c': c, 'id': x['id'], 'from': x['from'], 'at': x['at'], 'snippet': x['text']} for c, a in self.msgs.items() for x in a if x['text'] and ql in x['text'].lower() and not x['deleted']]
            return self.fulfill(route, {'ok': True, 'hits': hits[:20]})
        if path == 'preview':
            return self.fulfill(route, {'ok': True, 'title': 'RAFIKI Outreach', 'description': 'Clean water, schools and health in East Africa.', 'image': '', 'site': 'rafikioutreach.org'})
        if path == 'starred':
            return self.fulfill(route, {'ok': True, 'msgs': [dict(self.msgs['d:meril'][3], chat='')]})
        if path == 'ticket':
            return self.fulfill(route, {'ok': True, 't': 'tk1', 'exp': now_ms() + 60000})
        if path == 'group':
            cid = 'g:%d' % next(self.ids); self.chats[cid] = {'id': cid, 'kind': 'group', 'title': body['title'], 'description': body.get('description', ''), 'photo': '', 'project': None, 'members': [ME] + body['members'], 'admins': [ME], 'unread': 0, 'mention': False, 'pinned': False, 'muted': 0, 'archived': False, 'onlyAdmins': False, 'last': None}
            self.msgs[cid] = []; return self.fulfill(route, {'ok': True, 'id': cid})
        if path == 'join':
            return self.fulfill(route, {'ok': True, 'id': 'g:kitale'})
        if path in ('read', 'delivered', 'prefs', 'star', 'group-update', 'members', 'leave', 'live', 'live-stop'):
            if path == 'prefs' and body.get('c') in self.chats:
                for k in ('pinned', 'muted', 'archived', 'wallpaper'):
                    if k in body: self.chats[body['c']][k] = body[k]
            if path == 'group-update' and body.get('c') in self.chats:
                for k in ('title', 'description', 'photo', 'onlyAdmins'):
                    if k in body: self.chats[body['c']][k] = body[k]
            if path == 'read' and body.get('c') in self.chats: self.chats[body['c']]['unread'] = 0; self.chats[body['c']]['mention'] = False
            if path in ('prefs', 'group-update', 'members') and body.get('c') in self.chats: return self.fulfill(route, {'ok': True, 'chat': self.chats[body['c']]})
            return self.fulfill(route, {'ok': True})
        return self.fulfill(route, {'ok': False, 'reason': 'unknown'}, 404)

    def on_ws(self, ws):
        self.ws.append(ws); self.wsin = getattr(self, 'wsin', [])
        ws.on_message(lambda m: self.wsin.append(m))
    def push(self, ev):
        for w in self.ws:
            try: w.send(json.dumps(ev))
            except Exception: pass
    def install(self, pg):
        pg.route(re.compile(r'/api/chat/(?!ws)'), self.handle)
        pg.route_web_socket(re.compile(r'/api/chat/ws'), self.on_ws)
