# Chat 3 (direction B, design/chat-2026-10b/board-b.webp): screenshots of the chat list, one chat, the long-press menu and
# chat info at 320 / 390 in light, dark and sun, plus a desktop view. Dev helper:  OUT=/tmp/x python3 qa/pin/chat3-look.py [dump]
import os, sys
HERE = os.path.dirname(os.path.abspath(__file__))
src = open(os.path.join(HERE, 'chat2-room.py')).read(); src = src[:src.index('\nwith sync_playwright()')]
os.environ.setdefault('OUT', os.path.join(HERE, 'chat3-look')); sys.argv = sys.argv[:1] + ['en'] + sys.argv[1:]
exec(compile(src, 'chat2-room.py', 'exec'))
DUMP = 'dump' in sys.argv
TREE = """(sel => { const out = []; const walk = (el, d) => { if (d > 7 || !el.getClientRects().length) return; const c = (el.className && el.className.baseVal === undefined) ? el.className : '';
  out.push('  '.repeat(d) + el.tagName.toLowerCase() + (el.id ? '#' + el.id : '') + (c ? '.' + String(c).trim().split(/\\s+/).join('.') : '') + (el.dataset && el.dataset.mk ? '[mk=' + el.dataset.mk + ']' : ''));
  if (out.length < 400) [...el.children].forEach(k => walk(k, d + 1)); }; document.querySelectorAll(sel).forEach(e => walk(e, 0)); return out.join('\\n'); })"""
with sync_playwright() as p:
    br = p.chromium.launch()
    for w, theme in [(390, ''), (390, 'dark'), (390, 'sun'), (320, ''), (1280, '')]:
        cm = ChatMock(); seed(cm); W = World(True)
        ctx, pg = context(br, 'en', cm, W, w=w, theme=theme)
        k = '%d-%s' % (w, theme or 'light')
        pg.click('#tabs button[data-v=vChat]'); pg.wait_for_timeout(1300); shot(pg, 'list-' + k, 'b')
        if DUMP and w == 390 and not theme: open(os.path.join(OUT, 'dom-list.txt'), 'w').write(pg.evaluate(TREE, '#vChat'))
        pg.click('.cw-row[data-open="p:101"]'); pg.wait_for_timeout(1500); shot(pg, 'room-' + k, 'b')
        if DUMP and w == 390 and not theme: open(os.path.join(OUT, 'dom-room.txt'), 'w').write(pg.evaluate(TREE, '#vRoom'))
        if w < 1000:
            longpress(pg, '#cwm-5 .cw-tx'); shot(pg, 'sheet-' + k, 'b')
            if DUMP and not theme: open(os.path.join(OUT, 'dom-sheet.txt'), 'w').write(pg.evaluate(TREE, '.bsh'))
            back(pg); tap(pg, '#cwHd', 900); shot(pg, 'info-' + k, 'b')
            if DUMP and not theme: open(os.path.join(OUT, 'dom-info.txt'), 'w').write(pg.evaluate(TREE, '.bsh, .tsh'))
            pg.evaluate("document.querySelector('.tsh-p, .bsh-p') && (document.querySelector('.tsh-p, .bsh-p').scrollTop = 9999)"); pg.wait_for_timeout(300); shot(pg, 'info2-' + k, 'b')
        ctx.close()
    br.close()
print('errors:', ERRS[:5])
