"""QA for the redesigned projects pages: /projects (overview), /projects?view=all (list), /projects?project=<id> (one project).

    python3 qa/web/projects-redesign.py <base_url> [out_dir]
    e.g.  python3 srvpin.py . 8960 & sleep 1; python3 qa/web/projects-redesign.py http://localhost:8960

Needs a plain static server for the checkout (no Pages Functions): /projects?view=all is sent on to
/project-list?view=all by the page itself. The live data is stood in for inside the browser:
  /api/projects  -> qa/web/projects-fixture.json (a copy of the live feed, 181 projects)
  /api/photo/... -> a few photos from /img, chosen by photo id
  /api/stories   -> no published stories
With one Chromium browser: screenshots of the three pages at 320, 360, 390, 768, 1024, 1280 and 1440 px
(into out_dir, default qa-out/projects, which git ignores), and checks for sideways scrolling, things off
screen or cut off, overlapping text, words broken across lines, images without size or alt text and console
errors. Then the features: group tabs, water kinds, country / status / year / sort menus, search, filter chips,
Show more, the phone Filters button, opening a project, Previous / Next, Share, the photo viewer, Back and refresh.
Exits non-zero when a check fails.
"""
import json, os, sys
from playwright.sync_api import sync_playwright

HERE = os.path.dirname(os.path.abspath(__file__)); ROOT = os.path.dirname(os.path.dirname(HERE))
BASE = (sys.argv[1] if len(sys.argv) > 1 else 'http://localhost:8960').rstrip('/')
OUT = sys.argv[2] if len(sys.argv) > 2 else os.path.join(ROOT, 'qa-out', 'projects'); os.makedirs(OUT, exist_ok=True)
WIDTHS = [320, 360, 390, 768, 1024, 1280, 1440]
FEED = open(os.path.join(HERE, 'projects-fixture.json'), 'rb').read()
PHOTOS = [open(os.path.join(ROOT, 'img', f), 'rb').read() for f in ('w-field3-600.webp', 'w-child-600.webp', 'washing-600.webp', 'w-elder-600.webp', 'w-drill2-600.webp')]
ID = '11030694394'  # ACK St John's Sigowet Church: 3 photos, completed, Kenya
PAGES = [('overview', '/projects'), ('list', '/projects?view=all'), ('project', '/projects?project=' + ID),
         ('project-nophoto', '/projects?project=18291679383'), ('list-beyond', '/projects?cat=school')]
fails = []
def fail(msg): fails.append(msg); print('FAIL', msg)
def ok(cond, msg):
    if not cond: fail(msg)

def stand_ins(ctx):
    ctx.route('**/api/projects*', lambda r: r.fulfill(status=200, body=FEED, content_type='application/json'))
    ctx.route('**/api/stories*', lambda r: r.fulfill(status=200, body='{"ok":true,"stories":[]}', content_type='application/json'))
    def photo(r):
        pid = r.request.url.split('/api/photo/')[1].split('?')[0]
        r.fulfill(status=200, body=PHOTOS[sum(map(ord, pid)) % len(PHOTOS)], content_type='image/webp')
    ctx.route('**/api/photo/**', photo)

# runs in the page: problems with layout and text in the redesigned parts (header, menu and footer are not part of it)
CHECK = r"""() => {
  const W = document.documentElement.clientWidth, out = [];
  if (document.documentElement.scrollWidth > W + 1) out.push('page scrolls sideways: ' + document.documentElement.scrollWidth + ' > ' + W);
  const roots = [...document.querySelectorAll('.pv-hero, main.pv, .pv-list, .pv-d')];
  const vis = el => { const r = el.getBoundingClientRect(), cs = getComputedStyle(el); return r.width > 0 && r.height > 0 && cs.visibility !== 'hidden' && cs.display !== 'none' && cs.opacity !== '0'; };
  const name = el => el.tagName.toLowerCase() + (el.className && typeof el.className === 'string' ? '.' + el.className.trim().split(/\s+/).join('.') : '') + ' "' + (el.textContent || '').trim().slice(0, 40) + '"';
  const all = roots.flatMap(r => [r, ...r.querySelectorAll('*')]).filter(el => !el.closest('.xmq-band,.xs.blue,svg'));
  all.forEach(el => {
    if (!vis(el)) return;
    const r = el.getBoundingClientRect();
    if (r.width <= 1 && r.height <= 1) return; // text for screen readers only
    // off screen sideways
    if (r.left < -1 || r.right > W + 1) out.push('off screen: ' + name(el) + ' ' + Math.round(r.left) + '..' + Math.round(r.right));
    // cut off: text wider or taller than its box where the box hides the rest
    const cs = getComputedStyle(el);
    const hasText = [...el.childNodes].some(n => n.nodeType === 3 && n.nodeValue.trim());
    if (hasText && (cs.overflowX !== 'visible' || cs.overflowY !== 'visible' || cs.textOverflow === 'ellipsis') && el.tagName !== 'SELECT' && el.tagName !== 'INPUT') {
      if (el.scrollWidth > el.clientWidth + 1 || el.scrollHeight > el.clientHeight + 2) out.push('cut off: ' + name(el));
    }
  });
  // menus: the chosen option fits its box
  roots.forEach(r => r.querySelectorAll('select').forEach(s => {
    if (!vis(s)) return;
    const c = document.createElement('span'); c.textContent = s.options[s.selectedIndex] ? s.options[s.selectedIndex].text : '';
    c.style.cssText = 'position:absolute;visibility:hidden;white-space:nowrap;font:' + getComputedStyle(s).font; document.body.appendChild(c);
    if (c.getBoundingClientRect().width > s.getBoundingClientRect().width + 1) out.push('menu text cut off: ' + s.id + ' "' + c.textContent + '"');
    c.remove();
  }));
  // text: words broken across lines, and pieces of text on top of each other
  const range = document.createRange(), boxes = [];
  roots.forEach(root => {
    const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT); let n;
    while ((n = walker.nextNode())) {
      const p = n.parentElement; if (!p || !n.nodeValue.trim() || !vis(p) || p.closest('script,style,svg,.xmq-band,.xs.blue') || getComputedStyle(p).position === 'absolute' && p.getBoundingClientRect().width <= 1) continue;
      const re = /[A-Za-zÀ-ɏ'’0-9,]{3,}/g; let m;
      while ((m = re.exec(n.nodeValue))) {
        range.setStart(n, m.index); range.setEnd(n, m.index + m[0].length);
        const rs = [...range.getClientRects()].filter(r => r.width > 0);
        if (rs.length > 1 && Math.abs(rs[0].top - rs[rs.length - 1].top) > 4) out.push('broken word: "' + m[0] + '" in ' + name(p));
      }
      range.selectNodeContents(n);
      [...range.getClientRects()].filter(r => r.width > 1 && r.height > 1).forEach(r => boxes.push([r, p]));
    }
  });
  for (let i = 0; i < boxes.length; i++) for (let j = i + 1; j < boxes.length; j++) {
    const [a, pa] = boxes[i], [b, pb] = boxes[j]; if (pa === pb) continue;
    const ox = Math.min(a.right, b.right) - Math.max(a.left, b.left), oy = Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top);
    if (ox > 2 && oy > 3) out.push('text overlaps: ' + name(pa) + ' / ' + name(pb));
  }
  // photos: a size for the layout and alt text (country photos sit beside the country name and are decorative)
  roots.forEach(r => r.querySelectorAll('img').forEach(im => {
    if (im.closest('.xmq-band')) return; // partner logos: not part of this redesign
    if (!im.getAttribute('width') && !im.closest('.pv-viewer,.pv-after')) out.push('image without size: ' + im.src.slice(-60));
    if (!im.hasAttribute('alt')) out.push('image without alt: ' + im.src.slice(-60));
    if (vis(im) && im.complete && !im.naturalWidth) out.push('image did not load: ' + im.src.slice(-60));
  }));
  return [...new Set(out)];
}"""

def settle(pg):
    pg.wait_for_timeout(900)
    h = pg.evaluate('document.documentElement.scrollHeight')
    for y in range(0, h, 600): pg.evaluate('y => window.scrollTo(0, y)', y); pg.wait_for_timeout(50)
    pg.evaluate('window.scrollTo(0, 0)'); pg.wait_for_timeout(900)

def text(pg, sel): return pg.inner_text(sel).strip() if pg.query_selector(sel) else ''
def cards(pg): return pg.eval_on_selector_all('.pv-list .pv-grid .pv-card .pv-card-t', 'els => els.map(e => e.textContent)')

with sync_playwright() as p:
    b = p.chromium.launch()
    # ---- every page at every width: layout checks and screenshots ----
    for tag, path in PAGES:
        for w in WIDTHS:
            ctx = b.new_context(viewport={'width': w, 'height': 900 if w > 700 else 780}); stand_ins(ctx)
            pg = ctx.new_page(); errs = []
            pg.on('pageerror', lambda e: errs.append(str(e)))
            pg.on('console', lambda c: c.type == 'error' and errs.append(c.text))
            pg.on('response', lambda r: r.status >= 400 and errs.append('HTTP %d %s' % (r.status, r.url)))
            pg.goto(BASE + path); settle(pg)
            for e in pg.evaluate(CHECK): fail('%s @%d: %s' % (tag, w, e))
            for e in errs: fail('%s @%d: console: %s' % (tag, w, e))
            pg.screenshot(path=os.path.join(OUT, '%s-%d.png' % (tag, w)), full_page=True)
            ctx.close()

    # ---- overview: live numbers and links ----
    ctx = b.new_context(viewport={'width': 1280, 'height': 900}); stand_ins(ctx); pg = ctx.new_page()
    pg.goto(BASE + '/projects'); settle(pg)
    figs = pg.eval_on_selector_all('.pv-figs dd', 'els => els.map(e => e.textContent.trim())')
    ok(figs == ['109', '4', '181', '178,032'], 'overview: figures ' + str(figs))
    st = pg.eval_on_selector_all('.pv-st-n', 'els => els.map(e => e.textContent.trim())')
    ok(st == ['167', '5', '9'], 'overview: status counts ' + str(st))
    hrefs = pg.eval_on_selector_all('.pv-st-go', 'els => els.map(e => e.getAttribute("href"))')
    ok(hrefs == ['/projects?status=completed', '/projects?status=underway', '/projects?status=funding'], 'overview: status links ' + str(hrefs))
    rows = pg.eval_on_selector_all('.pv-led-r', 'els => els.map(e => [e.querySelector("b").textContent, e.getAttribute("href"), [...e.querySelectorAll(".pv-led-c")].map(c => c.textContent).join("/")])')
    ok(['Borehole wells', '/projects?kind=borehole', '109/4/7'] in rows, 'overview: borehole row ' + str(rows[:1]))
    ok(len(rows) == 6, 'overview: %d kinds of project' % len(rows))
    ok(len(pg.query_selector_all('.pv-up-list li')) == 6, 'overview: projects looking for funding')
    cty = pg.eval_on_selector_all('.pv-cty-go', 'els => els.map(e => e.getAttribute("href"))')
    ok(cty == ['/kenya', '/tanzania', '/uganda', '/malawi'], 'overview: country links ' + str(cty))
    pg.click('.pv-st-go >> nth=2'); pg.wait_for_timeout(1500)
    ok('status=funding' in pg.url and 'looking for funding' in text(pg, '.pv-sumt'), 'overview: funding panel opens the filtered list (%s)' % pg.url)
    ctx.close()

    # ---- list: tabs, menus, search, sort, show more, chips ----
    ctx = b.new_context(viewport={'width': 1280, 'height': 900}); stand_ins(ctx); pg = ctx.new_page()
    pg.goto(BASE + '/projects?view=all'); settle(pg)
    ok(len(cards(pg)) == 12 and '181' in text(pg, '.pv-count'), 'list: first 12 of 181 (%s)' % text(pg, '.pv-count'))
    pg.click('.pv-more button'); pg.wait_for_timeout(300)
    ok(len(cards(pg)) == 24, 'list: Show more adds 12 (%d)' % len(cards(pg)))
    pg.click('.pv-tab:has-text("Water")'); pg.wait_for_timeout(400)
    ok('cat=water' in pg.url and text(pg, '.pv-sumt').startswith('157 water'), 'list: Water tab (%s, %s)' % (pg.url, text(pg, '.pv-sumt')))
    pg.click('.pv-kind:has-text("Borehole")'); pg.wait_for_timeout(300)
    ok('kind=borehole' in pg.url, 'list: borehole chip')
    ok(pg.query_selector('#pj-pump') is not None, 'list: pump menu shows for water')
    pg.select_option('#pj-status', 'Upcoming'); pg.wait_for_timeout(300)
    ok('status=funding' in pg.url and text(pg, '.pv-sumt').startswith('7 boreholes looking for funding'), 'list: status menu (%s)' % text(pg, '.pv-sumt'))
    ok(all(t == 'Looking for funding' for t in pg.eval_on_selector_all('.pv-card .pv-dot', 'els => els.map(e => e.textContent)')), 'list: cards show funding status')
    pg.select_option('#pj-country', 'mw'); pg.wait_for_timeout(300)
    ok('country=mw' in pg.url and len(cards(pg)) == 2, 'list: country menu (%d)' % len(cards(pg)))
    ok(len(pg.query_selector_all('.pv-x')) == 4, 'list: four filter chips')
    pg.click('.pv-x >> nth=0'); pg.wait_for_timeout(300)
    ok('country=' not in pg.url, 'list: removing a chip drops the filter')
    pg.click('.pv-reset'); pg.wait_for_timeout(300)
    ok('view=all' in pg.url and '181' in text(pg, '.pv-count'), 'list: Show all projects resets')
    pg.select_option('#pj-year', '2025'); pg.wait_for_timeout(300)
    ok('year=2025' in pg.url, 'list: year menu')
    pg.select_option('#pj-year', 'all'); pg.wait_for_timeout(200)
    first = cards(pg)[0]
    pg.select_option('#pj-sort', 'old'); pg.wait_for_timeout(300)
    ok(cards(pg)[0] != first, 'list: sort oldest first')
    pg.select_option('#pj-sort', 'new'); pg.wait_for_timeout(200)
    pg.fill('#pj-q', 'Sigowet'); pg.wait_for_timeout(400)
    ok(cards(pg) == ["ACK St John's Sigowet Church"] and 'q=Sigowet' in pg.url, 'list: search (%s)' % cards(pg))
    pg.fill('#pj-q', 'zzzz'); pg.wait_for_timeout(300)
    ok('No projects match' in text(pg, '.pv-list'), 'list: no results message')
    pg.fill('#pj-q', ''); pg.wait_for_timeout(300)
    pg.click('.pv-tab:has-text("Schools")'); pg.wait_for_timeout(300)
    ok('cat=school' in pg.url and len(cards(pg)) == 10, 'list: Schools tab')
    pg.click('.pv-tab:has-text("All")'); pg.wait_for_timeout(300)
    # open a project from the list, Previous / Next, Back
    pg.fill('#pj-q', ''); pg.click('.pv-card >> nth=0'); pg.wait_for_timeout(700)
    ok(pg.query_selector('.pv-d') is not None and 'project=' in pg.url, 'list: a card opens its project')
    t1 = text(pg, '#pd-title')
    pg.click('.pv-d-nav button >> nth=1'); pg.wait_for_timeout(500); t2 = text(pg, '#pd-title')
    ok(t2 and t2 != t1, 'project: Next (%s -> %s)' % (t1, t2))
    pg.click('.pv-d-nav button >> nth=0'); pg.wait_for_timeout(500)
    ok(text(pg, '#pd-title') == t1, 'project: Previous')
    pg.go_back(); pg.wait_for_timeout(800)
    ok(pg.query_selector('.pv-list .pv-grid') is not None and 'project=' not in pg.url, 'project: browser Back returns to the list (%s)' % pg.url)
    ctx.close()

    # ---- one project: share, photo viewer, refresh, Back from a shared link ----
    ctx = b.new_context(viewport={'width': 1280, 'height': 900}); stand_ins(ctx); pg = ctx.new_page()
    ctx.grant_permissions(['clipboard-read', 'clipboard-write'], origin=BASE)
    pg.goto(BASE + '/projects?project=' + ID); settle(pg)
    ok(text(pg, '#pd-title') == "ACK St John's Sigowet Church", 'project: title')
    ok(text(pg, '.pv-kick') == 'BOREHOLE WELL · 2026' or text(pg, '.pv-kick').lower() == 'borehole well · 2026', 'project: kicker ' + text(pg, '.pv-kick'))
    facts = dict(pg.eval_on_selector_all('.pv-glance dl > div', 'els => els.map(e => [e.querySelector("dt").textContent, e.querySelector("dd").textContent])'))
    ok(facts.get('People served') == '5,000' and facts.get('Depth') == '150 m' and facts.get('Status') == 'Completed', 'project: facts ' + str(facts))
    ok(len(pg.query_selector_all('.pv-step.done')) == 3, 'project: all three steps done')
    ok(pg.get_attribute('.pv-sponsor a', 'href') == '/donate', 'project: Give links to /donate')
    pg.click('.pv-shbtn'); pg.wait_for_timeout(600)
    msg = text(pg, '.pv-shmsg')
    ok(msg == 'Link copied' or '/project/' + ID in msg, 'project: share message "%s"' % msg)
    if msg == 'Link copied': ok(pg.evaluate('navigator.clipboard.readText()').endswith('/project/' + ID), 'project: share copies the /project/<id> link')
    pg.click('.pv-gal button >> nth=1'); pg.wait_for_timeout(500)
    ok(pg.is_visible('.pv-viewer') and text(pg, '.pv-viewer figcaption') == '2 of 3', 'viewer: opens on the photo clicked')
    ok(pg.evaluate('document.activeElement.classList.contains("pv-vx")'), 'viewer: focus on Close')
    pg.keyboard.press('ArrowRight'); pg.wait_for_timeout(200)
    ok(text(pg, '.pv-viewer figcaption') == '3 of 3', 'viewer: arrow key moves on')
    pg.click('.pv-vn button >> nth=1'); pg.wait_for_timeout(200)
    ok(text(pg, '.pv-viewer figcaption') == '1 of 3', 'viewer: Next wraps round')
    pg.wait_for_timeout(500)
    ok(pg.evaluate('(() => { const i = document.querySelector(".pv-viewer img"); return i.complete && i.naturalWidth > 0; })()'), 'viewer: photo loads')
    pg.keyboard.press('Escape'); pg.wait_for_timeout(300)
    ok(pg.query_selector('.pv-viewer') is None and 'project=' + ID in pg.url, 'viewer: Escape closes it and stays on the project')
    ok(pg.evaluate('!document.documentElement.classList.contains("pv-lock")'), 'viewer: page scrolls again after closing')
    pg.evaluate('window.scrollTo(0, 600)'); pg.wait_for_timeout(300)
    pg.reload(); pg.wait_for_timeout(2500)
    ok(text(pg, '#pd-title') == "ACK St John's Sigowet Church", 'project: refresh keeps the project')
    ok(abs(pg.evaluate('scrollY') - 600) < 60, 'project: refresh keeps the place on the page (%d)' % pg.evaluate('scrollY'))
    pg.click('.pv-tb.back'); pg.wait_for_timeout(600)
    ok(pg.query_selector('.pv-list .pv-grid') is not None, 'project: All projects goes to the list')
    ctx.close()

    # ---- phones: Filters button, Beyond the well chips, card layout ----
    ctx = b.new_context(viewport={'width': 390, 'height': 780}, has_touch=True, is_mobile=True); stand_ins(ctx); pg = ctx.new_page()
    pg.goto(BASE + '/projects?view=all'); settle(pg)
    ok(not pg.is_visible('#pj-country') and pg.is_visible('#pj-sort'), 'phone: country menu waits behind Filters, sort shows')
    pg.click('.pv-ftog'); pg.wait_for_timeout(300)
    ok(pg.is_visible('#pj-country') and pg.is_visible('#pj-status') and pg.get_attribute('.pv-ftog', 'aria-expanded') == 'true', 'phone: Filters opens the menus')
    pg.select_option('#pj-status', 'Completed'); pg.wait_for_timeout(300)
    ok(text(pg, '.pv-ftog') == 'Filters (1)', 'phone: Filters counts the filters (%s)' % text(pg, '.pv-ftog'))
    tabs = [t for t in pg.eval_on_selector_all('.pv-tab', 'els => els.filter(e => e.offsetWidth).map(e => e.firstChild.textContent)')]
    ok(tabs == ['All', 'Water', 'Beyond the well'], 'phone: three tabs ' + str(tabs))
    pg.click('.pv-tab:has-text("Beyond the well")'); pg.wait_for_timeout(300)
    ok(pg.is_visible('.pv-bsub') and pg.is_visible('.pv-bsub .pv-kind:has-text("Health")'), 'phone: Beyond the well groups as chips')
    pg.click('.pv-bsub .pv-kind:has-text("Health")'); pg.wait_for_timeout(300)
    ok('cat=health' in pg.url and 'on-m' in pg.get_attribute('.pv-tab:has-text("Beyond the well")', 'class'), 'phone: Health chip keeps Beyond the well underlined')
    pg.goto(BASE + '/projects?project=' + ID); settle(pg)
    pg.click('.pv-gal button >> nth=0'); pg.wait_for_timeout(400)
    ok(pg.is_visible('.pv-viewer') and text(pg, '.pv-viewer figcaption') == '1 of 3', 'phone: photo viewer')
    pg.click('.pv-vx'); pg.wait_for_timeout(300)
    ok(pg.query_selector('.pv-viewer') is None, 'phone: viewer closes')
    ctx.close()
    b.close()

print('\n%d problem(s); screenshots in %s' % (len(fails), OUT))
sys.exit(1 if fails else 0)
