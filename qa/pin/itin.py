# Trips (itinw): Office › Trips and Vehicles, a trip's day plan, Plan a trip with suggestions, costs, packing, share,
# "Move the rest?", Me › Next stop, a group trip and donor mode, against a mock of the itin API (contract v1.1, docs/itinerary.md).
# Also walks it once like a user: Back one sheet at a time, drag a stop, lock a time, a one-tap fix, Check in → Report a visit.
# Screenshots at 360, 390 and 1280 wide, in light, dark and sun, in English and Kiswahili. Each one is checked for
# words broken across lines, things on top of each other, sideways scrolling and console errors.
#   BASE=http://localhost:8931 OUT=/tmp/shots python3 qa/pin/itin.py [en|sw|both] [360,390,1280] [light,dark,sun]
import os, re, sys, json, datetime, subprocess
from playwright.sync_api import sync_playwright
HERE = os.path.dirname(os.path.abspath(__file__)); BASE = os.environ.get('BASE', 'http://localhost:8931')
OUT = os.environ.get('OUT', os.path.join(HERE, 'itin')); os.makedirs(OUT, exist_ok=True)
TILE = open(os.path.join(HERE, 'tile.png'), 'rb').read()
LANGS = {'en': ['en'], 'sw': ['sw'], 'both': ['en', 'sw']}[sys.argv[1] if len(sys.argv) > 1 else 'both']
WIDTHS = [int(x) for x in (sys.argv[2] if len(sys.argv) > 2 else '360,390,1280').split(',')]
THEMES = (sys.argv[3] if len(sys.argv) > 3 else 'light,dark,sun').split(',')
ONLY = os.environ.get('ONLY', '')

now = datetime.datetime.now(); iso = lambda h: (datetime.datetime.now(datetime.timezone.utc).replace(tzinfo=None) - datetime.timedelta(hours=h)).strftime('%Y-%m-%dT%H:%M:%SZ')
day = lambda d: (now + datetime.timedelta(days=d)).strftime('%Y-%m-%d')
def pj(i, t, area, ll, ty='Borehole well'):
    return {'id': i, 'title': t, 'country': 'Kenya', 'ck': 'ke', 'area': area, 'type': ty, 'status': 'Completed', 'gps': ll}
P = [pj('101', 'Kapsaret Borehole', 'Kapsaret, Eldoret', [0.47, 35.25]), pj('102', 'Chepkongi Dispensary', 'Eldoret', [0.5143, 35.2698], 'Health'),
     pj('104', 'Nzoia Bridge Primary School - 6 Classrooms and a Water Tank', 'Bungoma', [0.58, 34.62], 'School'), pj('107', 'Moiben Health Centre', 'Moiben', [0.8, 35.4], 'Health'),
     pj('108', 'Ziwa Borehole', 'Ziwa', [0.75, 35.3]), pj('109', 'Kapsabaot Comprehensive School Rainwater Harvesting Tank', 'Kapsabaot', [0.95, 35.08], 'School'),
     pj('113', 'Kiptoo Primary School', 'Nandi', [0.25, 35.12], 'School')]
REP = {'101': {'state': 'notworking', 'by': 'Bertin Mwangi', 'at': iso(50), 't': 'Kapsaret Borehole', 'urgency': 'urgent', 'parts': 'Pump handle, chain', 'step': 'reported'}}
TEAM = [{'name': 'Reid Anderson', 'admin': True, 'source': 'cloudflare'}, {'name': 'Meril Kiprop', 'source': 'app', 'home': 'ke'}, {'name': 'Grace Achieng', 'source': 'app', 'home': 'ke'}]
def W(code, level, en, sw, **k): d = {'code': code, 'level': level, 'en': en, 'sw': sw}; d.update(k); return d
CH = lambda s, r, t, rk='ok': {'surface': {'en': s[0], 'sw': s[1]}, 'rain': {'en': r[0], 'sw': r[1]}, 'time': {'en': t[0], 'sw': t[1]}}
def stop(sid, p, ty, vm, win=None, why=None, **k):
    d = {'sid': sid, 'id': p['id'], 'name': p['title'], 'lat': p['gps'][0], 'lng': p['gps'][1], 'type': ty, 'visitMin': vm, 'window': win, 'fixed': None, 'locked': False, 'status': 'planned', 'checkin': None, 'why': why or []}
    d.update(k); return d
BP = {p['id']: p for p in P}
IT = {
  'id': 'it_moiben', 'v': 3, 'title': 'Moiben and Ziwa repairs', 'status': 'active', 'owner': 'Reid Anderson', 'people': ['Reid Anderson', 'Meril Kiprop'], 'viewers': [], 'ck': 'ke',
  'from': day(0), 'to': day(1), 'hours': {'start': '07:30', 'end': '17:30'}, 'lunch': {'at': '12:30', 'min': 45},
  'start': {'kind': 'office', 'name': 'Eldoret office', 'lat': 0.514, 'lng': 35.27}, 'end': {'kind': 'same'}, 'vehicleId': 'vh_hilux', 'passengers': 2,
  'stops': [stop('s1', BP['101'], 'borehole', 60, why=[{'code': 'urgent', 'en': 'open repair · urgent', 'sw': 'matengenezo yaliyo wazi · ya dharura'}], status='done', checkin={'at': iso(3), 'by': 'Meril Kiprop'}),
            stop('s2', BP['108'], 'borehole', 45, why=[{'code': 'overdue', 'en': 'due a visit · 8 months', 'sw': 'ziara inahitajika · miezi 8'}]),
            stop('s3', BP['107'], 'health', 45, {'open': '08:00', 'close': '17:00', 'src': 'type'}),
            stop('s4', BP['109'], 'school', 60, {'open': '08:00', 'close': '15:30', 'src': 'type'}, fixed='10:00', locked=True),
            stop('s5', BP['104'], 'school', 60, {'open': '08:00', 'close': '15:30', 'src': 'type'})],
  'days': [{'date': day(0), 'stops': ['s1', 's2', 's3'], 'overnight': {'town': 'Kitale', 'lat': 1.015, 'lng': 35.0, 'suggested': True, 'why': {'en': 'near tomorrow’s first stop (25 min)', 'sw': 'karibu na kituo cha kwanza cha kesho (dak 25)'}, 'lodging': {'name': 'Kitale Club', 'phone': '', 'kes': 4500, 'note': ''}},
            'riskLevel': 'caution', 'weather': {'rainMm': 6, 'roadLevel': 'light'},
            'warnings': [W('ROAD_REPORT', 'caution', 'Potholes reported 3 km before the site by Grace, 12 days ago.', 'Ripoti: Mashimo barabarani, km 3 kabla ya kufika — Grace, siku 12 zilizopita', sid='s2')]},
           {'date': day(1), 'stops': ['s4', 's5'], 'riskLevel': 'difficult', 'weather': {'rainMm': 25, 'roadLevel': 'heavy'},
            'warnings': [W('SWAP_DAY', 'difficult', 'Heavy rain on ' + 'Tuesday on the murram road to Nzoia Bridge. Swap with today?', 'Mvua kubwa Jumanne kwenye barabara ya murram kwenda Nzoia Bridge. Badilisha na leo?', fix={'action': 'swap', 'day': 1, 'with': 0}),
                         W('AFTER_CLOSE', 'caution', 'Arrives 15:50, after school closes (15:30)', 'Inafika 15:50, baada ya shule kufungwa (15:30)', sid='s5', fix={'action': 'move', 'sid': 's5', 'toDay': 0})]}],
  'unplanned': [], 'warnings': [],
  'legs': {'S>s1': {'min': 25, 'km': 14, 'src': 'trip', 'chips': CH(('12 km tarmac · 2 km murram', 'km 12 lami · km 2 murram'), ('No rain', 'Hakuna mvua'), ('~25 min', '~dak 25')), 'riskLevel': 'ok', 'geometry': [[0.514, 35.27], [0.49, 35.26], [0.47, 35.25]]},
           's1>s2': {'min': 70, 'km': 48, 'src': 'trip', 'chips': CH(('40 km tarmac · 8 km murram', 'km 40 lami · km 8 murram'), ('Rain 6 mm', 'Mvua mm 6'), ('~1 h 10', '~saa 1 dak 10')), 'riskLevel': 'caution', 'roadLevel': 'light',
                     'warnings': [{'code': 'MURRAM_LIGHT_RAIN', 'level': 'caution', 'en': 'Last 8 km is murram and light rain is forecast; allow 10 min extra', 'sw': 'Kilomita 8 za mwisho ni murram na mvua kidogo inatarajiwa; ongeza dakika 10'}], 'geometry': [[0.47, 35.25], [0.6, 35.28], [0.75, 35.3]]},
           's2>s3': {'min': 35, 'km': 18, 'src': 'trip', 'chips': CH(('18 km tarmac', 'km 18 lami'), ('No rain', 'Hakuna mvua'), ('~35 min', '~dak 35')), 'riskLevel': 'ok'},
           's3>T:Kitale': {'min': 80, 'km': 62, 'src': 'trip', 'chips': CH(('62 km tarmac', 'km 62 lami'), ('No rain', 'Hakuna mvua'), ('~1 h 20', '~saa 1 dak 20')), 'riskLevel': 'ok'},
           'T:Kitale>s4': {'min': 25, 'km': 15, 'src': 'trip', 'chips': CH(('15 km tarmac', 'km 15 lami'), ('Rain 25 mm', 'Mvua mm 25'), ('~25 min', '~dak 25')), 'riskLevel': 'ok', 'roadLevel': 'heavy'},
           's4>s5': {'min': 150, 'km': 70, 'src': 'trip', 'chips': CH(('62 km tarmac · 8 km murram', 'km 62 lami · km 8 murram'), ('Rain 25 mm', 'Mvua mm 25'), ('~2 h 30', '~saa 2 dak 30')), 'riskLevel': 'difficult', 'roadLevel': 'heavy',
                     'warnings': [{'code': 'MURRAM_HEAVY_RAIN', 'level': 'difficult', 'en': 'Last 8 km is murram and 25 mm of rain is forecast; allow about 20 min extra or take a 4x4', 'sw': 'Kilomita 8 za mwisho ni murram na mvua ya mm 25 inatarajiwa; ongeza takriban dakika 20 au tumia gari la 4x4'}]},
           's5>E': {'min': 120, 'km': 95, 'src': 'trip', 'chips': CH(('95 km tarmac', 'km 95 lami'), ('Rain 25 mm', 'Mvua mm 25'), ('~2 h', '~saa 2')), 'riskLevel': 'ok'}},
  'cost': {'currency': 'KES', 'fuel': {'litres': 31.2, 'lPer100': 10.4, 'basis': {'en': 'Toyota Hilux 2.4 diesel pickup · model 9.0 L/100 km, murram +25%, 2 people +2%', 'sw': 'Toyota Hilux 2.4 dizeli pick-up · modeli L 9.0/100 km, murram +25%, watu 2 +2%'},
           'price': {'kes': 171.47, 'fuel': 'diesel', 'town': 'Eldoret', 'source': 'EPRA', 'from': '2026-10-15', 'to': '2026-11-14', 'kind': 'official'}, 'kes': 5350, 'reason': None,
           'label': {'en': 'Fuel: KES 171.47/L diesel, Eldoret, EPRA 15 Oct–14 Nov', 'sw': 'Mafuta: KES 171.47/L dizeli, Eldoret, EPRA 15 Okt–14 Nov'}},
           'days': [{'date': day(0), 'km': 142, 'totalKes': 14620}, {'date': day(1), 'km': 180, 'totalKes': 10134}],
           'est': {'fuelKes': 5350, 'lunchKes': 2000, 'perDiemKes': 6000, 'lodgingKes': 4500, 'extrasKes': 400, 'totalKes': 18250},
           'actual': {'fuelKes': 4980, 'fuelLitres': 29.0, 'extrasKes': 200, 'lodgingKes': 4500, 'totalKes': 9680, 'logs': 1}},
  'extras': [{'xid': 'x1', 'day': 0, 'kind': 'parking', 'label': 'Parking in Eldoret', 'kes': 200, 'actual': True}, {'xid': 'x2', 'day': 1, 'kind': 'toll', 'label': 'County cess', 'kes': 200, 'actual': False}],
  'packing': [{'key': 'auto:strips', 'auto': True, 'done': True, 'en': 'Water test strips (2 sites)', 'sw': 'Vipande vya kupima maji (maeneo 2)', 'why': ['108', '104']},
              {'key': 'auto:parts:101', 'auto': True, 'done': False, 'en': 'Parts for Kapsaret Borehole: pump handle, chain', 'sw': 'Vipuri vya Kapsaret Borehole: mkono wa pampu, mnyororo', 'why': ['101']},
              {'key': 'auto:consent', 'auto': True, 'done': False, 'en': 'Consent forms (1 site with story moments)', 'sw': 'Fomu za idhini (eneo 1 lenye hadithi)'},
              {'key': 'std:firstaid', 'auto': True, 'done': True, 'en': 'First aid kit', 'sw': 'Sanduku la huduma ya kwanza'},
              {'key': 'std:charger', 'auto': True, 'done': False, 'en': 'Phone charger and power bank', 'sw': 'Chaja ya simu na power bank'},
              {'key': 'std:offline', 'auto': True, 'done': False, 'en': 'Offline map saved for this trip', 'sw': 'Ramani ya nje ya mtandao imehifadhiwa kwa safari hii'},
              {'key': 'u:1', 'auto': False, 'done': False, 'en': 'Spare tyre checked'}],
  'share': None, 'created': iso(48), 'updated': iso(2), 'by': 'Reid Anderson'}
# the timeline per day, worked out with the app's own schedule rule (itw-core) so the mock and the app agree
html = open(os.path.join(HERE, '..', '..', 'pin.html'), encoding='utf8').read()
core = html[html.index('// itw-core:start'):html.index('// itw-core:end')]
r = subprocess.run(['node', '-e', core + '\nconst it = JSON.parse(require("fs").readFileSync(0, "utf8")); ITC.retime(it); it.days.forEach(d => { delete d.local; d.warnings = d.warnings.filter(w => !w.local); }); process.stdout.write(JSON.stringify(it));'], input=json.dumps(IT), capture_output=True, text=True)
keep = [d['warnings'] for d in IT['days']]; IT = json.loads(r.stdout)
for d, w in zip(IT['days'], keep): d['warnings'] = w
PROG = {'day': 0, 'now': '11:05', 'stops': [{'sid': 's1', 'state': 'done', 'planned': '07:55', 'at': '08:02', 'by': 'Meril Kiprop'}, {'sid': 's2', 'state': 'late', 'planned': '09:10', 'at': None, 'lateMin': 25},
        {'sid': 's3', 'state': 'planned', 'planned': '10:40'}, {'sid': 's4', 'state': 'planned'}, {'sid': 's5', 'state': 'planned'}],
        'where': [{'name': 'Meril Kiprop', 'lat': 0.62, 'lng': 35.29, 'at': iso(0.1), 'acc': 12}]}
IDX = [{'id': 'it_moiben', 'title': IT['title'], 'owner': 'Reid Anderson', 'people': IT['people'], 'from': day(0), 'to': day(1), 'status': 'active', 'ck': 'ke'},
       {'id': 'it_kitale', 'title': 'Kitale school water tests', 'owner': 'Grace Achieng', 'people': ['Grace Achieng'], 'from': day(4), 'to': day(6), 'status': 'planned', 'ck': 'ke'},
       {'id': 'it_nandi', 'title': 'Nandi follow-ups', 'owner': 'Meril Kiprop', 'people': ['Meril Kiprop', 'Grace Achieng', 'Bertin Mwangi'], 'from': day(-9), 'to': day(-8), 'status': 'done', 'ck': 'ke'}]
SUG = {'ok': True, 'areas': ['Kapsaret', 'Moiben', 'Ziwa', 'Bungoma', 'Kapsabaot', 'Nandi'], 'items': [
  {'id': '101', 'name': 'Kapsaret Borehole', 'area': 'Kapsaret', 'type': 'borehole', 'score': 98, 'why': [{'code': 'urgent', 'en': 'open repair · urgent', 'sw': 'matengenezo yaliyo wazi · ya dharura'}, {'code': 'community', 'en': 'community report 2 days ago', 'sw': 'ripoti ya jamii siku 2 zilizopita'}]},
  {'id': '108', 'name': 'Ziwa Borehole', 'area': 'Ziwa', 'type': 'borehole', 'score': 71, 'why': [{'code': 'overdue', 'en': 'due a visit · 8 months', 'sw': 'ziara inahitajika · miezi 8'}, {'code': 'test', 'en': 'water test due', 'sw': 'kipimo cha maji kinahitajika'}]},
  {'id': '104', 'name': 'Nzoia Bridge Primary School - 6 Classrooms and a Water Tank', 'area': 'Bungoma', 'type': 'school', 'score': 55, 'why': [{'code': 'never', 'en': 'never visited with the app', 'sw': 'haijawahi kutembelewa kwa programu'}]},
  {'id': '113', 'name': 'Kiptoo Primary School', 'area': 'Nandi', 'type': 'school', 'score': 40, 'why': [{'code': 'assigned', 'en': 'assigned to you', 'sw': 'umepewa wewe'}]}]}
VEH = [{'vid': 'vh_hilux', 'name': 'Hilux KDA 123X', 'plate': 'KDA 123X', 'type': 'pickup', 'cc': 2400, 'fuel': 'diesel', 'lPer100': None, 'owner': None, 'seats': 5,
        'model': {'lPer100': 9.0, 'base': 10.6, 'fuelFactor': 0.85, 'band': '2001–2500 cc'}, 'real': {'lPer100': 10.9, 'logs': 4, 'km': 1830}, 'use': {'lPer100': 10.1, 'src': 'blend', 'weight': 0.57}},
       {'vid': 'vh_bike', 'name': 'Meril’s motorbike', 'plate': 'KMDB 456Y', 'type': 'motorbike', 'cc': 150, 'fuel': 'petrol', 'owner': 'Meril Kiprop', 'model': {'lPer100': 2.6, 'band': '126–200 cc'}, 'real': None, 'use': {'lPer100': 2.6, 'src': 'model'}}]
FUEL = {'ke': {'ck': 'ke', 'source': 'EPRA', 'sourceName': 'Energy and Petroleum Regulatory Authority', 'url': 'https://www.epra.go.ke/', 'from': '2026-10-15', 'to': '2026-11-14', 'fetchedAt': iso(5), 'kind': 'official', 'currency': 'KES',
               'towns': {'Nairobi': {'petrol': 184.52, 'diesel': 171.47}, 'Eldoret': {'petrol': 185.84, 'diesel': 172.78}, 'Kitale': {'petrol': 186.62, 'diesel': 173.57}, 'Kisumu': {'petrol': 185.6, 'diesel': 172.55}},
               'overrides': {'Eldoret': {'petrol': 189.84, 'diesel': 177.0, 'at': iso(30), 'by': 'Jessica'}}, 'status': {'at': iso(5), 'ok': True}},
        'tz': {'ck': 'tz', 'source': 'EWURA', 'from': '2026-10-01', 'to': '2026-10-31', 'fetchedAt': iso(20), 'kind': 'cached', 'currency': 'TZS', 'towns': {'Dar es Salaam': {'petrol': 2842, 'diesel': 2721}, 'Arusha': {'petrol': 2945, 'diesel': 2824}}, 'status': {'at': iso(3), 'ok': False, 'reason': 'parse'}},
        'ug': {'ck': 'ug', 'kind': 'none', 'currency': 'UGX', 'towns': {}, 'status': {'at': iso(3), 'ok': False, 'reason': 'no-source'}}}
GT = {'id': 'gt_july', 'v': 2, 'name': 'Kenya water trip, July 2027', 'from': '2027-07-03', 'to': '2027-07-14', 'country': 'ke', 'destination': 'Kitale, Trans Nzoia', 'summary': 'Two weeks with a drilling team and three schools.', 'public': True,
      'leaders': ['Jessica'], 'contact': 'info@rafikioutreach.org',
      'participants': [{'pid': 'p1', 'name': 'Sam Lee', 'email': 'sam@example.org', 'phone': '+1 555 0100', 'role': 'volunteer', 'diet': 'vegetarian', 'medical': 'asthma', 'passportReminder': True, 'passportExpiry': '2027-02'},
                       {'pid': 'p2', 'name': 'Ann Moss', 'email': 'ann@example.org', 'role': 'donor'}],
      'items': [{'iid': 'i1', 'date': '2027-07-03', 'start': '12:40', 'end': '13:40', 'kind': 'flight', 'title': 'Fly Nairobi to Eldoret', 'flight': {'airline': 'Kenya Airways', 'number': 'KQ 640', 'from': 'NBO', 'to': 'EDL', 'depart': '12:40', 'arrive': '13:40'}, 'remind': {'before': '24h', 'sentAt': None}},
                {'iid': 'i2', 'date': '2027-07-03', 'start': '14:00', 'kind': 'transfer', 'title': 'Airport pick-up', 'location': {'name': 'Eldoret International Airport', 'lat': 0.4045, 'lng': 35.2389}, 'remind': {'before': '3h'}},
                {'iid': 'i3', 'date': '2027-07-04', 'start': '08:00', 'end': '16:00', 'kind': 'project', 'title': 'Drilling day at Kapsaret', 'projectId': '101'}],
      'costs': [{'cid': 'c1', 'label': 'Lodging', 'amount': 450, 'currency': 'USD', 'per': 'person'}, {'cid': 'c2', 'label': 'Bus hire', 'amount': 180000, 'currency': 'KES', 'per': 'group'}],
      'payments': [{'payid': 'y1', 'pid': 'p1', 'amount': 1200, 'currency': 'USD', 'at': '2027-03-01'}],
      'packing': 'Sun hat, closed shoes for drilling days, headlamp.', 'expect': 'Long days outside; warm evenings in Kitale.', 'emergency': [{'name': 'Jessica', 'phone': '+254 700 000000', 'role': 'Trip leader'}],
      'totals': {'participants': 2, 'perPerson': {'byCurrency': {'USD': 450, 'KES': 90000}, 'USD': 1146.6, 'KES': 148140}, 'group': {'byCurrency': {'USD': 900, 'KES': 180000}, 'USD': 2293.2, 'KES': 296280},
                 'paid': {'p1': {'byCurrency': {'USD': 1200}, 'USD': 1200, 'KES': 155040}}, 'due': {'p1': {'USD': 0, 'KES': 0}, 'p2': {'USD': 1146.6, 'KES': 148140}}, 'fx': {'at': '2026-10-26', 'USDKES': 129.2}},
      'share': {'url': 'https://pin.rafikioutreach.org/pin-trip?t=abc', 'at': iso(30), 'by': 'Reid Anderson'}}

def snap():
    return {'ok': True, 'who': 'Reid Anderson', 'admin': True, 'reviewOpen': 0, 'projects': P, 'repairs': REP, 'visits': {}, 'tests': {}, 'jobs': [], 'assigned': [], 'confirmed': {}, 'me': {'kind': 'app', 'nick': '', 'phone': '', 'home': 'ke'}, 'approveAbove': 50000}
def install(pg):
    J = lambda r, d, s=200: r.fulfill(status=s, json=d)
    def pin_api(route, req):
        u = req.url
        if req.method != 'GET': return J(route, {'ok': True, 'updateId': '9001'})
        if 'team=' in u: return J(route, {'ok': True, 'team': [{'name': t['name'], 'home': 'ke'} for t in TEAM]})
        if 'activity=' in u: return J(route, {'ok': True, 'activity': []})
        if re.search(r'[?&]id=\d+', u): return J(route, {'ok': True, 'last': None, 'canUndo': False, 'visits': [], 'costs': {'sum': 0, 'n': 0, 'last': []}})
        return J(route, snap())
    def admin_api(route, req):
        u = req.url
        if 'view=team' in u: return J(route, {'ok': True, 'team': TEAM, 'mail': {'ready': True, 'to': []}, 'cron': True})
        return J(route, {'ok': True, 'items': [], 'rows': [], 'people': [], 'visits': [], 'donors': []})
    def itin(route, req):
        u = req.url; path = u.split('/api/itin')[1].split('?')[0]
        if req.method == 'GET':
            if 'alerts=1' in u: return J(route, {'ok': True, 'alerts': [{'at': iso(0.3), 'id': 'it_moiben', 'title': IT['title'], 'by': 'Meril Kiprop', 'kind': 'late', 'en': 'Ziwa Borehole is 25 min late', 'sw': 'Ziwa Borehole imechelewa dak 25'}]})
            if 'settings=1' in u: return J(route, {'ok': True, 'settings': {'lunchKes': 500, 'perDiemKes': 1500, 'lodgingKes': 3500, 'hours': {'start': '07:30', 'end': '17:30'}, 'lunch': {'at': '12:30', 'min': 45}}})
            if 'card=1' in u: return J(route, {'ok': True, 'card': {'title': IT['title'], 'stops': 5}})
            if path.startswith('/'): return J(route, {'ok': True, 'itin': IT, 'can': {'edit': True, 'admin': True}, 'progress': PROG})
            return J(route, {'ok': True, 'items': IDX, 'admin': True})
        b = json.loads(req.post_data or '{}')
        if path == '/suggest': return J(route, SUG)
        if path == '/optimise': return J(route, {'ok': True, 'plan': dict(IT, id=None, status='draft', share=None)})
        if path == '/settings': return J(route, {'ok': True, 'settings': b})
        a = b.get('action')
        if a == 'share': return J(route, {'ok': True, 'url': 'https://pin.rafikioutreach.org/pin-trip?t=Xy3kP0aLm9QwErTyUiOp1aSdFgHjKlZxCvBnMqWeRtY', 'token': 'x'})
        POSTS.append(b)
        if a == 'stops':   # like the server: the new order and edits, re-timed (here by the app itself: no timeline in the answer)
            n = json.loads(json.dumps(IT)); by = {x['sid']: x for x in n['stops']}
            for d, nd in zip(n['days'], b.get('days', [])): d['stops'] = nd['stops']; d.pop('timeline', None)
            for e in b.get('stops', []): by.get(e['sid'], {}).update(e)
            n['v'] = IT['v'] + 1; return J(route, {'ok': True, 'itin': n, 'can': {'edit': True, 'admin': True}})
        if a == 'replan': return J(route, {'ok': True, 'preview': True, 'day': 0, 'keep': ['s1', 's2'], 'move': ['s3'], 'endsAt': '17:10'} if b.get('mode') == 'preview' else {'ok': True, 'itin': IT, 'moved': ['s3'], 'dropped': []})
        if a == 'summary': return J(route, {'ok': True, 'itin': dict(IT, summary={'visited': [{'sid': 's1', 'id': '101', 'name': 'Kapsaret Borehole', 'at': iso(3)}], 'skipped': [], 'km': {'value': 312, 'src': 'gps', 'planned': 298}, 'cost': {'estKes': 18250, 'actualKes': 9680, 'fuelLitres': 29.0}, 'problems': [], 'repairsOpened': 1, 'waterTests': 2})})
        return J(route, {'ok': True, 'itin': IT})
    def fuel(route, req):
        u = req.url
        if 'prices=1' in u: return J(route, {'ok': True, 'countries': FUEL})
        if 'itin=' in u or 'logs=' in u: return J(route, {'ok': True, 'logs': [{'lid': 'fl1', 'vid': 'vh_hilux', 'at': iso(4), 'by': 'Meril Kiprop', 'litres': 29.0, 'kesPerL': 171.72, 'totalKes': 4980, 'odometer': 120455, 'town': 'Eldoret'}], 'real': VEH[0]['real']})
        return J(route, {'ok': True})
    def groups(route, req):
        u = req.url
        if req.method == 'GET' and '/api/group-trips/' in u: return J(route, {'ok': True, 'trip': GT})
        if req.method == 'GET': return J(route, {'ok': True, 'items': [{'id': GT['id'], 'name': GT['name'], 'from': GT['from'], 'to': GT['to'], 'country': 'ke', 'participants': 2, 'public': True}]})
        b = json.loads(req.post_data or '{}')
        if b.get('action') == 'remind': return J(route, {'ok': True, 'preview': {'subject': 'Tomorrow: KQ 640 to Eldoret', 'text': 'Hello! Your flight KQ 640 leaves Nairobi at 12:40 tomorrow. Check in online 24 hours before.\n\nJessica, RAFIKI'}, 'to': 2, 'missing': 0})
        return J(route, {'ok': True, 'trip': GT})
    pg.route('**/api/pin-auth/me', lambda r, q: r.fulfill(json={'available': False}))
    pg.route(re.compile(r'/api/pin(\?.*)?$'), pin_api); pg.route(re.compile(r'/api/admin(\?.*)?$'), admin_api)
    pg.route(re.compile(r'/api/itin(/.*)?(\?.*)?$'), itin); pg.route(re.compile(r'/api/vehicles(\?.*)?$'), lambda r, q: J(r, {'ok': True, 'vehicles': VEH, 'admin': True}))
    pg.route(re.compile(r'/api/fuel(\?.*)?$'), fuel); pg.route(re.compile(r'/api/group-trips(/.*)?(\?.*)?$'), groups)
    pg.route(re.compile(r'/api/trip/week'), lambda r, q: J(r, {'ok': True, 'bestDay': {'date': day(2), 'roadLevel': 'dry'}, 'days': [{'date': day(1), 'roadLevel': 'heavy'}]}))
    pg.route(re.compile(r'/api/(place|sms-admin|road-reports|fx)(\?.*)?$'), lambda r, q: J(r, {'ok': True, 'places': [], 'rates': {}}))
    pg.route(re.compile(r'https://(www\.google\.com/s2|fonts\.googleapis\.com)/.*'), lambda r, q: r.abort())

# words split across lines, children of a row on top of each other, sideways scrolling
CHECK = r"""(root) => {
  const R = document.querySelector(root) || document.body, out = { broken: [], overlap: [], wide: [] };
  const vis = e => { const s = getComputedStyle(e), b = e.getBoundingClientRect(); return s.display !== 'none' && s.visibility !== 'hidden' && b.width > 1 && b.height > 1; };
  const w = document.createTreeWalker(R, NodeFilter.SHOW_TEXT); let t;
  while ((t = w.nextNode())) {
    const p = t.parentElement; if (!p || !vis(p) || p.closest('.leaflet-container,script,style,option,select,textarea,input,.vh')) continue;
    const re = /[^\s ]+/g; let m;
    while ((m = re.exec(t.data))) {
      const r = document.createRange(); r.setStart(t, m.index); r.setEnd(t, m.index + m[0].length);
      const rs = [...r.getClientRects()].filter(x => x.width > 0.5);
      if (rs.length > 1 && Math.abs(rs[0].top - rs[rs.length - 1].top) > 3) out.broken.push(m[0]);
    }
  }
  const S = '.itw-acts,.itw-cw,.itw-nb,.itw-nb2,.itw-days,.itw-tl>li,.itw-row,.itw-sg,.itw-xl li,.itw-w,.itw-fch,.itw-two,.itw-f2,.itw-sh3,.itw-unr,.mmh,.dqm-hact,.itw-pr,.itw-sa';
  R.querySelectorAll(S).forEach(c => { if (!vis(c)) return; const k = [...c.children].filter(vis).map(e => [e, e.getBoundingClientRect()]);
    for (let i = 0; i < k.length; i++) for (let j = i + 1; j < k.length; j++) { const a = k[i][1], b = k[j][1], x = Math.min(a.right, b.right) - Math.max(a.left, b.left), y = Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top);
      if (x > 2 && y > 2) out.overlap.push((c.className || c.tagName) + ': ' + (k[i][0].textContent || k[i][0].className).trim().slice(0, 24) + ' × ' + (k[j][0].textContent || k[j][0].className).trim().slice(0, 24)); } });
  // text wider than its own box (a long word pushed out under the next thing)
  R.querySelectorAll('b,small,h2,h3,h4,p,label,.itw-rt,.itw-sb,.itw-smt,.dqm-pill,.itw-chip,.btn,.chip').forEach(e => { if (!vis(e) || e.closest('.leaflet-container')) return; const s = getComputedStyle(e);
    if (s.display !== 'inline' && s.overflow === 'visible' && s.textOverflow !== 'ellipsis' && e.scrollWidth > e.clientWidth + 2) out.overlap.push('clip: ' + e.textContent.trim().slice(0, 30)); });
  if (document.documentElement.scrollWidth > innerWidth + 1) out.wide.push('page ' + document.documentElement.scrollWidth);
  R.querySelectorAll('.bsh-p,.card,.itw-tl,.itw-tb').forEach(e => { if (vis(e) && e.scrollWidth > e.clientWidth + 2 && getComputedStyle(e).overflowX !== 'auto') out.wide.push((e.className || '').slice(0, 30) + ' ' + e.scrollWidth + '>' + e.clientWidth); });
  return out; }"""

RES = []; ERRS = []; POSTS = []
def context(br, lang, width, theme):
    mob = width < 700
    ctx = br.new_context(viewport={'width': width, 'height': 844 if mob else 900}, is_mobile=mob, has_touch=mob, device_scale_factor=2 if mob else 1, color_scheme='dark' if theme == 'dark' else 'light', service_workers='block', locale='en-GB', timezone_id='Africa/Nairobi',
                         geolocation={'latitude': 0.62, 'longitude': 35.29, 'accuracy': 8}, permissions=['geolocation'])
    ctx.route(re.compile(r'https://(tile\.openstreetmap\.org|[abc]\.tile\.openstreetmap\.org|server\.arcgisonline\.com)/.*'), lambda r, q: r.fulfill(status=200, body=TILE, headers={'Content-Type': 'image/png', 'Access-Control-Allow-Origin': '*'}))
    pg = ctx.new_page(); install(pg)
    pg.on('pageerror', lambda e: ERRS.append('pageerror: ' + str(e)[:300]))
    pg.on('console', lambda m: ERRS.append('console: ' + m.text[:200]) if m.type == 'error' and 'Failed to load resource' not in m.text else None)
    pg.goto(BASE + '/pin'); pg.wait_for_timeout(600)
    pg.evaluate("([l, t]) => { localStorage.setItem('rfPinOnb', '1'); localStorage.setItem('rfPinName', 'Reid Anderson'); if (l === 'sw') localStorage.setItem('rfPinLang', 'sw'); if (t !== 'light') localStorage.setItem('rfPinTheme', t); }", [lang, theme])
    pg.reload(); pg.wait_for_timeout(700)
    for d in '9999': pg.click('.pk[data-k="%s"]' % d)
    pg.wait_for_timeout(1500)
    return ctx, pg

def look(pg, name, tag, root='body', full=False):
    pg.wait_for_timeout(450)
    fn = os.path.join(OUT, '%s-%s.png' % (tag, name)); pg.screenshot(path=fn, full_page=full)
    r = pg.evaluate(CHECK, root); bad = {k: sorted(set(v))[:8] for k, v in r.items() if v}
    RES.append((tag, name, bad)); print(('OK   ' if not bad else 'BAD  ') + tag + ' ' + name + ('' if not bad else ' :: ' + json.dumps(bad, ensure_ascii=False)), flush=True)
def sheet_scroll(pg, sel):
    pg.evaluate("s => { const e = document.querySelector(s); if (!e) return; const p = e.closest('.bsh-p'); p.scrollTop = e.getBoundingClientRect().top - p.getBoundingClientRect().top + p.scrollTop - 60; }", sel); pg.wait_for_timeout(300)
def top_sheet(): return '.bsh.in:last-of-type'
def close_sheets(pg):
    for _ in range(4):
        if not pg.locator('.bsh').count(): break
        pg.evaluate('history.back()'); pg.wait_for_timeout(500)

def act(pg, tag):
    notes = []; ck = lambda c, w: None if c else notes.append(w)
    pg.evaluate("location.hash = '#office/trips'"); pg.wait_for_timeout(1500)
    pg.click('[data-itwopen="it_moiben"]'); pg.wait_for_timeout(1300)
    pg.click('.itw-acts [data-v=cost]'); pg.wait_for_timeout(800)
    pg.evaluate('history.back()'); pg.wait_for_timeout(700)
    ck(pg.locator('.bsh').count() == 1 and pg.locator('.bsh.itw-trip').count() == 1, 'Back closes Costs only, the trip stays')
    # drag the first stop of day 1 below the second: the order and the times change at once
    first = pg.evaluate("[...document.querySelectorAll('.itw-tl .itw-st')].map(e => e.dataset.sid)")
    g = pg.locator('.itw-tl .itw-st').nth(0).locator('.itw-grip'); g.scroll_into_view_if_needed(); b1 = g.bounding_box(); b2 = pg.locator('.itw-tl .itw-st').nth(1).bounding_box()
    pg.mouse.move(b1['x'] + b1['width'] / 2, b1['y'] + b1['height'] / 2); pg.mouse.down(); pg.mouse.move(b1['x'] + 10, b2['y'] + b2['height'] * 0.85, steps=12); pg.mouse.up(); pg.wait_for_timeout(600)
    after = pg.evaluate("[...document.querySelectorAll('.itw-tl .itw-st')].map(e => e.dataset.sid)")
    ck(after[:2] == [first[1], first[0]], 'drag reorders the day (%s → %s)' % (first, after))
    ck(any(p.get('action') == 'stops' for p in POSTS), 'the new order is saved (stops)')
    # lock a stop's time
    pg.locator('.itw-tl [data-lock]').nth(1).click(); pg.wait_for_timeout(400)
    ck(pg.locator('.itw-tl [data-lock]').nth(1).get_attribute('aria-pressed') == 'true', 'the lock holds the time')
    ck(any(p.get('action') == 'stops' and any(x.get('locked') for x in p.get('stops', [])) for p in POSTS), 'the lock is saved')
    # one-tap fix on day 2: swap the rainy day
    pg.click('.itw-days [data-day="1"]'); pg.wait_for_timeout(400); pg.locator('[data-fix]').first.click(); pg.wait_for_timeout(600)
    ck(any(p.get('action') == 'swap-days' for p in POSTS), 'Swap sends swap-days')
    pg.evaluate('history.back()'); pg.wait_for_timeout(700)
    ck(pg.locator('.bsh').count() == 0 and pg.evaluate('location.hash') == '#office/trips', 'Back closes the trip, still on Office › Trips (%s)' % pg.evaluate('location.hash'))
    # check in from Me: the visit flow for that project
    pg.evaluate("location.hash = '#me'"); pg.wait_for_timeout(1800)
    nx = pg.locator('#itwNext [data-nx=checkin]'); ck(nx.count() == 1, 'Me shows the Next stop card')
    if nx.count(): nx.scroll_into_view_if_needed(); nx.click(); pg.wait_for_timeout(1800); ck(pg.evaluate('location.hash') == '#p/108/visit', 'Check in opens Report a visit for the project (%s)' % pg.evaluate('location.hash'))
    ck(any(p.get('action') == 'checkin' and p.get('sid') == 's2' for p in POSTS), 'the check-in is sent')
    RES.append((tag, 'behaviour', {'fail': notes} if notes else {})); print(('OK   ' if not notes else 'BAD  ') + tag + ' behaviour' + ('' if not notes else ' :: ' + ' | '.join(notes)), flush=True)

def run(pg, tag):
    want = lambda n: not ONLY or n in ONLY.split(',')
    pg.evaluate("location.hash = '#office/trips'"); pg.wait_for_timeout(1800)
    if want('office'): look(pg, 'office-trips', tag, '#vOffice')
    pg.click('[data-itwopen="it_moiben"]'); pg.wait_for_timeout(1500)
    if want('trip'): look(pg, 'trip-top', tag, '.bsh.itw-trip')
    sheet_scroll(pg, '#itwDay')
    if want('trip'): look(pg, 'trip-day1', tag, '.bsh.itw-trip')
    pg.click('.itw-days [data-day="1"]'); pg.wait_for_timeout(500); sheet_scroll(pg, '.itw-days')
    if want('trip'): look(pg, 'trip-day2-warnings', tag, '.bsh.itw-trip')
    if want('cost'):
        pg.click('.itw-acts [data-v=cost]'); pg.wait_for_timeout(1000); look(pg, 'costs', tag, '.bsh:last-of-type'); pg.evaluate('history.back()'); pg.wait_for_timeout(600)
    if want('pack'):
        pg.click('.itw-acts [data-v=pack]'); pg.wait_for_timeout(800); look(pg, 'packing', tag, '.bsh:last-of-type'); pg.evaluate('history.back()'); pg.wait_for_timeout(600)
    if want('share'):
        pg.click('.itw-acts [data-v=share]'); pg.wait_for_timeout(600); pg.click('[data-sh=make]'); pg.wait_for_timeout(900); look(pg, 'share', tag, '.bsh:last-of-type'); pg.evaluate('history.back()'); pg.wait_for_timeout(600)
    if want('replan'):
        pg.click('.itw-days [data-day="0"]'); pg.wait_for_timeout(400)
        b = pg.locator('.bsh.itw-trip [data-nx=late]')
        if b.count(): b.first.click(); pg.wait_for_timeout(1000); look(pg, 'replan', tag, '.bsh:last-of-type'); pg.evaluate('history.back()'); pg.wait_for_timeout(600)
    close_sheets(pg)
    if want('plan'):
        pg.evaluate("localStorage.setItem('rfPinItinDraft', JSON.stringify({ title: '', ck: 'ke', stops: ['101', '108'], areas: [], from: '%s', to: '%s', people: ['Reid Anderson', 'Meril Kiprop'], start: { kind: 'office' }, end: { kind: 'same' }, hours: null, vehicleId: 'vh_hilux', passengers: 2 }))" % (day(1), day(2)))
        pg.click('[data-testid=trips_plan]'); pg.wait_for_timeout(900); pg.click('[data-testid=plan_suggest]'); pg.wait_for_timeout(1000)
        look(pg, 'plan', tag, '.bsh:last-of-type'); sheet_scroll(pg, '#itwPSug'); look(pg, 'plan-suggest', tag, '.bsh:last-of-type')
        pg.click('[data-testid=plan_build]'); pg.wait_for_timeout(1500); look(pg, 'plan-preview', tag, '.bsh:last-of-type')
        close_sheets(pg)
    if want('vehicles'):
        pg.evaluate("location.hash = '#office/vehicles'"); pg.wait_for_timeout(1500); look(pg, 'vehicles', tag, '#vOffice')
        pg.evaluate("document.getElementById('itwFp').scrollIntoView()"); pg.wait_for_timeout(300); look(pg, 'fuel-prices', tag, '#vOffice')
        pg.click('[data-veh=vh_hilux]'); pg.wait_for_timeout(700); look(pg, 'vehicle', tag, '.bsh:last-of-type'); close_sheets(pg)
    if want('group'):
        pg.evaluate("location.hash = '#office/trips'"); pg.wait_for_timeout(1500); pg.click('[data-itwseg=group]'); pg.wait_for_timeout(900)
        look(pg, 'group-list', tag, '#vOffice')
        pg.click('[data-gopen=gt_july]'); pg.wait_for_timeout(900)
        pg.evaluate("document.querySelectorAll('.itw-gs').forEach(d => { if (['days','cost','ppl'].includes(d.dataset.gs)) d.open = true; })"); pg.wait_for_timeout(300)
        look(pg, 'group-trip', tag, '.bsh:last-of-type'); sheet_scroll(pg, '[data-gs=cost]'); look(pg, 'group-costs', tag, '.bsh:last-of-type')
        pg.click('[data-gp=p1]'); pg.wait_for_timeout(600); look(pg, 'group-person', tag, '.bsh:last-of-type'); close_sheets(pg)
    if want('donor'):   # donor mode: the group trip they are on, with its page
        pg.route(re.compile(r'/api/donor(\?.*)?$'), lambda r, q: r.fulfill(json={'ok': True, 'donor': {'name': 'Ann Moss', 'viewer': 'Ann Moss'}, 'totals': {'projects': 1, 'water': 1, 'people': 1200, 'visitsYear': 3}, 'projects': [], 'updates': [],
            'groupTrips': [{'name': GT['name'], 'from': GT['from'], 'to': GT['to'], 'destination': GT['destination'], 'summary': GT['summary'], 'url': 'https://pin.rafikioutreach.org/pin-trip?t=abc'}]}))
        pg.evaluate("localStorage.setItem('rfPinDonor', '" + 'D' * 43 + "'); localStorage.removeItem('rfPinDonorData')"); pg.goto(BASE + '/pin-donor'); pg.wait_for_timeout(2200)
        look(pg, 'donor-group-trip', tag, 'main'); pg.evaluate("localStorage.removeItem('rfPinDonor'); localStorage.removeItem('rfPinDonorData')"); pg.goto(BASE + '/pin'); pg.wait_for_timeout(2500)
    if want('me'):
        pg.evaluate("location.hash = '#me'"); pg.wait_for_timeout(1800); pg.evaluate("document.getElementById('meTrips').scrollIntoView()"); pg.wait_for_timeout(800)
        look(pg, 'me-next-stop', tag, '#vMe')

with sync_playwright() as p:
    br = p.chromium.launch()
    for lang in LANGS:
        for wd in WIDTHS:
            for th in THEMES:
                tag = '%s-%d-%s' % (lang, wd, th); del ERRS[:]
                ctx, pg = context(br, lang, wd, th)
                try:
                    if wd == WIDTHS[0] and th == THEMES[0] and (not ONLY or 'behaviour' in ONLY): act(pg, tag); pg.goto(BASE + '/pin#office/trips'); pg.wait_for_timeout(1500)
                    run(pg, tag)
                except Exception as e: RES.append((tag, 'run', {'error': [str(e).split('\n')[0][:300]]})); print('ERR  ' + tag + ' ' + str(e).split('\n')[0][:300], flush=True)
                if ERRS: RES.append((tag, 'console', {'errors': ERRS[:5]})); print('ERR  ' + tag + ' console ' + json.dumps(ERRS[:5]), flush=True)
                ctx.close()
    br.close()
bad = [r for r in RES if r[2]]
print('\n%d of %d screens clean' % (len(RES) - len(bad), len(RES)))
sys.exit(1 if bad else 0)
