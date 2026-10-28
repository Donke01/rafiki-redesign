"""QA for the website Stories pages (stories.html hub, story.html, the /stories -> /projects redirect).

    python3 qa/web/stories.py [out_dir]

Starts qa/web/serve_stories.py on port 8894 (sample stories from qa/web/stories-fixture.json), then with one
Chromium browser: screenshots of every page and state at 320, 360, 390, 768, 1280 and 1440 px, and checks for
sideways overflow, things off screen or overlapping, words broken across lines, images without size or alt
text, console errors, reduced motion, and Back / refresh from the hub to a story and back.
Exits non-zero when a check fails. Screenshots go to out_dir (default /tmp/stories-qa), not into the repo.
"""
import os, subprocess, sys, time, urllib.request
from playwright.sync_api import sync_playwright

HERE = os.path.dirname(os.path.abspath(__file__)); ROOT = os.path.dirname(os.path.dirname(HERE))
OUT = sys.argv[1] if len(sys.argv) > 1 else '/tmp/stories-qa'; os.makedirs(OUT, exist_ok=True)
PORT = 8894; BASE = 'http://localhost:%d' % PORT
WIDTHS = [320, 360, 390, 768, 1280, 1440]
S1 = '/stories/9100000001-water-at-the-school-gate'
S2 = '/stories/9100000002-a-classroom-with-walls-of-its-own'
S3 = '/stories/9100000003-a-playground-built-from-old-tyres-at-the-childrens-home-in-busia'
PAGES = [('hub', '/stories', 'ok'), ('hub-kenya', '/stories?where=ke', 'ok'), ('hub-empty', '/stories', 'empty'), ('hub-down', '/stories', 'down'),
         ('story1', S1, 'ok'), ('story2', S2, 'ok'), ('story3', S3, 'ok'), ('story-gone', '/stories/42-not-here', 'ok')]
fails = []
def fail(msg): fails.append(msg); print('FAIL', msg)
def mode(m): urllib.request.urlopen(BASE + '/__qa/mode?stories=' + m).read()

# runs in the page: problems with layout and text
CHECK = r"""() => {
  const W = document.documentElement.clientWidth, out = [];
  if (document.documentElement.scrollWidth > W + 1) out.push('page scrolls sideways: ' + document.documentElement.scrollWidth + ' > ' + W);
  const scrollers = [...document.querySelectorAll('.sy-strip,.sx-chips')];
  const inScroller = el => scrollers.some(s => s.contains(el));
  const vis = el => { const r = el.getBoundingClientRect(), cs = getComputedStyle(el); return r.width > 0 && r.height > 0 && cs.visibility !== 'hidden' && cs.display !== 'none'; };
  const name = el => el.tagName.toLowerCase() + (el.className && typeof el.className === 'string' ? '.' + el.className.trim().split(/\s+/).join('.') : '') + ' "' + (el.textContent || '').trim().slice(0, 40) + '"';
  // off screen: anything in the page content sticking out sideways (strips and chip rows scroll on purpose)
  document.querySelectorAll('main *, .sx-cta *, .sy-share *').forEach(el => {
    if (!vis(el) || inScroller(el) || el.closest('.sx-sky,.sy-bg')) return;
    const r = el.getBoundingClientRect();
    if (r.left < -1 || r.right > W + 1) out.push('off screen: ' + name(el) + ' ' + Math.round(r.left) + '..' + Math.round(r.right));
  });
  // broken words: a word whose letters land on two lines
  const walker = document.createTreeWalker(document.querySelector('.pg') || document.body, NodeFilter.SHOW_TEXT);
  let n; const range = document.createRange();
  while ((n = walker.nextNode())) {
    const p = n.parentElement; if (!p || !vis(p) || p.closest('script,style,.sx-sr,footer,.hd-wrap')) continue;
    const re = /[A-Za-zÀ-ɏ'’]{4,}/g; let m;
    while ((m = re.exec(n.nodeValue))) {
      range.setStart(n, m.index); range.setEnd(n, m.index + m[0].length);
      const rs = [...range.getClientRects()].filter(r => r.width > 0);
      if (rs.length > 1 && Math.abs(rs[0].top - rs[rs.length - 1].top) > 4) out.push('broken word: "' + m[0] + '" in ' + name(p));
    }
  }
  // overlaps: items stacked in the heroes, card parts, facts, share buttons
  const groups = ['.sx-hin > *', '.sy-hin > *', '.sy-chips > li', '.sx-fb > *', '.sy-facts > *', '.sy-share > *', '.sx-cf > *', '.sy-give > *', '.sx-sh *', '.sx-frow > *', '.sy-jh > *'];
  groups.forEach(sel => {
    const els = [...document.querySelectorAll(sel)].filter(vis).filter(e => !e.closest('.sx-sky'));
    for (let i = 0; i < els.length; i++) for (let j = i + 1; j < els.length; j++) {
      if (els[i].contains(els[j]) || els[j].contains(els[i])) continue;
      const a = els[i].getBoundingClientRect(), b = els[j].getBoundingClientRect();
      const ox = Math.min(a.right, b.right) - Math.max(a.left, b.left), oy = Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top);
      if (ox > 2 && oy > 2) out.push('overlap: ' + name(els[i]) + ' / ' + name(els[j]));
    }
  });
  // images: a size for the layout (no shift) and alt text (flags are decorative, next to the country name)
  document.querySelectorAll('.pg img').forEach(im => {
    if (im.closest('.hd-wrap,footer')) return;
    if (!im.getAttribute('width') || !im.getAttribute('height')) out.push('image without size: ' + im.src.slice(-60));
    if (!im.classList.contains('sx-flag') && !(im.getAttribute('alt') || '').trim()) out.push('image without alt: ' + im.src.slice(-60));
  });
  return out;
}"""

srv = subprocess.Popen([sys.executable, os.path.join(HERE, 'serve_stories.py'), ROOT, str(PORT)], stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT)
try:
    for _ in range(50):
        try: urllib.request.urlopen(BASE + '/__qa/mode'); break
        except Exception: time.sleep(0.1)
    with sync_playwright() as p:
        b = p.chromium.launch()
        for tag, path, m in PAGES:
            mode(m)
            for w in WIDTHS:
                ctx = b.new_context(viewport={'width': w, 'height': 900 if w > 700 else 780}); pg = ctx.new_page(); errs = []
                pg.on('pageerror', lambda e: errs.append(str(e)))
                pg.on('console', lambda c: c.type == 'error' and 'status of 404' not in c.text and 'status of 503' not in c.text and errs.append(c.text))
                pg.goto(BASE + path); pg.wait_for_timeout(1200)
                # scroll through once so lazy photos load and reveals finish, then check and shoot
                h = pg.evaluate('document.documentElement.scrollHeight')
                for y in range(0, h, 700): pg.evaluate('y => window.scrollTo(0, y)', y); pg.wait_for_timeout(60)
                pg.evaluate('window.scrollTo(0, 0)'); pg.wait_for_timeout(1500)
                for e in pg.evaluate(CHECK): fail('%s @%d: %s' % (tag, w, e))
                for e in errs: fail('%s @%d: console: %s' % (tag, w, e))
                pg.screenshot(path=os.path.join(OUT, '%s-%d.png' % (tag, w)), full_page=True)
                ctx.close()
        mode('ok')
        # states that must say the right thing
        ctx = b.new_context(viewport={'width': 390, 'height': 800}); pg = ctx.new_page()
        pg.goto(BASE + '/stories/42-not-here'); pg.wait_for_timeout(1000)
        if 'not on the website' not in pg.inner_text('main'): fail('missing story: no message')
        mode('empty'); pg.goto(BASE + '/stories'); pg.evaluate('sessionStorage.clear()'); pg.reload(); pg.wait_for_timeout(1000)
        if 'The first stories are on their way' not in pg.inner_text('body'): fail('empty hub: no message')
        mode('ok'); pg.evaluate('sessionStorage.clear()')
        # old project-list links: the server sends them on, and so does the page itself on hosts without it
        pg.goto(BASE + '/stories?cat=water&status=completed'); pg.wait_for_timeout(800)
        if not pg.url.startswith(BASE + '/projects?cat=water&status=completed'): fail('server redirect: ' + pg.url)
        pg.goto(BASE + '/stories.html?kind=borehole&status=completed'); pg.wait_for_timeout(800)
        if '/projects?kind=borehole&status=completed' not in pg.url and '/project-list?kind=borehole&status=completed' not in pg.url: fail('page redirect: ' + pg.url)
        pg.goto(BASE + '/projects.html?cat=water'); pg.wait_for_timeout(800)
        if '/project-list?cat=water' not in pg.url: fail('projects page fallback: ' + pg.url)
        # Back / refresh: hub (filtered) -> story -> refresh -> back
        pg.goto(BASE + '/stories?where=tz'); pg.wait_for_timeout(1000)
        pg.click('.sx-card .sx-ct a'); pg.wait_for_url('**/stories/9100000002-*'); pg.wait_for_timeout(1000)
        if pg.inner_text('#sy-h1') != 'A classroom with walls of its own': fail('story from hub: ' + pg.inner_text('#sy-h1'))
        pg.reload(); pg.wait_for_timeout(1000)
        if pg.inner_text('#sy-h1') != 'A classroom with walls of its own': fail('story after refresh')
        pg.go_back(); pg.wait_for_timeout(1200)
        if not pg.url.endswith('/stories?where=tz'): fail('back to hub: ' + pg.url)
        if pg.locator('.sx-chip.on').first.inner_text().strip().split('\n')[0] != 'Tanzania': fail('filter kept after back')
        # a story link without its slug is put right in the address bar
        pg.goto(BASE + '/stories/9100000003'); pg.wait_for_timeout(1000)
        if not pg.url.endswith(S3): fail('slug fixed: ' + pg.url)
        # share: copy link says so
        ctx.grant_permissions(['clipboard-read', 'clipboard-write'], origin=BASE)
        pg.goto(BASE + S1); pg.wait_for_timeout(800); pg.click('.sy-share button'); pg.wait_for_timeout(300)
        if 'Link copied' not in pg.inner_text('.sy-msg'): fail('copy link: ' + pg.inner_text('.sy-msg'))
        if pg.evaluate('navigator.clipboard.readText()') != BASE + S1: fail('copied text')
        hrefs = pg.eval_on_selector_all('.sy-share a', 'as => as.map(a => a.href)')
        if not (len(hrefs) == 3 and 'wa.me' in hrefs[0] and 'facebook.com' in hrefs[1] and 'twitter.com' in hrefs[2]): fail('share links: %s' % hrefs)
        ctx.close()
        # reduced motion: nothing moves
        ctx = b.new_context(viewport={'width': 1280, 'height': 900}, reduced_motion='reduce'); pg = ctx.new_page()
        for path in ('/stories', S2):
            pg.goto(BASE + path); pg.wait_for_timeout(1200)
            n = pg.evaluate("document.getAnimations().filter(a => a.playState === 'running' && !(a.effect && a.effect.target && a.effect.target.closest && a.effect.target.closest('.hd-wrap,footer'))).length")
            if n: fail('reduced motion: %d animations running on %s' % (n, path))
        ctx.close()
        b.close()
finally:
    srv.terminate()
print('screenshots:', OUT)
print('OK' if not fails else '%d problem(s)' % len(fails))
sys.exit(1 if fails else 0)
