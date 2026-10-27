# The Stories tab (bottom bar): the hub with counts per status, "Write a story" picker, opening the project's story
# writer, Back to the hub, refresh on #stories, field user vs admin, Kiswahili, 320px. Run with the site on BASE.
import os, re, json
SRC = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'team.py')
code = open(SRC).read().split('\nwith sync_playwright() as p:')[0]
code = code.replace("OUT = os.path.join(HERE, 'team'); os.makedirs(OUT, exist_ok=True)", "OUT = os.environ.get('OUT', os.path.join(HERE, 'stories-hub')); os.makedirs(OUT, exist_ok=True)")
code = code.replace("for f in os.listdir(OUT):\n    if f.endswith('.png'): os.remove(os.path.join(OUT, f))\n", "")
exec(compile(code, 'team-mocks', 'exec'))
from playwright.sync_api import sync_playwright
STORIES = {'101': {'st': 'draft', 'n': 6, 'b': 1, 'i': 0, 'a': 2, 'c': 'received', 'at': iso(3)}, '104': {'st': 'collecting', 'n': 3, 'b': 0, 'i': 0, 'a': 1, 'c': 'not-yet', 'at': iso(30)}, '107': {'st': 'published', 'n': 6, 'b': 2, 'i': 1, 'a': 2, 'c': 'received', 'at': iso(300)}}
_snap = snap
def snap2(): s = _snap(); s['stories'] = json.loads(json.dumps(STORIES)); return s
snap = snap2
def story_api(route, req):
    m = re.search(r'id=(\d+)', req.url); pid = m.group(1) if m else '104'; x = STORIES.get(pid, {})
    return route.fulfill(json={'ok': True, 'story': {'id': pid, 'status': x.get('st', 'not-started'), 'answers': {'q1': 'Before, we walked 4 km'} if x else {}, 'photos': {'before': [], 'progress': [], 'after': []}, 'consent': x.get('c', 'not-yet'), 'quote': '', 'who': '', 'text': '', 'prompts': {}}})
def field_pin(route, req):
    if req.method == 'GET' and not re.search(r'(activity|history|id)=', req.url):
        s = snap(); s['admin'] = False; s['who'] = 'Meril Kiprop'; return route.fulfill(json=s)
    return pin_api(route, req)
def check(c, w): print(('ok   ' if c else 'FAIL ') + w); (None if c else problems.append(w))
with sync_playwright() as p:
    b = p.chromium.launch()
    for W, H, tag, sw, field in [(390, 844, 'admin', 0, 0), (390, 844, 'field', 0, 1), (320, 640, 'p320', 0, 0), (360, 740, 'sw', 1, 1)]:
        ctx = b.new_context(viewport={'width': W, 'height': H}, device_scale_factor=2)
        pg, errs = login(ctx)
        pg.route('**/api/story**', story_api)
        if field: pg.unroute(re.compile(r'/api/pin(\?.*)?$')); pg.route(re.compile(r'/api/pin(\?.*)?$'), field_pin); pg.reload(); pg.wait_for_timeout(1500)
        if sw: pg.evaluate("localStorage.setItem('rfPinLang','sw')"); pg.reload(); pg.wait_for_timeout(1500)
        pg.evaluate("localStorage.setItem('rfPinMeHint','1')")
        check(pg.locator('#tabStories').is_visible(), tag + ': Stories tab in the bar')
        tabs = pg.eval_on_selector_all('#tabs button:not(.hidden)', 'e => e.map(x => x.innerText.split("\\n")[0].trim())'); check(len(tabs) == 6, tag + ': 6 tabs ' + str(tabs))
        # labels never overflow their button
        box = pg.evaluate("[...document.querySelectorAll('#tabs button:not(.hidden)')].map(x => { const r = x.getBoundingClientRect(); return [x.innerText.split(String.fromCharCode(10))[0], Math.round(r.left), Math.round(r.right)]; })")
        check(all(l >= 0 and r <= W for _, l, r in box), tag + ': every tab inside the screen ' + str(box) + ' cols=' + pg.evaluate("getComputedStyle(document.querySelector('#tabs nav')).gridTemplateColumns"))
        over = pg.evaluate("(() => { document.querySelectorAll('#tabs .dot').forEach(d => d.style.display = 'none'); const o = [...document.querySelectorAll('#tabs button:not(.hidden)')].filter(x => x.scrollWidth > x.clientWidth + 1).map(x => x.innerText); document.querySelectorAll('#tabs .dot').forEach(d => d.style.display = ''); return o; })()"); check(not over, tag + ': no tab label overflows ' + str(over))
        out = pg.evaluate("(() => { const n = document.querySelector('#tabs nav').getBoundingClientRect(); return [...document.querySelectorAll('#tabs .dot:not(.hidden)')].filter(d => { const r = d.getBoundingClientRect(); return r.width && (r.right > n.right - 2 || r.left < n.left); }).map(d => d.parentNode.innerText); })()"); check(not out, tag + ': badges stay inside the bar ' + str(out))
        pg.click('#tabStories'); pg.wait_for_timeout(900)
        check(pg.evaluate('location.hash') == '#stories', tag + ': address #stories')
        nums = pg.eval_on_selector_all('[data-shf]', 'e => e.map(x => x.dataset.shf + "=" + x.querySelector("b").innerText)')
        check(nums == ['need=3', 'draft=1', 'approved=0', 'published=1'], tag + ': counts ' + str(nums))
        cur = pg.get_attribute('[data-shf][aria-pressed=true]', 'data-shf'); check(cur == ('need' if field else 'draft'), tag + ': opens on ' + cur)
        pg.screenshot(path=OUT + '/%s-1-hub.png' % tag, full_page=True)
        if field: check(pg.locator('#shDot').is_hidden(), tag + ': no dot for field users')
        else: check(pg.inner_text('#shDot') == '1', tag + ': admin dot shows 1 waiting')
        pg.click('[data-shf=need]'); pg.wait_for_timeout(300)
        order = pg.eval_on_selector_all('#shBody [data-shopen]', 'e => e.map(x => x.dataset.shopen)'); check(order[:1] == ['104'], tag + ': collecting first ' + str(order))
        pg.click('[data-sh=write]'); pg.wait_for_timeout(600); check(pg.locator('#shQ').count() == 1, tag + ': picker opens')
        pg.fill('#shQ', 'mzuzu'); pg.wait_for_timeout(200); check(pg.eval_on_selector_all('#shPickL [data-shopen]', 'e => e.map(x => x.dataset.shopen)') == ['107'], tag + ': search finds Mzuzu')
        pg.fill('#shQ', 'kapsaret'); pg.wait_for_timeout(200); pg.screenshot(path=OUT + '/%s-2-picker.png' % tag)
        pg.click('#shPickL [data-shopen="104"]'); pg.wait_for_timeout(1500)
        check(pg.evaluate('location.hash') == '#p/104/story', tag + ': writer opens on #p/104/story ' + pg.evaluate('location.hash'))
        check(pg.locator('#styBody .sty-h').count() == 1, tag + ': the story writer shows')
        pg.wait_for_timeout(700); check(pg.evaluate("(() => { const r = document.querySelector('#styBody .sty-h').getBoundingClientRect(); return r.top >= 0 && r.top < innerHeight * 0.6; })()"), tag + ': the writer is in view, not below the fold')
        check(pg.get_attribute('#tabStories', 'aria-current') == 'page', tag + ': Stories stays lit in the bar')
        check(pg.locator('[data-testid=sty_send]').count() == (1 if field else 0), tag + ': "sends it for review" note for field users only')
        pg.screenshot(path=OUT + '/%s-3-writer.png' % tag)
        pg.go_back(); pg.wait_for_timeout(1200); check(pg.evaluate('location.hash') == '#stories' and pg.locator('#vStories').is_visible(), tag + ': Back returns to Stories ' + pg.evaluate('location.hash'))
        pg.reload(); pg.wait_for_timeout(2000); check(pg.locator('#vStories').is_visible() and pg.locator('[data-shf]').count() == 4, tag + ': refresh stays on Stories')
        pg.click('#tabStories'); pg.wait_for_timeout(600); check(pg.locator('#vStories').is_visible(), tag + ': re-tap keeps Stories')
        check(not errs, tag + ': no page errors ' + str(errs[:1]))
        ctx.close()
print('PROBLEMS', problems)
