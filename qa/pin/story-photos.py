# Story photos in the app (Oct 2026): the four-step status, "Still needed" cards, the Photos card (rows with a count,
# a month, a grid with "In story" badges and an "Add photos" tile), the Add photos sheet (gallery several at once,
# or a few camera shots then add), the review sheet (take out, which show children), sending one by one with a ring
# and a banner (slowed), no signal (waits on the phone, sends on its own; the Stories tab says so), the app closed
# while sending (sends on the next start), failures (too large: retry; not a photo: take out), a photo's details
# (caption, move, children, delete), the month of each column, the "new photos since the draft" nudge.
# Phone 320 / 390, EN / SW, light / dark / sun, and 1280: no word split, nothing cut or overlapping. Mocked APIs.
#   python3 S/srvpin.py <checkout> 8895 & BASE=http://localhost:8895 python3 qa/pin/story-photos.py [look|flow|all]
import os, re, sys, json, datetime
HERE = os.path.dirname(os.path.abspath(__file__)); sys.path.insert(0, HERE)
WHAT = sys.argv[1] if len(sys.argv) > 1 else 'all'
sys.argv = sys.argv[:1] + ['none']
os.environ.setdefault('OUT', os.path.join(HERE, 'story-photos'))
import me as M
from playwright.sync_api import sync_playwright
OUT = M.OUT
PID = '102'

# ---- photo files: three JPEGs (one with the day it was taken in its EXIF), and a text file pretending to be a photo
def jpeg(fn, rgb, taken=None, w=2400, h=1800):
    from PIL import Image, ImageDraw
    im = Image.new('RGB', (w, h), rgb); d = ImageDraw.Draw(im); d.ellipse([w * .3, h * .2, w * .7, h * .8], fill=(250, 230, 200))
    kw = {}
    if taken:
        ex = Image.Exif(); ex[0x0132] = taken; ex.get_ifd(0x8769)[0x9003] = taken; kw['exif'] = ex.tobytes()
    im.save(fn, 'JPEG', quality=90, **kw)
FILES = [os.path.join(OUT, '_p%d.jpg' % i) for i in range(4)]
for i, (c, t) in enumerate([((30, 120, 190), '2024:11:03 09:12:00'), ((40, 150, 90), None), ((190, 90, 40), None), ((120, 80, 200), None)]): jpeg(FILES[i], c, t)
NOTPIC = os.path.join(OUT, '_not.jpg'); open(NOTPIC, 'w').write('not really a photo')

# ---- the story server, with a switch to hold uploads (to see them on their way) and to fail the next ones
ST = {}; SENT = []; HOLD = []; CFG = {'hold': False, 'fail': [], 'order': False}
def iso(d=0): return (datetime.datetime.now(datetime.timezone.utc) - datetime.timedelta(days=d)).strftime('%Y-%m-%dT%H:%M:%SZ')
def reset(status='draft', pub=None, new=0, before_date=True, after=0, consent='not-yet', answers=4):
    ST.clear(); CFG.update({'hold': False, 'fail': [], 'order': False}); del HOLD[:]; del SENT[:]
    ph = lambda i, ch=False, cap='': dict({'id': str(9400 + i), 'name': 'p.jpg', 'children': ch, 'by': 'Meril Kiprop', 'at': iso(20 - i)}, **({'cap': cap} if cap else {}))
    ST.update({'id': PID, 'status': status, 'consent': consent, 'answers': {q: ('An answer.' if i < answers else '') for i, q in enumerate(['q1', 'q2', 'q3', 'q4', 'q5', 'q6'])}, 'quote': '', 'quoteBy': '', 'credit': 'Meril Kiprop',
        'photos': {'before': [ph(0, True), dict(ph(1), taken='2024-11-03')], 'progress': [ph(2, cap='Drilling reached water at the dispensary'), ph(3), ph(4)], 'after': [ph(5 + i) for i in range(after)]},
        'dates': {'before': '2024-11'} if before_date else {}, 'newPhotos': new, 'pub': pub, 'pool': []})
    secs = [{'h': 'Before', 'body': 'Patients waited outside.', 'photo': {'id': '9400', 'row': 'before', 'cap': ''}}, {'h': 'The work', 'body': 'Drilling began.', 'photo': {'id': '9402', 'row': 'progress', 'cap': ''}},
            {'h': 'Raising the tank', 'body': 'The tank went up.', 'photo': {'id': '9404', 'row': 'progress', 'cap': ''}}, {'h': 'Now', 'body': 'Clean water.', 'photo': None}]
    ST['doc'] = {'v': 1, 'title': 'Clean water at Chepkongi', 'dek': 'A dispensary with water.', 'sections': secs, 'pull': None, 'facts': {'stages': []}, 'at': iso(10), 'by': 'Meril Kiprop', 'model': 'text'}
    ST['text'] = 'Clean water at Chepkongi'
NID = [9500]
def missing():
    m = []; n = sum(1 for v in ST['answers'].values() if v)
    if n < 6: m.append('answers:%d/6' % n)
    if not ST['photos']['after']: m.append('after-photos')
    if any(ST['photos'][r] and not ST['dates'].get(r) for r in ('before', 'progress', 'after')): m.append('dates')
    if ST['consent'] != 'received': m.append('consent')
    return m
def view():
    y = json.loads(json.dumps(ST)); y['prompts'] = M.PROMPTS; y['missing'] = missing(); y['canApprove'] = True; return y
def field(raw, k):
    m = re.search(rb'name="' + k.encode() + rb'"\r\n\r\n([^\r]*)', raw); return m.group(1).decode() if m else None
def answer(route, req):
    raw = req.post_data_buffer or b''
    if CFG['fail']:
        code = CFG['fail'].pop(0); SENT.append({'photo': True, 'code': code}); return route.fulfill(status=code, json={'ok': False, 'reason': {413: 'too-large', 415: 'type', 500: 'down'}.get(code, 'x')})
    fsize = len(raw); row = field(raw, 'row'); NID[0] += 1
    p = {'id': str(NID[0]), 'name': 'n.jpg', 'children': field(raw, 'children') == 'true', 'by': 'Reid Anderson', 'at': iso(0)}
    if field(raw, 'taken'): p['taken'] = field(raw, 'taken')
    if field(raw, 'cap'): p['cap'] = field(raw, 'cap')
    ST['photos'][row].append(p); ST['newPhotos'] = ST.get('newPhotos', 0) + 1 if ST.get('doc') else 0
    SENT.append({'photo': True, 'row': row, 'children': field(raw, 'children'), 'taken': field(raw, 'taken'), 'size': fsize, 'jpeg': b'\xff\xd8\xff' in raw})
    route.fulfill(json={'ok': True, 'photo': p, 'story': view()})
def install(pg, admin=True):
    def story_api(route, req):
        from urllib.parse import urlparse, parse_qs
        q = parse_qs(urlparse(req.url).query)
        if req.method == 'GET':
            if 'asset' in q: n = int(q['asset'][0]) % 7; return route.fulfill(body=M.face([(10, 120, 194), (28, 156, 90), (194, 84, 40), (122, 79, 208), (200, 150, 20), (30, 160, 160), (90, 90, 90)][n], 320), content_type='image/png')
            return route.fulfill(json={'ok': True, 'story': view()})
        if (req.headers.get('content-type') or '').startswith('multipart'):
            if CFG['hold']: HOLD.append((route, req)); return
            return answer(route, req)
        b = json.loads(req.post_data or '{}'); SENT.append(b); a = b.get('action')
        if a == 'photo-edit':
            fr, to = b['row'], b.get('to') or b['row']; cur = [p for p in ST['photos'][fr] if p['id'] == b['asset']]
            if not cur: return route.fulfill(status=404, json={'ok': False, 'reason': 'photo'})
            p = dict(cur[0])
            if 'cap' in b: p['cap'] = b['cap']
            if 'children' in b: p['children'] = bool(b['children'])
            ST['photos'][fr] = [x for x in ST['photos'][fr] if x['id'] != b['asset']]; ST['photos'][to].append(p)
            return route.fulfill(json={'ok': True, 'photo': p, 'story': view()})
        if a == 'photo-remove': ST['photos'][b['row']] = [x for x in ST['photos'][b['row']] if x['id'] != b['asset']]
        if a == 'dates':
            if CFG['order']: return route.fulfill(status=400, json={'ok': False, 'reason': 'order'})
            for k in ('before', 'progress', 'progressEnd', 'after'):
                if k in b:
                    if b[k]: ST['dates'][k] = b[k]
                    else: ST['dates'].pop(k, None)
        if a == 'consent': ST['consent'] = b['value']
        if a == 'draft': ST['newPhotos'] = 0; ST['doc']['at'] = iso(0)
        if a == 'answers':
            for k in ['q1', 'q2', 'q3', 'q4', 'q5', 'q6']:
                if k in b: ST['answers'][k] = b[k]
        return route.fulfill(json={'ok': True, 'story': view()})
    pg.route(re.compile(r'/api/story(\?.*)?$'), story_api)
def release(pg, n=99):
    k = 0
    while HOLD and k < n: r, q = HOLD.pop(0); answer(r, q); k += 1
    pg.wait_for_timeout(300)

def open_story(br, lang='en', w=390, th='', admin=True, reduce=False, **kw):
    reset(**kw)
    ctx, pg = M.context(br, lang, w, th, admin=admin, reduce=reduce, h=900 if w > 1000 else None); install(pg, admin)
    M.go(pg, '#p/%s/story' % PID, 2200)
    return ctx, pg
def photos_card(pg): pg.locator('#styPh').scroll_into_view_if_needed(); pg.wait_for_timeout(300)
def ups(): return [x for x in SENT if x.get('photo')]
def acts(a): return [x for x in SENT if x.get('action') == a]

OVERLAP = """sels => { const bad = []; const q = s => [...document.querySelectorAll(s)].filter(e => e.offsetParent && getComputedStyle(e).visibility !== 'hidden');
  const hit = (a, b) => { const r = a.getBoundingClientRect(), t = b.getBoundingClientRect(); return r.width && t.width && r.left < t.right - 1 && t.left < r.right - 1 && r.top < t.bottom - 1 && t.top < r.bottom - 1; };
  for (const sel of sels) { const els = q(sel); for (let i = 0; i < els.length; i++) for (let j = i + 1; j < els.length; j++) if (els[i].parentNode === els[j].parentNode && hit(els[i], els[j])) bad.push(sel + ':' + (els[i].innerText || els[i].tagName).slice(0, 18) + '/' + (els[j].innerText || els[j].tagName).slice(0, 18)); }
  const W = document.documentElement.clientWidth; q('#paneStory *, .bsh-p *, #sqb *').forEach(e => { const r = e.getBoundingClientRect(); if (r.width && (r.right > W + 1 || r.left < -1) && !e.closest('.vh')) bad.push('off-screen:' + e.tagName + '.' + String(e.className).slice(0, 30)); });
  if (document.documentElement.scrollWidth > W + 1) bad.push('sideways scroll'); return bad.slice(0, 6); }"""
SELS = ['.sty-st > li', '.sty-st li > *', '.sty-hh > *', '.sty-ni > *', '.sty-ni > span > *', '.sty-rh > *', '.sty-g > *', '.sty-phh > *', '.sty-t > :not(img)', '.sty-nudge > *', '.sqb-r > *', '.sqb-t > *',
        '.sty-seg > *', '.sty-opt > *', '.sty-opt > span > *', '.sty-rv > *', '.sty-sw > *', '.sty-ym > *', '.sty-tray .two > *', '.bsh-h > *', '.sty-acts > *']
def look(pg, check, t, scope='#paneStory .card, .bsh-p, #sqb'):
    bad = M.wrapcheck(pg, scope); check(not bad, '[%s] no word split or cut (%s)' % (t, bad))
    ov = pg.evaluate(OVERLAP, SELS); check(not ov, '[%s] no overlaps, nothing off-screen (%s)' % (t, ov))
def shot(pg, name): pg.wait_for_timeout(350); pg.screenshot(path=os.path.join(OUT, name + '.png'))

# ---- the look at every width, language and theme ---------------------------------------------------------------
def s_look(check):
    with sync_playwright() as p:
        br = p.chromium.launch()
        combos = [(w, l, th) for w in (320, 390) for l in ('en', 'sw') for th in ('', 'dark', 'sun')] + [(1280, 'en', ''), (1280, 'sw', 'dark')]
        for w, lang, th in combos:
            t = '%d-%s%s' % (w, lang, '-' + th if th else '')
            ctx, pg = open_story(br, lang, w, th, new=2)
            st = pg.evaluate("[...document.querySelectorAll('.sty-st li')].map(l => l.className)")
            check(st == ['done', 'now', 'todo', 'todo'], '[%s] four steps: Collecting ticked, Draft ready ringed (%s)' % (t, st))
            check(pg.locator('.sty-st li.done i svg').count() == 1, '[%s] a tick on the past step' % t)
            needs = pg.evaluate("[...document.querySelectorAll('#styNeed .sty-ni')].map(n => n.dataset.need)")
            check(needs == ['after', 'dates', 'answers', 'consent'], '[%s] still needed: after photos, dates, answers, consent (%s)' % (t, needs))
            dn = pg.inner_text('[data-testid=sty_need_dates] b')
            check(('In progress' if lang == 'en' else 'Wakati wa kazi') in dn and ('Before' not in dn and 'Kabla' not in dn), '[%s] the dates card names only In progress (%s)' % (t, dn))
            rows = pg.evaluate("[...document.querySelectorAll('#styPh .sty-row')].map(r => [r.dataset.row, r.querySelector('.n').textContent, r.querySelectorAll('.sty-t').length, r.querySelectorAll('.sty-addt').length])")
            check(rows == [['before', '2', 2, 1], ['progress', '3', 3, 1], ['after', '0', 0, 1]], '[%s] rows with counts, thumbnails and an Add tile (%s)' % (t, rows))
            check(pg.locator('#styPh .sty-ins').count() == 3 and pg.inner_text('[data-testid=sty_in_n]').strip().startswith('3'), '[%s] 3 "In story" badges and "3 in the story"' % t)
            check(pg.inner_text('[data-testid=sty_date_before]').strip() == ('Nov 2024'), '[%s] Before · Nov 2024' % t)
            check(pg.locator('[data-testid=sty_date_after].sty-dchip').count() == 1, '[%s] "Add date" chip where there is no month' % t)
            check(M.vis(pg, '[data-testid=sty_nudge]') and M.vis(pg, '[data-testid=sty_nudge_draft]'), '[%s] the new-photos nudge with Draft again' % t)
            cols = pg.evaluate("getComputedStyle(document.querySelector('.sty-g')).gridTemplateColumns.split(' ').length")
            check(cols == (4 if w >= 700 else 3), '[%s] %d columns of thumbnails (%s)' % (t, 4 if w >= 700 else 3, cols))
            pg.wait_for_timeout(500); look(pg, check, t)
            pg.evaluate("window.scrollTo(0, document.getElementById('styHead').getBoundingClientRect().top + scrollY - 70)"); shot(pg, 'page-%s' % t)
            pg.locator('#styPh').scroll_into_view_if_needed(); shot(pg, 'photos-%s' % t)
            if w == 390 or (w == 320 and th == '') or w == 1280:
                # the sheets
                pg.click('[data-testid=sty_need_after] .btn'); pg.wait_for_timeout(500)
                check(pg.get_attribute('.sty-addsh .sty-seg [data-row=after]', 'aria-pressed') == 'true', '[%s] Add after photos: the sheet opens on After' % t)
                look(pg, check, t + ' add sheet', '.bsh-p'); shot(pg, 'sheet-add-%s' % t)
                pg.set_input_files('[data-testid=sty_fpick]', FILES[:3]); pg.wait_for_timeout(700)
                pg.click('[data-testid=sty_kyes]'); pg.locator('.sty-rvt .k').first.click(); pg.wait_for_timeout(200)
                look(pg, check, t + ' review', '.bsh-p'); shot(pg, 'sheet-review-%s' % t)
                M.back(pg, 600); check(pg.locator('.sty-rvsh').count() == 0 and M.h(pg) == '#p/%s/story' % PID, '[%s] Back closes the review sheet' % t)
                pg.locator('#styPh [data-sty-ph=progress]').first.click(); pg.wait_for_timeout(700)
                look(pg, check, t + ' details', '.bsh-p'); shot(pg, 'sheet-details-%s' % t)
                M.back(pg, 600)
                pg.click('[data-testid=sty_date_progress]'); pg.wait_for_timeout(500)
                look(pg, check, t + ' dates', '.bsh-p'); shot(pg, 'sheet-dates-%s' % t)
                M.back(pg, 600)
                # the banner while sending
                CFG['hold'] = True; pg.click('[data-testid=sty_add_after]'); pg.wait_for_timeout(400)
                pg.set_input_files('[data-testid=sty_fpick]', FILES[:3]); pg.wait_for_timeout(500); pg.click('[data-testid=sty_kno]'); pg.click('[data-testid=sty_rv_go]'); pg.wait_for_timeout(1500)
                photos_card(pg); pg.locator('.sty-row[data-row=after]').scroll_into_view_if_needed(); look(pg, check, t + ' sending'); shot(pg, 'sending-%s' % t)
                release(pg, 2); CFG['fail'] = [500]; release(pg); pg.wait_for_timeout(700)
                look(pg, check, t + ' done/failed'); shot(pg, 'done-failed-%s' % t)
            ctx.close()
        br.close()

# ---- the flows ---------------------------------------------------------------------------------------------
def s_add(check):
    with sync_playwright() as p:
        br = p.chromium.launch()
        for lang in ('en', 'sw'):
            ctx, pg = open_story(br, lang, 390, '')
            photos_card(pg); pg.click('[data-testid=sty_add_after]'); pg.wait_for_timeout(500)
            check(M.vis(pg, '[data-testid=sty_addsheet]') and pg.get_attribute('.sty-addsh [data-row=after]', 'aria-pressed') == 'true', '[%s] the Add tile opens the sheet on After' % lang)
            check(pg.get_attribute('[data-testid=sty_fpick]', 'multiple') is not None and pg.get_attribute('[data-testid=sty_fcam]', 'capture') == 'environment', '[%s] gallery picks several; camera opens the back camera' % lang)
            pg.set_input_files('[data-testid=sty_fpick]', FILES[:3]); pg.wait_for_timeout(700)
            check(M.vis(pg, '[data-testid=sty_review]') and pg.locator('.sty-rvt').count() == 3, '[%s] the review sheet shows the 3 chosen' % lang)
            pg.locator('.sty-rvt .x').nth(2).click(); pg.wait_for_timeout(200)
            check(pg.locator('.sty-rvt').count() == 2, '[%s] one taken out: 2 left' % lang)
            go = pg.inner_text('[data-testid=sty_rv_go]'); check(go == ('Add 2 photos to After' if lang == 'en' else 'Ongeza picha 2 kwenye Baada'), '[%s] the button: %s' % (lang, go))
            pg.click('[data-testid=sty_rv_go]'); pg.wait_for_timeout(300)
            check('children' in pg.inner_text('.sty-rvsh .msg') or 'watoto' in pg.inner_text('.sty-rvsh .msg'), '[%s] it asks about children first' % lang)
            pg.click('[data-testid=sty_kyes]'); pg.click('[data-testid=sty_rv_go]'); pg.wait_for_timeout(300)
            check(pg.inner_text('.sty-rvsh .msg') != '', '[%s] Yes: tap the ones that do' % lang)
            pg.locator('.sty-rvt .k').first.click(); pg.wait_for_timeout(200)
            check(pg.locator('.sty-rvt .sty-kid').count() == 1, '[%s] the tapped one has the Children badge' % lang)
            CFG['hold'] = True; pg.click('[data-testid=sty_rv_go]'); pg.wait_for_timeout(1500)
            check(pg.locator('.sty-rvsh').count() == 0, '[%s] the sheet closes' % lang)
            check(pg.locator('.sty-row[data-row=after] .sty-t.up').count() == 2 and pg.inner_text('.sty-row[data-row=after] .n') == '2', '[%s] 2 tiles in After at once' % lang)
            check(pg.locator('.sty-row[data-row=after] [data-st=send] .sty-ring b').count() == 1, '[%s] the first one has its ring with a %%' % lang)
            check(M.vis(pg, '#sqb') and ('Adding 2 photos to After' if lang == 'en' else 'Inaongeza picha 2 kwenye Baada') in pg.inner_text('#sqb'), '[%s] the banner: %s' % (lang, pg.inner_text('#sqb') if M.vis(pg, '#sqb') else '-'))
            check(pg.locator('#styNeed .sty-ni[data-need=after]').count() == 1, '[%s] the after card stays until a photo is in' % lang)
            shot(pg, 'flow-sending-%s' % lang)
            release(pg, 1); pg.wait_for_timeout(900)
            u = ups(); check(len(u) == 1 and u[0]['row'] == 'after' and u[0]['children'] == 'true' and u[0]['taken'] == '2024-11-03' and u[0]['jpeg'], '[%s] the first: After, children, taken 2024-11-03 from the photo (%s)' % (lang, u[:1]))
            check(u and u[0]['size'] < 900000, '[%s] made smaller before sending (%s bytes)' % (lang, u and u[0]['size']))
            check(pg.locator('.sty-row[data-row=after] .sty-tk').count() == 1, '[%s] a green tick on the one that is in' % lang)
            check(pg.locator('#styNeed .sty-ni[data-need=after]').count() == 0, '[%s] … and "Add after photos" is gone' % lang)
            check(('1 of 2 sent' if lang == 'en' else '1 kati ya 2') in pg.inner_text('#sqb'), '[%s] the banner: 1 of 2 sent' % lang)
            release(pg); pg.wait_for_timeout(900)
            u = ups(); check(len(u) == 2 and u[1]['children'] == 'false' and not u[1]['taken'], '[%s] the second: no children, no date (none in the photo)' % lang)
            check(('2 photos added' if lang == 'en' else 'Picha 2 zimeongezwa') in pg.inner_text('#sqb'), '[%s] the banner: 2 photos added ✓' % lang)
            shot(pg, 'flow-added-%s' % lang)
            pg.wait_for_timeout(4200)
            check(not M.vis(pg, '#sqb'), '[%s] the banner goes by itself' % lang)
            check(pg.locator('.sty-row[data-row=after] .sty-tk').count() == 0 and pg.locator('.sty-row[data-row=after] .sty-t').count() == 2, '[%s] the ticks fade; 2 photos in After' % lang)
            # the camera: two shots, then add them together (taken today)
            pg.click('[data-testid=sty_add_progress]'); pg.wait_for_timeout(400)
            pg.set_input_files('[data-testid=sty_fcam]', FILES[1]); pg.wait_for_timeout(300); pg.set_input_files('[data-testid=sty_fcam]', FILES[2]); pg.wait_for_timeout(300)
            check(('2 taken' if lang == 'en' else 'Picha 2 zimepigwa') in pg.inner_text('[data-testid=sty_tray]'), '[%s] the camera tray: 2 taken' % lang)
            look(pg, check, lang + ' tray', '.bsh-p'); shot(pg, 'camera-tray-%s' % lang)
            pg.click('[data-testid=sty_camgo]'); pg.wait_for_timeout(500); pg.click('[data-testid=sty_kno]'); CFG['hold'] = False; pg.click('[data-testid=sty_rv_go]'); pg.wait_for_timeout(2000)
            u = ups(); today = datetime.date.today().isoformat()
            check(len(u) == 4 and u[2]['row'] == 'progress' and u[2]['taken'] == today and u[3]['taken'] == today, '[%s] camera shots: In progress, taken today (%s)' % (lang, [x.get('taken') for x in u]))
            ctx.close()
        br.close()

def s_offline(check):
    with sync_playwright() as p:
        br = p.chromium.launch()
        ctx, pg = open_story(br, 'en', 390, '')
        photos_card(pg); ctx.set_offline(True); pg.wait_for_timeout(300)
        pg.click('[data-testid=sty_add_after]'); pg.wait_for_timeout(400); pg.set_input_files('[data-testid=sty_fpick]', FILES[1:3]); pg.wait_for_timeout(500)
        pg.click('[data-testid=sty_kno]'); pg.click('[data-testid=sty_rv_go]'); pg.wait_for_timeout(1500)
        check(pg.locator('.sty-row[data-row=after] [data-st=off]').count() == 2, 'no signal: both tiles say "Waiting for signal"')
        check('waiting for signal' in pg.inner_text('#sqb'), 'the banner says they wait for signal (%s)' % (pg.inner_text('#sqb') if M.vis(pg, '#sqb') else '-'))
        n = pg.evaluate("new Promise(ok => { const r = indexedDB.open('rafiki-story', 1); r.onsuccess = () => { const q = r.result.transaction('q').objectStore('q').getAll(); q.onsuccess = () => ok(q.result.length); }; })")
        check(n == 2, 'both are kept on the phone (%s)' % n)
        look(pg, check, 'offline'); shot(pg, 'offline-waiting')
        # the bottom bar still works; the Stories tab says what is waiting
        M.go(pg, '#stories', 1200)
        check(M.vis(pg, '[data-testid=sh_sending]') and '2 photos waiting for signal' in pg.inner_text('[data-testid=sh_sending]'), 'the Stories tab: "2 photos waiting for signal"')
        shot(pg, 'stories-pill-offline')
        ctx.set_offline(False); pg.wait_for_timeout(2500)
        check(len(ups()) == 2, 'signal back: both sent by themselves (%d)' % len(ups()))
        check(not M.vis(pg, '[data-testid=sh_sending]'), 'the pill is gone')
        n = pg.evaluate("new Promise(ok => { const r = indexedDB.open('rafiki-story', 1); r.onsuccess = () => { const q = r.result.transaction('q').objectStore('q').getAll(); q.onsuccess = () => ok(q.result.length); }; })")
        check(n == 0, 'nothing left on the phone (%s)' % n)
        # sending, then the app is closed: on the next start they go
        M.go(pg, '#p/%s/story' % PID, 1500); photos_card(pg); CFG['hold'] = True
        pg.click('[data-testid=sty_add_before]'); pg.wait_for_timeout(400); pg.set_input_files('[data-testid=sty_fpick]', FILES[0:2]); pg.wait_for_timeout(500)
        pg.click('[data-testid=sty_kno]'); pg.click('[data-testid=sty_rv_go]'); pg.wait_for_timeout(1500)
        M.go(pg, '#stories', 1000)
        check(M.vis(pg, '[data-testid=sh_sending]') and 'Sending 2 photos' in pg.inner_text('[data-testid=sh_sending]'), 'left the story: the Stories tab says "Sending 2 photos…"'); shot(pg, 'stories-pill-sending')
        pg.click('[data-testid=sh_sending]'); pg.wait_for_timeout(1500)
        check(M.h(pg) == '#p/%s/story' % PID, 'the pill opens that story (%s)' % M.h(pg))
        k = len(ups()); held = len(HOLD); del HOLD[:]; CFG['hold'] = False
        pg.reload(); pg.wait_for_timeout(4000)
        check(held >= 1 and len(ups()) - k == 2, 'reopened: the 2 photos send on start (%d held, %d sent)' % (held, len(ups()) - k))
        check(len(ST['photos']['before']) == 4, 'Before has 4 photos, none lost, none twice (%d)' % len(ST['photos']['before']))
        ctx.close(); br.close()

def s_fail(check):
    with sync_playwright() as p:
        br = p.chromium.launch()
        ctx, pg = open_story(br, 'en', 390, '')
        photos_card(pg); CFG['fail'] = [413]
        pg.click('[data-testid=sty_add_after]'); pg.wait_for_timeout(400); pg.set_input_files('[data-testid=sty_fpick]', FILES[1:3]); pg.wait_for_timeout(500)
        pg.click('[data-testid=sty_kno]'); pg.click('[data-testid=sty_rv_go]'); pg.wait_for_timeout(2500)
        t = pg.locator('.sty-row[data-row=after] [data-st=fail]')
        check(t.count() == 1 and 'Tap to retry' in t.inner_text() and 'Too large' in t.inner_text(), 'too large: "Tap to retry", with the reason')
        b = pg.inner_text('#sqb') if M.vis(pg, '#sqb') else ''
        check('1 photo added' in b and '1 couldn’t send' in b and M.vis(pg, '[data-testid=sqb_retry]'), 'the banner: 1 added ✓, 1 couldn’t send, Retry (%s)' % b)
        look(pg, check, 'failed'); shot(pg, 'failed')
        pg.click('[data-testid=sqb_retry]'); pg.wait_for_timeout(2000)
        check(len(ups()) == 3 and ups()[-1].get('row') == 'after' and pg.locator('.sty-row[data-row=after] .sty-t').count() == 2, 'Retry: sent (smaller), 2 in After')
        # not a photo: taken out
        CFG['fail'] = [415]
        pg.click('[data-testid=sty_add_after]'); pg.wait_for_timeout(400); pg.set_input_files('[data-testid=sty_fpick]', NOTPIC); pg.wait_for_timeout(500)
        pg.click('[data-testid=sty_kno]'); pg.click('[data-testid=sty_rv_go]'); pg.wait_for_timeout(2000)
        t = pg.locator('.sty-row[data-row=after] [data-st=fail]')
        check(t.count() == 1 and 'Not a photo' in t.inner_text(), 'not a photo: said on the tile')
        t.click(); pg.wait_for_timeout(500); check(M.vis(pg, '.po-ask .po-red'), 'tapping it asks to take it out'); pg.click('.po-ask [data-k=yes]'); pg.wait_for_timeout(600)
        check(pg.locator('.sty-row[data-row=after] .sty-t.up').count() == 0, 'taken out')
        # a server problem, then the tile retries
        CFG['fail'] = [500]
        pg.click('[data-testid=sty_add_after]'); pg.wait_for_timeout(400); pg.set_input_files('[data-testid=sty_fpick]', FILES[3]); pg.wait_for_timeout(500)
        pg.click('[data-testid=sty_kno]'); pg.click('[data-testid=sty_rv_go]'); pg.wait_for_timeout(2000)
        pg.locator('.sty-row[data-row=after] [data-st=fail]').click(); pg.wait_for_timeout(1500)
        check(pg.locator('.sty-row[data-row=after] .sty-t.up[data-st]').count() == 0 and pg.locator('.sty-row[data-row=after] .sty-t').count() == 3, 'tap to retry: in')
        ctx.close()
        # reduced motion: no spinning, just the % and the bar
        ctx, pg = open_story(br, 'en', 390, '', reduce=True); photos_card(pg); CFG['hold'] = True
        pg.click('[data-testid=sty_add_after]'); pg.wait_for_timeout(400); pg.set_input_files('[data-testid=sty_fpick]', FILES[1]); pg.wait_for_timeout(500)
        pg.click('[data-testid=sty_kno]'); pg.click('[data-testid=sty_rv_go]'); pg.wait_for_timeout(1500)
        sp = pg.evaluate("(() => { const s = document.querySelector('.sty-ring .sp'); return s ? getComputedStyle(s).display : 'none'; })()")
        check(sp == 'none' and pg.locator('.sty-ring b').count() == 1, 'reduced motion: no spinner, the % stays (%s)' % sp)
        release(pg); ctx.close(); br.close()

def s_details(check):
    with sync_playwright() as p:
        br = p.chromium.launch()
        for lang in ('en', 'sw'):
            ctx, pg = open_story(br, lang, 390, '')
            photos_card(pg); pg.locator('#styPh [data-sty-ph=progress]').first.click(); pg.wait_for_timeout(700)
            check(M.vis(pg, '[data-testid=sty_details]') and pg.locator('.sty-dsh .sty-pv img.in').count() == 1, '[%s] the details sheet with the big photo' % lang)
            check(pg.input_value('[data-testid=sty_cap]') == 'Drilling reached water at the dispensary', '[%s] its caption' % lang)
            check(('Added' if lang == 'en' else 'Imeongezwa') in pg.inner_text('.sty-dsh .sty-sub') and 'Meril' in pg.inner_text('.sty-dsh .sty-sub'), '[%s] "Added … by Meril"' % lang)
            check(M.vis(pg, '.sty-dsh .sty-ins'), '[%s] "In story" on the preview' % lang)
            pg.fill('[data-testid=sty_cap]', 'The drill at 60 metres'); pg.keyboard.press('Enter'); pg.wait_for_timeout(700)
            e = acts('photo-edit'); check(e and e[-1].get('cap') == 'The drill at 60 metres' and e[-1]['row'] == 'progress' and e[-1]['asset'] == '9402', '[%s] caption saved (%s)' % (lang, e[-1:] if e else e))
            pg.click('[data-testid=sty_kids]'); pg.wait_for_timeout(700)
            e = acts('photo-edit'); check(e and e[-1].get('children') is True and pg.get_attribute('[data-testid=sty_kids]', 'aria-checked') == 'true', '[%s] shows children: on' % lang)
            check(pg.locator('#styPh .sty-row[data-row=progress] .sty-t').first.locator('.sty-kid').count() == 1, '[%s] … the thumbnail says Children' % lang)
            pg.click('[data-testid=sty_mv_after]'); pg.wait_for_timeout(800)
            e = acts('photo-edit'); check(e and e[-1].get('to') == 'after', '[%s] moved to After' % lang)
            check(pg.locator('.sty-row[data-row=after] .sty-t').count() == 1 and pg.locator('.sty-row[data-row=progress] .sty-t').count() == 2, '[%s] the rows show it' % lang)
            shot(pg, 'details-edited-%s' % lang)
            pg.click('[data-testid=sty_del]'); pg.wait_for_timeout(500); check(M.vis(pg, '.po-ask .po-red'), '[%s] Delete asks first' % lang)
            pg.click('.po-ask [data-k=yes]'); pg.wait_for_timeout(900)
            r = acts('photo-remove'); check(r and r[-1]['row'] == 'after' and r[-1]['asset'] == '9402', '[%s] deleted from After (%s)' % (lang, r[-1:] if r else r))
            check(pg.locator('.sty-dsh').count() == 0 and pg.locator('.sty-row[data-row=after] .sty-t').count() == 0, '[%s] the sheet closes, the photo is gone' % lang)
            # caption saved on close (Back)
            pg.locator('#styPh [data-sty-ph=before]').first.click(); pg.wait_for_timeout(600)
            pg.fill('[data-testid=sty_cap]', 'Patients waiting outside'); M.back(pg, 900)
            e = acts('photo-edit'); check(pg.locator('.sty-dsh').count() == 0 and e and e[-1].get('cap') == 'Patients waiting outside', '[%s] Back closes it and keeps the caption' % lang)
            check(M.h(pg) == '#p/%s/story' % PID, '[%s] still on the story' % lang)
            ctx.close()
        br.close()

def s_dates(check):
    with sync_playwright() as p:
        br = p.chromium.launch()
        ctx, pg = open_story(br, 'en', 390, '', before_date=False)
        dn = pg.inner_text('[data-testid=sty_need_dates] b'); check(dn == 'Add the dates for Before and In progress', 'still needed: %s' % dn)
        pg.click('[data-testid=sty_need_dates] .btn'); pg.wait_for_timeout(500)
        check(M.vis(pg, '[data-testid=sty_dates]') and 'Before' in pg.inner_text('.sty-dtsh .bsh-h'), 'the card opens the picker on Before')
        check(M.vis(pg, '[data-testid=sty_sug]') and 'Nov 2024' in pg.inner_text('[data-testid=sty_sug]'), 'suggested from the photo: Nov 2024')
        pg.click('[data-testid=sty_sug]'); pg.click('[data-testid=sty_dsave]'); pg.wait_for_timeout(800)
        d = acts('dates'); check(d and d[-1].get('before') == '2024-11', 'saved Before 2024-11 (%s)' % d[-1:])
        check(pg.inner_text('[data-testid=sty_date_before]').strip() == 'Nov 2024', 'Before · Nov 2024')
        pg.click('[data-testid=sty_date_progress]'); pg.wait_for_timeout(500)
        pg.select_option('[data-testid=styYaM]', '01'); pg.select_option('[data-testid=styYaY]', '2025'); pg.select_option('[data-testid=styYbM]', '03'); pg.select_option('[data-testid=styYbY]', '2025')
        shot(pg, 'dates-progress'); pg.click('[data-testid=sty_dsave]'); pg.wait_for_timeout(800)
        d = acts('dates'); check(d and d[-1].get('progress') == '2025-01' and d[-1].get('progressEnd') == '2025-03', 'In progress Jan – Mar 2025 sent')
        check(pg.inner_text('[data-testid=sty_date_progress]').strip() == 'Jan – Mar 2025', 'In progress · Jan – Mar 2025 (%s)' % pg.inner_text('[data-testid=sty_date_progress]'))
        check(pg.locator('[data-testid=sty_need_dates]').count() == 0, 'the dates card is gone')
        # out of order (server), only a month, the future
        CFG['order'] = True; pg.click('[data-testid=sty_date_after]'); pg.wait_for_timeout(500)
        pg.select_option('[data-testid=styYaM]', '06'); pg.click('[data-testid=sty_dsave]'); pg.wait_for_timeout(300)
        check('month and the year' in pg.inner_text('.sty-dtsh .msg'), 'only a month: asks for the year too')
        nx = datetime.date.today().year + 0; pg.select_option('[data-testid=styYaY]', str(nx)); pg.select_option('[data-testid=styYaM]', '12' if datetime.date.today().month < 12 else '12')
        if datetime.date.today().month < 12:
            pg.click('[data-testid=sty_dsave]'); pg.wait_for_timeout(300); check('future' in pg.inner_text('.sty-dtsh .msg'), 'a month in the future: said')
        pg.select_option('[data-testid=styYaY]', '2024'); pg.select_option('[data-testid=styYaM]', '06'); pg.click('[data-testid=sty_dsave]'); pg.wait_for_timeout(800)
        check('in order' in pg.inner_text('.sty-dtsh .msg'), 'out of order: the server’s answer said in the sheet (%s)' % pg.inner_text('.sty-dtsh .msg'))
        look(pg, check, 'dates error', '.bsh-p'); shot(pg, 'dates-order')
        ctx.close()
        # "Finished: June 2025 (from monday.com)" when the story knows the day the project was finished
        reset(); ST['done'] = '2025-06-20'
        ctx, pg = M.context(br, 'en', 390, ''); install(pg); ST['done'] = '2025-06-20'; M.go(pg, '#p/%s/story' % PID, 2200)
        check(M.vis(pg, '[data-testid=sty_fin]') and 'Finished: June 2025 (from monday.com)' in pg.inner_text('[data-testid=sty_fin]'), 'Finished: June 2025 (from monday.com)')
        pg.click('[data-testid=sty_date_after]'); pg.wait_for_timeout(500)
        check('Jun 2025' in pg.inner_text('[data-testid=sty_sug]'), 'After suggests the month it was finished')
        ctx.close(); br.close()

def s_cards(check):
    with sync_playwright() as p:
        br = p.chromium.launch()
        ctx, pg = open_story(br, 'en', 390, '', new=2)
        pg.click('[data-testid=sty_need_answers] .btn'); pg.wait_for_timeout(900)
        check('Question 5 of 6' in pg.inner_text('.sty-q') and pg.evaluate("(() => { const r = document.getElementById('styQs').getBoundingClientRect(); return r.top >= 0 && r.top < innerHeight / 2; })()"), 'answers card: goes to the first unanswered question')
        pg.click('[data-testid=sty_need_consent] .btn'); pg.wait_for_timeout(900)
        check(pg.evaluate("(() => { const r = document.getElementById('styC').getBoundingClientRect(); return r.top >= 0 && r.top < innerHeight / 2; })()"), 'consent card: goes to Photo consent')
        # the nudge: Draft again asks first, then it's gone
        pg.click('[data-testid=sty_nudge_draft]'); pg.wait_for_timeout(500); check(M.vis(pg, '.po-ask'), 'Draft again (nudge) asks first')
        pg.click('.po-ask [data-k=yes]'); pg.wait_for_timeout(1000)
        check(acts('draft') and pg.locator('[data-testid=sty_nudge]').count() == 0, 'drafted again: the nudge is gone')
        ctx.close()
        pub = {'at': iso(1), 'by': 'Reid Anderson', 'url': 'https://www.rafikioutreach.org/stories/102-x', 'path': '/stories/102-x'}
        ctx, pg = open_story(br, 'en', 390, '', status='published', pub=pub, new=2)
        check('2 new photos' in pg.inner_text('[data-testid=sty_nudge]') and 'story editor' in pg.inner_text('[data-testid=sty_nudge]') and pg.locator('[data-testid=sty_nudge_draft]').count() == 0, 'on the website, admin: "add them in the story editor"')
        check(pg.evaluate("[...document.querySelectorAll('.sty-st li')].map(l => l.className).join()") == 'done,done,done,now' and pg.locator('#styNeed .sty-ni').count() == 0, 'on the website: last step, nothing "still needed"')
        shot(pg, 'published-nudge'); ctx.close()
        ctx, pg = open_story(br, 'en', 390, '', admin=False, status='published', pub=pub, new=2)
        check(pg.locator('[data-testid=sty_nudge]').count() == 0, 'on the website, field user: no nudge'); ctx.close()
        br.close()

if __name__ == '__main__':
    if WHAT in ('all', 'look'): M.seq('The look: 320 / 390, EN / SW, light / dark / sun, 1280; sheets, sending, done / failed', s_look)
    if WHAT in ('all', 'flow', 'add'): M.seq('Add: several from the gallery, review, children, sending one by one, camera shots', s_add)
    if WHAT in ('all', 'flow', 'off'): M.seq('No signal: waits on the phone, the Stories tab says so, sends when back; app closed while sending', s_offline)
    if WHAT in ('all', 'flow', 'fail'): M.seq('Failures: too large (retry), not a photo (take out), server (tap to retry); reduced motion', s_fail)
    if WHAT in ('all', 'flow', 'details'): M.seq('A photo’s details: caption, children, move, delete, Back', s_details)
    if WHAT in ('all', 'flow', 'dates'): M.seq('The month of each column; Finished (from monday.com)', s_dates)
    if WHAT in ('all', 'flow', 'cards'): M.seq('Still needed cards; the new-photos nudge', s_cards)
    fails = [r for r in M.RESULTS if not r[1]]
    print('\n%d/%d passed' % (len(M.RESULTS) - len(fails), len(M.RESULTS)))
    errs = sorted(set(M.ERRS)); print('page errors: %d' % len(errs)); [print('  ' + e) for e in errs[:20]]
    sys.exit(1 if fails or errs else 0)
