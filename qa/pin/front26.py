# Monthly report on the RAFIKI letterhead: Kenya team gets the Kitale office address, everyone else the US address.
# Saves screen shots and real PDFs (print media, Letter and A4) to qa/pin/letterhead/, then renders the PDFs to PNG.
import json, os, re, datetime, subprocess
from playwright.sync_api import sync_playwright
HERE = os.path.dirname(os.path.abspath(__file__)); BASE = os.environ.get('BASE', 'http://localhost:8800'); OUT = os.path.join(HERE, 'letterhead'); os.makedirs(OUT, exist_ok=True)
now = datetime.datetime.utcnow()
iso = lambda h: (now - datetime.timedelta(hours=h)).strftime('%Y-%m-%dT%H:%M:%SZ')
P = [{'id': '104', 'title': 'Kapsaret Borehole', 'country': 'Kenya', 'ck': 'ke', 'area': 'Eldoret', 'type': 'Borehole well', 'status': 'Completed', 'gps': [0.47, 35.25]},
     {'id': '105', 'title': 'Turbo Spring', 'country': 'Kenya', 'ck': 'ke', 'area': 'Turbo', 'type': 'Water collection', 'status': 'Completed', 'gps': [0.63, 35.05]},
     {'id': '201', 'title': 'Mwanza Primary Well', 'country': 'Tanzania', 'ck': 'tz', 'area': 'Mwanza', 'type': 'Borehole well', 'status': 'Completed', 'gps': [-2.5, 32.9]}]
ACT = [{'by': 'Bertin Mwangi', 'a': 'visit', 's': 'notworking', 'id': '104', 't': 'Kapsaret Borehole', 'at': iso(2)}, {'by': 'Grace Achieng', 'a': 'fixed', 'id': '105', 't': 'Turbo Spring', 'at': iso(5)},
       {'by': 'Meril Kiprop', 'a': 'pin', 'id': '104', 't': 'Kapsaret Borehole', 'at': iso(3)}]
# enough visits for a second page, so the footer can be checked on every page
ACT += [{'by': ['Meril Kiprop', 'Bertin Ngindu', 'Grace Achieng'][i % 3], 'a': 'visit', 's': ['working', 'repair', 'working'][i % 3], 'id': ['104', '105', '201'][i % 3], 't': ['Kapsaret Borehole', 'Turbo Spring', 'Mwanza Primary Well'][i % 3], 'at': iso(6 + i)} for i in range(40)]
problems = []
def check(c, w): print(('ok   ' if c else 'FAIL ') + w); (None if c else problems.append(w))
KE = ['Mega Centre, 3rd Floor, Makasembo Road', 'Kitale, Kenya', '+254 791 674 001', 'info@rafikioutreach.org']
US = ['1817 N. Beck Street', 'Salt Lake City, UT 84116, U.S.A.', 'Phone: +1 801 547 7847', 'Email: Info@rafikioutreach.org']
FOOT = '501(c)(3) Nonprofit Organization'

def run(br, home, who, tag):
    me = {'kind': 'app', 'nick': '', 'phone': '', 'home': home}
    def api(route, req):
        if req.method == 'GET':
            if 'activity=' in req.url: return route.fulfill(json={'ok': True, 'activity': ACT})
            if 'team=' in req.url: return route.fulfill(json={'ok': True, 'team': []})
            return route.fulfill(json={'ok': True, 'who': who, 'admin': False, 'projects': P, 'repairs': {'104': {'state': 'notworking', 'by': 'Bertin', 'at': iso(2), 't': 'Kapsaret Borehole', 'urgency': 'urgent', 'problems': ['Pump handle broken'], 'cost': 4500}}, 'visits': {}, 'jobs': [], 'assigned': [], 'confirmed': {}, 'me': me, 'home': home})
        route.fulfill(json={'ok': True})
    out = {}
    for vw, name in (({'width': 390, 'height': 844}, 'phone'), ({'width': 1200, 'height': 900}, 'desktop')):
        ctx = br.new_context(viewport=vw, is_mobile=name == 'phone', has_touch=name == 'phone', service_workers='block')
        pg = ctx.new_page(); errs = []; pg.on('pageerror', lambda e: errs.append(str(e)))
        pg.route('**/api/pin-auth/me', lambda r, q: r.fulfill(json={'available': False})); pg.route(re.compile(r'/api/pin(\?.*)?$'), api)
        pg.goto(BASE + '/pin'); pg.wait_for_timeout(800)
        if pg.locator('#iName').is_visible(): pg.fill('#iName', who)
        for d in '9999': pg.click('.pk[data-k="%s"]' % d)
        pg.wait_for_timeout(1200); pg.evaluate("localStorage.setItem('rfPinOnb','1')")
        pg.goto(BASE + '/pin#report'); pg.wait_for_timeout(1800)
        adr = pg.inner_text('.lh-adr') if pg.locator('.lh-adr').count() else ''
        out['adr'] = adr
        check(pg.evaluate("[...document.querySelectorAll('.lh-logo,.lh-flogo')].length === 2 && [...document.querySelectorAll('.lh-logo,.lh-flogo')].every(i => i.complete && i.naturalWidth > 0)"), '%s %s: letterhead logo and the small footer logo loaded' % (tag, name))
        check(pg.evaluate("!document.querySelector('.lh-seal,img[src*=\"seal\"]') && /rafiki-logo/.test(document.querySelector('.lh-foot img').src)"), '%s %s: footer uses the RAFIKI logo, no round seal' % (tag, name))
        pg.screenshot(path=os.path.join(OUT, '%s-screen-%s.png' % (tag, name)), full_page=True)
        pg.locator('.lh-top').screenshot(path=os.path.join(OUT, '%s-screen-%s-head.png' % (tag, name)))
        if name == 'desktop':
            pg.emulate_media(media='print')
            for fmt in ('Letter', 'A4'):
                f = os.path.join(OUT, '%s-report-%s.pdf' % (tag, fmt.lower()))
                pg.pdf(path=f, format=fmt, prefer_css_page_size=True)
                subprocess.run(['pdftoppm', '-r', '80', '-png', f, f[:-4]], check=True)
                txt = subprocess.run(['pdftotext', '-layout', f, '-'], capture_output=True, text=True).stdout
                pages = txt.count('\f') or 1
                out['pdf-' + fmt] = txt
                check(txt.count(FOOT) >= pages and pages >= 2, '%s %s PDF: %d pages, footer on each page (%d)' % (tag, fmt, pages, txt.count(FOOT)))
                check(txt.count('Mega Centre') + txt.count('1817 N. Beck') == 1, '%s %s PDF: address only once (page 1 header)' % (tag, fmt))
                check('Field report' in txt and 'Visits this month' in txt and 'Only visits, repairs and pins' in txt, '%s %s PDF: report contents intact' % (tag, fmt))
                imgs = [l.split() for l in subprocess.run(['pdfimages', '-list', f], capture_output=True, text=True).stdout.splitlines()[2:]]
                per = {}
                for l in imgs: per[l[0]] = per.get(l[0], 0) + 1
                check(len(per) == pages and per.get('1', 0) >= 2 and all(v >= 1 for v in per.values()) and all(int(l[3]) == 703 for l in imgs), '%s %s PDF: RAFIKI logo in the header and on every page foot, nothing else (%s)' % (tag, fmt, per))
        check(not errs, '%s %s: no page errors %s' % (tag, name, errs[:2]))
        ctx.close()
    return out

with sync_playwright() as p:
    br = p.chromium.launch()
    ke = run(br, 'ke', 'Meril Kiprop', 'kenya')
    check(all(x in ke['adr'] for x in KE) and 'Beck' not in ke['adr'], 'Kenya user: Kitale office address + info@ e-mail on screen: ' + ke['adr'].replace('\n', ' / '))
    check(all(x in ke['pdf-Letter'] for x in ['Mega Centre, 3rd Floor, Makasembo Road', 'Kitale, Kenya', 'Email: info@rafikioutreach.org']), 'Kenya user: Kenya address in the PDF')
    tz = run(br, 'tz', 'Bertin Ngindu', 'tanzania')
    check(all(x in tz['adr'] for x in US) and 'Kitale' not in tz['adr'], 'Tanzania user: US address exactly as on the letterhead: ' + tz['adr'].replace('\n', ' / '))
    check(all(x in tz['pdf-A4'] for x in US), 'Tanzania user: US address in the PDF')
    br.close()
print('PROBLEMS', problems)
