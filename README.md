# Rafiki Outreach website (redesign)

Static site for Rafiki Outreach. Kenya is the default country; visitors can switch to Tanzania, Uganda, Malawi or all countries.

## Pages

The menu follows the owner's eight sections.

| URL | Page | Menu |
| --- | --- | --- |
| `/` | Home | Home |
| `/about` | Mission, values, story and leadership. `/meet` redirects here | About |
| `/#where-we-work` | Country cards on the homepage; each opens its country page | Where we work |
| `/kenya`, `/tanzania`, `/uganda`, `/malawi` | Country pages: team, work, impact, projects, map, stories, needs | Where we work |
| `/projects` | Completed, current and upcoming projects, donors and partners | Our projects |
| `/communities` | Impacted communities (Tanzania, 2023). `/about-1` redirects here | Our projects |
| `/water` | Our approach: why water, the partnership model, the Community Water Agreement | Our approach |
| `/progress` | Our impact in numbers, annual reports | Our impact |
| `/donate` | Get involved: give, sponsor a project, expeditions, volunteer | Get involved |
| `/trips`, `/event-list` | Expeditions and events | Get involved |
| `/projects?…` | Every project, with filters (`project-list.html`, answered at `/projects?cat=…&status=…`, `?project=<id>` or `?view=all` by `functions/projects.js`). Old `/stories?…` list links go here | Projects |
| `/stories` | Stories: the newest approved story up top, then every story with country and kind filters (`?where=ke&topic=water`) | Stories |
| `/stories/<id>-<slug>` | One story: photo hero, the journey (before, each stage, finished), the story with its photos, quote, give and share. `functions/stories/[id].js` fills in the share preview from `pubstory:<id>`; stories appear here when an admin approves them in the app | Stories |
| `/loan` | Loan program (closed; linked from About and the footer) | |

Anything still to be supplied by Rafiki is shown on the page in a yellow "To add" box or tag.

Those boxes and tags only show on previews (localhost, Vercel or Cloudflare preview links). On rafikioutreach.org they are hidden, together with any section that would be empty without them. Add `?live` to a preview link to see the public view, or `?preview` on the live site to see the boxes. Notes that say figures or projects are samples stay visible everywhere until the real data is in. To hide a whole block on the live site, give it the class `draft`.

## Images

Pages use the WebP files in `img/` (photos at most 1200px, logos 120px tall). The original JPG and PNG files are kept for social previews (`og:image`) and as sources. When adding a photo, also save a `.webp` copy and point the page at that.

## Before going live

- **Donations:** paste the Zeffy embed code into `donate.html`. Search for `ZEFFY:` and replace the "Give today" box under it.
- **Numbers:** the figures below are consistent across the site but still need confirming with Rafiki's real data.
  - Totals (`progress.html` data props, `index.html` counters): 300 wells completed, 12 under construction, 780,500+ people, 230,000+ households, 10,000 households a month goal, about 2,600 people per well, $5 per person.
  - Per country (`var CTRY` in every page): Kenya 120 + 6, Tanzania 60 + 2, Uganda 60 + 2, Malawi 60 + 2 wells; people 312,200 / 156,100 / 156,100 / 156,100.
  - The 312 projects on `/projects?view=all` and `/progress` are sample data (`var PROJECTS`), generated in the page script. Replace with real projects.
- **Team:** Uganda and Malawi directors and the Tanzania, Uganda and Malawi office details are hidden until added to `var OFFICES`.
- **Newsletter:** the home page box posts to `/api/subscribe` (`functions/api/subscribe.js`), so visitors never leave the site or see the list provider. Set two Cloudflare secrets: `MAILCHIMP_API_KEY` (Mailchimp → Profile → Extras → API keys; audience id `7e30ec875e` is in the function) and `TURNSTILE_SECRET` (Cloudflare → Turnstile widget). The widget's public site key goes in `var TURNSTILE_SITEKEY` in `index.html`. Until the API key is set, signups wait in the FEED KV store (`sub:<email>`) and are added on the first signup after it is.

## Live project data (monday.com)

The Stories, Impact, country pages and homepage totals can read the **Projects** board in the RAFIKI Outreach workspace on monday.com. Until it is set up, the pages show their sample data.

Setup, in the Cloudflare Pages project:
1. Settings → Variables and Secrets → Add → type **Secret**, name `MONDAY_API_TOKEN`, value: the token from monday.com (profile picture → Developers → My access tokens).
2. Strongly recommended (free): Workers & Pages → KV → Create namespace (e.g. `rafiki-feed`), then in the Pages project Settings → Bindings → Add → KV namespace, variable name `FEED`. It keeps one shared copy of the board and serves the last good copy if monday.com is down.
3. Redeploy (Deployments → Retry deployment). `/api/projects` should now return JSON.

How it works:
- `functions/api/projects.js` returns the public feed; `functions/api/photo/[id].js` passes project photos on (monday.com files need a login). Both use `lib/monday.js` and `lib/feed-cache.js`; `feed.js` loads the feed in the browser.
- The board is read at most every 10 minutes (`FEED_TTL_SECONDS` to change it).
- Only these fields are published: name, country, village/location (no GPS), project type, pump, estimated impact, year, status, photos, and the total cost of projects still looking for funding. Costs of other projects, invoices, people, drilling companies, co-sponsors and notes never leave monday.com. Only photos of published projects can be fetched.
- Statuses: Completed/Operational → Completed; Under Construction → Under construction; Planned and/or Permitted, Approved – Ready to Fund and Recommended to Fund → Looking for funding. Due diligence, failed attempts, needs attention and blank statuses are not shown.
- To choose projects one by one, add a **Checkbox** column titled `Show on website` to the board; once it exists, only ticked projects are published.
- Photos must be uploaded to monday.com as JPG, PNG or WebP. HEIC (iPhone) photos, videos and Google Drive links are skipped.
- "Wells" counts Water (Drilling) projects; "people reached" adds up Estimated Impact of completed water projects, so a community entered as several projects with the same number is counted more than once.
- Column ids are listed at the top of `lib/monday.js`; update them there if columns on the board are replaced.

## Deploy

The site is hosted on **Cloudflare Pages** (free, unlimited traffic).

1. Cloudflare dashboard → Workers & Pages → Create → Pages → Connect to Git → choose `donke01/rafiki-redesign`.
2. Production branch `main`, framework preset None, build command `npm run build:pages`, build output directory `dist`.
3. Every push to `main` redeploys. Other branches get their own preview link.
4. Custom domain: the project → Custom domains → add `rafikioutreach.org` (and `www`). The yellow "To add" boxes only hide on that domain.

`_redirects` holds the redirects, `_headers` the Content-Security-Policy (the page runtime `support.js` needs `'unsafe-eval'`), and `404.html` is served for unknown pages. Only the site files are copied into `dist`, so `server.js`, `package.json` and this README are not published.

Local preview: `npm start` runs `server.js`, a small static server with the same headers, on port 3000. `vercel.json` is kept only in case the site moves to Vercel.
