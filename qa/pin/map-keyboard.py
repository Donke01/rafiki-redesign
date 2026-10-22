# Map home at every height: nothing over the map may sit on top of anything else, calm or revealed, searching or not.
#   BASE=http://localhost:8878 python3 qa/pin/map-keyboard.py OUTDIR      (the API mock is S/audit-web/mock.py)
# States: calm (the map opens like this), calm + the search box from the round button (focused, typed, closed),
# revealed (a tap on empty map), revealed + search (focused, typed, closed), revealed + a card, and a filter's pill in calm.
import sys, os, re
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.environ.get('MOCK', os.path.join(HERE, '..', '..', '..', 'audit-web'))); import mock
from playwright.sync_api import sync_playwright
OUT = sys.argv[1]; os.makedirs(OUT, exist_ok=True); TILE = open(os.path.join(HERE, 'tile.png'), 'rb').read()
BASE = os.environ.get('BASE', 'http://localhost:8878')
CHK = """() => { const sel=['#mpaQ','.mpa-chips','#mpaKey','#mclKeyPop','.ov.top .pill','#mclPill','#mHere','#mclSb','#m3Hint','#nearSheet','#mpaCard','#mpaSug','.mcl-side','.leaflet-control-attribution']; const r=[];
 const vis=e=>{const s=getComputedStyle(e);const b=e.getBoundingClientRect();return s.display!='none'&&s.visibility!='hidden'&&+s.opacity>0.05&&b.width>4&&b.height>4&&!e.closest('.hidden')&&!e.closest('[hidden]')};
 const els=[];sel.forEach(q=>document.querySelectorAll('#vMap '+q).forEach(e=>{if(vis(e))els.push([q,e.getBoundingClientRect()])}));
 for(let i=0;i<els.length;i++)for(let j=i+1;j<els.length;j++){const a=els[i][1],c=els[j][1];const x=Math.min(a.right,c.right)-Math.max(a.left,c.left),y=Math.min(a.bottom,c.bottom)-Math.max(a.top,c.top);if(x>2&&y>2)r.push(els[i][0]+' x '+els[j][0])}return r;}"""
EMPTY = """(() => { const r = document.getElementById('hMap').getBoundingClientRect();
  for (let fy = .45; fy < .8; fy += .05) for (let fx = .1; fx < .7; fx += .06) { const x = r.left + r.width * fx, y = r.top + r.height * fy, e = document.elementFromPoint(x, y);
    if (e && (e.id === 'hMap' || e.classList.contains('leaflet-tile') || e.classList.contains('leaflet-container')) && ![...document.querySelectorAll('#hMap .leaflet-marker-icon')].some(m => { const b = m.getBoundingClientRect(); return x > b.left - 24 && x < b.right + 24 && y > b.top - 24 && y < b.bottom + 24; })) return [x, y]; }
  return [r.left + 30, r.top + r.height * .6]; })()"""
PICK = "(() => { const r = document.getElementById('hMap').getBoundingClientRect(), cx = r.left + r.width / 2, cy = r.top + r.height / 2; let best = null, bd = 1e9; document.querySelectorAll('#hMap .mpa-pin[data-id]').forEach(m => { const b = m.getBoundingClientRect(), d = Math.hypot(b.left + 22 - cx, b.top + 22 - cy); if (b.top > r.top && b.bottom < r.bottom && d < bd) { bd = d; best = m.dataset.id; } }); return best; })()"
def calm(pg): return pg.evaluate("document.getElementById('vMap').classList.contains('mcl-clean')")
def tap_empty(pg): x, y = pg.evaluate(EMPTY); pg.mouse.click(x, y); pg.wait_for_timeout(800)
bad = 0
with sync_playwright() as p:
    b = p.chromium.launch()
    for lang in ('en', 'sw'):
     for H in [int(x) for x in os.environ.get('HEIGHTS', '480,520,600,844').split(',')]:
        ctx = b.new_context(viewport={'width': 390, 'height': H}, device_scale_factor=2, is_mobile=True, has_touch=True)
        ctx.route(re.compile(r'https://(tile\.openstreetmap\.org|server\.arcgisonline\.com)/.*'), lambda r, q: r.fulfill(status=200, body=TILE, headers={'Content-Type': 'image/png'}))
        pg = ctx.new_page(); errs = []; pg.on('pageerror', lambda e: errs.append(str(e)[:200])); mock.install(pg, True, [])
        pg.goto(BASE + '/pin'); pg.wait_for_timeout(800)
        pg.evaluate("l => { localStorage.setItem('rfPinOnb', '1'); if (l === 'sw') localStorage.setItem('rfPinLang', 'sw'); }", lang); pg.reload(); pg.wait_for_timeout(800)
        for d in '9999': pg.click('.pk[data-k="%s"]' % d)
        pg.wait_for_timeout(2500); res = {}
        def st(name, shot=True):
            if shot: pg.screenshot(path=f'{OUT}/{lang}-{H}-{name}.png')
            res[name] = pg.evaluate(CHK)
        # calm, then searching from the round button
        res['starts-calm'] = [] if calm(pg) else ['not calm']
        st('calm')
        pg.click('#mclSb'); pg.wait_for_timeout(500); st('calm-focus')
        pg.keyboard.type('Kap'); pg.wait_for_timeout(700); st('calm-typed')
        pg.keyboard.press('Escape'); pg.evaluate("document.activeElement.blur()"); pg.wait_for_timeout(500); st('calm-after', False)
        SHOWN = "!!document.getElementById('mpaS').offsetParent"
        res['calm-esc'] = [] if not pg.evaluate(SHOWN) else ['Escape left the search box']   # Escape empties it: back to the round button
        pg.click('#mclSb'); pg.wait_for_timeout(400); pg.evaluate("history.back()"); pg.wait_for_timeout(700)   # Back puts the search box away again
        res['calm-back'] = [] if not pg.evaluate(SHOWN) and pg.evaluate("location.hash") == '#map' else ['Back left the search box']
        # revealed: a tap on empty map
        tap_empty(pg); res['reveals'] = [] if not calm(pg) else ['tap did not reveal']
        st('revealed')
        pg.click('#mpaQ'); pg.wait_for_timeout(500); st('focus')
        pg.keyboard.type('Kap'); pg.wait_for_timeout(700); st('typed')
        pg.keyboard.press('Escape'); pg.evaluate("document.activeElement.blur()"); pg.wait_for_timeout(500); st('after', False)
        # revealed + a card
        pid = pg.evaluate(PICK)
        for _ in range(5):   # zoomed far out: a tap on a bubble flies into it until a pin stands alone
            if pid: break
            pg.evaluate("(() => { const r = document.getElementById('hMap').getBoundingClientRect(), cx = r.left + r.width / 2, cy = r.top + r.height / 2; let best = null, bd = 1e9; document.querySelectorAll('#hMap .mpa-cl').forEach(m => { const b = m.getBoundingClientRect(), d = Math.hypot(b.left + b.width / 2 - cx, b.top + b.height / 2 - cy); if (d < bd) { bd = d; best = m; } }); if (best) best.click(); })()")
            pg.wait_for_timeout(900); pid = pg.evaluate(PICK)
            if os.environ.get('DEBUG'): print(pg.evaluate("[...document.querySelectorAll('#hMap .leaflet-marker-icon')].map(m=>{const b=m.getBoundingClientRect();return (m.dataset.id||'n'+m.dataset.n)+'@'+Math.round(b.left)+','+Math.round(b.top)}).join(' ')"))
        res['card-pin'] = [] if pid else ['no single pin to tap']
        if pid:
            pg.evaluate("id => document.querySelector('#hMap .mpa-pin[data-id=\"' + id + '\"]').click()", pid); pg.wait_for_timeout(900); st('card')
            res['card-open'] = [] if pg.evaluate("!!document.getElementById('mpaCard').offsetParent") else ['the card did not open']
            pg.evaluate("history.back()"); pg.wait_for_timeout(700)
        # Back: the tiles go away (one step)
        if not calm(pg): pg.evaluate("history.back()"); pg.wait_for_timeout(700)
        res['back-calm'] = [] if calm(pg) else ['Back did not return to calm']
        # a filter in calm: its pill stays
        tap_empty(pg); pg.click('#mpaChips [data-mf=broken]'); pg.wait_for_timeout(900)
        if not calm(pg): tap_empty(pg)
        st('calm-filter')
        res['pill-in-calm'] = [] if pg.evaluate("!!document.getElementById('mclPill').offsetParent") else ['no pill in calm']
        fails = {k: v for k, v in res.items() if v}
        bad += len(fails) + len(errs)
        print(lang, H, 'OK' if not fails else fails, errs, flush=True); ctx.close()
    b.close()
print('overlaps/problems:', bad)
