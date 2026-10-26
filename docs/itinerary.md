# Itinerary planner: field trips, group trips, costs and live fuel prices (server)

Code: `lib/itin.js` (trips), `lib/itin-plan.js` (the optimiser and the schedule rule), `lib/itin-matrix.js` (ORS matrix),
`lib/fuel-model.js` (consumption), `lib/fuel-prices.js` + `lib/pdf-text.js` (official prices), `lib/towns.js`, `lib/fx-rates.js`,
`lib/group-trips.js`; endpoints `functions/api/itin/[[path]].js`, `functions/api/vehicles.js`, `functions/api/fuel.js`,
`functions/api/group-trips/[[path]].js`, `functions/api/trip-share.js`, and the shared page `functions/pin-trip.js`.
Tests: `tests/itin.test.mjs`, `tests/fuel.test.mjs`, `tests/group-trips.test.mjs` (fixtures in `tests/fixtures/fuel/`).
It re-uses the trip engine (`lib/trip.js`, `lib/roads.js`, `lib/weather.js`, `docs/trip-weather-roads.md`).

## How a trip is planned

1. **Drive times between every pair** of places: one OpenRouteService matrix request (`ORS_API_KEY`, cached 30 days,
   `ORS_MATRIX_DAILY_LIMIT`, default 400 a day). Without a key, or over the limit: straight line × 1.4 at 45 km/h.
2. **Order:** nearest-neighbour from the start, then 2-opt. Within a day the 2-opt cost is drive minutes + 3 × minutes late
   for a window or a locked meeting + 2 × minutes past the end of the working day + 0.2 × minutes waiting
   (`PENALTY` in `lib/itin-plan.js`), so a school that closes at 15:30 is visited earlier.
3. **Days:** stops are filled into days in that order while the day still ends on time (default 07:30–17:30) and no window
   is missed; stops "pinned" to a day stay there. What doesn't fit is `unplanned` (warning `NO_FIT`).
4. **Overnight:** when the next day's first stop (or this day's last) is more than 90 min from the start point
   (`overnightAfterMin`), the day ends in the listed town nearest that stop (within 80 km, `lib/towns.js`); lodging can be
   entered by hand.
5. **Real legs and weather:** each day's sequence goes through `tripAdvice` (routes with tarmac/murram, Open-Meteo
   weather, road reports, learned times). The days are re-timed with those minutes, and their warnings are attached.
   `weekAdvice` (one forecast per 5 km square) finds a drier trip day for a wet one (`SWAP_DAY`).
6. **Costs and packing** are recalculated on every save.

## Consumption model

All the numbers are in `CONSUMPTION` in `lib/fuel-model.js` (one table, with the reasoning in the comment above it):
base L/100 km by vehicle type and engine size (petrol, tarmac, driver only), × 0.85 for diesel, × the surface factor of each
leg (murram 1.25 dry → 1.60 in heavy rain; tarmac 1.00 → 1.05; unknown surface halfway), × load (+2 % a passenger,
+8 % for a pillion on a motorbike, +1.5 % per 100 kg of cargo), × 1.15 on the km within 4 km of a town.
From 3 fuel logs with odometer readings (full tank to full tank) the vehicle's real L/100 km, divided by 1.12 for mixed
roads, is blended in with weight n/(n+3), at most 0.8. An entered L/100 km replaces the model's base.
The base figures are typical values for the vehicles RAFIKI uses, not measurements: compare them with the real figures as
fuel logs come in, and adjust the table.

## Live fuel prices: the sources (checked 26 Oct 2026)

| Country | Source | Format | Current values (26 Oct 2026) | Fetchable from a server |
|---|---|---|---|---|
| Kenya | EPRA, maximum pump prices, https://www.epra.go.ke/pump-prices | HTML table, every town (228 rows + Mombasa in the page's ticker), one row per town per period, newest first. Monthly, published on the 14th for the 15th–14th. The page holds every period since late 2024 (~7 MB), so the server reads it as a stream and stops after the newest period (~350 KB). | 15 Oct–14 Nov 2026: Nairobi petrol 226.96, diesel 227.76, kerosene 219.87; Eldoret 226.51 / 227.71; Kitale 226.76 / 227.96; Kisumu 226.51 / 227.71; Nakuru 225.73 / 226.93; Mombasa 223.81 / 224.71 (KES/L) | Yes: fetched and parsed live on 26 Oct 2026 |
| Tanzania | EWURA cap prices, https://www.ewura.go.tz/publications/petroleum-price → the newest "Cap Prices" PDF | PDF notice, Table 3 "Retail Cap Prices" per town (190 towns), effective from the first Wednesday of the month. Parsed with a small built-in PDF reader (`lib/pdf-text.js`: Flate streams, ToUnicode maps). | From 7 Oct 2026: Dar es Salaam petrol 4,057, diesel 4,086, kerosene 4,067; Arusha 4,186 / 4,215; Moshi 4,171 / 4,200; Mwanza 4,272 / 4,301 (TZS/L) | Yes: fetched and parsed live on 26 Oct 2026. The site sometimes resets connections; a failed fetch keeps the last list. |
| Malawi | MERA, "Fuel Pump Prices" download, https://mera.mw/download/fuel-pump-prices/ (file `?wpdmdl=3633`) | PDF, one national price per revision date ("Apr-26 6,672.00 6,687.00 5,824.00"), newest first | The file still shows **April 2026**: petrol 6,672, diesel 6,687, paraffin 5,824 MWK/L. News reports say MERA changed prices since (e.g. from 1 Aug 2026 petrol 5,619, diesel 5,863), but MERA has not updated this file, so the server marks it **"may be out of date"** (older than 150 days) and an office price entered later wins automatically. | Yes (fetched and parsed live), but stale at the source |
| Uganda | none | Pump prices are deregulated; dealers set them. The Ministry of Energy publishes "indicative" prices, but its site (memd.go.ug) could not be reached from here. | Kampala pump prices were UGX 5,049–5,400/L in mid-April 2026 (press); no official list | No source: admins enter prices (whole country `*` or per town) |

Rules (`lib/fuel-prices.js`):
- **Never invented.** A list is only stored when it parses fully (towns ≥ 10, the capital present, every price in a plausible
  range for that currency). A page that changed shape fails with `parse` and the last good list stays.
- **Fallbacks:** the last fetched list (shown with its dates; after its period it is "cached" and "may be out of date")
  → the office price → none ("Fuel: add a fuel price"). An office price for a town wins over the official one only when it
  was entered on or after the official list's start date, so a new EPRA period replaces an old office price by itself.
- **Per trip:** the price of the listed town nearest to the start or any stop (`lib/towns.js` has the positions of about 120 towns;
  others in the lists are kept but can't be "nearest"); then the national price.
- TZS, UGX and MWK are shown with KES at the day's rate (`lib/fx-rates.js`, the same free source as `/api/fx`).
- **Fetching:** daily by `.github/workflows/fuel-prices.yml` (07:10 Nairobi), and on demand from Office ("Refresh",
  at most 20 an hour). One fetch of all three takes about 8 s. Reading the EWURA and MERA PDFs needs more CPU time than the
  Workers free plan's 10 ms per request; on the free plan those two will fail with `parse` and need the office price
  (EPRA's HTML is light). On the Workers Paid plan (needed for the team chat already) it works.

## What the owner must set up

1. **Nothing new in Cloudflare.** Storage is the existing KV (`FEED`); `ORS_API_KEY` and `CRON_KEY` already exist.
   Optional: `ORS_MATRIX_DAILY_LIMIT` (default 400; the free ORS plan allows about 500 matrix requests a day).
2. **GitHub:** the new workflow `.github/workflows/fuel-prices.yml` uses the same repository secret `CRON_KEY` (and
   variable `SITE_URL`) as the monthly report. Once merged it runs daily; run it once by hand ("Run workflow") to fill the
   prices straight away.
3. **Office settings:** lunch, per diem and lodging amounts start at 0 (not set); set them in Office (`POST /api/itin/settings`).
4. **Uganda (and Malawi while MERA's file is old):** enter fuel prices in Office › Vehicles › Fuel prices.
5. **Optional:** `FUEL_EPRA_URL`, `FUEL_EWURA_URL`, `FUEL_MERA_URL` override the source addresses if a regulator moves its page.

---

# The API contract

## Storage (KV `FEED`; no new binding)

| Key | What |
|---|---|
| `itin:<id>` | one field itinerary (the record below) |
| `itinidx` | index: `[{ id, title, owner, people, viewers, from, to, status, ck, projects: [ids], stops: n, updated }]` (newest 1000) |
| `itinshare:<sha256>` | `{ kind: "itin" \| "group", id }` for private read-only links |
| `itintrack:<id>` | GPS track `[[lat, lng, epochMs], …]` (≤ 3000, thinned; kept 400 days) |
| `itinsettings` · `itinwindows` · `itinalerts` | Office settings · per-project time windows · re-plan/skip notices (newest 200) |
| `itinmatrix:<hash>` | cached ORS matrix (30 days) |
| `vehicles` · `fuellog:<vid>` | vehicles · fuel logs per vehicle (newest 300) |
| `fuelprice:<ck>` · `fuelprice:prev:<ck>` · `fuelprice:override` · `fuelprice:status` | official list per country (`ke`, `tz`, `mw`) · the period before · office prices · last fetch per country |
| `fx:usd` | exchange rates for server totals (open.er-api.com, 1 day) |
| `group:<id>` · `groupidx` | group trips |

## 1. Field itineraries: `/api/itin`

### Permissions
- **Admins:** see and edit every itinerary.
- **Field officers:** see trips where they are `owner`, in `people` or in `viewers`; edit those where they are `owner` or in `people`. A field officer creating a trip is always added to `people`.
- Only the owner or an admin can `delete`.

### The record (`itin`)
```json
{
  "id": "it_k3x9…", "v": 4, "title": "Moiben repairs", "status": "draft|planned|active|done|cancelled",
  "owner": "Meril", "people": ["Meril", "Grace"], "viewers": ["Jessica"], "ck": "ke", "tz": "Africa/Nairobi",
  "from": "2026-10-28", "to": "2026-10-30", "hours": { "start": "07:30", "end": "17:30" }, "lunch": { "at": "12:30", "min": 45, "skip": false },
  "start": { "kind": "office|here|place", "name": "Kitale office", "lat": 1.015, "lng": 35.006 },
  "end": { "kind": "same" } | { "kind": "office|here|place", "name", "lat", "lng" },
  "vehicleId": "vh_…"|null, "passengers": 1|null, "cargoKg": 0,
  "stops": [{ "sid": "s1", "id": "104", "name": "Bishop Luge School", "lat": 0.95, "lng": 34.95, "type": "borehole|collection|school|health|children|farming|community|building|other",
              "area": "Kitale", "ck": "ke", "visitMin": 60, "window": { "open": "08:00", "close": "15:30", "src": "type|project|stop" } | null,
              "fixed": "10:00"|null, "locked": false, "day": 0|null, "pinDay": false, "why": [ { "code", "en", "sw" } ],
              "status": "planned|done|late|skipped|moved", "checkin": { "at", "by", "lat", "lng" } | null, "note": "" }],
  "days": [{ "date": "2026-10-28", "name": { "en": "Wed 28 Oct", "sw": "Jumatano 28/10" }, "stops": ["s1", "s3"],
             "startAt": { "key": "S", "name": "Kitale office", "lat", "lng" },
             "endAt": { "kind": "home|overnight", "key": "S|E|T:Iten|N:0", "name": "Iten", "lat", "lng" },
             "overnight": { "town": "Iten", "lat", "lng", "suggested": true, "why": { "en": "near Thursday’s first stop (25 min)", "sw": "…" },
                            "lodging": { "name", "phone", "kes", "note", "paid" } | null } | null,
             "timeline": [ ], "km": 142, "driveMin": 215, "endsAt": "16:40", "riskLevel": "ok|caution|difficult",
             "weather": { "rainMm": 25, "roadLevel": "heavy" } | null, "warnings": [ ] }],
  "unplanned": ["s9"], "warnings": [ ],
  "legs": { "S>s1": { "km": 70, "min": 112, "maxMin": 159, "src": "trip|matrix|estimate", "key": "<legKey for /api/trip learning>", "roads": true, "roadsReason": null,
                      "chips": { "surface": {en,sw}, "rain": {en,sw}, "time": {en,sw} }, "riskLevel": "difficult", "warnings": [ /* /api/trip leg warnings */ ],
                      "surface": { "pavedKm", "unpavedKm", "unknownKm", "likely" }, "roadLevel": "heavy", "rainMm": 25, "date": "2026-10-28", "geometry": [[lat,lng],…≤80], "to": "Moiben" } },
  "matrix": { "keys": ["S", "E", "s1", …], "min": [[…]], "km": [[…]], "src": "ors|estimate", "reason": "no-key|limit|…"? },
  "cost": { /* Costs */ }, "packing": [ /* Packing */ ], "extras": [{ "xid", "day": 0|null, "kind": "toll|parking|lodging|lunch|other", "label", "kes", "actual": false, "receipt": "<photo id>"|null }],
  "share": { "url", "at", "by" } | null, "summary": { /* Summary */ } | undefined,
  "created", "updated", "by"
}
```
Point keys (in `legs`, `matrix`, timeline `from`/`to`): `S` start, `E` end (same as `S` when `end.kind` is `same`), a stop's `sid`, `T:<town>` an overnight town, `N:<day>` an overnight place with no listed town near.

### Timeline (per day, in order)
```json
{ "kind": "leave", "at": "07:30", "place": "Kitale office" }
{ "kind": "drive", "from": "S", "to": "s1", "dep": "07:30", "arr": "09:22", "min": 112, "km": 70, "leg": "S>s1", "src": "trip|matrix|estimate|test" }
{ "kind": "wait", "from": "09:22", "to": "09:30", "min": 8, "sid": "s1", "why": "opens|fixed" }
{ "kind": "stop", "sid": "s1", "arrive": "09:22", "start": "09:30", "leave": "10:30", "visitMin": 60, "fixed": null, "late": false }
{ "kind": "lunch", "from": "12:30", "to": "13:15" }
{ "kind": "overnight", "at": "16:40", "town": "Iten" }   or   { "kind": "return", "at": "16:40", "place": "Kitale office" }
```

**The schedule rule** (`lib/itin-plan.js` `schedule()`; clients may re-time locally with exactly this when offline):
1. `t = hours.start`; position = `days[i].startAt.key`.
2. Each stop: drive minutes = `legs["a>b"].min` (or `legs["b>a"]`), else `matrix.min`, else straight line × 1.4 at 45 km/h. `arrive = t + drive`.
3. Lunch (unless `lunch.skip`), once a day, at the first of: (a) right after a stop, if the time is ≥ `lunch.at`; (b) before a drive, if it's within 60 min before `lunch.at` and the drive would arrive after `lunch.at + 60`; (c) on arrival at a stop at or after `lunch.at + 60`, unless eating first would miss its meeting time or closing time. It adds `lunch.min`.
4. `fixed` (locked meeting): wait until `fixed` if early; more than 5 min late → `FIXED_LATE`. Else `window`: wait until `open` if early (`BEFORE_OPEN`, info, when > 20 min); arriving at/after `close` → `AFTER_CLOSE` (difficult) "Arrives 15:50, after the school closes"; visit running past `close` → `AFTER_CLOSE` (caution) "Visit runs until 15:45, after the school closes at 15:30".
5. `leave = start + visitMin`.
6. Last: drive to `endAt.key` (no drive item when it's the same place). After `hours.end` → `OVER_HOURS` "Day ends 18:20, after 17:30".

### Warnings
`{ code, level: "info|caution|difficult", en, sw, sid?, leg?, fix? }`. `fix` is the one-tap action to offer.

| code | where | when | `fix` |
|---|---|---|---|
| `AFTER_CLOSE` | day | see the rule | `{ action: "move", sid, toDay }` when another day fits it |
| `BEFORE_OPEN` | day (info) | waits > 20 min | — |
| `FIXED_LATE` | day | can't reach a locked time | — |
| `OVER_HOURS` | day | ends after `hours.end` | `{ action: "move", sid: <last stop>, toDay: i+1 }` |
| `SWAP_DAY` | day | rain on this day's roads and another trip day is drier: "Heavy rain Thursday on the murram road to Moiben. Swap with Tuesday?" | `{ action: "swap", day: i, with: j }` → send `swap-days` |
| `ROAD_REPORT` | day | a field road report on a leg ("Potholes reported 3 km before the site by Grace, 12 days ago"), with `leg`, `rid`, `kind` | — |
| `LEG_<CODE>` | day | the leg's own `/api/trip` warning (`LEG_MURRAM_HEAVY_RAIN`, `LEG_STORM`, `LEG_NO_FORECAST`…), with `leg` | — |
| `NO_FIT` | root | stops in `unplanned` (+ `sids`) | `{ action: "extend" }` (UI: offer more days) |
| `NO_VEHICLE` / `NO_FUEL_PRICE` | root (info) | no vehicle chosen / no price found | — |

### Endpoints
- `GET /api/itin` → `{ ok, items: [<index rows>], admin }` — filters `?status=planned,active`, `?mine=1`, `?person=Grace` (admins), `?project=104` (project history), `?month=2026-10`.
- `GET /api/itin?settings=1` → `{ ok, settings, admin }` · `?windows=1` → `{ ok, windows }` · `?alerts=1` (admins) → `{ ok, alerts: [{ at, id, title, kind: "replan|skip", by, en, sw }] }`.
- `GET /api/itin/<id>` → `{ ok, itin, can: { edit, admin } }`; `?progress=1` adds `progress`; `?card=1` → `{ ok, card: { id, title, from, to, stops, people, url } }` (for `shareToChat(itineraryId)`: post `card.url`; `url` is null until shared).
- `POST /api/itin/suggest` `{ ck, areas?: [..], from?: { lat, lng }, limit?: 30 }` → `{ ok, items: [{ id, name, area, ck, lat, lng, type, score, why: [{ code, en, sw }], windows, visitMin, km? }], total, areas, ck }`, ranked. `why.code`: `repair` / `urgent` ("open repair · urgent"), `notworking`, `overdue` ("due a visit · 8 months"), `never`, `test` ("water test due"), `community` ("community report 2 days ago"), `assigned` ("assigned to you").
- `POST /api/itin/optimise` → `{ ok, plan }` (not saved). Body:
  ```json
  { "title"?, "from", "to", "people": ["Meril"], "ck"?, "start": { "kind", "name", "lat", "lng" }, "end"?: { "kind": "same" } | { "kind": "place", "name", "lat", "lng" },
    "hours"?, "lunch"?, "stops": [ "104" | { "id": "104" } | { "sid"?, "id"?, "name", "lat", "lng", "type"?, "visitMin"?, "window"?, "fixed"?, "locked"?, "day"?, "pinDay"? } ],
    "vehicleId"?, "passengers"?, "cargoKg"?, "weather"?: true, "plan"?: { "days": [{ "stops": [sid], "overnight"? }] } }
  ```
  ≤ 40 stops, ≤ 14 days. Refusals: `stops` (+ `unknown`), `too-many` (+ `max`), `dates` (+ `max`), `start`, `end`, `hours`, `lunch`, `no-vehicle`. `weather: false` skips roads/weather (fast). `plan` keeps a previewed/edited day split instead of re-optimising.
- `POST /api/itin` (create): same body → `201 { ok, itin, can }`. Optional `status`.
- `POST /api/itin/settings` (admins) `{ lunchKes, perDiemKes, lodgingKes, hours, lunch, visitMin: { type: min }, windows: { type: { open, close } | null }, overnightAfterMin }` → `{ ok, settings }`. Refusals `bad-amount`, `hours`, `lunch`.
- `POST /api/itin/windows` (admins) `{ id, window: { open, close } | null, fixed?, visitMin?, days?: [0-6] }` → `{ ok, windows }`.
- `POST /api/itin/<id>` `{ action, v, … }` → `{ ok, itin, can }` unless noted:

| action | body | does |
|---|---|---|
| `update` | any of `title, from, to, people, viewers, start, end, hours, lunch, vehicleId, passengers, cargoKg, status` | `status: "done"` also writes `summary` |
| `stops` | `days: [{ stops: [sid] }]` (one per day), `unplanned?: [sid]`, `stops?: [{ sid, visitMin?, window?, fixed?, locked?, note? }]` | drag/reorder/move between days/remove (a sid in no day and not in `unplanned` is removed); re-times from cached legs, no network |
| `add-stop` | `stop`, `day?` | without `day`: the day and position with least extra time |
| `optimise` | `keepDays?: bool` | re-orders (and re-splits unless `keepDays`) |
| `refresh` | | re-fetches real legs and weather, keeps the order |
| `swap-days` | `day`, `with` | the `SWAP_DAY` fix |
| `lodging` | `day`, `overnight: { town, lat, lng, lodging: { name, phone, kes, note, paid } }` or `{ lodging }` (keep the place) or `null` (go home) | refusals `day`, `place` |
| `extras` | `extras: [...]` | replaces the list |
| `packing` | `items: [{ key, done?, removed?, en?, sw?, delete? }]`, `add: [{ en, sw? }]` | |
| `checkin` | `sid`, `at?`, `lat?`, `lng?`, `left?` (ISO time they left the previous place) | marks done/late (> 30 min after the plan); with `left`, records the real leg time for `/api/trip` learning; status → `active` |
| `skip` | `sid`, `why?` | marks skipped; notifies the office |
| `replan` | `day?` (default today), `now?: "HH:MM"`, `mode: "preview"|"push"|"drop"` | preview → `{ ok, preview: true, day, keep, move, endsAt }`; push/drop → `{ ok, itin, can, moved: [sid], dropped: [sid] }`. Push moves to the next day (last day: to `unplanned`); drop skips them. Notifies the office (alert + e-mail to admins). Lunch isn't re-planned once it's past lunch time. |
| `track` | `points: [[lat, lng, epochMs]…]` ≤ 500 | → `{ ok, points }` |
| `share` | `rotate?` | → `{ ok, url, token, v }`; the same link again unless `rotate` (the old one then stops working) |
| `unshare` | | |
| `share-team` | `names: [...]` | adds viewers |
| `summary` | | refreshes `summary` |
| `delete` | | owner/admin → `{ ok, deleted }` |

### Progress (`?progress=1`)
```json
{ "day": 0, "now": "11:05", "next": "s3", "stops": [{ "sid", "day", "state": "done|late|due|next|planned|skipped", "planned": "09:30", "at": "…"|null, "lateMin": 11|null, "by": "Meril"|null }],
  "where": [{ "name", "lat", "lng", "at", "acc" }] }
```
Done = a check-in, or a `/api/pin` visit report for that project on that day by one of the trip's people. `where` = trip people sharing their location (Team map opt-in), last 12 h.

### Summary (`itin.summary`)
`{ at, visited: [{ sid, id, name, at }], skipped: [...], km: { value, src: "gps|planned", planned }, cost: { estKes, actualKes, fuelLitres, fuelKes, estFuelMissing }, problems: [{ id, name, state, by, at }], repairsOpened, waterTests }`.

### Costs (`itin.cost`, recalculated on every save)
```json
{ "currency": "KES", "missing": ["lunchKes"],
  "fuel": { "litres": 31.2, "lPer100": 8.5, "use": { "lPer100", "src": "model|entered|blend|logs", "weight", "model", "real" },
            "basis": { "en": "Hilux · 2400 cc diesel pickup · 8.5 L/100 km (model), adjusted for murram, rain, load and towns", "sw": "…" },
            "price": <price object, see Fuel> | null, "kes": 7110 | null, "reason": null | "no-price" | "no-vehicle",
            "label": { "en": "Fuel: KES 227.96/L diesel, Kitale, EPRA 15 Oct–14 Nov", "sw": "Mafuta: KES 227.96/L dizeli, Kitale, EPRA 15 Okt–14 Nov" },
            "vehicle": { "vid", "name", "type", "fuel" } | null },
  "days": [{ "date", "km", "litres", "fuelKes"|null, "lunchKes", "perDiemKes", "lodgingKes", "extrasKes", "totalKes" }],
  "est": { "fuelKes"|null, "lunchKes", "perDiemKes", "lodgingKes", "extrasKes", "totalKes", "fuelMissing"? },
  "actual": { "fuelKes", "fuelLitres", "logs", "extrasKes", "lodgingKes", "totalKes" } }
```
- No price → `fuel.kes: null`, `label` "Fuel: add a fuel price" / "Mafuta: weka bei ya mafuta", `est.fuelMissing: true` (fuel left out of the total).
- Lunch = `lunchKes` × people per working day; per diem = `perDiemKes` × people × days; lodging = the night's `lodging.kes` if entered, else `lodgingKes` × people. **These Office amounts default to 0 (not set)**; `missing` lists the ones not set yet so the Office can be prompted.
- Actual = fuel logs with this `itinId` + extras with `actual: true` + lodging marked `paid`.

### Packing (`itin.packing`)
`[{ key, auto, done, removed?, edited?, en, sw, why?: [project ids] }]`. Auto keys: `auto:strips` (water tests due), `auto:parts:<id>` (open repair parts), `auto:consent` (sites with story moments), `std:firstaid`, `std:charger`, `std:offline`, `std:water`, `std:id`. Own items `u:<n>`. Ticks and edits survive re-planning; `removed` auto items stay hidden.

## 2. Vehicles: `/api/vehicles`
- `GET` → `{ ok, vehicles: [<vehicle>], types, fuels, admin }`.
- `POST { action: "add", name, plate?, type, cc, fuel, lPer100?, seats?, mine? }` → `201 { ok, vehicle }`. Admins any type; field officers only `motorbike` (owner = them).
- `POST { action: "update", vid, … }` · `{ action: "remove", vid, force? }` (`409 in-use` + `trips` when on a planned/active trip).
- Refusals: `not-admin`, `forbidden`, `bad-name`, `bad-type`, `bad-cc` (49–8000), `bad-fuel`, `bad-consumption` (1–60), `too-many`, `in-use`.
```json
{ "vid": "vh_…", "name": "Hilux", "plate": "KDA 123X", "type": "saloon|suv|pickup|4x4|van|minibus|motorbike", "cc": 2400, "fuel": "petrol|diesel", "lPer100": null, "seats": 5, "owner": null|"Grace",
  "model": { "lPer100": 8.5, "base": 10, "fuelFactor": 0.85, "band": "2001–2500 cc" }, "real": { "lPer100", "logs", "km" } | null, "use": { "lPer100": 8.5, "src": "model", "weight": 0 } }
```
Consumption model (`lib/fuel-model.js`, `CONSUMPTION`): L/100 km = base(type, cc band) × fuel (diesel 0.85) × surface (murram dry 1.25 / light 1.32 / wet 1.42 / heavy 1.60; tarmac wet 1.03, heavy 1.05; unknown halfway) × load (+2 % per passenger, motorbike pillion +8 %, +1.5 % per 100 kg) × town (+15 % on km within 4 km of a town). From 3 fuel logs the real figure (÷ 1.12 for mixed roads) is blended in with weight n/(n+3), max 0.8.

## 3. Fuel: `/api/fuel`
- `GET ?prices=1[&ck=ke]` → `{ ok, admin, countries: { ke: { ck, source: "EPRA", sourceName, url, docUrl, from, to, month, fetchedAt, currency, national: { town, petrol, diesel, kerosene }, towns: { Eldoret: { petrol, diesel, kerosene } }, kind: "official|cached|none", stale, overrides: { Eldoret: { petrol, diesel, at, by, until } }, status: { at, ok, reason, url, from }, fx?: { rate, at } } } }`.
- `GET ?price=1&lat=&lng=&fuel=diesel` (or `&ck=ke&town=Eldoret`) → `{ ok, price, reason }`. Price object:
  `{ local: 4200, kes: 221.49, currency: "TZS", fuel, town, distKm, source: "EWURA"|"office", from, to, month, kind: "official|cached|override", stale, url?, at?, by?, fx?: { rate, at }, label: { en, sw } }`.
  Order: an office price for that town entered on/after the official list's start → the official price of the nearest listed town (the `cached` one after its period ended, marked `stale`) → the national price → the office `*` (whole country) price → `null` (`no-price`). Never invented.
  Labels: "Fuel: KES 227.96/L diesel, Kitale, EPRA 15 Oct–14 Nov" · "Fuel: TZS 4,200/L (KES 221.49) diesel, Kilimanjaro (Moshi), EWURA from 7 Oct" · "Fuel: KES 229.50/L petrol, Kitale, office price, 2026-10-20" · stale adds " (may be out of date)". (Space after KES/TZS is a non-breaking space.)
- `GET ?logs=<vid>` → `{ ok, logs (newest first), real }` · `?itin=<id>` → `{ ok, logs }`.
- `POST { action: "override", ck, town ("*" = whole country), petrol?, diesel?, until? }` (admins; local currency) · `{ action: "override-remove", ck, town }` · `{ action: "refresh", ck? }` (admins, ≤ 20/h) → `{ ok, results: { ke: { ok, from, to, towns } | { ok: false, reason } } }`.
- `POST { action: "log", vid, itinId?, litres, kesPerL | totalKes, (or perL | total with currency: "TZS|UGX|MWK"), odometer?, full?: true, town?, lat?, lng?, receipt?, note?, at? }` → `201 { ok, log, real }`. `log.paid` keeps the local amount and rate when not KES.
- `POST { action: "log-remove", vid, lid }` (who added it, or an admin).
- `POST /api/fuel?cron=prices` + `X-Cron-Key` → the daily fetch.
- Refusals: `bad-country`, `bad-town`, `bad-price` (+ `range`, `currency`), `bad-litres` (0.5–400), `bad-odometer`, `no-vehicle`, `no-fx`. Fetch status reasons: `fetch`, `blocked`, `parse`, `stale`, `no-source`.

## 4. Group trips: `/api/group-trips` (admins only)
- `GET` → `{ ok, items: [{ id, name, from, to, country, participants: n, public, donors, updated }] }` · `GET /<id>` → `{ ok, trip }`.
- `POST` (create) → `201 { ok, trip }`. `POST /<id> { action, v, … }` → `{ ok, trip }`:
  `update` (trip fields) · `participant-add { participant }` / `participant-update { pid, participant }` / `participant-remove { pid }` · `item-add { item }` / `item-update { iid, item }` / `item-remove { iid }` · `cost-add { cost }` / `cost-remove { cid }` · `payment-add { payment }` / `payment-remove { payid }` · `share { rotate? }` → `{ ok, url, token, v }` · `unshare` · `remind { iid, send?: false }` → preview `{ ok, preview: { subject, html, text }, to: n, missing: n, ready }`; `send: true` → `{ ok, sent, failed: [{ name, error }] }` (refusals `no-mail`, `no-recipients`, `cap|day-cap|month-cap` 429, shared caps and log with Office › Send an e-mail) · `delete`.
- Refusals: `not-admin`, `passport-number`, `bad-name`, `bad-email`, `bad-date` (+ `why: outside-trip|items-outside`), `bad-time`, `bad-item`, `bad-amount`, `bad-currency`, `participant`, `too-many`.
```json
{ "id": "gt_…", "v": 3, "name", "from", "to", "country": "ke|tz|ug|mw", "destination", "summary", "public": false, "leaders": ["Jessica"], "contact": "info@rafikioutreach.org", "donors": ["<donor id>"],
  "participants": [{ "pid": "p1", "name", "email", "phone", "role": "volunteer|donor|leader", "diet", "medical", "passportReminder": true, "passportExpiry": "2027-02"|null, "donorId"? }],
  "items": [{ "iid": "i1", "date", "start"?, "end"?, "kind": "flight|transfer|lodging|project|school|safari|free|meal|other", "title", "notes", "location": { "name", "address", "lat", "lng" } | null,
              "attachments": [{ "name", "photo"|null, "url"|null }], "flight": { "airline", "number", "from", "to", "depart", "arrive", "checkIn" } | null,
              "lodging": { "name", "address", "checkIn", "checkOut", "contact" } | null, "projectId": "104"|null, "remind": { "before": "24h|3h|1h"|null, "sentAt" } | null }],
  "costs": [{ "cid", "label", "amount", "currency", "per": "person|group" }], "payments": [{ "payid", "pid", "amount", "currency", "at", "note" }],
  "packing": "text (default given)", "expect": "text (default given: vaccinations, malaria link, drilling-day clothes, safari tips)", "emergency": [{ "name", "phone", "role" }],
  "totals": { "participants": 2, "perPerson": { "byCurrency": { "USD": 450, "KES": 64600 }, "USD": 950, "KES": 122740 }, "group": { … }, "paid": { "p1": { … } }, "due": { "p1": { "USD", "KES" } }, "fx": { "at", "USDKES" } | null },
  "share": { "url", "at", "by" } | null, "created", "updated", "by" }
```
Currencies: USD, KES, GBP, EUR, CAD, AUD, TZS, UGX, MWK. Without exchange rates, totals stay per currency (no USD/KES). **Never stored:** passport numbers (any field named like one, or "passport no. X1234567" in any text, is refused).

## 5. Public read-only: `/api/trip-share` and `/pin-trip`
- `GET /api/trip-share?t=<43-char token>` (no sign-in) → `{ ok, kind, updated, trip }` + `ETag`; send `If-None-Match` to poll (304 when unchanged). Unknown/revoked → `404 not-found`; too many wrong links → `429 rate`.
  - `kind: "itin"`: `{ title, from, to, status, people: [first names], days: [{ date, name, endsAt, km, timeline: [{ kind: "leave|drive|stop|lunch|wait|overnight|return", … names and times only }], overnight: { town, lodging: { name } | null } | null }], stops: [{ name, lat, lng }], lines: [[[lat,lng]…]] }`. No PINs, phone numbers, costs, notes.
  - `kind: "group"`: `{ name, from, to, country, destination, summary, leaders, contact, days: [{ date, items: [{ start, end, kind, title, notes, location, flight, lodging: { name, address, checkIn, checkOut }, projectUrl, attachments: [{ name, url }] }] }], packing, expect, emergency, map: [{ name, lat, lng, kind }], updated }`. Never participants, e-mails, phones, diet, medical, payments.
- `GET /api/trip-share?public=1` → `{ ok, trips: [{ name, from, to, country, summary, contact }] }`: upcoming group trips marked `public` (for `trips.html`, CORS open, cached 10 min).
- **The page:** `GET /pin-trip?t=<token>` (`&lang=sw` for field trips) — server-rendered, mobile-first, print/PDF friendly, map, "Add to home screen" (`&manifest=1` is its manifest), reloads itself when the plan changes. Share URLs from `action: "share"` point here.

### Donor mode
`GET /api/donor` (donor token, also `?bg=1`) now includes `groupTrips: [<the group shape above> + url]` for group trips whose `donors` include that donor.

## 6. Monthly report
The monthly admin e-mail (`/api/admin?cron=monthly`) gets a "Trips" row when trips were finished: "2 trips, 9 sites visited, 640 km; KES 48,000 estimated, KES 45,500 spent."

## Existing APIs the UI also uses (unchanged)
`/api/trip/week?ids=…&start=&days=` for the **best day per area** when choosing days; `/api/road-reports` for **Report the road**; `/api/trip` `action: "actual"` is done for you by `checkin` with `left`.

---
## Change log
- 2026-10-26 v1 published (design).
- 2026-10-26 **v1.1 (implemented).** Changes from v1:
  - Timeline `stop` items now have `arrive` (arrival) **and** `start` (visit start after waiting); `drive` items have `src`. No zero-minute drive item.
  - Days have `name {en,sw}`, `startAt.key`, `endAt.key` (point keys), overnight `lodging.paid`.
  - Lunch rule written out precisely (rule 3); `AFTER_CLOSE` text is "Arrives 15:50, after the school closes" / "Inafika 15:50, baada ya shule kufungwa".
  - New action `refresh`; `checkin` takes `left`; `replan` preview answers `{ preview: true, keep, move, endsAt }`, push/drop add `moved`/`dropped`.
  - Office amounts (lunch, per diem, lodging) default to 0 = not set; `cost.missing` lists them. `cost.fuel` adds `use` and `vehicle`.
  - Fuel price object: `local` + `kes` + `currency` (+ `fx`), `stale`, `month`; office prices win only when entered on/after the official list's start. Fuel logs accept `currency` TZS/UGX/MWK.
  - Vehicles: `in-use` is 409 with `trips`; new refusals `bad-name`, `bad-consumption`.
  - Group trips: `totals` shape (`byCurrency`, `USD`, `KES`), `flight.checkIn`, `participant.donorId`; reminder preview/send answers as above.
  - Public itinerary adds `status`, `lines`; group page adds `leaders`, `contact`, `attachments`.
