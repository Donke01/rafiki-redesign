# Rafiki Outreach website (redesign)

Static site for Rafiki Outreach. Kenya is the default country; visitors can switch to Tanzania, Uganda, Malawi or all countries.

## Pages

| URL | Page |
| --- | --- |
| `/` | Home |
| `/meet` | Who we are (team) |
| `/projects` | About: where we work, donors and partners |
| `/water` | Why water, and how we drill |
| `/stories` | Impact stories: every project, with filters |
| `/progress` | Our progress in numbers |
| `/communities` | Impacted communities (Tanzania, 2023). `/about-1` redirects here |
| `/trips` | Humanitarian trips |
| `/event-list` | Events |
| `/donate` | Donate |
| `/loan` | Loan program (closed; linked from the footer) |

## Before going live

- **Donations:** paste the Zeffy embed code into `donate.html`. Search for `ZEFFY:` and replace the "Give today" box under it.
- **Numbers:** the figures below are consistent across the site but still need confirming with Rafiki's real data.
  - Totals (`progress.html` data props, `index.html` counters): 300 wells completed, 12 under construction, 780,500+ people, 230,000+ households, 10,000 households a month goal, about 2,600 people per well, $5 per person.
  - Per country (`var CTRY` in every page): Kenya 120 + 6, Tanzania 60 + 2, Uganda 60 + 2, Malawi 60 + 2 wells; people 312,200 / 156,100 / 156,100 / 156,100.
  - The 312 projects on `/stories` and `/progress` are sample data (`var PROJECTS`), generated in the page script. Replace with real projects.
- **Team:** Uganda and Malawi directors and the Tanzania, Uganda and Malawi office details are hidden until added to `var OFFICES`.
- **Newsletter:** the homepage signup opens the visitor's email app. Connect a mailing list service for real signups.

## Deploy

- Vercel: import the repo (framework preset: Other, no build command). `vercel.json` holds clean URLs and redirects.
- Railway or any Node host: `npm start` runs `server.js`, a small static server. It sends a Content-Security-Policy that allows `'unsafe-eval'`, which the page runtime (`support.js`) needs.
