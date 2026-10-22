# Trip advice: weather and roads for planned visits

The server side is done. This note is for the UI round: what the API answers, where the web app (`pin.html`) and the Android app (`apps/android-native`) should show it, how it behaves offline, and what the owner has to set up.

Code: `lib/weather.js`, `lib/roads.js`, `lib/road-reports.js`, `lib/trip.js`, `functions/api/trip.js`, `functions/api/trip/week.js`, `functions/api/road-reports.js`. Tests: `tests/trip.test.mjs`.

## What it does

For each leg of a day (start, stop 1, stop 2, and so on) the server:

1. gets the driving route from OpenRouteService (ORS), with the length of tarmac, murram and unknown surface. Routes are cached for 30 days.
2. gets the Open-Meteo forecast at the destination and halfway along the route. It uses the worse of the two, and counts rain from the 2 days before, because murram stays bad after rain. Forecasts are cached for 3 hours.
3. picks up field road reports within about 150 m of the route, and within 500 m of the site.
4. turns all of this into a time estimate, warnings in English and Kiswahili, and a risk level: `ok`, `caution` or `difficult`.

Everyone signed in can use it, with the same sign-in as `/api/pin` (session cookie or `X-Pin`).

## Endpoints

### `GET /api/trip`

| Parameter | Meaning |
|---|---|
| `stops` | Project ids (`104,105`), or ids and points separated by `;` (`104;1.01,35.0`). At most 12. |
| `from` | Where the day starts: `lat,lng` or a project id. Leave it out to start at the first stop. For a single visit, send the phone's position. |
| `date` | `YYYY-MM-DD`. The default is today in Nairobi. |
| `until` | Optional. Rates every day from `date` to `until` (at most 16 days) and adds `days[]` and `bestDay`. |
| `geometry=0` | Leaves out the route lines. |

### `POST /api/trip`

- **Advice as JSON:** `{ from, stops: [{id,lat,lng,name} | "104"], date, until, geometry }`. Same answer as the GET.
- **Real trip time:** `{ action: "actual", from: [lat,lng], to: [lat,lng], minutes, level }`, or `{ action: "actual", leg: "<leg.key>", minutes }`. Answers `{ ok, leg, n }`. The visit tracker should send this when it sees a check-in at the next site. Use the time from leaving the previous site (or from the start of the day) to arriving. `level` is that day's `weather.roadLevel`. From 3 trips on, the median is blended into `estMin` (weight n/(n+3), at most 0.8). The leg is the same in both directions.

### `GET /api/trip/week`

Parameters: `ids=104,105,106&start=YYYY-MM-DD&days=5[&groups=104,105|106]`

This is weather only, so it makes no ORS calls. It returns the driest day for the projects, and for each group if you send groups. One group is one planned day of Plan a week. Projects with no location come back in `nogps`.

### `/api/road-reports`

- **List:** `GET ?s=&w=&n=&e=` (a box), `?route=lat,lng;lat,lng…&m=150`, or `?near=lat,lng&km=5`. Only active reports are listed.
- **Change:** `POST { action: "add", kind, note, lat, lng, acc, photo }`, `{ action: "confirm", rid }` ("still there", which restarts the expiry) or `{ action: "clear", rid, why: "fixed"|"gone"|"wrong" }`. Only the reporter or an admin can clear a report.
- **Kinds and how long they last:**

| Kind | Lasts |
|---|---|
| `flooded` | 7 days |
| `potholes` | 120 days |
| `under_construction` | 180 days |
| `slow_murram` | 365 days |
| `good_tarmac` | 365 days |
| `impassable_when_wet` | until cleared |
| `bridge_out` | until cleared |

  The reasons are in `lib/road-reports.js`.
- **Labels:** every report has `words: { en, sw }` with its label.
- **Photo:** `photo` is an optional id of at most 64 characters (`[A-Za-z0-9_-]`). Upload the photo with `/api/pin-photo` first, then pass its note or file id here.

### Example: `/api/trip` (Kitale to a school, heavy rain on the 23rd)

The numbers come from the test fixture, and `geometry` is shortened.

```json
{
  "ok": true, "date": "2026-10-23", "today": "2026-10-22", "roads": true,
  "legs": [{
    "key": "0.385,35.000|1.015,35.000",
    "from": { "id": "", "name": "Kitale", "lat": 1.015, "lng": 35 },
    "to": { "id": "104", "name": "Bishop Luge Memorial", "lat": 0.38547, "lng": 35 },
    "roads": true, "km": 70, "baseMin": 80, "estMin": 112, "estMaxMin": 159,
    "surface": { "pavedKm": 62, "unpavedKm": 7, "unknownKm": 1, "likely": { "pavedKm": 0, "unpavedKm": 1 } },
    "minutes": { "paved": 62, "unpaved": 18, "unknown": 0 },
    "unpaved": [{ "from": [0.45742, 35], "to": [0.38547, 35], "km": 8, "startKm": 62, "endKm": 70, "surface": "Dirt, Gravel", "guess": true }],
    "geometry": [[1.015, 35], [1.00601, 35], "…"], "tolM": 0, "snapM": [0, 0],
    "weather": { "available": true, "date": "2026-10-23", "rainMm": 25, "rainChance": 90, "code": 65, "tMax": 24, "tMin": 14,
      "morningRainMm": 6.3, "level": "heavy", "prev": [0, 0], "prevRainMm": 0, "soakMm": 25, "roadLevel": "heavy",
      "summary": "Heavy rain expected · 25 mm (90%) · 24°/14°", "summarySw": "Mvua kubwa inatarajiwa · mm 25 (90%) · 24°/14°",
      "at": "dest", "dest": { "…": "…" }, "mid": { "…": "…" } },
    "warnings": [
      { "code": "MURRAM_HEAVY_RAIN", "level": "difficult", "murramKm": 8, "extraMin": 20,
        "en": "Last 8 km is murram and 25 mm of rain is forecast; allow about 20 min extra or take a 4x4",
        "sw": "Kilomita 8 za mwisho ni murram na mvua ya mm 25 inatarajiwa; ongeza takriban dakika 20 au tumia gari la 4x4" },
      { "code": "MAY_BE_IMPASSABLE", "level": "difficult", "en": "Murram may be impassable after this much rain; check with someone local before you go", "sw": "…" },
      { "code": "REPORT_POTHOLES", "level": "caution", "rid": "d057ad43-e67",
        "en": "Potholes reported 3 km before the site by Grace, 12 days ago",
        "sw": "Ripoti: Mashimo barabarani, km 3 kabla ya kufika — Grace, siku 12 zilizopita" }
    ],
    "riskLevel": "difficult",
    "chips": { "surface": { "en": "62 km tarmac · 8 km murram", "sw": "km 62 lami · km 8 murram" },
               "rain": { "en": "Rain 25 mm", "sw": "Mvua mm 25" }, "time": { "en": "~1 h 52", "sw": "~saa 1 dak 52" } },
    "reports": [{ "rid": "d057ad43-e67", "kind": "potholes", "note": "deep, by the shop", "by": "Grace", "at": "2026-10-10T06:00:00.000Z",
                  "lat": 0.41245, "lng": 35.0004, "distM": 45, "alongKm": 67, "kmBeforeEnd": 3, "photo": null }]
  }],
  "total": { "km": 70, "baseMin": 80, "estMin": 112, "estMaxMin": 159, "surface": { "…": "…" }, "rainMm": 25, "roadLevel": "heavy",
             "riskLevel": "difficult", "warnings": 3, "chips": { "time": { "en": "~1 h 52" }, "surface": { "en": "62 km tarmac · 8 km murram" } } },
  "days": [{ "date": "2026-10-23", "name": { "en": "Fri 23 Oct", "sw": "Ijumaa 23/10" }, "estMin": 112, "estMaxMin": 159, "riskLevel": "difficult", "rainMm": 25, "roadLevel": "heavy", "warnings": 3 }, "…"],
  "bestDay": { "date": "2026-10-27", "name": { "en": "Tue 27 Oct", "sw": "Jumanne 27/10" }, "estMin": 88, "rainMm": 0, "roadLevel": "dry", "riskLevel": "caution" }
}
```

Field notes:

- **`level` and `roadLevel`:** `level` is that day's rain. `roadLevel` is how wet the murram is, counting the 2 days before. Use `roadLevel` for the road and `level` for the weather icon.
- **`estMin` and `estMaxMin`:** show `~estMin` and offer "up to estMaxMin" for the cautious.
- **`learned`:** present when real trips were blended in, as `{ n, median, weight, modelMin }`. The UI can say "from 5 real trips".
- **`roads: false`:** the leg has no road data. Show "Road details are off" and the chip "70 km (estimate)". `roadsReason` says why:

| `roadsReason` | Meaning |
|---|---|
| `no-key` | No `ORS_API_KEY` is set |
| `limit` | The daily allowance is used up |
| `no-route` | No road near a point |
| `key` | The key was refused |
| `rate` | ORS is busy |
| `down` | ORS can't be reached |

- **`weather.available: false`:** `reason` is `too-far` (more than 16 days ahead; `availableFrom` says when), `past` or `down`. Show "No forecast yet" from `chips.rain`.
- **`snapM[1]`:** metres from the site to the nearest mapped road. Above 500 m there is an `UNMAPPED_END` warning.
- **Warning levels:** `info`, `caution` or `difficult`. `info` warnings don't raise the risk level. Show them in a smaller, grey style.

Warning codes:

| Code | When |
|---|---|
| `MURRAM_HEAVY_RAIN` | Heavy rain is forecast on a leg with murram |
| `MURRAM_WET` | Rain is forecast on a leg with murram |
| `MURRAM_LIGHT_RAIN` | Light rain is forecast on a leg with murram |
| `MURRAM_AFTER_RAIN` | The day is dry but rain from the 2 days before keeps the murram wet |
| `MAY_BE_IMPASSABLE` | Heavy rain and at least 2 km of murram |
| `RAIN_NO_ROADS` | Wet or heavy rain on a leg with no road data |
| `STORM` | Thunderstorms forecast |
| `NO_FORECAST` | More than 16 days ahead; mentions the rainy season when it applies |
| `SURFACE_UNKNOWN` | The surface is unknown for a large part of the leg |
| `UNMAPPED_END` | The site is more than 500 m from the nearest mapped road |
| `REPORT_<KIND>` | A road report near the route, e.g. `REPORT_POTHOLES` or `REPORT_BRIDGE_OUT` |

### Example: `/api/trip/week`

```json
{ "ok": true, "today": "2026-10-22", "start": "2026-10-26",
  "days": [{ "date": "2026-10-26", "name": { "en": "Mon 26 Oct", "sw": "Jumatatu 26/10" }, "available": true, "score": 2, "rainMaxMm": 0, "roadLevel": "light", "wetSites": 0 }, "…"],
  "bestDay": { "date": "2026-10-27", "name": { "en": "Tue 27 Oct", "sw": "Jumanne 27/10" }, "rainMaxMm": 0, "roadLevel": "dry" },
  "projects": [{ "id": "104", "name": "Bishop Luge Memorial", "bestDay": "2026-10-27", "wetReports": [],
    "days": [{ "date": "2026-10-26", "rainMm": 0, "rainChance": 10, "level": "dry", "roadLevel": "light", "morningRainMm": 0,
               "summary": "No rain expected · 24°/14°", "summarySw": "Hakuna mvua inayotarajiwa · 24°/14°", "tMax": 24, "tMin": 14, "code": 2, "score": 1 }] }],
  "groups": [{ "ids": ["104"], "bestDay": { "date": "2026-10-27", "…": "…" }, "days": ["…"] }],
  "nogps": [] }
```

## Where the UI should show it

| Place | Web (`pin.html`) | Android |
|---|---|---|
| **Office › Plan a week** | Under each planned day, a weather row with an icon, mm and the `roadLevel` colour for each of the next days, from `/api/trip/week?ids=…&groups=<one group per day>`. A hint such as "Driest day for this group: Tue 27 Oct" when `groups[i].bestDay` is not the day it is planned for. Optional: "Swap days" to put the murram-heavy group on the driest day. | `TeamMap.kt`: the week plan (`WeekPlan`, `PlanPickSheet`, `StopSheet`). Use the same row of chips under each day header. |
| **Assigning a visit (Office)** | Next to the date picker, the chosen project's weather for the next 16 days from `/api/trip/week?ids=<id>&days=16`. Grey out or mark wet days, and show "Driest: …". After a date is picked, show a `/api/trip?stops=<id>&date=…` summary: chips and the first warning. | `OfficeOverview.kt` `OcvAssignSheet`, and the assign flow in `OfficeScreen.kt`. |
| **Route screen** | For each leg: `chips.surface` ("62 km tarmac · 8 km murram"), `chips.rain` ("Rain 25 mm") and `chips.time` ("~1 h 40"), plus the warnings below them. Draw `geometry` with the `unpaved` stretches in a murram (brown) colour and report pins on the line. At the top: `total.chips` and the risk level. | `RouteReport.kt` `RouteScreen`. Use the same chips (`Badges`/`Components`), and draw the line in the map layer (`MapLayers.kt`). |
| **Project page, "Getting there"** | A small card: from the person's position (or their home base), `/api/trip?from=<lat,lng>&stops=<id>&date=<today>` gives the chips, top warnings and the date of the driest day this week. Plus active reports near the site (`/api/road-reports?near=<site>&km=5`). | `ProjectScreen.kt`, a card in the overview tab (or `ProjectExtras.kt`). |
| **Visit flow** | A quick **Report the road** button on the visit screen and on the map. It opens 7 big kind buttons with the Kiswahili label underneath, an optional note and photo, and sends GPS from the phone. On reports near you: "Still there" and, for your own reports or as an admin, "Fixed". | `VisitTab.kt`, and the map home (`MapHome.kt`), as a small floating button or in the "more" menu. |

Notes:

- **Language:** every piece of text comes in `en` and `sw`. Pick by the app's language setting (`I18n.kt` on Android). Don't build sentences on the client.
- **Risk colours:** `ok` green, `caution` amber, `difficult` red. Use the same tones as the e-mails: green `#18804F`, amber `#B45F06`, red `#C62828`.
- **Murram:** call it "murram", and in Kiswahili "murram" as well. Field staff use that word. Tarmac is "lami".

## Offline

- **The day's advice:** cache the last `/api/trip` answer for each day and stops list, keyed by `date + stops`, in the service worker or IndexedDB (web) or in `Store.kt` (Android). Show it with "as of 07:40" when there is no signal. The route lines and murram stretches don't change for weeks, so they stay useful. The weather is at most a few hours old when cached in the morning.
- **Pre-fetching:** fetch on the morning of a planned day. Android can use the existing background check (`AlertWorker`). The web app can fetch when the Plan or Route screen opens.
- **Road reports:** queue reports made offline the same way visits are queued, and send them when back online. The position and time are taken at the moment of the report, so send `lat`, `lng` and the note as they were. The server stamps its own time; a later round could accept the phone's `at`.
- **Real trip times:** `action: "actual"` can be queued too.

## What the owner must set up

1. **OpenRouteService key (needed for road details):**
   - Create a free HeiGIT account (account.heigit.org); its Basic key works for openrouteservice. The API lives at api.heigit.org (api.openrouteservice.org shuts down in Nov 2026).
   - In Cloudflare, open Pages › rafiki-redesign › Settings › Variables and secrets and add a **secret** named `ORS_API_KEY`. Do this for Production, and for Preview if wanted.
   - Never put the key in the code; the repository is public.
   - The free plan allows about **2,000 routes a day** and 40 a minute. Routes are cached for 30 days, so real use is far below that. `ORS_DAILY_LIMIT` (default 1800) stops calls before the limit.
2. **Optional settings:**
   - `ORS_DAILY_LIMIT`: lower or raise the daily cap.
   - `OPEN_METEO_API_KEY`: only if RAFIKI takes a paid Open-Meteo plan (see the open questions below).
3. **Nothing else:** storage is the existing KV (`FEED`). Without the ORS key, everything still works with weather and straight-line distances (`roads: false`).

## Tuning

- **Speed model:** `MODEL` and `PENALTY` in `lib/trip.js`. The murram factors are 1.3 dry, 1.45 light rain, 1.8 wet and 2.5 heavy rain, applied to ORS's own murram minutes. With no road data, the fallback uses a road factor of 1.4 at 45 km/h, with 30% of the distance counted as murram.
- **Rain thresholds:** `LEVELS` and `SOAK` in `lib/weather.js`.
  - Dry: under 1 mm. Light: 1–10 mm. Wet: 10–20 mm. Heavy: 20 mm or more.
  - Murram wetness = rain that day + 0.5 × yesterday + 0.25 × the day before.
- **Report expiry:** `TTL_DAYS` in `lib/road-reports.js`.
- **Learned times:** once real trips are recorded, compare `learned.median` with `learned.modelMin` across legs to adjust the factors.

## Open questions about the data

- **Unknown surfaces in OpenStreetMap:** OSM often has no surface tag on rural roads in western Kenya. ORS then answers "Unknown" (code 0). The server guesses from the road type: an untagged trunk or primary road counts as tarmac, an untagged track as murram. Untagged tertiary and unclassified roads stay unknown and count as half murram.
  - Main roads such as Kitale–Eldoret are usually tagged with an asphalt surface. Small roads to schools often aren't, or are not on the map at all (`UNMAPPED_END`).
  - Field reports (`slow_murram`, `good_tarmac`) help fill the gaps. Even better, the team can add `surface=unpaved` or `surface=asphalt` to OSM while mapping; ORS picks it up within a few weeks.
- **A national dataset:** there is a paved/unpaved road dataset for all of Kenya made from satellite images and OSM. It is published in Scientific Data (2024, doi 10.1038/s41597-024-03158-7), with the data on Figshare. It could fill unknown stretches later; its licence would need checking first.
- **Open-Meteo terms:** the free Open-Meteo API is for non-commercial use. A nonprofit's internal use is probably fine, but this is worth confirming. `OPEN_METEO_API_KEY` switches to the paid service if needed.
- **Kiswahili:** the Kiswahili text was written carefully but should be read once by a native speaker on the team, for example "Mashimo barabarani", "Haipitiki wakati wa mvua" and "Murram mbaya, mwendo wa polepole".
