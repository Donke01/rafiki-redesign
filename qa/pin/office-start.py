# Office: tiles in four groups, Overview "Start here" checklist (ticks itself off, opens the right place, Hide) and "Needs you" list. Run with the site on BASE.
import os
SRC = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'team.py')
code = open(SRC).read().split('\nwith sync_playwright() as p:')[0]
code = code.replace("OUT = os.path.join(HERE, 'team'); os.makedirs(OUT, exist_ok=True)", "OUT = os.environ.get('OUT', os.path.join(HERE, 'office-start')); os.makedirs(OUT, exist_ok=True)")
code = code.replace("for f in os.listdir(OUT):\n    if f.endswith('.png'): os.remove(os.path.join(OUT, f))\n", "")
exec(compile(code, 'team-mocks', 'exec'))
from playwright.sync_api import sync_playwright
with sync_playwright() as p:
    b = p.chromium.launch()
    for W, H, tag, th, sw in [(390, 844, 'phone', '', 0), (360, 740, 'sw360', '', 1), (390, 844, 'dark', 'dark', 0), (1280, 900, 'desk', '', 0), (320, 640, 'p320', '', 0)]:
        ctx = b.new_context(viewport={'width': W, 'height': H}, device_scale_factor=1)
        pg, errs = login(ctx); theme(pg, th)
        if sw: pg.evaluate("localStorage.setItem('rfPinLang','sw')"); pg.reload(); pg.wait_for_timeout(1500)
        pg.goto(BASE + '/pin#office'); pg.wait_for_timeout(1800)
        pg.screenshot(path=OUT + '/%s-overview.png' % tag, full_page=True)
        if tag == 'phone':
            print('start rows', pg.eval_on_selector_all('[data-start]', 'e => e.map(x => x.dataset.start + (x.classList.contains("ok") ? ":ok" : ""))'))
            print('todo', pg.eval_on_selector_all('[data-todo]', 'e => e.map(x => x.dataset.todo + " | " + x.innerText.split("\\n")[0])'))
            pg.click('[data-start=approve]'); pg.wait_for_timeout(1800); print('after approve tap:', pg.evaluate('location.hash'), 'lim in view', pg.evaluate("(function(){var r=document.querySelector('#ofbLim').getBoundingClientRect();return r.top>0&&r.bottom<innerHeight})()"))
            pg.go_back(); pg.wait_for_timeout(1200); print('back ->', pg.evaluate('location.hash'), pg.evaluate('location.hash'))
            print('approve ok now', pg.locator('[data-start=approve].ok').count())
            pg.click('[data-todo=rr-unassigned]'); pg.wait_for_timeout(1500); print('todo urgent ->', pg.evaluate('location.hash'), pg.eval_on_selector_all('[data-rrf][aria-pressed=true], .rr-chip[aria-pressed=true]', 'e => e.map(x => x.innerText)'))
            pg.go_back(); pg.wait_for_timeout(1000)
            pg.click('[data-start=off]'); pg.wait_for_timeout(500); pg.reload(); pg.wait_for_timeout(1800); print('hidden after reload', pg.locator('#ocvStart').count() == 0)
        print(tag, 'errors', errs[:2])
