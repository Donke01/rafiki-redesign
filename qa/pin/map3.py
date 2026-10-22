# Map home, round 3 (map3w): calm by default, pins that talk, short animations. Screenshots of each state in each theme,
# language and width, a few behaviour checks, and the time to draw 250 projects on a 4× slower CPU (a mid-range phone).
#   BASE=http://localhost:8879 OUT=/tmp/shots python3 qa/pin/map3.py [quick]
# Uses the mock in nav.py (projects around Eldoret, "You're at" Chepkongi) plus three projects on one spot and a few
# working ones, so every ring colour, a cluster ring, a spread-out spot and the names show.
import os, re, sys, time
HERE = os.path.dirname(os.path.abspath(__file__))
argv = sys.argv[:]; sys.argv = [argv[0], 'en']
g = {'__file__': os.path.join(HERE, 'nav.py')}
exec(open(os.path.join(HERE, 'nav.py')).read().split('\nRESULTS = []')[0], g)
sys.argv = argv
from playwright.sync_api import sync_playwright
BASE = g['BASE']; OUT = os.environ.get('OUT', os.path.join(HERE, 'map3')); os.makedirs(OUT, exist_ok=True)
pj, iso, P, VIS = g['pj'], g['iso'], g['P'], g['VIS']
P += [pj('119', 'Kapsaret Primary School Tank', 'Kapsaret, Eldoret', [0.47, 35.25], 'School'), pj('120', 'Kapsaret Health Post', 'Kapsaret, Eldoret', [0.47, 35.25], 'Health'),
      pj('121', 'Kaptagat Farm', 'Kaptagat', [0.43, 35.48], 'Farming'), pj('122', 'Upendo Children’s Home', 'Eldoret', [0.55, 35.31], 'Orphanage'),
      pj('123', 'Sergoit Borehole', 'Sergoit', [0.66, 35.38]), pj('124', 'Langas Water Kiosk', 'Langas', [0.497, 35.262], 'Water collection'),
      pj('125', 'Huruma Primary School', 'Huruma', [0.528, 35.287], 'School'), pj('126', 'Kimumu Well', 'Kimumu', [0.535, 35.248]), pj('127', 'Pioneer Clinic', 'Pioneer', [0.503, 35.296], 'Health')]
VIS.update({'124': {'at': iso(24 * 20), 'by': 'Meril Kiprop', 'state': 'working'}, '126': {'at': iso(24 * 40), 'by': 'Meril Kiprop', 'state': 'working'}, '119': {'at': iso(24 * 12), 'by': 'Meril Kiprop', 'state': 'working'}, '121': {'at': iso(24 * 3), 'by': 'Meril Kiprop', 'state': 'working'},
            '122': {'at': iso(24 * 30), 'by': 'Reid Anderson', 'state': 'working'}, '123': {'at': iso(24 * 9), 'by': 'Meril Kiprop', 'state': 'working'}})
TILE = g['TILE']; ERRS = []
HM = "window.__maps.filter(x => x.getContainer().id === 'hMap')[0]"

def context(br, lang, w=390, h=844, theme='', reduce=False):
    ctx = br.new_context(viewport={'width': w, 'height': h}, device_scale_factor=2, is_mobile=w < 700, has_touch=w < 700, service_workers='block', locale='en-GB', timezone_id='Africa/Nairobi',
                         geolocation={'latitude': 0.515, 'longitude': 35.27, 'accuracy': 8}, permissions=['geolocation'], reduced_motion='reduce' if reduce else 'no-preference')
    ctx.route(re.compile(r'https://(tile\.openstreetmap\.org|server\.arcgisonline\.com)/.*'), lambda r, q: r.fulfill(status=200, body=TILE, headers={'Content-Type': 'image/png', 'Access-Control-Allow-Origin': '*'}))
    ctx.add_init_script("(() => { let _L; Object.defineProperty(window, 'L', { configurable: true, get() { return _L; }, set(v) { _L = v; try { v.Map.addInitHook(function () { (window.__maps = window.__maps || []).push(this); }); } catch (e) {} } }); })()")
    pg = ctx.new_page(); g['install'](pg)
    pg.on('pageerror', lambda e: ERRS.append('pageerror: ' + str(e)[:200]))
    pg.goto(BASE + '/pin'); pg.wait_for_timeout(700)
    pg.evaluate("([l, t]) => { localStorage.setItem('rfPinOnb', '1'); if (l === 'sw') localStorage.setItem('rfPinLang', 'sw'); if (t) localStorage.setItem('rfPinTheme', t); }", [lang, theme])
    pg.reload(); pg.wait_for_timeout(700)
    for d in '9999': pg.click('.pk[data-k="%s"]' % d)
    pg.wait_for_timeout(2200)
    return ctx, pg

def shot(pg, name, clip=None):
    pg.wait_for_timeout(450); fn = os.path.join(OUT, name + '.png')
    if clip: pg.screenshot(path=fn, clip=clip)
    else: pg.screenshot(path=fn)
    return fn
def calm(pg): return pg.evaluate("document.getElementById('vMap').classList.contains('mcl-clean')")
def tap_empty(pg): x, y = g['empty_spot'](pg); pg.mouse.click(x, y); pg.wait_for_timeout(700)
def view(pg, ll, z): pg.evaluate("([ll, z]) => %s.setView(ll, z, { animate: false })" % HM, [ll, z]); pg.wait_for_timeout(500)
def pin(pg, pid): pg.evaluate("id => document.querySelector('#hMap .mpa-pin[data-id=\"' + id + '\"]').click()", pid); pg.wait_for_timeout(900)
def around(pg, sel, pad=70):
    b = pg.evaluate("s => { const e = document.querySelector(s); if (!e) return null; const r = e.getBoundingClientRect(); return [r.left, r.top, r.width, r.height]; }", sel)
    if not b: return None
    x, y = max(0, b[0] - pad), max(0, b[1] - pad)
    return {'x': x, 'y': y, 'width': min(b[2] + 2 * pad, pg.viewport_size['width'] - x), 'height': min(b[3] + 2 * pad, pg.viewport_size['height'] - y)}

CHECKS = []
def check(c, what): CHECKS.append((bool(c), what)); print(('ok   ' if c else 'FAIL ') + what, flush=True)

def full(br, lang, w=390, h=844, theme='', tag=''):
    t = tag or '%s-%d%s' % (lang, w, '-' + theme if theme else '')
    ctx, pg = context(br, lang, w, h, theme)
    check(calm(pg), '[%s] the map opens calm' % t)
    check(pg.evaluate("!!document.getElementById('m3Hint')"), '[%s] the one-time hint shows' % t)
    shot(pg, t + '-1-calm')
    tap_empty(pg); check(not calm(pg), '[%s] a tap on empty map reveals the tiles' % t); shot(pg, t + '-2-revealed')
    view(pg, [0.52, 35.27], 14); pin(pg, '102'); check(pg.evaluate("!!document.getElementById('mpaCard').offsetParent"), '[%s] a pin opens its card' % t)
    check(pg.evaluate("document.getElementById('hMap').classList.contains('m3-dim') && document.querySelector('.mpa-pin.on[data-id=\"102\"]') != null"), '[%s] the pin lifts, the others step back' % t)
    shot(pg, t + '-3-card')
    pg.evaluate('history.back()'); pg.wait_for_timeout(700); check(not pg.evaluate("!!document.getElementById('mpaCard').offsetParent") and not calm(pg), '[%s] Back closes the card only' % t)
    pg.evaluate('history.back()'); pg.wait_for_timeout(700); check(calm(pg) and pg.evaluate('location.hash') == '#map', '[%s] Back again puts the tiles away, still on Map' % t)
    # names at zoom 13
    view(pg, [0.515, 35.272], 13); n = pg.evaluate("document.querySelectorAll('#hMap .m3-lb').length")
    check(n > 0, '[%s] names show at zoom 13 (%d)' % (t, n))
    lap = pg.evaluate("""(() => { const r = [...document.querySelectorAll('#hMap .m3-lb, #hMap .mpa-dot, #hMap .mpa-bub')].map(e => [e, e.getBoundingClientRect()]); let k = 0;
      for (let i = 0; i < r.length; i++) for (let j = i + 1; j < r.length; j++) { if (!r[i][0].classList.contains('m3-lb') && !r[j][0].classList.contains('m3-lb')) continue; if (r[i][0].closest('.leaflet-marker-icon') === r[j][0].closest('.leaflet-marker-icon')) continue;
        const a = r[i][1], b = r[j][1]; if (Math.min(a.right, b.right) - Math.max(a.left, b.left) > 1 && Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top) > 1) k++; } return k; })()""")
    check(lap == 0, '[%s] no name overlaps a pin or another name (%d)' % (t, lap))
    shot(pg, t + '-4-names')
    view(pg, [0.6, 35.25], 13); view(pg, [0.6, 35.25], 13); return ctx, pg

with sync_playwright() as p:
    br = p.chromium.launch()
    quick = len(sys.argv) > 1 and sys.argv[1] == 'quick'
    for lang in ('en', 'sw'):
        ctx, pg = full(br, lang)
        # a cluster: its ring split by condition, the number in the middle
        view(pg, [0.6, 35.2], 9); pg.evaluate("(() => { const c = [...document.querySelectorAll('#hMap .mpa-cl')].sort((a, b) => b.dataset.n - a.dataset.n)[0]; if (c) c.id = 'm3Big'; })()"); cl = around(pg, '#m3Big', 70); check(cl is not None, '[%s] a cluster at zoom 9' % lang)
        if cl: shot(pg, lang + '-390-5-cluster', cl)
        rings = pg.evaluate("[...document.querySelectorAll('#hMap .mpa-bub')].map(b => b.style.getPropertyValue('--m3r')).filter(Boolean).length")
        check(rings > 0, '[%s] clusters carry a condition ring (%d)' % (lang, rings))
        # three projects on one spot: a tap spreads them out
        view(pg, [0.47, 35.25], 17); n = pg.evaluate("document.querySelectorAll('#hMap .mpa-cl').length")
        pg.evaluate("document.querySelector('#hMap .mpa-cl').click()"); pg.wait_for_timeout(700)
        sp = pg.evaluate("document.querySelectorAll('#hMap .m3-spi').length"); check(n >= 1 and sp == 3, '[%s] a spot with 3 projects spreads out (%d)' % (lang, sp))
        shot(pg, lang + '-390-6-spread', around(pg, '#hMap .m3-spc', 110))
        # a long press: Report a visit, Directions, Open
        view(pg, [0.5143, 35.2698], 15)
        pg.evaluate("""(() => { const e = document.querySelector('#hMap .mpa-pin[data-id="102"] .mpa-dot'), r = e.getBoundingClientRect(); e.dispatchEvent(new MouseEvent('contextmenu', { bubbles: true, cancelable: true, clientX: r.left + 10, clientY: r.top + 10, button: 2 })); })()""")
        pg.wait_for_timeout(500); check(pg.evaluate("document.querySelectorAll('#m3Qa [data-m3q]').length") == 3, '[%s] a long press opens 3 quick actions' % lang)
        shot(pg, lang + '-390-7-quick')
        pg.evaluate('history.back()'); pg.wait_for_timeout(700); check(not pg.evaluate("!!document.getElementById('m3Qa')") and pg.evaluate('location.hash') == '#map', '[%s] Back closes the quick actions only' % lang)
        # a filter: leaving pins shrink out, the pill stays in calm
        view(pg, [0.6, 35.25], 10); tap_empty(pg)
        pg.click('#mpaChips [data-mf=broken]'); pg.wait_for_timeout(60); out = pg.evaluate("document.querySelectorAll('#hMap .m3-out').length"); pg.wait_for_timeout(900)
        check(out > 0, '[%s] a filter shrinks the pins that leave (%d)' % (lang, out))
        if not calm(pg): tap_empty(pg)
        check(pg.evaluate("!!document.getElementById('mclPill').offsetParent"), '[%s] the filter pill stays in calm' % lang); shot(pg, lang + '-390-8-filter-calm')
        # locate me: a short flight, the you-dot pulses once
        pg.click('#hMap .lc-me a'); pg.wait_for_timeout(250)
        pinged = pg.evaluate("new Promise(r => { const t0 = Date.now(), f = () => document.querySelector('#hMap .you.m3-ping') ? r(true) : Date.now() - t0 > 900 ? r(false) : setTimeout(f, 20); f(); })")
        check(pinged, '[%s] Show where I am: the you-dot pulses once' % lang)
        # the bottom bar: back to calm, no filter
        pg.click('#tabs button[data-v=vList]'); pg.wait_for_timeout(900); pg.click('#tabs button[data-v=vMap]'); pg.wait_for_timeout(1500)
        check(calm(pg) and not pg.evaluate("!!document.getElementById('mclPill').offsetParent"), '[%s] the bottom bar brings the map back calm, no filter' % lang)
        ctx.close()
        if quick: break
        for theme in ('dark', 'sun'):
            ctx, pg = full(br, lang, theme=theme); ctx.close()
        for w, h in ((360, 740), (1280, 800)):
            ctx, pg = full(br, lang, w, h); ctx.close()
    # reduced motion: nothing animates
    ctx, pg = context(br, 'en', reduce=True); tap_empty(pg)
    check(pg.evaluate("!document.getElementById('vMap').classList.contains('m3-in') && getComputedStyle(document.querySelector('#hMap .mpa-dot.c-bad'), '::before').animationName === 'none'"), 'reduced motion: no reveal animation, no pulse')
    ctx.close()
    # 250 projects on a 4× slower CPU
    base = list(P); P[:] = [pj(str(1000 + i), 'Site %d Borehole' % i, 'Area %d' % (i % 40), [0.1 + (i % 25) * 0.04, 34.7 + (i // 25) * 0.09], ['Borehole well', 'School', 'Health', 'Orphanage', 'Farming'][i % 5]) for i in range(250)]
    ctx, pg = context(br, 'en'); cdp = ctx.new_cdp_session(pg); cdp.send('Emulation.setCPUThrottlingRate', {'rate': 4})
    ms = []
    for z in (8, 10, 12, 13, 14):
        view(pg, [0.6, 35.15], z)
        ms.append(pg.evaluate("z => { const m = %s, t = performance.now(); m.fire('zoomend'); return Math.round(performance.now() - t); }" % HM, z))
    check(max(ms) < 120, '250 projects, CPU 4× slower: a redraw takes %s ms at zoom 8/10/12/13/14' % ms)
    shot(pg, 'perf-250-z13'); ctx.close(); P[:] = base
    br.close()
bad = [c for c in CHECKS if not c[0]]
print('\n%d of %d checks pass; page errors: %s' % (len(CHECKS) - len(bad), len(CHECKS), ERRS[:5] or 'none'))
sys.exit(1 if bad or ERRS else 0)
