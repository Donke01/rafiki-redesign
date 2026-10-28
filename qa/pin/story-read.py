# Stories tab: tapping a story that already has words (draft, approved, on the website) offers View story / Edit story;
# View opens the story as a full page with "Open on the website" at the top. Back closes it. EN/SW, 390 and 320.
import os, re, json
SRC = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'stories-hub.py')
code = open(SRC).read().split('\nwith sync_playwright() as p:')[0].replace("os.path.join(HERE, 'stories-hub')", "os.path.join(HERE, 'story-read')")
exec(compile(code, 'hub-mocks', 'exec'))
DOC = {'v': 1, 'title': 'Water at the school gate', 'dek': 'How a dry hillside got its own clean water.', 'pull': {'quote': 'Now we drink clean water every day.', 'who': 'Mary, mother of three'},
       'sections': [{'h': 'The long walk', 'body': 'Families walked four kilometres to the river.\n\nThe water was brown in the rains.', 'photo': {'id': '9301', 'row': 'before', 'cap': 'The river path'}},
                    {'h': 'Drilling day', 'body': 'The rig reached water at sixty metres.', 'photo': None}, {'h': 'Today', 'body': 'Children fill their cans at the gate.', 'photo': {'id': '9302', 'row': 'after', 'cap': 'At the tap'}}]}
def read_api(route, req):
    if 'asset=' in req.url: return route.fulfill(body=open(os.path.join(HERE, 'tile.png'), 'rb').read(), content_type='image/png')
    m = re.search(r'id=(\d+)', req.url); pid = m.group(1) if m else '107'
    if pid == '107': return route.fulfill(json={'ok': True, 'story': {'id': pid, 'status': 'published', 'doc': DOC, 'text': 'x', 'pub': {'url': 'https://test.rafikioutreach.org/stories/107-water', 'at': iso(1)}, 'photos': {'before': [], 'progress': [], 'after': []}, 'answers': {}, 'pool': [], 'missing': [], 'prompts': {}, 'dates': {}}})
    return story_api(route, req)
with sync_playwright() as p:
    b = p.chromium.launch()
    for W, H, tag, sw in [(390, 844, 'en', 0), (320, 640, 'p320', 0), (360, 740, 'sw', 1)]:
        ctx = b.new_context(viewport={'width': W, 'height': H}, device_scale_factor=2)
        pg, errs = login(ctx); pg.route('**/api/story**', read_api)
        if sw: pg.evaluate("localStorage.setItem('rfPinLang','sw')"); pg.reload(); pg.wait_for_timeout(1500)
        pg.click('#tabStories'); pg.wait_for_timeout(900); pg.click('[data-shf=published]'); pg.wait_for_timeout(400)
        pg.click('#shBody [data-shopen="107"]'); pg.wait_for_timeout(700)
        check(pg.locator('[data-testid=sh_choose]').is_visible(), tag + ': View story / Edit story')
        pg.screenshot(path=OUT + '/%s-choose.png' % tag)
        pg.click('[data-testid=sh_view]'); pg.wait_for_timeout(1500)
        check(pg.locator('[data-testid=sh_read] h2').inner_text() == 'Water at the school gate', tag + ': the story as a full page')
        web = pg.locator('[data-testid=sh_web]'); check(web.is_visible() and web.get_attribute('href').endswith('/stories/107-water'), tag + ': Open on the website at the top')
        r = pg.evaluate("(() => { const h = document.querySelector('.sh-rdsh .bsh-h').getBoundingClientRect(), a = document.querySelector('[data-testid=sh_web]').getBoundingClientRect(), x = document.querySelector('.sh-rdsh .bsh-x').getBoundingClientRect(), p = document.querySelector('.sh-rdsh .bsh-p').getBoundingClientRect(); return [Math.round(p.top), Math.round(p.height), a.right <= x.left + 1, a.top < h.bottom, x.right <= innerWidth]; })()")
        check(r[0] <= 1 and r[1] >= H - 2 and r[2] and r[3] and r[4], tag + ': full page, link at the top right beside Close ' + str(r))
        check(pg.evaluate("document.documentElement.scrollWidth <= innerWidth + 1"), tag + ': nothing sideways')
        pg.screenshot(path=OUT + '/%s-read.png' % tag)
        pg.go_back(); pg.wait_for_timeout(900)
        check(pg.locator('[data-testid=sh_read]').count() == 0 and pg.evaluate('location.hash') == '#stories', tag + ': Back closes it, still on Stories')
        pg.click('#shBody [data-shopen="107"]'); pg.wait_for_timeout(700); pg.click('[data-testid=sh_edit]'); pg.wait_for_timeout(1600)
        check(pg.evaluate('location.hash') == '#p/107/story', tag + ': Edit story opens the writer ' + pg.evaluate('location.hash'))
        pg.go_back(); pg.wait_for_timeout(1000); check(pg.evaluate('location.hash') == '#stories', tag + ': Back to Stories')
        pg.click('[data-shf=need]'); pg.wait_for_timeout(300); pg.click('#shBody [data-shopen="104"]'); pg.wait_for_timeout(1500)
        check(pg.evaluate('location.hash') == '#p/104/story', tag + ': a story still collecting goes straight to the writer')
        check(not errs, tag + ': no page errors ' + str(errs[:3]))
        ctx.close()
    b.close()
print('PROBLEMS', problems)
