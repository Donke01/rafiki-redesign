"""Local server for QA of the website Stories pages (test-only; never deployed).

    python3 qa/web/serve_stories.py <checkout> <port> [--feed feed.json]

Serves the checkout like the static host (clean URLs: /stories -> stories.html) and stands in for the
Pages Functions the pages rely on, using qa/web/stories-fixture.json:
  GET /api/stories                 -> { ok, stories: index }       (the published-stories index)
  GET /api/stories?id=<id>         -> { ok, story } or 404
  GET /api/stories/photo?id=&asset=&w=  -> a stand-in photo from /img (only assets listed on that story)
  GET /stories/<id>-<slug>         -> story.html (the real function also fills the share tags)
  GET /stories?<project-list filter>  -> 301 /projects?<same>      (functions/stories/index.js)
  GET /projects?<project-list filter> -> project-list.html         (functions/projects.js)
  GET /api/projects                -> the feed file given with --feed, else 503 (the list shows its error state)
Test switches (QA only): GET /__qa/mode?stories=empty|down|ok&delay=<ms>
"""
import http.server, json, os, sys, time, urllib.parse

ROOT = os.path.abspath(sys.argv[1]); PORT = int(sys.argv[2])
FEED = sys.argv[sys.argv.index('--feed') + 1] if '--feed' in sys.argv else None
FIX = json.load(open(os.path.join(os.path.dirname(os.path.abspath(__file__)), 'stories-fixture.json')))
LIST_KEYS = {'country', 'cat', 'kind', 'status', 'serve', 'region', 'q', 'year', 'pump', 'project', 'view'}
MODE = {'stories': 'ok', 'delay': 0}
TYPES = {'.webp': 'image/webp', '.jpg': 'image/jpeg', '.png': 'image/png'}


class H(http.server.SimpleHTTPRequestHandler):
    def __init__(s, *a, **k): super().__init__(*a, directory=ROOT, **k)

    def log_message(s, *a): pass

    def send(s, code, body, ctype='application/json', headers=None):
        b = body if isinstance(body, bytes) else json.dumps(body).encode()
        s.send_response(code)
        s.send_header('Content-Type', ctype); s.send_header('Content-Length', str(len(b)))
        for k, v in (headers or {}).items(): s.send_header(k, v)
        s.end_headers(); s.wfile.write(b)

    def do_GET(s):
        u = urllib.parse.urlsplit(s.path); q = urllib.parse.parse_qs(u.query); path = u.path
        if path == '/__qa/mode':
            for k in ('stories', 'delay'):
                if k in q: MODE[k] = q[k][0] if k == 'stories' else int(q[k][0])
            return s.send(200, MODE)
        if path == '/api/stories':
            if MODE['delay']: time.sleep(MODE['delay'] / 1000)
            if MODE['stories'] == 'down': return s.send(503, {'ok': False, 'error': 'down'})
            if 'id' in q:
                st = None if MODE['stories'] == 'empty' else FIX['stories'].get(q['id'][0])
                return s.send(200, {'ok': True, 'story': st}) if st else s.send(404, {'ok': False, 'error': 'not-found'})
            return s.send(200, {'ok': True, 'stories': [] if MODE['stories'] == 'empty' else FIX['index']})
        if path == '/api/stories/photo':
            st = FIX['stories'].get((q.get('id') or [''])[0]); a = (q.get('asset') or [''])[0]
            if not st or a not in st['photos'] or a not in FIX['assets']: return s.send(404, {'ok': False})
            f = os.path.join(ROOT, FIX['assets'][a])
            return s.send(200, open(f, 'rb').read(), TYPES[os.path.splitext(f)[1]], {'Cache-Control': 'public, max-age=86400'})
        if path == '/api/projects':
            if FEED: return s.send(200, open(FEED, 'rb').read())
            return s.send(503, {'error': 'no feed'})
        if path.startswith('/api/'): return s.send(404, {'ok': False})
        listq = any(k in LIST_KEYS for k in q.keys()) or any(k in LIST_KEYS for k in urllib.parse.parse_qs(u.query, keep_blank_values=True))
        if path in ('/stories', '/stories/') and listq:
            return s.send(301, b'', 'text/plain', {'Location': '/projects?' + u.query})
        if path == '/projects' and listq: s.path = '/project-list.html' + ('?' + u.query if u.query else '')
        elif path.startswith('/stories/') and len(path) > 9: s.path = '/story.html'
        return super().do_GET()

    def translate_path(s, path):
        p = path.split('?')[0].split('#')[0]
        if not os.path.splitext(p)[1] and os.path.exists(os.path.join(ROOT, p.strip('/') + '.html')): path = p.rstrip('/') + '.html'
        return super().translate_path(path)


http.server.ThreadingHTTPServer(('', PORT), H).serve_forever()
