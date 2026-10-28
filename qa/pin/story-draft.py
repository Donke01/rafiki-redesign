# Stories 2 in the app: the long draft (title, the line under it, sections with their photos, the quote), admins edit
# it in place (words, a section's photo from the project's photos, remove a photo), Approve and publish (asks first),
# "On the website" with the link, Back to Collecting takes it off (asks first); field users read it. Back closes the
# photo picker, a refresh keeps the writer, unsaved edits survive a redraw. Phone 320 / 390, EN / SW, light / dark / sun:
# no word split or cut, no overlaps, no sideways scroll. Mocked APIs (qa/pin/me.py). PASS / FAIL.
#   python3 S/srvpin.py <checkout> 8893 & BASE=http://localhost:8893 OUT=/tmp/sd python3 qa/pin/story-draft.py
import os, re, sys, json
HERE = os.path.dirname(os.path.abspath(__file__)); sys.path.insert(0, HERE)
sys.argv = sys.argv[:1] + ['none']
os.environ.setdefault('OUT', os.path.join(HERE, 'story-draft'))
import me as M
from playwright.sync_api import sync_playwright
OUT = M.OUT

CAPS = ['Before the work began, January 2026', 'Laying the foundation, January 2026', 'Raising the walls, February 2026', 'Raising the walls, February 2026', 'Putting on the roof, February 2026', 'Handing over, March 2026', 'The finished project, March 2026', 'Water flowing']
ROWS = ['before', 'stage:foundation', 'stage:walls', 'stage:walls', 'stage:roof', 'stage:handed', 'after', 'after']
POOL = [{'id': str(9300 + i), 'row': r, 'cap': c, 'children': i == 6} for i, (r, c) in enumerate(zip(ROWS, CAPS))]
LONG = 'Before the spring was protected, women and girls from Turbo walked down a steep path every morning to a pool that cattle also drank from. The water was brown after rain, and children were often sick.'
def doc():
    ph = lambda i: {'id': POOL[i]['id'], 'row': POOL[i]['row'], 'cap': POOL[i]['cap']}
    secs = [('Mornings at the open pool', LONG + '\n\n' + 'Families had no other water within reach, so they kept coming back to the same pool.', ph(0)),
            ('The people it serves', 'The spring now serves about 1,200 people, most of them families from the villages around Turbo market.', None),
            ('Laying the foundation', 'Work on the foundation began on 12 January 2026. Parents carried sand and stones up from the river bed, and the trenches were dug by hand.', ph(1)),
            ('Raising the walls', 'The walls of the spring box began to go up on 1 February 2026. Local masons built them to window level in under two weeks.', ph(2)),
            ('Putting on the roof', 'Work on the roof began on 20 February 2026, and iron sheets went on before the long rains came.', ph(4)),
            ('What changed', 'Since then, the water runs clear from a pipe. Mothers say the children are sick less often.', ph(7)),
            ('One moment', 'On the day it was handed over, the first girl to fill her jerrycan held it up for everyone to see.', None)]
    d = {'v': 1, 'title': 'Clear water at Turbo Spring', 'dek': 'How a muddy pool shared with cattle became a protected spring for the whole village.',
         'sections': [{'h': h, 'body': b, 'photo': p} for h, b, p in secs], 'pull': {'quote': 'Now my children drink water I am not afraid of.', 'who': 'Mary Wanjiku, mother of three'},
         'facts': {'place': 'Turbo', 'country': 'Kenya', 'ck': 'ke', 'type': 'Water collection', 'people': 1200, 'year': 2026, 'stages': []}, 'at': '2026-10-28T08:00:00Z', 'by': 'Meril Kiprop', 'model': 'openai:gpt-4o-mini'}
    d['words'] = sum(len(x['body'].split()) for x in d['sections']) + len(d['title'].split()) + len(d['dek'].split())
    d['text'] = '\n\n'.join([d['title'], d['dek']] + [y for x in d['sections'] for y in (x['h'], x['body'])])
    return d
ST = {}
def reset(status='draft', pub=None):
    ST.clear(); ST.update({'id': '103', 'status': status, 'consent': 'received', 'answers': {q: 'An answer.' for q in ['q1', 'q2', 'q3', 'q4', 'q5', 'q6']}, 'quote': 'Now my children drink water I am not afraid of.', 'quoteBy': 'Mary Wanjiku, mother of three', 'credit': 'Meril Kiprop',
        'photos': {'before': [{'id': '9300', 'name': 'b.jpg', 'children': False, 'by': 'Meril Kiprop', 'at': '2026-01-10T00:00:00Z'}], 'progress': [], 'after': [{'id': '9306', 'name': 'a.jpg', 'children': True, 'by': 'Meril Kiprop', 'at': '2026-03-05T00:00:00Z'}, {'id': '9307', 'name': 'water-flowing.jpg', 'children': False, 'by': 'Meril Kiprop', 'at': '2026-03-05T00:00:00Z'}]},
        'doc': doc(), 'pub': pub, 'pool': POOL})
    ST['text'] = ST['doc']['text']
SENT = []
def view():
    y = json.loads(json.dumps(ST)); y['prompts'] = M.PROMPTS; y['missing'] = []; y['canApprove'] = True; return y
def install(pg, admin):
    def story_api(route, req):
        from urllib.parse import urlparse, parse_qs
        q = parse_qs(urlparse(req.url).query)
        if req.method == 'GET':
            if 'asset' in q: n = int(q['asset'][0]) % 7; return route.fulfill(body=M.face([(10, 120, 194), (28, 156, 90), (194, 84, 40), (122, 79, 208), (200, 150, 20), (30, 160, 160), (90, 90, 90)][n], 320), content_type='image/png')
            return route.fulfill(json={'ok': True, 'story': view()})
        b = json.loads(req.post_data or '{}'); SENT.append(b); a = b.get('action')
        if a in ('doc', 'approve', 'collecting', 'text') and not admin: return route.fulfill(status=403, json={'ok': False, 'reason': 'admin-only'})
        if a in ('doc', 'approve') and b.get('doc'):
            d = ST['doc']; d['title'] = b['doc']['title']; d['dek'] = b['doc']['dek']; pool = {p['id']: p for p in POOL}
            d['sections'] = [{'h': x['h'], 'body': x['body'], 'photo': ({'id': x['photo']['id'], 'row': pool[x['photo']['id']]['row'], 'cap': pool[x['photo']['id']]['cap']} if x.get('photo') else None)} for x in b['doc']['sections']]
            d['at'] = '2026-10-28T09:%02d:00Z' % len(SENT)
        if a == 'approve': ST['status'] = 'published'; ST['pub'] = {'at': '2026-10-28T09:00:00Z', 'by': 'Reid Anderson', 'url': 'https://www.rafikioutreach.org/stories/103-clear-water-at-turbo-spring', 'path': '/stories/103-clear-water-at-turbo-spring'}
        if a == 'collecting': ST['status'] = 'collecting'; ST['pub'] = None
        if a == 'consent': ST['consent'] = b['value']
        return route.fulfill(json={'ok': True, 'story': view()})
    pg.route(re.compile(r'/api/story(\?.*)?$'), story_api)

def open_writer(br, lang, w, th, admin=True, status='draft', pub=None):
    reset(status, pub); del SENT[:]
    ctx, pg = M.context(br, lang, w, th, admin=admin); install(pg, admin)
    M.go(pg, '#p/103/story', 2200)
    pg.locator('[data-testid=sty_doc]').scroll_into_view_if_needed(); pg.wait_for_timeout(500)
    return ctx, pg

OVERLAP = """() => { const bad = []; const q = s => [...document.querySelectorAll(s)].filter(e => e.offsetParent);
  const hit = (a, b) => { const r = a.getBoundingClientRect(), t = b.getBoundingClientRect(); return r.width && t.width && r.left < t.right - 1 && t.left < r.right - 1 && r.top < t.bottom - 1 && t.top < r.bottom - 1; };
  for (const sel of ['.sty-doc .sty-acts > *', '.sty-doc figcaption > *', '.sty-doc .sty-fa > *', '.sty-doc .sty-dh > *', '.sty-doc .sty-sech > *:not(label)', '.sty-pub > *']) { const els = q(sel); for (let i = 0; i < els.length; i++) for (let j = i + 1; j < els.length; j++) if (els[i].parentNode === els[j].parentNode && hit(els[i], els[j])) bad.push(sel + ':' + (els[i].innerText || els[i].tagName).slice(0, 20) + '/' + (els[j].innerText || els[j].tagName).slice(0, 20)); }
  const W = document.documentElement.clientWidth; q('.sty-doc *').forEach(e => { const r = e.getBoundingClientRect(); if (r.width && (r.right > W + 1 || r.left < -1)) bad.push('off-screen:' + e.tagName + '.' + e.className); });
  if (document.documentElement.scrollWidth > W + 1) bad.push('sideways scroll'); return bad.slice(0, 6); }"""

def s_look(check):
    with sync_playwright() as p:
        br = p.chromium.launch()
        for w in (320, 390):
            for lang in ('en', 'sw'):
                for th in ('', 'dark', 'sun'):
                    t = '%d-%s%s' % (w, lang, '-' + th if th else '')
                    ctx, pg = open_writer(br, lang, w, th)
                    check(pg.locator('.sty-doc .sty-sec').count() == 7 and pg.locator('#styDT').input_value() == 'Clear water at Turbo Spring', '[%s] the draft: title and 7 sections to edit' % t)
                    check(pg.locator('.sty-doc .sty-fig img.in').count() == 5, '[%s] 5 section photos shown (%d)' % (t, pg.locator('.sty-doc .sty-fig img.in').count()))
                    bad = M.wrapcheck(pg, '#paneStory .sty-doc'); check(not bad, '[%s] no word split or cut (%s)' % (t, bad))
                    ov = pg.evaluate(OVERLAP); check(not ov, '[%s] no overlaps, nothing off-screen (%s)' % (t, ov))
                    label = 'Idhinisha na uchapishe' if lang == 'sw' else 'Approve and publish'
                    check(pg.inner_text('[data-testid=sty_approve]').strip() == label, '[%s] the button says “%s”' % (t, label))
                    pg.locator('[data-testid=sty_doc]').screenshot(path=os.path.join(OUT, 'draft-admin-%s.png' % t))
                    # read-only for field users, on the website for admins
                    ctx.close()
                    ctx, pg = open_writer(br, lang, w, th, admin=False)
                    check(pg.locator('.sty-doc input, .sty-doc textarea').count() == 0 and pg.locator('.sty-art .sty-sec').count() == 7, '[%s] field user: read-only article' % t)
                    check(pg.locator('[data-testid=sty_approve]').count() == 0, '[%s] field user: no Approve' % t)
                    bad = M.wrapcheck(pg, '#paneStory .sty-doc'); check(not bad, '[%s] field: no word split or cut (%s)' % (t, bad))
                    ov = pg.evaluate(OVERLAP); check(not ov, '[%s] field: no overlaps (%s)' % (t, ov))
                    if lang == 'en' or w == 390: pg.locator('[data-testid=sty_doc]').screenshot(path=os.path.join(OUT, 'draft-field-%s.png' % t))
                    ctx.close()
                    if w == 390 or th == '':
                        ctx, pg = open_writer(br, lang, w, th, status='published', pub={'at': '2026-10-28T09:00:00Z', 'by': 'Reid Anderson', 'url': 'https://www.rafikioutreach.org/stories/103-clear-water-at-turbo-spring', 'path': '/stories/103-clear-water-at-turbo-spring'})
                        check(M.vis(pg, '[data-testid=sty_pub]') and pg.get_attribute('[data-testid=sty_pub]', 'href').endswith('/stories/103-clear-water-at-turbo-spring'), '[%s] published: “On the website” with the link' % t)
                        check(pg.locator('[data-testid=sty_approve]').count() == 0 and pg.locator('[data-testid=sty_draft]').count() == 0, '[%s] published: no Approve, no new draft' % t)
                        ov = pg.evaluate(OVERLAP); check(not ov, '[%s] published: no overlaps (%s)' % (t, ov))
                        bad = M.wrapcheck(pg, '#paneStory .sty-doc'); check(not bad, '[%s] published: no word split (%s)' % (t, bad))
                        pg.locator('[data-testid=sty_doc]').screenshot(path=os.path.join(OUT, 'published-%s.png' % t))
                        ctx.close()
        br.close()

def s_flow(check):
    with sync_playwright() as p:
        br = p.chromium.launch()
        for lang in ('en', 'sw'):
            ctx, pg = open_writer(br, lang, 390, '')
            # edit the words; a redraw (consent) keeps the unsaved edit
            pg.fill('#styDT', 'Clear water for Turbo'); pg.fill('#styB1', 'The spring now serves about 1,200 people.')
            pg.click('[data-sty=consent][data-v=received]'); pg.wait_for_timeout(700)
            check(pg.locator('#styDT').input_value() == 'Clear water for Turbo', '[%s] an unsaved edit survives a redraw' % lang)
            # a photo for section 2 from the project's photos; Back closes the picker and stays on the writer
            pg.locator('.sty-sec').nth(1).locator('[data-sty=pick]').first.click(); pg.wait_for_timeout(700)
            check(M.vis(pg, '.sty-psh') and pg.locator('.sty-psh .sty-po').count() == 8, '[%s] the photo picker lists the 8 project photos' % lang)
            pg.wait_for_timeout(400); pg.screenshot(path=os.path.join(OUT, 'picker-%s.png' % lang))
            bad = M.wrapcheck(pg, '.sty-psh .bsh-p'); check(not bad, '[%s] picker: no word split (%s)' % (lang, bad))
            M.back(pg, 900); check(pg.locator('.sty-psh').count() == 0 and M.h(pg) == '#p/103/story' and M.vis(pg, '[data-testid=sty_doc]'), '[%s] Back closes the picker, the writer stays (%s)' % (lang, M.h(pg)))
            pg.locator('.sty-sec').nth(1).locator('[data-sty=pick]').first.click(); pg.wait_for_timeout(700)
            pg.click('.sty-psh .sty-po[data-pid="9303"]'); pg.wait_for_timeout(700)
            check(pg.locator('.sty-sec').nth(1).locator('.sty-fig figcaption').inner_text().startswith('Raising the walls'), '[%s] section 2 now shows the picked photo' % lang)
            # a photo already in another section moves (each photo shows once)
            pg.locator('.sty-sec').nth(2).locator('[data-sty=pick]').first.click(); pg.wait_for_timeout(700)
            check('In section' in pg.inner_text('.sty-psh') or 'Kwenye sehemu' in pg.inner_text('.sty-psh'), '[%s] the picker says which photos are in use' % lang)
            pg.click('.sty-psh .sty-po[data-pid="9300"]'); pg.wait_for_timeout(700)
            check(pg.locator('.sty-sec').nth(0).locator('.sty-fig').count() == 0, '[%s] the photo moved from section 1 to section 3' % lang)
            pg.locator('.sty-sec').nth(3).locator('[data-sty=nophoto]').click(); pg.wait_for_timeout(500)
            check(pg.locator('.sty-sec').nth(3).locator('.sty-add').count() == 1, '[%s] Remove leaves “Add a photo”' % lang)
            pg.click('[data-testid=sty_save]'); pg.wait_for_timeout(800)
            d = [x for x in SENT if x.get('action') == 'doc']
            ok = d and d[-1]['doc']['title'] == 'Clear water for Turbo' and [ (x['photo'] or {}).get('id') for x in d[-1]['doc']['sections']] == [None, '9303', '9300', None, '9304', '9307', None]
            check(ok, '[%s] Save sends the words and the photos (%s)' % (lang, d and [(x['photo'] or {}).get('id') for x in d[-1]['doc']['sections']]))
            pg.locator('[data-testid=sty_doc]').screenshot(path=os.path.join(OUT, 'edited-%s.png' % lang))
            # refresh keeps the writer and the saved draft
            pg.reload(); pg.wait_for_timeout(2600)
            check(M.h(pg) == '#p/103/story' and pg.locator('#styDT').input_value() == 'Clear water for Turbo', '[%s] refresh: the writer with the saved draft' % lang)
            # approve and publish: asks first, sends the document, then “On the website”
            pg.locator('[data-testid=sty_approve]').scroll_into_view_if_needed(); pg.click('[data-testid=sty_approve]'); pg.wait_for_timeout(600)
            check(M.vis(pg, '.po-ask'), '[%s] Approve and publish asks first' % lang); pg.screenshot(path=os.path.join(OUT, 'approve-ask-%s.png' % lang))
            pg.click('.po-ask [data-k=yes]'); pg.wait_for_timeout(900)
            ap = [x for x in SENT if x.get('action') == 'approve']
            check(ap and ap[-1].get('doc') and ap[-1]['doc']['title'] == 'Clear water for Turbo', '[%s] approve sends the document' % lang)
            check(M.vis(pg, '[data-testid=sty_pub]') and pg.locator('[data-testid=sty_now]').inner_text().startswith('Imechapishwa' if lang == 'sw' else 'Published'), '[%s] Published, on the website' % lang)
            pg.locator('[data-testid=sty_doc]').scroll_into_view_if_needed(); pg.wait_for_timeout(300); pg.screenshot(path=os.path.join(OUT, 'approved-%s.png' % lang))
            # back to Collecting: asks first (red), then off the website
            pg.click('[data-testid=sty_back]'); pg.wait_for_timeout(600)
            check(M.vis(pg, '.po-ask .po-red'), '[%s] taking it off asks first' % lang)
            M.back(pg, 800); check(pg.locator('.po-ask').count() == 0 and M.h(pg) == '#p/103/story', '[%s] Back closes the question, nothing sent' % lang)
            check(not [x for x in SENT if x.get('action') == 'collecting'], '[%s] … and nothing was taken off' % lang)
            pg.click('[data-testid=sty_back]'); pg.wait_for_timeout(600); pg.click('.po-ask [data-k=yes]'); pg.wait_for_timeout(900)
            check([x for x in SENT if x.get('action') == 'collecting'] and pg.locator('[data-testid=sty_pub]').count() == 0, '[%s] back to Collecting: off the website' % lang)
            # Back from the writer goes to the project list / map as before, Forward returns
            ctx.close()
        br.close()

if __name__ == '__main__':
    M.seq('The draft: look at 320 / 390, EN / SW, light / dark / sun (admin, field user, published)', s_look)
    M.seq('The draft: edit, photos, Back, refresh, approve and publish, take off the website', s_flow)
    fails = [r for r in M.RESULTS if not r[1]]
    print('\n%d/%d passed' % (len(M.RESULTS) - len(fails), len(M.RESULTS)))
    errs = sorted(set(M.ERRS)); print('page errors: %d' % len(errs)); [print('  ' + e) for e in errs[:20]]
    sys.exit(1 if fails or errs else 0)
