#!/usr/bin/env python3
"""Makes a small SYNTHETIC test area for the country-map pipeline, so the build and the apps can be
tested without downloading a real country.

It is not a real map: places, roads and water points are made up around a few real town positions
(Eldoret, Nairobi, Kisumu, Nakuru, Mombasa). Output, in the folder given (default ./data/sources):
  test.osm.pbf                    an OpenStreetMap PBF file (what Geofabrik serves for a country)
  water-polygons-split-4326.zip   a tiny stand-in for the sea outlines from osmdata.openstreetmap.de

Then:  java -jar planetiler.jar generate-custom --schema=rafiki-country.yml --area=test --output=test.pmtiles
Only the Python standard library is used.
"""
import math, os, random, struct, sys, zipfile, zlib, io

OUT = sys.argv[1] if len(sys.argv) > 1 else 'data/sources'
rnd = random.Random(7)

# --- protobuf, by hand ------------------------------------------------------------------------------
def varint(n):
    out = bytearray()
    while True:
        b = n & 0x7F; n >>= 7
        if n: out.append(b | 0x80)
        else: out.append(b); return bytes(out)
def zz(n): return (n << 1) ^ (n >> 63)
def key(f, wt): return varint((f << 3) | wt)
def f_bytes(f, b): return key(f, 2) + varint(len(b)) + b
def f_int(f, n): return key(f, 0) + varint(n)
def f_sint(f, n): return key(f, 0) + varint(zz(n))
def packed(f, nums, signed=False): return f_bytes(f, b''.join(varint(zz(n) if signed else n) for n in nums))

# --- the made-up data -----------------------------------------------------------------------------------
nodes, ways, rels = [], [], []          # nodes: (id, lat, lon, tags); ways: (id, [refs], tags)
nid, wid = [1000], [5000]
def node(lat, lon, tags=None):
    nid[0] += 1; nodes.append((nid[0], lat, lon, tags or {})); return nid[0]
def way(pts, tags, closed=False):
    refs = [node(a, b) for a, b in pts]
    if closed: refs.append(refs[0])
    wid[0] += 1; ways.append((wid[0], refs, tags)); return wid[0]
def wiggle(a, b, n=8, amp=0.01):
    """a line from a to b with some bends"""
    pts = []
    for i in range(n + 1):
        t = i / n; j = 0 if i in (0, n) else rnd.uniform(-amp, amp)
        pts.append((a[0] + (b[0] - a[0]) * t + j, a[1] + (b[1] - a[1]) * t - j))
    return pts
def blob(c, r, n=14, rough=0.25):
    pts = []
    for i in range(n):
        an = 2 * math.pi * i / n; k = 1 + rnd.uniform(-rough, rough)
        pts.append((c[0] + r * k * math.sin(an), c[1] + r * k * math.cos(an) * 1.0))
    return pts

ELD = (0.5143, 35.2698)
towns = {'Nairobi': (-1.2864, 36.8172, 'city', '4397073', 'yes'), 'Mombasa': (-4.0435, 39.6682, 'city', '1208333', None),
         'Kisumu': (-0.0917, 34.7680, 'city', '397957', None), 'Nakuru': (-0.3031, 36.0800, 'city', '570674', None),
         'Eldoret': (0.5143, 35.2698, 'city', '475716', None), 'Turbo': (0.6330, 35.0500, 'town', None, None),
         'Burnt Forest': (0.2190, 35.4330, 'town', None, None), 'Iten': (0.6710, 35.5080, 'town', None, None)}
for n, (la, lo, kind, pop, cap) in towns.items():
    t = {'place': kind, 'name': n}
    if pop: t['population'] = pop
    if cap: t['capital'] = cap
    node(la, lo, t)
# main roads between the towns (shapes made up)
def T(n): return towns[n][:2]
way(wiggle(T('Mombasa'), T('Nairobi'), 30, 0.08), {'highway': 'trunk', 'ref': 'A109', 'name': 'Mombasa Road'})
way(wiggle(T('Nairobi'), T('Nakuru'), 20, 0.05), {'highway': 'trunk', 'ref': 'A104'})
way(wiggle(T('Nakuru'), T('Burnt Forest'), 14, 0.04), {'highway': 'trunk', 'ref': 'A104'})
way(wiggle(T('Burnt Forest'), T('Eldoret'), 10, 0.015), {'highway': 'trunk', 'ref': 'A104', 'name': 'Uganda Road'})
way(wiggle(T('Eldoret'), T('Turbo'), 10, 0.012), {'highway': 'trunk', 'ref': 'A104', 'name': 'Uganda Road'})
way(wiggle(T('Kisumu'), T('Nakuru'), 18, 0.05), {'highway': 'primary', 'ref': 'B1'})
way(wiggle(T('Eldoret'), T('Iten'), 10, 0.015), {'highway': 'primary', 'ref': 'C51', 'name': 'Iten Road'})
way(wiggle(T('Eldoret'), T('Kisumu'), 16, 0.04), {'highway': 'secondary', 'ref': 'C39'})
way(wiggle((-1.1, 36.65), (-0.5, 36.3), 12, 0.03), {'railway': 'rail', 'name': 'Uganda Railway'})
# a lake (part of Lake Victoria's shape, made simple) and a coast
way([(-0.05, 34.70), (-0.15, 34.74), (-0.30, 34.60), (-0.50, 34.35), (-0.70, 34.10), (-0.40, 34.00), (-0.10, 34.10), (0.05, 34.30), (-0.05, 34.55)],
    {'natural': 'water', 'name': 'Lake Victoria'}, closed=True)
# the country (a rough outline) and a county
way([(4.6, 33.9), (4.6, 41.0), (-1.6, 41.6), (-4.7, 39.2), (-1.0, 34.0), (1.0, 34.2), (4.6, 33.9)][:-1], {'boundary': 'administrative', 'admin_level': '2', 'name': 'Kenya (test outline)'}, closed=True)
way([(0.95, 35.0), (0.95, 35.6), (0.2, 35.6), (0.2, 35.0)], {'boundary': 'administrative', 'admin_level': '4', 'name': 'Test County'}, closed=True)

# around Eldoret: villages, hamlets, roads, tracks, paths, rivers, a dam, a forest, schools, clinics, churches, water points
VIL = ['Kapsaret', 'Chepkongi', 'Kaptagat', 'Ziwa', 'Moiben', 'Kesses', 'Kapkoi', 'Tarakwa', 'Kipkabus', 'Kimumu', 'Huruma', 'Langas',
       'Kapteldon', 'Soy', 'Matunda', 'Ngeria', 'Plateau', 'Kamagut', 'Megun', 'Kuinet', 'Sergoit', 'Cheptiret', 'Kaplelach', 'Ainabkoi']
HAM = ['Kapchumba', 'Chebarus', 'Tulwet', 'Kiplombe', 'Sambut', 'Kokwet', 'Lelmolok', 'Kaptuiyo', 'Simat', 'Chepsaita', 'Kabomoi', 'Lolkinyei',
       'Kapyemit', 'Kiboswa', 'Kosachei', 'Mosop', 'Kaptinga', 'Sugoi', 'Leseru', 'Chemalal']
vill = []
for i, n in enumerate(VIL):
    an = i * 2.399; r = 0.06 + 0.32 * ((i * 37) % 24) / 24
    p = (ELD[0] + r * math.sin(an), ELD[1] + r * math.cos(an)); vill.append(p)
    node(p[0], p[1], {'place': 'village', 'name': n})
ham = []
for i, n in enumerate(HAM):
    v = vill[i % len(vill)]; p = (v[0] + rnd.uniform(-0.03, 0.03), v[1] + rnd.uniform(-0.03, 0.03)); ham.append(p)
    node(p[0], p[1], {'place': 'hamlet', 'name': n})
# Eldoret's built-up area and some town streets
way(blob(ELD, 0.035, 18, 0.15), {'landuse': 'residential'}, closed=True)
for i in range(10):
    a = (ELD[0] - 0.03 + i * 0.006, ELD[1] - 0.035); b = (ELD[0] - 0.03 + i * 0.006, ELD[1] + 0.035)
    way(wiggle(a, b, 4, 0.001), {'highway': 'residential', 'name': 'Street %d' % (i + 1)})
for i in range(8):
    a = (ELD[0] - 0.032, ELD[1] - 0.03 + i * 0.008); b = (ELD[0] + 0.032, ELD[1] - 0.03 + i * 0.008)
    way(wiggle(a, b, 4, 0.001), {'highway': 'service' if i % 3 else 'residential'})
# each village joins the town or its nearest neighbour
for i, v in enumerate(vill):
    near = min([ELD] + vill[:i], key=lambda q: (q[0] - v[0]) ** 2 + (q[1] - v[1]) ** 2)
    way(wiggle(v, near, 8, 0.006), {'highway': 'tertiary' if i < 6 else 'unclassified', 'surface': 'unpaved' if i % 2 else 'asphalt'})
for i, h in enumerate(ham):
    way(wiggle(h, vill[i % len(vill)], 6, 0.004), {'highway': 'track'})
    way(wiggle(h, (h[0] + rnd.uniform(-0.02, 0.02), h[1] + rnd.uniform(-0.02, 0.02)), 5, 0.002), {'highway': 'path'})
# rivers and streams
riv = wiggle((0.95, 35.45), (0.05, 34.95), 40, 0.02)
way(riv, {'waterway': 'river', 'name': 'Sosiani River'})
for i in range(0, 36, 5):
    a = riv[i]; way(wiggle(a, (a[0] + rnd.uniform(-0.12, 0.12), a[1] + rnd.uniform(0.05, 0.15)), 8, 0.006), {'waterway': 'stream'})
way(blob((0.42, 35.36), 0.012, 12), {'natural': 'water', 'name': 'Kesses Dam'}, closed=True)
way(blob((0.30, 35.50), 0.025, 10), {'natural': 'wetland'}, closed=True)
way(blob((0.40, 35.48), 0.07, 16, 0.2), {'landuse': 'forest', 'name': 'Kaptagat Forest'}, closed=True)
way(blob((0.80, 35.15), 0.05, 14, 0.2), {'boundary': 'protected_area', 'name': 'Test Reserve'}, closed=True)
# schools (some as areas), clinics, a hospital, churches, a mosque, water points
for i, v in enumerate(vill):
    p = (v[0] + 0.004, v[1] + 0.003)
    if i % 3 == 0: way(blob(p, 0.0012, 6, 0.1), {'amenity': 'school', 'name': VIL[i] + ' Primary School'}, closed=True)
    else: node(p[0], p[1], {'amenity': 'school', 'name': VIL[i] + ' Primary School'})
    if i % 4 == 1: node(v[0] - 0.003, v[1] + 0.004, {'amenity': 'clinic', 'name': VIL[i] + ' Dispensary'})
    if i % 4 == 3: node(v[0] - 0.003, v[1] + 0.004, {'healthcare': 'centre', 'name': VIL[i] + ' Health Centre'})
    node(v[0] + 0.002, v[1] - 0.004, {'amenity': 'place_of_worship', 'religion': 'christian', 'name': ('AIC ' if i % 2 else 'St. Mary’s ') + VIL[i]})
    node(v[0] - 0.004, v[1] - 0.002, {'man_made': 'water_well', 'pump': 'manual', 'name': VIL[i] + ' Borehole'})
    if i % 2: node(v[0] + 0.005, v[1] - 0.001, {'amenity': 'water_point', 'name': VIL[i] + ' Water Kiosk'})
for i, h in enumerate(ham):
    node(h[0] + 0.002, h[1] + 0.002, {'man_made': 'water_well'})
    if i % 3 == 0: node(h[0] - 0.002, h[1] + 0.002, {'natural': 'spring'})
    if i % 4 == 0: node(h[0] + 0.002, h[1] - 0.002, {'amenity': 'school', 'name': HAM[i] + ' Primary'})
node(ELD[0] + 0.006, ELD[1] + 0.008, {'amenity': 'hospital', 'name': 'Eldoret Test Hospital'})
node(ELD[0] - 0.008, ELD[1] + 0.004, {'amenity': 'place_of_worship', 'religion': 'muslim', 'name': 'Eldoret Mosque'})
node(ELD[0] + 0.010, ELD[1] - 0.012, {'man_made': 'water_tap', 'name': 'Market Tap'})

# --- write the PBF ------------------------------------------------------------------------------------
def write_blob(f, kind, payload):
    z = zlib.compress(payload, 9)
    b = f_int(2, len(payload)) + f_bytes(3, z)
    h = f_bytes(1, kind.encode()) + f_int(3, len(b))
    f.write(struct.pack('>I', len(h))); f.write(h); f.write(b)

def block(group, strings):
    st = b''.join(f_bytes(1, s.encode()) for s in strings)
    return f_bytes(1, st) + f_bytes(2, group) + f_int(17, 100)

def strtab():
    s, ix = [''], {'': 0}
    def get(x):
        if x not in ix: ix[x] = len(s); s.append(x)
        return ix[x]
    return s, get

os.makedirs(OUT, exist_ok=True)
lat_all = [n[1] for n in nodes]; lon_all = [n[2] for n in nodes]
W, E, S, N = 33.9, 41.9, -4.8, 5.0
with open(os.path.join(OUT, 'test.osm.pbf'), 'wb') as f:
    bbox = f_sint(1, int(W * 1e9)) + f_sint(2, int(E * 1e9)) + f_sint(3, int(N * 1e9)) + f_sint(4, int(S * 1e9))
    write_blob(f, 'OSMHeader', f_bytes(1, bbox) + f_bytes(4, b'OsmSchema-V0.6') + f_bytes(4, b'DenseNodes') + f_bytes(16, b'rafiki make-test-data.py'))
    # nodes (dense)
    s, get = strtab(); ids, lats, lons, kv = [], [], [], []
    pid = plat = plon = 0
    for (i, la, lo, tags) in nodes:
        a, b = round(la * 1e7), round(lo * 1e7)
        ids.append(i - pid); lats.append(a - plat); lons.append(b - plon); pid, plat, plon = i, a, b
        for k, v in tags.items(): kv += [get(k), get(v)]
        kv.append(0)
    dense = packed(1, ids, True) + packed(8, lats, True) + packed(9, lons, True) + packed(10, kv)
    write_blob(f, 'OSMData', block(f_bytes(2, dense), s))
    # ways
    s, get = strtab(); g = b''
    for (i, refs, tags) in ways:
        ks = [get(k) for k in tags]; vs = [get(v) for v in tags.values()]
        d, prev = [], 0
        for r in refs: d.append(r - prev); prev = r
        g += f_bytes(3, f_int(1, i) + packed(2, ks) + packed(3, vs) + packed(8, d, True))
    write_blob(f, 'OSMData', block(g, s))

# --- the sea: a polygon shapefile (EPSG:4326) east of a made-up coast, zipped like osmdata's file ----------
coast = [(-4.8, 39.25), (-4.2, 39.6), (-3.6, 39.85), (-2.9, 40.1), (-2.2, 40.6), (-1.7, 41.2), (-1.4, 41.6)]
ring = [(lo, la) for la, lo in coast] + [(41.9, -1.4), (41.9, -4.8), (39.25, -4.8)]
ring = ring + [ring[0]]
if sum((ring[i + 1][0] - ring[i][0]) * (ring[i + 1][1] + ring[i][1]) for i in range(len(ring) - 1)) < 0: ring.reverse()  # outer ring clockwise
xs = [p[0] for p in ring]; ys = [p[1] for p in ring]
rec = struct.pack('<i', 5) + struct.pack('<4d', min(xs), min(ys), max(xs), max(ys)) + struct.pack('<ii', 1, len(ring)) + struct.pack('<i', 0) + b''.join(struct.pack('<2d', *p) for p in ring)
def shp_header(total_len_words):
    return struct.pack('>7i', 9994, 0, 0, 0, 0, 0, total_len_words) + struct.pack('<2i', 1000, 5) + struct.pack('<4d', min(xs), min(ys), max(xs), max(ys)) + struct.pack('<4d', 0, 0, 0, 0)
shp = shp_header((100 + 8 + len(rec)) // 2) + struct.pack('>2i', 1, len(rec) // 2) + rec
shx = shp_header((100 + 8) // 2) + struct.pack('>2i', 50, len(rec) // 2)
# dBase file with one numeric field "x"
dbf = struct.pack('<BBBBIHH20x', 3, 126, 1, 1, 1, 32 + 32 + 1, 1 + 4) + b'X'.ljust(11, b'\0') + b'N' + b'\0' * 4 + bytes([4, 0]) + b'\0' * 14 + b'\r' + b' ' + b'   1' + b'\x1a'
prj = b'GEOGCS["GCS_WGS_1984",DATUM["D_WGS_1984",SPHEROID["WGS_1984",6378137.0,298.257223563]],PRIMEM["Greenwich",0.0],UNIT["Degree",0.0174532925199433]]'
with zipfile.ZipFile(os.path.join(OUT, 'water-polygons-split-4326.zip'), 'w') as z:
    for ext, data in (('shp', shp), ('shx', shx), ('dbf', dbf), ('prj', prj)):
        z.writestr('water-polygons-split-4326/water_polygons.' + ext, data)
print('wrote', len(nodes), 'nodes and', len(ways), 'ways to', OUT)
