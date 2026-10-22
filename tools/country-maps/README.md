# Whole-country street maps for RAFIKI Pin

Field teams often have no signal. These map files let RAFIKI Pin show a full street map with no signal anywhere in a
country. There is one file per country (Kenya, Uganda, Tanzania, Malawi). Each phone downloads a country once on
Wi-Fi in **Offline maps › Whole country maps**. After that, the map uses the file whenever there is no signal, or when
someone picks **Offline street map** in Map style. Project pins, routes and your location still show on top.

The maps are made from **OpenStreetMap** data (© OpenStreetMap contributors, ODbL licence). They show:

- villages and towns
- roads, tracks and footpaths
- schools, clinics and hospitals, churches and mosques
- rivers, lakes and water points (wells, boreholes, taps, kiosks, springs)

Buildings and shops are left out to keep the files small.

Satellite photos are **not** in these files. They stay online only, because the Google and Esri terms don't allow
saving them for offline use.

## What's in this folder

| File | What it is |
| --- | --- |
| `rafiki-country.yml` | The map recipe for [Planetiler](https://github.com/onthegomap/planetiler): what goes into the file and from which zoom |
| `build.sh` | Builds the country files and `countries.json` |
| `manifest.mjs` | Writes `countries.json`, the list the apps read (sizes, dates, checksums) |
| `test/make-test-data.py` | Makes a small **made-up** test area around Eldoret, used by the tests (no download needed) |
| `test/test-area.pmtiles` | That test area, built with this recipe (190 KB). Not a real map |

The files are [PMTiles](https://github.com/protomaps/PMTiles): vector map tiles from zoom 0 to 14, in one file.
The apps draw the map from it themselves, and draw closer zooms from the zoom-14 tiles.

## 1. Build the maps (on a laptop, about once a month)

You need:

- **Docker Desktop**, or Java 21 (then put `USE_JAVA=1` before the command)
- Node 18 or newer
- About 8 GB of memory and 10 GB of free disk space
- Internet access to `download.geofabrik.de`, `osmdata.openstreetmap.de` and `ghcr.io` (or `github.com` with Java)

Then run:

```
cd tools/country-maps
./build.sh                  # all four countries
./build.sh kenya            # just one
```

The first run downloads:

- the sea outlines, about 900 MB, once (Kenya and Tanzania have a coast)
- each country from Geofabrik

The results go into `out/`:

- `kenya-2026-10-20.pmtiles` and the other countries (the date is the build date)
- `countries.json`

The script prints each file's size. The `out/` and `data/` folders are not saved in git. Never commit map files.

Rough sizes and times (estimates; the script prints the real sizes):

| Country | Download from Geofabrik | Country map file | Build time (4-core laptop) |
| --- | --- | --- | --- |
| Kenya | about 300–350 MB | about 70–150 MB | 3–8 minutes |
| Uganda | about 250–350 MB | about 70–150 MB | 3–8 minutes |
| Tanzania | about 600–750 MB | about 130–300 MB | 6–15 minutes |
| Malawi | about 120–200 MB | about 40–90 MB | 2–5 minutes |

## 2. Make a place for the files in Cloudflare (once)

The files are too big for Cloudflare Pages, which allows 25 MB per file. So they go in **Cloudflare R2**.

1. In the Cloudflare dashboard, open **R2 Object Storage**. If asked, turn R2 on. The free allowance is 10 GB of
   storage, and downloads from R2 are free.
2. Click **Create bucket**. Name it `rafiki-maps`, leave the location on Automatic, and click **Create bucket**.

## 3. Upload the files

1. Open the `rafiki-maps` bucket and click **Upload**.
2. Upload the new `.pmtiles` files first.
3. Upload `countries.json` **last**. Phones read this list, so it should never point to a file that isn't there yet.

The dashboard takes files up to 300 MB. If a file is bigger, upload it with `rclone`, following Cloudflare's guide
"Use rclone with R2".

When you upload a new month's maps:

- Keep last month's files for about a week, so phones that are still downloading them can finish.
- Then delete them.

## 4. Connect the bucket to the app (choose ONE way)

### Way A (recommended): bind the bucket to the Pages project

With this way, the bucket doesn't need to be public and no CORS rule is needed.

1. Go to **Workers & Pages**, open the Pages project (`rafiki-redesign`), then **Settings › Bindings**.
2. Click **Add**, choose **R2 bucket**, set **Variable name** to `COUNTRY_MAPS`, choose the bucket `rafiki-maps`, and save.
3. Redeploy: go to **Deployments**, open the menu on the latest deployment, and click **Retry deployment**.
4. Check that it works: open `https://rafiki-redesign.pages.dev/api/cmap/countries.json`. It should show the list.

The app then reads the files from `/api/cmap/` on the site's own address.

### Way B: a public bucket with its own address

1. In the bucket, open **Settings › Public access**. Then either:
   - connect a **Custom domain** (the domain must be managed by Cloudflare, e.g. `maps.<domain>`), or
   - allow the **R2.dev subdomain**. Cloudflare limits how much this address can be used, so it is fine for
     testing but not for every day.
2. In the bucket, open **Settings › CORS policy**, click **Add CORS policy**, and paste this:

   ```json
   [
     {
       "AllowedOrigins": ["https://rafiki-redesign.pages.dev", "https://pin.rafikioutreach.org"],
       "AllowedMethods": ["GET", "HEAD"],
       "AllowedHeaders": ["Range", "If-Match"],
       "ExposeHeaders": ["Content-Range", "Content-Length", "ETag", "Accept-Ranges"],
       "MaxAgeSeconds": 86400
     }
   ]
   ```

3. In the Pages project, open **Settings › Variables and Secrets** and click **Add**:
   - Type: **Text**
   - Name: `PIN_COUNTRY_MAPS`
   - Value: the bucket's address ending in `/`, e.g. `https://maps.example.org/`

   Then save and redeploy.

If `PIN_COUNTRY_MAPS` is set, it is used even when the binding also exists.

The app refuses addresses on OpenStreetMap's, Esri's or Google's servers. Bulk downloads from those are not allowed.

**To switch the feature off**, remove the binding or the variable and redeploy. The section and the map style then
disappear. Phones keep maps they have already downloaded until someone deletes them.

## 5. Check on a phone

1. On Wi-Fi, open RAFIKI Pin, then **Map › download button (green) › Saved areas**. Find **Whole country maps**.
   On Android this screen is **Offline maps**.
2. Tap **Download** next to Kenya. You should see:
   - the size of the download
   - progress, with a **Stop** button
   - after a dropped connection, the download carrying on by itself
3. Turn on airplane mode, then open the Map. The street map should still show, with the projects on top and
   "© OpenStreetMap contributors" in the corner.

Wi-Fi only is the default. To download on mobile data too, switch on **Use mobile data too**.

A web browser can also keep a country map. Browsers may clear it when the device runs low on space, so the Android app
is the safer place for it.

## Licence

OpenStreetMap data is © OpenStreetMap contributors and available under the Open Database Licence (ODbL):
https://www.openstreetmap.org/copyright

- The apps show "© OpenStreetMap contributors" on the map while a country map is in use.
- The country files are themselves OpenStreetMap data. If you share them outside RAFIKI, share them under the
  same licence.
