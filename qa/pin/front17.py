# Me > Connect monday.com: open the token page, paste from the clipboard, connected as ... ✓
import re, os
from playwright.sync_api import sync_playwright
BASE = 'http://localhost:8797'; OUT = '/tmp/claude-0/-home-claude/a284d8b3-3cbb-5c42-a9ef-c5363bc71e30/scratchpad/web17'; os.makedirs(OUT, exist_ok=True)
P = [{'id': '101', 'title': 'Chepkongi Dispensary', 'country': 'Kenya', 'ck': 'ke', 'area': 'Eldoret', 'type': 'health', 'status': 'Completed', 'gps': [0.5143, 35.2698], 'photo': None}]
problems = []; posts = []
def check(c, w):
    print(('ok   ' if c else 'FAIL ') + w)
    if not c: problems.append(w)
TOK = 'eyJhbGciOiJIUzI1NiJ9.' + 'a' * 120 + '.sig'
def api(route, req):
    if req.method == 'POST':
        b = req.post_data_json; posts.append(b)
        if b.get('action') == 'monday-link': return route.fulfill(json={'ok': True, 'name': 'Meril Adoyo'} if b.get('token') == TOK else {'ok': False, 'reason': 'token-bad'}, status=200 if b.get('token') == TOK else 400)
        return route.fulfill(json={'ok': True})
    if 'activity=' in req.url: return route.fulfill(json={'ok': True, 'activity': []})
    if 'team=' in req.url: return route.fulfill(json={'ok': True, 'team': []})
    return route.fulfill(json={'ok': True, 'who': 'Meril Kiprop', 'linked': '', 'admin': False, 'reviewOpen': 0, 'projects': P, 'repairs': {}, 'visits': {}, 'jobs': [], 'assigned': [], 'confirmed': {}})
with sync_playwright() as p:
    br = p.chromium.launch(); ctx = br.new_context(viewport={'width': 390, 'height': 844}, device_scale_factor=2, service_workers='block', permissions=['clipboard-read', 'clipboard-write'])
    pg = ctx.new_page(); errs = []; pg.on('pageerror', lambda e: errs.append(str(e)))
    pg.route('**/api/pin-auth/me', lambda r, q: r.fulfill(json={'available': True, 'user': None})); pg.route(re.compile(r'/api/pin(\?.*)?$'), api)
    pg.route('**/rafikicap-company.monday.com/**', lambda r, q: r.fulfill(body='<h1>tokens</h1>', content_type='text/html'))
    pg.goto(BASE + '/pin'); pg.wait_for_timeout(800)
    for d in '9999': pg.click('.pk[data-k="%s"]' % d)
    pg.wait_for_timeout(1200); pg.evaluate("localStorage.setItem('rfPinOnb','1')"); pg.goto(BASE + '/pin#me'); pg.wait_for_timeout(1500)
    t = pg.inner_text('#meMonday'); check('Your name on monday.com' in t and pg.locator('#monOpen').count() == 1, 'connect card with the two steps')
    check(pg.get_attribute('#monOpen', 'href').endswith('/apps/manage/tokens'), 'step 1 opens the token page'); pg.screenshot(path=OUT + '/1-connect.png')
    with ctx.expect_page() as np: pg.click('#monOpen')
    np.value.close()
    pg.evaluate('t => navigator.clipboard.writeText(t)', 'not a token'); pg.click('#monPaste'); pg.wait_for_timeout(300)
    check('doesn’t look like' in pg.inner_text('#monMsg'), 'a wrong clipboard says so')
    pg.evaluate('t => navigator.clipboard.writeText(t)', TOK); pg.click('#monPaste'); pg.wait_for_timeout(500)
    check('Connected as Meril Adoyo ✓' in pg.inner_text('#meMonday'), 'paste and connect: connected as Meril Adoyo ✓')
    check(any(b.get('action') == 'monday-link' and b.get('token') == TOK for b in posts), 'token sent once to the server')
    check(pg.evaluate('navigator.clipboard.readText()') == '', 'clipboard cleared afterwards'); pg.screenshot(path=OUT + '/2-connected.png')
    pg.click('#monOff'); pg.wait_for_timeout(400); check(pg.locator('#monOpen').count() == 1, 'disconnect shows the steps again')
    check(not errs, 'no errors ' + str(errs[:1])); br.close()
print('PROBLEMS', problems)
