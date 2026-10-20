# Records a short clip of the card motion (for review), as a video in the given folder.
import os, re, sys
from playwright.sync_api import sync_playwright
OUT = sys.argv[1]; os.makedirs(OUT, exist_ok=True)
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import importlib.util
spec = importlib.util.spec_from_file_location('f13', os.path.join(os.path.dirname(os.path.abspath(__file__)), 'front13.py'))
P = [{'id': '101', 'title': 'Chepkongi Dispensary', 'country': 'Kenya', 'ck': 'ke', 'area': 'Eldoret', 'type': 'health', 'status': 'Completed', 'gps': [0.5143, 35.2698], 'photo': None},
     {'id': '102', 'title': 'Kiptoo Well', 'country': 'Kenya', 'ck': 'ke', 'area': 'Nandi', 'type': 'borehole', 'status': 'Completed', 'gps': None, 'photo': None},
     {'id': '104', 'title': 'Kapsaret Borehole', 'country': 'Kenya', 'ck': 'ke', 'area': 'Eldoret', 'type': 'borehole', 'status': 'Completed', 'gps': [0.5101, 35.2742], 'photo': None},
     {'id': '105', 'title': 'Turbo Spring', 'country': 'Kenya', 'ck': 'ke', 'area': 'Turbo', 'type': 'collection', 'status': 'Completed', 'gps': [0.63, 35.05], 'photo': None}]
REP = {'104': {'state': 'notworking', 'by': 'Bertin', 'at': '2026-10-18T08:00:00Z', 'note': 'Handle loose', 'urgency': 'urgent', 'cost': 4500, 'step': 'parts', 'assignee': 'Meril Kiprop', 'due': '2026-10-24', 'stepAt': {'assigned': '2026-10-18T10:20:00Z', 'parts': '2026-10-19T07:45:00Z'}}}
VIS = {'101': {'at': '2026-10-19T09:00:00Z', 'by': 'Meril Kiprop', 'state': 'working'}}
def api(route, req):
    u = req.url
    if req.method == 'GET':
        if 'activity=' in u: return route.fulfill(json={'ok': True, 'activity': [{'by': 'Meril Kiprop', 'a': 'pin', 'id': '101', 't': 'Chepkongi', 'at': '2026-10-19T08:00:00Z'}, {'by': 'Meril Kiprop', 'a': 'visit', 's': 'working', 'id': '101', 't': 'Chepkongi', 'at': '2026-10-19T09:00:00Z'}]})
        if 'history=' in u: return route.fulfill(json={'ok': True, 'notes': []})
        if 'id=' in u: return route.fulfill(json={'ok': True, 'last': None, 'visits': []})
        return route.fulfill(json={'ok': True, 'who': 'Meril Kiprop', 'admin': True, 'reviewOpen': 0, 'projects': P, 'repairs': REP, 'visits': VIS, 'jobs': [], 'assigned': [], 'confirmed': {}})
    return route.fulfill(json={'ok': True})
with sync_playwright() as p:
    br = p.chromium.launch()
    ctx0 = br.new_context(viewport={'width': 390, 'height': 844}, service_workers='block')
    pg = ctx0.new_page(); pg.route('**/api/pin-auth/me', lambda r, q: r.fulfill(json={'available': False})); pg.route(re.compile(r'/api/pin(\?.*)?$'), api)
    pg.goto('http://localhost:8797/pin'); pg.wait_for_timeout(800)
    for d in '9999': pg.click('.pk[data-k="%s"]' % d)
    pg.wait_for_timeout(1000); pg.evaluate("localStorage.setItem('rfPinOnb','1'); localStorage.setItem('rfPinFilter','all')"); st = ctx0.storage_state(); ctx0.close()
    ctx = br.new_context(viewport={'width': 390, 'height': 844}, service_workers='block', storage_state=st, record_video_dir=OUT, record_video_size={'width': 390, 'height': 844},
                         geolocation={'latitude': 0.5150, 'longitude': 35.27, 'accuracy': 8}, permissions=['geolocation'])
    pg = ctx.new_page(); pg.route('**/api/pin-auth/me', lambda r, q: r.fulfill(json={'available': False})); pg.route(re.compile(r'/api/pin(\?.*)?$'), api)
    pg.route('**/api/admin**', lambda r, q: r.fulfill(json={'ok': True, 'items': [], 'team': [], 'mail': {}, 'cron': False}))
    pg.route('**/arcgisonline.com/**', lambda r, q: r.abort())
    pg.goto('http://localhost:8797/pin#map'); pg.wait_for_timeout(2600)
    pg.click('#tabs [data-v=vList]'); pg.wait_for_timeout(2200)
    pg.mouse.wheel(0, 700); pg.wait_for_timeout(1200)
    pg.click('#list .row[data-id="104"]'); pg.wait_for_timeout(1400)
    pg.evaluate("document.querySelector('#pRepair').scrollIntoView({behavior:'instant'})"); pg.evaluate("document.querySelector('#pRepair').innerHTML=''"); pg.evaluate("window.__r && 0")
    pg.click('#hBack'); pg.wait_for_timeout(600); pg.click('#list .row[data-id="104"]'); pg.wait_for_timeout(300)
    pg.mouse.wheel(0, 420); pg.wait_for_timeout(2200)
    pg.click('#tabs [data-v=vMe]'); pg.wait_for_timeout(800); pg.evaluate("document.getElementById('meStamps').scrollIntoView({block:'center'})"); pg.wait_for_timeout(2200)
    ctx.close(); br.close()
