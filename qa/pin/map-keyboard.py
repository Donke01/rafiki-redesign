import sys, os, re
sys.path.insert(0,'../audit-web'); import mock
from playwright.sync_api import sync_playwright
OUT=sys.argv[1]; os.makedirs(OUT,exist_ok=True); TILE=open('rafiki-site/qa/pin/tile.png','rb').read()
CHK="""() => { const sel=['#mpaQ','.mpa-chips','#mpaKey','.ov.top .pill','#mHere','#nearSheet','#mpaSug','.mcl-side','.leaflet-control-attribution']; const r=[];
 const vis=e=>{const s=getComputedStyle(e);const b=e.getBoundingClientRect();return s.display!='none'&&s.visibility!='hidden'&&+s.opacity>0.05&&b.width>4&&b.height>4&&!e.closest('.hidden')};
 const els=[];sel.forEach(q=>document.querySelectorAll('#vMap '+q).forEach(e=>{if(vis(e))els.push([q,e.getBoundingClientRect()])}));
 for(let i=0;i<els.length;i++)for(let j=i+1;j<els.length;j++){const a=els[i][1],c=els[j][1];const x=Math.min(a.right,c.right)-Math.max(a.left,c.left),y=Math.min(a.bottom,c.bottom)-Math.max(a.top,c.top);if(x>2&&y>2)r.push(els[i][0]+' x '+els[j][0])}return r;}"""
with sync_playwright() as p:
    b=p.chromium.launch()
    for lang in ('en','sw'):
     for H in (480,520,600,844):
        ctx=b.new_context(viewport={'width':390,'height':H},device_scale_factor=2,is_mobile=True,has_touch=True)
        ctx.route(re.compile(r'https://(tile\.openstreetmap\.org|server\.arcgisonline\.com)/.*'), lambda r,q: r.fulfill(status=200,body=TILE,headers={'Content-Type':'image/png'}))
        pg=ctx.new_page(); errs=[]; pg.on('pageerror',lambda e: errs.append(str(e)[:200])); mock.install(pg,True,[])
        pg.goto('http://localhost:8878/pin'); pg.wait_for_timeout(800)
        if lang=='sw': pg.evaluate("localStorage.setItem('rfPinLang','sw')"); pg.reload(); pg.wait_for_timeout(800)
        for d in '9999': pg.click('.pk[data-k="%s"]'%d)
        pg.wait_for_timeout(2500); res={}
        pg.screenshot(path=f'{OUT}/{lang}-{H}-map.png'); res['map']=pg.evaluate(CHK)
        pg.click('#mpaQ'); pg.wait_for_timeout(500); pg.screenshot(path=f'{OUT}/{lang}-{H}-focus.png'); res['focus']=pg.evaluate(CHK)
        pg.keyboard.type('Kap'); pg.wait_for_timeout(700); pg.screenshot(path=f'{OUT}/{lang}-{H}-typed.png'); res['typed']=pg.evaluate(CHK)
        pg.keyboard.press('Escape'); pg.evaluate("document.activeElement.blur()"); pg.wait_for_timeout(500); res['after']=pg.evaluate(CHK)
        print(lang,H,res,errs); ctx.close()
    b.close()
